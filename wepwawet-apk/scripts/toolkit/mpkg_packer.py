#!/usr/bin/env python3
"""
mpkg_packer.py — Pack a wallpaper project into a .mpkg file using the
REAL format verified from a sample.

REAL FORMAT (verified from a 1.16 GB real .mpkg sample, magic "PKGM0014"):
=========================================================================
Layout:
  [u32 LE header_marker = 8]               (4 bytes — length of the magic)
  [8-byte ASCII magic]                       ("PKGM" + 4-digit version, e.g. "PKGM0020")
  [u32 LE entry_count]                       (4 bytes)
  For each entry:
    [u32 LE name_len]
    [name_len bytes UTF-8 name]              (no null terminator)
    [u32 LE file_offset]                     (RELATIVE to data_section_start)
    [u32 LE file_size]
  [data section: file blobs packed back-to-back]

The data_section_start is the byte offset immediately after the last VFS
entry's file_size field. File offsets are RELATIVE to data_section_start.

USAGE
=====
    python mpkg_packer.py <input_dir> <output.mpkg> [--manifest project.json] [--version 0020]
    python mpkg_packer.py --inspect <file.mpkg>
    python mpkg_packer.py --help

The input directory should contain at minimum:
    project.json    - WE wallpaper project manifest (title, type, files list)
    <files...>      - Any wallpaper assets referenced by project.json

Example project.json:
    {
        "title": "My Wallpaper",
        "type": "Scene",
        "file": "scene.pkg",
        "preview": "preview.jpg",
        "general": {"resolution": "1080x2400", "fps": 30}
    }
"""

from __future__ import annotations

import argparse
import json
import struct
import sys
from pathlib import Path

# Magic = "PKGM" + 4-digit version. Use "0020" for the current WE Android app
# (matches TransferService.MPKG_SUPPORT_VERSION = "PKGM0020").
# The user's real sample was "PKGM0014" (older version).
DEFAULT_MAGIC_VERSION = "0020"
HEADER_MARKER = 8  # u32 LE = 8 (length of the magic)


def pack(input_dir: Path, output: Path, manifest_name: str = "project.json",
         version: str = DEFAULT_MAGIC_VERSION) -> int:
    """Pack the contents of `input_dir` into a .mpkg at `output`."""
    manifest_path = input_dir / manifest_name
    if not manifest_path.exists():
        print(f"error: manifest not found: {manifest_path}", file=sys.stderr)
        return 1

    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as e:
        print(f"error: manifest is not valid JSON: {e}", file=sys.stderr)
        return 1

    # Collect the list of files to embed.
    files_to_embed: list[tuple[str, Path]] = []
    files_to_embed.append(("project.json", manifest_path))

    def _add(field: str) -> None:
        v = manifest.get(field)
        if isinstance(v, str) and v:
            p = input_dir / v
            if p.exists():
                files_to_embed.append((v, p))

    _add("file")
    _add("preview")
    _add("preview_image")
    _add("preview_video")
    _add("thumbnail")
    for f in manifest.get("files", []) or []:
        if isinstance(f, dict) and f.get("path"):
            p = input_dir / f["path"]
            if p.exists():
                files_to_embed.append((f["path"], p))
        elif isinstance(f, str) and f:
            p = input_dir / f
            if p.exists():
                files_to_embed.append((f, p))

    # Read each file's bytes
    blobs: list[tuple[str, bytes]] = []
    for vpath, p in files_to_embed:
        blobs.append((vpath, p.read_bytes()))

    print(f"Packing {len(blobs)} files into {output}:")
    for vpath, b in blobs:
        print(f"  {vpath:40s}  {len(b):>10} bytes")

    # Build the .mpkg:
    #   [u32 LE header_marker = 8]
    #   [8-byte ASCII magic "PKGM" + version]
    #   [u32 LE entry count N]
    #   [N entries of: u32 LE name_len, name, u32 LE file_offset, u32 LE file_size]
    #   [data section: file blobs packed back-to-back]
    magic = f"PKGM{version}".encode("ascii")
    if len(magic) != 8:
        print(f"error: magic must be exactly 8 bytes; got {magic!r} ({len(magic)} bytes)",
              file=sys.stderr)
        return 1

    n = len(blobs)
    # Compute layout
    header_size = 4 + 8 + 4                                # header_marker + magic + entry_count
    vfs_table_size = sum(4 + len(n.encode("utf-8")) + 4 + 4 for n, _ in blobs)
    data_section_start = header_size + vfs_table_size

    # Compute offsets and sizes for each blob
    vfs_entries: list[tuple[int, int]] = []  # (offset, size)
    cursor = 0  # relative offset within data section
    for _, blob in blobs:
        vfs_entries.append((cursor, len(blob)))
        cursor += len(blob)

    # Assemble
    out = bytearray()
    out += struct.pack("<I", HEADER_MARKER)
    out += magic
    out += struct.pack("<I", n)
    for (name, _), (offset, size) in zip(blobs, vfs_entries):
        nb = name.encode("utf-8")
        out += struct.pack("<I", len(nb))
        out += nb
        out += struct.pack("<I", offset)
        out += struct.pack("<I", size)
    for _, blob in blobs:
        out += blob

    output.write_bytes(bytes(out))
    print(f"\nWrote {output} ({len(out):,} bytes)")
    print(f"  header_marker:    {HEADER_MARKER} (u32 LE = magic length)")
    print(f"  magic:            {magic.decode('ascii')!r}")
    print(f"  entry_count:     {n}")
    print(f"  VFS table at:     0x{header_size:08x}  ({vfs_table_size} bytes)")
    print(f"  data section at:  0x{data_section_start:08x}")
    for i, (name, blob) in enumerate(blobs):
        offset, size = vfs_entries[i]
        abs_off = data_section_start + offset
        print(f"    [{i}] {name:40s}  @rel=0x{offset:08x}  @abs=0x{abs_off:08x}  {size:>10} bytes")
    print(f"\nNext step: install this .mpkg on a phone, or analyze with:")
    print(f"  python mpkg_analyzer.py {output}")
    return 0


