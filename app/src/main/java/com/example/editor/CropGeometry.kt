package com.example.editor

import android.graphics.Bitmap
import androidx.compose.runtime.Immutable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

enum class CropOverlayMode(val label: String) {
    THIRDS("Thirds"),
    GRID("Grid"),
    GOLDEN("Golden"),
    DIAGONAL("Diagonal"),
    TRIANGLE("Triangle"),
    SPIRAL("Spiral"),
    NONE("None")
}

enum class CropAspectPreset(
    val label: String,
    val ratioWidth: Float?,
    val ratioHeight: Float?
) {
    ORIGINAL("Original", null, null),
    FREE("Free", null, null),
    RATIO_1_1("1:1", 1f, 1f),
    RATIO_4_5("4:5", 4f, 5f),
    RATIO_16_9("16:9", 16f, 9f),
    RATIO_3_2("3:2", 3f, 2f),
    RATIO_5_7("5:7", 5f, 7f)
}

@Immutable
data class NormalizedCropRect(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f
) {
    val width: Float get() = (right - left).coerceAtLeast(0.01f)
    val height: Float get() = (bottom - top).coerceAtLeast(0.01f)

    val isFull: Boolean
        get() = abs(left) < 0.001f &&
            abs(top) < 0.001f &&
            abs(right - 1f) < 0.001f &&
            abs(bottom - 1f) < 0.001f
}

@Immutable
data class CropGeometryParams(
    val cropLeft: Float = 0f,
    val cropTop: Float = 0f,
    val cropRight: Float = 1f,
    val cropBottom: Float = 1f,
    val aspectPreset: CropAspectPreset = CropAspectPreset.ORIGINAL,
    val aspectLocked: Boolean = true,
    val aspectSwapped: Boolean = false,
    val rotationSteps: Int = 0, // 0..3 clockwise 90° steps
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    val straightenDeg: Float = 0f,         // -45.0 .. +45.0
    val constrainCrop: Boolean = true,
    val verticalPerspective: Float = 0f,   // -100 .. +100
    val horizontalPerspective: Float = 0f, // -100 .. +100
    val geometryRotateDeg: Float = 0f,     // -180.0 .. +180.0
    val aspectStretch: Float = 0f,         // -100 .. +100
    val geometryScale: Float = 100f,       // 50 .. 200
    val offsetX: Float = 0f,               // -100 .. +100
    val offsetY: Float = 0f                // -100 .. +100
) {
    val hasCropBoxEdits: Boolean
        get() = abs(cropLeft) > 0.001f ||
            abs(cropTop) > 0.001f ||
            abs(cropRight - 1f) > 0.001f ||
            abs(cropBottom - 1f) > 0.001f

    val hasOrientationEdits: Boolean
        get() = (rotationSteps and 3) != 0 ||
            flipH ||
            flipV ||
            abs(straightenDeg) > 0.001f

    val hasCropOrOrientationEdits: Boolean
        get() = hasCropBoxEdits || hasOrientationEdits

    val hasGeometryEdits: Boolean
        get() = abs(verticalPerspective) > 0.001f ||
            abs(horizontalPerspective) > 0.001f ||
            abs(geometryRotateDeg) > 0.001f ||
            abs(aspectStretch) > 0.001f ||
            abs(geometryScale - 100f) > 0.001f ||
            abs(offsetX) > 0.001f ||
            abs(offsetY) > 0.001f

    val isDefault: Boolean
        get() = !hasCropOrOrientationEdits && !hasGeometryEdits

    val normalizedRect: NormalizedCropRect
        get() {
            val l = cropLeft.coerceIn(0f, 0.92f)
            val t = cropTop.coerceIn(0f, 0.92f)
            val r = cropRight.coerceIn(l + 0.08f, 1f)
            val b = cropBottom.coerceIn(t + 0.08f, 1f)
            return NormalizedCropRect(l, t, r, b)
        }

    fun withCropRect(rect: NormalizedCropRect): CropGeometryParams {
        val l = rect.left.coerceIn(0f, 0.92f)
        val t = rect.top.coerceIn(0f, 0.92f)
        val r = rect.right.coerceIn((l + 0.08f).coerceAtMost(1f), 1f)
        val b = rect.bottom.coerceIn((t + 0.08f).coerceAtMost(1f), 1f)
        return copy(cropLeft = l, cropTop = t, cropRight = r, cropBottom = b)
    }

    fun resetCropAndOrientation(): CropGeometryParams = copy(
        cropLeft = 0f,
        cropTop = 0f,
        cropRight = 1f,
        cropBottom = 1f,
        aspectPreset = CropAspectPreset.ORIGINAL,
        aspectLocked = true,
        aspectSwapped = false,
        rotationSteps = 0,
        flipH = false,
        flipV = false,
        straightenDeg = 0f
    )

    fun resetGeometry(): CropGeometryParams = copy(
        constrainCrop = true,
        verticalPerspective = 0f,
        horizontalPerspective = 0f,
        geometryRotateDeg = 0f,
        aspectStretch = 0f,
        geometryScale = 100f,
        offsetX = 0f,
        offsetY = 0f
    )
}

