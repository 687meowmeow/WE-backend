#!/usr/bin/env bash
# re_toolkit.sh — Backward-compatible shim that dispatches to the new
# cross-platform Python toolkit at scripts/toolkit/toolkit.py.
#
# The original bash-only toolkit has been replaced by a pure-Python toolkit
# so it works on Windows (where bash is not available). All sub-commands
# are now implemented in scripts/toolkit/*.py.
#
# Usage:
#   ./re_toolkit.sh <command> [args...]
#
# Commands (forwarded to toolkit.py):
#   analyze      - Analyze a .mpkg file
#   pack         - Pack a wallpaper project into .mpkg
#   server       - Run a standalone WE pairing server (for testing)
#   broadcast    - Broadcast WallpaperEngine/Ping on UDP 7884
#   capture      - Print Wireshark/tshark capture commands
#   decompile    - Download + decompile the WE Android APK with jadx
#   search       - Search decompiled WE Android source
#   keypair      - Generate RSA-2048 keypair
#   frida        - Print a Frida hooks JS script
#   help         - Show this help
#
# To run the toolkit directly:
#   python3 scripts/toolkit/toolkit.py help
# Or use the OS-specific launchers:
#   Windows: scripts\toolkit\run.bat help
#   Unix:    scripts/toolkit/run.sh help

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TOOLKIT="$SCRIPT_DIR/toolkit/toolkit.py"

# Pick a Python interpreter
PYTHON="${PYTHON:-}"
if [ -z "$PYTHON" ]; then
    if command -v python3 >/dev/null 2>&1; then
        PYTHON=python3
    elif command -v python >/dev/null 2>&1; then
        PYTHON=python
    else
        echo "[error] Python 3.10+ not found on PATH."
        echo "        Install Python or set PYTHON env var."
        exit 3
    fi
fi

if [ ! -f "$TOOLKIT" ]; then
    echo "[error] Toolkit entry point not found: $TOOLKIT"
    exit 1
fi

exec "$PYTHON" "$TOOLKIT" "$@"
