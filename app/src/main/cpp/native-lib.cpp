#include <jni.h>
#include <android/bitmap.h>
#include <string>
#include <vector>
#include <fstream>
#include <cmath>
#include <algorithm>
#include <cstdint>

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_nativebridge_NativeBridge_getEngineVersion(
        JNIEnv* env,
        jobject /* this */) {
    std::string info = "ZamLab Native C++ Engine (16-bit TIFF + Fast Heal + Histogram)";
    return env->NewStringUTF(info.c_str());
}

// 1. REAL 16-BIT UNCOMPRESSED TIFF EXPORT (Bypasses JVM Heap Limits)
extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_nativebridge_NativeBridge_export16BitTiffNative(
        JNIEnv* env,
        jobject /* this */,
        jobject bitmap,
        jstring outputPath) {
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) return JNI_FALSE;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return JNI_FALSE;

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) return JNI_FALSE;

    const char* pathStr = env->GetStringUTFChars(outputPath, nullptr);
    std::ofstream out(pathStr, std::ios::binary);
    env->ReleaseStringUTFChars(outputPath, pathStr);

    if (!out.is_open()) {
        AndroidBitmap_unlockPixels(env, bitmap);
        return JNI_FALSE;
    }

    uint32_t w = info.width;
    uint32_t h = info.height;
    uint32_t stripByteCount = w * h * 3 * 2; // 3 channels (RGB) * 2 bytes (16-bit)
    uint32_t ifdOffset = 8 + stripByteCount;

    auto writeU16 = [&](uint16_t v) { out.write(reinterpret_cast<const char*>(&v), 2); };
    auto writeU32 = [&](uint32_t v) { out.write(reinterpret_cast<const char*>(&v), 4); };
    auto writeTag = [&](uint16_t tag, uint16_t type, uint32_t count, uint32_t val) {
        writeU16(tag); writeU16(type); writeU32(count); writeU32(val);
    };

    // TIFF Header (Little-Endian)
    out.write("II", 2);
    writeU16(42);
    writeU32(ifdOffset);

    // 16-bit RGB Pixel Payload (Gamma-expanded to 16-bit precision)
    std::vector<uint16_t> rowBuf(w * 3);
    for (uint32_t y = 0; y < h; ++y) {
        const uint8_t* row = reinterpret_cast<const uint8_t*>(pixels) + y * info.stride;
        for (uint32_t x = 0; x < w; ++x) {
            rowBuf[x * 3 + 0] = static_cast<uint16_t>(row[x * 4 + 0]) * 257;
            rowBuf[x * 3 + 1] = static_cast<uint16_t>(row[x * 4 + 1]) * 257;
            rowBuf[x * 3 + 2] = static_cast<uint16_t>(row[x * 4 + 2]) * 257;
        }
        out.write(reinterpret_cast<const char*>(rowBuf.data()), rowBuf.size() * 2);
    }

    uint32_t bpsOffset = ifdOffset + 2 + (10 * 12) + 4;
    writeU16(10); // 10 IFD entries
    writeTag(256, 4, 1, w);                  // ImageWidth
    writeTag(257, 4, 1, h);                  // ImageLength
    writeTag(258, 3, 3, bpsOffset);          // BitsPerSample (16, 16, 16)
    writeTag(259, 3, 1, 1);                  // Compression = None
    writeTag(262, 3, 1, 2);                  // Photometric = RGB
    writeTag(273, 4, 1, 8);                  // StripOffsets
    writeTag(277, 3, 1, 3);                  // SamplesPerPixel = 3
    writeTag(278, 4, 1, h);                  // RowsPerStrip
    writeTag(279, 4, 1, stripByteCount);     // StripByteCounts
    writeTag(284, 3, 1, 1);                  // PlanarConfiguration = Chunky
    writeU32(0);                             // Next IFD = 0

    writeU16(16); writeU16(16); writeU16(16); // BitsPerSample array
    out.close();

    AndroidBitmap_unlockPixels(env, bitmap);
    return JNI_TRUE;
}

