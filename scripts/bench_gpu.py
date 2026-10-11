"""Benchmark: run YOLOv8-pose on GPU with synthetic + real frames, measure latency."""
import time, json, sys
import numpy as np
import torch
from ultralytics import YOLO

def bench(model, img, n=60):
    lat = []
    for _ in range(n):
        t0 = time.perf_counter()
        r = model.predict(img, verbose=False, device=0)
        torch.cuda.synchronize()
        lat.append((time.perf_counter() - t0) * 1000)
    lat.sort()
    return {"n": n, "p50": round(lat[len(lat)//2], 1), "p90": round(lat[int(len(lat)*0.9)], 1),
            "mean": round(sum(lat)/len(lat), 1)}

def main():
    model = YOLO("yolov8n-pose.pt")
    model.to("cuda")
    print("device:", next(model.parameters()).device)
    # warmup
    for _ in range(10):
        model.predict(np.zeros((480, 640, 3), dtype=np.uint8), verbose=False, device=0)
    print("synthetic 480x640:", json.dumps(bench(model, np.zeros((480, 640, 3), dtype=np.uint8), 40)))
    print("synthetic 720p   :", json.dumps(bench(model, np.zeros((720, 1280, 3), dtype=np.uint8), 40)))
    # real frames if provided
    import cv2, glob
    frames = sorted(glob.glob("/root/workspace/spinecare/testdata/*.jpg"))[:30]
    if frames:
        imgs = [cv2.imread(f) for f in frames]
        print("real frames    :", json.dumps(bench(model, imgs, len(imgs))))
    else:
        print("real frames    : none provided")

if __name__ == "__main__":
    main()
