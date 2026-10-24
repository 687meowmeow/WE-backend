#!/usr/bin/env python3
"""Analyze .tex files in the mpkg to find the corruption."""
import struct
import os

def parse_mpkg(path):
    with open(path, 'rb') as f:
        data = f.read()
    
    file_count = struct.unpack_from('<I', data, 12)[0]
    pos = 16
    entries = []
    for i in range(file_count):
        name_len = struct.unpack_from('<I', data, pos)[0]
        pos += 4
        name = data[pos:pos+name_len].decode('utf-8', errors='replace')
        pos += name_len
        offset = struct.unpack_from('<I', data, pos)[0]
        pos += 4
        size = struct.unpack_from('<I', data, pos)[0]
        pos += 4
        entries.append((name, offset, size))
    
    return entries, pos, data

entries, data_start, data = parse_mpkg('/home/z/my-project/upload/3823357259_debug.mpkg')

print(f"=== 3823357259_debug.mpkg ===")
print(f"  File count: {len(entries)}")
print(f"  Data section starts at: {data_start}")
print()

# Check each .tex file
for name, offset, size in entries:
    if name.endswith('.tex'):
        tex_data = data[data_start + offset: data_start + offset + size]
        print(f"\n--- {name} ({size} bytes / {size/1024:.1f} KB) ---")
        print(f"  First 32 bytes (hex): {tex_data[:32].hex()}")
        
        # WE .tex format: first 4 bytes = format (0=RGBA8888, 1=RGB888, 2=RGBA4444, etc.)
        # Next 4 bytes = width
        # Next 4 bytes = height  
        # But there might be different versions...
        
        # Try to parse as WE tex header
        if len(tex_data) >= 12:
            tex_format = struct.unpack_from('<I', tex_data, 0)[0]
            width = struct.unpack_from('<I', tex_data, 4)[0]
            height = struct.unpack_from('<I', tex_data, 8)[0]
            print(f"  Format: {tex_format}, Width: {width}, Height: {height}")
            print(f"  Expected data size: {width * height * 4} (RGBA) or {width * height * 3} (RGB)")
            print(f"  Actual data size (after 12-byte header): {size - 12}")
            if width > 0 and height > 0:
                if tex_format == 0:
                    expected = width * height * 4
                elif tex_format == 1:
                    expected = width * height * 3
                else:
                    expected = -1
                if expected > 0:
                    print(f"  Size match: {'✓' if expected == size - 12 else '✗'} (expected {expected}, got {size-12})")
        elif len(tex_data) >= 8:
            # Maybe different header format
            print(f"  Bytes 0-3: {tex_data[:4].hex()} = {struct.unpack_from('<I', tex_data, 0)[0]}")
            print(f"  Bytes 4-7: {tex_data[4:8].hex()} = {struct.unpack_from('<I', tex_data, 4)[0]}")

# Also check the real mpkg's tex files
print("\n\n" + "=" * 80)
print("REAL mpkg (2279314480.mpkg)")
print("=" * 80)

real_entries, real_data_start, real_data = parse_mpkg('/home/z/my-project/upload/2279314480.mpkg')

for name, offset, size in real_entries:
    if name.endswith('.tex'):
        tex_data = real_data[real_data_start + offset: real_data_start + offset + size]
        print(f"\n--- {name} ({size} bytes / {size/1024:.1f} KB) ---")
        print(f"  First 32 bytes (hex): {tex_data[:32].hex()}")
        
        if len(tex_data) >= 12:
            tex_format = struct.unpack_from('<I', tex_data, 0)[0]
            width = struct.unpack_from('<I', tex_data, 4)[0]
            height = struct.unpack_from('<I', tex_data, 8)[0]
            print(f"  Format: {tex_format}, Width: {width}, Height: {height}")
            print(f"  Expected data size: {width * height * 4} (RGBA) or {width * height * 3} (RGB)")
            print(f"  Actual data size (after 12-byte header): {size - 12}")
            if width > 0 and height > 0:
                if tex_format == 0:
                    expected = width * height * 4
                elif tex_format == 1:
                    expected = width * height * 3
                else:
                    expected = -1
                if expected > 0:
                    print(f"  Size match: {'✓' if expected == size - 12 else '✗'} (expected {expected}, got {size-12})")

# Also check the scene.json and project.json
print("\n\n" + "=" * 80)
print("project.json from debug mpkg:")
print("=" * 80)
for name, offset, size in entries:
    if name == "project.json":
        content = data[data_start + offset: data_start + offset + size]
        print(content.decode('utf-8', errors='replace'))

# Check if preview.jpg exists and has JPEG magic
print("\n\n" + "=" * 80)
print("preview.jpg check:")
print("=" * 80)
for name, offset, size in entries:
    if name == "preview.jpg":
        jpg_data = data[data_start + offset: data_start + offset + size]
        print(f"  Size: {size} bytes")
        print(f"  First 4 bytes: {jpg_data[:4].hex()}")
        print(f"  JPEG magic (FFD8): {'✓' if jpg_data[:2] == b'\xff\xd8' else '✗'}")
