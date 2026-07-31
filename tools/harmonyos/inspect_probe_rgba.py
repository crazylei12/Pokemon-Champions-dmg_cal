#!/usr/bin/env python3
"""Validate and convert a raw AVScreenCapture RGBA frame produced by the stage-1 probe."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

import cv2
import numpy as np


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path, help="Raw frame written by the capability probe")
    parser.add_argument("--width", type=int, required=True)
    parser.add_argument("--height", type=int, required=True)
    parser.add_argument("--stride", type=int, required=True, help="Row stride in bytes reported by the probe")
    parser.add_argument("--output", type=Path, required=True, help="PNG evidence output")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.width <= 0 or args.height <= 0:
        raise ValueError("width and height must be positive")
    if args.stride < args.width * 4:
        raise ValueError(f"stride {args.stride} is smaller than visible RGBA row {args.width * 4}")

    raw = args.input.read_bytes()
    expected_bytes = args.stride * args.height
    if len(raw) != expected_bytes:
        raise ValueError(f"raw byte count {len(raw)} does not equal stride*height {expected_bytes}")

    rows = np.frombuffer(raw, dtype=np.uint8).reshape(args.height, args.stride)
    rgba = rows[:, : args.width * 4].reshape(args.height, args.width, 4)
    bgra = cv2.cvtColor(rgba, cv2.COLOR_RGBA2BGRA)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    if not cv2.imwrite(str(args.output), bgra):
        raise RuntimeError(f"failed to write {args.output}")

    rgb = rgba[:, :, :3]
    black_pixels = np.all(rgb <= 3, axis=2)
    report = {
        "input": str(args.input.resolve()),
        "output": str(args.output.resolve()),
        "width": args.width,
        "height": args.height,
        "strideBytes": args.stride,
        "rawBytes": len(raw),
        "rawSha256": hashlib.sha256(raw).hexdigest().upper(),
        "visibleRgbaSha256": hashlib.sha256(rgba.tobytes()).hexdigest().upper(),
        "blackPixelRatio": float(np.mean(black_pixels)),
        "alphaMin": int(np.min(rgba[:, :, 3])),
        "alphaMax": int(np.max(rgba[:, :, 3])),
    }
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
