use crate::edit::Edit;
use crate::gpu::Gpu;
use crate::preview::{Frame, Photo, Presenter};
use jni::objects::{JClass, JFloatArray, JObject, JString};
use jni::sys::{jboolean, jfloat, jint, jlong, jobject};
use jni::JNIEnv;
use raw_window_handle::{AndroidDisplayHandle, AndroidNdkWindowHandle};
use std::ffi::c_void;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::ptr::NonNull;

#[link(name = "android")]
extern "C" {
    fn ANativeWindow_fromSurface(env: *mut jni::sys::JNIEnv, surface: jobject) -> *mut c_void;
    fn ANativeWindow_release(window: *mut c_void);
}

#[repr(C)]
#[derive(Default)]
struct BitmapInfo {
    width: u32,
    height: u32,
    stride: u32,
    format: i32,
    flags: u32,
}

#[link(name = "jnigraphics")]
extern "C" {
    fn AndroidBitmap_getInfo(
        env: *mut jni::sys::JNIEnv,
        bitmap: jobject,
        info: *mut BitmapInfo,
    ) -> i32;
    fn AndroidBitmap_lockPixels(
        env: *mut jni::sys::JNIEnv,
        bitmap: jobject,
        pixels: *mut *mut c_void,
    ) -> i32;
    fn AndroidBitmap_unlockPixels(env: *mut jni::sys::JNIEnv, bitmap: jobject) -> i32;
}

#[link(name = "log")]
extern "C" {
    fn __android_log_write(
        priority: i32,
        tag: *const std::ffi::c_char,
        text: *const std::ffi::c_char,
    ) -> i32;
}

fn log(message: &str) {
    if let Ok(text) = std::ffi::CString::new(message) {
        unsafe { __android_log_write(4, c"Grainlegit".as_ptr(), text.as_ptr()) };
    }
}

struct Window(NonNull<c_void>);

impl Drop for Window {
    fn drop(&mut self) {
        unsafe { ANativeWindow_release(self.0.as_ptr()) };
    }
}

struct Surface {
    surface: wgpu::Surface<'static>,
    _window: Window, // The surface is dropped before its retained native window.
    config: wgpu::SurfaceConfiguration,
    presenter: Presenter,
}

impl Surface {
    fn open(
        instance: &wgpu::Instance,
        env: &JNIEnv,
        surface: &JObject,
    ) -> Result<(wgpu::Surface<'static>, Window), String> {
        let pointer = unsafe { ANativeWindow_fromSurface(env.get_raw(), surface.as_raw()) };
        let window = Window(NonNull::new(pointer).ok_or("Android surface is unavailable")?);
        let target = wgpu::SurfaceTargetUnsafe::RawHandle {
            raw_display_handle: AndroidDisplayHandle::new().into(),
            raw_window_handle: AndroidNdkWindowHandle::new(window.0).into(),
        };
        // Window owns the ANativeWindow reference for the entire surface lifetime.
        let surface =
            unsafe { instance.create_surface_unsafe(target) }.map_err(|error| error.to_string())?;
        Ok((surface, window))
    }

    fn new(
        surface: wgpu::Surface<'static>,
        window: Window,
        adapter: &wgpu::Adapter,
        gpu: &Gpu,
    ) -> Result<Self, String> {
        let mut config = surface
            .get_default_config(adapter, 1, 1)
            .ok_or("GPU cannot present to this Android surface")?;
        config.present_mode = wgpu::PresentMode::Fifo;
        config.desired_maximum_frame_latency = 1;
        let caps = surface.get_capabilities(adapter);
        if let Some(format) = caps
            .formats
            .iter()
            .copied()
            .find(|format| !format.is_srgb())
        {
            config.format = format;
        }
        log(&format!(
            "Surface format {:?}; modes {:?}",
            config.format, caps.present_modes
        ));
        gpu.device.push_error_scope(wgpu::ErrorFilter::Validation);
        let presenter = Presenter::new(gpu, config.format);
        if let Some(error) = pollster::block_on(gpu.device.pop_error_scope()) {
            return Err(error.to_string());
        }
        config.width = 0;
        config.height = 0;
        Ok(Self {
            surface,
            _window: window,
            config,
            presenter,
        })
    }

    fn resize(&mut self, device: &wgpu::Device, width: u32, height: u32) {
        if (self.config.width, self.config.height) != (width, height) {
            self.config.width = width;
            self.config.height = height;
            if width > 0 && height > 0 {
                self.surface.configure(device, &self.config);
            }
        }
    }
}

struct Engine {
    surface: Option<Surface>,
    instance: wgpu::Instance,
    adapter: wgpu::Adapter,
    gpu: Gpu,
    photo: Option<Photo>,
}

