#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#include <math.h>
#include <stdio.h>
#include <string.h>
#include "filmgrain_native.h"

typedef struct {
    uint32_t offset;
    uint32_t radius;
    float dyeLoad;
} Cloud;

typedef struct {
    uint32_t width, height, pixelStride, firstRow, rows, halo, seedLow, seedHigh;
    uint32_t inputLevels, outputKind, stochastic;
    uint32_t originX, originY;
    float baseLight, densityRange, exposureKnee, exposureSpan, inputSaturation;
} Band;

typedef struct {
    Band band;
    id<MTLBuffer> clouds, weights, rates, aliases;
} Emulsion;

enum { rgbLayers = 3, cloudsPerLayer = 2, aliasSize = 128, printLevels = 4096, bandHeight = 256 };
enum { negativeScan, linearLight, printScan };

static id<MTLDevice> device;
static id<MTLCommandQueue> queue;
static id<MTLComputePipelineState> formPipeline, scanPipeline, filterPipeline, readPipeline, grainColourPipeline;

@interface PreviewCache : NSObject {
@public
    id<MTLBuffer> camera;
    id<MTLBuffer> negative;
    id<MTLCommandQueue> commands;
    FilmgrainSettings cachedSettings;
    uint64_t cachedSeed;
    const uint8_t *sourcePixels;
    uint32_t sourceWidth, sourceHeight;
}
@end

@implementation PreviewCache
@end

void *filmgrain_new_preview_cache(void) {
    return (__bridge_retained void *)[PreviewCache new];
}

void filmgrain_free_preview_cache(void *cache) {
    (void)CFBridgingRelease(cache);
}

static BOOL sameNegative(const FilmgrainSettings *a, const FilmgrainSettings *b) {
    return a->negativeStock == b->negativeStock
        && a->grain == b->grain && a->grainSize == b->grainSize && a->grainLoad == b->grainLoad
        && a->coarseFraction == b->coarseFraction && a->negativeWidth == b->negativeWidth
        && a->exposure == b->exposure && a->development == b->development && a->grainColour == b->grainColour
        && !memcmp(a->layerSize, b->layerSize, sizeof(a->layerSize));
}

static int fail(char *message, size_t length, NSString *reason) {
    snprintf(message, length, "%s", reason.UTF8String);
    return 1;
}

static NSString *setupMetal(const char *source) {
    static dispatch_once_t initialization;
    static NSString *failure;
    dispatch_once(&initialization, ^{
        device = MTLCreateSystemDefaultDevice();
        queue = [device newCommandQueue];
        if (!queue) {
            failure = @"No Metal device or command queue is available";
            return;
        }
        NSError *error = nil;
        id<MTLLibrary> library = [device newLibraryWithSource:@(source) options:nil error:&error];
        if (library) {
            id<MTLComputePipelineState> __strong *pipelines[] =
                {&formPipeline, &scanPipeline, &filterPipeline, &readPipeline, &grainColourPipeline};
            NSArray *names = @[@"formCloudRows", @"scanClouds", @"filterLight", @"readLight", @"balanceGrainColour"];
            for (NSUInteger i = 0; i < names.count; ++i) {
                id<MTLFunction> function = [library newFunctionWithName:names[i]];
                *pipelines[i] = function ? [device newComputePipelineStateWithFunction:function error:&error] : nil;
                if (!*pipelines[i]) break;
            }
        }
        if (!grainColourPipeline) failure = error.localizedDescription ?: @"Metal shader compilation failed";
    });
    return failure;
}

static void makeAliases(float rate, uint32_t *table) {
    // The omitted Poisson tail above 127 is below 2e-12 for rates up to 64.
    if (rate <= 0 || rate > 64) {
        return;
    }
    double mass[aliasSize] = {exp(-rate)};
    double total = mass[0];
    for (int count = 1; count < aliasSize; ++count) {
        mass[count] = mass[count - 1] * rate / count;
        total += mass[count];
    }
    int small[aliasSize], large[aliasSize], smallCount = 0, largeCount = 0;
    for (int count = 0; count < aliasSize; ++count) {
        table[count] = (uint32_t)count;
        mass[count] *= aliasSize / total;
        if (mass[count] < 1) {
            small[smallCount++] = count;
        } else {
            large[largeCount++] = count;
        }
    }
    while (smallCount && largeCount) {
        int slot = small[--smallCount];
        int alias = large[--largeCount];
        uint32_t cutoff = (uint32_t)(mass[slot] * (1u << 25));
        table[slot] = (cutoff << 7) | (uint32_t)alias;
        mass[alias] -= 1 - mass[slot];
        if (mass[alias] < 1) {
            small[smallCount++] = alias;
        } else {
            large[largeCount++] = alias;
        }
    }
}

