#!/usr/bin/env bash
# re_toolkit.sh — Complete RE toolkit for WE Android app
#
# Usage:
#   ./re_toolkit.sh decompile   — decompile WE Android APK with jadx
#   ./re_toolkit.sh analyze      — search decompiled source for protocol/crypto/mpkg
#   ./re_toolkit.sh capture      — generate Wireshark capture script for pairing protocol
#   ./re_toolkit.sh mpkg         — analyze .mpkg format (if a sample is available)
#   ./re_toolkit.sh frida        — generate Frida hooks script for runtime analysis
#   ./re_toolkit.sh all          — do all of the above

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
RESEARCH_DIR="${PROJECT_ROOT}/research"
WE_APK="/tmp/we-android.apk"
JADX="${PROJECT_ROOT}/tools/jadx/bin/jadx"

decompile() {
    echo "=== Decompiling WE Android APK ==="
    if [ ! -f "$WE_APK" ]; then
        echo "→ Downloading WE Android APK..."
        curl -fsSL "https://www.wallpaperengine.io/android/apk/wallpaper-engine.apk" -o "$WE_APK"
    fi
    mkdir -p "$RESEARCH_DIR/we-android-decompiled"
    "$JADX" -d "$RESEARCH_DIR/we-android-decompiled" "$WE_APK" 2>&1 | tail -5
    echo "✓ Decompiled to $RESEARCH_DIR/we-android-decompiled/"
}

analyze() {
    local SRC="$RESEARCH_DIR/we-android-decompiled/sources"
    echo "=== Searching for pairing protocol ==="
    grep -rl -i 'pair\|7884\|7889\|WallpaperEngine.*Ping' "$SRC" --include='*.java' 2>/dev/null | \
        grep -v 'androidx\|com\.google\|okhttp\|retrofit\|kotlin\|material' | sort
    echo ""
    echo "=== Searching for crypto ==="
    grep -rl -i 'bcrypt\|AES\|cipher\|encrypt\|decrypt\|RSA\|SecretKey' "$SRC" --include='*.java' 2>/dev/null | \
        grep -v 'androidx\|com\.google\|okhttp\|retrofit\|kotlin\|material' | sort
    echo ""
    echo "=== Searching for .mpkg format ==="
    grep -rl -i 'mpkg' "$SRC" --include='*.java' 2>/dev/null | sort
    echo ""
    echo "=== Key WE package files ==="
    find "$SRC" -path '*/wallpaperengine/*' -name '*.java' | sort
}

capture() {
    local CAPTURE_SCRIPT="$RESEARCH_DIR/wireshark_capture.sh"
    cat > "$CAPTURE_SCRIPT" << 'CAPTURE_EOF'
#!/usr/bin/env bash
# Wireshark capture script for WE pairing protocol
# Captures UDP 7884 (discovery) + TCP 7889 (pairing/transfer)
# Usage: sudo ./wireshark_capture.sh [interface]
IFACE="${1:wlan0}"
DURATION="${2:60}"
echo "Capturing WE pairing protocol on $IFACE for $DURATION seconds..."
echo "Filter: udp.port == 7884 or tcp.port == 7889"
tshark -i "$IFACE" -a duration:"$DURATION" \
    -f "udp port 7884 or tcp port 7889" \
    -w "we_pairing_$(date +%Y%m%d_%H%M%S).pcapng"
echo "Capture saved. Analyze with:"
echo "  wireshark we_pairing_*.pcapng"
echo "  tshark -r we_pairing_*.pcapng -Y 'udp.port == 7884' -V  # discovery packets"
echo "  tshark -r we_pairing_*.pcapng -Y 'tcp.port == 7889' -V  # pairing/transfer"
echo ""
echo "Look for:"
echo "  UDP 7884: 'WallpaperEngine/Ping:<pid>:<hostname>:<flag>:<ifaceId>'"
echo "  TCP 7889: RSA public key exchange (large BigInteger values)"
echo "  TCP 7889: AES-encrypted JSON (EncAuthRequest/EncAuthResponse)"
echo "  TCP 7889: File transfer (.mpkg data)"
CAPTURE_EOF
    chmod +x "$CAPTURE_SCRIPT"
    echo "✓ Wireshark capture script written to $CAPTURE_SCRIPT"
}

