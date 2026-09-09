#!/usr/bin/env python3
"""Refresh catalog features while retaining compiled private screenshot records.

Use the full export-team-preview-templates.py --verify workflow when the private
corpora are available. This fallback cannot establish current screenshot accuracy.
"""
import hashlib
import importlib.util
import io
import json
import struct
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def load(name, relative):
    spec = importlib.util.spec_from_file_location(name, ROOT / relative)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def records(data):
    if len(data) < 64 or data[:8] != b"PTVFEAT2":
        raise ValueError("Invalid feature header")
    version, size, coarse, hist, count = struct.unpack_from(">5I", data, 8)
    if (version, size, coarse, hist) != (2, 96, 16, 384):
        raise ValueError("Unsupported feature layout")
    stream = io.BytesIO(data)
    stream.seek(64)
    result = []
    for _ in range(count):
        start = stream.tell()
        fields = []
        for _ in range(9):
            raw = stream.read(2)
            if len(raw) != 2:
                raise ValueError("Truncated record length")
            length = struct.unpack(">H", raw)[0]
            raw = stream.read(length)
            if len(raw) != length:
                raise ValueError("Truncated record text")
            fields.append(raw.decode("utf-8"))
        payload_size = 5 + size * size + coarse * coarse + size * size // 8 + hist * 4 + 8
        if len(stream.read(payload_size)) != payload_size:
            raise ValueError("Truncated record features")
        result.append((fields, data[start:stream.tell()]))
    if stream.tell() != len(data):
        raise ValueError("Unexpected trailing feature bytes")
    return result


def main():
    exporter = load("feature_exporter", "tools/android/export-team-preview-templates.py")
    builder = load("dataset_builder", "tools/recognition/build-pokemon-vision-dataset.py")
    target = exporter.DEFAULT_OUTPUT
    metadata_path = exporter.DEFAULT_METADATA_OUTPUT
    old = target.read_bytes()
    metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    old_hash = hashlib.sha256(old).hexdigest()
    if old_hash != metadata["binary"]["sha256"]:
        raise ValueError("Existing binary differs from its provenance metadata")
    original = records(old)
    retained = [row for row in original if row[0][6] == "USER_LABELED_SCREENSHOT"]
    if len(retained) != metadata["labeledTemplateCount"] or not retained:
        raise ValueError("Existing labeled record count mismatch")
    pipeline = exporter.load_pipeline()
    exporter.load_pipeline_cached = pipeline
    entities = exporter.load_species_entities()
    with tempfile.TemporaryDirectory(prefix="champions-features-") as directory:
        refs = Path(directory) / "references"
        refs.mkdir()
        builder.build_references(ROOT / "src/data/pokemon-icons/catalog.pokeapi-composite.json", refs)
        from types import SimpleNamespace
        catalog = pipeline.build_templates(SimpleNamespace(
            references=refs, no_augment=True, augment_count=0, seed=12345,
            no_template_cache=True, refresh_template_cache=True,
            template_cache_dir=refs / "cache"))
        # Keep provenance reproducible; the temporary root is not a source path.
        for template in catalog:
            template.source_path = Path("references") / Path(template.source_path).name
        binary = Path(directory) / "catalog.bin"
        exporter.write_binary(binary, catalog, entities, pipeline.FEATURE_SIZE)
        fresh = binary.read_bytes()
    if fresh[:24] != old[:24] or fresh[28:64] != old[28:64]:
        raise ValueError("Feature parameters changed; require full corpus rebuild")
    current = records(fresh)
    coverage = {row[0][0] for row in current}
    expected = {row["canonicalId"] for row in json.loads((ROOT / "src/data/pokemon-icons/catalog.pokeapi-composite.json").read_text(encoding="utf-8"))["entries"]}
    if expected - coverage:
        raise ValueError(f"Missing catalog feature species: {sorted(expected - coverage)}")
    output = fresh[:24] + struct.pack(">I", len(current) + len(retained)) + fresh[28:] + b"".join(row[1] for row in retained)
    merged = records(output)
    if [row[1] for row in merged if row[0][6] == "USER_LABELED_SCREENSHOT"] != [row[1] for row in retained]:
        raise ValueError("Private screenshot feature preservation failed")
    metadata.update(templateCount=len(merged), catalogTemplateCount=len(current), verification=None)
    metadata["catalogRefresh"] = {
        "method": "REBUILD_CATALOG_PRESERVE_COMPILED_LABELED_RECORDS",
        "sourceBinarySha256": old_hash,
        "retainedLabeledRecords": len(retained),
        "coveredCatalogSpecies": len(coverage),
        "previousCorpusVerification": metadata.get("catalogRefresh", {}).get("previousCorpusVerification") or json.loads(metadata_path.read_text(encoding="utf-8")).get("verification"),
        "currentCorpusVerification": "NOT_RUN_PRIVATE_SCREENSHOTS_UNAVAILABLE",
    }
    metadata["binary"].update(bytes=len(output), sha256=hashlib.sha256(output).hexdigest())
    target.write_bytes(output)
    metadata_path.write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Refreshed {len(current)} catalog features for {len(coverage)} species; retained {len(retained)} labeled records byte-for-byte. Screenshot accuracy not re-evaluated.")


if __name__ == "__main__":
    main()
