"""
TailClip Backend – multi-device clipboard relay server.

Run:
    uvicorn backend:app --host 0.0.0.0 --port 8765

The server is a pure relay – it does NOT read/write a local clipboard.
Each client (Android, PC) registers with an ID and name, and can send
clipboard content to specific devices or to all connected devices.

Endpoints:
    • WS   /ws          – clipboard sync channel (JSON protocol)
    • GET  /health      – health-check endpoint
    • GET  /devices     – list of connected devices
    • POST /upload      – receive files from clients
    • POST /push-file   – push a file to specific devices
"""

from __future__ import annotations

import asyncio
import json
import logging
import uuid
from contextlib import asynccontextmanager
from dataclasses import dataclass, asdict
from datetime import datetime, timezone
from typing import Any

import os
import shutil
from pathlib import Path
import urllib.parse
from fastapi import FastAPI, WebSocket, WebSocketDisconnect, UploadFile, File, Form
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
DOWNLOADS_DIR = Path("./tailclip_files")
UPLOADS_DIR = DOWNLOADS_DIR / "uploads"
SHARED_DIR = DOWNLOADS_DIR / "shared"

UPLOADS_DIR.mkdir(parents=True, exist_ok=True)
SHARED_DIR.mkdir(parents=True, exist_ok=True)

# ---------------------------------------------------------------------------
# Device Registry
# ---------------------------------------------------------------------------

@dataclass
class DeviceConnection:
    """Represents a connected device."""
    device_id: str
    device_name: str
    device_type: str          # "android", "desktop", "browser", "cli"
    ws: WebSocket
    connected_at: datetime

    def to_dict(self) -> dict[str, Any]:
        """Serialisable representation (excludes WebSocket)."""
        return {
            "device_id": self.device_id,
            "device_name": self.device_name,
            "device_type": self.device_type,
            "connected_at": self.connected_at.isoformat(),
        }


# All connected devices keyed by device_id.
_devices: dict[str, DeviceConnection] = {}

# Lock protecting shared state.
_lock = asyncio.Lock()


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

async def _send_json(ws: WebSocket, payload: dict) -> bool:
    """Send a JSON payload to a single client. Returns False on failure."""
    try:
        await ws.send_text(json.dumps(payload))
        return True
    except Exception:
        return False


async def _broadcast_device_list() -> None:
    """Send the current device list to every connected client."""
    devices_payload = {
        "type": "device_list",
        "devices": [d.to_dict() for d in _devices.values()],
    }
    dead: list[str] = []
    for dev_id, dev in _devices.items():
        ok = await _send_json(dev.ws, devices_payload)
        if not ok:
            dead.append(dev_id)
    for dev_id in dead:
        _devices.pop(dev_id, None)


async def _route_message(
    payload: dict,
    sender_id: str,
    to_devices: list[str] | str,
) -> None:
    """Route a message to the specified targets (or 'all')."""
    dead: list[str] = []

    for dev_id, dev in _devices.items():
        if dev_id == sender_id:
            continue
        if to_devices != "all" and dev_id not in to_devices:
            continue
        ok = await _send_json(dev.ws, payload)
        if not ok:
            dead.append(dev_id)

    for dev_id in dead:
        _devices.pop(dev_id, None)


def _unique_filepath(directory: Path, filename: str) -> Path:
    """Return a unique file path to avoid overwrites."""
    file_path = directory / filename
    counter = 1
    while file_path.exists():
        stem = Path(filename).stem
        ext = Path(filename).suffix
        file_path = directory / f"{stem}_{counter}{ext}"
        counter += 1
    return file_path


# ---------------------------------------------------------------------------
# Lifespan
# ---------------------------------------------------------------------------
@asynccontextmanager
async def _lifespan(app: FastAPI):
    log.info("TailClip relay server ready – ws://0.0.0.0:8765/ws")
    yield
    # Cleanup: close all connections
    for dev in list(_devices.values()):
        try:
            await dev.ws.close()
        except Exception:
            pass
    _devices.clear()


# ---------------------------------------------------------------------------
# FastAPI app
# ---------------------------------------------------------------------------
app = FastAPI(title="TailClip", lifespan=_lifespan)

# Mount the static directory so clients can download shared files
app.mount("/download", StaticFiles(directory=str(SHARED_DIR)), name="download")


@app.get("/health")
async def health():
    return {
        "status": "ok",
        "devices": len(_devices),
        "ts": datetime.now(timezone.utc).isoformat(),
    }


@app.get("/devices")
async def list_devices():
    """Return a list of all currently connected devices."""
    return {
        "devices": [d.to_dict() for d in _devices.values()],
    }


