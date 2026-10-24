#!/usr/bin/env python3
"""Verify that the new .tex format produced by MpkgPacker is correct.

This script reads a .tex file produced by the new convertTexPngToRgba and
verifies that its structure matches the format expected by RePKG and
linux-wallpaperengine.

Expected structure:
  Header (18 bytes):
    "TEXV0005\0" (9 bytes magic1)
    "TEXI0001\0" (9 bytes magic2)
  TEXI Header (28 bytes = 7 × int32 LE):
    format (int) — should be 0 (RGBA8888) for converted .tex
    flags (int) — preserved from original
    texture_width (int) — should be PNG width
    texture_height (int) — should be PNG height
    image_width (int) — should be PNG width
    image_height (int) — should be PNG height
    UnkInt0 (int) — preserved from original
  TEXB Container (variable):
    "TEXB0004\0" (9 bytes magic)
    image_count (int) — should be 1
    format (int) — should be -1 (FIF_UNKNOWN)
    is_video_mp4 (int) — should be 0
    For each image (just 1):
      mipmap_count (int) — should be 1
      For each mipmap (just 1, v2/v3 format):
        width (int) — should match PNG width
        height (int) — should match PNG height
        compression (int) — should be 0 (no LZ4)
        uncompressed_size (int) — should be w*h*4
        compressed_size (int) — should be w*h*4
        data (w*h*4 bytes of RGBA pixels)
"""
import struct
import sys
from pathlib import Path

def verify_tex(tex_path):
    data = Path(tex_path).read_bytes()
    print(f"\n=== {tex_path} ({len(data):,} bytes) ===")
    
    if len(data) < 91:
        print(f"  ERROR: file too small ({len(data)} bytes, need at least 91)")
        return False
    
    # Magic1
    magic1 = data[0:8].decode('ascii', errors='replace')
    null1 = data[8]
    print(f"  magic1: {magic1!r} (null={null1})")
    if magic1 != "TEXV0005" or null1 != 0:
        print(f"  ERROR: bad magic1")
        return False
    
    # Magic2
    magic2 = data[9:17].decode('ascii', errors='replace')
    null2 = data[17]
    print(f"  magic2: {magic2!r} (null={null2})")
    if magic2 != "TEXI0001" or null2 != 0:
        print(f"  ERROR: bad magic2")
        return False
    
    # TEXI Header
    fmt = struct.unpack('<I', data[18:22])[0]
    flags = struct.unpack('<I', data[22:26])[0]
    tex_w = struct.unpack('<I', data[26:30])[0]
    tex_h = struct.unpack('<I', data[30:34])[0]
    img_w = struct.unpack('<I', data[34:38])[0]
    img_h = struct.unpack('<I', data[38:42])[0]
    unk0 = struct.unpack('<I', data[42:46])[0]
    print(f"  TEXI header:")
    print(f"    format={fmt} (expect 0=RGBA8888)")
    print(f"    flags={flags}")
    print(f"    texture_width={tex_w}")
    print(f"    texture_height={tex_h}")
    print(f"    image_width={img_w}")
    print(f"    image_height={img_h}")
    print(f"    UnkInt0=0x{unk0:08x}")
    
    if fmt != 0:
        print(f"  ERROR: format should be 0 (RGBA8888), got {fmt}")
        return False
    
    # TEXB Container
    texb_magic = data[46:54].decode('ascii', errors='replace')
    texb_null = data[54]
    print(f"  TEXB magic: {texb_magic!r} (null={texb_null})")
    if texb_magic != "TEXB0004" or texb_null != 0:
        print(f"  ERROR: bad TEXB magic")
        return False
    
    image_count = struct.unpack('<I', data[55:59])[0]
    fif_format = struct.unpack('<i', data[59:63])[0]  # signed
    is_mp4 = struct.unpack('<I', data[63:67])[0]
    print(f"  image_count={image_count} (expect 1)")
    print(f"  format(FIF)={fif_format} (expect -1=FIF_UNKNOWN)")
    print(f"  is_video_mp4={is_mp4} (expect 0)")
    
    if image_count != 1:
        print(f"  ERROR: image_count should be 1")
        return False
    if fif_format != -1:
        print(f"  ERROR: FIF should be -1 (FIF_UNKNOWN), got {fif_format}")
        return False
    if is_mp4 != 0:
        print(f"  ERROR: is_video_mp4 should be 0")
        return False
    
    # Image 0
    mipmap_count = struct.unpack('<I', data[67:71])[0]
    print(f"  mipmap_count={mipmap_count} (expect 1)")
    if mipmap_count != 1:
        print(f"  ERROR: mipmap_count should be 1")
        return False
    
    # Mipmap 0
    m_w = struct.unpack('<I', data[71:75])[0]
    m_h = struct.unpack('<I', data[75:79])[0]
    compression = struct.unpack('<I', data[79:83])[0]
    uncompressed_size = struct.unpack('<I', data[83:87])[0]
    compressed_size = struct.unpack('<I', data[87:91])[0]
    print(f"  mipmap[0]:")
    print(f"    width={m_w} (expect matches PNG width)")
    print(f"    height={m_h} (expect matches PNG height)")
    print(f"    compression={compression} (expect 0=no LZ4)")
    print(f"    uncompressed_size={uncompressed_size} (expect w*h*4={m_w*m_h*4})")
    print(f"    compressed_size={compressed_size} (expect same as uncompressed)")
    
    if m_w != tex_w or m_h != tex_h:
        print(f"  ERROR: mipmap dimensions don't match texture dimensions")
        return False
    if compression != 0:
        print(f"  ERROR: compression should be 0, got {compression}")
        return False
    expected_data_size = m_w * m_h * 4
    if uncompressed_size != expected_data_size:
        print(f"  ERROR: uncompressed_size={uncompressed_size} != w*h*4={expected_data_size}")
        return False
    if compressed_size != expected_data_size:
        print(f"  ERROR: compressed_size={compressed_size} != w*h*4={expected_data_size}")
        return False
    
    # Check data length
    actual_data_len = len(data) - 91
    if actual_data_len != expected_data_size:
        print(f"  ERROR: file has {actual_data_len} bytes of data, expected {expected_data_size}")
        return False
    
    # Check first 16 bytes of data (should be RGBA bytes — not PNG magic)
    first_16 = data[91:107]
    if first_16[0:4] == b'\x89PNG':
        print(f"  ERROR: first 4 bytes are PNG magic — conversion didn't work!")
        return False
    
    print(f"  first 16 bytes of data: {first_16.hex()}")
    print(f"\n  ✓ All checks passed!")
    return True

if __name__ == '__main__':
    if len(sys.argv) < 2:
        print("Usage: verify_new_tex.py <tex_file>")
        sys.exit(1)
    
    success = verify_tex(sys.argv[1])
    sys.exit(0 if success else 1)