static void gaussianKernel(double sigma, double *kernel, uint32_t radius) {
    if (!radius) {
        kernel[0] = 1;
        return;
    }
    double scale = 1 / (M_SQRT2 * sigma);
    double lower = 0.5 * erfc((radius + 0.5) * scale);
    double total = 0;
    for (size_t i = 0; i <= 2 * radius; ++i) {
        double upper = 0.5 * erfc(((double)radius - i - 0.5) * scale);
        kernel[i] = upper - lower;
        lower = upper;
        total += kernel[i];
    }
    for (size_t i = 0; i <= 2 * radius; ++i) kernel[i] /= total;
}

static double sampleCloud(Cloud *cloud, NSMutableData *weights, double sigma, double peak,
                          double fraction, BOOL reference) {
    cloud->offset = (uint32_t)(weights.length / sizeof(float));
    cloud->radius = sigma ? (uint32_t)MAX(1, ceil(4 * sigma)) : 0;
    double dyeLoad = sigma ? peak * 2 * M_PI * sigma * sigma : peak;
    cloud->dyeLoad = (float)dyeLoad;
    size_t length = 2 * cloud->radius + 1;
    NSMutableData *exactStorage = [NSMutableData dataWithLength:length * sizeof(double)];
    double *exact = exactStorage.mutableBytes;
    gaussianKernel(sigma, exact, cloud->radius);
    [weights increaseLengthBy:length * sizeof(float)];
    float *kernel = (float *)weights.mutableBytes + cloud->offset;
    for (size_t i = 0; i < length; ++i) kernel[i] = (float)exact[i];

    // Derive the Poisson event rate that preserves flat-field transmission.
    if (!sigma) return fraction;
    // Use the same cloud footprint for a smooth expected-density reference.
    if (reference) return fraction / dyeLoad;
    double logTen = log(10);
    double mass = 0;
    for (size_t y = 0; y < length; ++y) {
        for (size_t x = 0; x < length; ++x) {
            mass -= expm1(-logTen * dyeLoad * exact[y] * exact[x]);
        }
    }
    return fraction * logTen / mass;
}

static float targetDensity(double signal, double gain, double slope, NSDictionary *profile, BOOL print) {
    double range = [profile[@"image_density_range"] doubleValue];
    if (print) {
        double t = pow(signal * gain / [profile[@"exposure_midpoint"] doubleValue],
                       [profile[@"contrast"] doubleValue]);
        return (float)(range * t / (1 + t));
    }
    double exposure = signal <= 0.04045 ? signal / 12.92 : pow((signal + 0.055) / 1.055, 2.4);
    double knee = [profile[@"exposure_knee"] doubleValue];
    double density = range * asinh(exposure * gain / knee) * slope / asinh(1 / knee);
    if (density > range) {
        double shoulder = [profile[@"shoulder_range"] doubleValue];
        density = range - shoulder * expm1(-(density - range) / shoulder);
    }
    return (float)density;
}

static double cloudScale(uint32_t edge, const FilmgrainSettings *options, BOOL print) {
    double widthMM = options->negativeWidth * (print ? options->enlargement : 1);
    double scale = 2 * (double)edge / (1000 * widthMM);
    return print ? scale : scale * sqrt(options->grain) * options->grainSize;
}

static double cloudSigma(NSArray *spec, double scale, const FilmgrainSettings *options,
                          BOOL print, uint32_t layer) {
    return [spec[0] doubleValue] * scale * (print ? 1 : options->layerSize[layer]);
}

