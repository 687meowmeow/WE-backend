#!/usr/bin/env python3
"""Deep analysis of .mpkg files - real vs debug.

Compares:
- Header structure
- VFS table format
- File entries (paths, offsets, sizes)
- .tex file structure (TEXV/TEXI/TEXB headers)
- Verifies that data at each offset matches the expected file type
"""
import struct
import os
import sys
from pathlib import Path

def read_cstring(data, offset):
    """Read null-terminated string."""
    end = data.index(b'\x00', offset)
    return data[offset:end].decode('utf-8', errors='replace'), end + 1

def parse_mpkg(path):
    """Parse .mpkg file structure."""
    data = Path(path).read_bytes()
    result = {
        'file': path,
        'size': len(data),
        'header': {},
        'entries': [],
    }
    
    # Header: 8 bytes magic + 4 bytes entry count + 4 bytes (reserved/version?)
    if len(data) < 16:
        result['error'] = 'file too small'
        return result
    
    magic = data[:8]
    result['header']['magic'] = magic
    result['header']['magic_str'] = magic.decode('ascii', errors='replace')
    
    # Try common patterns: magic(8) + count(4) + ???(4)
    entry_count_be = struct.unpack('>I', data[8:12])[0]
    entry_count_le = struct.unpack('<I', data[8:12])[0]
    result['header']['entry_count_be'] = entry_count_be
    result['header']['entry_count_le'] = entry_count_le
    
    # Read remaining header bytes
    result['header']['bytes_12_16'] = data[12:16].hex()
    result['header']['first_32_bytes'] = data[:32].hex()
    
    # Try to find VFS table - look for path strings
    # Paths typically start with project.json, scene.json, preview.jpg etc.
    # Find first path string after the header
    path_markers = [b'project.json', b'scene.json', b'preview.jpg', b'scene.pkg']
    first_path_offset = None
    for marker in path_markers:
        idx = data.find(marker)
        if idx >= 0 and (first_path_offset is None or idx < first_path_offset):
            first_path_offset = idx
    
    result['header']['first_path_offset'] = first_path_offset
    
    if first_path_offset is None:
        result['error'] = 'no path markers found'
        return result
    
    # Look backwards from first path to find table start
    # VFS table typically has: count(4) + entries where each is path_len(4) + path + offset(8) + size(8)
    # OR: count(4) + entries where each is offset(8) + size(8) + path_len(4) + path
    
    # Heuristic: scan backwards looking for entry count
    # Or just parse forward from a known good position
    
    # Try parsing from offset 8 with entry count
    count = entry_count_le
    result['header']['assumed_count'] = count
    
    # Try format: count(4) + entries[ path_len(4) + path + offset(8) + size(8) ]
    offset = 12
    entries = []
    for i in range(min(count, 200)):  # cap at 200
        if offset + 4 > len(data):
            break
        path_len = struct.unpack('<I', data[offset:offset+4])[0]
        offset += 4
        if path_len > 1024 or path_len == 0:
            # try BE
            offset -= 4
            path_len_be = struct.unpack('>I', data[offset:offset+4])[0]
            offset += 4
            if path_len_be > 1024 or path_len_be == 0:
                break
            path_len = path_len_be
        if offset + path_len > len(data):
            break
        path = data[offset:offset+path_len].decode('utf-8', errors='replace').rstrip('\x00')
        offset += path_len
        # align to 4 or 8 bytes?
        # Try reading offset(8) + size(8)
        if offset + 16 > len(data):
            break
        file_offset = struct.unpack('<Q', data[offset:offset+8])[0]
        file_size = struct.unpack('<Q', data[offset+8:offset+16])[0]
        offset += 16
        entries.append({
            'index': i,
            'path': path,
            'offset': file_offset,
            'size': file_size,
        })
    
    if len(entries) < 2:
        # Try different format: offset(8) + size(8) + path_len(4) + path
        offset = 12
        entries = []
        for i in range(min(count, 200)):
            if offset + 20 > len(data):
                break
            file_offset = struct.unpack('<Q', data[offset:offset+8])[0]
            file_size = struct.unpack('<Q', data[offset+8:offset+16])[0]
            path_len = struct.unpack('<I', data[offset+16:offset+20])[0]
            offset += 20
            if path_len > 1024:
                break
            path = data[offset:offset+path_len].decode('utf-8', errors='replace').rstrip('\x00')
            offset += path_len
            entries.append({
                'index': i,
                'path': path,
                'offset': file_offset,
                'size': file_size,
            })
    
    result['entries'] = entries
    result['data_section_start'] = offset
    
    return result

