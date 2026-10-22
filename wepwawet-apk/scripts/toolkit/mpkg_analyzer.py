#!/usr/bin/env python3
"""
mpkg_analyzer.py — Analyze the Wallpaper Engine Android .mpkg file format.

REAL FORMAT (verified from a 1.16 GB real .mpkg sample, magic "PKGM0014"):
=========================================================================
The .mpkg container is a custom binary format with a Virtual File System
(VFS) table. Layout:

  [u32 LE header_marker = 8]               (4 bytes — length of the magic)
  [8-byte ASCII magic]                       ("PKGM" + 4-digit version, e.g. "PKGM0014")
  [u32 LE entry_count]                       (4 bytes — number of VFS entries)
  For each entry (in order, no padding):
    [u32 LE name_len]
    [name_len bytes UTF-8 name]              (no null terminator)
    [u32 LE file_offset]                     (RELATIVE to data_section_start)
    [u32 LE file_size]
  [data section: file blobs packed back-to-back]

The data_section_start is the byte offset immediately after the last VFS
entry's file_size field. File offsets in the VFS entries are RELATIVE to
data_section_start (so the first file is always at relative offset 0).

The native library `libscenejni.so` parses this format and exposes JNI
methods on `SceneLib` (see io.wallpaperengine.wrapper.SceneLib):
  - getWallpaperType(filePath) → "Scene" | "Video" | ...
  - getWallpaperFileVirtualOffset(filePath) → VirtualFileRange{vOffset, vSize}
  - enumerateWallpapers() → WallpaperInfoSparse[]
  - getWallpaperInfoSparse(filePath) → WallpaperInfoSparse
  - getWallpaperInfoPreviewBitmap(filePath) → byte[]

The Java side then uses SupportFileLoader.loadBitmapFromVFS(vfsSource, offset, size)
which wraps the .mpkg in a RangedInputStream to read a single virtual file
out of the container.

For "Video" wallpapers, Util.getMPKGVideoReader(reader, offsets) returns a
MediaDataSource that reads at `position + offsets.vOffset` for
`offsets.vSize` bytes.

WHAT THIS SCRIPT DOES
=====================
Given a .mpkg file, this script will:
1. Print the file size and first 128 bytes (hex + ASCII).
2. Detect the magic ("PKGMvvvv").
3. Try to parse the file as a ZIP (some .mpkg variants are ZIPs).
4. Scan the first 16 KiB for embedded JSON objects (project.json, manifest).
5. Scan for known file magics: ZIP (PK), PNG, JPEG, Matroska, RIFF, MP4, GIF.
6. Print all printable strings > 4 chars in the first 4 KiB.
7. Parse the REAL VFS table format and print every entry with detected
   file format and absolute offset.
8. Print CRC-32 and SHA-256 of the file for sample fingerprinting.

USAGE
=====
    python mpkg_analyzer.py <file.mpkg> [--full] [--extract <dir>]
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
import hashlib
import json
import struct
import sys
from pathlib import Path

# Known magic bytes for files that may appear embedded in a .mpkg
KNOWN_MAGICS: list[tuple[bytes, str]] = [
    (b"PKGM",       "WE .mpkg container (PKGM magic)"),
    (b"PK",         "ZIP archive (PK\\x03\\x04)"),
    (b"\x89PNG",     "PNG image"),
    (b"\xff\xd8\xff", "JPEG image"),
    (b"\x1a\x45\xdf\xa3", "Matroska/MKV/WebM video"),
    (b"RIFF",       "RIFF (AVI/WAV/WebP)"),
    (b"GIF89a",     "GIF image (89a)"),
    (b"GIF87a",     "GIF image (87a)"),
    (b"TEXB",       "WE .tex texture format"),
    (b"\xfd7zXZ",   "XZ-compressed data"),
    (b"\x28\xb5\x2f\xfd", "Zstandard-compressed data"),
    (b"\x1f\x8b",   "GZIP-compressed data"),
]


def hex_dump(data: bytes, offset: int = 0, length: int = 128, width: int = 16) -> str:
    out = []
    end = min(offset + length, len(data))
    for i in range(offset, end, width):
        chunk = data[i:i + width]
        hex_part = " ".join(f"{b:02x}" for b in chunk)
        ascii_part = "".join(chr(b) if 32 <= b <= 126 else "." for b in chunk)
        out.append(f"  {i:08x}: {hex_part:<{width * 3}}  {ascii_part}")
    return "\n".join(out)


def find_json_objects(data: bytes, scan_limit: int = 16384) -> list[dict]:
    results = []
    i = 0
    limit = min(len(data), scan_limit)
    while i < limit:
        try:
            start = data.index(b"{", i)
        except ValueError:
            break
        depth = 0
        end = -1
        in_str = False
        esc = False
        for j in range(start, min(start + 8192, len(data))):
            b = data[j]
            if in_str:
                if esc:
                    esc = False
                elif b == 0x5c:
                    esc = True
                elif b == 0x22:
                    in_str = False
            else:
                if b == 0x22:
                    in_str = True
                elif b == 0x7b:
                    depth += 1
                elif b == 0x7d:
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
    results = []
    end = len(data) if scan_limit == 0 else min(len(data), scan_limit)
    for magic, desc in KNOWN_MAGICS:
        idx = 0
        while True:
            pos = data.find(magic, idx, end)
            if pos == -1:
                break
            results.append((pos, desc))
            idx = pos + 1
    return sorted(results)


def extract_strings(data: bytes, min_len: int = 4, scan_limit: int = 4096) -> list[tuple[int, str]]:
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


def detect_format(first_bytes: bytes) -> str:
    if first_bytes.startswith(b"PKGM"):
        return "PKGM .mpkg (nested?)"
    if first_bytes.startswith(b"PK"):
        return "ZIP archive"
    if first_bytes.startswith(b"\x89PNG"):
        return "PNG image"
    if first_bytes.startswith(b"\xff\xd8\xff"):
        return "JPEG image"
    if first_bytes.startswith(b"GIF89a") or first_bytes.startswith(b"GIF87a"):
        return "GIF image"
    if first_bytes.startswith(b"\x1a\x45\xdf\xa3"):
        return "Matroska/MKV/WebM video"
    if b"ftyp" in first_bytes[:8]:
        return "MP4/M4V video"
    if first_bytes.startswith(b"RIFF"):
        return "RIFF (AVI/WAV/WebP)"
    if first_bytes.startswith(b"\xfd7zXZ"):
        return "XZ-compressed"
    if first_bytes.startswith(b"\x28\xb5\x2f\xfd"):
        return "Zstandard-compressed"
    if first_bytes.startswith(b"\x1f\x8b"):
        return "GZIP-compressed"
    return "unknown"


def parse_real_vfs(data: bytes) -> dict:
    """Parse the REAL .mpkg VFS table format (verified from a real sample).

    Format:
      [u32 LE header_marker]  (always 8 = length of magic)
      [8-byte ASCII magic]    ("PKGM" + 4-digit version)
      [u32 LE entry_count]
      For each entry:
        [u32 LE name_len]
        [name_len bytes UTF-8 name]
        [u32 LE file_offset] (relative to data_section_start)
        [u32 LE file_size]
      [data section]
    """
    if len(data) < 16:
        return {"error": "file too small", "size": len(data)}

    if data[4:8] != b"PKGM":
        return {"error": f"magic mismatch at offset 4: expected 'PKGM', got {data[4:8]!r}"}

    header_marker = struct.unpack_from("<I", data, 0)[0]
    magic = data[4:12].decode("ascii", errors="replace")
    entry_count = struct.unpack_from("<I", data, 12)[0]

    if not (1 <= entry_count <= 4096):
        return {
            "header_marker": header_marker,
            "magic": magic,
            "entry_count": entry_count,
            "error": f"implausible entry count {entry_count}",
        }

    entries = []
    pos = 16
    for i in range(entry_count):
        if pos + 4 > len(data):
            entries.append({"index": i, "error": "truncated entry (no name_len)"})
            break
        name_len = struct.unpack_from("<I", data, pos)[0]
        pos += 4
        if name_len > 4096 or pos + name_len > len(data):
            entries.append({"index": i, "error": f"bad name_len {name_len}"})
            break
        name = data[pos:pos + name_len].decode("utf-8", errors="replace")
        pos += name_len
        if pos + 8 > len(data):
            entries.append({"index": i, "error": "truncated entry (no offset/size)"})
            break
        file_offset = struct.unpack_from("<I", data, pos)[0]
        file_size = struct.unpack_from("<I", data, pos + 4)[0]
        pos += 8
        entries.append({
            "index": i,
            "name": name,
            "name_len": name_len,
            "file_offset_relative": file_offset,
            "file_size": file_size,
        })

    data_section_start = pos
    # Compute absolute offsets and detect format at each file's start
    for e in entries:
        if "file_offset_relative" in e:
            abs_off = data_section_start + e["file_offset_relative"]
            e["file_offset_absolute"] = abs_off
            e["file_end_absolute"] = abs_off + e["file_size"]
            if abs_off + 16 <= len(data):
                first = data[abs_off:abs_off + 16]
                e["first_16_bytes_hex"] = first.hex()
                e["detected_format"] = detect_format(first)

    return {
        "header_marker": header_marker,
        "magic": magic,
        "entry_count": entry_count,
        "data_section_start": data_section_start,
        "data_section_size": len(data) - data_section_start,
        "entries": entries,
    }


def analyze(path: Path, full: bool = False, extract_dir: Path | None = None) -> int:
    if not path.exists():
        print(f"error: file not found: {path}", file=sys.stderr)
        return 1
    data = path.read_bytes()
    print(f"=== .mpkg Analyzer ===")
    print(f"File:    {path}")
    print(f"Size:    {len(data):,} bytes  ({len(data) / 1024 / 1024:.2f} MB)")
    print(f"Magic:   {data[:16]!r}  (first 16 bytes)")
    print()

    print("--- Magic detection ---")
    matched = False
    for magic, desc in KNOWN_MAGICS:
        if data.startswith(magic):
            print(f"  Match (offset 0): {desc}")
            matched = True
    if not matched and data[4:8] == b"PKGM":
        print(f"  Match (offset 4): WE .mpkg container (magic at offset 4, with 4-byte LE header marker first)")
        matched = True
    if not matched:
        print(f"  No known magic matched.")
        print(f"  First 16 bytes hex: {data[:16].hex()}")
    print()

    print("--- First 128 bytes (hex + ASCII) ---")
    print(hex_dump(data, 0, 128))
    print()

    # ZIP test
    if data[:2] == b"PK":
        print("--- ZIP container detected ---")
        try:
            import zipfile
            with zipfile.ZipFile(path) as z:
                for info in z.infolist():
                    print(f"  {info.filename:40s}  {info.file_size:>10} bytes")
                for name in ["manifest.json", "project.json", "info.json", "metadata.json"]:
                    if name in z.namelist():
                        with z.open(name) as mf:
                            try:
                                manifest = json.loads(mf.read())
                                print(f"\n  Manifest '{name}':")
                                print(json.dumps(manifest, indent=2))
                            except Exception as e:
                                print(f"  Manifest '{name}' (not JSON: {e})")
        except Exception as e:
            print(f"  ZIP parse failed: {e}")
        print()

    # JSON scan
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

    # Known magics
    scan_limit = 0 if full else 4 * 1024 * 1024
    print(f"--- Known file magics (scan: {'full file' if full else 'first 4 MiB'}) ---")
    magics = find_all_magics(data, scan_limit=scan_limit)
    if not magics:
        print("  No embedded file magics found.")
    else:
        for pos, desc in magics[:30]:
            print(f"  @0x{pos:08x}  {desc}")
        if len(magics) > 30:
            print(f"  ... and {len(magics) - 30} more (use --full to see all)")
    print()

    # Strings
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

    # REAL VFS table parse
    print("--- VFS table parse (REAL format from sample) ---")
    vfs = parse_real_vfs(data)
    if "error" in vfs:
        print(f"  VFS parse failed: {vfs['error']}")
    else:
        print(f"  header_marker:    {vfs['header_marker']} (u32 LE, expected 8 = magic length)")
        print(f"  magic:            {vfs['magic']!r}")
        print(f"  entry_count:      {vfs['entry_count']}")
        print(f"  data_section_start: 0x{vfs['data_section_start']:x} ({vfs['data_section_start']})")
        print(f"  data_section_size:  {vfs['data_section_size']:,} bytes")
        print(f"  Entries:")
        for e in vfs["entries"]:
            if "error" in e:
                print(f"    [{e['index']}] ERROR: {e['error']}")
                continue
            print(f"    [{e['index']}] name={e['name']!r}")
            print(f"        name_len={e['name_len']} bytes")
            print(f"        file_offset_relative=0x{e['file_offset_relative']:08x} ({e['file_offset_relative']})")
            print(f"        file_size={e['file_size']:,} bytes")
            print(f"        file_offset_absolute=0x{e['file_offset_absolute']:08x} ({e['file_offset_absolute']})")
            print(f"        file_end_absolute=0x{e['file_end_absolute']:08x}")
            if "first_16_bytes_hex" in e:
                print(f"        first_16_bytes={e['first_16_bytes_hex']}")
                print(f"        detected_format={e['detected_format']}")
    print()

    # Extract files if requested
    if extract_dir and "entries" in vfs:
        extract_dir.mkdir(parents=True, exist_ok=True)
        print(f"--- Extracting files to {extract_dir} ---")
        for e in vfs["entries"]:
            if "file_offset_absolute" not in e:
                continue
            abs_off = e["file_offset_absolute"]
            size = e["file_size"]
            if abs_off + size > len(data):
                print(f"  WARN: {e['name']} truncated (claimed {size}, only {len(data) - abs_off} available)")
                size = len(data) - abs_off
            safe_name = e["name"].replace("/", "_").replace("\\", "_")
            out_path = extract_dir / safe_name
            out_path.write_bytes(data[abs_off:abs_off + size])
            print(f"  Extracted: {out_path.name}  ({size:,} bytes)")
        print()

    # File identity
    crc = binascii.crc32(data) & 0xFFFFFFFF
    sha256 = hashlib.sha256(data).hexdigest()
    print("--- File identity ---")
    print(f"  CRC-32:    {crc:08x}")
    print(f"  SHA-256:   {sha256}")
    print()
    print("=== Analysis complete ===")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="mpkg_analyzer",
        description="Analyze the Wallpaper Engine Android .mpkg file format (REAL layout).",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument("file", type=Path, help="Path to the .mpkg file (or any binary) to analyze.")
    parser.add_argument("--full", "-f", action="store_true",
                        help="Scan the entire file for known magics (default: first 4 MiB).")
    parser.add_argument("--extract", "-x", type=Path, default=None,
                        help="Directory to extract the .mpkg's virtual files into.")
    args = parser.parse_args(argv)
    return analyze(args.file, full=args.full, extract_dir=args.extract)


if __name__ == "__main__":
    sys.exit(main())
