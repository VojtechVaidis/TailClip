#!/usr/bin/env python3
"""
TailClip Test Client – connect to the backend and test clipboard sync.

Usage:
    python test_client.py [--host HOST] [--port PORT]

Commands (type in terminal):
    <any text>   → sends the text to the server (simulates Android → PC)
    quit         → disconnect and exit
"""

import asyncio
import argparse
import sys
import os

# Force unbuffered stdout
os.environ["PYTHONUNBUFFERED"] = "1"

import websockets


async def main(host: str, port: int) -> None:
    uri = f"ws://{host}:{port}/ws"
    sys.stdout.write(f"Connecting to {uri} ...\n")
    sys.stdout.flush()

    async with websockets.connect(uri) as ws:
        sys.stdout.write("✅ Connected! Type text to send, or 'quit' to exit.\n\n")
        sys.stdout.flush()

        async def receiver():
            """Print messages received from the server (PC → client)."""
            try:
                async for msg in ws:
                    sys.stdout.write(
                        f"\n📋 Received from server ({len(msg)} chars): {msg!r}\n> "
                    )
                    sys.stdout.flush()
            except websockets.ConnectionClosed:
                sys.stdout.write("\n⚠️  Connection closed by server.\n")
                sys.stdout.flush()

        async def sender():
            """Read stdin and send to server (client → PC)."""
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
                await ws.send(line)
                sys.stdout.write(f"📤 Sent ({len(line)} chars)\n")
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
    args = parser.parse_args()

    try:
        asyncio.run(main(args.host, args.port))
    except KeyboardInterrupt:
        sys.stdout.write("\nBye!\n")
        sys.stdout.flush()
