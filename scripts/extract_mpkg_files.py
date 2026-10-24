#!/usr/bin/env python3
"""Extract files from .mpkg and compare .tex structure between real and debug.

The .mpkg format:
- Header (16 bytes): version(4) + magic "PKGM0014"(8) + entry_count(4)
- VFS Table: entry_count * entry, where each entry is:
  - path_len(4) + path(N) + offset(4) + size(4)
  (no padding, no null terminator)
- Data section: files written consecutively at their offsets

The offset is relative to the data section start (i.e. after the VFS table).
"""
import struct
import sys
import os
from pathlib import Path

def parse_mpkg(path):
    data = Path(path).read_bytes()
    version = struct.unpack('<I', data[0:4])[0]
    magic = data[4:12].decode('ascii', errors='replace')
    count = struct.unpack('<I', data[12:16])[0]
    
    off = 16
    entries = []
    for i in range(count):
        if off + 4 > len(data):
            break
        path_len = struct.unpack('<I', data[off:off+4])[0]
        off += 4
        if path_len > 4096 or path_len == 0:
            print(f"BAD path_len={path_len} at offset {off-4}")
            break
        path = data[off:off+path_len].decode('utf-8', errors='replace')
        off += path_len
        if off + 8 > len(data):
            break
        file_offset = struct.unpack('<I', data[off:off+4])[0]
        file_size = struct.unpack('<I', data[off+4:off+8])[0]
        off += 8
        entries.append({
            'index': i,
            'path': path,
            'offset': file_offset,
            'size': file_size,
        })
    
    return data, version, magic, count, entries, off

def extract_tex_info(tex_data, name):
    """Analyze .tex structure."""
    info = {'name': name, 'size': len(tex_data)}
    if len(tex_data) < 32:
        info['error'] = 'too small'
        return info
    
    # TEXV header (8 bytes null-terminated = 9 bytes)
    # Actually first 8 bytes should be 'TEXV0005'
    texv = tex_data[:8].decode('ascii', errors='replace')
    info['texv'] = texv
    
    # Find TEXI marker
    texi_pos = tex_data.find(b'TEXI')
    if texi_pos < 0:
        info['error'] = 'no TEXI marker'
        return info
    info['texi_pos'] = texi_pos
    info['texi_header'] = tex_data[texi_pos:texi_pos+8].decode('ascii', errors='replace')
    
    # TEXI fields start at texi_pos + 9 (after 'TEXI0001\0')
    texi_fields_start = texi_pos + 9
    if texi_fields_start + 8 <= len(tex_data):
        # format(2) + pad(2) + mipmap_count(4)
        fmt = struct.unpack('<H', tex_data[texi_fields_start:texi_fields_start+2])[0]
        pad = struct.unpack('<H', tex_data[texi_fields_start+2:texi_fields_start+4])[0]
        mipmap_count = struct.unpack('<I', tex_data[texi_fields_start+4:texi_fields_start+8])[0]
        info['texi_format'] = fmt
        info['texi_pad'] = pad
        info['mipmap_count'] = mipmap_count
        
        # Read mipmap width/height pairs
        mipmaps = []
        for i in range(min(mipmap_count, 16)):
            base = texi_fields_start + 8 + i * 8
            if base + 8 <= len(tex_data):
                w = struct.unpack('<I', tex_data[base:base+4])[0]
                h = struct.unpack('<I', tex_data[base+4:base+8])[0]
                mipmaps.append((w, h))
        info['mipmaps'] = mipmaps
    
    # Find all TEXB markers
    texb_offsets = []
    pos = 0
    while True:
        idx = tex_data.find(b'TEXB', pos)
        if idx < 0:
            break
        texb_offsets.append(idx)
        pos = idx + 1
    
    info['texb_count'] = len(texb_offsets)
    info['texb_offsets'] = texb_offsets
    
    # Parse each TEXB section
    texb_sections = []
    for idx, t in enumerate(texb_offsets):
        if t + 9 + 36 > len(tex_data):
            break
        # Skip 'TEXB0004\0' (9 bytes)
        fields_start = t + 9
        # 9 fields of 4 bytes = 36 bytes
        fields = []
        for i in range(9):
            v = struct.unpack('<I', tex_data[fields_start + i*4:fields_start + i*4 + 4])[0]
            fields.append(v)
        # Then 4 bytes data_size
        data_size_offset = fields_start + 36
        if data_size_offset + 4 <= len(tex_data):
            data_size = struct.unpack('<I', tex_data[data_size_offset:data_size_offset+4])[0]
        else:
            data_size = -1
        # Then 1 byte padding
        data_start = data_size_offset + 4 + 1
        # Check first 4 bytes of data
        if data_start + 4 <= len(tex_data):
            data_magic = tex_data[data_start:data_start+4]
        else:
            data_magic = b''
        
        texb_sections.append({
            'offset': t,
            'flags': fields[0],
            'format': fields[1],
            'unknown2': fields[2],
            'bpp': fields[3],
            'width': fields[4],
            'height': fields[5],
            'unknown6': fields[6],
            'unknown7': fields[7],
            'unknown8': fields[8],
            'data_size': data_size,
            'data_start': data_start,
            'data_magic_hex': data_magic.hex(),
            'data_magic_ascii': data_magic.decode('ascii', errors='replace'),
        })
    
    info['texb_sections'] = texb_sections
    return info

