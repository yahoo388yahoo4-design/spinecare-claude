"""Regenerate shared/metric_vectors.json from server/pose.py.

The vectors pin the metric definitions so the Android (Metrics.kt) and iOS
(Engines.swift) ports can be checked against the server implementation.
Run: python scripts/gen_metric_vectors.py
"""
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "server"))
from pose import PersonPose, compute_metrics  # noqa: E402

FRONT = {
    "nose": (320, 100, 0.9), "lear": (300, 110, 0.9), "rear": (340, 110, 0.9),
    "lsho": (270, 160, 0.9), "rsho": (370, 160, 0.9),
    "lelb": (255, 230, 0.8), "relb": (385, 230, 0.8),
    "lwri": (250, 300, 0.8), "rwri": (390, 300, 0.8),
    "lhip": (285, 300, 0.9), "rhip": (355, 300, 0.9),
    "lknee": (283, 380, 0.9), "rknee": (357, 380, 0.9),
    "lank": (282, 450, 0.9), "rank": (358, 450, 0.9),
}
SIDE_R = {  # profile facing image-right, left side toward the camera
    "nose": (352, 102, 0.9), "lear": (318, 108, 0.9), "rear": (318, 108, 0.2),
    "lsho": (305, 165, 0.9), "rsho": (305, 165, 0.3),
    "lhip": (300, 305, 0.9), "rhip": (300, 305, 0.3),
    "lknee": (350, 330, 0.9), "rknee": (350, 330, 0.3),
}


def mirror(d, width=640):
    return {k: (width - x, y, c) for k, (x, y, c) in d.items()}


def with_(base, **kw):
    d = dict(base)
    d.update(kw)
    return d


CASES = {
    "front_upright": FRONT,
    "front_trunk_lean": with_(FRONT, lhip=(345, 300, 0.9), rhip=(415, 300, 0.9)),
    "front_head_right": with_(FRONT, nose=(336, 100, 0.9)),
    "front_head_left_ears_occluded": with_(FRONT, nose=(304, 100, 0.9), lear=(300, 110, 0.1), rear=(322, 110, 0.1)),
    "front_shoulder_raised": with_(FRONT, rsho=(370, 148, 0.9)),
    "side_facing_right": SIDE_R,
    "side_facing_left": mirror(SIDE_R),
    "side_head_forward": with_(SIDE_R, lear=(335, 110, 0.9), nose=(368, 106, 0.9)),
    "side_leaning_back": with_(SIDE_R, lsho=(285, 166, 0.9), lear=(298, 108, 0.9), nose=(332, 100, 0.9)),
    "back_retracted": with_(FRONT, nose=(320, 100, 0.1), lsho=(276, 160, 0.9), rsho=(364, 160, 0.9)),
}

out = {"tolerance": 1e-3, "cases": []}
for name, lms in CASES.items():
    m = compute_metrics(PersonPose(dict(lms)))
    out["cases"].append({
        "name": name,
        "landmarks": {k: {"x": x, "y": y, "conf": c} for k, (x, y, c) in lms.items()},
        "expected": {k: round(v, 6) for k, v in sorted(m.items())},
    })
(ROOT / "shared" / "metric_vectors.json").write_text(json.dumps(out, indent=1) + "\n")
print(f"wrote {len(out['cases'])} cases")
