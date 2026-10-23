# WepWawet Workshop Content Backend

A self-hosted backend that downloads Steam Workshop `.pkg` files via SteamCMD and serves them to the WepWawet Android app.

## Why This Exists

Steam hides the `file_url` field in `ISteamRemoteStorage/GetPublishedFileDetails/v1/` for paid apps like Wallpaper Engine (431960). Even with Steam login + API key, Steam returns an empty `file_url`. So the Android app cannot directly download workshop content via Steam's API.

The only reliable way to download workshop content for paid apps is **SteamCMD** — Valve's official command-line Steam client. SteamCMD requires:
1. A Steam account (the user's own — anonymous doesn't work for paid apps)
2. A Linux/Windows/macOS host (SteamCMD has no Android port)

This backend runs SteamCMD on your PC or VPS and exposes a simple HTTP API that the WepWawet Android app calls.

## Quick Start (Docker)

1. **Set your Steam credentials** in `docker-compose.yml`:
   ```yaml
   environment:
     - STEAM_USERNAME=your_steam_username
     - STEAM_PASSWORD=your_steam_password
   ```

2. **Build and start**:
   ```bash
   cd backend
   docker compose up -d
   ```

3. **Verify it's running**:
   ```bash
   curl http://localhost:8080/api/health
   # {"status":"ok","steamcmd_available":true,...}
   ```

4. **In the WepWawet Android app**: Go to Settings → Backend URL → `http://<your-server-ip>:8080`

## Quick Start (No Docker, Requires SteamCMD Installed)

```bash
# Install SteamCMD (Linux):
# See https://developer.valvesoftware.com/wiki/SteamCMD

# Install Python deps:
pip install -r requirements.txt

# Set your Steam credentials:
export STEAM_USERNAME=your_steam_username
export STEAM_PASSWORD=your_steam_password

# Set SteamCMD path (adjust as needed):
export STEAMCMD_PATH=/path/to/steamcmd.sh

# Run:
python3 server.py
```

## API Endpoints

### `POST /api/workshop/{workshop_id}`
Start downloading a workshop item. Returns a task ID immediately (the download runs in the background).

**Request body** (optional):
```json
{"app_id": 431960}
```

**Response**:
```json
{
  "task_id": "abc123",
  "status": "queued",
  "workshop_id": "3822839089",
  "app_id": 431960
}
```

### `GET /api/status/{task_id}`
Poll the status of a download task. The WepWawet app polls this every 2 seconds.

**Response**:
```json
{
  "task_id": "abc123",
  "status": "downloading",  // queued | downloading | complete | error
  "progress": 0.45,
  "error": null,
  "file_size": 0,
  "elapsed_seconds": 12.3,
  "log_lines": ["[12:34:56] Starting SteamCMD..."]
}
```

### `GET /api/file/{task_id}`
Stream the downloaded `.pkg` file (only available when status is `complete`).

**Response**: binary `.pkg` file (`application/octet-stream`)

### `DELETE /api/task/{task_id}`
Delete a task and its downloaded file (for cleanup).

### `GET /api/health`
Health check.

## Configuration

| Environment Variable | Default | Description |
|---|---|---|
| `STEAM_USERNAME` | `anonymous` | Your Steam username (use your real account for paid apps) |
| `STEAM_PASSWORD` | (empty) | Your Steam password |
| `STEAM_GUARD_CODE` | (empty) | Steam Guard 2FA code (if 2FA enabled) |
| `STEAMCMD_PATH` | `/home/steam/steamcmd/steamcmd.sh` | Path to `steamcmd.sh` |
| `DEFAULT_APP_ID` | `431960` | Default app ID (431960 = Wallpaper Engine) |
| `DOWNLOAD_TIMEOUT` | `1800` | Max seconds for a single download (30 min) |
| `MAX_CONCURRENT_TASKS` | `2` | Max simultaneous downloads |
| `PORT` | `8080` | HTTP port |

## Important Notes

### For Paid Apps (Wallpaper Engine)

SteamCMD requires a Steam account that **owns** the game. Anonymous login only works for free-to-play games. For Wallpaper Engine (paid):
- You MUST use your real Steam credentials
- Your account must own Wallpaper Engine
- If 2FA is enabled, you need to provide a Steam Guard code

### Security

- The backend stores Steam credentials in **memory only** (read from env vars on startup)
- The Steam password is **NEVER** logged or persisted to disk
- For production, put this behind a reverse proxy (nginx, Caddy) with HTTPS

### Deployment Options

**Docker Compose** (easiest):
```bash
docker compose up -d
```

**Railway**:
1. Fork this repo
2. Connect Railway to your fork
3. Set env vars: `STEAM_USERNAME`, `STEAM_PASSWORD`
4. Deploy

**Render**:
1. Create a new Web Service from this repo
2. Set env vars: `STEAM_USERNAME`, `STEAM_PASSWORD`
3. Deploy

**Local PC**:
1. Install SteamCMD
2. `pip install -r requirements.txt`
3. `STEAM_USERNAME=... STEAM_PASSWORD=... python3 server.py`
4. In the WepWawet app, set Backend URL to `http://<your-pc-ip>:8080`

## How It Works

```
┌──────────────────┐         ┌─────────────────────────┐
│ WepWawet Android │         │  Backend (this server)  │
│      App         │         │                         │
│                  │  POST   │  ┌───────────────────┐  │
│  Send to phone   ├────────►│  │  SteamCMD          │  │
│  (workshop ID)   │         │  │  workshop_download │  │
│                  │  Poll   │  │  _item 431960 X    │  │
│  Status: queued  │<────────┤  │                   │  │
│  Status: downloading │     │  │  Downloads .pkg   │  │
│  Status: complete     │     │  └───────────────────┘  │
│                  │  GET    │                         │
│  Download .pkg   ├────────►│  Stream .pkg file      │
│                  │         │                         │
└──────────────────┘         └─────────────────────────┘
```

1. User taps "Send to phone" on a workshop item in the WepWawet app
2. The app calls `POST /api/workshop/<id>` on the backend
3. The backend runs SteamCMD with `workshop_download_item 431960 <id>`
4. The app polls `GET /api/status/<task_id>` until `status=complete`
5. The app calls `GET /api/file/<task_id>` to download the .pkg
6. The app packs the .pkg as a .mpkg and transfers it to the WE Android app
