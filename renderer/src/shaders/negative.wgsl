// Generic direct scan. The model follows reference/shaders/grain.metal.
struct Band {
    width: u32,
    height: u32,
    first_row: u32,
    rows: u32,
    origin: vec2<u32>,
    seed: vec2<u32>,
    halo: u32,
    tones: u32,
    stochastic: u32,
    quantize: u32,
    saturation: f32,
    base_light: f32,
    unused: vec2<u32>,
}

const RATE_START = 18u;
const LANES = 32u;
const LOG_TWO_OF_TEN = 3.3219280949;

@group(0) @binding(0) var source: texture_2d<f32>;
@group(0) @binding(1) var output: texture_storage_2d<rgba32float, write>;
@group(0) @binding(2) var<uniform> band: Band;
@group(0) @binding(3) var<storage, read_write> scratch: array<vec2<f32>>;
@group(0) @binding(4) var<storage, read> emulsion: array<u32>;
var<workgroup> sites: array<vec2<f32>, 544>; // 32 lanes + two 256-site halos.

fn mix_bits(input: u32) -> u32 {
    var value = input;
    value = (value ^ (value >> 16u)) * 0x7feb352du;
    value = (value ^ (value >> 15u)) * 0x846ca68bu;
    return value ^ (value >> 16u);
}

fn next_word(state: ptr<function, u32>) -> u32 {
    *state = *state * 747796405u + 2891336453u;
    let word = ((*state >> ((*state >> 28u) + 4u)) ^ *state) * 277803737u;
    return (word >> 22u) ^ word;
}

fn open_uniform(state: ptr<function, u32>) -> f32 {
    return (f32(next_word(state) >> 9u) + 0.5) / 8388608.0;
}

fn log_poisson_mass(count: f32, rate: f32) -> f32 {
    if count < 8.0 {
        var factorial = 0.0;
        for (var n = 2u; n <= u32(count); n++) {
            factorial += log(f32(n));
        }
        return count * log(rate) - rate - factorial;
    }
    let delta = (rate - count) / count;
    var deviance = rate - count - count * log(rate / count);
    if abs(delta) < 0.0625 {
        let series = 0.5 + delta * (-1.0 / 3.0 + delta * (
            0.25 + delta * (-0.2 + delta * (1.0 / 6.0 - delta / 7.0))));
        deviance = count * delta * delta * series;
    }
    let inverse = 1.0 / count;
    let correction = inverse * (1.0 / 12.0 - inverse * inverse / 360.0);
    return -deviance - (1.8378770664 + log(count)) * 0.5 - correction;
}

fn poisson(row: u32, initial_state: u32) -> f32 {
    let rate = bitcast<f32>(emulsion[RATE_START + row]);
    if rate == 0.0 || band.stochastic == 0u {
        return rate;
    }
    var state = initial_state;
    if rate <= 64.0 {
        let random = next_word(&state);
        let slot = random & 127u;
        let entry = emulsion[RATE_START + 6u * band.tones + row * 128u + slot];
        return f32(select(entry & 127u, slot, (random >> 7u) < (entry >> 7u)));
    }
    if rate >= 1048576.0 {
        let radius = sqrt(-2.0 * log(open_uniform(&state)));
        let normal = radius * cos(6.2831853072 * open_uniform(&state));
        return round(fma(sqrt(rate), normal, rate));
    }

    // Hörmann transformed rejection, with a bounded retry for GPU work.
    let b = 0.931 + 2.53 * sqrt(rate);
    let a = -0.059 + 0.02483 * b;
    let inverse_alpha = 1.1239 + 1.1328 / (b - 3.4);
    let quick_accept = 0.9277 - 3.6224 / (b - 2.0);
    for (var attempt = 0u; attempt < 64u; attempt++) {
        let u = open_uniform(&state) - 0.5;
        let v = open_uniform(&state);
        let distance = 0.5 - abs(u);
        let count = floor(fma(2.0 * a / distance + b, u, rate) + 0.43);
        if distance >= 0.07 && v <= quick_accept {
            return count;
        }
        if count < 0.0 || (distance < 0.013 && v > distance) {
            continue;
        }
        let proposal = log(v * inverse_alpha / (a / (distance * distance) + b));
        if proposal <= log_poisson_mass(count, rate) {
            return count;
        }
    }
    return round(rate);
}