// 2. FAST C++ HEAL BRUSH (In-place Native Diffusion Inpainting)
extern "C" JNIEXPORT void JNICALL
Java_com_example_nativebridge_NativeBridge_healSpotNative(
        JNIEnv* env,
        jobject /* this */,
        jobject bitmap,
        jint centerX,
        jint centerY,
        jint radius) {
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) return;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || radius <= 0) return;

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) return;

    int w = static_cast<int>(info.width);
    int h = static_cast<int>(info.height);
    int x0 = std::max(1, centerX - radius);
    int y0 = std::max(1, centerY - radius);
    int x1 = std::min(w - 2, centerX + radius);
    int y1 = std::min(h - 2, centerY + radius);
    int r2 = radius * radius;

    uint8_t* base = reinterpret_cast<uint8_t*>(pixels);
    int iterations = std::min(60, radius * 2);

    for (int iter = 0; iter < iterations; ++iter) {
        for (int y = y0; y <= y1; ++y) {
            int dy = y - centerY;
            uint8_t* row = base + y * info.stride;
            uint8_t* rowUp = base + (y - 1) * info.stride;
            uint8_t* rowDn = base + (y + 1) * info.stride;

            for (int x = x0; x <= x1; ++x) {
                int dx = x - centerX;
                if (dx * dx + dy * dy <= r2) {
                    int idx = x * 4;
                    int idxL = (x - 1) * 4;
                    int idxR = (x + 1) * 4;
                    for (int c = 0; c < 3; ++c) {
                        int avg = (rowUp[idx + c] + rowDn[idx + c] + row[idxL + c] + row[idxR + c]) >> 2;
                        row[idx + c] = static_cast<uint8_t>(avg);
                    }
                }
            }
        }
    }

    AndroidBitmap_unlockPixels(env, bitmap);
}

// 3. ULTRA-FAST NATIVE RGB + LUMA HISTOGRAM (Returns 256 * 4 Ints)
extern "C" JNIEXPORT jintArray JNICALL
Java_com_example_nativebridge_NativeBridge_computeHistogramNative(
        JNIEnv* env,
        jobject /* this */,
        jobject bitmap) {
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) return nullptr;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return nullptr;

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) return nullptr;

    int hist[1024] = {0}; // [0..255]=R, [256..511]=G, [512..767]=B, [768..1023]=Luma
    for (uint32_t y = 0; y < info.height; ++y) {
        const uint8_t* row = reinterpret_cast<const uint8_t*>(pixels) + y * info.stride;
        for (uint32_t x = 0; x < info.width; ++x) {
            uint8_t r = row[x * 4 + 0];
            uint8_t g = row[x * 4 + 1];
            uint8_t b = row[x * 4 + 2];
            uint8_t luma = static_cast<uint8_t>((77 * r + 150 * g + 29 * b) >> 8);
            hist[r]++;
            hist[256 + g]++;
            hist[512 + b]++;
            hist[768 + luma]++;
        }
    }

    AndroidBitmap_unlockPixels(env, bitmap);

    jintArray result = env->NewIntArray(1024);
    if (result != nullptr) {
        env->SetIntArrayRegion(result, 0, 1024, hist);
    }
    return result;
}

