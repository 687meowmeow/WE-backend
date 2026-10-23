#!/usr/bin/env python3
"""
WepWawet Workshop Content Backend

A self-hosted backend that downloads Steam Workshop .pkg files via SteamCMD
and serves them to the WepWawet Android app.

=== WHY THIS EXISTS ===

Steam hides the `file_url` field in ISteamRemoteStorage/GetPublishedFileDetails/v1/
for paid apps like Wallpaper Engine (431960). Even with Steam login + API key,
Steam returns an empty file_url. So the WepWawet Android app cannot directly
download workshop content via Steam's API.

The only reliable way to download workshop content for paid apps is SteamCMD —
Valve's official command-line Steam client. SteamCMD requires:
  1. A Steam account (the user's own, or anonymous for free games)
  2. A Linux/Windows/macOS host (NOT Android — SteamCMD has no Android port)

This backend runs SteamCMD on a server (or the user's PC) and exposes a simple
HTTP API that the WepWawet Android app can call.

=== DEPLOYMENT ===

See docker-compose.yml for the simplest deployment. The backend runs in a
Docker container based on `cm2network/steamcmd:debian` (which has SteamCMD
pre-installed). The user provides their Steam credentials via environment
variables.

Quick start (Docker):
    docker compose up -d

Quick start (no Docker, requires SteamCMD installed):
    pip install -r requirements.txt
    STEAM_USERNAME=your_username STEAM_PASSWORD=your_password python3 server.py

=== API ===

  POST /api/workshop/<workshop_id>
    Body (JSON): {"app_id": 431960}  (optional, defaults to 431960 for WE)
    Response: {"task_id": "...", "status": "queued"}

  GET /api/status/<task_id>
    Response: {"status": "queued|downloading|complete|error", "progress": 0.0,
               "error": "...", "file_size": 0, "file_path": "..."}

  GET /api/file/<task_id>
    Response: streams the .pkg file (application/octet-stream)

  GET /api/health
    Response: {"status": "ok", "steamcmd_available": true, "tasks": 0}

=== SECURITY ===

The backend stores Steam credentials in memory only (read from env vars on
startup). The Steam password is NEVER logged. Workshop items are downloaded
to a temporary directory and deleted after the file is served.

For production, put this behind a reverse proxy (nginx, Caddy) with HTTPS.
"""
import json
import os
import re
import shutil
import subprocess
import tempfile
import threading
import time
import uuid
from dataclasses import dataclass, field
from enum import Enum
from pathlib import Path
from typing import Dict, Optional

from fastapi import FastAPI, HTTPException
from fastapi.responses import FileResponse, JSONResponse
from pydantic import BaseModel

# === Configuration ===

STEAM_USERNAME = os.environ.get("STEAM_USERNAME", "anonymous")
STEAM_PASSWORD = os.environ.get("STEAM_PASSWORD", "")
STEAM_GUARD_CODE = os.environ.get("STEAM_GUARD_CODE", "")  # for 2FA if needed
STEAMCMD_PATH = os.environ.get("STEAMCMD_PATH", "/home/steam/steamcmd/steamcmd.sh")
WORKSHOP_DIR = os.environ.get("WORKSHOP_DIR", "/home/steam/steamapps/workshop/content")
DEFAULT_APP_ID = int(os.environ.get("DEFAULT_APP_ID", "431960"))  # Wallpaper Engine
DOWNLOAD_TIMEOUT = int(os.environ.get("DOWNLOAD_TIMEOUT", "1800"))  # 30 min max
MAX_CONCURRENT_TASKS = int(os.environ.get("MAX_CONCURRENT_TASKS", "2"))
PORT = int(os.environ.get("PORT", "8080"))

# === Task state ===

class TaskStatus(str, Enum):
    QUEUED = "queued"
    DOWNLOADING = "downloading"
    COMPLETE = "complete"
    ERROR = "error"

@dataclass
class DownloadTask:
    task_id: str
    workshop_id: str
    app_id: int
    status: TaskStatus = TaskStatus.QUEUED
    progress: float = 0.0
    error: Optional[str] = None
    file_path: Optional[str] = None
    file_size: int = 0
    created_at: float = field(default_factory=time.time)
    started_at: Optional[float] = None
    completed_at: Optional[float] = None
    log_lines: list = field(default_factory=list)

