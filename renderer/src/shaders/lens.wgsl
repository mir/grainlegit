struct Lens {
    origin: vec2<u32>,
    frame_size: vec2<u32>,
    vignette: f32,
    glow: f32,
    haze: f32,
    softness: f32,
    warmth: f32,
    saturation: f32,
    ghosts: f32,
    unused: f32,
    softness_radius: f32,
}

@group(0) @binding(0) var input: texture_2d<f32>;
@group(0) @binding(1) var output: texture_storage_2d<rgba8unorm, write>;
@group(0) @binding(2) var<uniform> lens: Lens;
@group(0) @binding(3) var glow_map: texture_2d<f32>;
@group(0) @binding(4) var<storage, read> highlight: vec4<f32>;
@group(0) @binding(5) var light: texture_storage_2d<rgba16float, write>;
@group(0) @binding(6) var<storage, read_write> brightest: vec4<f32>;
@group(0) @binding(7) var linear_sampler: sampler;

const LUMA = vec3(0.2126, 0.7152, 0.0722);

fn to_linear(v: vec3<f32>) -> vec3<f32> {
    return select(pow((v + 0.055) / 1.055, vec3(2.4)), v / 12.92, v <= vec3(0.04045));
}

fn to_srgb(v: vec3<f32>) -> vec3<f32> {
    return select(1.055 * pow(max(v, vec3(0.0)), vec3(1.0 / 2.4)) - 0.055,
        v * 12.92, v <= vec3(0.0031308));
}

fn sample_source(uv: vec2<f32>) -> vec3<f32> {
    return to_linear(textureSampleLevel(input, linear_sampler, uv, 0.0).rgb);
}

@compute @workgroup_size(8, 8)
fn overview(@builtin(global_invocation_id) at: vec3<u32>) {
    let size = textureDimensions(light);
    if any(at.xy >= size) { return; }
    let uv = (vec2<f32>(at.xy) + 0.5) / vec2<f32>(size);
    let radius = 0.26 / vec2<f32>(size);
    let colour = 0.25 * (sample_source(uv + radius) + sample_source(uv - radius)
        + sample_source(uv + vec2(radius.x, -radius.y)) + sample_source(uv + vec2(-radius.x, radius.y)));
    textureStore(light, vec2<i32>(at.xy), vec4(colour, 1.0));
}

fn bloom(uv: vec2<f32>, step: vec2<f32>, extract: bool) -> vec3<f32> {
    // Gaussian taps at 0.6-sigma intervals, truncated at 2.4 sigma.
    let taps = array(0.013523, 0.047042, 0.117562, 0.201728, 0.240290,
        0.201728, 0.117562, 0.047042, 0.013523);
    var sum = vec3(0.0);
    for (var i = 0; i < 9; i++) {
        var colour = textureSampleLevel(input, linear_sampler, uv + f32(i - 4) * step, 0.0).rgb;
        if extract {
            // Pick up saturated highlights while keeping the source hue in the halo.
            let peak = max(colour.r, max(colour.g, colour.b));
            colour *= smoothstep(0.48, 0.95, peak);
        }
        sum += colour * taps[i];
    }
    return sum;
}

fn blur(at: vec2<u32>, axis: vec2<f32>, extract: bool) {
    let size = textureDimensions(light);
    if any(at >= size) { return; }
    let dimensions = vec2<f32>(size);
    let uv = (vec2<f32>(at) + 0.5) / dimensions;
    let step = axis * (0.008 * length(dimensions)) / dimensions;
    textureStore(light, vec2<i32>(at), vec4(bloom(uv, step, extract), 1.0));
}

@compute @workgroup_size(8, 8)
fn glow_x(@builtin(global_invocation_id) at: vec3<u32>) {
    blur(at.xy, vec2(1.0, 0.0), true);
}

@compute @workgroup_size(8, 8)
fn glow_y(@builtin(global_invocation_id) at: vec3<u32>) {
    blur(at.xy, vec2(0.0, 1.0), false);
}

var<workgroup> peaks: array<f32, 64>;
var<workgroup> locations: array<u32, 64>;

@compute @workgroup_size(64)
fn find_highlight(@builtin(local_invocation_index) lane: u32) {
    let size = textureDimensions(input);
    var peak = 0.0;
    var index = lane;
    for (var i = lane; i < size.x * size.y; i += 64u) {
        let point = vec2<i32>(i32(i % size.x), i32(i / size.x));
        let luma = dot(textureLoad(input, point, 0).rgb, LUMA);
        if luma > peak {
            peak = luma;
            index = i;
        }
    }
    peaks[lane] = peak;
    locations[lane] = index;
    workgroupBarrier();
    for (var offset = 32u; offset > 0u; offset >>= 1u) {
        if lane < offset {
            let other = lane + offset;
            if peaks[other] > peaks[lane] ||
                (peaks[other] == peaks[lane] && locations[other] < locations[lane]) {
                peaks[lane] = peaks[other];
                locations[lane] = locations[other];
            }
        }
        workgroupBarrier();
    }
    if lane == 0u {
        let i = locations[0];
        let uv = (vec2<f32>(f32(i % size.x), f32(i / size.x)) + 0.5) / vec2<f32>(size);
        brightest = vec4(uv, smoothstep(0.68, 0.98, peaks[0]), 0.0);
    }
}

