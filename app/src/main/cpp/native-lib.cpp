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
    env->SetIntArrayRegion(result, 0, 1024, hist);
    return result;
}
