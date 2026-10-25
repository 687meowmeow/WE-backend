#!/usr/bin/env python3
"""
Build a Wallpaper Engine .mpkg file for Scene-type wallpapers.

The Scene .mpkg includes:
  - project.json (type:"scene", file:"scene.json")
  - scene.json (camera + one fullscreen image quad)
  - models/<name>.json (model definition with autosize + material reference)
  - materials/<name>.json (material using "genericimage2" shader)
  - materials/<name>.tex (ETC2_RGBA8 + LZ4 compressed texture)
  - preview.jpg (preview thumbnail)

This produces a .mpkg that the WE Android app's native SceneLib
can render directly via initScene() — same as a real WE PC Scene wallpaper.
"""
import argparse
import io
import json
import os
import struct
import subprocess
import sys
from pathlib import Path

from PIL import Image

# Import the .tex builder
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, SCRIPT_DIR)
from make_tex import build_tex, ETC_TOOL


# .mpkg constants (verified from real .mpkg files)
MPKG_MAGIC = "PKGM0014"  # The actual format used by real .mpkg files on Android
MPKG_HEADER_MARKER = 8    # u32 LE = 8 (length of the magic string)


def pack_mpkg(output_path, entries):
    """Pack a list of (name, bytes) into a .mpkg file.

    Format:
      [u32 LE header_marker=8][8-byte magic][u32 LE entry_count]
      [per entry: u32 LE name_len, name_bytes, u32 LE file_offset, u32 LE file_size]
      [data section: file blobs packed back-to-back]
    """
    header_size = 4 + 8 + 4  # header_marker + magic + entry_count
    vfs_table_size = sum(4 + len(name.encode('utf-8')) + 4 + 4 for name, _ in entries)
    data_section_start = header_size + vfs_table_size

    # Compute offsets relative to data_section_start
    vfs_entries = []
    cursor = 0
    for name, blob in entries:
        vfs_entries.append((name, cursor, len(blob)))
        cursor += len(blob)

    out = bytearray()
    out += struct.pack('<I', MPKG_HEADER_MARKER)
    out += MPKG_MAGIC.encode('ascii')
    if len(MPKG_MAGIC) != 8:
        raise ValueError(f"magic must be 8 bytes, got {len(MPKG_MAGIC)}")
    out += struct.pack('<I', len(entries))

    for name, offset, size in vfs_entries:
        nb = name.encode('utf-8')
        out += struct.pack('<I', len(nb))
        out += nb
        out += struct.pack('<I', offset)
        out += struct.pack('<I', size)

    for _, blob in entries:
        out += blob

    with open(output_path, 'wb') as f:
        f.write(out)
    return len(out)