object GeometryMath {

    private const val MIN_CROP_SPAN = 0.08f

    fun orientedDimensions(srcW: Int, srcH: Int, crop: CropGeometryParams): Pair<Int, Int> {
        val w = srcW.coerceAtLeast(1)
        val h = srcH.coerceAtLeast(1)
        return if ((crop.rotationSteps and 1) == 1) h to w else w to h
    }

    fun orientedAspect(srcW: Int, srcH: Int, crop: CropGeometryParams): Float {
        val (ow, oh) = orientedDimensions(srcW, srcH, crop)
        return ow.toFloat() / oh.toFloat().coerceAtLeast(1f)
    }

    fun resolveTargetAspectRatio(srcW: Int, srcH: Int, crop: CropGeometryParams): Float? {
        val oAspect = orientedAspect(srcW, srcH, crop)
        val baseRatio = when (crop.aspectPreset) {
            CropAspectPreset.FREE -> return null
            CropAspectPreset.ORIGINAL -> oAspect
            else -> {
                val rw = crop.aspectPreset.ratioWidth ?: return null
                val rh = crop.aspectPreset.ratioHeight ?: return null
                rw / rh
            }
        }
        return if (crop.aspectSwapped) 1f / baseRatio else baseRatio
    }

    fun fitCenteredCropForAspect(
        targetAspect: Float,
        orientAspect: Float,
        bounds: NormalizedCropRect = NormalizedCropRect(0f, 0f, 1f, 1f)
    ): NormalizedCropRect {
        val normAspect = (targetAspect / orientAspect.coerceAtLeast(1e-4f)).coerceIn(0.05f, 20f)
        val maxW = bounds.width
        val maxH = bounds.height
        val w: Float
        val h: Float
        if (maxW / maxH > normAspect) {
            h = maxH
            w = (h * normAspect).coerceAtMost(maxW)
        } else {
            w = maxW
            h = (w / normAspect).coerceAtMost(maxH)
        }
        val cx = (bounds.left + bounds.right) * 0.5f
        val cy = (bounds.top + bounds.bottom) * 0.5f
        return NormalizedCropRect(
            left = (cx - w * 0.5f).coerceIn(bounds.left, bounds.right - MIN_CROP_SPAN),
            top = (cy - h * 0.5f).coerceIn(bounds.top, bounds.bottom - MIN_CROP_SPAN),
            right = (cx + w * 0.5f).coerceIn(bounds.left + MIN_CROP_SPAN, bounds.right),
            bottom = (cy + h * 0.5f).coerceIn(bounds.top + MIN_CROP_SPAN, bounds.bottom)
        )
    }

    /**
     * Adjusts crop parameters when user picks an aspect ratio preset, lock state, or swap button.
     */
    fun applyAspectPreset(
        srcW: Int,
        srcH: Int,
        crop: CropGeometryParams,
        preset: CropAspectPreset,
        swapped: Boolean = crop.aspectSwapped
    ): CropGeometryParams {
        val shouldLock = preset != CropAspectPreset.FREE
        val next = crop.copy(
            aspectPreset = preset,
            aspectLocked = shouldLock,
            aspectSwapped = if (preset == CropAspectPreset.RATIO_1_1) false else swapped
        )
        val orientAsp = orientedAspect(srcW, srcH, next)
        val targetAsp = resolveTargetAspectRatio(srcW, srcH, next) ?: return next
        val fitted = fitCenteredCropForAspect(targetAsp, orientAsp)
        return next.withCropRect(fitted)
    }

