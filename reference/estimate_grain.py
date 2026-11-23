# /// script
# requires-python = ">=3.14"
# dependencies = [
#   "numpy>=2.4,<3",
#   "pymupdf>=1.26,<2",
#   "rawpy>=0.26,<1",
#   "scipy>=1.17,<2",
#   "tifffile>=2025",
# ]
# ///
"""Estimate negative cloud loads from the references in grain-calibration.md."""

import argparse
from functools import cache
import json
import math
from pathlib import Path
import sys
from zipfile import ZipFile

import numpy as np
import pymupdf
import rawpy
from scipy.ndimage import gaussian_filter
from scipy.optimize import brentq
from scipy.signal import convolve2d, fftconvolve
import tifffile

sys.path.insert(0, str(Path(__file__).resolve().parent))

from filmgrain_core.cpu import LOG_10, gaussian_kernel, negative_curve
from filmgrain_core.settings import NEGATIVE_STOCKS


APERTURE_UM = 48
LUMA = np.array([0.2126, 0.7152, 0.0722])


@cache
def aperture(pitch):
    """Fractional pixel coverage of a 48 µm circular aperture."""
    radius = APERTURE_UM / (2 * pitch)
    edge = math.ceil(radius + 0.5)
    subpixels = (np.arange(32) + 0.5) / 32 - 0.5
    coordinates = np.arange(-edge, edge + 1)[:, None] + subpixels
    distances = coordinates[:, :, None, None] ** 2 + coordinates[None, None, :, :] ** 2
    weights = (distances <= radius**2).mean(axis=(1, 3))
    return weights / weights.sum()


@cache
def aperture_overlap():
    weights = aperture(1)
    return fftconvolve(weights, weights[::-1, ::-1], mode="full")


def flat_patches(signal, base, pitch, size, *, tail_limit, smooth_limit):
    """Sample independent tiles and reject dust, edges and visible gradients."""
    y, x = np.indices((size, size), dtype=np.float64) - (size - 1) / 2
    norm = (x * x).sum()
    kernel = aperture(pitch)
    observations = []
    for top in range(0, signal.shape[0] - size + 1, size):
        for left in range(0, signal.shape[1] - size + 1, size):
            tile = signal[top:top + size, left:left + size]
            if tile.min() <= 0:
                continue
            detail = -np.log10(tile / base)
            density = detail.mean()
            detail -= density
            detail -= ((detail * x).sum() * x + (detail * y).sum() * y) / norm
            pixel_rms = detail.std()
            if not pixel_rms or np.quantile(np.abs(detail), 0.995) > tail_limit * pixel_rms:
                continue
            if gaussian_filter(detail, 5)[12:-12, 12:-12].std() > smooth_limit * pixel_rms:
                continue
            averaged_light = convolve2d(10 ** -detail, kernel, mode="valid")
            rms = np.log10(averaged_light).std()
            covariance = ((detail[:, 1:] * detail[:, :-1]).mean()
                          + (detail[1:] * detail[:-1]).mean()) / 2
            observations.append((density, rms, covariance / pixel_rms**2))
    return np.array(observations).reshape(-1, 3)


def ektar_measurements(paths):
    scans = []
    for path in paths:
        pixels = tifffile.memmap(path)
        layers = []
        for channel in range(3):
            # Treat the 14-bit scanner code steps in the TIFF as linear light.
            signal = pixels[430:-430, 650:-650, channel].astype(np.float64)
            tiles = flat_patches(signal, 65535, 6.35, 128, tail_limit=3.8, smooth_limit=0.19)
            layers.append({"tiles": len(tiles), "rms": np.median(tiles[:, 1]),
                           "corr_at_6_35um": np.median(tiles[:, 2])})
        scans.append(layers)
    return scans


def gold_measurements(path, density):
    # The 36 mm image area spans about 3350 samples of each Bayer colour.
    pitch = 10.75
    readings = [[], [], []]
    bayer = {"red": (0, 0, 0), "green": (0, 1, 1), "blue": (1, 1, 2)}
    with ZipFile(path) as archive:
        for member in sorted(archive.namelist()):
            if "/film-scans/" not in member or not member.endswith(".ARW"):
                continue
            name = Path(member).name
            colour = name.split("-")[1]
            y, x, channel = bayer[colour]
            with archive.open(member) as source, rawpy.imread(source) as raw:
                signal = raw.raw_image_visible[y::2, x::2].astype(np.float64)
                signal -= raw.black_level_per_channel[channel]
            base = np.median(signal[300:2000, 25:55])
            tiles = flat_patches(signal[120:2200, 160:3330], base, pitch, 96,
                                 tail_limit=3.5, smooth_limit=0.33)
            nearby = tiles[(tiles[:, 0] >= 0.25) & (tiles[:, 0] <= 0.65)]
            if len(nearby):
                adjusted = nearby[:, 1] * np.sqrt(density[channel] / nearby[:, 0])
                readings[channel].append({"frame": name, "tiles": len(nearby),
                                          "rms_at_reference": np.median(adjusted)})
    return readings


