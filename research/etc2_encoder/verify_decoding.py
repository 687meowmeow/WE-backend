"""Verify our understanding of the .tex format by decoding the real .tex file
and comparing with the original image (preview.jpg from the same .mpkg)."""
import struct
import os
import sys
import lz4.block
from PIL import Image
import texture2ddecoder

def parse_tex(path):
    """Parse a .tex file: return (format, width, height, decompressed_bytes)."""
    with open(path, 'rb') as f:
        data = f.read()
    
    # TEXV0005\0
    assert data[0:9] == b'TEXV0005\x00', f"Bad TEXV0005: {data[0:9]!r}"
    # TEXI0001\0
    assert data[9:18] == b'TEXI0001\x00', f"Bad TEXI0001: {data[9:18]!r}"
    
    # TEXI header: 7x u32 (format, flags, tex_w, tex_h, img_w, img_h, color)
    fmt, flags, tex_w, tex_h, img_w, img_h, color = struct.unpack_from('<7I', data, 18)
    print(f"  Format={fmt}, Flags={flags}, TexSize={tex_w}x{tex_h}, ImgSize={img_w}x{img_h}, Color=0x{color:08x}")
    
    # TEXB0004\0
    texb_pos = data.find(b'TEXB0004\x00')
    assert texb_pos >= 0, "TEXB0004 not found"
    pos = texb_pos + 9  # skip "TEXB0004\0"
    
    # TEXB container: imageCount, format, isVideoMp4
    image_count, fmt2, is_video = struct.unpack_from('<3I', data, pos)
    pos += 12
    print(f"  ImageCount={image_count}, Format={fmt2}, IsVideo={is_video}")
    
    # Then per mipmap (V3): mipmap_count, then per-mipmap:
    # Width, Height, IsLZ4, DecompressedBytesCount, then byteCount + data
    mipmap_count = struct.unpack_from('<I', data, pos)[0]
    pos += 4
    print(f"  MipmapCount={mipmap_count}")
    
    for i in range(mipmap_count):
        width, height, is_lz4, decomp_size = struct.unpack_from('<4I', data, pos)
        pos += 16
        byte_count = struct.unpack_from('<I', data, pos)[0]
        pos += 4
        compressed_data = data[pos:pos+byte_count]
        pos += byte_count
        print(f"  Mipmap[{i}]: {width}x{height}, LZ4={is_lz4}, DecompSize={decomp_size}, CompSize={byte_count}")
        
        if is_lz4:
            decompressed = lz4.block.decompress(compressed_data, uncompressed_size=decomp_size)
        else:
            decompressed = compressed_data
        
        return fmt, width, height, decompressed
    
    return None

# Try to decode the real .tex file
tex_file = '/home/z/my-project/research/tex_analysis/extracted/materials_69721567_p0.tex'
print(f"=== Decoding {tex_file} ===")
fmt, w, h, decomp = parse_tex(tex_file)
print(f"  Decompressed size: {len(decomp)}")

# Expected: format=5 (ETC2_RGBA8), width=948, height=1440
# Block count = (948/4) * (1440/4) = 237 * 360 = 85320 blocks
# Each ETC2_RGBA8 block = 16 bytes
# Total = 85320 * 16 = 1,365,120 ✓

# Decode ETC2_RGBA8 -> RGBA8888
# texture2ddecoder.decode_etc2a8(data, width, height) returns bytes
out = bytearray(w * h * 4)
texture2ddecoder.decode_etc2a8(decomp, w, h, out)
print(f"  Decoded RGBA size: {len(out)}")

# Save as PNG to compare with preview.jpg
img = Image.frombytes('RGBA', (w, h), bytes(out))
out_png = '/home/z/my-project/research/etc2_encoder/decoded_69721567_p0.png'
img.save(out_png)
print(f"  Saved decoded image: {out_png}")

# Also load preview.jpg for comparison
src_mpkg = '/home/z/my-project/upload/2279314480.mpkg'
# Read entries from src_mpkg to find preview.jpg
with open(src_mpkg, 'rb') as f:
    mpkg = f.read()
pos = 16
entries = []
for _ in range(struct.unpack_from('<I', mpkg, 12)[0]):
    nl = struct.unpack_from('<I', mpkg, pos)[0]; pos += 4
    name = mpkg[pos:pos+nl].decode('utf-8'); pos += nl
    off, sz = struct.unpack_from('<2I', mpkg, pos); pos += 8
    entries.append((name, off, sz))
data_start = pos
preview_data = None
for n, o, s in entries:
    if n == 'preview.jpg':
        preview_data = mpkg[data_start+o : data_start+o+s]
        break

if preview_data:
    import io
    preview_img = Image.open(io.BytesIO(preview_data))
    print(f"  Preview image size: {preview_img.size}")
    preview_img.save('/home/z/my-project/research/etc2_encoder/preview_69721567_p0.png')

print("\n=== Now compare the images ===")
print(f"  decoded image: {w}x{h}")
print(f"  preview.jpg: {preview_img.size if preview_data else 'N/A'}")
print(f"  (the texture may differ from preview if it has transparency or padding)")
