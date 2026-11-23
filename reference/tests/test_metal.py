# /// script
# requires-python = ">=3.14"
# dependencies = [
#   "numpy>=2.4,<3",
#   "scipy>=1.17,<2",
# ]
# ///
"""Exercise the physical controls and compare the two rendering backends."""

from dataclasses import replace
from pathlib import Path
import sys
import unittest

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from filmgrain_core.cpu import render as render_cpu
from filmgrain_core.metal import is_supported_platform, render as render_metal
from filmgrain_core.settings import Settings


class MetalModelTests(unittest.TestCase):
    @unittest.skipUnless(is_supported_platform(), "requires Apple Silicon")
    def test_negative_stock_response_matches_cpu(self):
        ramp = np.arange(256, dtype=np.uint8)
        strip = np.stack((ramp, np.roll(ramp, 71), np.roll(ramp, 137)), axis=1)
        picture = np.tile(strip, (31, 3, 1))
        for stock in ("portra-400", "gold-200", "ektar-100", "vision3-500t"):
            with self.subTest(stock=stock):
                controls = Settings(grain=0, negative_stock=stock)
                cpu = render_cpu(picture, controls, 457)
                gpu, _ = render_metal(picture, controls, 457)
                self.assertFalse(np.array_equal(cpu, picture))
                self.assertLessEqual(np.abs(gpu.astype(np.int16) - cpu).max(), 2)

    @unittest.skipUnless(is_supported_platform(), "requires Apple Silicon")
    def test_scan_against_cpu_reference(self):
        # Odd dimensions exercise partial dispatch groups and the boundary
        # between the full Metal band and the final short band.
        picture = np.full((263, 3809, 3), 128, dtype=np.uint8)
        picture[:, 1904] = 110
        for grain, size in ((0.2, 1), (1.0, 1), (2.0, 1), (1.0, 10)):
            with self.subTest(grain=grain, size=size):
                controls = Settings(grain=grain, grain_size=size)
                cpu = render_cpu(picture, controls, 457)
                gpu, _ = render_metal(picture, controls, 457)
                flat_cpu = cpu[:, :1500].astype(np.float32)
                flat_gpu = gpu[:, :1500].astype(np.float32)
                self.assertLess(abs(flat_gpu.mean() - flat_cpu.mean()), 0.2)
                self.assertAlmostEqual(flat_gpu.std() / flat_cpu.std(), 1.0, delta=0.05)
                self.assertAlmostEqual(adjacent_correlation(flat_gpu), adjacent_correlation(flat_cpu), delta=0.025)
                self.assertAlmostEqual(line_contrast(gpu), line_contrast(cpu), delta=1.5)
                jumps = np.abs(np.diff(flat_gpu, axis=0)).mean(axis=(1, 2))
                self.assertAlmostEqual(jumps[255], np.median(jumps), delta=0.25)

        repeat, _ = render_metal(picture, controls, 457)
        self.assertTrue(np.array_equal(gpu, repeat))
        other_seed, _ = render_metal(picture, controls, 458)
        self.assertFalse(np.array_equal(gpu, other_seed))

    @unittest.skipUnless(is_supported_platform(), "requires Apple Silicon")
    def test_optical_print_matches_cpu(self):
        picture = np.tile(np.arange(256, dtype=np.uint8)[None, :, None], (263, 6, 3))
        controls = Settings(
            print_medium="paper", exposure=-0.4, development=0.6, grain_load=1.5,
            coarse_fraction=0.4, layer_size=(0.8, 1, 1.3), negative_width=24,
            enlargement=2.5, enlarger_blur=25, print_exposure=0.3,
            print_lights=(-0.2, 0, 0.5), scan_blur=0.5, scan_aperture=2,
        )
        for medium in ("paper", "film"):
            with self.subTest(medium=medium):
                settings = replace(controls, print_medium=medium)
                cpu = render_cpu(picture, settings, 457)
                gpu, _ = render_metal(picture, settings, 457)
                delta = gpu.astype(np.float32) - cpu
                self.assertLess(np.abs(delta.mean(axis=(0, 1))).max(), 0.3)
                repeat, _ = render_metal(picture, settings, 457)
                self.assertTrue(np.array_equal(gpu, repeat))