fn sample_sites(x: i32, y: i32, layer: u32) -> vec2<f32> {
    let source_x = clamp(x, 0, i32(2u * band.width - 1u)) >> 1u;
    let source_y = clamp(y, 0, i32(2u * band.height - 1u)) >> 1u;
    let pixel = textureLoad(source, vec2(source_x, source_y), 0).rgb;
    var tone: u32;
    if band.tones == 256u {
        let rgb = vec3<u32>(round(255.0 * pixel));
        let luminance = f32((54u * rgb.r + 183u * rgb.g + 19u * rgb.b + 128u) >> 8u);
        tone = u32(round(clamp(fma(f32(rgb[layer]) - luminance, band.saturation, luminance), 0.0, 255.0)));
    } else {
        tone = (u32(pixel[layer]) * (band.tones - 1u) + 32767u) / 65535u;
    }
    let row = 2u * layer * band.tones + tone;
    let position = mix_bits(bitcast<u32>(x) + 2u * band.origin.x + band.seed.x)
        ^ mix_bits(bitcast<u32>(y) + 2u * band.origin.y + band.seed.y + layer * 0x9e3779b9u);
    return vec2(poisson(row, mix_bits(position + 0x682f0161u)),
                poisson(row + band.tones, mix_bits(position + 0x963a412fu)));
}

fn pitch() -> u32 {
    return band.width * 2u;
}

fn sample_rows() -> u32 {
    return band.rows * 2u + 2u * band.halo;
}

@compute @workgroup_size(32)
fn form_rows(@builtin(global_invocation_id) at: vec3<u32>,
             @builtin(local_invocation_id) lane: vec3<u32>) {
    let start = i32(at.x - lane.x) - i32(band.halo);
    let y = i32(2u * band.first_row + at.y) - i32(band.halo);
    for (var offset = lane.x; offset < LANES + 2u * band.halo; offset += LANES) {
        sites[offset] = sample_sites(start + i32(offset), y, at.z);
    }
    workgroupBarrier();
    if at.x >= pitch() {
        return;
    }

    let center = lane.x + band.halo;
    var horizontal = vec2(0.0);
    for (var population = 0u; population < 2u; population++) {
        let cloud = 3u * (2u * at.z + population);
        let radius = emulsion[cloud];
        let weight_offset = emulsion[cloud + 1u];
        var sum = 0.0;
        for (var i = 0u; i <= 2u * radius; i++) {
            sum = fma(sites[center - radius + i][population],
                      bitcast<f32>(emulsion[weight_offset + i]), sum);
        }
        horizontal[population] = sum * bitcast<f32>(emulsion[cloud + 2u]);
    }
    scratch[(at.z * sample_rows() + at.y) * pitch() + at.x] = horizontal;
}

fn scan_layer(at: vec2<u32>, layer: u32) -> f32 {
    let center = layer * sample_rows() + 2u * at.y + band.halo;
    let x = at.x * 2u;
    var density = vec4(0.0);
    for (var population = 0u; population < 2u; population++) {
        let cloud = 3u * (2u * layer + population);
        let radius = emulsion[cloud];
        let weight_offset = emulsion[cloud + 1u];
        for (var i = 0u; i <= 2u * radius; i++) {
            let index = (center - radius + i) * pitch() + x;
            let values = vec4(scratch[index][population], scratch[index + 1u][population],
                 scratch[index + pitch()][population], scratch[index + pitch() + 1u][population]);
            density = fma(values, vec4(bitcast<f32>(emulsion[weight_offset + i])), density);
        }
    }
    let light = dot(exp2(-LOG_TWO_OF_TEN * density), vec4(0.25)) * band.base_light;
    return select(light, round(65535.0 * clamp(light, 0.0, 1.0)), band.quantize != 0u);
}

@compute @workgroup_size(8, 8)
fn scan(@builtin(global_invocation_id) at: vec3<u32>) {
    if at.x >= band.width || at.y >= band.rows {
        return;
    }
    let rgb = vec3(scan_layer(at.xy, 0u), scan_layer(at.xy, 1u), scan_layer(at.xy, 2u));
    textureStore(output, vec2<i32>(vec2(at.x, at.y + band.first_row)), vec4(rgb, 1.0));
}
