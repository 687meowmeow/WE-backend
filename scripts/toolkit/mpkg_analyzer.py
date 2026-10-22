#!/usr/bin/env python3
"""
mpkg_analyzer.py — Analyze the Wallpaper Engine Android .mpkg file format.

WHAT IS .mpkg?
==============
The WE Android app (package: io.wallpaperengine.weclient) loads wallpapers
from `.mpkg` files. The format is a custom container with magic "PKGM" at the
start (verified from `TransferService.MPKG_SUPPORT_VERSION = "PKGM0020"` in
the decompiled source). The native library `libscenejni.so` parses it.

The .mpkg container implements a "Virtual File System" (VFS): multiple
virtual files (project.json, preview image, scene assets, video) are laid
out at specific byte offsets inside the .mpkg file. The Java side calls
`SceneLib.getWallpaperFileVirtualOffset(filePath)` which returns a
`VirtualFileRange(vOffset, vSize)` describing where each virtual file lives.

For "Video" wallpapers, `Util.getMPKGVideoReader(reader, offsets)` returns
a `MediaDataSource` that reads at `position + offsets.getVOffset()` for
`offsets.getVSize()` bytes — confirming the VFS layout.

WHAT THIS SCRIPT DOES
=====================
Given a .mpkg file (or any binary), this script will:
1. Print the file size and first 128 bytes (hex + ASCII).
2. Detect the magic ("PKGM" prefix in `PKGM0020`).
3. Try to parse the file as a ZIP (some .mpkg variants are ZIPs).
4. Scan the first 16 KiB for embedded JSON objects (project.json, manifest).
5. Scan for known file magics: ZIP (PK), PNG, JPEG, Matroska, RIFF, MP4, TEXB.
6. Print all printable strings > 4 chars in the first 4 KiB.
7. If "PKGM" magic is present, try to parse the VFS table:
   - Look for repeated (offset, size) pairs after the header
   - Match virtual files to embedded asset magics.

USAGE
=====
    python mpkg_analyzer.py <file.mpkg> [--full]
    python mpkg_analyzer.py --help

To get a sample .mpkg file:
1. Pair a real phone with a real PC running Wallpaper Engine.
2. Transfer any wallpaper to the phone.
3. The .mpkg files are saved at:
   /storage/emulated/0/Android/data/io.wallpaperengine.weclient/files/
4. Pull via adb:  adb pull /sdcard/Android/data/io.wallpaperengine.weclient/files .
"""

from __future__ import annotations

import argparse
import binascii
import json
import struct
import sys
from pathlib import Path

# Known magic bytes for files that may appear embedded in a .mpkg
KNOWN_MAGICS: list[tuple[bytes, str]] = [
    (b"PKGM",       "WE .mpkg container (PKGM magic — TransferService.MPKG_SUPPORT_VERSION)"),
    (b"PKGM0020",   "WE .mpkg container, version PKGM0020 (current WE Android)"),
    (b"PK",         "ZIP archive (PK\\x03\\x04)"),
    (b"\x89PNG",     "PNG image"),
    (b"\xff\xd8\xff", "JPEG image"),
    (b"\x1a\x45\xdf\xa3", "Matroska/MKV/WebM video"),
    (b"RIFF",       "RIFF (AVI/WAV/WebP)"),
    (b"\x00\x00\x00", "MP4/M4V (ftyp box, may be 0 size prefix)"),
    (b"TEXB",       "WE .tex texture format"),
    (b"PKGS",       "Possible .pkg (PC-side) variant"),
    (b"\xfd7zXZ",   "XZ-compressed data"),
    (b"\x28\xb5\x2f\xfd", "Zstandard-compressed data"),
    (b"\x1f\x8b",   "GZIP-compressed data"),
]


def hex_dump(data: bytes, offset: int = 0, length: int = 128,
             width: int = 16) -> str:
    """Render `length` bytes of `data` starting at `offset` as a hex+ASCII dump."""
    out = []
    end = min(offset + length, len(data))
    for i in range(offset, end, width):
        chunk = data[i:i + width]
        hex_part = " ".join(f"{b:02x}" for b in chunk)
        ascii_part = "".join(chr(b) if 32 <= b <= 126 else "." for b in chunk)
        out.append(f"  {i:08x}: {hex_part:<{width * 3}}  {ascii_part}")
    return "\n".join(out)


