#!/usr/bin/env python3
"""we_decompile_android.py — Download + decompile the official WE Android APK."""
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
    if override:
        p = Path(override)
        if p.is_file() and os.access(p, os.X_OK):
            return p
        if (p / "bin" / "jadx").is_file():
            return p / "bin" / "jadx"
        if (p / "bin" / "jadx.bat").is_file():
            return p / "bin" / "jadx.bat"
        return None
    for name in ("jadx", "jadx.bat"):
        path = shutil.which(name)
        if path:
            return Path(path)
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
    except FileNotFoundError:
        print(f"jadx not found at {jadx}", file=sys.stderr)
        return False
    except subprocess.TimeoutExpired:
        print("jadx timed out after 10 minutes", file=sys.stderr)
        return False
    if out.exists() and any(out.iterdir()):
        print(f"Decompiled source tree: {out}")
        return True
    return False


def summarize(out: Path) -> int:
    print()
    print("=" * 72)
    print(f"Decompiled WE Android source tree: {out}")
    print("=" * 72)
    if not out.exists():
        print("(not present)")
        return 1
    sources = out / "sources"
    if not sources.exists():
        print("(no sources/ directory)")
        return 1
    we_dir = sources / "io" / "wallpaperengine"
    if we_dir.exists():
        for sub in sorted(we_dir.iterdir()):
            file_count = sum(1 for _ in sub.rglob("*.java")) if sub.is_dir() else 0
            print(f"  io/wallpaperengine/{sub.name:15s}  {file_count} Java files")
    print()
    print("Key files to inspect:")
    key_files = [
        "io/wallpaperengine/weutil/TcpClient.java",
        "io/wallpaperengine/weutil/SupportFileLoader.java",
        "io/wallpaperengine/weutil/RangedInputStream.java",
        "io/wallpaperengine/weutil/VirtualFileRange.java",
        "io/wallpaperengine/weutil/WallpaperInfoSparse.java",
        "io/wallpaperengine/weutil/Util.java",
        "io/wallpaperengine/wedata/RSAPublicKeyHelper.java",
        "io/wallpaperengine/wrapper/SceneLib.java",
        "io/wallpaperengine/weclient/DiscoverService.java",
        "io/wallpaperengine/weclient/TransferService.java",
    ]
    for kf in key_files:
        p = sources / kf
        marker = "OK" if p.exists() else "MISSING"
        print(f"  [{marker}] {kf}")
    print()
    print("Next: run `python we_analyze_source.py {out}` to search for "
          "pairing/crypto/.mpkg references.")
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(
        prog="we_decompile_android",
        description="Download + decompile the WE Android APK with jadx.",
    )
    parser.add_argument("--apk", type=Path, default=Path("we-android.apk"))
    parser.add_argument("--out", type=Path, default=Path("we-android-decompiled"))
    parser.add_argument("--url", default=DEFAULT_APK_URL)
    parser.add_argument("--jadx", default=None)
    parser.add_argument("--skip-download", action="store_true")
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
        return 1

    if not run_jadx(jadx, args.apk, args.out):
        return 1

    return summarize(args.out)


if __name__ == "__main__":
    sys.exit(main())
