#include <metal_stdlib>
using namespace metal;

struct Cloud {
    uint offset;
    uint radius;
    float dyeLoad;
};

struct Band {
    uint width;
    uint height;
    uint pixelStride;
    uint firstRow;
    uint rows;
    uint halo;
    uint seedLow;
    uint seedHigh;
    uint inputLevels;
    uint outputKind; // 0 negative scan, 1 linear light, 2 print scan
    uint stochastic;
    uint originX;
    uint originY;
    float baseLight;
    float densityRange;
    float exposureKnee;
    float exposureSpan;
    float inputSaturation;
};

constant uint aliasSize = 128;
constant float aliasRateLimit = 64.0f;
constant float lnTwoPi = 1.8378770664f;
constant float logTwoOfTen = 3.3219280949f;

static uint mixBits(uint value) {
    value ^= value >> 16;
    value *= 0x7feb352du;
    value ^= value >> 15;
    value *= 0x846ca68bu;
    return value ^ (value >> 16);
}

static uint nextWord(thread uint &state) {
    // PCG RXS-M-XS output permutation.
    state = state * 747796405u + 2891336453u;
    uint word = ((state >> ((state >> 28u) + 4u)) ^ state) * 277803737u;
    return (word >> 22u) ^ word;
}

static float openUniform(thread uint &state) {
    return (float(nextWord(state) >> 9) + 0.5f) * 0x1p-23f;
}

static float logPoissonMass(float count, float rate) {
    if (count < 8.0f) {
        float logFactorial = 0.0f;
        for (uint n = 2; n <= uint(count); ++n) {
            logFactorial += log(float(n));
        }
        return count * log(rate) - rate - logFactorial;
    }
    // Use the Poisson deviance near the mode to avoid subtracting large
    // nearly equal terms in log(lambda^k * exp(-lambda) / k!).
    float delta = (rate - count) / count;
    float deviance;
    if (abs(delta) < 0.0625f) {
        float series = 0.5f + delta * (-1.0f / 3.0f + delta * (
            0.25f + delta * (-0.2f + delta * (1.0f / 6.0f - delta / 7.0f))));
        deviance = count * delta * delta * series;
    } else {
        deviance = rate - count - count * log(rate / count);
    }
    float inverse = 1.0f / count;
    float correction = inverse * (1.0f / 12.0f - inverse * inverse / 360.0f);
    return -deviance - (lnTwoPi + log(count)) * 0.5f - correction;
}

static float poisson(float rate, uint tableRow, device const uint *aliases, thread uint &state) {
    if (rate == 0.0f) {
        return 0.0f;
    }
    if (rate <= aliasRateLimit) {
        uint random = nextWord(state);
        uint slot = random & (aliasSize - 1);
        uint entry = aliases[tableRow * aliasSize + slot];
        return float((random >> 7) < (entry >> 7) ? slot : entry & (aliasSize - 1));
    }
    if (rate >= 1048576.0f) {
        // At very high rates the Poisson approaches a normal distribution.
        // These cells have very small dye loads and sub-code-value variance.
        float radius = sqrt(-2.0f * log(openUniform(state)));
        float normal = radius * cos(6.2831853072f * openUniform(state));
        return rint(fma(sqrt(rate), normal, rate));
    }

    // Hörmann's transformed rejection algorithm for Poisson variates.
    float b = 0.931f + 2.53f * sqrt(rate);
    float a = -0.059f + 0.02483f * b;
    float inverseAlpha = 1.1239f + 1.1328f / (b - 3.4f);
    float quickAccept = 0.9277f - 3.6224f / (b - 2.0f);
    for (;;) {
        float u = openUniform(state) - 0.5f;
        float v = openUniform(state);
        float distance = 0.5f - abs(u);
        float count = floor(fma(2.0f * a / distance + b, u, rate) + 0.43f);
        if (distance >= 0.07f && v <= quickAccept) {
            return count;
        }
        if (count < 0.0f || (distance < 0.013f && v > distance)) {
            continue;
        }
        float proposal = log(v * inverseAlpha / (a / (distance * distance) + b));
        if (proposal <= logPoissonMass(count, rate)) {
            return count;
        }
    }
}

static float2 sampleSites(
    int x, int y, uint layer, uint tone, constant Band &band,
    device const float *rates, device const uint *aliases
) {
    // Use absolute subpixel coordinates so repeated halo samples agree.
    uint row = 2 * band.inputLevels * layer + tone;
    if (!band.stochastic) {
        return float2(rates[row], rates[row + band.inputLevels]);
    }
    uint position = mixBits(uint(x) + 2 * band.originX + band.seedLow)
                  ^ mixBits(uint(y) + 2 * band.originY + band.seedHigh + layer * 0x9e3779b9u);
    uint fineState = mixBits(position + 0x682f0161u);
    uint coarseState = mixBits(position + 0x963a412fu);
    return float2(
        poisson(rates[row], row, aliases, fineState),
        poisson(rates[row + band.inputLevels], row + band.inputLevels, aliases, coarseState)
    );
}

