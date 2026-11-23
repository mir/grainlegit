# /// script
# requires-python = ">=3.14"
# dependencies = [
#   "numpy>=2.4,<3",
#   "pillow>=12,<13",
#   "scipy>=1.17,<2",
# ]
# ///
"""Render a simulated colour-negative scan or optical print."""

import argparse
from dataclasses import fields
import io
import os
from pathlib import Path
import sys
import tempfile
from time import perf_counter

from filmgrain_core.settings import MEDIA, NEGATIVE_STOCKS, Settings

FORMATS = {".png": "PNG", ".jpg": "JPEG", ".jpeg": "JPEG", ".tif": "TIFF", ".tiff": "TIFF"}


def bounded(low: float, high: float, *, rgb: bool = False):
    def parse(value: str):
        values = tuple(map(float, value.split(","))) if rgb else (float(value),)
        if rgb and len(values) != 3:
            raise argparse.ArgumentTypeError("requires three comma-separated R,G,B values")
        if not all(low <= item <= high for item in values):
            raise argparse.ArgumentTypeError(f"must be between {low:g} and {high:g}")
        return values if rgb else values[0]
    return parse


def positive_int(value: str) -> int:
    number = int(value)
    if number < 1:
        raise argparse.ArgumentTypeError("must be a positive integer")
    return number


def nonnegative_int(value: str) -> int:
    number = int(value)
    if number < 0:
        raise argparse.ArgumentTypeError("must be a nonnegative integer")
    return number


def parse_args() -> tuple[argparse.Namespace, Settings]:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path, help="still photo")
    parser.add_argument("-o", "--output", type=Path, help="PNG, JPEG or TIFF (default: renders/INPUT-film.png beside input)")
    parser.add_argument("--seed", type=nonnegative_int, default=0, help="random seed (default: 0)")
    parser.add_argument("--backend", choices=("auto", "metal", "cpu"), default="auto",
                        help="renderer (default: Metal on Apple Silicon, CPU otherwise)")
    parser.add_argument("--timing", action="store_true", help="report decode, rendering, and encode times")
    parser.add_argument("--overwrite", action="store_true", help="replace an existing output")

    negative = parser.add_argument_group("Negative")
    negative.add_argument("--negative-stock", choices=NEGATIVE_STOCKS, help="negative stock profile (default: generic)")
    negative.add_argument("--grain", type=bounded(0, 3), help="grain amount, 0–3 (default: 1)")
    negative.add_argument("--grain-size", type=bounded(0.1, 10), help="cloud diameter multiplier (default: 1)")
    negative.add_argument("--grain-load", type=bounded(0.1, 8), help="dye per cloud at fixed size (default: 1)")
    negative.add_argument("--grain-colour", type=bounded(0, 1), help="coloured grain fraction, 0–1 (default: 1)")
    negative.add_argument("--coarse-fraction", type=bounded(0, 1), help="share of density from coarse clouds")
    negative.add_argument("--layer-size", type=bounded(0.25, 4, rgb=True), metavar="R,G,B",
                          help="diameter multipliers per colour layer (default: 1,1,1)")
    negative.add_argument("--negative-width", type=bounded(8, 100), metavar="MM",
                          help="long edge of virtual negative (default: 36 mm)")
    negative.add_argument("--exposure", type=bounded(-4, 4), metavar="STOPS", help="camera exposure trim")
    negative.add_argument("--development", type=bounded(-2, 2), help="pull/push response")

    printing = parser.add_argument_group("Optical print")
    printing.add_argument("--print-medium", choices=MEDIA, help="receiving medium (default: direct)")
    printing.add_argument("--enlargement", type=bounded(1, 30), help="print to negative width ratio (default: 6)")
    printing.add_argument("--enlarger-blur", type=bounded(0, 150), metavar="UM", help="lens blur sigma on the print")
    printing.add_argument("--print-exposure", type=bounded(-4, 4), metavar="STOPS", help="overall printer exposure")
    printing.add_argument("--print-lights", type=bounded(-4, 4, rgb=True), metavar="R,G,B",
                          help="printer exposure per channel in stops (default: 0,0,0)")
    printing.add_argument("--print-grain", type=bounded(0, 4), help="print dye-load multiplier (default: 1)")

    scan = parser.add_argument_group("Scan")
    scan.add_argument("--max-edge", type=positive_int, help="limit the longest output edge in pixels")
    scan.add_argument("--scan-blur", type=bounded(0, 8), metavar="PX", help="optical blur sigma in output pixels")
    scan.add_argument("--scan-aperture", type=bounded(1, 8), metavar="PX", help="sampling aperture width (default: 1)")
    args = parser.parse_args()
    controls = Settings(**{
        field.name: value for field in fields(Settings) if (value := getattr(args, field.name)) is not None
    })
    try:
        controls.validate_print()
    except ValueError as error:
        parser.error(str(error))
    return args, controls


