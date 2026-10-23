#!/usr/bin/env python3
"""
Extract a .tex file and all scene assets from a real WE .mpkg,
then create a minimal Scene-type .mpkg that uses those assets.

This proves that Scene-type wallpapers work via .mpkg transfer.
Once confirmed, we can work on creating .tex files from JPEG images.
"""
import struct
import sys
import os
import json

def read_mpkg(path):
    """Read a .mpkg file and return (entries, data_section_start, raw_bytes)."""
    with open(path, 'rb') as f:
        data = f.read()
    
    header_marker = struct.unpack_from('<I', data, 0)[0]
    magic = data[4:12].decode('ascii', errors='replace')
    entry_count = struct.unpack_from('<I', data, 12)[0]
    
    pos = 16
    entries = []
    for i in range(entry_count):
        name_len = struct.unpack_from('<I', data, pos)[0]; pos += 4
        name = data[pos:pos+name_len].decode('utf-8'); pos += name_len
        offset = struct.unpack_from('<I', data, pos)[0]
        size = struct.unpack_from('<I', data, pos+4)[0]; pos += 8
        entries.append({'name': name, 'offset': offset, 'size': size})
    
    data_start = pos
    return entries, data_start, data

def extract_entry(data, data_start, entry):
    """Extract a single entry's bytes from the .mpkg data."""
    start = data_start + entry['offset']
    return data[start:start + entry['size']]

def pack_mpkg(output_path, magic, entries_list):
    """
    Pack a list of (name, bytes) into a .mpkg file.
    
    Format:
      [u32 LE header_marker=8][8-byte magic][u32 LE entry_count]
      [per entry: u32 LE name_len, name_bytes, u32 LE file_offset, u32 LE file_size]
      [data section: file blobs packed back-to-back]
    """
    header_size = 4 + 8 + 4  # header_marker + magic + entry_count
    vfs_table_size = sum(4 + len(name.encode('utf-8')) + 4 + 4 for name, _ in entries_list)
    data_section_start = header_size + vfs_table_size
    
    # Compute offsets
    vfs_entries = []
    cursor = 0
    for name, blob in entries_list:
        vfs_entries.append((name, cursor, len(blob)))
        cursor += len(blob)
    
    # Assemble
    out = bytearray()
    out += struct.pack('<I', 8)  # header_marker
    out += magic.encode('ascii')
    if len(magic) != 8:
        raise ValueError(f"magic must be 8 bytes, got {len(magic)}")
    out += struct.pack('<I', len(entries_list))
    
    # VFS table
    for (name, offset, size) in vfs_entries:
        nb = name.encode('utf-8')
        out += struct.pack('<I', len(nb))
        out += nb
        out += struct.pack('<I', offset)
        out += struct.pack('<I', size)
    
    # Data section
    for _, blob in entries_list:
        out += blob
    
    with open(output_path, 'wb') as f:
        f.write(out)
    
    return len(out)

