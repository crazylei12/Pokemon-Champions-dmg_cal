#!/usr/bin/env python3
"""Build the local Pokemon vision dataset from project assets.

Generated layout:
- references/ from src/data/pokemon-icons/catalog.pokeapi-composite.json
- dataset/real_train/ from labeled team-preview screenshots
- dataset/labels.csv with image,side,pokemon
- dataset/real_test/ remains empty until a verified team-preview test set is provided
"""

from __future__ import annotations

import argparse
import csv
import json
import re
import shutil
from pathlib import Path
from typing import Any, Iterable


DEFAULT_CATALOG = Path("src/data/pokemon-icons/catalog.pokeapi-composite.json")
DEFAULT_TEAM_PREVIEW_PREVIEW = Path("docs/pic/team_preview/preview.md")
DEFAULT_TEAM_PREVIEW_EXPECTED = Path("src/data/recognition/team-preview.expected.zh-Hans.json")
DEFAULT_PIC2_REPORT = Path(".tmp/team-preview-docs-pic-2-current-roi-report.json")
DEFAULT_REFERENCES = Path("references")
DEFAULT_DATASET = Path("dataset")
IMAGE_EXTENSIONS = {".png", ".jpg", ".jpeg", ".webp", ".bmp"}
EXCLUDED_TEAM_PREVIEW_SAMPLE_IDS = {"3", "4", "5"}
LOCAL_NAME_ALIASES = {
    "炽焰咆啸虎": "Incineroar",
    "大纽拉": "Sneasler",
    "迷拟丘": "Mimikyu",
    "加热洛托姆": "Rotom-Heat",
    "清洗洛托姆": "Rotom-Wash",
    "切割洛托姆": "Rotom-Mow",
    "仆刀将军": "Kingambit",
    "玛丽露丽": "Azumarill",
    "班吉拉斯": "Tyranitar",
    "迷失棺": "Cofagrigus",
}


def main() -> int:
    parser = argparse.ArgumentParser(description="Build references/ and dataset/ for Pokemon vision evaluation.")
    parser.add_argument("--catalog", type=Path, default=DEFAULT_CATALOG)
    parser.add_argument("--team-preview-preview", type=Path, default=DEFAULT_TEAM_PREVIEW_PREVIEW)
    parser.add_argument("--team-preview-expected", type=Path, default=DEFAULT_TEAM_PREVIEW_EXPECTED)
    parser.add_argument("--pic2-report", type=Path, default=DEFAULT_PIC2_REPORT)
    parser.add_argument("--references", type=Path, default=DEFAULT_REFERENCES)
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    parser.add_argument(
        "--real-test-source",
        action="append",
        type=Path,
        help="Optional folder of unlabeled TEAM_PREVIEW screenshots to copy into dataset/real_test. Repeatable.",
    )
    parser.add_argument("--clear-output", action="store_true")
    args = parser.parse_args()

    if args.clear_output:
        clear_generated_outputs(args.references, args.dataset)
    args.references.mkdir(parents=True, exist_ok=True)
    (args.dataset / "real_train").mkdir(parents=True, exist_ok=True)
    (args.dataset / "real_test").mkdir(parents=True, exist_ok=True)

    reference_rows = build_references(args.catalog, args.references)
    catalog = read_json(args.catalog)
    label_rows, train_manifest = build_training_set(
        catalog,
        args.team_preview_preview,
        args.team_preview_expected,
        args.pic2_report,
        args.dataset / "real_train",
    )
    test_manifest = build_real_test_set(args.dataset / "real_test", args.real_test_source or [])

    write_labels(args.dataset / "labels.csv", label_rows)
    write_csv(args.dataset / "real_train_manifest.csv", train_manifest)
    write_csv(args.dataset / "real_test_manifest.csv", test_manifest, ["image", "source", "source_folder"])
    write_json(
        args.dataset / "manifest.json",
        {
            "schemaVersion": 1,
            "references": {
                "path": str(args.references),
                "count": len(reference_rows),
                "sourceCatalog": str(args.catalog),
            },
            "realTrain": {
                "path": str(args.dataset / "real_train"),
                "imageCount": len(train_manifest),
                "labelCount": len(label_rows),
                "labelSources": [
                    str(args.team_preview_preview),
                    str(args.team_preview_expected),
                    str(args.pic2_report),
                ],
            },
            "realTest": {
                "path": str(args.dataset / "real_test"),
                "imageCount": len(test_manifest),
                "sources": sorted({row["source_folder"] for row in test_manifest}),
            },
            "notes": [
                "dataset/real_train contains only screenshots with structured labels.",
                "dataset/real_test is intentionally empty unless --real-test-source is provided.",
                "Do not populate real_test with non-TEAM_PREVIEW screenshots.",
                "references filenames begin with the Showdown id so pokemon-vision-pipeline.py can preserve stable species IDs.",
            ],
        },
    )
    write_json(args.references / "_manifest.json", {"schemaVersion": 1, "references": reference_rows})
    print(
        "Dataset built: "
        f"references={len(reference_rows)}, "
        f"train_images={len(train_manifest)}, "
        f"labels={len(label_rows)}, "
        f"real_test_images={len(test_manifest)}"
    )
    return 0


