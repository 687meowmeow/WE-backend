#!/usr/bin/env bash
# === Install Python dependencies for the WepWawet RE Toolkit (Unix) ===
set -euo pipefail

PYTHON="${PYTHON:-}"
if [ -z "$PYTHON" ]; then
    if command -v python3 >/dev/null 2>&1; then
        PYTHON=python3
    elif command -v python >/dev/null 2>&1; then
        PYTHON=python
    else
        echo "[error] Python not found on PATH."
        exit 3
    fi
fi

echo "Installing Python dependencies for the WepWawet RE Toolkit ..."
$PYTHON -m pip install --upgrade pip
$PYTHON -m pip install -r "$(dirname "$0")/requirements.txt"
echo
echo "Done. You can now run:"
echo "  ./run.sh help"
