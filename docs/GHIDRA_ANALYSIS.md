# Ghidra Decompilation Analysis: getWallpaperFileVirtualOffset

> Source: `libscenejni.so` from the official WE Android APK, decompiled with Ghidra.
> Function: `Java_io_wallpaperengine_wrapper_SceneLib_getWallpaperFileVirtualOffset`

## Function signature

```c
jobject Java_io_wallpaperengine_wrapper_SceneLib_getWallpaperFileVirtualOffset(
    JNIEnv* env,         // param_1
    jobject this,        // param_2 (SceneLib instance)
    jstring filePath     // param_3 (path to the .mpkg file)
)
```

Returns: a `VirtualFileRange` Java object with `vOffset` and `vSize` fields.

## Decoded flow (from Ghidra decompilation)

### 1. Convert the jstring filePath to a C string

```c
const char* path = env->GetStringUTFChars(filePath, NULL);  // JNI offset 0x548
size_t pathLen = strlen(path);
```

### 2. Build a std::string from the path

The function constructs a `std::string` (libc++ SSO layout) from the path.
For paths <= 22 chars, the string is stored inline; longer strings are
heap-allocated. This is the standard libc++ `basic_string` implementation.

### 3. Locale/language detection (lowercase + search)

A large block of ARM64 NEON SIMD code lowercases the path string, then
searches for language tag substrings (at binary addresses `UNK_00d4f3de`
and `UNK_00d390ea`). Based on the match, it calls `Locale::SetLanguage()`
with one of two locale strings (`UNK_00d0f342` or `UNK_00cf3855`).

This step is for UI localization and does NOT affect the VFS parsing.

### 4. Construct an AndroidFileSystem object

```c
AndroidFileSystem androidFS;
androidFS.AndroidFileSystem(
    globalAssetManager,    // AAssetManager* (global at 0x28f5960)
    globalStoragePath,     // std::string& (global at 0x28f47f8 — the app's files dir)
    globalAssetTime         // long (global at 0x28f5968 — BuildConfig.BUILD_TIME)
);
```

### 5. Load the .mpkg Virtual File System

```c
int result = androidFS.LoadVirtualFileSystem(path);  // path = the .mpkg file path
if (result != 0) return NULL;  // failed to load
```

`LoadVirtualFileSystem` parses the .mpkg file header and builds an in-memory
VFS table. (This function's decompilation is not included in the user's
upload, but the .mpkg format is verified from real samples — see below.)

### 6. Read the manifest file from inside the VFS

```c
std::string manifestContent;
ReadFileAsString(&androidFS, "project.json", &manifestContent, true);
// UNK_00d01893 = "project.json" (string constant in the binary)
```

The function reads a file named `"project.json"` from inside the .mpkg VFS
into a C++ string. The filename is a hardcoded string constant at address
`0x00d01893` in the .so binary.

### 7. Parse the manifest as JSON

```c
Json::Value root;
Json::CharReader* reader = Json::GetGlobalJsonBuilder()->newCharReader();
bool ok = reader->parse(manifestContent.begin(), manifestContent.end(), &root, NULL);
if (!ok || (!root.isObject() && !root.isArray())) return NULL;
if (!root.isObject()) return NULL;
```

### 8. Look up the "file" key in the manifest

```c
Json::Value& fileValue = root["file"];  // UNK_00d658c3 = "file" (string constant)
if (!fileValue.isString()) return NULL;
std::string wallpaperFile = fileValue.asString();
```

The function looks up the JSON key `"file"` (at binary address `0x00d658c3`)
in the project.json manifest. The value is the main wallpaper filename.

For a **Scene** wallpaper, `"file"` is typically `"scene.json"`.
For a **Video** wallpaper, `"file"` is the `.mp4` filename.

### 9. Look up the wallpaper file's offset in the VFS