def inspect(path: Path) -> int:
    """Quick inspect: print the magic, entry count, and VFS table."""
    if not path.exists():
        print(f"error: file not found: {path}", file=sys.stderr)
        return 1
    data = path.read_bytes()
    print(f"File:   {path}  ({len(data):,} bytes)")
    if len(data) < 16 or data[4:8] != b"PKGM":
        print(f"error: not a .mpkg file. Expected 'PKGM' at offset 4, got {data[4:8]!r}",
              file=sys.stderr)
        return 1
    header_marker = struct.unpack_from("<I", data, 0)[0]
    magic = data[4:12].decode("ascii", errors="replace")
    n = struct.unpack_from("<I", data, 12)[0]
    print(f"header_marker: {header_marker} (u32 LE = magic length, expected 8)")
    print(f"magic:         {magic!r}")
    print(f"VFS entries:   {n}")
    if not (1 <= n <= 4096):
        print(f"error: implausible VFS entry count {n}", file=sys.stderr)
        return 1
    pos = 16
    entries = []
    for i in range(n):
        if pos + 4 > len(data):
            print(f"  [{i}] truncated (no name_len)")
            break
        name_len = struct.unpack_from("<I", data, pos)[0]
        pos += 4
        name = data[pos:pos + name_len].decode("utf-8", errors="replace")
        pos += name_len
        file_offset = struct.unpack_from("<I", data, pos)[0]
        file_size = struct.unpack_from("<I", data, pos + 4)[0]
        pos += 8
        entries.append((i, name, file_offset, file_size))

    data_section_start = pos
    print(f"data_section_start: 0x{data_section_start:x}")
    print()
    print(f"{'idx':>4}  {'name':40s}  {'rel_off':>12}  {'size':>12}  {'abs_off':>12}  {'first_bytes':>24}")
    for i, name, off, size in entries:
        abs_off = data_section_start + off
        first = data[abs_off:abs_off + 8].hex() if abs_off + 8 <= len(data) else ""
        print(f"{i:>4}  {name:40s}  0x{off:08x}    {size:>12}  0x{abs_off:08x}  {first}")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="mpkg_packer",
        description="Pack or inspect a Wallpaper Engine Android .mpkg file (REAL format).",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument("input", type=Path, nargs="?",
                        help="Input directory (for pack) OR .mpkg file (with --inspect)")
    parser.add_argument("output", type=Path, nargs="?",
                        help="Output .mpkg path (only for pack mode)")
    parser.add_argument("--inspect", action="store_true",
                        help="Inspect the input .mpkg file instead of packing.")
    parser.add_argument("--manifest", default="project.json",
                        help="Manifest file name (default: project.json)")
    parser.add_argument("--version", default=DEFAULT_MAGIC_VERSION,
                        help=f"4-digit .mpkg version (default: {DEFAULT_MAGIC_VERSION} -> 'PKGM{DEFAULT_MAGIC_VERSION}')")
    args = parser.parse_args(argv)

    if args.input is None:
        parser.error("input is required (directory for pack, or .mpkg file with --inspect)")

    if args.inspect:
        return inspect(args.input)
    if args.output is None:
        parser.error("output path is required for pack mode (or use --inspect)")
    return pack(args.input, args.output, args.manifest, args.version)


if __name__ == "__main__":
    sys.exit(main())
