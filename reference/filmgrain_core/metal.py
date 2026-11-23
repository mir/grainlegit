"""Build and call the Apple Silicon renderer."""

import ctypes as ct
from dataclasses import astuple
from functools import cache
import hashlib
from pathlib import Path
import platform
import subprocess
from tempfile import TemporaryDirectory

import numpy as np
from numpy.typing import NDArray
from .settings import MEDIA, NEGATIVE_STOCKS, PROFILE_PATH, Settings


RGB = ct.c_double * 3


class NativeSettings(ct.Structure):
    _fields_ = [
        ("grain", ct.c_double), ("grain_size", ct.c_double), ("grain_load", ct.c_double),
        ("coarse_fraction", ct.c_double), ("layer_size", RGB), ("negative_width", ct.c_double),
        ("exposure", ct.c_double), ("development", ct.c_double), ("enlargement", ct.c_double),
        ("enlarger_blur", ct.c_double), ("print_exposure", ct.c_double), ("print_lights", RGB),
        ("print_grain", ct.c_double), ("scan_blur", ct.c_double), ("scan_aperture", ct.c_double),
        ("grain_colour", ct.c_double), ("print_medium", ct.c_uint32), ("negative_stock", ct.c_uint32),
    ]


class MetalUnavailable(RuntimeError):
    """Metal could not initialize or run the image."""


def is_supported_platform() -> bool:
    return platform.system() == "Darwin" and platform.machine() == "arm64"


@cache
def load_metal():
    if not is_supported_platform():
        raise MetalUnavailable("Metal requires native Python on an Apple Silicon Mac")
    directory = Path(__file__).resolve().parents[1]
    source = directory / "native" / "filmgrain.m"
    try:
        header = source.with_name("filmgrain_native.h")
        cache_key = hashlib.blake2s(source.read_bytes() + header.read_bytes(), digest_size=12).hexdigest()
        cache_dir = Path.home() / "Library" / "Caches" / "filmgrain"
        cache_dir.mkdir(parents=True, exist_ok=True)
        library = cache_dir / f"metal-{cache_key}.dylib"
        if not library.is_file():
            with TemporaryDirectory(dir=cache_dir) as build_dir:
                built = Path(build_dir) / library.name
                subprocess.run(
                    [
                        "xcrun", "clang", "-arch", "arm64", "-O3", "-fobjc-arc", "-dynamiclib",
                        "-framework", "Foundation", "-framework", "Metal", source, "-o", built,
                    ],
                    check=True,
                    capture_output=True,
                    text=True,
                )
                built.replace(library)

        native = ct.CDLL(str(library))
        shader = (directory / "shaders" / "grain.metal").read_bytes()
        profile = PROFILE_PATH.read_bytes()
    except subprocess.CalledProcessError as error:
        raise MetalUnavailable(f"Metal host build failed: {error.stderr.strip()}") from error
    except OSError as error:
        raise MetalUnavailable(str(error)) from error

    native.filmgrain_render.argtypes = (
        ct.c_char_p, ct.c_char_p, ct.c_void_p, ct.c_void_p,
        ct.c_uint32, ct.c_uint32, ct.c_uint32, ct.POINTER(NativeSettings), ct.c_uint64, ct.POINTER(ct.c_double),
        ct.c_void_p, ct.c_size_t,
    )
    native.filmgrain_render.restype = ct.c_int
    return native, shader, profile


def render(
    pixels: NDArray[np.uint8], settings: Settings, seed: int
) -> tuple[NDArray[np.uint8], float]:
    if settings.bypass:
        return pixels.copy(), 0.0
    native, shader, profile = load_metal()
    image = np.ascontiguousarray(pixels)
    height, width, channels = image.shape
    output = np.empty_like(image)
    *values, medium, stock = astuple(settings)
    controls = NativeSettings(*values, MEDIA.index(medium), tuple(NEGATIVE_STOCKS).index(stock))
    if seed >= 1 << 64:
        seed = int(np.random.SeedSequence(seed).generate_state(1, dtype=np.uint64)[0])
    seconds = ct.c_double()
    error = ct.create_string_buffer(4096)
    status = native.filmgrain_render(
        shader, profile, image.ctypes.data, output.ctypes.data, width, height, channels,
        ct.byref(controls), seed, ct.byref(seconds), error, len(error),
    )
    if status != 0:
        raise MetalUnavailable(error.value.decode("utf-8", errors="replace"))
    return output, seconds.value
