use crate::edit::{Adjust, Edit};
use crate::gpu::{entry, texture_binding, Gpu, Image};
use crate::lens::LensLight;
use bytemuck::{Pod, Zeroable};
use std::num::NonZeroU64;

#[repr(C)]
#[derive(Clone, Copy, Pod, Zeroable)]
struct View {
    viewport_center: [f32; 2],
    image_center: [f32; 2],
    full_size: [f32; 2],
    texture_origin: [f32; 2],
    texture_span: [f32; 2],
    scale: f32,
    srgb_surface: u32,
    preview_to_source: [[f32; 4]; 3],
}

pub struct Frame {
    pub edit: Edit,
    pub compare: bool,
    pub center: [f32; 2],
    pub scale: f32,
    pub use_detail: bool,
    pub preview_to_source: Option<[f32; 9]>,
}

pub struct Photo {
    pub size: [u32; 2],
    pub seed: u64,
    pub fit: Image,
    pub detail: Option<Image>,
    pub lens_light: Option<(Adjust, LensLight)>,
}

impl Photo {
    fn covers_viewport(&self, image: &Image, viewport: [u32; 2], frame: &Frame) -> bool {
        let extent = [image.extent.width, image.extent.height];
        let Ok(pad) = frame.edit.padding(self.size) else {
            return false;
        };
        (0..2).all(|axis| {
            let half = viewport[axis] as f32 / (2.0 * frame.scale);
            let left = (frame.center[axis] - half).floor().max(0.0);
            let right = (frame.center[axis] + half)
                .ceil()
                .min(self.size[axis] as f32);
            let origin = image.origin[axis];
            let end = origin + extent[axis];
            let safe_left = if origin == 0 { 0 } else { origin + pad };
            let safe_right = if end == self.size[axis] {
                end
            } else {
                end.saturating_sub(pad)
            };
            left >= safe_left as f32 && right <= safe_right as f32
        })
    }
}

pub struct Presenter {
    labels: wgpu::BindGroupLayout,
    pipeline: wgpu::RenderPipeline,
    sampler: wgpu::Sampler,
    uniform: wgpu::Buffer,
    srgb: bool,
}

