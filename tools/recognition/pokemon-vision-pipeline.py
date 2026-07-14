#!/usr/bin/env python3
"""Evaluable Pokemon icon recognition pipeline.

The pipeline is dataset-driven:
- references/ contains template images named with a Pokemon id or name.
- dataset/real_train contains real screenshots.
- dataset/labels.csv contains image, side, pokemon.
- dataset/real_test contains final-test screenshots for prediction export.
"""

from __future__ import annotations

import argparse
import copy
import csv
import hashlib
import json
import math
import pickle
import random
import re
import shutil
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable

try:
    import cv2
    import numpy as np
except ImportError as exc:
    print(
        "OpenCV recognition dependencies are missing. Install them with:\n"
        "  python -m pip install -r requirements-recognition.txt",
        file=sys.stderr,
    )
    raise SystemExit(2) from exc


DEFAULT_ROI_CONFIG = Path("src/data/recognition/roi.pokemon-champions.zh-Hans.v1.json")
DEFAULT_TEAM_PREVIEW_ROI = Path("src/data/recognition/team-preview.safe-zone-roi.zh-Hans.v1.json")
DEFAULT_REFERENCES_DIR = Path("references")
DEFAULT_DATASET_DIR = Path("dataset")
DEFAULT_OUTPUT_DIR = Path(".tmp/pokemon-vision-eval")
DEFAULT_TEMPLATE_CACHE_DIR = Path("src/data/recognition/template-cache")
IMAGE_EXTENSIONS = {".png", ".jpg", ".jpeg", ".webp", ".bmp"}
FEATURE_SIZE = 96
TEMPLATE_CACHE_VERSION = 3
TEAM_PREVIEW_SCENE = "TEAM_PREVIEW"
PROJECT_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_SIDES = [
    "own.slot0",
    "own.slot1",
    "own.slot2",
    "own.slot3",
    "own.slot4",
    "own.slot5",
    "opponent.slot0",
    "opponent.slot1",
    "opponent.slot2",
    "opponent.slot3",
    "opponent.slot4",
    "opponent.slot5",
]
DEFAULT_WEIGHTS = {
    "phash": 0.22,
    "edge": 0.24,
    "color": 0.24,
    "template": 0.30,
}
DEFAULT_SIDE_WEIGHT_OVERRIDES = ["opponent:phash=0,edge=0.4,color=0.2,template=0.4"]
SCORE_METHODS = ["combined", "phash", "edge", "color", "template"]
FOREGROUND_DISTANCE_THRESHOLD = 52.0
HSV_FOREGROUND_DISTANCE_THRESHOLD = 32.0
RELAXED_FOREGROUND_DISTANCE_THRESHOLD = 42.0
RELAXED_HSV_FOREGROUND_DISTANCE_THRESHOLD = 26.0
RELAXED_COLOR_MASK_MIN_PIXEL_GAIN = 1.08
RELAXED_COLOR_MASK_MIN_STRICT_OVERLAP = 0.82
RELAXED_COLOR_MASK_QUALITY_MARGIN = 0.15
GRABCUT_EXPANSION_MIN_EDGE_DENSITY = 0.12
GRABCUT_EXPANSION_MIN_QUALITY_GAIN = 0.02
GRABCUT_SUBSET_QUALITY_MARGIN = 0.10
GRABCUT_SUBSET_AXIS_SHRINK_RATIO = 0.88
UI_FRAME_BONUS_ARTIFACT_RATIO = 0.015
UI_FRAME_BONUS_FOREGROUND_RATIO = 0.48


@dataclass
class RoiRegion:
    id: str
    side_key: str
    rect: dict[str, float]


@dataclass
class ReferenceImage:
    pokemon_id: str
    aliases: set[str]
    path: Path
    source_path: Path
    variant: str


@dataclass
class TemplateFeature:
    pokemon_id: str
    aliases: set[str]
    reference_path: Path
    source_path: Path
    variant: str
    feature: dict[str, Any]
    sample_image: str | None = None
    side_key: str | None = None


@dataclass
class LabelRow:
    row_index: int
    image: str
    side: str
    pokemon: str


class TimingRecorder:
    def __init__(self) -> None:
        self.started_at = time.perf_counter()
        self.steps: list[dict[str, Any]] = []

    def add(self, step: str, seconds: float, **details: Any) -> None:
        entry = {
            "step": step,
            "seconds": round_float(seconds),
            "elapsed_seconds": round_float(time.perf_counter() - self.started_at),
        }
        for key, value in details.items():
            if isinstance(value, dict):
                entry[key] = {
                    nested_key: round_float(nested_value) if isinstance(nested_value, float) else nested_value
                    for nested_key, nested_value in value.items()
                }
            elif isinstance(value, float):
                entry[key] = round_float(value)
            else:
                entry[key] = value
        self.steps.append(entry)

    def report(self, command: str, args: argparse.Namespace, labels_count: int, failed_count: int) -> dict[str, Any]:
        return {
            "command": command,
            "output": str(args.output),
            "labels": str(args.labels),
            "images_dir": str(args.images_dir),
            "crop_mode": args.crop_mode,
            "use_labeled_roi_templates": args.use_labeled_roi_templates,
            "labeled_template_labels": str(args.labeled_template_labels or ""),
            "labeled_template_images_dir": str(args.labeled_template_images_dir or ""),
            "labeled_template_scope": args.labeled_template_scope,
            "skip_failed_debug": args.skip_failed_debug,
            "fast_detect": getattr(args, "fast_detect", False),
            "template_cache_dir": str(args.template_cache_dir),
            "team_preview_roi": str(args.team_preview_roi),
            "template_cache_enabled": not args.no_template_cache,
            "refresh_template_cache": args.refresh_template_cache,
            "skip_debug_roi_crops": args.skip_debug_roi_crops,
            "skip_contact_sheet": args.skip_contact_sheet,
            "skip_roi_quality": args.skip_roi_quality,
            "skip_roi_overlays": args.skip_roi_overlays,
            "skip_roi_debug_outputs": args.skip_roi_debug_outputs,
            "labels_count": labels_count,
            "failed_count": failed_count,
            "total_seconds": round_float(time.perf_counter() - self.started_at),
            "steps": self.steps,
        }


def main() -> int:
    parser = argparse.ArgumentParser(description="Dataset-driven Pokemon image recognition pipeline.")
    subparsers = parser.add_subparsers(dest="command", required=True)

    evaluate = subparsers.add_parser("evaluate", help="Evaluate labels.csv and write metrics.")
    add_common_args(evaluate)
    evaluate.add_argument("--labels", type=Path, default=DEFAULT_DATASET_DIR / "labels.csv")
    evaluate.add_argument("--images-dir", type=Path, default=DEFAULT_DATASET_DIR / "real_train")
    evaluate.add_argument("--fail-on-miss", action="store_true")
    evaluate.add_argument("--skip-failed-debug", action="store_true")
    evaluate.add_argument(
        "--fast-detect",
        dest="fast_detect",
        action="store_true",
        default=True,
        help="Only score combined TopK and skip ROI debug outputs. This is the project default.",
    )
    evaluate.add_argument(
        "--full-scoring",
        dest="fast_detect",
        action="store_false",
        help="Score combined/phash/edge/color/template and allow ROI debug outputs for diagnostics.",
    )
    evaluate.add_argument("--timing-output", type=Path, help="Write per-step timing JSON for evaluate.")

    tune = subparsers.add_parser("tune-weights", help="Grid-search combined-score weights on labels.csv.")
    add_common_args(tune)
    tune.add_argument("--labels", type=Path, default=DEFAULT_DATASET_DIR / "labels.csv")
    tune.add_argument("--images-dir", type=Path, default=DEFAULT_DATASET_DIR / "real_train")
    tune.add_argument("--grid-step", type=float, default=0.1)
    tune.add_argument("--min-weight", type=float, default=0.0)

    cross_validate = subparsers.add_parser(
        "cross-validate",
        help="Group-by-image cross validation for weight tuning and held-out evaluation.",
    )
    add_common_args(cross_validate)
    cross_validate.add_argument("--labels", type=Path, default=DEFAULT_DATASET_DIR / "labels.csv")
    cross_validate.add_argument("--images-dir", type=Path, default=DEFAULT_DATASET_DIR / "real_train")
    cross_validate.add_argument("--folds", type=int, default=5)
    cross_validate.add_argument("--grid-step", type=float, default=0.1)
    cross_validate.add_argument("--min-weight", type=float, default=0.0)

    calibrate = subparsers.add_parser(
        "calibrate-roi",
        help="Search generic per-slot ROI perturbations on labels.csv and export a calibrated ROI config.",
    )
    add_common_args(calibrate)
    calibrate.add_argument("--labels", type=Path, default=DEFAULT_DATASET_DIR / "labels.csv")
    calibrate.add_argument("--images-dir", type=Path, default=DEFAULT_DATASET_DIR / "real_train")
    calibrate.add_argument(
        "--shift-steps",
        default="-0.08,0,0.08",
        help="Comma-separated x/y shifts as fractions of the current ROI width/height.",
    )
    calibrate.add_argument(
        "--padding-steps",
        default="-0.06,0,0.08",
        help="Comma-separated padding values as fractions of the current ROI width/height.",
    )
    calibrate.add_argument("--side", action="append", help="Only calibrate selected side/slot. Repeatable.")

    predict = subparsers.add_parser("predict", help="Predict Top5 candidates for real_test screenshots.")
    add_common_args(predict)
    predict.add_argument("--images-dir", type=Path, default=DEFAULT_DATASET_DIR / "real_test")
    predict.add_argument("--side", action="append", help="Side/slot to crop. Repeatable. Defaults to all 12 slots.")

    augment = subparsers.add_parser("augment", help="Generate augmented references only.")
    augment.add_argument("--references", type=Path, default=DEFAULT_REFERENCES_DIR)
    augment.add_argument("--output", type=Path, default=DEFAULT_OUTPUT_DIR / "augmented_references")
    augment.add_argument("--augment-count", type=int, default=8)
    augment.add_argument("--seed", type=int, default=12345)
    augment.add_argument("--clear-output", action="store_true")

    args = parser.parse_args()
    if args.command == "evaluate":
        return run_evaluate(args)
    if args.command == "tune-weights":
        return run_tune_weights(args)
    if args.command == "cross-validate":
        return run_cross_validate(args)
    if args.command == "calibrate-roi":
        return run_calibrate_roi(args)
    if args.command == "predict":
        return run_predict(args)
    if args.command == "augment":
        references = load_reference_images(args.references)
        write_augmented_references(
            references,
            args.output,
            augment_count=args.augment_count,
            seed=args.seed,
            clear_output=args.clear_output,
        )
        print(f"Generated augmented references under {args.output}")
        return 0
    raise AssertionError(args.command)


def add_common_args(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--references", type=Path, default=DEFAULT_REFERENCES_DIR)
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET_DIR)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT_DIR)
    parser.add_argument("--roi-config", type=Path, default=DEFAULT_ROI_CONFIG)
    parser.add_argument("--team-preview-roi", type=Path, default=DEFAULT_TEAM_PREVIEW_ROI)
    parser.add_argument("--scene", default="TEAM_PREVIEW")
    parser.add_argument(
        "--crop-mode",
        choices=["team-preview-safe-zone"],
        default="team-preview-safe-zone",
        help="Pokemon icon crops use the project SafeZone ROI resource.",
    )
    parser.add_argument("--top-k", type=int, default=5)
    parser.add_argument("--augment-count", type=int, default=1)
    parser.add_argument("--seed", type=int, default=12345)
    parser.add_argument("--no-augment", action="store_true")
    parser.add_argument("--clear-output", action="store_true")
    parser.add_argument("--contact-sheet-limit", type=int, default=240)
    parser.add_argument("--template-cache-dir", type=Path, default=DEFAULT_TEMPLATE_CACHE_DIR)
    parser.add_argument("--no-template-cache", action="store_true")
    parser.add_argument("--refresh-template-cache", action="store_true")
    parser.add_argument("--skip-debug-roi-crops", action="store_true")
    parser.add_argument("--skip-contact-sheet", action="store_true")
    parser.add_argument("--skip-roi-quality", action="store_true")
    parser.add_argument("--skip-roi-overlays", action="store_true")
    parser.add_argument("--skip-roi-debug-outputs", action="store_true")
    parser.add_argument(
        "--use-labeled-roi-templates",
        dest="use_labeled_roi_templates",
        action="store_true",
        default=True,
        help="Add ROI crops from labeled real_train screenshots as screenshot-style templates.",
    )
    parser.add_argument(
        "--no-labeled-roi-templates",
        dest="use_labeled_roi_templates",
        action="store_false",
        help="Disable project default labeled ROI screenshot templates.",
    )
    parser.add_argument(
        "--labeled-template-labels",
        type=Path,
        default=DEFAULT_DATASET_DIR / "labels.csv",
        help="labels.csv used for --use-labeled-roi-templates. Defaults to the command labels when available.",
    )
    parser.add_argument(
        "--labeled-template-images-dir",
        type=Path,
        default=DEFAULT_DATASET_DIR / "real_train",
        help="Image directory used for --use-labeled-roi-templates. Defaults to the command images-dir when available.",
    )
    parser.add_argument(
        "--keep-self-labeled-templates",
        action="store_true",
        help="Do not exclude labeled templates cropped from the same image during evaluation diagnostics.",
    )
    parser.add_argument(
        "--labeled-template-scope",
        choices=["all", "same-side", "same-slot"],
        default="same-side",
        help="Limit labeled ROI templates by query side/slot. Catalog references are always kept.",
    )
    parser.add_argument(
        "--labeled-template-bonus",
        type=float,
        default=0.04,
        help="Add this score prior to labeled ROI templates for combined ranking only.",
    )
    parser.add_argument(
        "--weights",
        default=",".join(f"{key}={value}" for key, value in DEFAULT_WEIGHTS.items()),
        help="Combined score weights, e.g. phash=0.22,edge=0.24,color=0.24,template=0.30",
    )
    parser.add_argument(
        "--side-weights",
        action="append",
        default=None,
        help=(
            "Override combined score weights for a side or slot, e.g. "
            "opponent:phash=0,edge=0.4,color=0.2,template=0.4. Repeatable."
        ),
    )


