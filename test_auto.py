#!/usr/bin/env python3 -u
"""
Automated integration test for TailClip backend.
Run this while 'uvicorn backend:app --host 0.0.0.0 --port 8765' is running.

    python3 -u test_auto.py
"""

import asyncio
import sys
import pyperclip
import websockets


async def run_tests():
    uri = "ws://localhost:8765/ws"
    print(f"🔌 Connecting to {uri} ...")

    async with websockets.connect(uri) as ws:
        print("✅ Connected!\n")
        passed = 0
        failed = 0

        # --- Test 1: Client → PC ---
        print("── Test 1: Client → PC (send text, check clipboard) ──")
        test_text = "TailClip-test-12345"
        await ws.send(test_text)
        await asyncio.sleep(1)
        clip = pyperclip.paste()
        if clip == test_text:
            print(f"   ✅ PASS – clipboard contains: {clip!r}")
            passed += 1
        else:
            print(f"   ❌ FAIL – expected {test_text!r}, got {clip!r}")
            failed += 1

        # --- Test 2: PC → Client ---
        print("\n── Test 2: PC → Client (copy on PC, check WS message) ──")
        new_text = "Copied-on-PC-67890"
        pyperclip.copy(new_text)
        try:
            msg = await asyncio.wait_for(ws.recv(), timeout=3)
            if msg == new_text:
                print(f"   ✅ PASS – received: {msg!r}")
                passed += 1
            else:
                print(f"   ❌ FAIL – expected {new_text!r}, got {msg!r}")
                failed += 1
        except asyncio.TimeoutError:
            print("   ❌ FAIL – timeout, no message received")
            failed += 1

        # --- Test 3: Echo prevention ---
        print("\n── Test 3: Echo prevention (send text, should NOT echo) ──")
        echo_text = "EchoTest-ABCDE"
        await ws.send(echo_text)
        await asyncio.sleep(0.5)
        try:
            msg = await asyncio.wait_for(ws.recv(), timeout=2.5)
            print(f"   ❌ FAIL – got echo: {msg!r}")
            failed += 1
        except asyncio.TimeoutError:
            print("   ✅ PASS – no echo received")
            passed += 1

        # --- Summary ---
        print(f"\n{'='*40}")
        print(f"Results: {passed} passed, {failed} failed")
        if failed == 0:
            print("🎉 All tests passed! Backend is ready.")
        else:
            print("⚠️  Some tests failed.")
        print(f"{'='*40}")

        return failed == 0


if __name__ == "__main__":
    try:
        ok = asyncio.run(run_tests())
        sys.exit(0 if ok else 1)
    except ConnectionRefusedError:
        print("❌ Cannot connect. Is the backend running?")
        print("   uvicorn backend:app --host 0.0.0.0 --port 8765")
        sys.exit(1)
    except KeyboardInterrupt:
        print("\nAborted.")
        sys.exit(1)