@app.post("/upload")
async def upload_file(
    file: UploadFile = File(...),
    from_device: str = Form("unknown"),
):
    """Receive a file from a client and save it to uploads/."""
    filename = file.filename or "unknown_file"
    file_path = _unique_filepath(UPLOADS_DIR, filename)

    try:
        with open(file_path, "wb") as buffer:
            shutil.copyfileobj(file.file, buffer)
        log.info(f"File received from {from_device}: {file_path}")
        return {"status": "success", "filename": file_path.name}
    except Exception as e:
        log.error(f"Failed to save uploaded file: {e}")
        return JSONResponse(
            status_code=500,
            content={"status": "error", "message": str(e)},
        )


@app.post("/push-file")
async def push_file(
    file: UploadFile = File(...),
    from_device: str = Form("unknown"),
    to_devices: str = Form("all"),
):
    """Receive a file and notify target devices to download it."""
    filename = file.filename or "unknown_file"
    file_path = _unique_filepath(SHARED_DIR, filename)

    try:
        with open(file_path, "wb") as buffer:
            shutil.copyfileobj(file.file, buffer)
        log.info(f"File queued from {from_device}: {file_path}")

        # Parse targets
        targets: list[str] | str = "all"
        if to_devices != "all":
            targets = [t.strip() for t in to_devices.split(",") if t.strip()]

        # Notify target clients
        encoded_filename = urllib.parse.quote(file_path.name)
        msg = {
            "type": "file",
            "from_device": from_device,
            "from_name": _devices[from_device].device_name if from_device in _devices else "Unknown",
            "filename": encoded_filename,
        }
        await _route_message(msg, sender_id=from_device, to_devices=targets)

        return {
            "status": "success",
            "filename": file_path.name,
            "notified_targets": to_devices,
        }
    except Exception as e:
        log.error(f"Failed to push file: {e}")
        return JSONResponse(
            status_code=500,
            content={"status": "error", "message": str(e)},
        )


# ---------------------------------------------------------------------------
# WebSocket endpoint
# ---------------------------------------------------------------------------
@app.websocket("/ws")
async def ws_endpoint(ws: WebSocket):
    await ws.accept()

    device_id: str | None = None

    try:
        while True:
            raw = await ws.receive_text()
            if not raw:
                continue

            try:
                msg = json.loads(raw)
            except json.JSONDecodeError:
                # Legacy plaintext fallback: treat as clipboard broadcast
                log.warning("Received non-JSON message, ignoring")
                continue

            msg_type = msg.get("type")

            # ----- Registration -----
            if msg_type == "register":
                device_id = msg.get("device_id") or str(uuid.uuid4())
                device_name = msg.get("device_name", "Unknown Device")
                device_type = msg.get("device_type", "unknown")

                async with _lock:
                    # If this device_id already exists, remove old connection
                    if device_id in _devices:
                        old = _devices[device_id]
                        try:
                            await old.ws.close()
                        except Exception:
                            pass

                    _devices[device_id] = DeviceConnection(
                        device_id=device_id,
                        device_name=device_name,
                        device_type=device_type,
                        ws=ws,
                        connected_at=datetime.now(timezone.utc),
                    )

                log.info(
                    "Device registered: %s (%s / %s). Total: %d",
                    device_name, device_type, device_id[:8], len(_devices),
                )

                # Confirm registration
                await _send_json(ws, {
                    "type": "registered",
                    "device_id": device_id,
                })

                # Broadcast updated device list to all
                await _broadcast_device_list()

            # ----- Clipboard -----
            elif msg_type == "clipboard":
                if not device_id:
                    await _send_json(ws, {
                        "type": "error",
                        "message": "Must register before sending clipboard",
                    })
                    continue

                content = msg.get("content", "")
                to_devices = msg.get("to_devices", "all")
                if not content:
                    continue

                sender_name = (
                    _devices[device_id].device_name
                    if device_id in _devices
                    else "Unknown"
                )

                log.info(
                    "Clipboard from %s (%d chars) → %s",
                    sender_name,
                    len(content),
                    to_devices if to_devices == "all" else f"{len(to_devices)} devices",
                )

                outgoing = {
                    "type": "clipboard",
                    "from_device": device_id,
                    "from_name": sender_name,
                    "content": content,
                }
                await _route_message(outgoing, sender_id=device_id, to_devices=to_devices)

            # ----- Unknown message type -----
            else:
                log.warning("Unknown message type: %s", msg_type)

    except WebSocketDisconnect:
        log.info("Client disconnected (%s).", device_id or "unregistered")
    except Exception as exc:
        log.warning("Client error: %s", exc)
    finally:
        if device_id:
            async with _lock:
                _devices.pop(device_id, None)
            log.info("Device removed: %s. Remaining: %d", device_id[:8], len(_devices))
            await _broadcast_device_list()