def build_references(catalog_path: Path, references_dir: Path) -> list[dict[str, Any]]:
    catalog = read_json(catalog_path)
    rows = []
    used_names: set[str] = set()
    for entry in catalog.get("entries", []):
        items = []
        if entry.get("icon"):
            items.append(("regular", False, entry["icon"]))
        for variant in entry.get("iconVariants") or []:
            variant_id = str(variant.get("variantId") or variant.get("role") or "variant")
            items.append((variant_id, bool(variant.get("isShiny")), variant))
        for variant_id, is_shiny, item in items:
            source_path_text = item.get("localPath")
            if not source_path_text:
                continue
            source_path = Path(source_path_text)
            if not source_path.exists() or source_path.suffix.lower() not in IMAGE_EXTENSIONS:
                continue
            filename = unique_filename(reference_filename(entry, item, variant_id), used_names)
            target_path = references_dir / filename
            shutil.copy2(source_path, target_path)
            rows.append(
                {
                    "file": str(target_path),
                    "source": str(source_path),
                    "canonicalId": entry.get("canonicalId", ""),
                    "showdownId": entry.get("showdownId", ""),
                    "displayName": entry.get("displayName", ""),
                    "englishName": entry.get("englishName", ""),
                    "pokemonId": (entry.get("pokemonApi") or {}).get("pokemonId", ""),
                    "sourceId": item.get("sourceId", ""),
                    "role": item.get("role", ""),
                    "variantId": variant_id,
                    "isShiny": is_shiny,
                }
            )
    if not rows:
        raise RuntimeError(f"No reference images could be copied from {catalog_path}")
    return rows


def reference_filename(entry: dict[str, Any], item: dict[str, Any], variant_id: str) -> str:
    showdown = entry.get("showdownId") or entry.get("englishName") or entry.get("canonicalId") or "pokemon"
    display_name = entry.get("displayName") or ""
    canonical = str(entry.get("canonicalId") or "").replace("species.", "")
    pokemon_id = str((entry.get("pokemonApi") or {}).get("pokemonId") or "")
    source_id = item.get("sourceId") or item.get("role") or "source"
    suffix = Path(item.get("localPath") or "").suffix.lower() or ".png"
    parts = [showdown, display_name, canonical, pokemon_id, source_id, variant_id]
    return "__".join(safe_filename(part) for part in parts if str(part).strip()) + suffix


def build_training_set(
    catalog: dict[str, Any],
    team_preview_preview_path: Path,
    team_preview_expected_path: Path,
    pic2_report_path: Path,
    real_train_dir: Path,
) -> tuple[list[dict[str, str]], list[dict[str, str]]]:
    label_rows: list[dict[str, str]] = []
    manifest: list[dict[str, str]] = []
    seen_hashes: set[str] = set()
    alias_map = build_name_alias_map(catalog)
    if team_preview_preview_path.exists():
        label_rows.extend(
            copy_preview_markdown_labels(team_preview_preview_path, real_train_dir, manifest, alias_map, seen_hashes)
        )
    else:
        label_rows.extend(copy_expected_team_preview(team_preview_expected_path, real_train_dir, manifest, seen_hashes))
    if pic2_report_path.exists():
        label_rows.extend(copy_pic2_report_labels(pic2_report_path, real_train_dir, manifest, seen_hashes))
    return label_rows, manifest


