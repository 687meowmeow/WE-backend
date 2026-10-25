#!/usr/bin/env python3
"""
Extract and display the full project.json from a real .mpkg file.
"""
import struct
import sys

def main():
    path = sys.argv[1] if len(sys.argv) > 1 else '/home/z/my-project/upload/2279314480.mpkg'
    with open(path, 'rb') as f:
        data = f.read()
    
    # Parse header
    header_marker = struct.unpack_from('<I', data, 0)[0]
    magic = data[4:12].decode('ascii', errors='replace')
    entry_count = struct.unpack_from('<I', data, 12)[0]
    
    print(f"=== {path} ===")
    print(f"Size: {len(data):,} bytes")
    print(f"Header marker: {header_marker}")
    print(f"Magic: {magic!r}")
    print(f"Entry count: {entry_count}")
    print()
    
    pos = 16
    for i in range(entry_count):
        name_len = struct.unpack_from('<I', data, pos)[0]; pos += 4
        name = data[pos:pos+name_len].decode('utf-8'); pos += name_len
        offset = struct.unpack_from('<I', data, pos)[0]
        size = struct.unpack_from('<I', data, pos+4)[0]; pos += 8
        print(f"[{i}] {name}  offset={offset} size={size}")
    
    data_section_start = pos
    print(f"\nData section starts at: {pos}")
    print()
    
    # Now extract each entry's content
    pos = 16
    for i in range(entry_count):
        name_len = struct.unpack_from('<I', data, pos)[0]; pos += 4
        name = data[pos:pos+name_len].decode('utf-8'); pos += name_len
        offset = struct.unpack_from('<I', data, pos)[0]
        size = struct.unpack_from('<I', data, pos+4)[0]; pos += 8
        
        abs_off = data_section_start + offset
        content = data[abs_off:abs_off+size]
        
        # Print full content for .json files (these are text)
        if name.endswith('.json'):
            print(f"\n{'='*70}")
            print(f"  {name} (size={size})")
            print(f"{'='*70}")
            try:
                print(content.decode('utf-8'))
            except:
                print(f"(binary content, first 200 bytes hex: {content[:200].hex()})")
        else:
            # For binary files, just show first 64 bytes
            print(f"\n[{name}] (size={size}, binary)")
            print(f"  first 32 bytes: {content[:32].hex()}")

if __name__ == '__main__':
    main()