mpkg_analyze() {
    local MPKG_ANALYZER="$RESEARCH_DIR/mpkg_analyzer.py"
    cat > "$MPKG_ANALYZER" << 'PYEOF'
#!/usr/bin/env python3
"""Analyze .mpkg file format.
Usage: python3 mpkg_analyzer.py <file.mpkg>
"""
import sys, struct, json, os

def analyze_mpkg(path):
    with open(path, 'rb') as f:
        data = f.read()
    print(f"File: {path}")
    print(f"Size: {len(data)} bytes ({len(data)/1024/1024:.1f} MB)")
    print(f"Magic (first 16 bytes hex): {data[:16].hex()}")
    print(f"Magic (first 16 bytes ASCII): {data[:16]}")
    
    # Check if it's a ZIP
    if data[:2] == b'PK':
        print("→ Format: ZIP container")
        import zipfile
        with zipfile.ZipFile(path) as z:
            for info in z.infolist():
                print(f"  {info.filename}: {info.file_size} bytes")
            # Try reading manifest
            for name in ['manifest.json', 'project.json', 'info.json', 'metadata.json']:
                if name in z.namelist():
                    with z.open(name) as mf:
                        try:
                            manifest = json.loads(mf.read())
                            print(f"\n  {name}:")
                            print(json.dumps(manifest, indent=2))
                        except:
                            print(f"\n  {name}: (not JSON)")
    
    # Check for known magic bytes
    magics = {
        b'PKGV': 'WE .pkg format (PKGV magic)',
        b'TEXB': 'WE .tex format (TEXB magic)',
        b'PK': 'ZIP archive',
        b'\x89PNG': 'PNG image',
        b'\x1a\x45\xdf\xa3': 'Matroska/MKV video',
        b'\xff\xd8\xff': 'JPEG image',
        b'RIFF': 'RIFF (AVI/WAV)',
        b'\x00\x00\x00': 'MP4/M4V video (ftyp box)',
    }
    for magic, desc in magics.items():
        if data.startswith(magic):
            print(f"→ Detected: {desc}")
    
    # Search for JSON-like content
    for i in range(min(len(data), 4096)):
        if data[i:i+1] == b'{':
            end = data.find(b'}', i)
            if end != -1 and end - i < 4096:
                try:
                    snippet = data[i:end+1].decode('utf-8')
                    parsed = json.loads(snippet)
                    print(f"\nJSON found at offset {i}:")
                    print(json.dumps(parsed, indent=2))
                except:
                    pass
    
    # Look for null-terminated strings
    print(f"\nString scan (first 256 bytes):")
    current = b''
    for b in data[:256]:
        if 32 <= b <= 126:
            current += bytes([b])
        else:
            if len(current) > 3:
                print(f"  @{data.index(current)}: {current.decode('ascii')}")
            current = b''
    
    print(f"\nFull hex dump of first 128 bytes:")
    for i in range(0, min(128, len(data)), 16):
        hex_part = ' '.join(f'{b:02x}' for b in data[i:i+16])
        ascii_part = ''.join(chr(b) if 32 <= b <= 126 else '.' for b in data[i:i+16])
        print(f"  {i:04x}: {hex_part:<48} {ascii_part}")

if __name__ == '__main__':
    if len(sys.argv) < 2:
        print("Usage: python3 mpkg_analyzer.py <file.mpkg>")
        sys.exit(1)
    analyze_mpkg(sys.argv[1])
PYEOF
    chmod +x "$MPKG_ANALYZER"
    echo "✓ .mpkg analyzer written to $MPKG_ANALYZER"
    echo "  Usage: python3 $MPKG_ANALYZER <file.mpkg>"
    echo "  To get a sample .mpkg: pair your phone with a real PC running WE,"
    echo "  then look in /storage/emulated/0/Android/data/io.wallpaperengine.weclient/files/"
}

