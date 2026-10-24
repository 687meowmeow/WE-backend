#!/usr/bin/env python3
"""Compare two .mpkg files to understand what's different."""
import struct
import sys
import os

def parse_mpkg(path):
    """Parse a .mpkg file and return list of (name, offset, size) entries."""
    with open(path, 'rb') as f:
        data = f.read()
    
    # Header
    header_marker = struct.unpack_from('<I', data, 0)[0]  # 4 bytes
    magic = data[4:12].decode('ascii', errors='replace')  # 8 bytes
    file_count = struct.unpack_from('<I', data, 12)[0]  # 4 bytes
    
    print(f"\n=== {os.path.basename(path)} ===")
    print(f"  Size: {len(data)} bytes ({len(data)/1024/1024:.1f} MB)")
    print(f"  Header marker: {header_marker}")
    print(f"  Magic: {magic}")
    print(f"  File count: {file_count}")
    
    # Parse VFS table
    pos = 16  # after header
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
    
    # Calculate data section start
    data_start = pos
    print(f"  Data section starts at: {data_start}")
    
    # List entries
    print(f"\n  Entries ({len(entries)}):")
    for name, offset, size in sorted(entries):
        print(f"    {name:60s} offset={offset:#10x} size={size:>10d} ({size/1024:.1f} KB)")
    
    return entries, data_start, data

# Parse both files
print("=" * 80)
print("COMPARING .mpkg FILES")
print("=" * 80)

real_entries, real_data_start, real_data = parse_mpkg('/home/z/my-project/upload/2279314480.mpkg')
debug_entries, debug_data_start, debug_data = parse_mpkg('/home/z/my-project/upload/3823357259_debug.mpkg')

# Compare file names
real_names = set(e[0] for e in real_entries)
debug_names = set(e[0] for e in debug_entries)

print("\n" + "=" * 80)
print("FILE NAME COMPARISON")
print("=" * 80)

print(f"\nFiles in REAL but NOT in DEBUG ({len(real_names - debug_names)}):")
for name in sorted(real_names - debug_names):
    print(f"  {name}")

print(f"\nFiles in DEBUG but NOT in REAL ({len(debug_names - real_names)}):")
for name in sorted(debug_names - real_names):
    print(f"  {name}")

print(f"\nFiles in BOTH ({len(real_names & debug_names)}):")
for name in sorted(real_names & debug_names):
    real_entry = [e for e in real_entries if e[0] == name][0]
    debug_entry = [e for e in debug_entries if e[0] == name][0]
    same_size = "✓" if real_entry[2] == debug_entry[2] else "✗"
    print(f"  {name:60s} real={real_entry[2]:>8d} debug={debug_entry[2]:>8d} {same_size}")

# Compare project.json
print("\n" + "=" * 80)
print("project.json COMPARISON")
print("=" * 80)

for name, offset, size in real_entries:
    if name == "project.json":
        content = real_data[real_data_start + offset: real_data_start + offset + size]
        print(f"\nREAL project.json ({size} bytes):")
        print(content.decode('utf-8', errors='replace'))

for name, offset, size in debug_entries:
    if name == "project.json":
        content = debug_data[debug_data_start + offset: debug_data_start + offset + size]
        print(f"\nDEBUG project.json ({size} bytes):")
        print(content.decode('utf-8', errors='replace'))