impl Presenter {
    pub fn new(gpu: &Gpu, format: wgpu::TextureFormat) -> Self {
        let device = &gpu.device;
        let binding = |binding, ty| wgpu::BindGroupLayoutEntry {
            binding,
            visibility: wgpu::ShaderStages::FRAGMENT,
            ty,
            count: None,
        };
        let labels = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: Some("photo presentation"),
            entries: &[
                binding(
                    0,
                    wgpu::BindingType::Buffer {
                        ty: wgpu::BufferBindingType::Uniform,
                        has_dynamic_offset: false,
                        min_binding_size: NonZeroU64::new(size_of::<View>() as u64),
                    },
                ),
                binding(1, texture_binding()),
                binding(
                    2,
                    wgpu::BindingType::Sampler(wgpu::SamplerBindingType::Filtering),
                ),
            ],
        });
        let layout = device.create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
            label: Some("present layout"),
            bind_group_layouts: &[&labels],
            push_constant_ranges: &[],
        });
        let shader = device.create_shader_module(wgpu::include_wgsl!("shaders/present.wgsl"));
        let pipeline = device.create_render_pipeline(&wgpu::RenderPipelineDescriptor {
            label: Some("photo presentation"),
            layout: Some(&layout),
            vertex: wgpu::VertexState {
                module: &shader,
                entry_point: Some("triangle"),
                compilation_options: Default::default(),
                buffers: &[],
            },
            fragment: Some(wgpu::FragmentState {
                module: &shader,
                entry_point: Some("photo"),
                compilation_options: Default::default(),
                targets: &[Some(format.into())],
            }),
            primitive: Default::default(),
            depth_stencil: None,
            multisample: Default::default(),
            multiview: None,
            cache: None,
        });
        let sampler = device.create_sampler(&wgpu::SamplerDescriptor {
            label: Some("photo filtering"),
            mag_filter: wgpu::FilterMode::Linear,
            min_filter: wgpu::FilterMode::Linear,
            ..Default::default()
        });
        let uniform = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("preview viewport"),
            size: size_of::<View>() as u64,
            usage: wgpu::BufferUsages::UNIFORM | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        });
        Self {
            labels,
            pipeline,
            sampler,
            uniform,
            srgb: format.is_srgb(),
        }
    }

    #[cfg(test)]
    pub fn encode(
        &self,
        gpu: &Gpu,
        encoder: &mut wgpu::CommandEncoder,
        target: &wgpu::TextureView,
        viewport: [u32; 2],
        photo: Option<&mut Photo>,
        frame: &Frame,
    ) -> Result<(), String> {
        let bindings = self.prepare(gpu, encoder, viewport, photo, frame)?;
        self.draw(encoder, target, bindings.as_ref());
        Ok(())
    }

    /// Records the film processing for a frame. Its output is drawn to the screen with [Self::draw].
    pub fn prepare(
        &self,
        gpu: &Gpu,
        encoder: &mut wgpu::CommandEncoder,
        viewport: [u32; 2],
        photo: Option<&mut Photo>,
        frame: &Frame,
    ) -> Result<Option<wgpu::BindGroup>, String> {
        Ok(if let Some(photo) = photo {
            let original = frame.compare;
            let needs_light = !original && frame.edit.lens.effects().needs_light();
            if needs_light
                && !photo
                    .lens_light
                    .as_ref()
                    .is_some_and(|(adjust, _)| *adjust == frame.edit.adjust)
            {
                let light = gpu.prepare_lens_light(encoder, &photo.fit, &frame.edit.adjust);
                photo.lens_light = Some((frame.edit.adjust, light));
            }
            let detail = frame.use_detail
                && frame.preview_to_source.is_none()
                && photo
                    .detail
                    .as_ref()
                    .is_some_and(|image| photo.covers_viewport(image, viewport, frame));
            let (image, span, frame_size) = if detail {
                let image = photo.detail.as_mut().unwrap();
                let span = [image.extent.width, image.extent.height];
                (image, span, photo.size)
            } else {
                let extent = photo.fit.extent;
                (&mut photo.fit, photo.size, [extent.width, extent.height])
            };
            if !original {
                let light = photo.lens_light.as_ref().map(|(_, light)| light);
                gpu.encode(encoder, image, &frame.edit, frame_size, photo.seed, light)?;
            }
            let [a, b, c, d, e, f, g, h, i] = frame
                .preview_to_source
                .unwrap_or([1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0]);
            let view = View {
                viewport_center: viewport.map(|v| v as f32 * 0.5),
                image_center: frame.center,
                full_size: photo.size.map(|v| v as f32),
                texture_origin: image.origin.map(|v| v as f32),
                texture_span: span.map(|v| v as f32),
                scale: frame.scale,
                srgb_surface: u32::from(self.srgb),
                preview_to_source: [[a, b, c, 0.0], [d, e, f, 0.0], [g, h, i, 0.0]],
            };
            gpu.queue
                .write_buffer(&self.uniform, 0, bytemuck::bytes_of(&view));
            let texture = if original {
                &image.source_view
            } else {
                &image.output_view
            };
            Some(gpu.device.create_bind_group(&wgpu::BindGroupDescriptor {
                label: Some("preview photo"),
                layout: &self.labels,
                entries: &[
                    entry(0, self.uniform.as_entire_binding()),
                    entry(1, wgpu::BindingResource::TextureView(texture)),
                    entry(2, wgpu::BindingResource::Sampler(&self.sampler)),
                ],
            }))
        } else {
            None
        })
    }

    /// Draws a prepared frame, or the background alone, to the screen.
    pub fn draw(
        &self,
        encoder: &mut wgpu::CommandEncoder,
        target: &wgpu::TextureView,
        bindings: Option<&wgpu::BindGroup>,
    ) {
        // Match the #101010 editor background where the photo does not fill the viewport.
        let grey: f64 = 16.0 / 255.0;
        let grey = if self.srgb {
            ((grey + 0.055) / 1.055).powf(2.4)
        } else {
            grey
        };
        let mut pass = encoder.begin_render_pass(&wgpu::RenderPassDescriptor {
            label: Some("present photo"),
            color_attachments: &[Some(wgpu::RenderPassColorAttachment {
                view: target,
                resolve_target: None,
                depth_slice: None,
                ops: wgpu::Operations {
                    load: wgpu::LoadOp::Clear(wgpu::Color {
                        r: grey,
                        g: grey,
                        b: grey,
                        a: 1.0,
                    }),
                    store: wgpu::StoreOp::Store,
                },
            })],
            ..Default::default()
        });
        if let Some(bindings) = bindings {
            pass.set_pipeline(&self.pipeline);
            pass.set_bind_group(0, bindings, &[]);
            pass.draw(0..3, 0..1);
        }
    }
}
