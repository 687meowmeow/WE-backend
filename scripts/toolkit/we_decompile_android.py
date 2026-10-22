#!/usr/bin/env python3
"""
we_decompile_android.py — Download and decompile the official Wallpaper Engine
Android APK with jadx, so you can browse the source code that implements
the pairing protocol, .mpkg loader, and SceneLib JNI methods.

WHAT IT DOES
============
1. (Optional) Download the official WE Android APK from
   https://www.wallpaperengine.io/android/apk/wallpaper-engine.apk
   to ./we-android.apk
2. Check that jadx is installed and on PATH (or under ./tools/jadx/).
3. Run jadx -d ./we-android-decompiled ./we-android.apk
4. Print a summary of the decompiled source tree.

USAGE
=====
    python we_decompile_android.py                    # download + decompile
    python we_decompile_android.py --apk ./local.apk # use a local APK
    python we_decompile_android.py --skip-download    # skip the curl fetch
    python we_decompile_android.py --jadx ./path/to/jadx
    python we_decompile_android.py --help

DEPENDENCIES
============
- jadx (https://github.com/skylot/jadx) — Java decompiler for Android.
  Download the release ZIP, extract to ./tools/jadx/, or install via
  `brew install jadx` (macOS) / `choco install jadx` (Windows).
- curl (already installed on most systems).

EXIT CODES
==========
    0  success
    1  fatal error (download failed, jadx missing, decompile failed)
    2  bad arguments
    3  missing jadx
"""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
import urllib.request
from pathlib import Path

DEFAULT_APK_URL = "https://www.wallpaperengine.io/android/apk/wallpaper-engine.apk"


def find_jadx(override: str | None = None) -> Path | None:
    """Locate the jadx binary."""
    if override:
        p = Path(override)
        if p.is_file() and os.access(p, os.X_OK):
            return p
        if (p / "bin" / "jadx").is_file():
            return p / "bin" / "jadx"
        if (p / "bin" / "jadx.bat").is_file():
            return p / "bin" / "jadx.bat"
        return None
    # Try PATH
    for name in ("jadx", "jadx.bat"):
        path = shutil.which(name)
        if path:
            return Path(path)
    # Try ./tools/jadx/
    local = Path(__file__).resolve().parent.parent.parent / "tools" / "jadx" / "bin" / "jadx"
    if local.is_file():
        return local
    return None


def download_apk(url: str, dest: Path) -> bool:
    print(f"Downloading WE Android APK from {url} ...")
    try:
        with urllib.request.urlopen(url, timeout=60) as resp:
            data = resp.read()
            dest.write_bytes(data)
        print(f"Saved {len(data):,} bytes ({len(data) / 1024 / 1024:.1f} MB) to {dest}")
        return True
    except Exception as e:
        print(f"Download failed: {e}", file=sys.stderr)
        return False


def run_jadx(jadx: Path, apk: Path, out: Path) -> bool:
    print(f"Decompiling {apk} with jadx -> {out}")
    cmd = [str(jadx), "-d", str(out), str(apk)]
    print(f"Running: {' '.join(cmd)}")
    try:
        proc = subprocess.run(cmd, timeout=600, capture_output=True, text=True)
        if proc.returncode != 0:
            print(f"jadx returned exit code {proc.returncode}", file=sys.stderr)
            if proc.stderr:
                print(proc.stderr[-2000:], file=sys.stderr)
            # jadx often returns non-zero even on success; check output dir
        if out.exists() and any(out.iterdir()):
            print(f"Decompiled source tree: {out}")
            return True
        return False
    except FileNotFoundError:
        print(f"jadx not found at {jadx}", file=sys.stderr)
        return False
    except subprocess.TimeoutExpired:
        print("jadx timed out after 10 minutes", file=sys.stderr)
        return False


def summarize(out: Path) -> int:
    """Print a summary of the decompiled source tree."""
    print()
    print("=" * 72)
    print(f"Decompiled WE Android source tree: {out}")
    print("=" * 72)
    if not out.exists():
        print("(not present)")
        return 1
    sources = out / "sources"
    if not sources.exists():
        print(f"(no sources/ directory)")
        return 1
    we_dir = sources / "io" / "wallpaperengine"
    if we_dir.exists():
        for sub in sorted(we_dir.iterdir()):
            file_count = sum(1 for _ in sub.rglob("*.java")) if sub.is_dir() else 0
            print(f"  io/wallpaperengine/{sub.name:15s}  {file_count} Java files")
    else:
        print("(no io/wallpaperengine/ directory)")
    print()
    print("Key files to inspect:")
    key_files = [
        "io/wallpaperengine/weutil/TcpClient.java",
        "io/wallpaperengine/weutil/SupportFileLoader.java",
        "io/wallpaperengine/weutil/RangedInputStream.java",
        "io/wallpaperengine/weutil/VirtualFileRange.java",
        "io/wallpaperengine/weutil/WallpaperInfoSparse.java",
        "io/wallpaperengine/weutil/LocalWallpaperSparse.java",
        "io/wallpaperengine/weutil/Util.java",
        "io/wallpaperengine/wedata/RSAPublicKeyHelper.java",
        "io/wallpaperengine/wedata/PairedServer.java",
        "io/wallpaperengine/wedata/DownloadInfo.java",
        "io/wallpaperengine/wrapper/SceneLib.java",
        "io/wallpaperengine/weclient/DiscoverService.java",
        "io/wallpaperengine/weclient/TransferService.java",
        "io/wallpaperengine/weclient/PairingActivity.java",
    ]
    for kf in key_files:
        p = sources / kf
        marker = "OK" if p.exists() else "MISSING"
        print(f"  [{marker}] {kf}")
    print()
    print("Next: run `python we_analyze_source.py {out}` to search for "
          "pairing/crypto/.mpkg references.")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="we_decompile_android",
        description="Download and decompile the WE Android APK with jadx.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument("--apk", type=Path, default=Path("we-android.apk"),
                       help=f"APK path (default: we-android.apk)")
    parser.add_argument("--out", type=Path, default=Path("we-android-decompiled"),
                       help="Output directory (default: we-android-decompiled)")
    parser.add_argument("--url", default=DEFAULT_APK_URL,
                       help=f"APK download URL (default: {DEFAULT_APK_URL})")
    parser.add_argument("--jadx", default=None,
                       help="Path to jadx binary or its install dir.")
    parser.add_argument("--skip-download", action="store_true",
                       help="Skip the APK download (use existing --apk file)")
    args = parser.parse_args(argv)

    jadx = find_jadx(args.jadx)
    if jadx is None:
        print("error: jadx not found.", file=sys.stderr)
        print("       Install from https://github.com/skylot/jadx/releases", file=sys.stderr)
        print("       Or set --jadx <path>", file=sys.stderr)
        return 3

    if not args.skip_download and not args.apk.exists():
        if not download_apk(args.url, args.apk):
            return 1
    if not args.apk.exists():
        print(f"error: APK not found: {args.apk}", file=sys.stderr)
        print("       Pass --apk <path> or remove --skip-download", file=sys.stderr)
        return 1

    if not run_jadx(jadx, args.apk, args.out):
        return 1

    return summarize(args.out)


if __name__ == "__main__":
    sys.exit(main())