class PhysicalControlsTests(unittest.TestCase):
    def test_grain_colour_retains_brightness_texture(self):
        image = np.full((155, 1601, 3), 130, dtype=np.uint8)
        negative = Settings(grain_size=2.7, grain_load=2.5)
        paper = replace(negative, print_medium="paper", negative_width=18, enlargement=1, print_grain=3)
        renderers = [("CPU", render_cpu)]
        if is_supported_platform():
            renderers.append(("Metal", lambda pixels, settings, seed: render_metal(pixels, settings, seed)[0]))
        for name, render in renderers:
            for settings in (negative, paper):
                with self.subTest(backend=name, medium=settings.print_medium):
                    separate = render(image, settings, 457).astype(np.float32)
                    balanced = render(image, replace(settings, grain_colour=0.25), 457).astype(np.float32)
                    original_chroma = (separate[..., 0] - separate[..., 1]).std()
                    balanced_chroma = (balanced[..., 0] - balanced[..., 1]).std()
                    luma = np.array([0.2126, 0.7152, 0.0722], dtype=np.float32)
                    self.assertLess(balanced_chroma, original_chroma * 0.5)
                    self.assertGreater((balanced @ luma).std(), (separate @ luma).std() * 0.8)
                    self.assertLess(abs(balanced.mean() - separate.mean()), 1)

    def test_print_exposure_and_aperture(self):
        picture = np.full((60, 122, 3), 85, dtype=np.uint8)
        picture[:, 61:] = 160
        clean = Settings(grain=0, print_grain=0, print_medium="paper")
        light = render_cpu(picture, clean, 1)
        darker = render_cpu(picture, replace(clean, print_exposure=0.8), 1)
        self.assertTrue(np.all(darker < light))
        red_light = render_cpu(picture, replace(clean, print_lights=(0.8, 0, 0)), 1)
        self.assertTrue(np.all(red_light[:, :, 0] < light[:, :, 0]))
        self.assertTrue(np.array_equal(red_light[:, :, 1:], light[:, :, 1:]))
        blur = render_cpu(picture, replace(clean, scan_aperture=3), 1)
        self.assertTrue(np.array_equal(blur[:, 40], light[:, 40]))
        self.assertGreater(blur[:, 60].mean(), light[:, 60].mean())

    def test_print_grain_exists_without_negative_grain(self):
        picture = np.full((100, 2103, 3), 128, dtype=np.uint8)
        controls = Settings(grain=0, print_medium="paper", negative_width=18, enlargement=1)
        smooth = render_cpu(picture, replace(controls, print_grain=0), 1)
        self.assertTrue(np.all(smooth == smooth[0, 0]))
        textured = render_cpu(picture, controls, 1)
        self.assertGreater(textured.std(), 0.5)

    def test_grain_load_changes_visibility(self):
        picture = np.full((129, 2103, 3), 128, dtype=np.uint8)
        normal = render_cpu(picture, Settings(), 457)
        dense = render_cpu(picture, Settings(grain_load=2), 457)
        self.assertGreater(dense.std(), normal.std() * 1.2)
        self.assertLess(abs(dense.mean() - normal.mean()), 0.3)


def adjacent_correlation(picture):
    green = picture[:, :, 1]
    return np.corrcoef(green[:, :-1].ravel(), green[:, 1:].ravel())[0, 1]


def line_contrast(picture):
    return np.median(picture[:, 1902:1904].mean(axis=(1, 2)) - picture[:, 1904].mean(axis=1))


if __name__ == "__main__":
    unittest.main()