// 4. ULTRA-FAST NATIVE 3D .CUBE LUT (Trilinear Interpolation with Precomputed Channel Coords)
extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_nativebridge_NativeBridge_applyLut3dNative(
        JNIEnv* env,
        jobject /* this */,
        jobject bitmap,
        jfloatArray lutTable,
        jint lutSize,
        jfloatArray domainMin,
        jfloatArray domainMax,
        jfloat intensity) {
    if (bitmap == nullptr || lutTable == nullptr || lutSize < 2 || lutSize > 65) {
        return JNI_FALSE;
    }

    const float mix = std::clamp(std::isfinite(intensity) ? intensity : 0.0f, 0.0f, 1.0f);
    if (mix <= 0.0005f) {
        return JNI_TRUE;
    }

    const jsize tableLen = env->GetArrayLength(lutTable);
    const int64_t s = static_cast<int64_t>(lutSize);
    const int64_t requiredFloats = s * s * s * 3;
    if (static_cast<int64_t>(tableLen) < requiredFloats) {
        return JNI_FALSE;
    }

    float dMin[3] = {0.0f, 0.0f, 0.0f};
    float dMax[3] = {1.0f, 1.0f, 1.0f};
    if (domainMin != nullptr && env->GetArrayLength(domainMin) >= 3) {
        jfloat tmpMin[3] = {0.0f, 0.0f, 0.0f};
        env->GetFloatArrayRegion(domainMin, 0, 3, tmpMin);
        for (int c = 0; c < 3; ++c) {
            if (std::isfinite(tmpMin[c])) dMin[c] = tmpMin[c];
        }
    }
    if (domainMax != nullptr && env->GetArrayLength(domainMax) >= 3) {
        jfloat tmpMax[3] = {1.0f, 1.0f, 1.0f};
        env->GetFloatArrayRegion(domainMax, 0, 3, tmpMax);
        for (int c = 0; c < 3; ++c) {
            if (std::isfinite(tmpMax[c])) dMax[c] = tmpMax[c];
        }
    }

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) return JNI_FALSE;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return JNI_FALSE;
    if (info.width == 0 || info.height == 0) return JNI_TRUE;

    jfloat* t = env->GetFloatArrayElements(lutTable, nullptr);
    if (t == nullptr) return JNI_FALSE;

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) {
        env->ReleaseFloatArrayElements(lutTable, t, JNI_ABORT);
        return JNI_FALSE;
    }

    // Precompute 256-entry lattice indices & fractional weights per RGB channel
    const int maxGrid = lutSize - 1;
    const float fMaxGrid = static_cast<float>(maxGrid);
    const float inv255 = 1.0f / 255.0f;

    int r0Lut[256], r1Lut[256];
    int g0Lut[256], g1Lut[256];
    int b0Lut[256], b1Lut[256];
    float drLut[256], dgLut[256], dbLut[256];

    const float invRange0 = 1.0f / std::max(dMax[0] - dMin[0], 1e-5f);
    const float invRange1 = 1.0f / std::max(dMax[1] - dMin[1], 1e-5f);
    const float invRange2 = 1.0f / std::max(dMax[2] - dMin[2], 1e-5f);

    for (int v = 0; v < 256; ++v) {
        const float normV = static_cast<float>(v) * inv255;

        const float nr = std::clamp((normV - dMin[0]) * invRange0, 0.0f, 1.0f);
        const float fr = nr * fMaxGrid;
        const int r0 = std::clamp(static_cast<int>(std::floor(fr)), 0, maxGrid);
        r0Lut[v] = r0;
        r1Lut[v] = std::min(r0 + 1, maxGrid);
        drLut[v] = fr - static_cast<float>(r0);

        const float ng = std::clamp((normV - dMin[1]) * invRange1, 0.0f, 1.0f);
        const float fg = ng * fMaxGrid;
        const int g0 = std::clamp(static_cast<int>(std::floor(fg)), 0, maxGrid);
        g0Lut[v] = g0;
        g1Lut[v] = std::min(g0 + 1, maxGrid);
        dgLut[v] = fg - static_cast<float>(g0);

        const float nb = std::clamp((normV - dMin[2]) * invRange2, 0.0f, 1.0f);
        const float fb = nb * fMaxGrid;
        const int b0 = std::clamp(static_cast<int>(std::floor(fb)), 0, maxGrid);
        b0Lut[v] = b0;
        b1Lut[v] = std::min(b0 + 1, maxGrid);
        dbLut[v] = fb - static_cast<float>(b0);
    }

    const int size = lutSize;
    const int size2 = size * size;
    uint8_t* base = reinterpret_cast<uint8_t*>(pixels);

    for (uint32_t y = 0; y < info.height; ++y) {
        uint8_t* row = base + y * info.stride;
        for (uint32_t x = 0; x < info.width; ++x) {
            uint8_t* px = row + x * 4;
            const uint8_t rByte = px[0];
            const uint8_t gByte = px[1];
            const uint8_t bByte = px[2];

            const int r0 = r0Lut[rByte];
            const int r1 = r1Lut[rByte];
            const float dr = drLut[rByte];

            const int g0 = g0Lut[gByte] * size;
            const int g1 = g1Lut[gByte] * size;
            const float dg = dgLut[gByte];

            const int b0 = b0Lut[bByte] * size2;
            const int b1 = b1Lut[bByte] * size2;
            const float db = dbLut[bByte];

            const int i000 = (r0 + g0 + b0) * 3;
            const int i100 = (r1 + g0 + b0) * 3;
            const int i010 = (r0 + g1 + b0) * 3;
            const int i110 = (r1 + g1 + b0) * 3;
            const int i001 = (r0 + g0 + b1) * 3;
            const int i101 = (r1 + g0 + b1) * 3;
            const int i011 = (r0 + g1 + b1) * 3;
            const int i111 = (r1 + g1 + b1) * 3;

            const float rIn = static_cast<float>(rByte) * inv255;
            const float gIn = static_cast<float>(gByte) * inv255;
            const float bIn = static_cast<float>(bByte) * inv255;

            const float inputs[3] = {rIn, gIn, bIn};
            for (int c = 0; c < 3; ++c) {
                const float c00 = t[i000 + c] + (t[i100 + c] - t[i000 + c]) * dr;
                const float c10 = t[i010 + c] + (t[i110 + c] - t[i010 + c]) * dr;
                const float c01 = t[i001 + c] + (t[i101 + c] - t[i001 + c]) * dr;
                const float c11 = t[i011 + c] + (t[i111 + c] - t[i011 + c]) * dr;

                const float c0 = c00 + (c10 - c00) * dg;
                const float c1 = c01 + (c11 - c01) * dg;

                const float lutVal = std::clamp(c0 + (c1 - c0) * db, 0.0f, 1.0f);
                const float finalVal = inputs[c] + (lutVal - inputs[c]) * mix;
                const int outByte = static_cast<int>(finalVal * 255.0f + 0.5f);
                px[c] = static_cast<uint8_t>(std::clamp(outByte, 0, 255));
            }
        }
    }

    AndroidBitmap_unlockPixels(env, bitmap);
    env->ReleaseFloatArrayElements(lutTable, t, JNI_ABORT);
    return JNI_TRUE;
}