impl Engine {
    fn new(env: &JNIEnv, surface: &JObject) -> Result<Self, String> {
        let start = std::time::Instant::now();
        let instance = wgpu::Instance::new(&wgpu::InstanceDescriptor {
            backends: wgpu::Backends::VULKAN,
            flags: wgpu::InstanceFlags::empty(),
            ..Default::default()
        });
        let (surface, window) = Surface::open(&instance, env, surface)?;
        let adapter = pollster::block_on(instance.request_adapter(&wgpu::RequestAdapterOptions {
            power_preference: wgpu::PowerPreference::HighPerformance,
            force_fallback_adapter: false,
            compatible_surface: Some(&surface),
        }))
        .map_err(|error| error.to_string())?;
        let version = {
            let vk = unsafe { adapter.as_hal::<wgpu::hal::api::Vulkan>() }
                .ok_or("Android requires a Vulkan adapter")?;
            // Both handles are borrowed from the live wgpu Vulkan adapter.
            let properties = unsafe {
                vk.shared_instance()
                    .raw_instance()
                    .get_physical_device_properties(vk.raw_physical_device())
            };
            properties.api_version
        };
        let major = (version >> 22) & 0x7f;
        let minor = (version >> 12) & 0x3ff;
        if (major, minor) < (1, 3) {
            return Err(format!(
                "Grainlegit requires Vulkan 1.3 (found {major}.{minor})"
            ));
        }
        let limits = adapter.limits();
        log(&format!(
            "Vulkan {major}.{minor}.{} {:?}; max texture {}; workgroup storage {}; storage binding {}",
            version & 0xfff, adapter.get_info(), limits.max_texture_dimension_2d,
            limits.max_compute_workgroup_storage_size, limits.max_storage_buffer_binding_size,
        ));
        for format in [
            wgpu::TextureFormat::Rgba8Unorm,
            wgpu::TextureFormat::Rgba32Float,
        ] {
            let features = adapter.get_texture_format_features(format);
            log(&format!(
                "{format:?} supports {:?}",
                features.allowed_usages
            ));
        }
        let gpu = pollster::block_on(Gpu::new(&adapter))?;
        let surface = Surface::new(surface, window, &adapter, &gpu)?;
        log(&format!(
            "Vulkan setup {:.1} ms",
            start.elapsed().as_secs_f64() * 1000.0
        ));
        Ok(Self {
            surface: Some(surface),
            instance,
            adapter,
            gpu,
            photo: None,
        })
    }

    fn draw(&mut self, frame: Frame) -> Result<(), String> {
        let Some(surface) = self.surface.as_ref() else {
            return Ok(());
        };
        let size = [surface.config.width, surface.config.height];
        if size.contains(&0) {
            return Ok(());
        }
        let texture = match surface.surface.get_current_texture() {
            Err(wgpu::SurfaceError::Outdated | wgpu::SurfaceError::Lost) => {
                surface.surface.configure(&self.gpu.device, &surface.config);
                surface.surface.get_current_texture()
            }
            result => result,
        }
        .map_err(|error| error.to_string())?;
        let view = texture.texture.create_view(&Default::default());
        let mut encoder = self
            .gpu
            .device
            .create_command_encoder(&wgpu::CommandEncoderDescriptor {
                label: Some("editor preview"),
            });
        surface.presenter.encode(
            &self.gpu,
            &mut encoder,
            &view,
            size,
            self.photo.as_mut(),
            &frame,
        )?;
        let submitted = self.gpu.queue.submit([encoder.finish()]);
        texture.present();
        self.gpu
            .device
            .poll(wgpu::PollType::wait_for(submitted))
            .map_err(|error| format!("GPU completion failed {error:?}"))?;
        Ok(())
    }
}

struct LockedBitmap {
    env: *mut jni::sys::JNIEnv,
    bitmap: jobject,
    pixels: NonNull<c_void>,
    info: BitmapInfo,
}

impl LockedBitmap {
    fn new(env: &JNIEnv, bitmap: &JObject) -> Result<Self, String> {
        let env = env.get_raw();
        let bitmap = bitmap.as_raw();
        let mut info = BitmapInfo::default();
        if unsafe { AndroidBitmap_getInfo(env, bitmap, &mut info) } != 0 || info.format != 1 {
            return Err("Expected a software RGBA_8888 bitmap".into());
        }
        let mut pointer = std::ptr::null_mut();
        if unsafe { AndroidBitmap_lockPixels(env, bitmap, &mut pointer) } != 0 {
            return Err("Android could not lock the bitmap pixels".into());
        }
        let Some(pixels) = NonNull::new(pointer) else {
            unsafe { AndroidBitmap_unlockPixels(env, bitmap) };
            return Err("Android returned empty bitmap pixels".into());
        };
        Ok(Self {
            env,
            bitmap,
            pixels,
            info,
        })
    }