const SOFTNESS_TAPS = 32u;
const GOLDEN_ANGLE = 2.39996323;

fn frame_hash(p: vec2<u32>) -> f32 {
    var h = p.x * 0x8da6b343u ^ p.y * 0xd8163841u;
    h = (h ^ (h >> 16u)) * 0x7feb352du;
    h = (h ^ (h >> 15u)) * 0x846ca68bu;
    return f32(h ^ (h >> 16u)) / 4294967296.0;
}

// Disc blur of the given radius in pixels, sampled along a golden-angle spiral. The spiral turns
// by a per-pixel angle keyed to the frame position, so sparse taps read as noise rather than
// doubled edges; the film grain rendered afterwards hides that noise. Taps load whole texels, so
// export tiles match the full frame exactly.
fn softened(texel: vec2<i32>, frame_pixel: vec2<u32>, size: vec2<i32>, radius: f32, colour: vec3<f32>) -> vec3<f32> {
    let turn = 6.28318531 * frame_hash(frame_pixel);
    var sum = colour;
    var weight = 1.0;
    for (var i = 0u; i < SOFTNESS_TAPS; i++) {
        let t = (f32(i) + 0.5) / f32(SOFTNESS_TAPS);
        let angle = turn + f32(i) * GOLDEN_ANGLE;
        let offset = vec2<i32>(round(radius * sqrt(t) * vec2(cos(angle), sin(angle))));
        let at = clamp(texel + offset, vec2(0), size - 1);
        let w = 1.0 - 0.6 * t;
        sum += w * to_linear(textureLoad(input, at, 0).rgb);
        weight += w;
    }
    return sum / weight;
}

fn ghost_disc(point: vec2<f32>, center: vec2<f32>, radius: f32) -> f32 {
    let distance = length(point - center) / radius;
    return 0.55 * (1.0 - smoothstep(0.0, 1.0, distance))
        + 0.45 * (1.0 - smoothstep(0.08, 0.22, abs(distance - 0.75)));
}

@compute @workgroup_size(8, 8)
fn apply_lens(@builtin(global_invocation_id) at: vec3<u32>) {
    let size = textureDimensions(input);
    if any(at.xy >= size) { return; }
    let pixel = vec2<f32>(at.xy) + 0.5;
    let uv = (vec2<f32>(lens.origin) + pixel) / vec2<f32>(lens.frame_size);
    let aspect = vec2<f32>(lens.frame_size) / length(vec2<f32>(lens.frame_size));
    let position = (uv - 0.5) * aspect;
    let radius = 2.0 * length(position);
    var colour = to_linear(textureLoad(input, vec2<i32>(at.xy), 0).rgb);

    // The blur radius grows from the middle of the frame out to its full size at the corners.
    let blur = lens.softness_radius * lens.softness * smoothstep(0.30, 1.0, radius);
    if blur > 0.35 {
        colour = softened(vec2<i32>(at.xy), lens.origin + at.xy, vec2<i32>(size), blur, colour);
    }

    let warmth = lens.warmth * mix(1.0, -0.55, smoothstep(0.12, 1.0, radius));
    colour *= exp2(vec3(warmth, 0.0, -warmth));
    let grey = vec3(dot(colour, LUMA));
    colour = mix(grey, colour, 1.0 + lens.saturation * smoothstep(0.40, 1.0, radius));

    // cos^4 falloff expressed in stops, normalized to one at the farthest corner.
    let falloff = 1.1 * clamp((radius - 0.40) / 0.60, 0.0, 1.0);
    let stops = lens.vignette * log2(1.0 + falloff * falloff) / log2(2.21);
    colour *= exp2(stops);

    if lens.glow > 0.0 {
        let glow = textureSampleLevel(glow_map, linear_sampler, uv, 0.0).rgb;
        colour += lens.glow * glow * (1.0 - clamp(dot(colour, LUMA), 0.0, 1.0));
    }
    if lens.ghosts > 0.0 {
        let source = (highlight.xy - 0.5) * aspect;
        let near = ghost_disc(position, -0.42 * source, 0.024);
        let far = ghost_disc(position, -0.84 * source, 0.045);
        colour += lens.ghosts * highlight.z *
            (near * vec3(0.07, 0.12, 0.08) + far * vec3(0.10, 0.065, 0.045));
    }
    // Haze sets a small visible floor, so it does not swamp the vignette in deep shadows.
    let srgb = mix(clamp(to_srgb(colour), vec3(0.0), vec3(1.0)), vec3(1.0), lens.haze);
    textureStore(output, vec2<i32>(at.xy), vec4(srgb, 1.0));
}