def main():
    # Source: real .mpkg from the user
    src_mpkg = '/home/z/my-project/upload/2279314480.mpkg'
    
    # Read the source .mpkg
    entries, data_start, data = read_mpkg(src_mpkg)
    
    print(f"Source: {src_mpkg}")
    print(f"Magic: {data[4:12].decode('ascii')}")
    print(f"Entries: {len(entries)}")
    for e in entries:
        print(f"  {e['name']:40s}  offset={e['offset']:>10}  size={e['size']:>12}")
    
    # Extract key files
    extracted = {}
    for e in entries:
        extracted[e['name']] = extract_entry(data, data_start, e)
    
    # Get image dimensions from the first .tex file
    tex = None
    tex_name = None
    for name in extracted:
        if name.endswith('.tex'):
            tex = extracted[name]
            tex_name = name
            break
    
    if tex is None:
        print("ERROR: No .tex file found in source .mpkg")
        return
    
    # Parse .tex header to get dimensions
    # TEXV0005\0 (9 bytes) + TEXI0001\0 (9 bytes) + header (28 bytes)
    pos = 18  # skip both magics + nulls
    fmt, flags, tex_w, tex_h, img_w, img_h, unk0 = struct.unpack_from('<7I', tex, pos)
    print(f"\nTexture: {tex_name}")
    print(f"  Format: {fmt}")
    print(f"  Texture dimensions: {tex_w}x{tex_h}")
    print(f"  Image dimensions: {img_w}x{img_h}")
    
    # Build a minimal Scene-type .mpkg
    # We need:
    # 1. project.json (type:"scene", file:"scene.json")
    # 2. scene.json (minimal camera + one fullscreen image object)
    # 3. A model JSON (references material)
    # 4. A material JSON (uses "genericimage2" shader, references texture)
    # 5. The .tex texture file (extracted from real .mpkg)
    # 6. preview.jpg (for the thumbnail in the wallpaper list)
    
    # Use the texture's base name (without path and extension)
    tex_base = tex_name.rsplit('/', 1)[-1].rsplit('.', 1)[0]  # e.g., "69721567_p0"
    
    # project.json
    project_json = json.dumps({
        "contentrating": "Everyone",
        "description": "Test Scene wallpaper from WepWawet",
        "file": "scene.json",
        "general": {
            "properties": {
                "schemecolor": {
                    "order": 0,
                    "text": "ui_browse_properties_scheme_color",
                    "type": "color",
                    "value": "0.000 0.000 0.000"
                }
            }
        },
        "preview": "preview.jpg",
        "tags": ["Anime"],
        "title": "WepWawet Scene Test",
        "type": "scene",
        "version": 0,
        "visibility": "private",
        "workshopurl": "https://steamcommunity.com/sharedfiles/filedetails/?id=2279314480"
    }, indent='\t').encode('utf-8')
    
    # scene.json — minimal camera + one fullscreen image object
    scene_json = json.dumps({
        "camera": {
            "center": "0.000 0.000 -1.000",
            "eye": "0.000 0.000 0.000",
            "up": "0.000 1.000 0.000"
        },
        "general": {
            "clearcolor": "0.000 0.000 0.000",
            "clearenabled": True,
            "farz": 10000.0,
            "fov": 50.0,
            "nearz": 0.0099999997764825821,
            "orthogonalprojection": {
                "height": tex_h,
                "width": tex_w
            },
            "zoom": 1.0
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
                "visible": True
            }
        ]
    }, indent='\t').encode('utf-8')
    
    # Model JSON
    model_json = json.dumps({
        "autosize": True,
        "material": f"materials/{tex_base}.json"
    }, indent='\t').encode('utf-8')
    
    # Material JSON — uses "genericimage2" built-in shader
    material_json = json.dumps({
        "passes": [
            {
                "blending": "translucent",
                "combos": {
                    "version": 2
                },
                "cullmode": "nocull",
                "depthtest": "disabled",
                "depthwrite": "disabled",
                "shader": "genericimage2",
                "textures": [tex_base]
            }
        ]
    }, indent='\t').encode('utf-8')
    
    # Get preview.jpg from the source .mpkg
    preview_jpg = extracted.get('preview.jpg', b'')
    if not preview_jpg:
        print("ERROR: No preview.jpg in source .mpkg")
        return
    
    # The .tex file (extracted from real .mpkg)
    tex_data = extracted[tex_name]
    
    # Build the entries list
    # Order: project.json first (as the real .mpkg has it)
    entries = [
        ("project.json", project_json),
        ("scene.json", scene_json),
        (f"models/{tex_base}.json", model_json),
        (f"materials/{tex_base}.json", material_json),
        (tex_name, tex_data),  # The .tex file
        ("preview.jpg", preview_jpg),
    ]
    
    # Pack into .mpkg
    output = '/home/z/my-project/download/scene_test.mpkg'
    os.makedirs(os.path.dirname(output), exist_ok=True)
    size = pack_mpkg(output, "PKGM0014", entries)
    
    print(f"\n=== Output ===")
    print(f"File: {output}")
    print(f"Size: {size:,} bytes")
    print(f"Magic: PKGM0014")
    print(f"Entries: {len(entries)}")
    for name, blob in entries:
        print(f"  {name:40s}  {len(blob):>12,} bytes")
    
    # Verify by re-reading
    entries2, ds2, data2 = read_mpkg(output)
    print(f"\nVerification: {len(entries2)} entries, data section at {ds2}")
    for e in entries2:
        print(f"  {e['name']:40s}  offset={e['offset']:>10}  size={e['size']:>12}")

if __name__ == '__main__':
    main()
