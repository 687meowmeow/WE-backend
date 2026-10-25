# WepWawet

> Steam Workshop browser & downloader for Android. Browse 3M+ Wallpaper Engine workshop items, log in with your own Steam account (2FA / Steam Guard supported), and download `.pkg` wallpaper packs directly to your phone. **No PC required.**

**Status: v0.5.6-alpha — functional beta.** Pairing proxy is included for the official WE Android app.

[![Build APK](https://github.com/687meowmeow/WE-backend/actions/workflows/build.yml/badge.svg)](https://github.com/687meowmeow/WE-backend/actions/workflows/build.yml)

## What this is

WepWawet is an Android app (Kotlin + Jetpack Compose, min Android 8.0 / API 26, target SDK 35) that lets you browse the Steam Workshop for Wallpaper Engine (app 431960), inspect items, and download the actual `.pkg` wallpaper packs to your device.

The download path is **on-device JavaSteam** — your Steam credentials live on your phone; JavaSteam talks directly to Steam's auth + content servers. **No PC, no server, no third-party service required.** Steam Guard 2FA codes are supported. Refresh tokens are saved to skip 2FA on subsequent sessions.

The repo also includes a foreground **pairing proxy** (`PairingProxyService`) that lets the official Wallpaper Engine Android app pair with this on-device "fake PC" — useful if you want WE's official UI to drive content served from WepWawet.

## Features (v0.5.6-alpha)

- **Browse**: paginated `IPublishedFileService/QueryFiles` (server-side sorting, cursor-based pagination, proper `requiredtags[]` repeated-field format, auto-excludes Web/Application tags that aren't mobile-compatible).
- **Search**: free-text search via `RankedByTextSearch` query type.
- **Item details**: `ISteamRemoteStorage/GetPublishedFileDetails` enrichment with a robust `WorkshopItemDeserializer` that coerces Steam API type mismatches (e.g. `banned: Int → Boolean`) so future API changes won't crash parsing.
- **Creator names**: async resolution via profile-page scraping (`resolveCreatorName`), capped at 8 concurrent lookups, fire-and-forget so items render immediately.
- **Steam login**: WebView-based login with transparent 2FA / Steam Guard handling (`SteamLoginActivity`).
- **JavaSteam download**: on-device `.pkg` download via JavaSteam's modern auth flow (`WorkshopDownloader.kt`). Saves refresh tokens to skip 2FA on subsequent sessions. Handles `RateLimitExceeded` by clearing the stale token.
- **Download manager**: tracks active and completed downloads.
- **Pairing proxy**: foreground service on UDP 7884 + TCP 7889 that implements the WE pairing protocol.
- **Extensive logging**: structured logger (`WepWawetLogger`) with 6 levels, 7 domains, 2000-entry ring buffer, WorkManager daily flush, 14-day prune, and crash capture. **Download log button** in the Logs screen exports the full log buffer to the public Downloads/WepWawet/ folder.

## Repo layout

| Path | Purpose |
|------|---------|
| `app/` | Android app module (Kotlin + Jetpack Compose) |
| `app/src/main/java/com/wepwawet/` | App source — UI, data, pairing, auth, log |
| `app/build.gradle.kts` | App gradle config (versionCode, ABI filters, packaging rules) |
| `app/proguard-rules.pro` | R8 rules (keep Gson reflection targets) |
| `app/debug.keystore` | Stable debug keystore so all CI builds are signed with the same key |
| `.github/workflows/build.yml` | CI: APK build → release |
| `gradle/`, `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `gradlew*` | Gradle project files |
| `ARCHITECTURE.md` | Architecture decisions, data flow |
| `CHANGELOG.md` | Per-version changelog |

## Device requirements

- **SoC:** Snapdragon 8 Gen 2 or newer (for on-device JavaSteam path; pairing proxy path has no special hardware needs)
- **RAM:** 6 GB+ recommended
- **Storage:** 1 GB+ free for the app + downloaded `.pkg` files
- **Android:** 8.0 (API 26) or higher
- **Network:** Internet access for Steam APIs

## Building

### Local build

Requirements: JDK 17, Android SDK with NDK + CMake, x86_64 host (for Wine build steps if you also rebuild native components).

```bash
./gradlew :app:assembleRelease
```

The release APK is signed with `app/debug.keystore` (committed to the repo so all builds — local + CI — share a signing key). For a real release you should replace this with your own keystore before publishing.

### CI build

Push a `v*` tag (e.g. `v0.5.6-alpha`) and GitHub Actions will assemble the APK and attach it to a Release. See `.github/workflows/build.yml`.

## Quick navigation for new contributors

1. Read `ARCHITECTURE.md` — explains what the system looks like end-to-end.
2. Skim `app/src/main/java/com/wepwawet/data/` — Steam API service, repository, models.
3. Skim `app/src/main/java/com/wepwawet/pairing/` — JavaSteam downloader, pairing proxy.
4. Skim `app/src/main/java/com/wepwawet/ui/screens/` — Compose screens (browser, detail, downloads, settings, pairing, logs).

## Versioning

Pre-1.0 semver with `-alpha` suffix. As of v0.5.6-alpha the core browse → login → download loop is functional end-to-end on-device via JavaSteam. The pairing proxy is functional but the larger on-device Wine emulation plan (the original v0.1 vision) is on hold.

## License

TBD. Will be either Apache 2.0 (with LGPL'd third-party modifications published as separate source archives per LGPL compliance) or GPL 3 to simplify compliance. See `LICENSE` once finalized.
