#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[2]
PIPELINE_PATH = PROJECT_ROOT / "tools/recognition/pokemon-vision-pipeline.py"
SPEC = importlib.util.spec_from_file_location("pokemon_vision_pipeline", PIPELINE_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Cannot load recognition pipeline: {PIPELINE_PATH}")
PIPELINE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = PIPELINE
SPEC.loader.exec_module(PIPELINE)


class TeamPreviewViewportTest(unittest.TestCase):
    def test_detects_tablet_and_phone_game_viewports(self) -> None:
        self.assertEqual(
            {"left": 0, "top": 246, "width": 3392, "height": 1908},
            PIPELINE.centered_aspect_viewport(3392, 2400, 16 / 9),
        )
        self.assertEqual(
            {"left": 284, "top": 0, "width": 2204, "height": 1240},
            PIPELINE.centered_aspect_viewport(2772, 1240, 16 / 9),
        )

    def test_maps_safe_zones_through_centered_16_by_9_viewports(self) -> None:
        own_slot0 = {
            "left": 862,
            "top": 528,
            "right": 1065,
            "bottom": 705,
            "baseWidth": 3392,
            "baseHeight": 2400,
        }
        opponent_slot0 = {
            "left": 2774,
            "top": 512,
            "right": 3050,
            "bottom": 716,
            "baseWidth": 3392,
            "baseHeight": 2400,
        }
        self.assertEqual(
            {"left": 844, "top": 183, "width": 132, "height": 115},
            PIPELINE.scaled_safe_zone_rect_to_image_rect(
                own_slot0,
                2772,
                1240,
                mapping_mode="largest_centered_aspect",
                target_aspect_ratio=16 / 9,
            ),
        )
        self.assertEqual(
            {"left": 2087, "top": 173, "width": 179, "height": 132},
            PIPELINE.scaled_safe_zone_rect_to_image_rect(
                opponent_slot0,
                2772,
                1240,
                mapping_mode="largest_centered_aspect",
                target_aspect_ratio=16 / 9,
            ),
        )

    def test_keeps_base_screenshot_coordinates_unchanged(self) -> None:
        rect = {
            "left": 2774,
            "top": 1637,
            "right": 3050,
            "bottom": 1835,
            "baseWidth": 3392,
            "baseHeight": 2400,
        }
        self.assertEqual(
            {"left": 2774, "top": 1637, "width": 276, "height": 198},
            PIPELINE.scaled_safe_zone_rect_to_image_rect(
                rect,
                3392,
                2400,
                mapping_mode="largest_centered_aspect",
                target_aspect_ratio=16 / 9,
            ),
        )

    def test_default_safe_zone_resource_uses_v2_viewport_mapping(self) -> None:
        regions = PIPELINE.load_team_preview_safe_zone_regions(PROJECT_ROOT / PIPELINE.DEFAULT_TEAM_PREVIEW_ROI)
        self.assertEqual(12, len(regions))
        self.assertTrue(all(region.viewport_mapping_mode == "largest_centered_aspect" for region in regions.values()))


if __name__ == "__main__":
    unittest.main()
