"""End-to-end WebSocket test: start session, stream a real photo, expect pose+metrics.
Run on the VM after the server is up:  python e2e_ws_test.py [image.jpg]
"""
import asyncio, base64, json, sys, time
import websockets  # noqa: F401
from ws_auth import connect

URI = "ws://127.0.0.1:8300/ws/session"

async def main(img_path: str):
    async with connect(URI, max_size=8 * 1024 * 1024) as ws:
        await ws.send(json.dumps({"type": "session", "exercise_id": "cervical_rotation_rom"}))
        msg = json.loads(await ws.recv())
        assert msg["type"] == "session_started", msg
        print("session:", msg["session_id"], "exercise:", msg["exercise"]["name"])

        with open(img_path, "rb") as f:
            b64 = base64.b64encode(f.read()).decode()

        lat = []
        for i in range(10):
            t0 = time.time()
            await ws.send(json.dumps({"type": "frame", "t": int(t0 * 1000), "img": b64, "w": 0, "h": 0}))
            reply = json.loads(await ws.recv())
            assert reply["type"] == "pose", reply
            lat.append(reply["latency_ms"])
            if i == 0:
                print("first pose:", json.dumps({k: reply[k] for k in ("metrics", "framing", "reps", "phase", "cues")}, indent=1)[:800])
        await ws.send(json.dumps({"type": "end_session"}))
        saved = json.loads(await ws.recv())
        assert saved["type"] == "session_saved", saved
        print(f"latency p50={sorted(lat)[len(lat)//2]}ms mean={sum(lat)/len(lat):.0f}ms frames={saved['frames']}")
        print("session saved:", saved["session_id"], "avg:", saved["avg_metrics"])
        print("E2E OK")

if __name__ == "__main__":
    asyncio.run(main(sys.argv[1] if len(sys.argv) > 1 else str(__import__("pathlib").Path(__file__).resolve().parent.parent / "testdata" / "zidane.jpg")))