static BOOL prepareEmulsion(Emulsion *plate, Band band, uint32_t edge, NSDictionary *profile,
                            const FilmgrainSettings *options, BOOL print, BOOL reference) {
    const BOOL active = (print ? options->printGrain : options->grain) > 1e-6;
    const BOOL stochastic = active && !reference;
    band.stochastic = stochastic;
    band.inputLevels = print ? printLevels : 256;
    NSNumber *saturation = profile[@"saturation"];
    band.inputSaturation = saturation ? saturation.floatValue : 1;
    double scale = cloudScale(edge, options, print);
    double load = print ? options->printGrain : options->grainLoad * exp2(options->development / 3);
    uint32_t rateCount = rgbLayers * cloudsPerLayer * band.inputLevels;
    NSUInteger aliasCount = stochastic ? (NSUInteger)rateCount * aliasSize : 1;
    NSMutableData *rateData = [NSMutableData dataWithLength:rateCount * sizeof(float)];
    NSMutableData *aliasData = [NSMutableData dataWithLength:aliasCount * sizeof(uint32_t)];
    NSMutableData *weights = [NSMutableData data];
    float *rates = rateData.mutableBytes;
    uint32_t *aliases = aliasData.mutableBytes;
    Cloud clouds[rgbLayers * cloudsPerLayer];
    NSArray *layers = profile[@"layers"];
    NSArray *development = profile[@"development_sensitivity"];
    NSArray *exposureBalance = profile[@"exposure_balance_ev"];

    for (uint32_t layer = 0; layer < rgbLayers; ++layer) {
        NSArray *populations = layers[layer];
        double gain = exp2(print ? options->printExposure + options->printLights[layer]
                                 : options->exposure + [exposureBalance[layer] doubleValue]);
        double slope = print ? 1 : exp2(options->development * [development[layer] doubleValue] / 2);
        for (uint32_t population = 0; population < cloudsPerLayer; ++population) {
            uint32_t id = cloudsPerLayer * layer + population;
            NSArray *spec = populations[population];
            double sigma = active ? cloudSigma(spec, scale, options, print, layer) : 0;
            if (sigma > 128) return NO;
            double fraction = [spec[2] doubleValue];
            if (!active) fraction = population == 0 ? 1 : 0;
            if (active && !print && options->coarseFraction >= 0) {
                fraction = population == 1 ? options->coarseFraction : 1 - options->coarseFraction;
            }
            double peak = active ? [spec[1] doubleValue] * load : 1;
            double events = sampleCloud(&clouds[id], weights, sigma, peak, fraction, reference);
            band.halo = MAX(band.halo, clouds[id].radius);
            for (uint32_t tone = 0; tone < band.inputLevels; ++tone) {
                uint32_t row = id * band.inputLevels + tone;
                double intensity = (double)tone / (band.inputLevels - 1);
                rates[row] = (float)(targetDensity(intensity, gain, slope, profile, print) * events);
                if (stochastic) makeAliases(rates[row], aliases + (NSUInteger)row * aliasSize);
            }
        }
    }

    plate->band = band;
    plate->clouds = [device newBufferWithBytes:clouds length:sizeof(clouds) options:MTLResourceStorageModeShared];
    plate->weights = [device newBufferWithBytes:weights.bytes length:weights.length options:MTLResourceStorageModeShared];
    plate->rates = [device newBufferWithBytes:rates length:rateData.length options:MTLResourceStorageModeShared];
    plate->aliases = [device newBufferWithBytes:aliases length:aliasData.length options:MTLResourceStorageModeShared];
    return plate->clouds && plate->weights && plate->rates && plate->aliases;
}

static void dispatchRGB(id<MTLComputeCommandEncoder> encoder, NSUInteger width,
                        NSUInteger height, MTLSize group) {
    MTLSize count = MTLSizeMake((width + group.width - 1) / group.width,
                               (height + group.height - 1) / group.height, rgbLayers);
    [encoder dispatchThreadgroups:count threadsPerThreadgroup:group];
    [encoder memoryBarrierWithScope:MTLBarrierScopeBuffers];
}

