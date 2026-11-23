# /// script
# requires-python = ">=3.14"
# dependencies = ["matplotlib>=3.10,<4", "numpy>=2.4,<3", "pillow>=12,<13"]
# ///
"""Measure pixels rendered by the production WGSL pipelines on macOS."""
from pathlib import Path
import csv
import os
import subprocess
import tempfile

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "local/android-testing"
WIDTH, HEIGHT = 768, 512

with tempfile.TemporaryDirectory(prefix="grainlegit-measure-") as work:
    subprocess.run(
        ["cargo", "test", "--locked", "write_measurement_renders"],
        cwd=ROOT / "renderer",
        env={**os.environ, "CARGO_TARGET_DIR": str(ROOT / "target/renderer-host"),
             "GRAINLEGIT_MEASUREMENTS": work},
        check=True,
    )
    images = {
        raw.stem: np.fromfile(raw, dtype=np.uint8).reshape(HEIGHT, WIDTH, 3)
        for raw in Path(work).glob("*.rgb")
    }

OUTPUT.mkdir(parents=True, exist_ok=True)
Image.fromarray(images["source"]).save(OUTPUT / "slider-control-chart.jpg", quality=100, subsampling=0)

def luma(rgb):
    return rgb @ np.array([0.2126, 0.7152, 0.0722])

def ramp(name):
    return luma(images[name][150:220].mean(axis=0))

def grain(name):
    return np.std(luma(images[name][20:91, 410:456]))

def plot_tones(axis, names):
    source = ramp("source")
    for name, label in names:
        axis.plot(source, ramp(name), label=label)
    axis.set(xlabel="Input sRGB code value", ylabel="Output luma code value", xlim=(0, 255), ylim=(0, 255))
    axis.legend(fontsize=8)
    axis.grid(alpha=0.25)

fig, axes = plt.subplots(2, 2, figsize=(12, 9), layout="constrained")
plot_tones(axes[0, 0], [("source", "Neutral"), ("exposure-minus", "−1 EV"), ("exposure-plus", "+1 EV"),
                       ("camera-plus", "Portra +1 camera EV")])
axes[0, 0].set_title("Exposure and camera exposure")
plot_tones(axes[0, 1], [("source", "Neutral"), ("highlights", "Highlights +80%"), ("shadows", "Shadows +80%"),
                       ("whites", "Whites +80%"), ("blacks", "Blacks +80%")])
axes[0, 1].set_title("Tonal range controls")
plot_tones(axes[1, 0], [("source", "Neutral"), ("contrast", "Contrast +80%"), ("brightness", "Brightness +80%"),
                       ("curve", "Raised RGB midpoint")])
axes[1, 0].set_title("Contrast, brightness, and RGB curves")
plot_tones(axes[1, 1], [("source", "Direct scan"), ("paper", "Paper"), ("paper-plus", "Paper +1 print EV"),
                       ("print-film", "Print film")])
axes[1, 1].set_title("Optical print response")
fig.savefig(OUTPUT / "tone-and-print-response.png", dpi=150)
plt.close(fig)

fig, axes = plt.subplots(1, 3, figsize=(15, 4.7), layout="constrained")
grain_names = ["source", "grain-low", "grain-default", "grain-high", "film-120"]
grain_values = [grain(name) for name in grain_names]
axes[0].bar(["Off", "45%", "100%", "240%", "120 · 6×6\n100%"], grain_values, color="#84603e")
axes[0].set(title="Grain on a neutral 128 patch", ylabel="RMS luma (8-bit code values)")
axes[0].grid(axis="y", alpha=0.25)
edge_x = np.arange(-16, 17)
for name, label in [
    ("source", "Neutral"), ("sharpen", "Sharpen +180%"), ("scan-aperture", "5 px scan aperture"),
    ("paper", "Paper"), ("enlarger-blur", "Paper + enlarger blur"),
]:
    edge = luma(images[name][301:350, 368:401].mean(axis=0))
    edge = (edge - edge[0]) / (edge[-1] - edge[0])
    axes[1].plot(edge_x, edge, label=label)
axes[1].set(title="Edge response", xlabel="Distance from edge (px)", ylabel="Normalized luma")
axes[1].legend(fontsize=7)
axes[1].grid(alpha=0.25)
names = ["source", "temperature", "tint", "saturation", "vibrance", "white-balance", "red-curve", "portra"]
samples = np.stack([images[name][440, 48::96] for name in names])
axes[2].imshow(samples, interpolation="nearest", aspect="auto")
axes[2].set(title="Color patch response", xticks=[], yticks=range(len(names)),
            yticklabels=["Neutral", "Temperature", "Tint", "Saturation", "Vibrance", "White balance", "Red curve", "Portra 400"])
fig.savefig(OUTPUT / "grain-detail-and-colour.png", dpi=150)
plt.close(fig)

sample_indices = np.searchsorted(images["source"][180, :, 0], [64, 128, 192])
with (OUTPUT / "slider-measurements.csv").open("w", newline="") as handle:
    report = csv.writer(handle)
    report.writerow(["render", "ramp_luma_64", "ramp_luma_128", "ramp_luma_192", "grey_patch_rms",
                     "edge_slope", "red_patch_r", "red_patch_g", "red_patch_b"])
    for name in sorted(images):
        response = ramp(name)
        edge = luma(images[name][300:350, 377:392].mean(axis=0))
        report.writerow([name, *[f"{response[x]:.3f}" for x in sample_indices],
                         f"{grain(name):.3f}", f"{max(np.abs(np.diff(edge))):.3f}",
                         *images[name][440, 144]])

mid = sample_indices[1]
assert np.allclose(ramp("source")[mid], 128)
assert ramp("exposure-minus")[mid] < ramp("source")[mid] < ramp("exposure-plus")[mid]
assert ramp("paper-plus")[mid] < ramp("paper")[mid]
assert grain_values[0] < grain_values[1] < grain_values[2] < grain_values[3]
assert grain_values[4] < grain_values[2]
print(f"Measured {len(images)} renders. Charts and CSV saved to {OUTPUT}.")
