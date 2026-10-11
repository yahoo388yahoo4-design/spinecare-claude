"""SpineCare coaching server (optional; the apps work fully offline without it).

HTTP (all authenticated with HTTP Basic unless SPINECARE_ALLOW_NO_AUTH=1):
  GET  /health            service status, GPU availability, exercise ids
  GET  /exercises         the shared exercise plan
  GET  /sessions          recent session summaries (derived metrics only)
  POST /analyze/pose      landmark-only analysis (no images): visibility summary
                          plus body-segment metrics when enough landmarks arrive

WebSocket /ws/session (JSON messages, same auth):
  client -> {"type":"session","exercise_id":..,"lang":"en"|"ro"}
  client -> {"type":"frame","t":ms,"img":"<b64 jpeg>"}                 GPU pose
  client -> {"type":"landmarks","t":ms,"w":px,"h":px,
             "format":"mediapipe33"|"named","landmarks":[..]|{..}}    privacy mode
  client -> {"type":"calibrate"}                                       redo neutral baseline
  client -> {"type":"end_session"}
  server -> {"type":"pose", metrics, framing, signal, reps, phase, cues, calibrating, latency_ms, ..}
  server -> {"type":"error","error":..}   (the socket stays open)

Frames and landmarks are processed in memory; only derived metrics and session
summaries are persisted (data minimization).
"""
from __future__ import annotations

import asyncio
import base64
import binascii
import io
import json
import logging
import os
import secrets
import sqlite3
import time
import uuid
from pathlib import Path
from typing import Optional

from fastapi import Depends, FastAPI, HTTPException, WebSocket, WebSocketDisconnect, status
from fastapi.security import HTTPBasic, HTTPBasicCredentials
from pydantic import BaseModel, Field

from pose import ExerciseTracker, PersonPose, compute_metrics, framing_check

log = logging.getLogger("spinecare")
ROOT = Path(__file__).resolve().parent


def _find_exercises() -> Path:
    for p in (os.environ.get("SPINECARE_EXERCISES"), ROOT.parent / "shared" / "exercises.json",
              ROOT / "exercises.json"):
        if p and Path(p).exists():
            return Path(p)
    raise FileNotFoundError("shared/exercises.json not found")


EXERCISES = json.loads(_find_exercises().read_text(encoding="utf-8"))
EX_BY_ID = {e["id"]: e for e in EXERCISES["exercises"]}
CALIBRATION_S = float(EXERCISES.get("calibration_s", 1.5))
DB = Path(os.environ.get("SPINECARE_DB", ROOT / "sessions.db"))
MAX_IMAGE_BYTES = 4 * 1024 * 1024

app = FastAPI(title="SpineCare coaching server", version="2.0.0", docs_url=None, redoc_url=None)
security = HTTPBasic(auto_error=False)


# ---------------------------------------------------------------- auth

def _auth_config():
    return (os.environ.get("SPINECARE_SERVER_USER", ""),
            os.environ.get("SPINECARE_SERVER_PASSWORD", ""),
            os.environ.get("SPINECARE_ALLOW_NO_AUTH", "") == "1")


def _check(user: str | None, password: str | None) -> bool:
    exp_user, exp_pass, open_mode = _auth_config()
    if not exp_user or not exp_pass:
        return open_mode
    ok_user = secrets.compare_digest((user or "").encode(), exp_user.encode())
    ok_pass = secrets.compare_digest((password or "").encode(), exp_pass.encode())
    return ok_user and ok_pass


def authenticate(credentials: Optional[HTTPBasicCredentials] = Depends(security)) -> str:
    if _check(credentials.username if credentials else None,
              credentials.password if credentials else None):
        return credentials.username if credentials else "anonymous"
    raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Authentication required",
                        headers={"WWW-Authenticate": "Basic"})


def _ws_authorized(ws: WebSocket) -> bool:
    header = ws.headers.get("authorization", "")
    user = password = None
    if header.lower().startswith("basic "):
        try:
            user, _, password = base64.b64decode(header[6:]).decode("utf-8").partition(":")
        except (binascii.Error, UnicodeDecodeError):
            return False
    return _check(user, password)


# ---------------------------------------------------------------- GPU model (lazy)

_MODEL = None


def _gpu_ok() -> bool:
    try:
        import torch
        return torch.cuda.is_available()
    except Exception:
        return False


def get_model():
    global _MODEL
    if _MODEL is None:
        from ultralytics import YOLO
        _MODEL = YOLO(os.environ.get("SPINECARE_POSE_MODEL", "yolov8n-pose.pt"))
    return _MODEL