kernel void formCloudRows(
    device const uchar *input [[buffer(0)]],
    device float2 *scratch [[buffer(1)]],
    device const float *rates [[buffer(2)]],
    device const uint *aliases [[buffer(3)]],
    constant const Cloud *clouds [[buffer(4)]],
    constant const float *weights [[buffer(5)]],
    constant Band &band [[buffer(6)]],
    threadgroup float2 *sites [[threadgroup(0)]],
    uint3 at [[thread_position_in_grid]],
    uint3 lane [[thread_position_in_threadgroup]],
    uint3 groupSize [[threads_per_threadgroup]]
) {
    uint sampleWidth = 2 * band.width;
    uint sampleRows = 2 * (band.rows + band.halo);
    uint tileWidth = groupSize.x + 2 * band.halo;
    uint tileRow = lane.y * tileWidth;
    uint layer = at.z;
    if (at.y < sampleRows) {
        int y = int(2 * band.firstRow + at.y) - int(band.halo);
        uint sourceY = uint(clamp(y, 0, int(2 * band.height) - 1)) >> 1;
        int left = int(at.x - lane.x) - int(band.halo);
        for (uint offset = lane.x; offset < tileWidth; offset += groupSize.x) {
            int x = left + int(offset);
            uint sourceX = uint(clamp(x, 0, int(sampleWidth) - 1)) >> 1;
            uint pixel = sourceY * band.width + sourceX;
            uint tone;
            if (band.inputLevels == 256) {
                device const uchar *rgb = input + band.pixelStride * pixel;
                tone = rgb[layer];
                if (band.inputSaturation != 1.0f) {
                    uint luma = (54u * rgb[0] + 183u * rgb[1] + 19u * rgb[2] + 128u) >> 8;
                    float adjusted = fma(float(tone) - float(luma), band.inputSaturation, float(luma));
                    tone = uint(rint(clamp(adjusted, 0.0f, 255.0f)));
                }
            } else {
                uint light = reinterpret_cast<device const ushort *>(input)[3 * pixel + layer];
                tone = (light * (band.inputLevels - 1) + 32767u) / 65535u;
            }
            sites[tileRow + offset] = sampleSites(x, y, layer, tone, band, rates, aliases);
        }
    }
    threadgroup_barrier(mem_flags::mem_threadgroup);
    if (at.x >= sampleWidth || at.y >= sampleRows) {
        return;
    }

    Cloud fine = clouds[2 * layer];
    Cloud coarse = clouds[2 * layer + 1];
    uint center = tileRow + lane.x + band.halo;
    float fineDensity = 0.0f;
    float coarseDensity = 0.0f;
    for (uint offset = 0; offset <= 2 * fine.radius; ++offset) {
        fineDensity = fma(sites[center - fine.radius + offset].x,
                          weights[fine.offset + offset], fineDensity);
    }
    for (uint offset = 0; offset <= 2 * coarse.radius; ++offset) {
        coarseDensity = fma(sites[center - coarse.radius + offset].y,
                            weights[coarse.offset + offset], coarseDensity);
    }
    scratch[(layer * sampleRows + at.y) * sampleWidth + at.x]
        = float2(fineDensity * fine.dyeLoad, coarseDensity * coarse.dyeLoad);
}

template <bool Fine>
static float4 spreadAndGather(
    device const float4 *rows, uint pitch, Cloud cloud, constant const float *weights
) {
    float4 density = 0.0f;
    float4 row = rows[0];
    for (uint offset = 0; offset <= 2 * cloud.radius; ++offset) {
        float4 nextRow = rows[(offset + 1) * pitch];
        float4 samples = Fine ? float4(row.xz, nextRow.xz) : float4(row.yw, nextRow.yw);
        density = fma(samples, weights[cloud.offset + offset], density);
        row = nextRow;
    }
    return density;
}

static uchar encodeOutput(float light, constant Band &band, bool print) {
    float exposure = light;
    if (!print) {
        float fraction = saturate(-fast::log2(light) / (logTwoOfTen * band.densityRange));
        float curve = fast::exp(fraction * band.exposureSpan);
        exposure = band.exposureKnee * 0.5f * (curve - 1.0f / curve);
    }
    float srgb = exposure <= 0.0031308f ? 12.92f * exposure
        : fma(1.055f, fast::powr(exposure, 1.0f / 2.4f), -0.055f);
    return uchar(rint(saturate(srgb) * 255.0f));
}

