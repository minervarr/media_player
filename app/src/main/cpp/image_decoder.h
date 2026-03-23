#ifndef IMAGE_DECODER_H
#define IMAGE_DECODER_H

#include <cstdint>
#include <cstdlib>
#include <turbojpeg.h>

struct DecodedImage {
    uint8_t* pixels; // RGBA
    int width;
    int height;

    ~DecodedImage() { free(pixels); }
};

class NativeImageDecoder {
public:
    // Decode JPEG from memory. Returns null on failure.
    // targetSize controls IDCT downscaling (1/2, 1/4, 1/8).
    static DecodedImage* decodeJpeg(const uint8_t* data, int length, int targetSize) {
        tjhandle handle = tjInitDecompress();
        if (!handle) return nullptr;

        int width, height, subsamp, colorspace;
        if (tjDecompressHeader3(handle, data, length, &width, &height, &subsamp, &colorspace) != 0) {
            tjDestroy(handle);
            return nullptr;
        }

        // Pick scale factor: find largest 1/N where scaled dims >= targetSize
        int scaledWidth = width;
        int scaledHeight = height;
        int numScalingFactors = 0;
        tjscalingfactor* factors = tjGetScalingFactors(&numScalingFactors);
        if (factors && targetSize > 0) {
            for (int i = 0; i < numScalingFactors; i++) {
                int sw = TJSCALED(width, factors[i]);
                int sh = TJSCALED(height, factors[i]);
                if (sw >= targetSize && sh >= targetSize) {
                    scaledWidth = sw;
                    scaledHeight = sh;
                }
            }
        }

        int pitch = scaledWidth * 4; // RGBA
        uint8_t* pixels = (uint8_t*)malloc(pitch * scaledHeight);
        if (!pixels) {
            tjDestroy(handle);
            return nullptr;
        }

        if (tjDecompress2(handle, data, length, pixels, scaledWidth, pitch,
                          scaledHeight, TJPF_RGBA, TJFLAG_FASTDCT) != 0) {
            free(pixels);
            tjDestroy(handle);
            return nullptr;
        }

        tjDestroy(handle);

        auto* result = new DecodedImage();
        result->pixels = pixels;
        result->width = scaledWidth;
        result->height = scaledHeight;
        return result;
    }

    // Check JPEG magic bytes: FF D8 FF
    static bool isJpeg(const uint8_t* data, int length) {
        return length >= 3 && data[0] == 0xFF && data[1] == 0xD8 && data[2] == 0xFF;
    }
};

#endif // IMAGE_DECODER_H
