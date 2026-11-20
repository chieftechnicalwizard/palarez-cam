package com.palarez.streamcam;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * RtmpMuxer — Zero-Dependency Pure-Java RTMP &amp; RTMPS (TLS) Live Publisher
 * with AMF0 Server Status Diagnostics &amp; Non-Publishing Dry-Run Handshake Validator.
 *
 * <p>Implements RTMP v3 handshake ({@code C0/C1/C2}), chunk size negotiation ({@code 4096} bytes),
 * AMF0 command framing ({@code connect}, {@code releaseStream}, {@code FCPublish},
 * {@code createStream}, {@code publish}, {@code @setDataFrame("onMetaData")}), Annex-B to AVCC
 * 4-byte length-prefixed NALU conversion, raw AAC framing, 32-bit extended timestamps ({@code >= 0xFFFFFF}
 * for multi-day 24/7 streams), P-frame-first backpressure shedding, and automatic network reconnect
 * backoff ({@code 2s -> 15s}, failing fast on {@code AuthRejectedException}).
 */
public final class RtmpMuxer {
    private static final int CHUNK_SIZE = 4096;
    private static final int CSID_CTRL = 2;
    private static final int CSID_CMD = 3;
    private static final int CSID_AUDIO = 4;
    private static final int CSID_DATA = 5;
    private static final int CSID_VIDEO = 6;

    public interface ErrorListener {
        void onFatalRtmpError(String reason);
    }

    public static final class ValidationResult {
        public boolean ok = false;
        public boolean urlSyntaxOk = false;
        public String dnsResolvedIp = "";
        public int tcpConnectMs = -1;
        public int tlsHandshakeMs = -1;
        public boolean rtmpHandshakeOk = false;
        public boolean amfConnectOk = false;
        public String serverMessage = "";
        public String error = null;
    }

    private static final class AuthRejectedException extends IOException {
        AuthRejectedException(String msg) { super(msg); }
    }

    private static final class FramePacket {
        final int csid;
        final int typeId;
        final int timestampMs;
        final byte[] payload;
        final boolean keyOrHeader;

        FramePacket(int csid, int typeId, int timestampMs, byte[] payload, boolean keyOrHeader) {
            this.csid = csid;
            this.typeId = typeId;
            this.timestampMs = timestampMs;
            this.payload = payload;
            this.keyOrHeader = keyOrHeader;
        }
    }

    private final ArrayBlockingQueue<FramePacket> queue = new ArrayBlockingQueue<>(96);
    private volatile boolean running = false;
    private volatile boolean connected = false;
    public volatile String statusText = "IDLE";
    public volatile long bytesSent = 0;
    public volatile long droppedFrames = 0;

    private Socket socket;
    private OutputStream out;
    private InputStream in;
    private Thread writerThread;
    private final int streamId = 1;
    private ErrorListener errorListener;

    private volatile byte[] avcSeqHeader;
    private volatile byte[] aacSeqHeader;
    private final ByteArrayOutputStream videoTagBuf = new ByteArrayOutputStream(65536);

    public void setErrorListener(ErrorListener l) {
        this.errorListener = l;
    }

    public boolean isConnected() { return connected && running; }

    public synchronized void start(String rtmpUrl, int width, int height, int fps, int vBitrateKbps, int aBitrateKbps, int aSampleRate) {
        stop();
        running = true;
        statusText = "CONNECTING";
        bytesSent = 0;
        droppedFrames = 0;
        queue.clear();

        writerThread = new Thread(() -> runLoop(rtmpUrl, width, height, fps, vBitrateKbps, aBitrateKbps, aSampleRate), "PalaRezRTMP");
        writerThread.start();
    }

    public synchronized void stop() {
        running = false;
        connected = false;
        statusText = "STOPPED";
        if (socket != null) {
            try { socket.close(); } catch (Throwable ignored) {}
            socket = null;
        }
        if (writerThread != null) {
            writerThread.interrupt();
            try { writerThread.join(1500); } catch (InterruptedException ignored) {}
            writerThread = null;
        }
    }

