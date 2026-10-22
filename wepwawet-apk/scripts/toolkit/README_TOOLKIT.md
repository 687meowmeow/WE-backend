# WepWawet RE Toolkit (v2 — verified against real capture)

A cross-platform (Windows / macOS / Linux) reverse-engineering toolkit for the
Wallpaper Engine Android app and the `.mpkg` wallpaper container format.

All tools are written in pure Python (3.10+) so you can run them on **Windows
without bash**. The only required third-party dependency is `pycryptodome`
(for the standalone pairing server and the RSA keypair generator).

## PROTOCOL VERIFIED FROM REAL CAPTURE (2026-10-22)

The protocol implementation in this toolkit is **VERIFIED** against a real
Wireshark capture between a Windows PC running Wallpaper Engine and the
official WE Android app. See `docs/RE_FINDINGS_VERIFIED.md` for the full
decoded protocol.

Key findings:

1. **Discovery (UDP 7884)** — `WallpaperEngine/Ping:07889:LAPTOP-NAME:4:<GUID>`,
   broadcast every ~1.3s to multicast 239.100.0.1 + subnet broadcast + loopback.

2. **Pairing (TCP 7889, 4-byte LITTLE-ENDIAN length prefixes)**:
   - PC sends: Frame 0 = 3-byte RSA exponent (`01 00 01` = 65537)
   - PC sends: Frame 1 = 128-byte RSA-1024 modulus (NOT RSA-2048!)
   - Phone sends: 3 × 128B RSA-encrypted frames (AES key + IV + ???)
   - Phone sends: 176B AES-encrypted EncAuthRequest
   - After pairing: phone sends literal ASCII `heartbeat\n` (10B, no length prefix)

3. **.mpkg format (verified from real 1.16 GB sample, magic "PKGM0014")**:
   ```
   [u32 LE header_marker = 8]
   [8-byte ASCII magic "PKGMvvvv"]
   [u32 LE entry_count N]
   For each entry:
     [u32 LE name_len]
     [name_len bytes UTF-8 name]
     [u32 LE file_offset (RELATIVE to data_section_start)]
     [u32 LE file_size]
   [data section: file blobs packed back-to-back]
   ```

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
| `analyze`   | Inspect a `.mpkg` file: detect PKGM magic, parse the VFS table (REAL format), locate embedded PNG/JPEG/MKV/MP4/GIF, extract virtual files. | `run.bat analyze wallpaper.mpkg --full --extract ./out` |
| `pack`      | Pack a wallpaper project directory (containing `project.json` + assets) into a `.mpkg` file using the REAL format. | `run.bat pack ./my_wallpaper out.mpkg` |
| `server`    | Run a standalone WE PC-side pairing server (TCP 7889, RSA-1024 + AES-CBC) using the verified protocol. | `run.bat server --pin 1234` |
| `broadcast` | Broadcast `WallpaperEngine/Ping` on UDP 7884 (multicast + subnet broadcast + loopback) every 1.3s. | `run.bat broadcast` |
| `capture`   | Print cross-platform Wireshark/tshark capture commands. Also has a `--monitor` mode that listens on UDP 7884 and prints discovery packets in real time. | `run.bat capture --monitor` |
| `decompile` | Download the official WE Android APK from `wallpaperengine.io` and decompile it with `jadx`. | `run.bat decompile` |
| `search`    | Search a decompiled WE Android source tree for the pairing protocol, crypto operations, and `.mpkg` references. | `run.bat search ./we-android-decompiled` |
| `keypair`   | Generate an RSA-1024 keypair (matches the real protocol — verified). | `run.bat keypair --out key.json` |
| `frida`     | Print a Frida hooks JS script for runtime analysis of the WE Android app. | `run.bat frida --out hooks.js` |

## File layout

```
scripts/toolkit/
├── toolkit.py                 # Main entry point with subcommand dispatch
├── run.bat                    # Windows launcher
├── run.sh                     # Unix launcher
├── install_deps.bat           # Windows: pip install -r requirements.txt
├── install_deps.sh            # Unix:    pip install -r requirements.txt
├── requirements.txt           # pycryptodome
├── mpkg_analyzer.py           # .mpkg file analyzer (REAL format)
├── mpkg_packer.py             # .mpkg file packer / inspector (REAL format)
├── we_pairing_server.py       # Standalone WE PC-side pairing server (REAL protocol)
├── we_discovery_broadcaster.py # UDP 7884 WallpaperEngine/Ping broadcaster (VERIFIED)
├── we_protocol_capture.py     # Wireshark/tshark capture command helper + monitor
├── we_decompile_android.py    # jadx decompile workflow
├── we_analyze_source.py       # Search decompiled WE source
├── generate_keypair.py        # RSA-1024 keypair generator (matches real protocol)
└── generate_frida_hooks.py    # Frida hooks JS generator
```

## Environment variables

- `PYTHON` — override the Python interpreter used by `run.bat` / `run.sh`.
  Example on Windows: `set PYTHON=C:\Python312\python.exe`
  Example on Unix:    `PYTHON=/opt/python3.12/bin/python ./run.sh help`
