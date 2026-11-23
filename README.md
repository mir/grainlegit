# Grainlegit

Grainlegit brings Filmgrain’s colour-negative and optical-print simulation to Android. It opens JPEG and HEIF photos, edits them on-device, and exports full-resolution sRGB JPEGs. See the [Android implementation plan](PLAN.md) for the device and performance targets.

| Path | Purpose |
| --- | --- |
| `android/` | Gradle project and Kotlin/Compose editor. Open this directory in Android Studio. |
| `renderer/` | Rust JNI renderer, `wgpu` pipelines, and WGSL shaders, with its own Cargo lockfile and toolchain. |
| `scripts/` | SDK setup, build, install, and native-library verification. Gradle invokes `build-android-native` when packaging the app. |
| [`design/android-editor.figcraft`](design/android-editor.figcraft) | Editable Android prototype. Open in FigCraft and choose **Try the editor** in Present mode. |
| `src/` | Portable Rust settings, defaults, and stock selection |
| `profiles/` | Emulsion parameters shared by Rust and the reference renderers, with [calibration sources and method](profiles/grain-calibration.md) |
| `reference/` | Python CLI and CPU model, Apple Silicon Metal renderer, calibration script, and renderer parity tests |

## Build and run on Android

Install a JDK (17 or 21), `rustup`, and Android SDK Command-line Tools (latest). Set `ANDROID_HOME` if your SDK is outside `~/Library/Android/sdk` on macOS or `~/Android/Sdk` on Linux. The Gradle wrapper downloads Gradle.

```sh
scripts/android setup      # API 36 SDK, pinned NDK, build tools, adb, and Rust Android target
scripts/android build     # android/app/build/outputs/apk/debug/app-debug.apk
scripts/android verify    # APK ZIP and arm64 ELF alignment for 16 KiB page devices
scripts/android devices   # check that adb sees the phone
scripts/android run       # build, install, and launch
```

The first device target is the Xiaomi 17 Ultra. Enable USB debugging, connect the phone, and accept its debugging prompt. Open a photo, tap **100%** for a source-pixel crop, and hold the half-circle button to see the original. Use **Fit** to return to the full frame. **Adjust** includes tone, colour, detail, curves, and crop. **Negative** selects film stock, format, camera exposure, and grain. **Optical Print** includes direct scan, paper, print film, and scan optics.

The app keeps the original intact and saves the last edit and its seed locally. Use the export arrow to save a JPEG to `Pictures/Grainlegit`. The exporter decodes and renders padded tiles and stages RGB rows in the app cache before encoding. Allow approximately three bytes of temporary disk space per output pixel, plus space for the JPEG. Cancelled exports remove their temporary files and any unpublished MediaStore entry.

Use `scripts/android logs` for the adapter and driver, Vulkan setup, and render-worker completion timings. The timing logs end at GPU completion. Collect an Android FrameTimeline/Perfetto trace for input-to-display measurements. Run the WGSL direct-scan, optical-print, and tile parity checks on macOS with `(cd renderer && cargo test --locked)`.

To reproduce the slider measurements on macOS, run `uv run --python 3.14 scripts/measure-renderer.py`. It renders a control chart through the same WGSL pipeline and saves the source chart, response diagrams, and CSV under `local/android-testing/`.

## Run the reference

```sh
cargo test --locked
uv run --python 3.14 reference/tests/test_metal.py
uv run --python 3.14 reference/filmgrain.py /path/to/photo.jpg --backend cpu -o /path/to/render.png
```

The tests use synthetic inputs. On Apple Silicon they also exercise Metal. To render a reference using Metal, pass `--backend metal` to the reference CLI. Pass `--print-medium paper` to render an optical print. Keep private test photos and local renders under `local/` or outside this repository.

## Provenance and license

The starting files came from the Filmgrain project in `album-turkey` at base commit `23c65d1`, including the working changes with the current stock profiles, crop handling, and preview renderer. [MIT](LICENSE) covers this project's original code and documentation. The profile calibration notes link to the external measurements. The scans, datasets, and manufacturer documents stay at their sources.

Run `uv run --python 3.14 scripts/dependency-notices.py` to inventory the resolved Android and Rust dependency licenses and collect Rust notice texts under `local/`. The encoder's sRGB ICC profile was generated with Little CMS.