static void encodeEmulsion(id<MTLComputeCommandEncoder> encoder, const Emulsion *plate,
                           id<MTLBuffer> input, id<MTLBuffer> output, id<MTLBuffer> scratch,
                           MTLSize formGroup, MTLSize scanGroup) {
    Band band = plate->band;
    NSUInteger tileBytes = (formGroup.width + 2 * band.halo) * formGroup.height * 2 * sizeof(float);
    for (uint32_t first = 0; first < band.height; first += band.rows) {
        band.firstRow = first;
        band.rows = MIN(bandHeight, band.height - first);
        [encoder setComputePipelineState:formPipeline];
        [encoder setBuffer:input offset:0 atIndex:0];
        [encoder setBuffer:scratch offset:0 atIndex:1];
        [encoder setBuffer:plate->rates offset:0 atIndex:2];
        [encoder setBuffer:plate->aliases offset:0 atIndex:3];
        [encoder setBuffer:plate->clouds offset:0 atIndex:4];
        [encoder setBuffer:plate->weights offset:0 atIndex:5];
        [encoder setBytes:&band length:sizeof(band) atIndex:6];
        [encoder setThreadgroupMemoryLength:tileBytes atIndex:0];
        dispatchRGB(encoder, 2 * band.width, 2 * (band.rows + band.halo), formGroup);

        [encoder setComputePipelineState:scanPipeline];
        [encoder setBuffer:scratch offset:0 atIndex:0];
        [encoder setBuffer:output offset:0 atIndex:1];
        [encoder setBuffer:plate->clouds offset:0 atIndex:2];
        [encoder setBuffer:plate->weights offset:0 atIndex:3];
        [encoder setBytes:&band length:sizeof(band) atIndex:4];
        dispatchRGB(encoder, band.width, band.rows, scanGroup);
    }
}

static void balanceGrain(id<MTLComputeCommandEncoder> encoder, const Emulsion *mean,
                         id<MTLBuffer> input, id<MTLBuffer> formed, id<MTLBuffer> reference,
                         id<MTLBuffer> scratch, MTLSize formGroup, MTLSize scanGroup, float colour) {
    encodeEmulsion(encoder, mean, input, reference, scratch, formGroup, scanGroup);
    struct {
        uint32_t width, height;
        float fraction;
    } balance = {mean->band.width, mean->band.height, colour};
    [encoder setComputePipelineState:grainColourPipeline];
    [encoder setBuffer:formed offset:0 atIndex:0];
    [encoder setBuffer:reference offset:0 atIndex:1];
    [encoder setBytes:&balance length:sizeof(balance) atIndex:2];
    MTLSize count = MTLSizeMake((balance.width + scanGroup.width - 1) / scanGroup.width,
                               (balance.height + scanGroup.height - 1) / scanGroup.height, 1);
    [encoder dispatchThreadgroups:count threadsPerThreadgroup:scanGroup];
    [encoder memoryBarrierWithScope:MTLBarrierScopeBuffers];
}

static NSData *opticalWeights(double sigma, double aperture) {
    uint32_t gaussianRadius = (uint32_t)ceil(4 * sigma);
    uint32_t apertureRadius = (uint32_t)ceil((aperture - 1) / 2);
    NSUInteger count = 2 * (gaussianRadius + apertureRadius) + 1;
    NSMutableData *Gaussian = [NSMutableData dataWithLength:(2 * gaussianRadius + 1) * sizeof(double)];
    double *g = Gaussian.mutableBytes;
    gaussianKernel(sigma, g, gaussianRadius);
    NSMutableData *data = [NSMutableData dataWithLength:count * sizeof(float)];
    float *kernel = data.mutableBytes;
    for (uint32_t j = 0; j <= 2 * apertureRadius; ++j) {
        double delta = fabs((double)j - apertureRadius);
        double weight = fmax(0, fmin(1, aperture / 2 + 0.5 - delta)) / aperture;
        for (uint32_t i = 0; i <= 2 * gaussianRadius; ++i) kernel[i + j] += (float)(g[i] * weight);
    }
    return data;
}

static NSDictionary *readProfiles(const char *profile) {
    NSData *data = [NSData dataWithBytes:profile length:strlen(profile)];
    return [NSJSONSerialization JSONObjectWithData:data options:0 error:nil];
}

static NSDictionary *negativeEmulsion(NSDictionary *materials, const FilmgrainSettings *settings) {
    return settings->negativeStock ? materials[@"negative_stocks"][settings->negativeStock - 1]
                                   : materials[@"negative"];
}

static uint32_t emulsionPadding(uint32_t edge, NSDictionary *profile,
                                const FilmgrainSettings *settings, BOOL print) {
    if ((print ? settings->printGrain : settings->grain) <= 1e-6) return 0;
    NSArray *layers = profile[@"layers"];
    double scale = cloudScale(edge, settings, print);
    double sigma = 0;
    for (uint32_t layer = 0; layer < rgbLayers; ++layer) {
        for (NSArray *spec in layers[layer]) {
            sigma = MAX(sigma, cloudSigma(spec, scale, settings, print, layer));
        }
    }
    return (uint32_t)ceil(MAX(1, ceil(4 * sigma)) / 2);
}

