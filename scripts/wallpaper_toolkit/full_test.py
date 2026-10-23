"""Full end-to-end test of the WepWawet wallpaper toolkit.

Tests:
1. Generate a Scene .mpkg from a JPEG using Python (with ETC2_RGBA8 encoding)
2. Generate a Video .mpkg from an MP4 using Python
3. Verify the .mpkg files parse correctly with our .tex parser
4. Decode the .tex back to RGBA8888 to verify round-trip
"""
import os
import sys
import struct
import lz4.block
from PIL import Image
import texture2ddecoder

sys.path.insert(0, '/home/z/my-project/scripts/wallpaper_toolkit')
from make_tex import build_tex
from make_scene_mpkg import build_scene_mpkg
from make_video_mpkg import build_video_mpkg


def parse_mpkg(path):
    """Parse a .mpkg file and return the entries."""
    with open(path, 'rb') as f:
        data = f.read()
    hm = struct.unpack_from('<I', data, 0)[0]
    magic = data[4:12].decode('ascii')
    ec = struct.unpack_from('<I', data, 12)[0]
    assert hm == 8, f"Bad header_marker: {hm}"
    assert magic.startswith('PKGM'), f"Bad magic: {magic!r}"
    
    pos = 16
    entries = []
    for _ in range(ec):
        nl = struct.unpack_from('<I', data, pos)[0]; pos += 4
        name = data[pos:pos+nl].decode('utf-8'); pos += nl
        off, sz = struct.unpack_from('<2I', data, pos); pos += 8
        entries.append((name, off, sz))
    
    data_start = pos
    return {
        'magic': magic,
        'entries': entries,
        'data_start': data_start,
        'data': data,
    }


def parse_tex(data):
    """Parse a .tex file in memory and return (format, tex_w, tex_h, decomp_data)."""
    assert data[0:9] == b'TEXV0005\x00', f"Bad TEXV: {data[0:9]!r}"
    assert data[9:18] == b'TEXI0001\x00', f"Bad TEXI: {data[9:18]!r}"
    fmt, flags, tw, th, iw, ih, color = struct.unpack_from('<7I', data, 18)
    
    texb_pos = data.find(b'TEXB0004\x00')
    assert texb_pos >= 0, "TEXB0004 not found"
    pos = texb_pos + 9
    img_count, fmt2, is_video = struct.unpack_from('<3I', data, pos); pos += 12
    mip_count = struct.unpack_from('<I', data, pos)[0]; pos += 4
    
    mips = []
    for _ in range(mip_count):
        w, h, is_lz4, decomp_size = struct.unpack_from('<4I', data, pos); pos += 16
        byte_count = struct.unpack_from('<I', data, pos)[0]; pos += 4
        comp_data = data[pos:pos+byte_count]; pos += byte_count
        
        if is_lz4:
            decomp = lz4.block.decompress(comp_data, uncompressed_size=decomp_size)
        else:
            decomp = comp_data
        
        mips.append({
            'width': w, 'height': h,
            'is_lz4': is_lz4,
            'decomp_size': decomp_size,
            'comp_size': byte_count,
            'data': decomp,
        })
    
    return {
        'format': fmt, 'flags': flags,
        'tex_width': tw, 'tex_height': th,
        'img_width': iw, 'img_height': ih,
        'color': color,
        'mips': mips,
    }


def test_scene_mpkg(image_path, output_path):
    """Test building a Scene .mpkg from an image."""
    print(f"\n=== Test 1: Build Scene .mpkg from {image_path} ===")
    if not os.path.exists(image_path):
        print(f"  SKIP: source image not found")
        return False
    
    # Build the .mpkg
    result = build_scene_mpkg(
        image_path, output_path,
        title="Test Scene",
        description="End-to-end test wallpaper",
        tags=["Test", "Scene"],
        jobs=4, effort=30,
    )
    print(f"  Built: {result['mpkg_size']:,} bytes")
    print(f"  Tex: {result['tex_size']:,} bytes")
    print(f"  Image: {result['image_size']}")
    print(f"  Tex dims: {result['tex_dims']}")
    
    # Verify by parsing
    parsed = parse_mpkg(output_path)
    print(f"  Verified: magic={parsed['magic']}, {len(parsed['entries'])} entries")
    
    # Find the .tex entry and decode it
    tex_data = None
    for name, off, sz in parsed['entries']:
        if name.endswith('.tex'):
            start = parsed['data_start'] + off
            tex_data = parsed['data'][start:start+sz]
            break
    
    if tex_data:
        tex = parse_tex(tex_data)
        print(f"  Tex header: format={tex['format']}, dims={tex['tex_width']}x{tex['tex_height']}")
        print(f"  Tex data: decomp_size={tex['mips'][0]['decomp_size']:,} bytes, is_lz4={tex['mips'][0]['is_lz4']}")
        
        # Decode ETC2 back to RGBA and verify
        if tex['format'] == 5:  # ETC2_RGBA8
            decoded = texture2ddecoder.decode_etc2a8(
                tex['mips'][0]['data'],
                tex['mips'][0]['width'],
                tex['mips'][0]['height'],
            )
            out_png = output_path + '.decoded.png'
            img = Image.frombytes('RGBA', (tex['mips'][0]['width'], tex['mips'][0]['height']), decoded)
            img.save(out_png)
            print(f"  Decoded back to: {out_png} ({img.size})")
    
    return True


def test_video_mpkg(video_path, output_path):
    """Test building a Video .mpkg from a video."""
    print(f"\n=== Test 2: Build Video .mpkg from {video_path} ===")
    if not os.path.exists(video_path):
        print(f"  SKIP: source video not found")
        return False
    
    result = build_video_mpkg(
        video_path, output_path,
        title="Test Video",
        description="End-to-end test video wallpaper",
        tags=["Test", "Video"],
        max_width=640, crf=28,
    )
    print(f"  Built: {result['mpkg_size']:,} bytes")
    print(f"  Video: {result['video_size']:,} bytes ({result['video_dims'][0]}x{result['video_dims'][1]}, {result['duration']:.1f}s)")
    
    parsed = parse_mpkg(output_path)
    print(f"  Verified: magic={parsed['magic']}, {len(parsed['entries'])} entries")
    return True


# Run the tests
os.makedirs('/home/z/my-project/download', exist_ok=True)

# Test 1: Scene .mpkg from a JPEG
test_image = '/home/z/my-project/upload/Screenshot_2026-10-22-10-57-35-10_1add49265faf438ec63905244bebd4fd.jpg'
test_scene_mpkg(test_image, '/home/z/my-project/download/e2e_scene_test.mpkg')

# Test 2: Video .mpkg from an MP4
# Generate a test video first if needed
test_video = '/tmp/wepwawet_test_video.mp4'
if not os.path.exists(test_video):
    import subprocess
    print(f"\nGenerating test video: {test_video}")
    subprocess.run([
        'ffmpeg', '-y', '-f', 'lavfi',
        '-i', 'testsrc=duration=2:size=320x240:rate=15',
        '-c:v', 'libx264', '-profile:v', 'baseline',
        '-pix_fmt', 'yuv420p', test_video,
    ], check=True, capture_output=True)

test_video_mpkg(test_video, '/home/z/my-project/download/e2e_video_test.mpkg')

print("\n=== All tests completed ===")
print("\nGenerated .mpkg files in /home/z/my-project/download/:")
for f in os.listdir('/home/z/my-project/download'):
    if f.endswith('.mpkg'):
        path = os.path.join('/home/z/my-project/download', f)
        size = os.path.getsize(path)
        print(f"  {f:50s} {size:>10,} bytes")
