use crate::edit::{Adjust, Edit};
use crate::emulsion::{self, Emulsion, PRINT_SEED_MASK};
use bytemuck::{Pod, Zeroable};
use grainlegit::Settings;
use std::cell::RefCell;
use std::collections::VecDeque;
use std::num::NonZeroU64;
use std::rc::Rc;
use wgpu::util::DeviceExt;
use wgpu::{BindingResource as Resource, TextureFormat, TextureUsages as Usage};

const COLOR: TextureFormat = TextureFormat::Rgba8Unorm;
const LIGHT: TextureFormat = TextureFormat::Rgba32Float;
const SCRATCH_BYTES: u64 = 32 * 1024 * 1024;

#[repr(C)]
#[derive(Clone, Copy, Pod, Zeroable)]
struct Band {
    size: [u32; 2],
    first_row: u32,
    rows: u32,
    origin: [u32; 2],
    seed: [u32; 2],
    halo: u32,
    tones: u32,
    stochastic: u32,
    quantize: u32,
    saturation: f32,
    base_light: f32,
    unused: [u32; 2],
}

#[repr(C)]
#[derive(Clone, Copy, Pod, Zeroable)]
struct Optics {
    axis: u32,
    quantized: u32,
    print_scan: u32,
    radius: u32,
    curve: [f32; 3],
    grain_colour: f32,
}

pub struct Image {
    pub extent: wgpu::Extent3d,
    pub origin: [u32; 2],
    pub source: wgpu::Texture,
    pub source_view: wgpu::TextureView,
    pub output: wgpu::Texture,
    pub output_view: wgpu::TextureView,
    rendered: Option<(Edit, u32, u64)>,
    negative: Option<(NegativeKey, wgpu::TextureView)>,
}

struct Kernel {
    layout: wgpu::BindGroupLayout,
    pipeline: wgpu::ComputePipeline,
}

impl Kernel {
    fn new(
        device: &wgpu::Device,
        module: &wgpu::ShaderModule,
        entry: &'static str,
        bindings: &[(u32, wgpu::BindingType)],
    ) -> Self {
        let entries: Vec<_> = bindings
            .iter()
            .map(|&(binding, ty)| wgpu::BindGroupLayoutEntry {
                binding,
                ty,
                visibility: wgpu::ShaderStages::COMPUTE,
                count: None,
            })
            .collect();
        let layout = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: Some(entry),
            entries: &entries,
        });
        let pipeline_layout = device.create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
            label: Some(entry),
            bind_group_layouts: &[&layout],
            push_constant_ranges: &[],
        });
        let pipeline = device.create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
            label: Some(entry),
            layout: Some(&pipeline_layout),
            module,
            entry_point: Some(entry),
            compilation_options: Default::default(),
            cache: None,
        });
        Self { layout, pipeline }
    }

    fn run(
        &self,
        gpu: &Gpu,
        encoder: &mut wgpu::CommandEncoder,
        extent: wgpu::Extent3d,
        entries: &[wgpu::BindGroupEntry],
    ) {
        let bindings = gpu.device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: None,
            layout: &self.layout,
            entries,
        });
        let mut pass = encoder.begin_compute_pass(&Default::default());
        pass.set_pipeline(&self.pipeline);
        pass.set_bind_group(0, &bindings, &[]);
        pass.dispatch_workgroups(extent.width.div_ceil(8), extent.height.div_ceil(8), 1);
    }
}

struct Plate {
    info: Emulsion,
    tables: wgpu::Buffer,
}

impl Plate {
    fn new(gpu: &Gpu, edge: u32, settings: &Settings, print: bool, mean: bool) -> Self {
        let mut emulsion = Emulsion::new(edge, settings, print, mean);
        let tables = gpu.buffer(
            "emulsion tables",
            wgpu::BufferUsages::STORAGE,
            bytemuck::cast_slice(&emulsion.words),
        );
        emulsion.words = Vec::new();
        Self {
            info: emulsion,
            tables,
        }
    }
}

#[derive(Clone, Copy, PartialEq)]
struct PlateKey {
    edge: u32,
    settings: Settings,
    print: bool,
    mean: bool,
}

