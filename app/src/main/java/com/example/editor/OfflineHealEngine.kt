package com.example.editor

import android.graphics.Bitmap
import androidx.compose.runtime.Immutable
import androidx.core.graphics.createBitmap
import com.example.ai.LocalInpaintingEngine
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class HealMode(val label: String) {
    SPOT_HEAL("Spot Heal"),
    CLONE_STAMP("Clone Stamp"),
    PATCH_SMOOTH("Patch / Smooth")
}

@Immutable
data class HealStroke(
    val mode: HealMode,
    val points: List<Pair<Float, Float>>,
    val radiusNorm: Float,
    val feather: Float = 0.50f,
    val opacity: Float = 1.0f,
    val cloneSourceNorm: Pair<Float, Float>? = null
)

/**
 * 100% offline, non-destructive pixel retouching engine supporting three modes:
 * 1. [HealMode.SPOT_HEAL]: Content-aware PatchMatch + Poisson gradient-domain inpainting.
 * 2. [HealMode.CLONE_STAMP]: Offset-locked bilinear pixel sampling from user-selected source point
 *    with feathered softness and opacity blending.
 * 3. [HealMode.PATCH_SMOOTH]: Localized edge-preserving joint bilateral texture smoothing for skin
 *    blemishes and sensor noise without blurring crisp structural edges.
 *
 * All modes strictly preserve 100% of unmasked pixels (`alpha == 0`) bit-for-bit.
 */
object OfflineHealEngine {

    const val DEFAULT_BRUSH_SIZE_PX = 42f
    const val MIN_BRUSH_SIZE_PX = 5f
    const val MAX_BRUSH_SIZE_PX = 150f

    const val DEFAULT_FEATHER = 0.50f
    const val DEFAULT_OPACITY = 1.0f

    fun brushSizePxToRadiusNorm(sizePx: Float): Float {
        return (sizePx.coerceIn(MIN_BRUSH_SIZE_PX, MAX_BRUSH_SIZE_PX) / 1000f)
    }

    fun applyStroke(
        sourceBitmap: Bitmap,
        stroke: HealStroke
    ): Bitmap {
        if (stroke.points.isEmpty()) {
            return sourceBitmap.copy(Bitmap.Config.ARGB_8888, false)
        }
        return when (stroke.mode) {
            HealMode.SPOT_HEAL -> LocalInpaintingEngine.inpaint(sourceBitmap, listOf(stroke))
            HealMode.CLONE_STAMP -> applyCloneStamp(sourceBitmap, stroke)
            HealMode.PATCH_SMOOTH -> applyPatchSmooth(sourceBitmap, stroke)
        }
    }

    fun applyAllStrokes(
        originalSourceBitmap: Bitmap,
        strokes: List<HealStroke>
    ): Bitmap {
        var current = originalSourceBitmap
        for (stroke in strokes) {
            val next = applyStroke(current, stroke)
            if (current !== originalSourceBitmap && current !== next && !current.isRecycled) {
                current.recycle()
            }
            current = next
        }
        return current
    }

    private fun applyCloneStamp(
        sourceBitmap: Bitmap,
        stroke: HealStroke
    ): Bitmap {
        val cloneAnchor = stroke.cloneSourceNorm ?: return sourceBitmap.copy(Bitmap.Config.ARGB_8888, false)
        val w = sourceBitmap.width
        val h = sourceBitmap.height
        if (w <= 0 || h <= 0) return sourceBitmap.copy(Bitmap.Config.ARGB_8888, false)

        val alphaMask = LocalInpaintingEngine.rasterizeAlphaMask(
            width = w,
            height = h,
            strokes = listOf(stroke),
            defaultFeatherPx = 3.0f
        )

        val firstPt = stroke.points.first()
        val dxPx = (cloneAnchor.first - firstPt.first) * w
        val dyPx = (cloneAnchor.second - firstPt.second) * h
        val opacity = stroke.opacity.coerceIn(0.10f, 1.0f)

        val pixels = IntArray(w * h)
        sourceBitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val outPixels = pixels.copyOf()

        for (y in 0 until h) {
            val rowOff = y * w
            for (x in 0 until w) {
                val idx = rowOff + x
                val maskVal = alphaMask[idx]
                if (maskVal <= 0) continue

                val sx = (x.toFloat() + dxPx).coerceIn(0f, (w - 1).toFloat())
                val sy = (y.toFloat() + dyPx).coerceIn(0f, (h - 1).toFloat())

                val x0 = floor(sx).toInt().coerceIn(0, w - 1)
                val y0 = floor(sy).toInt().coerceIn(0, h - 1)
                val x1 = min(w - 1, x0 + 1)
                val y1 = min(h - 1, y0 + 1)
                val fx = sx - x0
                val fy = sy - y0

                val p00 = pixels[y0 * w + x0]
                val p10 = pixels[y0 * w + x1]
                val p01 = pixels[y1 * w + x0]
                val p11 = pixels[y1 * w + x1]

                val w00 = (1f - fx) * (1f - fy)
                val w10 = fx * (1f - fy)
                val w01 = (1f - fx) * fy
                val w11 = fx * fy

                val sampleR = ((p00 ushr 16) and 0xFF) * w00 +
                    ((p10 ushr 16) and 0xFF) * w10 +
                    ((p01 ushr 16) and 0xFF) * w01 +
                    ((p11 ushr 16) and 0xFF) * w11
                val sampleG = ((p00 ushr 8) and 0xFF) * w00 +
                    ((p10 ushr 8) and 0xFF) * w10 +
                    ((p01 ushr 8) and 0xFF) * w01 +
                    ((p11 ushr 8) and 0xFF) * w11
                val sampleB = (p00 and 0xFF) * w00 +
                    (p10 and 0xFF) * w10 +
                    (p01 and 0xFF) * w01 +
                    (p11 and 0xFF) * w11

                val t = ((maskVal / 255f) * opacity).coerceIn(0f, 1f)
                val inv = 1f - t
                val orig = pixels[idx]
                val origA = (orig ushr 24) and 0xFF
                val origR = (orig ushr 16) and 0xFF
                val origG = (orig ushr 8) and 0xFF
                val origB = orig and 0xFF

                val outR = (origR * inv + sampleR * t).roundToInt().coerceIn(0, 255)
                val outG = (origG * inv + sampleG * t).roundToInt().coerceIn(0, 255)
                val outB = (origB * inv + sampleB * t).roundToInt().coerceIn(0, 255)
                outPixels[idx] = (origA shl 24) or (outR shl 16) or (outG shl 8) or outB
            }
        }

        return createBitmap(w, h).apply {
            setPixels(outPixels, 0, w, 0, 0, w, h)
        }
    }

