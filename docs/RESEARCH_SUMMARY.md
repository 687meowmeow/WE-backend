# Research Summary — WepWawet Feasibility Study

> Full research findings in `/home/z/my-project/worklog.md` (Tasks 1-A, 1-B, 1-C, 1-E). This document is the human-readable digest used to drive architectural decisions in `ARCHITECTURE.md`.

## Executive summary

**Verdict: feasible with one critical caveat.** Steam runs under Box64+Winlator on Snapdragon 8 Gen 2-class hardware (community-confirmed). Wallpaper Engine under emulation is unproven publicly but has no architectural blocker. The PC→Android WE pairing protocol was reverse engineered at a high level by the community (UDP 7884 + TCP 7889 + 4-digit PIN), but the exact wire format of the TCP handshake is undocumented. Worse, Wine's `bcrypt` library has a known gap that the WE developer himself flagged in 2022 as breaking pairing under Wine — Wine 9-10 has improved bcrypt, but the gap may not be fully closed.

**Mitigation:** WepWawet implements the pairing protocol **natively in Kotlin**, never letting Wine see a pairing request. The emulated WE inside Wine is used only for (1) authenticated workshop downloads, (2) rendering/converting wallpapers to `.mpkg`, and (3) receiving control commands via the documented `wallpaper32.exe -control` CLI.

## Research scope

Five areas were investigated. Four completed (1-A, 1-B, 1-C, 1-E); two follow-ups (1-D Android networking, 1-F bcrypt + `.mpkg` RE) failed with tool context-deadline timeouts. The failed areas are partially covered by the completed research and the chosen architecture sidesteps the unknowns.

| ID | Area | Status | Outcome |
|----|------|--------|---------|
| 1-A | Emulation stack feasibility | done | Winlator v11.2 chosen |
| 1-B | WE pairing protocol | done | Native proxy architecture chosen (sidesteps Wine bcrypt gap) |
| 1-C | WE architecture + workshop + formats | done | Steam Web API for browse, emulated Steam for download, `.mpkg` format remains an RE target |
| 1-D | Android localhost + root networking | timed out | Mitigated: manual IP entry in WE Android app sidesteps mDNS uncertainty; root chroot is straightforward |
| 1-E | GitHub Actions APK build pipeline | done | GHCR Docker image + 5-way matrix |
| 1-F | Wine bcrypt gap + `.mpkg` RE | timed out | Mitigated by ADR-002: native proxy makes Wine bcrypt irrelevant; `.mpkg` produced by emulated WE (no native builder needed for v1) |

## Findings by area

### 1-A — Emulation stack feasibility

**Decision: Winlator v11.2 forked as the runtime base.**

