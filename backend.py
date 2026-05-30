"""
TailClip Backend – bidirectional clipboard sync over WebSockets.

Run:
    uvicorn backend:app --host 0.0.0.0 --port 8765

The server exposes:
    • WS  /ws          – clipboard sync channel
    • GET /health      – simple health-check endpoint
"""

from __future__ import annotations

import asyncio
import logging
from contextlib import asynccontextmanager
from datetime import datetime, timezone

import os
import shutil
from pathlib import Path
import urllib.parse
from fastapi import FastAPI, WebSocket, WebSocketDisconnect, UploadFile, File
from fastapi.staticfiles import StaticFiles
from fastapi.responses import JSONResponse

# ---------------------------------------------------------------------------
# Logging
# ---------------------------------------------------------------------------
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s | %(levelname)-7s | %(message)s",
    datefmt="%H:%M:%S",
)
log = logging.getLogger("tailclip")

# ---------------------------------------------------------------------------
# Directories
# ---------------------------------------------------------------------------
DOWNLOADS_DIR = Path.home() / "Downloads" / "TailClip"
FROM_MOBILE_DIR = DOWNLOADS_DIR / "FromMobile"
TO_MOBILE_DIR = DOWNLOADS_DIR / "ToMobile"

FROM_MOBILE_DIR.mkdir(parents=True, exist_ok=True)
TO_MOBILE_DIR.mkdir(parents=True, exist_ok=True)

# ---------------------------------------------------------------------------
# State
# ---------------------------------------------------------------------------
# All connected WebSocket clients.
_clients: set[WebSocket] = set()

# The last clipboard content we know about (local or remote).
_last_known_clip: str = ""

# The last text that arrived from a remote client.
# Used to suppress the echo-loop: when the local poller sees this exact text
# appear in the system clipboard it will NOT broadcast it back.
_last_remote_clip: str | None = None

# Lock protecting shared state so the poller and WS handlers don't race.
_lock = asyncio.Lock()

# ---------------------------------------------------------------------------
# Clipboard helpers
# ---------------------------------------------------------------------------
POLL_INTERVAL_S: float = 0.5  # seconds between local clipboard checks


def _read_clipboard() -> str:
    """Read the system clipboard, returning '' on any failure."""
    try:
        import pyperclip
        return pyperclip.paste() or ""
    except Exception:
        return ""


def _write_clipboard(text: str) -> None:
    """Write *text* to the system clipboard."""
    try:
        import pyperclip
        pyperclip.copy(text)
    except Exception as exc:
        log.error("Failed to write clipboard: %s", exc)


# ---------------------------------------------------------------------------
# Broadcast
# ---------------------------------------------------------------------------
async def _broadcast(text: str, *, sender: WebSocket | None = None) -> None:
    """Send *text* to every connected client except *sender*."""
    dead: list[WebSocket] = []
    for ws in _clients:
        if ws is sender:
            continue
        try:
            await ws.send_text(text)
        except Exception:
            dead.append(ws)
    for ws in dead:
        _clients.discard(ws)


# ---------------------------------------------------------------------------
# Background clipboard poller (PC → Android direction)
# ---------------------------------------------------------------------------
async def _clipboard_poller() -> None:
    """Periodically check the local clipboard for changes and broadcast."""
    global _last_known_clip, _last_remote_clip

    # Seed with current clipboard so we don't broadcast stale content on start.
    _last_known_clip = _read_clipboard()
    log.info("Clipboard poller started (interval=%.1fs)", POLL_INTERVAL_S)

    while True:
        await asyncio.sleep(POLL_INTERVAL_S)

        current = _read_clipboard()
        if not current or current == _last_known_clip:
            continue

        async with _lock:
            # Echo-loop guard: if the local clipboard now equals the last
            # text we received from a remote client, skip broadcasting.
            if current == _last_remote_clip:
                _last_known_clip = current
                _last_remote_clip = None  # one-time suppression
                continue

            _last_known_clip = current

        log.info(
            "Local clipboard changed (%d chars) → broadcasting",
            len(current),
        )
        await _broadcast(current)


