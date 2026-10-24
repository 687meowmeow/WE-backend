#!/usr/bin/env python3
"""Compare new debug mpkg with real mpkg."""
import struct
import sys
import os

def parse_mpkg(path):
    with open(path, 'rb') as f:
        data = f.read()
    
    header_marker = struct.unpack_from('<I', data, 0)[0]
    magic = data[4:12].decode('ascii', errors='replace')
    file_count = struct.unpack_from('<I', data, 12)[0]
    
    print(f"\n=== {os.path.basename(path)} ===")
    print(f"  Size: {len(data)} bytes ({len(data)/1024/1024:.1f} MB)")
    print(f"  Header marker: {header_marker}")
    print(f"  Magic: {magic}")
    print(f"  File count: {file_count}")
    
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
    
    data_start = pos
    print(f"  Data section starts at: {data_start}")
    
    print(f"\n  Entries ({len(entries)}):")
    for name, offset, size in sorted(entries):
        print(f"    {name:60s} offset={offset:#10x} size={size:>10d} ({size/1024:.1f} KB)")
    
    return entries, data_start, data

real_entries, real_data_start, real_data = parse_mpkg('/home/z/my-project/upload/2279314480.mpkg')
debug_entries, debug_data_start, debug_data = parse_mpkg('/home/z/my-project/upload/3822043862_debug.mpkg')

real_names = set(e[0] for e in real_entries)
debug_names = set(e[0] for e in debug_entries)

print(f"\nFiles in REAL but NOT in DEBUG ({len(real_names - debug_names)}):")
for name in sorted(real_names - debug_names):
    print(f"  {name}")

print(f"\nFiles in DEBUG but NOT in REAL ({len(debug_names - real_names)}):")
for name in sorted(debug_names - real_names):
    print(f"  {name}")

print(f"\nFiles in BOTH ({len(real_names & debug_names)}):")
for name in sorted(real_names & debug_names):
    print(f"  {name}")

# Compare project.json
print("\n" + "=" * 80)
print("project.json COMPARISON")
print("=" * 80)

for name, offset, size in debug_entries:
    if name == "project.json":
        content = debug_data[debug_data_start + offset: debug_data_start + offset + size]
        print(f"\nDEBUG project.json ({size} bytes):")
        print(content.decode('utf-8', errors='replace'))

for name, offset, size in real_entries:
    if name == "project.json":
        content = real_data[real_data_start + offset: real_data_start + offset + size]
        print(f"\nREAL project.json ({size} bytes):")
        print(content.decode('utf-8', errors='replace'))

# Compare scene.json
print("\n" + "=" * 80)
print("scene.json COMPARISON")
print("=" * 80)

for name, offset, size in debug_entries:
    if name == "scene.json":
        content = debug_data[debug_data_start + offset: debug_data_start + offset + size]
        print(f"\nDEBUG scene.json ({size} bytes):")
        print(content.decode('utf-8', errors='replace')[:500])

for name, offset, size in real_entries:
    if name == "scene.json":
        content = real_data[real_data_start + offset: real_data_start + offset + size]
        print(f"\nREAL scene.json ({size} bytes):")
        print(content.decode('utf-8', errors='replace')[:500])