uint32_t filmgrain_preview_padding(const char *profile, uint32_t width, uint32_t height,
                                   const FilmgrainSettings *settings) {
    @autoreleasepool {
        NSDictionary *materials = readProfiles(profile);
        uint32_t edge = MAX(width, height);
        uint32_t padding = emulsionPadding(edge, negativeEmulsion(materials, settings), settings, NO);
        if (settings->printMedium) {
            NSDictionary *printProfile = materials[settings->printMedium == 1 ? @"paper" : @"film"];
            padding += emulsionPadding(edge, printProfile, settings, YES);
            double printMM = settings->negativeWidth * settings->enlargement;
            padding += (uint32_t)ceil(4 * settings->enlargerBlur * edge / (1000 * printMM));
        }
        padding += (uint32_t)(ceil(4 * settings->scanBlur) + ceil((settings->scanAperture - 1) / 2));
        return padding;
    }
}

static void filterLight(id<MTLComputeCommandEncoder> encoder, id<MTLBuffer> light,
                        id<MTLBuffer> scratch, id<MTLBuffer> filtered,
                        id<MTLBuffer> weights, Band band, MTLSize group) {
    uint32_t radius = (uint32_t)(weights.length / sizeof(float) / 2);
    [encoder setComputePipelineState:filterPipeline];
    [encoder setBuffer:weights offset:0 atIndex:2];
    for (uint32_t axis = 0; axis < 2; ++axis) {
        uint32_t optics[] = {band.width, band.height, radius, axis};
        [encoder setBuffer:axis ? scratch : light offset:0 atIndex:0];
        [encoder setBuffer:axis ? filtered : scratch offset:0 atIndex:1];
        [encoder setBytes:optics length:sizeof(optics) atIndex:3];
        dispatchRGB(encoder, band.width, band.height, group);
    }
}

