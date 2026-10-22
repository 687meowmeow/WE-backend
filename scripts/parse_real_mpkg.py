#!/usr/bin/env python3
"""Real .mpkg format parser — based on the actual sample file.
Format decoded from a 1.16 GB real .mpkg file ('3693384383.mpkg'):

Layout:
  [u32 LE header_marker = 8]            (4 bytes — length of the magic)
  [8-byte ASCII magic]                    ("PKGM" + 4-digit version, e.g. "PKGM0014")
  [u32 LE entry_count]                    (4 bytes — number of VFS entries)
  [VFS entries, each:
     [u32 LE name_len]
     [name_len bytes UTF-8 name]           (no null terminator)
     [u32 LE file_offset]                  (relative to data_section_start)
     [u32 LE file_size]
  ]
  [data section: file blobs packed back-to-back]

The data_section_start is the offset immediately after the last VFS entry's
file_size field. For our sample, the layout was:
  header_marker (4B) + magic (8B) + entry_count (4B) +
  3 entries × (4 + name_len + 4 + 4) +
  data section.

Confirmed from a real .mpkg file with magic "PKGM0014" containing:
  - preview.gif (offset 0 in data section, size 919028 bytes — the GIF89a magic
    was at absolute offset 0x72 = 114 = data_section_start)
  - project.json (offset 919028, size 244 bytes)
  - [Consome] 喜歡 Rouge 膚衣襪涼.mp4 (offset 919272, size 1212175194 bytes
    — runs to the end of the file)
"""
import struct
import json
from pathlib import Path
import sys


def parse_mpkg(path: Path) -> dict:
    """Parse a real .mpkg file and return its structure."""
    data = path.read_bytes()
    if len(data) < 16:
        return {"error": "file too small", "size": len(data)}

    result = {"size": len(data), "size_mb": len(data) / 1024 / 1024}

    # 4-byte LE header marker (always 8 — length of the magic)
    result["header_marker"] = struct.unpack_from("<I", data, 0)[0]

    # 8-byte ASCII magic
    result["magic"] = data[4:12].decode("ascii", errors="replace")
    if not result["magic"].startswith("PKGM"):
        result["error"] = f"magic mismatch: expected 'PKGM****', got {result['magic']!r}"
        return result

    # 4-byte LE entry count
    result["entry_count"] = struct.unpack_from("<I", data, 12)[0]

    # Parse VFS entries
    entries = []
    pos = 16
    for i in range(result["entry_count"]):
        if pos + 4 > len(data):
            entries.append({"index": i, "error": "truncated entry (no name_len)"})
            break
        name_len = struct.unpack_from("<I", data, pos)[0]
        pos += 4
        if pos + name_len > len(data):
            entries.append({"index": i, "error": f"truncated entry (name_len={name_len})"})
            break
        name = data[pos:pos + name_len].decode("utf-8", errors="replace")
        pos += name_len
        if pos + 8 > len(data):
            entries.append({"index": i, "error": "truncated entry (no offset/size)"})
            break
        file_offset = struct.unpack_from("<I", data, pos)[0]
        file_size = struct.unpack_from("<I", data, pos + 4)[0]
        pos += 8
        entry = {
            "index": i,
            "name": name,
            "name_len": name_len,
            "file_offset_relative": file_offset,
            "file_size": file_size,
        }
        entries.append(entry)

    result["entries"] = entries
    result["data_section_start"] = pos
    result["data_section_size"] = len(data) - pos

    # Compute absolute offsets
    for e in entries:
        if "file_offset_relative" in e:
            e["file_offset_absolute"] = pos + e["file_offset_relative"]
            e["file_end_absolute"] = e["file_offset_absolute"] + e["file_size"]

    # For each entry, peek at the first few bytes of its data
    for e in entries:
        if "file_offset_absolute" in e:
            abs_off = e["file_offset_absolute"]
            if abs_off + 16 <= len(data):
                e["first_16_bytes_hex"] = data[abs_off:abs_off + 16].hex()
                # Detect magic
                first = data[abs_off:abs_off + 8]
                if first.startswith(b"PKGM"):
                    e["detected_format"] = "PKGM .mpkg (nested?)"
                elif first.startswith(b"PK"):
                    e["detected_format"] = "ZIP archive"
                elif first.startswith(b"\x89PNG"):
                    e["detected_format"] = "PNG image"
                elif first.startswith(b"\xff\xd8\xff"):
                    e["detected_format"] = "JPEG image"
                elif first.startswith(b"GIF89a") or first.startswith(b"GIF87a"):
                    e["detected_format"] = "GIF image"
                elif first.startswith(b"\x1a\x45\xdf\xa3"):
                    e["detected_format"] = "Matroska/MKV/WebM video"
                elif first.startswith(b"\x00\x00\x00\x1cftyp") or first.startswith(b"\x00\x00\x00\x18ftyp") or b"ftyp" in first:
                    e["detected_format"] = "MP4/M4V video"
                elif first.startswith(b"RIFF"):
                    e["detected_format"] = "RIFF (AVI/WAV/WebP)"
                else:
                    e["detected_format"] = "unknown"
    return result


def main(argv=None):
    import argparse
    parser = argparse.ArgumentParser(description="Parse a real WE .mpkg file.")
    parser.add_argument("file", type=Path, help="Path to .mpkg file")
    parser.add_argument("--extract", type=Path, default=None,
                       help="Directory to extract files into (default: just print)")
    args = parser.parse_args(argv)

    result = parse_mpkg(args.file)
    print(json.dumps(result, indent=2, default=str))

    if args.extract and "entries" in result:
        args.extract.mkdir(parents=True, exist_ok=True)
        data = args.file.read_bytes()
        data_section_start = result.get("data_section_start", 0)
        for e in result["entries"]:
            if "file_offset_absolute" not in e:
                continue
            abs_off = e["file_offset_absolute"]
            size = e["file_size"]
            if abs_off + size > len(data):
                print(f"WARN: {e['name']} truncated")
                size = len(data) - abs_off
            # Sanitize the name for filesystem
            safe_name = e["name"].replace("/", "_").replace("\\", "_")
            out_path = args.extract / safe_name
            out_path.write_bytes(data[abs_off:abs_off + size])
            print(f"Extracted: {out_path} ({size} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
