# ==============================================================================
# Dockerfile — Self-Contained APK Builder & Optional LAN Companion Hub (:8895)
#
# Compiles `palarez-cam.apk` from source on first container boot (storing the
# auto-generated signing key in `/var/lib/palarez/keystore` so upgrades keep the
# same signature) and serves QR/browser APK downloads, multi-phone LAN discovery
# (`GET /api/phones`), and JSONL + JPEG archival on port 8895.
# ==============================================================================
FROM ubuntu:24.04
ARG DEBIAN_FRONTEND=noninteractive

RUN apt-get -o Acquire::ForceIPv4=true update -qq && apt-get -o Acquire::ForceIPv4=true install -y --no-install-recommends \
        ca-certificates curl openjdk-21-jdk-headless python3 qrencode unzip zip \
    && rm -rf /var/lib/apt/lists/*

ENV ANDROID_HOME=/opt/android-sdk
ENV PATH="${ANDROID_HOME}/platform-tools:${PATH}"
RUN mkdir -p "${ANDROID_HOME}/cmdline-tools" \
    && curl -fsSL "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip" -o /tmp/cmd.zip \
    && unzip -q /tmp/cmd.zip -d "${ANDROID_HOME}/cmdline-tools" \
    && mv "${ANDROID_HOME}/cmdline-tools/cmdline-tools" "${ANDROID_HOME}/cmdline-tools/latest" \
    && rm -f /tmp/cmd.zip \
    && yes | "${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager" --sdk_root="${ANDROID_HOME}" \
        "platforms;android-35" "build-tools;35.0.0" >/dev/null \
    && rm -rf "${ANDROID_HOME}/cmdline-tools"

WORKDIR /app
COPY android_app/ /app/android_app/
COPY companion/ /app/companion/
COPY build_apk.sh /app/build_apk.sh
RUN chmod +x /app/build_apk.sh /app/companion/server.py

ENV PALAREZ_KEYSTORE_DIR=/var/lib/palarez/keystore \
    PALAREZ_DATA_DIR=/var/lib/palarez/data \
    PALAREZ_APK_PATH=/var/lib/palarez/data/palarez-cam.apk \
    PALAREZ_PORT=8895

EXPOSE 8895
CMD ["/bin/bash", "-c", "mkdir -p /var/lib/palarez/keystore /var/lib/palarez/data && [ -f \"$PALAREZ_APK_PATH\" ] || /app/build_apk.sh && exec python3 -u /app/companion/server.py"]