# ---------------------------------------------------------------------------
# Lifespan (start / stop the poller task)
# ---------------------------------------------------------------------------
@asynccontextmanager
async def _lifespan(app: FastAPI):
    task = asyncio.create_task(_clipboard_poller())
    log.info("TailClip backend ready – ws://0.0.0.0:8765/ws")
    yield
    task.cancel()
    try:
        await task
    except asyncio.CancelledError:
        pass


# ---------------------------------------------------------------------------
# FastAPI app
# ---------------------------------------------------------------------------
app = FastAPI(title="TailClip", lifespan=_lifespan)

# Mount the static directory for mobile to download files from PC
app.mount("/download", StaticFiles(directory=str(TO_MOBILE_DIR)), name="download")


@app.get("/health")
async def health():
    return {
        "status": "ok",
        "clients": len(_clients),
        "ts": datetime.now(timezone.utc).isoformat(),
    }


@app.post("/upload")
async def upload_file(file: UploadFile = File(...)):
    """Receive a file from Mobile and save it to ~/Downloads/TailClip/FromMobile/"""
    filename = file.filename or "unknown_file"
    file_path = FROM_MOBILE_DIR / filename
    
    # Avoid overwriting existing files
    counter = 1
    while file_path.exists():
        name = file_path.stem
        ext = file_path.suffix
        file_path = FROM_MOBILE_DIR / f"{name}_{counter}{ext}"
        counter += 1

    try:
        with open(file_path, "wb") as buffer:
            shutil.copyfileobj(file.file, buffer)
        log.info(f"File received from mobile: {file_path}")
        return {"status": "success", "filename": file_path.name}
    except Exception as e:
        log.error(f"Failed to save uploaded file: {e}")
        return JSONResponse(status_code=500, content={"status": "error", "message": str(e)})


@app.post("/push-to-phone")
async def push_to_phone(file: UploadFile = File(...)):
    """Receive a file from PC CLI, save it to ToMobile, and notify clients."""
    filename = file.filename or "unknown_file"
    file_path = TO_MOBILE_DIR / filename
    
    # Avoid overwriting existing files
    counter = 1
    while file_path.exists():
        name = file_path.stem
        ext = file_path.suffix
        file_path = TO_MOBILE_DIR / f"{name}_{counter}{ext}"
        counter += 1

    try:
        with open(file_path, "wb") as buffer:
            shutil.copyfileobj(file.file, buffer)
        log.info(f"File queued for mobile: {file_path}")
        
        # Broadcast message to all connected clients
        encoded_filename = urllib.parse.quote(file_path.name)
        msg = f"[TAILCLIP_FILE]:{encoded_filename}"
        await _broadcast(msg)
        
        return {"status": "success", "filename": file_path.name, "broadcasted": True}
    except Exception as e:
        log.error(f"Failed to queue file for mobile: {e}")
        return JSONResponse(status_code=500, content={"status": "error", "message": str(e)})


@app.websocket("/ws")
async def ws_endpoint(ws: WebSocket):
    global _last_known_clip, _last_remote_clip

    await ws.accept()
    _clients.add(ws)
    log.info("Client connected (%s). Total: %d", ws.client, len(_clients))

    try:
        while True:
            text = await ws.receive_text()
            if not text:
                continue

            log.info(
                "Received from client (%d chars) → updating local clipboard",
                len(text),
            )

            async with _lock:
                _last_remote_clip = text
                _last_known_clip = text

            _write_clipboard(text)

            # Relay to other connected clients (e.g. multiple phones).
            await _broadcast(text, sender=ws)

    except WebSocketDisconnect:
        log.info("Client disconnected (%s).", ws.client)
    except Exception as exc:
        log.warning("Client error: %s", exc)
    finally:
        _clients.discard(ws)
        log.info("Remaining clients: %d", len(_clients))
