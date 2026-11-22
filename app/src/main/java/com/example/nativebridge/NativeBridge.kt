
package com.example.nativebridge

import android.graphics.Bitmap

object NativeBridge {
    val isAvailable: Boolean = try {
        System.loadLibrary("zamlab_native")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    } catch (_: SecurityException) {
        false
    }

    external fun getEngineVersion(): String

    external fun export16BitTiffNative(bitmap: Bitmap, outputPath: String): Boolean

    external fun healSpotNative(bitmap: Bitmap, centerX: Int, centerY: Int, radius: Int)

    external fun computeHistogramNative(bitmap: Bitmap): IntArray?

    external fun applyLut3dNative(
        bitmap: Bitmap,
        lutTable: FloatArray,
        lutSize: Int,
        domainMin: FloatArray,
        domainMax: FloatArray,
        intensity: Float
    ): Boolean
}