kernel void scanClouds(
    device const float4 *scratch [[buffer(0)]],
    device uchar *output [[buffer(1)]],
    constant const Cloud *clouds [[buffer(2)]],
    constant const float *weights [[buffer(3)]],
    constant Band &band [[buffer(4)]],
    uint3 at [[thread_position_in_grid]]
) {
    if (at.x >= band.width || at.y >= band.rows) {
        return;
    }
    uint sampleRows = 2 * (band.rows + band.halo);
    uint center = at.z * sampleRows + 2 * at.y + band.halo;
    Cloud fine = clouds[2 * at.z];
    Cloud coarse = clouds[2 * at.z + 1];
    float4 density = spreadAndGather<true>(
        scratch + (center - fine.radius) * band.width + at.x, band.width, fine, weights
    ) + spreadAndGather<false>(
        scratch + (center - coarse.radius) * band.width + at.x, band.width, coarse, weights
    );

    // Integrate transmitted light over the 2x2 aperture. Constant fog
    // factors out of the average and cancels when inverting the negative.
    float light = dot(fast::exp2(-logTwoOfTen * density), float4(0.25f)) * band.baseLight;
    uint index = (band.firstRow + at.y) * band.width + at.x;
    if (band.outputKind == 1) {
        reinterpret_cast<device ushort *>(output)[3 * index + at.z] = ushort(rint(light * 65535.0f));
        return;
    }
    uint pixel = band.pixelStride * index;
    output[pixel + at.z] = encodeOutput(light, band, band.outputKind == 2);
    if (band.pixelStride == 4 && at.z == 0) {
        output[pixel + 3] = 255;
    }
}

struct Optics {
    uint width;
    uint height;
    uint radius;
    uint axis;
};

kernel void filterLight(
    device const ushort *input [[buffer(0)]],
    device ushort *output [[buffer(1)]],
    constant const float *weights [[buffer(2)]],
    constant Optics &optics [[buffer(3)]],
    uint3 at [[thread_position_in_grid]]
) {
    if (at.x >= optics.width || at.y >= optics.height) {
        return;
    }
    float sum = 0.0f;
    int limit = int(optics.axis == 0 ? optics.width : optics.height) - 1;
    int center = int(optics.axis == 0 ? at.x : at.y) - int(optics.radius);
    for (uint offset = 0; offset <= 2 * optics.radius; ++offset) {
        uint position = uint(clamp(center + int(offset), 0, limit));
        uint x = optics.axis == 0 ? position : at.x;
        uint y = optics.axis == 0 ? at.y : position;
        sum = fma(float(input[3 * (y * optics.width + x) + at.z]), weights[offset], sum);
    }
    output[3 * (at.y * optics.width + at.x) + at.z] = ushort(rint(clamp(sum, 0.0f, 65535.0f)));
}

kernel void readLight(
    device const ushort *input [[buffer(0)]],
    device uchar *output [[buffer(1)]],
    constant Band &band [[buffer(2)]],
    uint3 at [[thread_position_in_grid]]
) {
    if (at.x >= band.width || at.y >= band.height) {
        return;
    }
    uint index = at.y * band.width + at.x;
    float light = max(1.0f, float(input[3 * index + at.z])) / 65535.0f;
    uint pixel = band.pixelStride * index;
    output[pixel + at.z] = encodeOutput(light, band, band.outputKind == 2);
    if (band.pixelStride == 4 && at.z == 0) {
        output[pixel + 3] = 255;
    }
}

struct GrainColour {
    uint width;
    uint height;
    float fraction;
};

kernel void balanceGrainColour(
    device ushort *formed [[buffer(0)]],
    device const ushort *reference [[buffer(1)]],
    constant GrainColour &settings [[buffer(2)]],
    uint2 at [[thread_position_in_grid]]
) {
    if (at.x >= settings.width || at.y >= settings.height) {
        return;
    }
    uint index = 3 * (at.y * settings.width + at.x);
    float3 texture = float3(formed[index], formed[index + 1], formed[index + 2]);
    float3 base = float3(reference[index], reference[index + 1], reference[index + 2]);
    // Equal log-transmission shifts correspond to a neutral density change.
    float3 shift = fast::log2(max(texture, 1.0f) / max(base, 1.0f));
    float neutral = dot(shift, float3(0.2126f, 0.7152f, 0.0722f));
    float3 light = base * fast::exp2(mix(float3(neutral), shift, settings.fraction));
    ushort3 out = ushort3(rint(clamp(light, 0.0f, 65535.0f)));
    formed[index] = out.r;
    formed[index + 1] = out.g;
    formed[index + 2] = out.b;
}
