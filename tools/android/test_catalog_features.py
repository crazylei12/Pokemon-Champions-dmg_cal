import importlib.util
import struct
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("refresh", Path(__file__).with_name("refresh-catalog-features.py"))
refresh = importlib.util.module_from_spec(spec)
spec.loader.exec_module(refresh)


class CatalogFeatureTest(unittest.TestCase):
    def test_rejects_truncated_and_trailing_records(self):
        raw = (refresh.ROOT / "src/data/recognition/android/team-preview-templates-v2.bin").read_bytes()
        self.assertTrue(refresh.records(raw))
        for malformed in (raw[:24], raw[:-1], raw + b"x"):
            with self.assertRaises(ValueError):
                refresh.records(malformed)

    def test_count_and_both_variants_cover_current_catalog(self):
        import json
        root = refresh.ROOT
        raw = (root / "src/data/recognition/android/team-preview-templates-v2.bin").read_bytes()
        rows = refresh.records(raw)
        self.assertEqual(len(rows), struct.unpack_from(">I", raw, 24)[0])
        catalog = json.loads((root / "src/data/pokemon-icons/catalog.pokeapi-composite.json").read_text(encoding="utf-8"))
        variants = {(fields[0], fields[7]) for fields, _ in rows if fields[6] == "CATALOG_REFERENCE"}
        for entry in catalog["entries"]:
            for variant in ("regular", "shiny"):
                self.assertIn((entry["canonicalId"], variant), variants)
        self.assertEqual(sum(fields[6] == "USER_LABELED_SCREENSHOT" for fields, _ in rows), 366)


if __name__ == "__main__":
    unittest.main()
