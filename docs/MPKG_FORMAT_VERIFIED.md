# Complete .mpkg Format — VERIFIED from Native C++ Source

> Source: `libscenejni.so.c` — full Ghidra decompilation (5,020,933 lines)
> Functions analyzed:
> - `AndroidFileSystem::LoadVirtualFileSystem` (line 6397)
> - `AndroidFileSystem::GetVirtualFileOffset` (line 7094)
> - `Java_io_wallpaperengine_wrapper_SceneLib_getWallpaperFileVirtualOffset` (user's earlier upload)

## .mpkg Format — 100% CONFIRMED from C++ source

```
[u32 LE header_marker]            — read at line 6586, clamped to max 8 (line 6592-6594)
[header_marker bytes ASCII magic] — read at line 6608, validated against "PKGM" (line 6622-6624)
                                    version number (bytes 4-7) parsed via atoi() (line 6629)
                                    must be <= 20 (0x14) or error (line 6630-6634)
[u32 LE entry_count N]            — read at line 6643
For each entry (loop at lines 6648-6839):
  [u32 LE name_len]               — read at line 6654, clamped to max 1024 (0x400) (line 6660-6662)
  [name_len bytes name]           — read at line 6675, lowercased via ARM64 NEON SIMD (lines 6697-6828)
  [u32 LE file_offset]            — read at line 6677 (separate 4-byte read)
  [u32 LE file_size]              — read at line 6680 (separate 4-byte read)
[data section: file blobs packed back-to-back]
```

After the loop (line 6841-6845):
```c
iVar14 = (**(code **)(**(long **)(this + 0x88) + 0x20))();  // GetFilePosition() = data_section_start
for (plVar26 = *(long **)(this + 0x70); plVar26 != NULL; plVar26 = (long *)*plVar26) {
    *(int *)(plVar26 + 5) = *(int *)(plVar26 + 5) + iVar14;  // offset += data_section_start
}
```

**Each entry's `file_offset` is stored as RELATIVE to `data_section_start`, and the native
code adds `data_section_start` to make it ABSOLUTE after loading.**

## GetVirtualFileOffset — case-insensitive hash table lookup

```c
bool GetVirtualFileOffset(this, const char* filename, uint* outOffset, uint* outSize) {
    // 1. Build std::string from filename (lines 7130-7155)
    // 2. Lowercase the string via ARM64 NEON SIMD (lines 7158-7288)
    //    - Same 0x20 | trick with 0x5b comparison as in getWallpaperFileVirtualOffset
    // 3. hash_table.find(lowercased_name) (line 7290)
    // 4. If found:
    //    *outOffset = *(uint*)(node + 0x28);  // file_offset (line 7296)
    //    *outSize = *(uint*)(node + 0x2c);    // file_size (line 7297)
    // 5. return found != NULL (line 7299)
}
```

**The VFS lookup is CASE-INSENSITIVE** — both the stored names and the lookup
key are lowercased before comparison.

## VirtualFile struct in memory (hash table node)

```
Offset 0x00-0x27: hash node overhead (next pointer, key string, hash value)
Offset 0x28:      uint32 file_offset (stored relative, made absolute after load)
Offset 0x2c:      uint32 file_size
```

Stored via: `*(ulong*)(node + 0x28) = CONCAT44(file_size, file_offset);` (line 6834)
Read via: `*outOffset = *(uint*)(node + 0x28); *outSize = *(uint*)(node + 0x2c);` (lines 7296-7297)

## Magic validation (line 6622-6624)

```c
local_b0 = CONCAT26(local_b0._6_2_, 0x4d474b5008);  // = "\x08PKGM\0..."
memcmp(first_bytes_of_magic, local_b0, min(magic_len, 4));
```

The comparison constant `0x4d474b5008` encodes `\x08` + `PKGM` (header_marker + magic prefix).

## Version check (lines 6629-6634)

```c
uVar15 = atoi((char*)(magic + 4));  // parse version string (e.g., "0014" → 14)
if (0x14 < (int)uVar15) {           // if version > 20
    WPX_Error("Cannot open %s because version %i is not supported.", path, version);
    return 2;  // error
}
```

Supported versions: 0 through 20 (0x14). The user's samples are version 0014 (= 14). ✓

## name_len clamping (lines 6660-6662)

```c
uVar15 = (uint)local_b0;  // name_len
if (0x3ff < (uint)local_b0) {  // if name_len > 1023
    uVar15 = 0x400;  // clamp to 1024
}
```

Filenames longer than 1024 bytes are truncated to 1024.

## Summary — the format is 100% confirmed

| Field | Type | Size | Verified from |
|-------|------|------|---------------|
| header_marker | u32 LE | 4 bytes | Line 6586: `Read(&local_80, 4)` |
| magic | ASCII | header_marker bytes (max 8) | Line 6608: `Read(puVar21, uVar16)` |
| entry_count | u32 LE | 4 bytes | Line 6643: `Read(&local_80, 4)` |
| name_len | u32 LE | 4 bytes | Line 6654: `Read(&local_b0, 4)` |
| name | UTF-8 | name_len bytes (max 1024) | Line 6675: `Read(pbVar20, uVar16)` |
| file_offset | u32 LE | 4 bytes | Line 6677: `Read(&local_b0, 4)` |
| file_size | u32 LE | 4 bytes | Line 6680: `Read(&local_b0, 4)` |

All offsets are RELATIVE to data_section_start (converted to absolute after load).
All file names are lowercased for case-insensitive lookup.