    /**
     * Non-publishing dry-run validator: checks DNS + TCP + TLS + RTMP C0/C1/C2 +
     * AMF0 `connect(app)` WITHOUT sending `FCPublish` or `publish` so YouTube
     * auto-start broadcasts NEVER go live.
     */
    public static ValidationResult validateDryRun(String url, int timeoutMs) {
        ValidationResult res = new ValidationResult();
        if (url == null || !url.matches("^rtmps?://[^/]+/[^/]+/.*$")) {
            res.error = "Invalid RTMP URL (expected rtmp[s]://host/app/key)";
            return res;
        }
        res.urlSyntaxOk = true;

        boolean tls = url.toLowerCase(Locale.US).startsWith("rtmps://");
        String rest = url.replaceFirst("^rtmps?://", "");
        int slash1 = rest.indexOf('/');
        String hostPort = rest.substring(0, slash1);
        String path = rest.substring(slash1 + 1);
        int slash2 = path.indexOf('/');
        String app = path.substring(0, slash2);

        String host = hostPort;
        int port = tls ? 443 : 1935;
        int colon = hostPort.lastIndexOf(':');
        if (colon > 0) {
            host = hostPort.substring(0, colon);
            try { port = Integer.parseInt(hostPort.substring(colon + 1)); } catch (NumberFormatException e) {
                res.error = "Invalid port in URL";
                return res;
            }
        }

        Socket sock = null;
        try {
            InetAddress addr = InetAddress.getByName(host);
            res.dnsResolvedIp = addr.getHostAddress();

            long t0 = System.currentTimeMillis();
            if (tls) {
                Socket tcp = new Socket();
                tcp.connect(new InetSocketAddress(addr, port), timeoutMs);
                res.tcpConnectMs = (int) (System.currentTimeMillis() - t0);
                long t1 = System.currentTimeMillis();
                SSLSocket ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(tcp, host, port, true);
                ssl.setSoTimeout(timeoutMs);
                SSLParameters params = ssl.getSSLParameters();
                params.setEndpointIdentificationAlgorithm("HTTPS");
                ssl.setSSLParameters(params);
                ssl.startHandshake();
                res.tlsHandshakeMs = (int) (System.currentTimeMillis() - t1);
                sock = ssl;
            } else {
                sock = new Socket();
                sock.connect(new InetSocketAddress(addr, port), timeoutMs);
                res.tcpConnectMs = (int) (System.currentTimeMillis() - t0);
            }
            sock.setSoTimeout(timeoutMs);
            OutputStream o = sock.getOutputStream();
            InputStream i = sock.getInputStream();

            o.write(0x03);
            byte[] c1 = new byte[1536];
            new SecureRandom().nextBytes(c1);
            o.write(c1);
            o.flush();

            if (i.read() != 0x03) {
                res.error = "Bad RTMP S0 byte";
                return res;
            }
            byte[] s1 = readFully(i, 1536);
            readFully(i, 1536);
            o.write(s1);
            o.flush();
            res.rtmpHandshakeOk = true;

            String tcUrl = (tls ? "rtmps://" : "rtmp://") + hostPort + "/" + app;
            ByteArrayOutputStream amf = new ByteArrayOutputStream();
            amfStr(amf, "connect");
            amfNum(amf, 1.0);
            amfObjStart(amf);
            amfPropStr(amf, "app", app);
            amfPropStr(amf, "type", "nonprivate");
            amfPropStr(amf, "tcUrl", tcUrl);
            amfPropBool(amf, "fpad", false);
            amfPropNum(amf, "capabilities", 15.0);
            amfPropNum(amf, "audioCodecs", 3575.0);
            amfPropNum(amf, "videoCodecs", 252.0);
            amfPropNum(amf, "videoFunction", 1.0);
            amfObjEnd(amf);
            writeChunkStatic(o, CSID_CMD, 20, 0, 0, amf.toByteArray());

            String reply = readAmfStringsBriefly(sock, i, 700);
            res.serverMessage = reply;
            if (reply.contains("_error") || reply.contains("Rejected")) {
                res.error = "RTMP connect rejected: " + reply;
                return res;
            }
            res.amfConnectOk = true;
            res.ok = true;
        } catch (Throwable t) {
            res.error = t.getClass().getSimpleName() + ": " + t.getMessage();
        } finally {
            if (sock != null) try { sock.close(); } catch (Throwable ignored) {}
        }
        return res;
    }

