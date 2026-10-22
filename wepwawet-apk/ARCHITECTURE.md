# WepWawet Architecture

> Companion to `docs/RESEARCH_SUMMARY.md` (the *why*) — this document is the *what*.

## TL;DR

WepWawet is an Android APK with five layers:

```
┌───────────────────────────────────────────────────────────────────┐
│  Native Android UI (Jetpack Compose)                             │
│  Workshop browser • Pairing PIN • Settings • Logs                 │
├───────────────────────────────────────────────────────────────────┤
│  Native Pairing Proxy Service (Kotlin)                            │
│  Speaks WE protocol natively: UDP 7884 + TCP 7889 + 4-digit PIN  │
│  ← Sidesteps Wine's bcrypt gap (the killer blocker)              │
├───────────────────────────────────────────────────────────────────┤
│  Wine Process Manager (Kotlin, extends Winlator ForegroundService)│
│  Lifecycle of the emulated Steam + WE under Wine+Box64           │
├───────────────────────────────────────────────────────────────────┤
│  Bundled Emulation Stack (assets/)                                │
│  Box64 (arm64) + Wine 10.x prefix + Mesa Turnip + DXVK + VKD3D   │
│  Ubuntu Focal rootfs (chroot when rooted, PRoot fallback)         │
├───────────────────────────────────────────────────────────────────┤
│  Steam + Wallpaper Engine (running under emulation)              │
│  Authenticates to Steam with user's account, downloads workshop, │
│  renders .mpkg via wallpaper32.exe -control (driven by our proxy) │
└───────────────────────────────────────────────────────────────────┘
                ▲
                │ pairs via
                │ UDP 7884 + TCP 7889 + 4-digit PIN
                │ on 127.0.0.1
                │
        ┌───────┴───────┐
        │ Official WE   │
        │ Android app   │  ← renders the wallpaper as the phone's
        │ (Play Store)  │     live wallpaper
        └───────────────┘
```

## The Wine bcrypt problem and why we sidestep it

Per research Task 1-B (see `docs/RESEARCH_SUMMARY.md`): Wallpaper Engine's pairing handshake uses Windows' `bcrypt` library in a way Wine historically did not implement. The 2022 community report from WE's developer "Biohazard" said the handshake fails on Wine. Wine 9–10 has improved bcrypt significantly, but **whether Wine 10.x actually has the specific primitive WE needs is unverified**.

Rather than betting on Wine's bcrypt catching up — or shipping a brittle `bcrypt.dll` override — **WepWawet implements the pairing protocol natively in Kotlin.** The emulated WE inside Wine is used *only* for:

1. Authenticated workshop downloads (via Steam client)
2. Converting workshop `.pkg` content into mobile `.mpkg` format (via `wallpaper32.exe -control`)
3. Pushing `.mpkg` files to a path our native proxy serves over TCP 7889

The emulated WE never sees a pairing request directly. The official WE Android app pairs with our **native proxy**, which is fully in control of the protocol.

## Pairing protocol (what we know)

Captured from public research and community RE:

| Step | Source → Destination | Wire |
|------|----------------------|------|
| Discovery (PC → broadcast) | `ui32.exe` (PC) → `255.255.255.255:7884` UDP + multicast `239.100.0.1:7884` | `WallpaperEngine/Ping:<pid>:<hostname>:N:<ifaceId>` |
| Discovery response (Android → PC) | Android app → `<pc-ip>:7884` UDP | (TBD by RE — likely `WallpaperEngine/Pong:...`) |
| Pairing PIN exchange (manual) | User reads PIN from PC UI, types into Android app | UI only |
| Pairing handshake | Android → PC TCP `7889` | Encrypted challenge/response using PIN-derived key (likely AES-256-GCM or ChaCha20-Poly1305) |
| Wallpaper transfer | PC → Android TCP `7889` | `.mpkg` file (proprietary, contains transcoded wallpaper assets) |
| Live control | Android → PC TCP `7889` | Commands like "switch wallpaper", "pause", "next" |

**Topology decision (open question, user delegated):** Two paths were considered:

1. **Self-pair via `127.0.0.1`** — cleanest, but requires the official WE Android app to accept `127.0.0.1` as a pairing target (manual IP entry). The Android app almost certainly allows manual IP entry as a discovery fallback, so this should work. **Chosen primary topology.**
2. **mDNS/multicast self-discovery** — elegant but Android's loopback may not propagate multicast between apps; lower-confidence path. Kept as fallback.

