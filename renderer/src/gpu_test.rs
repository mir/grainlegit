use crate::edit::Edit;
use crate::gpu::{Gpu, Image};
use crate::preview::{Frame, Photo, Presenter};

const SEED: u64 = 457;

#[test]
fn negative_preserves_grain_across_bands_and_viewport_crops() {
    pollster::block_on(async {
        let instance = wgpu::Instance::new(&wgpu::InstanceDescriptor {
            backends: wgpu::Backends::METAL,
            ..Default::default()
        });
        let adapter = instance.request_adapter(&Default::default()).await.unwrap();
        let gpu = Gpu::new(&adapter).await.unwrap();
        let [width, height] = [3809, 113];
        let source = pixels(width, height, 0);
        let mut full = gpu
            .upload([width, height], [0, 0], width * 4, &source)
            .unwrap();
        let full_scan = scan(&gpu, &mut full, [width, height]);
        assert_ne!(full_scan, source);
        let crop_size = [203, 103];
        let origin = [1801, 5];
        let portrait = pixels(crop_size[0], crop_size[1], origin[0]);
        let mut crop = gpu
            .upload(crop_size, origin, crop_size[0] * 4, &portrait)
            .unwrap();
        let crop_scan = scan(&gpu, &mut crop, [width, height]);
        for y in 4..crop_size[1] - 4 {
            let full_index = ((y + origin[1]) * width + origin[0] + 4) as usize * 4;
            let crop_index = (y * crop_size[0] + 4) as usize * 4;
            let length = (crop_size[0] as usize - 8) * 4;
            assert_eq!(
                &full_scan[full_index..full_index + length],
                &crop_scan[crop_index..crop_index + length]
            );
        }

        let mut flat_field: Vec<u8> = Vec::new();
        for row in full_scan.chunks_exact(width as usize * 4) {
            flat_field.extend(row[..1500 * 4].chunks_exact(4).flat_map(|rgba| &rgba[..3]));
        }
        let count = flat_field.len() as f64;
        let mean = flat_field.iter().map(|&v| f64::from(v)).sum::<f64>() / count;
        let rms = (flat_field
            .iter()
            .map(|&v| (f64::from(v) - mean).powi(2))
            .sum::<f64>()
            / count)
            .sqrt();
        eprintln!("WGSL flat-field mean={mean:.4} RMS={rms:.4}");
        // Values from the same fixture and seed in reference/shaders/grain.metal.
        assert!((mean - 128.1918).abs() < 0.2);
        assert!((rms / 4.9680 - 1.0).abs() < 0.05);

        let green: Vec<_> = flat_field
            .chunks_exact(3)
            .map(|rgb| f64::from(rgb[1]))
            .collect();
        let green_mean = green.iter().sum::<f64>() / green.len() as f64;
        let [cross, left_var, right_var] = green
            .chunks_exact(1500)
            .flat_map(|row| row.windows(2))
            .fold([0.0; 3], |[cross, left, right], pair| {
                let a = pair[0] - green_mean;
                let b = pair[1] - green_mean;
                [cross + a * b, left + a * a, right + b * b]
            });
        let correlation = cross / (left_var * right_var).sqrt();
        assert!((correlation - 0.2442).abs() < 0.025);

        let mut contrast: Vec<_> = full_scan
            .chunks_exact(width as usize * 4)
            .map(|row| {
                let sum_rgb = |x: usize| {
                    row[x * 4..x * 4 + 3]
                        .iter()
                        .map(|&v| f64::from(v))
                        .sum::<f64>()
                };
                (sum_rgb(1902) + sum_rgb(1903)) / 6.0 - sum_rgb(1904) / 3.0
            })
            .collect();
        contrast.sort_by(f64::total_cmp);
        assert!((contrast[contrast.len() / 2] - 14.0).abs() < 1.5);

        // Check that the same surface shader draws the grain and the original at 1:1.
        let mut photo = Photo {
            size: [width, height],
            seed: SEED,
            fit: full,
            detail: Some(crop),
            lens_light: None,
        };
        let presenter = Presenter::new(&gpu, wgpu::TextureFormat::Rgba8UnormSrgb);
        let extent = wgpu::Extent3d {
            width: 23,
            height: 19,
            depth_or_array_layers: 1,
        };
        let target = gpu.device.create_texture(&wgpu::TextureDescriptor {
            label: Some("preview test target"),
            size: extent,
            mip_level_count: 1,
            sample_count: 1,
            dimension: wgpu::TextureDimension::D2,
            format: wgpu::TextureFormat::Rgba8UnormSrgb,
            usage: wgpu::TextureUsages::RENDER_ATTACHMENT | wgpu::TextureUsages::COPY_SRC,
            view_formats: &[],
        });
        let target_view = target.create_view(&Default::default());
        let [x, y] = [1891, 41];
        let mut frame = Frame {
            edit: Edit::default(),
            compare: false,
            center: [x as f32 + 11.5, y as f32 + 9.5],
            scale: 1.0,
            use_detail: true,
            preview_to_source: None,
        };
        for expected in [&full_scan, &source] {
            let mut encoder = gpu.device.create_command_encoder(&Default::default());
            presenter
                .encode(
                    &gpu,
                    &mut encoder,
                    &target_view,
                    [extent.width, extent.height],
                    Some(&mut photo),
                    &frame,
                )
                .unwrap();
            let actual = read(&gpu, encoder, &target, extent);
            for row in 0..extent.height {
                let offset = 4 * ((y + row) * width + x) as usize;
                let length = 4 * extent.width as usize;
                let start = row as usize * length;
                assert_eq!(
                    &actual[start..start + length],
                    &expected[offset..offset + length]
                );
            }
            frame.compare = true;
        }

        // The crop editor mirrors the cached Fit image in the presentation pass.
        frame.center[0] = width as f32 - frame.center[0];
        frame.preview_to_source = Some([-1.0, 0.0, width as f32, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0]);
        frame.compare = false;
        let mut encoder = gpu.device.create_command_encoder(&Default::default());
        presenter
            .encode(
                &gpu,
                &mut encoder,
                &target_view,
                [extent.width, extent.height],
                Some(&mut photo),
                &frame,
            )
            .unwrap();
        let actual = read(&gpu, encoder, &target, extent);
        for row in 0..extent.height {
            for column in 0..extent.width {
                let source_column = x + extent.width - 1 - column;
                let offset = 4 * ((y + row) * width + source_column) as usize;
                let start = 4 * (row * extent.width + column) as usize;
                assert_eq!(&actual[start..start + 4], &full_scan[offset..offset + 4]);
            }
        }
    });
}