static int render(const char *source, const char *profile, const uint8_t *input,
                  uint8_t *output, uint32_t width, uint32_t height, uint32_t pixelStride,
                  const FilmgrainWindow *window, const FilmgrainSettings *settings, uint64_t seed, PreviewCache *cache,
                  double *seconds, char *message, size_t messageLength) {
    @autoreleasepool {
        if (!width || !height || (pixelStride != 3 && pixelStride != 4) || settings->printMedium > 2) {
            return fail(message, messageLength, @"Invalid image size, channel layout or print medium");
        }
        uint32_t edge = window ? MAX(window->width, window->height) : MAX(width, height);
        double printMM = settings->negativeWidth * settings->enlargement;
        double enlargerSigma = settings->enlargerBlur * edge / (1000 * printMM);
        if (enlargerSigma > 128) {
            return fail(message, messageLength, @"Enlarger blur exceeds the Metal limit at this print size");
        }
        NSString *failure = setupMetal(source);
        if (failure) return fail(message, messageLength, failure);

        NSDictionary *materials = readProfiles(profile);
        NSDictionary *negativeProfile = negativeEmulsion(materials, settings);
        // Use one scan calibration so stock-dependent density curves remain visible after inversion.
        NSDictionary *scanProfile = materials[@"negative"];
        BOOL printing = settings->printMedium != 0;
        BOOL enlarge = printing && enlargerSigma > 0;
        if (cache && (cache->sourcePixels != input || cache->sourceWidth != width || cache->sourceHeight != height)) {
            cache->camera = nil;
            cache->negative = nil;
            cache->sourcePixels = input;
            cache->sourceWidth = width;
            cache->sourceHeight = height;
        }
        BOOL reuseNegative = printing && cache && cache->negative && cache->cachedSeed == seed
            && sameNegative(&cache->cachedSettings, settings);
        BOOL scanOptics = settings->scanBlur > 0 || settings->scanAperture > 1;
        BOOL balanceNegative = settings->grainColour < 1 && settings->grain > 1e-6;
        BOOL balancePrint = printing && settings->grainColour < 1 && settings->printGrain > 1e-6;
        BOOL needsReference = (balanceNegative && !reuseNegative) || balancePrint;
        BOOL negativeLight = printing || scanOptics || balanceNegative;
        BOOL positiveLight = printing && (scanOptics || balancePrint);
        double knee = [scanProfile[@"exposure_knee"] doubleValue];
        Band negativeBand = {
            .width = width, .height = height, .pixelStride = pixelStride,
            .seedLow = (uint32_t)seed, .seedHigh = (uint32_t)(seed >> 32),
            .originX = window ? window->x : 0, .originY = window ? window->y : 0,
            .outputKind = negativeLight ? linearLight : negativeScan,
            .baseLight = printing ? (float)pow(10, -[negativeProfile[@"fog_density"] doubleValue]) : 1,
            .densityRange = [scanProfile[@"image_density_range"] floatValue],
            .exposureKnee = (float)knee, .exposureSpan = (float)asinh(1 / knee),
        };
        Emulsion negative = {0}, positive = {0}, negativeMean = {0}, printMean = {0};
        if (!reuseNegative && (!prepareEmulsion(&negative, negativeBand, edge, negativeProfile, settings, NO, NO)
            || (balanceNegative && !prepareEmulsion(&negativeMean, negativeBand, edge, negativeProfile, settings, NO, YES)))) {
            return fail(message, messageLength, @"Could not prepare the negative emulsion at this size");
        }
        if (printing) {
            NSDictionary *printProfile = materials[settings->printMedium == 1 ? @"paper" : @"film"];
            uint64_t printSeed = seed ^ UINT64_C(0xd1b54a32d192ed03);
            Band printBand = negativeBand;
            printBand.seedLow = (uint32_t)printSeed;
            printBand.seedHigh = (uint32_t)(printSeed >> 32);
            printBand.outputKind = positiveLight ? linearLight : printScan;
            printBand.baseLight = (float)pow(10, -[printProfile[@"fog_density"] doubleValue]);
            if (!prepareEmulsion(&positive, printBand, edge, printProfile, settings, YES, NO)
                || (balancePrint && !prepareEmulsion(&printMean, printBand, edge, printProfile, settings, YES, YES))) {
                return fail(message, messageLength, @"Could not prepare the print emulsion at this size");
            }
        }

        uint32_t halo = MAX(negative.band.halo, positive.band.halo);
        NSUInteger pixels = (NSUInteger)width * height;
        NSUInteger imageBytes = pixels * pixelStride;
        NSUInteger lightBytes = pixels * rgbLayers * sizeof(uint16_t);
        NSUInteger scratchCells = 2 * (NSUInteger)width * (2 * (MIN(bandHeight, height) + halo)) * rgbLayers;
        NSUInteger scratchBytes = scratchCells * 2 * sizeof(float);
        MTLSize formGroup = MTLSizeMake(2 * formPipeline.threadExecutionWidth, 2, 1);
        MTLSize scanGroup = MTLSizeMake(scanPipeline.threadExecutionWidth, 4, 1);
        NSUInteger tileBytes = (formGroup.width + 2 * halo) * formGroup.height * 2 * sizeof(float);
        if (imageBytes > MIN(device.maxBufferLength, UINT32_MAX) ||
            (negativeLight && lightBytes > MIN(device.maxBufferLength, UINT32_MAX)) ||
            scratchBytes > device.maxBufferLength || scratchCells > UINT32_MAX ||
            tileBytes > device.maxThreadgroupMemoryLength) {
            return fail(message, messageLength, @"Image or cloud radius exceeds Metal buffer limits");
        }

        const MTLResourceOptions shared = MTLResourceStorageModeShared;
        const MTLResourceOptions resident = MTLResourceStorageModePrivate;
        id<MTLBuffer> camera = cache ? cache->camera : nil;
        if (!camera && !reuseNegative) {
            camera = [device newBufferWithBytes:input length:imageBytes options:shared];
            if (cache) cache->camera = camera;
        }
        id<MTLBuffer> result = [device newBufferWithLength:imageBytes options:shared];
        id<MTLBuffer> scratch = [device newBufferWithLength:scratchBytes options:resident];
        id<MTLBuffer> light = reuseNegative ? cache->negative
            : negativeLight ? [device newBufferWithLength:lightBytes options:resident] : nil;
        id<MTLBuffer> opticalScratch = enlarge || scanOptics ? [device newBufferWithLength:lightBytes options:resident] : nil;
        id<MTLBuffer> printLight = positiveLight ? [device newBufferWithLength:lightBytes options:resident] : nil;
        id<MTLBuffer> reference = needsReference ? [device newBufferWithLength:lightBytes options:resident] : nil;
        id<MTLBuffer> projection = enlarge && cache ? [device newBufferWithLength:lightBytes options:resident] : light;
        id<MTLBuffer> enlargerWeights = nil, scanWeights = nil;
        if (enlarge) {
            NSData *weights = opticalWeights(enlargerSigma, 1);
            enlargerWeights = [device newBufferWithBytes:weights.bytes length:weights.length options:shared];
        }
        if (scanOptics) {
            NSData *weights = opticalWeights(settings->scanBlur, settings->scanAperture);
            scanWeights = [device newBufferWithBytes:weights.bytes length:weights.length options:shared];
        }
        if (cache && !cache->commands) cache->commands = [device newCommandQueue];
        id<MTLCommandBuffer> command = [cache ? cache->commands : queue commandBuffer];
        id<MTLComputeCommandEncoder> encoder = [command computeCommandEncoder];
        if ((!reuseNegative && !camera) || !result || !scratch || !encoder ||
            (negativeLight && !light) || ((enlarge || scanOptics) && !opticalScratch) ||
            (positiveLight && !printLight) || (needsReference && !reference) ||
            (enlarge && (!enlargerWeights || !projection)) ||
            (scanOptics && !scanWeights)) {
            return fail(message, messageLength, @"Metal could not allocate image or compute resources");
        }

        if (!reuseNegative) {
            encodeEmulsion(encoder, &negative, camera, light ?: result, scratch, formGroup, scanGroup);
            if (balanceNegative) {
                balanceGrain(encoder, &negativeMean, camera, light, reference, scratch,
                             formGroup, scanGroup, (float)settings->grainColour);
            }
        }
        if (enlarge) filterLight(encoder, light, opticalScratch, projection, enlargerWeights, negativeBand, scanGroup);
        if (printing) {
            encodeEmulsion(encoder, &positive, projection, printLight ?: result, scratch, formGroup, scanGroup);
            if (balancePrint) {
                balanceGrain(encoder, &printMean, projection, printLight, reference, scratch,
                             formGroup, scanGroup, (float)settings->grainColour);
            }
        }
        if (printing ? positiveLight : negativeLight) {
            id<MTLBuffer> scanLight = printLight ?: light;
            if (scanOptics) filterLight(encoder, scanLight, opticalScratch, scanLight, scanWeights, negativeBand, scanGroup);
            Band readBand = negativeBand;
            readBand.outputKind = printing ? printScan : negativeScan;
            [encoder setComputePipelineState:readPipeline];
            [encoder setBuffer:scanLight offset:0 atIndex:0];
            [encoder setBuffer:result offset:0 atIndex:1];
            [encoder setBytes:&readBand length:sizeof(readBand) atIndex:2];
            dispatchRGB(encoder, width, height, scanGroup);
        }
        [encoder endEncoding];
        [command commit];
        [command waitUntilCompleted];
        if (command.status != MTLCommandBufferStatusCompleted) {
            return fail(message, messageLength, command.error.localizedDescription ?: @"Metal render failed");
        }
        if (printing && cache && !reuseNegative) {
            cache->negative = light;
            cache->cachedSettings = *settings;
            cache->cachedSeed = seed;
        }
        memcpy(output, result.contents, imageBytes);
        *seconds = command.GPUEndTime - command.GPUStartTime;
        return 0;
    }
}

int filmgrain_render(const char *source, const char *profile, const uint8_t *input,
                     uint8_t *output, uint32_t width, uint32_t height, uint32_t pixelStride,
                     const FilmgrainSettings *settings, uint64_t seed, double *seconds,
                     char *message, size_t messageLength) {
    return render(source, profile, input, output, width, height, pixelStride,
                  NULL, settings, seed, nil, seconds, message, messageLength);
}

int filmgrain_render_preview(const char *source, const char *profile, const uint8_t *input,
                             uint8_t *output, uint32_t width, uint32_t height,
                             const FilmgrainWindow *window,
                             const FilmgrainSettings *settings, uint64_t seed,
                             void *cache, double *seconds, char *message, size_t messageLength) {
    return render(source, profile, input, output, width, height, 4, window, settings, seed,
                  (__bridge PreviewCache *)cache, seconds, message, messageLength);
}
