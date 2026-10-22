# WE Pairing Protocol — Verified from Real Wireshark Capture

> Captured 2026-10-22 between a Windows PC running Wallpaper Engine (192.168.1.178)
> and the official WE Android app on a phone (192.168.1.67).
>
> Source files:
> - `upload/wireshark.pcapng` — 77 packets, 2 TCP flows on port 7889
> - `upload/capture.txt` — UDP 7884 discovery monitor output
> - `upload/mpkg from script.txt` — mpkg_analyzer output on a real 1.16 GB .mpkg file
> - `upload/decoded_protocol.txt` — frame-by-frame protocol decode

## 1. Discovery Protocol (UDP 7884) — VERIFIED ✓

The PC broadcasts `WallpaperEngine/Ping` packets every ~1.3 seconds to:
- Multicast group `239.100.0.1:7884`
- Subnet broadcast `192.168.1.255:7884`
- Loopback `127.0.0.1:7884`

### Packet format (128-byte DatagramPacket, UTF-8, null-padded)

```
WallpaperEngine/Ping:<port>:<hostname>:<protocolVersion>:<guid>
```

### Verified fields (from real capture)

```
WallpaperEngine/Ping:07889:LAPTOP-2H7TH5UT:4:5FB2F0F0-5B62-4DA0-81F1-E15AE1DE94BE
                    ─┬──  ──────┬──────  ┬  ─────────────┬─────────────
                     port        hostname │           deviceGuid
                     (5 chars,             │           (UUID with hyphens)
                      zero-padded)    protocolVersion
                                     (integer, currently 4)
```

- **`port`** = `07889` — the TCP pairing port, zero-padded to exactly 5 chars
  (matches `DiscoverService.beginDiscovery$lambda$0`: `split$default.size == 5 && parts[1].length == 5`)
- **`hostname`** = `LAPTOP-2H7TH5UT` — Windows computer name
- **`protocolVersion`** = `4` — matches `TransferService.CONNECTION_PROTOCOL_VERSION = 4`
- **`deviceGuid`** = `5FB2F0F0-5B62-4DA0-81F1-E15AE1DE94BE` — Windows interface GUID

The phone's `DiscoverService.beginDiscovery$lambda$0` parses this and creates a `DeviceItem`:
```java
deviceItem.setDeviceName(parts[2]);              // hostname
deviceItem.setDeviceIP(datagramPacket.getAddress().getHostAddress());
deviceItem.setDeviceGuid(parts[4]);              // GUID
deviceItem.setPort(parseInt(parts[1]));          // port (7889)
deviceItem.setProtocolVersion(parseInt(parts[3])); // 4
```

## 2. Pairing Protocol (TCP 7889) — VERIFIED ✓

### Wire format: 4-byte LITTLE-ENDIAN length prefix + N bytes of data

This is the only framing. The 4-byte LE int is the length of the following data.
TCP segmentation is irrelevant — the receiver reads 4 bytes for length, then N bytes for data.

Verified from decompiled `TcpClient$Companion.readInt/writeInt`:
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

### Real pairing exchange (Flow 2, port 49330 — successful pairing)

**PC → phone (247 bytes total, 5 frames):**

