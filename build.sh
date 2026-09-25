#!/bin/bash
# desktop-fly-android — dependency-free APK build (no gradle, no androidx).
# Uses aapt2 + javac + d8 + zipalign + apksigner from the local Android SDK.
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_SDK:-/Volumes/外置磁盘1/android-sdk}"
BT="$SDK/build-tools/35.0.0"
PLATFORM="$SDK/platforms/android-34/android.jar"
KEYSTORE="$HOME/.android/desktop-fly-mvp.keystore"
OUT=build
APK_NAME=DesktopFly-MVP.apk

rm -rf "$OUT/classes" "$OUT/dex" "$OUT/apk"
mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/apk"

echo "[1/6] aapt2 compile + link resources"
"$BT/aapt2" compile --dir res -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/apk/base.apk" \
    -I "$PLATFORM" \
    --manifest AndroidManifest.xml \
    -A assets \
    --min-sdk-version 24 --target-sdk-version 34 \
    --version-code 1 --version-name 0.1.0 \
    "$OUT/res.zip"

echo "[2/6] javac (src only; test/ stays a JVM suite)"
find src -name '*.java' > "$OUT/sources.txt"
javac --release 8 -classpath "$PLATFORM" -d "$OUT/classes" @"$OUT/sources.txt" -Xlint:-options

echo "[3/6] d8 dex"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$BT/d8" --release --lib "$PLATFORM" --min-api 24 \
    --output "$OUT/dex" @"$OUT/classes.txt"

echo "[4/6] package dex into apk"
cd "$OUT/apk"
cp base.apk unsigned.apk
cp ../dex/classes.dex .
zip -q -j unsigned.apk classes.dex
cd ../..

echo "[5/6] zipalign"
"$BT/zipalign" -f 4 "$OUT/apk/unsigned.apk" "$OUT/apk/aligned.apk"

echo "[6/6] sign"
if [ ! -f "$KEYSTORE" ]; then
    mkdir -p "$(dirname "$KEYSTORE")"
    keytool -genkeypair -keystore "$KEYSTORE" -alias fly \
        -keyalg RSA -keysize 2048 -validity 10950 \
        -storepass flyflyfly -keypass flyflyfly \
        -dname "CN=DesktopFly MVP, OU=fly, O=maltjuice" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:flyflyfly \
    --out "$APK_NAME" "$OUT/apk/aligned.apk"
"$BT/apksigner" verify "$APK_NAME" && echo "verify: OK"

ls -lh "$APK_NAME"
echo "done: $(pwd)/$APK_NAME"
