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