frida_hooks() {
    local FRIDA_SCRIPT="$RESEARCH_DIR/frida_hooks.js"
    cat > "$FRIDA_SCRIPT" << 'JSEOF'
// Frida hooks for WE Android app runtime analysis
// Usage: frida -U -f io.wallpaperengine.weclient -l frida_hooks.js

Java.perform(function() {
    // Hook TcpClient — the pairing protocol
    var TcpClient = Java.use('io.wallpaperengine.weutil.TcpClient');
    
    // Hook performAuthentication
    TcpClient.performAuthentication.implementation = function(pin, name, version, callback) {
        console.log('[TcpClient] performAuthentication:');
        console.log('  PIN: ' + pin);
        console.log('  DeviceName: ' + name);
        console.log('  AppVersion: ' + version);
        return this.performAuthentication(pin, name, version, callback);
    };
    
    // Hook connect
    TcpClient.connect.implementation = function() {
        console.log('[TcpClient] connect() called');
        return this.connect();
    };
    
    // Hook EncAuthRequest constructor
    var EncAuthRequest = Java.use('io.wallpaperengine.weutil.TcpClient$EncAuthRequest');
    EncAuthRequest.$init.implementation = function() {
        this.$init();
        console.log('[EncAuthRequest] created — version=' + this.getVersion() + 
                    ' mpkgVersion=' + this.getMpkgVersion());
    };
    
    // Hook DiscoverService — UDP discovery
    var DiscoverService = Java.use('io.wallpaperengine.weclient.DiscoverService');
    DiscoverService.beginDiscovery.implementation = function() {
        console.log('[DiscoverService] beginDiscovery() — starting UDP 7884 multicast listen');
        return this.beginDiscovery();
    };
    
    // Hook TransferService — file transfer
    var TransferService = Java.use('io.wallpaperengine.weclient.TransferService');
    
    // Hook all crypto operations
    var Cipher = Java.use('javax.crypto.Cipher');
    Cipher.init.implementation = function(mode, key, iv) {
        var modeStr = mode == 1 ? 'ENCRYPT' : mode == 2 ? 'DECRYPT' : 'mode=' + mode;
        console.log('[Cipher] init(' + modeStr + ', key=' + key + ', iv=' + iv + ')');
        return this.init(mode, key, iv);
    };
    
    // Hook RSA key handling
    var RSAPublicKeyHelper = Java.use('io.wallpaperengine.wedata.RSAPublicKeyHelper');
    RSAPublicKeyHelper.setModulus.implementation = function(bi) {
        console.log('[RSA] setModulus: ' + bi.toString(16).substring(0, 32) + '...');
        return this.setModulus(bi);
    };
    RSAPublicKeyHelper.setExponent.implementation = function(bi) {
        console.log('[RSA] setExponent: ' + bi.toString(16));
        return this.setExponent(bi);
    };
    
    console.log('[*] Frida hooks installed for WE Android app');
    console.log('[*] Monitoring: TcpClient, DiscoverService, TransferService, Cipher, RSA');
});
JSEOF
    echo "✓ Frida hooks script written to $FRIDA_SCRIPT"
    echo "  Usage: frida -U -f io.wallpaperengine.weclient -l $FRIDA_SCRIPT"
}

case "${1:-all}" in
    decompile) decompile ;;
    analyze) analyze ;;
    capture) capture ;;
    mpkg) mpkg_analyze ;;
    frida) frida_hooks ;;
    all)
        decompile
        analyze
        capture
        mpkg_analyze
        frida_hooks
        echo ""
        echo "=== RE toolkit complete ==="
        echo "All tools in $RESEARCH_DIR/"
        ;;
    *)
        echo "Usage: $0 {decompile|analyze|capture|mpkg|frida|all}"
        exit 1
        ;;
esac
