# WepWawet — On-Device Backend for Wallpaper Engine

> **WepWawet** (named after the ancient Egyptian wolf-headed god who "opened the way" — fitting because this project *opens the way* for Wallpaper Engine on Android without a PC).

A single Android APK that bundles an x86-on-ARM emulation stack (Winlator v11.2 fork + Box64 + Wine + Mesa Turnip + DXVK) running Steam + Wallpaper Engine, plus a **native pairing proxy** that lets your *official* Wallpaper Engine Android app pair with this on-device "fake PC" — no separate gaming PC required.

## Why?

Wallpaper Engine has an official Android app, but it requires a PC running WE on your LAN. If your PC is off, you have no wallpapers. WepWawet turns your phone into its own backend: the official WE Android app pairs with WepWawet running in the background, and WepWawet serves wallpapers from a custom workshop UI. No PC needed.

## Status

**Pre-alpha / architectural skeleton.** This repository contains the project skeleton, build pipeline, and core source files. It is **not yet functional** — major RE work remains (see `ARCHITECTURE.md` → "Open Work"). The goal of this skeleton is to scaffold the architecture decisions made after deep research (see `docs/RESEARCH_SUMMARY.md`) so subsequent development can iterate on a known-good foundation.

## What's here

| Path | Purpose |
|------|---------|
| `app/` | Android app module (Kotlin + Jetpack Compose, min Android 8.0 / API 26) |
| `app/src/main/java/com/wepwawet/` | App source — UI, proxy, wine process, data, auth, log |
| `.github/workflows/` | CI: per-component matrix builds → APK assembly → release |
| `docker/Dockerfile.builder` | Pre-built GHCR image with NDK + Wine + Box64 toolchains |
| `scripts/` | Fetch-and-package scripts for emulation components |
| `docs/RESEARCH_SUMMARY.md` | Findings from the 4-area feasibility research |
| `ARCHITECTURE.md` | Architecture decisions, data flow, open work |

## Device requirements (target)

- **SoC:** Snapdragon 8 Gen 2 or newer (Adreno 740+ Vulkan 1.3)
- **RAM:** 12 GB minimum (8 GB working set for Steam + WE + Wine + Box64)
- **Storage:** 8 GB free minimum (rootfs + Wine prefix + Steam + WE + workshop cache)
- **Root:** Required (Magisk recommended) — used to bypass PRoot overhead, unlock TUN/TAP if needed
- **Android:** 13+ (target SDK 35, min SDK 26)
- **Wallpaper Engine Android app:** Installed from Play Store or from wallpaperengine.io

## Building

The full APK must be built via GitHub Actions — local builds are possible but the Docker image with all toolchains is heavy. See `docs/BUILD.md` (generated during first CI run) or run:

```bash
gh act -W .github/workflows/build.yml  # locally via `act`
# or push to GitHub and let Actions build it
```

Artifacts (1–4 GB APK) appear under GitHub Releases once a `v*` tag is pushed.

## Quick navigation for new contributors

1. Read `docs/RESEARCH_SUMMARY.md` — explains *why* every architectural decision was made.
2. Read `ARCHITECTURE.md` — explains *what* the system looks like.
3. Skim `app/src/main/java/com/wepwawet/proxy/` — the heart of the system (native WE pairing proxy).
4. Skim `app/src/main/java/com/wepwawet/wine/` — the Wine+Box64 process manager.
5. Run `./scripts/fetch_components.sh` to download Winlator component artifacts (Box64 `.wcp`, Wine prefix, Turnip driver, DXVK, VKD3D) into `app/src/main/assets/`.

## License

TBD. Will be either Apache 2.0 (with LGPL'd Winlator/Box64 modifications published as separate source archives per LGPL compliance) or GPL 3 to simplify compliance. See `LICENSE` once finalized.

## Acknowledgments

- **Bruno Landsberg** (`brunodev85`) — Winlator, without which this project would be impossible
- **ptitSeb** — Box64 / Box86
- **whitebelyash** — Mesa Turnip fork for Adreno 7xx
- **Almamu** — `linux-wallpaperengine` (native Linux WE reimplementation, used as reference for `.pkg`/`.tex` parsing)
- **not-wegener** / RePKG — canonical reference for WE workshop file format RE
- Wallpaper Engine fans everywhere who want their wallpapers on the go

## Disclaimer

This project is **not affiliated with Wallpaper Engine, Valve, or Steam**. It is an independent research/compatibility project. It does not bypass DRM or circumvent Steam's authentication — the user must own Wallpaper Engine on their own Steam account and use their own Steam credentials. The emulated Steam client is a standard Steam install under Wine. The pairing proxy implements the same protocol a real PC WE install uses; it does not exploit or bypass WE's pairing security.
