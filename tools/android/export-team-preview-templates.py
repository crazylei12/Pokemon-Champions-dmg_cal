#!/usr/bin/env python3
"""Export PC-validated TEAM_PREVIEW features for the offline Android matcher."""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import math
import struct
import sys
from pathlib import Path
from types import SimpleNamespace
from typing import Any, Iterable

import numpy as np


PROJECT_ROOT = Path(__file__).resolve().parents[2]
PIPELINE_PATH = PROJECT_ROOT / "tools/recognition/pokemon-vision-pipeline.py"
DEFAULT_OUTPUT = PROJECT_ROOT / "src/data/recognition/android/team-preview-templates-v2.bin"
DEFAULT_METADATA_OUTPUT = PROJECT_ROOT / "src/data/recognition/android/team-preview-templates-v2.json"
MAGIC = b"PTVFEAT2"
FORMAT_VERSION = 2
COARSE_SIZE = 16
COARSE_SPECIES_TOP_K = 24
HIST_SIZE = 24 * 16
DEFAULT_WEIGHTS = (0.22, 0.24, 0.24, 0.30)
OPPONENT_WEIGHTS = (0.0, 0.40, 0.20, 0.40)
LABELED_TEMPLATE_BONUS = 0.04
CORPORA = (
    (PROJECT_ROOT / "dataset/labels.csv", PROJECT_ROOT / "dataset/real_train"),
    (PROJECT_ROOT / "dataset/0707/labels.csv", PROJECT_ROOT / "dataset/0707"),
)


def load_pipeline():
    spec = importlib.util.spec_from_file_location("pokemon_vision_pipeline", PIPELINE_PATH)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Cannot load recognition pipeline: {PIPELINE_PATH}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--metadata-output", type=Path, default=DEFAULT_METADATA_OUTPUT)
    parser.add_argument(
        "--verify",
        action="store_true",
        help="Run leave-one-image-out Top1/Top3/Top5 verification after export.",
    )
    parser.add_argument("--refresh-template-cache", action="store_true")
    return parser.parse_args()


def normalize_entity_key(value: str) -> str:
    return "".join(character.lower() for character in value if character.isalnum())


def load_species_entities() -> dict[str, dict[str, str]]:
    entries = json.loads((PROJECT_ROOT / "src/data/localization/zh-Hans.json").read_text(encoding="utf-8"))
    entities: dict[str, dict[str, str]] = {}
    for entry in entries:
        if entry.get("entityType") != "species":
            continue
        localized = entry.get("localizedNames", {}).get("zh-Hans") or []
        entity = {
            "canonicalId": entry["canonicalId"],
            "showdownId": entry["showdownId"],
            "displayName": localized[0] if localized else entry["showdownId"],
        }
        keys = {entry["showdownId"], entry.get("englishName", ""), entry["canonicalId"].split(".", 1)[-1]}
        keys.update(entry.get("aliases") or [])
        for key in keys:
            normalized = normalize_entity_key(key)
            if normalized:
                entities[normalized] = entity
    return entities


def catalog_templates(pipeline: Any, refresh_template_cache: bool) -> list[Any]:
    args = SimpleNamespace(
        references=PROJECT_ROOT / "references",
        no_augment=True,
        augment_count=0,
        seed=12345,
        template_cache_dir=PROJECT_ROOT / "src/data/recognition/template-cache",
        no_template_cache=False,
        refresh_template_cache=refresh_template_cache,
    )
    return pipeline.build_templates(args)


def labeled_templates(pipeline: Any) -> tuple[list[Any], list[Any]]:
    roi_regions = pipeline.load_team_preview_safe_zone_regions(
        PROJECT_ROOT / "src/data/recognition/team-preview.safe-zone-roi.zh-Hans.v1.json"
    )
    templates: list[Any] = []
    labels: list[Any] = []
    for labels_path, images_dir in CORPORA:
        corpus_labels = pipeline.read_labels(labels_path)
        labels.extend(corpus_labels)
        templates.extend(
            pipeline.build_labeled_roi_templates(
                corpus_labels,
                images_dir,
                labels_path.parent,
                roi_regions,
                PROJECT_ROOT / "src/data/recognition/roi.pokemon-champions.zh-Hans.v1.json",
                crop_mode="team-preview-safe-zone",
            )
        )
    return templates, labels