    fun swapCropAspect(srcW: Int, srcH: Int, crop: CropGeometryParams): CropGeometryParams {
        val cur = crop.normalizedRect
        val orientAsp = orientedAspect(srcW, srcH, crop)
        if (crop.aspectPreset == CropAspectPreset.FREE || !crop.aspectLocked) {
            val currentPixelAsp = (cur.width * orientAsp) / cur.height.coerceAtLeast(1e-4f)
            val flippedAsp = (1f / currentPixelAsp).coerceIn(0.1f, 10f)
            return crop.withCropRect(fitCenteredCropForAspect(flippedAsp, orientAsp))
        }
        val nextSwapped = !crop.aspectSwapped
        val updated = crop.copy(aspectSwapped = nextSwapped)
        val targetAsp = resolveTargetAspectRatio(srcW, srcH, updated) ?: return updated
        return updated.withCropRect(fitCenteredCropForAspect(targetAsp, orientAsp))
    }

    fun rotateCropSteps(crop: CropGeometryParams, deltaSteps: Int): CropGeometryParams {
        val nextSteps = ((crop.rotationSteps + deltaSteps) % 4 + 4) % 4
        val r = crop.normalizedRect
        // Transform existing crop rectangle along with 90° rotation so crop framing moves with the image
        val nextRect = when ((deltaSteps % 4 + 4) % 4) {
            1 -> NormalizedCropRect(1f - r.bottom, r.left, 1f - r.top, r.right)
            2 -> NormalizedCropRect(1f - r.right, 1f - r.bottom, 1f - r.left, 1f - r.top)
            3 -> NormalizedCropRect(r.top, 1f - r.right, r.bottom, 1f - r.left)
            else -> r
        }
        return crop.copy(rotationSteps = nextSteps).withCropRect(nextRect)
    }

    fun flipHorizontal(crop: CropGeometryParams): CropGeometryParams {
        val r = crop.normalizedRect
        return crop.copy(
            flipH = !crop.flipH,
            cropLeft = 1f - r.right,
            cropRight = 1f - r.left
        )
    }

    fun flipVertical(crop: CropGeometryParams): CropGeometryParams {
        val r = crop.normalizedRect
        return crop.copy(
            flipV = !crop.flipV,
            cropTop = 1f - r.bottom,
            cropBottom = 1f - r.top
        )
    }

