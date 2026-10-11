"""Pose analysis shared by every coaching path: landmarks -> body-segment
metrics -> framing gate -> calibrated signal -> rep counting + form cues.

Landmarks use one name space across server, Android and iOS:
  nose, lear, rear, lsho, rsho, lelb, relb, lwri, rwri,
  lhip, rhip, lknee, rknee, lank, rank
Coordinates are image pixels (or any isotropic unit), y pointing down.
Android (Metrics.kt) and iOS (Engines.swift) mirror this file; the shared test
vectors in shared/metric_vectors.json keep the three implementations in step.

These are ESTIMATES of body-segment orientation, never vertebral measurements.
"""
from __future__ import annotations

import math
from dataclasses import dataclass
from statistics import median

NAMES = ["nose", "lear", "rear", "lsho", "rsho", "lelb", "relb", "lwri", "rwri",
         "lhip", "rhip", "lknee", "rknee", "lank", "rank"]

# COCO-17 order (YOLOv8-pose output)
COCO17 = ["nose", "leye", "reye", "lear", "rear", "lsho", "rsho", "lelb", "relb",
          "lwri", "rwri", "lhip", "rhip", "lknee", "rknee", "lank", "rank"]

# MediaPipe BlazePose 33 indices for the names we use
MEDIAPIPE33 = {"nose": 0, "lear": 7, "rear": 8, "lsho": 11, "rsho": 12, "lelb": 13,
               "relb": 14, "lwri": 15, "rwri": 16, "lhip": 23, "rhip": 24,
               "lknee": 25, "rknee": 26, "lank": 27, "rank": 28}

VIS = 0.5


@dataclass
class PersonPose:
    """Named keypoints: name -> (x, y, conf)."""
    kpts: dict
    bbox: tuple | None = None

    def p(self, name):
        return self.kpts.get(name, (0.0, 0.0, 0.0))

    def vis(self, name, min_conf=VIS):
        return self.p(name)[2] >= min_conf

    @classmethod
    def from_coco(cls, rows, bbox=None):
        return cls({n: (float(r[0]), float(r[1]), float(r[2])) for n, r in zip(COCO17, rows)}, bbox)

    @classmethod
    def from_mediapipe(cls, lms, w: float = 1.0, h: float = 1.0):
        """lms: list of 33 dicts/tuples with normalized x, y and visibility."""
        out = {}
        for name, idx in MEDIAPIPE33.items():
            if idx < len(lms):
                x, y, c = _xyc(lms[idx])
                out[name] = (x * w, y * h, c)
        return cls(out)

    @classmethod
    def from_named(cls, lms: dict, w: float = 1.0, h: float = 1.0):
        out = {}
        for name, v in lms.items():
            x, y, c = _xyc(v)
            out[name] = (x * w, y * h, c)
        return cls(out)


def _xyc(v):
    if isinstance(v, dict):
        c = v.get("visibility", v.get("conf", v.get("presence", 1.0)))
        return float(v["x"]), float(v["y"]), float(1.0 if c is None else c)
    return float(v[0]), float(v[1]), float(v[2]) if len(v) > 2 else 1.0


def _mid(a, b):
    return ((a[0] + b[0]) / 2.0, (a[1] + b[1]) / 2.0, min(a[2], b[2]))


def _dist(a, b):
    return math.hypot(a[0] - b[0], a[1] - b[1])


def _ang_between(v1, v2):
    n1 = math.hypot(*v1)
    n2 = math.hypot(*v2)
    if n1 < 1e-6 or n2 < 1e-6:
        return None
    d = (v1[0] * v2[0] + v1[1] * v2[1]) / (n1 * n2)
    return math.degrees(math.acos(max(-1.0, min(1.0, d))))


def _ang_from_vertical(a, b):
    """Angle of segment a->b vs downward vertical (0 = straight down)."""
    return _ang_between((b[0] - a[0], b[1] - a[1]), (0.0, 1.0))


