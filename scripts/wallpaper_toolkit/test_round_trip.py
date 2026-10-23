"""Round-trip test: PNG → ETC2_RGBA8 → decode back to PNG and verify."""
import subprocess
import struct
import os
import lz4.block
from PIL import Image
import texture2ddecoder

ETC_TOOL = '/home/z/my-project/research/etc2_encoder/etc2comp/build/EtcTool/EtcTool'

def png_to_etc2_rgba8(png_path, output_ktx):
    """Encode a PNG to ETC2_RGBA8 KTX."""
    cmd = [
        ETC_TOOL, png_path,
        '-format', 'RGBA8',
        '-output', output_ktx,
        '-j', '1',
        '-effort', '0',  # fastest
    ]
    result = subprocess.run(cmd, capture_output=True, text=True, timeout=120)
    if result.returncode != 0:
        raise RuntimeError(f"EtcTool failed: {result.stderr}")
    return result.stdout

def parse_ktx(ktx_path):
    """Extract raw ETC2_RGBA8 bytes from KTX file."""
    with open(ktx_path, 'rb') as f:
        data = f.read()
    
    assert data[:12] == bytes.fromhex('ab4b5458203131bb0d0a1a0a'), "Bad KTX magic"
    endianness, gl_type, gl_type_size, gl_format, gl_internal_format = struct.unpack_from('<5I', data, 12)
    gl_base_level, width, height, depth = struct.unpack_from('<4I', data, 32)
    num_array, num_mips, kvd_bytes = struct.unpack_from('<3I', data, 48)
    
    pos = 64 + kvd_bytes
    if kvd_bytes % 4:
        pos += 4 - (kvd_bytes % 4)
    
    mips = []
    for _ in range(num_mips):
        img_size = struct.unpack_from('<I', data, pos)[0]; pos += 4
        mips.append(data[pos:pos+img_size])
        pos += img_size
        if img_size % 4:
            pos += 4 - (img_size % 4)
    
    return {
        'width': width,
        'height': height,
        'internal_format': gl_internal_format,
        'mips': mips,
    }

# Test 1: Round-trip with a simple image
print("=== Test 1: 8x8 image round-trip ===")
img = Image.new('RGBA', (8, 8))
for y in range(8):
    for x in range(8):
        img.putpixel((x, y), (x*32, y*32, 128, 255))
img.save('/tmp/test_8x8.png')

png_to_etc2_rgba8('/tmp/test_8x8.png', '/tmp/test_8x8.ktx')
ktx = parse_ktx('/tmp/test_8x8.ktx')
print(f"KTX: {ktx['width']}x{ktx['height']}, internal_format=0x{ktx['internal_format']:x}")
print(f"  ETC2_RGBA8 bytes: {len(ktx['mips'][0])} (expected {2*2*16}=64)")
print(f"  First 16 bytes: {ktx['mips'][0][:16].hex()}")

# Decode it back
decoded = texture2ddecoder.decode_etc2a8(ktx['mips'][0], ktx['width'], ktx['height'])
out_img = Image.frombytes('RGBA', (ktx['width'], ktx['height']), decoded)
out_img.save('/tmp/test_8x8_decoded.png')
print(f"  Decoded back to PNG: /tmp/test_8x8_decoded.png")

# Test 2: Encode a real preview.jpg and compare
print("\n=== Test 2: Encode preview.jpg (871x871, needs padding to 872x872) ===")
src_mpkg = '/home/z/my-project/upload/2279314480.mpkg'
import io
with open(src_mpkg, 'rb') as f:
    data = f.read()
entry_count = struct.unpack_from('<I', data, 12)[0]
pos = 16
entries = []
for _ in range(entry_count):
    nl = struct.unpack_from('<I', data, pos)[0]; pos += 4
    name = data[pos:pos+nl].decode('utf-8'); pos += nl
    off, sz = struct.unpack_from('<2I', data, pos); pos += 8
    entries.append((name, off, sz))
data_start = pos

preview = None
for n, o, s in entries:
    if n == 'preview.jpg':
        preview = data[data_start+o : data_start+o+s]
        break

img = Image.open(io.BytesIO(preview)).convert('RGBA')
print(f"  Original size: {img.size}")

# Pad to multiple of 4
w, h = img.size
pad_w = (w + 3) & ~3
pad_h = (h + 3) & ~3
if pad_w != w or pad_h != h:
    padded = Image.new('RGBA', (pad_w, pad_h), (0, 0, 0, 0))
    padded.paste(img, (0, 0))
    img = padded
print(f"  Padded size: {img.size}")

img.save('/tmp/preview_padded.png')
png_to_etc2_rgba8('/tmp/preview_padded.png', '/tmp/preview.ktx')
ktx = parse_ktx('/tmp/preview.ktx')
print(f"  Encoded: {ktx['width']}x{ktx['height']}")
print(f"  ETC2 bytes: {len(ktx['mips'][0])} (expected {(ktx['width']//4)*(ktx['height']//4)*16})")

# Decode it back and save
decoded = texture2ddecoder.decode_etc2a8(ktx['mips'][0], ktx['width'], ktx['height'])
out_img = Image.frombytes('RGBA', (ktx['width'], ktx['height']), decoded)
out_img.save('/tmp/preview_decoded.png')
print(f"  Decoded: /tmp/preview_decoded.png")

# Also compress with LZ4
compressed = lz4.block.compress(ktx['mips'][0])
print(f"  LZ4 compressed: {len(compressed)} bytes (ratio: {len(compressed)/len(ktx['mips'][0])*100:.1f}%)")