fn pixels(width: u32, height: u32, origin_x: u32) -> Vec<u8> {
    (0..height)
        .flat_map(|_| {
            (origin_x..origin_x + width).flat_map(|x| {
                let value = if x == 1904 { 110 } else { 128 };
                [value, value, value, 255]
            })
        })
        .collect()
}

fn scan(gpu: &Gpu, image: &mut Image, size: [u32; 2]) -> Vec<u8> {
    let mut encoder = gpu.device.create_command_encoder(&Default::default());
    gpu.encode(&mut encoder, image, &Edit::default(), size, SEED, None)
        .unwrap();
    read(gpu, encoder, &image.output, image.extent)
}

fn read(
    gpu: &Gpu,
    mut encoder: wgpu::CommandEncoder,
    texture: &wgpu::Texture,
    extent: wgpu::Extent3d,
) -> Vec<u8> {
    let row_bytes = extent.width * 4;
    let stride = row_bytes.next_multiple_of(wgpu::COPY_BYTES_PER_ROW_ALIGNMENT);
    let buffer = gpu.device.create_buffer(&wgpu::BufferDescriptor {
        label: Some("GPU test readback"),
        size: u64::from(stride) * u64::from(extent.height),
        usage: wgpu::BufferUsages::COPY_DST | wgpu::BufferUsages::MAP_READ,
        mapped_at_creation: false,
    });
    encoder.copy_texture_to_buffer(
        texture.as_image_copy(),
        wgpu::TexelCopyBufferInfo {
            buffer: &buffer,
            layout: wgpu::TexelCopyBufferLayout {
                offset: 0,
                bytes_per_row: Some(stride),
                rows_per_image: None,
            },
        },
        extent,
    );
    let submission = gpu.queue.submit([encoder.finish()]);
    let (sender, receiver) = std::sync::mpsc::channel();
    buffer
        .slice(..)
        .map_async(wgpu::MapMode::Read, move |result| {
            sender.send(result).unwrap()
        });
    gpu.device
        .poll(wgpu::PollType::wait_for(submission))
        .unwrap();
    receiver.recv().unwrap().unwrap();
    let mapped = buffer.slice(..).get_mapped_range();
    mapped
        .chunks_exact(stride as usize)
        .flat_map(|row| &row[..row_bytes as usize])
        .copied()
        .collect()
}

