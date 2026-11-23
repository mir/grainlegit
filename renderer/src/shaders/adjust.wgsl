struct Adjust {
    light: vec4<f32>,
    tones: vec4<f32>,
    colour: vec4<f32>,
    balance: vec4<f32>,
    curves: array<vec4<f32>, 5>,
}

@group(0) @binding(0) var input: texture_2d<f32>;
@group(0) @binding(1) var output: texture_storage_2d<rgba8unorm, write>;
@group(0) @binding(2) var<uniform> adjust: Adjust;

const LUMINANCE = vec3(0.2126, 0.7152, 0.0722);

fn srgb_to_linear(v: vec3<f32>) -> vec3<f32> {
    return select(pow((v + 0.055) / 1.055, vec3(2.4)), v / 12.92, v <= vec3(0.04045));
}

fn linear_to_srgb(v: vec3<f32>) -> vec3<f32> {
    return select(fma(vec3(1.055), pow(max(v, vec3(0.0)), vec3(1.0 / 2.4)), vec3(-0.055)),
        v * 12.92, v <= vec3(0.0031308));
}

fn curve(input: f32, channel: u32) -> f32 {
    let x = 4.0 * clamp(input, 0.0, 1.0);
    let index = min(u32(x), 3u);
    return mix(adjust.curves[index][channel], adjust.curves[index + 1u][channel], x - f32(index));
}

@compute @workgroup_size(8, 8)
fn grade(@builtin(global_invocation_id) at: vec3<u32>) {
    let size = textureDimensions(input);
    if any(at.xy >= size) { return; }
    let point = vec2<i32>(at.xy);
    var color = srgb_to_linear(textureLoad(input, point, 0).rgb);
    if adjust.tones.w > 0.0 {
        let last = vec2<i32>(size) - 1;
        let left = srgb_to_linear(textureLoad(input, max(point - vec2(1, 0), vec2(0)), 0).rgb);
        let right = srgb_to_linear(textureLoad(input, min(point + vec2(1, 0), last), 0).rgb);
        let top = srgb_to_linear(textureLoad(input, max(point - vec2(0, 1), vec2(0)), 0).rgb);
        let bottom = srgb_to_linear(textureLoad(input, min(point + vec2(0, 1), last), 0).rgb);
        color = max(color + adjust.tones.w * (color - 0.25 * (left + right + top + bottom)), vec3(0.0));
    }

    let white_balance = vec3(0.4 * adjust.colour.x, 0.25 * adjust.colour.y, -0.4 * adjust.colour.x);
    color *= adjust.balance.rgb * exp2(white_balance + adjust.light.x);
    color = linear_to_srgb(color);
    let luminance = clamp(dot(color, LUMINANCE), 0.0, 1.0);
    let shift = 0.24 * (adjust.light.y * (4.0 * luminance * (1.0 - luminance))
        + adjust.light.z * smoothstep(0.40, 0.95, luminance)
        + adjust.light.w * (1.0 - smoothstep(0.05, 0.60, luminance))
        + adjust.tones.y * pow(luminance, 4.0)
        + adjust.tones.z * pow(1.0 - luminance, 4.0));
    color = (color + shift - 0.5) * exp2(adjust.tones.x) + 0.5;
    let grey = vec3(dot(color, LUMINANCE));
    let chroma = clamp(max(color.r, max(color.g, color.b)) - min(color.r, min(color.g, color.b)), 0.0, 1.0);
    color = grey + (color - grey) * max(0.0, 1.0 + adjust.colour.z + adjust.colour.w * (1.0 - chroma) * 0.75);
    color = vec3(curve(curve(color.r, 0u), 1u), curve(curve(color.g, 0u), 2u), curve(curve(color.b, 0u), 3u));
    textureStore(output, point, vec4(color, 1.0));
}
