use crate::edit::{softness_radius, LensEffects};
use crate::gpu::{entry, sample, storage, uniform, write, Gpu, Image, Kernel, COLOR};
use bytemuck::{Pod, Zeroable};
use std::mem::size_of;
use wgpu::{BindingResource as Resource, TextureFormat, TextureUsages as Usage};

const MAP_FORMAT: TextureFormat = TextureFormat::Rgba16Float;
const MAP_EDGE: f32 = 256.0;

#[repr(C)]
#[derive(Clone, Copy, Pod, Zeroable)]
struct LensUniform {
    origin: [u32; 2],
    frame_size: [u32; 2],
    effects: LensEffects,
    radius: f32,
    unused: [f32; 3],
}

pub struct LensLight {
    glow: wgpu::TextureView,
    highlight: wgpu::Buffer,
}

pub struct LensPipeline {
    overview: Kernel,
    glow_x: Kernel,
    glow_y: Kernel,
    find_highlight: Kernel,
    apply: Kernel,
    sampler: wgpu::Sampler,
    empty_highlight: wgpu::Buffer,
}

impl LensPipeline {
    pub fn new(device: &wgpu::Device) -> Self {
        let shader = device.create_shader_module(wgpu::include_wgsl!("shaders/lens.wgsl"));
        let sampler_type = wgpu::BindingType::Sampler(wgpu::SamplerBindingType::Filtering);
        let map = [(0, sample(true)), (5, write(MAP_FORMAT)), (7, sampler_type)];
        let sampler = device.create_sampler(&wgpu::SamplerDescriptor {
            mag_filter: wgpu::FilterMode::Linear,
            min_filter: wgpu::FilterMode::Linear,
            ..Default::default()
        });
        Self {
            overview: Kernel::new(device, &shader, "overview", &map),
            glow_x: Kernel::new(device, &shader, "glow_x", &map),
            glow_y: Kernel::new(device, &shader, "glow_y", &map),
            find_highlight: Kernel::new(
                device,
                &shader,
                "find_highlight",
                &[(0, sample(false)), (6, storage(false))],
            ),
            apply: Kernel::new(
                device,
                &shader,
                "apply_lens",
                &[
                    (0, sample(true)),
                    (1, write(COLOR)),
                    (2, uniform(size_of::<LensUniform>(), false)),
                    (3, sample(true)),
                    (4, storage(true)),
                    (7, sampler_type),
                ],
            ),
            sampler,
            empty_highlight: device.create_buffer(&wgpu::BufferDescriptor {
                label: Some("no lens highlight"),
                size: 16,
                usage: wgpu::BufferUsages::STORAGE,
                mapped_at_creation: false,
            }),
        }
    }

    pub fn prepare(
        &self,
        gpu: &Gpu,
        encoder: &mut wgpu::CommandEncoder,
        extent: wgpu::Extent3d,
        source: &wgpu::TextureView,
    ) -> LensLight {
        let scale = (MAP_EDGE / extent.width.max(extent.height) as f32).min(1.0);
        let map = wgpu::Extent3d {
            width: (extent.width as f32 * scale).round().max(1.0) as u32,
            height: (extent.height as f32 * scale).round().max(1.0) as u32,
            depth_or_array_layers: 1,
        };
        let view = |label| {
            gpu.texture(map, MAP_FORMAT, label, Usage::STORAGE_BINDING)
                .create_view(&Default::default())
        };
        let overview = view("lens overview");
        let horizontal = view("lens glow horizontal");
        let glow = view("lens glow");
        for (kernel, input, output) in [
            (&self.overview, source, &overview),
            (&self.glow_x, &overview, &horizontal),
            (&self.glow_y, &horizontal, &glow),
        ] {
            kernel.run(
                gpu,
                encoder,
                map,
                &[
                    entry(0, Resource::TextureView(input)),
                    entry(5, Resource::TextureView(output)),
                    entry(7, Resource::Sampler(&self.sampler)),
                ],
            );
        }
        let highlight = gpu.device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("brightest lens highlight"),
            size: 16,
            usage: wgpu::BufferUsages::STORAGE,
            mapped_at_creation: false,
        });
        self.find_highlight.run(
            gpu,
            encoder,
            wgpu::Extent3d {
                width: 1,
                height: 1,
                depth_or_array_layers: 1,
            },
            &[
                entry(0, Resource::TextureView(&overview)),
                entry(6, highlight.as_entire_binding()),
            ],
        );
        LensLight { glow, highlight }
    }

    #[allow(clippy::too_many_arguments)]
    pub fn apply(
        &self,
        gpu: &Gpu,
        encoder: &mut wgpu::CommandEncoder,
        image: &Image,
        source: &wgpu::TextureView,
        frame_size: [u32; 2],
        effects: LensEffects,
        light: Option<&LensLight>,
    ) -> (wgpu::Texture, wgpu::TextureView) {
        let output = gpu.texture(
            image.extent,
            COLOR,
            "lens image",
            Usage::STORAGE_BINDING | Usage::COPY_SRC,
        );
        let view = output.create_view(&Default::default());
        let params = gpu.params(&LensUniform {
            origin: image.origin,
            frame_size,
            effects,
            radius: softness_radius(frame_size),
            unused: [0.0; 3],
        });
        let (glow, highlight) = light.map_or((source, &self.empty_highlight), |light| {
            (&light.glow, &light.highlight)
        });
        self.apply.run(
            gpu,
            encoder,
            image.extent,
            &[
                entry(0, Resource::TextureView(source)),
                entry(1, Resource::TextureView(&view)),
                entry(2, params.as_entire_binding()),
                entry(3, Resource::TextureView(glow)),
                entry(4, highlight.as_entire_binding()),
                entry(7, Resource::Sampler(&self.sampler)),
            ],
        );
        (output, view)
    }
}
