#!/usr/bin/env python3
"""Dump raw bytes of .tex files from both .mpkg files for comparison."""
import struct
from pathlib import Path

def parse_mpkg(path):
    data = Path(path).read_bytes()
    version = struct.unpack('<I', data[0:4])[0]
    magic = data[4:12].decode('ascii', errors='replace')
    count = struct.unpack('<I', data[12:16])[0]
    off = 16
    entries = []
    for i in range(count):
        path_len = struct.unpack('<I', data[off:off+4])[0]
        off += 4
        path = data[off:off+path_len].decode('utf-8', errors='replace')
        off += path_len
        file_offset = struct.unpack('<I', data[off:off+4])[0]
        file_size = struct.unpack('<I', data[off+4:off+8])[0]
        off += 8
        entries.append({'path': path, 'offset': file_offset, 'size': file_size})
    return data, entries, off

def dump_bytes(data, label, max_bytes=200):
    print(f'\n=== {label} ===')
    for i in range(0, min(len(data), max_bytes), 16):
        line = data[i:i+16]
        hex_str = ' '.join(f'{b:02x}' for b in line)
        ascii_str = ''.join(chr(b) if 32 <= b < 127 else '.' for b in line)
        print(f'{i:5d}: {hex_str}  {ascii_str}')

def main():
    # Real
    real_data, real_entries, real_data_start = parse_mpkg('/home/z/my-project/upload/2279314480.mpkg')
    real_tex = [e for e in real_entries if e['path'].endswith('.tex')]
    
    print(f"REAL .mpkg has {len(real_tex)} .tex files")
    
    for e in real_tex[:1]:  # first .tex file
        tex_data = real_data[real_data_start + e['offset']:real_data_start + e['offset'] + e['size']]
        print(f"\nReal .tex: {e['path']!r} size={e['size']}")
        dump_bytes(tex_data, 'first 256 bytes', 256)
        
        # Find TEXB and dump fields
        texb_pos = tex_data.find(b'TEXB')
        if texb_pos >= 0:
            print(f'\nTEXB at offset {texb_pos}')
            # Dump 80 bytes starting at TEXB
            dump_bytes(tex_data[texb_pos:texb_pos+80], 'TEXB header + fields')
        
        # Find all PNG signatures
        png_pos = []
        pos = 0
        while True:
            idx = tex_data.find(b'\x89PNG', pos)
            if idx < 0:
                break
            png_pos.append(idx)
            pos = idx + 1
        print(f'\nPNG signatures found at: {png_pos}')
        
        # Find IEND
        iend_pos = []
        pos = 0
        while True:
            idx = tex_data.find(b'IEND', pos)
            if idx < 0:
                break
            iend_pos.append(idx)
            pos = idx + 1
        print(f'IEND markers found at: {iend_pos}')
    
    # Debug
    debug_data, debug_entries, debug_data_start = parse_mpkg('/home/z/my-project/upload/3823357259_debug.mpkg')
    debug_tex = [e for e in debug_entries if e['path'].endswith('.tex')]
    
    print(f"\n\nDEBUG .mpkg has {len(debug_tex)} .tex files")
    
    for e in debug_tex[:1]:  # first .tex file
        tex_data = debug_data[debug_data_start + e['offset']:debug_data_start + e['offset'] + e['size']]
        print(f"\nDebug .tex: {e['path']!r} size={e['size']}")
        dump_bytes(tex_data, 'first 256 bytes', 256)
        
        # Find TEXB and dump fields
        texb_pos = tex_data.find(b'TEXB')
        if texb_pos >= 0:
            print(f'\nTEXB at offset {texb_pos}')
            dump_bytes(tex_data[texb_pos:texb_pos+80], 'TEXB header + fields')
        
        # Find all PNG signatures
        png_pos = []
        pos = 0
        while True:
            idx = tex_data.find(b'\x89PNG', pos)
            if idx < 0:
                break
            png_pos.append(idx)
            pos = idx + 1
        print(f'\nPNG signatures found at: {png_pos}')
        
        # Find IEND
        iend_pos = []
        pos = 0
        while True:
            idx = tex_data.find(b'IEND', pos)
            if idx < 0:
                break
            iend_pos.append(idx)
            pos = idx + 1
        print(f'IEND markers found at: {iend_pos}')
        
        # Dump bytes around first PNG
        if png_pos:
            print(f'\nBytes around first PNG at {png_pos[0]}:')
            start = max(0, png_pos[0] - 50)
            end = min(len(tex_data), png_pos[0] + 50)
            dump_bytes(tex_data[start:end], f'bytes {start}..{end}')

if __name__ == '__main__':
    main()
