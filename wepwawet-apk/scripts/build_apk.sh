#!/usr/bin/env bash
# build_apk.sh — local build wrapper
#
# 1. Ensures the 5 component artifacts are present (calls fetch_components.sh if not)
# 2. Runs ./gradlew assembleRelease
# 3. Tells you where the APK is

set -euo pipefail

cd "$(dirname "$0")/.."

DEST="app/src/main/assets"
REQUIRED_FILES=(
    "box64-arm64-winlator.wcp"
    "wine-10.10-arm64-prefix.tar.zst"
    "libvulkan_turnip.so"
    "dxvk-1.10.3.tar.zst"
    "vkd3d-2.12.tar.zst"
)

# Check if all artifacts present
NEED_FETCH=0
for f in "${REQUIRED_FILES[@]}"; do
    if [ ! -f "$DEST/$f" ]; then
        echo "Missing: $DEST/$f"
        NEED_FETCH=1
    fi
done

if [ "$NEED_FETCH" -eq 1 ]; then
    echo "Some components missing. Run:"
    echo "  ./scripts/fetch_components.sh"
    echo "and try again."
    exit 1
fi

# Build the APK
if [ ! -f ./gradlew ]; then
    echo "Gradle wrapper missing — generating..."
    gradle wrapper --gradle-version 8.10.2
fi

if [ -n "${WEPWAWET_KEYSTORE_PATH:-}" ]; then
    echo "Building release APK (signed)..."
    ./gradlew :app:assembleRelease --no-daemon --stacktrace
else
    echo "Building debug APK (unsigned)..."
    ./gradlew :app:assembleDebug --no-daemon --stacktrace
fi

# Find and report
APK=$(find app/build/outputs/apk -name '*.apk' | head -1)
if [ -z "$APK" ]; then
    echo "::error::No APK produced"
    exit 1
fi
echo "✓ Built: $APK"
ls -lh "$APK"