def copy_preview_markdown_labels(
    preview_path: Path,
    real_train_dir: Path,
    manifest: list[dict[str, str]],
    alias_map: dict[str, str],
    seen_hashes: set[str],
) -> list[dict[str, str]]:
    samples = parse_team_preview_markdown(preview_path, alias_map)
    rows = []
    image_dir = preview_path.parent
    for sample_id, pokemon_names in samples:
        if sample_id in EXCLUDED_TEAM_PREVIEW_SAMPLE_IDS:
            continue
        source_path = image_dir / f"{sample_id}.jpg"
        if not source_path.exists():
            raise FileNotFoundError(f"Preview answer has no matching image: {source_path}")
        target_name = f"team_preview_{int(sample_id):03d}{source_path.suffix.lower()}"
        copied = copy_training_image(source_path, real_train_dir / target_name, seen_hashes)
        if not copied:
            continue
        manifest.append(
            {
                "image": target_name,
                "source": str(source_path),
                "source_type": "team_preview_preview_md",
                "sample_id": f"team-preview-{sample_id}",
            }
        )
        for index, pokemon in enumerate(pokemon_names):
            side = "own" if index < 6 else "opponent"
            slot_index = index if index < 6 else index - 6
            rows.append(
                {
                    "image": target_name,
                    "side": f"{side}.slot{slot_index}",
                    "pokemon": pokemon,
                }
            )
    return rows


def parse_team_preview_markdown(preview_path: Path, alias_map: dict[str, str]) -> list[tuple[str, list[str]]]:
    text = preview_path.read_text(encoding="utf-8-sig")
    matches = list(re.finditer(r"(?m)^\s*(\d{1,2})\.?\s*", text))
    samples = []
    for index, match in enumerate(matches):
        sample_id = match.group(1)
        start = match.end()
        end = matches[index + 1].start() if index + 1 < len(matches) else len(text)
        body = text[start:end]
        names = parse_preview_answer_names(body)
        if len(names) != 12:
            raise ValueError(f"{preview_path} sample {sample_id} has {len(names)} names; expected 12.")
        samples.append((sample_id, [resolve_answer_name(name, alias_map, preview_path) for name in names]))
    if not samples:
        raise ValueError(f"No samples were parsed from {preview_path}")
    return samples


def parse_preview_answer_names(text: str) -> list[str]:
    names = []
    for line in text.splitlines():
        line = line.strip()
        if not line:
            continue
        line = re.sub(r"^(左侧|右侧)[:：]\s*", "", line)
        names.extend(token for token in re.split(r"\s+", line) if token)
    return names


def build_name_alias_map(catalog: dict[str, Any]) -> dict[str, str]:
    aliases = {}
    for entry in catalog.get("entries", []):
        showdown_id = entry.get("showdownId")
        if not showdown_id:
            continue
        for key in ("displayName", "showdownId", "englishName", "canonicalId"):
            value = entry.get(key)
            if value:
                aliases[normalize_answer_name(str(value).replace("species.", ""))] = showdown_id
    for answer_text, showdown_id in LOCAL_NAME_ALIASES.items():
        aliases[normalize_answer_name(answer_text)] = showdown_id
    return aliases


def resolve_answer_name(name: str, alias_map: dict[str, str], source_path: Path) -> str:
    key = normalize_answer_name(name)
    if key not in alias_map:
        raise ValueError(f"Cannot map answer name '{name}' from {source_path} to a Showdown id.")
    return alias_map[key]


def copy_expected_team_preview(
    expected_path: Path,
    real_train_dir: Path,
    manifest: list[dict[str, str]],
    seen_hashes: set[str],
) -> list[dict[str, str]]:
    expected = read_json(expected_path)
    rows = []
    for index, sample in enumerate(expected.get("samples", []), start=1):
        source_path = Path(sample["image"])
        target_name = f"team_preview_{index:03d}{source_path.suffix.lower()}"
        target_path = real_train_dir / target_name
        copied = copy_training_image(source_path, target_path, seen_hashes)
        if not copied:
            continue
        manifest.append(
            {
                "image": target_name,
                "source": str(source_path),
                "source_type": "team_preview_expected",
                "sample_id": sample.get("sampleId", ""),
            }
        )
        for side in ("own", "opponent"):
            for slot_index, item in enumerate(sample.get("answers", {}).get(side, [])):
                rows.append(
                    {
                        "image": target_name,
                        "side": f"{side}.slot{slot_index}",
                        "pokemon": item.get("showdownId") or item.get("canonicalId") or item.get("answerText"),
                    }
                )
    return rows


