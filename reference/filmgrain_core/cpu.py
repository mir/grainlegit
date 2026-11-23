"""Stochastic chromogenic negative and density-calibrated scan."""

from dataclasses import dataclass
import math

import numpy as np
from numpy.random import Generator, SeedSequence, default_rng
from numpy.typing import NDArray
from scipy.ndimage import convolve1d
from scipy.special import ndtr

from .settings import MIN_VISIBLE_GRAIN, NEGATIVE_STOCKS, PROFILES, Settings


SAMPLES_PER_PIXEL = 2
BAND_ROWS = 128
LOG_10 = math.log(10)
PROFILE = PROFILES["negative"]
IMAGE_DENSITY_RANGE = PROFILE["image_density_range"]
EXPOSURE_KNEE = PROFILE["exposure_knee"]
EXPOSURE_SPAN = math.asinh(1 / EXPOSURE_KNEE)
PRINT_LEVELS = 4096
PRINT_SEED_MASK = 0xD1B54A32D192ED03


def gaussian_kernel(sigma: float, radius: int) -> NDArray[np.float64]:
    if radius == 0:
        return np.ones(1)
    edges = (np.arange(-radius, radius + 2) - 0.5) / sigma
    kernel = np.diff(ndtr(edges))
    return kernel / kernel.sum()


@dataclass(frozen=True)
class CloudPopulation:
    sigma_um: float
    centre_density: float
    density_fraction: float

    def sample(self, samples_per_um: float, load: float, fraction: float, reference: bool) -> "SampledCloud":
        sigma = self.sigma_um * samples_per_um
        radius = max(1, math.ceil(4 * sigma))
        kernel = gaussian_kernel(sigma, radius)

        # The integrated optical density of one Gaussian dye cloud.
        dye_load = self.centre_density * load * 2 * math.pi * sigma**2
        if reference:
            return SampledCloud(kernel, dye_load, fraction / dye_load)
        density_stamp = dye_load * np.outer(kernel, kernel)

        # For independent Poisson counts, E[exp(-a * count)] is
        # exp(rate * expm1(-a)). Calibrate the mean transmitted light on a
        # uniform patch before scan inversion and pixel quantization.
        effective_density = -np.expm1(-LOG_10 * density_stamp).sum() / LOG_10
        return SampledCloud(kernel, dye_load, fraction / effective_density)


@dataclass(frozen=True)
class SampledCloud:
    kernel: NDArray[np.float64]
    dye_load: float
    events_per_density: float


def negative_curve(profile: dict, gain: float, slope: float) -> NDArray[np.float32]:
    srgb = np.arange(256, dtype=np.float64) / 255
    exposure = np.where(srgb <= 0.04045, srgb / 12.92, ((srgb + 0.055) / 1.055) ** 2.4)
    knee = profile["exposure_knee"]
    density_range = profile["image_density_range"]
    density = density_range * np.arcsinh(exposure * gain / knee) * slope / math.asinh(1 / knee)
    shoulder = density > density_range
    headroom = profile["shoulder_range"]
    density[shoulder] = density_range - headroom * np.expm1(-(density[shoulder] - density_range) / headroom)
    return density.astype(np.float32)


def print_curve(profile: dict, gain: float) -> NDArray[np.float32]:
    incident = np.linspace(0, 1, PRINT_LEVELS) * gain
    response = (incident / profile["exposure_midpoint"]) ** profile["contrast"]
    return (profile["image_density_range"] * response / (1 + response)).astype(np.float32)


