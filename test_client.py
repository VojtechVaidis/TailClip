#!/usr/bin/env python3
"""
TailClip Test Client – connect to the relay server and test clipboard sync.

Usage:
    python test_client.py [--host HOST] [--port PORT] [--name NAME]

Commands (type in terminal):
    <any text>       → sends the text as clipboard content to the server
    /devices         → print connected device list
    /target <id,...> → set target devices (comma-separated IDs, or 'all')
    quit             → disconnect and exit
"""

import asyncio
import argparse
import json
import sys
import os
import uuid

# Force unbuffered stdout
os.environ["PYTHONUNBUFFERED"] = "1"

import websockets


async def main(host: str, port: int, device_name: str) -> None:
    uri = f"ws://{host}:{port}/ws"
    device_id = str(uuid.uuid4())
    target_devices: list[str] | str = "all"
    connected_devices: list[dict] = []

    sys.stdout.write(f"Connecting to {uri} ...\n")
    sys.stdout.flush()

    async with websockets.connect(uri) as ws:
        # Register
        register_msg = json.dumps({
            "type": "register",
            "device_id": device_id,
            "device_name": device_name,
            "device_type": "cli",
        })
        await ws.send(register_msg)

        sys.stdout.write(f"✅ Registered as '{device_name}' (id={device_id[:8]})\n")
        sys.stdout.write("Commands: <text>, /devices, /target <id,...|all>, quit\n\n")
        sys.stdout.flush()

        async def receiver():
            """Print messages received from the server."""
            nonlocal connected_devices
            try:
                async for raw in ws:
                    try:
                        msg = json.loads(raw)
                    except json.JSONDecodeError:
                        sys.stdout.write(f"\n⚠️  Non-JSON: {raw!r}\n> ")
                        sys.stdout.flush()
                        continue

                    msg_type = msg.get("type")

                    if msg_type == "registered":
                        sys.stdout.write(f"\n✅ Registration confirmed\n> ")

                    elif msg_type == "device_list":
                        connected_devices = msg.get("devices", [])
                        others = [d for d in connected_devices if d["device_id"] != device_id]
                        sys.stdout.write(f"\n📱 Devices online ({len(others)} others):")
                        for d in others:
                            sys.stdout.write(
                                f"\n   • {d['device_name']} ({d['device_type']}) [{d['device_id'][:8]}]"
                            )
                        if not others:
                            sys.stdout.write("\n   (no other devices)")
                        sys.stdout.write("\n> ")

                    elif msg_type == "clipboard":
                        content = msg.get("content", "")
                        from_name = msg.get("from_name", "?")
                        sys.stdout.write(
                            f"\n📋 Clipboard from {from_name} ({len(content)} chars): {content!r}\n> "
                        )

                    elif msg_type == "file":
                        filename = msg.get("filename", "")
                        from_name = msg.get("from_name", "?")
                        sys.stdout.write(
                            f"\n📁 File from {from_name}: {filename}\n> "
                        )

                    elif msg_type == "error":
                        sys.stdout.write(
                            f"\n❌ Error: {msg.get('message')}\n> "
                        )

                    else:
                        sys.stdout.write(f"\n❓ Unknown: {msg}\n> ")

                    sys.stdout.flush()

            except websockets.ConnectionClosed:
                sys.stdout.write("\n⚠️  Connection closed by server.\n")
                sys.stdout.flush()

        async def sender():
            """Read stdin and send to server."""
            nonlocal target_devices
            loop = asyncio.get_event_loop()
            while True:
                sys.stdout.write("> ")
                sys.stdout.flush()
                line = await loop.run_in_executor(None, sys.stdin.readline)
                line = line.strip()
                if not line:
                    continue

                if line.lower() == "quit":
                    sys.stdout.write("Disconnecting...\n")
                    sys.stdout.flush()
                    await ws.close()
                    return

                if line.lower() == "/devices":
                    others = [d for d in connected_devices if d["device_id"] != device_id]
                    sys.stdout.write(f"📱 Known devices ({len(others)} others):\n")
                    for d in others:
                        sys.stdout.write(
                            f"   • {d['device_name']} ({d['device_type']}) [{d['device_id'][:8]}]\n"
                        )
                    if not others:
                        sys.stdout.write("   (no other devices)\n")
                    sys.stdout.write(f"Current target: {target_devices}\n")
                    sys.stdout.flush()
                    continue

                if line.lower().startswith("/target "):
                    val = line[8:].strip()
                    if val == "all":
                        target_devices = "all"
                    else:
                        target_devices = [t.strip() for t in val.split(",") if t.strip()]
                    sys.stdout.write(f"Target set to: {target_devices}\n")
                    sys.stdout.flush()
                    continue

                # Send clipboard message
                msg = json.dumps({
                    "type": "clipboard",
                    "to_devices": target_devices,
                    "content": line,
                })
                await ws.send(msg)
                sys.stdout.write(f"📤 Sent ({len(line)} chars) → {target_devices}\n")
                sys.stdout.flush()

        # Run both concurrently; when sender finishes (quit), cancel receiver.
        recv_task = asyncio.create_task(receiver())
        try:
            await sender()
        finally:
            recv_task.cancel()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="TailClip test client")
    parser.add_argument("--host", default="localhost")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--name", default="TestClient", help="Device name")
    args = parser.parse_args()

    try:
        asyncio.run(main(args.host, args.port, args.name))
    except KeyboardInterrupt:
        sys.stdout.write("\nBye!\n")
        sys.stdout.flush()
