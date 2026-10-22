#!/usr/bin/env python3
"""WepWawet RE Toolkit - Cross-Platform Reverse Engineering Toolkit.

All tools are standalone Python scripts so they run on Windows without bash.
Use `run.bat` on Windows or `./run.sh` on Unix to invoke this script.

Commands:
    analyze      - Analyze a .mpkg file (REAL format from verified sample)
    pack         - Pack a wallpaper project into a .mpkg file
    server       - Run a WE PC-side pairing server (REAL protocol from capture)
    broadcast    - Broadcast WallpaperEngine/Ping on UDP 7884 (VERIFIED format)
    capture      - Print Wireshark/tshark capture commands
    decompile    - Download + decompile the WE Android APK with jadx
    search       - Search decompiled WE Android source for pairing/crypto/mpkg
    keypair      - Generate RSA-1024 keypair (matches real protocol)
    frida        - Print a Frida hooks JS script
    help         - Show this help

Environment:
    PYTHON       - override python interpreter used by .bat launchers
                  (default: 'python' on Windows, 'python3' elsewhere)
"""
from __future__ import annotations
import argparse
import os
import subprocess
import sys
from pathlib import Path

TOOLKIT_DIR = Path(__file__).resolve().parent
SCRIPTS = {
    "analyze":   "mpkg_analyzer.py",
    "pack":      "mpkg_packer.py",
    "server":    "we_pairing_server.py",
    "broadcast": "we_discovery_broadcaster.py",
    "capture":   "we_protocol_capture.py",
    "decompile": "we_decompile_android.py",
    "search":    "we_analyze_source.py",
    "keypair":   "generate_keypair.py",
    "frida":     "generate_frida_hooks.py",
}


def _python() -> str:
    return os.environ.get("PYTHON") or (sys.executable or "python3")


def _run_subcommand(script: str, extra_args: list[str]) -> int:
    script_path = TOOLKIT_DIR / script
    if not script_path.exists():
        print(f"error: sub-script not found: {script_path}", file=sys.stderr)
        return 1
    cmd = [_python(), str(script_path)] + extra_args
    try:
        return subprocess.call(cmd)
    except FileNotFoundError as e:
        print(f"error: failed to launch {script}: {e}", file=sys.stderr)
        print("hint: set PYTHON env var to a working python interpreter", file=sys.stderr)
        return 3


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="toolkit",
        description="WepWawet RE Toolkit - cross-platform reverse engineering toolkit.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument("command", choices=list(SCRIPTS.keys()) + ["help"],
                        help="Subcommand to run")
    parser.add_argument("args", nargs=argparse.REMAINDER,
                        help="Arguments forwarded to the subcommand")
    args = parser.parse_args(argv)

    if args.command == "help":
        parser.print_help()
        print("\nAvailable subcommands and their per-tool --help:")
        for name, script in SCRIPTS.items():
            print(f"  {name:11s}  ->  python {script} --help")
        return 0

    script = SCRIPTS[args.command]
    forwarded = list(args.args)
    if forwarded and forwarded[0] == "--":
        forwarded = forwarded[1:]
    return _run_subcommand(script, forwarded)


if __name__ == "__main__":
    sys.exit(main())