def main():
    files = [
        ('REAL', '/home/z/my-project/upload/2279314480.mpkg'),
        ('DEBUG', '/home/z/my-project/upload/3823357259_debug.mpkg'),
    ]
    
    for label, path in files:
        if not os.path.exists(path):
            print(f"--- {label}: NOT FOUND ---")
            continue
        print(f"\n{'='*80}")
        print(f"{label}: {path}")
        print(f"SIZE: {os.path.getsize(path):,} bytes")
        print('='*80)
        
        data, version, magic, count, entries, data_start = parse_mpkg(path)
        print(f"\nversion={version} magic={magic!r} count={count} data_section_start={data_start}")
        print(f"\nAll {len(entries)} entries:")
        for e in entries:
            file_off_abs = data_start + e['offset']
            print(f"  [{e['index']:3d}] path={e['path']!r}")
            print(f"        offset_in_data_section={e['offset']:8d} abs_offset={file_off_abs:8d} size={e['size']:8d}")
            # Verify the data at this offset
            if file_off_abs + min(16, e['size']) <= len(data):
                chunk = data[file_off_abs:file_off_abs + min(16, e['size'])]
                print(f"        first 16 bytes: hex={chunk.hex()} ascii={chunk.decode('ascii', errors='replace').replace(chr(0), '.')!r}")
        
        # Extract and analyze .tex files
        tex_entries = [e for e in entries if e['path'].endswith('.tex')]
        if tex_entries:
            print(f"\n--- .tex file analysis ({len(tex_entries)} files) ---")
            for e in tex_entries:
                tex_data = data[data_start + e['offset']:data_start + e['offset'] + e['size']]
                info = extract_tex_info(tex_data, e['path'])
                print(f"\n  .tex: {e['path']!r} size={info['size']}")
                if 'error' in info:
                    print(f"    ERROR: {info['error']}")
                    continue
                print(f"    texv={info.get('texv', 'N/A')}")
                print(f"    texi_pos={info.get('texi_pos', 'N/A')} texi_header={info.get('texi_header', 'N/A')}")
                print(f"    texi_format={info.get('texi_format', 'N/A')} mipmap_count={info.get('mipmap_count', 'N/A')}")
                print(f"    mipmaps (w,h): {info.get('mipmaps', [])}")
                print(f"    texb_count={info.get('texb_count', 0)}")
                for j, ts in enumerate(info.get('texb_sections', [])):
                    print(f"    TEXB[{j}]: flags={ts['flags']} format={ts['format']} bpp={ts['bpp']} w={ts['width']} h={ts['height']} data_size={ts['data_size']} data_magic={ts['data_magic_hex']!r} ({ts['data_magic_ascii']!r})")

if __name__ == '__main__':
    main()
