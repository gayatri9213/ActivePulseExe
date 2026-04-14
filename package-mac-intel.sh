#!/bin/bash

APP_NAME="ActivePulse-x64"   # ✅ Only change (important)
APP_VERSION="1.0.0"
MAIN_CLASS="com.activepulse.agent.ActivePulseApplication"
JAR_NAME="active-pulse-0.0.1-SNAPSHOT.jar"
JAR_FILE="target/$JAR_NAME"
BUILD_DIR="/tmp/ap-build"
INPUT_DIR="$BUILD_DIR/input"
OUT_DIR="target/setup"
BUNDLE_ID="com.aress.activepulse"

echo "Building macOS Intel (x64)..."

[ ! -f "$JAR_FILE" ] && echo "[ERROR] JAR not found" && exit 1
command -v jpackage &>/dev/null || { echo "[ERROR] jpackage not found"; exit 1; }

rm -rf "$BUILD_DIR" && mkdir -p "$INPUT_DIR" "$OUT_DIR"
cp "$JAR_FILE" "$INPUT_DIR/$JAR_NAME"

ICON_ARG=""
if [ -f "src/main/resources/tray-icon.png" ]; then
    mkdir -p "$BUILD_DIR/icon.iconset"
    for size in 16 32 64 128 256 512; do
        sips -z $size $size "src/main/resources/tray-icon.png" \
             --out "$BUILD_DIR/icon.iconset/icon_${size}x${size}.png" 2>/dev/null
    done
    iconutil -c icns "$BUILD_DIR/icon.iconset" -o "$BUILD_DIR/tray-icon.icns"
    ICON_ARG="--icon $BUILD_DIR/tray-icon.icns"
fi

jpackage \
  --type dmg \
  --name "$APP_NAME" \
  --app-version "$APP_VERSION" \
  --input "$INPUT_DIR" \
  --main-jar "$JAR_NAME" \
  --main-class "$MAIN_CLASS" \
  --dest "$OUT_DIR" \
  --mac-package-identifier "$BUNDLE_ID" \
  $ICON_ARG

echo "✅ Intel build done"
