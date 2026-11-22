
package com.example.nativebridge

import android.graphics.Bitmap

object NativeBridge {
    init {
        System.loadLibrary("zamlab_native")
    }

    external fun getEngineVersion(): String

    external fun export16BitTiffNative(bitmap: Bitmap, outputPath: String): Boolean

    external fun healSpotNative(bitmap: Bitmap, centerX: Int, centerY: Int, radius: Int)

    external fun computeHistogramNative(bitmap: Bitmap): IntArray?
}
