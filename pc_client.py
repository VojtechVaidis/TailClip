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
# Download & Upload helpers
# ---------------------------------------------------------------------------
DOWNLOAD_DIR = Path.home() / "Downloads" / "TailClip"
TO_MOBILE_DIR = DOWNLOAD_DIR / "ToMobile"


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


def _upload_file(server_url: str, filepath: Path, from_device: str, to_devices: str) -> bool:
    """Upload a file using standard library urllib."""
    import urllib.request
    import uuid

    boundary = f"TailClipBoundary{uuid.uuid4().hex}"
    
    try:
        with open(filepath, "rb") as f:
            file_content = f.read()
    except Exception as exc:
        log.error("Failed to read file %s: %s", filepath.name, exc)
        return False

    # Construct multipart body
    part_boundary = f"--{boundary}\r\n".encode("utf-8")
    end_boundary = f"--{boundary}--\r\n".encode("utf-8")

    body = bytearray()

    # from_device field
    body.extend(part_boundary)
    body.extend(f'Content-Disposition: form-data; name="from_device"\r\n\r\n{from_device}\r\n'.encode("utf-8"))

    # to_devices field
    body.extend(part_boundary)
    body.extend(f'Content-Disposition: form-data; name="to_devices"\r\n\r\n{to_devices}\r\n'.encode("utf-8"))

    # file field
    body.extend(part_boundary)
    body.extend(f'Content-Disposition: form-data; name="file"; filename="{filepath.name}"\r\n'.encode("utf-8"))
    body.extend(b'Content-Type: application/octet-stream\r\n\r\n')
    body.extend(file_content)
    body.extend(b'\r\n')
    body.extend(end_boundary)

    req = urllib.request.Request(
        f"{server_url}/push-file",
        data=body,
        headers={"Content-Type": f"multipart/form-data; boundary={boundary}"}
    )

    try:
        with urllib.request.urlopen(req) as response:
            status = response.status
            if 200 <= status < 300:
                log.info("Successfully pushed file to server: %s", filepath.name)
                return True
            else:
                log.error("Failed to push file %s (status: %d)", filepath.name, status)
                return False
    except Exception as exc:
        log.error("Failed to push file %s: %s", filepath.name, exc)
        return False


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

                async def file_watcher():
                    """Watch the ToMobile directory and upload new files."""
                    TO_MOBILE_DIR.mkdir(parents=True, exist_ok=True)
                    while True:
                        await asyncio.sleep(1.0)
                        
                        try:
                            if not TO_MOBILE_DIR.exists():
                                continue
                            files = [f for f in TO_MOBILE_DIR.iterdir() if f.is_file()]
                            for filepath in files:
                                if filepath.name.startswith("."):
                                    continue
                                
                                # Wait a moment to make sure the file is fully written
                                try:
                                    size_before = filepath.stat().st_size
                                    await asyncio.sleep(0.5)
                                    if not filepath.exists() or filepath.stat().st_size != size_before:
                                        continue
                                except FileNotFoundError:
                                    continue
                                
                                log.info("Found file in ToMobile: %s. Uploading...", filepath.name)
                                targets_str = "all"
                                if isinstance(target_devices, list):
                                    targets_str = ",".join(target_devices)
                                
                                success = await asyncio.to_thread(
                                    _upload_file,
                                    server_url,
                                    filepath,
                                    device_id,
                                    targets_str
                                )
                                if success:
                                    try:
                                        filepath.unlink()
                                        log.info("Successfully sent and deleted local file: %s", filepath.name)
                                    except Exception as e:
                                        log.error("Failed to delete local file %s: %s", filepath.name, e)
                                else:
                                    # Wait before retrying
                                    await asyncio.sleep(2.0)
                        except Exception as e:
                            log.error("Error in file watcher: %s", e)

                # Run tasks concurrently
                poller_task = asyncio.create_task(clipboard_poller())
                watcher_task = asyncio.create_task(file_watcher())
                try:
                    await message_receiver()
                finally:
                    poller_task.cancel()
                    watcher_task.cancel()

        except asyncio.CancelledError:
            log.info("Client cancelled, shutting down")
            return

        except Exception as exc:
            log.warning("Connection error: %s", exc)

        log.info("Reconnecting in %.0fs ...", reconnect_delay)
        await asyncio.sleep(reconnect_delay)
        reconnect_delay = min(reconnect_delay * 2, max_reconnect_delay)