#[test]
fn optical_print_matches_reference_and_tiled_readback() {
    pollster::block_on(async {
        let instance = wgpu::Instance::new(&wgpu::InstanceDescriptor {
            backends: wgpu::Backends::METAL,
            ..Default::default()
        });
        let adapter = instance.request_adapter(&Default::default()).await.unwrap();
        let gpu = Gpu::new(&adapter).await.unwrap();
        let width = 1503;
        let height = 93;
        let input = |origin: u32, w: u32| -> Vec<u8> {
            (0..height)
                .flat_map(|_| {
                    (origin..origin + w).flat_map(|x| {
                        if x < 750 {
                            [128, 128, 128, 255]
                        } else {
                            [143, 112, 94, 255]
                        }
                    })
                })
                .collect()
        };
        let mut full = gpu
            .upload([width, height], [0, 0], width * 4, &input(0, width))
            .unwrap();
        let left = 720;
        let tile_width = 70;
        let mut tile = gpu
            .upload(
                [tile_width, height],
                [left, 0],
                tile_width * 4,
                &input(left, tile_width),
            )
            .unwrap();
        for (print_medium, expected) in [
            (1, [69.1574, 59.9647, 50.4431]),
            (2, [57.9931, 45.2539, 33.3194]),
        ] {
            let edit = Edit {
                film: grainlegit::Settings {
                    print_medium,
                    grain_colour: 0.25,
                    negative_stock: 1,
                    exposure: -0.25,
                    print_exposure: 0.4,
                    print_lights: [0.12, -0.08, 0.0],
                    enlarger_blur: 38.0,
                    scan_blur: 0.8,
                    scan_aperture: 1.5,
                    ..Default::default()
                },
                ..Default::default()
            };
            let scan = gpu
                .read_rgb(
                    &mut full,
                    &edit,
                    [width, height],
                    SEED,
                    [0, 0, width, height],
                    None,
                )
                .unwrap();
            let crop = gpu
                .read_rgb(
                    &mut tile,
                    &edit,
                    [width, height],
                    SEED,
                    [12, 0, 40, height],
                    None,
                )
                .unwrap();
            for (index, row) in scan.chunks_exact(width as usize * 3).enumerate() {
                let start = (left as usize + 12) * 3;
                assert_eq!(
                    &row[start..start + 120],
                    &crop[index * 120..(index + 1) * 120]
                );
            }
            let mut sum = [0.0; 3];
            for pixel in scan.chunks_exact(3) {
                for i in 0..3 {
                    sum[i] += f64::from(pixel[i]);
                }
            }
            let found = sum.map(|sum| sum / f64::from(width * height));
            eprintln!("Print {print_medium}: {found:?}");
            // Values calculated by the reference Metal renderer with the same settings.
            for (result, reference) in found.into_iter().zip(expected) {
                assert!((result - reference).abs() < 0.35);
            }
        }
    });
}

