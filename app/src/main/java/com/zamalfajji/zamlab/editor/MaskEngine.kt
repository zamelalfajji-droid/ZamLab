package com.zamalfajji.zamlab.editor

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Resolution-independent CPU/reference masking engine that mirrors the GLSL ES 3.00 GPU
 * masking pipeline 1:1:
 *  - Vector Brush strokes in normalized [0.0, 1.0] UV coordinates
 *  - Rotatable Radial & Linear gradients
 *  - Rec. 709 Luminance Range parametric masks
 *  - CIELAB perceptual Color Range masks
 *  - Boolean sub-mask compositing (ADD, SUBTRACT, INTERSECT) + Invert
 *  - Edge-aware Guided Image Filter (He et al. 2010) driven by "Feather / Edge Snap" (0..100)
 *  - All 8 selective local adjustments + 50% opacity Rubylith (#FF2A2A) overlay
 */
object MaskEngine {

    const val RUBYLITH_R = 1.0f
    const val RUBYLITH_G = 0x2A / 255f // 0.16470589f (#FF2A2A)
    const val RUBYLITH_B = 0x2A / 255f
    const val RUBYLITH_OPACITY = 0.50f

    /** Rec. 709 luminance matching the fragment shader exactly. */
    fun luma709(r: Float, g: Float, b: Float): Float =
        (0.2126f * r + 0.7152f * g + 0.0722f * b).coerceIn(0f, 1f)

    fun srgbToLinear(c: Float): Float {
        val x = c.coerceIn(0f, 1f)
        return if (x <= 0.04045f) x / 12.92f else ((x + 0.055f) / 1.055f).pow(2.4f)
    }

    fun linearToSrgb(c: Float): Float {
        val x = c.coerceIn(0f, 1f)
        return if (x <= 0.0031308f) x * 12.92f else (1.055f * x.pow(1f / 2.4f) - 0.055f).coerceIn(0f, 1f)
    }

    /**
     * Converts display sRGB [0..1] to CIE L*a*b* (D65 reference white) for perceptual color matching.
     * L* in [0, 100], a* and b* roughly in [-128, 128].
     */
    fun srgbToLab(r: Float, g: Float, b: Float, out: FloatArray = FloatArray(3)): FloatArray {
        val rl = srgbToLinear(r)
        val gl = srgbToLinear(g)
        val bl = srgbToLinear(b)

        // sRGB -> XYZ (D65)
        val x = (0.4124564f * rl + 0.3575761f * gl + 0.1804375f * bl) / 0.95047f
        val y = (0.2126729f * rl + 0.7151522f * gl + 0.0721750f * bl) / 1.00000f
        val z = (0.0193339f * rl + 0.1191920f * gl + 0.9503041f * bl) / 1.08883f

        val fx = labF(x)
        val fy = labF(y)
        val fz = labF(z)

        out[0] = 116f * fy - 16f
        out[1] = 500f * (fx - fy)
        out[2] = 200f * (fy - fz)
        return out
    }

    private fun labF(t: Float): Float {
        val clamped = max(0f, t)
        return if (clamped > 0.008856f) {
            cbrt(clamped.toDouble()).toFloat()
        } else {
            (7.787f * clamped) + (16f / 116f)
        }
    }

    fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val denom = edge1 - edge0
        if (abs(denom) < 1e-6f) return if (x >= edge1) 1f else 0f
        val t = ((x - edge0) / denom).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    fun combineMaskOp(base: Float, sub: Float, op: MaskBooleanOp): Float {
        return when (op) {
            MaskBooleanOp.ADD -> (base + sub).coerceIn(0f, 1f)
            MaskBooleanOp.SUBTRACT -> (base - sub).coerceIn(0f, 1f)
            MaskBooleanOp.INTERSECT -> (base * sub).coerceIn(0f, 1f)
        }
    }

    /**
     * Maps the user-facing "Feather / Edge Snap" slider [0..100] to Guided Filter parameters:
     *  - radius (in pixels, scaled proportionally to image resolution so preview & export match)
     *  - epsilon (regularization controlling edge crispness: smaller = tighter snap to edges)
     */
    fun guidedFilterParams(edgeSnap: Float, width: Int, height: Int): Pair<Int, Float> {
        val s = (edgeSnap / 100f).coerceIn(0f, 1f)
        if (s <= 0.005f) return 0 to 0.01f
        val minDim = max(1, min(width, height))
        // Scale radius proportionally to image resolution within GPU MAX_GUIDED_RADIUS (24)
        // so soft feathered masks reliably span and snap onto real image edges at both preview and export sizes.
        val radius = (minDim * (0.03f + s * 0.22f)).roundToInt().coerceIn(3, 24)
        // Edge-preservation epsilon: tighter edge snap as user increases slider (0.02 down to 0.0004)
        val eps = (0.02f * (1f - s * 0.85f).pow(2.2f)).coerceIn(0.0004f, 0.025f)
        return radius to eps
    }

    fun evaluateRadialSubMask(sub: MaskSubMask, u: Float, v: Float): Float {
        val rx = sub.radiusX.coerceAtLeast(0.02f)
        val ry = sub.radiusY.coerceAtLeast(0.02f)
        val dxRaw = u - sub.centerX
        val dyRaw = v - sub.centerY
        val rad = (-sub.rotationDeg * (PI / 180.0)).toFloat()
        val c = cos(rad)
        val s = sin(rad)
        val dx = (dxRaw * c - dyRaw * s) / rx
        val dy = (dxRaw * s + dyRaw * c) / ry
        val dist = sqrt(dx * dx + dy * dy)
        val f = sub.feather.coerceIn(0f, 1f)
        val inner = (1f - f).coerceIn(0f, 0.98f)
        val outer = (1f + f * 0.15f).coerceAtLeast(inner + 0.02f)
        return 1f - smoothstep(inner, outer, dist)
    }

    fun evaluateLinearSubMask(sub: MaskSubMask, u: Float, v: Float, aspect: Float): Float {
        val safeAspect = aspect.coerceAtLeast(0.1f)
        val px = (u - sub.startX) * safeAspect
        val py = v - sub.startY
        val dx = (sub.endX - sub.startX) * safeAspect
        val dy = sub.endY - sub.startY
        val len2 = (dx * dx + dy * dy).coerceAtLeast(1e-4f)
        val proj = ((px * dx + py * dy) / len2)
        // Feather widens/sharpens the transition ramp centered around the start->end span
        val f = sub.feather.coerceIn(0.02f, 1f)
        val halfSpan = 0.5f * f
        val edge0 = 0.5f - halfSpan
        val edge1 = 0.5f + halfSpan
        return 1f - smoothstep(edge0, edge1, proj)
    }

    fun evaluateBrushSubMask(sub: MaskSubMask, u: Float, v: Float, aspect: Float): Float {
        if (sub.strokes.isEmpty()) return 0f
        val safeAspect = aspect.coerceAtLeast(0.1f)
        val ax = if (safeAspect >= 1f) safeAspect else 1f
        val ay = if (safeAspect < 1f) 1f / safeAspect else 1f
        var accum = 0f
        for (stroke in sub.strokes) {
            val pts = stroke.points
            if (pts.isEmpty()) continue
            val r = stroke.radius.coerceIn(0.005f, 0.35f)
            val hardness = stroke.hardness.coerceIn(0f, 1f)
            val flow = stroke.flow.coerceIn(0.02f, 1f)
            val inner = r * hardness * 0.95f
            var minDist = Float.MAX_VALUE
            if (pts.size == 1) {
                val dx = (u - pts[0].first) * ax
                val dy = (v - pts[0].second) * ay
                minDist = sqrt(dx * dx + dy * dy)
            } else {
                for (i in 0 until pts.size - 1) {
                    val p0 = pts[i]
                    val p1 = pts[i + 1]
                    val d = capsuleDistance(
                        px = u * ax,
                        py = v * ay,
                        ax = p0.first * ax,
                        ay = p0.second * ay,
                        bx = p1.first * ax,
                        by = p1.second * ay
                    )
                    if (d < minDist) minDist = d
                }
            }
            if (minDist < r) {
                val dab = (1f - smoothstep(inner, r, minDist)) * flow
                // Porter-Duff screen/over buildup per stroke so strokes layer smoothly
                accum = (accum + dab * (1f - accum)).coerceIn(0f, 1f)
                if (accum >= 0.999f) return 1f
            }
        }
        return accum
    }

    private fun capsuleDistance(
        px: Float,
        py: Float,
        ax: Float,
        ay: Float,
        bx: Float,
        by: Float
    ): Float {
        val abx = bx - ax
        val aby = by - ay
        val abLen2 = abx * abx + aby * aby
        if (abLen2 < 1e-8f) {
            val dx = px - ax
            val dy = py - ay
            return sqrt(dx * dx + dy * dy)
        }
        val t = (((px - ax) * abx + (py - ay) * aby) / abLen2).coerceIn(0f, 1f)
        val cx = ax + abx * t
        val cy = ay + aby * t
        val dx = px - cx
        val dy = py - cy
        return sqrt(dx * dx + dy * dy)
    }

    /**
     * Evaluates Rec. 709 Luminance Range mask weight [0..1] for pixel sRGB ([r], [g], [b]).
     */
    fun evaluateLuminanceRangeWeight(
        r: Float,
        g: Float,
        b: Float,
        lumMin: Float,
        lumMax: Float,
        falloff: Float
    ): Float {
        val lo = min(lumMin, lumMax).coerceIn(0f, 1f)
        val hi = max(lumMin, lumMax).coerceIn(0f, 1f)
        val f = falloff.coerceIn(0.005f, 0.5f)
        val y = luma709(r, g, b)
        val lowPass = if (lo <= 0.001f) 1f else smoothstep(lo - f, lo + f * 0.25f, y)
        val highPass = if (hi >= 0.999f) 1f else (1f - smoothstep(hi - f * 0.25f, hi + f, y))
        return (lowPass * highPass).coerceIn(0f, 1f)
    }

    /**
     * Evaluates CIELAB perceptual Color Range mask weight [0..1] for pixel sRGB ([r], [g], [b]).
     */
    fun evaluateColorRangeWeight(
        r: Float,
        g: Float,
        b: Float,
        targetLab: FloatArray,
        tolerance: Float,
        smoothness: Float,
        sampled: Boolean,
        scratchLab: FloatArray = FloatArray(3)
    ): Float {
        if (!sampled) return 0f
        srgbToLab(r, g, b, scratchLab)
        val dL = scratchLab[0] - targetLab[0]
        val dA = scratchLab[1] - targetLab[1]
        val dB = scratchLab[2] - targetLab[2]
        val deltaE = sqrt(dL * dL + dA * dA + dB * dB)
        val tol = tolerance.coerceIn(2f, 120f)
        val smoothBand = (tol * smoothness.coerceIn(0.02f, 1f)).coerceAtLeast(1.5f)
        val inner = (tol - smoothBand * 0.5f).coerceAtLeast(0f)
        val outer = tol + smoothBand * 0.5f
        return (1f - smoothstep(inner, outer, deltaE)).coerceIn(0f, 1f)
    }

    fun evaluateSubMaskAtPixel(
        sub: MaskSubMask,
        u: Float,
        v: Float,
        aspect: Float,
        r: Float,
        g: Float,
        b: Float,
        targetLabCache: FloatArray? = null,
        scratchLab: FloatArray = FloatArray(3)
    ): Float {
        if (!sub.enabled) return 0f
        return when (sub.type) {
            MaskType.RADIAL -> evaluateRadialSubMask(sub, u, v)
            MaskType.LINEAR -> evaluateLinearSubMask(sub, u, v, aspect)
            MaskType.BRUSH -> evaluateBrushSubMask(sub, u, v, aspect)
            MaskType.LUMINANCE_RANGE -> evaluateLuminanceRangeWeight(
                r = r,
                g = g,
                b = b,
                lumMin = sub.lumMin,
                lumMax = sub.lumMax,
                falloff = sub.lumFalloff
            )
            MaskType.COLOR_RANGE -> {
                val lab = targetLabCache ?: srgbToLab(sub.targetR, sub.targetG, sub.targetB)
                evaluateColorRangeWeight(
                    r = r,
                    g = g,
                    b = b,
                    targetLab = lab,
                    tolerance = sub.colorTolerance,
                    smoothness = sub.colorSmoothness,
                    sampled = sub.colorSampled,
                    scratchLab = scratchLab
                )
            }
        }
    }

    /**
     * Evaluates a full [LocalMask] at a single pixel (Boolean sub-masks + layer invert,
     * pre-Guided-Filter).
     */
    fun evaluateCompositeMaskAtPixel(
        mask: LocalMask,
        u: Float,
        v: Float,
        aspect: Float,
        r: Float = 0.5f,
        g: Float = 0.5f,
        b: Float = 0.5f
    ): Float {
        if (!mask.enabled) return 0f
        val subs = mask.resolvedSubMasks()
        var accum = 0f
        var initialized = false
        val scratchLab = FloatArray(3)
        for (sub in subs) {
            if (!sub.enabled) continue
            val w = evaluateSubMaskAtPixel(sub, u, v, aspect, r, g, b, null, scratchLab)
            if (!initialized) {
                accum = if (sub.op == MaskBooleanOp.SUBTRACT) 0f else w
                initialized = true
            } else {
                accum = combineMaskOp(accum, w, sub.op)
            }
        }
        if (mask.inverted) {
            accum = 1f - accum
        }
        return accum.coerceIn(0f, 1f)
    }

    /**
     * Computes the full-resolution float mask alpha buffer [0..1] for [mask] using the underlying
     * RGB guide image [guideR], [guideG], [guideB], including Boolean compositing, Invert, and
     * the edge-aware Guided Image Filter when [LocalMask.edgeSnap] > 0.
     */
    fun computeMaskAlphaBuffer(
        mask: LocalMask,
        guideR: FloatArray,
        guideG: FloatArray,
        guideB: FloatArray,
        width: Int,
        height: Int
    ): FloatArray {
        val n = width * height
        val out = FloatArray(n)
        if (!mask.enabled || width <= 0 || height <= 0) return out

        val subs = mask.resolvedSubMasks().filter { it.enabled }
        val targetLabs = subs.map { sub ->
            if (sub.type == MaskType.COLOR_RANGE && sub.colorSampled) {
                srgbToLab(sub.targetR, sub.targetG, sub.targetB)
            } else {
                null
            }
        }
        val aspect = width.toFloat() / max(1, height).toFloat()
        val scratchLab = FloatArray(3)

        for (y in 0 until height) {
            val v = (y + 0.5f) / height
            val rowOff = y * width
            for (x in 0 until width) {
                val u = (x + 0.5f) / width
                val idx = rowOff + x
                val r = guideR[idx]
                val g = guideG[idx]
                val b = guideB[idx]
                var accum = 0f
                var initialized = false
                for (si in subs.indices) {
                    val sub = subs[si]
                    val w = evaluateSubMaskAtPixel(
                        sub = sub,
                        u = u,
                        v = v,
                        aspect = aspect,
                        r = r,
                        g = g,
                        b = b,
                        targetLabCache = targetLabs[si],
                        scratchLab = scratchLab
                    )
                    if (!initialized) {
                        accum = if (sub.op == MaskBooleanOp.SUBTRACT) 0f else w
                        initialized = true
                    } else {
                        accum = combineMaskOp(accum, w, sub.op)
                    }
                }
                if (mask.inverted) accum = 1f - accum
                out[idx] = accum.coerceIn(0f, 1f)
            }
        }

        if (mask.edgeSnap > 0.5f) {
            val (radius, eps) = guidedFilterParams(mask.edgeSnap, width, height)
            if (radius >= 1) {
                val guideLuma = FloatArray(n) { i -> luma709(guideR[i], guideG[i], guideB[i]) }
                return guidedImageFilter(guideLuma, out, width, height, radius, eps)
            }
        }
        return out
    }

    /**
     * Exact O(N) separable box-filter Guided Image Filter (He, Sun, Tang 2010):
     *   meanI = box(I), meanP = box(p), corrI = box(I*I), corrIP = box(I*p)
     *   varI  = max(0, corrI - meanI*meanI)
     *   covIP = corrIP - meanI*meanP
     *   a     = covIP / (varI + eps)
     *   b     = meanP - a * meanI
     *   q     = clamp(box(a) * I + box(b), 0, 1)
     */
    fun guidedImageFilter(
        guideI: FloatArray,
        inputP: FloatArray,
        width: Int,
        height: Int,
        radius: Int,
        eps: Float
    ): FloatArray {
        val n = width * height
        if (radius <= 0 || n == 0) return inputP.copyOf()

        val ii = FloatArray(n) { i -> guideI[i] * guideI[i] }
        val ip = FloatArray(n) { i -> guideI[i] * inputP[i] }
        val scratch = FloatArray(n)

        val meanI = boxFilter2D(guideI, width, height, radius, scratch)
        val meanP = boxFilter2D(inputP, width, height, radius, scratch)
        val corrI = boxFilter2D(ii, width, height, radius, scratch)
        val corrIP = boxFilter2D(ip, width, height, radius, scratch)

        val a = FloatArray(n)
        val b = FloatArray(n)
        for (i in 0 until n) {
            val varI = max(0f, corrI[i] - meanI[i] * meanI[i])
            val covIP = corrIP[i] - meanI[i] * meanP[i]
            val ai = covIP / (varI + eps)
            a[i] = ai
            b[i] = meanP[i] - ai * meanI[i]
        }

        val meanA = boxFilter2D(a, width, height, radius, scratch)
        val meanB = boxFilter2D(b, width, height, radius, scratch)

        val q = FloatArray(n)
        for (i in 0 until n) {
            q[i] = (meanA[i] * guideI[i] + meanB[i]).coerceIn(0f, 1f)
        }
        return q
    }

    private fun boxFilter2D(
        src: FloatArray,
        width: Int,
        height: Int,
        radius: Int,
        scratch: FloatArray
    ): FloatArray {
        val dst = FloatArray(width * height)
        val windowSize = (2 * radius + 1).toFloat()

        // Horizontal pass (clamp-to-edge)
        for (y in 0 until height) {
            val rowOff = y * width
            var sum = 0f
            for (k in -radius..radius) {
                val sx = k.coerceIn(0, width - 1)
                sum += src[rowOff + sx]
            }
            for (x in 0 until width) {
                scratch[rowOff + x] = sum / windowSize
                val outX = (x - radius).coerceIn(0, width - 1)
                val inX = (x + radius + 1).coerceIn(0, width - 1)
                sum += src[rowOff + inX] - src[rowOff + outX]
            }
        }

        // Vertical pass (clamp-to-edge)
        for (x in 0 until width) {
            var sum = 0f
            for (k in -radius..radius) {
                val sy = k.coerceIn(0, height - 1)
                sum += scratch[sy * width + x]
            }
            for (y in 0 until height) {
                dst[y * width + x] = sum / windowSize
                val outY = (y - radius).coerceIn(0, height - 1)
                val inY = (y + radius + 1).coerceIn(0, height - 1)
                sum += scratch[inY * width + x] - scratch[outY * width + x]
            }
        }
        return dst
    }

    /**
     * Applies all 8 selective local adjustments for [mask] in-place to RGB float buffers [rBuf], [gBuf], [bBuf]
     * weighted by [maskAlpha]. Mirrors `MaskLocalAdjustShader` 1:1.
     */
    fun applyLocalMaskAdjustmentsCpu(
        mask: LocalMask,
        maskAlpha: FloatArray,
        rBuf: FloatArray,
        gBuf: FloatArray,
        bBuf: FloatArray,
        width: Int,
        height: Int
    ) {
        if (!mask.enabled || !mask.hasAdjustments) return
        val n = width * height
        val expGain = 2.0.pow(mask.exposure.toDouble()).toFloat()
        val con = mask.contrast / 100f
        val hi = mask.highlights / 100f
        val sh = mask.shadows / 100f
        val temp = mask.effectiveTemperature / 100f
        val tint = mask.tint / 100f
        val sat = mask.saturation / 100f
        val sharp = (mask.sharpness / 100f).coerceIn(0f, 1f)
        val clarity = (mask.clarity / 100f).coerceIn(-1f, 1f)

        val needSpatial = sharp > 0.005f || abs(clarity) > 0.005f
        val srcR = if (needSpatial) rBuf.copyOf() else null
        val srcG = if (needSpatial) gBuf.copyOf() else null
        val srcB = if (needSpatial) bBuf.copyOf() else null

        for (y in 0 until height) {
            val rowOff = y * width
            for (x in 0 until width) {
                val idx = rowOff + x
                val w = maskAlpha[idx]
                if (w <= 0.0005f) continue

                val origR = rBuf[idx]
                val origG = gBuf[idx]
                val origB = bBuf[idx]

                // 1. Linear Exposure + White Balance (Temperature & Tint)
                var rl = srgbToLinear(origR)
                var gl = srgbToLinear(origG)
                var bl = srgbToLinear(origB)

                if (abs(mask.exposure) > 0.001f) {
                    rl *= expGain
                    gl *= expGain
                    bl *= expGain
                }
                if (abs(temp) > 0.001f || abs(tint) > 0.001f) {
                    val rMul = (1f + temp * 0.28f + tint * 0.14f).coerceIn(0.45f, 1.85f)
                    val gMul = (1f - tint * 0.22f).coerceIn(0.45f, 1.85f)
                    val bMul = (1f - temp * 0.28f + tint * 0.14f).coerceIn(0.45f, 1.85f)
                    val norm = 1f / (0.2126f * rMul + 0.7152f * gMul + 0.0722f * bMul)
                    rl *= rMul * norm
                    gl *= gMul * norm
                    bl *= bMul * norm
                }

                // Soft highlight rolloff back into display space
                var r = linearToSrgb(softClipLinear(rl))
                var g = linearToSrgb(softClipLinear(gl))
                var b = linearToSrgb(softClipLinear(bl))

                // 2. Contrast + Highlights + Shadows in display tonal space
                if (abs(con) > 0.001f) {
                    val k = 1f + con * 0.65f
                    r = ((r - 0.5f) * k + 0.5f).coerceIn(0f, 1f)
                    g = ((g - 0.5f) * k + 0.5f).coerceIn(0f, 1f)
                    b = ((b - 0.5f) * k + 0.5f).coerceIn(0f, 1f)
                }
                if (abs(hi) > 0.001f || abs(sh) > 0.001f) {
                    val yLuma = luma709(r, g, b)
                    val hiMask = smoothstep(0.45f, 0.98f, yLuma)
                    val shMask = 1f - smoothstep(0.02f, 0.55f, yLuma)
                    val delta = hi * 0.32f * hiMask + sh * 0.34f * shMask
                    r = (r + delta).coerceIn(0f, 1f)
                    g = (g + delta).coerceIn(0f, 1f)
                    b = (b + delta).coerceIn(0f, 1f)
                }

                // 3. Saturation
                if (abs(sat) > 0.001f) {
                    val yLuma = luma709(r, g, b)
                    val sMul = (1f + sat).coerceIn(0f, 2.2f)
                    r = (yLuma + (r - yLuma) * sMul).coerceIn(0f, 1f)
                    g = (yLuma + (g - yLuma) * sMul).coerceIn(0f, 1f)
                    b = (yLuma + (b - yLuma) * sMul).coerceIn(0f, 1f)
                }

                // 4. Spatial Sharpness (USM) & Clarity (midtone local contrast)
                if (needSpatial && srcR != null && srcG != null && srcB != null) {
                    val xm1 = max(0, x - 1)
                    val xp1 = min(width - 1, x + 1)
                    val ym1 = max(0, y - 1)
                    val yp1 = min(height - 1, y + 1)
                    val xm3 = max(0, x - 3)
                    val xp3 = min(width - 1, x + 3)
                    val ym3 = max(0, y - 3)
                    val yp3 = min(height - 1, y + 3)

                    val centerL = luma709(srcR[idx], srcG[idx], srcB[idx])
                    if (sharp > 0.005f) {
                        val nL = (
                            luma709(srcR[rowOff + xm1], srcG[rowOff + xm1], srcB[rowOff + xm1]) +
                                luma709(srcR[rowOff + xp1], srcG[rowOff + xp1], srcB[rowOff + xp1]) +
                                luma709(srcR[ym1 * width + x], srcG[ym1 * width + x], srcB[ym1 * width + x]) +
                                luma709(srcR[yp1 * width + x], srcG[yp1 * width + x], srcB[yp1 * width + x])
                            ) * 0.25f
                        val usm = (centerL - nL) * (sharp * 1.15f)
                        r = (r + usm).coerceIn(0f, 1f)
                        g = (g + usm).coerceIn(0f, 1f)
                        b = (b + usm).coerceIn(0f, 1f)
                    }
                    if (abs(clarity) > 0.005f) {
                        val wideL = (
                            luma709(srcR[rowOff + xm3], srcG[rowOff + xm3], srcB[rowOff + xm3]) +
                                luma709(srcR[rowOff + xp3], srcG[rowOff + xp3], srcB[rowOff + xp3]) +
                                luma709(srcR[ym3 * width + x], srcG[ym3 * width + x], srcB[ym3 * width + x]) +
                                luma709(srcR[yp3 * width + x], srcG[yp3 * width + x], srcB[yp3 * width + x])
                            ) * 0.25f
                        val midWeight = (1f - abs(centerL * 2f - 1f)).coerceIn(0f, 1f)
                        val clarDelta = (centerL - wideL) * (clarity * 0.72f) * (0.35f + 0.65f * midWeight)
                        r = (r + clarDelta).coerceIn(0f, 1f)
                        g = (g + clarDelta).coerceIn(0f, 1f)
                        b = (b + clarDelta).coerceIn(0f, 1f)
                    }
                }

                // Alpha-blend adjusted result with unmasked pixel
                rBuf[idx] = origR + (r - origR) * w
                gBuf[idx] = origG + (g - origG) * w
                bBuf[idx] = origB + (b - origB) * w
            }
        }
    }

    private fun softClipLinear(v: Float): Float {
        if (v <= 0f) return 0f
        if (v <= 0.85f) return v
        val excess = v - 0.85f
        return 0.85f + 0.15f * (excess / (excess + 0.15f))
    }

    /**
     * Composites a 50% opacity Rubylith red (#FF2A2A) overlay over [source] according to [maskAlpha].
     * Used by CPU fallback preview & tests; GPU preview uses `MaskRubylithOverlayShader`.
     */
    fun applyRubylithOverlayBitmap(source: Bitmap, mask: LocalMask): Bitmap {
        val w = source.width
        val h = source.height
        val n = w * h
        val pixels = IntArray(n)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        val rBuf = FloatArray(n)
        val gBuf = FloatArray(n)
        val bBuf = FloatArray(n)
        for (i in 0 until n) {
            val px = pixels[i]
            rBuf[i] = ((px ushr 16) and 0xFF) / 255f
            gBuf[i] = ((px ushr 8) and 0xFF) / 255f
            bBuf[i] = (px and 0xFF) / 255f
        }
        val alpha = computeMaskAlphaBuffer(mask, rBuf, gBuf, bBuf, w, h)
        for (i in 0 until n) {
            val mixA = (alpha[i] * RUBYLITH_OPACITY).coerceIn(0f, RUBYLITH_OPACITY)
            if (mixA > 0.001f) {
                val nr = ((rBuf[i] * (1f - mixA) + RUBYLITH_R * mixA) * 255f).roundToInt().coerceIn(0, 255)
                val ng = ((gBuf[i] * (1f - mixA) + RUBYLITH_G * mixA) * 255f).roundToInt().coerceIn(0, 255)
                val nb = ((bBuf[i] * (1f - mixA) + RUBYLITH_B * mixA) * 255f).roundToInt().coerceIn(0, 255)
                val a = (pixels[i] ushr 24) and 0xFF
                pixels[i] = Color.argb(a, nr, ng, nb)
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }
}
