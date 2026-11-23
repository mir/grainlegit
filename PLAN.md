# Grainlegit Android implementation plan

## Target

Start with the Xiaomi 17 Ultra. Support Android 16 and later (`minSdk 36`), `arm64-v8a`, Vulkan 1.3 compute, and 16 KB memory pages in native libraries. Query the physical device for its Vulkan version, compute limits, and storage formats. Qualify other GPUs by capability and on-device results. Keep the CPU model as a desktop reference. [Xiaomi hardware](https://www.mi.com/global/support/faq/details/KA-667490/) · [Android Vulkan requirements](https://developer.android.com/games/develop/vulkan/native-engine-support) · [16 KB page support](https://developer.android.com/guide/practices/page-sizes)

Use Kotlin and Jetpack Compose for the editor, with Rust as the owner of render state, settings, and GPU scheduling. Use `wgpu` with the Vulkan backend and port the project's own Metal kernels to WGSL. Pass a native Android surface to Rust through a small JNI interface, so the preview can stay on the GPU. Android owns the photo picker, decode and orientation for the initial import, and saving to MediaStore. Store edits separately from the image and persist the seed and stock ID with the settings. [wgpu backends](https://github.com/gfx-rs/wgpu)

## 1. Reproduce the renderer

- Define input as oriented, colour-managed sRGB for the first port. Use the same units, profile values, and tone curves as the references.
- Implement the negative path first. Convert exposure to dye density, sample fine and coarse dye clouds, then integrate transmitted light at the existing 2×2 subpixel resolution. Add the optical print path next, with enlarger blur, print exposure and filtration, paper or print-film response, print grain, and scan optics. Keep the reference quantization at the negative-to-print boundary for the first parity check.
- Start with 32-bit shader math. Measure compact storage and workgroup tuning after parity. Compare deterministic ramps, flat-field means and RMS, adjacent-pixel correlation, thin-edge contrast, and repeatability at a fixed seed using the [existing test tolerances](reference/tests/test_metal.py).

## 2. Get the first image on the Xiaomi

- Build a minimal Android app that imports and displays a JPEG. Report the Vulkan device and driver, tested formats, and timing for setup, first render, and subsequent renders. Verify 16 KB APK and native library alignment.
- Present the GPU texture in a `SurfaceView`. Test surface recreation, rotation, backgrounding, and the driver's error path on the phone.
- Add HEIF import with the same orientation and colour conversion. Include a wide-gamut fixture in decoder tests. Keep RAW/DNG for a later milestone.

## 3. Build the editing interaction

- Use the [FigCraft prototype](design/android-editor.figcraft) for the layout and gesture behaviour. Give the photo the full screen width. Use icons for undo, redo, export, and hold-to-compare. Keep **Fit** and **100%** within reach below the preview.
- Keep one compact ruler above the scrolling tool strip in **Adjust**, **Negative**, and **Optical Print**. Show the active tool and its current value. A tap selects a tool. A swipe that starts on the strip scrolls the strip without changing the edit, including when the gesture ends over the ruler.
- In **Adjust**, keep Crop and the tool-group picker beside the scrolling tools. **Light** includes exposure, brightness, highlights, shadows, contrast, whites, blacks, and curves with master RGB and individual colour channels. **Colour** includes temperature, tint, saturation, vibrance, and a white-balance picker that samples the photo. **Detail** includes sharpening. Sharpen the source before adding simulated grain.
- In **Crop**, keep the grid visible and reuse the fixed ruler for straightening and horizontal and vertical perspective. Use a ratio picker for presets and free crop, with separate 90° rotation, horizontal and vertical flips, and Reset. **Constrain Crop** trims blank edges created by the geometric transform. Retain the other geometry settings when the user selects a different tool. Apply commits the crop and geometry, and Cancel returns to the prior edit.
- Put stock, format, camera exposure, and grain amount in **Negative**. Use 15 visible ruler ticks, the existing six format positions, and −2 to +2 EV for camera exposure. Use each stock's coarse fraction and the desktop editor's 25% grain-colour starting point. In **Optical Print**, offer direct scan, paper, and print film. Enable print exposure and enlarger settings for paper and print film.
- Keep the last completed frame visible during a drag. Schedule the newest requested stop first, preserve grain in Fit, and render a sharper visible crop when the gesture settles. Validate the foreground path before adding bounded neighbour prefetch. Stop speculative work when it delays foreground rendering.
- Implement hold-to-compare, pinch and pan, and a 100% view in which each source pixel maps to one display pixel. Retire stale results on a photo, settings, or viewport change. Verify every edge and corner at 100% and check that neither compare nor a late preview changes the framing.
- Measure touch-to-update, settle time, memory, and sustained responsiveness on the Xiaomi. Start with a target of a grain-bearing update within 100 ms and a settled visible crop within 250 ms during warm editing of `DSC00457.JPG`. Measure independently for Fit and 100%. Keep the photo local.

## 4. Export at full resolution

- Render tiles with an overlap derived from the combined dye cloud, enlarger, and scan sampling radii. Compute physical scale from the full image extent, and seed negative and print grain from absolute coordinates. Compare each 100% viewport pixel to the corresponding export before encoding and test tile seams on a synthetic edge.
- Bound memory across decode, GPU buffers, readback, and encoding. Start with JPEG export with sRGB colour information and support cancellation. Prove 12 MP and 50 MP end to end on the phone. Stress-test 200 MP separately and use the measurements to decide the supported maximum. Verify that the import and encode paths can sustain the chosen size as well as the GPU.

## Licensing and later work

Port the shaders from Grainlegit's Metal reference. Use stock profiles calibrated from independent measurements and record their sources. Track the licenses and notices for dependencies bundled with the app. Broader spectral colour modelling and RAW development are separate follow-up projects.