#[test]
fn write_measurement_renders() {
    let Some(directory) = std::env::var_os("GRAINLEGIT_MEASUREMENTS") else {
        return;
    };
    let directory = std::path::PathBuf::from(directory);
    std::fs::create_dir_all(&directory).unwrap();
    const WIDTH: u32 = 768;
    const HEIGHT: u32 = 512;
    let levels = [16, 40, 64, 100, 128, 161, 204, 240];
    let colours = [
        [165, 112, 98],
        [202, 68, 60],
        [85, 120, 190],
        [74, 145, 91],
        [196, 168, 71],
        [88, 165, 171],
        [186, 191, 178],
        [136, 115, 101],
    ];
    let mut rgba = Vec::new();
    for y in 0..HEIGHT {
        for x in 0..WIDTH {
            let patch = x as usize / 96;
            let sample = match y {
                0..=115 => [levels[patch]; 3],
                128..=245 => [(x * 255 / (WIDTH - 1)) as u8; 3],
                264..=379 => {
                    let level = 90.0 + (0.5 + (x as f32 - 384.0) / 6.0).clamp(0.0, 1.0) * 100.0;
                    [level.round() as u8; 3]
                }
                400..=511 => colours[patch],
                _ => [29; 3],
            };
            rgba.extend([sample[0], sample[1], sample[2], 255]);
        }
    }
    pollster::block_on(async {
        let instance = wgpu::Instance::new(&wgpu::InstanceDescriptor {
            backends: wgpu::Backends::METAL,
            ..Default::default()
        });
        let adapter = instance.request_adapter(&Default::default()).await.unwrap();
        let gpu = Gpu::new(&adapter).await.unwrap();
        let mut image = gpu
            .upload([WIDTH, HEIGHT], [0, 0], WIDTH * 4, &rgba)
            .unwrap();
        let clean = Edit {
            film: grainlegit::Settings {
                grain: 0.0,
                ..Default::default()
            },
            ..Default::default()
        };
        let mut scenarios = vec![("source", clean)];
        let changes: &[(&str, fn(&mut Edit))] = &[
            ("exposure-minus", |e| e.adjust.light[0] = -1.0),
            ("exposure-plus", |e| e.adjust.light[0] = 1.0),
            ("brightness", |e| e.adjust.light[1] = 0.8),
            ("highlights", |e| e.adjust.light[2] = 0.8),
            ("shadows", |e| e.adjust.light[3] = 0.8),
            ("contrast", |e| e.adjust.tones[0] = 0.8),
            ("whites", |e| e.adjust.tones[1] = 0.8),
            ("blacks", |e| e.adjust.tones[2] = 0.8),
            ("sharpen", |e| e.adjust.tones[3] = 1.8),
            ("curve", |e| e.adjust.curves[2][0] = 0.67),
            ("red-curve", |e| e.adjust.curves[2][1] = 0.67),
            ("temperature", |e| e.adjust.colour[0] = 0.8),
            ("tint", |e| e.adjust.colour[1] = 0.8),
            ("saturation", |e| e.adjust.colour[2] = 0.8),
            ("vibrance", |e| e.adjust.colour[3] = 0.8),
            ("white-balance", |e| {
                e.adjust.balance = [0.8, 1.08, 1.28, 1.0]
            }),
            ("grain-low", |e| {
                e.film.grain = 0.45;
                e.film.grain_colour = 0.25;
            }),
            ("grain-default", |e| {
                e.film.grain = 1.0;
                e.film.grain_colour = 0.25;
            }),
            ("grain-high", |e| {
                e.film.grain = 2.4;
                e.film.grain_colour = 0.25;
            }),
            ("film-35", |e| {
                e.film.grain = 1.0;
                e.film.grain_colour = 0.25;
            }),
            ("film-120", |e| {
                e.film.grain = 1.0;
                e.film.grain_colour = 0.25;
                e.film.negative_width = 56.0;
            }),
            ("portra", |e| e.film.negative_stock = 1),
            ("camera-plus", |e| {
                e.film.negative_stock = 1;
                e.film.exposure = 1.0;
            }),
            ("paper", |e| {
                e.film.print_medium = 1;
                e.film.print_grain = 0.0;
            }),
            ("paper-plus", |e| {
                e.film.print_medium = 1;
                e.film.print_grain = 0.0;
                e.film.print_exposure = 1.0;
            }),
            ("print-film", |e| {
                e.film.print_medium = 2;
                e.film.print_grain = 0.0;
            }),
            ("scan-aperture", |e| e.film.scan_aperture = 5.0),
            ("enlarger-blur", |e| {
                e.film.print_medium = 1;
                e.film.print_grain = 0.0;
                e.film.enlarger_blur = 150.0;
                e.film.enlargement = 1.0;
            }),
        ];
        for &(name, change) in changes {
            let mut edit = clean;
            change(&mut edit);
            scenarios.push((name, edit));
        }
        for (name, edit) in scenarios {
            // Model an interior viewport of a 6000 px long source at 100%.
            let rgb = gpu
                .read_rgb(
                    &mut image,
                    &edit,
                    [6000, HEIGHT],
                    SEED,
                    [0, 0, WIDTH, HEIGHT],
                    None,
                )
                .unwrap();
            std::fs::write(directory.join(format!("{name}.rgb")), rgb).unwrap();
        }
    });
}