# In-memory task store (thread-safe via lock)
TASKS: Dict[str, DownloadTask] = {}
TASKS_LOCK = threading.Lock()
ACTIVE_TASKS = 0
ACTIVE_TASKS_LOCK = threading.Lock()

app = FastAPI(title="WepWawet Workshop Backend", version="1.0.0")


class WorkshopRequest(BaseModel):
    app_id: int = DEFAULT_APP_ID


def log_task(task: DownloadTask, message: str):
    """Append a log line to the task (capped at 100 lines)."""
    task.log_lines.append(f"[{time.strftime('%H:%M:%S')}] {message}")
    if len(task.log_lines) > 100:
        task.log_lines = task.log_lines[-100:]
    print(f"[task {task.task_id[:8]}] {message}", flush=True)


def find_pkg_file(workshop_dir: str, workshop_id: str) -> Optional[str]:
    """Find the .pkg file downloaded by SteamCMD for the given workshop ID."""
    # SteamCMD downloads to: <workshop_dir>/<app_id>/<workshop_id>/
    # The .pkg file is inside that folder, named like scene.pkg or <workshop_id>.pkg
    for app_id_dir in Path(workshop_dir).iterdir():
        workshop_dir_path = app_id_dir / workshop_id
        if workshop_dir_path.is_dir():
            # Find any .pkg file inside
            pkg_files = list(workshop_dir_path.glob("*.pkg"))
            if pkg_files:
                return str(pkg_files[0])
            # Some workshops store the .pkg directly with the workshop ID name
            # Try alternative paths
            for entry in workshop_dir_path.iterdir():
                if entry.is_file() and entry.stat().st_size > 1024:
                    # Check magic bytes
                    with open(entry, "rb") as f:
                        header = f.read(12)
                    if (len(header) >= 12 and header[0:4] == b"\x08\x00\x00\x00"
                        and header[4:12] == b"PKGV0024"):
                        return str(entry)
    return None


