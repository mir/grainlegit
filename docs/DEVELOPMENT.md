# Developing Grainlegit

This page covers building, testing and releasing Grainlegit. For what the app does, see the [README](../README.md).

The [implementation plan](../PLAN.md) covers the renderer design, preview scheduling, tiled export and performance targets.

## Repository layout

| Path | Purpose |
| --- | --- |
| `android/` | Gradle project and Kotlin/Compose editor. Open this directory in Android Studio. |
| `renderer/` | Rust JNI renderer, `wgpu` pipelines, and WGSL shaders, with its own Cargo lockfile and toolchain. |
| `scripts/` | SDK setup, build, install, and native-library verification. Gradle invokes `build-android-native` when packaging the app. |
| `src/` | Portable Rust settings, defaults, and stock selection |
| `profiles/` | Emulsion parameters shared by Rust and the reference renderers, with [calibration sources and method](../profiles/grain-calibration.md) |
| `reference/` | Python CLI and CPU model, Apple Silicon Metal renderer, calibration script, and renderer parity tests |

## Build and run on Android

Install a JDK (17 or 21), `rustup`, and Android SDK Command-line Tools (latest). Set `ANDROID_HOME` if your SDK is outside `~/Library/Android/sdk` on macOS or `~/Android/Sdk` on Linux. The Gradle wrapper downloads Gradle.

```sh
scripts/android setup      # API 36 SDK, pinned NDK, build tools, adb, and Rust Android target
scripts/ai-models          # stage the on-device AI Erase assets
scripts/android build     # android/app/build/outputs/apk/debug/app-debug.apk
scripts/android verify    # APK ZIP and arm64 ELF alignment for 16 KiB page devices
scripts/android devices   # check that adb sees the phone
scripts/android run       # build, install, and launch
```

The first device target is the Xiaomi 17 Ultra. Enable USB debugging, connect the phone, and accept its debugging prompt. Open a photo and pinch out for a source-pixel crop or pinch in to see the full frame. Hold the compare icon next to **Save copy** to see the original.

The app keeps the original intact and saves recent edits and their seeds locally. Tap **Save copy** to save a JPEG to `Pictures/Grainlegit`. The exporter decodes and renders padded tiles and stages RGB rows in the app cache before encoding. Allow approximately three bytes of temporary disk space per output pixel, plus space for the JPEG. Cancelled exports remove their temporary files and any unpublished MediaStore entry.

### SAM 3.1 on SM8850