def detect_pose(img_bytes: bytes):
    """Decode a JPEG and run GPU pose. Returns (PersonPose | None, width, height)."""
    import numpy as np
    from PIL import Image
    img = Image.open(io.BytesIO(img_bytes)).convert("RGB")
    arr = np.asarray(img)
    res = get_model().predict(arr, verbose=False, device=0 if _gpu_ok() else "cpu")
    r = res[0]
    pose = None
    if r.keypoints is not None and len(r.keypoints) > 0:
        rows = r.keypoints.data[0].cpu().numpy().tolist()
        bbox = tuple(map(float, r.boxes.xyxy[0].cpu().numpy())) if r.boxes is not None and len(r.boxes) else None
        pose = PersonPose.from_coco(rows, bbox)
    return pose, arr.shape[1], arr.shape[0]


# ---------------------------------------------------------------- storage

def _db():
    con = sqlite3.connect(DB)
    con.execute("""CREATE TABLE IF NOT EXISTS sessions(
        id TEXT PRIMARY KEY, exercise_id TEXT, started_at REAL, ended_at REAL,
        reps INTEGER, avg_metrics TEXT, cues TEXT, frames INTEGER, source TEXT)""")
    cols = {r[1] for r in con.execute("PRAGMA table_info(sessions)")}
    if "source" not in cols:  # databases created by the earlier server
        con.execute("ALTER TABLE sessions ADD COLUMN source TEXT")
    return con


def _save_session(s: "Session"):
    avg = s.averages()
    con = _db()
    con.execute("INSERT OR REPLACE INTO sessions VALUES(?,?,?,?,?,?,?,?,?)",
                (s.id, s.ex["id"], s.started, time.time(), s.tracker.reps.reps,
                 json.dumps(avg), json.dumps(s.cue_counts), s.frames, s.source))
    con.commit()
    con.close()
    return avg


class Session:
    def __init__(self, ex_id: str, lang: str):
        self.id = uuid.uuid4().hex
        self.ex = EX_BY_ID[ex_id]
        self.started = time.time()
        self.tracker = ExerciseTracker(self.ex, CALIBRATION_S, lang)
        self.sums: dict[str, list[float]] = {}
        self.cue_counts: dict[str, int] = {}
        self.frames = 0
        self.source = "landmarks"

    def averages(self):
        return {k: round(v[0] / v[1], 3) for k, v in self.sums.items() if v[1]}

    def process(self, pose: PersonPose | None, w: float, h: float, t: float, t_client) -> dict:
        ex = self.ex
        framing = framing_check(pose, ex["required_landmarks"], ex["min_conf"], w, h)
        metrics = compute_metrics(pose) if pose else {}
        state = self.tracker.update(metrics, framing["ready"], t)
        for c in state["cues"]:
            self.cue_counts[c["metric"]] = self.cue_counts.get(c["metric"], 0) + 1
        for k, v in metrics.items():
            acc = self.sums.setdefault(k, [0.0, 0])
            acc[0] += v
            acc[1] += 1
        self.frames += 1
        return {
            "type": "pose", "t": t_client, "session_id": self.id,
            "landmarks": ([{"name": n, "x": pose.p(n)[0], "y": pose.p(n)[1], "visibility": pose.p(n)[2]}
                           for n in pose.kpts] if pose else []),
            "metrics": {k: round(v, 3) for k, v in metrics.items()},
            "signal": state["signal"], "framing": framing,
            "calibrating": state["calibrating"],
            "reps": state["reps"], "phase": state["phase"],
            "rep_just_done": state["rep_just_done"],
            "target_reps": ex["target_reps"],
            "cues": state["cues"],
            "stop_conditions": ex["stop_conditions"].get(self.tracker.lang, ex["stop_conditions"]["en"])
            if isinstance(ex["stop_conditions"], dict) else ex["stop_conditions"],
        }


# ---------------------------------------------------------------- HTTP

@app.get("/health")
def health(_: str = Depends(authenticate)):
    return {"ok": True, "status": "ok", "service": "spinecare", "schemaVersion": 2,
            "gpu": _gpu_ok(), "exercises": list(EX_BY_ID)}


@app.get("/exercises")
def exercises(_: str = Depends(authenticate)):
    return EXERCISES


@app.get("/sessions")
def sessions(_: str = Depends(authenticate)):
    con = _db()
    rows = con.execute("SELECT id,exercise_id,started_at,ended_at,reps,frames,source FROM sessions "
                       "ORDER BY started_at DESC LIMIT 50").fetchall()
    con.close()
    return [dict(zip(["id", "exercise_id", "started_at", "ended_at", "reps", "frames", "source"], r))
            for r in rows]


class Landmark(BaseModel):
    x: float
    y: float
    z: float = 0.0
    visibility: Optional[float] = Field(default=None, ge=0, le=1)
    presence: Optional[float] = Field(default=None, ge=0, le=1)


class AnalysisRequest(BaseModel):
    landmarks: list[Landmark] = Field(min_length=1, max_length=100)
    client: str = Field(default="spinecare-mobile", max_length=64)
    schemaVersion: int = Field(default=1, ge=1)
    width: float = Field(default=1.0, gt=0)
    height: float = Field(default=1.0, gt=0)