## Top-level data flow

```
USER                              WEPWAWET APK                          OFFICIAL WE
─────                             ─────────────                          ANDROID APP
                                                                              
[Open WepWawet]                                                          
       │                                                                    
       ▼                                                                    
[Workshop UI]                                                          
       │                                                                    
       │ taps "Subscribe to wallpaper X"                                
       ▼                                                                    
[SteamApiService]                                                      
       │                                                                    
       │ HTTP POST ISteamRemoteStorage/GetPublishedFileDetails          
       │ (anonymous, no Steam client needed for metadata)              
       ▼                                                                    
[WorkshopRepository]                                                   
       │                                                                    
       │ tells WineProcessService:                                      
       │   "Subscribe to <publishedfileid> on the emulated Steam"       
       ▼                                                                    
[WineProcessService]                                                   
       │                                                                    
       │ launches Steam+WE under Wine+Box64 (if not already running)   
       │ issues: steam://subscribe/<publishedfileid>                   
       ▼                                                                    
[Steam under emulation]                                               
       │                                                                    
       │ downloads workshop content into .pkg                          
       │ notifies our WineProcessService via callback                  
       ▼                                                                    
[WineProcessService]                                                   
       │                                                                    
       │ tells WE via -control:                                         
       │   wallpaper32.exe -control openWallpaper X                    
       ▼                                                                    
[Wallpaper Engine under emulation]                                     
       │                                                                    
       │ renders wallpaper in headless mode                            
       │ prepares .mpkg transcode                                      
       │ writes .mpkg to /sdcard/wepwawet/outbox/                      
       ▼                                                                    
[WineProcessService] → [PairingProxyService]                           
       │                                                                    
       │ proxy detects new .mpkg in outbox                             
       │ ▼                                                                
       │                                                              [Open official WE Android app]
       │                                                                   │
       │ multicast 239.100.0.1:7884 + UDP 7884 to 255.255.255.255       │
       │ ◄───────────────────────── WallpaperEngine/Ping:NNN:host:N ◀──┤
       │                                                                   │
       │ UDP 7884 → 127.0.0.1:7884 (direct)                              │
       │ ───────────────────────────────────────────────────────────────▶│
       │ WallpaperEngine/Pong:...                                         │
       │ ◄───────────────────────────────────────────────────────────────│
       │                                                                   │
       │ User enters 4-digit PIN shown in WepWawet UI                     │
       │ Android app → 127.0.0.1:7889 TCP                                 │
       │ ─────────────────────────────────────────────────────────────────▶│
       │ Encrypted handshake (PIN-derived key)                            │
       │ ◄────────────────────────────────────────────────────────────────│
       │                                                                   │
       │ Proxy pushes .mpkg over TCP 7889                                 │
       │ ─────────────────────────────────────────────────────────────────▶│
       │                                                                   │
       │                                                              [Official WE app applies .mpkg as live wallpaper]
       │                                                                   │
                                                                              
[User closes WepWawet]                                                          
       │                                                                    
       ▼                                                                    
[ProcessLifecycle teardown]                                             
       │                                                                    
       │ stops Steam+WE (saves battery)                                
       │ keeps proxy service in foreground with sticky notification    
       │ (so user can re-pair later without re-launching WepWawet)     
```

## Component inventory

### Layer 1 — Native UI (Jetpack Compose)
- `MainActivity.kt` — Compose nav host
- `ui/WorkshopBrowserScreen.kt` — workshop browse + search + filter + tag chips + wallpaper cards
- `ui/WallpaperDetailScreen.kt` — full preview, tags, author, "subscribe" button
- `ui/PairingScreen.kt` — shows 4-digit PIN, paired-device list, revoke
- `ui/SettingsScreen.kt` — Steam account, root mode, prefix reset, log level
- `ui/LogsScreen.kt` — live logcat-style stream of WepWawet events
- `ui/theme/` — Material 3 dark theme (wallpaper-friendly)

