"""Dump .tex file structure from real WE samples."""
import struct
import sys
import os

def hex_dump(data, start, length, indent=2):
    """Pretty hex dump."""
    end = min(start + length, len(data))
    chunk = data[start:end]
    hex_str = chunk.hex()
    pad = " " * indent
    lines = []
    for i in range(0, len(chunk), 16):
        b = chunk[i:i+16]
        h = b.hex()
        a = "".join(chr(c) if 32 <= c < 127 else "." for c in b)
        lines.append(f"{pad}{start+i:6d}: {h:<32}  {a}")
    return "\n".join(lines)

def parse_tex(path):
    """Parse a .tex file."""
    with open(path, 'rb') as f:
        data = f.read()
    
    print(f"\n=== {os.path.basename(path)} ({len(data)} bytes) ===")
    print(f"Full file first 256 bytes:")
    print(hex_dump(data, 0, 256, indent=2))
    
    # Find chunk markers
    for marker in [b'TEXV0005', b'TEXI0001', b'TEXB0004', b'TEXS0003']:
        positions = []
        start = 0
        while True:
            p = data.find(marker, start)
            if p < 0:
                break
            positions.append(p)
            start = p + 1
        print(f"\n{marker.decode()}: positions = {positions}")
    
    # Parse the structure assuming:
    # TEXV0005 (8 bytes) + 0x00 (separator, byte 8)
    # Then comes TEXI0001 chunk:
    #   TEXI0001 (8 bytes) + 0x00 (separator)
    #   then a struct of: image_format (u32 LE), mipmap_count? (u32 LE), width, height, width2, height2 (4x u32 LE)?
    # Then comes TEXB0004 chunk:
    #   TEXB0004 (8 bytes) + 0x00 (separator)
    #   then header + mipmap entries
    pos = 0
    
    # First 8 bytes: TEXV0005
    texv_magic = data[pos:pos+8].decode('ascii', errors='replace')
    pos += 8
    # next byte should be null
    sep = data[pos]
    pos += 1
    print(f"\nHeader: magic={texv_magic!r} separator=0x{sep:02x}")
    
    # TEXI0001 chunk
    texi_magic = data[pos:pos+8].decode('ascii', errors='replace')
    pos += 8
    sep1 = data[pos]
    pos += 1
    print(f"TEXI: magic={texi_magic!r} separator=0x{sep1:02x}")
    
    # Now parse TEXI payload
    # Based on what I see: format(5), type(2), width1(948), height1(1440), width2(947), height2(1440)
    # Actually let me try: 6x u32 = 24 bytes
    texi_payload = data[pos:pos+24]
    print(f"TEXI payload (24 bytes): {texi_payload.hex()}")
    fields = struct.unpack('<6I', texi_payload)
    print(f"  As 6x u32 LE: {fields}")
    print(f"    field0 (format?)    = {fields[0]}")
    print(f"    field1 (miplevels?) = {fields[1]}")
    print(f"    field2 (width?)     = {fields[2]}")
    print(f"    field3 (height?)    = {fields[3]}")
    print(f"    field4 (width2?)    = {fields[4]}")
    print(f"    field5 (height2?)   = {fields[5]}")
    pos += 24
    
    # Look at the next 64 bytes - this might be more TEXI data or a separator
    print(f"\nAfter TEXI+24 bytes (offset {pos}), next 64 bytes:")
    print(hex_dump(data, pos, 64, indent=2))
    
    # Find TEXB
    texb_pos = data.find(b'TEXB0004')
    if texb_pos < 0:
        print("No TEXB0004 chunk found")
        return
    print(f"\nTEXB0004 found at offset {texb_pos}")
    print(f"Bytes between TEXI payload end ({pos}) and TEXB ({texb_pos}): {texb_pos - pos} bytes")
    
    # Parse TEXB chunk
    pos = texb_pos
    texb_magic = data[pos:pos+8].decode('ascii', errors='replace')
    pos += 8
    sep2 = data[pos]
    pos += 1
    print(f"\nTEXB: magic={texb_magic!r} separator=0x{sep2:02x}")
    
    # TEXB payload starts here
    print(f"TEXB payload starts at offset {pos}")
    print(f"Next 128 bytes:")
    print(hex_dump(data, pos, 128, indent=2))
    
    # Try to interpret as: count(4) + flags(4) + reserved(4) + width(4) + height(4) + ...
    # OR: format(4) + count(4) + reserved(4) + ... + mip_levels
    # Looking at bytes after TEXB0004\0:
    # 01000000 ffffffff 00000000 01000000 b4030000 a0050000 01000000 80d41400 dcd40800
    
    texb_fields = struct.unpack_from('<10I', data, pos)
    print(f"\nTEXB first 10x u32 LE: {texb_fields}")
    print(f"  field0 = 0x{texb_fields[0]:08x} ({texb_fields[0]})")
    print(f"  field1 = 0x{texb_fields[1]:08x} ({texb_fields[1]})")
    print(f"  field2 = 0x{texb_fields[2]:08x} ({texb_fields[2]})")
    print(f"  field3 = 0x{texb_fields[3]:08x} ({texb_fields[3]})")
    print(f"  field4 = 0x{texb_fields[4]:08x} ({texb_fields[4]}) = width?")
    print(f"  field5 = 0x{texb_fields[5]:08x} ({texb_fields[5]}) = height?")
    print(f"  field6 = 0x{texb_fields[6]:08x} ({texb_fields[6]}) = mipmap count?")
    print(f"  field7 = 0x{texb_fields[7]:08x} ({texb_fields[7]}) = decompressed size?")
    print(f"  field8 = 0x{texb_fields[8]:08x} ({texb_fields[8]}) = compressed size?")

# Extract .tex files from real .mpkg
def extract_tex_from_mpkg(mpkg_path, output_dir):
    """Extract all .tex files from a .mpkg."""
    with open(mpkg_path, 'rb') as f:
        data = f.read()
    
    pos = 0
    header_marker = struct.unpack_from('<I', data, pos)[0]; pos += 4
    magic = data[pos:pos+8].decode('ascii'); pos += 8
    entry_count = struct.unpack_from('<I', data, pos)[0]; pos += 4
    
    entries = []
    for _ in range(entry_count):
        name_len = struct.unpack_from('<I', data, pos)[0]; pos += 4
        name = data[pos:pos+name_len].decode('utf-8'); pos += name_len
        offset = struct.unpack_from('<I', data, pos)[0]; pos += 4
        size = struct.unpack_from('<I', data, pos)[0]; pos += 4
        entries.append((name, offset, size))
    
    data_start = pos
    os.makedirs(output_dir, exist_ok=True)
    for name, offset, size in entries:
        file_data = data[data_start+offset : data_start+offset+size]
        out_path = os.path.join(output_dir, name.replace('/', '_'))
        os.makedirs(os.path.dirname(out_path) if os.path.dirname(out_path) else '.', exist_ok=True)
        with open(out_path, 'wb') as f:
            f.write(file_data)
    return entries

# Extract from 2279314480.mpkg
out_dir = '/home/z/my-project/research/tex_analysis/extracted'
entries = extract_tex_from_mpkg('/home/z/my-project/upload/2279314480.mpkg', out_dir)
print("Extracted files:")
for n, o, s in entries:
    print(f"  {n} (offset={o}, size={s})")

# Parse each .tex file
import glob
for tex_file in glob.glob(f'{out_dir}/*.tex'):
    parse_tex(tex_file)