    fn upload(&self, gpu: &Gpu, origin: [u32; 2]) -> Result<crate::gpu::Image, String> {
        let BitmapInfo {
            width,
            height,
            stride,
            ..
        } = self.info;
        let size = usize::try_from(u64::from(stride) * u64::from(height))
            .map_err(|_| "Bitmap is too large")?;
        // Pixel memory is valid until this guard unlocks the JNI bitmap.
        let rgba = unsafe { std::slice::from_raw_parts(self.pixels.as_ptr().cast::<u8>(), size) };
        gpu.upload([width, height], origin, stride, rgba)
    }
}

impl Drop for LockedBitmap {
    fn drop(&mut self) {
        unsafe { AndroidBitmap_unlockPixels(self.env, self.bitmap) };
    }
}

fn call<T: Default>(mut env: JNIEnv, body: impl FnOnce(&JNIEnv) -> Result<T, String>) -> T {
    let result = catch_unwind(AssertUnwindSafe(|| body(&env)));
    let error = match result {
        Ok(Ok(value)) => return value,
        Ok(Err(error)) => error,
        Err(panic) => panic
            .downcast_ref::<String>()
            .map(String::as_str)
            .or_else(|| panic.downcast_ref::<&str>().copied())
            .unwrap_or("Native renderer failed")
            .to_owned(),
    };
    let _ = env.throw_new("java/lang/IllegalStateException", error);
    T::default()
}

fn with_engine<T>(
    handle: jlong,
    use_engine: impl FnOnce(&mut Engine) -> Result<T, String>,
) -> Result<T, String> {
    if handle == 0 {
        return Err("The renderer is not initialized".into());
    }
    // Renderer.kt confines all calls and ownership of this handle to one thread.
    use_engine(unsafe { &mut *(handle as *mut Engine) })
}

#[no_mangle]
pub extern "system" fn Java_dev_grainlegit_editor_NativeRenderer_mount(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    surface: JObject,
) -> jlong {
    call(env, |env| {
        if handle == 0 {
            return Ok(Box::into_raw(Box::new(Engine::new(env, &surface)?)) as jlong);
        }
        with_engine(handle, |engine| {
            let (surface, window) = Surface::open(&engine.instance, env, &surface)?;
            engine.surface = Some(Surface::new(surface, window, &engine.adapter, &engine.gpu)?);
            Ok(handle)
        })
    })
}

#[no_mangle]
pub extern "system" fn Java_dev_grainlegit_editor_NativeRenderer_resize(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    width: jint,
    height: jint,
) {
    call(env, |_| {
        with_engine(handle, |engine| {
            if let Some(surface) = engine.surface.as_mut() {
                surface.resize(
                    &engine.gpu.device,
                    width.max(0) as u32,
                    height.max(0) as u32,
                );
            }
            Ok(())
        })
    });
}

#[no_mangle]
pub extern "system" fn Java_dev_grainlegit_editor_NativeRenderer_unmount(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    call(env, |_| {
        with_engine(handle, |engine| {
            engine.surface = None;
            Ok(())
        })
    });
}

#[no_mangle]
pub extern "system" fn Java_dev_grainlegit_editor_NativeRenderer_setSource(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    bitmap: JObject,
    width: jint,
    height: jint,
    seed: jlong,
) {
    call(env, |env| {
        with_engine(handle, |engine| {
            if width <= 0 || height <= 0 {
                return Err("Invalid source dimensions".into());
            }
            let fit = LockedBitmap::new(env, &bitmap)?.upload(&engine.gpu, [0, 0])?;
            engine.photo = Some(Photo {
                size: [width as u32, height as u32],
                seed: seed as u64,
                fit,
                detail: None,
            });
            Ok(())
        })
    });
}

#[no_mangle]
pub extern "system" fn Java_dev_grainlegit_editor_NativeRenderer_setDetail(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    bitmap: JObject,
    x: jint,
    y: jint,
) {
    call(env, |env| {
        with_engine(handle, |engine| {
            if let Some(photo) = engine.photo.as_mut() {
                let bitmap = LockedBitmap::new(env, &bitmap)?;
                if x < 0
                    || y < 0
                    || x as u32 + bitmap.info.width > photo.size[0]
                    || y as u32 + bitmap.info.height > photo.size[1]
                {
                    return Err("Preview crop is outside the photo".into());
                }
                photo.detail = Some(bitmap.upload(&engine.gpu, [x as u32, y as u32])?);
            }
            Ok(())
        })
    });
}

