# Changelog

All notable changes to WepWawet are documented here.
Format roughly follows Keep-a-Changelog; dates are UTC.

## [Unreleased]

### Added — project skeleton
- Architecture decisions documented in `ARCHITECTURE.md`
- Research findings from Tasks 1-A, 1-B, 1-C, 1-E documented in `docs/RESEARCH_SUMMARY.md`
- Project Gradle setup (Kotlin 2.0.21 + AGP 8.7 + Compose Compiler 2.0.21 + Compose BOM 2024.10.01)
- AndroidManifest with:
  - PairingProxyService (foreground service, type dataSync)
  - WineProcessService (foreground service, type specialUse)
  - FileProvider for .mpkg outbox
  - Permissions: INTERNET, FOREGROUND_SERVICE_*, WAKE_LOCK, MULTICAST, POST_NOTIFICATIONS
- Native Android core:
  - WepWawetApplication (logger + crash handler init, notification channels)
  - MainActivity (single-Activity Compose nav host)
- Extensive logging infrastructure (per user's explicit request):
  - WepWawetLogger (6 levels, 7 domains, structured key-value context, 2000-entry ring buffer)
  - LogPersistenceWorker (WorkManager daily flush, 14-day prune)
  - CrashHandler (Java + best-effort native crash capture, full crash report to disk)
- Workshop data layer:
  - SteamRemoteStorageApi (Retrofit interface to ISteamRemoteStorage anonymous endpoints)
  - SteamApiService (browse via HTML scrape + batch GetPublishedFileDetails enrichment)
  - WorkshopRepository (paginated browse, in-memory subscription state)
  - WorkshopItem model (with WallpaperType derivation from tags)
- Pairing proxy layer (the heart of the system — sidesteps Wine bcrypt gap):
  - PairingProxyService (foreground service, owns UDP 7884 + TCP 7889)
  - WeDiscoveryServer (broadcasts WallpaperEngine/Ping on subnet + 239.100.0.1 + 127.0.0.1)
  - WeControlServer (TCP 7889 listener; logs every received byte pending RE)
  - PairingCrypto (PBKDF2-HMAC-SHA256 -> AES-256-GCM — hypothesized pending RE)
  - PairedDeviceStore (persistent pairing tokens)
  - MpkgTransporter (outbox poller + push queue — wire format pending RE)
- Wine process layer:
  - WineProcessService (lifecycle orchestration, foreground notification)
  - Box64Runner (env composition, ProcessBuilder invocation, stdout pump to logger)
  - WinePrefixManager (extraction, chroot/PRoot mode, rootfs bind mounts)
  - WeControlClient (wallpaper32.exe -control wrapper)
  - SteamLifecycleObserver (readiness polling, crash detection)
- Compose UI:
  - WepWawetNavHost (4 top-level destinations + detail screen)
  - WorkshopBrowserScreen (search, type filter chips, paginated list)
  - WallpaperDetailScreen (preview, metadata, subscribe button, send-to-phone button)
  - PairingScreen (4-digit PIN display, paired device list)
  - SettingsScreen (Steam account, root mode, verbose logging, reset prefix, about)
  - LogsScreen (live ring buffer view, monospace, level-colored)
  - WallpaperCard (16:9 thumbnail, title, creator, type, subs)
- Steam auth:
  - SteamLoginActivity (WebView for Steam login, cookie extraction)
- CI/CD:
  - .github/workflows/docker-image.yml (weekly GHCR builder image rebuild)
  - .github/workflows/build.yml (5-way component matrix + APK assembly + GitHub Releases)
  - docker/Dockerfile.builder (NDK 26 + SDK 35 + Mesa/Wine/Box64/DXVK/VKD3D build deps)
  - scripts/build_component.sh (builds one of: box64 / wine / mesa-turnip / dxvk / vkd3d)
  - scripts/fetch_components.sh (download artifacts from CI for local build)
  - scripts/build_apk.sh (verify artifacts present + ./gradlew assembleRelease)
  - scripts/reverse_engineer_we_android.sh (jadx + apktool decompile + grep for pairing protocol)

### Known limitations
- All emulation runtime calls (Box64Runner.exec, WinePrefixManager mount/unmount) are STUBS.
  The real implementation requires vendoring Winlator's `xenvironment` and `container` packages
  into this repo (TODO: see ARCHITECTURE.md → Open Work).
- Pairing protocol wire format is UNVERIFIED. WeControlServer logs every received byte to
  WepWawetLogger; a real WE Android pairing attempt will produce the data needed for RE.
- PairingCrypto algorithm is HYPOTHESIZED (PBKDF2-HMAC-SHA256 with fixed salt). Until jadx
  decompile of the WE Android APK confirms the actual salt + iteration count, pairing will
  fail with an auth-error tag mismatch — which itself is informative.
- .mpkg format is UNVERIFIED. MpkgTransporter detects new files in the outbox and queues
  them for push, but the actual push wire format is stubbed until RE confirms.

## [0.1.0-alpha] — not yet released

First tagged release. Will happen when:
- The 5 component builds produce real artifacts in CI
- The APK assembles successfully
- A user has run it on a SD8Gen2 device and seen the workshop browser work

After 0.1.0-alpha, the next milestones are:
- 0.2.0-alpha: real Wine prefix, real Box64 exec, Steam launches under Wine
- 0.3.0-alpha: WE subscribes to a wallpaper and the .mpkg appears in the outbox
- 0.4.0-alpha: PairingProxyService speaks the real wire format (post-RE)
- 0.5.0-alpha: official WE Android app pairs with WepWawet and shows a video wallpaper
- 0.6.0-alpha: scene (3D) wallpaper works
- 0.7.0-alpha: web (HTML) wallpaper works
- 0.8.0-alpha: application wallpaper works
- 0.9.0-beta: 8-hour stability test passes
- 1.0.0: first stable release