# ---------------------------------------------------------------------------
# Configuration Persistence & CLI Menu
# ---------------------------------------------------------------------------
CONFIG_FILE = CONFIG_DIR / "config.json"


def _load_config() -> dict:
    """Load settings from config file."""
    if CONFIG_FILE.exists():
        try:
            return json.loads(CONFIG_FILE.read_text(encoding="utf-8"))
        except Exception:
            pass
    return {}


def _save_config(config: dict) -> None:
    """Save settings to config file."""
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    try:
        CONFIG_FILE.write_text(json.dumps(config, indent=2), encoding="utf-8")
    except Exception as exc:
        log.error("Failed to save config: %s", exc)


def _interactive_setup() -> tuple[str, int, str]:
    """Interactively prompt user for server settings, using cached values where possible."""
    config = _load_config()
    saved_server = config.get("server")
    saved_port = config.get("port", 8765)
    saved_device_name = config.get("device_name")

    default_name = _get_device_name(saved_device_name)

    print("\n" + "=" * 45)
    print("           TailClip PC Client Setup           ")
    print("=" * 45)

    if saved_server:
        print("Uložené nastavení serveru:")
        print(f"  Adresa:  {saved_server}:{saved_port}")
        print(f"  Zařízení: {default_name}")
        print("-" * 45)
        print("[1] Připojit s uloženým nastavením (výchozí)")
        print("[2] Nastavit nové připojení")
        print("[3] Ukončit")
        print("-" * 45)
        try:
            choice = input("Vyber možnost [1]: ").strip()
        except (KeyboardInterrupt, EOFError):
            print("\nUkončuji...")
            sys.exit(0)

        if choice == "3":
            print("Ukončuji...")
            sys.exit(0)
        elif choice != "2":
            return saved_server, saved_port, default_name

    # Prompt for new settings
    print("\nZadejte nové nastavení připojení:")
    while True:
        try:
            server = input("Adresa serveru (IP nebo doména): ").strip()
        except (KeyboardInterrupt, EOFError):
            print("\nUkončuji...")
            sys.exit(0)
        if server:
            break
        print("Chyba: Adresa serveru je povinná!")

    try:
        port_str = input(f"Port serveru [{saved_port}]: ").strip()
    except (KeyboardInterrupt, EOFError):
        print("\nUkončuji...")
        sys.exit(0)

    if port_str:
        try:
            port = int(port_str)
        except ValueError:
            print(f"Neplatný port, použije se výchozí: {saved_port}")
            port = saved_port
    else:
        port = saved_port

    try:
        device_name = input(f"Název tohoto PC [{default_name}]: ").strip()
    except (KeyboardInterrupt, EOFError):
        print("\nUkončuji...")
        sys.exit(0)

    if not device_name:
        device_name = default_name

    # Save new settings
    config = {
        "server": server,
        "port": port,
        "device_name": device_name,
    }
    _save_config(config)

    print(f"Nastavení uloženo do {CONFIG_FILE}\n")
    return server, port, device_name


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------
def main():
    parser = argparse.ArgumentParser(
        description="TailClip PC Client – clipboard sync daemon",
    )
    parser.add_argument(
        "--server", "-s",
        required=False,
        default=None,
        help="TailClip server address (IP or hostname)",
    )
    parser.add_argument(
        "--port", "-p",
        type=int,
        default=None,
        help="Server port (default: 8765 or saved)",
    )
    parser.add_argument(
        "--device-name", "-n",
        default=None,
        help="Device name (default: hostname or saved)",
    )
    parser.add_argument(
        "--targets", "-t",
        default="all",
        help="Comma-separated device IDs to send to, or 'all' (default: all)",
    )
    args = parser.parse_args()

    # Determine settings
    if args.server:
        server = args.server
        port = args.port if args.port is not None else 8765
        device_name = _get_device_name(args.device_name)
    else:
        server, port, device_name = _interactive_setup()

    log.info("TailClip PC Client")
    log.info("  Server:  %s:%d", server, port)
    log.info("  Device:  %s", device_name)
    log.info("  Targets: %s", args.targets)

    try:
        asyncio.run(run_client(
            server=server,
            port=port,
            device_name=device_name,
            targets=args.targets,
        ))
    except KeyboardInterrupt:
        log.info("Bye!")


if __name__ == "__main__":
    main()