@app.post("/analyze/pose")
def analyze_pose(request: AnalysisRequest, _: str = Depends(authenticate)):
    """Landmark-only analysis. Technical quality summary plus body-segment
    estimates; makes no medical, diagnostic or exercise-safety claims."""
    count = len(request.landmarks)
    vis = [p.visibility for p in request.landmarks if p.visibility is not None]
    low = sum(v < 0.5 for v in vis)
    mean = round(sum(vis) / len(vis), 3) if vis else None
    if not vis:
        summary = f"Received {count} pose landmarks. Visibility confidence was not supplied."
    elif mean < 0.5:
        summary = "Pose visibility is limited. Improve lighting and camera framing, then retry."
    elif low:
        summary = f"Received {count} landmarks; {low} have low visibility confidence. Check framing."
    else:
        summary = f"Received {count} landmarks with generally visible keypoints. This is not a clinical assessment."
    metrics = {}
    if count >= 33:
        pose = PersonPose.from_mediapipe([p.model_dump() for p in request.landmarks],
                                         request.width, request.height)
        metrics = {k: round(v, 3) for k, v in compute_metrics(pose).items()}
    return {"summary": summary, "landmarkCount": count, "meanVisibility": mean,
            "lowVisibilityCount": low, "metrics": metrics, "clinicalAssessment": False}


# ---------------------------------------------------------------- WebSocket

def _pose_from_landmarks(msg: dict):
    w = float(msg.get("w") or 1.0)
    h = float(msg.get("h") or 1.0)
    lms = msg.get("landmarks")
    if not lms:
        return None, w, h
    if isinstance(lms, dict) or msg.get("format") == "named":
        return PersonPose.from_named(lms, w, h), w, h
    return PersonPose.from_mediapipe(lms, w, h), w, h


@app.websocket("/ws/session")
async def ws_session(ws: WebSocket):
    if not _ws_authorized(ws):
        await ws.close(code=1008)
        return
    await ws.accept()
    session: Session | None = None
    loop = asyncio.get_running_loop()

    async def error(text):
        await ws.send_json({"type": "error", "error": text})

    try:
        while True:
            raw = await ws.receive_text()
            t0 = time.time()
            try:
                msg = json.loads(raw)
                kind = msg["type"]
            except (ValueError, KeyError, TypeError):
                await error("invalid message")
                continue

            if kind == "session":
                ex_id = msg.get("exercise_id")
                if ex_id not in EX_BY_ID:
                    await error("unknown exercise")
                    continue
                if session is not None and session.frames:
                    _save_session(session)
                lang = msg.get("lang") if msg.get("lang") in ("en", "ro") else "en"
                session = Session(ex_id, lang)
                await ws.send_json({"type": "session_started", "session_id": session.id,
                                    "exercise": session.ex})
            elif kind == "calibrate":
                if session:
                    session.tracker.recalibrate()
            elif kind in ("frame", "landmarks"):
                if session is None:
                    await error("no active session")
                    continue
                # Rep timing uses the client timestamp when present (video replay,
                # buffered streams) and server wall-clock otherwise.
                t_client = msg.get("t")
                rep_t = t_client / 1000.0 if isinstance(t_client, (int, float)) else time.time()
                try:
                    if kind == "frame":
                        img = base64.b64decode(msg["img"], validate=True)
                        if len(img) > MAX_IMAGE_BYTES:
                            raise ValueError("image too large")
                        pose, w, h = await loop.run_in_executor(None, detect_pose, img)
                        session.source = "gpu-server"
                    else:
                        pose, w, h = _pose_from_landmarks(msg)
                except Exception as e:  # bad image / payload: report, keep the socket
                    await error(f"could not process {kind}: {type(e).__name__}")
                    continue
                reply = session.process(pose, w, h, rep_t, t_client)
                reply["latency_ms"] = int((time.time() - t0) * 1000)
                await ws.send_json(reply)
            elif kind == "end_session":
                if session:
                    avg = _save_session(session)
                    await ws.send_json({"type": "session_saved", "session_id": session.id,
                                        "reps": session.tracker.reps.reps, "avg_metrics": avg,
                                        "cue_counts": session.cue_counts, "frames": session.frames})
                    session = None
            else:
                await error("unknown message type")
    except WebSocketDisconnect:
        pass
    finally:
        if session is not None and session.frames:
            _save_session(session)


if __name__ == "__main__":
    import uvicorn
    user, password, open_mode = _auth_config()
    if not (user and password) and not open_mode:
        log.warning("SPINECARE_SERVER_USER/PASSWORD are not set: every request will be rejected. "
                    "Set them, or SPINECARE_ALLOW_NO_AUTH=1 for local development only.")
    uvicorn.run(app, host=os.environ.get("SPINECARE_HOST", "0.0.0.0"),
                port=int(os.environ.get("SPINECARE_PORT", "8300")))
