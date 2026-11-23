struct View {
    viewport_center: vec2<f32>,
    image_center: vec2<f32>,
    full_size: vec2<f32>,
    texture_origin: vec2<f32>,
    texture_span: vec2<f32>,
    scale: f32,
    srgb_surface: u32,
}

@group(0) @binding(0) var<uniform> view: View;
@group(0) @binding(1) var pixels: texture_2d<f32>;
@group(0) @binding(2) var filtering: sampler;

@vertex
fn triangle(@builtin(vertex_index) i: u32) -> @builtin(position) vec4<f32> {
    let xy = array(vec2(-1.0, -1.0), vec2(3.0, -1.0), vec2(-1.0, 3.0));
    return vec4(xy[i], 0.0, 1.0);
}

fn surface_color(srgb: vec3<f32>) -> vec4<f32> {
    if view.srgb_surface == 0u {
        return vec4(srgb, 1.0);
    }
    let linear = select(pow((srgb + 0.055) / 1.055, vec3(2.4)),
                  srgb / 12.92, srgb <= vec3(0.04045));
    return vec4(linear, 1.0);
}

@fragment
fn photo(@builtin(position) screen: vec4<f32>) -> @location(0) vec4<f32> {
    let source = (screen.xy - view.viewport_center) / view.scale + view.image_center;
    if any(source < vec2(0.0)) || any(source >= view.full_size) {
        discard;
    }
    let uv = (source - view.texture_origin) / view.texture_span;
    return surface_color(textureSampleLevel(pixels, filtering, uv, 0.0).rgb);
}
