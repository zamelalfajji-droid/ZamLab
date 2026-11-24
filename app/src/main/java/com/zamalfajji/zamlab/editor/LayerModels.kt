package com.zamalfajji.zamlab.editor

import android.graphics.Bitmap
import android.graphics.Matrix
import com.zamalfajji.zamlab.lut.CubeLut
import kotlin.math.min

object LayerIds {
    const val BACKGROUND: String = "layer_background"
}

/**
 * Supported multi-layer compositing blend modes rendered identically in real-time preview
 * and full-resolution export.
 */
enum class LayerBlendMode(val label: String) {
    NORMAL("Normal"),
    MULTIPLY("Multiply"),
    SCREEN("Screen"),
    OVERLAY("Overlay"),
    SOFT_LIGHT("Soft Light")
}

/**
 * Brush painting mode for manual alpha mask refinement on the active layer.
 */
enum class LayerMaskBrushMode(val label: String) {
    ERASE("Erase"),
    RESTORE("Restore")
}

/**
 * Per-layer non-destructive color grading, LUT, preset, and local mask selection state.
 * Every layer in the stack owns its own independent [ColorGradingState] so adjustments
 * affect only the currently selected active layer.
 */
data class ColorGradingState(
    val gradingParams: GradingParams = GradingParams(),
    val selectedLutId: String? = null,
    val activeLut: CubeLut? = null,
    val selectedUserPresetId: String? = null,
    val selectedMaskId: String? = null,
    val selectedSubMaskId: String? = null
) {
    val isDefault: Boolean
        get() = gradingParams.isDefault &&
            selectedLutId == null &&
            activeLut == null &&
            selectedUserPresetId == null
}

/**
 * Single non-destructive image layer in Z-Effect's multi-layer / collage stack.
 *
 * Transform coordinates:
 * - [offsetX], [offsetY]: normalized translation relative to canvas width/height (`0f, 0f` = centered).
 * - [scale]: uniform scale factor relative to aspect-fit size inside the canvas (`1f` fills fit bounds).
 * - [rotation]: clockwise rotation in degrees around the layer's center.
 * - [opacity]: alpha multiplier in `0f..1f`.
 * - [blendMode]: compositing blend mode (`NORMAL`, `MULTIPLY`, `SCREEN`, `OVERLAY`, `SOFT_LIGHT`).
 * - [edgeFeather]: alpha edge feather radius in `0f..20f` px.
 * - [bokehBlurRadius]: smooth bokeh / shallow-DOF blur radius in `0f..25f` px.
 */
data class ImageLayer(
    val id: String,
    val name: String,
    val bitmap: Bitmap,
    val maskBitmap: Bitmap? = null,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val scale: Float = 1f,
    val rotation: Float = 0f,
    val opacity: Float = 1f,
    val isVisible: Boolean = true,
    val isBackground: Boolean = false,
    val colorGradingState: ColorGradingState = ColorGradingState(),
    val renderedPreviewBitmap: Bitmap? = null,
    val thumbnailBitmap: Bitmap? = null,
    val nativeWidth: Int = bitmap.width,
    val nativeHeight: Int = bitmap.height,
    val sourceRef: PhotoSourceRef? = null,
    val defaultScale: Float = if (isBackground) 1f else LayerTransformMath.DEFAULT_OVERLAY_SCALE,
    val blendMode: LayerBlendMode = LayerBlendMode.NORMAL,
    val edgeFeather: Float = 0f,
    val bokehBlurRadius: Float = 0f,
    val maskVersion: Long = 0L
) {
    val displayBitmap: Bitmap
        get() = renderedPreviewBitmap?.takeIf { !it.isRecycled } ?: bitmap

    val hasNonDefaultTransform: Boolean
        get() = !isBackground && (
            kotlin.math.abs(offsetX) > 0.002f ||
                kotlin.math.abs(offsetY) > 0.002f ||
                kotlin.math.abs(scale - defaultScale) > 0.005f ||
                kotlin.math.abs(rotation) > 0.2f
            )

    val hasLayerFx: Boolean
        get() = blendMode != LayerBlendMode.NORMAL ||
            edgeFeather > 0.05f ||
            bokehBlurRadius > 0.05f
}

/**
 * Shared transform geometry math used by BOTH the live Compose canvas preview
 * and the full-resolution bitmap export compositor for 1:1 WYSIWYG parity.
 */
object LayerTransformMath {
    const val DEFAULT_OVERLAY_SCALE: Float = 0.56f
    const val MIN_SCALE: Float = 0.15f
    const val MAX_SCALE: Float = 5.0f
    const val MAX_OFFSET: Float = 1.5f
    const val MAX_EDGE_FEATHER: Float = 20f
    const val MAX_BOKEH_BLUR_RADIUS: Float = 25f
    const val MIN_MASK_BRUSH_SIZE_DP: Float = 10f
    const val MAX_MASK_BRUSH_SIZE_DP: Float = 120f