def render(
    pixels: NDArray[np.uint8], settings: Settings, seed: int
) -> NDArray[np.uint8]:
    """Render one seeded negative or optical print."""
    if settings.bypass:
        return pixels.copy()

    negative = NEGATIVE_STOCKS[settings.negative_stock]
    saturation = negative.get("saturation", 1)
    if saturation != 1:
        # Match the Metal exposure proxy's quantized luma and saturation.
        luma = (((pixels @ np.array([54, 183, 19], dtype=np.uint16)) + 128) >> 8).astype(np.float32)
        adjusted = np.empty_like(pixels)
        for channel in range(3):
            tone = luma + (pixels[..., channel] - luma) * np.float32(saturation)
            adjusted[..., channel] = np.clip(np.rint(tone), 0, 255)
        pixels = adjusted

    printing = settings.print_medium != "direct"
    negative_output = "light" if printing or settings.scan_optics else "negative"
    fog = negative["fog_density"] if printing or (not settings.scan_optics and settings.grain_colour >= 1) else 0
    image = form_emulsion(pixels, settings, seed, negative, negative_output, fog, printing=False)
    if printing:
        print_width_um = 1000 * settings.negative_width * settings.enlargement
        sigma = settings.enlarger_blur * max(pixels.shape[:2]) / print_width_um
        image = filter_light(image, sigma, 1)
        # Align the receiver lookup with the Metal 12-bit exposure table.
        tones = ((image.astype(np.uint32) * (PRINT_LEVELS - 1) + 32767) // 65535).astype(np.uint16)
        material = PROFILES[settings.print_medium]
        kind = "light" if settings.scan_optics else "print"
        image = form_emulsion(tones, settings, seed ^ PRINT_SEED_MASK, material, kind,
                              material["fog_density"], printing=True)
    if settings.scan_optics:
        light = filter_light(image, settings.scan_blur, settings.scan_aperture).astype(np.float32)
        image = encode_light(light / 65535, "print" if printing else "negative", 0)
    return image


def form_emulsion(
    pixels: NDArray, settings: Settings, seed: int, profile: dict, kind: str, fog: float, *, printing: bool
) -> NDArray:
    active_grain = settings.print_grain if printing else settings.grain
    if settings.grain_colour >= 1 or active_grain <= MIN_VISIBLE_GRAIN:
        return expose(pixels, settings, seed, profile, kind, fog, printing=printing)

    formed = expose(pixels, settings, seed, profile, "light", fog, printing=printing)
    expected = expose(pixels, settings, seed, profile, "light", fog, printing=printing, reference=True)
    luminance = np.array([0.2126, 0.7152, 0.0722], dtype=np.float32)
    for start in range(0, len(formed), BAND_ROWS):
        target = formed[start:start + BAND_ROWS]
        base = expected[start:start + BAND_ROWS].astype(np.float32)
        shift = np.log2(np.maximum(target, 1).astype(np.float32) / np.maximum(base, 1))
        neutral = (shift @ luminance)[..., None]
        shift = neutral + (shift - neutral) * settings.grain_colour
        target[:] = np.clip(np.rint(base * np.exp2(shift)), 0, 65535)
    return formed if kind == "light" else encode_light(formed.astype(np.float32) / 65535, kind, fog)


def expose(
    pixels: NDArray, settings: Settings, seed: int, profile: dict, kind: str, fog: float, *,
    printing: bool, reference: bool = False
) -> NDArray:
    height, width, _ = pixels.shape
    rendered = np.empty(pixels.shape, dtype=np.uint16 if kind == "light" else np.uint8)
    frame_width = settings.negative_width * (settings.enlargement if printing else 1)
    samples_per_um = SAMPLES_PER_PIXEL * max(width, height) / (1000 * frame_width)
    stochastic = (settings.print_grain if printing else settings.grain) > MIN_VISIBLE_GRAIN
    for channel, layer_seed in enumerate(SeedSequence(seed).spawn(3)):
        if printing:
            density_lut = print_curve(profile, 2 ** (settings.print_exposure + settings.print_lights[channel]))
            scale, load = samples_per_um, settings.print_grain
            fraction = -1
        else:
            slope = 2 ** (settings.development * profile["development_sensitivity"][channel] / 2)
            balance = profile.get("exposure_balance_ev", (0, 0, 0))[channel]
            density_lut = negative_curve(profile, 2 ** (settings.exposure + balance), slope)
            scale = samples_per_um * math.sqrt(settings.grain) * settings.grain_size * settings.layer_size[channel]
            load = settings.grain_load * 2 ** (settings.development / 3)
            fraction = settings.coarse_fraction

        channel_tones = pixels[:, :, channel]
        out = rendered[:, :, channel]
        if not stochastic:
            out[:] = encode_light(np.exp(-LOG_10 * (density_lut[channel_tones] + fog)), kind, fog)
            continue
        clouds = []
        for index, spec in enumerate(profile["layers"][channel]):
            population = CloudPopulation(*spec)
            weight = population.density_fraction if fraction < 0 else (fraction if index else 1 - fraction)
            clouds.append(population.sample(scale, load, weight, reference))
        rng = None if reference else default_rng(layer_seed)
        render_layer(channel_tones, out, tuple(clouds), rng, density_lut, kind, fog)
    return rendered


def filter_light(light: NDArray[np.uint16], sigma: float, aperture: float) -> NDArray[np.uint16]:
    if sigma == 0 and aperture == 1:
        return light
    radius = math.ceil((aperture - 1) / 2)
    box = np.clip(aperture / 2 + 0.5 - np.abs(np.arange(-radius, radius + 1)), 0, 1) / aperture
    kernel = np.convolve(gaussian_kernel(sigma, math.ceil(4 * sigma)), box)
    for axis in (1, 0):
        filtered = convolve1d(light, kernel, axis=axis, output=np.float32, mode="nearest")
        light = np.clip(np.rint(filtered), 0, 65535).astype(np.uint16)
    return light


def sample_sites(
    channel: NDArray,
    start: int,
    stop: int,
    columns: NDArray[np.intp],
    clouds: tuple[SampledCloud, ...],
    rng: Generator | None,
    density_lut: NDArray[np.float32],
) -> tuple[NDArray[np.float32], ...]:
    rows = np.clip(np.arange(start, stop), 0, channel.shape[0] - 1)
    density = density_lut[channel[rows[:, None], columns]]
    # Each subpixel cell gets its own independent count. The camera image
    # supplies a piecewise-constant exposure over the area of an input pixel.
    sample_shape = (len(rows), SAMPLES_PER_PIXEL, len(columns), SAMPLES_PER_PIXEL)
    field_shape = (len(rows) * SAMPLES_PER_PIXEL, len(columns) * SAMPLES_PER_PIXEL)
    fields = []
    for cloud in clouds:
        rates = density[:, None, :, None] * cloud.events_per_density
        if rng is None:
            fields.append(np.broadcast_to(rates, sample_shape).reshape(field_shape))
            continue
        huge = rates >= 1e18
        if huge.any():
            # NumPy's Poisson counts use int64. The normal limit is adequate
            # here because an individual event contributes almost no density.
            counts = rng.poisson(np.where(huge, 0, rates), size=sample_shape).astype(np.float32)
            mask = np.broadcast_to(huge, sample_shape)
            large_rates = np.broadcast_to(rates, sample_shape)[mask]
            counts[mask] = rng.normal(large_rates, np.sqrt(large_rates))
        else:
            counts = rng.poisson(rates, size=sample_shape).astype(np.float32)
        fields.append(counts.reshape(field_shape))
    return tuple(fields)


def render_layer(
    channel: NDArray,
    output: NDArray,
    clouds: tuple[SampledCloud, ...],
    rng: Generator | None,
    density_lut: NDArray[np.float32],
    kind: str,
    fog: float,
) -> None:
    height, width = channel.shape
    padding = math.ceil(max(len(cloud.kernel) // 2 for cloud in clouds) / SAMPLES_PER_PIXEL)
    halo = padding * SAMPLES_PER_PIXEL
    columns = np.clip(np.arange(-padding, width + padding), 0, width - 1)
    band_rows = max(BAND_ROWS, padding)

    def site_bands():
        for start in range(0, height, band_rows):
            stop = min(start + band_rows, height)
            sample_start = start if start else -padding
            sample_stop = stop + padding if stop == height else stop
            yield start, stop, sample_sites(channel, sample_start, sample_stop, columns, clouds, rng, density_lut)

    bands = iter(site_bands())
    previous, current, following = None, next(bands), next(bands, None)
    while current is not None:
        start, stop, fields = current
        shape = ((stop - start) * SAMPLES_PER_PIXEL, width * SAMPLES_PER_PIXEL)
        density = np.full(shape, fog, dtype=np.float32)
        for index, (field, cloud) in enumerate(zip(fields, clouds, strict=True)):
            # Both sides of each band boundary reuse the actual same grain
            # counts. The outer halo samples a continuation of the border tone.
            neighbors = []
            if previous is not None:
                neighbors.append(previous[2][index][-halo:])
            neighbors.append(field)
            if following is not None:
                neighbors.append(following[2][index][:halo])
            sites = np.concatenate(neighbors, axis=0) if len(neighbors) > 1 else field
            spread = convolve1d(sites, cloud.kernel, axis=0)
            spread = convolve1d(spread[halo:-halo, :], cloud.kernel, axis=1)
            density += spread[:, halo:-halo] * cloud.dye_load

        density *= -LOG_10
        np.exp(density, out=density)
        light = density.reshape(stop - start, SAMPLES_PER_PIXEL, width, SAMPLES_PER_PIXEL).mean(axis=(1, 3))
        output[start:stop] = encode_light(light, kind, fog)
        previous, current, following = current, following, next(bands, None)


def encode_light(light: NDArray[np.float32], kind: str, fog: float) -> NDArray:
    if kind == "light":
        return np.clip(np.rint(light * 65535), 0, 65535).astype(np.uint16)
    if kind == "negative":
        scan_density = -np.log10(np.maximum(light, 1 / 65535))
        fraction = (scan_density - fog) / IMAGE_DENSITY_RANGE
        light = EXPOSURE_KNEE * np.sinh(np.clip(fraction, 0, 1) * EXPOSURE_SPAN)
    exposure = light
    srgb = np.where(exposure <= 0.0031308, exposure * 12.92, 1.055 * exposure ** (1 / 2.4) - 0.055)
    return np.clip(np.rint(srgb * 255), 0, 255).astype(np.uint8)
