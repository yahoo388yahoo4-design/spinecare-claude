"""Unit tests for pose.py: metrics, framing, rep counting, calibration."""
import json
import math
from pathlib import Path

import pytest

from pose import ExerciseTracker, PersonPose, RepEngine, compute_metrics, framing_check

SHARED = Path(__file__).resolve().parent.parent / "shared"
EXERCISES = {e["id"]: e for e in json.loads((SHARED / "exercises.json").read_text())["exercises"]}
VECTORS = json.loads((SHARED / "metric_vectors.json").read_text())

FRONT = {
    "nose": (320, 100, 0.9), "lear": (300, 110, 0.9), "rear": (340, 110, 0.9),
    "lsho": (270, 160, 0.9), "rsho": (370, 160, 0.9),
    "lhip": (285, 300, 0.9), "rhip": (355, 300, 0.9),
    "lknee": (283, 380, 0.9), "rknee": (357, 380, 0.9),
}


def mk(**overrides):
    d = dict(FRONT)
    d.update(overrides)
    return PersonPose(d)


@pytest.mark.parametrize("case", VECTORS["cases"], ids=lambda c: c["name"])
def test_shared_vectors(case):
    lms = {k: (v["x"], v["y"], v["conf"]) for k, v in case["landmarks"].items()}
    got = compute_metrics(PersonPose(lms))
    assert set(got) == set(case["expected"])
    for k, v in case["expected"].items():
        assert got[k] == pytest.approx(v, abs=VECTORS["tolerance"]), k


def test_upright_trunk_zero_tilt():
    m = compute_metrics(mk())
    assert abs(m["trunk_tilt_frontal"]) < 3
    assert abs(m["shoulder_line_tilt"]) < 1


def test_leaning_trunk_detected():
    m = compute_metrics(mk(lhip=(345, 300, 0.9), rhip=(415, 300, 0.9)))
    assert m["trunk_tilt_frontal"] > 10


def test_neck_yaw_signed():
    assert abs(compute_metrics(mk())["neck_yaw_est"]) < 15
    assert compute_metrics(mk(nose=(336, 100, 0.9)))["neck_yaw_est"] > 25
    assert compute_metrics(mk(nose=(304, 100, 0.9)))["neck_yaw_est"] < -25


def test_neck_yaw_survives_ear_occlusion():
    m = compute_metrics(mk(nose=(336, 100, 0.9), lear=(300, 110, 0.1), rear=(322, 110, 0.1)))
    assert m["neck_yaw_est"] > 25


def test_side_metrics_use_visible_side_and_facing():
    """Regression: the old head-forward proxy averaged both ears, so an occluded
    far ear (reported at a bogus position) corrupted it, and facing left flipped its sign."""
    right = {"nose": (352, 102, 0.9), "lear": (318, 108, 0.9), "rear": (0, 0, 0.0),
             "lsho": (305, 165, 0.9), "lhip": (300, 305, 0.9)}
    # same person facing image-left: right-side chain visible, mirrored x
    left = {"nose": (288, 102, 0.9), "rear": (322, 108, 0.9), "lear": (0, 0, 0.0),
            "rsho": (335, 165, 0.9), "rhip": (340, 305, 0.9)}
    mr, ml = compute_metrics(PersonPose(right)), compute_metrics(PersonPose(left))
    assert mr["head_forward_proxy"] > 0
    assert mr["head_forward_proxy"] == pytest.approx(ml["head_forward_proxy"])
    assert mr["trunk_lean_sagittal"] == pytest.approx(ml["trunk_lean_sagittal"])


def test_framing_all_visible():
    f = framing_check(mk(), ["lear", "lsho", "lhip"], 0.5, 640, 480)
    assert f["ready"] and not f["missing"]


def test_framing_any_side():
    p = mk(rear=(340, 110, 0.1))
    assert framing_check(p, ["any_ear"], 0.5, 640, 480)["ready"]
    p = mk(lear=(300, 110, 0.1), rear=(340, 110, 0.1))
    assert not framing_check(p, ["any_ear"], 0.5, 640, 480)["ready"]