    /**
     * Computes the aspect-fit base dimensions `(baseWidth, baseHeight)` of a layer bitmap
     * when `scale == 1f` inside a canvas of `(canvasW, canvasH)`.
     */
    fun baseFitSize(
        canvasW: Float,
        canvasH: Float,
        layerW: Float,
        layerH: Float
    ): Pair<Float, Float> {
        val safeCanvasW = canvasW.coerceAtLeast(1f)
        val safeCanvasH = canvasH.coerceAtLeast(1f)
        val safeLayerW = layerW.coerceAtLeast(1f)
        val safeLayerH = layerH.coerceAtLeast(1f)
        val fit = min(safeCanvasW / safeLayerW, safeCanvasH / safeLayerH)
        return (safeLayerW * fit) to (safeLayerH * fit)
    }

    /**
     * Builds the Android [Matrix] mapping source pixels `(0..layerW, 0..layerH)` onto the
     * destination canvas `(0..canvasW, 0..canvasH)` using identical pivot, scale, rotation,
     * and normalized offset math as the Compose preview.
     */
    fun buildCanvasMatrix(
        canvasW: Float,
        canvasH: Float,
        layerW: Float,
        layerH: Float,
        offsetX: Float,
        offsetY: Float,
        scale: Float,
        rotationDegrees: Float
    ): Matrix {
        val safeLayerW = layerW.coerceAtLeast(1f)
        val safeLayerH = layerH.coerceAtLeast(1f)
        val (fitW, fitH) = baseFitSize(canvasW, canvasH, safeLayerW, safeLayerH)
        val effectiveScaleX = (fitW / safeLayerW) * scale
        val effectiveScaleY = (fitH / safeLayerH) * scale
        val centerX = canvasW * 0.5f + offsetX * canvasW
        val centerY = canvasH * 0.5f + offsetY * canvasH

        return Matrix().apply {
            postTranslate(-safeLayerW * 0.5f, -safeLayerH * 0.5f)
            postScale(effectiveScaleX, effectiveScaleY)
            if (rotationDegrees != 0f) {
                postRotate(rotationDegrees)
            }
            postTranslate(centerX, centerY)
        }
    }

    /**
     * Inverse-maps a normalized canvas touch coordinate `(normX, normY)` in `0..1` back into
     * the active layer's local bitmap/mask pixel coordinates `(px, py)` so manual mask brush
     * strokes track the finger 1:1 regardless of layer offset, scale, or rotation.
     */
    fun canvasNormalizedToLayerPixel(
        normX: Float,
        normY: Float,
        canvasW: Float,
        canvasH: Float,
        layerW: Float,
        layerH: Float,
        offsetX: Float,
        offsetY: Float,
        scale: Float,
        rotationDegrees: Float
    ): Pair<Float, Float>? {
        val forward = buildCanvasMatrix(
            canvasW = canvasW,
            canvasH = canvasH,
            layerW = layerW,
            layerH = layerH,
            offsetX = offsetX,
            offsetY = offsetY,
            scale = scale,
            rotationDegrees = rotationDegrees
        )
        val inverse = Matrix()
        if (!forward.invert(inverse)) return null
        val pt = floatArrayOf(normX * canvasW, normY * canvasH)
        inverse.mapPoints(pt)
        return pt[0] to pt[1]
    }

    /**
     * Converts a brush radius in canvas pixels into equivalent pixels in the layer's mask bitmap.
     */
    fun canvasRadiusToLayerMaskRadius(
        canvasRadiusPx: Float,
        canvasW: Float,
        canvasH: Float,
        layerW: Float,
        layerH: Float,
        scale: Float
    ): Float {
        val (fitW, _) = baseFitSize(canvasW, canvasH, layerW, layerH)
        val effectiveScale = ((fitW / layerW.coerceAtLeast(1f)) * scale).coerceAtLeast(0.05f)
        return (canvasRadiusPx / effectiveScale).coerceAtLeast(1.5f)
    }

    fun normalizeRotationDegrees(degrees: Float): Float {
        var r = degrees % 360f
        if (r > 180f) r -= 360f
        if (r <= -180f) r += 360f
        return r
    }

    fun clampScale(scale: Float): Float = scale.coerceIn(MIN_SCALE, MAX_SCALE)

    fun clampOffset(offset: Float): Float = offset.coerceIn(-MAX_OFFSET, MAX_OFFSET)

    fun clampEdgeFeather(feather: Float): Float = feather.coerceIn(0f, MAX_EDGE_FEATHER)

    fun clampBokehBlurRadius(radius: Float): Float = radius.coerceIn(0f, MAX_BOKEH_BLUR_RADIUS)
}