def load_photo(path: Path, max_edge: int | None):
    from PIL import Image, ImageCms, ImageOps

    srgb = ImageCms.ImageCmsProfile(ImageCms.createProfile("sRGB"))
    with Image.open(path) as source:
        # Camera JPEGs can carry auxiliary JPEG frames in an MP container.
        # Pillow opens the primary photograph at index zero.
        if source.format != "MPO" and getattr(source, "n_frames", 1) != 1:
            raise ValueError("input must contain a single still image")
        photo = ImageOps.exif_transpose(source)
        profile = photo.info.get("icc_profile")
        has_alpha = "A" in photo.getbands() or "transparency" in photo.info
        alpha = photo.convert("RGBA").getchannel("A") if has_alpha else None
        if profile:
            embedded = ImageCms.ImageCmsProfile(io.BytesIO(profile))
            if photo.mode not in {"L", "RGB", "CMYK", "LAB"}:
                photo = photo.getchannel("L") if photo.mode == "LA" else photo.convert("RGB")
            photo = ImageCms.profileToProfile(photo, embedded, srgb, outputMode="RGB")
        else:
            photo = photo.convert("RGB")
        if alpha is not None:
            photo = Image.composite(photo, Image.new("RGB", photo.size, "white"), alpha)
        if max_edge is not None:
            photo.thumbnail((max_edge, max_edge), Image.Resampling.LANCZOS)
    return photo, srgb.tobytes()


def save_output(photo, destination: Path, profile: bytes, overwrite: bool) -> None:
    format_name = FORMATS[destination.suffix.lower()]
    settings = {"icc_profile": profile}
    if format_name == "JPEG":
        settings.update(quality=97, subsampling=0, optimize=True)
    elif format_name == "TIFF":
        settings.update(compression="tiff_deflate")

    with tempfile.NamedTemporaryFile(dir=destination.parent, prefix=".filmgrain-", delete=False) as file:
        temporary = Path(file.name)
    try:
        photo.save(temporary, format=format_name, **settings)
        if overwrite:
            temporary.replace(destination)
        else:
            os.link(temporary, destination)
    finally:
        temporary.unlink(missing_ok=True)


def render_pixels(pixels, settings: Settings, seed: int, backend: str):
    from filmgrain_core.cpu import render
    from filmgrain_core.metal import MetalUnavailable, is_supported_platform, render as render_metal

    if backend == "metal" or (backend == "auto" and is_supported_platform()):
        try:
            result, seconds = render_metal(pixels, settings, seed)
            return result, "metal", seconds
        except MetalUnavailable as error:
            if backend == "metal":
                raise
            print(f"filmgrain: {error}. Using CPU.", file=sys.stderr)
    return render(pixels, settings, seed), "cpu", None


def main() -> int:
    args, settings = parse_args()
    from PIL import Image, ImageCms
    import numpy as np

    from filmgrain_core.metal import MetalUnavailable

    source = args.input.expanduser()
    destination = (args.output or source.parent / "renders" / f"{source.stem}-film.png").expanduser()
    try:
        if destination.suffix.lower() not in FORMATS:
            raise ValueError("output extension must be .png, .jpg, .jpeg, .tif, or .tiff")
        if source.resolve() == destination.resolve() or (
            source.exists() and destination.exists() and source.samefile(destination)
        ):
            raise ValueError("input and output must be different files")
        if not args.overwrite and destination.exists():
            raise FileExistsError(f"{destination} already exists (choose an output or pass --overwrite)")
        if args.output is None:
            source.stat()
            destination.parent.mkdir(exist_ok=True)
        if not destination.parent.is_dir():
            raise FileNotFoundError(f"output directory does not exist: {destination.parent}")
        started = perf_counter()
        photo, profile = load_photo(source, args.max_edge)
        decoded = perf_counter()
        backend, gpu_seconds = "bypass", None
        if not settings.bypass:
            pixels, backend, gpu_seconds = render_pixels(
                np.asarray(photo), settings, args.seed, args.backend
            )
            photo = Image.fromarray(pixels)
        rendered = perf_counter()
        save_output(photo, destination, profile, args.overwrite)
        encoded = perf_counter()
    except (OSError, ValueError, Image.DecompressionBombError, ImageCms.PyCMSError, MetalUnavailable) as error:
        print(f"filmgrain: {error}", file=sys.stderr)
        return 1
    print(f"{destination} ({photo.width} × {photo.height}, {settings.print_medium}, grain {settings.grain:g}, "
          f"size {settings.grain_size:g}×, seed {args.seed}, {backend})")
    if args.timing:
        print(f"Decode {decoded - started:.3f}s  Render {rendered - decoded:.3f}s  "
              f"Encode {encoded - rendered:.3f}s")
        if gpu_seconds is not None:
            print(f"Metal GPU {gpu_seconds:.3f}s")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