def run_steamcmd_download(task: DownloadTask):
    """Run SteamCMD to download the workshop item."""
    global ACTIVE_TASKS

    with ACTIVE_TASKS_LOCK:
        if ACTIVE_TASKS >= MAX_CONCURRENT_TASKS:
            task.status = TaskStatus.QUEUED
            log_task(task, "Task queued (max concurrent reached)")
            # Wait for a slot
            while True:
                time.sleep(5)
                with ACTIVE_TASKS_LOCK:
                    if ACTIVE_TASKS < MAX_CONCURRENT_TASKS:
                        ACTIVE_TASKS += 1
                        break
        else:
            ACTIVE_TASKS += 1

    try:
        task.status = TaskStatus.DOWNLOADING
        task.started_at = time.time()
        log_task(task, f"Starting SteamCMD download for workshop {task.workshop_id} (app {task.app_id})")
        log_task(task, f"Steam user: {STEAM_USERNAME}")

        # Create a temp directory for this download
        temp_dir = tempfile.mkdtemp(prefix=f"wepwawet_{task.workshop_id}_")
        log_task(task, f"Temp dir: {temp_dir}")

        # Build the SteamCMD command
        # +@sSteamCmdForcePlatformType linux  (force Linux platform)
        # +@ShutdownOnFailedCommand 1
        # +login <user> <pass> [<guard>]
        # +force_install_dir <temp_dir>
        # +workshop_download_item <app_id> <workshop_id>
        # +quit
        cmd = [
            STEAMCMD_PATH,
            "+@sSteamCmdForcePlatformType", "linux",
            "+@ShutdownOnFailedCommand", "1",
            "+force_install_dir", temp_dir,
            "+login", STEAM_USERNAME,
        ]
        if STEAM_PASSWORD:
            cmd.append(STEAM_PASSWORD)
        if STEAM_GUARD_CODE:
            cmd.append(STEAM_GUARD_CODE)
        cmd.extend([
            "+workshop_download_item", str(task.app_id), task.workshop_id,
            "+quit",
        ])

        log_task(task, f"Running: {' '.join(cmd[:5])}... (password redacted)")

        # Run SteamCMD and capture output
        process = subprocess.Popen(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            cwd=temp_dir,
        )

        # Stream the output and parse progress
        last_progress_log = time.time()
        for line in iter(process.stdout.readline, ''):
            line = line.rstrip()
            if line:
                task.log_lines.append(f"[{time.strftime('%H:%M:%S')}] {line}")
                if len(task.log_lines) > 100:
                    task.log_lines = task.log_lines[-100:]

                # Look for progress percentage
                progress_match = re.search(r"(\d+\.\d+)\s*%", line)
                if progress_match:
                    pct = float(progress_match.group(1))
                    task.progress = pct / 100.0

                # Look for "Success" message
                if "Success" in line or "success" in line:
                    task.progress = 1.0
                    log_task(task, "SteamCMD reported success")

                # Log every 3 seconds
                if time.time() - last_progress_log > 3:
                    print(f"[task {task.task_id[:8]}] {line[:200]}", flush=True)
                    last_progress_log = time.time()

        process.wait(timeout=DOWNLOAD_TIMEOUT)
        return_code = process.returncode
        log_task(task, f"SteamCMD exit code: {return_code}")

        if return_code != 0:
            task.status = TaskStatus.ERROR
            task.error = f"SteamCMD exited with code {return_code}"
            log_task(task, f"FAILED: {task.error}")
            return

        # Find the .pkg file in the download directory
        # SteamCMD stores to: <temp_dir>/steamapps/workshop/content/<app_id>/<workshop_id>/
        workshop_dir_path = Path(temp_dir) / "steamapps" / "workshop" / "content"
        pkg_path = find_pkg_file(str(workshop_dir_path), task.workshop_id)

        if not pkg_path or not Path(pkg_path).exists():
            task.status = TaskStatus.ERROR
            task.error = f"SteamCMD completed but no .pkg file found for workshop {task.workshop_id}"
            log_task(task, f"FAILED: {task.error}")
            log_task(task, f"Searched in: {workshop_dir_path}")
            # List all files for debugging
            if workshop_dir_path.exists():
                for entry in workshop_dir_path.rglob("*"):
                    log_task(task, f"  Found: {entry}")
            return

        # Copy the .pkg to a stable location
        stable_path = Path(tempfile.gettempdir()) / f"wepwawet_{task.task_id}_{task.workshop_id}.pkg"
        shutil.copy2(pkg_path, stable_path)
        task.file_path = str(stable_path)
        task.file_size = Path(stable_path).stat().st_size
        task.status = TaskStatus.COMPLETE
        task.progress = 1.0
        task.completed_at = time.time()
        log_task(task, f"Download COMPLETE: {task.file_path} ({task.file_size:,} bytes)")

        # Clean up the SteamCMD temp dir
        try:
            shutil.rmtree(temp_dir, ignore_errors=True)
        except Exception:
            pass

    except subprocess.TimeoutExpired:
        task.status = TaskStatus.ERROR
        task.error = f"SteamCMD timed out after {DOWNLOAD_TIMEOUT}s"
        log_task(task, f"TIMEOUT: {task.error}")
    except Exception as e:
        task.status = TaskStatus.ERROR
        task.error = f"Exception: {e}"
        log_task(task, f"EXCEPTION: {task.error}")
    finally:
        with ACTIVE_TASKS_LOCK:
            global_ref = globals()
            if 'ACTIVE_TASKS' in global_ref:
                ACTIVE_TASKS = max(0, ACTIVE_TASKS - 1)


@app.get("/api/health")
async def health():
    """Health check — confirms the backend is running and SteamCMD is available."""
    steamcmd_available = Path(STEAMCMD_PATH).exists()
    with TASKS_LOCK:
        task_count = len(TASKS)
        active_count = sum(1 for t in TASKS.values() if t.status == TaskStatus.DOWNLOADING)
    return {
        "status": "ok",
        "steamcmd_available": steamcmd_available,
        "steamcmd_path": STEAMCMD_PATH,
        "steam_username": STEAM_USERNAME,
        "tasks_total": task_count,
        "tasks_active": active_count,
        "max_concurrent": MAX_CONCURRENT_TASKS,
    }


