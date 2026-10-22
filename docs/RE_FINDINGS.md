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

### 6. Constants

```kotlin
MAX_SAFE_COMMAND_LENGTH = 4096
MAX_SAFE_DATA_LENGTH = 1048592  // 1 MB
```

### 7. RSA Public Key

```kotlin
class RSAPublicKeyHelper {
    var exponent: BigInteger  // RSA public exponent
    var modulus: BigInteger   // RSA modulus
}
```

### 8. Paired Server Data

```kotlin
class PairedServer {
    var pin: String                       // 4-digit PIN
    var publicKeyBlob: RSAPublicKeyHelper  // RSA public key
}
```

## .mpkg Format (Partially RE'd)

### What we know:
- `.mpkg` is the Android-specific wallpaper format (distinct from `.pkg` which is the PC format)
- PC WE converts `.pkg` content to `.mpkg` before transferring to the phone
- The WE Android app loads `.mpkg` files via `SupportFileLoader`
- `TransferService.MPKG_SUPPORT_VERSION` is a version string sent during pairing
- The `EncAuthRequest` includes `mpkgVersion` so the PC knows what version to produce
- `.mpkg` files are received via encrypted TCP transfer with `RangedInputStream`
- `LocalWallpaperSparse` and `WallpaperInfoSparse` handle the metadata

### What we DON'T know (needs more RE):
- The exact binary format of `.mpkg` (magic bytes, container type)
- How `.pkg` content is converted to `.mpkg` on the PC side
- Whether `.mpkg` is a ZIP, custom binary, or something else
- How 3D scene wallpapers are converted for Android rendering

### How to get a sample `.mpkg`:
1. Pair a real phone with a real PC running WE
2. Transfer a wallpaper
3. Look in `/storage/emulated/0/Android/data/io.wallpaperengine.weclient/files/`
4. Use `scripts/re_toolkit.sh mpkg` to analyze the format

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
