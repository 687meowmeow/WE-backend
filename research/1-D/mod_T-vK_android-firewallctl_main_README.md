# android-firewallctl

Block or allow network access **per app** on a rooted phone, using the same
mechanism as **Settings → Apps → \<app\> → Mobile data & Wi‑Fi**. Changes
show up in the system UI immediately — no VPN, no separate iptables rules.

| Install | What you get |
|---|---|
| **Magisk module** | New user apps blocked by default; notification to allow |
| **CLI** | Manual block/allow per package from adb or scripts |
| **Termux `.deb`** | CLI in Termux’s prefix |

> **Root required.**

**Tested with:** LineageOS 23 and Magisk on a Google Pixel 4. Other ROMs and
devices may work — we have not tried them yet. See
[docs/COMPATIBILITY.md](docs/COMPATIBILITY.md).

**Releases:** [github.com/T-vK/android-firewallctl/releases](https://github.com/T-vK/android-firewallctl/releases)

---

## Magisk module (recommended)

**1. Download** the latest module zip (on your PC):

```bash
curl -fsSL -o firewall_default_deny.zip \
  "$(curl -fsSL https://api.github.com/repos/T-vK/android-firewallctl/releases/latest \
    | grep -Eo '"browser_download_url": "[^"]+firewall_default_deny[^"]+\.zip"' \
    | head -1 | cut -d'"' -f4)"
```

**2. Install** — copy to the phone, then Magisk → **Modules** → Install from storage → **Reboot**.

**3. Try it** — install a new app from the store. You should get a notification that it was blocked; tap **Allow** or change the app in Settings.

**Sideload allowlist (optional):**  
`/data/adb/firewall_default_deny/allowlist.txt` — one package per line, `#` for comments.  
Only for apps you install by hand; the module never writes this file.

**Logs:** `/data/adb/firewall_default_deny/watcher.log`  
Details: [docs/STATE.md](docs/STATE.md) · [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md)

---

## CLI (adb)

**1. Download** the latest dex jar and wrapper (on your PC):

```bash
curl -fsSL -o firewallctl.dex.jar \
  "$(curl -fsSL https://api.github.com/repos/T-vK/android-firewallctl/releases/latest \
    | grep -Eo '"browser_download_url": "[^"]+firewallctl-[^"]+\.dex\.jar"' \
    | head -1 | cut -d'"' -f4)"

curl -fsSL -o firewallctl \
  https://raw.githubusercontent.com/T-vK/android-firewallctl/main/scripts/firewallctl
chmod +x firewallctl
```

**2. Push** to the device and run as root:

```bash
adb push firewallctl.dex.jar firewallctl /data/local/tmp/
adb shell su -c 'chmod 0755 /data/local/tmp/firewallctl'

adb shell su -c '/data/local/tmp/firewallctl list-policies'
adb shell su -c '/data/local/tmp/firewallctl get com.android.chrome'
adb shell su -c '/data/local/tmp/firewallctl set com.android.chrome +REJECT_ALL'
adb shell su -c '/data/local/tmp/firewallctl clear com.android.chrome'
```

Common flags: `+REJECT_ALL` / `-REJECT_ALL`. Run `list-policies` on your device for names your ROM exposes.

---

## Termux

**1. Download** the latest `.deb` (on the phone or PC):

```bash
curl -fsSL -o firewallctl.deb \
  "$(curl -fsSL https://api.github.com/repos/T-vK/android-firewallctl/releases/latest \
    | grep -Eo '"browser_download_url": "[^"]+firewallctl_[^"]+_all\.deb"' \
    | head -1 | cut -d'"' -f4)"
```

**2. Install** in Termux:

```bash
dpkg -i firewallctl.deb
su -c 'firewallctl list-policies'
```

---

## Notifications not working?

On the device as root:

```bash
/system/bin/firewall-watcher --test-notify com.example.someapp
```

See [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md).

---

## More documentation

| Doc | Contents |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | How the pieces fit together |
| [docs/BUILD.md](docs/BUILD.md) | Build from source (`make`, SDK, tests) |
| [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md) | What has been tested |
| [docs/STATE.md](docs/STATE.md) | State directory on the device |
| [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) | Logs and diagnostics |
| [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) | Contributing, CI, git hooks |

---

## License

Apache-2.0 — see [LICENSE](LICENSE).
