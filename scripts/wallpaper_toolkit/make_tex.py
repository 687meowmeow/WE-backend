#!/usr/bin/env python3
"""
Build a Wallpaper Engine .tex file from any JPEG/PNG image.

The .tex format (verified from real samples + repkg source):
  [TEXV0005\0]            # 9 bytes - file magic
  [TEXI0001\0]            # 9 bytes - header chunk
  [7x u32 LE]            # 28 bytes - format(5=ETC2_RGBA8), flags(2),
                          #             tex_width, tex_height,
                          #             img_width, img_height, color
  [TEXB0004\0]            # 9 bytes - image container chunk
  [3x u32 LE]            # 12 bytes - imageCount(1), format(-1), isVideoMp4(0)
  [1x u32 LE]            #  4 bytes - mipmap_count(1)
  [4x u32 LE]            # 16 bytes - width, height, IsLZ4(1), DecompressedSize
  [1x u32 LE]            #  4 bytes - byteCount (compressed size)
  [byte[byteCount]]       # LZ4-compressed ETC2_RGBA8 pixel data

Total header before pixel data: 9+9+28+9+12+4+16+4 = 91 bytes
"""
import argparse
import io
import os
import struct
import subprocess
import sys

import lz4.block
from PIL import Image

ETC_TOOL = '/home/z/my-project/research/etc2_encoder/etc2comp/build/EtcTool/EtcTool'

# TexFormat values (from repkg + our analysis)
TEX_FORMAT_ETC2_RGBA8 = 5  # mobile-optimized; matches real .tex files

# TexFlags values (from repkg TexFlags.cs)
TEX_FLAGS_NONE = 0
TEX_FLAGS_NO_INTERPOLATION = 1
TEX_FLAGS_CLAMP_UVS = 2  # matches the "2" we see in real .tex files


def png_to_etc2_rgba8(png_path, output_ktx, jobs=4, effort=20):
    """Encode a PNG to ETC2_RGBA8 KTX using Google's etc2comp.

    Args:
        png_path: path to source PNG (RGBA, dimensions multiple of 4).
        output_ktx: path to write KTX file.
        jobs: thread count (1-8).
        effort: encoding effort 0-100 (higher = better quality, slower).

    Returns:
        subprocess.CompletedProcess
    """
    cmd = [
        ETC_TOOL, png_path,
        '-format', 'RGBA8',
        '-output', output_ktx,
        '-j', str(jobs),
        '-effort', str(effort),
    ]
    return subprocess.run(cmd, capture_output=True, text=True, timeout=600)


def parse_ktx(ktx_path):
    """Extract raw ETC2_RGBA8 bytes from a KTX file.

    KTX layout:
      12 bytes: magic (KTX 11 \\x1A\\n)
      4 bytes: endianness (0x04030201 LE)
      48 bytes: header fields (gl_type..bytes_of_key_value_data)
      kvd_bytes: key/value data, padded to 4-byte alignment
      Per mipmap: 4 bytes image_size + image_data + padding to 4-byte alignment

    Returns: dict with width, height, internal_format, list of (mipmap_bytes)
    """
    with open(ktx_path, 'rb') as f:
        data = f.read()

    assert data[:12] == bytes.fromhex('ab4b5458203131bb0d0a1a0a'), f"Bad KTX magic: {data[:12].hex()}"

    endianness, gl_type, gl_type_size, gl_format, gl_internal_format = struct.unpack_from('<5I', data, 12)
    gl_base_level, width, height, depth = struct.unpack_from('<4I', data, 32)
    num_array, num_mips, kvd_bytes = struct.unpack_from('<3I', data, 48)

    pos = 60 + kvd_bytes
    if kvd_bytes % 4:
        pos += 4 - (kvd_bytes % 4)

    mips = []
    for _ in range(num_mips):
        img_size = struct.unpack_from('<I', data, pos)[0]
        pos += 4
        mips.append(data[pos:pos + img_size])
        pos += img_size
        if img_size % 4:
            pos += 4 - (img_size % 4)

    return {
        'width': width,
        'height': height,
        'internal_format': gl_internal_format,
        'mips': mips,
    }