impl PlateKey {
    fn new(edge: u32, film: &Settings, print: bool, mean: bool) -> Self {
        let settings = if print {
            Settings {
                negative_width: film.negative_width,
                enlargement: film.enlargement,
                print_medium: film.print_medium,
                print_exposure: film.print_exposure,
                print_lights: film.print_lights,
                print_grain: film.print_grain,
                ..Settings::default()
            }
        } else {
            Settings {
                grain: film.grain,
                grain_size: film.grain_size,
                grain_load: film.grain_load,
                coarse_fraction: film.coarse_fraction,
                layer_size: film.layer_size,
                negative_width: film.negative_width,
                exposure: film.exposure,
                development: film.development,
                negative_stock: film.negative_stock,
                print_medium: u32::from(film.print_medium != 0),
                ..Settings::default()
            }
        };
        Self {
            edge,
            settings,
            print,
            mean,
        }
    }
}

#[derive(Clone, Copy, PartialEq)]
struct NegativeKey {
    plate: PlateKey,
    adjust: Adjust,
    seed: u64,
    grain_colour: f64,
}

pub struct Gpu {
    pub device: wgpu::Device,
    pub queue: wgpu::Queue,
    form_rows: Kernel,
    scan: Kernel,
    blur: Kernel,
    balance: Kernel,
    read_scan: Kernel,
    adjust: Kernel,
    scratch: wgpu::Buffer,
    plates: RefCell<VecDeque<(PlateKey, Rc<Plate>)>>,
}