def best_side(pose: PersonPose):
    """Side ('l' or 'r') whose ear/shoulder/hip chain is most visible, or None."""
    def score(s):
        return min(pose.p(s + "ear")[2], pose.p(s + "sho")[2], pose.p(s + "hip")[2])
    s = "l" if score("l") >= score("r") else "r"
    return s if score(s) >= VIS else None


def compute_metrics(pose: PersonPose) -> dict:
    """Visible-segment metrics. Angles in degrees; offsets normalized by torso length."""
    m: dict[str, float] = {}
    lsho, rsho = pose.p("lsho"), pose.p("rsho")
    lhip, rhip = pose.p("lhip"), pose.p("rhip")
    lear, rear = pose.p("lear"), pose.p("rear")
    nose = pose.p("nose")
    v = pose.vis

    both_sho = v("lsho") and v("rsho")
    both_hip = v("lhip") and v("rhip")
    sho_mid = _mid(lsho, rsho)
    hip_mid = _mid(lhip, rhip)
    sho_w = _dist(lsho, rsho)

    # Frontal/back view
    if both_sho and both_hip:
        torso = _dist(sho_mid, hip_mid)
        m["trunk_tilt_frontal"] = _ang_from_vertical(sho_mid, hip_mid)
        if torso > 1e-6:
            m["shoulder_width_ratio"] = sho_w / torso
    if v("lear") and v("rear") and both_sho:
        m["cervical_tilt"] = _ang_from_vertical(_mid(lear, rear), sho_mid)
    # Neck rotation (front view): signed lateral nose offset vs shoulder midline.
    # Ear-separation proxies collapse when an ear occludes during rotation.
    if v("nose") and both_sho and sho_w > 1e-6:
        ratio = max(-1.0, min(1.0, (nose[0] - sho_mid[0]) / (0.28 * sho_w)))
        m["neck_yaw_est"] = math.degrees(math.asin(ratio))
    if both_sho:
        deg = math.degrees(math.atan2(rsho[1] - lsho[1], rsho[0] - lsho[0]))
        m["shoulder_line_tilt"] = (deg + 90.0) % 180.0 - 90.0

    # Side view: use the single most visible ear/shoulder/hip chain, and orient
    # by facing direction (nose ahead of the ear) so either profile works.
    side = best_side(pose)
    if side and v("nose"):
        ear, sho, hip = pose.p(side + "ear"), pose.p(side + "sho"), pose.p(side + "hip")
        facing = 1.0 if nose[0] >= ear[0] else -1.0
        torso = _dist(sho, hip)
        if torso > 1e-6:
            # + = head ahead of the shoulder (forward head posture)
            m["head_forward_proxy"] = (ear[0] - sho[0]) * facing / torso
            # + = shoulders behind hips (trunk extension / leaning back)
            m["trunk_lean_sagittal"] = math.degrees(
                math.atan2((sho[0] - hip[0]) * -facing, hip[1] - sho[1]))
        ang = _ang_between((nose[0] - ear[0], nose[1] - ear[1]), (sho[0] - ear[0], sho[1] - ear[1]))
        if ang is not None:
            m["cervical_sag_proxy"] = ang
    return {k: val for k, val in m.items() if val is not None}


def _resolve(pose: PersonPose, name: str) -> str:
    """'any_ear' etc. -> the more confident of the left/right landmark."""
    if name.startswith("any_"):
        part = name[4:]
        l, r = "l" + part, "r" + part
        return l if pose.p(l)[2] >= pose.p(r)[2] else r
    return name


