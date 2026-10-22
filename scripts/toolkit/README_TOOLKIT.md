# WepWawet RE Toolkit

A cross-platform (Windows / macOS / Linux) reverse-engineering toolkit for the
Wallpaper Engine Android app and the `.mpkg` wallpaper container format.

All tools are written in pure Python (3.10+) so you can run them on **Windows
without bash**. The only required third-party dependency is `pycryptodome`
(for the standalone pairing server and the RSA keypair generator).

## Quick start (Windows)

```cmd
cd scripts\toolkit
install_deps.bat
run.bat help
```

## Quick start (macOS / Linux)

```bash
cd scripts/toolkit
./install_deps.sh
./run.sh help
```

## Sub-commands

| Command | What it does | Example |
|---|---|---|
| `analyze`   | Inspect a `.mpkg` file: detect `PKGM0020` magic, scan for embedded JSON, locate embedded PNG/JPEG/MKV/MP4, dump VFS table, print hex + ASCII of first 128 bytes. | `run.bat analyze wallpaper.mpkg --full` |
| `pack`      | Pack a wallpaper project directory (containing `project.json` + assets) into a `.mpkg` file. **Heuristic layout** — iterate against the real WE Android app with the Frida hooks. | `run.bat pack ./my_wallpaper out.mpkg` |
| `server`    | Run a standalone WE PC-side pairing server (TCP 7889, RSA-2048 + AES-256-CBC). Use to test the official WE Android app against your own machine. | `run.bat server --pin 1234` |
| `broadcast` | Broadcast `WallpaperEngine/Ping` on UDP 7884 multicast `239.100.0.1` so the official WE Android app discovers this machine. | `run.bat broadcast` |
| `capture`   | Print cross-platform Wireshark/tshark capture commands for the pairing protocol (UDP 7884 + TCP 7889). Also has a `--monitor` mode that listens on UDP 7884 and prints discovery packets in real time without Wireshark. | `run.bat capture --monitor` |
| `decompile` | Download the official WE Android APK from `wallpaperengine.io` and decompile it with `jadx`. | `run.bat decompile` |
| `search`    | Search a decompiled WE Android source tree for the pairing protocol, crypto operations, and `.mpkg` references. | `run.bat search ./we-android-decompiled` |
| `keypair`   | Generate an RSA-2048 keypair (PEM + decimal + hex) for use with `server`. | `run.bat keypair --out key.json` |
| `frida`     | Print a Frida hooks JS script for runtime analysis of the WE Android app. Hooks `TcpClient`, `Cipher`, `RSAPublicKeyHelper`, `SceneLib`, `SupportFileLoader`, `DiscoverService`, `TransferService`. | `run.bat frida --out hooks.js` |

## Pairing protocol (reverse-engineered)

```
DISCOVERY (UDP 7884, multicast 239.100.0.1)
  PC broadcasts: "WallpaperEngine/Ping:<port 5 chars>:<hostname>:<protVer>:<guid>"
  Phone listens on MulticastSocket(7884), 128-byte DatagramPacket.
  Phone creates DeviceItem{name=hostname, ip=srcAddr, port=parts[1],
                           guid=parts[4], protocolVersion=parts[3]}.

PAIRING (TCP 7889, all length prefixes are LITTLE-ENDIAN)
  1. Server sends: <LE 4-byte modulus len> <modulus bytes>
                  <LE 4-byte exponent len> <exponent bytes>
  2. Phone sends:  <LE 4-byte encAesKeyLen> <RSA/ECB/PKCS1Padding(AES key)>
                  <LE 4-byte ivLen = 16>   <16 raw IV bytes>
  3. Phone sends:  <LE 4-byte encAuthReqLen> <AES/CBC/PKCS5Padding(JSON)>
     JSON: {"pin":"1234","name":"Pixel","deviceUserName":"My Phone",
            "displayWidth":1080,"displayHeight":2400,"version":4,
            "mpkgVersion":"PKGM0020","appVersion":"2.21.0"}
  4. Server sends: <LE 4-byte encRespLen> <AES/CBC/PKCS5Padding(JSON)>
     JSON: {"status":"ok"}
  5. Phone sends "heartbeat\n" (10 ASCII bytes, no length prefix) every ~3 s.
     The server must detect and skip these frames, otherwise it misreads the
     'h','e','a','r' bytes as a bogus length prefix (0x72616568 = ~1.9 GiB)
     and bails out of the command loop.

FILE TRANSFER (after pairing, same AES key/IV)
  Length-prefixed LITTLE-ENDIAN AES-encrypted JSON commands.
  .mpkg file is sent via RangedInputStream over the TCP stream.

Constants (from decompiled TcpClient.java):
  MAX_SAFE_COMMAND_LENGTH = 4096
  MAX_SAFE_DATA_LENGTH    = 1048592  (~1 MiB)
  CONNECTION_PROTOCOL_VERSION = 4
  MPKG_SUPPORT_VERSION        = "PKGM0020"
```

## .mpkg format (best-effort RE)

The `.mpkg` container starts with the 8-byte magic `PKGM0020` (verified from
`TransferService.MPKG_SUPPORT_VERSION`). The native library `libscenejni.so`
parses the file to build a VFS (Virtual File System) table. The Java side calls:

