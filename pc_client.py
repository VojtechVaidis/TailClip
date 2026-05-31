#!/usr/bin/env python3
"""
TailClip PC Client – persistent clipboard sync daemon.

Connects to a remote TailClip relay server, watches the local system
clipboard, and syncs changes bidirectionally.

Usage:
    python pc_client.py --server 100.x.x.x [--port 8765] [--device-name "My PC"]

The client:
    • Registers as a "desktop" device on the server
    • Polls the local clipboard for changes and sends them to the server
    • Receives clipboard content from the server and writes it locally
    • Handles file download notifications
"""

from __future__ import annotations

import argparse
import asyncio
import json
import logging
import os
import platform
import signal
import sys
import uuid
from pathlib import Path

import websockets

# ---------------------------------------------------------------------------
# Logging
# ---------------------------------------------------------------------------
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s | %(levelname)-7s | %(message)s",
    datefmt="%H:%M:%S",
)
log = logging.getLogger("tailclip-pc")

# ---------------------------------------------------------------------------
# Clipboard helpers
# ---------------------------------------------------------------------------
POLL_INTERVAL_S: float = 0.5


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
# Device ID persistence
# ---------------------------------------------------------------------------
CONFIG_DIR = Path.home() / ".config" / "tailclip"
DEVICE_ID_FILE = CONFIG_DIR / "device_id"


def _get_device_id() -> str:
    """Get or create a persistent device ID."""
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    if DEVICE_ID_FILE.exists():
        return DEVICE_ID_FILE.read_text().strip()
    device_id = str(uuid.uuid4())
    DEVICE_ID_FILE.write_text(device_id)
    log.info("Generated new device ID: %s", device_id[:8])
    return device_id


def _get_device_name(override: str | None = None) -> str:
    """Auto-detect device name from hostname, or use override."""
    if override:
        return override
    return platform.node() or "PC"


# ---------------------------------------------------------------------------
# Download helpers
# ---------------------------------------------------------------------------
DOWNLOAD_DIR = Path.home() / "Downloads" / "TailClip"


def _download_file(server_url: str, filename: str) -> None:
    """Download a file from the relay server."""
    import urllib.request
    import urllib.parse

    DOWNLOAD_DIR.mkdir(parents=True, exist_ok=True)
    url = f"{server_url}/download/{urllib.parse.quote(filename)}"
    dest = DOWNLOAD_DIR / urllib.parse.unquote(filename)

    try:
        urllib.request.urlretrieve(url, str(dest))
        log.info("Downloaded file: %s → %s", filename, dest)
    except Exception as exc:
        log.error("Failed to download %s: %s", filename, exc)


# ---------------------------------------------------------------------------
# Main client loop
# ---------------------------------------------------------------------------
async def run_client(
    server: str,
    port: int,
    device_name: str,
    targets: str,
) -> None:
    """Connect to the server and run clipboard sync."""

    device_id = _get_device_id()
    uri = f"ws://{server}:{port}/ws"
    server_url = f"http://{server}:{port}"

    # State for echo-loop prevention
    last_known_clip: str = _read_clipboard()
    last_remote_clip: str | None = None

    reconnect_delay = 1.0
    max_reconnect_delay = 30.0

    while True:
        try:
            log.info("Connecting to %s ...", uri)

            async with websockets.connect(uri, ping_interval=15) as ws:
                reconnect_delay = 1.0  # reset on success

                # Register
                register_msg = json.dumps({
                    "type": "register",
                    "device_id": device_id,
                    "device_name": device_name,
                    "device_type": "desktop",
                })
                await ws.send(register_msg)
                log.info("Registered as '%s' (id=%s)", device_name, device_id[:8])

                # Parse target list
                target_devices: list[str] | str = "all"
                if targets and targets != "all":
                    target_devices = [t.strip() for t in targets.split(",") if t.strip()]

                async def clipboard_poller():
                    """Poll local clipboard and send changes."""
                    nonlocal last_known_clip, last_remote_clip

                    while True:
                        await asyncio.sleep(POLL_INTERVAL_S)
                        current = _read_clipboard()

                        if not current or current == last_known_clip:
                            continue

                        # Echo-loop guard
                        if current == last_remote_clip:
                            last_known_clip = current
                            last_remote_clip = None
                            continue

                        last_known_clip = current

                        msg = json.dumps({
                            "type": "clipboard",
                            "to_devices": target_devices,
                            "content": current,
                        })
                        await ws.send(msg)
                        log.info(
                            "Local clipboard changed (%d chars) → sent",
                            len(current),
                        )

                async def message_receiver():
                    """Receive messages from the server."""
                    nonlocal last_known_clip, last_remote_clip

                    async for raw in ws:
                        try:
                            msg = json.loads(raw)
                        except json.JSONDecodeError:
                            continue

                        msg_type = msg.get("type")

                        if msg_type == "registered":
                            log.info("✅ Registration confirmed by server")

                        elif msg_type == "device_list":
                            devices = msg.get("devices", [])
                            others = [
                                d for d in devices
                                if d["device_id"] != device_id
                            ]
                            log.info(
                                "Connected devices: %s",
                                ", ".join(
                                    f"{d['device_name']} ({d['device_type']})"
                                    for d in others
                                ) or "(none)",
                            )

                        elif msg_type == "clipboard":
                            content = msg.get("content", "")
                            from_name = msg.get("from_name", "?")
                            if content:
                                last_remote_clip = content
                                last_known_clip = content
                                _write_clipboard(content)
                                log.info(
                                    "📋 Clipboard from %s (%d chars) → written locally",
                                    from_name, len(content),
                                )

                        elif msg_type == "file":
                            filename = msg.get("filename", "")
                            from_name = msg.get("from_name", "?")
                            if filename:
                                log.info("📁 File from %s: %s", from_name, filename)
                                _download_file(server_url, filename)

                        elif msg_type == "error":
                            log.error("Server error: %s", msg.get("message"))

                # Run both tasks concurrently
                poller_task = asyncio.create_task(clipboard_poller())
                try:
                    await message_receiver()
                finally:
                    poller_task.cancel()

        except asyncio.CancelledError:
            log.info("Client cancelled, shutting down")
            return

        except Exception as exc:
            log.warning("Connection error: %s", exc)

        log.info("Reconnecting in %.0fs ...", reconnect_delay)
        await asyncio.sleep(reconnect_delay)
        reconnect_delay = min(reconnect_delay * 2, max_reconnect_delay)


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------
def main():
    parser = argparse.ArgumentParser(
        description="TailClip PC Client – clipboard sync daemon",
    )
    parser.add_argument(
        "--server", "-s",
        required=True,
        help="TailClip server address (IP or hostname)",
    )
    parser.add_argument(
        "--port", "-p",
        type=int,
        default=8765,
        help="Server port (default: 8765)",
    )
    parser.add_argument(
        "--device-name", "-n",
        default=None,
        help="Device name (default: hostname)",
    )
    parser.add_argument(
        "--targets", "-t",
        default="all",
        help="Comma-separated device IDs to send to, or 'all' (default: all)",
    )
    args = parser.parse_args()

    device_name = _get_device_name(args.device_name)

    log.info("TailClip PC Client")
    log.info("  Server:  %s:%d", args.server, args.port)
    log.info("  Device:  %s", device_name)
    log.info("  Targets: %s", args.targets)

    try:
        asyncio.run(run_client(
            server=args.server,
            port=args.port,
            device_name=device_name,
            targets=args.targets,
        ))
    except KeyboardInterrupt:
        log.info("Bye!")


if __name__ == "__main__":
    main()