def vision3_measurements(path):
    # Vector paths in Kodak H-1-5219t (July 2015), figure TI2647C, page 4.
    with pymupdf.open(path) as document:
        page = document[3]
        paths = {item["seqno"]: item for item in page.get_drawings()}
    axis = paths[46]["rect"]
    assert abs(axis.y1 - 345.05) < 0.01, "Expected the July 2015 Kodak datasheet"
    decade = axis.y1 - paths[70]["rect"].y0 - paths[70]["rect"].height / 2

    def traces(sequence):
        result = []
        for _, start, end in paths[sequence]["items"]:
            if not result or start.x < result[-1][-1].x - 0.001:
                result.append([start])
            result[-1].append(end)
        return [np.array([(point.x, point.y) for point in line]) for line in result]

    blue, = traces(50)
    green, red = traces(51)
    net_density = [0.3, 0.6, 1.0, 1.5]
    rms = []
    for sequence, granularity in zip((49, 48, 47), (red, green, blue)):
        curve, = traces(sequence)
        relative_y = curve[0, 1] - np.array(net_density) * axis.height / 3
        exposure = np.interp(relative_y, curve[::-1, 1], curve[::-1, 0])
        grain_y = np.interp(exposure, granularity[:, 0], granularity[:, 1])
        rms.append(10 ** (-3 + (axis.y1 - grain_y) / decade))
    return net_density, np.array(rms).T


def reference_density(stock):
    return np.array([negative_curve(stock, 2 ** balance, 1)[128]
                     for balance in stock["exposure_balance_ev"]])


def rms_of_clouds(layer, density, scale):
    # Poisson transmission covariance is the exponential of the summed
    # autocorrelations of each cloud's transmission loss times its event rate.
    radius = math.ceil(4 * max(cloud[0] for cloud in layer))
    exponent = np.zeros((4 * radius + 1, 4 * radius + 1))
    for sigma, peak, fraction in layer:
        kernel = gaussian_kernel(sigma, radius)
        stamp = (peak * scale * 2 * math.pi * sigma**2) * np.outer(kernel, kernel)
        loss = -np.expm1(-LOG_10 * stamp)
        rate = density * fraction * LOG_10 / loss.sum()
        exponent += rate * fftconvolve(loss, loss[::-1, ::-1], mode="full")
    weights = aperture_overlap()
    start = (len(weights) - len(exponent)) // 2
    return math.sqrt(np.log1p((weights[start:start + len(exponent), start:start + len(exponent)]
                               * np.expm1(exponent)).sum())) / LOG_10


def fit(stock, density, targets):
    return [brentq(lambda scale: rms_of_clouds(layer, level, scale) - goal, 0.01, 12)
            for layer, level, goal in zip(stock["layers"], density, targets)]


def portra_strength(ektar_rms, portra, density):
    # Estimate PGI change per doubling of magnification within each stock,
    # excluding Ektar's censored "below 25" value at 4.4×.
    magnifications = [np.log2([4.4, 8.8, 17.8]), np.log2([8.8, 17.8])]
    indices = [np.array([37, 59, 89]), np.array([38, 66])]
    slope = sum((m - m.mean()) @ (g - g.mean()) for m, g in zip(magnifications, indices))
    slope /= sum(np.square(m - m.mean()).sum() for m in magnifications)
    ratio = 2 ** ((59 - 38) / slope)
    target = np.linalg.norm(LUMA * ektar_rms) * ratio

    def portra_rms(scale):
        return np.array([rms_of_clouds(layer, d, scale) for layer, d in zip(portra["layers"], density)])

    scalar = brentq(lambda scale: np.linalg.norm(LUMA * portra_rms(scale)) - target, 0.01, 12)
    return [scalar] * 3, ratio, portra_rms(scalar)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ektar", nargs=2, type=Path, required=True)
    parser.add_argument("--gold", type=Path, required=True)
    parser.add_argument("--vision3", type=Path, required=True)
    args = parser.parse_args()

    stocks = {name: NEGATIVE_STOCKS[name] for name in ("ektar-100", "gold-200", "portra-400", "vision3-500t")}
    densities = {name: reference_density(stock) for name, stock in stocks.items()}
    densities["vision3-500t"] = np.ones(3)

    ektar = ektar_measurements(args.ektar)
    gold = gold_measurements(args.gold, densities["gold-200"])
    vision_density, vision_rms = vision3_measurements(args.vision3)
    targets = {
        "ektar-100": np.median([[layer["rms"] for layer in scan] for scan in ektar], axis=0),
        "gold-200": np.array([np.median([frame["rms_at_reference"] for frame in layer]) for layer in gold]),
        "vision3-500t": vision_rms[vision_density.index(1.0)],
    }
    portra_scales, pgi_ratio, targets["portra-400"] = portra_strength(
        targets["ektar-100"], stocks["portra-400"], densities["portra-400"]
    )

    estimates = {}
    for name, stock in stocks.items():
        levels, target = densities[name], targets[name]
        scales = portra_scales if name == "portra-400" else fit(stock, levels, target)
        peaks = [[round(cloud[1] * scale, 5) for cloud in layer] for layer, scale in zip(stock["layers"], scales)]
        estimates[name] = {"net_density": levels.tolist(), "rms48": target.tolist(), "centre_density": peaks}
    print(json.dumps({"ektar_scans": ektar, "gold_scans": gold, "portra_to_ektar_rms": pgi_ratio,
                      "vision3_curve_density": vision_density, "vision3_curve_rms": vision_rms.tolist(),
                      "estimates": estimates}, indent=2))


if __name__ == "__main__":
    main()