def analyze_tex(data, name):
    """Analyze .tex file structure."""
    info = {'name': name, 'size': len(data)}
    if len(data) < 18:
        info['error'] = 'too small'
        return info
    
    # TEXV header
    texv_magic = data[:8]
    info['texv_magic'] = texv_magic.decode('ascii', errors='replace')
    
    # TEXI header at offset 8
    texi_magic = data[8:14]
    info['texi_magic'] = texi_magic.decode('ascii', errors='replace')
    
    # Common .tex structure:
    # TEXV0005\0 (8 bytes)
    # TEXI0001\0 (8 bytes)
    # format(4) width(4) height(4) mipmap_count(4) ...
    
    if data[14:16] == b'\x00\x00':
        # nulls before TEXI data
        pass
    
    # Try to parse TEXI: format(4) + mipmap_count(4) + mipmap levels
    # Then TEXB sections: magic(8) + format(4) + width(4) + height(4) + size(4) + data
    
    # Find all TEXB occurrences
    texb_offsets = []
    pos = 0
    while True:
        idx = data.find(b'TEXB', pos)
        if idx < 0:
            break
        texb_offsets.append(idx)
        pos = idx + 1
    
    info['texb_count'] = len(texb_offsets)
    info['texb_offsets'] = texb_offsets[:10]  # first 10
    
    # Find all PNG signatures
    png_offsets = []
    pos = 0
    while True:
        idx = data.find(b'\x89PNG', pos)
        if idx < 0:
            break
        png_offsets.append(idx)
        pos = idx + 1
    
    info['png_count'] = len(png_offsets)
    info['png_offsets'] = png_offsets[:10]
    
    # Parse first TEXB header (if exists)
    if texb_offsets:
        t = texb_offsets[0]
        if t + 24 <= len(data):
            texb_magic = data[t:t+8]
            # TEXB format (4 bytes after magic) — try different field positions
            # Common layout: magic(8) + format(4) + width(4) + height(4) + size(4) + data
            texb_format = struct.unpack('<I', data[t+8:t+12])[0]
            texb_width = struct.unpack('<I', data[t+12:t+16])[0]
            texb_height = struct.unpack('<I', data[t+16:t+20])[0]
            texb_size = struct.unpack('<I', data[t+20:t+24])[0]
            info['first_texb'] = {
                'offset': t,
                'format': texb_format,
                'width': texb_width,
                'height': texb_height,
                'data_size': texb_size,
            }
    
    return info

def main():
    files = [
        '/home/z/my-project/upload/2279314480.mpkg',  # real
        '/home/z/my-project/upload/3823357259_debug.mpkg',  # latest debug
        '/home/z/my-project/upload/3822043862_debug.mpkg',  # earlier debug
    ]
    
    for f in files:
        if not os.path.exists(f):
            print(f"--- {f}: NOT FOUND ---")
            continue
        print(f"\n{'='*80}")
        print(f"FILE: {f}")
        print(f"SIZE: {os.path.getsize(f):,} bytes")
        print('='*80)
        
        result = parse_mpkg(f)
        print(f"\nHeader:")
        for k, v in result['header'].items():
            print(f"  {k}: {v}")
        
        print(f"\nEntries: {len(result['entries'])}")
        for e in result['entries'][:15]:
            print(f"  [{e['index']:3d}] path={e['path']!r} offset={e['offset']:8d} size={e['size']:8d}")
        if len(result['entries']) > 15:
            print(f"  ... ({len(result['entries'])-15} more)")
        
        # Check if entries make sense
        if result['entries']:
            print(f"\nData section starts at: {result['data_section_start']}")
            
            # Look at the actual data for each entry
            data = Path(f).read_bytes()
            print(f"\nFile verification (first 16 bytes of each entry):")
            tex_files = []
            for e in result['entries'][:30]:
                if e['offset'] + min(16, e['size']) > len(data):
                    print(f"  [{e['index']:3d}] {e['path']!r}: OUT OF BOUNDS")
                    continue
                chunk = data[e['offset']:e['offset'] + min(16, e['size'])]
                hex_str = chunk.hex()
                # try decode as ASCII
                try:
                    ascii_str = chunk.decode('ascii', errors='replace').replace('\x00', '.')
                except:
                    ascii_str = ''
                print(f"  [{e['index']:3d}] {e['path']!r}: hex={hex_str} ascii={ascii_str!r}")
                
                if e['path'].endswith('.tex'):
                    tex_files.append((e['path'], e['offset'], e['size']))
            
            # Analyze each .tex file
            if tex_files:
                print(f"\n.tex file analysis ({len(tex_files)} files):")
                for path, offset, size in tex_files:
                    tex_data = data[offset:offset+size]
                    info = analyze_tex(tex_data, path)
                    print(f"\n  {path}:")
                    for k, v in info.items():
                        if k != 'name':
                            print(f"    {k}: {v}")
        
        if 'error' in result:
            print(f"\nERROR: {result['error']}")

if __name__ == '__main__':
    main()
