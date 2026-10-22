# WE Android App — Reverse Engineering Findings

> Decompiled from `https://www.wallpaperengine.io/android/apk/wallpaper-engine.apk` (139 MB)
> Decompiled with jadx v1.5.0 → 4,067 Java files in `io.wallpaperengine.*` packages

## Package Structure

```
io.wallpaperengine.weclient/  — Activities, Services (the app)
  ├── PairingActivity          — PIN entry + pairing flow
  ├── BrowseActivity           — Browse wallpapers on paired PC
  ├── DiscoverDevicesActivity  — Scan for PCs on network
  ├── DiscoverService          — UDP 7884 discovery (Android Service)
  ├── TransferService          — Wallpaper transfer (Android Service)
  ├── WEWallpaperService       — Live wallpaper rendering
  ├── FileDetailsActivity      — Wallpaper details
  ├── GeneralSettingsActivity  — Settings
  ├── PlaylistSettingsActivity — Playlist management
  └── WallpaperProperties      — Wallpaper properties

io.wallpaperengine.weutil/    — Utilities
  ├── TcpClient               — TCP 7889 client (pairing + crypto + transfer)
  ├── DeviceItem              — PC device data model
  ├── Util                    — General utilities (mpkg, etc.)
  ├── SupportFileLoader       — .mpkg file loader
  ├── RangedInputStream       — Ranged file reading
  ├── VirtualFileRange        — Virtual file range
  ├── AsyncPreviewLoader      — Async wallpaper preview
  ├── WallpaperInfoSparse     — Sparse wallpaper info
  ├── LocalWallpaperSparse    — Local wallpaper info
  ├── PlaylistInfoSparse      — Playlist info
  └── GLUtil, VecUtil, etc.  — Rendering utilities

io.wallpaperengine.wedata/    — Data models
  ├── PairedServer            — Paired PC data (pin + RSA public key)
  ├── RSAPublicKeyHelper      — RSA public key (BigInteger exponent + modulus)
  ├── DownloadInfo            — Download info
  ├── PropertyList             — Property list
  └── PlaylistData            — Playlist data

io.wallpaperengine.weviews/   — Wallpaper views
  ├── SceneWallpaperView      — 3D scene wallpaper (uses libscenejni.so)
  ├── VideoWallpaperView     — Video wallpaper
  └── WallpaperControls       — Wallpaper control interface

io.wallpaperengine.wrapper/  — Native library wrapper
  └── SceneLib                — JNI wrapper for libscenejni.so
```

## Pairing Protocol (Fully Reverse-Engineered)

### 1. Discovery (UDP 7884)

**Broadcaster**: The PC's `ui32.exe`/`ui64.exe` (the WE Chromium CEF UI process)

**Protocol**: UDP multicast
- **Port**: 7884
- **Multicast group**: 239.100.0.1
- **Payload format**: `WallpaperEngine/Ping:<pid>:<hostname>:<flag>:<interfaceId>`
- **Fields**:
  - `pid`: Process ID (5 digits)
  - `hostname`: PC hostname
  - `flag`: Flag byte (usually `1`)
  - `interfaceId`: Network interface ID

**Phone side** (`DiscoverService.java`):
- Creates `MulticastSocket(7884)`
- Joins multicast group `239.100.0.1`
- Receives 128-byte `DatagramPacket`
- Parses payload as UTF-8 string
- Splits by `:` → expects 5 parts
- Creates `DeviceItem` with name + IP + port

### 2. Pairing (TCP 7889)

**Client**: The phone (our app acts as the "PC" = server)