#[test]
fn grain_strength_and_tone_scale_grain_and_keep_the_mean() {
    pollster::block_on(async {
        let instance = wgpu::Instance::new(&wgpu::InstanceDescriptor {
            backends: wgpu::Backends::METAL,
            ..Default::default()
        });
        let adapter = instance.request_adapter(&Default::default()).await.unwrap();
        let gpu = Gpu::new(&adapter).await.unwrap();
        let [width, height] = [512, 128];
        // Left half dark grey, right half light grey.
        let source: Vec<u8> = (0..height)
            .flat_map(|_| (0..width).flat_map(|x| {
                let v = if x < width / 2 { 60 } else { 200 };
                [v, v, v, 255]
            }))
            .collect();
        let mut image = gpu.upload([width, height], [0, 0], width * 4, &source).unwrap();
        let mut stats = |strength: f64, tone: f64| {
            let edit = Edit {
                film: grainlegit::Settings {
                    grain_colour: 0.25,
                    grain_size: 4.0,
                    grain_strength: strength,
                    grain_tone: tone,
                    ..Default::default()
                },
                ..Default::default()
            };
            let rgb = gpu
                .read_rgb(
                    &mut image,
                    &edit,
                    [6000, height],
                    SEED,
                    [0, 0, width, height],
                    None,
                )
                .unwrap();
            let half = |range: std::ops::Range<u32>| {
                let values: Vec<f64> = (8..height - 8)
                    .flat_map(|y| range.clone().map(move |x| (y * width + x) as usize * 3 + 1))
                    .map(|i| f64::from(rgb[i]))
                    .collect();
                let mean = values.iter().sum::<f64>() / values.len() as f64;
                let sd = (values.iter().map(|v| (v - mean).powi(2)).sum::<f64>() / values.len() as f64).sqrt();
                [mean, sd]
            };
            [half(8..width / 2 - 8), half(width / 2 + 8..width - 8)]
        };
        let [dark, light] = stats(1.0, 0.0);
        let [weak_dark, weak_light] = stats(0.5, 0.0);
        let [tone_dark, tone_light] = stats(1.0, 1.0);
        eprintln!("base {dark:?} {light:?} half {weak_dark:?} {weak_light:?} highlights {tone_dark:?} {tone_light:?}");
        for (a, b) in [(dark, weak_dark), (light, weak_light), (dark, tone_dark), (light, tone_light)] {
            assert!((a[0] - b[0]).abs() < 1.5, "mean moved: {a:?} vs {b:?}");
        }
        assert!((weak_dark[1] / dark[1] - 0.5).abs() < 0.1);
        assert!((weak_light[1] / light[1] - 0.5).abs() < 0.1);
        // Towards highlights: the light half gains grain relative to the dark half.
        assert!(tone_light[1] / light[1] > 1.2 * tone_dark[1] / dark[1]);
    });
}