| Frame | Length | Bytes | Interpretation |
|-------|--------|-------|----------------|
| 0     | 3      | `01 00 01` | **RSA public exponent** = 0x010001 = 65537 (sent as BE BigInteger byte array!) |
| 1     | 128    | 128B blob | **RSA-1024 modulus** (128 bytes = 1024 bits, sent as raw BE bytes) |
| 2     | 32     | 32B blob  | AES-encrypted EncAuthResponse (after phone's frame 3) — see timeline below |
| 3     | 32     | 32B blob  | Second encrypted response |
| 4     | 32     | 32B blob  | Same as frame 3 — duplicate ack / heartbeat-like |

**Phone → PC (586 bytes total, 7 frames):**

| Frame | Length | Bytes | Interpretation |
|-------|--------|-------|----------------|
| 0     | 128    | 128B blob | **RSA-encrypted AES key** (RSA-1024 → 128-byte ciphertext) |
| 1     | 128    | 128B blob | **RSA-encrypted AES IV** (16-byte IV padded to 128B RSA block) |
| 2     | 128    | 128B blob | **RSA-encrypted ???** (third RSA-encrypted block — possibly MAC or session token) |
| 3     | 176    | 176B blob | **AES-encrypted EncAuthRequest** (176B ciphertext = 11 AES blocks, ~160B plaintext) |
| 4     | 4      | `68 65 61 72` ("hear") | First 4 bytes of `"heartbeat\n"` (split by TCP segmentation) |
| 5     | 4      | `74 62 65 61` ("tbea") | Next 4 bytes of `"heartbeat\n"` |
| 6     | 2      | `74 0a` ("t\n")     | Last 2 bytes of `"heartbeat\n"` |

### Timeline (Flow 2)

```
1.  Phone → PC: SYN
2.  PC → phone: SYN-ACK
3.  Phone → PC: ACK                          [TCP handshake complete]
4.  PC → phone: Frame 0 (length=3, exponent)
5.  Phone → PC: ACK
6.  PC → phone: Frame 1 (length=128, modulus)
7.  Phone → PC: ACK
8.  Phone → PC: Frame 0 (length=128, RSA-enc AES key) — first byte `0x80`
9.  PC → phone: ACK
10. Phone → PC: Frames 1+2+3 (395B — RSA-enc IV + RSA-enc ??? + AES-enc EncAuthRequest)
11. PC → phone: ACK
12. Phone → PC: Frame 4 partial (1B, `0x68` = "h")    [heartbeat starts]
13. PC → phone: ACK
14. Phone → PC: 179B (frames 4 partial + 5 + 6 = "heartbeat\n" + more)
                — actually: rest of heartbeat (9B "eartbeat\n") + Frame 3 of PC?
15. PC → phone: Frame 2 (length=32, AES-enc response 1)
16. Phone → PC: ACK
17. PC → phone: Frame 3 (length=32, AES-enc response 2)
18. Phone → PC: ACK
19. PC → phone: Frame 4 (length=32, same as Frame 3 — duplicate)
20. Phone → PC: ACK
21. Phone → PC: Frame 4 partial (1B, `0x68` = "h")    [next heartbeat]
22. PC → phone: ACK
23. Phone → PC: 9B (`65 61 72 74 62 65 61 74 0a` = "eartbeat\n")  [rest of heartbeat]
24. PC → phone: Frame 4 (length=32, same again — periodic ack)
25. Phone → PC: ACK
```

### Key findings vs. previous assumptions

1. **RSA-1024 (NOT RSA-2048)**: The PC sends a 128-byte modulus = RSA-1024.
   Our previous Kotlin code used RSA-2048 (256-byte modulus). **MUST BE CHANGED**.

2. **Exponent is sent FIRST, as a 3-byte BE BigInteger**: `01 00 01` = 0x010001 = 65537.
   Our previous Kotlin code sent modulus first, then exponent as a separate length-prefixed
   BigInteger. **MUST BE CHANGED** — exponent frame goes first, and it's only 3 bytes
   (not the full BigInteger.toByteArray() which would be 4 bytes with a sign byte).

3. **Phone sends 3 RSA-encrypted frames, not 1**: After receiving the PC's RSA pubkey,
   the phone sends:
   - 128B RSA-encrypted AES key
   - 128B RSA-encrypted IV (16-byte IV encrypted with RSA-1024)
   - 128B RSA-encrypted ??? (purpose TBD — possibly a MAC or session token)
   
   Our previous Kotlin code expected only 1 RSA-encrypted frame (just the AES key).

4. **EncAuthRequest is AES-encrypted with 176-byte ciphertext** = 11 AES blocks.
   Plaintext is at most 176 bytes, probably ~160 bytes after PKCS5 padding.
   The JSON `{"pin":"1234","name":"...","deviceUserName":"...","displayWidth":...,
   "displayHeight":...,"version":4,"mpkgVersion":"PKGM0020","appVersion":"..."}` fits
   in ~160 bytes.

5. **EncAuthResponse is 32 bytes** = 2 AES blocks = 16-byte ciphertext = at most 16 bytes
   plaintext (probably `{"status":"ok"}` = 15 bytes + 1 byte PKCS5 padding = 16 bytes
   ciphertext, but with 4-byte length prefix that's 20 bytes, not 32).
   
   Hmm — actually the 32-byte response could be a different message type, or the
   plaintext could be longer (e.g., includes a session token).
   
   Or the 32 bytes might be 2 separate 16-byte messages (each 4+12 = 16 bytes raw),
   but that doesn't quite fit either. **NEEDS MORE RE**.

6. **Heartbeat confirmed**: Phone sends `"heartbeat\n"` (10 ASCII bytes, NO length prefix)
   every ~3 seconds. Split across TCP segments as `hear` (4B) + `tbea` (4B) + `t\n` (2B).
   The server must detect and skip these frames.

### Constants (verified)

```kotlin
// From decompiled source:
MAX_SAFE_COMMAND_LENGTH = 4096
MAX_SAFE_DATA_LENGTH = 1048592  // ~1 MiB
CONNECTION_PROTOCOL_VERSION = 4
MPKG_SUPPORT_VERSION = "PKGM0020"  // current; user's .mpkg sample is PKGM0014 (older)

// From real capture:
RSA_KEY_SIZE = 1024  // (was 2048 in our previous code — WRONG!)
RSA_EXPONENT = 65537  // 0x10001, standard
AES_KEY_SIZE = ?      // 16/24/32 bytes — needs to decrypt to confirm
AES_IV_SIZE = 16      // standard AES-CBC IV
HEARTBEAT_INTERVAL_SEC = ~3
HEARTBEAT_PAYLOAD = "heartbeat\n"  // 10 ASCII bytes, no length prefix
```

## 3. .mpkg Format — VERIFIED from real sample ✓

Sample: `3693384383.mpkg` (1,213,094,580 bytes = 1.16 GB)
Magic: `PKGM0014` (older version; current app uses `PKGM0020`)

### Real binary layout (from hex dump of first 128 bytes)

```
00000000: 08 00 00 00 50 4b 47 4d 30 30 31 34 03 00 00 00   ....PKGM0014....
         ^^^^^^^^^^^ ^^^^^^^^^^^^^^^^^^^^ ^^^^^^^^^^^
         header_marker  8-byte ASCII     entry_count
         (u32 LE = 8)  magic ("PKGM0014") (u32 LE = 3)

00000010: 0b 00 00 00 70 72 65 76 69 65 77 2e 67 69 66 00   ....preview.gif.
         ^^^^^^^^^^^^ ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^ ^^^
         name_len=11   "preview.gif" (11 bytes)     3 bytes
                                                   (start of offset/size)
00000020: 00 00 00 f4 05 0e 00 0c 00 00 00 70 72 6f 6a 65   ...........proje
         ^^^^^^^^^^^^^^^^^ ^^^^^^^^^^^^ ^^^^^^^^^^^^^
         file_offset=0     file_size    name_len=12
         (u32 LE)          (u32 LE=
                            0x000e05f4
                            = 919028)

00000030: 63 74 2e 6a 73 6f 6e f4 05 0e 00 f4 00 00 00 27   ct.json........'
         ^^^^^^^^^^^^^^^^^^ ^^^^^^^^^^^^^^^^^^^^^^ ^^^^^^^^^^
         "project.json"     file_offset        file_size
         (12 bytes)         (u32 LE = 919028)  (u32 LE = 244)

00000040: 00 00 00 5b 43 6f 6e 73 6f 6d 65 5d e6 ac a2 e8   ...[Consome]....
         ^^^^^^^^^^^ ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
         name_len=39 title (UTF-8, includes Chinese chars): "[Consome] 喜歡..."
```

### Full structure

```
[u32 LE: header_marker = 8]                            // length of the magic
[8-byte ASCII magic: "PKGM" + 4-digit version]          // e.g. "PKGM0014" or "PKGM0020"
[u32 LE: entry_count N]
For each entry (in order, no padding between fields):
  [u32 LE: name_len]
  [name_len bytes UTF-8 name]                           // no null terminator
  [u32 LE: file_offset]                                 // RELATIVE to data_section_start
  [u32 LE: file_size]
[data section: file blobs packed back-to-back]
```

### Verified VFS entries from the sample

| Idx | Name                                  | Rel Offset | Size        | Abs Offset | Detected format |
|-----|---------------------------------------|------------|-------------|------------|-----------------|
| 0   | preview.gif                           | 0          | 919,028     | 0x72 (114) | GIF89a ✓        |
| 1   | project.json                          | 919,028    | 244         | 919,142    | JSON            |
| 2   | [Consome] 喜歡 Rouge 膚衣襪涼.mp4     | 919,272    | 1,212,175,194 | 919,386    | MP4 video       |

Total: 919,386 + 1,212,175,194 = 1,213,094,580 = file size ✓ (exactly)

The data section starts immediately after the last VFS entry's file_size field.
For this sample, data_section_start = 0x72 = 114 bytes.

### SceneLib JNI methods that parse this format (from decompiled source)

```java
// io.wallpaperengine.wrapper.SceneLib (native methods implemented in libscenejni.so):
native String getWallpaperType(String filePath);                  // "Scene", "Video", etc.
native VirtualFileRange getWallpaperFileVirtualOffset(String filePath);  // {vOffset, vSize}
native WallpaperInfoSparse[] enumerateWallpapers();
native WallpaperInfoSparse getWallpaperInfoSparse(String filePath);
native byte[] getWallpaperInfoPreviewBitmap(String filePath);
native Point getWallpaperResolution(String filePath);
native boolean isWallpaperVersionValid(String filePath);
native String getWallpaperProjectString(String filePath, String key);
```

The Java side reads virtual files via:
```java
// SupportFileLoader.loadBitmapFromVFS(vfsSource, offset, size):
RangedInputStream(FileInputStream(vfsSource), offset, size)

// Util.getMPKGVideoReader(reader, offsets):
MediaDataSource {
    readAt(position, buffer, offset, size) {
        reader.seek(position + offsets.getVOffset());
        return reader.read(buffer, offset, size);  // bounded by offsets.getVSize()
    }
    getSize() { return offsets.getVSize(); }
}
```

### URI scheme

`Util.URI_PROTOCOL_MPKG_VFS = "MPKG_VFS://"` — wallpapers reference internal
virtual files via this URI scheme.

## 4. What we still don't know (needs more RE)

1. **The third RSA-encrypted frame from the phone** (Frame 2, 128B): What's in it?
   Could be a MAC, a session token, or a hash. Need Frida hooks on `Cipher.doFinal`
   to see the plaintext before encryption.

2. **The 32-byte encrypted responses from the PC** (Frames 2, 3, 4): What's in them?
   Are these the EncAuthResponse, or something else? Why is Frame 4 a duplicate of
   Frame 3?

3. **The exact EncAuthRequest JSON**: We have the ciphertext but can't decrypt without
   the AES key (which requires the RSA private key of the PC that paired).

4. **The .mpkg VFS table byte layout for PKGM0020**: We verified PKGM0014. Need a
   PKGM0020 sample to confirm the format hasn't changed across versions.

5. **`libscenejni.so` internals**: The native library parses the .mpkg. We have the
   JNI method signatures but not the C implementation.

## 5. RE next steps (priority order)

1. **Run the Frida hooks against the official WE Android app** while pairing:
   - Capture `TcpClient.performAuthentication` arguments (pin, customDeviceName, appVersion)
   - Capture `EncAuthRequest` field setters
   - Capture `Cipher.init` calls (key + iv + mode) and `Cipher.doFinal` calls
     (plaintext input + ciphertext output as hex)
   - Capture `RSAPublicKeyHelper.setModulus` / `setExponent` (the RSA pubkey the phone received)
   - Capture `SceneLib.getWallpaperFileVirtualOffset` (VFS offsets for each virtual file)

2. **Compare our standalone pairing server's RSA pubkey with what the phone expects**:
   The PC sent modulus `c6 95 e5 95 ... 86 cd d2` (128 bytes = RSA-1024). If we run our
   own server, we need to also use RSA-1024, and we need to send the exponent FIRST
   as a 3-byte BE BigInteger `01 00 01`.

3. **Capture a PKGM0020 .mpkg file** to confirm the VFS table layout hasn't changed.

4. **Extract `libscenejni.so` from the WE Android APK** and reverse it with Ghidra/IDA
   to find the exact VFS table parser.
