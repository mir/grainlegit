#ifndef FILMGRAIN_NATIVE_H
#define FILMGRAIN_NATIVE_H

#include <stddef.h>
#include <stdint.h>

typedef struct {
    uint8_t *pixels;
    uint32_t width;
    uint32_t height;
} FilmgrainPhoto;

typedef struct {
    uint32_t width, height, x, y;
} FilmgrainWindow;

typedef struct {
    double grain, grainSize, grainLoad, coarseFraction;
    double layerSize[3];
    double negativeWidth, exposure, development;
    double enlargement, enlargerBlur, printExposure, printLights[3], printGrain;
    double scanBlur, scanAperture;
    double grainColour;
    uint32_t printMedium; // 0 direct, 1 paper, 2 print film
    uint32_t negativeStock; // 0 generic, then the bundled stock profiles in order
} FilmgrainSettings;

typedef struct {
    uint8_t *bytes;
    size_t length;
} FilmgrainData;

int filmgrain_render(const char *source, const char *profile, const uint8_t *input,
                     uint8_t *output, uint32_t width, uint32_t height, uint32_t pixelStride,
                     const FilmgrainSettings *settings, uint64_t seed, double *seconds,
                     char *message, size_t messageLength);
int filmgrain_read_photo(const char *path, uint32_t maxEdge, FilmgrainPhoto *photo,
                        char *message, size_t messageLength);
int filmgrain_write_photo(const char *path, const char *format, const FilmgrainPhoto *photo,
                         char *message, size_t messageLength);
void filmgrain_free_photo(FilmgrainPhoto *photo);

void *filmgrain_new_preview_cache(void);
void filmgrain_free_preview_cache(void *cache);
int filmgrain_render_preview(const char *source, const char *profile, const uint8_t *input,
                             uint8_t *output, uint32_t width, uint32_t height,
                             const FilmgrainWindow *window,
                             const FilmgrainSettings *settings, uint64_t seed,
                             void *cache, double *seconds, char *message, size_t messageLength);
uint32_t filmgrain_preview_padding(const char *profile, uint32_t width, uint32_t height,
                                   const FilmgrainSettings *settings);
int filmgrain_decode_photo(const uint8_t *bytes, size_t length, FilmgrainPhoto *photo,
                          char *message, size_t messageLength);
int filmgrain_fit_photo(const FilmgrainPhoto *source, uint32_t edge, FilmgrainPhoto *photo,
                       char *message, size_t messageLength);
int filmgrain_copy_photo_rect(const FilmgrainPhoto *source, uint32_t x, uint32_t y,
                             uint32_t width, uint32_t height, FilmgrainPhoto *photo,
                             char *message, size_t messageLength);
int filmgrain_encode_photo(const FilmgrainPhoto *photo, uint32_t x, uint32_t y,
                           uint32_t width, uint32_t height, uint32_t edge, uint32_t format,
                           FilmgrainData *payload, char *message, size_t messageLength);
void filmgrain_free_data(FilmgrainData *payload);

#endif