@app.post("/api/workshop/{workshop_id}")
async def start_download(workshop_id: str, request: WorkshopRequest = Workshop()):
    """Start downloading a workshop item. Returns a task_id immediately."""
    # Validate workshop ID is numeric
    if not workshop_id.isdigit():
        raise HTTPException(status_code=400, detail="workshop_id must be numeric")

    with TASKS_LOCK:
        # Check for existing task with same workshop_id
        for existing in TASKS.values():
            if (existing.workshop_id == workshop_id and existing.app_id == request.app_id
                and existing.status in (TaskStatus.QUEUED, TaskStatus.DOWNLOADING)):
                return {
                    "task_id": existing.task_id,
                    "status": existing.status.value,
                    "message": "Already downloading this workshop item",
                }

    task = DownloadTask(
        task_id=str(uuid.uuid4()),
        workshop_id=workshop_id,
        app_id=request.app_id,
    )

    with TASKS_LOCK:
        TASKS[task.task_id] = task

    # Start the download in a background thread
    thread = threading.Thread(target=run_steamcmd_download, args=(task,), daemon=True)
    thread.start()

    return {
        "task_id": task.task_id,
        "status": task.status.value,
        "workshop_id": workshop_id,
        "app_id": request.app_id,
    }


@app.get("/api/status/{task_id}")
async def get_status(task_id: str):
    """Get the current status of a download task."""
    with TASKS_LOCK:
        task = TASKS.get(task_id)
    if not task:
        raise HTTPException(status_code=404, detail="task not found")

    elapsed = None
    if task.started_at:
        end = task.completed_at or time.time()
        elapsed = end - task.started_at

    return {
        "task_id": task.task_id,
        "workshop_id": task.workshop_id,
        "app_id": task.app_id,
        "status": task.status.value,
        "progress": task.progress,
        "error": task.error,
        "file_path": task.file_path,
        "file_size": task.file_size,
        "created_at": task.created_at,
        "started_at": task.started_at,
        "completed_at": task.completed_at,
        "elapsed_seconds": elapsed,
        "log_lines": task.log_lines[-20:],  # last 20 lines for debugging
    }


@app.get("/api/file/{task_id}")
async def download_file(task_id: str):
    """Stream the downloaded .pkg file to the client."""
    with TASKS_LOCK:
        task = TASKS.get(task_id)
    if not task:
        raise HTTPException(status_code=404, detail="task not found")
    if task.status != TaskStatus.COMPLETE:
        raise HTTPException(status_code=409, detail=f"task not complete (status={task.status.value})")
    if not task.file_path or not Path(task.file_path).exists():
        raise HTTPException(status_code=410, detail="file no longer available")

    return FileResponse(
        task.file_path,
        media_type="application/octet-stream",
        filename=f"{task.workshop_id}.pkg",
    )


@app.delete("/api/task/{task_id}")
async def delete_task(task_id: str):
    """Delete a task and its downloaded file (cleanup)."""
    with TASKS_LOCK:
        task = TASKS.pop(task_id, None)
    if task and task.file_path:
        try:
            Path(task.file_path).unlink(missing_ok=True)
        except Exception:
            pass
    return {"deleted": task_id}


if __name__ == "__main__":
    import uvicorn
    print(f"WepWawet Workshop Backend starting on port {PORT}")
    print(f"  Steam user: {STEAM_USERNAME}")
    print(f"  SteamCMD: {STEAMCMD_PATH}")
    print(f"  Workshop dir: {WORKSHOP_DIR}")
    print(f"  Default app ID: {DEFAULT_APP_ID} (Wallpaper Engine)")
    print(f"  Max concurrent: {MAX_CONCURRENT_TASKS}")
    print(f"  Download timeout: {DOWNLOAD_TIMEOUT}s")
    uvicorn.run(app, host="0.0.0.0", port=PORT)