    private void runLoop(String rtmpUrl, int width, int height, int fps, int vBitrateKbps, int aBitrateKbps, int aSampleRate) {
        int backoffMs = 2000;
        while (running) {
            long sessionStartMs = System.currentTimeMillis();
            try {
                connectAndPublish(rtmpUrl, width, height, fps, vBitrateKbps, aBitrateKbps, aSampleRate);
                connected = true;
                statusText = "STREAMING";
                queue.clear();

                if (avcSeqHeader != null) writeRtmpMessage(CSID_VIDEO, 9, streamId, 0, avcSeqHeader);
                if (aacSeqHeader != null) writeRtmpMessage(CSID_AUDIO, 8, streamId, 0, aacSeqHeader);

                while (running) {
                    FramePacket pkt = queue.poll(500, TimeUnit.MILLISECONDS);
                    if (pkt == null) continue;
                    writeRtmpMessage(pkt.csid, pkt.typeId, streamId, pkt.timestampMs, pkt.payload);
                }
            } catch (AuthRejectedException authErr) {
                connected = false;
                running = false;
                statusText = "AUTH_ERROR: " + authErr.getMessage();
                try { if (socket != null) socket.close(); } catch (Throwable ignored) {}
                if (errorListener != null) errorListener.onFatalRtmpError(statusText);
                break;
            } catch (Throwable t) {
                connected = false;
                if (!running) break;
                if (System.currentTimeMillis() - sessionStartMs > 10000L) {
                    backoffMs = 2000;
                }
                statusText = "RECONNECTING (" + t.getClass().getSimpleName() + ", " + (backoffMs / 1000) + "s)";
                try { if (socket != null) socket.close(); } catch (Throwable ignored) {}
                queue.clear();
                try { Thread.sleep(backoffMs); } catch (InterruptedException ignored) { break; }
                backoffMs = Math.min(15000, backoffMs * 2);
            }
        }
    }

