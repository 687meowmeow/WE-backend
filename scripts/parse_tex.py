#!/usr/bin/env python3
"""Parse a .tex file from a WE .mpkg to understand the binary format."""
import struct
import sys

def parse_tex(data, label=""):
    """Parse a .tex binary blob and print its structure."""
    print(f"\n=== .tex file ({label}) ===")
    print(f"Total size: {len(data)} bytes")
    
    pos = 0
    # Magic 1: TEXV0005 (8 bytes)
    if len(data) < 8:
        print("Too small for magic1")
        return
    magic1 = data[0:8].decode('ascii', errors='replace')
    print(f"[0x{0:04x}] Magic1: {magic1!r}")
    pos = 8
    
    # Check for null byte after magic1
    if pos < len(data) and data[pos] == 0:
        print(f"[0x{pos:04x}] Null byte after magic1")
        pos += 1
    
    # Magic 2: TEXI0001 (8 bytes)
    if pos + 8 > len(data):
        print("Too small for magic2")
        return
    magic2 = data[pos:pos+8].decode('ascii', errors='replace')
    print(f"[0x{pos:04x}] Magic2: {magic2!r}")
    pos += 8
    
    # Check for null byte after magic2
    if pos < len(data) and data[pos] == 0:
        print(f"[0x{pos:04x}] Null byte after magic2")
        pos += 1
    
    # TEXI header: Format(u32), Flags(u32), TexWidth(u32), TexHeight(u32), 
    #              ImgWidth(u32), ImgHeight(u32), UnkInt0(u32)
    header_size = 7 * 4  # 28 bytes
    if pos + header_size > len(data):
        print(f"Too small for header (need {header_size} bytes from pos {pos})")
        # Print what we have
        remaining = data[pos:]
        print(f"  Remaining bytes ({len(remaining)}): {remaining.hex()}")
        return
    
    fmt, flags, tex_w, tex_h, img_w, img_h, unk0 = struct.unpack_from('<7I', data, pos)
    print(f"[0x{pos:04x}] TEXI Header:")
    print(f"  Format:       {fmt}")
    print(f"  Flags:        {flags} (0x{flags:08x})")
    print(f"  TextureWidth: {tex_w}")
    print(f"  TextureHeight: {tex_h}")
    print(f"  ImageWidth:   {img_w}")
    print(f"  ImageHeight:  {img_h}")
    print(f"  UnkInt0:      {unk0} (0x{unk0:08x})")
    pos += header_size
    
    # Now look for TEXB0004 or TEXB0003 magic
    # It might have a null byte before it
    if pos < len(data) and data[pos] == 0:
        print(f"[0x{pos:04x}] Null byte before TEXB")
        pos += 1
    
    if pos + 8 > len(data):
        print(f"Too small for TEXB magic at pos {pos}")
        remaining = data[pos:]
        print(f"  Remaining bytes ({len(remaining)}): {remaining[:64].hex()}")
        return
    
    magic3 = data[pos:pos+8].decode('ascii', errors='replace')
    print(f"[0x{pos:04x}] Magic3: {magic3!r}")
    pos += 8
    
    if pos < len(data) and data[pos] == 0:
        print(f"[0x{pos:04x}] Null byte after TEXB magic")
        pos += 1
    
    # TEXB data: mipmap_count(u32), then per-mipmap: decompressed_size(u32), compressed_data
    if pos + 4 > len(data):
        print("Too small for mipmap count")
        return
    mipmap_count = struct.unpack_from('<I', data, pos)[0]
    print(f"[0x{pos:04x}] Mipmap count: {mipmap_count}")
    pos += 4
    
    for i in range(min(mipmap_count, 5)):  # limit to 5 mipmaps for display
        if pos + 4 > len(data):
            print(f"  Mipmap {i}: truncated")
            break
        # Check if there's a format byte or size field
        # Looking at the hex: 01 00 00 00 ff ff ff ff 00...
        # 01 00 00 00 might be the mipmap format
        # ff ff ff ff might be the decompressed size (0xFFFFFFFF = uncompressed?)
        mipmap_fmt = struct.unpack_from('<I', data, pos)[0]
        print(f"[0x{pos:04x}] Mipmap {i}:")
        print(f"  Format/Count: {mipmap_fmt} (0x{mipmap_fmt:08x})")
        pos += 4
        
        if pos + 4 > len(data):
            break
        decomp_size = struct.unpack_from('<I', data, pos)[0]
        print(f"  DecompressedSize: {decomp_size} (0x{decomp_size:08x})")
        pos += 4
        
        # The actual data follows — its size depends on the format
        # For uncompressed RGBA: width * height * 4
        # For DXT1: width * height / 2
        # For DXT5: width * height
        # For LZ4 compressed: the compressed size IS the decompressed_size? Or is there a separate field?
        
        # Let's just show the first 32 bytes of the mipmap data
        remaining = len(data) - pos
        show_bytes = min(32, remaining)
        print(f"  Data (first {show_bytes} bytes): {data[pos:pos+show_bytes].hex()}")
        
        # Try to figure out the data size
        # For format 5 (which might be RGBA8888): data_size = img_w * img_h * 4
        expected_rgba = img_w * img_h * 4
        print(f"  Expected RGBA size: {expected_rgba} ({img_w}x{img_h}x4)")
        
        # Check if the decompressed_size matches expected_rgba
        if decomp_size == 0xFFFFFFFF:
            print(f"  → Size is 0xFFFFFFFF, might mean uncompressed")
            # The actual data size might be right after
            if pos + 4 <= len(data):
                actual_size = struct.unpack_from('<I', data, pos)[0]
                print(f"  Actual data size: {actual_size}")
                pos += 4
                pos += actual_size  # skip the data
        elif decomp_size > 0 and decomp_size < len(data):
            print(f"  → Data size = {decomp_size}")
            pos += decomp_size
        else:
            print(f"  → Unknown data size, skipping to end")
            pos = len(data)
            break
    
    print(f"\n[0x{pos:04x}] End of parsed data (total {len(data)} bytes)")
    if pos < len(data):
        remaining = len(data) - pos
        print(f"  Remaining: {remaining} bytes")
        # Check for TEXS0003 magic
        if pos + 8 <= len(data):
            magic4 = data[pos:pos+8].decode('ascii', errors='replace')
            print(f"  Next magic: {magic4!r}")
            if magic4.startswith("TEXS"):
                print("  → TEXS section found (streaming/mipmap data)")