def stable_project_path(path: Path | str | None) -> str:
    if not path:
        return ""
    candidate = Path(path)
    try:
        return candidate.resolve().relative_to(PROJECT_ROOT).as_posix()
    except (OSError, ValueError):
        return candidate.as_posix()


def write_text(stream: Any, value: str) -> None:
    encoded = value.encode("utf-8")
    if len(encoded) > 0xFFFF:
        raise ValueError("Template metadata string is too long for the binary format.")
    stream.write(struct.pack(">H", len(encoded)))
    stream.write(encoded)


def edge_bits(feature: dict[str, Any]) -> bytes:
    edge = feature.get("edge_bool")
    if edge is None:
        edge = feature["edge"] > 0
    return np.packbits(np.asarray(edge, dtype=np.uint8).reshape(-1), bitorder="big").tobytes()


def phash_bits(feature: dict[str, Any]) -> bytes:
    return np.packbits(np.asarray(feature["phash"], dtype=np.uint8).reshape(-1), bitorder="big").tobytes()


def coarse_gray(feature: dict[str, Any]) -> np.ndarray:
    gray = np.asarray(feature["gray"], dtype=np.uint8)
    return load_pipeline_cached.cv2.resize(
        gray,
        (COARSE_SIZE, COARSE_SIZE),
        interpolation=load_pipeline_cached.cv2.INTER_AREA,
    )


def entity_for_template(template: Any, entities: dict[str, dict[str, str]]) -> dict[str, str]:
    entity = entities.get(normalize_entity_key(template.pokemon_id))
    if entity is None:
        for alias in template.aliases:
            entity = entities.get(normalize_entity_key(alias))
            if entity is not None:
                break
    if entity is None:
        raise KeyError(f"No localization entity for template Pokemon id: {template.pokemon_id}")
    return entity


def template_metadata(template: Any, entities: dict[str, dict[str, str]]) -> dict[str, Any]:
    entity = entity_for_template(template, entities)
    side_key = template.side_key or ""
    is_labeled = bool(side_key)
    source_path = stable_project_path(template.source_path)
    is_shiny = (not is_labeled) and "shiny" in source_path.lower()
    return {
        **entity,
        "pokemonId": normalize_entity_key(template.pokemon_id),
        "sideKey": side_key,
        "sampleImage": stable_project_path(template.sample_image),
        "source": "USER_LABELED_SCREENSHOT" if is_labeled else "CATALOG_REFERENCE",
        "visualVariant": "screen" if is_labeled else ("shiny" if is_shiny else "regular"),
        "isShiny": is_shiny,
        "sourcePath": source_path,
    }


def write_binary(path: Path, templates: Iterable[Any], entities: dict[str, dict[str, str]], feature_size: int) -> int:
    templates = list(templates)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("wb") as stream:
        stream.write(MAGIC)
        stream.write(struct.pack(">IIIII", FORMAT_VERSION, feature_size, COARSE_SIZE, HIST_SIZE, len(templates)))
        stream.write(struct.pack(">9f", *(DEFAULT_WEIGHTS + OPPONENT_WEIGHTS + (LABELED_TEMPLATE_BONUS,))))
        for template in templates:
            metadata = template_metadata(template, entities)
            for key in (
                "canonicalId",
                "showdownId",
                "displayName",
                "pokemonId",
                "sideKey",
                "sampleImage",
                "source",
                "visualVariant",
                "sourcePath",
            ):
                write_text(stream, str(metadata[key]))
            stream.write(struct.pack(">B", 1 if metadata["isShiny"] else 0))
            bonus_scale = float(load_pipeline_cached.labeled_template_bonus_scale(template.feature)) if metadata["sideKey"] else 0.0
            stream.write(struct.pack(">f", bonus_scale))
            gray = np.asarray(template.feature["gray"], dtype=np.uint8).reshape(feature_size, feature_size)
            stream.write(gray.tobytes(order="C"))
            stream.write(coarse_gray(template.feature).tobytes(order="C"))
            stream.write(edge_bits(template.feature))
            hist = np.asarray(template.feature["hist"], dtype=">f4").reshape(-1)
            if hist.size != HIST_SIZE:
                raise ValueError(f"Unexpected histogram size: {hist.size}")
            stream.write(hist.tobytes(order="C"))
            packed_phash = phash_bits(template.feature)
            if len(packed_phash) != 8:
                raise ValueError(f"Unexpected pHash size: {len(packed_phash)}")
            stream.write(packed_phash)
    return len(templates)