    private void connectAndPublish(String url, int width, int height, int fps, int vBitrateKbps, int aBitrateKbps, int aSampleRate) throws IOException {
        boolean tls = url.toLowerCase(Locale.US).startsWith("rtmps://");
        String rest = url.replaceFirst("^rtmps?://", "");
        int slash1 = rest.indexOf('/');
        if (slash1 < 0) throw new AuthRejectedException("Invalid RTMP URL (expected rtmp[s]://host/app/key)");
        String hostPort = rest.substring(0, slash1);
        String path = rest.substring(slash1 + 1);
        int slash2 = path.indexOf('/');
        if (slash2 < 0 || slash2 + 1 >= path.length()) throw new AuthRejectedException("Missing stream key in RTMP URL");
        String app = path.substring(0, slash2);
        String streamKey = path.substring(slash2 + 1);

        String host = hostPort;
        int port = tls ? 443 : 1935;
        int colon = hostPort.lastIndexOf(':');
        if (colon > 0) {
            host = hostPort.substring(0, colon);
            try {
                port = Integer.parseInt(hostPort.substring(colon + 1));
            } catch (NumberFormatException e) {
                throw new AuthRejectedException("Invalid port in RTMP URL");
            }
        }

        if (tls) {
            Socket tcp = new Socket();
            tcp.connect(new InetSocketAddress(host, port), 10000);
            SSLSocket ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(tcp, host, port, true);
            ssl.setSoTimeout(15000);
            SSLParameters params = ssl.getSSLParameters();
            params.setEndpointIdentificationAlgorithm("HTTPS");
            ssl.setSSLParameters(params);
            ssl.startHandshake();
            socket = ssl;
        } else {
            Socket s = new Socket();
            s.connect(new InetSocketAddress(host, port), 10000);
            socket = s;
        }
        socket.setSoTimeout(15000);
        socket.setTcpNoDelay(true);
        out = socket.getOutputStream();
        in = socket.getInputStream();

        out.write(0x03);
        byte[] c1 = new byte[1536];
        new SecureRandom().nextBytes(c1);
        out.write(c1);
        out.flush();

        int s0 = in.read();
        if (s0 != 0x03) throw new IOException("Bad RTMP S0: " + s0);
        byte[] s1 = readFully(in, 1536);
        readFully(in, 1536);
        out.write(s1);
        out.flush();

        byte[] cs = new byte[] { (CHUNK_SIZE >> 24) & 0xFF, (CHUNK_SIZE >> 16) & 0xFF, (CHUNK_SIZE >> 8) & 0xFF, CHUNK_SIZE & 0xFF };
        writeRtmpMessage(CSID_CTRL, 1, 0, 0, cs);

        String tcUrl = (tls ? "rtmps://" : "rtmp://") + hostPort + "/" + app;
        ByteArrayOutputStream amf = new ByteArrayOutputStream();
        amfStr(amf, "connect");
        amfNum(amf, 1.0);
        amfObjStart(amf);
        amfPropStr(amf, "app", app);
        amfPropStr(amf, "type", "nonprivate");
        amfPropStr(amf, "tcUrl", tcUrl);
        amfPropBool(amf, "fpad", false);
        amfPropNum(amf, "capabilities", 15.0);
        amfPropNum(amf, "audioCodecs", 3575.0);
        amfPropNum(amf, "videoCodecs", 252.0);
        amfPropNum(amf, "videoFunction", 1.0);
        amfObjEnd(amf);
        writeRtmpMessage(CSID_CMD, 20, 0, 0, amf.toByteArray());
        String connReply = readAmfStringsBriefly(socket, in, 350);
        if (connReply.contains("_error") || connReply.contains("Rejected")) {
            throw new AuthRejectedException("Connect rejected: " + connReply);
        }

        sendCmd("releaseStream", 2.0, streamKey);
        sendCmd("FCPublish", 3.0, streamKey);
        sendCmd("createStream", 4.0, null);
        readAmfStringsBriefly(socket, in, 250);

        amf.reset();
        amfStr(amf, "publish");
        amfNum(amf, 5.0);
        amfNull(amf);
        amfStr(amf, streamKey);
        amfStr(amf, "live");
        writeRtmpMessage(CSID_CMD, 20, streamId, 0, amf.toByteArray());
        String pubReply = readAmfStringsBriefly(socket, in, 400);
        if (pubReply.contains("BadName") || pubReply.contains("Rejected") || pubReply.contains("Failed") || pubReply.contains("_error")) {
            throw new AuthRejectedException("Publish rejected (" + pubReply + ")");
        }

        amf.reset();
        amfStr(amf, "@setDataFrame");
        amfStr(amf, "onMetaData");
        amfObjStart(amf);
        amfPropNum(amf, "width", width);
        amfPropNum(amf, "height", height);
        amfPropNum(amf, "framerate", fps);
        amfPropNum(amf, "videodatarate", vBitrateKbps);
        amfPropNum(amf, "videocodecid", 7.0);
        amfPropNum(amf, "audiodatarate", aBitrateKbps);
        amfPropNum(amf, "audiosamplerate", aSampleRate);
        amfPropNum(amf, "audiosamplesize", 16.0);
        amfPropBool(amf, "stereo", false);
        amfPropNum(amf, "audiocodecid", 10.0);
        amfObjEnd(amf);
        writeRtmpMessage(CSID_DATA, 18, streamId, 0, amf.toByteArray());
    }