def run_evaluate(args: argparse.Namespace) -> int:
    timing = TimingRecorder() if args.timing_output else None
    step_start = time.perf_counter()
    prepare_output_dir(args.output, args.clear_output)
    if timing:
        timing.add("prepare_output_dir", time.perf_counter() - step_start, clear_output=args.clear_output)
    step_start = time.perf_counter()
    labels = read_labels(args.labels)
    if timing:
        timing.add("read_labels", time.perf_counter() - step_start, label_count=len(labels))
    step_start = time.perf_counter()
    roi_regions = load_roi_regions(args.roi_config, args.scene, args.team_preview_roi)
    if timing:
        timing.add("load_roi_regions", time.perf_counter() - step_start, roi_count=len(roi_regions))
    step_start = time.perf_counter()
    templates = build_templates(args)
    if timing:
        timing.add("build_catalog_templates", time.perf_counter() - step_start, template_count=len(templates))
    step_start = time.perf_counter()
    templates.extend(
        build_labeled_roi_templates_if_requested(args, roi_regions, labels, args.images_dir, args.labels.parent)
    )
    if timing:
        timing.add("build_labeled_roi_templates", time.perf_counter() - step_start, template_count=len(templates))
    step_start = time.perf_counter()
    weights = parse_weights(args.weights)
    side_weights = parse_side_weights(args.side_weights)
    if timing:
        timing.add("parse_weights", time.perf_counter() - step_start, side_weight_count=len(side_weights))
    score_methods = active_score_methods(args)
    debug_dir = args.output / "debug_roi"
    predictions = []
    failed_cases = []
    debug_entries = []
    method_counters = {
        method: {"correct_top1": 0, "correct_top3": 0, "correct_top5": 0}
        for method in score_methods
    }
    correct_top1 = 0
    correct_top3 = 0
    correct_top5 = 0
    loop_breakdown = {
        "resolve_image": 0.0,
        "crop_roi": 0.0,
        "write_debug_roi": 0.0,
        "create_feature": 0.0,
        "rank_candidates": 0.0,
        "score_prediction": 0.0,
    }
    step_start = time.perf_counter()

    for row in labels:
        part_start = time.perf_counter()
        image_path = resolve_image_path(row.image, args.images_dir, args.labels.parent)
        if not image_path.exists():
            raise FileNotFoundError(f"Label row {row.row_index} image not found: {row.image}")
        side_key = normalize_side_key(row.side)
        if side_key not in roi_regions:
            raise ValueError(
                f"Label row {row.row_index} side '{row.side}' is not a known ROI. "
                f"Use values such as own.slot0, opponent.slot3, or a full ROI id."
            )
        loop_breakdown["resolve_image"] += time.perf_counter() - part_start
        part_start = time.perf_counter()
        crop_info = crop_roi_with_metadata(
            image_path,
            roi_regions[side_key],
            args.roi_config,
            scene_name=args.scene,
            crop_mode=args.crop_mode,
        )
        roi = crop_info["roi"]
        loop_breakdown["crop_roi"] += time.perf_counter() - part_start
        part_start = time.perf_counter()
        if skip_debug_roi_crops(args):
            pass
        else:
            write_debug_roi(debug_dir, image_path, side_key, roi)
        loop_breakdown["write_debug_roi"] += time.perf_counter() - part_start
        debug_entries.append(crop_info)
        part_start = time.perf_counter()
        roi_feature = create_feature(roi)
        loop_breakdown["create_feature"] += time.perf_counter() - part_start
        excluded_sample_image = (
            normalized_path_text(image_path)
            if args.use_labeled_roi_templates and not args.keep_self_labeled_templates
            else None
        )
        query_weights = weights_for_side(side_key, weights, side_weights)
        part_start = time.perf_counter()
        method_candidates = rank_candidates_by_methods_from_feature(
            roi_feature,
            templates,
            query_weights,
            args.top_k,
            score_methods,
            excluded_sample_image=excluded_sample_image,
            query_side_key=side_key,
            labeled_template_scope=args.labeled_template_scope,
            labeled_template_bonus=args.labeled_template_bonus,
        )
        loop_breakdown["rank_candidates"] += time.perf_counter() - part_start
        part_start = time.perf_counter()
        candidates = method_candidates["combined"]
        true_key = normalize_pokemon_key(row.pokemon)
        for method, ranked in method_candidates.items():
            ranks = candidate_match_ranks(ranked, true_key)
            method_counters[method]["correct_top1"] += int(bool(ranks and ranks[0] <= 1))
            method_counters[method]["correct_top3"] += int(bool(ranks and ranks[0] <= 3))
            method_counters[method]["correct_top5"] += int(bool(ranks and ranks[0] <= 5))
        match_ranks = [
            index + 1
            for index, candidate in enumerate(candidates)
            if true_key in candidate["aliases"]
        ]
        top1 = bool(match_ranks and match_ranks[0] <= 1)
        top3 = bool(match_ranks and match_ranks[0] <= 3)
        top5 = bool(match_ranks and match_ranks[0] <= 5)
        correct_top1 += int(top1)
        correct_top3 += int(top3)
        correct_top5 += int(top5)
        prediction = {
            "image": row.image,
            "resolved_image": str(image_path),
            "side": row.side,
            "side_key": side_key,
            "pokemon": row.pokemon,
            "top1": top1,
            "top3": top3,
            "top5": top5,
            "top5_candidates": candidates,
        }
        predictions.append(prediction)
        if not top1:
            failed_cases.append(prediction)
        loop_breakdown["score_prediction"] += time.perf_counter() - part_start

    if timing:
        timing.add(
            "evaluate_rows",
            time.perf_counter() - step_start,
            label_count=len(labels),
            image_count=len({row.image for row in labels}),
            breakdown_seconds=loop_breakdown,
        )

    total = len(labels)
    metrics = {
        "total": total,
        "top1_accuracy": safe_ratio(correct_top1, total),
        "top3_accuracy": safe_ratio(correct_top3, total),
        "top5_accuracy": safe_ratio(correct_top5, total),
        "correct_top1": correct_top1,
        "correct_top3": correct_top3,
        "correct_top5": correct_top5,
        "weights": weights,
        "side_weights": side_weights,
        "labeled_template_bonus": args.labeled_template_bonus,
        "crop_mode": args.crop_mode,
        "score_methods": score_methods,
        "fast_detect": args.fast_detect,
        "templates": summarize_templates(templates),
    }
    side_metrics = build_side_metrics(predictions)
    method_metrics = build_method_metrics(method_counters, total, weights, side_weights, summarize_templates(templates))
    step_start = time.perf_counter()
    write_json(args.output / "metrics.json", metrics)
    write_json(args.output / "side_metrics.json", side_metrics)
    write_side_metrics_csv(args.output / "side_metrics.csv", side_metrics)
    write_json(args.output / "method_metrics.json", method_metrics)
    write_method_metrics_csv(args.output / "method_metrics.csv", method_metrics)
    write_predictions_csv(args.output / "predictions.csv", predictions)
    write_failed_cases_csv(args.output / "failed_cases.csv", failed_cases)
    if timing:
        timing.add("write_metric_files", time.perf_counter() - step_start)
    step_start = time.perf_counter()
    if args.skip_failed_debug or args.fast_detect:
        if timing:
            timing.add("write_failed_debug_outputs", 0.0, skipped=True, failed_count=len(failed_cases))
    else:
        write_failed_case_debug_outputs(
            args.output / "failed_debug",
            failed_cases,
            debug_entries,
            roi_regions,
            args.roi_config,
            args.scene,
            args.crop_mode,
        )
        if timing:
            timing.add("write_failed_debug_outputs", time.perf_counter() - step_start, failed_count=len(failed_cases))
    write_roi_debug_outputs(
        args.output,
        debug_entries,
        args.contact_sheet_limit,
        timing,
        skip_contact_sheet=skip_contact_sheet(args),
        skip_quality=skip_roi_quality(args),
        skip_overlays=skip_roi_overlays(args),
    )
    if timing:
        write_json(args.timing_output, timing.report(args.command, args, total, len(failed_cases)))
    print(
        "Evaluation complete: "
        f"Top1={metrics['top1_accuracy']:.4f}, "
        f"Top3={metrics['top3_accuracy']:.4f}, "
        f"Top5={metrics['top5_accuracy']:.4f}; "
        f"failed={len(failed_cases)}/{total}"
    )
    return 1 if args.fail_on_miss and failed_cases else 0


def run_tune_weights(args: argparse.Namespace) -> int:
    prepare_output_dir(args.output, args.clear_output)
    labels = read_labels(args.labels)
    roi_regions = load_roi_regions(args.roi_config, args.scene, args.team_preview_roi)
    templates = build_templates(args)
    templates.extend(build_labeled_roi_templates_if_requested(args, roi_regions, labels, args.images_dir, args.labels.parent))
    debug_dir = args.output / "debug_roi"
    cases, debug_entries = build_scored_cases(
        labels,
        args.images_dir,
        args.labels.parent,
        roi_regions,
        args.roi_config,
        templates,
        debug_dir,
        exclude_self_labeled_templates=args.use_labeled_roi_templates and not args.keep_self_labeled_templates,
        labeled_template_scope=args.labeled_template_scope,
        scene_name=args.scene,
        crop_mode=args.crop_mode,
    )
    weight_grid = generate_weight_grid(args.grid_step, args.min_weight)
    results = []
    best = None
    for weights in weight_grid:
        result = evaluate_weight_candidate(cases, weights, args.top_k)
        results.append(result)
        if best is None or weight_result_sort_key(result) > weight_result_sort_key(best):
            best = result

    if best is None:
        raise RuntimeError("No weight candidates were generated.")
    report = {
        "best": best,
        "grid_step": args.grid_step,
        "min_weight": args.min_weight,
        "candidates": results,
        "templates": summarize_templates(templates),
        "labels": {
            "path": str(args.labels),
            "count": len(labels),
        },
    }
    write_json(args.output / "weight_search.json", report)
    write_weight_search_csv(args.output / "weight_search.csv", results)
    write_json(args.output / "best_weights.json", best)
    write_roi_debug_outputs(args.output, debug_entries, args.contact_sheet_limit)
    weights_text = ",".join(f"{key}={best['weights'][key]:.3f}" for key in DEFAULT_WEIGHTS)
    print(
        "Weight tuning complete: "
        f"best Top1={best['top1_accuracy']:.4f}, "
        f"Top3={best['top3_accuracy']:.4f}, "
        f"Top5={best['top5_accuracy']:.4f}; "
        f"weights={weights_text}"
    )
    return 0


def run_cross_validate(args: argparse.Namespace) -> int:
    prepare_output_dir(args.output, args.clear_output)
    labels = read_labels(args.labels)
    roi_regions = load_roi_regions(args.roi_config, args.scene, args.team_preview_roi)
    templates = build_templates(args)
    templates.extend(build_labeled_roi_templates_if_requested(args, roi_regions, labels, args.images_dir, args.labels.parent))
    debug_dir = args.output / "debug_roi"
    cases, debug_entries = build_scored_cases(
        labels,
        args.images_dir,
        args.labels.parent,
        roi_regions,
        args.roi_config,
        templates,
        debug_dir,
        exclude_self_labeled_templates=args.use_labeled_roi_templates and not args.keep_self_labeled_templates,
        labeled_template_scope=args.labeled_template_scope,
        scene_name=args.scene,
        crop_mode=args.crop_mode,
    )
    folds = make_group_folds(cases, args.folds, args.seed)
    weight_grid = generate_weight_grid(args.grid_step, args.min_weight)
    fold_results = []
    aggregate = {"correct_top1": 0, "correct_top3": 0, "correct_top5": 0, "total": 0}
    for fold_index, validation_groups in enumerate(folds, start=1):
        train_cases = [case for case in cases if case["group_key"] not in validation_groups]
        validation_cases = [case for case in cases if case["group_key"] in validation_groups]
        if not train_cases or not validation_cases:
            continue
        train_results = [evaluate_weight_candidate(train_cases, weights, args.top_k) for weights in weight_grid]
        best_train = max(train_results, key=weight_result_sort_key)
        validation_result = evaluate_weight_candidate(validation_cases, best_train["weights"], args.top_k)
        fold_result = {
            "fold": fold_index,
            "validation_groups": sorted(validation_groups),
            "train_total": len(train_cases),
            "validation_total": len(validation_cases),
            "best_train": best_train,
            "validation": validation_result,
        }
        fold_results.append(fold_result)
        aggregate["correct_top1"] += validation_result["correct_top1"]
        aggregate["correct_top3"] += validation_result["correct_top3"]
        aggregate["correct_top5"] += validation_result["correct_top5"]
        aggregate["total"] += validation_result["total"]

    if not fold_results:
        raise RuntimeError("No valid cross-validation folds were generated.")
    summary = {
        "fold_count": len(fold_results),
        "group_count": len({case["group_key"] for case in cases}),
        "total": aggregate["total"],
        "top1_accuracy": safe_ratio(aggregate["correct_top1"], aggregate["total"]),
        "top3_accuracy": safe_ratio(aggregate["correct_top3"], aggregate["total"]),
        "top5_accuracy": safe_ratio(aggregate["correct_top5"], aggregate["total"]),
        "correct_top1": aggregate["correct_top1"],
        "correct_top3": aggregate["correct_top3"],
        "correct_top5": aggregate["correct_top5"],
    }
    report = {
        "summary": summary,
        "folds": fold_results,
        "grid_step": args.grid_step,
        "min_weight": args.min_weight,
        "templates": summarize_templates(templates),
        "labels": {
            "path": str(args.labels),
            "count": len(labels),
        },
    }
    write_json(args.output / "cross_validation.json", report)
    write_cross_validation_csv(args.output / "cross_validation.csv", fold_results)
    write_roi_debug_outputs(args.output, debug_entries, args.contact_sheet_limit)
    print(
        "Cross validation complete: "
        f"folds={summary['fold_count']}, "
        f"Top1={summary['top1_accuracy']:.4f}, "
        f"Top3={summary['top3_accuracy']:.4f}, "
        f"Top5={summary['top5_accuracy']:.4f}"
    )
    return 0


def run_calibrate_roi(args: argparse.Namespace) -> int:
    if args.scene == TEAM_PREVIEW_SCENE:
        raise SystemExit(
            "TEAM_PREVIEW pokemon_icon ROI is managed by "
            f"{args.team_preview_roi}; the old roi-config calibration path has been removed."
        )
    prepare_output_dir(args.output, args.clear_output)
    labels = read_labels(args.labels)
    roi_config = read_json(args.roi_config)
    roi_regions = load_roi_regions_from_config(roi_config, args.scene)
    templates = build_templates(args)
    weights = parse_weights(args.weights)
    shift_steps = parse_float_list(args.shift_steps, "--shift-steps")
    padding_steps = parse_float_list(args.padding_steps, "--padding-steps")
    requested_sides = {normalize_side_key(side) for side in (args.side or [])}
    rows_by_side: dict[str, list[LabelRow]] = {}
    for row in labels:
        side_key = normalize_side_key(row.side)
        if requested_sides and side_key not in requested_sides:
            continue
        if side_key not in roi_regions:
            raise ValueError(
                f"Label row {row.row_index} side '{row.side}' is not a known ROI. "
                f"Use values such as own.slot0, opponent.slot3, or a full ROI id."
            )
        rows_by_side.setdefault(side_key, []).append(row)
    missing_requested = requested_sides - set(rows_by_side)
    if missing_requested:
        raise ValueError(f"Requested side has no labels or no ROI: {', '.join(sorted(missing_requested))}")
    if not rows_by_side:
        raise ValueError("No labels are available for ROI calibration.")

    calibrated_regions = {
        side_key: RoiRegion(id=region.id, side_key=region.side_key, rect=dict(region.rect))
        for side_key, region in roi_regions.items()
    }
    all_candidate_rows = []
    best_by_side = []
    for side_key, side_rows in sorted(rows_by_side.items()):
        base_region = roi_regions[side_key]
        candidate_results = []
        for dx in shift_steps:
            for dy in shift_steps:
                for padding in padding_steps:
                    candidate_region = perturb_roi_region(base_region, dx, dy, padding)
                    result = evaluate_roi_calibration_candidate(
                        side_rows,
                        args.images_dir,
                        args.labels.parent,
                        candidate_region,
                        roi_config,
                        templates,
                        weights,
                        args.top_k,
                        dx,
                        dy,
                        padding,
                    )
                    candidate_results.append(result)
        ranked = sorted(candidate_results, key=roi_calibration_sort_key, reverse=True)
        best = ranked[0]
        calibrated_regions[side_key] = RoiRegion(
            id=base_region.id,
            side_key=side_key,
            rect=dict(best["rect"]),
        )
        best_by_side.append(best)
        for rank, result in enumerate(ranked, start=1):
            all_candidate_rows.append({"rank": rank, **result})

    calibrated_config = apply_calibrated_roi_regions(roi_config, args.scene, calibrated_regions, rows_by_side.keys())
    calibrated_config_path = args.output / "roi.calibrated.json"
    write_json(calibrated_config_path, calibrated_config)
    write_roi_calibration_csv(args.output / "roi_calibration.csv", all_candidate_rows)
    write_json(
        args.output / "roi_calibration.json",
        {
            "labels": {"path": str(args.labels), "count": len(labels)},
            "calibrated_sides": sorted(rows_by_side),
            "shift_steps": shift_steps,
            "padding_steps": padding_steps,
            "weights": weights,
            "templates": summarize_templates(templates),
            "best_by_side": best_by_side,
            "candidate_count": len(all_candidate_rows),
            "calibrated_roi_config": str(calibrated_config_path),
        },
    )

    debug_dir = args.output / "debug_roi"
    cases, debug_entries = build_scored_cases(
        labels,
        args.images_dir,
        args.labels.parent,
        calibrated_regions,
        calibrated_config_path,
        templates,
        debug_dir,
    )
    calibrated_metrics = evaluate_weight_candidate(cases, weights, args.top_k)
    predictions, failed_cases = predictions_from_scored_cases(cases, weights, args.top_k)
    write_json(
        args.output / "calibrated_metrics.json",
        {
            **calibrated_metrics,
            "weights": weights,
            "calibrated_roi_config": str(calibrated_config_path),
        },
    )
    write_predictions_csv(args.output / "calibrated_predictions.csv", predictions)
    write_failed_cases_csv(args.output / "calibrated_failed_cases.csv", failed_cases)
    write_roi_debug_outputs(args.output, debug_entries, args.contact_sheet_limit)
    print(
        "ROI calibration complete: "
        f"sides={len(best_by_side)}, "
        f"Top1={calibrated_metrics['top1_accuracy']:.4f}, "
        f"Top3={calibrated_metrics['top3_accuracy']:.4f}, "
        f"Top5={calibrated_metrics['top5_accuracy']:.4f}; "
        f"config={calibrated_config_path}"
    )
    return 0