- `SceneLib.getWallpaperType(filePath)` → returns `"Scene"`, `"Video"`, etc.
- `SceneLib.getWallpaperFileVirtualOffset(filePath)` → returns `VirtualFileRange{vOffset, vSize}`
- `SupportFileLoader.loadBitmapFromVFS(vfsSource, offset, size)` → reads bytes from
  `RangedInputStream(FileInputStream(vfsSource), offset, size)`
- `Util.getMPKGVideoReader(reader, offsets)` → wraps the .mpkg file in a
  `MediaDataSource` that reads `position + offsets.getVOffset()` for
  `offsets.getVSize()` bytes.

The packer in `mpkg_packer.py` writes the following layout, which is a
**heuristic** pending confirmation from a real `.mpkg` sample or RE'd
`libscenejni.so`:

```
[8-byte magic "PKGM0020"]
[4-byte LE entry count N]
[N * (8-byte LE vOffset, 8-byte LE vSize)]   VFS table
[N * (4-byte LE nameLen, nameLen bytes UTF-8 name)]   name table
[N blobs, packed back-to-back]
```

## Getting a real `.mpkg` sample

1. Pair a real phone with a real PC running Wallpaper Engine.
2. Transfer any wallpaper to the phone.
3. `.mpkg` files are saved at:
   `/storage/emulated/0/Android/data/io.wallpaperengine.weclient/files/`
4. Pull via adb:
   ```bash
   adb pull /sdcard/Android/data/io.wallpaperengine.weclient/files/ ./we-files
   ```
5. Analyze:
   ```bash
   ./run.sh analyze ./we-files/some-wallpaper.mpkg --full
   ```

## Workflow: capturing a real pairing session

1. Run the standalone pairing server on your PC (or just run the APK).
   ```bash
   ./run.sh server --pin 1234
   # in another terminal:
   ./run.sh broadcast
   ```
2. On your phone, open the official WE Android app → Discover devices →
   your PC should appear.
3. Capture the protocol:
   ```bash
   ./run.sh capture --monitor
   # or, in Wireshark:
   #   Capture filter: udp port 7884 or tcp port 7889
   ```
4. Pair with PIN `1234` and let the server log every command.

## Workflow: hooking the official app with Frida

1. Rooted phone, install Frida server (matching version).
2. Generate the hooks:
   ```bash
   ./run.sh frida --out hooks.js
   ```
3. Launch the WE Android app under Frida:
   ```bash
   frida -U -f io.wallpaperengine.weclient -l hooks.js
   ```
4. Pair with a real PC. The hooks will print:
   - `TcpClient.performAuthentication(pin, customDeviceName, appVersion)`
   - `EncAuthRequest` field setters (pin, mpkgVersion, appVersion, displayWidth/Height)
   - `Cipher.init(ENCRYPT/DECRYPT, key, iv)` and `Cipher.doFinal(in) -> out` with hex dumps
   - `RSAPublicKeyHelper.setModulus` and `setExponent`
   - `SceneLib.getWallpaperType` and `getWallpaperFileVirtualOffset` (native JNI)
   - `SupportFileLoader.loadBitmapFromVFS(vfsSource, offset, size)`
   - `DiscoverService.beginDiscovery`

## Environment variables

- `PYTHON` — override the Python interpreter used by `run.bat` / `run.sh`.
  Example on Windows: `set PYTHON=C:\Python312\python.exe`
  Example on Unix:    `PYTHON=/opt/python3.12/bin/python ./run.sh help`

## File layout

```
scripts/toolkit/
├── toolkit.py                 # Main entry point with subcommand dispatch
├── run.bat                    # Windows launcher
├── run.sh                     # Unix launcher
├── install_deps.bat           # Windows: pip install -r requirements.txt
├── install_deps.sh            # Unix:    pip install -r requirements.txt
├── requirements.txt           # pycryptodome
├── sample_project.json        # Sample manifest for mpkg_packer.py
├── mpkg_analyzer.py           # .mpkg file analyzer
├── mpkg_packer.py             # .mpkg file packer / inspector
├── we_pairing_server.py       # Standalone WE PC-side pairing server
├── we_discovery_broadcaster.py # UDP 7884 WallpaperEngine/Ping broadcaster
├── we_protocol_capture.py     # Wireshark/tshark capture command helper
├── we_decompile_android.py    # jadx decompile workflow
├── we_analyze_source.py       # Search decompiled WE source
├── generate_keypair.py        # RSA-2048 keypair generator
└── generate_frida_hooks.py    # Frida hooks JS generator
```

## Cross-platform notes

- All scripts use only the Python standard library + `pycryptodome`.
- No `subprocess` calls except `we_decompile_android.py` invoking `jadx` (which
  you must install separately).
- All file paths use `pathlib.Path` (Windows-safe).
- Length prefixes use `struct.pack("<I", value)` — explicit little-endian,
  matching the decompiled `TcpClient$Companion.readInt/writeInt`.
- The `.bat` launchers handle both `python` and `py -3` (Python launcher for
  Windows) so they work even on bare Windows installs.