    private void sendCmd(String name, double txId, String arg) throws IOException {
        ByteArrayOutputStream amf = new ByteArrayOutputStream();
        amfStr(amf, name);
        amfNum(amf, txId);
        amfNull(amf);
        if (arg != null) amfStr(amf, arg);
        writeRtmpMessage(CSID_CMD, 20, 0, 0, amf.toByteArray());
    }

    private static String readAmfStringsBriefly(Socket sock, InputStream in, int maxWaitMs) {
        ByteArrayOutputStream collected = new ByteArrayOutputStream();
        try {
            sock.setSoTimeout(maxWaitMs);
            byte[] buf = new byte[1024];
            long deadline = System.currentTimeMillis() + maxWaitMs;
            while (System.currentTimeMillis() < deadline) {
                int n = in.read(buf);
                if (n <= 0) break;
                collected.write(buf, 0, n);
                String ascii = new String(collected.toByteArray(), StandardCharsets.US_ASCII);
                if (ascii.contains("_result") || ascii.contains("_error") || ascii.contains("NetStream.Publish.")) {
                    break;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            try { sock.setSoTimeout(15000); } catch (Throwable ignored) {}
        }
        byte[] raw = collected.toByteArray();
        // Extract printable runs for crisp server status diagnostics
        StringBuilder sb = new StringBuilder();
        StringBuilder cur = new StringBuilder();
        for (byte b : raw) {
            int c = b & 0xFF;
            if (c >= 32 && c <= 126) cur.append((char) c);
            else {
                if (cur.length() >= 4) {
                    if (sb.length() > 0) sb.append(' ');
                    sb.append(cur);
                }
                cur.setLength(0);
            }
        }
        if (cur.length() >= 4) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(cur);
        }
        String res = sb.toString();
        return res.length() > 140 ? res.substring(0, 140) : res;
    }

    public void setAvcConfig(byte[] sps, byte[] pps) {
        if (sps == null || pps == null || sps.length < 4) return;
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(0x17); b.write(0x00); b.write(0x00); b.write(0x00); b.write(0x00);
        b.write(0x01);
        b.write(sps[1]); b.write(sps[2]); b.write(sps[3]);
        b.write(0xFF);
        b.write(0xE1);
        b.write((sps.length >> 8) & 0xFF);
        b.write(sps.length & 0xFF);
        b.write(sps, 0, sps.length);
        b.write(0x01);
        b.write((pps.length >> 8) & 0xFF);
        b.write(pps.length & 0xFF);
        b.write(pps, 0, pps.length);
        avcSeqHeader = b.toByteArray();
        if (connected) enqueue(new FramePacket(CSID_VIDEO, 9, 0, avcSeqHeader, true));
    }

    public void setAacConfig(byte[] asc) {
        if (asc == null || asc.length < 2) return;
        byte[] tag = new byte[2 + asc.length];
        tag[0] = (byte) 0xAF;
        tag[1] = 0x00;
        System.arraycopy(asc, 0, tag, 2, asc.length);
        aacSeqHeader = tag;
        if (connected) enqueue(new FramePacket(CSID_AUDIO, 8, 0, aacSeqHeader, true));
    }

    /**
     * Converts an H.264 {@code MediaCodec} output buffer from Annex-B start-code format
     * ({@code 00 00 01} / {@code 00 00 00 01}) into an FLV/RTMP AVC VideoTag ({@code 0x17} keyframe
     * or {@code 0x27} interframe, {@code AVC NALU = 0x01}, {@code CTS = 0}) with 4-byte big-endian
     * AVCC NALU lengths. Inline SPS ({@code type 7}) and PPS ({@code type 8}) NALUs are skipped here
     * because they are transmitted out-of-band via {@link #setAvcConfig(byte[], byte[])}.
     */
    public void sendVideoAnnexB(byte[] annexB, int timestampMs, boolean isKey) {
        if (!connected || avcSeqHeader == null || annexB == null || annexB.length == 0) return;
        List<byte[]> nalus = splitAnnexBNalus(annexB);
        if (nalus.isEmpty()) return;

        videoTagBuf.reset();
        videoTagBuf.write(isKey ? 0x17 : 0x27);
        videoTagBuf.write(0x01);
        videoTagBuf.write(0x00); videoTagBuf.write(0x00); videoTagBuf.write(0x00);
        for (byte[] nalu : nalus) {
            int ntype = nalu[0] & 0x1F;
            if (ntype == 7 || ntype == 8) continue;
            int len = nalu.length;
            videoTagBuf.write((len >> 24) & 0xFF);
            videoTagBuf.write((len >> 16) & 0xFF);
            videoTagBuf.write((len >> 8) & 0xFF);
            videoTagBuf.write(len & 0xFF);
            videoTagBuf.write(nalu, 0, len);
        }
        if (videoTagBuf.size() <= 5) return;
        enqueue(new FramePacket(CSID_VIDEO, 9, timestampMs, videoTagBuf.toByteArray(), isKey));
    }

    public void sendAacFrame(byte[] rawAac, int timestampMs) {
        if (!connected || aacSeqHeader == null || rawAac == null || rawAac.length == 0) return;
        byte[] tag = new byte[2 + rawAac.length];
        tag[0] = (byte) 0xAF;
        tag[1] = 0x01;
        System.arraycopy(rawAac, 0, tag, 2, rawAac.length);
        enqueue(new FramePacket(CSID_AUDIO, 8, timestampMs, tag, true));
    }

    /**
     * Enqueues an outbound RTMP packet into the bounded 96-packet ring. Under uplink congestion,
     * non-keyframe inter-frames are dropped first so decoder reference integrity is preserved;
     * keyframes and sequence headers evict the oldest queued packet if full.
     */
    private void enqueue(FramePacket pkt) {
        if (!queue.offer(pkt)) {
            if (!pkt.keyOrHeader) {
                droppedFrames++;
                return;
            }
            queue.poll();
            if (!queue.offer(pkt)) droppedFrames++;
        }
    }

    private synchronized void writeRtmpMessage(int csid, int msgTypeId, int msid, int tsMs, byte[] body) throws IOException {
        writeChunkStatic(out, csid, msgTypeId, msid, tsMs, body);
        bytesSent += body.length;
    }

    /**
     * Fragments an RTMP message into {@link #CHUNK_SIZE} ({@code 4096}-byte) chunks using a Type-0
     * 12-byte header on the first chunk (with little-endian message stream ID per RTMP spec) and
     * Type-3 1-byte continuation headers on subsequent chunks, emitting 4-byte big-endian extended
     * timestamps whenever {@code tsMs >= 0xFFFFFF} (~4h 39m) for uninterrupted 24/7 broadcasts.
     */
    private static void writeChunkStatic(OutputStream o, int csid, int msgTypeId, int msid, int tsMs, byte[] body) throws IOException {
        int len = body.length;
        int offset = 0;
        boolean first = true;
        while (offset < len || first) {
            int chunkLen = Math.min(CHUNK_SIZE, len - offset);
            if (first) {
                o.write(csid & 0x3F);
                int tsHeader = tsMs >= 0xFFFFFF ? 0xFFFFFF : tsMs;
                o.write((tsHeader >> 16) & 0xFF);
                o.write((tsHeader >> 8) & 0xFF);
                o.write(tsHeader & 0xFF);
                o.write((len >> 16) & 0xFF);
                o.write((len >> 8) & 0xFF);
                o.write(len & 0xFF);
                o.write(msgTypeId & 0xFF);
                o.write(msid & 0xFF);
                o.write((msid >> 8) & 0xFF);
                o.write((msid >> 16) & 0xFF);
                o.write((msid >> 24) & 0xFF);
                if (tsMs >= 0xFFFFFF) writeU32BE(o, tsMs);
                first = false;
            } else {
                o.write(0xC0 | (csid & 0x3F));
                if (tsMs >= 0xFFFFFF) writeU32BE(o, tsMs);
            }
            if (chunkLen > 0) {
                o.write(body, offset, chunkLen);
                offset += chunkLen;
            }
            if (offset >= len) break;
        }
        o.flush();
    }

    public static List<byte[]> splitAnnexBNalus(byte[] data) {
        List<byte[]> out = new ArrayList<>();
        int i = 0;
        int n = data.length;
        while (i < n) {
            while (i < n && data[i] == 0) i++;
            if (i < n && data[i] == 1 && ((i >= 2 && data[i - 1] == 0 && data[i - 2] == 0))) {
                i++;
                int start = i;
                while (i < n) {
                    if (i + 2 < n && data[i] == 0 && data[i + 1] == 0 && (data[i + 2] == 1 || (i + 3 < n && data[i + 2] == 0 && data[i + 3] == 1))) {
                        break;
                    }
                    i++;
                }
                if (i > start) {
                    byte[] nalu = new byte[i - start];
                    System.arraycopy(data, start, nalu, 0, nalu.length);
                    out.add(nalu);
                }
            } else {
                i++;
            }
        }
        return out;
    }

    private static byte[] readFully(InputStream in, int len) throws IOException {
        byte[] b = new byte[len];
        int off = 0;
        while (off < len) {
            int r = in.read(b, off, len - off);
            if (r < 0) throw new IOException("EOF in RTMP stream");
            off += r;
        }
        return b;
    }

    private static void writeU32BE(OutputStream o, int v) throws IOException {
        o.write((v >> 24) & 0xFF); o.write((v >> 16) & 0xFF); o.write((v >> 8) & 0xFF); o.write(v & 0xFF);
    }

    private static void amfStr(ByteArrayOutputStream o, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        o.write(0x02); o.write((b.length >> 8) & 0xFF); o.write(b.length & 0xFF); o.write(b, 0, b.length);
    }

    private static void amfNum(ByteArrayOutputStream o, double v) {
        long bits = Double.doubleToRawLongBits(v);
        o.write(0x00);
        for (int i = 7; i >= 0; i--) o.write((int) ((bits >> (i * 8)) & 0xFF));
    }

    private static void amfNull(ByteArrayOutputStream o) { o.write(0x05); }
    private static void amfObjStart(ByteArrayOutputStream o) { o.write(0x03); }
    private static void amfObjEnd(ByteArrayOutputStream o) { o.write(0x00); o.write(0x00); o.write(0x09); }

    private static void amfPropStr(ByteArrayOutputStream o, String k, String v) {
        byte[] kb = k.getBytes(StandardCharsets.UTF_8);
        o.write((kb.length >> 8) & 0xFF); o.write(kb.length & 0xFF); o.write(kb, 0, kb.length);
        amfStr(o, v);
    }

    private static void amfPropNum(ByteArrayOutputStream o, String k, double v) {
        byte[] kb = k.getBytes(StandardCharsets.UTF_8);
        o.write((kb.length >> 8) & 0xFF); o.write(kb.length & 0xFF); o.write(kb, 0, kb.length);
        amfNum(o, v);
    }

    private static void amfPropBool(ByteArrayOutputStream o, String k, boolean v) {
        byte[] kb = k.getBytes(StandardCharsets.UTF_8);
        o.write((kb.length >> 8) & 0xFF); o.write(kb.length & 0xFF); o.write(kb, 0, kb.length);
        o.write(0x01); o.write(v ? 1 : 0);
    }
}
