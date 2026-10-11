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

# Head-and-shoulders close-up (neck programme): nothing below the shoulders.
NECK_FRONT = {
    "nose": (320, 150, 0.9), "lear": (270, 165, 0.9), "rear": (370, 165, 0.9),
    "lsho": (170, 330, 0.9), "rsho": (470, 330, 0.9),
}
NECK_SIDE_R = {  # close-up profile facing image-right
    "nose": (400, 150, 0.9), "lear": (320, 165, 0.9), "rear": (320, 165, 0.2),
    "lsho": (300, 330, 0.9), "rsho": (300, 330, 0.3),
}
# Waist-up framing (upper-body programme): hips out of view.
UPPER_FRONT = {
    "nose": (320, 90, 0.9), "lear": (295, 100, 0.9), "rear": (345, 100, 0.9),
    "lsho": (250, 170, 0.9), "rsho": (390, 170, 0.9),
    "lelb": (240, 280, 0.9), "relb": (400, 280, 0.9),
    "lwri": (238, 380, 0.8), "rwri": (402, 380, 0.8),
}
UPPER_SIDE_R = {
    "nose": (360, 95, 0.9), "lear": (322, 102, 0.9), "rear": (322, 102, 0.2),
    "lsho": (310, 180, 0.9), "rsho": (310, 180, 0.3),
    "lelb": (312, 290, 0.9), "relb": (312, 290, 0.3),
    "lwri": (380, 300, 0.8),
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
    "neck_front_neutral": NECK_FRONT,
    "neck_front_side_bend": with_(NECK_FRONT, lear=(275, 140, 0.9), rear=(360, 190, 0.9), nose=(330, 160, 0.9)),
    "neck_front_shrug": with_(NECK_FRONT, lsho=(170, 290, 0.9), rsho=(470, 290, 0.9)),
    "neck_side_neutral": NECK_SIDE_R,
    "neck_side_chin_tuck": with_(NECK_SIDE_R, lear=(300, 165, 0.9), nose=(380, 152, 0.9)),
    "neck_side_flexion": with_(NECK_SIDE_R, nose=(385, 215, 0.9)),
    "neck_side_facing_left": mirror(NECK_SIDE_R),
    "upper_front_arms_down": UPPER_FRONT,
    "upper_front_arms_raised": with_(UPPER_FRONT, lelb=(150, 220, 0.9), relb=(495, 210, 0.9)),
    "upper_side_neutral": UPPER_SIDE_R,
    "upper_side_retracted": with_(UPPER_SIDE_R, lelb=(285, 285, 0.9)),
    "upper_side_facing_left_retracted": mirror(with_(UPPER_SIDE_R, lelb=(285, 285, 0.9))),
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