def test_framing_low_conf_and_out_of_frame():
    f = framing_check(mk(lear=(300, 110, 0.2)), ["lear", "lsho"], 0.5, 640, 480)
    assert not f["ready"] and "lear" in f["missing"]
    f = framing_check(mk(lhip=(5, 300, 0.9)), ["lhip"], 0.5, 640, 480)
    assert not f["ready"] and f["issues"] == ["lhip:out_of_frame"]


def test_framing_no_person():
    f = framing_check(None, ["lsho"], 0.5, 640, 480)
    assert not f["ready"] and f["issues"] == ["no_person"]


def test_rep_engine_counts_cycles():
    r = RepEngine(8, 35, min_hold_s=0.0)
    t, done = 0.0, 0
    for _ in range(3):
        for v in (5, 20, 40, 45, 40, 20, 5):
            done += r.update(v, t)["rep_just_done"]
            t += 0.1
    assert r.reps == 3 and done == 3


def test_rep_engine_hysteresis_no_double_count():
    r = RepEngine(8, 35, min_hold_s=0.0)
    for i, v in enumerate((5, 40, 36, 34, 36, 40, 5)):
        r.update(v, i * 0.1)
    assert r.reps == 1


def test_rep_engine_min_hold_rejects_flicks():
    r = RepEngine(8, 35, min_hold_s=0.4)
    r.update(40, 0.0)
    r.update(5, 0.1)
    assert r.reps == 0


def test_rep_engine_ignores_none_and_nan():
    r = RepEngine(0, 10)
    assert r.update(None, 1.0)["rep_just_done"] is False
    assert r.update(math.nan, 1.0)["rep_just_done"] is False


def _run(tracker, frames, dt=0.1):
    t, out = 0.0, None
    for m in frames:
        out = tracker.update(m, True, t)
        t += dt
    return out


def test_tracker_calibrates_then_counts_relative_to_baseline():
    ex = EXERCISES["cervical_rotation_rom"]
    tr = ExerciseTracker(ex, calibration_s=0.5)
    # resting head already turned 10 degrees: must not count as movement
    base = [{"neck_yaw_est": 10.0, "shoulder_line_tilt": 5.0}] * 8
    out = _run(tr, base)
    assert not out["calibrating"]
    assert tr.baseline["neck_yaw_est"] == 10.0
    cycle = [12, 30, 50, 52, 50, 30, 12]
    t = 1.0
    for _ in range(2):
        for v in cycle:
            out = tr.update({"neck_yaw_est": float(v), "shoulder_line_tilt": 5.0}, True, t)
            t += 0.15
    assert out["reps"] == 2
    # resting shoulder asymmetry (scoliosis) is not a form fault once calibrated
    assert out["cues"] == []


def test_tracker_direction_for_chin_tuck():
    ex = EXERCISES["chin_tuck"]
    tr = ExerciseTracker(ex, calibration_s=0.3)
    _run(tr, [{"head_forward_proxy": 0.20}] * 5)
    t = 1.0
    for v in (0.19, 0.12, 0.10, 0.10, 0.10, 0.10, 0.15, 0.20):  # tuck back then release
        out = tr.update({"head_forward_proxy": v}, True, t)
        t += 0.2
    assert out["reps"] == 1


def test_tracker_waits_for_framing():
    tr = ExerciseTracker(EXERCISES["chin_tuck"], calibration_s=0.3)
    for i in range(10):
        out = tr.update({"head_forward_proxy": 0.2}, False, i * 0.1)
    assert out["calibrating"]


def test_tracker_localized_cues():
    tr = ExerciseTracker(EXERCISES["cervical_rotation_rom"], calibration_s=0.2, lang="ro")
    _run(tr, [{"neck_yaw_est": 0.0, "shoulder_line_tilt": 0.0}] * 4)
    out = tr.update({"neck_yaw_est": 0.0, "shoulder_line_tilt": 20.0}, True, 5.0)
    assert out["cues"] and "umărul" in out["cues"][0]["cue"]


def test_exercise_plan_is_consistent():
    for ex in EXERCISES.values():
        assert ex["rep_band"]["low"] < ex["rep_band"]["high"]
        assert set(ex["name"]) == {"en", "ro"}
        for rule in ex["form_rules"]:
            assert set(rule["cue"]) == {"en", "ro"}