### Layer 2 — Native Pairing Proxy (`proxy/`)
- `PairingProxyService.kt` — foreground service, owns UDP+TCP servers
- `WeDiscoveryServer.kt` — UDP 7884 listener, replies to `WallpaperEngine/Ping`
- `WeControlServer.kt` — TCP 7889 listener, implements pairing handshake + control protocol
- `PairingCrypto.kt` — PIN-derived key derivation (target: PBKDF2-HMAC-SHA256 → AES-256-GCM)
- `MpkgTransporter.kt` — streams `.mpkg` files from outbox over TCP
- `PairedDeviceStore.kt` — persistent pairing tokens (per-device shared secret)

### Layer 3 — Wine Process Manager (`wine/`)
- `WineProcessService.kt` — extends Winlator `ForegroundService`, owns Wine process lifecycle
- `Box64Runner.kt` — wraps Box64 invocation, manages DynaCache, sets `WINLATOR_GLIBC=1`
- `WinePrefixManager.kt` — extracts/first-boot-configures the bundled Wine prefix
- `RootfsManager.kt` — extracts Ubuntu Focal rootfs, chooses chroot (root) vs PRoot (no root)
- `WeControlClient.kt` — invokes `wallpaper32.exe -control <cmd>` inside the prefix
- `SteamLifecycleObserver.kt` — observes Steam process state, restart on crash, exit on idle timeout

### Layer 4 — Bundled Emulation (assets/)
- `assets/rootfs/ubuntu-focal-arm64.tar.zst` (~350 MB)
- `assets/prefix/wine-10.x-arm64.tar.zst` (~500 MB)
- `assets/box64/box64-arm64-winlator` (~25 MB, `.wcp`-flavored binary from Box64 CI)
- `assets/turnip/libvulkan_turnip.so` (~30 MB, Mesa Turnip from whitebelyash fork)
- `assets/dxvk/dxvk-1.10.3.tar.zst` (~15 MB)
- `assets/vkd3d/vkd3d-2.12.tar.zst` (~10 MB)
- `assets/wine-dll-overrides/*.dll.so` — bcrypt shim if Wine's bcrypt is insufficient (research spike 1-F was inconclusive — left as a stub)

### Layer 5 — Emulated Steam + WE
- Standard Steam install (downloaded at first run from `steam-install/wine` script)
- Standard Wallpaper Engine install (subscribed via Steam after first login)
- **Headless mode** configured via `wallpaper32.exe -control windowstate hidden` and Steam's `-nofriendsui -noverifyfiles -skipinitialbootstrap`

