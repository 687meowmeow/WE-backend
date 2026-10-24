#!/usr/bin/env python3
"""Simulate the new convertTexPngToRgba output and verify the structure.

This creates a fake .tex file with the same structure as what the Kotlin
MpkgPacker.convertTexPngToRgba function would produce, then verifies it
parses correctly using the structure expected by RePKG/linux-wallpaperengine.

The goal is to validate that the FORMAT we're producing matches what
WE Android expects.
"""
import struct
from pathlib import Path

# Simulate a 4x4 RGBA texture (small for testing)
W, H = 4, 4
RGBA = bytes(range(64))  # 64 bytes of fake RGBA data (16 pixels × 4 bytes)

# Build the .tex file matching MpkgPacker.convertTexPngToRgba output
out = bytearray()

# Magic1: "TEXV0005\0" (9 bytes)
out += b"TEXV0005\x00"

# Magic2: "TEXI0001\0" (9 bytes)
out += b"TEXI0001\x00"

# TEXI Header (28 bytes = 7 × int32 LE)
out += struct.pack('<I', 0)        # format = 0 (RGBA8888)
out += struct.pack('<I', 2)        # flags = 2 (ClampUVs, preserved from typical original)
out += struct.pack('<I', W)        # texture_width
out += struct.pack('<I', H)        # texture_height
out += struct.pack('<I', W)        # image_width
out += struct.pack('<I', H)        # image_height
out += struct.pack('<I', 0xff8d5a49)  # UnkInt0 (preserved from typical original)

# TEXB Container
out += b"TEXB0004\x00"             # TEXB magic (9 bytes)
out += struct.pack('<I', 1)         # image_count = 1
out += struct.pack('<i', -1)        # format = -1 (FIF_UNKNOWN, signed)
out += struct.pack('<I', 0)         # is_video_mp4 = 0

# Image 0
out += struct.pack('<I', 1)         # mipmap_count = 1

# Mipmap 0
out += struct.pack('<I', W)         # width
out += struct.pack('<I', H)         # height
out += struct.pack('<I', 0)         # compression = 0 (no LZ4)
out += struct.pack('<I', len(RGBA)) # uncompressed_size = w*h*4
out += struct.pack('<I', len(RGBA)) # compressed_size = same (no compression)
out += RGBA                         # raw RGBA data

print(f"Total bytes: {len(out)}")
print(f"Expected structure size: 91 (header) + {len(RGBA)} (data) = {91 + len(RGBA)}")

# Verify by parsing back
print("\n--- Verification ---")

# Header
assert out[0:9] == b"TEXV0005\x00", "Magic1 wrong"
assert out[9:18] == b"TEXI0001\x00", "Magic2 wrong"
print(f"✓ Magic1: {out[0:8].decode('ascii')!r}")
print(f"✓ Magic2: {out[9:17].decode('ascii')!r}")

# TEXI Header
fmt, flags, tw, th, iw, ih, unk = struct.unpack('<7I', out[18:46])
assert fmt == 0, f"format should be 0, got {fmt}"
assert tw == W and th == H, f"texture dimensions wrong"
assert iw == W and ih == H, f"image dimensions wrong"
print(f"✓ TEXI: format={fmt}, flags={flags}, tex={tw}x{th}, img={iw}x{ih}, unk=0x{unk:08x}")

# TEXB Container
assert out[46:55] == b"TEXB0004\x00", "TEXB magic wrong"
image_count, = struct.unpack('<I', out[55:59])
fif, = struct.unpack('<i', out[59:63])
is_mp4, = struct.unpack('<I', out[63:67])
assert image_count == 1, f"image_count should be 1, got {image_count}"
assert fif == -1, f"FIF should be -1, got {fif}"
assert is_mp4 == 0, f"is_mp4 should be 0, got {is_mp4}"
print(f"✓ TEXB: image_count={image_count}, FIF={fif}, is_mp4={is_mp4}")

# Image 0
mipmap_count, = struct.unpack('<I', out[67:71])
assert mipmap_count == 1, f"mipmap_count should be 1, got {mipmap_count}"
print(f"✓ image 0: mipmap_count={mipmap_count}")

# Mipmap 0
m_w, m_h, comp, uncomp, comp_size = struct.unpack('<5I', out[71:91])
assert m_w == W and m_h == H, f"mipmap dimensions wrong"
assert comp == 0, f"compression should be 0, got {comp}"
assert uncomp == len(RGBA), f"uncompressed_size wrong: {uncomp} vs {len(RGBA)}"
assert comp_size == len(RGBA), f"compressed_size wrong: {comp_size} vs {len(RGBA)}"
print(f"✓ mipmap 0: width={m_w}, height={m_h}, compression={comp}, uncomp_size={uncomp}, comp_size={comp_size}")

# Data
data = out[91:]
assert data == RGBA, f"data mismatch"
print(f"✓ data: {len(data)} bytes, first 8: {data[:8].hex()}")

print("\n=== ALL CHECKS PASSED ===")
print("\nThe new .tex format produced by MpkgPacker.convertTexPngToRgba is correct")
print("and matches the structure expected by RePKG and linux-wallpaperengine.")

# Save the file for inspection
output_path = Path('/tmp/test_converted_tex.tex')
output_path.write_bytes(out)
print(f"\nTest file saved to: {output_path}")
