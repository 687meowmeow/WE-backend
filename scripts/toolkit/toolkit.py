#!/usr/bin/env python3
"""
WepWawet RE Toolkit - Cross-Platform Reverse Engineering Toolkit for the
Wallpaper Engine Android app and the .mpkg wallpaper format.

This is the main entry point. All tools are standalone Python scripts
(located next to this file) so they can be run on Windows, macOS, and Linux
without needing bash. Use `run.bat` on Windows or `./run.sh` on Unix to
invoke this script.

Usage:
    python toolkit.py <command> [args...]

Commands:
    analyze      - Analyze a .mpkg file: detect PKGM magic, VFS, manifest,
                   embedded JSON, embedded video/image, and produce a hex dump.
    pack         - Pack a wallpaper project (project.json + assets) into a
                   .mpkg file (best-effort based on RE'd format).
    server       - Run a WE PC-side pairing server on this machine (for testing
                   the official WE Android app against your own server).
    broadcast    - Broadcast WallpaperEngine/Ping UDP packets on port 7884 so
                   the official WE Android app discovers this machine.
    capture      - Print cross-platform Wireshark/tshark capture commands for
                   the WE pairing protocol (UDP 7884 + TCP 7889).
    decompile    - Download the official WE Android APK and decompile it with
                   jadx (requires jadx on PATH).
    search       - Search a decompiled WE Android source tree for pairing,
                   crypto, and .mpkg references.
    keypair      - Generate an RSA-2048 keypair (PEM) for use with `server`.
    frida        - Print a Frida hooks JS script for runtime analysis of the
                   WE Android app. Save the output and load with frida -U.
    help         - Show this help.

Environment:
    PYTHON       - override python interpreter used by .bat launchers
                   (default: 'python' on Windows, 'python3' elsewhere)

Exit codes:
    0  success
    1  generic failure
    2  bad arguments
    3  missing dependency (jadx, tshark, pycryptodome, etc.)
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
    """Return the python interpreter to use for sub-scripts."""
    return os.environ.get("PYTHON") or (sys.executable or "python3")


def _run_subcommand(script: str, extra_args: list[str]) -> int:
    """Run a sibling Python script with the same interpreter, forwarding args."""
    script_path = TOOLKIT_DIR / script
    if not script_path.exists():
        print(f"error: sub-script not found: {script_path}", file=sys.stderr)
        return 1
    cmd = [_python(), str(script_path)] + extra_args
    try:
        return subprocess.call(cmd)
    except FileNotFoundError as e:
        print(f"error: failed to launch {script}: {e}", file=sys.stderr)
        print("hint: set PYTHON env var to a working python interpreter",
              file=sys.stderr)
        return 3


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="toolkit",
        description="WepWawet RE Toolkit - cross-platform reverse engineering "
                    "toolkit for the Wallpaper Engine Android app and .mpkg format.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument(
        "command",
        choices=list(SCRIPTS.keys()) + ["help"],
        help="Subcommand to run",
    )
    parser.add_argument(
        "args",
        nargs=argparse.REMAINDER,
        help="Arguments forwarded to the subcommand",
    )
    args = parser.parse_args(argv)

    if args.command == "help" or args.command is None:
        parser.print_help()
        print("\nAvailable subcommands and their per-tool --help:")
        for name, script in SCRIPTS.items():
            print(f"  {name:11s}  ->  python {script} --help")
        return 0

    script = SCRIPTS[args.command]
    # Strip a leading "--" inserted by REMAINDER
    forwarded = list(args.args)
    if forwarded and forwarded[0] == "--":
        forwarded = forwarded[1:]
    return _run_subcommand(script, forwarded)


if __name__ == "__main__":
    sys.exit(main())