```c
unsigned int vOffset, vSize;
bool found = androidFS.GetVirtualFileOffset(wallpaperFile.c_str(), &vOffset, &vSize);
// _ZN17AndroidFileSystem20GetVirtualFileOffsetEPKcRjS2_
// = AndroidFileSystem::GetVirtualFileOffset(const char*, unsigned int&, unsigned int&)
if (!found) return NULL;
```

`GetVirtualFileOffset` searches the VFS table for a file matching the given
name and returns its offset and size. Both are **32-bit unsigned ints**
(uint32_t), stored as `jlong` (64-bit) in the Java `VirtualFileRange` object.

### 10. Create and return the VirtualFileRange Java object

```c
jclass vfrClass = env->FindClass("io/wallpaperengine/weutil/VirtualFileRange");  // offset 0x30
jmethodID ctor = env->GetMethodID(vfrClass, "<init>", "()V");                    // offset 0x108
jfieldID vOffsetField = env->GetFieldID(vfrClass, "vOffset", "J");               // offset 0x2f0
jfieldID vSizeField = env->GetFieldID(vfrClass, "vSize", "J");                    // offset 0x2f0
jobject result = env->NewObject(vfrClass, ctor);                                  // _ZN7_JNIEnv9NewObjectEP7_jclassP10_jmethodIDz
env->SetLongField(result, vOffsetField, (jlong)vOffset);                         // offset 0x370
env->SetLongField(result, vSizeField, (jlong)vSize);                              // offset 0x370
return result;
```

## Key findings from the decompilation

1. **`getWallpaperFileVirtualOffset(mpkgPath)` does NOT look up the offset of the
   file passed as the parameter.** Instead:
   - The parameter IS the .mpkg file path
   - The function loads the .mpkg VFS
   - Reads `project.json` from inside the VFS
   - Looks up the `"file"` key in project.json → gets the wallpaper filename
   - Returns the offset+size of THAT file within the .mpkg

2. **The manifest file is always `"project.json"`** (hardcoded at `UNK_00d01893`).

3. **The JSON key is `"file"`** (hardcoded at `UNK_00d658c3`).

4. **`vOffset` and `vSize` are 32-bit unsigned ints** (uint32_t), even though
   they're stored as `jlong` (64-bit) in Java. This means files up to 4 GB
   are supported, which is consistent with the user's 1.16 GB sample.

5. **The VFS table format is inside `LoadVirtualFileSystem` and
   `GetVirtualFileOffset`** — functions whose decompilation is NOT included
   in the user's upload. However, the format has been VERIFIED from real
   .mpkg samples (see below).

## Verified .mpkg format (from real samples)

Verified against TWO real samples:
- **1.16 GB Video wallpaper** (magic "PKGM0014") — 3 VFS entries
- **2 MB Scene wallpaper** (magic "PKGM0014") — 11 VFS entries

```
[u32 LE header_marker = 8]            (length of the magic string)
[8-byte ASCII magic "PKGMvvvv"]       (e.g. "PKGM0014" or "PKGM0020")
[u32 LE entry_count N]
For each entry (in order, no padding between fields):
  [u32 LE name_len]
  [name_len bytes UTF-8 name]          (no null terminator)
  [u32 LE file_offset]                (RELATIVE to data_section_start)
  [u32 LE file_size]
[data section: file blobs packed back-to-back]
```

The `data_section_start` is the byte offset immediately after the last VFS
entry's `file_size` field. All `file_offset` values in the VFS entries are
relative to `data_section_start` (so the first file is always at relative
offset 0).

### Verification: 2 MB Scene sample (2279314480.mpkg)

```
header_marker = 8
magic = "PKGM0014"
entry_count = 11
data_section_start = 0x183 (387 bytes)

Entries:
[0]  effects/xray/effect.json        offset=0         size=412
[1]  materials/69721567_p0.json      offset=412       size=271
[2]  materials/69721567_p0.tex       offset=683       size=578871
[3]  materials/effects/xray.json     offset=579554    size=161
[4]  materials/hagikora180727-19.tex offset=579715    size=1107407
[5]  models/69721567_p0.json         offset=1687122   size=69
[6]  preview.jpg                     offset=1687191   size=403874
[7]  project.json                    offset=2091065   size=729
[8]  scene.json                      offset=2091794   size=1830
[9]  shaders/effects/xray.frag       offset=2093624   size=2026
[10] shaders/effects/xray.vert       offset=2095650   size=1548
```

