#!/bin/bash

set -e

# ==============================
# Android AAB Offline Installer
# ==============================

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
OUTPUT_DIR="$SCRIPT_DIR/output"
TMP_DIR="$SCRIPT_DIR/tmp"
BUNDLETOOL="/usr/bin/bundletool"

AAB_FILE="$1"

if [ -z "$AAB_FILE" ]; then
    echo "Usage: ./install-aab.sh <file.aab>"
    exit 1
fi

if [ ! -f "$AAB_FILE" ]; then
    echo "AAB file not found: $AAB_FILE"
    exit 1
fi

if [ ! -f "$BUNDLETOOL" ]; then
    echo "bundletool.jar not found in script directory"
    exit 1
fi

mkdir -p "$OUTPUT_DIR"
mkdir -p "$TMP_DIR"

APP_NAME=$(basename "$AAB_FILE" .aab)
APKS_FILE="$TMP_DIR/$APP_NAME.apks"

echo "=================================="
echo " AAB Offline Installer"
echo "=================================="
echo "App: $APP_NAME"
echo ""

echo "Checking device..."

adb devices

echo ""
echo "Building APK set..."

"$BUNDLETOOL" build-apks \
  --bundle="$AAB_FILE" \
  --output="$APKS_FILE" \
  --ks=/home/nerio/Files/essential/keeAndroid.jks \
  --ks-key-alias=key0 \
  --ks-pass=pass:j0jwnfYa3A4RhJH7FIO0dGiR5RoHYaIXAKSV8wfT \
  --key-pass=pass:QzIFJuiHxvhazjqFVxHF00ciYPsDtaoU3JsJ4CMl \
  --connected-device

echo ""
echo "Installing APKs..."

"$BUNDLETOOL" install-apks \
  --apks="$APKS_FILE"

echo ""
echo "Archiving build..."

mv "$APKS_FILE" "$OUTPUT_DIR/"

echo ""
echo "Cleaning temporary files..."

rm -rf "$TMP_DIR"

echo ""
echo "=================================="
echo " Installation complete!"
echo " APKS saved in: $OUTPUT_DIR"
echo "=================================="