def main():
    # Extract the .tex file from the real .mpkg
    mpkg_path = '/home/z/my-project/upload/2279314480.mpkg'
    with open(mpkg_path, 'rb') as f:
        data = f.read()
    
    # Parse mpkg header to find .tex entries
    header_marker = struct.unpack_from('<I', data, 0)[0]
    magic = data[4:12].decode('ascii', errors='replace')
    entry_count = struct.unpack_from('<I', data, 12)[0]
    
    print(f"=== {mpkg_path} ===")
    print(f"Header marker: {header_marker}")
    print(f"Magic: {magic!r}")
    print(f"Entry count: {entry_count}")
    
    pos = 16
    entries = []
    for i in range(entry_count):
        name_len = struct.unpack_from('<I', data, pos)[0]; pos += 4
        name = data[pos:pos+name_len].decode('utf-8'); pos += name_len
        offset = struct.unpack_from('<I', data, pos)[0]
        size = struct.unpack_from('<I', data, pos+4)[0]; pos += 8
        entries.append((name, offset, size))
    
    data_start = pos
    print(f"Data section starts at: {pos}")
    
    # Find and parse .tex files
    for name, offset, size in entries:
        if name.endswith('.tex'):
            tex_data = data[data_start+offset:data_start+offset+size]
            parse_tex(tex_data, name)
            print("\n" + "="*70)

if __name__ == '__main__':
    main()
