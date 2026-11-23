# Negative grain calibration

The fit sets the peak optical density of the fine and coarse dye clouds in each layer. It matches the RMS density fluctuation through a 48 µm circular aperture. Cloud sizes and fine/coarse fractions use the existing profile estimates. The aperture measurements include scanner optics, and their spatial sampling does not resolve the fine clouds individually.

| Stock | Reference net density, R / G / B | Estimated RMS × 1000, R / G / B | Basis |
|---|---|---|---|
| Ektar 100 | 0.534 / 0.531 / 0.537 | 7.47 / 7.36 / 13.62 | Two Nikon Coolscan V ED uniform exposures |
| Gold 200 | 0.585 / 0.559 / 0.519 | 14.54 / 18.58 / 33.26 | Smooth patches from three Sony RAW negative captures per colour |
| Portra 400 | 0.626 / 0.615 / 0.606 | 14.56 / 12.82 / 15.96 | Relative Print Grain Index estimate, with the prior layer balance |
| Vision3 500T | 1.000 / 1.000 / 1.000 | 6.41 / 7.09 / 19.17 | Kodak’s diffuse RMS granularity curves, digitized from the vector artwork |

R, G, and B are the density measurement bands, corresponding to cyan, magenta, and yellow dye in the model. The still-photo presets use their model’s density at input sRGB code 128 as the reference. The estimates apply with the grain controls at 1×, unmodified coarse fractions, and full grain colour.

- **Ektar 100.** At 4,000 dpi the TIFFs sample every 6.35 µm. The fit treats the 14-bit-stepped codes as linear scanner samples despite the GIMP sRGB tag. Interpreting the tag as an encoding curve would roughly double the RMS estimate. The scans cover medium density, so the fit anchors them to the model’s middle gray. The two scans’ aperture RMS estimates agree within 2% in each channel. Their adjacent-pixel correlations span 0.61–0.65, equivalent to about a 4.5–4.8 µm Gaussian sigma for the combined film and scanner response. [Scan 03](https://commons.wikimedia.org/wiki/File:KodakEktar100GrainScan03.tif) · [Scan 04](https://commons.wikimedia.org/wiki/File:KodakEktar100GrainScan04.tif) · [Nikon scanner specifications](https://www.nikonusa.com/fileuploads/pdfs/2004_coolscan.pdf)
- **Gold 200.** The clear film border sets each capture’s base level. The fit uses approximately 10.75 µm per Bayer colour sample, selects smooth patches at net densities 0.25–0.65, and scales their RMS to the reference density using the model’s square-root density trend. It takes the median within each frame and then across the three frames. The sample estimates include scanner noise and remaining scene texture, so Gold has the most uncertain direct strength estimate. [Raw dataset](https://github.com/DhaliwalX/gold200-dataset)
- **Portra 400.** Kodak reports PGI 59 for Portra and 38 for Ektar at 8.8×. A fit to both stocks’ within-stock enlargement series gives about 26 PGI units per doubling of magnification. Treating a doubling of magnification as a doubling of RMS at a fixed output aperture gives a rough 1.74× Portra-to-Ektar strength target. This is a relative estimate under that assumption. The fit applies a single multiplier to Portra’s dye loads, preserving their relative balance. [Portra data](https://www.kodakprofessional.com/sites/default/files/2025-07/e4050.pdf) · [Ektar data](https://kodakprofessional.com/sites/default/files/2025-07/e4046.pdf)
- **Vision3 500T.** The script reads the individual density and granularity traces and subtracts each density trace’s base-plus-fog level before finding the RMS at net density 1. The published curves show a different density dependence in the toe. This fit anchors one density within the existing Poisson model. [Kodak data, page 4](https://www.kodak.com/content/products-brochures/Film/VISION3-500T-Color-Negative-Film-7219-TECHNICAL-DATA.pdf#page=4)

The estimator removes the plane from each sample in log density, rejects tiles with outliers or excessive low-frequency structure, and measures density after averaging transmission. It predicts the equivalent aperture RMS from the cloud transmission-loss autocorrelation and solves for dye load using the model’s flat-field event rate.

Run the estimator with the two original Ektar TIFFs, the Gold ZIP, and the July 2015 Kodak PDF. It prints the scan measurements, targets, and fitted centre densities.

```sh
uv run --python 3.14 reference/estimate_grain.py \
  --ektar KodakEktar100GrainScan03.tif KodakEktar100GrainScan04.tif \
  --gold gold200-dataset-2026-10-14.zip \
  --vision3 VISION3-500T-Color-Negative-Film-7219-TECHNICAL-DATA.pdf
```