// 5. FILM HALATION & BLOOM (Highlight Threshold + Downsampled Separable Gaussian Blur + Screen Composite)
extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_nativebridge_NativeBridge_applyHalationBloomNative(
        JNIEnv* env,
        jobject /* this */,
        jobject bitmap,
        jfloat halation,
        jfloat bloom) {
    if (bitmap == nullptr) return JNI_FALSE;

    const float hal = std::clamp(std::isfinite(halation) ? halation : 0.0f, 0.0f, 1.0f);
    const float blm = std::clamp(std::isfinite(bloom) ? bloom : 0.0f, 0.0f, 1.0f);
    if (hal <= 0.0005f && blm <= 0.0005f) {
        return JNI_TRUE;
    }

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) return JNI_FALSE;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return JNI_FALSE;
    const int w = static_cast<int>(info.width);
    const int h = static_cast<int>(info.height);
    if (w <= 0 || h <= 0) return JNI_TRUE;

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) return JNI_FALSE;

    const int ds = std::clamp(std::max(w, h) / 480, 1, 4);
    const int gw = std::max(1, (w + ds - 1) / ds);
    const int gh = std::max(1, (h + ds - 1) / ds);
    const bool useHal = hal > 0.0005f;
    const bool useBloom = blm > 0.0005f;

    std::vector<float> halBuf(useHal ? static_cast<size_t>(gw) * gh * 3 : 0, 0.0f);
    std::vector<float> bloomBuf(useBloom ? static_cast<size_t>(gw) * gh * 3 : 0, 0.0f);

    auto smoothRamp = [](float edge0, float edge1, float x) -> float {
        const float denom = std::max(edge1 - edge0, 1e-4f);
        const float t = std::clamp((x - edge0) / denom, 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    };

    const float inv255 = 1.0f / 255.0f;
    const uint8_t* base = reinterpret_cast<const uint8_t*>(pixels);

    // Pass 1: Box-downsample + Highlight Threshold extraction
    for (int gy = 0; gy < gh; ++gy) {
        const int yStart = gy * ds;
        const int yEnd = std::min(h, yStart + ds);
        for (int gx = 0; gx < gw; ++gx) {
            const int xStart = gx * ds;
            const int xEnd = std::min(w, xStart + ds);
            float sumR = 0.0f, sumG = 0.0f, sumB = 0.0f;
            int cnt = 0;
            for (int y = yStart; y < yEnd; ++y) {
                const uint8_t* row = base + static_cast<size_t>(y) * info.stride;
                for (int x = xStart; x < xEnd; ++x) {
                    const uint8_t* px = row + x * 4;
                    sumR += static_cast<float>(px[0]);
                    sumG += static_cast<float>(px[1]);
                    sumB += static_cast<float>(px[2]);
                    cnt++;
                }
            }
            const float invCnt = inv255 / static_cast<float>(std::max(1, cnt));
            const float r = sumR * invCnt;
            const float g = sumG * invCnt;
            const float b = sumB * invCnt;
            const float lum = 0.2126f * r + 0.7152f * g + 0.0722f * b;
            const size_t dstIdx = (static_cast<size_t>(gy) * gw + gx) * 3;

            if (useHal) {
                const float rawT = smoothRamp(0.65f, 0.96f, lum);
                const float tH = rawT * (0.45f + 0.55f * rawT);
                halBuf[dstIdx + 0] = std::min(1.0f, (r * 1.12f + 0.14f) * tH);
                halBuf[dstIdx + 1] = g * 0.30f * tH;
                halBuf[dstIdx + 2] = b * 0.07f * tH;
            }
            if (useBloom) {
                const float rawT = smoothRamp(0.54f, 0.94f, lum);
                const float tB = rawT * (0.55f + 0.45f * rawT);
                bloomBuf[dstIdx + 0] = r * tB;
                bloomBuf[dstIdx + 1] = g * tB;
                bloomBuf[dstIdx + 2] = b * tB;
            }
        }
    }

    // Pass 2: Separable 1D Gaussian Blur (Horizontal + Vertical)
    auto separableGaussian = [&](std::vector<float>& buf, int radius) {
        if (radius <= 0 || gw <= 1 || gh <= 1 || buf.empty()) return;
        const float sigma = std::max(0.8f, static_cast<float>(radius) * 0.45f);
        const float invTwoSigma2 = 1.0f / (2.0f * sigma * sigma);
        std::vector<float> kernel(radius * 2 + 1);
        float kSum = 0.0f;
        for (int i = -radius; i <= radius; ++i) {
            const float wt = std::exp(-static_cast<float>(i * i) * invTwoSigma2);
            kernel[i + radius] = wt;
            kSum += wt;
        }
        const float invKSum = 1.0f / std::max(kSum, 1e-6f);
        for (float& wt : kernel) wt *= invKSum;

        std::vector<float> tmp(buf.size(), 0.0f);
        for (int y = 0; y < gh; ++y) {
            const float* srcRow = buf.data() + static_cast<size_t>(y) * gw * 3;
            float* dstRow = tmp.data() + static_cast<size_t>(y) * gw * 3;
            for (int x = 0; x < gw; ++x) {
                float accR = 0.0f, accG = 0.0f, accB = 0.0f;
                for (int k = -radius; k <= radius; ++k) {
                    const int sx = std::clamp(x + k, 0, gw - 1);
                    const float wt = kernel[k + radius];
                    const float* sp = srcRow + sx * 3;
                    accR += sp[0] * wt;
                    accG += sp[1] * wt;
                    accB += sp[2] * wt;
                }
                dstRow[x * 3 + 0] = accR;
                dstRow[x * 3 + 1] = accG;
                dstRow[x * 3 + 2] = accB;
            }
        }
        for (int y = 0; y < gh; ++y) {
            float* dstRow = buf.data() + static_cast<size_t>(y) * gw * 3;
            for (int x = 0; x < gw; ++x) {
                float accR = 0.0f, accG = 0.0f, accB = 0.0f;
                for (int k = -radius; k <= radius; ++k) {
                    const int sy = std::clamp(y + k, 0, gh - 1);
                    const float wt = kernel[k + radius];
                    const float* sp = tmp.data() + (static_cast<size_t>(sy) * gw + x) * 3;
                    accR += sp[0] * wt;
                    accG += sp[1] * wt;
                    accB += sp[2] * wt;
                }
                dstRow[x * 3 + 0] = accR;
                dstRow[x * 3 + 1] = accG;
                dstRow[x * 3 + 2] = accB;
            }
        }
    };

    const int maxGDim = std::max(gw, gh);
    if (useHal) {
        const int radH = std::clamp(static_cast<int>(maxGDim * 0.022f), 3, 14);
        separableGaussian(halBuf, radH);
    }
    if (useBloom) {
        const int radB = std::clamp(static_cast<int>(maxGDim * 0.015f), 2, 10);
        separableGaussian(bloomBuf, radB);
    }

    // Pass 3: Bilinear upsample + Screen composite back onto RGBA_8888 bitmap
    std::vector<int> gx0(w), gx1(w);
    std::vector<float> tx(w);
    const float invDs = 1.0f / static_cast<float>(ds);
    const float maxGx = static_cast<float>(gw - 1);
    const float maxGy = static_cast<float>(gh - 1);
    for (int x = 0; x < w; ++x) {
        const float fx = std::clamp((static_cast<float>(x) + 0.5f) * invDs - 0.5f, 0.0f, maxGx);
        const int ix = std::clamp(static_cast<int>(std::floor(fx)), 0, gw - 1);
        gx0[x] = ix;
        gx1[x] = std::min(ix + 1, gw - 1);
        tx[x] = fx - static_cast<float>(ix);
    }

    const float halStrength = hal * 0.90f;
    const float bloomStrength = blm * 0.80f;
    uint8_t* mutBase = reinterpret_cast<uint8_t*>(pixels);

    for (int y = 0; y < h; ++y) {
        const float fy = std::clamp((static_cast<float>(y) + 0.5f) * invDs - 0.5f, 0.0f, maxGy);
        const int gy0 = std::clamp(static_cast<int>(std::floor(fy)), 0, gh - 1);
        const int gy1 = std::min(gy0 + 1, gh - 1);
        const float ty = fy - static_cast<float>(gy0);
        const size_t rowG0 = static_cast<size_t>(gy0) * gw;
        const size_t rowG1 = static_cast<size_t>(gy1) * gw;
        uint8_t* row = mutBase + static_cast<size_t>(y) * info.stride;

        for (int x = 0; x < w; ++x) {
            const int x0 = gx0[x];
            const int x1 = gx1[x];
            const float ftx = tx[x];

            const size_t i00 = (rowG0 + x0) * 3;
            const size_t i10 = (rowG0 + x1) * 3;
            const size_t i01 = (rowG1 + x0) * 3;
            const size_t i11 = (rowG1 + x1) * 3;

            uint8_t* px = row + x * 4;
            for (int c = 0; c < 3; ++c) {
                float glow = 0.0f;
                if (useHal) {
                    const float top = halBuf[i00 + c] + (halBuf[i10 + c] - halBuf[i00 + c]) * ftx;
                    const float bot = halBuf[i01 + c] + (halBuf[i11 + c] - halBuf[i01 + c]) * ftx;
                    glow += (top + (bot - top) * ty) * halStrength;
                }
                if (useBloom) {
                    const float top = bloomBuf[i00 + c] + (bloomBuf[i10 + c] - bloomBuf[i00 + c]) * ftx;
                    const float bot = bloomBuf[i01 + c] + (bloomBuf[i11 + c] - bloomBuf[i01 + c]) * ftx;
                    glow += (top + (bot - top) * ty) * bloomStrength;
                }
                glow = std::clamp(glow, 0.0f, 1.0f);
                const float baseC = static_cast<float>(px[c]) * inv255;
                const float screenC = 1.0f - (1.0f - baseC) * (1.0f - glow);
                const int outByte = static_cast<int>(std::clamp(screenC, 0.0f, 1.0f) * 255.0f + 0.5f);
                px[c] = static_cast<uint8_t>(std::clamp(outByte, 0, 255));
            }
        }
    }

    AndroidBitmap_unlockPixels(env, bitmap);
    return JNI_TRUE;
}