impl Gpu {
    pub async fn new(adapter: &wgpu::Adapter) -> Result<Self, String> {
        let usage = Usage::STORAGE_BINDING | Usage::TEXTURE_BINDING;
        for format in [COLOR, LIGHT] {
            if !adapter
                .get_texture_format_features(format)
                .allowed_usages
                .contains(usage)
            {
                return Err(format!(
                    "GPU cannot sample and write {format:?} film textures"
                ));
            }
        }
        let (device, queue) = adapter
            .request_device(&wgpu::DeviceDescriptor::default())
            .await
            .map_err(|error| error.to_string())?;
        device.push_error_scope(wgpu::ErrorFilter::Validation);
        let shader = device.create_shader_module(wgpu::include_wgsl!("shaders/negative.wgsl"));
        let plate = [
            (0, sample(false)),
            (1, write(LIGHT)),
            (2, uniform(size_of::<Band>(), true)),
            (3, storage(false)),
            (4, storage(true)),
        ];
        let form_rows = Kernel::new(&device, &shader, "form_rows", &plate);
        let scan = Kernel::new(&device, &shader, "scan", &plate);
        // Bind groups created against the independently declared layouts have identical descriptors.
        let optics = device.create_shader_module(wgpu::include_wgsl!("shaders/optics.wgsl"));
        let params = uniform(size_of::<Optics>(), false);
        let blur = Kernel::new(
            &device,
            &optics,
            "convolve",
            &[
                (0, sample(false)),
                (2, params),
                (3, storage(true)),
                (4, write(LIGHT)),
            ],
        );
        let balance = Kernel::new(
            &device,
            &optics,
            "balance",
            &[
                (0, sample(false)),
                (1, sample(false)),
                (2, params),
                (4, write(LIGHT)),
            ],
        );
        let read_scan = Kernel::new(
            &device,
            &optics,
            "read_scan",
            &[(0, sample(false)), (2, params), (5, write(COLOR))],
        );
        let adjust_shader = device.create_shader_module(wgpu::include_wgsl!("shaders/adjust.wgsl"));
        let adjust = Kernel::new(
            &device,
            &adjust_shader,
            "grade",
            &[
                (0, sample(false)),
                (1, write(COLOR)),
                (2, uniform(size_of::<Adjust>(), false)),
            ],
        );
        let scratch = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("grain band scratch"),
            size: SCRATCH_BYTES,
            usage: wgpu::BufferUsages::STORAGE,
            mapped_at_creation: false,
        });
        if let Some(error) = device.pop_error_scope().await {
            return Err(error.to_string());
        }
        Ok(Self {
            device,
            queue,
            form_rows,
            scan,
            blur,
            balance,
            read_scan,
            adjust,
            scratch,
            plates: RefCell::new(VecDeque::new()),
        })
    }

    fn texture(
        &self,
        extent: wgpu::Extent3d,
        format: TextureFormat,
        label: &str,
        usage: Usage,
    ) -> wgpu::Texture {
        self.device.create_texture(&wgpu::TextureDescriptor {
            label: Some(label),
            size: extent,
            mip_level_count: 1,
            sample_count: 1,
            dimension: wgpu::TextureDimension::D2,
            format,
            usage: Usage::TEXTURE_BINDING | usage,
            view_formats: &[],
        })
    }

    pub fn upload(
        &self,
        size: [u32; 2],
        origin: [u32; 2],
        bytes_per_row: u32,
        rgba: &[u8],
    ) -> Result<Image, String> {
        let [width, height] = size;
        let max = self.device.limits().max_texture_dimension_2d;
        if width == 0 || height == 0 || width > max || height > max {
            return Err(format!("Unsupported GPU image size {width}×{height}"));
        }
        if bytes_per_row < width * 4
            || rgba.len() < (u64::from(bytes_per_row) * u64::from(height)) as usize
        {
            return Err("Invalid RGBA row stride or pixel buffer".into());
        }
        let extent = wgpu::Extent3d {
            width,
            height,
            depth_or_array_layers: 1,
        };
        let source = self.texture(
            extent,
            COLOR,
            "source photo",
            Usage::COPY_DST | Usage::COPY_SRC,
        );
        let output = self.texture(
            extent,
            COLOR,
            "final scan",
            Usage::STORAGE_BINDING | Usage::COPY_SRC | Usage::COPY_DST,
        );
        self.queue.write_texture(
            source.as_image_copy(),
            rgba,
            wgpu::TexelCopyBufferLayout {
                offset: 0,
                bytes_per_row: Some(bytes_per_row),
                rows_per_image: None,
            },
            extent,
        );
        Ok(Image {
            extent,
            origin,
            source_view: source.create_view(&Default::default()),
            output_view: output.create_view(&Default::default()),
            source,
            output,
            rendered: None,
            negative: None,
        })
    }

    pub fn encode(
        &self,
        encoder: &mut wgpu::CommandEncoder,
        image: &mut Image,
        edit: &Edit,
        edge: u32,
        seed: u64,
    ) -> Result<(), String> {
        let render_key = (*edit, edge, seed);
        if image.rendered == Some(render_key) {
            return Ok(());
        }
        let (extent, film) = (image.extent, &edit.film);
        emulsion::padding(edge, film)?;
        let key = NegativeKey {
            plate: PlateKey::new(edge, film, false, false),
            adjust: edit.adjust,
            seed,
            grain_colour: film.grain_colour,
        };
        let cached_negative = image
            .negative
            .as_ref()
            .filter(|(previous, _)| *previous == key)
            .map(|(_, view)| view.clone());
        if cached_negative.is_none() {
            image.negative = None;
        }
        let graded = if cached_negative.is_none() && edit.adjust != Adjust::default() {
            let texture = self.texture(
                extent,
                COLOR,
                "graded source",
                Usage::STORAGE_BINDING | Usage::COPY_SRC,
            );
            let output = texture.create_view(&Default::default());
            let uniform = self.params(&edit.adjust);
            self.adjust.run(
                self,
                encoder,
                extent,
                &[
                    entry(0, Resource::TextureView(&image.source_view)),
                    entry(1, Resource::TextureView(&output)),
                    entry(2, uniform.as_entire_binding()),
                ],
            );
            Some((texture, output))
        } else {
            None
        };
        let (source, view) = graded
            .as_ref()
            .map(|(a, b)| (a, b))
            .unwrap_or((&image.source, &image.source_view));
        if film.bypass() {
            encoder.copy_texture_to_texture(
                source.as_image_copy(),
                image.output.as_image_copy(),
                extent,
            );
        } else {
            let printing = film.print_medium != 0;
            let scan_optics = film.scan_blur > 0.0 || film.scan_aperture > 1.0;
            let negative_balance = film.grain_colour < 1.0 && film.grain > 1e-6;
            let negative = if let Some(view) = cached_negative {
                view
            } else {
                let negative = self.plate(
                    encoder,
                    extent,
                    image.origin,
                    view,
                    edge,
                    seed,
                    film,
                    false,
                    printing || scan_optics || negative_balance,
                    negative_balance,
                )?;
                if printing {
                    image.negative = Some((key, negative.clone()));
                }
                negative
            };
            let (scan, quantized) = if printing {
                let sigma = emulsion::enlarger_sigma(edge, film);
                let enlarger = if sigma > 0.0 {
                    self.filter(encoder, extent, &negative, &emulsion::optics(sigma, 1.0))
                } else {
                    negative
                };
                let balance = film.grain_colour < 1.0 && film.print_grain > 1e-6;
                let print = self.plate(
                    encoder,
                    extent,
                    image.origin,
                    &enlarger,
                    edge,
                    seed ^ PRINT_SEED_MASK,
                    film,
                    true,
                    scan_optics || balance,
                    balance,
                )?;
                (print, scan_optics || balance)
            } else {
                (negative, scan_optics || negative_balance)
            };
            let scan = if scan_optics {
                self.filter(
                    encoder,
                    extent,
                    &scan,
                    &emulsion::optics(film.scan_blur, film.scan_aperture),
                )
            } else {
                scan
            };
            let uniform = self.params(&Optics {
                axis: 0,
                quantized: u32::from(quantized),
                print_scan: u32::from(printing),
                radius: 0,
                curve: emulsion::scan_curve(),
                grain_colour: 0.0,
            });
            self.read_scan.run(
                self,
                encoder,
                extent,
                &[
                    entry(0, Resource::TextureView(&scan)),
                    entry(2, uniform.as_entire_binding()),
                    entry(5, Resource::TextureView(&image.output_view)),
                ],
            );
        }
        image.rendered = Some(render_key);
        Ok(())
    }

    pub fn read_rgb(
        &self,
        image: &mut Image,
        edit: &Edit,
        edge: u32,
        seed: u64,
        [left, top, width, height]: [u32; 4],
    ) -> Result<Vec<u8>, String> {
        if width == 0
            || height == 0
            || left.saturating_add(width) > image.extent.width
            || top.saturating_add(height) > image.extent.height
        {
            return Err("Invalid export tile".into());
        }
        let stride = (width * 4).next_multiple_of(wgpu::COPY_BYTES_PER_ROW_ALIGNMENT);
        let readback = self.device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("export tile readback"),
            size: u64::from(stride) * u64::from(height),
            usage: wgpu::BufferUsages::MAP_READ | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        });
        let mut encoder = self.device.create_command_encoder(&Default::default());
        self.encode(&mut encoder, image, edit, edge, seed)?;
        encoder.copy_texture_to_buffer(
            wgpu::TexelCopyTextureInfo {
                origin: wgpu::Origin3d {
                    x: left,
                    y: top,
                    z: 0,
                },
                ..image.output.as_image_copy()
            },
            wgpu::TexelCopyBufferInfo {
                buffer: &readback,
                layout: wgpu::TexelCopyBufferLayout {
                    offset: 0,
                    bytes_per_row: Some(stride),
                    rows_per_image: None,
                },
            },
            wgpu::Extent3d {
                width,
                height,
                depth_or_array_layers: 1,
            },
        );
        let submission = self.queue.submit([encoder.finish()]);
        let (send, receive) = std::sync::mpsc::channel();
        readback
            .slice(..)
            .map_async(wgpu::MapMode::Read, move |result| {
                let _ = send.send(result);
            });
        self.device
            .poll(wgpu::PollType::wait_for(submission))
            .map_err(|error| format!("{error:?}"))?;
        receive
            .recv()
            .map_err(|error| error.to_string())?
            .map_err(|error| error.to_string())?;
        let rgba = readback.slice(..).get_mapped_range();
        let mut rgb = Vec::with_capacity(width as usize * height as usize * 3);
        for row in rgba.chunks_exact(stride as usize) {
            for pixel in row[..width as usize * 4].chunks_exact(4) {
                rgb.extend_from_slice(&pixel[..3]);
            }
        }
        Ok(rgb)
    }

    fn cached_plate(&self, key: PlateKey) -> Rc<Plate> {
        let mut cache = self.plates.borrow_mut();
        if let Some(index) = cache.iter().position(|(cached, _)| *cached == key) {
            let entry = cache.remove(index).unwrap();
            let plate = Rc::clone(&entry.1);
            cache.push_front(entry);
            return plate;
        }
        cache.truncate(5);
        let plate = Rc::new(Plate::new(
            self,
            key.edge,
            &key.settings,
            key.print,
            key.mean,
        ));
        cache.push_front((key, Rc::clone(&plate)));
        plate
    }

    #[allow(clippy::too_many_arguments)]
    fn plate(
        &self,
        encoder: &mut wgpu::CommandEncoder,
        extent: wgpu::Extent3d,
        origin: [u32; 2],
        input: &wgpu::TextureView,
        edge: u32,
        seed: u64,
        film: &Settings,
        print: bool,
        quantize: bool,
        balance: bool,
    ) -> Result<wgpu::TextureView, String> {
        let scan = self.cached_plate(PlateKey::new(edge, film, print, false));
        let formed = self.expose(encoder, extent, origin, input, seed, quantize, &scan)?;
        if !balance {
            return Ok(formed);
        }
        let mean = self.cached_plate(PlateKey::new(edge, film, print, true));
        let reference = self.expose(encoder, extent, origin, input, seed, true, &mean)?;
        let result = self.light(extent);
        let uniform = self.params(&Optics {
            axis: 0,
            quantized: 0,
            print_scan: 0,
            radius: 0,
            curve: [0.0; 3],
            grain_colour: film.grain_colour as f32,
        });
        self.balance.run(
            self,
            encoder,
            extent,
            &[
                entry(0, Resource::TextureView(&formed)),
                entry(1, Resource::TextureView(&reference)),
                entry(2, uniform.as_entire_binding()),
                entry(4, Resource::TextureView(&result)),
            ],
        );
        Ok(result)
    }

    #[allow(clippy::too_many_arguments)]
    fn expose(
        &self,
        encoder: &mut wgpu::CommandEncoder,
        extent: wgpu::Extent3d,
        origin: [u32; 2],
        input: &wgpu::TextureView,
        seed: u64,
        quantize: bool,
        plate: &Plate,
    ) -> Result<wgpu::TextureView, String> {
        let wgpu::Extent3d { width, height, .. } = extent;
        let emulsion = &plate.info;
        let rows = ((SCRATCH_BYTES / (u64::from(width) * 96)) as u32)
            .checked_sub(emulsion.halo)
            .filter(|rows| *rows > 0)
            .ok_or("Image width and halo exceed the GPU scratch budget")?;
        let stride = size_of::<Band>()
            .next_multiple_of(self.device.limits().min_uniform_buffer_offset_alignment as usize);
        let mut bands = vec![0; height.div_ceil(rows) as usize * stride];
        for (index, chunk) in bands.chunks_exact_mut(stride).enumerate() {
            let first_row = index as u32 * rows;
            let band = Band {
                size: [width, height],
                first_row,
                rows: rows.min(height - first_row),
                origin,
                seed: [seed as u32, (seed >> 32) as u32],
                halo: emulsion.halo,
                tones: emulsion.tones,
                stochastic: u32::from(emulsion.stochastic),
                quantize: u32::from(quantize),
                saturation: emulsion.saturation,
                base_light: emulsion.base_light,
                unused: [0; 2],
            };
            chunk[..size_of::<Band>()].copy_from_slice(bytemuck::bytes_of(&band));
        }
        let uniforms = self.buffer("film bands", wgpu::BufferUsages::UNIFORM, &bands);
        let result = self.light(extent);
        let bindings = self.device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: Some("film plate"),
            layout: &self.form_rows.layout,
            entries: &[
                entry(0, Resource::TextureView(input)),
                entry(1, Resource::TextureView(&result)),
                entry(
                    2,
                    Resource::Buffer(wgpu::BufferBinding {
                        buffer: &uniforms,
                        offset: 0,
                        size: NonZeroU64::new(size_of::<Band>() as u64),
                    }),
                ),
                entry(3, self.scratch.as_entire_binding()),
                entry(4, plate.tables.as_entire_binding()),
            ],
        });
        for index in 0..height.div_ceil(rows) {
            let band_height = rows.min(height - index * rows);
            let offset = index * stride as u32;
            for (pipeline, groups) in [
                (
                    &self.form_rows,
                    [
                        (width * 2).div_ceil(32),
                        (band_height + emulsion.halo) * 2,
                        3,
                    ],
                ),
                (&self.scan, [width.div_ceil(8), band_height.div_ceil(8), 1]),
            ] {
                let mut pass = encoder.begin_compute_pass(&Default::default());
                pass.set_pipeline(&pipeline.pipeline);
                pass.set_bind_group(0, &bindings, &[offset]);
                pass.dispatch_workgroups(groups[0], groups[1], groups[2]);
            }
        }
        Ok(result)
    }

    fn filter(
        &self,
        encoder: &mut wgpu::CommandEncoder,
        extent: wgpu::Extent3d,
        input: &wgpu::TextureView,
        weights: &[f32],
    ) -> wgpu::TextureView {
        let weights_buffer = self.buffer(
            "optical weights",
            wgpu::BufferUsages::STORAGE,
            bytemuck::cast_slice(weights),
        );
        let horizontal = self.light(extent);
        let result = self.light(extent);
        for (axis, from, to) in [(0, input, &horizontal), (1, &horizontal, &result)] {
            let uniform = self.params(&Optics {
                axis,
                quantized: 0,
                print_scan: 0,
                radius: weights.len() as u32 / 2,
                curve: [0.0; 3],
                grain_colour: 0.0,
            });
            self.blur.run(
                self,
                encoder,
                extent,
                &[
                    entry(0, Resource::TextureView(from)),
                    entry(2, uniform.as_entire_binding()),
                    entry(3, weights_buffer.as_entire_binding()),
                    entry(4, Resource::TextureView(to)),
                ],
            );
        }
        result
    }

    fn light(&self, extent: wgpu::Extent3d) -> wgpu::TextureView {
        self.texture(extent, LIGHT, "transmitted light", Usage::STORAGE_BINDING)
            .create_view(&Default::default())
    }

    fn params(&self, values: &impl Pod) -> wgpu::Buffer {
        self.buffer(
            "render parameters",
            wgpu::BufferUsages::UNIFORM,
            bytemuck::bytes_of(values),
        )
    }

    fn buffer(&self, label: &str, usage: wgpu::BufferUsages, contents: &[u8]) -> wgpu::Buffer {
        self.device
            .create_buffer_init(&wgpu::util::BufferInitDescriptor {
                label: Some(label),
                usage,
                contents,
            })
    }
}