def query_rows(pipeline: Any) -> list[tuple[Any, Path, Path]]:
    rows: list[tuple[Any, Path, Path]] = []
    for labels_path, images_dir in CORPORA:
        for label in pipeline.read_labels(labels_path):
            rows.append((label, images_dir, labels_path.parent))
    return rows


def verify_templates(pipeline: Any, templates: list[Any]) -> dict[str, Any]:
    roi_regions = pipeline.load_team_preview_safe_zone_regions(
        PROJECT_ROOT / "src/data/recognition/team-preview.safe-zone-roi.zh-Hans.v1.json"
    )
    correct = {1: 0, 3: 0, 5: 0}
    failures: list[dict[str, Any]] = []
    coarse_top3_order_preserved = 0
    coarse_top3_set_preserved = 0
    refined_counts: list[int] = []
    template_coarse = {id(template): coarse_gray(template.feature) for template in templates}
    rows = query_rows(pipeline)
    for index, (label, images_dir, labels_dir) in enumerate(rows, start=1):
        image_path = pipeline.resolve_image_path(label.image, images_dir, labels_dir)
        roi = pipeline.crop_roi(
            image_path,
            roi_regions[pipeline.normalize_side_key(label.side)],
            PROJECT_ROOT / "src/data/recognition/roi.pokemon-champions.zh-Hans.v1.json",
            crop_mode="team-preview-safe-zone",
        )
        feature = pipeline.create_feature(roi)
        weights = OPPONENT_WEIGHTS if label.side.startswith("opponent.") else DEFAULT_WEIGHTS
        candidates = pipeline.rank_candidates_from_feature(
            feature,
            templates,
            dict(zip(("phash", "edge", "color", "template"), weights)),
            5,
            "combined",
            excluded_sample_image=pipeline.normalized_path_text(image_path),
            query_side_key=label.side,
            labeled_template_scope="same-side",
            labeled_template_bonus=LABELED_TEMPLATE_BONUS,
        )
        expected = pipeline.normalize_pokemon_key(label.pokemon)
        ids = [candidate["pokemon_id"] for candidate in candidates]
        query_side = pipeline.normalize_side_key(label.side)
        query_side_name = query_side.split(".", 1)[0]
        coarse_weights = dict(zip(("phash", "edge", "color", "template"), weights))
        coarse_best: dict[str, float] = {}
        excluded_image = pipeline.normalized_path_text(image_path)
        query_coarse = coarse_gray(feature)
        for template in templates:
            if template.sample_image == excluded_image:
                continue
            if template.side_key and pipeline.normalize_side_key(template.side_key).split(".", 1)[0] != query_side_name:
                continue
            coarse_score = (
                pipeline.hash_similarity(feature["phash"], template.feature["phash"]) * coarse_weights["phash"]
                + pipeline.hist_similarity(feature["hist"], template.feature["hist"]) * coarse_weights["color"]
                + pipeline.template_similarity(query_coarse, template_coarse[id(template)]) * coarse_weights["template"]
                + pipeline.labeled_template_source_bonus(
                    template,
                    feature,
                    query_side,
                    LABELED_TEMPLATE_BONUS,
                    "combined",
                )
            )
            current = coarse_best.get(template.pokemon_id)
            if current is None or coarse_score > current:
                coarse_best[template.pokemon_id] = coarse_score
        shortlist = {
            pokemon_id
            for pokemon_id, _ in sorted(coarse_best.items(), key=lambda item: item[1], reverse=True)[:COARSE_SPECIES_TOP_K]
        }
        refined_templates = [template for template in templates if template.pokemon_id in shortlist]
        refined_counts.append(
            sum(
                1
                for template in refined_templates
                if template.sample_image != excluded_image
                and (
                    not template.side_key
                    or pipeline.normalize_side_key(template.side_key).split(".", 1)[0] == query_side_name
                )
            )
        )
        coarse_candidates = pipeline.rank_candidates_from_feature(
            feature,
            refined_templates,
            coarse_weights,
            5,
            "combined",
            excluded_sample_image=excluded_image,
            query_side_key=label.side,
            labeled_template_scope="same-side",
            labeled_template_bonus=LABELED_TEMPLATE_BONUS,
        )
        coarse_ids = [candidate["pokemon_id"] for candidate in coarse_candidates]
        coarse_top3_order_preserved += int(coarse_ids[:3] == ids[:3])
        coarse_top3_set_preserved += int(set(coarse_ids[:3]) == set(ids[:3]))
        for k in correct:
            correct[k] += int(expected in ids[:k])
        if expected not in ids[:3]:
            failures.append({"image": stable_project_path(image_path), "side": label.side, "expected": expected, "top5": ids})
        if index % 24 == 0 or index == len(rows):
            print(f"Verified {index}/{len(rows)} slots", flush=True)
    total = len(rows)
    return {
        "total": total,
        "top1Accuracy": correct[1] / total,
        "top3Accuracy": correct[3] / total,
        "top5Accuracy": correct[5] / total,
        "correctTop1": correct[1],
        "correctTop3": correct[3],
        "correctTop5": correct[5],
        "top3Failures": failures,
        "coarsePrefilter": {
            "graySize": COARSE_SIZE,
            "speciesTopK": COARSE_SPECIES_TOP_K,
            "top3OrderPreserved": coarse_top3_order_preserved,
            "top3SetPreserved": coarse_top3_set_preserved,
            "averageRefinedTemplates": sum(refined_counts) / max(1, len(refined_counts)),
            "p95RefinedTemplates": float(np.percentile(refined_counts, 95)),
            "maxRefinedTemplates": max(refined_counts, default=0),
        },
    }


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    global load_pipeline_cached
    args = parse_args()
    pipeline = load_pipeline()
    load_pipeline_cached = pipeline
    entities = load_species_entities()
    catalog = catalog_templates(pipeline, args.refresh_template_cache)
    labeled, labels = labeled_templates(pipeline)
    templates = catalog + labeled
    count = write_binary(args.output, templates, entities, pipeline.FEATURE_SIZE)
    verification = verify_templates(pipeline, templates) if args.verify else None
    metadata = {
        "schemaVersion": 1,
        "kind": "AndroidTeamPreviewTemplateAsset",
        "binaryFormat": "PTVFEAT2",
        "featureSize": pipeline.FEATURE_SIZE,
        "coarseGraySize": COARSE_SIZE,
        "coarseSpeciesTopK": COARSE_SPECIES_TOP_K,
        "histogramBins": [24, 16],
        "templateCount": count,
        "catalogTemplateCount": len(catalog),
        "labeledTemplateCount": len(labeled),
        "labeledSlotCount": len(labels),
        "weights": {
            "default": dict(zip(("phash", "edge", "color", "template"), DEFAULT_WEIGHTS)),
            "opponent": dict(zip(("phash", "edge", "color", "template"), OPPONENT_WEIGHTS)),
            "labeledTemplateBonus": LABELED_TEMPLATE_BONUS,
        },
        "corpora": [
            {"labels": stable_project_path(labels_path), "images": stable_project_path(images_dir)}
            for labels_path, images_dir in CORPORA
        ],
        "binary": {
            "path": stable_project_path(args.output),
            "bytes": args.output.stat().st_size,
            "sha256": sha256(args.output),
        },
        "verification": verification,
    }
    args.metadata_output.parent.mkdir(parents=True, exist_ok=True)
    args.metadata_output.write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(
        f"Exported {count} templates ({len(catalog)} catalog + {len(labeled)} labeled) "
        f"to {stable_project_path(args.output)}"
    )
    if verification:
        print(
            "Leave-one-image-out: "
            f"Top1={verification['top1Accuracy']:.4f}, "
            f"Top3={verification['top3Accuracy']:.4f}, "
            f"Top5={verification['top5Accuracy']:.4f}"
        )
        coarse = verification["coarsePrefilter"]
        if (
            not math.isclose(verification["top3Accuracy"], 1.0)
            or coarse["top3SetPreserved"] != verification["total"]
        ):
            return 1
    return 0


load_pipeline_cached: Any


if __name__ == "__main__":
    raise SystemExit(main())
