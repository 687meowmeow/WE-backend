#!/usr/bin/env bash
# === WepWawet RE Toolkit - Unix launcher ===
#
# Usage:
#   ./run.sh <command> [args...]
#
# Commands:
#   analyze     Analyze a .mpkg file
#   pack        Pack a wallpaper project into .mpkg
#   server      Run a WE pairing server (for testing)
#   broadcast   Broadcast WallpaperEngine/Ping on UDP 7884
#   capture     Print Wireshark/tshark capture commands
#   decompile   Download + decompile the WE Android APK with jadx
#   search      Search decompiled WE Android source
#   keypair     Generate RSA-2048 keypair
#   frida       Print a Frida hooks JS script
#   help        Show this help
#
# If you don't have python3 on PATH, set PYTHON env var first:
#   PYTHON=/opt/python3.12/bin/python ./run.sh analyze file.mpkg

set -euo pipefail

PYTHON="${PYTHON:-}"
if [ -z "$PYTHON" ]; then
    if command -v python3 >/dev/null 2>&1; then
        PYTHON=python3
    elif command -v python >/dev/null 2>&1; then
        PYTHON=python
    else
        echo "[error] Python not found on PATH. Install Python 3.10+."
        echo "        Or set PYTHON env var to your python3 binary path."
        exit 3
    fi
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
exec "$PYTHON" "$SCRIPT_DIR/toolkit.py" "$@"