def run_predict(args: argparse.Namespace) -> int:
    prepare_output_dir(args.output, args.clear_output)
    roi_regions = load_roi_regions(args.roi_config, args.scene, args.team_preview_roi)
    templates = build_templates(args)
    templates.extend(build_labeled_roi_templates_if_requested(args, roi_regions))
    weights = parse_weights(args.weights)
    side_weights = parse_side_weights(args.side_weights)
    requested_sides = [normalize_side_key(side) for side in (args.side or DEFAULT_SIDES)]
    for side in requested_sides:
        if side not in roi_regions:
            raise ValueError(f"Unknown side/ROI for prediction: {side}")
    predictions = []
    debug_dir = args.output / "debug_roi"
    debug_entries = []
    for image_path in list_images(args.images_dir):
        for side_key in requested_sides:
            crop_info = crop_roi_with_metadata(
                image_path,
                roi_regions[side_key],
                args.roi_config,
                scene_name=args.scene,
                crop_mode=args.crop_mode,
            )
            roi = crop_info["roi"]
            if not skip_debug_roi_crops(args):
                write_debug_roi(debug_dir, image_path, side_key, roi)
            debug_entries.append(crop_info)
            candidates = rank_candidates(
                roi,
                templates,
                weights_for_side(side_key, weights, side_weights),
                args.top_k,
                query_side_key=side_key,
                labeled_template_scope=args.labeled_template_scope,
                labeled_template_bonus=args.labeled_template_bonus,
            )
            predictions.append(
                {
                    "image": str(image_path),
                    "resolved_image": str(image_path),
                    "side": side_key,
                    "side_key": side_key,
                    "pokemon": "",
                    "top1": "",
                    "top3": "",
                    "top5": "",
                    "top5_candidates": candidates,
                }
            )
    write_predictions_csv(args.output / "predictions.csv", predictions)
    write_json(
        args.output / "prediction_summary.json",
        {
            "images": len(list_images(args.images_dir)),
            "predictions": len(predictions),
            "templates": summarize_templates(templates),
            "weights": weights,
            "side_weights": side_weights,
            "labeled_template_bonus": args.labeled_template_bonus,
            "crop_mode": args.crop_mode,
        },
    )
    write_roi_debug_outputs(
        args.output,
        debug_entries,
        args.contact_sheet_limit,
        skip_contact_sheet=skip_contact_sheet(args),
        skip_quality=skip_roi_quality(args),
        skip_overlays=skip_roi_overlays(args),
    )
    print(f"Prediction complete: {len(predictions)} crops, output={args.output}")
    return 0


def build_templates(args: argparse.Namespace) -> list[TemplateFeature]:
    references = load_reference_images(args.references)
    if not references:
        raise FileNotFoundError(f"No reference images found under {args.references}")
    cache_path = catalog_template_cache_path(args, references)
    templates = load_template_cache(cache_path, args, "catalog")
    if templates is not None:
        return templates
    all_references = list(references)
    if not args.no_augment and args.augment_count > 0:
        augmented_dir = (
            args.template_cache_dir / "augmented_references" / cache_path.stem
            if template_cache_enabled(args)
            else args.output / "augmented_references"
        )
        all_references.extend(
            write_augmented_references(
                references,
                augmented_dir,
                augment_count=args.augment_count,
                seed=args.seed,
                clear_output=args.clear_output,
            )
        )
    templates = []
    for reference in all_references:
        image = imread(reference.path)
        if image is None:
            continue
        templates.append(
            TemplateFeature(
                pokemon_id=reference.pokemon_id,
                aliases=reference.aliases,
                reference_path=reference.path,
                source_path=reference.source_path,
                variant=reference.variant,
                feature=create_feature(image),
            )
        )
    if not templates:
        raise RuntimeError("Reference images were found, but no templates could be decoded.")
    save_template_cache(cache_path, args, "catalog", templates)
    return templates


def build_labeled_roi_templates_if_requested(
    args: argparse.Namespace,
    roi_regions: dict[str, RoiRegion],
    labels: list[LabelRow] | None = None,
    images_dir: Path | None = None,
    labels_dir: Path | None = None,
) -> list[TemplateFeature]:
    if not args.use_labeled_roi_templates:
        return []
    labels_path = args.labeled_template_labels or getattr(args, "labels", None)
    images_root = args.labeled_template_images_dir or images_dir
    if images_root is None and hasattr(args, "labels"):
        images_root = getattr(args, "images_dir", None)
    if labels_path is None or images_root is None:
        raise ValueError(
            "--use-labeled-roi-templates needs --labeled-template-labels and "
            "--labeled-template-images-dir when the command has no training labels."
        )
    command_labels_path = getattr(args, "labels", None)
    uses_command_labels = labels is not None and labels_path == command_labels_path
    template_labels = labels if uses_command_labels else read_labels(labels_path)
    template_labels_dir = labels_dir if uses_command_labels and labels_dir is not None else Path(labels_path).parent
    cache_path = labeled_template_cache_path(
        args,
        template_labels,
        Path(images_root),
        Path(template_labels_dir),
        roi_regions,
        Path(labels_path),
    )
    templates = load_template_cache(cache_path, args, "labeled ROI")
    if templates is not None:
        print(f"Loaded labeled ROI templates: {len(templates)}")
        return templates
    templates = build_labeled_roi_templates(
        template_labels,
        Path(images_root),
        Path(template_labels_dir),
        roi_regions,
        args.roi_config,
        args.scene,
        args.crop_mode,
    )
    save_template_cache(cache_path, args, "labeled ROI", templates)
    print(f"Loaded labeled ROI templates: {len(templates)}")
    return templates


def build_labeled_roi_templates(
    labels: list[LabelRow],
    images_dir: Path,
    labels_dir: Path,
    roi_regions: dict[str, RoiRegion],
    roi_config_path: Path,
    scene_name: str = "TEAM_PREVIEW",
    crop_mode: str = "team-preview-safe-zone",
) -> list[TemplateFeature]:
    templates = []
    for row in labels:
        image_path = resolve_image_path(row.image, images_dir, labels_dir)
        if not image_path.exists():
            raise FileNotFoundError(f"Label row {row.row_index} image not found: {row.image}")
        side_key = normalize_side_key(row.side)
        if side_key not in roi_regions:
            raise ValueError(f"Label row {row.row_index} side '{row.side}' is not a known ROI.")
        crop_info = crop_roi_with_metadata(
            image_path,
            roi_regions[side_key],
            roi_config_path,
            scene_name=scene_name,
            crop_mode=crop_mode,
        )
        pokemon_id = normalize_pokemon_key(row.pokemon)
        aliases = {pokemon_id, normalize_pokemon_key(row.pokemon)}
        templates.append(
            TemplateFeature(
                pokemon_id=pokemon_id,
                aliases={alias for alias in aliases if alias},
                reference_path=image_path,
                source_path=image_path,
                variant=f"labeled_roi:{side_key}",
                feature=create_feature(crop_info["roi"]),
                sample_image=normalized_path_text(image_path),
                side_key=side_key,
            )
        )
    return templates


def template_cache_enabled(args: argparse.Namespace) -> bool:
    return not getattr(args, "no_template_cache", False)


def active_score_methods(args: argparse.Namespace) -> list[str]:
    return ["combined"] if getattr(args, "fast_detect", False) else list(SCORE_METHODS)


def skip_debug_roi_crops(args: argparse.Namespace) -> bool:
    return (
        getattr(args, "fast_detect", False)
        or args.skip_roi_debug_outputs
        or args.skip_debug_roi_crops
    )


def skip_contact_sheet(args: argparse.Namespace) -> bool:
    return (
        getattr(args, "fast_detect", False)
        or args.skip_roi_debug_outputs
        or args.skip_contact_sheet
    )


def skip_roi_quality(args: argparse.Namespace) -> bool:
    return (
        getattr(args, "fast_detect", False)
        or args.skip_roi_debug_outputs
        or args.skip_roi_quality
    )


def skip_roi_overlays(args: argparse.Namespace) -> bool:
    return (
        getattr(args, "fast_detect", False)
        or args.skip_roi_debug_outputs
        or args.skip_roi_overlays
    )


def catalog_template_cache_path(args: argparse.Namespace, references: list[ReferenceImage]) -> Path:
    digest = digest_json(
        {
            "kind": "catalog",
            "version": TEMPLATE_CACHE_VERSION,
            "feature_size": FEATURE_SIZE,
            "references": [stable_file_fingerprint(reference.path) for reference in references],
            "augment": {
                "enabled": not args.no_augment and args.augment_count > 0,
                "count": args.augment_count,
                "seed": args.seed,
            },
        }
    )
    return args.template_cache_dir / f"catalog-{digest}.pkl"


def labeled_template_cache_path(
    args: argparse.Namespace,
    labels: list[LabelRow],
    images_dir: Path,
    labels_dir: Path,
    roi_regions: dict[str, RoiRegion],
    labels_path: Path,
) -> Path:
    label_fingerprints = []
    for row in labels:
        image_path = resolve_image_path(row.image, images_dir, labels_dir)
        label_fingerprints.append(
            {
                "image": row.image,
                "side": row.side,
                "pokemon": row.pokemon,
                "resolved_image": stable_file_fingerprint(image_path),
            }
        )
    digest = digest_json(
        {
            "kind": "labeled-roi",
            "version": TEMPLATE_CACHE_VERSION,
            "feature_size": FEATURE_SIZE,
            "labels": stable_file_fingerprint(labels_path),
            "label_rows": label_fingerprints,
            "images_dir": stable_path_text(images_dir),
            "labels_dir": stable_path_text(labels_dir),
            "roi_config": stable_file_fingerprint(args.roi_config),
            "team_preview_roi": stable_file_fingerprint(args.team_preview_roi),
            "scene": args.scene,
            "crop_mode": args.crop_mode,
            "roi_sides": sorted(roi_regions),
        }
    )
    return args.template_cache_dir / f"labeled-roi-{digest}.pkl"


def load_template_cache(cache_path: Path, args: argparse.Namespace, label: str) -> list[TemplateFeature] | None:
    if not template_cache_enabled(args) or args.refresh_template_cache or not cache_path.exists():
        return None
    try:
        with cache_path.open("rb") as handle:
            payload = pickle.load(handle)
    except (OSError, pickle.PickleError, EOFError, ValueError):
        return None
    if payload.get("version") != TEMPLATE_CACHE_VERSION:
        return None
    templates = [deserialize_template(item) for item in payload.get("templates", [])]
    if not templates:
        return None
    print(f"Loaded {label} templates from cache: {len(templates)}")
    return templates


def save_template_cache(
    cache_path: Path,
    args: argparse.Namespace,
    label: str,
    templates: list[TemplateFeature],
) -> None:
    if not template_cache_enabled(args):
        return
    cache_path.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "version": TEMPLATE_CACHE_VERSION,
        "label": label,
        "templates": [serialize_template(template) for template in templates],
    }
    with cache_path.open("wb") as handle:
        pickle.dump(payload, handle, protocol=pickle.HIGHEST_PROTOCOL)


def serialize_template(template: TemplateFeature) -> dict[str, Any]:
    return {
        "pokemon_id": template.pokemon_id,
        "aliases": sorted(template.aliases),
        "reference_path": stable_path_text(template.reference_path),
        "source_path": stable_path_text(template.source_path),
        "variant": template.variant,
        "feature": template.feature,
        "sample_image": template.sample_image,
        "side_key": template.side_key,
    }


def deserialize_template(data: dict[str, Any]) -> TemplateFeature:
    return TemplateFeature(
        pokemon_id=data["pokemon_id"],
        aliases=set(data["aliases"]),
        reference_path=Path(cache_path_text(data["reference_path"])),
        source_path=Path(cache_path_text(data["source_path"])),
        variant=data["variant"],
        feature=data["feature"],
        sample_image=cache_path_text(data.get("sample_image")) if data.get("sample_image") else None,
        side_key=data.get("side_key"),
    )