Use the [SAM 3.1 ONNX export](https://huggingface.co/smile-forge/sam3.1-onnx/tree/47874a1f1d996211f6cc11bc6201a36af87a3fd6). Place `image-encoder.onnx`, `image-encoder.onnx.data`, and `click-decoder.onnx` together in a local model directory. With the QAIRT 2.52 x86-64 Linux libraries on `LD_LIBRARY_PATH`, run `uv run scripts/compile-sam31.py MODEL_DIR` to generate the context under `MODEL_DIR/sm8850`.

Debug builds bundle SAM 3.1 when the compiled files are under `local/ai-models/selection-study/sam3.1`. For another model directory, set `GRAINLEGIT_SAM31_MODEL_DIR` or pass `-Psam31ModelDir=/path/to/MODEL_DIR` directly to Gradle. `scripts/android install` and `scripts/android run` require these assets when targeting an SM8850 phone. On opening Erase, `scripts/android logs` reports the SAM 3.1 NPU encoder and CPU decoder or the reason it used EdgeTAM.

Build the unsigned APK with `android/gradlew -p android :app:assembleRelease`. Run `scripts/package-android-sm8850 MODEL_DIR OUTPUT_APK` to add the precompiled model, align and sign the APK. The script uses `release.p12` and `release.pass` in `${XDG_CONFIG_HOME:-$HOME/.config}/grainlegit`. Override the paths with `GRAINLEGIT_RELEASE_KEYSTORE` and `GRAINLEGIT_RELEASE_PASSWORD_FILE`.

On SM8850, open **Erase → Download OSOR** to install the Phase II removal model. It uses about 4.3 GiB of storage and runs on the phone's NPU. Once installed, Erase uses it for the precise fill.

For the model release assets, run `scripts/package-osor-sm8850 COMPILED_MODEL_DIR OUTPUT_DIR`. It checks the pinned context hashes before creating three packs with the [model license](../android/app/src/main/assets/licenses/OSOR-SDXL-Model.txt) and [provenance notice](../android/app/src/main/assets/licenses/OSOR-SDXL-NOTICE.txt).

Use `scripts/android logs` for the adapter and driver, Vulkan setup, and render-worker completion timings. The timing logs end at GPU completion. Collect an Android FrameTimeline/Perfetto trace for input-to-display measurements. Run the WGSL direct-scan, optical-print, and tile parity checks on macOS with `(cd renderer && cargo test --locked)`.

To reproduce the slider measurements on macOS, run `uv run --python 3.14 scripts/measure-renderer.py`. It renders a control chart through the same WGSL pipeline and saves the source chart, response diagrams, and CSV under `local/android-testing/`.

## Run the reference

```sh
cargo test --locked
uv run --python 3.14 reference/tests/test_metal.py
uv run --python 3.14 reference/filmgrain.py /path/to/photo.jpg --backend cpu -o /path/to/render.png
```

The tests use synthetic inputs. On Apple Silicon they also exercise Metal. To render a reference using Metal, pass `--backend metal` to the reference CLI. Pass `--print-medium paper` to render an optical print. Keep private test photos and local renders under `local/` or outside this repository.

## Future ideas

Ideas for matching grain and texture in Erase fills that we have not built yet. An offline test fitted the app's dye-cloud grain model to scans with CMA-ES. On colour scans it did not beat the current measured top-up, so the app only redraws its own model on reopened exports.

| Idea | What it would add | Starting points |
| --- | --- | --- |
| Spectrum-shaped noise with a per-tone lookup table | One white-noise field filtered to the measured colour power spectrum, then mapped through quantiles of the real grain residual in each brightness bin. Gives clumps, tone-dependent strength and channel correlation, fitted in closed form. | [Heitz & Neyret 2018](https://dl.acm.org/doi/10.1145/3233304), [random-phase noise](http://www.ipol.im/pub/art/2011/ggm_rpn/index.html), [PGNM report](https://arxiv.org/abs/2610.39868) |
| AV1-style grain estimation | Autoregressive grain filter and a piecewise-linear strength curve over brightness, estimated from denoised flat blocks. Cheap fallback. | [SVT-AV1 film grain](https://github.com/AOMediaCodec/SVT-AV1/blob/main/Docs/Appendix-Film-Grain-Synthesis.md), [Norkin & Birkbeck 2018](https://norkin.org/pdf/DCC_2018_AV1_film_grain.pdf) |
| Closed-form Boolean grain estimation | Cloud size and blur estimated from a scan's residual autocorrelation and variance per tone, as a starting point for the search. | [Zhang et al. 2023](https://dl.acm.org/doi/10.1145/3592127) |
| Neural grain | A small network that predicts grain parameters from a patch (to seed the search), or synthesises grain directly. Needs training data from real scans and licence checks. | [FGA-NN](https://arxiv.org/abs/2506.14350), [Style-FG](https://dl.acm.org/doi/10.1145/3587819.3590992), [Neural Film Grain Rendering](https://github.com/Gwilherm-LESNE/Neural_Film_Grain_Rendering) |
| Oriented and periodic texture | Fabric, weaves and other directional or repeating structure that is finer than the model fill: oriented spectra (Gabor noise by example, steerable pyramid statistics) and periodic plus random decomposition, with patch copying kept for larger elements. | [Gabor noise by example](https://graphics.cs.kuleuven.be/publications/GLLD12GNBE), [Portilla–Simoncelli](https://docs.plenoptic.org/), [Moisan 2011](https://doi.org/10.1007/s10851-010-0227-1) |
| Distribution losses for the fit | Sliced-Wasserstein distance on band responses as the search objective, so the fit matches whole distributions rather than standard deviations. | [Heitz et al. 2021](https://openaccess.thecvf.com/content/CVPR2021/html/Heitz_A_Sliced_Wasserstein_Loss_for_Neural_Texture_Synthesis_CVPR_2021_paper.html) |

Gaussian splatting fits one image and would reproduce the surroundings rather than make new grain, so it is not on the list.

## Dependency notices

Run `uv run --python 3.14 scripts/dependency-notices.py` to inventory the resolved Android and Rust dependency licenses and collect Rust notice texts under `local/`. The encoder's sRGB ICC profile was generated with Little CMS.
