#!/usr/bin/env bash
# === WepWawet RE Toolkit - Unix launcher ===
# See run.bat for full usage docs.
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
