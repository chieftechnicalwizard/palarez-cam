#!/usr/bin/env bash
# ==============================================================================
# build_apk.sh — Builds & signs PalaRez StreamCam (output/palarez-cam.apk)
#
# Zero Gradle, Maven, or internet build dependencies — compiles Java 11 sources
# and vector XML resources directly against Android SDK 35 (aapt2 -> javac ->
# d8 --min-api 28 -> zipalign -> apksigner).
#
# Environment Variables (all optional):
#   ANDROID_HOME / ANDROID_SDK_ROOT  Path to Android SDK root (auto-detected on Linux/macOS)
#   PALAREZ_KEYSTORE_DIR             Directory for gitignored signing keystore (default: ./keystore)
#   PALAREZ_KEYSTORE_PASS            Explicit keystore password (otherwise auto-generated in .storepass)
#   PALAREZ_APK_PATH                 Output APK file path (default: ./output/palarez-cam.apk)
# ==============================================================================
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"

SDK_DIR=""
for candidate in \
    "${ANDROID_HOME:-}" \
    "${ANDROID_SDK_ROOT:-}" \
    "$HOME/.local/share/android-sdk" \
    "$HOME/Android/Sdk" \
    "$HOME/Library/Android/sdk" \
    "/opt/android-sdk"; do
    if [ -n "$candidate" ] && [ -f "$candidate/platforms/android-35/android.jar" ] && [ -x "$candidate/build-tools/35.0.0/apksigner" ]; then
        SDK_DIR="$candidate"
        break
    fi
done

if [ -z "$SDK_DIR" ]; then
    echo "Android SDK Platform 35 + Build-Tools 35.0.0 not found." >&2
    echo "Set ANDROID_HOME or ANDROID_SDK_ROOT, or install via:" >&2
    echo "  sdkmanager \"platforms;android-35\" \"build-tools;35.0.0\"" >&2
    exit 1
fi

PLATFORM_JAR="$SDK_DIR/platforms/android-35/android.jar"
BT_DIR="$SDK_DIR/build-tools/35.0.0"

BUILD_DIR="$ROOT_DIR/android_app/build"
SRC_DIR="$ROOT_DIR/android_app/src"
RES_DIR="$ROOT_DIR/android_app/res"
MANIFEST="$ROOT_DIR/android_app/AndroidManifest.xml"
KEYSTORE_DIR="${PALAREZ_KEYSTORE_DIR:-$ROOT_DIR/keystore}"
KEYSTORE="$KEYSTORE_DIR/palarez-cam.jks"
PASS_FILE="$KEYSTORE_DIR/.storepass"
APK_OUT="${PALAREZ_APK_PATH:-$ROOT_DIR/output/palarez-cam.apk}"

mkdir -p "$BUILD_DIR/obj" "$BUILD_DIR/dex" "$BUILD_DIR/gen" "$KEYSTORE_DIR" "$(dirname "$APK_OUT")"

if [ -n "${PALAREZ_KEYSTORE_PASS:-}" ]; then
    KS_PASS="$PALAREZ_KEYSTORE_PASS"
elif [ -f "$PASS_FILE" ]; then
    KS_PASS="$(cat "$PASS_FILE")"
else
    KS_PASS="$(python3 -c 'import secrets; print(secrets.token_urlsafe(18))')"
    printf '%s' "$KS_PASS" > "$PASS_FILE"
    chmod 600 "$PASS_FILE"
fi

if [ ! -f "$KEYSTORE" ]; then
    echo "[*] Generating private signing keystore in $KEYSTORE_DIR..."
    keytool -genkeypair -noprompt \
        -keystore "$KEYSTORE" \
        -alias palarez-cam \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass "$KS_PASS" -keypass "$KS_PASS" \
        -dname "CN=PalaRezStreamCam, OU=GardenTelemetry, O=Local, L=Local, ST=NA, C=US"
fi

rm -rf "$BUILD_DIR/obj" "$BUILD_DIR/dex" "$BUILD_DIR/gen"
mkdir -p "$BUILD_DIR/obj" "$BUILD_DIR/dex" "$BUILD_DIR/gen"

"$BT_DIR/aapt2" compile --dir "$RES_DIR" -o "$BUILD_DIR/res.zip"
"$BT_DIR/aapt2" link \
    -o "$BUILD_DIR/unaligned.apk" \
    --manifest "$MANIFEST" \
    -I "$PLATFORM_JAR" \
    --java "$BUILD_DIR/gen" \
    "$BUILD_DIR/res.zip"

JAVA_SOURCES=()
while IFS= read -r -d '' f; do
    JAVA_SOURCES+=("$f")
done < <(find "$SRC_DIR" "$BUILD_DIR/gen" -name "*.java" -print0)
javac --release 11 -classpath "$PLATFORM_JAR" -d "$BUILD_DIR/obj" "${JAVA_SOURCES[@]}"

CLASS_FILES=()
while IFS= read -r -d '' f; do
    CLASS_FILES+=("$f")
done < <(find "$BUILD_DIR/obj" -name "*.class" -print0)
"$BT_DIR/d8" --min-api 28 --lib "$PLATFORM_JAR" --output "$BUILD_DIR/dex" "${CLASS_FILES[@]}"

cp "$BUILD_DIR/unaligned.apk" "$BUILD_DIR/with_dex.apk"
(cd "$BUILD_DIR/dex" && zip -q "$BUILD_DIR/with_dex.apk" classes.dex)

"$BT_DIR/zipalign" -f 4 "$BUILD_DIR/with_dex.apk" "$BUILD_DIR/aligned.apk"
"$BT_DIR/apksigner" sign \
    --ks "$KEYSTORE" \
    --ks-key-alias palarez-cam \
    --ks-pass "pass:$KS_PASS" \
    --key-pass "pass:$KS_PASS" \
    --out "$APK_OUT" \
    "$BUILD_DIR/aligned.apk"

"$BT_DIR/apksigner" verify --print-certs "$APK_OUT" >/dev/null
echo "[+] Built & signed: $APK_OUT"
