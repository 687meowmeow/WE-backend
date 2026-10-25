#!/usr/bin/env python3
"""
we_analyze_source.py — Search a decompiled WE Android source tree for the
pairing protocol, crypto operations, and .mpkg file format references.

USAGE
=====
    python we_analyze_source.py <decompiled_root>
    python we_analyze_source.py <decompiled_root> --keyword mpkg
    python we_analyze_source.py --help

OUTPUT
======
A categorized report of every Java file in io.wallpaperengine.* that
references the pairing protocol, crypto operations, or .mpkg format.
For each file, prints the file path and the matching line snippets.

EXIT CODES
==========
    0  success
    1  source root not found
    2  bad arguments
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

# Categories of patterns to search for
CATEGORIES: list[tuple[str, list[re.Pattern[str]]]] = [
    ("Pairing protocol",
     [re.compile(r"\b(pair|pairing|PairingActivity|performAuth)", re.I)]),
    ("TCP pairing (7889)",
     [re.compile(r"\b(7889|TcpClient|EncAuth|connector\$lambda)", re.I)]),
    ("UDP discovery (7884)",
     [re.compile(r"\b(7884|DiscoverService|WallpaperEngine/Ping|MulticastSocket)",
                 re.I)]),
    ("RSA crypto",
     [re.compile(r"\b(RSA|RSAPublicKey|RSAPublicKeyHelper|BigInteger|RSA/ECB)",
                 re.I)]),
    ("AES crypto",
     [re.compile(r"\b(AES|AES/CBC|PKCS5Padding|SecretKeySpec|IvParameterSpec|"
                 r"KeyGenerator)", re.I)]),
    (".mpkg format",
     [re.compile(r"\b(mpkg|MPKG|MPKG_SUPPORT_VERSION|PKGM|mpkgVersion|"
                 r"VirtualFileRange|SupportFileLoader|RangedInputStream)", re.I)]),
    ("SceneLib (native)",
     [re.compile(r"\b(SceneLib|libscenejni|getWallpaperType|"
                 r"getWallpaperFileVirtualOffset|getWallpaperInfoSparse)",
                 re.I)]),
    ("Transfer service",
     [re.compile(r"\b(TransferService|DownloadInfo|tempWriteFile|"
                 r"tempTransmissionInfo|pendingDownloads)", re.I)]),
    ("Steam / Workshop",
     [re.compile(r"\b(ISteamRemoteStorage|GetPublishedFileDetails|"
                 r"workshop_download_item|publishedfileid|431960)", re.I)]),
    ("Heartbeat",
     [re.compile(r"heartbeat", re.I)]),
]


def find_we_files(root: Path) -> list[Path]:
    """Return every Java file under io/wallpaperengine/."""
    base = root / "sources" / "io" / "wallpaperengine"
    if not base.exists():
        return []
    return sorted(p for p in base.rglob("*.java"))


def analyze_file(path: Path) -> dict[str, list[tuple[int, str]]]:
    """Search the file for each category of pattern. Returns matches."""
    matches: dict[str, list[tuple[int, str]]] = {name: [] for name, _ in CATEGORIES}
    try:
        text = path.read_text(encoding="utf-8", errors="replace")
    except Exception as e:
        print(f"  error reading {path}: {e}", file=sys.stderr)
        return matches
    lines = text.splitlines()
    for name, patterns in CATEGORIES:
        for i, line in enumerate(lines, 1):
            for pat in patterns:
                if pat.search(line):
                    matches[name].append((i, line.strip()[:160]))
                    break
    return matches


def analyze_root(root: Path, keyword: str | None = None) -> int:
    if not root.exists():
        print(f"error: root not found: {root}", file=sys.stderr)
        return 1
    print(f"=== WE Android Source Analyzer ===")
    print(f"Root:  {root}")
    files = find_we_files(root)
    print(f"Files: {len(files)} Java files under io/wallpaperengine/")
    print()

    if keyword:
        print(f"Keyword search: {keyword!r}")
        pat = re.compile(re.escape(keyword), re.I)
        for f in files:
            try:
                text = f.read_text(encoding="utf-8", errors="replace")
            except Exception:
                continue
            for i, line in enumerate(text.splitlines(), 1):
                if pat.search(line):
                    rel = f.relative_to(root)
                    print(f"  {rel}:{i}: {line.strip()[:200]}")
        return 0

    by_category: dict[str, list[tuple[Path, int, str]]] = {
        name: [] for name, _ in CATEGORIES
    }
    for f in files:
        result = analyze_file(f)
        for cat, hits in result.items():
            for line_no, snippet in hits[:5]:
                by_category[cat].append((f, line_no, snippet))

    for cat, _ in CATEGORIES:
        hits = by_category[cat]
        print(f"--- {cat} ({len(hits)} matches across "
              f"{len(set(h[0] for h in hits))} files) ---")
        for path, line_no, snippet in hits[:25]:
            rel = path.relative_to(root)
            print(f"  {rel}:{line_no}: {snippet}")
        if len(hits) > 25:
            print(f"  ... and {len(hits) - 25} more")
        print()
    print("=== Analysis complete ===")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="we_analyze_source",
        description="Search decompiled WE Android source for pairing/crypto/mpkg.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument("root", type=Path,
                       help="Decompiled source root (contains sources/io/wallpaperengine/)")
    parser.add_argument("--keyword", default=None,
                       help="If set, do a free-form keyword search instead of "
                            "category-based analysis.")
    args = parser.parse_args(argv)
    return analyze_root(args.root, args.keyword)


if __name__ == "__main__":
    sys.exit(main())