// 6. CHROMATIC ABERRATION (Radial R/B Channel UV Shift with Bilinear Interpolation)
extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_nativebridge_NativeBridge_applyChromaticAberrationNative(
        JNIEnv* env,
        jobject /* this */,
        jobject bitmap,
        jfloat amount) {
    if (bitmap == nullptr) return JNI_FALSE;

    const float norm = std::clamp(std::isfinite(amount) ? amount : 0.0f, -1.0f, 1.0f);
    if (std::abs(norm) <= 0.0005f) {
        return JNI_TRUE;
    }

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) return JNI_FALSE;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return JNI_FALSE;
    const int w = static_cast<int>(info.width);
    const int h = static_cast<int>(info.height);
    if (w <= 1 || h <= 1) return JNI_TRUE;

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) return JNI_FALSE;

    const size_t tightRowBytes = static_cast<size_t>(w) * 4;
    std::vector<uint8_t> srcCopy(static_cast<size_t>(h) * tightRowBytes);
    const uint8_t* srcBase = reinterpret_cast<const uint8_t*>(pixels);
    for (int y = 0; y < h; ++y) {
        const uint8_t* row = srcBase + static_cast<size_t>(y) * info.stride;
        std::copy(row, row + tightRowBytes, srcCopy.data() + static_cast<size_t>(y) * tightRowBytes);
    }

    auto sampleChannelBilinear = [&](float u, float v, int channel) -> uint8_t {
        const float fx = std::clamp(u * static_cast<float>(w) - 0.5f, 0.0f, static_cast<float>(w - 1));
        const float fy = std::clamp(v * static_cast<float>(h) - 0.5f, 0.0f, static_cast<float>(h - 1));
        const int x0 = std::clamp(static_cast<int>(std::floor(fx)), 0, w - 1);
        const int y0 = std::clamp(static_cast<int>(std::floor(fy)), 0, h - 1);
        const int x1 = std::min(x0 + 1, w - 1);
        const int y1 = std::min(y0 + 1, h - 1);
        const float tx = fx - static_cast<float>(x0);
        const float ty = fy - static_cast<float>(y0);

        const uint8_t* r0 = srcCopy.data() + static_cast<size_t>(y0) * tightRowBytes;
        const uint8_t* r1 = srcCopy.data() + static_cast<size_t>(y1) * tightRowBytes;
        const float c00 = static_cast<float>(r0[x0 * 4 + channel]);
        const float c10 = static_cast<float>(r0[x1 * 4 + channel]);
        const float c01 = static_cast<float>(r1[x0 * 4 + channel]);
        const float c11 = static_cast<float>(r1[x1 * 4 + channel]);
        const float top = c00 + (c10 - c00) * tx;
        const float bot = c01 + (c11 - c01) * tx;
        const int outVal = static_cast<int>(top + (bot - top) * ty + 0.5f);
        return static_cast<uint8_t>(std::clamp(outVal, 0, 255));
    };

    const float shiftScale = norm * 0.012f;
    const float invW = 1.0f / static_cast<float>(w);
    const float invH = 1.0f / static_cast<float>(h);
    uint8_t* dstBase = reinterpret_cast<uint8_t*>(pixels);

    for (int y = 0; y < h; ++y) {
        const float v = (static_cast<float>(y) + 0.5f) * invH;
        const float dy = v - 0.5f;
        uint8_t* dstRow = dstBase + static_cast<size_t>(y) * info.stride;
        for (int x = 0; x < w; ++x) {
            const float u = (static_cast<float>(x) + 0.5f) * invW;
            const float dx = u - 0.5f;

            const float ru = std::clamp(0.5f + dx * (1.0f + shiftScale), 0.0f, 1.0f);
            const float rv = std::clamp(0.5f + dy * (1.0f + shiftScale), 0.0f, 1.0f);
            const float bu = std::clamp(0.5f + dx * (1.0f - shiftScale), 0.0f, 1.0f);
            const float bv = std::clamp(0.5f + dy * (1.0f - shiftScale), 0.0f, 1.0f);

            uint8_t* px = dstRow + x * 4;
            px[0] = sampleChannelBilinear(ru, rv, 0);
            px[2] = sampleChannelBilinear(bu, bv, 2);
        }
    }

    AndroidBitmap_unlockPixels(env, bitmap);
    return JNI_TRUE;
}

