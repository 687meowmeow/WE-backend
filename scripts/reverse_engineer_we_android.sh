#!/usr/bin/env bash
# reverse_engineer_we_android.sh — decompile the official WE Android APK
#
# This is the FIRST RECOMMENDED STEP for the next phase of development.
# Per research Task 1-C, the WE Android APK is freely downloadable at:
#   https://www.wallpaperengine.io/android/apk/wallpaper-engine.apk
#
# This script:
#   1. Downloads the APK
#   2. Decomiles it with jadx (Java sources) and apktool (resources + smali)
#   3. Greps for: pairing protocol, .mpkg parsing, crypto primitives, network ports
#   4. Writes a summary to re_findings.md
#
# Requires: jadx, apktool, curl, grep
# Install on Debian: sudo apt install jadx apktool curl

set -euo pipefail

WORKDIR="${WORKDIR:-/tmp/we-re}"
APK_URL="https://www.wallpaperengine.io/android/apk/wallpaper-engine.apk"
APK_FILE="$WORKDIR/we.apk"
JADX_OUT="$WORKDIR/jadx"
APKTOOL_OUT="$WORKDIR/apktool"
FINDINGS="$WORKDIR/re_findings.md"

mkdir -p "$WORKDIR"

if [ ! -f "$APK_FILE" ]; then
    echo "Downloading WE Android APK..."
    curl -fsSL "$APK_URL" -o "$APK_FILE"
fi
ls -lh "$APK_FILE"

if ! command -v jadx >/dev/null 2>&1; then
    echo "::error::jadx not installed. Install: https://github.com/skylot/jadx"
    exit 1
fi
if ! command -v apktool >/dev/null 2>&1; then
    echo "::error::apktool not installed. Install: https://apktool.org/"
    exit 1
fi

echo "::group::jadx decompile"
jadx -d "$JADX_OUT" "$APK_FILE" || true
echo "::endgroup::"

echo "::group::apktool decode"
apktool d -f -o "$APKTOOL_OUT" "$APK_FILE" || true
echo "::endgroup::"

{
    echo "# WE Android APK — RE Findings"
    echo
    echo "Decompiled from \`$APK_URL\` on $(date -u +'%Y-%m-%dT%H:%M:%SZ')"
    echo
    echo "## APK metadata"
    aapt dump badging "$APK_FILE" 2>/dev/null | head -20 || true
    echo
    echo "## Key findings"
    echo
    echo "### Files mentioning pairing / pairing protocol"
    grep -r "pair" "$JADX_OUT/sources" --include="*.java" -l 2>/dev/null | head -30 || true
    echo
    echo "### Files mentioning 7884 (UDP discovery port)"
    grep -r "7884" "$JADX_OUT/sources" --include="*.java" -l 2>/dev/null | head -30 || true
    echo
    echo "### Files mentioning 7889 (TCP control port)"
    grep -r "7889" "$JADX_OUT/sources" --include="*.java" -l 2>/dev/null | head -30 || true
    echo
    echo "### Files mentioning WallpaperEngine/Ping (UDP discovery payload)"
    grep -r "WallpaperEngine" "$JADX_OUT/sources" --include="*.java" -l 2>/dev/null | head -30 || true
    echo
    echo "### Files mentioning .mpkg (mobile package format)"
    grep -r "mpkg" "$JADX_OUT/sources" --include="*.java" -l 2>/dev/null | head -30 || true
    echo
    echo "### Files mentioning bcrypt (the Wine-blocker primitive)"
    grep -ri "bcrypt" "$JADX_OUT/sources" --include="*.java" -l 2>/dev/null | head -30 || true
    echo
    echo "### Files mentioning PBKDF2 / AES / GCM / ChaCha (PIN-derived crypto candidates)"
    grep -rEi "PBKDF2|AESGCM|ChaCha|Cipher\\.getInstance" "$JADX_OUT/sources" --include="*.java" -l 2>/dev/null | head -30 || true
    echo
    echo "### Files mentioning 239.100.0.1 (multicast group)"
    grep -r "239.100.0.1" "$JADX_OUT/sources" --include="*.java" -l 2>/dev/null | head -30 || true
    echo
    echo "### Native libraries (.so files in APK)"
    find "$APKTOOL_OUT/lib" -type f -name '*.so' 2>/dev/null | sort
    echo
    echo "## Next steps"
    echo
    echo "1. Open \`$JADX_OUT/sources/io/wallpaperengine/weclient/\` and look for:"
    echo "   - A class with name like \`PairingService\`, \`DiscoveryClient\`, \`PcConnection\`"
    echo "   - The 4-digit PIN entry and where it's used to derive a key"
    echo "   - The .mpkg file receiver and how it's parsed"
    echo
    echo "2. Use Wireshark to capture a real pairing session between a real PC running WE"
    echo "   and your phone running the official WE Android app:"
    echo "   - Filter: udp.port == 7884 or tcp.port == 7889"
    echo "   - Save the capture to \`we_pairing.pcapng\`"
    echo "   - Compare against what jadx reveals"
    echo
    echo "3. Once you know the wire format, update:"
    echo "   - app/src/main/java/com/wepwawet/proxy/WeControlServer.kt"
    echo "   - app/src/main/java/com/wepwawet/proxy/PairingCrypto.kt"
    echo "   - app/src/main/java/com/wepwawet/proxy/MpkgTransporter.kt"
} > "$FINDINGS"

echo "✓ Decompiled and grepped. Findings written to: $FINDINGS"
echo
echo "Next: open $JADX_OUT in IntelliJ and search for pairing protocol classes."
