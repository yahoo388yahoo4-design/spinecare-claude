"""API tests: auth, landmark analysis and the WebSocket session protocol."""
import base64
import importlib
import json

import pytest
from fastapi.testclient import TestClient

AUTH = ("tester", "a-long-test-password")


@pytest.fixture()
def client(tmp_path, monkeypatch):
    monkeypatch.setenv("SPINECARE_SERVER_USER", AUTH[0])
    monkeypatch.setenv("SPINECARE_SERVER_PASSWORD", AUTH[1])
    monkeypatch.setenv("SPINECARE_DB", str(tmp_path / "s.db"))
    monkeypatch.delenv("SPINECARE_ALLOW_NO_AUTH", raising=False)
    import main
    importlib.reload(main)
    return TestClient(main.app)


def basic(user, pw):
    return {"Authorization": "Basic " + base64.b64encode(f"{user}:{pw}".encode()).decode()}


def mp33(over=None):
    """33 MediaPipe landmarks (normalized), front view standing."""
    lms = [{"x": 0.5, "y": 0.5, "z": 0.0, "visibility": 0.1} for _ in range(33)]
    pts = {0: (0.50, 0.20), 7: (0.47, 0.22), 8: (0.53, 0.22), 11: (0.42, 0.33), 12: (0.58, 0.33),
           23: (0.45, 0.62), 24: (0.55, 0.62)}
    pts.update(over or {})
    for i, (x, y) in pts.items():
        lms[i] = {"x": x, "y": y, "z": 0.0, "visibility": 0.95}
    return lms


def test_requires_auth(client):
    assert client.get("/health").status_code == 401
    assert client.get("/health", auth=("tester", "wrong")).status_code == 401
    r = client.get("/health", auth=AUTH)
    assert r.status_code == 200 and r.json()["ok"]
    assert client.get("/sessions").status_code == 401


def test_rejects_everything_when_unconfigured(tmp_path, monkeypatch):
    monkeypatch.delenv("SPINECARE_SERVER_USER", raising=False)
    monkeypatch.delenv("SPINECARE_SERVER_PASSWORD", raising=False)
    monkeypatch.delenv("SPINECARE_ALLOW_NO_AUTH", raising=False)
    monkeypatch.setenv("SPINECARE_DB", str(tmp_path / "s.db"))
    import main
    importlib.reload(main)
    c = TestClient(main.app)
    assert c.get("/health", auth=("", "")).status_code == 401
    monkeypatch.setenv("SPINECARE_ALLOW_NO_AUTH", "1")
    assert c.get("/health").status_code == 200


def test_analyze_pose_landmarks_only(client):
    r = client.post("/analyze/pose", auth=AUTH, json={"landmarks": mp33(), "width": 720, "height": 1280})
    body = r.json()
    assert r.status_code == 200
    assert body["clinicalAssessment"] is False and body["landmarkCount"] == 33
    assert "neck_yaw_est" in body["metrics"]


def test_analyze_pose_validates(client):
    assert client.post("/analyze/pose", auth=AUTH, json={"landmarks": []}).status_code == 422


def test_ws_rejects_without_auth(client):
    from starlette.websockets import WebSocketDisconnect
    with pytest.raises(WebSocketDisconnect):
        with client.websocket_connect("/ws/session") as ws:
            ws.receive_text()


def test_ws_landmark_session_counts_reps(client):
    with client.websocket_connect("/ws/session", headers=basic(*AUTH)) as ws:
        ws.send_text("not json")
        assert ws.receive_json()["type"] == "error"
        ws.send_json({"type": "landmarks", "landmarks": mp33()})
        assert ws.receive_json()["error"] == "no active session"
        ws.send_json({"type": "session", "exercise_id": "cervical_rotation_rom", "lang": "ro"})
        started = ws.receive_json()
        assert started["type"] == "session_started"
        t = 0
        seq = [0.50] * 20 + [0.50, 0.53, 0.56, 0.58, 0.58, 0.58, 0.55, 0.51, 0.50] * 3
        last = None
        for nx in seq:
            ws.send_json({"type": "landmarks", "t": t, "w": 720, "h": 1280, "landmarks": mp33({0: (nx, 0.20)})})
            last = ws.receive_json()
            assert last["type"] == "pose"
            t += 150
        assert last["framing"]["ready"]
        assert last["reps"] == 3
        assert isinstance(last["stop_conditions"], list) and last["stop_conditions"][0].startswith("durere")
        ws.send_json({"type": "end_session"})
        saved = ws.receive_json()
        assert saved["type"] == "session_saved" and saved["reps"] == 3
    rows = client.get("/sessions", auth=AUTH).json()
    assert rows[0]["reps"] == 3 and rows[0]["source"] == "landmarks"


def test_ws_bad_frame_keeps_socket(client):
    with client.websocket_connect("/ws/session", headers=basic(*AUTH)) as ws:
        ws.send_json({"type": "session", "exercise_id": "chin_tuck"})
        ws.receive_json()
        ws.send_json({"type": "frame", "t": 1, "img": "%%%not-base64"})
        assert ws.receive_json()["type"] == "error"
        ws.send_json({"type": "landmarks", "t": 2, "landmarks": mp33()})
        assert ws.receive_json()["type"] == "pose"