// 7. ANAMORPHIC STREAK (Highlight Threshold + Wide 1D Horizontal Gaussian Streak Tinted Cyan/Blue)
extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_nativebridge_NativeBridge_applyAnamorphicStreakNative(
        JNIEnv* env,
        jobject /* this */,
        jobject bitmap,
        jfloat amount) {
    if (bitmap == nullptr) return JNI_FALSE;

    const float amt = std::clamp(std::isfinite(amount) ? amount : 0.0f, 0.0f, 1.0f);
    if (amt <= 0.0005f) {
        return JNI_TRUE;
    }

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) return JNI_FALSE;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return JNI_FALSE;
    const int w = static_cast<int>(info.width);
    const int h = static_cast<int>(info.height);
    if (w <= 0 || h <= 0) return JNI_TRUE;

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) return JNI_FALSE;

    const int ds = std::clamp(std::max(w, h) / 480, 1, 4);
    const int gw = std::max(1, (w + ds - 1) / ds);
    const int gh = std::max(1, (h + ds - 1) / ds);

    std::vector<float> hiMask(static_cast<size_t>(gw) * gh, 0.0f);
    std::vector<float> streakBuf(static_cast<size_t>(gw) * gh, 0.0f);

    auto smoothRamp = [](float edge0, float edge1, float x) -> float {
        const float denom = std::max(edge1 - edge0, 1e-4f);
        const float t = std::clamp((x - edge0) / denom, 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    };

    const float inv255 = 1.0f / 255.0f;
    const uint8_t* base = reinterpret_cast<const uint8_t*>(pixels);

    // Pass 1: Box-downsample + Highlight Threshold extraction
    for (int gy = 0; gy < gh; ++gy) {
        const int yStart = gy * ds;
        const int yEnd = std::min(h, yStart + ds);
        for (int gx = 0; gx < gw; ++gx) {
            const int xStart = gx * ds;
            const int xEnd = std::min(w, xStart + ds);
            float sumR = 0.0f, sumG = 0.0f, sumB = 0.0f;
            int cnt = 0;
            for (int y = yStart; y < yEnd; ++y) {
                const uint8_t* row = base + static_cast<size_t>(y) * info.stride;
                for (int x = xStart; x < xEnd; ++x) {
                    const uint8_t* px = row + x * 4;
                    sumR += static_cast<float>(px[0]);
                    sumG += static_cast<float>(px[1]);
                    sumB += static_cast<float>(px[2]);
                    cnt++;
                }
            }
            const float invCnt = inv255 / static_cast<float>(std::max(1, cnt));
            const float r = sumR * invCnt;
            const float g = sumG * invCnt;
            const float b = sumB * invCnt;
            const float lum = 0.2126f * r + 0.7152f * g + 0.0722f * b;
            const float maxChan = std::max(r, std::max(g, b));
            const float hiSignal = std::max(lum, maxChan * 0.92f);
            const float rawT = smoothRamp(0.68f, 0.96f, hiSignal);
            hiMask[static_cast<size_t>(gy) * gw + gx] = rawT * rawT;
        }
    }

    // Pass 2: Wide 1D Horizontal Gaussian Streak along each scanline
    const int radius = std::max(3, std::min(72, static_cast<int>(gw * (0.05f + 0.14f * amt))));
    if (gw > 1) {
        const float sigma = std::max(1.2f, static_cast<float>(radius) * 0.42f);
        const float invTwoSigma2 = 1.0f / (2.0f * sigma * sigma);
        std::vector<float> kernel(radius * 2 + 1);
        float kSum = 0.0f;
        for (int i = -radius; i <= radius; ++i) {
            const float wt = std::exp(-static_cast<float>(i * i) * invTwoSigma2);
            kernel[i + radius] = wt;
            kSum += wt;
        }
        const float invKSum = 1.0f / std::max(kSum, 1e-6f);
        for (float& wt : kernel) wt *= invKSum;

        for (int gy = 0; gy < gh; ++gy) {
            const float* srcRow = hiMask.data() + static_cast<size_t>(gy) * gw;
            float* dstRow = streakBuf.data() + static_cast<size_t>(gy) * gw;
            for (int gx = 0; gx < gw; ++gx) {
                float acc = 0.0f;
                for (int k = -radius; k <= radius; ++k) {
                    const int sx = gx + k;
                    if (sx >= 0 && sx < gw) {
                        acc += srcRow[sx] * kernel[k + radius];
                    }
                }
                dstRow[gx] = acc;
            }
        }
    } else {
        streakBuf = hiMask;
    }

    // Pass 3: Bilinear upsample + Additive Cool Cyan/Blue Streak Composite
    std::vector<int> gx0(w), gx1(w);
    std::vector<float> tx(w);
    const float invDs = 1.0f / static_cast<float>(ds);
    const float maxGx = static_cast<float>(gw - 1);
    const float maxGy = static_cast<float>(gh - 1);
    for (int x = 0; x < w; ++x) {
        const float fx = std::clamp((static_cast<float>(x) + 0.5f) * invDs - 0.5f, 0.0f, maxGx);
        const int ix = std::clamp(static_cast<int>(std::floor(fx)), 0, gw - 1);
        gx0[x] = ix;
        gx1[x] = std::min(ix + 1, gw - 1);
        tx[x] = fx - static_cast<float>(ix);
    }

    const float streakStrength = amt * 1.65f;
    uint8_t* mutBase = reinterpret_cast<uint8_t*>(pixels);

    for (int y = 0; y < h; ++y) {
        const float fy = std::clamp((static_cast<float>(y) + 0.5f) * invDs - 0.5f, 0.0f, maxGy);
        const int gy0 = std::clamp(static_cast<int>(std::floor(fy)), 0, gh - 1);
        const int gy1 = std::min(gy0 + 1, gh - 1);
        const float ty = fy - static_cast<float>(gy0);
        const size_t rowG0 = static_cast<size_t>(gy0) * gw;
        const size_t rowG1 = static_cast<size_t>(gy1) * gw;
        uint8_t* row = mutBase + static_cast<size_t>(y) * info.stride;

        for (int x = 0; x < w; ++x) {
            const int x0 = gx0[x];
            const int x1 = gx1[x];
            const float ftx = tx[x];

            const float s00 = streakBuf[rowG0 + x0];
            const float s10 = streakBuf[rowG0 + x1];
            const float s01 = streakBuf[rowG1 + x0];
            const float s11 = streakBuf[rowG1 + x1];
            const float top = s00 + (s10 - s00) * ftx;
            const float bot = s01 + (s11 - s01) * ftx;
            const float s = (top + (bot - top) * ty) * streakStrength;
            if (s <= 0.0005f) continue;

            uint8_t* px = row + x * 4;
            const float baseR = static_cast<float>(px[0]) * inv255;
            const float baseG = static_cast<float>(px[1]) * inv255;
            const float baseB = static_cast<float>(px[2]) * inv255;

            const float outR = std::clamp(baseR + s * 0.24f, 0.0f, 1.0f);
            const float outG = std::clamp(baseG + s * 0.74f, 0.0f, 1.0f);
            const float outB = std::clamp(baseB + s * 1.00f, 0.0f, 1.0f);

            px[0] = static_cast<uint8_t>(std::clamp(static_cast<int>(outR * 255.0f + 0.5f), 0, 255));
            px[1] = static_cast<uint8_t>(std::clamp(static_cast<int>(outG * 255.0f + 0.5f), 0, 255));
            px[2] = static_cast<uint8_t>(std::clamp(static_cast<int>(outB * 255.0f + 0.5f), 0, 255));
        }
    }

    AndroidBitmap_unlockPixels(env, bitmap);
    return JNI_TRUE;
}


