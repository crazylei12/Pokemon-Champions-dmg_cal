#!/usr/bin/env python3
"""Run RapidOCR on one or more images and emit compact JSON.

The PC path intentionally uses RapidOCR + ONNX Runtime so the OCR behavior can
stay close to the Android RapidOcrAndroidOnnx path.
"""

from __future__ import annotations

import argparse
import contextlib
import json
import sys
from pathlib import Path
from typing import Any


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--image", action="append", required=True, help="Image path. Repeatable.")
    parser.add_argument("--output", help="Optional JSON output path.")
    parser.add_argument("--return-word-box", action="store_true")
    parser.add_argument("--text-score", type=float)
    args = parser.parse_args()

    try:
        # RapidOCR writes informational logs during model creation. Keep stdout
        # reserved for machine-readable JSON.
        with contextlib.redirect_stdout(sys.stderr):
            from rapidocr import RapidOCR

            engine = RapidOCR()

        results = []
        for image_path in args.image:
            with contextlib.redirect_stdout(sys.stderr):
                output = engine(
                    image_path,
                    return_word_box=args.return_word_box,
                    text_score=args.text_score,
                )
            results.append(serialize_output(image_path, output))

        payload = {
            "backend": "rapidocr",
            "images": results,
        }
        text = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
        if args.output:
            Path(args.output).parent.mkdir(parents=True, exist_ok=True)
            Path(args.output).write_text(text + "\n", encoding="utf-8")
        else:
            print(text)
        return 0
    except Exception as exc:  # pragma: no cover - command-line diagnostic
        print(f"rapidocr-json failed: {exc}", file=sys.stderr)
        return 1


def serialize_output(image_path: str, output: Any) -> dict[str, Any]:
    boxes = getattr(output, "boxes", None)
    txts = getattr(output, "txts", None)
    scores = getattr(output, "scores", None)
    lines = []
    word_results = getattr(output, "word_results", None)
    if boxes is not None and txts is not None and scores is not None:
        for index, (box, text, score) in enumerate(zip(boxes, txts, scores)):
            lines.append(
                {
                    "text": str(text),
                    "score": float(score),
                    "box": to_plain_box(box),
                    "words": to_plain_words(word_results[index] if word_results else None),
                }
            )

    return {
        "path": image_path,
        "lines": lines,
        "text": "\n".join(line["text"] for line in lines),
        "elapse": float(getattr(output, "elapse", 0) or 0),
    }


def to_plain_box(box: Any) -> list[list[float]]:
    return [[float(point[0]), float(point[1])] for point in box]


def to_plain_words(words: Any) -> list[dict[str, Any]]:
    if not words:
        return []
    return [
        {
            "text": str(word[0]),
            "score": float(word[1]),
            "box": to_plain_box(word[2]) if word[2] is not None else None,
        }
        for word in words
    ]


if __name__ == "__main__":
    raise SystemExit(main())