### Cross-cutting
- `data/` — workshop data layer (Steam API client, repository, models)
- `auth/` — Steam login WebView + credential storage (Keystore-encrypted)
- `log/` — extensive logging infrastructure (per user's explicit request)
  - `WepWawetLogger.kt` — entry point, structured logging
  - `LogcatRingBuffer.kt` — bounded ring buffer of recent log entries
  - `LogPersistenceWorker.kt` — WorkManager job that flushes logs to `wepwawet-YYYYMMDD.log` daily
  - `LogUploader.kt` — share-log-file intent for debugging
  - `CrashHandler.kt` — global exception handler, captures native Wine crashes too

## Build pipeline

```
GitHub Action: push to main / tag v*                        
       │                                                      
       ▼                                                      
[Pull GHCR image: wepwawet/builder:latest]                  
       │                                                      
       │ image contains: Android NDK 26, Android SDK 35,    
       │ Mesa build deps, Wine build deps, DXVK, VKD3D,      
       │ Box64 cross-build env, ccache                       
       ▼                                                      
[Matrix job — 5 parallel builds]                             
   ├─ box64        → box64-arm64-winlator.wcp                
   ├─ wine         → wine-10.x-arm64-prefix.tar.zst          
   ├─ mesa-turnip  → libvulkan_turnip.so                    
   ├─ dxvk         → dxvk-1.10.3.tar.zst                     
   └─ vkd3d        → vkd3d-2.12.tar.zst                      
       │                                                      
       ▼                                                      
[Assemble APK]                                               
   ./gradlew assembleRelease bundleRelease                   
       │                                                      
       ▼                                                      
[Sign with keystore from GitHub Secret]                     
       │                                                      
       ▼                                                      
[Upload to GitHub Releases]                                  
   if APK > 2 GiB: split into chunks of 1.9 GiB              
   release notes auto-generated from CHANGELOG.md           
```

Estimated build time:
- **Cold**: ~90 minutes (Wine is the slow leg)
- **Warm** (ccache hit): ~50 minutes

## Open work

This skeleton establishes the architecture. To reach v1, these remain:

### Reverse engineering (highest priority)
- [ ] **Capture the WE pairing handshake** with Wireshark on a real PC+phone pairing session. Goal: confirm exact wire format of TCP 7889 messages, including crypto handshake.
- [ ] **Decompile `io.wallpaperengine.weclient`** with jadx (`curl -L -o we.apk https://www.wallpaperengine.io/android/apk/wallpaper-engine.apk`). Extract the pairing client code (Java/Kotlin, possibly JNI). Confirm PIN-derived key algorithm.
- [ ] **Decode `.mpkg` format**. Either (a) capture a real `.mpkg` from a PC→phone push and reverse it, or (b) extract the parser from the WE Android APK. Likely structure: ZIP container with `manifest.json` + transcoded PNG textures + transcoded MP4 video.
- [ ] **Verify Wine 10.x bcrypt coverage** for WE's needs. If insufficient, write a `bcrypt.dll.so` Wine override implementing just the missing primitives.

### Implementation
- [ ] Complete `WeDiscoveryServer.kt` UDP 7884 protocol (wire format TBD pending RE)
- [ ] Complete `WeControlServer.kt` TCP 7889 protocol (wire format TBD pending RE)
- [ ] Complete `PairingCrypto.kt` once PIN-derivation algorithm is confirmed
- [ ] Complete `WinePrefixManager.kt` — first-boot Steam + WE install automation
- [ ] Implement `WeControlClient.kt` — full coverage of `wallpaper32.exe -control` commands
- [ ] Implement Steam login WebView (`SteamLoginActivity.kt`) with Steam Guard support
- [ ] Implement workshop UI: pagination, search, tag filtering, subscriptions management

### Testing
- [ ] Unit tests for proxy crypto once algorithm confirmed
- [ ] Integration test: real Wine+Steam+WE on a phone, proxy pairs with real Android WE app
- [ ] Long-running stability test (8+ hours, watch for Steam memory leaks)

### Distribution
- [ ] Finalize license (Apache 2.0 + LGPL source archives vs. GPL 3)
- [ ] Publish GHCR builder image
- [ ] First tagged release `v0.1.0-alpha`

## Architectural decisions log

| # | Decision | Date | Rationale |
|---|----------|------|----------|
| ADR-001 | Fork Winlator v11.2, embed as library | 2026-10-21 | Only stack with Android foreground service; LGPL; modular Java; Box64 already CI-builds `.wcp` for it |
| ADR-002 | Native Android pairing proxy, NOT emulated WE pairing | 2026-10-21 | Wine bcrypt gap (Task 1-B) is unverified as fixed; native proxy sidesteps the entire problem |
| ADR-003 | Self-pair via 127.0.0.1 (manual IP entry in official WE app) | 2026-10-21 | Avoids Android multicast-between-apps uncertainty; manual IP entry is a documented WE Android fallback |
| ADR-004 | Anonymous ISteamRemoteStorage for workshop metadata, full Steam for downloads | 2026-10-21 | Task 1-C confirmed anonymous access for `GetPublishedFileDetails`; key-gated endpoints not needed for browse-only |
| ADR-005 | GHCR Docker image as build environment | 2026-10-21 | Task 1-E confirmed Wine build is the slow leg (~60-90 min cold); pre-baked image cuts wall time by ~70% |
| ADR-006 | Single APK with bundled assets, no OBB | 2026-10-21 | User is sideloading, not on Play Store; OBB complicates sideload. APK size limit ~200MB play-side is irrelevant for sideload |
| ADR-007 | Root-gated chroot over PRoot when root available | 2026-10-21 | PRoot adds 10-20% syscall overhead; user has root, so use chroot for measurable Wine launch speedup |
| ADR-008 | Use Box64 with `WINLATOR_GLIBC=ON` | 2026-10-21 | Required for Steam to launch under Winlator (confirmed by ivonblog tutorial + Box64 issue #2613) |
| ADR-009 | Keep `linux-wallpaperengine` as a long-term fallback | 2026-10-21 | If Wine+Box64 WE proves too heavy on phones, LWE native rendering + proxy is the escape hatch |