def framing_check(pose: PersonPose | None, required: list[str], min_conf: float,
                  frame_w: float, frame_h: float, margin: float = 0.03) -> dict:
    """Verify required landmarks are visible and inside frame margins."""
    if pose is None:
        return {"ready": False, "issues": ["no_person"], "missing": list(required)}
    issues, missing = [], []
    for req in required:
        name = _resolve(pose, req)
        x, y, c = pose.p(name)
        if c < min_conf:
            missing.append(req)
            issues.append(f"{req}:low_conf")
        elif not (margin * frame_w <= x <= (1 - margin) * frame_w
                  and margin * frame_h <= y <= (1 - margin) * frame_h):
            missing.append(req)
            issues.append(f"{req}:out_of_frame")
    return {"ready": not missing, "issues": issues, "missing": missing}


class RepEngine:
    """Hysteresis phase machine counting reps on a scalar signal.
    A rep = signal rises above `high` (held >= min_hold_s) and returns below `low`."""

    def __init__(self, low: float, high: float, min_hold_s: float = 0.4):
        self.low, self.high, self.min_hold = low, high, min_hold_s
        self.phase = "rest"
        self.reps = 0
        self.t_enter = None

    def update(self, value: float | None, t: float) -> dict:
        if value is None or math.isnan(value):
            return {"reps": self.reps, "phase": self.phase, "rep_just_done": False}
        done = False
        if self.phase in ("rest", "in") and value >= self.high:
            self.phase, self.t_enter = "out", t
        elif self.phase == "out" and value <= self.low:
            if t - (self.t_enter if self.t_enter is not None else t) >= self.min_hold:
                self.reps += 1
                done = True
            self.phase, self.t_enter = "in", t
        return {"reps": self.reps, "phase": self.phase, "rep_just_done": done}


def _localized(v, lang):
    if isinstance(v, dict):
        return v.get(lang) or v.get("en") or next(iter(v.values()), "")
    return v


class ExerciseTracker:
    """Calibration + signal + reps + form cues for one exercise. Mirrors the
    Android/iOS ExerciseTracker so every coaching path behaves the same."""

    def __init__(self, ex: dict, calibration_s: float = 1.5, lang: str = "en"):
        self.ex = ex
        self.lang = lang
        self.calibration_s = calibration_s
        band = ex["rep_band"]
        self.reps = RepEngine(band["low"], band["high"])
        self.direction = float(ex.get("direction", 1))
        sig = ex["signal"]
        self.use_abs = sig.startswith("abs:")
        self.metric = sig[4:] if self.use_abs else sig
        self.recalibrate()

    def recalibrate(self):
        self.calib_start = None
        self.calib_samples: dict[str, list[float]] = {}
        self.baseline: dict[str, float] | None = None

    @property
    def calibrating(self):
        return self.baseline is None

    def signal(self, metrics: dict) -> float | None:
        raw = metrics.get(self.metric)
        if raw is None or self.baseline is None:
            return None
        v = (raw - self.baseline.get(self.metric, 0.0)) * self.direction
        return abs(v) if self.use_abs else v

    def update(self, metrics: dict, ready: bool, t: float) -> dict:
        out = {"signal": None, "reps": self.reps.reps, "phase": self.reps.phase,
               "rep_just_done": False, "cues": [], "calibrating": self.calibrating}
        if not ready:
            return out
        if self.baseline is None:
            if self.calib_start is None:
                self.calib_start = t
            for k, v in metrics.items():
                self.calib_samples.setdefault(k, []).append(v)
            if t - self.calib_start >= self.calibration_s and self.metric in self.calib_samples:
                self.baseline = {k: median(v) for k, v in self.calib_samples.items() if v}
                out["calibrating"] = False
            return out
        sig = self.signal(metrics)
        state = self.reps.update(sig, t)
        out.update(state)
        out["signal"] = sig
        for rule in self.ex.get("form_rules", []):
            v = metrics.get(rule["metric"])
            if v is None:
                continue
            if rule.get("relative") and rule["metric"] in self.baseline:
                v = v - self.baseline[rule["metric"]]
            if abs(v) > rule["max_abs"]:
                out["cues"].append({"metric": rule["metric"], "value": round(v, 1),
                                    "cue": _localized(rule["cue"], self.lang)})
        return out