#[test]
fn lens_stops_down_and_uses_one_full_frame_light_map_for_tiles() {
    pollster::block_on(async {
        use crate::edit::Lens;

        let instance = wgpu::Instance::new(&wgpu::InstanceDescriptor {
            backends: wgpu::Backends::METAL,
            ..Default::default()
        });
        let adapter = instance.request_adapter(&Default::default()).await.unwrap();
        let gpu = Gpu::new(&adapter).await.unwrap();
        let size = [832, 576];
        let [width, height] = size;
        let source = |[left, top]: [u32; 2], [w, h]: [u32; 2]| -> Vec<u8> {
            (top..top + h)
                .flat_map(|y| {
                    (left..left + w).flat_map(move |x| {
                        let grey = if (180..194).contains(&x) && (126..140).contains(&y) {
                            255
                        } else if (405..411).contains(&x) && (284..291).contains(&y) {
                            0
                        } else if x >= 660 && y >= 440 && (x / 2 + y / 2) % 2 == 0 {
                            192
                        } else {
                            72
                        };
                        [grey, grey, grey, 255]
                    })
                })
                .collect()
        };
        let mut full = gpu
            .upload(size, [0, 0], width * 4, &source([0, 0], size))
            .unwrap();
        let clean = Edit {
            film: grainlegit::Settings {
                grain: 0.0,
                ..Default::default()
            },
            ..Default::default()
        };
        let mut encoder = gpu.device.create_command_encoder(&Default::default());
        let light = gpu.prepare_lens_light(&mut encoder, &full, &clean.adjust);
        gpu.queue.submit([encoder.finish()]);
        let rect = [0, 0, width, height];
        let mut render = |lens| {
            gpu.read_rgb(
                &mut full,
                &Edit { lens, ..clean },
                size,
                SEED,
                rect,
                Some(&light),
            )
            .unwrap()
        };
        let pixel = |rgb: &[u8], x, y| {
            let i = ((y * width + x) * 3) as usize;
            [rgb[i], rgb[i + 1], rgb[i + 2]]
        };

        let vignette = Lens {
            vignette: -2.3,
            ..Default::default()
        };
        for (aperture, expected) in [(1.4, 29u8), (2.8, 48), (5.6, 72)] {
            let frame = render(Lens {
                aperture,
                ..vignette
            });
            assert_eq!(pixel(&frame, width / 2, height / 2), [72; 3]);
            assert!(pixel(&frame, 0, 0)[0].abs_diff(expected) <= 2);
        }

        let lens = Lens {
            aperture: 1.4,
            glow: 0.3,
            softness: 0.35,
            ghosts: 1.0,
            ..Default::default()
        };
        let open = render(lens);
        let stopped = render(Lens {
            aperture: 2.8,
            ..lens
        });
        let neutral = render(Lens::default());
        assert_eq!(stopped, neutral);
        assert!(
            pixel(&open, 197, 133)[0] > 77,
            "glow should spill outside the lamp"
        );
        assert!(
            pixel(&open, 610, 420)[1] > 86,
            "the ghost should appear opposite the lamp"
        );
        assert!(pixel(&open, 814, 554)[0] < 185, "the corners should soften");
        assert_eq!(pixel(&open, width / 2, height / 2), [72; 3]);

        let edit = Edit {
            lens: Lens {
                haze: 0.03,
                warmth: 0.12,
                saturation: -0.15,
                ..lens
            },
            ..clean
        };
        let expected = gpu
            .read_rgb(&mut full, &edit, size, SEED, rect, Some(&light))
            .unwrap();
        let closed = Edit {
            lens: Lens { aperture: 5.6, ..edit.lens },
            ..edit
        };
        assert_eq!(
            neutral,
            gpu.read_rgb(&mut full, &closed, size, SEED, rect, None).unwrap()
        );
        assert_eq!(pixel(&expected, 407, 287), [8; 3]);
        let centre = pixel(&expected, width / 2, height / 2);
        assert!(centre[0] > centre[2]);
        let resized_film = Edit {
            film: grainlegit::Settings {
                negative_width: 56.0,
                ..edit.film
            },
            ..edit
        };
        assert_eq!(
            expected,
            gpu.read_rgb(&mut full, &resized_film, size, SEED, rect, Some(&light))
                .unwrap()
        );

        let edit = Edit {
            adjust: crate::edit::Adjust {
                tones: [0.0, 0.0, 0.0, 0.5],
                ..edit.adjust
            },
            film: grainlegit::Settings {
                grain: 0.4,
                grain_colour: 0.25,
                ..edit.film
            },
            ..edit
        };
        let mut encoder = gpu.device.create_command_encoder(&Default::default());
        let light = gpu.prepare_lens_light(&mut encoder, &full, &edit.adjust);
        gpu.queue.submit([encoder.finish()]);
        let expected = gpu
            .read_rgb(&mut full, &edit, size, SEED, rect, Some(&light))
            .unwrap();
        let origin = [540, 310];
        let tile_size = [270, 250];
        let mut tile = gpu
            .upload(
                tile_size,
                origin,
                tile_size[0] * 4,
                &source(origin, tile_size),
            )
            .unwrap();
        let pad = edit.padding(size).unwrap();
        let inner = [pad, pad, tile_size[0] - 2 * pad, tile_size[1] - 2 * pad];
        let tiled = gpu
            .read_rgb(&mut tile, &edit, size, SEED, inner, Some(&light))
            .unwrap();
        for (y, row) in tiled.chunks_exact((inner[2] * 3) as usize).enumerate() {
            let i = ((origin[1] as usize + y + pad as usize) * width as usize
                + (origin[0] + pad) as usize)
                * 3;
            assert_eq!(row, &expected[i..i + row.len()]);
        }
    });
}
