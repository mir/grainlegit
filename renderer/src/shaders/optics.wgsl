struct Optics {
    // dimensions determined by the input texture
    axis: u32,
    quantized: u32,
    print_scan: u32,
    radius: u32,
    density_range: f32,
    knee: f32,
    exposure_span: f32,
    grain_colour: f32,
    grain_strength: f32,
    grain_tone: f32,
    unused: vec2<u32>,
}

@group(0) @binding(0) var input: texture_2d<f32>;
@group(0) @binding(1) var mean: texture_2d<f32>;
@group(0) @binding(2) var<uniform> info: Optics;
@group(0) @binding(3) var<storage, read> weights: array<f32>;
@group(0) @binding(4) var light: texture_storage_2d<rgba32float, write>;
@group(0) @binding(5) var rgb: texture_storage_2d<rgba8unorm, write>;

@compute @workgroup_size(8, 8)
fn convolve(@builtin(global_invocation_id) at: vec3<u32>) {
    let size = textureDimensions(input);
    if any(at.xy >= size) { return; }
    let axis = select(vec2(1, 0), vec2(0, 1), info.axis != 0u);
    let center = vec2<i32>(at.xy) - i32(info.radius) * axis;
    var result = vec3(0.0);
    for (var i = 0u; i <= 2u * info.radius; i++) {
        let point = clamp(center + i32(i) * axis, vec2(0), vec2<i32>(size) - 1);
        result = fma(textureLoad(input, point, 0).rgb, vec3(weights[i]), result);
    }
    textureStore(light, vec2<i32>(at.xy), vec4(round(clamp(result, vec3(0.0), vec3(65535.0))), 1.0));
}

@compute @workgroup_size(8, 8)
fn balance(@builtin(global_invocation_id) at: vec3<u32>) {
    if any(at.xy >= textureDimensions(input)) { return; }
    let point = vec2<i32>(at.xy);
    let base = textureLoad(mean, point, 0).rgb;
    let formed = textureLoad(input, point, 0).rgb;
    let shift = log2(max(formed, vec3(1.0)) / max(base, vec3(1.0)));
    let neutral = dot(shift, vec3(0.2126, 0.7152, 0.0722));
    let mixed = base * exp2(mix(vec3(neutral), shift, info.grain_colour));
    // Strength and tone scale the deviation in light, which keeps the mean light. The tone weight
    // follows the negative's density: dense negative is a bright scan.
    let density = clamp(-log2(max(base, vec3(1.0)) / 65535.0) / (3.3219280949 * info.density_range),
        vec3(0.0), vec3(1.0));
    let weight = info.grain_strength * exp2(info.grain_tone * (2.0 * density - 1.0));
    let balanced = max(base + (mixed - base) * weight, vec3(0.0));
    textureStore(light, point, vec4(round(clamp(balanced, vec3(0.0), vec3(65535.0))), 1.0));
}

@compute @workgroup_size(8, 8)
fn read_scan(@builtin(global_invocation_id) at: vec3<u32>) {
    if any(at.xy >= textureDimensions(input)) { return; }
    let point = vec2<i32>(at.xy);
    var linear = textureLoad(input, point, 0).rgb;
    if info.quantized != 0u { linear = max(linear, vec3(1.0)) / 65535.0; }
    if info.print_scan == 0u {
        let fraction = clamp(-log2(linear) / (3.3219280949 * info.density_range), vec3(0.0), vec3(1.0));
        let curve = exp(fraction * info.exposure_span);
        linear = info.knee * 0.5 * (curve - 1.0 / curve);
    }
    let srgb = select(fma(vec3(1.055), pow(linear, vec3(1.0 / 2.4)), vec3(-0.055)),
        linear * 12.92, linear <= vec3(0.0031308));
    textureStore(rgb, point, vec4(srgb, 1.0));
}