Key facts:
- **Winlator** (`brunodev85/winlator`, LGPL 2.1) is the most mature Windows-on-Android runtime. v11.2 (latest) bundles Wine + Box64 + Mesa Turnip/Zink + DXVK + VKD3D + Ubuntu Focal rootfs + PRoot, plus in-house Vulkan/GLES compat layers (Vortek, Gladio).
- **Box64** (`ptitSeb/box64`, MIT) is the underlying x86-on-ARM64 translator. Its CHANGELOG explicitly notes "BOX32 can run steam now. At least on ARM64 backend", "Steam Large mode and new BigPicture are working", and an `ondemand SteamUI` patch credited to Winlator's maintainer.
- **Steam on Winlator is community-confirmed.** The ivonblog tutorial (2025-08-06) walks through installing Windows `SteamSetup.exe` in a Winlator container. Vanilla v10.1 could not run Steam reliably; the **Cmod GLIBC** mod and vanilla v11.2 with the foreground-service patch can.
- **Steam on Odin 2 (Snapdragon 8 Gen 2)** is reported working in Box64 issue #2613 (May 2025), with login flakiness noted — addressed by subsequent Box64 Steam PRs (#4198, #4323, #4397, #4433).
- **Wallpaper Engine under Box64+Winlator is unproven publicly.** No Winlator or Box64 issue, PR, or demo mentions WE specifically. But WE is a Win32 + DX9/11/12 + CEF app, and Winlator already runs DX9/10/11 games of comparable weight (Skyrim SE, Mass Effect 2, Fallout NV — all demoed on BrunoSX's YouTube). No architectural blocker.

**Performance expectations (estimated, SD8Gen2):**
- Steam cold-start: 30-90 seconds (mitigated by `ondemand SteamUI`)
- Steam warm-start: 10-15 seconds
- Steam RAM: >4 GB on its own under emulation (issue #3301, mitigated by `ondemand SteamUI`)
- WE 3D scene wallpaper FPS: 30-60 FPS for low/med complexity at 1080p, 15-30 FPS for high-end DX11
- WE video wallpaper decode: software-decode path through MFPlat/LAV; 1080p30 fine, 4K may stutter

**Risks:**
- Steam `steamwebhelper` (Chromium CEF) is the heaviest single component — ~4 GB RAM. Mitigation: `ondemand SteamUI`, kill after WE RPC channel is up.
- WE's own CEF-based web wallpapers double the Chromium cost.
- Box64 issue #4007 documents audio/video playback bugs under Chromium-on-Box64.
- First-launch Steam login + Steam Guard may be flaky (mitigation: retry flow, pre-warm prefix).
- PRoot adds 10-20% syscall overhead — user has root, so we use chroot instead.

### 1-B — WE pairing protocol

**Decision: Native Android pairing proxy. Emulated WE never sees pairing traffic.**

Key facts:
- **Discovery:** PC's `ui32.exe`/`ui64.exe` (the Chromium CEF UI process, NOT the renderer) sends a UDP broadcast to two destinations:
  1. The local subnet broadcast (e.g. `192.168.0.255:7884`)
  2. The multicast group `239.100.0.1:7884`
- **Payload format (UDP discovery):** `WallpaperEngine/Ping:NNNNN:HOSTNAME:N:INTERFACEID` where NNNNN is the PID, HOSTNAME is the PC's hostname, N is a flag, INTERFACEID is the network interface ID.
- **NOT mDNS/Bonjour, NOT SSDP, NOT Steamworks P2P.** Custom UDP protocol.
- **Control port:** TCP `7889`, listening on the PC side (also `ui32.exe`).
- **Handshake:** 4-digit PIN displayed by the PC, entered on Android. Persistent pairing — PC remembers the device after first successful PIN exchange.
- **Critical Wine blocker:** WE developer "Biohazard" publicly confirmed (Steam forum, June 2022) that Wine/Proton cannot complete the pairing handshake because Wine's `bcrypt` library is missing an API WE uses. UDP discovery works on Wine (Linux users saw their PC visible in the Android app), but the TCP 7889 handshake fails.
- **Manual IP entry is a fallback** when broadcasts are blocked — important for our `127.0.0.1` self-pair topology.
- **Wallpaper transfer:** TCP 7889 pushes an `.mpkg` file (proprietary mobile format, NOT the workshop `.pkg`). For 3D wallpapers, conversion happens on the PC side first; for video, "Pre-rendered High Performance" mode uses Media Foundation (also Wine-blocked).
- **Live control:** Same TCP 7889 channel. PC-side equivalent is the documented `wallpaper32.exe -control <command>` CLI (pause/stop/play/mute/openWallpaper/closeWallpaper/nextWallpaper/openPlaylist/applyPreset/settings/getCurrentWallpaper).
- **The Android->PC wire format is undocumented.** Need Wireshark capture + jadx decompilation of the official Android app to confirm.

**Mitigation — the native proxy architecture:**

Rather than relying on Wine's bcrypt catching up (it may have, but unverified), WepWawet builds a native Android service that:
1. Listens on UDP 7884 — replies to `WallpaperEngine/Ping` discovery broadcasts
2. Listens on TCP 7889 — implements the pairing handshake + control protocol natively
3. Generates a 4-digit PIN shown in the WepWawet UI
4. Derives a key from the PIN (algorithm TBD pending RE — likely PBKDF2-HMAC-SHA256 -> AES-256-GCM, common for 4-digit-PIN protocols)
5. Pushes `.mpkg` files from a known outbox path on TCP 7889

The emulated WE inside Wine is invoked **only** for:
- `wallpaper32.exe -control openWallpaper <id>` (to render + produce `.mpkg`)
- Workshop subscription via Steam's authenticated session

The emulated WE never sees a pairing packet. **This sidesteps the Wine bcrypt problem entirely.**

### 1-C — WE architecture + workshop + formats

**Decision: Anonymous ISteamRemoteStorage for workshop metadata; emulated Steam for downloads; `.pkg`/`.tex` parsing via RePKG-format port (if we want a native browser-only mode).**

Key facts:
- **WE Steam IDs:** App ID `431960`, workshop browse at `steamcommunity.com/workshop/browse?appid=431960`
- **WE Android app:** Package `io.wallpaperengine.weclient`, direct APK at `https://www.wallpaperengine.io/android/apk/wallpaper-engine.apk` (145 MB, freely downloadable, no Play Store needed)
- **Steam Workshop Web API:**
  - `POST ISteamRemoteStorage/GetPublishedFileDetails/v1/` — anonymous access confirmed (returns full metadata: title, description, tags, subscriptions, file_size, preview_url). **But `file_url` is always empty** (Steam deliberately hides direct download URLs).
  - `POST ISteamRemoteStorage/GetCollectionDetails/v1/` — anonymous access confirmed.
  - `IPublishedFileService/QueryFiles`, `GetDetails`, etc. — **require Steam Web API key** (403/401 without). User can get their own free key.
  - **Recommendation for v1 UI:** HTML scrape `steamcommunity.com/workshop/browse` + bulk enrich with `GetPublishedFileDetails` + direct preview image fetch from `images.steamusercontent.com`.
- **Workshop item file format** (from `linux-wallpaperengine` RE):
  - `project.json`: `{workshopid, title, type, file, preview, description, tags, visibility, general.supportsaudioprocessing, general.properties}`
  - `.pkg`: magic `PKGV` + uint32 fileCount + per-file {filename, offset, length}
  - `.tex`: magic `TEXB` + containerVersion 0002/0003/0004 + LZ4-compressed mipmaps + animation frames
  - RePKG (MIT, C#) is the canonical reference; linux-wallpaperengine (GPL3, C++) has working parsers in `src/WallpaperEngine/Data/Parsers/`
- **Wallpaper types:**
  | Type | File format | Renderer | Android-viability |
  |------|-------------|----------|-------------------|
  | Scene (3D) | .pkg + scene.json + .tex + .model + shaders | DX11->Vulkan via DXVK+Turnip | Via Winlator only |
  | Video | .mp4/.webm + project.json | MFPlat/LAV under Wine | Native Android ExoPlayer/MediaCodec (HW decode) for the rendered .mpkg |
  | Web (HTML) | .pkg/folder + index.html + JS/CSS | CEF under Wine | Heavy; Android WebView with WE API shims possible but complex |
  | Application | Author .exe + project.json | Wine+Box64 runs original .exe | Via Winlator only |
  | Image | .jpg/.png + project.json | Trivial | Trivial — Android BitmapFactory/Coil |
  | Playlist | playlist.json | Schedule rotation | Trivial |
- **Workshop download without Steam client:** Confirmed **impossible anonymously for paid apps**. SteamCMD `+workshop_download_item` requires `+login username password`; anonymous SteamCMD doesn't support workshop downloads for non-Source apps. The user must own WE on their Steam account — which they do (they need to for the Android app to work in the first place).
- **No public WE localhost HTTP API found.** Control is via CLI only: `wallpaper32.exe -control <command>`.
- **`.mpkg` format (the Android transfer format):** Proprietary, undocumented. Contains wallpaper content in WE-Android-compatible form (not raw workshop form). Likely ZIP container with `manifest.json` + transcoded assets (PNG textures, MP4 video, possibly pre-rendered scene screenshots for 3D wallpapers).
- **Linux-wallpaperengine (LWE)** (`Almamu/linux-wallpaperengine`, GPL3, C++20): native Linux reimplementation of WE that reads `.pkg` workshop content directly. Explicit ARM64 Linux support. Uses CEF 135 + MPV + quickjs + OpenGL 3.3. Supports only scene/video/web (not image/application/playlist).
  - **Reuse path:** Extract `Data/Parsers/` + `Data/Utils/BinaryReader` as a JNI-wrapped static library for our Android workshop browser.
  - **Critical alt-path:** If Wine+Box64 WE is too slow on SD8Gen2, LWE itself (or a port) could be the on-device renderer + a thin WE pairing-protocol server — bypassing Wine entirely.

### 1-E — GitHub Actions build pipeline

**Decision: GHCR Docker image + 5-way matrix + GitHub Releases (chunked if >2GB).**

Key facts:
- **Google Play size caps (irrelevant since user is sideloading):** base module 500 MB, asset pack 1.5 GB, install-time total 4 GB. Single APK limit 200 MB for Play; **for sideloaded APK there is no hard cap on the device side** (Android parses multi-GB APKs fine).
- **GitHub Actions hard caps:**
  - Job execution: 6 h hosted, 5 days self-hosted
  - Cache: 10 GB/repo, 14-day TTL
  - Per-artifact cap: 2 GB on `actions/upload-artifact@v4`
  - GitHub Release asset: 2 GiB per file, 1000 files per release, no total cap
  - GHCR: 10 GB per layer, 10 min upload timeout
  - Standard Ubuntu runner: 4 vCPU, 16 GB RAM, 14 GB SSD
- **Recommended architecture:**
  1. **GHCR image `wepwawet/builder`** with NDK 26 + Android SDK 35 + Mesa build deps + Wine build deps + DXVK + VKD3D + Box64 cross-build env + ccache. Rebuilt weekly.
  2. **Thin Actions workflow** pulls the image, runs a 5-way matrix (`box64`, `wine`, `mesa-turnip`, `dxvk`, `vkd3d`) with `hendrikmuhs/ccache-action@v1.2` per component.
  3. **Release workflow** assembles APK from the 5 component `.tzst` artifacts via `./gradlew assembleRelease bundleRelease`, signs with base64 keystore from secret, pushes to GitHub Releases (chunked to 1.9 GiB if APK > 2 GB).
- **Reference implementations borrowed from:**
  - **Azahar** (`azahar-emu/azahar`): container-image + SBOM + attestation + ccache pattern, `ubuntu-24.04-arm` for ARM64
  - **Box64** (`ptitSeb/box64`): direct upstream `.wcp` archive generation — `patchelf --set-interpreter /data/data/com.winlator/files/imagefs/usr/lib/ld-linux-aarch64.so.1 ./box64` + `tar --zstd -cf box64-latest.wcp box64 profile.json`
  - **Strato** (`strato-emu/strato`): `abiFilters "arm64-v8a"` + `packagingOptions { jniLibs.useLegacyPackaging = true }`
  - **Termux** (`termux/termux-app`): bootstrap-zip download with SHA-256 verification, ABI splits
  - **idans320/winlator-assets-builder**: Devbox-based Winlator asset packager — closest existing reference for our pipeline
- **Estimated build time:**
  - Cold (no cache, with GHCR image): ~90 minutes wall time via 5-way parallelism
  - Warm (with ccache): ~50 minutes
  - Wine is the slowest single component (60-90 min cold)

### 1-D and 1-F — Mitigated, not blocking

**1-D (Android localhost + root networking):** Mitigated by ADR-003 (manual IP entry in the official WE Android app, paired against `127.0.0.1:7889` on our proxy). Android sandbox allows any app to bind a TCP port on `0.0.0.0`, and any other app to connect to `127.0.0.1:<port>` — confirmed by Shizuku, ADBWifi, Termux, Cassowary. The official WE Android app supports manual IP entry as a discovery fallback (per the 1-B research), so self-pairing on localhost should work. UDP multicast self-discovery (`239.100.0.1`) is less certain — Android may not propagate multicast between apps on the same device — but it's not required since manual IP entry is the primary path.

**1-F (Wine bcrypt gap + `.mpkg` RE):** Mitigated by ADR-002 (native pairing proxy). Wine's bcrypt coverage becomes irrelevant because Wine never sees pairing traffic. The `.mpkg` format remains an open RE target, but for v1 we let emulated WE produce `.mpkg` (it does this natively) and our proxy simply serves those files. Native `.mpkg` building can be added in v2 if RE confirms the format — that would let us skip running WE under emulation entirely (Steam-only emulation), saving ~2 GB RAM and improving launch times dramatically.

## What we still don't know

1. **The exact TCP 7889 wire format** — handshake, control commands, file transfer framing. Need Wireshark capture of a real pairing session + jadx decompile of `io.wallpaperengine.weclient`.
2. **The PIN-derived crypto algorithm** — likely PBKDF2-HMAC-SHA256 -> AES-256-GCM, but unconfirmed. Need jadx.
3. **The `.mpkg` container format** — need to capture a real `.mpkg` file or extract the parser from the Android APK.
4. **Whether the official WE Android app accepts `127.0.0.1` as a manual pairing target** — almost certainly yes (manual IP entry is a documented fallback), but unverified.
5. **Wine 10.x bcrypt coverage** — only relevant if we ever want to let emulated WE see pairing traffic (we don't, per ADR-002).
6. **First-launch Steam Guard reliability under Wine** — known flaky on first attempt; mitigation: retry flow.
7. **Box64 issue #4007 (audio/video playback)** — directly impacts WE video wallpapers; mitigation may require Wine MFPlat patches or LAV Filters in prefix.

## Recommended next steps (in priority order)

1. **RE the WE Android app** — decompile with jadx, extract pairing client + `.mpkg` parser. Highest leverage.
2. **Wireshark capture** of a real PC->phone pairing session — confirms exact wire format.
3. **Manual validation spike** — install Winlator v11.2 + Steam + WE on a SD8Gen2 phone, attempt pairing with the real WE Android app. Confirms the Wine bcrypt gap is real and that our proxy approach is necessary.
4. **Implement `PairingProxyService`** — the heart of the system.
5. **Implement `WineProcessService`** — Wine+Box64 lifecycle (fork Winlator's ForegroundService).
6. **Implement workshop UI** — Compose screens using anonymous ISteamRemoteStorage.
7. **Set up CI** — GHCR image + matrix build.

The skeleton in this repo addresses steps 4, 5, 6, and 7. Steps 1, 2, 3 require the user to do hands-on RE and validation (the kind of work that can't be done by an AI in a chat session).