def build_scene_mpkg(image_path, output_path, title="WepWawet Scene Wallpaper",
                     description="Sent from WepWawet", tags=None,
                     workshop_url=None, jobs=4, effort=20, preview_size=400):
    """Build a complete Scene-type .mpkg from an image file.

    Args:
        image_path: source image (JPEG, PNG, etc.)
        output_path: where to write the .mpkg file
        title: wallpaper title
        description: wallpaper description
        tags: list of tags (default ["Anime"])
        workshop_url: optional Steam workshop URL
        jobs: ETC2 encoding threads
        effort: ETC2 encoding effort (0-100)
        preview_size: max dimension of preview thumbnail (px)

    Returns:
        dict with: mpkg_size, tex_size, image_size, entries
    """
    if tags is None:
        tags = ["Anime"]

    # Step 1: load image and create a preview thumbnail
    img = Image.open(image_path)
    img = img.convert('RGBA')
    img_w, img_h = img.size

    # Build preview thumbnail (max dim = preview_size, JPEG)
    preview_img = img.copy()
    preview_img.thumbnail((preview_size, preview_size), Image.LANCZOS)
    preview_buf = io.BytesIO()
    # Flatten onto black bg (JPEG doesn't support alpha)
    bg = Image.new('RGB', preview_img.size, (0, 0, 0))
    bg.paste(preview_img, mask=preview_img.split()[-1] if preview_img.mode == 'RGBA' else None)
    bg.save(preview_buf, format='JPEG', quality=85)
    preview_bytes = preview_buf.getvalue()

    # Step 2: build a .tex file from the image (full quality)
    # Convert image to PNG for EtcTool (it needs a PNG input)
    png_buf = io.BytesIO()
    img.save(png_buf, format='PNG')
    png_path = output_path + '.tmp.png'
    with open(png_path, 'wb') as f:
        f.write(png_buf.getvalue())

    tex_path = output_path + '.tmp.tex'
    try:
        tex_info = build_tex(png_path, tex_path, jobs=jobs, effort=effort)
        with open(tex_path, 'rb') as f:
            tex_bytes = f.read()
    finally:
        # Clean up temp files
        for tmp in [png_path, tex_path]:
            if os.path.exists(tmp):
                os.remove(tmp)

    tex_w = tex_info['tex_width']
    tex_h = tex_info['tex_height']
    tex_base = "wepwawet_image"

    # Step 3: build project.json (byte-for-byte compatible with real WE)
    # Real WE uses TAB indentation and " : " separator (space before AND after colon)
    project_json = json.dumps({
        "contentrating": "Everyone",
        "description": description,
        "file": "scene.json",
        "general": {
            "properties": {
                "schemecolor": {
                    "order": 0,
                    "text": "ui_browse_properties_scheme_color",
                    "type": "color",
                    "value": "0.000 0.000 0.000",
                }
            }
        },
        "preview": "preview.jpg",
        "tags": tags,
        "title": title,
        "type": "scene",
        "version": 0,
        "visibility": "private",
        "workshopurl": workshop_url or f"https://steamcommunity.com/sharedfiles/filedetails/?id=0",
    }, indent='\t', ensure_ascii=False, separators=(',', ' : ')).encode('utf-8')

    # Step 4: build scene.json — minimal camera + one fullscreen image quad
    # Matches the structure of real WE scene.json files
    scene_json = json.dumps({
        "camera": {
            "center": "0.000 0.000 -1.000",
            "eye": "0.000 0.000 0.000",
            "up": "0.000 1.000 0.000",
        },
        "general": {
            "clearcolor": "0.000 0.000 0.000",
            "clearenabled": True,
            "farz": 10000.0,
            "fov": 50.0,
            "nearz": 0.0099999997764825821,
            "orthogonalprojection": {
                "height": tex_h,
                "width": tex_w,
            },
            "zoom": 1.0,
        },
        "objects": [
            {
                "alignment": "center",
                "alpha": 1.0,
                "angles": "0.000 0.000 0.000",
                "color": "1.000 1.000 1.000",
                "colorBlendMode": 0,
                "copybackground": True,
                "id": 1,
                "image": f"models/{tex_base}.json",
                "ledsource": False,
                "locktransforms": True,
                "name": tex_base,
                "origin": f"{tex_w/2:.3f} {tex_h/2:.3f} 0.000",
                "parallaxDepth": "0.000 1.000",
                "perspective": False,
                "scale": "1.000 1.000 1.000",
                "size": f"{tex_w}.000 {tex_h}.000",
                "solid": True,
                "visible": True,
            }
        ],
    }, indent='\t', ensure_ascii=False, separators=(',', ' : ')).encode('utf-8')

    # Step 5: build model JSON
    model_json = json.dumps({
        "autosize": True,
        "material": f"materials/{tex_base}.json",
    }, indent='\t', ensure_ascii=False, separators=(',', ' : ')).encode('utf-8')

    # Step 6: build material JSON — uses "genericimage2" built-in shader
    # The "genericimage2" shader is a built-in WE shader that renders a textured quad
    material_json = json.dumps({
        "passes": [
            {
                "blending": "translucent",
                "combos": {
                    "version": 2,
                },
                "cullmode": "nocull",
                "depthtest": "disabled",
                "depthwrite": "disabled",
                "shader": "genericimage2",
                "textures": [tex_base],
            }
        ],
    }, indent='\t', ensure_ascii=False, separators=(',', ' : ')).encode('utf-8')

    # Step 7: assemble .mpkg entries (ordered like real .mpkg files)
    entries = [
        ("project.json", project_json),
        ("scene.json", scene_json),
        (f"models/{tex_base}.json", model_json),
        (f"materials/{tex_base}.json", material_json),
        (f"materials/{tex_base}.tex", tex_bytes),
        ("preview.jpg", preview_bytes),
    ]

    mpkg_size = pack_mpkg(output_path, entries)

    return {
        'mpkg_size': mpkg_size,
        'tex_size': len(tex_bytes),
        'preview_size': len(preview_bytes),
        'image_size': (img_w, img_h),
        'tex_dims': (tex_w, tex_h),
        'entries': [(name, len(blob)) for name, blob in entries],
    }


def main():
    parser = argparse.ArgumentParser(description='Build a Scene-type .mpkg from an image')
    parser.add_argument('input', help='Input image (JPEG, PNG, etc.)')
    parser.add_argument('output', help='Output .mpkg file path')
    parser.add_argument('--title', '-t', default='WepWawet Scene Wallpaper')
    parser.add_argument('--description', '-d', default='Sent from WepWawet')
    parser.add_argument('--tags', default='Anime,Scene')
    parser.add_argument('--url', help='Steam workshop URL')
    parser.add_argument('--jobs', '-j', type=int, default=4)
    parser.add_argument('--effort', '-e', type=int, default=20)
    parser.add_argument('--preview-size', '-p', type=int, default=400)
    args = parser.parse_args()

    tags = [t.strip() for t in args.tags.split(',') if t.strip()]
    print(f"Building Scene .mpkg from {args.input} → {args.output}")
    result = build_scene_mpkg(
        args.input, args.output,
        title=args.title, description=args.description, tags=tags,
        workshop_url=args.url, jobs=args.jobs, effort=args.effort,
        preview_size=args.preview_size,
    )
    print(f"\nResult:")
    print(f"  .mpkg size: {result['mpkg_size']:,} bytes")
    print(f"  .tex size: {result['tex_size']:,} bytes")
    print(f"  Preview size: {result['preview_size']:,} bytes")
    print(f"  Original image: {result['image_size'][0]}x{result['image_size'][1]}")
    print(f"  Texture dims: {result['tex_dims'][0]}x{result['tex_dims'][1]}")
    print(f"\n  Entries:")
    for name, sz in result['entries']:
        print(f"    {name:40s}  {sz:>12,} bytes")


if __name__ == '__main__':
    main()