#[no_mangle]
pub extern "system" fn Java_dev_grainlegit_editor_NativeRenderer_draw(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    edit: JString,
    compare: jboolean,
    x: jfloat,
    y: jfloat,
    scale: jfloat,
    use_detail: jboolean,
    preview_to_source: JFloatArray,
) {
    call(env, |env| {
        let edit = parse_edit(env, &edit)?;
        let preview_to_source = if preview_to_source.is_null() {
            None
        } else {
            let mut matrix = [0.0; 9];
            env.get_float_array_region(&preview_to_source, 0, &mut matrix)
                .map_err(|error| error.to_string())?;
            if !matrix.iter().all(|value| value.is_finite()) {
                return Err("Invalid crop transform".into());
            }
            Some(matrix)
        };
        with_engine(handle, |engine| {
            if ![x, y, scale].iter().all(|v| v.is_finite()) || scale <= 0.0 {
                return Err("Invalid preview settings".into());
            }
            engine.draw(Frame {
                edit,
                compare: compare != 0,
                center: [x, y],
                scale,
                use_detail: use_detail != 0,
                preview_to_source,
            })
        })
    });
}

#[no_mangle]
pub extern "system" fn Java_dev_grainlegit_editor_NativeRenderer_close(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    call(env, |_| {
        if handle != 0 {
            // Kotlin transfers its only handle here after all queued work.
            drop(unsafe { Box::from_raw(handle as *mut Engine) });
        }
        Ok(())
    });
}

#[no_mangle]
pub extern "system" fn Java_dev_grainlegit_editor_NativeRenderer_clear(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    call(env, |_| {
        with_engine(handle, |engine| {
            engine.photo = None;
            Ok(())
        })
    });
}

fn parse_edit(env: &JNIEnv, edit: &JString) -> Result<Edit, String> {
    let mut env = unsafe { env.unsafe_clone() };
    let json = env.get_string(edit).map_err(|error| error.to_string())?;
    let edit: Edit = serde_json::from_str(json.to_str().map_err(|error| error.to_string())?)
        .map_err(|error| error.to_string())?;
    if edit.film.negative_stock as usize > grainlegit::negative_stocks().len()
        || edit.film.print_medium > 2
        || edit.film.negative_width <= 0.0
        || edit.film.enlargement <= 0.0
        || edit.film.grain < 0.0
        || edit.film.print_grain < 0.0
    {
        return Err("Invalid film settings".into());
    }
    edit.film.validate()?;
    Ok(edit)
}

#[no_mangle]
pub extern "system" fn Java_dev_grainlegit_editor_NativeRenderer_padding(
    env: JNIEnv,
    _class: JClass,
    edit: JString,
    edge: jint,
) -> jint {
    call(env, |env| {
        let edit = parse_edit(env, &edit)?;
        let padding = crate::emulsion::padding(edge as u32, &edit.film)?;
        Ok(padding as jint + i32::from(edit.adjust.tones[3] > 0.0))
    })
}

#[no_mangle]
pub extern "system" fn Java_dev_grainlegit_editor_NativeRenderer_autoGrainSize(
    env: JNIEnv,
    _class: JClass,
    edit: JString,
    width: jint,
    height: jint,
) -> jfloat {
    call(env, |env| {
        let edit = parse_edit(env, &edit)?;
        if width <= 0 || height <= 0 {
            return Err("Invalid photo size".into());
        }
        Ok(crate::emulsion::auto_grain_size(width as u32, height as u32, &edit.film) as jfloat)
    })
}

#[no_mangle]
pub extern "system" fn Java_dev_grainlegit_editor_NativeRenderer_exportTile(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    bitmap: JObject,
    x: jint,
    y: jint,
    left: jint,
    top: jint,
    width: jint,
    height: jint,
    edge: jint,
    seed: jlong,
    edit: JString,
) -> jni::sys::jbyteArray {
    call(env, |env| {
        let edit = parse_edit(env, &edit)?;
        let pixels = with_engine(handle, |engine| {
            let mut image =
                LockedBitmap::new(env, &bitmap)?.upload(&engine.gpu, [x as u32, y as u32])?;
            engine.gpu.read_rgb(
                &mut image,
                &edit,
                edge as u32,
                seed as u64,
                [left as u32, top as u32, width as u32, height as u32],
            )
        })?;
        env.byte_array_from_slice(&pixels)
            .map(|result| result.into_raw())
            .map_err(|error| error.to_string())
    })
}

#[no_mangle]
pub extern "system" fn Java_dev_grainlegit_editor_NativeRenderer_encodeJpeg<'a>(
    env: JNIEnv<'a>,
    _class: JClass,
    raw: JString<'a>,
    output: JString<'a>,
    width: jint,
    height: jint,
    cancel: JObject<'a>,
) {
    call(env, |env| {
        let mut paths = unsafe { env.unsafe_clone() };
        let input = paths.get_string(&raw).map_err(|error| error.to_string())?;
        let output = paths
            .get_string(&output)
            .map_err(|error| error.to_string())?;
        crate::jpeg::encode(
            env,
            &cancel,
            &input.to_string_lossy(),
            &output.to_string_lossy(),
            width,
            height,
        )
    });
}
