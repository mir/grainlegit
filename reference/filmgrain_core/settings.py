"""Physical controls shared by the Python CPU and Metal backends."""

from dataclasses import dataclass
import json
from pathlib import Path


PROFILE_PATH = Path(__file__).resolve().parents[2] / "profiles" / "emulsions.json"
PROFILES = json.loads(PROFILE_PATH.read_bytes())
NEGATIVE_STOCKS = {
    "generic": PROFILES["negative"],
    **{stock["id"]: stock for stock in PROFILES["negative_stocks"]},
}
MIN_VISIBLE_GRAIN = 1e-6
MEDIA = ("direct", "paper", "film")
NEUTRAL_LIGHTS = (0.0, 0.0, 0.0)


@dataclass(frozen=True)
class Settings:
    grain: float = 1.0
    grain_size: float = 1.0
    grain_load: float = 1.0
    coarse_fraction: float = -1.0
    layer_size: tuple[float, float, float] = (1.0, 1.0, 1.0)
    negative_width: float = 36.0
    exposure: float = 0.0
    development: float = 0.0
    enlargement: float = 6.0
    enlarger_blur: float = 0.0
    print_exposure: float = 0.0
    print_lights: tuple[float, float, float] = NEUTRAL_LIGHTS
    print_grain: float = 1.0
    scan_blur: float = 0.0
    scan_aperture: float = 1.0
    grain_colour: float = 1.0
    print_medium: str = "direct"
    negative_stock: str = "generic"

    @property
    def scan_optics(self) -> bool:
        return self.scan_blur > 0 or self.scan_aperture > 1

    @property
    def bypass(self) -> bool:
        return (
            self.grain <= MIN_VISIBLE_GRAIN
            and self.negative_stock == "generic"
            and self.exposure == self.development == 0
            and self.print_medium == "direct"
            and not self.scan_optics
        )

    def validate_print(self) -> None:
        if self.print_medium == "direct" and (
            self.enlargement != 6 or self.enlarger_blur or self.print_exposure
            or self.print_lights != NEUTRAL_LIGHTS or self.print_grain != 1
        ):
            raise ValueError("select --print-medium paper or film to use optical printing controls")
