"""Decompress LZ4 data from .tex files and analyze the pixel format."""
import struct
import os
import lz4.block
from PIL import Image
import io

def parse_and_decompress_tex(path):
    """Parse a .tex file and decompress its pixel data."""
    with open(path, 'rb') as f:
        data = f.read()
    
    print(f"\n=== {os.path.basename(path)} ({len(data)} bytes) ===")
    
    # Skip TEXV0005\0 (9 bytes)
    # Skip TEXI0001\0 (9 bytes)
    # Parse TEXI payload (24 bytes = 6x u32 LE)
    texi_fields = struct.unpack_from('<6I', data, 18)
    fmt, mips, width, height, width2, height2 = texi_fields
    print(f"TEXI: format={fmt}, mips={mips}, width={width}, height={height}, width2={width2}, height2={height2}")
    
    # 4 bytes after TEXI payload (offset 42-45)
    mystery = data[42:46]
    print(f"Mystery bytes after TEXI (4 bytes): {mystery.hex()}")
    
    # TEXB starts at offset 46
    # Skip "TEXB0004\0" (9 bytes) → offset 55
    texb_fields = struct.unpack_from('<10I', data, 55)
    print(f"TEXB fields: {texb_fields}")
    print(f"  unknown1 = {texb_fields[0]}")
    print(f"  unknown2 = 0x{texb_fields[1]:08x}")
    print(f"  unknown3 = {texb_fields[2]}")
    print(f"  unknown4 = {texb_fields[3]}")
    print(f"  width = {texb_fields[4]}")
    print(f"  height = {texb_fields[5]}")
    print(f"  mipmap_count = {texb_fields[6]}")
    print(f"  decompressed_size = {texb_fields[7]}")
    print(f"  compressed_size = {texb_fields[8]}")
    
    # Compressed data starts at offset 55 + 9*4 = 55 + 36 = 91
    data_offset = 91
    compressed_size = texb_fields[8]
    decompressed_size = texb_fields[7]
    
    print(f"\nCompressed data at offset {data_offset}, size {compressed_size}")
    print(f"Decompressed size = {decompressed_size}")
    print(f"width * height = {width * height}")
    print(f"decompressed_size / (width*height) = {decompressed_size / (width*height):.4f}")
    
    # Decompress the data
    compressed_data = data[data_offset:data_offset + compressed_size]
    print(f"Actual compressed data slice size: {len(compressed_data)}")
    print(f"First 16 bytes of compressed: {compressed_data[:16].hex()}")
    
    try:
        decompressed = lz4.block.decompress(compressed_data, uncompressed_size=decompressed_size)
        print(f"\nDecompression SUCCESS! Decompressed size: {len(decompressed)}")
        # Show first 32 bytes
        print(f"First 32 bytes of decompressed: {decompressed[:32].hex()}")
        # Save it
        out_path = path.replace('.tex', '_decompressed.bin')
        with open(out_path, 'wb') as f:
            f.write(decompressed)
        print(f"Saved to: {out_path}")
        
        # Check pixel format - try various interpretations
        # If decompressed_size = width*height, it's 1 byte/pixel
        # For ETC2_RGBA8, that's 8 bytes per 4x4 block = 1 byte/pixel ✓
        # Actually for ETC2_RGBA8: 8 bytes per 4x4 block, 16 pixels per block = 0.5 bytes per pixel
        # For ETC2_RGBA8 (8-bit per pixel? actually ETC2 RGBA8 is 1 byte/pixel as two 8-byte halves per 4x4 block)
        
        # Actually: ETC2_RGBA8 = 16 bytes per 4x4 block (8 bytes RGB + 8 bytes A) = 1 byte/pixel
        
        # Try ETC2_RGBA8 interpretation
        if decompressed_size == width * height:
            print(f"\nFormat appears to be ETC2_RGBA8 (1 byte per pixel = 16 bytes per 4x4 block)")
            # Each 4x4 block = 16 bytes (8 for alpha, 8 for RGB)
            # Block order: left-to-right, top-to-bottom
            
        return decompressed, width, height
    except Exception as e:
        print(f"\nDecompression FAILED: {e}")
        import traceback
        traceback.print_exc()
        return None, None, None

# Test on the real .tex files
import glob
for tex_file in glob.glob('/home/z/my-project/research/tex_analysis/extracted/*.tex'):
    parse_and_decompress_tex(tex_file)
