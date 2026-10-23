# WepWawet Workshop Content Backend
#
# This backend downloads Steam Workshop .pkg files via SteamCMD and serves them
# to the WepWawet Android app. The user runs this on their PC or a VPS.
#
# === DEPLOYMENT ===
#
# Option 1: Docker Compose (recommended)
#   1. Edit docker-compose.yml to set your Steam credentials
#   2. Run: docker compose up -d
#   3. The backend will be available at http://localhost:8080
#
# Option 2: Standalone (requires SteamCMD installed)
#   1. pip install -r requirements.txt
#   2. export STEAM_USERNAME=your_username
#   3. export STEAM_PASSWORD=your_password
#   4. python3 server.py
#
# === CONFIGURATION ===
#
# Environment variables:
#   STEAM_USERNAME       - Your Steam username (default: anonymous)
#   STEAM_PASSWORD       - Your Steam password (empty for anonymous)
#   STEAM_GUARD_CODE     - Steam Guard 2FA code if needed (empty if no 2FA)
#   STEAMCMD_PATH        - Path to steamcmd.sh (default: /home/steam/steamcmd/steamcmd.sh)
#   DEFAULT_APP_ID       - Default app ID (431960 = Wallpaper Engine)
#   DOWNLOAD_TIMEOUT     - Max seconds for a download (default: 1800 = 30 min)
#   MAX_CONCURRENT_TASKS - Max simultaneous downloads (default: 2)
#   PORT                 - HTTP port (default: 8080)
#
# === IMPORTANT NOTES ===
#
# For paid apps like Wallpaper Engine (431960), SteamCMD requires:
#   - A Steam account that OWNS the game (anonymous login won't work)
#   - The user's actual Steam credentials (username + password)
#   - Steam Guard code if 2FA is enabled
#
# The backend stores credentials in MEMORY ONLY (read from env vars on startup).
# The Steam password is NEVER logged or persisted to disk.
#
# For production, put this behind a reverse proxy (nginx, Caddy) with HTTPS.

FROM cm2network/steamcmd:debian

# Install Python and dependencies
USER root
RUN apt-get update && apt-get install -y --no-install-recommends \
    python3 python3-pip python3-venv \
    && rm -rf /var/lib/apt/lists/*

# Create app directory
WORKDIR /app

# Copy requirements first (for Docker layer caching)
COPY requirements.txt .
RUN pip3 install --no-cache-dir --break-system-packages -r requirements.txt

# Copy the server code
COPY server.py .

# SteamCMD binary location in the base image
ENV STEAMCMD_PATH=/home/steam/steamcmd/steamcmd.sh
ENV WORKSHOP_DIR=/home/steam/steamapps/workshop/content
ENV DEFAULT_APP_ID=431960
ENV PORT=8080
ENV MAX_CONCURRENT_TASKS=2
ENV DOWNLOAD_TIMEOUT=1800

# Expose the HTTP port
EXPOSE 8080

# Run as the steam user (which has SteamCMD installed)
USER steam

# Health check
HEALTHCHECK --interval=30s --timeout=10s --retries=3 \
    CMD curl -f http://localhost:8080/api/health || exit 1

# Start the server
CMD ["python3", "server.py"]
