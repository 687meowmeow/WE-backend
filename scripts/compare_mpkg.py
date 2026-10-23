#!/usr/bin/env python3
"""
Compare two .mpkg files byte-by-byte to find format differences.

Usage: python3 compare_mpkg.py <file1.mpkg> <file2.mpkg>
"""
import struct
import sys
import os

def parse_mpkg(path):
    """Parse a .mpkg file and return its structure."""
    with open(path, 'rb') as f:
        data = f.read()
    
    result = {
        'file': path,
        'file_size': len(data),
        'header_marker': None,
        'magic': None,
        'entry_count': None,
        'entries': [],
        'data_section_start': None,
        'parse_error': None,
    }
    
    if len(data) < 16:
        result['parse_error'] = f'file too small ({len(data)} bytes)'
        return result
    
    # header_marker: u32 LE at offset 0
    result['header_marker'] = struct.unpack_from('<I', data, 0)[0]
    # magic: 8 ASCII bytes at offset 4
    result['magic'] = data[4:12].decode('ascii', errors='replace')
    # entry_count: u32 LE at offset 12
    result['entry_count'] = struct.unpack_from('<I', data, 12)[0]
    
    pos = 16
    entries = []
    for i in range(result['entry_count']):
        if pos + 4 > len(data):
            result['parse_error'] = f'truncated at entry {i} name_len'
            break
        name_len = struct.unpack_from('<I', data, pos)[0]
        pos += 4
        if name_len > 256 or name_len == 0:
            result['parse_error'] = f'entry {i} implausible name_len={name_len}'
            break
        if pos + name_len + 8 > len(data):
            result['parse_error'] = f'entry {i} truncated mid-entry'
            break
        name = data[pos:pos+name_len].decode('utf-8', errors='replace')
        pos += name_len
        offset = struct.unpack_from('<I', data, pos)[0]
        size = struct.unpack_from('<I', data, pos+4)[0]
        pos += 8
        entries.append({'index': i, 'name': name, 'offset': offset, 'size': size})
    
    result['data_section_start'] = pos
    result['entries'] = entries
    
    # For each entry, read the first 64 bytes of the data
    for entry in entries:
        abs_off = result['data_section_start'] + entry['offset']
        end = min(abs_off + 64, abs_off + entry['size'], len(data))
        if abs_off < len(data) and end > abs_off:
            entry['data_first_64_hex'] = data[abs_off:end].hex()
            entry['data_first_64_ascii'] = ''.join(
                chr(b) if 32 <= b < 127 else '.' for b in data[abs_off:end]
            )
            # Try to decode as text
            try:
                entry['data_as_text'] = data[abs_off:abs_off+entry['size']].decode('utf-8', errors='replace')[:200]
            except:
                entry['data_as_text'] = None
    
    return result


def print_mpkg(label, info):
    print(f"\n{'='*70}")
    print(f"  {label}")
    print(f"{'='*70}")
    print(f"File: {info['file']}")
    print(f"File size: {info['file_size']:,} bytes (0x{info['file_size']:x})")
    if info['parse_error']:
        print(f"PARSE ERROR: {info['parse_error']}")
        return
    print(f"Header marker: {info['header_marker']} (u32 LE, expected 8)")
    print(f"Magic: {info['magic']!r} (expected 'PKGMvvvv')")
    print(f"Entry count: {info['entry_count']}")
    print(f"Data section starts at: offset {info['data_section_start']} (0x{info['data_section_start']:x})")
    print()
    print(f"{'#':>3}  {'name':40s}  {'offset':>10}  {'size':>12}  abs_off")
    print(f"{'-'*3}  {'-'*40}  {'-'*10}  {'-'*12}  {'-'*10}")
    for e in info['entries']:
        abs_off = info['data_section_start'] + e['offset']
        print(f"{e['index']:>3}  {e['name'][:40]:40s}  {e['offset']:>10}  {e['size']:>12,}  0x{abs_off:x}")
        if 'data_first_64_hex' in e:
            print(f"     first 64 bytes (hex): {e['data_first_64_hex'][:128]}{'...' if len(e['data_first_64_hex']) > 128 else ''}")
            print(f"     first 64 bytes (asc): {e['data_first_64_ascii'][:64]}")
        if e.get('data_as_text'):
            text = e['data_as_text'].replace('\n', '\\n').replace('\r', '\\r')
            print(f"     content (first 200 chars): {text}")
        print()


def main():
    if len(sys.argv) != 3:
        print(f"Usage: {sys.argv[0]} <file1.mpkg> <file2.mpkg>")
        sys.exit(1)
    
    f1, f2 = sys.argv[1], sys.argv[2]
    if not os.path.exists(f1):
        print(f"Error: {f1} not found")
        sys.exit(1)
    if not os.path.exists(f2):
        print(f"Error: {f2} not found")
        sys.exit(1)
    
    info1 = parse_mpkg(f1)
    info2 = parse_mpkg(f2)
    
    print_mpkg("FILE 1 (reference / real)", info1)
    print_mpkg("FILE 2 (ours / generated)", info2)
    
    # Compare
    print(f"\n{'='*70}")
    print(f"  DIFF SUMMARY")
    print(f"{'='*70}")
    print(f"File sizes:     {info1['file_size']:,} vs {info2['file_size']:,}  (delta={info2['file_size']-info1['file_size']:+,})")
    print(f"Header marker:  {info1['header_marker']} vs {info2['header_marker']}")
    print(f"Magic:          {info1['magic']!r} vs {info2['magic']!r}")
    print(f"Entry count:    {info1['entry_count']} vs {info2['entry_count']}")
    print(f"Data section:   {info1['data_section_start']} vs {info2['data_section_start']}")
    
    print(f"\nEntry names comparison:")
    names1 = [e['name'] for e in info1['entries']]
    names2 = [e['name'] for e in info2['entries']]
    print(f"  File 1 names: {names1}")
    print(f"  File 2 names: {names2}")
    only1 = set(names1) - set(names2)
    only2 = set(names2) - set(names1)
    if only1: print(f"  Only in file 1: {only1}")
    if only2: print(f"  Only in file 2: {only2}")
    
    print(f"\nEntry-by-entry comparison (matching by name):")
    for e1 in info1['entries']:
        match = next((e for e in info2['entries'] if e['name'] == e1['name']), None)
        if match:
            print(f"\n  Entry '{e1['name']}':")
            print(f"    File 1: offset={e1['offset']}, size={e1['size']:,}")
            print(f"    File 2: offset={match['offset']}, size={match['size']:,}")
            if e1['size'] == match['size'] and e1['offset'] == match['offset']:
                print(f"    → MATCH")
            else:
                print(f"    → DIFFERENT (offset delta={match['offset']-e1['offset']:+}, size delta={match['size']-e1['size']:+,})")
            if 'data_first_64_hex' in e1 and 'data_first_64_hex' in match:
                if e1['data_first_64_hex'] == match['data_first_64_hex']:
                    print(f"    First 64 bytes: IDENTICAL")
                else:
                    print(f"    First 64 bytes DIFFER:")
                    print(f"      File 1: {e1['data_first_64_hex'][:128]}")
                    print(f"      File 2: {match['data_first_64_hex'][:128]}")
            if e1.get('data_as_text') and match.get('data_as_text'):
                print(f"    File 1 content: {e1['data_as_text'][:200]}")
                print(f"    File 2 content: {match['data_as_text'][:200]}")


if __name__ == '__main__':
    main()