def digest_json(data: Any) -> str:
    text = json.dumps(data, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(text.encode("utf-8")).hexdigest()[:20]


def stable_file_fingerprint(path: Path) -> dict[str, Any]:
    try:
        stat = path.stat()
    except OSError:
        return {"path": stable_path_text(path), "exists": False}
    return {
        "path": stable_path_text(path),
        "exists": True,
        "size": stat.st_size,
        "sha256": file_sha256(path),
    }


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_reference_images(references_dir: Path) -> list[ReferenceImage]:
    references = []
    for path in list_images(references_dir):
        pokemon_id, aliases = parse_reference_identity(path)
        references.append(
            ReferenceImage(
                pokemon_id=pokemon_id,
                aliases=aliases,
                path=path,
                source_path=path,
                variant="original",
            )
        )
    return references


def parse_reference_identity(path: Path) -> tuple[str, set[str]]:
    stem = path.stem
    primary_segment = stem.split("__", 1)[0].strip()
    tokens = [token for token in re.split(r"[^0-9A-Za-z\u4e00-\u9fff]+", stem) if token]
    numeric = next((token for token in tokens if token.isdigit()), None)
    non_numeric = [token for token in tokens if not token.isdigit()]
    if primary_segment and not primary_segment.isdigit():
        pokemon_id = normalize_pokemon_key(primary_segment)
    elif numeric:
        pokemon_id = str(int(numeric))
    elif non_numeric:
        pokemon_id = normalize_pokemon_key(" ".join(non_numeric))
    else:
        pokemon_id = normalize_pokemon_key(stem)
    aliases = {pokemon_id, normalize_pokemon_key(stem), normalize_pokemon_key(primary_segment)}
    aliases.update(normalize_pokemon_key(token) for token in tokens)
    if numeric and non_numeric:
        aliases.add(normalize_pokemon_key(" ".join(non_numeric)))
        aliases.add(normalize_pokemon_key("-".join(non_numeric)))
    return pokemon_id, {alias for alias in aliases if alias}


def write_augmented_references(
    references: list[ReferenceImage],
    output_dir: Path,
    augment_count: int,
    seed: int,
    clear_output: bool = False,
) -> list[ReferenceImage]:
    if augment_count <= 0:
        return []
    if clear_output and output_dir.exists():
        shutil.rmtree(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    rng = random.Random(seed)
    augmented = []
    for reference in references:
        image = imread(reference.path)
        if image is None:
            continue
        for index in range(augment_count):
            aug = augment_reference_image(image, rng)
            out_dir = output_dir / safe_filename(reference.pokemon_id)
            out_dir.mkdir(parents=True, exist_ok=True)
            out_path = out_dir / f"{reference.path.stem}_aug{index:02d}.jpg"
            encode_image(out_path, aug, quality=rng.randint(72, 95))
            augmented.append(
                ReferenceImage(
                    pokemon_id=reference.pokemon_id,
                    aliases=set(reference.aliases),
                    path=out_path,
                    source_path=reference.source_path,
                    variant=f"aug{index:02d}",
                )
            )
    return augmented


def augment_reference_image(image: np.ndarray, rng: random.Random) -> np.ndarray:
    bgr, alpha = to_bgr_alpha(image)
    mask = alpha_mask(alpha) if alpha is not None else foreground_mask(bgr)
    bbox = padded_bbox(mask_bbox(mask) or full_bbox(bgr), bgr.shape[1], bgr.shape[0], 0.04)
    fg = bgr[bbox["top"] : bbox["top"] + bbox["height"], bbox["left"] : bbox["left"] + bbox["width"]]
    fg_mask = mask[bbox["top"] : bbox["top"] + bbox["height"], bbox["left"] : bbox["left"] + bbox["width"]]
    height, width = bgr.shape[:2]
    bg = random_background(height, width, rng)
    scale = rng.uniform(0.72, 1.22)
    scaled_w = max(1, int(round(fg.shape[1] * scale)))
    scaled_h = max(1, int(round(fg.shape[0] * scale)))
    fg = cv2.resize(fg, (scaled_w, scaled_h), interpolation=cv2.INTER_AREA)
    fg_mask = cv2.resize(fg_mask, (scaled_w, scaled_h), interpolation=cv2.INTER_NEAREST)
    dx = rng.randint(-max(1, width // 8), max(1, width // 8))
    dy = rng.randint(-max(1, height // 8), max(1, height // 8))
    left = (width - scaled_w) // 2 + dx
    top = (height - scaled_h) // 2 + dy
    canvas = bg
    paste_masked(canvas, fg, fg_mask, left, top)
    contrast = rng.uniform(0.78, 1.25)
    brightness = rng.uniform(-28, 28)
    canvas = cv2.convertScaleAbs(canvas, alpha=contrast, beta=brightness)
    if rng.random() < 0.55:
        kernel = rng.choice([3, 5])
        canvas = cv2.GaussianBlur(canvas, (kernel, kernel), rng.uniform(0.1, 1.0))
    if rng.random() < 0.7:
        quality = rng.randint(45, 92)
        ok, encoded = cv2.imencode(".jpg", canvas, [int(cv2.IMWRITE_JPEG_QUALITY), quality])
        if ok:
            canvas = cv2.imdecode(encoded, cv2.IMREAD_COLOR)
    return canvas


def random_background(height: int, width: int, rng: random.Random) -> np.ndarray:
    base = np.array([rng.randint(25, 210), rng.randint(25, 210), rng.randint(25, 210)], dtype=np.uint8)
    bg = np.zeros((height, width, 3), dtype=np.uint8)
    bg[:] = base
    if rng.random() < 0.65:
        noise = np.random.default_rng(rng.randint(0, 2**31 - 1)).normal(0, rng.uniform(3, 18), bg.shape)
        bg = np.clip(bg.astype(np.float32) + noise, 0, 255).astype(np.uint8)
    if rng.random() < 0.45:
        stripe_color = np.array([rng.randint(20, 230), rng.randint(20, 230), rng.randint(20, 230)], dtype=np.uint8)
        y0 = rng.randint(0, max(0, height - 1))
        y1 = min(height, y0 + rng.randint(max(2, height // 8), max(3, height // 2)))
        bg[y0:y1] = (bg[y0:y1].astype(np.uint16) * 2 // 3 + stripe_color.astype(np.uint16) // 3).astype(np.uint8)
    return bg


def paste_masked(canvas: np.ndarray, fg: np.ndarray, mask: np.ndarray, left: int, top: int) -> None:
    height, width = canvas.shape[:2]
    src_left = max(0, -left)
    src_top = max(0, -top)
    dst_left = max(0, left)
    dst_top = max(0, top)
    dst_right = min(width, left + fg.shape[1])
    dst_bottom = min(height, top + fg.shape[0])
    if dst_left >= dst_right or dst_top >= dst_bottom:
        return
    src_right = src_left + (dst_right - dst_left)
    src_bottom = src_top + (dst_bottom - dst_top)
    local_mask = (mask[src_top:src_bottom, src_left:src_right] > 0).astype(np.float32)[..., None]
    canvas[dst_top:dst_bottom, dst_left:dst_right] = (
        fg[src_top:src_bottom, src_left:src_right].astype(np.float32) * local_mask
        + canvas[dst_top:dst_bottom, dst_left:dst_right].astype(np.float32) * (1.0 - local_mask)
    ).astype(np.uint8)


def load_roi_regions(
    roi_config_path: Path,
    scene_name: str,
    team_preview_roi_path: Path = DEFAULT_TEAM_PREVIEW_ROI,
) -> dict[str, RoiRegion]:
    if scene_name == TEAM_PREVIEW_SCENE:
        return load_team_preview_safe_zone_regions(team_preview_roi_path)
    config = read_json(roi_config_path)
    return load_roi_regions_from_config(config, scene_name)


def load_team_preview_safe_zone_regions(team_preview_roi_path: Path) -> dict[str, RoiRegion]:
    safe_zone = read_json(team_preview_roi_path)
    base_size = safe_zone.get("baseImageSize", {})
    base_width = int(base_size.get("width", 0))
    base_height = int(base_size.get("height", 0))
    if base_width <= 0 or base_height <= 0:
        raise ValueError(f"Invalid SafeZone baseImageSize in {team_preview_roi_path}")
    regions = {}
    for region in safe_zone.get("regions", []):
        if region.get("role") != "pokemon_icon":
            continue
        side_key = normalize_side_key(region["id"])
        rect = dict(region["rect"])
        rect["baseWidth"] = base_width
        rect["baseHeight"] = base_height
        regions[side_key] = RoiRegion(id=region["id"], side_key=side_key, rect=rect)
    missing = set(DEFAULT_SIDES) - set(regions)
    if missing:
        raise ValueError(f"SafeZone ROI missing sides: {', '.join(sorted(missing))}")
    return {side: regions[side] for side in DEFAULT_SIDES}


def load_roi_regions_from_config(roi_config: dict[str, Any], scene_name: str) -> dict[str, RoiRegion]:
    config = roi_config
    scene = config.get("scenes", {}).get(scene_name)
    if not scene:
        raise ValueError(f"ROI scene not found: {scene_name}")
    regions = {}
    for region in scene.get("regions", []):
        if region.get("role") != "pokemon_icon":
            continue
        side_key = normalize_side_key(region["id"])
        regions[side_key] = RoiRegion(id=region["id"], side_key=side_key, rect=region["rect"])
    return regions


def normalize_side_key(value: str) -> str:
    text = value.strip().lower().replace("\\", "/")
    text = text.replace("team_preview.", "").replace(".pokemon_icon", "")
    text = text.replace("pokemon_icon", "")
    text = text.replace("/", ".").replace("_", ".").replace("-", ".")
    text = re.sub(r"\.+", ".", text).strip(".")
    text = text.replace("opponent", "opponent").replace("opp.", "opponent.")
    text = text.replace("enemy.", "opponent.")
    text = text.replace("left.", "own.").replace("right.", "opponent.")
    match = re.fullmatch(r"(own|opponent)(?:\.slot)?\.?([0-5])", text)
    if match:
        return f"{match.group(1)}.slot{match.group(2)}"
    match = re.fullmatch(r"(own|opponent)\.slot([0-5])", text)
    if match:
        return f"{match.group(1)}.slot{match.group(2)}"
    return text


def crop_roi(
    image_path: Path,
    region: RoiRegion,
    roi_config_path: Path,
    scene_name: str = "TEAM_PREVIEW",
    crop_mode: str = "team-preview-safe-zone",
) -> np.ndarray:
    return crop_roi_with_metadata(
        image_path,
        region,
        roi_config_path,
        scene_name=scene_name,
        crop_mode=crop_mode,
    )["roi"]


def crop_roi_with_metadata(
    image_path: Path,
    region: RoiRegion,
    roi_config_path: Path,
    scene_name: str = "TEAM_PREVIEW",
    crop_mode: str = "team-preview-safe-zone",
) -> dict[str, Any]:
    config = read_json(roi_config_path)
    return crop_roi_with_metadata_from_config(
        image_path,
        region,
        config,
        scene_name=scene_name,
        crop_mode=crop_mode,
    )


def crop_roi_with_metadata_from_config(
    image_path: Path,
    region: RoiRegion,
    roi_config: dict[str, Any],
    scene_name: str = "TEAM_PREVIEW",
    crop_mode: str = "team-preview-safe-zone",
) -> dict[str, Any]:
    image = imread(image_path)
    if image is None:
        raise FileNotFoundError(f"Cannot read image: {image_path}")
    viewport = detect_game_viewport(
        {"width": int(image.shape[1]), "height": int(image.shape[0])},
        roi_config.get("viewportDetection", {}).get("targetAspectRatio"),
    )
    rect, rect_source = crop_rect_for_region(image.shape[1], image.shape[0], region, scene_name, crop_mode)
    return {
        "image_path": image_path,
        "side_key": region.side_key,
        "region_id": region.id,
        "roi": extract_rect(image, rect),
        "rect": rect,
        "rect_source": rect_source,
        "crop_mode": crop_mode,
        "image_width": int(image.shape[1]),
        "image_height": int(image.shape[0]),
        "viewport": viewport,
    }


def crop_rect_for_region(
    image_width: int,
    image_height: int,
    region: RoiRegion,
    scene_name: str,
    crop_mode: str,
) -> tuple[dict[str, int], str]:
    if crop_mode != "team-preview-safe-zone":
        raise ValueError(f"Unknown crop mode: {crop_mode}")
    if scene_name != TEAM_PREVIEW_SCENE:
        raise ValueError("team-preview-safe-zone crop mode only supports TEAM_PREVIEW.")
    return scaled_safe_zone_rect_to_image_rect(region.rect, image_width, image_height), "team-preview-safe-zone"


def scaled_safe_zone_rect_to_image_rect(rect: dict[str, int], image_width: int, image_height: int) -> dict[str, int]:
    scale_x = image_width / int(rect["baseWidth"])
    scale_y = image_height / int(rect["baseHeight"])
    left = round(rect["left"] * scale_x)
    top = round(rect["top"] * scale_y)
    right = round(rect["right"] * scale_x)
    bottom = round(rect["bottom"] * scale_y)
    return clamp_rect(
        {
            "left": left,
            "top": top,
            "width": max(1, right - left),
            "height": max(1, bottom - top),
        },
        full_image_bounds(image_width, image_height),
    )


def create_feature(image: np.ndarray) -> dict[str, Any]:
    bgr, alpha = to_bgr_alpha(image)
    mask = alpha_mask(alpha) if alpha is not None else foreground_mask(bgr)
    if mask is None or np.count_nonzero(mask) < max(8, mask.size * 0.005):
        mask = np.ones(bgr.shape[:2], dtype=np.uint8) * 255
    source_quality = source_mask_quality(mask, bgr)
    bbox = padded_bbox(mask_bbox(mask) or full_bbox(bgr), bgr.shape[1], bgr.shape[0], 0.06)
    cropped_bgr = bgr[bbox["top"] : bbox["top"] + bbox["height"], bbox["left"] : bbox["left"] + bbox["width"]]
    cropped_mask = mask[bbox["top"] : bbox["top"] + bbox["height"], bbox["left"] : bbox["left"] + bbox["width"]]
    normalized = normalize_color(cropped_bgr, cropped_mask)
    canvas_bgr, canvas_mask = resize_fit(normalized, cropped_mask, FEATURE_SIZE)
    gray = cv2.cvtColor(canvas_bgr, cv2.COLOR_BGR2GRAY)
    gray = cv2.bitwise_and(gray, gray, mask=canvas_mask)
    edge = cv2.Canny(gray, 60, 150)
    edge = cv2.bitwise_and(edge, edge, mask=canvas_mask)
    hsv = cv2.cvtColor(canvas_bgr, cv2.COLOR_BGR2HSV)
    hist = cv2.calcHist([hsv], [0, 1], canvas_mask, [24, 16], [0, 180, 0, 256]).astype(np.float32)
    hist_sum = float(hist.sum())
    if hist_sum > 0:
        hist /= hist_sum
    return {
        "bgr": canvas_bgr,
        "gray": gray,
        "mask": canvas_mask,
        "edge": edge,
        "edge_bool": edge > 0,
        "hist": hist,
        "phash": perceptual_hash(gray),
        "source_quality": source_quality,
    }


def rank_candidates(
    roi_image: np.ndarray,
    templates: list[TemplateFeature],
    weights: dict[str, float],
    top_k: int,
    query_side_key: str | None = None,
    labeled_template_scope: str = "all",
    labeled_template_bonus: float = 0.0,
) -> list[dict[str, Any]]:
    roi_feature = create_feature(roi_image)
    return rank_candidates_from_feature(
        roi_feature,
        templates,
        weights,
        top_k,
        "combined",
        query_side_key=query_side_key,
        labeled_template_scope=labeled_template_scope,
        labeled_template_bonus=labeled_template_bonus,
    )


def rank_candidates_from_feature(
    roi_feature: dict[str, Any],
    templates: list[TemplateFeature],
    weights: dict[str, float],
    top_k: int,
    score_method: str,
    excluded_sample_image: str | None = None,
    query_side_key: str | None = None,
    labeled_template_scope: str = "all",
    labeled_template_bonus: float = 0.0,
) -> list[dict[str, Any]]:
    return rank_candidates_by_methods_from_feature(
        roi_feature,
        templates,
        weights,
        top_k,
        [score_method],
        excluded_sample_image=excluded_sample_image,
        query_side_key=query_side_key,
        labeled_template_scope=labeled_template_scope,
        labeled_template_bonus=labeled_template_bonus,
    )[score_method]


def rank_candidates_by_methods_from_feature(
    roi_feature: dict[str, Any],
    templates: list[TemplateFeature],
    weights: dict[str, float],
    top_k: int,
    score_methods: list[str],
    excluded_sample_image: str | None = None,
    query_side_key: str | None = None,
    labeled_template_scope: str = "all",
    labeled_template_bonus: float = 0.0,
) -> dict[str, list[dict[str, Any]]]:
    unknown = [method for method in score_methods if method not in SCORE_METHODS]
    if unknown:
        raise ValueError(f"Unknown score method: {', '.join(unknown)}")
    best_by_method: dict[str, dict[str, dict[str, Any]]] = {method: {} for method in score_methods}
    for template in templates:
        if excluded_sample_image and template.sample_image == excluded_sample_image:
            continue
        if not labeled_template_matches_scope(template, query_side_key, labeled_template_scope):
            continue
        scores = compare_features(roi_feature, template.feature)
        for score_method in score_methods:
            total = (
                sum(scores[key] * weights[key] for key in weights)
                if score_method == "combined"
                else scores[score_method]
            )
            source_bonus = labeled_template_source_bonus(
                template,
                roi_feature,
                query_side_key,
                labeled_template_bonus,
                score_method,
            )
            adjusted_total = total + source_bonus
            candidate = {
                "pokemon_id": template.pokemon_id,
                "aliases": sorted(template.aliases),
                "score": round_float(adjusted_total),
                "base_score": round_float(total),
                "source_bonus": round_float(source_bonus),
                "score_method": score_method,
                "scores": {key: round_float(value) for key, value in scores.items()},
                "reference_path": str(template.reference_path),
                "source_path": str(template.source_path),
                "variant": template.variant,
                "sample_image": template.sample_image or "",
                "template_side_key": template.side_key or "",
            }
            best_by_pokemon = best_by_method[score_method]
            current = best_by_pokemon.get(template.pokemon_id)
            if current is None or candidate["score"] > current["score"]:
                best_by_pokemon[template.pokemon_id] = candidate
    return {
        method: sorted(best_by_method[method].values(), key=lambda item: item["score"], reverse=True)[:top_k]
        for method in score_methods
    }


def labeled_template_source_bonus(
    template: TemplateFeature,
    query_feature: dict[str, Any],
    query_side_key: str | None,
    labeled_template_bonus: float,
    score_method: str,
) -> float:
    if score_method != "combined" or not template.side_key or not query_side_key:
        return 0.0
    if normalize_side_key(template.side_key) != normalize_side_key(query_side_key):
        return 0.0
    scale = min(
        labeled_template_bonus_scale(query_feature),
        labeled_template_bonus_scale(template.feature),
    )
    return labeled_template_bonus * scale


def build_scored_cases(
    labels: list[LabelRow],
    images_dir: Path,
    labels_dir: Path,
    roi_regions: dict[str, RoiRegion],
    roi_config_path: Path,
    templates: list[TemplateFeature],
    debug_dir: Path,
    exclude_self_labeled_templates: bool = False,
    labeled_template_scope: str = "all",
    scene_name: str = "TEAM_PREVIEW",
    crop_mode: str = "team-preview-safe-zone",
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    cases = []
    debug_entries = []
    for row in labels:
        image_path = resolve_image_path(row.image, images_dir, labels_dir)
        if not image_path.exists():
            raise FileNotFoundError(f"Label row {row.row_index} image not found: {row.image}")
        side_key = normalize_side_key(row.side)
        if side_key not in roi_regions:
            raise ValueError(
                f"Label row {row.row_index} side '{row.side}' is not a known ROI. "
                f"Use values such as own.slot0, opponent.slot3, or a full ROI id."
            )
        crop_info = crop_roi_with_metadata(
            image_path,
            roi_regions[side_key],
            roi_config_path,
            scene_name=scene_name,
            crop_mode=crop_mode,
        )
        roi = crop_info["roi"]
        write_debug_roi(debug_dir, image_path, side_key, roi)
        debug_entries.append(crop_info)
        roi_feature = create_feature(roi)
        excluded_sample_image = normalized_path_text(image_path) if exclude_self_labeled_templates else None
        records = score_templates_for_feature(
            roi_feature,
            templates,
            excluded_sample_image=excluded_sample_image,
            query_side_key=side_key,
            labeled_template_scope=labeled_template_scope,
        )
        cases.append(
            {
                "row_index": row.row_index,
                "image": row.image,
                "resolved_image": str(image_path),
                "group_key": str(image_path),
                "side": row.side,
                "side_key": side_key,
                "pokemon": row.pokemon,
                "true_key": normalize_pokemon_key(row.pokemon),
                "records": records,
            }
        )
    return cases, debug_entries


def score_templates_for_feature(
    roi_feature: dict[str, Any],
    templates: list[TemplateFeature],
    excluded_sample_image: str | None = None,
    query_side_key: str | None = None,
    labeled_template_scope: str = "all",
) -> list[dict[str, Any]]:
    records = []
    for template in templates:
        if excluded_sample_image and template.sample_image == excluded_sample_image:
            continue
        if not labeled_template_matches_scope(template, query_side_key, labeled_template_scope):
            continue
        records.append(
            {
                "pokemon_id": template.pokemon_id,
                "aliases": sorted(template.aliases),
                "reference_path": str(template.reference_path),
                "source_path": str(template.source_path),
                "variant": template.variant,
                "sample_image": template.sample_image or "",
                "template_side_key": template.side_key or "",
                "scores": {
                    key: round_float(value)
                    for key, value in compare_features(roi_feature, template.feature).items()
                },
            }
        )
    return records


def evaluate_weight_candidate(
    cases: list[dict[str, Any]],
    weights: dict[str, float],
    top_k: int,
) -> dict[str, Any]:
    correct_top1 = 0
    correct_top3 = 0
    correct_top5 = 0
    for case in cases:
        candidates = rank_records_with_weights(case["records"], weights, top_k)
        ranks = candidate_match_ranks(candidates, case["true_key"])
        correct_top1 += int(bool(ranks and ranks[0] <= 1))
        correct_top3 += int(bool(ranks and ranks[0] <= 3))
        correct_top5 += int(bool(ranks and ranks[0] <= 5))
    total = len(cases)
    return {
        "weights": {key: round_float(weights[key]) for key in DEFAULT_WEIGHTS},
        "top1_accuracy": safe_ratio(correct_top1, total),
        "top3_accuracy": safe_ratio(correct_top3, total),
        "top5_accuracy": safe_ratio(correct_top5, total),
        "correct_top1": correct_top1,
        "correct_top3": correct_top3,
        "correct_top5": correct_top5,
        "total": total,
    }


def predictions_from_scored_cases(
    cases: list[dict[str, Any]],
    weights: dict[str, float],
    top_k: int,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    predictions = []
    failed_cases = []
    for case in cases:
        candidates = rank_records_with_weights(case["records"], weights, top_k)
        ranks = candidate_match_ranks(candidates, case["true_key"])
        top1 = bool(ranks and ranks[0] <= 1)
        top3 = bool(ranks and ranks[0] <= 3)
        top5 = bool(ranks and ranks[0] <= 5)
        prediction = {
            "image": case["image"],
            "resolved_image": case["resolved_image"],
            "side": case["side"],
            "side_key": case["side_key"],
            "pokemon": case["pokemon"],
            "top1": top1,
            "top3": top3,
            "top5": top5,
            "top5_candidates": candidates,
        }
        predictions.append(prediction)
        if not top1:
            failed_cases.append(prediction)
    return predictions, failed_cases


def evaluate_roi_calibration_candidate(
    rows: list[LabelRow],
    images_dir: Path,
    labels_dir: Path,
    region: RoiRegion,
    roi_config: dict[str, Any],
    templates: list[TemplateFeature],
    weights: dict[str, float],
    top_k: int,
    dx: float,
    dy: float,
    padding: float,
) -> dict[str, Any]:
    correct_top1 = 0
    correct_top3 = 0
    correct_top5 = 0
    reciprocal_rank_sum = 0.0
    top1_score_sum = 0.0
    for row in rows:
        image_path = resolve_image_path(row.image, images_dir, labels_dir)
        if not image_path.exists():
            raise FileNotFoundError(f"Label row {row.row_index} image not found: {row.image}")
        crop_info = crop_roi_with_metadata_from_config(image_path, region, roi_config)
        roi_feature = create_feature(crop_info["roi"])
        records = score_templates_for_feature(roi_feature, templates)
        candidates = rank_records_with_weights(records, weights, top_k)
        ranks = candidate_match_ranks(candidates, normalize_pokemon_key(row.pokemon))
        if candidates:
            top1_score_sum += candidates[0]["score"]
        if ranks:
            reciprocal_rank_sum += 1.0 / ranks[0]
        correct_top1 += int(bool(ranks and ranks[0] <= 1))
        correct_top3 += int(bool(ranks and ranks[0] <= 3))
        correct_top5 += int(bool(ranks and ranks[0] <= 5))
    total = len(rows)
    return {
        "side": region.side_key,
        "region_id": region.id,
        "label_count": total,
        "dx": round_float(dx),
        "dy": round_float(dy),
        "padding": round_float(padding),
        "rect": {key: round_float(value) for key, value in region.rect.items()},
        "top1_accuracy": safe_ratio(correct_top1, total),
        "top3_accuracy": safe_ratio(correct_top3, total),
        "top5_accuracy": safe_ratio(correct_top5, total),
        "correct_top1": correct_top1,
        "correct_top3": correct_top3,
        "correct_top5": correct_top5,
        "mean_reciprocal_rank_at_top_k": safe_ratio(reciprocal_rank_sum, total),
        "mean_top1_score": safe_ratio(top1_score_sum, total),
        "total": total,
    }


def perturb_roi_region(region: RoiRegion, dx: float, dy: float, padding: float) -> RoiRegion:
    rect = region.rect
    width = rect["width"]
    height = rect["height"]
    new_width = max(width * 0.35, width * (1.0 + 2.0 * padding))
    new_height = max(height * 0.35, height * (1.0 + 2.0 * padding))
    center_x = rect["x"] + width * 0.5 + dx * width
    center_y = rect["y"] + height * 0.5 + dy * height
    return RoiRegion(
        id=region.id,
        side_key=region.side_key,
        rect={
            "x": center_x - new_width * 0.5,
            "y": center_y - new_height * 0.5,
            "width": new_width,
            "height": new_height,
        },
    )


def roi_calibration_sort_key(result: dict[str, Any]) -> tuple[float, float, float, float, float, float]:
    distance = abs(result["dx"]) + abs(result["dy"]) + abs(result["padding"])
    return (
        result["top1_accuracy"],
        result["top3_accuracy"],
        result["top5_accuracy"],
        result["mean_reciprocal_rank_at_top_k"],
        result["mean_top1_score"],
        -distance,
    )


def apply_calibrated_roi_regions(
    roi_config: dict[str, Any],
    scene_name: str,
    calibrated_regions: dict[str, RoiRegion],
    side_keys_to_update: Iterable[str],
) -> dict[str, Any]:
    updated = copy.deepcopy(roi_config)
    side_keys = set(side_keys_to_update)
    scene = updated.get("scenes", {}).get(scene_name)
    if not scene:
        raise ValueError(f"ROI scene not found: {scene_name}")
    for region in scene.get("regions", []):
        if region.get("role") != "pokemon_icon":
            continue
        side_key = normalize_side_key(region["id"])
        if side_key in side_keys:
            region["rect"] = {
                key: round_float(value)
                for key, value in calibrated_regions[side_key].rect.items()
            }
    updated.setdefault("calibration", {})["pokemonVisionPipeline"] = {
        "scene": scene_name,
        "updatedSides": sorted(side_keys),
        "source": "tools/recognition/pokemon-vision-pipeline.py calibrate-roi",
    }
    return updated


def rank_records_with_weights(
    records: list[dict[str, Any]],
    weights: dict[str, float],
    top_k: int,
) -> list[dict[str, Any]]:
    best_by_pokemon: dict[str, dict[str, Any]] = {}
    for record in records:
        score = sum(record["scores"][key] * weights[key] for key in weights)
        candidate = {
            "pokemon_id": record["pokemon_id"],
            "aliases": record["aliases"],
            "score": round_float(score),
            "score_method": "combined",
            "scores": record["scores"],
            "reference_path": record["reference_path"],
            "source_path": record["source_path"],
            "variant": record["variant"],
        }
        current = best_by_pokemon.get(record["pokemon_id"])
        if current is None or candidate["score"] > current["score"]:
            best_by_pokemon[record["pokemon_id"]] = candidate
    return sorted(best_by_pokemon.values(), key=lambda item: item["score"], reverse=True)[:top_k]


def candidate_match_ranks(candidates: list[dict[str, Any]], true_key: str) -> list[int]:
    return [
        index + 1
        for index, candidate in enumerate(candidates)
        if true_key in candidate["aliases"]
    ]


def labeled_template_matches_scope(
    template: TemplateFeature,
    query_side_key: str | None,
    scope: str,
) -> bool:
    if not template.side_key or scope == "all" or not query_side_key:
        return True
    if scope == "same-side":
        return template.side_key.split(".", 1)[0] == query_side_key.split(".", 1)[0]
    if scope == "same-slot":
        return template.side_key == query_side_key
    raise ValueError(f"Unknown labeled template scope: {scope}")


def compare_features(left: dict[str, Any], right: dict[str, Any]) -> dict[str, float]:
    left_edge = left.get("edge_bool")
    if left_edge is None:
        left_edge = left["edge"] > 0
    right_edge = right.get("edge_bool")
    if right_edge is None:
        right_edge = right["edge"] > 0
    template = template_similarity(left["gray"], right["gray"])
    color = hist_similarity(left["hist"], right["hist"])
    return {
        "phash": hash_similarity(left["phash"], right["phash"]),
        "edge": binary_iou(left_edge, right_edge),
        "color": color,
        "template": template,
    }


def perceptual_hash(gray: np.ndarray, hash_size: int = 8, highfreq_factor: int = 4) -> np.ndarray:
    resized = cv2.resize(gray, (hash_size * highfreq_factor, hash_size * highfreq_factor), interpolation=cv2.INTER_AREA)
    dct = cv2.dct(np.float32(resized))
    low_freq = dct[:hash_size, :hash_size]
    median = np.median(low_freq[1:, 1:])
    return low_freq > median


def hash_similarity(left_hash: np.ndarray, right_hash: np.ndarray) -> float:
    total = int(left_hash.size)
    if not total:
        return 0.0
    return clamp01(1.0 - int(np.count_nonzero(left_hash != right_hash)) / total)


def binary_iou(left: np.ndarray, right: np.ndarray) -> float:
    union = np.logical_or(left, right)
    union_count = int(np.count_nonzero(union))
    if union_count == 0:
        return 0.0
    return int(np.count_nonzero(np.logical_and(left, right))) / union_count


def hist_similarity(left_hist: np.ndarray, right_hist: np.ndarray) -> float:
    if float(left_hist.sum()) <= 0 or float(right_hist.sum()) <= 0:
        return 0.0
    score = float(cv2.compareHist(left_hist, right_hist, cv2.HISTCMP_CORREL))
    if not math.isfinite(score):
        return 0.0
    return clamp01((score + 1.0) / 2.0)


def template_similarity(left_gray: np.ndarray, right_gray: np.ndarray) -> float:
    score = float(cv2.matchTemplate(left_gray, right_gray, cv2.TM_CCOEFF_NORMED)[0, 0])
    if not math.isfinite(score):
        return 0.0
    return clamp01((score + 1.0) / 2.0)


def normalize_color(bgr: np.ndarray, mask: np.ndarray) -> np.ndarray:
    lab = cv2.cvtColor(bgr, cv2.COLOR_BGR2LAB)
    l, a, b = cv2.split(lab)
    clahe = cv2.createCLAHE(clipLimit=1.6, tileGridSize=(4, 4))
    l = clahe.apply(l)
    return cv2.cvtColor(cv2.merge([l, a, b]), cv2.COLOR_LAB2BGR)


def foreground_mask(bgr: np.ndarray) -> np.ndarray:
    color_mask = color_distance_foreground_mask(bgr)
    relaxed_color_mask = color_distance_foreground_mask(
        bgr,
        distance_threshold=RELAXED_FOREGROUND_DISTANCE_THRESHOLD,
        hsv_threshold=RELAXED_HSV_FOREGROUND_DISTANCE_THRESHOLD,
    )
    color_mask = choose_color_mask_variant(color_mask, relaxed_color_mask)
    grabcut_mask = grabcut_foreground_mask(bgr)
    return choose_foreground_mask(color_mask, grabcut_mask, bgr)


def color_distance_foreground_mask(
    bgr: np.ndarray,
    distance_threshold: float = FOREGROUND_DISTANCE_THRESHOLD,
    hsv_threshold: float = HSV_FOREGROUND_DISTANCE_THRESHOLD,
) -> np.ndarray:
    height, width = bgr.shape[:2]
    prototypes = sample_background_prototypes(bgr)
    bgr_float = bgr.astype(np.float32)
    hsv = cv2.cvtColor(bgr, cv2.COLOR_BGR2HSV)
    hsv_float = hsv.astype(np.float32)
    bgr_distances = []
    hsv_distances = []
    for bgr_prototype, hsv_prototype in prototypes:
        bgr_diff = bgr_float - bgr_prototype
        bgr_distances.append(np.sqrt(np.sum(bgr_diff * bgr_diff, axis=2)))
        hue_delta = np.abs(hsv_float[:, :, 0] - hsv_prototype[0])
        hue_delta = np.minimum(hue_delta, 180.0 - hue_delta) * 1.6
        sat_delta = (hsv_float[:, :, 1] - hsv_prototype[1]) * 0.45
        val_delta = (hsv_float[:, :, 2] - hsv_prototype[2]) * 0.45
        hsv_distances.append(np.sqrt((hue_delta * hue_delta) + (sat_delta * sat_delta) + (val_delta * val_delta)))
    bgr_distance = np.min(np.stack(bgr_distances, axis=0), axis=0)
    hsv_distance = np.min(np.stack(hsv_distances, axis=0), axis=0)
    mask = np.where(
        (bgr_distance > distance_threshold) & (hsv_distance > hsv_threshold),
        255,
        0,
    ).astype(np.uint8)
    kernel = np.ones((3, 3), dtype=np.uint8)
    mask = cv2.morphologyEx(mask, cv2.MORPH_OPEN, kernel)
    mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, kernel)
    mask = remove_ui_frame_artifacts(mask, bgr)
    return select_foreground_components(mask, width, height)


def grabcut_foreground_mask(bgr: np.ndarray) -> np.ndarray:
    height, width = bgr.shape[:2]
    if width < 12 or height < 12:
        return np.zeros((height, width), dtype=np.uint8)
    mask = np.full((height, width), cv2.GC_PR_BGD, dtype=np.uint8)
    border = max(2, int(round(min(width, height) * 0.035)))
    mask[:border, :] = cv2.GC_BGD
    mask[-border:, :] = cv2.GC_BGD
    mask[:, :border] = cv2.GC_BGD
    mask[:, -border:] = cv2.GC_BGD
    margin_x = min(width // 3, max(border + 1, int(round(width * 0.12))))
    margin_y = min(height // 3, max(border + 1, int(round(height * 0.10))))
    mask[margin_y : height - margin_y, margin_x : width - margin_x] = cv2.GC_PR_FGD
    center = (width // 2, height // 2)
    axes = (max(2, int(width * 0.30)), max(2, int(height * 0.36)))
    cv2.ellipse(mask, center, axes, 0, 0, 360, cv2.GC_PR_FGD, -1)
    bgd_model = np.zeros((1, 65), dtype=np.float64)
    fgd_model = np.zeros((1, 65), dtype=np.float64)
    try:
        cv2.setRNGSeed(0)
        cv2.grabCut(bgr, mask, None, bgd_model, fgd_model, 3, cv2.GC_INIT_WITH_MASK)
    except cv2.error:
        return np.zeros((height, width), dtype=np.uint8)
    result = np.where((mask == cv2.GC_FGD) | (mask == cv2.GC_PR_FGD), 255, 0).astype(np.uint8)
    kernel = np.ones((3, 3), dtype=np.uint8)
    result = cv2.morphologyEx(result, cv2.MORPH_OPEN, kernel)
    result = cv2.morphologyEx(result, cv2.MORPH_CLOSE, kernel)
    result = remove_ui_frame_artifacts(result, bgr)
    return select_foreground_components(result, width, height)


def choose_foreground_mask(color_mask: np.ndarray, grabcut_mask: np.ndarray, bgr: np.ndarray) -> np.ndarray:
    height, width = color_mask.shape[:2]
    color_score = foreground_mask_quality(color_mask, width, height)
    grabcut_score = foreground_mask_quality(grabcut_mask, width, height)
    color_pixels = int(np.count_nonzero(color_mask))
    grabcut_pixels = int(np.count_nonzero(grabcut_mask))
    if color_pixels and grabcut_pixels:
        overlap = int(np.count_nonzero((color_mask > 0) & (grabcut_mask > 0))) / max(1, grabcut_pixels)
        if should_keep_color_mask_over_grabcut(color_mask, grabcut_mask, color_score, grabcut_score, overlap):
            return color_mask
        expands_grabcut = color_pixels >= grabcut_pixels * 1.25
        near_quality = color_score >= grabcut_score - 1.25
        if overlap >= 0.65 and expands_grabcut and near_quality:
            return color_mask
        reverse_overlap = int(np.count_nonzero((color_mask > 0) & (grabcut_mask > 0))) / max(1, color_pixels)
        grabcut_expands_color = grabcut_pixels >= color_pixels * 1.25
        grabcut_near_quality = grabcut_score >= color_score - 0.30
        expansion_edge_density = added_region_edge_density(bgr, color_mask, grabcut_mask)
        if reverse_overlap >= 0.65 and grabcut_expands_color and grabcut_near_quality:
            if (
                expansion_edge_density >= GRABCUT_EXPANSION_MIN_EDGE_DENSITY
                and grabcut_score >= color_score + GRABCUT_EXPANSION_MIN_QUALITY_GAIN
            ):
                return grabcut_mask
            return color_mask
    if grabcut_score > color_score:
        return grabcut_mask
    return color_mask


def choose_color_mask_variant(strict_mask: np.ndarray, relaxed_mask: np.ndarray) -> np.ndarray:
    strict_pixels = int(np.count_nonzero(strict_mask))
    relaxed_pixels = int(np.count_nonzero(relaxed_mask))
    if not strict_pixels:
        return relaxed_mask
    if relaxed_pixels < strict_pixels * RELAXED_COLOR_MASK_MIN_PIXEL_GAIN:
        return strict_mask
    overlap = int(np.count_nonzero((strict_mask > 0) & (relaxed_mask > 0))) / max(1, strict_pixels)
    if overlap < RELAXED_COLOR_MASK_MIN_STRICT_OVERLAP:
        return strict_mask
    height, width = strict_mask.shape[:2]
    strict_score = foreground_mask_quality(strict_mask, width, height)
    relaxed_score = foreground_mask_quality(relaxed_mask, width, height)
    if relaxed_score < strict_score - RELAXED_COLOR_MASK_QUALITY_MARGIN:
        return strict_mask
    relaxed_bbox = mask_bbox(relaxed_mask)
    if relaxed_bbox is None:
        return strict_mask
    if relaxed_bbox["width"] >= width * 0.96 or relaxed_bbox["height"] >= height * 0.96:
        return strict_mask
    return relaxed_mask


def should_keep_color_mask_over_grabcut(
    color_mask: np.ndarray,
    grabcut_mask: np.ndarray,
    color_score: float,
    grabcut_score: float,
    overlap: float,
) -> bool:
    if overlap < 0.80 or color_score < grabcut_score - GRABCUT_SUBSET_QUALITY_MARGIN:
        return False
    color_bbox = mask_bbox(color_mask)
    grabcut_bbox = mask_bbox(grabcut_mask)
    if color_bbox is None or grabcut_bbox is None:
        return False
    color_height, color_width = color_mask.shape[:2]
    grabcut_height, grabcut_width = grabcut_mask.shape[:2]
    color_width_ratio = color_bbox["width"] / max(1, color_width)
    color_height_ratio = color_bbox["height"] / max(1, color_height)
    grabcut_width_ratio = grabcut_bbox["width"] / max(1, grabcut_width)
    grabcut_height_ratio = grabcut_bbox["height"] / max(1, grabcut_height)
    return (
        grabcut_width_ratio <= color_width_ratio * GRABCUT_SUBSET_AXIS_SHRINK_RATIO
        or grabcut_height_ratio <= color_height_ratio * GRABCUT_SUBSET_AXIS_SHRINK_RATIO
    )


def added_region_edge_density(bgr: np.ndarray, base_mask: np.ndarray, expanded_mask: np.ndarray) -> float:
    base_dilated = cv2.dilate(base_mask, np.ones((3, 3), dtype=np.uint8), iterations=1)
    added = np.where((expanded_mask > 0) & (base_dilated == 0), 255, 0).astype(np.uint8)
    added_pixels = int(np.count_nonzero(added))
    if added_pixels < max(8, added.size * 0.0005):
        return 0.0
    gray = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY)
    edge = cv2.Canny(gray, 40, 110)
    edge = cv2.dilate(edge, np.ones((3, 3), dtype=np.uint8), iterations=1)
    return int(np.count_nonzero(cv2.bitwise_and(edge, added))) / added_pixels


def foreground_mask_quality(mask: np.ndarray, width: int, height: int) -> float:
    foreground_pixels = int(np.count_nonzero(mask))
    if foreground_pixels < max(8, width * height * 0.003):
        return -10.0
    ratio = foreground_pixels / max(1, width * height)
    bbox = mask_bbox(mask)
    if bbox is None:
        return -10.0
    width_ratio = bbox["width"] / max(1, width)
    height_ratio = bbox["height"] / max(1, height)
    border = max(1, int(round(min(width, height) * 0.03)))
    border_mask = np.zeros(mask.shape, dtype=np.uint8)
    border_mask[:border, :] = 1
    border_mask[-border:, :] = 1
    border_mask[:, :border] = 1
    border_mask[:, -border:] = 1
    border_pixels = int(np.count_nonzero(border_mask))
    border_foreground = int(np.count_nonzero((mask > 0) & (border_mask > 0))) / max(1, border_pixels)
    touches_border = (
        bbox["left"] <= 1
        or bbox["top"] <= 1
        or bbox["left"] + bbox["width"] >= width - 1
        or bbox["top"] + bbox["height"] >= height - 1
    )
    score = 0.0
    if 0.035 <= ratio <= 0.65:
        score += 2.0
    else:
        score -= 1.5
    if width_ratio <= 0.92:
        score += 1.0
    else:
        score -= 1.0
    if height_ratio <= 0.92:
        score += 1.0
    else:
        score -= 1.0
    score -= border_foreground * 4.0
    if touches_border:
        score -= 0.8
    center_x = bbox["left"] + bbox["width"] * 0.5
    center_y = bbox["top"] + bbox["height"] * 0.5
    dx = abs(center_x - width * 0.5) / max(1, width)
    dy = abs(center_y - height * 0.5) / max(1, height)
    score -= (dx + dy) * 0.75
    return score


def source_mask_quality(mask: np.ndarray, bgr: np.ndarray) -> dict[str, float | bool]:
    height, width = mask.shape[:2]
    bbox = mask_bbox(mask)
    foreground_pixels = int(np.count_nonzero(mask))
    foreground_ratio = foreground_pixels / max(1, width * height)
    if bbox is None:
        return {
            "foreground_ratio": 0.0,
            "bbox_width_ratio": 0.0,
            "bbox_height_ratio": 0.0,
            "touches_border": False,
            "ui_frame_artifact_ratio": 0.0,
        }
    touches_border = (
        bbox["left"] <= 1
        or bbox["top"] <= 1
        or bbox["left"] + bbox["width"] >= width - 1
        or bbox["top"] + bbox["height"] >= height - 1
    )
    return {
        "foreground_ratio": foreground_ratio,
        "bbox_width_ratio": bbox["width"] / max(1, width),
        "bbox_height_ratio": bbox["height"] / max(1, height),
        "touches_border": touches_border,
        "ui_frame_artifact_ratio": ui_frame_artifact_ratio(mask, bgr),
    }


def labeled_template_bonus_scale(feature: dict[str, Any]) -> float:
    quality = feature.get("source_quality") or {}
    foreground_ratio = float(quality.get("foreground_ratio") or 0.0)
    bbox_width_ratio = float(quality.get("bbox_width_ratio") or 0.0)
    bbox_height_ratio = float(quality.get("bbox_height_ratio") or 0.0)
    ui_frame_ratio = float(quality.get("ui_frame_artifact_ratio") or 0.0)
    if ui_frame_ratio >= UI_FRAME_BONUS_ARTIFACT_RATIO:
        return 0.0
    if (
        foreground_ratio >= UI_FRAME_BONUS_FOREGROUND_RATIO
        and bbox_width_ratio >= 0.72
        and bbox_height_ratio >= 0.72
    ):
        return 0.0
    return 1.0


def ui_frame_artifact_ratio(mask: np.ndarray, bgr: np.ndarray) -> float:
    foreground_pixels = int(np.count_nonzero(mask))
    if not foreground_pixels:
        return 0.0
    height, width = mask.shape[:2]
    edge_artifacts = cv2.bitwise_and(ui_frame_artifact_seed(bgr), roi_edge_band_mask(height, width))
    artifacts = cv2.bitwise_and(edge_artifacts, mask)
    return int(np.count_nonzero(artifacts)) / max(1, foreground_pixels)


def remove_ui_frame_artifacts(mask: np.ndarray, bgr: np.ndarray) -> np.ndarray:
    height, width = mask.shape[:2]
    remove = cv2.bitwise_and(ui_frame_artifact_seed(bgr), roi_edge_band_mask(height, width))
    if not np.count_nonzero(remove):
        return mask
    remove = cv2.dilate(remove, np.ones((3, 3), dtype=np.uint8), iterations=1)
    edge_band = cv2.dilate(roi_edge_band_mask(height, width), np.ones((5, 5), dtype=np.uint8), iterations=1)
    remove = cv2.bitwise_and(remove, edge_band)
    cleaned = mask.copy()
    cleaned[remove > 0] = 0
    return cleaned


def ui_frame_artifact_seed(bgr: np.ndarray) -> np.ndarray:
    hsv = cv2.cvtColor(bgr, cv2.COLOR_BGR2HSV)
    saturation = hsv[:, :, 1]
    value = hsv[:, :, 2]
    seed = np.where((saturation <= 130) & (value >= 130), 255, 0).astype(np.uint8)
    return cv2.morphologyEx(seed, cv2.MORPH_CLOSE, np.ones((3, 3), dtype=np.uint8))


def roi_edge_band_mask(height: int, width: int) -> np.ndarray:
    edge_band = np.zeros((height, width), dtype=np.uint8)
    x_band = max(2, int(round(width * 0.08)))
    right_band = max(2, int(round(width * 0.22)))
    y_band = max(2, int(round(height * 0.08)))
    bottom_band = max(2, int(round(height * 0.08)))
    edge_band[:, :x_band] = 255
    edge_band[:, max(0, width - right_band) :] = 255
    edge_band[:y_band, :] = 255
    edge_band[max(0, height - bottom_band) :] = 255
    return edge_band


def sample_background_prototypes(bgr: np.ndarray) -> list[tuple[np.ndarray, np.ndarray]]:
    height, width = bgr.shape[:2]
    boxes = [
        (0, 0, max(1, int(width * 0.18)), max(1, int(height * 0.18))),
        (int(width * 0.82), 0, max(1, int(width * 0.18)), max(1, int(height * 0.18))),
        (0, int(height * 0.82), max(1, int(width * 0.18)), max(1, int(height * 0.18))),
        (int(width * 0.82), int(height * 0.82), max(1, int(width * 0.18)), max(1, int(height * 0.18))),
        (0, 0, width, max(1, int(height * 0.05))),
        (0, int(height * 0.95), width, max(1, int(height * 0.05))),
    ]
    prototypes = []
    hsv = cv2.cvtColor(bgr, cv2.COLOR_BGR2HSV)
    for left, top, box_width, box_height in boxes:
        patch = bgr[top : min(height, top + box_height), left : min(width, left + box_width)]
        hsv_patch = hsv[top : min(height, top + box_height), left : min(width, left + box_width)]
        if patch.size and hsv_patch.size:
            prototypes.append(
                (
                    np.median(patch.reshape(-1, 3), axis=0).astype(np.float32),
                    np.median(hsv_patch.reshape(-1, 3), axis=0).astype(np.float32),
                )
            )
    return prototypes or [(np.array([0, 0, 0], dtype=np.float32), np.array([0, 0, 0], dtype=np.float32))]


def select_foreground_components(mask: np.ndarray, width: int, height: int) -> np.ndarray:
    count, labels, stats, centroids = cv2.connectedComponentsWithStats(mask, 8)
    components = []
    total_area = width * height
    for label in range(1, count):
        left, top, box_width, box_height, area = stats[label]
        if area < max(14, total_area * 0.0015):
            continue
        if box_width < width * 0.06 or box_height < height * 0.06:
            continue
        center_x, center_y = centroids[label]
        dx = (center_x - width / 2) / max(1, width)
        dy = (center_y - height / 2) / max(1, height)
        center_weight = 1.0 / (1.0 + math.sqrt(dx * dx + dy * dy) * 2.2)
        touches_border = left == 0 or top == 0 or left + box_width >= width or top + box_height >= height
        touch_weight = 0.65 if touches_border else 1.0
        components.append((label, float(area) * center_weight * touch_weight))
    if not components:
        return mask
    components.sort(key=lambda item: item[1], reverse=True)
    best_score = components[0][1]
    selected = np.zeros(mask.shape, dtype=np.uint8)
    for label, score in components:
        if score >= best_score * 0.20:
            selected[labels == label] = 255
    return selected


def to_bgr_alpha(image: np.ndarray) -> tuple[np.ndarray, np.ndarray | None]:
    if image.ndim == 2:
        return cv2.cvtColor(image, cv2.COLOR_GRAY2BGR), None
    if image.shape[2] == 4:
        alpha = image[:, :, 3]
        bgr = image[:, :, :3].copy()
        bgr[alpha <= 8] = 0
        return bgr, alpha
    return image[:, :, :3], None


def alpha_mask(alpha: np.ndarray) -> np.ndarray:
    return np.where(alpha > 8, 255, 0).astype(np.uint8)


def resize_fit(bgr: np.ndarray, mask: np.ndarray, size: int) -> tuple[np.ndarray, np.ndarray]:
    height, width = bgr.shape[:2]
    scale = min(size / max(1, width), size / max(1, height))
    resized_width = max(1, int(round(width * scale)))
    resized_height = max(1, int(round(height * scale)))
    resized_bgr = cv2.resize(bgr, (resized_width, resized_height), interpolation=cv2.INTER_AREA)
    resized_mask = cv2.resize(mask, (resized_width, resized_height), interpolation=cv2.INTER_NEAREST)
    canvas_bgr = np.zeros((size, size, 3), dtype=np.uint8)
    canvas_mask = np.zeros((size, size), dtype=np.uint8)
    left = (size - resized_width) // 2
    top = (size - resized_height) // 2
    canvas_bgr[top : top + resized_height, left : left + resized_width] = resized_bgr
    canvas_mask[top : top + resized_height, left : left + resized_width] = resized_mask
    return canvas_bgr, canvas_mask


def mask_bbox(mask: np.ndarray) -> dict[str, int] | None:
    ys, xs = np.where(mask > 0)
    if not len(xs) or not len(ys):
        return None
    left = int(xs.min())
    right = int(xs.max())
    top = int(ys.min())
    bottom = int(ys.max())
    return {"left": left, "top": top, "width": right - left + 1, "height": bottom - top + 1}


def full_bbox(image: np.ndarray) -> dict[str, int]:
    return {"left": 0, "top": 0, "width": image.shape[1], "height": image.shape[0]}


def padded_bbox(bbox: dict[str, int], image_width: int, image_height: int, ratio: float) -> dict[str, int]:
    padding = max(2, int(max(bbox["width"], bbox["height"]) * ratio))
    left = max(0, bbox["left"] - padding)
    top = max(0, bbox["top"] - padding)
    right = min(image_width, bbox["left"] + bbox["width"] + padding)
    bottom = min(image_height, bbox["top"] + bbox["height"] + padding)
    return {"left": left, "top": top, "width": right - left, "height": bottom - top}


def detect_game_viewport(image_size: dict[str, int], target_aspect_ratio_text: str | None) -> dict[str, Any]:
    target_ratio = parse_aspect_ratio(target_aspect_ratio_text) or (16 / 9)
    width = image_size["width"]
    height = image_size["height"]
    ratio = width / height
    if abs(ratio - target_ratio) < 0.001:
        return {"left": 0, "top": 0, "width": width, "height": height, "source": "full_image"}
    if ratio > target_ratio:
        viewport_width = round(height * target_ratio)
        return {
            "left": max(0, round((width - viewport_width) / 2)),
            "top": 0,
            "width": viewport_width,
            "height": height,
            "source": "largest_16_9_game_content_area",
        }
    viewport_height = round(width / target_ratio)
    return {
        "left": 0,
        "top": max(0, round((height - viewport_height) / 2)),
        "width": width,
        "height": viewport_height,
        "source": "largest_16_9_game_content_area",
    }


def parse_aspect_ratio(value: str | None) -> float | None:
    if not value or ":" not in value:
        return None
    left, right = value.split(":", 1)
    try:
        width = float(left)
        height = float(right)
    except ValueError:
        return None
    return width / height if width > 0 and height > 0 else None


def roi_region_to_pixel_rect(
    roi_config: dict[str, Any], region: dict[str, Any], viewport: dict[str, Any]
) -> dict[str, int]:
    unit = roi_config.get("coordinateSpace", {}).get("unit", "normalized")
    canonical = roi_config.get("canonicalViewport", viewport)
    rect = region["rect"]
    if unit == "normalized":
        left = viewport["left"] + rect["x"] * viewport["width"]
        top = viewport["top"] + rect["y"] * viewport["height"]
        width = rect["width"] * viewport["width"]
        height = rect["height"] * viewport["height"]
    else:
        left = viewport["left"] + rect["x"] * (viewport["width"] / canonical["width"])
        top = viewport["top"] + rect["y"] * (viewport["height"] / canonical["height"])
        width = rect["width"] * (viewport["width"] / canonical["width"])
        height = rect["height"] * (viewport["height"] / canonical["height"])
    return clamp_rect(
        {
            "left": math.floor(left),
            "top": math.floor(top),
            "width": max(1, math.ceil(width)),
            "height": max(1, math.ceil(height)),
        },
        viewport,
    )


def full_image_bounds(image_width: int, image_height: int) -> dict[str, int | str]:
    return {
        "left": 0,
        "top": 0,
        "width": int(image_width),
        "height": int(image_height),
        "source": "full_image",
    }


def clamp_rect(rect: dict[str, int], viewport: dict[str, Any]) -> dict[str, int]:
    left = clamp_int(rect["left"], viewport["left"], viewport["left"] + viewport["width"] - 1)
    top = clamp_int(rect["top"], viewport["top"], viewport["top"] + viewport["height"] - 1)
    right = clamp_int(rect["left"] + rect["width"], left + 1, viewport["left"] + viewport["width"])
    bottom = clamp_int(rect["top"] + rect["height"], top + 1, viewport["top"] + viewport["height"])
    return {"left": left, "top": top, "width": right - left, "height": bottom - top}


def clamp_int(value: int, minimum: int, maximum: int) -> int:
    return min(maximum, max(minimum, value))


def extract_rect(image: np.ndarray, rect: dict[str, int]) -> np.ndarray:
    return image[rect["top"] : rect["top"] + rect["height"], rect["left"] : rect["left"] + rect["width"]]


def read_labels(labels_path: Path) -> list[LabelRow]:
    with labels_path.open("r", encoding="utf-8-sig", newline="") as handle:
        reader = csv.DictReader(handle)
        required = {"image", "side", "pokemon"}
        missing = required - set(reader.fieldnames or [])
        if missing:
            raise ValueError(f"labels.csv missing required fields: {', '.join(sorted(missing))}")
        return [
            LabelRow(
                row_index=index,
                image=(row.get("image") or "").strip(),
                side=(row.get("side") or "").strip(),
                pokemon=(row.get("pokemon") or "").strip(),
            )
            for index, row in enumerate(reader, start=2)
        ]


def resolve_image_path(image_value: str, images_dir: Path, labels_dir: Path) -> Path:
    path = Path(image_value)
    candidates = []
    if path.is_absolute():
        candidates.append(path)
    else:
        candidates.extend([images_dir / path, labels_dir / path, path])
    for candidate in candidates:
        if candidate.exists():
            return candidate
    return candidates[0]


def list_images(root: Path) -> list[Path]:
    if not root.exists():
        return []
    return sorted(path for path in root.rglob("*") if path.is_file() and path.suffix.lower() in IMAGE_EXTENSIONS)


def write_debug_roi(debug_dir: Path, image_path: Path, side_key: str, roi: np.ndarray) -> None:
    out_dir = debug_dir / safe_filename(image_path.stem)
    out_dir.mkdir(parents=True, exist_ok=True)
    encode_image(out_dir / f"{safe_filename(side_key)}.png", roi)


def write_failed_case_debug_outputs(
    output_dir: Path,
    failed_cases: list[dict[str, Any]],
    debug_entries: list[dict[str, Any]],
    roi_regions: dict[str, RoiRegion],
    roi_config_path: Path,
    scene_name: str,
    crop_mode: str,
) -> None:
    if not failed_cases:
        return
    entries_by_key = {
        (str(entry["image_path"]), entry["side_key"]): entry
        for entry in debug_entries
    }
    for index, failed_case in enumerate(failed_cases, start=1):
        image_path = Path(failed_case["resolved_image"])
        side_key = failed_case["side_key"]
        entry = entries_by_key.get((str(image_path), side_key))
        if entry is None:
            continue
        case_dir = output_dir / f"{index:03d}_{safe_filename(image_path.stem)}_{safe_filename(side_key)}_{safe_filename(failed_case['pokemon'])}"
        case_dir.mkdir(parents=True, exist_ok=True)
        write_failed_case_process_images(case_dir, entry["roi"])
        write_failed_candidate_images(
            case_dir / "candidates",
            failed_case.get("top5_candidates", []),
            roi_regions,
            roi_config_path,
            scene_name,
            crop_mode,
        )
        metadata = {
            "image": failed_case["image"],
            "resolved_image": failed_case["resolved_image"],
            "side": failed_case["side"],
            "side_key": side_key,
            "pokemon": failed_case["pokemon"],
            "predicted_top1": failed_case["top5_candidates"][0]["pokemon_id"] if failed_case.get("top5_candidates") else "",
            "crop": {
                "mode": entry.get("crop_mode", ""),
                "rectSource": entry.get("rect_source", ""),
                "rect": entry.get("rect", {}),
                "regionId": entry.get("region_id", ""),
            },
            "top5_candidates": failed_case.get("top5_candidates", []),
        }
        write_json(case_dir / "metadata.json", metadata)


def write_failed_case_process_images(case_dir: Path, roi: np.ndarray) -> None:
    bgr, alpha = to_bgr_alpha(roi)
    mask = alpha_mask(alpha) if alpha is not None else foreground_mask(bgr)
    bbox = mask_bbox(mask) or full_bbox(bgr)
    padded = padded_bbox(bbox, bgr.shape[1], bgr.shape[0], 0.06)
    bbox_overlay = bgr.copy()
    cv2.rectangle(
        bbox_overlay,
        (bbox["left"], bbox["top"]),
        (bbox["left"] + bbox["width"] - 1, bbox["top"] + bbox["height"] - 1),
        (0, 255, 255),
        2,
    )
    cv2.rectangle(
        bbox_overlay,
        (padded["left"], padded["top"]),
        (padded["left"] + padded["width"] - 1, padded["top"] + padded["height"] - 1),
        (80, 255, 80),
        1,
    )
    masked = np.zeros_like(bgr)
    masked[mask > 0] = bgr[mask > 0]
    bbox_crop = bgr[padded["top"] : padded["top"] + padded["height"], padded["left"] : padded["left"] + padded["width"]]
    feature = create_feature(roi)
    encode_image(case_dir / "01_raw_roi.png", bgr)
    encode_image(case_dir / "02_foreground_mask.png", cv2.cvtColor(mask, cv2.COLOR_GRAY2BGR))
    encode_image(case_dir / "03_masked_roi.png", masked)
    encode_image(case_dir / "04_mask_bbox_overlay.png", bbox_overlay)
    encode_image(case_dir / "05_mask_bbox_crop.png", bbox_crop)
    encode_image(case_dir / "06_feature_bgr.png", feature["bgr"])
    encode_image(case_dir / "07_feature_mask.png", cv2.cvtColor(feature["mask"], cv2.COLOR_GRAY2BGR))
    encode_image(case_dir / "08_feature_edge.png", cv2.cvtColor(feature["edge"], cv2.COLOR_GRAY2BGR))


def write_failed_candidate_images(
    output_dir: Path,
    candidates: list[dict[str, Any]],
    roi_regions: dict[str, RoiRegion],
    roi_config_path: Path,
    scene_name: str,
    crop_mode: str,
) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    for rank, candidate in enumerate(candidates[:5], start=1):
        image = candidate_debug_image(candidate, roi_regions, roi_config_path, scene_name, crop_mode)
        if image is None:
            continue
        filename = f"{rank:02d}_{safe_filename(candidate.get('pokemon_id', 'candidate'))}_{safe_filename(candidate.get('variant', ''))}.png"
        encode_image(output_dir / filename, image)


def candidate_debug_image(
    candidate: dict[str, Any],
    roi_regions: dict[str, RoiRegion],
    roi_config_path: Path,
    scene_name: str,
    crop_mode: str,
) -> np.ndarray | None:
    template_side_key = candidate.get("template_side_key") or ""
    sample_image = candidate.get("sample_image") or ""
    if template_side_key and sample_image:
        sample_path = Path(sample_image)
        side_key = normalize_side_key(template_side_key)
        region = roi_regions.get(side_key)
        if region and sample_path.exists():
            try:
                crop_info = crop_roi_with_metadata(
                    sample_path,
                    region,
                    roi_config_path,
                    scene_name=scene_name,
                    crop_mode=crop_mode,
                )
                return to_bgr_alpha(crop_info["roi"])[0]
            except (FileNotFoundError, ValueError):
                return None
    reference_path = Path(candidate.get("reference_path") or candidate.get("source_path") or "")
    if reference_path.exists():
        image = imread(reference_path)
        if image is not None:
            return to_bgr_alpha(image)[0]
    return None


def write_roi_debug_outputs(
    output_dir: Path,
    entries: list[dict[str, Any]],
    contact_sheet_limit: int,
    timing: TimingRecorder | None = None,
    skip_contact_sheet: bool = False,
    skip_quality: bool = False,
    skip_overlays: bool = False,
) -> None:
    step_start = time.perf_counter()
    if skip_contact_sheet:
        if timing:
            timing.add("write_roi_contact_sheet", 0.0, skipped=True, entry_count=len(entries))
    else:
        write_roi_contact_sheet(output_dir / "debug_roi_contact_sheet.jpg", entries, contact_sheet_limit)
        if timing:
            timing.add("write_roi_contact_sheet", time.perf_counter() - step_start, entry_count=len(entries))
    step_start = time.perf_counter()
    if skip_quality:
        if timing:
            timing.add("write_roi_quality_reports", 0.0, skipped=True, entry_count=len(entries))
    else:
        write_roi_quality_reports(output_dir, entries)
        if timing:
            timing.add("write_roi_quality_reports", time.perf_counter() - step_start, entry_count=len(entries))
    step_start = time.perf_counter()
    if skip_overlays:
        if timing:
            timing.add("write_roi_overlays", 0.0, skipped=True, image_count=len({entry["image_path"] for entry in entries}))
    else:
        write_roi_overlays(output_dir / "debug_roi_overlay", entries)
        if timing:
            timing.add("write_roi_overlays", time.perf_counter() - step_start, image_count=len({entry["image_path"] for entry in entries}))


def write_roi_quality_reports(output_dir: Path, entries: list[dict[str, Any]]) -> None:
    rows = [roi_quality_row(entry) for entry in entries]
    write_json(
        output_dir / "roi_quality.json",
        {
            "count": len(rows),
            "rows": rows,
            "summary": summarize_roi_quality(rows),
        },
    )
    csv_path = output_dir / "roi_quality.csv"
    csv_path.parent.mkdir(parents=True, exist_ok=True)
    fieldnames = [
        "image",
        "side",
        "region_id",
        "image_width",
        "image_height",
        "rect_left",
        "rect_top",
        "rect_width",
        "rect_height",
        "crop_mode",
        "rect_source",
        "foreground_ratio",
        "foreground_bbox_left",
        "foreground_bbox_top",
        "foreground_bbox_width",
        "foreground_bbox_height",
        "foreground_bbox_width_ratio",
        "foreground_bbox_height_ratio",
        "foreground_touches_border",
    ]
    with csv_path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        for row in rows:
            writer.writerow(row)


def roi_quality_row(entry: dict[str, Any]) -> dict[str, Any]:
    roi = entry["roi"]
    bgr, alpha = to_bgr_alpha(roi)
    mask = alpha_mask(alpha) if alpha is not None else foreground_mask(bgr)
    bbox = mask_bbox(mask)
    rect = entry["rect"]
    width = int(roi.shape[1])
    height = int(roi.shape[0])
    foreground_pixels = int(np.count_nonzero(mask))
    foreground_ratio = foreground_pixels / max(1, width * height)
    if bbox is None:
        bbox = {"left": 0, "top": 0, "width": 0, "height": 0}
    touches_border = (
        bbox["width"] > 0
        and bbox["height"] > 0
        and (
            bbox["left"] <= 1
            or bbox["top"] <= 1
            or bbox["left"] + bbox["width"] >= width - 1
            or bbox["top"] + bbox["height"] >= height - 1
        )
    )
    return {
        "image": str(entry["image_path"]),
        "side": entry["side_key"],
        "region_id": entry["region_id"],
        "image_width": entry["image_width"],
        "image_height": entry["image_height"],
        "rect_left": rect["left"],
        "rect_top": rect["top"],
        "rect_width": rect["width"],
        "rect_height": rect["height"],
        "crop_mode": entry.get("crop_mode", ""),
        "rect_source": entry.get("rect_source", ""),
        "foreground_ratio": round_float(foreground_ratio),
        "foreground_bbox_left": bbox["left"],
        "foreground_bbox_top": bbox["top"],
        "foreground_bbox_width": bbox["width"],
        "foreground_bbox_height": bbox["height"],
        "foreground_bbox_width_ratio": round_float(bbox["width"] / max(1, width)),
        "foreground_bbox_height_ratio": round_float(bbox["height"] / max(1, height)),
        "foreground_touches_border": touches_border,
    }


def summarize_roi_quality(rows: list[dict[str, Any]]) -> dict[str, Any]:
    if not rows:
        return {}
    foreground_ratios = [row["foreground_ratio"] for row in rows]
    width_ratios = [row["foreground_bbox_width_ratio"] for row in rows]
    height_ratios = [row["foreground_bbox_height_ratio"] for row in rows]
    return {
        "foreground_ratio_min": round_float(min(foreground_ratios)),
        "foreground_ratio_max": round_float(max(foreground_ratios)),
        "foreground_ratio_mean": round_float(sum(foreground_ratios) / len(foreground_ratios)),
        "foreground_bbox_width_ratio_mean": round_float(sum(width_ratios) / len(width_ratios)),
        "foreground_bbox_height_ratio_mean": round_float(sum(height_ratios) / len(height_ratios)),
        "foreground_touches_border_count": sum(int(bool(row["foreground_touches_border"])) for row in rows),
    }


def write_roi_overlays(output_dir: Path, entries: list[dict[str, Any]]) -> None:
    if not entries:
        return
    output_dir.mkdir(parents=True, exist_ok=True)
    grouped: dict[Path, list[dict[str, Any]]] = {}
    for entry in entries:
        grouped.setdefault(entry["image_path"], []).append(entry)
    for image_path, image_entries in grouped.items():
        image = imread(image_path)
        if image is None:
            continue
        bgr, _ = to_bgr_alpha(image)
        overlay = bgr.copy()
        for entry in image_entries:
            rect = entry["rect"]
            color = (80, 220, 80) if entry["side_key"].startswith("own") else (80, 120, 255)
            left = rect["left"]
            top = rect["top"]
            right = rect["left"] + rect["width"]
            bottom = rect["top"] + rect["height"]
            cv2.rectangle(overlay, (left, top), (right, bottom), color, 4)
            cv2.putText(
                overlay,
                entry["side_key"],
                (left, max(24, top - 8)),
                cv2.FONT_HERSHEY_SIMPLEX,
                0.8,
                color,
                2,
                cv2.LINE_AA,
            )
        encode_image(output_dir / f"{safe_filename(image_path.stem)}.jpg", overlay, quality=92)


def write_predictions_csv(path: Path, predictions: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fieldnames = [
        "image",
        "side",
        "pokemon",
        "top1",
        "top3",
        "top5",
        "predicted_top1",
        "top1_score",
        "top1_phash",
        "top1_edge",
        "top1_color",
        "top1_template",
        "top5_candidates_json",
    ]
    with path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        for prediction in predictions:
            candidates = prediction["top5_candidates"]
            top1 = candidates[0] if candidates else {}
            writer.writerow(csv_row_for_prediction(prediction, top1, candidates))


def write_failed_cases_csv(path: Path, failed_cases: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fieldnames = [
        "image",
        "side",
        "true_pokemon",
        "predicted_top1",
        "top1_score",
        "top1_phash",
        "top1_edge",
        "top1_color",
        "top1_template",
        "top5_candidates_json",
    ]
    with path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        for prediction in failed_cases:
            candidates = prediction["top5_candidates"]
            top1 = candidates[0] if candidates else {}
            row = csv_row_for_prediction(prediction, top1, candidates)
            writer.writerow(
                {
                    "image": row["image"],
                    "side": row["side"],
                    "true_pokemon": row["pokemon"],
                    "predicted_top1": row["predicted_top1"],
                    "top1_score": row["top1_score"],
                    "top1_phash": row["top1_phash"],
                    "top1_edge": row["top1_edge"],
                    "top1_color": row["top1_color"],
                    "top1_template": row["top1_template"],
                    "top5_candidates_json": row["top5_candidates_json"],
                }
            )


def build_method_metrics(
    method_counters: dict[str, dict[str, int]],
    total: int,
    weights: dict[str, float],
    side_weights: dict[str, dict[str, float]],
    template_summary: dict[str, Any],
) -> dict[str, Any]:
    methods = []
    for method in method_counters:
        counters = method_counters[method]
        methods.append(
            {
                "method": method,
                "top1_accuracy": safe_ratio(counters["correct_top1"], total),
                "top3_accuracy": safe_ratio(counters["correct_top3"], total),
                "top5_accuracy": safe_ratio(counters["correct_top5"], total),
                "correct_top1": counters["correct_top1"],
                "correct_top3": counters["correct_top3"],
                "correct_top5": counters["correct_top5"],
                "total": total,
            }
        )
    return {
        "methods": methods,
        "combined_weights": weights,
        "side_weights": side_weights,
        "templates": template_summary,
    }


def write_method_metrics_csv(path: Path, method_metrics: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fieldnames = [
        "method",
        "top1_accuracy",
        "top3_accuracy",
        "top5_accuracy",
        "correct_top1",
        "correct_top3",
        "correct_top5",
        "total",
    ]
    with path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        for row in method_metrics["methods"]:
            writer.writerow(row)


def build_side_metrics(predictions: list[dict[str, Any]]) -> dict[str, Any]:
    groups: dict[str, list[dict[str, Any]]] = {"all": list(predictions)}
    for prediction in predictions:
        side_key = normalize_side_key(prediction["side_key"])
        side_prefix = side_key.split(".", 1)[0]
        groups.setdefault(side_prefix, []).append(prediction)
        groups.setdefault(side_key, []).append(prediction)
    rows = [side_metric_row(name, groups[name]) for name in sorted(groups, key=side_metric_sort_key)]
    return {"groups": rows}


def side_metric_sort_key(name: str) -> tuple[int, str]:
    if name == "all":
        return (0, name)
    if name in {"own", "opponent"}:
        return (1, name)
    return (2, name)


def side_metric_row(group: str, predictions: list[dict[str, Any]]) -> dict[str, Any]:
    total = len(predictions)
    correct_top1 = sum(int(bool(prediction.get("top1"))) for prediction in predictions)
    correct_top3 = sum(int(bool(prediction.get("top3"))) for prediction in predictions)
    correct_top5 = sum(int(bool(prediction.get("top5"))) for prediction in predictions)
    return {
        "group": group,
        "total": total,
        "top1_accuracy": safe_ratio(correct_top1, total),
        "top3_accuracy": safe_ratio(correct_top3, total),
        "top5_accuracy": safe_ratio(correct_top5, total),
        "correct_top1": correct_top1,
        "correct_top3": correct_top3,
        "correct_top5": correct_top5,
    }


def write_side_metrics_csv(path: Path, side_metrics: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fieldnames = [
        "group",
        "total",
        "top1_accuracy",
        "top3_accuracy",
        "top5_accuracy",
        "correct_top1",
        "correct_top3",
        "correct_top5",
    ]
    with path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        for row in side_metrics["groups"]:
            writer.writerow(row)


def generate_weight_grid(grid_step: float, min_weight: float) -> list[dict[str, float]]:
    if grid_step <= 0 or grid_step > 1:
        raise ValueError("--grid-step must be > 0 and <= 1.")
    if min_weight < 0 or min_weight >= 1:
        raise ValueError("--min-weight must be >= 0 and < 1.")
    units = round(1.0 / grid_step)
    if not math.isclose(units * grid_step, 1.0, rel_tol=1e-9, abs_tol=1e-9):
        raise ValueError("--grid-step must evenly divide 1.0, for example 0.2, 0.1, 0.05.")
    min_units = math.ceil(min_weight / grid_step - 1e-9)
    candidates = []
    for phash_units in range(min_units, units + 1):
        for edge_units in range(min_units, units - phash_units + 1):
            for color_units in range(min_units, units - phash_units - edge_units + 1):
                template_units = units - phash_units - edge_units - color_units
                if template_units < min_units:
                    continue
                weights = {
                    "phash": phash_units / units,
                    "edge": edge_units / units,
                    "color": color_units / units,
                    "template": template_units / units,
                }
                candidates.append(weights)
    return candidates


def make_group_folds(cases: list[dict[str, Any]], requested_folds: int, seed: int) -> list[set[str]]:
    groups = sorted({case["group_key"] for case in cases})
    if len(groups) < 2:
        raise ValueError("Cross validation needs labels from at least two distinct images.")
    fold_count = min(max(2, requested_folds), len(groups))
    rng = random.Random(seed)
    rng.shuffle(groups)
    folds = [set() for _ in range(fold_count)]
    for index, group in enumerate(groups):
        folds[index % fold_count].add(group)
    return [fold for fold in folds if fold]


def weight_result_sort_key(result: dict[str, Any]) -> tuple[float, float, float, float]:
    weights = result["weights"]
    # Prefer accurate Top1, then Top3, then Top5, then less extreme weight distributions.
    entropy = -sum(value * math.log(value) for value in weights.values() if value > 0)
    return (
        result["top1_accuracy"],
        result["top3_accuracy"],
        result["top5_accuracy"],
        entropy,
    )


def write_weight_search_csv(path: Path, results: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fieldnames = [
        "rank",
        "top1_accuracy",
        "top3_accuracy",
        "top5_accuracy",
        "correct_top1",
        "correct_top3",
        "correct_top5",
        "total",
        "phash",
        "edge",
        "color",
        "template",
    ]
    sorted_results = sorted(results, key=weight_result_sort_key, reverse=True)
    with path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        for index, result in enumerate(sorted_results, start=1):
            row = {
                "rank": index,
                "top1_accuracy": result["top1_accuracy"],
                "top3_accuracy": result["top3_accuracy"],
                "top5_accuracy": result["top5_accuracy"],
                "correct_top1": result["correct_top1"],
                "correct_top3": result["correct_top3"],
                "correct_top5": result["correct_top5"],
                "total": result["total"],
            }
            row.update(result["weights"])
            writer.writerow(row)


def write_cross_validation_csv(path: Path, fold_results: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fieldnames = [
        "fold",
        "train_total",
        "validation_total",
        "validation_top1_accuracy",
        "validation_top3_accuracy",
        "validation_top5_accuracy",
        "validation_correct_top1",
        "validation_correct_top3",
        "validation_correct_top5",
        "phash",
        "edge",
        "color",
        "template",
        "validation_groups_json",
    ]
    with path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        for fold in fold_results:
            validation = fold["validation"]
            weights = fold["best_train"]["weights"]
            writer.writerow(
                {
                    "fold": fold["fold"],
                    "train_total": fold["train_total"],
                    "validation_total": fold["validation_total"],
                    "validation_top1_accuracy": validation["top1_accuracy"],
                    "validation_top3_accuracy": validation["top3_accuracy"],
                    "validation_top5_accuracy": validation["top5_accuracy"],
                    "validation_correct_top1": validation["correct_top1"],
                    "validation_correct_top3": validation["correct_top3"],
                    "validation_correct_top5": validation["correct_top5"],
                    "phash": weights["phash"],
                    "edge": weights["edge"],
                    "color": weights["color"],
                    "template": weights["template"],
                    "validation_groups_json": json.dumps(fold["validation_groups"], ensure_ascii=False),
                }
            )


def write_roi_calibration_csv(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fieldnames = [
        "side",
        "rank",
        "label_count",
        "top1_accuracy",
        "top3_accuracy",
        "top5_accuracy",
        "correct_top1",
        "correct_top3",
        "correct_top5",
        "mean_reciprocal_rank_at_top_k",
        "mean_top1_score",
        "dx",
        "dy",
        "padding",
        "rect_x",
        "rect_y",
        "rect_width",
        "rect_height",
        "region_id",
    ]
    with path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        for row in rows:
            rect = row["rect"]
            writer.writerow(
                {
                    "side": row["side"],
                    "rank": row["rank"],
                    "label_count": row["label_count"],
                    "top1_accuracy": row["top1_accuracy"],
                    "top3_accuracy": row["top3_accuracy"],
                    "top5_accuracy": row["top5_accuracy"],
                    "correct_top1": row["correct_top1"],
                    "correct_top3": row["correct_top3"],
                    "correct_top5": row["correct_top5"],
                    "mean_reciprocal_rank_at_top_k": row["mean_reciprocal_rank_at_top_k"],
                    "mean_top1_score": row["mean_top1_score"],
                    "dx": row["dx"],
                    "dy": row["dy"],
                    "padding": row["padding"],
                    "rect_x": rect["x"],
                    "rect_y": rect["y"],
                    "rect_width": rect["width"],
                    "rect_height": rect["height"],
                    "region_id": row["region_id"],
                }
            )


def write_roi_contact_sheet(
    path: Path,
    entries: list[dict[str, Any]],
    limit: int,
) -> None:
    if not entries or limit <= 0:
        return
    cell_width = 180
    cell_height = 170
    columns = 4
    selected = entries[:limit]
    rows = math.ceil(len(selected) / columns)
    sheet = np.full((rows * cell_height, columns * cell_width, 3), 245, dtype=np.uint8)
    for index, entry in enumerate(selected):
        image_path = entry["image_path"]
        side_key = entry["side_key"]
        roi = entry["roi"]
        row = index // columns
        column = index % columns
        cell_left = column * cell_width
        cell_top = row * cell_height
        bgr, _ = to_bgr_alpha(roi)
        scale = min((cell_width - 16) / max(1, bgr.shape[1]), (cell_height - 36) / max(1, bgr.shape[0]))
        resized = cv2.resize(
            bgr,
            (max(1, int(round(bgr.shape[1] * scale))), max(1, int(round(bgr.shape[0] * scale)))),
            interpolation=cv2.INTER_AREA,
        )
        image_left = cell_left + (cell_width - resized.shape[1]) // 2
        image_top = cell_top + 6
        sheet[image_top : image_top + resized.shape[0], image_left : image_left + resized.shape[1]] = resized
        label = f"{image_path.stem} {side_key}"
        cv2.putText(
            sheet,
            label[:28],
            (cell_left + 6, cell_top + cell_height - 10),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.42,
            (25, 25, 25),
            1,
            cv2.LINE_AA,
        )
    path.parent.mkdir(parents=True, exist_ok=True)
    encode_image(path, sheet, quality=92)


def csv_row_for_prediction(
    prediction: dict[str, Any],
    top1: dict[str, Any],
    candidates: list[dict[str, Any]],
) -> dict[str, Any]:
    scores = top1.get("scores", {})
    return {
        "image": prediction["image"],
        "side": prediction["side"],
        "pokemon": prediction.get("pokemon", ""),
        "top1": prediction.get("top1", ""),
        "top3": prediction.get("top3", ""),
        "top5": prediction.get("top5", ""),
        "predicted_top1": top1.get("pokemon_id", ""),
        "top1_score": top1.get("score", ""),
        "top1_phash": scores.get("phash", ""),
        "top1_edge": scores.get("edge", ""),
        "top1_color": scores.get("color", ""),
        "top1_template": scores.get("template", ""),
        "top5_candidates_json": json.dumps(candidates, ensure_ascii=False),
    }


def summarize_templates(templates: list[TemplateFeature]) -> dict[str, Any]:
    by_variant: dict[str, int] = {}
    pokemon_ids = set()
    for template in templates:
        by_variant[template.variant] = by_variant.get(template.variant, 0) + 1
        pokemon_ids.add(template.pokemon_id)
    return {"total": len(templates), "pokemon_count": len(pokemon_ids), "by_variant": by_variant}


def parse_weights(text: str) -> dict[str, float]:
    weights = dict(DEFAULT_WEIGHTS)
    if text:
        weights.clear()
        for part in text.split(","):
            if not part.strip():
                continue
            key, _, value = part.partition("=")
            key = key.strip()
            if key not in DEFAULT_WEIGHTS:
                raise ValueError(f"Unknown score weight: {key}")
            weights[key] = float(value)
    missing = set(DEFAULT_WEIGHTS) - set(weights)
    if missing:
        raise ValueError(f"Missing score weights: {', '.join(sorted(missing))}")
    total = sum(weights.values())
    if total <= 0:
        raise ValueError("Score weights must sum to a positive value.")
    return {key: value / total for key, value in weights.items()}


def parse_side_weights(entries: Iterable[str] | None) -> dict[str, dict[str, float]]:
    overrides: dict[str, dict[str, float]] = {}
    for entry in DEFAULT_SIDE_WEIGHT_OVERRIDES if entries is None else entries:
        selector, separator, weights_text = entry.partition(":")
        if not separator:
            raise ValueError(
                "--side-weights must use '<side>:<weights>', "
                "for example opponent:phash=0,edge=0.4,color=0.2,template=0.4"
            )
        selector = normalize_side_weight_selector(selector)
        if selector in overrides:
            raise ValueError(f"Duplicate --side-weights selector: {selector}")
        overrides[selector] = parse_weights(weights_text)
    return overrides


def normalize_side_weight_selector(selector: str) -> str:
    normalized = normalize_side_key(selector)
    if normalized in {"own", "opponent"}:
        return normalized
    if re.fullmatch(r"(own|opponent)\.slot[0-5]", normalized):
        return normalized
    raise ValueError(
        "Side weight selector must be 'own', 'opponent', or a slot such as 'opponent.slot4': "
        f"{selector}"
    )


def weights_for_side(
    side_key: str,
    default_weights: dict[str, float],
    side_weights: dict[str, dict[str, float]],
) -> dict[str, float]:
    normalized = normalize_side_key(side_key)
    if normalized in side_weights:
        return side_weights[normalized]
    side_prefix = normalized.split(".", 1)[0]
    return side_weights.get(side_prefix, default_weights)


def parse_float_list(text: str, argument_name: str) -> list[float]:
    values = []
    for part in text.split(","):
        part = part.strip()
        if not part:
            continue
        try:
            values.append(float(part))
        except ValueError as exc:
            raise ValueError(f"{argument_name} contains a non-numeric value: {part}") from exc
    if not values:
        raise ValueError(f"{argument_name} must contain at least one number.")
    return sorted(set(values))


def normalize_pokemon_key(value: str) -> str:
    text = value.strip().lower()
    text = text.replace("Ｑ", "q").replace("♀", "f").replace("♂", "m")
    text = re.sub(r"[^0-9a-z\u4e00-\u9fff]+", "", text)
    if text.isdigit():
        return str(int(text))
    return text


def safe_filename(value: str) -> str:
    return re.sub(r"[^0-9A-Za-z_.\-\u4e00-\u9fff]+", "_", value).strip("_") or "item"


def normalized_path_text(path: Path) -> str:
    return stable_path_text(path).lower()


def stable_path_text(path: Path) -> str:
    try:
        resolved = path.resolve()
    except OSError:
        return str(path).replace("\\", "/")
    try:
        return resolved.relative_to(PROJECT_ROOT).as_posix()
    except ValueError:
        return str(resolved).replace("\\", "/")


def cache_path_text(value: str) -> str:
    text = value.replace("\\", "/")
    path = Path(text)
    if path.is_absolute():
        try:
            return path.resolve().relative_to(PROJECT_ROOT).as_posix()
        except (OSError, ValueError):
            return text
    return text


def imread(path: Path) -> np.ndarray | None:
    try:
        data = np.fromfile(str(path), dtype=np.uint8)
    except OSError:
        return None
    if data.size == 0:
        return None
    return cv2.imdecode(data, cv2.IMREAD_UNCHANGED)


def encode_image(path: Path, image: np.ndarray, quality: int | None = None) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    params = []
    if quality is not None and path.suffix.lower() in {".jpg", ".jpeg"}:
        params = [int(cv2.IMWRITE_JPEG_QUALITY), int(quality)]
    ok, encoded = cv2.imencode(path.suffix or ".png", image, params)
    if not ok:
        raise RuntimeError(f"Failed to encode image: {path}")
    encoded.tofile(str(path))


def read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def prepare_output_dir(output_dir: Path, clear_output: bool) -> None:
    if clear_output and output_dir.exists():
        shutil.rmtree(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)


def safe_ratio(numerator: int, denominator: int) -> float:
    return numerator / denominator if denominator else 0.0


def clamp01(value: float) -> float:
    if not math.isfinite(value):
        return 0.0
    return min(1.0, max(0.0, value))


def round_float(value: float) -> float:
    return round(float(value), 6)


if __name__ == "__main__":
    raise SystemExit(main())