    /**
     * Straighten auto-scale factor so rotating by straightenDeg keeps the crop frame filled.
     */
    fun computeStraightenAutoScale(orientAspect: Float, straightenDeg: Float): Float {
        if (abs(straightenDeg) < 0.001f) return 1f
        val rad = Math.toRadians(abs(straightenDeg).toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        val a = orientAspect.coerceIn(0.1f, 10f)
        return max(c + s / a, c + s * a)
    }

    /**
     * Builds row-major 3x3 inverse projective homography matrix mapping
     * oriented output UV (ou, ov, 1) -> unflipped/unrotated oriented source UV (soU, soV, w).
     */
    fun buildOrientToOrientedSourceMatrix(
        srcW: Int,
        srcH: Int,
        crop: CropGeometryParams
    ): FloatArray {
        val oAspect = orientedAspect(srcW, srcH, crop).coerceIn(0.1f, 10f)

        // 1. Center & physical aspect scaling: (ou, ov) in [0,1]^2 -> (x, y) in [-a, a] x [-1, 1]
        val mPhysFromUv = floatArrayOf(
            2f * oAspect, 0f, -oAspect,
            0f, 2f, -1f,
            0f, 0f, 1f
        )

        // 2. Inverse Offset + Inverse Scale (including Straighten auto-fill scale)
        val tx = (crop.offsetX / 100f) * oAspect
        val ty = (crop.offsetY / 100f) * 1f
        val zoom = (crop.geometryScale / 100f).coerceIn(0.5f, 2.0f)
        val autoFill = computeStraightenAutoScale(oAspect, crop.straightenDeg)
        val totalScale = (zoom * autoFill).coerceAtLeast(0.05f)
        val invScale = 1f / totalScale
        val mScaleOffset = floatArrayOf(
            invScale, 0f, -tx * invScale,
            0f, invScale, -ty * invScale,
            0f, 0f, 1f
        )

        // 3. Inverse Keystone perspective in centered physical plane
        val kv = (crop.verticalPerspective / 100f).coerceIn(-1f, 1f) * 0.42f
        val kh = (crop.horizontalPerspective / 100f).coerceIn(-1f, 1f) * 0.42f
        val mKeystone = floatArrayOf(
            1f, 0f, 0f,
            0f, 1f, 0f,
            kh / oAspect, -kv, 1f
        )

        // 4. Inverse Aspect Stretch
        val aspFactor = 2.0.pow((crop.aspectStretch / 100.0) * 0.42).toFloat()
        val mAspect = floatArrayOf(
            1f / aspFactor, 0f, 0f,
            0f, aspFactor, 0f,
            0f, 0f, 1f
        )

        // 5. Inverse Fine Rotation (Straighten + Geometry Rotate)
        val totalRotRad = Math.toRadians((crop.straightenDeg + crop.geometryRotateDeg).toDouble())
        val cosT = cos(totalRotRad).toFloat()
        val sinT = sin(totalRotRad).toFloat()
        val mRot = floatArrayOf(
            cosT, sinT, 0f,
            -sinT, cosT, 0f,
            0f, 0f, 1f
        )

        // 6. Back to normalized [0,1]^2 oriented source UV
        val mUvFromPhys = floatArrayOf(
            1f / (2f * oAspect), 0f, 0.5f,
            0f, 0.5f, 0.5f,
            0f, 0f, 1f
        )

        var m = mul3x3(mScaleOffset, mPhysFromUv)
        m = mul3x3(mKeystone, m)
        m = mul3x3(mAspect, m)
        m = mul3x3(mRot, m)
        m = mul3x3(mUvFromPhys, m)
        return m
    }

    /**
     * Full row-major 3x3 inverse projective homography mapping
     * oriented output UV (ou, ov, 1) -> raw source bitmap UV (su, sv, w),
     * including Flip H/V and 90° Orientation steps.
     */
    fun buildOrientToRawSourceMatrix(
        srcW: Int,
        srcH: Int,
        crop: CropGeometryParams
    ): FloatArray {
        val out = FloatArray(9)
        val scratchA = FloatArray(9)
        val scratchB = FloatArray(9)
        buildOrientToRawSourceMatrixInto(srcW, srcH, crop, out, scratchA, scratchB)
        return out
    }

    /**
     * Zero-allocation variant of [buildOrientToRawSourceMatrix] that writes the row-major 3x3
     * inverse projective homography directly into [out] using caller-owned scratch arrays.
     */
    fun buildOrientToRawSourceMatrixInto(
        srcW: Int,
        srcH: Int,
        crop: CropGeometryParams,
        out: FloatArray,
        scratchA: FloatArray,
        scratchB: FloatArray
    ) {
        val oAspect = orientedAspect(srcW, srcH, crop).coerceIn(0.1f, 10f)

        // 1. Center & physical aspect scaling -> scratchA
        set3x3(scratchA, 2f * oAspect, 0f, -oAspect, 0f, 2f, -1f, 0f, 0f, 1f)

        // 2. Inverse Offset + Inverse Scale (including Straighten auto-fill)
        val tx = (crop.offsetX / 100f) * oAspect
        val ty = (crop.offsetY / 100f) * 1f
        val zoom = (crop.geometryScale / 100f).coerceIn(0.5f, 2.0f)
        val autoFill = computeStraightenAutoScale(oAspect, crop.straightenDeg)
        val totalScale = (zoom * autoFill).coerceAtLeast(0.05f)
        val invScale = 1f / totalScale
        set3x3(scratchB, invScale, 0f, -tx * invScale, 0f, invScale, -ty * invScale, 0f, 0f, 1f)
        mul3x3Into(scratchB, scratchA, out)

        // 3. Inverse Keystone perspective
        val kv = (crop.verticalPerspective / 100f).coerceIn(-1f, 1f) * 0.42f
        val kh = (crop.horizontalPerspective / 100f).coerceIn(-1f, 1f) * 0.42f
        set3x3(scratchB, 1f, 0f, 0f, 0f, 1f, 0f, kh / oAspect, -kv, 1f)
        mul3x3Into(scratchB, out, scratchA)

        // 4. Inverse Aspect Stretch
        val aspFactor = 2.0.pow((crop.aspectStretch / 100.0) * 0.42).toFloat()
        set3x3(scratchB, 1f / aspFactor, 0f, 0f, 0f, aspFactor, 0f, 0f, 0f, 1f)
        mul3x3Into(scratchB, scratchA, out)

        // 5. Inverse Fine Rotation (Straighten + Geometry Rotate)
        val totalRotRad = Math.toRadians((crop.straightenDeg + crop.geometryRotateDeg).toDouble())
        val cosT = cos(totalRotRad).toFloat()
        val sinT = sin(totalRotRad).toFloat()
        set3x3(scratchB, cosT, sinT, 0f, -sinT, cosT, 0f, 0f, 0f, 1f)
        mul3x3Into(scratchB, out, scratchA)

        // 6. Back to normalized [0,1]^2 oriented source UV
        set3x3(scratchB, 1f / (2f * oAspect), 0f, 0.5f, 0f, 0.5f, 0.5f, 0f, 0f, 1f)
        mul3x3Into(scratchB, scratchA, out)

        // 7. Flip H/V
        val sx = if (crop.flipH) -1f else 1f
        val ftx = if (crop.flipH) 1f else 0f
        val sy = if (crop.flipV) -1f else 1f
        val fty = if (crop.flipV) 1f else 0f
        set3x3(scratchB, sx, 0f, ftx, 0f, sy, fty, 0f, 0f, 1f)
        mul3x3Into(scratchB, out, scratchA)

        // 8. 90° Orientation steps
        when (crop.rotationSteps and 3) {
            1 -> set3x3(scratchB, 0f, 1f, 0f, -1f, 0f, 1f, 0f, 0f, 1f)
            2 -> set3x3(scratchB, -1f, 0f, 1f, 0f, -1f, 1f, 0f, 0f, 1f)
            3 -> set3x3(scratchB, 0f, -1f, 1f, 1f, 0f, 0f, 0f, 0f, 1f)
            else -> set3x3(scratchB, 1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        }
        mul3x3Into(scratchB, scratchA, out)
    }

    private fun set3x3(
        dst: FloatArray,
        m0: Float, m1: Float, m2: Float,
        m3: Float, m4: Float, m5: Float,
        m6: Float, m7: Float, m8: Float
    ) {
        dst[0] = m0; dst[1] = m1; dst[2] = m2
        dst[3] = m3; dst[4] = m4; dst[5] = m5
        dst[6] = m6; dst[7] = m7; dst[8] = m8
    }

    private fun mul3x3Into(a: FloatArray, b: FloatArray, dst: FloatArray) {
        for (r in 0..2) {
            val r3 = r * 3
            val a0 = a[r3]
            val a1 = a[r3 + 1]
            val a2 = a[r3 + 2]
            dst[r3] = a0 * b[0] + a1 * b[3] + a2 * b[6]
            dst[r3 + 1] = a0 * b[1] + a1 * b[4] + a2 * b[7]
            dst[r3 + 2] = a0 * b[2] + a1 * b[5] + a2 * b[8]
        }
    }

    fun applyMatrix(m: FloatArray, x: Float, y: Float): Pair<Float, Float>? {
        val w = m[6] * x + m[7] * y + m[8]
        if (w <= 1e-4f) return null
        return ((m[0] * x + m[1] * y + m[2]) / w) to ((m[3] * x + m[4] * y + m[5]) / w)
    }

    private fun isPointInUnitSquare(m: FloatArray, x: Float, y: Float, eps: Float = 0.0005f): Boolean {
        val p = applyMatrix(m, x, y) ?: return false
        return p.first >= -eps && p.first <= 1f + eps && p.second >= -eps && p.second <= 1f + eps
    }

    private fun isRectInsideSource(m: FloatArray, l: Float, t: Float, r: Float, b: Float): Boolean {
        val mx = (l + r) * 0.5f
        val my = (t + b) * 0.5f
        return isPointInUnitSquare(m, l, t) &&
            isPointInUnitSquare(m, r, t) &&
            isPointInUnitSquare(m, r, b) &&
            isPointInUnitSquare(m, l, b) &&
            isPointInUnitSquare(m, mx, t) &&
            isPointInUnitSquare(m, mx, b) &&
            isPointInUnitSquare(m, l, my) &&
            isPointInUnitSquare(m, r, my)
    }

    /**
     * When constrainCrop is enabled, automatically tightens the crop rectangle inside the
     * warped valid source bounds so perspective/rotation adjustments never leave empty edges.
     */
    fun effectiveCropRect(
        srcW: Int,
        srcH: Int,
        crop: CropGeometryParams
    ): NormalizedCropRect {
        val base = crop.normalizedRect
        if (!crop.constrainCrop || (!crop.hasGeometryEdits && abs(crop.straightenDeg) < 0.001f)) {
            return base
        }

        val m = buildOrientToOrientedSourceMatrix(srcW, srcH, crop)
        if (isRectInsideSource(m, base.left, base.top, base.right, base.bottom)) {
            return base
        }

        // Find a guaranteed interior anchor by inverting matrix to locate source center in O-space
        val mFwd = invert3x3(m)
        val fwdCenter = mFwd?.let { applyMatrix(it, 0.5f, 0.5f) }
        val baseCx = (base.left + base.right) * 0.5f
        val baseCy = (base.top + base.bottom) * 0.5f
        val anchorX = if (isPointInUnitSquare(m, baseCx, baseCy, eps = -0.005f)) {
            baseCx
        } else {
            (fwdCenter?.first ?: 0.5f).coerceIn(0.02f, 0.98f)
        }
        val anchorY = if (isPointInUnitSquare(m, anchorX, baseCy, eps = -0.005f)) {
            baseCy
        } else {
            (fwdCenter?.second ?: 0.5f).coerceIn(0.02f, 0.98f)
        }

        if (!isPointInUnitSquare(m, anchorX, anchorY, eps = 0.001f)) {
            return base
        }

        var lo = 0.02f
        var hi = 1.0f
        var bestL = anchorX - 0.04f
        var bestT = anchorY - 0.04f
        var bestR = anchorX + 0.04f
        var bestB = anchorY + 0.04f

        for (iter in 0 until 20) {
            val mid = (lo + hi) * 0.5f
            val cl = (anchorX + (base.left - anchorX) * mid).coerceIn(0f, 1f)
            val ct = (anchorY + (base.top - anchorY) * mid).coerceIn(0f, 1f)
            val cr = (anchorX + (base.right - anchorX) * mid).coerceIn(0f, 1f)
            val cb = (anchorY + (base.bottom - anchorY) * mid).coerceIn(0f, 1f)
            if (cr - cl >= 0.02f && cb - ct >= 0.02f && isRectInsideSource(m, cl, ct, cr, cb)) {
                bestL = cl
                bestT = ct
                bestR = cr
                bestB = cb
                lo = mid
            } else {
                hi = mid
            }
        }

        return NormalizedCropRect(
            left = bestL.coerceIn(0f, 0.96f),
            top = bestT.coerceIn(0f, 0.96f),
            right = bestR.coerceIn((bestL + 0.02f).coerceAtMost(1f), 1f),
            bottom = bestB.coerceIn((bestT + 0.02f).coerceAtMost(1f), 1f)
        )
    }

    fun computeOutputDimensions(
        srcW: Int,
        srcH: Int,
        crop: CropGeometryParams
    ): Pair<Int, Int> {
        val (ow, oh) = orientedDimensions(srcW, srcH, crop)
        val eff = effectiveCropRect(srcW, srcH, crop)
        val cw = (eff.width * ow).roundToInt().coerceAtLeast(1)
        val ch = (eff.height * oh).roundToInt().coerceAtLeast(1)
        return cw to ch
    }

    fun mapOutputUvToSourceUv(
        u: Float,
        v: Float,
        srcW: Int,
        srcH: Int,
        crop: CropGeometryParams,
        applyCrop: Boolean = true
    ): Pair<Float, Float>? {
        val eff = if (applyCrop) {
            effectiveCropRect(srcW, srcH, crop)
        } else {
            NormalizedCropRect(0f, 0f, 1f, 1f)
        }
        val ou = eff.left + u * (eff.right - eff.left)
        val ov = eff.top + v * (eff.bottom - eff.top)
        val m = buildOrientToRawSourceMatrix(srcW, srcH, crop)
        val mapped = applyMatrix(m, ou, ov) ?: return null
        return if (mapped.first < 0f || mapped.first > 1f || mapped.second < 0f || mapped.second > 1f) {
            null
        } else {
            mapped
        }
    }

    fun mapSourceUvToOutputUv(
        su: Float,
        sv: Float,
        srcW: Int,
        srcH: Int,
        crop: CropGeometryParams,
        applyCrop: Boolean = true
    ): Pair<Float, Float>? {
        val m = buildOrientToRawSourceMatrix(srcW, srcH, crop)
        val inv = invert3x3(m) ?: return null
        val oriented = applyMatrix(inv, su, sv) ?: return null
        val eff = if (applyCrop) {
            effectiveCropRect(srcW, srcH, crop)
        } else {
            NormalizedCropRect(0f, 0f, 1f, 1f)
        }
        val effW = (eff.right - eff.left).coerceAtLeast(1e-5f)
        val effH = (eff.bottom - eff.top).coerceAtLeast(1e-5f)
        val u = (oriented.first - eff.left) / effW
        val v = (oriented.second - eff.top) / effH
        return if (u < -0.25f || u > 1.25f || v < -0.25f || v > 1.25f) {
            null
        } else {
            u to v
        }
    }

    /**
     * Analyzes dominant structural edge angles (horizon lines & architectural verticals)
     * in oriented image space to compute an Auto Straighten correction angle in [-45°, +45°].
     */
    fun computeAutoStraightenAngle(
        source: Bitmap,
        crop: CropGeometryParams = CropGeometryParams()
    ): Float {
        val (ow, oh) = orientedDimensions(source.width, source.height, crop)
        if (ow < 8 || oh < 8) return 0f

        val maxDim = 180
        val scale = min(1f, maxDim.toFloat() / max(ow, oh).toFloat())
        val gw = (ow * scale).roundToInt().coerceIn(16, maxDim)
        val gh = (oh * scale).roundToInt().coerceIn(16, maxDim)

        val sw = source.width
        val sh = source.height
        val pixels = IntArray(sw * sh)
        source.getPixels(pixels, 0, sw, 0, 0, sw, sh)

        val orientedOnly = CropGeometryParams(
            rotationSteps = crop.rotationSteps,
            flipH = crop.flipH,
            flipV = crop.flipV
        )
        val mOrient = buildOrientToRawSourceMatrix(sw, sh, orientedOnly)

        val luma = FloatArray(gw * gh)
        for (y in 0 until gh) {
            val ov = (y + 0.5f) / gh.toFloat()
            for (x in 0 until gw) {
                val ou = (x + 0.5f) / gw.toFloat()
                val mapped = applyMatrix(mOrient, ou, ov)
                if (mapped != null) {
                    val sx = (mapped.first.coerceIn(0f, 1f) * (sw - 1)).roundToInt().coerceIn(0, sw - 1)
                    val sy = (mapped.second.coerceIn(0f, 1f) * (sh - 1)).roundToInt().coerceIn(0, sh - 1)
                    val px = pixels[sy * sw + sx]
                    val r = ((px ushr 16) and 0xFF) / 255f
                    val g = ((px ushr 8) and 0xFF) / 255f
                    val b = (px and 0xFF) / 255f
                    luma[y * gw + x] = 0.2126f * r + 0.7152f * g + 0.0722f * b
                }
            }
        }

        // Six passes of separable [1, 2, 1]/4 smoothing (sigma ≈ 1.73px, 13px support) eliminates staircase aliasing
        val tempSmooth = FloatArray(gw * gh)
        val smoothLuma = FloatArray(gw * gh)
        luma.copyInto(smoothLuma)
        repeat(6) {
            for (y in 0 until gh) {
                val row = y * gw
                for (x in 0 until gw) {
                    val xm = (x - 1).coerceAtLeast(0)
                    val xp = (x + 1).coerceAtMost(gw - 1)
                    tempSmooth[row + x] =
                        (smoothLuma[row + xm] + 2f * smoothLuma[row + x] + smoothLuma[row + xp]) * 0.25f
                }
            }
            for (y in 0 until gh) {
                val ym = (y - 1).coerceAtLeast(0) * gw
                val y0 = y * gw
                val yp = (y + 1).coerceAtMost(gh - 1) * gw
                for (x in 0 until gw) {
                    smoothLuma[y0 + x] =
                        (tempSmooth[ym + x] + 2f * tempSmooth[y0 + x] + tempSmooth[yp + x]) * 0.25f
                }
            }
        }

        // Half-degree resolution bins covering [-25.0°, +25.0°] (101 bins)
        val minDeg = -25f
        val maxDeg = 25f
        val binStep = 0.5f
        val binCount = ((maxDeg - minDeg) / binStep).toInt() + 1
        val hist = FloatArray(binCount)
        var totalWeight = 0f

        for (y in 3 until gh - 3) {
            val row = y * gw
            for (x in 3 until gw - 3) {
                val gx = (smoothLuma[row + x + 2] - smoothLuma[row + x - 2]) * 0.5f
                val gy = (smoothLuma[row + 2 * gw + x] - smoothLuma[row - 2 * gw + x]) * 0.5f
                val mag = hypot(gx.toDouble(), gy.toDouble()).toFloat()
                if (mag < 0.025f) continue

                // Edge tangent vector is perpendicular to gradient: (-gy, gx)
                val angleRad = atan2(gx.toDouble(), (-gy).toDouble())
                var deg = (angleRad * 180.0 / PI).toFloat()
                // Fold into [-45°, +45°] deviation from nearest horizontal/vertical axis
                while (deg > 45f) deg -= 90f
                while (deg < -45f) deg += 90f

                if (abs(deg) <= 24f) {
                    val idx = ((deg - minDeg) / binStep).roundToInt().coerceIn(0, binCount - 1)
                    hist[idx] += mag
                    totalWeight += mag
                }
            }
        }

        if (totalWeight <= 0.01f) return 0f

        // Smooth histogram with [1, 2, 3, 2, 1] kernel
        val smoothed = FloatArray(binCount)
        for (i in 0 until binCount) {
            var sum = 0f
            var wSum = 0f
            for (k in -2..2) {
                val j = i + k
                if (j in 0 until binCount) {
                    val w = (3 - abs(k)).toFloat()
                    sum += hist[j] * w
                    wSum += w
                }
            }
            smoothed[i] = sum / wSum
        }

        var bestIdx = binCount / 2
        var bestVal = -1f
        for (i in 0 until binCount) {
            if (smoothed[i] > bestVal) {
                bestVal = smoothed[i]
                bestIdx = i
            }
        }

        val meanVal = totalWeight / binCount
        if (bestVal < meanVal * 1.45f) return 0f

        // Sub-bin parabolic refinement around bestIdx
        val subOffset = if (bestIdx in 1 until (binCount - 1)) {
            val yL = smoothed[bestIdx - 1]
            val yC = smoothed[bestIdx]
            val yR = smoothed[bestIdx + 1]
            val denom = 2f * (yL - 2f * yC + yR)
            if (abs(denom) > 1e-5f) ((yL - yR) / denom).coerceIn(-0.5f, 0.5f) else 0f
        } else 0f

        val dominantTangentTilt = minDeg + (bestIdx + subOffset) * binStep
        // Counter-rotate by -dominantTangentTilt to level the horizon/verticals
        val correction = (-dominantTangentTilt).coerceIn(-45f, 45f)
        return if (abs(correction) < 0.15f) 0f else ((correction * 20f).roundToInt() / 20f)
    }

    private fun mul3x3(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(9)
        for (r in 0..2) {
            for (c in 0..2) {
                out[r * 3 + c] = a[r * 3] * b[c] +
                    a[r * 3 + 1] * b[3 + c] +
                    a[r * 3 + 2] * b[6 + c]
            }
        }
        return out
    }

    private fun invert3x3(m: FloatArray): FloatArray? {
        val a = m[0]; val b = m[1]; val c = m[2]
        val d = m[3]; val e = m[4]; val f = m[5]
        val g = m[6]; val h = m[7]; val i = m[8]

        val c00 = e * i - f * h
        val c01 = -(d * i - f * g)
        val c02 = d * h - e * g
        val c10 = -(b * i - c * h)
        val c11 = a * i - c * g
        val c12 = -(a * h - b * g)
        val c20 = b * f - c * e
        val c21 = -(a * f - c * d)
        val c22 = a * e - b * d

        val det = a * c00 + b * c01 + c * c02
        if (abs(det) < 1e-7f) return null
        val invDet = 1f / det
        return floatArrayOf(
            c00 * invDet, c10 * invDet, c20 * invDet,
            c01 * invDet, c11 * invDet, c21 * invDet,
            c02 * invDet, c12 * invDet, c22 * invDet
        )
    }
}