def compute_average_rgba(pixels, w, h):
    """Compute the average RGBA color of all pixels in the image (0xffAABBGGRR)."""
    total_r = total_g = total_b = total_a = 0
    count = 0
    # Sample every Nth pixel for performance
    step = max(1, (w * h) // 4096)
    for i in range(0, len(pixels), 4 * step):
        r, g, b, a = pixels[i], pixels[i+1], pixels[i+2], pixels[i+3]
        if a > 0:  # only count non-transparent pixels
            total_r += r
            total_g += g
            total_b += b
            total_a += a
            count += 1
    if count == 0:
        return 0xff000000  # black with alpha 255
    avg_r = total_r // count
    avg_g = total_g // count
    avg_b = total_b // count
    avg_a = total_a // count
    # Pack as LE u32 = 0xAA BB GG RR
    return (avg_a << 24) | (avg_b << 16) | (avg_g << 8) | avg_r


def build_tex(image_path, output_path, jobs=4, effort=20):
    """Build a Wallpaper Engine .tex file from any JPEG/PNG.

    Args:
        image_path: source image (JPEG, PNG, etc.)
        output_path: where to write the .tex file
        jobs: ETC2 encoding threads
        effort: ETC2 encoding effort (0-100)

    Returns:
        dict with: tex_size, tex_width, tex_height, img_width, img_height, etc2_bytes, lz4_bytes
    """
    # Step 1: load image
    img = Image.open(image_path)
    img = img.convert('RGBA')
    img_w, img_h = img.size

    # Step 2: pad to multiple of 4 (ETC2 works on 4x4 blocks)
    tex_w = (img_w + 3) & ~3
    tex_h = (img_h + 3) & ~3
    if tex_w != img_w or tex_h != img_h:
        padded = Image.new('RGBA', (tex_w, tex_h), (0, 0, 0, 0))
        padded.paste(img, (0, 0))
        img = padded

    # Save padded image to PNG for EtcTool input
    png_path = output_path + '.tmp.png'
    img.save(png_path, format='PNG')

    # Step 3: compute average color (used in TEXI.color field)
    pixels = img.tobytes()
    avg_color = compute_average_rgba(pixels, tex_w, tex_h)

    # Step 4: encode to ETC2_RGBA8 via EtcTool
    ktx_path = output_path + '.tmp.ktx'
    try:
        result = png_to_etc2_rgba8(png_path, ktx_path, jobs=jobs, effort=effort)
        if result.returncode != 0:
            raise RuntimeError(f"EtcTool failed: {result.stderr}")
    finally:
        if os.path.exists(png_path):
            os.remove(png_path)

    # Step 5: parse KTX to get raw ETC2 bytes
    try:
        ktx = parse_ktx(ktx_path)
    finally:
        if os.path.exists(ktx_path):
            os.remove(ktx_path)

    etc2_bytes = ktx['mips'][0]
    etc2_size = len(etc2_bytes)
    expected_size = (tex_w // 4) * (tex_h // 4) * 16  # 16 bytes per 4x4 block
    if etc2_size != expected_size:
        print(f"WARNING: ETC2 size mismatch! Got {etc2_size}, expected {expected_size}", file=sys.stderr)

    # Step 6: LZ4 compress the ETC2 bytes
    compressed = lz4.block.compress(etc2_bytes, store_size=False)
    compressed_size = len(compressed)

    # Step 7: build the .tex file
    out = bytearray()

    # TEXV0005 + null
    out += b'TEXV0005\x00'

    # TEXI0001 + null
    out += b'TEXI0001\x00'

    # TEXI payload: 7x u32 LE
    # format=5 (ETC2_RGBA8), flags=2 (ClampUVs), tex_w, tex_h, img_w, img_h, color
    out += struct.pack('<7I',
        TEX_FORMAT_ETC2_RGBA8,
        TEX_FLAGS_CLAMP_UVS,
        tex_w, tex_h,
        img_w, img_h,
        avg_color,
    )

    # TEXB0004 + null
    out += b'TEXB0004\x00'

    # TEXB container: imageCount=1, format=-1 (FIF_UNKNOWN), isVideoMp4=0
    out += struct.pack('<3I', 1, 0xffffffff, 0)

    # mipmap count = 1
    out += struct.pack('<I', 1)

    # mipmap entry (V3 format): width, height, IsLZ4=1, DecompressedSize, byteCount, data
    out += struct.pack('<4I', tex_w, tex_h, 1, etc2_size)
    out += struct.pack('<I', compressed_size)
    out += compressed

    # Write to file
    with open(output_path, 'wb') as f:
        f.write(out)

    return {
        'tex_size': len(out),
        'tex_width': tex_w,
        'tex_height': tex_h,
        'img_width': img_w,
        'img_height': img_h,
        'etc2_bytes': etc2_size,
        'lz4_bytes': compressed_size,
        'avg_color': avg_color,
    }


def main():
    parser = argparse.ArgumentParser(description='Build a .tex file from a JPEG/PNG')
    parser.add_argument('input', help='Input image (JPEG, PNG, etc.)')
    parser.add_argument('output', help='Output .tex file path')
    parser.add_argument('--jobs', '-j', type=int, default=4, help='EtcTool threads')
    parser.add_argument('--effort', '-e', type=int, default=20, help='EtcTool effort (0-100)')
    args = parser.parse_args()

    if not os.path.exists(ETC_TOOL):
        print(f"ERROR: EtcTool not found at {ETC_TOOL}", file=sys.stderr)
        print("Please build etc2comp first: https://github.com/google/etc2comp", file=sys.stderr)
        sys.exit(1)

    print(f"Building .tex from {args.input} → {args.output}")
    result = build_tex(args.input, args.output, jobs=args.jobs, effort=args.effort)
    print(f"\nResult:")
    print(f"  Tex file: {result['tex_size']:,} bytes")
    print(f"  Texture dims: {result['tex_width']}x{result['tex_height']} (padded from {result['img_width']}x{result['img_height']})")
    print(f"  ETC2_RGBA8 data: {result['etc2_bytes']:,} bytes")
    print(f"  LZ4 compressed: {result['lz4_bytes']:,} bytes (ratio: {result['lz4_bytes']/result['etc2_bytes']*100:.1f}%)")
    print(f"  Average color: 0x{result['avg_color']:08x}")


if __name__ == '__main__':
    main()