    /**
     * Localized Edge-Preserving Bilateral Texture Smoothing inside masked pixels.
     */
    private fun applyPatchSmooth(
        sourceBitmap: Bitmap,
        stroke: HealStroke
    ): Bitmap {
        val w = sourceBitmap.width
        val h = sourceBitmap.height
        if (w <= 0 || h <= 0) return sourceBitmap.copy(Bitmap.Config.ARGB_8888, false)

        val alphaMask = LocalInpaintingEngine.rasterizeAlphaMask(
            width = w,
            height = h,
            strokes = listOf(stroke),
            defaultFeatherPx = 3.0f
        )

        var minX = w
        var maxX = -1
        var minY = h
        var maxY = -1
        for (y in 0 until h) {
            val rowOff = y * w
            for (x in 0 until w) {
                if (alphaMask[rowOff + x] > 0) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < minX || maxY < minY) {
            return sourceBitmap.copy(Bitmap.Config.ARGB_8888, false)
        }

        val pixels = IntArray(w * h)
        sourceBitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val outPixels = pixels.copyOf()

        val minSide = min(w, h).toFloat()
        val radiusPx = (stroke.radiusNorm * minSide).coerceAtLeast(3f)
        val kernelRadius = (radiusPx * 0.42f).roundToInt().coerceIn(3, 12)
        val step = if (kernelRadius >= 9) 2 else 1
        val sigmaSpace = max(2.2f, kernelRadius * 0.6f)
        val invTwoSigmaSpaceSq = 1f / (2f * sigmaSpace * sigmaSpace)
        // Photometric range threshold (~28 out of 255 per channel squared) preserves crisp edges
        val sigmaColor = 30f
        val invTwoSigmaColorSq = 1f / (2f * 3f * sigmaColor * sigmaColor)
        val opacity = stroke.opacity.coerceIn(0.10f, 1.0f)

        val kSize = kernelRadius * 2 + 1
        val spatialWeights = FloatArray(kSize * kSize) { idx ->
            val dx = (idx % kSize) - kernelRadius
            val dy = (idx / kSize) - kernelRadius
            exp(-(dx * dx + dy * dy).toFloat() * invTwoSigmaSpaceSq)
        }

        for (y in minY..maxY) {
            val rowOff = y * w
            for (x in minX..maxX) {
                val idx = rowOff + x
                val m = alphaMask[idx]
                if (m <= 0) continue

                val centerPx = pixels[idx]
                val cA = (centerPx ushr 24) and 0xFF
                val cR = (centerPx ushr 16) and 0xFF
                val cG = (centerPx ushr 8) and 0xFF
                val cB = centerPx and 0xFF

                var sumR = 0f
                var sumG = 0f
                var sumB = 0f
                var sumW = 0f

                var dy = -kernelRadius
                while (dy <= kernelRadius) {
                    val ny = (y + dy).coerceIn(0, h - 1)
                    val nRow = ny * w
                    val kRow = (dy + kernelRadius) * kSize
                    var dx = -kernelRadius
                    while (dx <= kernelRadius) {
                        val nx = (x + dx).coerceIn(0, w - 1)
                        val nPx = pixels[nRow + nx]
                        val nR = (nPx ushr 16) and 0xFF
                        val nG = (nPx ushr 8) and 0xFF
                        val nB = nPx and 0xFF

                        val dR = (nR - cR).toFloat()
                        val dG = (nG - cG).toFloat()
                        val dB = (nB - cB).toFloat()
                        val colorDistSq = dR * dR + dG * dG + dB * dB

                        val rangeW = exp(-colorDistSq * invTwoSigmaColorSq)
                        val wgt = spatialWeights[kRow + dx + kernelRadius] * rangeW
                        sumR += nR * wgt
                        sumG += nG * wgt
                        sumB += nB * wgt
                        sumW += wgt
                        dx += step
                    }
                    dy += step
                }

                if (sumW > 1e-5f) {
                    val smoothR = sumR / sumW
                    val smoothG = sumG / sumW
                    val smoothB = sumB / sumW
                    val t = ((m / 255f) * opacity).coerceIn(0f, 1f)
                    val inv = 1f - t
                    val outR = (cR * inv + smoothR * t).roundToInt().coerceIn(0, 255)
                    val outG = (cG * inv + smoothG * t).roundToInt().coerceIn(0, 255)
                    val outB = (cB * inv + smoothB * t).roundToInt().coerceIn(0, 255)
                    outPixels[idx] = (cA shl 24) or (outR shl 16) or (outG shl 8) or outB
                }
            }
        }

        return createBitmap(w, h).apply {
            setPixels(outPixels, 0, w, 0, 0, w, h)
        }
    }
}
