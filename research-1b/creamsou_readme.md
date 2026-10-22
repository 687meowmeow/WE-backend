# wallpaper-engine-ui-fix

> Wallpaper Engine won't open — no tray icon, main window never appears, but the wallpaper engine is still running.

[![Platform](https://img.shields.io/badge/platform-Windows%2010%20%2F%2011-0078D6)](#)
[![Shell](https://img.shields.io/badge/shell-PowerShell%205.1%2B-5391FE)](#)
[![Dependencies](https://img.shields.io/badge/dependencies-none-brightgreen)](#)
[![License](https://img.shields.io/badge/license-MIT-green)](./LICENSE)

The UI process (`wallpaperui.exe`) renders through an embedded Chromium (`libcef.dll`). On startup, CEF must create a **singleton lock file inside its profile folder** (`ui\uicache`). If that folder is not writable by your account, `ProcessSingleton` cannot initialise, libcef asserts, and the process dies in milliseconds — before it can even open its own log file.

The renderer (`wallpaper64.exe`) never touches that folder. That is why **the wallpaper keeps playing normally while the UI is gone**: you get the tray icon missing and no window, not a crash dialog.

This tool diagnoses that exact chain and repairs it by **adding one permission entry**, nothing more.

## Symptoms this fixes

| Symptom | This tool |
| --- | --- |
| No tray icon, double-clicking the shortcut does nothing | ✅ target case |
| Only `wallpaperservice64` is running; `wallpaperui.exe` vanishes right after start | ✅ target case |
| Wallpaper displays fine, only the window/tray is missing | ✅ target case (this is the signature of the bug) |
| Web-type wallpapers are black with an error | ❌ missing WebView2 Runtime |
| Tray icon exists but wallpaper is paused | ❌ right-click tray → unpause |

## Usage

1. Download this repo and extract it somewhere permanent.
2. **Right-click `run-fix.bat` → Run as administrator** (it elevates itself; changing folder permissions requires it).
3. Read the evidence table, then the script repairs the permission and restarts Wallpaper Engine.
4. Check the system tray.

Diagnose only — change nothing:

```powershell
.\fix-wallpaper-ui.ps1 -DryRun
```

### Parameters

| Parameter | Meaning |
| --- | --- |
| `-InstallDir <path>` | Point at the install folder if auto-detection fails |
| `-DryRun` | Print the diagnosis only |
| `-NoRestart` | Apply the permission fix, leave the app alone |
| `-RevertLegacyGpuSettings` | Undo the `uihardwareacceleration` / `--disable-gpu` leftovers (see below) |

```powershell
# Diagnose
.\fix-wallpaper-ui.ps1 -DryRun

# Repair (as Administrator)
.\fix-wallpaper-ui.ps1

# Install folder not where expected
.\fix-wallpaper-ui.ps1 -InstallDir "D:\SteamLibrary\steamapps\common\wallpaper_engine"
```

## What the diagnosis prints

```
Check                   Value
-----                   -----
Can write to ui\        NO - denied
Can write to ui\uicache NO - denied
Owner of ui\            BUILTIN\Administrators
Inheritance blocked     no
libcef crashes (30d)    18
Exception codes         80000003, c0000005
Fault offsets           0000000002ee4e17, 0000000007c61d4d
Crashes in last 60 min  0
```

The decisive line is **`Can write to ui\uicache: NO`**. ACLs can look correct on paper, so the tool performs a real write test rather than trusting the ACL view alone.

The crash statistics come from the structured fields of Windows event 1000 (not from parsing the message text, which is localized), so they read correctly on any system language.

## What it changes

Exactly one thing — an additive access entry on the `ui` folder:

```
icacls "<install>\ui" /grant "<you>:(OI)(CI)F" /T /C /Q
```

- **Nothing is removed or overwritten.** No existing permission entry is touched.
- **Reversible**: `icacls "<install>\ui" /remove:g "<you>" /T`
- **No backup needed**, for the same reason: there is nothing to restore. (An `icacls /save` dump of this tree is ~27 MB of text describing permissions that are supposed to be Windows defaults anyway.)

## Why this and not the usual advice

Two fixes are widely recommended for this symptom. Both were tried on the machine this tool came from, and **neither was the cause**:

| Popular advice | What actually happened |
| --- | --- |
| Install WebView2 Runtime | It was already installed. The registry key checked first just wasn't the one it lives under. |
| Disable GPU acceleration (`uihardwareacceleration: false`, `--disable-gpu`) | The UI appeared to work for a while, then failed again — with `--disable-gpu`, `--disable-gpu-compositing`, `--use-angle=swiftshader` and `--no-sandbox`, a fresh profile, and even a clean `--user-data-dir`. All crashed identically. |

The GPU theory looked convincing because `0x80000003` is a breakpoint/assert code and Chromium does assert on GPU init failures. But the real assert was `ProcessSingleton` failing on a non-writable profile folder — which happens before any GPU work.

If you previously applied the GPU "fix", `-RevertLegacyGpuSettings` removes it. Those settings only cost you UI frames; they do not prevent this crash.

## When it is not a permission problem

The tool says so explicitly if `ui\uicache` is writable. In that case the cause is elsewhere — most often the graphics driver. Note that CEF asserts on `0x80000003` are deterministic: if every crash sits at the **same fault offset**, the failure is in a fixed code path, not a random hardware fault.

## Verify by hand

```powershell
# 1. Is the profile folder writable?
$p = "<install>\ui\uicache\__probe.tmp"
try { [System.IO.File]::WriteAllText($p, "x"); "writable" } catch { "DENIED: $($_.Exception.Message)" }

# 2. What does the event log say?
Get-WinEvent -FilterHashtable @{LogName='Application'; Id=1000; StartTime=(Get-Date).AddDays(-30)} |
  ForEach-Object { [pscustomobject]@{
      Time   = $_.TimeCreated
      Module = $_.Properties[3].Value
      Code   = $_.Properties[6].Value
      Offset = $_.Properties[7].Value } } |
  Where-Object Module -match 'libcef' | Format-Table -AutoSize
```

## Notes

- **Do not run Steam (or Wallpaper Engine) as Administrator.** Elevating them is what tends to break the permissions on these folders in the first place; the next launch then fails the same way.
- Requires Administrator rights to change folder permissions — `run-fix.bat` requests the elevation for you.
- Idempotent: if the profile folder is already writable, it changes nothing.

## Disclaimer

This tool only adds an access-control entry on Wallpaper Engine's own `ui` folder. Use it at your own risk. Wallpaper Engine is a product of Kristjan Skutta; this project is not affiliated with it.

## License

[MIT](./LICENSE)