**Connection flow**:
1. Phone discovers PC via UDP 7884
2. Phone connects to PC via TCP on port 7889
3. PC sends RSA public key (exponent + modulus as BigInteger)
4. Phone generates AES key + IV
5. Phone encrypts `EncAuthRequest` (JSON serialized with Gson, encrypted with AES/CBC/PKCS5Padding)
6. Phone sends encrypted AES key (encrypted with PC's RSA public key)
7. Phone sends encrypted EncAuthRequest (AES-encrypted)
8. PC decrypts AES key with RSA private key
9. PC decrypts EncAuthRequest with AES key
10. PC verifies PIN
11. PC sends EncAuthResponse (AES-encrypted) with status
12. All subsequent communication is AES-encrypted

### 3. Wire Format (AES-encrypted messages)

**Sending** (`TcpClient.Companion.writeEncryptedData`):
```
writeInt(ciphertext_length)    // 4 bytes, big-endian
write(ciphertext)              // AES/CBC/PKCS5Padding encrypted JSON
```

**Receiving** (`TcpClient.Companion.readEncryptedData`):
```
readInt()                     // 4 bytes, big-endian → ciphertext_length
readFully(buffer, 0, length)  // read ciphertext
Cipher.getInstance("AES/CBC/PKCS5Padding").init(DECRYPT, key, iv)
cipher.doFinal(buffer)         → plaintext JSON
```

### 4. EncAuthRequest (Phone → PC)

```json
{
  "pin": "1234",
  "name": "device_name",
  "deviceUserName": "user_custom_name",
  "displayWidth": 1080,
  "displayHeight": 2400,
  "version": 4,
  "mpkgVersion": "<from TransferService.MPKG_SUPPORT_VERSION>",
  "appVersion": "2.0.0"
}
```

### 5. EncAuthResponse (PC → Phone)

```json
{
  "status": "ok"
}
```

### 6. Constants (verified from decompiled source)

```kotlin
// TcpClient.java:
MAX_SAFE_COMMAND_LENGTH = 4096
MAX_SAFE_DATA_LENGTH    = 1048592  // ~1 MiB

// TransferService.java:
CONNECTION_PROTOCOL_VERSION = 4
MPKG_SUPPORT_VERSION        = "PKGM0020"  // <-- THIS IS THE .mpkg MAGIC!
```

**CRITICAL**: `TcpClient$Companion.readInt` and `writeInt` use
LITTLE-ENDIAN byte order (verified from decompiled source):
```java
public final int readInt(DataInputStream input) {
    byte[] bArr = new byte[4];
    input.read(bArr, 0, 4);
    return ByteBuffer.wrap(bArr).order(ByteOrder.LITTLE_ENDIAN).getInt();
}
public final void writeInt(DataOutputStream output, int value) {
    output.writeByte(value & 255);          // LSB first
    output.writeByte((value >> 8) & 255);
    output.writeByte((value >> 16) & 255);
    output.writeByte((value >> 24) & 255);  // MSB last
}
```

Java's `DataInputStream.readInt()` uses BIG-ENDIAN, so any re-implementation
of the WE PC side MUST use little-endian length prefixes — otherwise every
length prefix is misread and pairing fails silently.

### 7. Heartbeat (verified from decompiled source)

After pairing succeeds, the phone's `connector$lambda$2$lambda$1` thread
sends the literal ASCII bytes `"heartbeat\n"` (10 bytes, NO length prefix)
every ~3 seconds via `dataOutputStream.writeBytes("heartbeat\n")`.

A WE PC-side server MUST detect and skip these frames. Otherwise, `readInt()`
would interpret the bytes `h`,`e`,`a`,`r` (0x68, 0x65, 0x61, 0x72) as a
little-endian 4-byte length = `0x72616568` = 1,919,014,504 bytes — far
exceeding `MAX_SAFE_DATA_LENGTH` (1,048,592) and breaking the command loop.

### 8. RSA Public Key

```kotlin
class RSAPublicKeyHelper {
    var exponent: BigInteger  // RSA public exponent
    var modulus: BigInteger   // RSA modulus
}
```

### 9. Paired Server Data

```kotlin
class PairedServer {
    var pin: String                       // 4-digit PIN
    var publicKeyBlob: RSAPublicKeyHelper  // RSA public key
}
```

## .mpkg Format (Best-Effort RE — Updated)

### Confirmed magic bytes

The `.mpkg` format begins with the **8-byte ASCII magic `PKGM0020`**.
This is confirmed by the constant `TransferService.MPKG_SUPPORT_VERSION = "PKGM0020"`
which is sent in the `EncAuthRequest.mpkgVersion` field during pairing,
telling the PC which .mpkg version the phone supports.

### VFS (Virtual File System) layout

The `.mpkg` is a custom binary container that implements a VFS. The native
library `libscenejni.so` parses it and exposes these JNI methods on `SceneLib`:

- `String getWallpaperType(String filePath)` — returns `"Scene"`, `"Video"`, etc.
- `VirtualFileRange getWallpaperFileVirtualOffset(String filePath)`
  → returns `{vOffset: long, vSize: long}` describing where a virtual file lives
- `WallpaperInfoSparse[] enumerateWallpapers()` — lists all wallpapers
- `WallpaperInfoSparse getWallpaperInfoSparse(String filePath)`
- `byte[] getWallpaperInfoPreviewBitmap(String filePath)`
- `Point getWallpaperResolution(String filePath)`
- `boolean isWallpaperVersionValid(String filePath)`
- `String getWallpaperProjectString(String filePath, String key)`

The Java side then uses `SupportFileLoader.loadBitmapFromVFS(vfsSource, offset, size)`
which wraps the .mpkg file in a `RangedInputStream(FileInputStream(vfsSource),
offset, size)` to read a single virtual file out of the container.

For video wallpapers, `Util.getMPKGVideoReader(reader, offsets)` returns a
`MediaDataSource` that does:
```java
reader.seek(position + offsets.getVOffset());
return reader.read(buffer, offset, size);  // bounded by offsets.getVSize()
```

### URI scheme

`Util.URI_PROTOCOL_MPKG_VFS = "MPKG_VFS://"` — wallpapers reference internal
virtual files via this URI scheme.

### What we still DON'T know

- The exact byte layout of the VFS table (offsets, sizes, name table location).
- How `.pkg` content (the PC format) is converted to `.mpkg` on the PC side.
- How 3D scene wallpapers are converted for Android rendering.
- The binary format of `libscenejni.so` itself (which contains the actual VFS parser).

### How to get a sample `.mpkg` (then feed to `scripts/toolkit/mpkg_analyzer.py`)

1. Pair a real phone with a real PC running WE.
2. Transfer any wallpaper.
3. The `.mpkg` files are saved at:
   `/storage/emulated/0/Android/data/io.wallpaperengine.weclient/files/`
4. Pull via adb:
   ```bash
   adb pull /sdcard/Android/data/io.wallpaperengine.weclient/files/ ./we-files
   ```
5. Analyze:
   ```bash
   ./scripts/toolkit/run.sh analyze ./we-files/some-wallpaper.mpkg --full
   ```

### RE next steps

1. Use `scripts/toolkit/run.sh frida --out hooks.js` to generate a Frida
   script that hooks `SceneLib.getWallpaperFileVirtualOffset` and prints
   the (offset, size) for every virtual file the native lib parses.
2. Compare the offsets against the binary layout to reverse the VFS table.
3. Use `scripts/toolkit/run.sh capture` to capture a real pairing session
   and confirm the wire format.
4. The `scripts/toolkit/mpkg_packer.py` script implements a HEURISTIC layout
   (8-byte magic + 4-byte LE count + N×16-byte VFS table + name table + blobs).
   Iterate this layout against the real app with Frida hooks to confirm.

## Steam Workshop Download Methods

### Method 1: Steam Web API (with user's API key)
- `ISteamRemoteStorage/GetPublishedFileDetails/v1/` — anonymous, returns metadata only
- `file_url` is always empty (Steam hides download URLs)
- Cannot download actual content anonymously

### Method 2: SteamCMD (with user's Steam account)
- `steamcmd +login <user> <pass> +workshop_download_item 431960 <id> +quit`
- Downloads workshop items to local filesystem
- Requires owning Wallpaper Engine on the Steam account
- Can be automated but requires Steam Guard handling

### Method 3: WorkshopDL (github.com/imwaitingnow/WorkshopDL)
- Python tool that downloads workshop items with Steam login
- Can download anonymously for some games (not WE — it's paid)
- Can use personal Steam account

### Method 4: Third-party mirrors
- `steamworkshop.download` — may be unreliable/down
- `steamworkshop.io` — another mirror
- These bypass Steam auth but may not have all items

### Recommended approach for WepWawet:
- Use Steam Web API for browsing (anonymous — already working)
- Use SteamCMD/WorkshopDL for downloading (with user's Steam credentials)
- The user provides their Steam login in the app → we use them to download via SteamCMD