def find_json_objects(data: bytes, scan_limit: int = 16384) -> list[dict]:
    """Find well-formed JSON objects in the first `scan_limit` bytes."""
    results = []
    i = 0
    limit = min(len(data), scan_limit)
    while i < limit:
        # Find the next opening brace
        try:
            start = data.index(b"{", i)
        except ValueError:
            break
        # Find matching closing brace (simple bracket-matcher; handles nesting)
        depth = 0
        end = -1
        in_str = False
        esc = False
        for j in range(start, min(start + 8192, len(data))):
            b = data[j]
            if in_str:
                if esc:
                    esc = False
                elif b == 0x5c:  # backslash
                    esc = True
                elif b == 0x22:  # closing quote
                    in_str = False
            else:
                if b == 0x22:  # opening quote
                    in_str = True
                elif b == 0x7b:  # {
                    depth += 1
                elif b == 0x7d:  # }
                    depth -= 1
                    if depth == 0:
                        end = j
                        break
        if end == -1:
            i = start + 1
            continue
        snippet = data[start:end + 1]
        try:
            parsed = json.loads(snippet.decode("utf-8"))
            results.append({
                "offset": start,
                "length": end - start + 1,
                "json": parsed,
            })
        except (json.JSONDecodeError, UnicodeDecodeError):
            pass
        i = end + 1
    return results


def find_all_magics(data: bytes, scan_limit: int = 0) -> list[tuple[int, str]]:
    """Find every occurrence of each known magic in the file.

    If scan_limit > 0, only scan up to that many bytes; otherwise full file.
    Useful for locating embedded PNGs/JPEGs/videos inside a .mpkg VFS.
    """
    results = []
    end = len(data) if scan_limit == 0 else min(len(data), scan_limit)
    for magic, desc in KNOWN_MAGICS:
        # Skip the catch-all 3-byte MP4 prefix to avoid noise
        if magic == b"\x00\x00\x00":
            continue
        idx = 0
        while True:
            pos = data.find(magic, idx, end)
            if pos == -1:
                break
            results.append((pos, desc))
            idx = pos + 1
    return sorted(results)


def extract_strings(data: bytes, min_len: int = 4,
                   scan_limit: int = 4096) -> list[tuple[int, str]]:
    """Find printable ASCII strings of length >= min_len in the first scan_limit bytes."""
    results = []
    current = bytearray()
    start = 0
    end = min(len(data), scan_limit)
    for i in range(end):
        b = data[i]
        if 32 <= b <= 126:
            if not current:
                start = i
            current.append(b)
        else:
            if len(current) >= min_len:
                results.append((start, current.decode("ascii")))
            current = bytearray()
    if len(current) >= min_len:
        results.append((start, current.decode("ascii")))
    return results


def try_parse_vfs_table(data: bytes) -> list[dict]:
    """Best-effort parse of the VFS table.

    The native libscenejni.so reads the .mpkg header to build a virtual file
    table. Without REing the .so binary, we use a heuristic: look for a
    contiguous run of (u64 vOffset, u64 vSize) pairs after the "PKGM" magic.

    Returns a list of dicts with offset, size, and any detected magic at
    that virtual offset.
    """
    if not data.startswith(b"PKGM"):
        return []

    entries = []
    # Skip the 8-byte "PKGM0020" header, then look for a 4-byte entry count
    # followed by N (u64 offset, u64 size) pairs.
    pos = 8
    # Try both little-endian and big-endian
    for endian, label in [("<", "little-endian"), (">", "big-endian")]:
        try:
            count = struct.unpack_from(endian + "I", data, pos)[0]
            if 1 <= count <= 1024:
                entries_attempt = []
                p = pos + 4
                ok = True
                for i in range(count):
                    if p + 16 > len(data):
                        ok = False
                        break
                    v_off, v_size = struct.unpack_from(endian + "QQ", data, p)
                    if v_off + v_size > len(data):
                        ok = False
                        break
                    entries_attempt.append({
                        "index": i,
                        "v_offset": v_off,
                        "v_size": v_size,
                        "endian": label,
                        "magic_at_offset": (
                            data[v_off:v_off + 8].hex()
                            if v_off + 8 <= len(data) else ""
                        ),
                    })
                    p += 16
                if ok and entries_attempt:
                    return entries_attempt
        except struct.error:
            continue
    return entries