fn sample(filterable: bool) -> wgpu::BindingType {
    wgpu::BindingType::Texture {
        sample_type: wgpu::TextureSampleType::Float { filterable },
        view_dimension: wgpu::TextureViewDimension::D2,
        multisampled: false,
    }
}

pub fn texture_binding() -> wgpu::BindingType {
    sample(true)
}

fn write(format: TextureFormat) -> wgpu::BindingType {
    wgpu::BindingType::StorageTexture {
        access: wgpu::StorageTextureAccess::WriteOnly,
        format,
        view_dimension: wgpu::TextureViewDimension::D2,
    }
}

fn uniform(size: usize, has_dynamic_offset: bool) -> wgpu::BindingType {
    wgpu::BindingType::Buffer {
        ty: wgpu::BufferBindingType::Uniform,
        has_dynamic_offset,
        min_binding_size: NonZeroU64::new(size as u64),
    }
}

fn storage(read_only: bool) -> wgpu::BindingType {
    wgpu::BindingType::Buffer {
        ty: wgpu::BufferBindingType::Storage { read_only },
        has_dynamic_offset: false,
        min_binding_size: None,
    }
}

pub fn entry(binding: u32, resource: Resource<'_>) -> wgpu::BindGroupEntry<'_> {
    wgpu::BindGroupEntry { binding, resource }
}