def copy_pic2_report_labels(
    report_path: Path,
    real_train_dir: Path,
    manifest: list[dict[str, str]],
    seen_hashes: set[str],
) -> list[dict[str, str]]:
    report = read_json(report_path)
    rows = []
    for index, sample in enumerate(report.get("samples", []), start=1):
        source_path = Path(sample["image"])
        target_name = f"pic2_{index:03d}{source_path.suffix.lower()}"
        target_path = real_train_dir / target_name
        copied = copy_training_image(source_path, target_path, seen_hashes)
        if not copied:
            continue
        manifest.append(
            {
                "image": target_name,
                "source": str(source_path),
                "source_type": "pic2_current_roi_report_expected",
                "sample_id": source_path.stem,
            }
        )
        for slot in sample.get("slots", []):
            expected = slot.get("expected") or {}
            side = str(slot.get("side") or "").strip().lower()
            if side == "own":
                side_key = "own"
            elif side == "opponent":
                side_key = "opponent"
            else:
                raise ValueError(f"Unknown side in {report_path}: {slot.get('side')}")
            rows.append(
                {
                    "image": target_name,
                    "side": f"{side_key}.slot{int(slot['slotIndex'])}",
                    "pokemon": expected.get("showdownId") or expected.get("canonicalId") or expected.get("answerText"),
                }
            )
    return rows


def copy_training_image(source_path: Path, target_path: Path, seen_hashes: set[str]) -> bool:
    digest = file_digest(source_path)
    if digest in seen_hashes:
        return False
    seen_hashes.add(digest)
    shutil.copy2(source_path, target_path)
    return True


def build_real_test_set(real_test_dir: Path, source_dirs: list[Path]) -> list[dict[str, str]]:
    manifest = []
    for source_index, folder in enumerate(source_dirs, start=1):
        prefix = safe_filename(folder.name) or f"test{source_index}"
        for index, source_path in enumerate(list_images(folder), start=1):
            target_name = f"{prefix}_{index:03d}{source_path.suffix.lower()}"
            target_path = real_test_dir / target_name
            shutil.copy2(source_path, target_path)
            manifest.append(
                {
                    "image": target_name,
                    "source": str(source_path),
                    "source_folder": str(folder),
                }
            )
    return manifest


def clear_generated_outputs(references_dir: Path, dataset_dir: Path) -> None:
    workspace = Path.cwd().resolve()
    for target in (references_dir, dataset_dir):
        resolved = target.resolve()
        if resolved == workspace or workspace not in resolved.parents:
            raise RuntimeError(f"Refusing to clear path outside workspace: {target}")
        if target.exists():
            shutil.rmtree(target)


def write_labels(path: Path, rows: list[dict[str, str]]) -> None:
    write_csv(path, rows, ["image", "side", "pokemon"])


def write_csv(path: Path, rows: list[dict[str, Any]], fieldnames: Iterable[str] | None = None) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if fieldnames is not None:
        fields = list(fieldnames)
    elif rows:
        fields = list(rows[0].keys())
    else:
        fields = []
    with path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        for row in rows:
            writer.writerow(row)


def read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def list_images(root: Path) -> list[Path]:
    if not root.exists():
        return []
    return sorted(path for path in root.iterdir() if path.is_file() and path.suffix.lower() in IMAGE_EXTENSIONS)


def unique_filename(filename: str, used_names: set[str]) -> str:
    stem = Path(filename).stem
    suffix = Path(filename).suffix
    candidate = filename
    index = 2
    while candidate.lower() in used_names:
        candidate = f"{stem}__{index}{suffix}"
        index += 1
    used_names.add(candidate.lower())
    return candidate


def safe_filename(value: Any) -> str:
    text = str(value).strip()
    return re.sub(r"[^0-9A-Za-z_.\-\u4e00-\u9fff]+", "_", text).strip("_") or "item"


def normalize_answer_name(value: str) -> str:
    text = value.strip().lower()
    text = text.replace("Ｑ", "q").replace("♀", "f").replace("♂", "m")
    return re.sub(r"[^0-9a-z\u4e00-\u9fff]+", "", text)


def file_digest(path: Path) -> str:
    import hashlib

    hasher = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            hasher.update(chunk)
    return hasher.hexdigest()


if __name__ == "__main__":
    raise SystemExit(main())