def analyze(path: Path, full: bool = False) -> int:
    if not path.exists():
        print(f"error: file not found: {path}", file=sys.stderr)
        return 1
    data = path.read_bytes()
    print(f"=== .mpkg Analyzer ===")
    print(f"File:    {path}")
    print(f"Size:    {len(data):,} bytes  ({len(data) / 1024 / 1024:.2f} MB)")
    print(f"Magic:   {data[:16]!r}  (first 16 bytes)")
    print()

    # 1. Detect magic
    print("--- Magic detection ---")
    matched = False
    for magic, desc in KNOWN_MAGICS:
        if data.startswith(magic):
            print(f"  Match: {desc}")
            matched = True
    if not matched:
        print(f"  No known magic matched.")
        print(f"  First 16 bytes hex: {data[:16].hex()}")
    print()

    # 2. Hex dump
    print("--- First 128 bytes (hex + ASCII) ---")
    print(hex_dump(data, 0, 128))
    print()

    # 3. ZIP test
    if data[:2] == b"PK":
        print("--- ZIP container detected ---")
        try:
            import zipfile
            with zipfile.ZipFile(path) as z:
                for info in z.infolist():
                    print(f"  {info.filename:40s}  {info.file_size:>10} bytes")
                print()
                # Try common manifest files
                for name in ["manifest.json", "project.json", "info.json",
                             "metadata.json", "wallpaperengine.json",
                             "wallpaper.json"]:
                    if name in z.namelist():
                        with z.open(name) as mf:
                            try:
                                manifest = json.loads(mf.read())
                                print(f"  Manifest '{name}':")
                                print(json.dumps(manifest, indent=2))
                                print()
                            except Exception as e:
                                print(f"  Manifest '{name}' (not JSON: {e})")
        except Exception as e:
            print(f"  ZIP parse failed: {e}")
        print()

    # 4. JSON scan
    print("--- Embedded JSON objects (first 16 KiB) ---")
    jsons = find_json_objects(data, scan_limit=16384)
    if not jsons:
        print("  No JSON objects found.")
    for j in jsons[:8]:
        print(f"  @0x{j['offset']:06x}  ({j['length']} bytes):")
        snippet = json.dumps(j["json"], indent=2)
        if len(snippet) > 600:
            snippet = snippet[:600] + "  ...(truncated)"
        print(snippet)
        print()
    print()

    # 5. Known magics everywhere (or first 4 MiB if --full is false)
    scan_limit = 0 if full else 4 * 1024 * 1024
    print(f"--- Known file magics (scan: "
          f"{'full file' if full else 'first 4 MiB'}) ---")
    magics = find_all_magics(data, scan_limit=scan_limit)
    if not magics:
        print("  No embedded file magics found.")
    else:
        for pos, desc in magics[:30]:
            print(f"  @0x{pos:08x}  {desc}")
        if len(magics) > 30:
            print(f"  ... and {len(magics) - 30} more (use --full to see all)")
    print()

    # 6. Strings
    print("--- Printable strings (first 4 KiB, length >= 4) ---")
    strings = extract_strings(data, min_len=4, scan_limit=4096)
    if not strings:
        print("  No printable strings found.")
    else:
        for off, s in strings[:30]:
            print(f"  @{off:5d}  {s}")
        if len(strings) > 30:
            print(f"  ... and {len(strings) - 30} more")
    print()

    # 7. VFS table parse
    print("--- VFS table parse (best-effort) ---")
    vfs = try_parse_vfs_table(data)
    if not vfs:
        print("  No VFS table found (file may not start with PKGM, "
              "or table layout differs from heuristic).")
    else:
        endian_label = vfs[0].get("endian", "?")
        print(f"  Detected VFS table ({len(vfs)} entries, {endian_label}):")
        for e in vfs[:20]:
            magic_hex = e.get("magic_at_offset", "")
            print(f"    [{e['index']:3d}] v_offset=0x{e['v_offset']:08x}  "
                  f"v_size={e['v_size']:>10}  magic_at_offset={magic_hex}")
        if len(vfs) > 20:
            print(f"  ... and {len(vfs) - 20} more entries")
    print()

    # 8. CRC32 of the file (for sample fingerprinting)
    crc = binascii.crc32(data) & 0xFFFFFFFF
    print(f"--- File identity ---")
    print(f"  CRC-32:    {crc:08x}")
    print(f"  SHA-256:   {binascii.hexlify(_sha256(data)).decode()}")
    print()
    print("=== Analysis complete ===")
    return 0


def _sha256(data: bytes) -> bytes:
    import hashlib
    return hashlib.sha256(data).digest()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="mpkg_analyzer",
        description="Analyze the Wallpaper Engine Android .mpkg file format.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument(
        "file",
        type=Path,
        help="Path to the .mpkg file (or any binary) to analyze.",
    )
    parser.add_argument(
        "--full", "-f",
        action="store_true",
        help="Scan the entire file for known magics (default: first 4 MiB).",
    )
    args = parser.parse_args(argv)
    return analyze(args.file, full=args.full)


if __name__ == "__main__":
    sys.exit(main())
