#!/usr/bin/env python3
"""
mpkg_packer.py — Pack a wallpaper project into a .mpkg file for the
Wallpaper Engine Android app.

FORMAT NOTES (best-effort, reverse-engineered from the WE Android app)
========================================================================
The .mpkg format is a custom container with magic "PKGM" + version "0020"
(8 bytes total: b"PKGM0020"). After the magic, the native libscenejni.so
parses a VFS (Virtual File System) table describing where each virtual
file lives inside the .mpkg.

We don't have a sample .mpkg nor the decompiled libscenejni.so binary, so
the layout below is a HEURISTIC built from:
- The Java side calls `SceneLib.getWallpaperFileVirtualOffset(filePath)`
  which returns `VirtualFileRange(vOffset, vSize)` (two u64s in Java longs).
- `Util.getMPKGVideoReader(reader, offsets)` does:
    reader.seek(position + offsets.getVOffset())
    reader.read(buffer, offset, size)  # bounded by offsets.getVSize()
- `TransferService.MPKG_SUPPORT_VERSION = "PKGM0020"` is the current
  format version the official app expects.
- `SupportFileLoader.loadBitmapFromVFS(vfsSource, offset, size)` uses
  `RangedInputStream(FileInputStream(vfsSource), offset, size)` to read
  a single virtual file out of the container.

So the minimum layout the native lib must be able to parse is:
    [8-byte magic "PKGM0020"]
    [4-byte LE entry count N]
    [N * (8-byte LE vOffset, 8-byte LE vSize)]  ->  VFS table
    [string table: N null-terminated UTF-8 names]   (HEURISTIC — position
                                                     them in the same order)
    [file data blobs, packed back-to-back]

WARNING: This packer is a HYPOTHESIS. Without a real .mpkg sample or RE'd
libscenejni.so, we cannot guarantee interoperability. Use this packer
together with mpkg_analyzer.py and Frida hooks to iterate against the
real WE Android app.

USAGE
=====
    python mpkg_packer.py <input_dir> <output.mpkg> [--manifest project.json]
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

MAGIC = b"PKGM0020"  # TransferService.MPKG_SUPPORT_VERSION


def pack(input_dir: Path, output: Path, manifest_name: str = "project.json") -> int:
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

    # Collect the list of files to embed. We always embed:
    #   - project.json (the manifest itself, virtual path "project.json")
    #   - any file referenced by the manifest's "file" / "preview" fields
    #   - any files in the manifest's "files" array (if present)
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
    #   [8-byte magic]
    #   [4-byte LE entry count N]
    #   [N * (8-byte LE vOffset, 8-byte LE vSize)]   VFS table
    #   [N * (4-byte LE nameLen, nameLen bytes UTF-8 name)]  name table
    #   [N blobs, packed back-to-back]
    n = len(blobs)
    header_len = 8 + 4                       # magic + count
    vfs_table_len = n * 16                    # 16 bytes per entry
    name_table_len = sum(4 + len(n.encode("utf-8")) for n, _ in blobs)
    data_start = header_len + vfs_table_len + name_table_len

    # Compute offsets and sizes for each blob
    vfs_entries: list[tuple[int, int]] = []  # (offset, size)
    cursor = data_start
    for _, blob in blobs:
        vfs_entries.append((cursor, len(blob)))
        cursor += len(blob)

    # Assemble
    out = bytearray()
    out += MAGIC
    out += struct.pack("<I", n)
    for offset, size in vfs_entries:
        out += struct.pack("<QQ", offset, size)
    for name, _ in blobs:
        nb = name.encode("utf-8")
        out += struct.pack("<I", len(nb))
        out += nb
    for _, blob in blobs:
        out += blob

    output.write_bytes(bytes(out))
    print(f"\nWrote {output} ({len(out):,} bytes)")
    print(f"  magic:           {MAGIC.decode('ascii')}")
    print(f"  entry count:     {n}")
    print(f"  VFS table at:    0x{8:08x}  ({vfs_table_len} bytes)")
    print(f"  name table at:   0x{8 + vfs_table_len:08x}  ({name_table_len} bytes)")
    print(f"  data start:      0x{data_start:08x}")
    for i, (name, blob) in enumerate(blobs):
        offset, size = vfs_entries[i]
        print(f"    [{i}] {name:40s}  @0x{offset:08x}  {size:>10} bytes")
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
    if not data.startswith(MAGIC):
        print(f"error: magic mismatch. Expected {MAGIC!r}, got {data[:8]!r}",
              file=sys.stderr)
        return 1
    n = struct.unpack_from("<I", data, 8)[0]
    print(f"Magic:  {data[:8].decode('ascii')}")
    print(f"VFS entries: {n}")
    if n < 1 or n > 4096:
        print(f"error: implausible VFS entry count {n}", file=sys.stderr)
        return 1
    print(f"{'idx':>4}  {'v_offset':>12}  {'v_size':>12}  {'first_bytes':>24}")
    p = 12
    for i in range(n):
        offset, size = struct.unpack_from("<QQ", data, p + i * 16)
        first = data[offset:offset + 8].hex() if offset + 8 <= len(data) else ""
        print(f"{i:>4}  0x{offset:08x}  {size:>12}  {first}")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="mpkg_packer",
        description="Pack or inspect a Wallpaper Engine Android .mpkg file.",
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
    args = parser.parse_args(argv)

    if args.input is None:
        parser.error("input is required (directory for pack, or .mpkg file with --inspect)")

    if args.inspect:
        return inspect(args.input)
    if args.output is None:
        parser.error("output path is required for pack mode (or use --inspect)")
    return pack(args.input, args.output, args.manifest)


if __name__ == "__main__":
    sys.exit(main())