Last entry ends at: data_section_start(387) + 2095650 + 1548 = 2097585 = file size ✓

### project.json content (from the 2 MB Scene sample)

```json
{
    "contentrating" : "Mature",
    "description" : "呉マサヒロ (CLESTA)\ncle_masahiro\n...",
    "file" : "scene.json",          ← getWallpaperFileVirtualOffset looks up this key
    "general" : { ... },
    "preview" : "preview.jpg",
    "tags" : [ "Anime" ],
    "title" : "Mordred (X-Ray)",
    "type" : "scene",
    "version" : 0,
    "visibility" : "private",
    "workshopurl" : "https://steamcommunity.com/sharedfiles/filedetails/?id=2279314480"
}
```

So `getWallpaperFileVirtualOffset("2279314480.mpkg")` would:
1. Load the VFS
2. Read `project.json` → finds `"file": "scene.json"`
3. Look up `scene.json` in the VFS → offset=2091794, size=1830
4. Return `VirtualFileRange{vOffset=387+2091794=2092181, vSize=1830}`

(387 is the data_section_start that gets added to the relative offset.)

## .tex texture format

WE's custom texture format (`.tex` files inside the .mpkg) starts with:
```
TEXV0005\0   (8 bytes — "TEXV" + version "0005" + null)
TEXI0000\0   (8 bytes — "TEXI" + info version "0000" + null)
```

Followed by texture metadata and image data. The `TEXV` magic is used for
detection (NOT `TEXB` as I previously guessed).

## String constants in libscenejni.so (verified from Ghidra)

| Address      | Value             | Purpose                              |
|--------------|-------------------|--------------------------------------|
| `0x00d01893` | `"project.json"`  | Manifest filename read from VFS      |
| `0x00d658c3` | `"file"`          | JSON key for the main wallpaper file |
| `0x00d4f3de` | (language tag 1)  | Searched in path for locale detection |
| `0x00d390ea` | (language tag 2)  | Searched in path for locale detection |
| `0x00d0f342` | (locale string 1) | Passed to Locale::SetLanguage         |
| `0x00cf3855` | (locale string 2) | Passed to Locale::SetLanguage         |

## JNI function table offsets (verified from decompilation)

| Offset  | JNI function              |
|---------|---------------------------|
| 0x030   | `FindClass`               |
| 0x108   | `GetMethodID`             |
| 0x2f0   | `GetFieldID`              |
| 0x370   | `SetLongField`            |
| 0x548   | `GetStringUTFChars`       |
| 0x550   | `ReleaseStringUTFChars`    |

## What we STILL don't have (decompilation gaps)

1. **`AndroidFileSystem::LoadVirtualFileSystem`** — the function that actually
   parses the .mpkg header and builds the VFS table. We've VERIFIED the format
   from real samples, but we don't have the C++ source to confirm the exact
   parsing logic.

2. **`AndroidFileSystem::GetVirtualFileOffset`** — the function that searches
   the VFS table for a given filename and returns its offset+size. Again,
   we've verified the format from samples, but don't have the C++ source.

3. **`AndroidFileSystem::ReadFile`** — the function that reads a virtual file
   from the VFS (used by `ReadFileAsString`).

These functions are inside `libscenejni.so` but were not included in the
user's Ghidra export. If needed, they can be decompiled by running Ghidra
on the full .so binary (provided as `libscenejni.so.zip` in the upload).

However, since we've VERIFIED the .mpkg format from TWO real samples
(1.16 GB Video + 2 MB Scene) with exact byte-level matching, we have
high confidence in the format even without the C++ parsing source.
