package com.example.editor

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import com.example.lut.CubeLut
import com.example.lut.LutSourceFormat
import com.example.lut.LutType
import com.example.nativebridge.NativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

object ColorGradingEngine {

    private const val HIST_BINS = 48
    private val HSL_BAND_CENTERS = floatArrayOf(0f, 30f, 60f, 120f, 180f, 240f, 285f, 330f)
    private val HSL_BAND_SPANS = floatArrayOf(45f, 45f, 45f, 55f, 55f, 55f, 55f, 55f)
    private val LENS_BLUR_TAPS = arrayOf(
        floatArrayOf(-0.326f, -0.406f),
        floatArrayOf(-0.840f, -0.074f),
        floatArrayOf(-0.696f,  0.457f),
        floatArrayOf(-0.203f,  0.621f),
        floatArrayOf( 0.962f, -0.195f),
        floatArrayOf( 0.473f, -0.480f),
        floatArrayOf( 0.519f,  0.767f),
        floatArrayOf( 0.185f, -0.893f),
        floatArrayOf( 0.507f,  0.064f),
        floatArrayOf( 0.896f,  0.412f),
        floatArrayOf(-0.322f, -0.933f),
        floatArrayOf(-0.792f, -0.598f)
    )

    suspend fun computePreviewHistogram(
        histSource: Bitmap,
        params: GradingParams,
        lut: CubeLut?
    ): HistogramData = withContext(Dispatchers.Default) {
        if (histSource.isRecycled || histSource.width <= 0 || histSource.height <= 0) {
            return@withContext emptyHistogram()
        }
        val gradedThumb = processBitmapInternal(histSource, params, lut)
        try {
            computeHistogram(gradedThumb)
        } finally {
            if (gradedThumb !== histSource && !gradedThumb.isRecycled) {
                gradedThumb.recycle()
            }
        }
    }

    suspend fun processPreview(
        source: Bitmap,
        params: GradingParams,
        lut: CubeLut?
    ): GradedResult = withContext(Dispatchers.Default) {
        val graded = processBitmapInternal(source, params, lut)
        val hist = computeHistogram(graded)
        GradedResult(graded, hist)
    }

    suspend fun processFullResolution(
        source: Bitmap,
        params: GradingParams,
        lut: CubeLut?
    ): Bitmap = withContext(Dispatchers.Default) {
        processBitmapInternal(source, params, lut)
    }

    suspend fun computeAutoToneParams(
        source: Bitmap,
        currentParams: GradingParams = GradingParams()
    ): GradingParams = withContext(Dispatchers.Default) {
        if (source.isRecycled) return@withContext currentParams
        val w = source.width
        val h = source.height
        val total = w * h
        if (w <= 0 || h <= 0 || total <= 0) return@withContext currentParams

        val pixels = IntArray(total)
        source.getPixels(pixels, 0, w, 0, 0, w, h)

        val lumaHist = IntArray(256)
        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        var sumSat = 0.0
        var count = 0

        val step = max(1, total / 24_000)
        var i = 0
        while (i < total) {
            val px = pixels[i]
            val r = ((px ushr 16) and 0xFF) / 255f
            val g = ((px ushr 8) and 0xFF) / 255f
            val b = (px and 0xFF) / 255f
            val luma = (0.2126f * r + 0.7152f * g + 0.0722f * b).coerceIn(0f, 1f)
            lumaHist[(luma * 255f + 0.5f).toInt().coerceIn(0, 255)]++
            sumR += r
            sumG += g
            sumB += b
            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))
            if (mx > 0.01f) sumSat += (mx - mn) / mx
            count++
            i += step
        }

        if (count == 0) return@withContext currentParams

        fun percentile(frac: Float): Float {
            val target = (count * frac).toInt()
            var acc = 0
            for (bin in 0..255) {
                acc += lumaHist[bin]
                if (acc >= target) return bin / 255f
            }
            return 1f
        }

        val p02 = percentile(0.02f)
        val p15 = percentile(0.15f)
        val p50 = percentile(0.50f)
        val p85 = percentile(0.85f)
        val p98 = percentile(0.98f)

        // Target mid-gray around 0.46 while protecting highlights/shadows
        val safeMedian = p50.coerceIn(0.08f, 0.90f)
        val rawExposure = (ln(0.46 / safeMedian) / ln(2.0)).toFloat().coerceIn(-1.15f, 1.15f)
        val spread = (p85 - p15).coerceAtLeast(0.05f)
        val autoContrast = (((0.52f - spread) / 0.52f) * 32f).coerceIn(-18f, 26f)

        val autoHighlights = if (p98 > 0.88f) -((p98 - 0.85f) * 260f).coerceIn(6f, 45f) else 0f
        val autoShadows = if (p15 < 0.22f) ((0.22f - p15) * 220f).coerceIn(6f, 42f) else 0f
        val autoWhites = if (p98 < 0.92f) ((0.92f - p98) * 140f).coerceIn(0f, 24f) else -8f
        val autoBlacks = if (p02 > 0.06f) -((p02 - 0.03f) * 180f).coerceIn(4f, 24f) else 4f

        // Gentle gray-world color balance correction (dampened 35% to keep natural mood)
        val avgR = (sumR / count).toFloat()
        val avgG = (sumG / count).toFloat()
        val avgB = (sumB / count).toFloat()
        val avgGray = (avgR + avgG + avgB) / 3f
        val tempDelta = ((avgB - avgR) / avgGray.coerceAtLeast(0.05f)) * 38f
        val tintDelta = ((avgG - (avgR + avgB) * 0.5f) / avgGray.coerceAtLeast(0.05f)) * 28f

        val avgSat = (sumSat / count).toFloat()
        val autoVibrance = ((0.38f - avgSat) * 55f).coerceIn(-8f, 24f)

        currentParams.copy(
            exposure = (rawExposure * 100f).toInt() / 100f,
            contrast = autoContrast,
            highlights = autoHighlights,
            shadows = autoShadows,
            whites = autoWhites,
            blacks = autoBlacks,
            temperature = tempDelta.coerceIn(-25f, 25f),
            tint = tintDelta.coerceIn(-20f, 20f),
            vibrance = autoVibrance,
            clarity = 10f
        )
    }


    suspend fun computeAutoWhiteBalance(source: Bitmap): Pair<Float, Float> = withContext(Dispatchers.Default) {
        if (source.isRecycled) return@withContext 0f to 0f
        val w = source.width
        val h = source.height
        val total = w * h
        if (w <= 0 || h <= 0 || total <= 0) return@withContext 0f to 0f

        val pixels = IntArray(total)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        var count = 0
        val step = max(1, total / 24_000)
        var i = 0
        while (i < total) {
            val px = pixels[i]
            val r = ((px ushr 16) and 0xFF) / 255f
            val g = ((px ushr 8) and 0xFF) / 255f
            val b = (px and 0xFF) / 255f
            val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
            if (luma in 0.08f..0.92f) {
                sumR += r
                sumG += g
                sumB += b
                count++
            }
            i += step
        }
        if (count == 0) return@withContext 0f to 0f
        val avgR = (sumR / count).toFloat()
        val avgG = (sumG / count).toFloat()
        val avgB = (sumB / count).toFloat()
        val avgGray = ((avgR + avgG + avgB) / 3f).coerceAtLeast(0.05f)
        val temp = (((avgB - avgR) / avgGray) * 95f).coerceIn(-65f, 65f)
        val tint = (((avgG - (avgR + avgB) * 0.5f) / avgGray) * 75f).coerceIn(-55f, 55f)
        temp to tint
    }

    data class EyedropperSample(
        val r: Float,
        val g: Float,
        val b: Float,
        val hueDeg: Float,
        val sat: Float,
        val luma: Float,
        val swatchArgb: Long
    )

    suspend fun sampleNormalizedPatch(
        source: Bitmap,
        nx: Float,
        ny: Float,
        paramsForRenderedSample: GradingParams? = null
    ): EyedropperSample = withContext(Dispatchers.Default) {
        if (source.isRecycled || source.width <= 0 || source.height <= 0) {
            return@withContext EyedropperSample(0.5f, 0.5f, 0.5f, 0f, 0f, 0.5f, 0xFF808080L)
        }
        val w = source.width.coerceAtLeast(1)
        val h = source.height.coerceAtLeast(1)
        val cx = (nx.coerceIn(0f, 1f) * (w - 1)).roundToInt().coerceIn(0, w - 1)
        val cy = (ny.coerceIn(0f, 1f) * (h - 1)).roundToInt().coerceIn(0, h - 1)

        val x0 = max(0, cx - 3)
        val y0 = max(0, cy - 3)
        val pw = (min(w - 1, cx + 3) - x0 + 1).coerceAtLeast(1)
        val ph = (min(h - 1, cy + 3) - y0 + 1).coerceAtLeast(1)

        var patch: Bitmap? = null
        var evaluatedPatch: Bitmap? = null
        val pxCount = pw * ph
        val pixels = IntArray(pxCount)
        try {
            val localPatch = Bitmap.createBitmap(source, x0, y0, pw, ph)
            patch = localPatch
            val evaluated = if (paramsForRenderedSample != null) {
                processBitmapInternal(
                    localPatch,
                    paramsForRenderedSample.copy(pointColor = PointColorData()),
                    null
                )
            } else {
                localPatch
            }
            evaluatedPatch = evaluated
            evaluated.getPixels(pixels, 0, pw, 0, 0, pw, ph)
        } finally {
            evaluatedPatch?.let { ep ->
                if (ep !== patch && ep !== source && !ep.isRecycled) ep.recycle()
            }
            patch?.let { p ->
                if (p !== source && !p.isRecycled) p.recycle()
            }
        }

        var rSum = 0f
        var gSum = 0f
        var bSum = 0f
        for (px in pixels) {
            rSum += ((px ushr 16) and 0xFF) / 255f
            gSum += ((px ushr 8) and 0xFF) / 255f
            bSum += (px and 0xFF) / 255f
        }
        val r = (rSum / pxCount).coerceIn(0f, 1f)
        val g = (gSum / pxCount).coerceIn(0f, 1f)
        val b = (bSum / pxCount).coerceIn(0f, 1f)
        val hsl = FloatArray(3)
        rgbToHsl(r, g, b, hsl)
        val ri = (r * 255f + 0.5f).roundToInt().coerceIn(0, 255)
        val gi = (g * 255f + 0.5f).roundToInt().coerceIn(0, 255)
        val bi = (b * 255f + 0.5f).roundToInt().coerceIn(0, 255)
        val argb = (0xFFL shl 24) or (ri.toLong() shl 16) or (gi.toLong() shl 8) or bi.toLong()

        EyedropperSample(
            r = r,
            g = g,
            b = b,
            hueDeg = hsl[0],
            sat = hsl[1],
            luma = hsl[2],
            swatchArgb = argb
        )
    }

    private suspend fun processBitmapInternal(
        source: Bitmap,
        params: GradingParams,
        lut: CubeLut?
    ): Bitmap = coroutineScope {
        if (source.isRecycled || source.width <= 0 || source.height <= 0) {
            throw IllegalStateException("Source image unavailable for grading.")
        }
        val rawW = source.width
        val rawH = source.height
        val rawTotal = rawW * rawH
        var rawPixels: IntArray? = IntArray(rawTotal)
        source.getPixels(rawPixels!!, 0, rawW, 0, 0, rawW, rawH)

        val effectiveLut = if (lut != null && lut.sourceFormat != LutSourceFormat.XMP) lut else null
        val applyLut = effectiveLut != null && params.lutIntensity > 0.001f
        if (params.isDefault && !applyLut) {
            val out = createBitmap(rawW, rawH)
            out.setPixels(rawPixels, 0, rawW, 0, 0, rawW, rawH)
            return@coroutineScope out
        }

        val crop = params.crop
        val hasGeomOrCrop = !crop.isDefault
        val outDims = if (hasGeomOrCrop) {
            GeometryMath.computeOutputDimensions(rawW, rawH, crop)
        } else {
            rawW to rawH
        }
        val width = outDims.first.coerceAtLeast(1)
        val height = outDims.second.coerceAtLeast(1)
        val total = width * height

        var oobMask: BooleanArray? = null
        var hasAnyOob = false
        var srcPixelsRef: IntArray? = if (!hasGeomOrCrop) {
            rawPixels
        } else {
            val eff = GeometryMath.effectiveCropRect(rawW, rawH, crop)
            val m = GeometryMath.buildOrientToRawSourceMatrix(rawW, rawH, crop)
            val hasMatrix = crop.hasOrientationEdits || crop.hasGeometryEdits
            val resampled = IntArray(total)
            val mask = BooleanArray(total)
            val cropW = eff.width
            val cropH = eff.height
            val m0 = m[0]; val m1 = m[1]; val m2 = m[2]
            val m3 = m[3]; val m4 = m[4]; val m5 = m[5]
            val m6 = m[6]; val m7 = m[7]; val m8 = m[8]
            val localRaw = rawPixels!!

            for (y in 0 until height) {
                val vy = (y + 0.5f) / height.toFloat()
                val ov = eff.top + vy * cropH
                val rowOff = y * width
                for (x in 0 until width) {
                    val vx = (x + 0.5f) / width.toFloat()
                    val ou = eff.left + vx * cropW
                    val su: Float
                    val sv: Float
                    val isOob: Boolean
                    if (!hasMatrix) {
                        su = ou.coerceIn(0f, 1f)
                        sv = ov.coerceIn(0f, 1f)
                        isOob = false
                    } else {
                        val w = m6 * ou + m7 * ov + m8
                        if (w <= 1e-4f) {
                            su = 0f
                            sv = 0f
                            isOob = true
                        } else {
                            su = (m0 * ou + m1 * ov + m2) / w
                            sv = (m3 * ou + m4 * ov + m5) / w
                            isOob = (su < 0f || su > 1f || sv < 0f || sv > 1f)
                        }
                    }
                    if (isOob) {
                        mask[rowOff + x] = true
                        hasAnyOob = true
                        resampled[rowOff + x] = 0xFF0B0E14.toInt()
                    } else {
                        resampled[rowOff + x] = sampleBilinear(localRaw, rawW, rawH, su, sv)
                    }
                }
            }
            oobMask = mask
            resampled
        }
        if (hasGeomOrCrop) {
            rawPixels = null
        }

        val activeMasks = params.masks
            .filter { it.enabled && it.hasAdjustments }
            .take(MAX_LOCAL_MASKS)
        val hasActiveMasks = activeMasks.isNotEmpty()
        val hasOtherEffectsEdits = params.texture != 0f ||
            params.clarity != 0f ||
            params.dehaze != 0f ||
            params.vignette != 0f ||
            params.grain > 0f
        val hasHalationOrBloom = params.halation > 0.01f || params.bloom > 0.01f
        val hasChromaticAberration = abs(params.chromaticAberration) > 0.01f
        val hasAnamorphicStreak = params.anamorphicStreak > 0.01f
        val hasNonLutPixelEdits =
            params.hasProfileEdits ||
            params.hasLightEdits ||
            params.hasColorEdits ||
            hasOtherEffectsEdits ||
            params.hasDetailEdits ||
            params.hasOpticsEdits ||
            params.hasLensBlurEdits ||
            params.hasCalibrationEdits ||
            hasActiveMasks

        val onlyGeomOrCrop = hasGeomOrCrop &&
            !hasNonLutPixelEdits &&
            !applyLut &&
            !hasHalationOrBloom &&
            !hasChromaticAberration &&
            !hasAnamorphicStreak
        if (onlyGeomOrCrop) {
            val pixelsToWrite = srcPixelsRef!!
            srcPixelsRef = null
            val out = createBitmap(width, height)
            out.setPixels(pixelsToWrite, 0, width, 0, 0, width, height)
            return@coroutineScope out
        }

        val lutMix = if (applyLut) params.lutIntensity.coerceIn(0f, 1f) else 0f
        val preferNative3dLut = applyLut &&
            effectiveLut!!.type == LutType.LUT_3D &&
            lutMix > 0.001f &&
            !hasAnyOob &&
            NativeBridge.isAvailable

        if (!hasNonLutPixelEdits && (!applyLut || preferNative3dLut)) {
            val out = createBitmap(width, height)
            out.setPixels(srcPixelsRef!!, 0, width, 0, 0, width, height)
            val lutOk = if (preferNative3dLut && effectiveLut != null) {
                tryApplyLut3dNative(out, effectiveLut, lutMix)
            } else {
                true
            }
            if (lutOk) {
                if (hasChromaticAberration) {
                    applyChromaticAberrationInPlace(out, params.chromaticAberration)
                }
                if (hasHalationOrBloom) {
                    applyHalationBloomInPlace(out, params.halation, params.bloom)
                }
                if (hasAnamorphicStreak) {
                    applyAnamorphicStreakInPlace(out, params.anamorphicStreak)
                }
                srcPixelsRef = null
                rawPixels = null
                oobMask = null
                return@coroutineScope out
            }
            if (!out.isRecycled) out.recycle()
        }

        val srcPixels = srcPixelsRef!!
        val dstPixels = IntArray(total)

        val hasOptics = params.lensDistortion != 0f
        val hasSpatialDetail = params.texture != 0f ||
            params.clarity != 0f ||
            params.sharpening > 0f ||
            params.noiseReduction > 0f ||
            params.colorNoiseReduction > 0f

        val distortionK = -(params.lensDistortion / 100f) * 0.35f
        val lensBlurNorm = (params.lensBlurAmount / 100f).coerceIn(0f, 1f)
        val bokehFocusNorm = (params.bokehFocus / 100f).coerceIn(0f, 1f)
        val minDim = min(width, height).toFloat()
        val blurNormX = if (width > 0) (minDim / width.toFloat()) * (lensBlurNorm * 0.018f) else 0f
        val blurNormY = if (height > 0) (minDim / height.toFloat()) * (lensBlurNorm * 0.018f) else 0f

        val expMult = 2.0.pow(params.exposure.toDouble() * 1.15).toFloat()
        val contrastNorm = params.contrast / 100f
        val contrastFactor = if (contrastNorm >= 0f) {
            1f + contrastNorm * 1.25f
        } else {
            (1f + contrastNorm * 0.85f).coerceAtLeast(0.15f)
        }
        val hiStrength = (params.highlights / 100f) * 0.88f
        val shStrength = (params.shadows / 100f) * 0.88f
        val blackLift = (params.blacks / 100f) * 0.28f
        val whiteGain = 1f + (params.whites / 100f) * 0.45f
        val hasParametric = params.parametricShadows != 0f ||
            params.parametricDarks != 0f ||
            params.parametricLights != 0f ||
            params.parametricHighlights != 0f

        val tempNorm = params.temperature / 100f
        val tintNorm = params.tint / 100f
        val rTempMult = (1f + tempNorm * 0.44f + tintNorm * 0.16f).coerceAtLeast(0.15f)
        val gTempMult = (1f + tempNorm * 0.06f - tintNorm * 0.34f).coerceAtLeast(0.15f)
        val bTempMult = (1f - tempNorm * 0.46f + tintNorm * 0.16f).coerceAtLeast(0.15f)

        val dehazeNorm = params.dehaze / 100f
        val satNorm = params.saturation / 100f
        val satFactor = if (satNorm >= 0f) {
            1f + satNorm * 1.40f
        } else {
            (1f + satNorm).coerceAtLeast(0f)
        }
        val vibranceNorm = params.vibrance / 100f

        val hasCurve = !params.toneCurve.isDefault
        val masterCurveLut = if (hasCurve) params.toneCurve.buildMasterLut() else null
        val redCurveLut = if (hasCurve) params.toneCurve.buildRedLut() else null
        val greenCurveLut = if (hasCurve) params.toneCurve.buildGreenLut() else null
        val blueCurveLut = if (hasCurve) params.toneCurve.buildBlueLut() else null

        val hasHsl = !params.hsl.isDefault
        val hueAdj = FloatArray(8) { params.hsl.bands[it].hue }
        val satAdj = FloatArray(8) { params.hsl.bands[it].saturation }
        val lumAdj = FloatArray(8) { params.hsl.bands[it].luminance }

        val cal = params.calibration
        val hasCal = !cal.isDefault
        val calRedHue = cal.redHue / 100f
        val calRedSat = 1f + (cal.redSaturation / 100f) * 0.5f
        val calGreenHue = cal.greenHue / 100f
        val calGreenSat = 1f + (cal.greenSaturation / 100f) * 0.5f
        val calBlueHue = cal.blueHue / 100f
        val calBlueSat = 1f + (cal.blueSaturation / 100f) * 0.5f

        val hasSplitTone = params.splitShadowSat > 0f || params.splitHighlightSat > 0f
        val shTint = hueSatToRgbTint(params.splitShadowHue, params.splitShadowSat)
        val hiTint = hueSatToRgbTint(params.splitHighlightHue, params.splitHighlightSat)
        val balanceNorm = params.splitBalance / 100f
        val shBalanceMult = (1f - balanceNorm * 0.5f).coerceIn(0.25f, 1.75f)
        val hiBalanceMult = (1f + balanceNorm * 0.5f).coerceIn(0.25f, 1.75f)

        val texAmount = (params.texture / 100f) * 0.85f
        val clarityAmount = (params.clarity / 100f) * 1.05f
        val sharpNorm = (params.sharpening / 150f).coerceIn(0f, 1f)
        val sharpRadius = params.sharpenRadius.coerceIn(0.5f, 3.0f)
        val sharpDetailNorm = (params.sharpenDetail / 100f).coerceIn(0f, 1f)
        val sharpMaskNorm = (params.sharpenMasking / 100f).coerceIn(0f, 1f)
        val nrNorm = (params.noiseReduction / 100f).coerceIn(0f, 1f)
        val nrDetailNorm = (params.noiseReductionDetail / 100f).coerceIn(0f, 1f)
        val nrContrastNorm = (params.noiseReductionContrast / 100f).coerceIn(0f, 1f)
        val cnrNorm = (params.colorNoiseReduction / 100f).coerceIn(0f, 1f)
        val cnrDetailNorm = (params.colorNoiseReductionDetail / 100f).coerceIn(0f, 1f)
        val cnrSmoothNorm = (params.colorNoiseReductionSmoothness / 100f).coerceIn(0f, 1f)

        val vigNorm = (params.vignette / 100f) * 0.90f
        val vigMidpoint = (params.vignetteMidpoint / 100f).coerceIn(0f, 1f)
        val vigFeather = (params.vignetteFeather / 100f).coerceIn(0f, 1f)
        val vigRoundness = (params.vignetteRoundness / 100f).coerceIn(-1f, 1f)
        val vigStyleIdx = params.vignetteStyle.shaderIndex
        val vigHiProtect = (params.vignetteHighlights / 100f).coerceIn(0f, 1f)
        val grainStrength = (params.grain / 100f) * 0.16f
        val grainSizeNorm = (params.grainSize / 100f).coerceIn(0f, 1f)
        val grainRoughNorm = (params.grainRoughness / 100f).coerceIn(0f, 1f)
        val grainCellPx = 0.75f + (4.6f - 0.75f) * (grainSizeNorm * grainSizeNorm * 0.65f + grainSizeNorm * 0.35f)
        val minRes = max(1, min(width, height)).toFloat()
        val aspectX = width.toFloat() / minRes
        val aspectY = height.toFloat() / minRes

        val activeProfile = CameraProfiles.byId(params.profileId)
        val hasProfile = activeProfile.isCreative && params.profileAmount > 0.001f
        val profSt = params.profileAmount.coerceIn(0f, 2f)
        val hasProfSelective = activeProfile.foliageBoost != 0f ||
            activeProfile.skinSoftWarmth != 0f ||
            activeProfile.skyDepth != 0f

        val pc = params.pointColor
        val hasPointColor = pc.isModified

        val cg = params.colorGrading
        val hasColorGrading = !cg.isDefault
        fun buildWheelVec(w: ColorWheelValue): FloatArray {
            val tint = hueSatToRgbTint(w.hueDeg, w.saturation)
            val satGain = (w.saturation / 100f) * 0.42f
            val lumDelta = (w.luminance / 100f) * 0.22f
            return floatArrayOf(tint[0] * satGain, tint[1] * satGain, tint[2] * satGain, lumDelta)
        }
        val cgShadowVec = buildWheelVec(cg.shadows)
        val cgMidVec = buildWheelVec(cg.midtones)
        val cgHighlightVec = buildWheelVec(cg.highlights)
        val cgGlobalVec = buildWheelVec(cg.global)
        val cgBlend = (cg.blending / 100f).coerceIn(0f, 1f)
        val cgPivot = (0.5f + (cg.balance / 100f).coerceIn(-1f, 1f) * 0.24f).coerceIn(0.22f, 0.78f)
        val cgHalfW = 0.22f + (0.60f - 0.22f) * cgBlend

        val outputAspect = width.toFloat() / height.coerceAtLeast(1).toFloat()

        val workerCount = if (total > 80_000) 4 else 1
        val chunkRows = (height + workerCount - 1) / workerCount

        val resolvedOobMask = oobMask
        val jobs = (0 until workerCount).map { worker ->
            val yStart = worker * chunkRows
            val yEnd = min(height, yStart + chunkRows)
            async(Dispatchers.Default) {
                val lutOut = FloatArray(3)
                val hslWork = FloatArray(3)
                val rgbWork = FloatArray(3)

                for (y in yStart until yEnd) {
                    val v = (y + 0.5f) / height.toFloat()
                    val rowBase = y * width
                    for (x in 0 until width) {
                        val u = (x + 0.5f) / width.toFloat()
                        val idx = rowBase + x
                        if (resolvedOobMask != null && resolvedOobMask[idx]) {
                            dstPixels[idx] = 0xFF0B0E14.toInt()
                            continue
                        }

                        var a: Int
                        var r: Float
                        var g: Float
                        var b: Float

                        if (hasOptics) {
                            val dx = u - 0.5f
                            val dy = v - 0.5f
                            val r2 = dx * dx + dy * dy
                            val radialScale = 1f + distortionK * r2
                            val wu = (0.5f + dx * radialScale).coerceIn(0f, 1f)
                            val wv = (0.5f + dy * radialScale).coerceIn(0f, 1f)

                            val basePx = sampleBilinear(srcPixels, width, height, wu, wv)
                            a = basePx ushr 24
                            r = ((basePx ushr 16) and 0xFF) * (1f / 255f)
                            g = ((basePx ushr 8) and 0xFF) * (1f / 255f)
                            b = (basePx and 0xFF) * (1f / 255f)
                        } else {
                            val px = srcPixels[idx]
                            a = px ushr 24
                            r = ((px ushr 16) and 0xFF) * (1f / 255f)
                            g = ((px ushr 8) and 0xFF) * (1f / 255f)
                            b = (px and 0xFF) * (1f / 255f)
                        }

                        // 0. Lens Blur (radial depth-of-field bokeh disk blur)
                        if (lensBlurNorm > 0.001f) {
                            val dxB = u - 0.5f
                            val dyB = v - 0.5f
                            val radial = sqrt(dxB * dxB + dyB * dyB) * 1.4142135f
                            val focusRadius = bokehFocusNorm * 0.62f
                            val edge0 = focusRadius * 0.45f
                            val edge1 = focusRadius + 0.36f
                            val tBlur = ((radial - edge0) / (edge1 - edge0).coerceAtLeast(1e-4f)).coerceIn(0f, 1f)
                            val blurMask = tBlur * tBlur * (3f - 2f * tBlur)
                            if (blurMask > 0.01f) {
                                val rx = blurNormX * blurMask
                                val ry = blurNormY * blurMask
                                val cLuma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                                val cw = 1f + cLuma * cLuma * 1.4f
                                var accR = r * cw
                                var accG = g * cw
                                var accB = b * cw
                                var wSum = cw

                                for (tapIdx in LENS_BLUR_TAPS.indices) {
                                    val tap = LENS_BLUR_TAPS[tapIdx]
                                    val su = (u + tap[0] * rx).coerceIn(0f, 1f)
                                    val sv = (v + tap[1] * ry).coerceIn(0f, 1f)
                                    val spx = sampleBilinear(srcPixels, width, height, su, sv)
                                    val sr = ((spx ushr 16) and 0xFF) * (1f / 255f)
                                    val sg = ((spx ushr 8) and 0xFF) * (1f / 255f)
                                    val sb = (spx and 0xFF) * (1f / 255f)
                                    val sLuma = 0.2126f * sr + 0.7152f * sg + 0.0722f * sb
                                    val w = 1f + sLuma * sLuma * 1.6f
                                    accR += sr * w
                                    accG += sg * w
                                    accB += sb * w
                                    wSum += w
                                }
                                val invW = 1f / wSum.coerceAtLeast(1e-4f)
                                val mixAmt = (blurMask * 1.25f).coerceIn(0f, 1f)
                                r += (accR * invW - r) * mixAmt
                                g += (accG * invW - g) * mixAmt
                                b += (accB * invW - b) * mixAmt
                            }
                        }

                        // Spatial filters: Luminance NR, Color NR, Texture, Sharpening, Clarity
                        if (hasSpatialDetail) {
                            val xm1 = max(0, x - 1)
                            val xp1 = min(width - 1, x + 1)
                            val ym1 = max(0, y - 1)
                            val yp1 = min(height - 1, y + 1)

                            val pxL = srcPixels[rowBase + xm1]
                            val pxR = srcPixels[rowBase + xp1]
                            val pxU = srcPixels[ym1 * width + x]
                            val pxD = srcPixels[yp1 * width + x]

                            val rL = ((pxL ushr 16) and 0xFF) * (1f / 255f)
                            val gL = ((pxL ushr 8) and 0xFF) * (1f / 255f)
                            val bL = (pxL and 0xFF) * (1f / 255f)

                            val rR = ((pxR ushr 16) and 0xFF) * (1f / 255f)
                            val gR = ((pxR ushr 8) and 0xFF) * (1f / 255f)
                            val bR = (pxR and 0xFF) * (1f / 255f)

                            val rU = ((pxU ushr 16) and 0xFF) * (1f / 255f)
                            val gU = ((pxU ushr 8) and 0xFF) * (1f / 255f)
                            val bU = (pxU and 0xFF) * (1f / 255f)

                            val rD = ((pxD ushr 16) and 0xFF) * (1f / 255f)
                            val gD = ((pxD ushr 8) and 0xFF) * (1f / 255f)
                            val bD = (pxD and 0xFF) * (1f / 255f)

                            val avgR = (rL + rR + rU + rD) * 0.25f
                            val avgG = (gL + gR + gU + gD) * 0.25f
                            val avgB = (bL + bR + bU + bD) * 0.25f
                            val lumaNear = 0.2126f * avgR + 0.7152f * avgG + 0.0722f * avgB

                            val lcL = 0.2126f * rL + 0.7152f * gL + 0.0722f * bL
                            val lcR = 0.2126f * rR + 0.7152f * gR + 0.0722f * bR
                            val lcU = 0.2126f * rU + 0.7152f * gU + 0.0722f * bU
                            val lcD = 0.2126f * rD + 0.7152f * gD + 0.0722f * bD

                            // 1. Luminance Noise Reduction (Amount + Detail + Contrast)
                            if (nrNorm > 0.001f) {
                                val nrL = samplePixelBilinear(srcPixels, width, height, x - 2.5f, y.toFloat())
                                val nrR = samplePixelBilinear(srcPixels, width, height, x + 2.5f, y.toFloat())
                                val nrU = samplePixelBilinear(srcPixels, width, height, x.toFloat(), y - 2.5f)
                                val nrD = samplePixelBilinear(srcPixels, width, height, x.toFloat(), y + 2.5f)
                                val nrWideLuma = (
                                    pixelLuma(nrL) + pixelLuma(nrR) + pixelLuma(nrU) + pixelLuma(nrD)
                                    ) * 0.25f
                                val nrTargetLuma = nrWideLuma + (lumaNear - nrWideLuma) * (0.30f + nrDetailNorm * 0.50f)

                                val curLuma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                                val dLumaMin = min(
                                    min(abs(curLuma - lcL), abs(curLuma - lcR)),
                                    min(abs(curLuma - lcU), abs(curLuma - lcD))
                                )
                                val coherentEdge = max(abs(curLuma - nrTargetLuma) - dLumaMin * 0.85f, 0f)
                                val detailScale = 1.8f + (15.0f - 1.8f) * nrDetailNorm
                                val edgeProtect = (1f - coherentEdge * detailScale).coerceIn(0.08f, 1f)
                                val smoothedLuma = curLuma + (nrTargetLuma - curLuma) * (nrNorm * 0.84f * edgeProtect)
                                val contrastRestore = (smoothedLuma - nrWideLuma) * nrContrastNorm * nrNorm * 0.50f
                                val nrLumaDelta = (smoothedLuma + contrastRestore) - curLuma
                                r = (r + nrLumaDelta).coerceIn(0f, 1f)
                                g = (g + nrLumaDelta).coerceIn(0f, 1f)
                                b = (b + nrLumaDelta).coerceIn(0f, 1f)
                            }

                            // 2. Color Noise Reduction (Amount + Detail + Smoothness; preserves luminance Y)
                            if (cnrNorm > 0.001f) {
                                val curLumaCnr = 0.2126f * r + 0.7152f * g + 0.0722f * b
                                val chrC = r - curLumaCnr
                                val chgC = g - curLumaCnr
                                val chbC = b - curLumaCnr

                                val cRad = 1.4f + (4.5f - 1.4f) * cnrSmoothNorm
                                val cn0 = samplePixelBilinear(srcPixels, width, height, x - cRad, y - cRad)
                                val cn1 = samplePixelBilinear(srcPixels, width, height, x + cRad, y - cRad)
                                val cn2 = samplePixelBilinear(srcPixels, width, height, x - cRad, y + cRad)
                                val cn3 = samplePixelBilinear(srcPixels, width, height, x + cRad, y + cRad)

                                var sumChr = (avgR - lumaNear) * 2f
                                var sumChg = (avgG - lumaNear) * 2f
                                var sumChb = (avgB - lumaNear) * 2f
                                val cnSamples = intArrayOf(cn0, cn1, cn2, cn3)
                                for (k in 0..3) {
                                    val cpx = cnSamples[k]
                                    val cr = ((cpx ushr 16) and 0xFF) * (1f / 255f)
                                    val cg = ((cpx ushr 8) and 0xFF) * (1f / 255f)
                                    val cb = (cpx and 0xFF) * (1f / 255f)
                                    val cl = 0.2126f * cr + 0.7152f * cg + 0.0722f * cb
                                    sumChr += (cr - cl)
                                    sumChg += (cg - cl)
                                    sumChb += (cb - cl)
                                }
                                val avgChr = sumChr / 6f
                                val avgChg = sumChg / 6f
                                val avgChb = sumChb / 6f

                                fun chDist(nxR: Float, nxG: Float, nxB: Float): Float {
                                    val nl = 0.2126f * nxR + 0.7152f * nxG + 0.0722f * nxB
                                    val dr = chrC - (nxR - nl)
                                    val dg = chgC - (nxG - nl)
                                    val db = chbC - (nxB - nl)
                                    return sqrt(dr * dr + dg * dg + db * db)
                                }

                                val dChromaMin = min(
                                    min(chDist(rL, gL, bL), chDist(rR, gR, bR)),
                                    min(chDist(rU, gU, bU), chDist(rD, gD, bD))
                                )
                                val dAvg = sqrt(
                                    (chrC - avgChr) * (chrC - avgChr) +
                                        (chgC - avgChg) * (chgC - avgChg) +
                                        (chbC - avgChb) * (chbC - avgChb)
                                )
                                val chromaEdge = max(dAvg - dChromaMin * 0.85f, 0f)
                                val cnrScale = 0.25f + (4.2f - 0.25f) * cnrDetailNorm
                                val chromaProtect = (1f - chromaEdge * cnrScale).coerceIn(0.14f, 1f)
                                val cnrBlend = cnrNorm * 0.94f * chromaProtect

                                r = (curLumaCnr + chrC + (avgChr - chrC) * cnrBlend).coerceIn(0f, 1f)
                                g = (curLumaCnr + chgC + (avgChg - chgC) * cnrBlend).coerceIn(0f, 1f)
                                b = (curLumaCnr + chbC + (avgChb - chbC) * cnrBlend).coerceIn(0f, 1f)
                            }

                            val lumaCenter = 0.2126f * r + 0.7152f * g + 0.0722f * b
                            var localDelta = (lumaCenter - lumaNear) * texAmount

                            // 3. Sharpening (Amount + Radius + Detail + Masking)
                            if (sharpNorm > 0.001f) {
                                val sL = samplePixelBilinear(srcPixels, width, height, x - sharpRadius, y.toFloat())
                                val sR = samplePixelBilinear(srcPixels, width, height, x + sharpRadius, y.toFloat())
                                val sU = samplePixelBilinear(srcPixels, width, height, x.toFloat(), y - sharpRadius)
                                val sD = samplePixelBilinear(srcPixels, width, height, x.toFloat(), y + sharpRadius)

                                val lsL = pixelLuma(sL)
                                val lsR = pixelLuma(sR)
                                val lsU = pixelLuma(sU)
                                val lsD = pixelLuma(sD)
                                val lumaRing = (lsL + lsR + lsU + lsD) * 0.25f

                                val unsharpDelta = lumaCenter - lumaRing
                                val fineDelta = lumaCenter - lumaNear
                                val sharpSignal =
                                    unsharpDelta * (0.85f - 0.15f * sharpDetailNorm) +
                                        fineDelta * (0.15f + sharpDetailNorm * 0.95f)

                                var edgeMask = 1f
                                if (sharpMaskNorm > 0.001f) {
                                    val g1 = abs(lcR - lcL) + abs(lcD - lcU)
                                    val gR = abs(lsR - lsL) + abs(lsD - lsU)
                                    val edgeMag = max(g1, gR) * 0.5f
                                    val thresh = sharpMaskNorm * 0.18f
                                    edgeMask = smoothRamp(thresh * 0.45f, max(thresh * 1.45f, 0.002f), edgeMag)
                                }
                                localDelta += sharpSignal * sharpNorm * 1.45f * edgeMask
                            }

                            if (clarityAmount != 0f) {
                                val xm4 = max(0, x - 4)
                                val xp4 = min(width - 1, x + 4)
                                val ym4 = max(0, y - 4)
                                val yp4 = min(height - 1, y + 4)
                                val pW1 = srcPixels[rowBase + xm4]
                                val pW2 = srcPixels[rowBase + xp4]
                                val pW3 = srcPixels[ym4 * width + x]
                                val pW4 = srcPixels[yp4 * width + x]
                                val wideLuma = (
                                    pixelLuma(pW1) + pixelLuma(pW2) + pixelLuma(pW3) + pixelLuma(pW4)
                                    ) * 0.25f
                                val midMask = (1f - abs(lumaCenter - 0.5f) * 1.8f).coerceIn(0.2f, 1f)
                                localDelta += (lumaCenter - wideLuma) * clarityAmount * midMask
                            }

                            if (localDelta != 0f) {
                                r = (r + localDelta).coerceIn(0f, 1f)
                                g = (g + localDelta).coerceIn(0f, 1f)
                                b = (b + localDelta).coerceIn(0f, 1f)
                            }
                        }

                        // 0.5. Foundational Camera Profile (non-destructive base layer before user sliders)
                        if (hasProfile) {
                            r = (r * (1f + activeProfile.rGainDelta * profSt)).coerceIn(0f, 1f)
                            g = (g * (1f + activeProfile.gGainDelta * profSt)).coerceIn(0f, 1f)
                            b = (b * (1f + activeProfile.bGainDelta * profSt)).coerceIn(0f, 1f)

                            if (hasProfSelective) {
                                val lumaS = 0.2126f * r + 0.7152f * g + 0.0722f * b
                                val greenMask = ((g - max(r, b) * 0.88f) * 3f).coerceIn(0f, 1f)
                                val skyMask = ((b - max(r, g) * 0.92f) * 3f).coerceIn(0f, 1f)
                                val skinMask = ((r - b) * 2.2f).coerceIn(0f, 1f) *
                                    ((g - b * 0.9f) * 2f).coerceIn(0f, 1f) *
                                    (1f - abs(lumaS - 0.58f) * 1.4f).coerceIn(0f, 1f)

                                val foil = activeProfile.foliageBoost * profSt * greenMask
                                g = (g + foil * 0.07f).coerceIn(0f, 1f)
                                b = (b + foil * 0.03f).coerceIn(0f, 1f)

                                val skin = activeProfile.skinSoftWarmth * profSt * skinMask
                                r = (r + skin * 0.05f).coerceIn(0f, 1f)
                                g = (g + skin * 0.025f).coerceIn(0f, 1f)

                                val sky = activeProfile.skyDepth * profSt * skyMask
                                b = (b + sky * 0.06f).coerceIn(0f, 1f)
                                r = (r - sky * 0.025f).coerceIn(0f, 1f)
                            }

                            val monoT = (activeProfile.monoMix * profSt).coerceIn(0f, 1f)
                            if (monoT > 0.001f) {
                                val monoLuma = (r * activeProfile.monoWeightR +
                                    g * activeProfile.monoWeightG +
                                    b * activeProfile.monoWeightB).coerceIn(0f, 1f)
                                r += (monoLuma - r) * monoT
                                g += (monoLuma - g) * monoT
                                b += (monoLuma - b) * monoT
                            }

                            if (monoT < 0.999f) {
                                if (activeProfile.vibDelta != 0f) {
                                    val maxC = max(r, max(g, b))
                                    val minC = min(r, min(g, b))
                                    val vibMult = (1f + activeProfile.vibDelta * profSt * (1f - (maxC - minC)) * 0.85f).coerceAtLeast(0f)
                                    val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
                                    r = (lum + (r - lum) * vibMult).coerceIn(0f, 1f)
                                    g = (lum + (g - lum) * vibMult).coerceIn(0f, 1f)
                                    b = (lum + (b - lum) * vibMult).coerceIn(0f, 1f)
                                }
                                if (activeProfile.satDelta != 0f) {
                                    val satMult = (1f + activeProfile.satDelta * profSt).coerceAtLeast(0f)
                                    val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
                                    r = (lum + (r - lum) * satMult).coerceIn(0f, 1f)
                                    g = (lum + (g - lum) * satMult).coerceIn(0f, 1f)
                                    b = (lum + (b - lum) * satMult).coerceIn(0f, 1f)
                                }
                            }

                            val pLuma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                            val midMask = 4f * pLuma * (1f - pLuma)
                            val cGain = 1f + activeProfile.contrast * profSt + activeProfile.midPunch * profSt * midMask
                            r = ((r - 0.5f) * cGain + 0.5f).coerceIn(0f, 1f)
                            g = ((g - 0.5f) * cGain + 0.5f).coerceIn(0f, 1f)
                            b = ((b - 0.5f) * cGain + 0.5f).coerceIn(0f, 1f)

                            if (activeProfile.shadowLift != 0f) {
                                val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
                                val toe = (1f - lum).coerceIn(0f, 1f)
                                val toeW = activeProfile.shadowLift * profSt * (toe * toe)
                                r = (r + toeW).coerceIn(0f, 1f)
                                g = (g + toeW).coerceIn(0f, 1f)
                                b = (b + toeW).coerceIn(0f, 1f)
                            }
                            if (activeProfile.highlightCompress != 0f) {
                                val lum = (0.2126f * r + 0.7152f * g + 0.0722f * b).coerceIn(0f, 1f)
                                val hiW = activeProfile.highlightCompress * profSt * lum.pow(1.8f) * 0.35f
                                r = (r - hiW).coerceIn(0f, 1f)
                                g = (g - hiW).coerceIn(0f, 1f)
                                b = (b - hiW).coerceIn(0f, 1f)
                            }

                            val l2 = (0.2126f * r + 0.7152f * g + 0.0722f * b).coerceIn(0f, 1f)
                            val shCastW = (1f - l2).pow(1.6f) * profSt
                            val hiCastW = l2.pow(1.6f) * profSt
                            r = (r + activeProfile.shadowRedBias * shCastW + activeProfile.highlightRedBias * hiCastW).coerceIn(0f, 1f)
                            g = (g + activeProfile.shadowGreenBias * shCastW + activeProfile.highlightGreenBias * hiCastW).coerceIn(0f, 1f)
                            b = (b + activeProfile.shadowBlueBias * shCastW + activeProfile.highlightBlueBias * hiCastW).coerceIn(0f, 1f)
                        }

                        // 1. Exposure (in linear light) + Temperature + Tint
                        if (abs(expMult - 1f) > 0.0005f) {
                            r = linearToSrgbChan(srgbToLinearChan(r) * expMult)
                            g = linearToSrgbChan(srgbToLinearChan(g) * expMult)
                            b = linearToSrgbChan(srgbToLinearChan(b) * expMult)
                        }
                        if (tempNorm != 0f || tintNorm != 0f) {
                            r *= rTempMult
                            g *= gTempMult
                            b *= bTempMult
                        }
                        r = r.coerceIn(0f, 1f)
                        g = g.coerceIn(0f, 1f)
                        b = b.coerceIn(0f, 1f)

                        // 2. Dehaze (atmospheric contrast & shadow deepening)
                        if (dehazeNorm != 0f) {
                            val dContrast = (1f + dehazeNorm * 0.48f).coerceAtLeast(0.25f)
                            val dShift = -dehazeNorm * 0.11f
                            r = ((r - 0.5f) * dContrast + 0.5f + dShift).coerceIn(0f, 1f)
                            g = ((g - 0.5f) * dContrast + 0.5f + dShift).coerceIn(0f, 1f)
                            b = ((b - 0.5f) * dContrast + 0.5f + dShift).coerceIn(0f, 1f)
                            val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                            val dSat = (1f + dehazeNorm * 0.36f).coerceAtLeast(0f)
                            r = (luma + (r - luma) * dSat).coerceIn(0f, 1f)
                            g = (luma + (g - luma) * dSat).coerceIn(0f, 1f)
                            b = (luma + (b - luma) * dSat).coerceIn(0f, 1f)
                        }

                        // 3. Whites & Blacks (tonally tapered so negative Whites don't crush shadows)
                        if (blackLift != 0f || whiteGain != 1f) {
                            val wbLuma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                            val hiMask = smoothRamp(0.28f, 0.96f, wbLuma)
                            val shMask = 1f - smoothRamp(0.0f, 0.62f, wbLuma)
                            val shoulderPush = (whiteGain - 1f) * 0.22f * hiMask * hiMask
                            val wr = r + (r * whiteGain - r) * hiMask + shoulderPush
                            val wg = g + (g * whiteGain - g) * hiMask + shoulderPush
                            val wb = b + (b * whiteGain - b) * hiMask + shoulderPush
                            r = (wr + blackLift * shMask).coerceIn(0f, 1f)
                            g = (wg + blackLift * shMask).coerceIn(0f, 1f)
                            b = (wb + blackLift * shMask).coerceIn(0f, 1f)
                        }

                        // 4. Highlights, Shadows & Parametric tones
                        if (hiStrength != 0f || shStrength != 0f || hasParametric) {
                            val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                            var delta = 0f
                            if (shStrength != 0f) {
                                val shT = ((0.64f - luma) / 0.64f).coerceIn(0f, 1f)
                                delta += shStrength * (shT * shT * (3f - 2f * shT)) * 0.56f
                            }
                            if (hiStrength != 0f) {
                                val hiT = ((luma - 0.36f) / 0.64f).coerceIn(0f, 1f)
                                delta += hiStrength * (hiT * hiT * (3f - 2f * hiT)) * 0.56f
                            }
                            if (hasParametric) {
                                delta += (
                                    (params.parametricShadows / 100f) * bandWeight(luma, 0.12f, 0.28f) +
                                        (params.parametricDarks / 100f) * bandWeight(luma, 0.35f, 0.26f) +
                                        (params.parametricLights / 100f) * bandWeight(luma, 0.65f, 0.26f) +
                                        (params.parametricHighlights / 100f) * bandWeight(luma, 0.88f, 0.28f)
                                    ) * 0.22f
                            }
                            if (delta != 0f) {
                                r = (r + delta).coerceIn(0f, 1f)
                                g = (g + delta).coerceIn(0f, 1f)
                                b = (b + delta).coerceIn(0f, 1f)
                            }
                        }

                        // 5. Contrast
                        if (contrastFactor != 1f) {
                            r = ((r - 0.5f) * contrastFactor + 0.5f).coerceIn(0f, 1f)
                            g = ((g - 0.5f) * contrastFactor + 0.5f).coerceIn(0f, 1f)
                            b = ((b - 0.5f) * contrastFactor + 0.5f).coerceIn(0f, 1f)
                        }

                        // 6. Tone Curves (Master + R/G/B)
                        if (hasCurve) {
                            if (masterCurveLut != null) {
                                r = sampleCurveLut(masterCurveLut, r)
                                g = sampleCurveLut(masterCurveLut, g)
                                b = sampleCurveLut(masterCurveLut, b)
                            }
                            if (redCurveLut != null) r = sampleCurveLut(redCurveLut, r)
                            if (greenCurveLut != null) g = sampleCurveLut(greenCurveLut, g)
                            if (blueCurveLut != null) b = sampleCurveLut(blueCurveLut, b)
                        }

                        // 7. RGB Primary Calibration (row-normalized to preserve neutral grays)
                        if (hasCal) {
                            val rShift = calRedHue * 0.18f
                            val gShift = calGreenHue * 0.18f
                            val bShift = calBlueHue * 0.18f
                            val r00 = 1f - abs(rShift) * 0.25f
                            val r01 = max(0f, -gShift)
                            val r02 = max(0f, bShift)
                            val nr = (r * r00 + g * r01 + b * r02) / max(r00 + r01 + r02, 0.001f)

                            val g10 = max(0f, rShift)
                            val g11 = 1f - abs(gShift) * 0.25f
                            val g12 = max(0f, -bShift)
                            val ng = (r * g10 + g * g11 + b * g12) / max(g10 + g11 + g12, 0.001f)

                            val b20 = max(0f, -rShift)
                            val b21 = max(0f, gShift)
                            val b22 = 1f - abs(bShift) * 0.25f
                            val nb = (r * b20 + g * b21 + b * b22) / max(b20 + b21 + b22, 0.001f)

                            val luma = 0.2126f * nr + 0.7152f * ng + 0.0722f * nb
                            r = (luma + (nr - luma) * calRedSat).coerceIn(0f, 1f)
                            g = (luma + (ng - luma) * calGreenSat).coerceIn(0f, 1f)
                            b = (luma + (nb - luma) * calBlueSat).coerceIn(0f, 1f)
                        }

                        // 8. 8-Band HSL Color Mixer
                        if (hasHsl && !params.isGrayscale) {
                            applyHslBands(r, g, b, hueAdj, satAdj, lumAdj, hslWork, rgbWork)
                            r = rgbWork[0]
                            g = rgbWork[1]
                            b = rgbWork[2]
                        }

                        // 8.5. Point Color (targeted single-color isolation)
                        if (hasPointColor && !params.isGrayscale) {
                            rgbToHsl(r, g, b, hslWork)
                            val pSat = hslWork[1]
                            if (pSat > 0.01f) {
                                val pHue = hslWork[0]
                                val pLum = hslWork[2]
                                val rangeNorm = (pc.range / 100f).coerceIn(0f, 1f)
                                var dh = abs(pHue - pc.sourceHueDeg)
                                if (dh > 180f) dh = 360f - dh
                                val hueW = 14f + (75f - 14f) * rangeNorm
                                val hw = 1f - smoothRamp(0f, hueW, dh)
                                val satW = 0.20f + (0.88f - 0.20f) * rangeNorm
                                val sw = (1f - abs(pSat - pc.sourceSat) / satW).coerceIn(0f, 1f)
                                val lumW = 0.28f + (0.92f - 0.28f) * rangeNorm
                                val lw = (1f - abs(pLum - pc.sourceLuma) / lumW).coerceIn(0f, 1f)
                                val mask = hw * (0.45f + 0.55f * sw) * (0.55f + 0.45f * lw)
                                if (mask > 0.001f) {
                                    val newH = ((pHue + (pc.hueShift / 100f) * 45f * mask) % 360f + 360f) % 360f
                                    val newS = (pSat * (1f + (pc.saturationShift / 100f) * mask)).coerceIn(0f, 1f)
                                    val newL = (pLum + (pc.luminanceShift / 100f) * 0.25f * mask).coerceIn(0f, 1f)
                                    hslToRgb(newH, newS, newL, rgbWork)
                                    r = rgbWork[0]
                                    g = rgbWork[1]
                                    b = rgbWork[2]
                                }
                            }
                        }

                        // 9. Grayscale or Vibrance + Saturation
                        if (params.isGrayscale) {
                            val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                            r = luma
                            g = luma
                            b = luma
                        } else {
                            if (vibranceNorm != 0f) {
                                val maxC = max(r, max(g, b))
                                val minC = min(r, min(g, b))
                                val curSat = maxC - minC
                                val vibMult = (1f + vibranceNorm * (1f - curSat * 0.75f) * 1.35f).coerceAtLeast(0f)
                                val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                                r = (luma + (r - luma) * vibMult).coerceIn(0f, 1f)
                                g = (luma + (g - luma) * vibMult).coerceIn(0f, 1f)
                                b = (luma + (b - luma) * vibMult).coerceIn(0f, 1f)
                            }
                            if (satFactor != 1f) {
                                val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                                r = (luma + (r - luma) * satFactor).coerceIn(0f, 1f)
                                g = (luma + (g - luma) * satFactor).coerceIn(0f, 1f)
                                b = (luma + (b - luma) * satFactor).coerceIn(0f, 1f)
                            }
                        }

                        // 10. Split Toning
                        if (hasSplitTone) {
                            val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                            val shW = (1f - luma).pow(1.4f) * (params.splitShadowSat / 100f) * 0.35f * shBalanceMult
                            val hiW = luma.pow(1.4f) * (params.splitHighlightSat / 100f) * 0.35f * hiBalanceMult
                            r = (r + shTint[0] * shW + hiTint[0] * hiW).coerceIn(0f, 1f)
                            g = (g + shTint[1] * shW + hiTint[1] * hiW).coerceIn(0f, 1f)
                            b = (b + shTint[2] * shW + hiTint[2] * hiW).coerceIn(0f, 1f)
                        }

                        // 10.5. Color Grading (Shadows / Midtones / Highlights / Global Color Wheels)
                        if (hasColorGrading) {
                            val l = (0.2126f * r + 0.7152f * g + 0.0722f * b).coerceIn(0f, 1f)
                            val shW = 1f - smoothRamp(max(0f, cgPivot - cgHalfW), min(1f, cgPivot + cgHalfW * 0.35f), l)
                            val hiW = smoothRamp(max(0f, cgPivot - cgHalfW * 0.35f), min(1f, cgPivot + cgHalfW), l)
                            val dMid = (l - cgPivot) / max(cgHalfW * 0.9f, 0.16f)
                            val midW = exp(-dMid * dMid * 1.45f)

                            r = (r +
                                cgShadowVec[0] * shW * (1f - l * 0.35f) + cgShadowVec[3] * shW * 0.65f +
                                cgMidVec[0] * midW + cgMidVec[3] * midW * 0.75f +
                                cgHighlightVec[0] * hiW * (0.45f + l * 0.55f) + cgHighlightVec[3] * hiW * 0.65f +
                                cgGlobalVec[0] + cgGlobalVec[3]).coerceIn(0f, 1f)
                            g = (g +
                                cgShadowVec[1] * shW * (1f - l * 0.35f) + cgShadowVec[3] * shW * 0.65f +
                                cgMidVec[1] * midW + cgMidVec[3] * midW * 0.75f +
                                cgHighlightVec[1] * hiW * (0.45f + l * 0.55f) + cgHighlightVec[3] * hiW * 0.65f +
                                cgGlobalVec[1] + cgGlobalVec[3]).coerceIn(0f, 1f)
                            b = (b +
                                cgShadowVec[2] * shW * (1f - l * 0.35f) + cgShadowVec[3] * shW * 0.65f +
                                cgMidVec[2] * midW + cgMidVec[3] * midW * 0.75f +
                                cgHighlightVec[2] * hiW * (0.45f + l * 0.55f) + cgHighlightVec[3] * hiW * 0.65f +
                                cgGlobalVec[2] + cgGlobalVec[3]).coerceIn(0f, 1f)
                        }

                        // 10.8. Selective Local Masks (Radial & Linear gradients)
                        if (hasActiveMasks) {
                            for (mIdx in activeMasks.indices) {
                                val lm = activeMasks[mIdx]
                                val mw = evaluateLocalMaskWeight(lm, u, v, outputAspect)
                                if (mw <= 0.001f) continue
                                if (abs(lm.exposure) > 0.001f) {
                                    val em = 2.0.pow((lm.exposure * 1.15f * mw).toDouble()).toFloat()
                                    r = linearToSrgbChan(srgbToLinearChan(r) * em)
                                    g = linearToSrgbChan(srgbToLinearChan(g) * em)
                                    b = linearToSrgbChan(srgbToLinearChan(b) * em)
                                }
                                if (abs(lm.warmth) > 0.01f) {
                                    val wn = (lm.warmth / 100f) * mw
                                    r = (r * (1f + wn * 0.44f).coerceAtLeast(0.15f)).coerceIn(0f, 1f)
                                    g = (g * (1f + wn * 0.06f).coerceAtLeast(0.15f)).coerceIn(0f, 1f)
                                    b = (b * (1f - wn * 0.46f).coerceAtLeast(0.15f)).coerceIn(0f, 1f)
                                }
                                if (abs(lm.contrast) > 0.01f) {
                                    val cn = lm.contrast / 100f
                                    val cScale = if (cn >= 0f) 1.25f else 0.85f
                                    val cf = (1f + cn * cScale * mw).coerceAtLeast(0.15f)
                                    r = ((r - 0.5f) * cf + 0.5f).coerceIn(0f, 1f)
                                    g = ((g - 0.5f) * cf + 0.5f).coerceIn(0f, 1f)
                                    b = ((b - 0.5f) * cf + 0.5f).coerceIn(0f, 1f)
                                }
                                if (abs(lm.saturation) > 0.01f) {
                                    val ml = 0.2126f * r + 0.7152f * g + 0.0722f * b
                                    val sn = lm.saturation / 100f
                                    val satScale = if (sn >= 0f) 1.40f else 1f
                                    val sf = (1f + sn * satScale * mw).coerceAtLeast(0f)
                                    r = (ml + (r - ml) * sf).coerceIn(0f, 1f)
                                    g = (ml + (g - ml) * sf).coerceIn(0f, 1f)
                                    b = (ml + (b - ml) * sf).coerceIn(0f, 1f)
                                }
                                if (lm.sharpness > 0.01f) {
                                    val sOffX = 1.35f / width.coerceAtLeast(1)
                                    val sOffY = 1.35f / height.coerceAtLeast(1)
                                    val lC = pixelLuma(sampleBilinear(srcPixels, width, height, u, v))
                                    val lL = pixelLuma(sampleBilinear(srcPixels, width, height, (u - sOffX).coerceIn(0f, 1f), v))
                                    val lR = pixelLuma(sampleBilinear(srcPixels, width, height, (u + sOffX).coerceIn(0f, 1f), v))
                                    val lU = pixelLuma(sampleBilinear(srcPixels, width, height, u, (v - sOffY).coerceIn(0f, 1f)))
                                    val lD = pixelLuma(sampleBilinear(srcPixels, width, height, u, (v + sOffY).coerceIn(0f, 1f)))
                                    val avgNL = (lL + lR + lU + lD) * 0.25f
                                    val sDelta = (lC - avgNL) * (lm.sharpness / 100f) * 1.55f * mw
                                    r = (r + sDelta).coerceIn(0f, 1f)
                                    g = (g + sDelta).coerceIn(0f, 1f)
                                    b = (b + sDelta).coerceIn(0f, 1f)
                                }
                            }
                        }

                        // 11. Vignette (Highlight / Color / Paint + Midpoint, Feather, Roundness, Highlights)
                        if (abs(vigNorm) > 0.001f) {
                            val dNormX = (u - 0.5f) * 2f
                            val dNormY = (v - 0.5f) * 2f
                            val dSquareX = dNormX * aspectX
                            val dSquareY = dNormY * aspectY
                            val shapeMix = if (vigRoundness >= 0f) vigRoundness * 0.72f else 0f
                            val dShapeX = dNormX + (dSquareX - dNormX) * shapeMix
                            val dShapeY = dNormY + (dSquareY - dNormY) * shapeMix
                            val pExp = if (vigRoundness < 0f) 2f + (-vigRoundness) * 3f else 2f
                            var dist = (max(abs(dShapeX), 0.0001f).pow(pExp) + max(abs(dShapeY), 0.0001f).pow(pExp)).pow(1f / pExp)
                            if (vigRoundness < 0f) {
                                dist *= 1f + (-vigRoundness) * 0.18f
                            }
                            val midR = 0.16f + (1.14f - 0.16f) * vigMidpoint
                            val halfFeather = 0.025f + (0.96f - 0.025f) * vigFeather
                            val edge0 = max(midR - halfFeather * 0.5f, 0f)
                            val edge1 = max(midR + halfFeather * 0.5f, edge0 + 0.02f)
                            val tMask = ((dist - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
                            val mask = tMask * tMask * (3f - 2f * tMask)

                            val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
                            val tHi = ((lum - 0.52f) / (0.98f - 0.52f)).coerceIn(0f, 1f)
                            val hiFactor = tHi * tHi * (3f - 2f * tHi)
                            val protect = if (vigNorm < 0f) 1f - hiFactor * vigHiProtect * 0.88f else 1f

                            when (vigStyleIdx) {
                                2 -> { // Paint Overlay
                                    val target = if (vigNorm < 0f) 0f else 1f
                                    val amt = (abs(vigNorm) * mask * protect * 0.92f).coerceIn(0f, 0.95f)
                                    r = (r + (target - r) * amt).coerceIn(0f, 1f)
                                    g = (g + (target - g) * amt).coerceIn(0f, 1f)
                                    b = (b + (target - b) * amt).coerceIn(0f, 1f)
                                }
                                1 -> { // Color Priority
                                    val effectiveVig = vigNorm * mask * protect
                                    val targetLum = (lum * (1f + effectiveVig * 0.92f)).coerceIn(0f, 1f)
                                    val ratio = if (lum > 0.002f) targetLum / lum else (1f + effectiveVig)
                                    var cr = (r * ratio).coerceIn(0f, 1f)
                                    var cgCol = (g * ratio).coerceIn(0f, 1f)
                                    var cb = (b * ratio).coerceIn(0f, 1f)
                                    if (effectiveVig < 0f) {
                                        val chromaKeep = 1f + (-effectiveVig) * 0.16f
                                        cr = (targetLum + (cr - targetLum) * chromaKeep).coerceIn(0f, 1f)
                                        cgCol = (targetLum + (cgCol - targetLum) * chromaKeep).coerceIn(0f, 1f)
                                        cb = (targetLum + (cb - targetLum) * chromaKeep).coerceIn(0f, 1f)
                                    }
                                    r = cr
                                    g = cgCol
                                    b = cb
                                }
                                else -> { // Highlight Priority
                                    val vigGain = (1f + vigNorm * mask * protect).coerceIn(0.04f, 1.92f)
                                    var vr = r * vigGain
                                    var vg = g * vigGain
                                    var vb = b * vigGain
                                    if (vigNorm < 0f && vigHiProtect > 0.001f) {
                                        val floorKeep = 1f - mask * 0.20f
                                        val k = hiFactor * vigHiProtect
                                        vr += (max(vr, r * floorKeep) - vr) * k
                                        vg += (max(vg, g * floorKeep) - vg) * k
                                        vb += (max(vb, b * floorKeep) - vb) * k
                                    }
                                    r = vr.coerceIn(0f, 1f)
                                    g = vg.coerceIn(0f, 1f)
                                    b = vb.coerceIn(0f, 1f)
                                }
                            }
                        }

                        // 12. Film Grain (Amount + Size + Roughness clumping)
                        if (grainStrength > 0.0005f) {
                            val gxCoord = x.toFloat() / grainCellPx
                            val gyCoord = y.toFloat() / grainCellPx
                            val nFine = cpuSmoothNoise(gxCoord, gyCoord) - 0.5f
                            val nClump = cpuSmoothNoise(gxCoord * 0.42f + 17.3f, gyCoord * 0.42f + 9.2f) - 0.5f
                            val nCrisp = cpuGrainHash(kotlin.math.floor(gxCoord).toInt(), kotlin.math.floor(gyCoord).toInt()) - 0.5f
                            val uniformNoise = nFine + (nCrisp - nFine) * 0.35f
                            val clumpedNoise = nFine * 0.45f + nClump * 0.65f + nCrisp * 0.35f
                            val noise = uniformNoise + (clumpedNoise - uniformNoise) * grainRoughNorm
                            val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                            val midWeight = (1f - abs(luma - 0.5f) * 1.25f).coerceIn(0.32f, 1f)
                            val amp = 0.85f + (1.35f - 0.85f) * grainRoughNorm
                            val gDelta = noise * grainStrength * midWeight * amp
                            r = (r + gDelta).coerceIn(0f, 1f)
                            g = (g + gDelta).coerceIn(0f, 1f)
                            b = (b + gDelta).coerceIn(0f, 1f)
                        }

                        // 13. Custom .cube 3D/1D LUT + Intensity Mix (inline fallback if native LUT pass is skipped)
                        if (effectiveLut != null && lutMix > 0f && !preferNative3dLut) {
                            effectiveLut.sample(r, g, b, lutOut)
                            r += (lutOut[0] - r) * lutMix
                            g += (lutOut[1] - g) * lutMix
                            b += (lutOut[2] - b) * lutMix
                        }

                        val ri = (r * 255f + 0.5f).toInt().coerceIn(0, 255)
                        val gi = (g * 255f + 0.5f).toInt().coerceIn(0, 255)
                        val bi = (b * 255f + 0.5f).toInt().coerceIn(0, 255)
                        dstPixels[idx] = (a shl 24) or (ri shl 16) or (gi shl 8) or bi
                    }
                }
            }
        }
        jobs.awaitAll()
        rawPixels = null
        srcPixelsRef = null
        oobMask = null

        val out = createBitmap(width, height)
        out.setPixels(dstPixels, 0, width, 0, 0, width, height)
        if (preferNative3dLut && effectiveLut != null && lutMix > 0f) {
            if (!tryApplyLut3dNative(out, effectiveLut, lutMix)) {
                applyLutKotlinOnBitmap(out, effectiveLut, lutMix)
            }
        }
        if (hasChromaticAberration) {
            applyChromaticAberrationInPlace(out, params.chromaticAberration)
        }
        if (hasHalationOrBloom) {
            applyHalationBloomInPlace(out, params.halation, params.bloom)
        }
        if (hasAnamorphicStreak) {
            applyAnamorphicStreakInPlace(out, params.anamorphicStreak)
        }
        out
    }

    fun applyHalationBloomInPlace(
        bitmap: Bitmap,
        halation: Float,
        bloom: Float
    ) {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return
        val halNorm = (halation / 100f).coerceIn(0f, 1f)
        val bloomNorm = (bloom / 100f).coerceIn(0f, 1f)
        if (halNorm <= 0.0005f && bloomNorm <= 0.0005f) return

        val nativeApplied = if (NativeBridge.isAvailable && bitmap.config == Bitmap.Config.ARGB_8888) {
            try {
                NativeBridge.applyHalationBloomNative(bitmap, halNorm, bloomNorm)
            } catch (_: Throwable) {
                false
            }
        } else {
            false
        }
        if (!nativeApplied) {
            applyHalationBloomKotlin(bitmap, halNorm, bloomNorm)
        }
    }

    private fun applyHalationBloomKotlin(
        bitmap: Bitmap,
        halNorm: Float,
        bloomNorm: Float
    ) {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        applyHalationBloomPixels(pixels, w, h, halNorm, bloomNorm)
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    internal fun applyHalationBloomPixels(
        pixels: IntArray,
        w: Int,
        h: Int,
        halNorm: Float,
        bloomNorm: Float
    ) {
        if (w <= 0 || h <= 0 || pixels.size < w * h) return
        val hal = halNorm.coerceIn(0f, 1f)
        val blm = bloomNorm.coerceIn(0f, 1f)
        if (hal <= 0.0005f && blm <= 0.0005f) return

        val ds = (max(w, h) / 480).coerceIn(1, 4)
        val gw = max(1, (w + ds - 1) / ds)
        val gh = max(1, (h + ds - 1) / ds)
        val useHal = hal > 0.0005f
        val useBloom = blm > 0.0005f

        val halBuf = if (useHal) FloatArray(gw * gh * 3) else FloatArray(0)
        val bloomBuf = if (useBloom) FloatArray(gw * gh * 3) else FloatArray(0)
        val inv255 = 1f / 255f

        for (gy in 0 until gh) {
            val yStart = gy * ds
            val yEnd = min(h, yStart + ds)
            for (gx in 0 until gw) {
                val xStart = gx * ds
                val xEnd = min(w, xStart + ds)
                var sumR = 0f
                var sumG = 0f
                var sumB = 0f
                var cnt = 0
                for (y in yStart until yEnd) {
                    val rowOff = y * w
                    for (x in xStart until xEnd) {
                        val px = pixels[rowOff + x]
                        sumR += ((px ushr 16) and 0xFF)
                        sumG += ((px ushr 8) and 0xFF)
                        sumB += (px and 0xFF)
                        cnt++
                    }
                }
                val invCnt = inv255 / max(1, cnt)
                val r = sumR * invCnt
                val g = sumG * invCnt
                val b = sumB * invCnt
                val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
                val dstIdx = (gy * gw + gx) * 3

                if (useHal) {
                    val rawT = smoothRamp(0.65f, 0.96f, lum)
                    val tH = rawT * (0.45f + 0.55f * rawT)
                    halBuf[dstIdx + 0] = min(1f, (r * 1.12f + 0.14f) * tH)
                    halBuf[dstIdx + 1] = g * 0.30f * tH
                    halBuf[dstIdx + 2] = b * 0.07f * tH
                }
                if (useBloom) {
                    val rawT = smoothRamp(0.54f, 0.94f, lum)
                    val tB = rawT * (0.55f + 0.45f * rawT)
                    bloomBuf[dstIdx + 0] = r * tB
                    bloomBuf[dstIdx + 1] = g * tB
                    bloomBuf[dstIdx + 2] = b * tB
                }
            }
        }

        fun separableGaussian(buf: FloatArray, radius: Int) {
            if (radius <= 0 || gw <= 1 || gh <= 1 || buf.isEmpty()) return
            val sigma = max(0.8f, radius * 0.45f)
            val invTwoSigma2 = 1f / (2f * sigma * sigma)
            val kernel = FloatArray(radius * 2 + 1)
            var kSum = 0f
            for (i in -radius..radius) {
                val wt = exp(-(i * i).toFloat() * invTwoSigma2)
                kernel[i + radius] = wt
                kSum += wt
            }
            val invKSum = 1f / max(kSum, 1e-6f)
            for (i in kernel.indices) kernel[i] *= invKSum

            val tmp = FloatArray(buf.size)
            for (y in 0 until gh) {
                val rowBase = y * gw * 3
                for (x in 0 until gw) {
                    var accR = 0f
                    var accG = 0f
                    var accB = 0f
                    for (k in -radius..radius) {
                        val sx = (x + k).coerceIn(0, gw - 1)
                        val wt = kernel[k + radius]
                        val sIdx = rowBase + sx * 3
                        accR += buf[sIdx + 0] * wt
                        accG += buf[sIdx + 1] * wt
                        accB += buf[sIdx + 2] * wt
                    }
                    val dIdx = rowBase + x * 3
                    tmp[dIdx + 0] = accR
                    tmp[dIdx + 1] = accG
                    tmp[dIdx + 2] = accB
                }
            }
            for (y in 0 until gh) {
                val dstRowBase = y * gw * 3
                for (x in 0 until gw) {
                    var accR = 0f
                    var accG = 0f
                    var accB = 0f
                    for (k in -radius..radius) {
                        val sy = (y + k).coerceIn(0, gh - 1)
                        val wt = kernel[k + radius]
                        val sIdx = (sy * gw + x) * 3
                        accR += tmp[sIdx + 0] * wt
                        accG += tmp[sIdx + 1] * wt
                        accB += tmp[sIdx + 2] * wt
                    }
                    val dIdx = dstRowBase + x * 3
                    buf[dIdx + 0] = accR
                    buf[dIdx + 1] = accG
                    buf[dIdx + 2] = accB
                }
            }
        }

        val maxGDim = max(gw, gh)
        if (useHal) {
            separableGaussian(halBuf, (maxGDim * 0.022f).toInt().coerceIn(3, 14))
        }
        if (useBloom) {
            separableGaussian(bloomBuf, (maxGDim * 0.015f).toInt().coerceIn(2, 10))
        }

        val gx0 = IntArray(w)
        val gx1 = IntArray(w)
        val tx = FloatArray(w)
        val invDs = 1f / ds.toFloat()
        val maxGx = (gw - 1).toFloat()
        val maxGy = (gh - 1).toFloat()
        for (x in 0 until w) {
            val fx = ((x + 0.5f) * invDs - 0.5f).coerceIn(0f, maxGx)
            val ix = fx.toInt().coerceIn(0, gw - 1)
            gx0[x] = ix
            gx1[x] = min(ix + 1, gw - 1)
            tx[x] = fx - ix
        }

        val halStrength = hal * 0.90f
        val bloomStrength = blm * 0.80f

        for (y in 0 until h) {
            val fy = ((y + 0.5f) * invDs - 0.5f).coerceIn(0f, maxGy)
            val gy0 = fy.toInt().coerceIn(0, gh - 1)
            val gy1 = min(gy0 + 1, gh - 1)
            val ty = fy - gy0
            val rowG0 = gy0 * gw
            val rowG1 = gy1 * gw
            val rowOff = y * w

            for (x in 0 until w) {
                val x0 = gx0[x]
                val x1 = gx1[x]
                val ftx = tx[x]
                val i00 = (rowG0 + x0) * 3
                val i10 = (rowG0 + x1) * 3
                val i01 = (rowG1 + x0) * 3
                val i11 = (rowG1 + x1) * 3

                val px = pixels[rowOff + x]
                val a = px and 0xFF000000.toInt()
                val baseR = ((px ushr 16) and 0xFF) * inv255
                val baseG = ((px ushr 8) and 0xFF) * inv255
                val baseB = (px and 0xFF) * inv255

                var glowR = 0f
                var glowG = 0f
                var glowB = 0f
                if (useHal) {
                    val topR = halBuf[i00 + 0] + (halBuf[i10 + 0] - halBuf[i00 + 0]) * ftx
                    val botR = halBuf[i01 + 0] + (halBuf[i11 + 0] - halBuf[i01 + 0]) * ftx
                    glowR += (topR + (botR - topR) * ty) * halStrength

                    val topG = halBuf[i00 + 1] + (halBuf[i10 + 1] - halBuf[i00 + 1]) * ftx
                    val botG = halBuf[i01 + 1] + (halBuf[i11 + 1] - halBuf[i01 + 1]) * ftx
                    glowG += (topG + (botG - topG) * ty) * halStrength

                    val topB = halBuf[i00 + 2] + (halBuf[i10 + 2] - halBuf[i00 + 2]) * ftx
                    val botB = halBuf[i01 + 2] + (halBuf[i11 + 2] - halBuf[i01 + 2]) * ftx
                    glowB += (topB + (botB - topB) * ty) * halStrength
                }
                if (useBloom) {
                    val topR = bloomBuf[i00 + 0] + (bloomBuf[i10 + 0] - bloomBuf[i00 + 0]) * ftx
                    val botR = bloomBuf[i01 + 0] + (bloomBuf[i11 + 0] - bloomBuf[i01 + 0]) * ftx
                    glowR += (topR + (botR - topR) * ty) * bloomStrength

                    val topG = bloomBuf[i00 + 1] + (bloomBuf[i10 + 1] - bloomBuf[i00 + 1]) * ftx
                    val botG = bloomBuf[i01 + 1] + (bloomBuf[i11 + 1] - bloomBuf[i01 + 1]) * ftx
                    glowG += (topG + (botG - topG) * ty) * bloomStrength

                    val topB = bloomBuf[i00 + 2] + (bloomBuf[i10 + 2] - bloomBuf[i00 + 2]) * ftx
                    val botB = bloomBuf[i01 + 2] + (bloomBuf[i11 + 2] - bloomBuf[i01 + 2]) * ftx
                    glowB += (topB + (botB - topB) * ty) * bloomStrength
                }

                val gr = glowR.coerceIn(0f, 1f)
                val gg = glowG.coerceIn(0f, 1f)
                val gb = glowB.coerceIn(0f, 1f)

                val outR = (1f - (1f - baseR) * (1f - gr)).coerceIn(0f, 1f)
                val outG = (1f - (1f - baseG) * (1f - gg)).coerceIn(0f, 1f)
                val outB = (1f - (1f - baseB) * (1f - gb)).coerceIn(0f, 1f)

                val ri = (outR * 255f + 0.5f).toInt().coerceIn(0, 255)
                val gi = (outG * 255f + 0.5f).toInt().coerceIn(0, 255)
                val bi = (outB * 255f + 0.5f).toInt().coerceIn(0, 255)
                pixels[rowOff + x] = a or (ri shl 16) or (gi shl 8) or bi
            }
        }
    }

    fun applyChromaticAberrationInPlace(
        bitmap: Bitmap,
        chromaticAberration: Float
    ) {
        if (bitmap.isRecycled || bitmap.width <= 1 || bitmap.height <= 1) return
        val amountNorm = (chromaticAberration / 50f).coerceIn(-1f, 1f)
        if (abs(amountNorm) <= 0.0005f) return

        val nativeApplied = if (NativeBridge.isAvailable && bitmap.config == Bitmap.Config.ARGB_8888) {
            try {
                NativeBridge.applyChromaticAberrationNative(bitmap, amountNorm)
            } catch (_: Throwable) {
                false
            }
        } else {
            false
        }
        if (!nativeApplied) {
            applyChromaticAberrationKotlin(bitmap, amountNorm)
        }
    }

    private fun applyChromaticAberrationKotlin(
        bitmap: Bitmap,
        amountNorm: Float
    ) {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        applyChromaticAberrationPixels(pixels, w, h, amountNorm)
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    internal fun applyChromaticAberrationPixels(
        pixels: IntArray,
        w: Int,
        h: Int,
        amountNorm: Float
    ) {
        if (w <= 1 || h <= 1 || pixels.size < w * h) return
        val norm = amountNorm.coerceIn(-1f, 1f)
        if (abs(norm) <= 0.0005f) return

        val src = pixels.copyOf()
        val shiftScale = norm * 0.012f
        val invW = 1f / w.toFloat()
        val invH = 1f / h.toFloat()
        val maxFx = (w - 1).toFloat()
        val maxFy = (h - 1).toFloat()

        fun sampleChan(u: Float, v: Float, shiftBits: Int): Int {
            val fx = (u * w - 0.5f).coerceIn(0f, maxFx)
            val fy = (v * h - 0.5f).coerceIn(0f, maxFy)
            val x0 = fx.toInt().coerceIn(0, w - 1)
            val y0 = fy.toInt().coerceIn(0, h - 1)
            val x1 = min(x0 + 1, w - 1)
            val y1 = min(y0 + 1, h - 1)
            val tx = fx - x0
            val ty = fy - y0

            val r0 = y0 * w
            val r1 = y1 * w
            val c00 = (src[r0 + x0] ushr shiftBits) and 0xFF
            val c10 = (src[r0 + x1] ushr shiftBits) and 0xFF
            val c01 = (src[r1 + x0] ushr shiftBits) and 0xFF
            val c11 = (src[r1 + x1] ushr shiftBits) and 0xFF
            val top = c00 + (c10 - c00) * tx
            val bot = c01 + (c11 - c01) * tx
            return (top + (bot - top) * ty + 0.5f).toInt().coerceIn(0, 255)
        }

        for (y in 0 until h) {
            val v = (y + 0.5f) * invH
            val dy = v - 0.5f
            val rowOff = y * w
            for (x in 0 until w) {
                val u = (x + 0.5f) * invW
                val dx = u - 0.5f

                val ru = (0.5f + dx * (1f + shiftScale)).coerceIn(0f, 1f)
                val rv = (0.5f + dy * (1f + shiftScale)).coerceIn(0f, 1f)
                val bu = (0.5f + dx * (1f - shiftScale)).coerceIn(0f, 1f)
                val bv = (0.5f + dy * (1f - shiftScale)).coerceIn(0f, 1f)

                val orig = src[rowOff + x]
                val aAndG = orig and 0xFF00FF00.toInt()
                val rOut = sampleChan(ru, rv, 16)
                val bOut = sampleChan(bu, bv, 0)
                pixels[rowOff + x] = aAndG or (rOut shl 16) or bOut
            }
        }
    }

    fun applyAnamorphicStreakInPlace(
        bitmap: Bitmap,
        anamorphicStreak: Float
    ) {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return
        val amountNorm = (anamorphicStreak / 100f).coerceIn(0f, 1f)
        if (amountNorm <= 0.0005f) return

        val nativeApplied = if (NativeBridge.isAvailable && bitmap.config == Bitmap.Config.ARGB_8888) {
            try {
                NativeBridge.applyAnamorphicStreakNative(bitmap, amountNorm)
            } catch (_: Throwable) {
                false
            }
        } else {
            false
        }
        if (!nativeApplied) {
            applyAnamorphicStreakKotlin(bitmap, amountNorm)
        }
    }

    private fun applyAnamorphicStreakKotlin(
        bitmap: Bitmap,
        amountNorm: Float
    ) {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        applyAnamorphicStreakPixels(pixels, w, h, amountNorm)
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    internal fun applyAnamorphicStreakPixels(
        pixels: IntArray,
        w: Int,
        h: Int,
        amountNorm: Float
    ) {
        if (w <= 0 || h <= 0 || pixels.size < w * h) return
        val amt = amountNorm.coerceIn(0f, 1f)
        if (amt <= 0.0005f) return

        val ds = (max(w, h) / 480).coerceIn(1, 4)
        val gw = max(1, (w + ds - 1) / ds)
        val gh = max(1, (h + ds - 1) / ds)
        val hiMask = FloatArray(gw * gh)
        val streakBuf = FloatArray(gw * gh)
        val inv255 = 1f / 255f

        for (gy in 0 until gh) {
            val yStart = gy * ds
            val yEnd = min(h, yStart + ds)
            for (gx in 0 until gw) {
                val xStart = gx * ds
                val xEnd = min(w, xStart + ds)
                var sumR = 0f
                var sumG = 0f
                var sumB = 0f
                var cnt = 0
                for (y in yStart until yEnd) {
                    val rowOff = y * w
                    for (x in xStart until xEnd) {
                        val px = pixels[rowOff + x]
                        sumR += ((px ushr 16) and 0xFF)
                        sumG += ((px ushr 8) and 0xFF)
                        sumB += (px and 0xFF)
                        cnt++
                    }
                }
                val invCnt = inv255 / max(1, cnt)
                val r = sumR * invCnt
                val g = sumG * invCnt
                val b = sumB * invCnt
                val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
                val maxChan = max(r, max(g, b))
                val hiSignal = max(lum, maxChan * 0.92f)
                val rawT = smoothRamp(0.68f, 0.96f, hiSignal)
                hiMask[gy * gw + gx] = rawT * rawT
            }
        }

        val radius = max(3, min(72, (gw * (0.05f + 0.14f * amt)).toInt()))
        if (gw > 1) {
            val sigma = max(1.2f, radius * 0.42f)
            val invTwoSigma2 = 1f / (2f * sigma * sigma)
            val kernel = FloatArray(radius * 2 + 1)
            var kSum = 0f
            for (i in -radius..radius) {
                val wt = exp(-(i * i).toFloat() * invTwoSigma2)
                kernel[i + radius] = wt
                kSum += wt
            }
            val invKSum = 1f / max(kSum, 1e-6f)
            for (i in kernel.indices) kernel[i] *= invKSum

            for (gy in 0 until gh) {
                val rowOff = gy * gw
                for (gx in 0 until gw) {
                    var acc = 0f
                    for (k in -radius..radius) {
                        val sx = gx + k
                        if (sx in 0 until gw) {
                            acc += hiMask[rowOff + sx] * kernel[k + radius]
                        }
                    }
                    streakBuf[rowOff + gx] = acc
                }
            }
        } else {
            hiMask.copyInto(streakBuf)
        }

        val gx0 = IntArray(w)
        val gx1 = IntArray(w)
        val tx = FloatArray(w)
        val invDs = 1f / ds.toFloat()
        val maxGx = (gw - 1).toFloat()
        val maxGy = (gh - 1).toFloat()
        for (x in 0 until w) {
            val fx = ((x + 0.5f) * invDs - 0.5f).coerceIn(0f, maxGx)
            val ix = fx.toInt().coerceIn(0, gw - 1)
            gx0[x] = ix
            gx1[x] = min(ix + 1, gw - 1)
            tx[x] = fx - ix
        }

        val streakStrength = amt * 1.65f
        for (y in 0 until h) {
            val fy = ((y + 0.5f) * invDs - 0.5f).coerceIn(0f, maxGy)
            val gy0 = fy.toInt().coerceIn(0, gh - 1)
            val gy1 = min(gy0 + 1, gh - 1)
            val ty = fy - gy0
            val rowG0 = gy0 * gw
            val rowG1 = gy1 * gw
            val rowOff = y * w

            for (x in 0 until w) {
                val x0 = gx0[x]
                val x1 = gx1[x]
                val ftx = tx[x]

                val s00 = streakBuf[rowG0 + x0]
                val s10 = streakBuf[rowG0 + x1]
                val s01 = streakBuf[rowG1 + x0]
                val s11 = streakBuf[rowG1 + x1]
                val top = s00 + (s10 - s00) * ftx
                val bot = s01 + (s11 - s01) * ftx
                val s = (top + (bot - top) * ty) * streakStrength
                if (s <= 0.0005f) continue

                val px = pixels[rowOff + x]
                val a = px and 0xFF000000.toInt()
                val baseR = ((px ushr 16) and 0xFF) * inv255
                val baseG = ((px ushr 8) and 0xFF) * inv255
                val baseB = (px and 0xFF) * inv255

                val outR = (baseR + s * 0.24f).coerceIn(0f, 1f)
                val outG = (baseG + s * 0.74f).coerceIn(0f, 1f)
                val outB = (baseB + s * 1.00f).coerceIn(0f, 1f)

                val ri = (outR * 255f + 0.5f).toInt().coerceIn(0, 255)
                val gi = (outG * 255f + 0.5f).toInt().coerceIn(0, 255)
                val bi = (outB * 255f + 0.5f).toInt().coerceIn(0, 255)
                pixels[rowOff + x] = a or (ri shl 16) or (gi shl 8) or bi
            }
        }
    }

    private fun tryApplyLut3dNative(
        bitmap: Bitmap,
        lut: CubeLut,
        intensity: Float
    ): Boolean {
        if (!NativeBridge.isAvailable) return false
        if (lut.type != LutType.LUT_3D || lut.size !in 2..65) return false
        if (bitmap.isRecycled || bitmap.config != Bitmap.Config.ARGB_8888) return false
        return try {
            NativeBridge.applyLut3dNative(
                bitmap = bitmap,
                lutTable = lut.table,
                lutSize = lut.size,
                domainMin = lut.domainMin,
                domainMax = lut.domainMax,
                intensity = intensity
            )
        } catch (_: Throwable) {
            false
        }
    }

    private fun applyLutKotlinOnBitmap(
        bitmap: Bitmap,
        lut: CubeLut,
        intensity: Float
    ) {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return
        val mix = intensity.coerceIn(0f, 1f)
        if (mix <= 0.0005f) return
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val lutOut = FloatArray(3)
        for (i in pixels.indices) {
            val px = pixels[i]
            val a = px and 0xFF000000.toInt()
            val rIn = ((px ushr 16) and 0xFF) * (1f / 255f)
            val gIn = ((px ushr 8) and 0xFF) * (1f / 255f)
            val bIn = (px and 0xFF) * (1f / 255f)
            lut.sample(rIn, gIn, bIn, lutOut)
            val r = rIn + (lutOut[0] - rIn) * mix
            val g = gIn + (lutOut[1] - gIn) * mix
            val b = bIn + (lutOut[2] - bIn) * mix
            val ri = (r * 255f + 0.5f).toInt().coerceIn(0, 255)
            val gi = (g * 255f + 0.5f).toInt().coerceIn(0, 255)
            val bi = (b * 255f + 0.5f).toInt().coerceIn(0, 255)
            pixels[i] = a or (ri shl 16) or (gi shl 8) or bi
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    private fun sampleBilinear(pixels: IntArray, w: Int, h: Int, u: Float, v: Float): Int {
        val fx = (u * (w - 1)).coerceIn(0f, (w - 1).toFloat())
        val fy = (v * (h - 1)).coerceIn(0f, (h - 1).toFloat())
        val x0 = fx.toInt().coerceIn(0, w - 1)
        val y0 = fy.toInt().coerceIn(0, h - 1)
        val x1 = min(w - 1, x0 + 1)
        val y1 = min(h - 1, y0 + 1)
        val tx = fx - x0
        val ty = fy - y0

        val p00 = pixels[y0 * w + x0]
        val p10 = pixels[y0 * w + x1]
        val p01 = pixels[y1 * w + x0]
        val p11 = pixels[y1 * w + x1]

        fun lerpChan(shift: Int): Int {
            val c00 = (p00 ushr shift) and 0xFF
            val c10 = (p10 ushr shift) and 0xFF
            val c01 = (p01 ushr shift) and 0xFF
            val c11 = (p11 ushr shift) and 0xFF
            val top = c00 + (c10 - c00) * tx
            val bot = c01 + (c11 - c01) * tx
            return (top + (bot - top) * ty + 0.5f).toInt().coerceIn(0, 255)
        }

        val a = (p00 ushr 24) and 0xFF
        return (a shl 24) or (lerpChan(16) shl 16) or (lerpChan(8) shl 8) or lerpChan(0)
    }

    private fun samplePixelBilinear(pixels: IntArray, w: Int, h: Int, pxX: Float, pxY: Float): Int {
        val fx = pxX.coerceIn(0f, (w - 1).toFloat())
        val fy = pxY.coerceIn(0f, (h - 1).toFloat())
        val x0 = fx.toInt().coerceIn(0, w - 1)
        val y0 = fy.toInt().coerceIn(0, h - 1)
        val x1 = min(w - 1, x0 + 1)
        val y1 = min(h - 1, y0 + 1)
        val tx = fx - x0
        val ty = fy - y0

        val p00 = pixels[y0 * w + x0]
        val p10 = pixels[y0 * w + x1]
        val p01 = pixels[y1 * w + x0]
        val p11 = pixels[y1 * w + x1]

        fun lerpChan(shift: Int): Int {
            val c00 = (p00 ushr shift) and 0xFF
            val c10 = (p10 ushr shift) and 0xFF
            val c01 = (p01 ushr shift) and 0xFF
            val c11 = (p11 ushr shift) and 0xFF
            val top = c00 + (c10 - c00) * tx
            val bot = c01 + (c11 - c01) * tx
            return (top + (bot - top) * ty + 0.5f).toInt().coerceIn(0, 255)
        }

        val a = (p00 ushr 24) and 0xFF
        return (a shl 24) or (lerpChan(16) shl 16) or (lerpChan(8) shl 8) or lerpChan(0)
    }

    private fun pixelLuma(px: Int): Float {
        val r = ((px ushr 16) and 0xFF) * (1f / 255f)
        val g = ((px ushr 8) and 0xFF) * (1f / 255f)
        val b = (px and 0xFF) * (1f / 255f)
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }

    private fun bandWeight(value: Float, center: Float, halfWidth: Float): Float {
        val d = abs(value - center)
        if (d >= halfWidth) return 0f
        val t = 1f - d / halfWidth
        return t * t * (3f - 2f * t)
    }

    private fun smoothRamp(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0).coerceAtLeast(1e-4f)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun srgbToLinearChan(c: Float): Float {
        val cc = c.coerceIn(0f, 1f)
        return if (cc <= 0.04045f) {
            cc / 12.92f
        } else {
            ((cc + 0.055f) / 1.055f).pow(2.4f)
        }
    }

    private fun linearToSrgbChan(c: Float): Float {
        val cc = c.coerceAtLeast(0f)
        val s = if (cc <= 0.0031308f) {
            cc * 12.92f
        } else {
            1.055f * cc.pow(1f / 2.4f) - 0.055f
        }
        return s.coerceIn(0f, 1f)
    }

    private fun sampleCurveLut(lut: FloatArray, v: Float): Float {
        val pos = v.coerceIn(0f, 1f) * 255f
        val i0 = pos.toInt().coerceIn(0, 255)
        val i1 = min(255, i0 + 1)
        val frac = pos - i0
        return (lut[i0] + (lut[i1] - lut[i0]) * frac).coerceIn(0f, 1f)
    }

    private fun applyHslBands(
        r: Float,
        g: Float,
        b: Float,
        hueAdj: FloatArray,
        satAdj: FloatArray,
        lumAdj: FloatArray,
        hslWork: FloatArray,
        rgbOut: FloatArray
    ) {
        rgbToHsl(r, g, b, hslWork)
        val hueDeg = hslWork[0]
        val sat = hslWork[1]
        val light = hslWork[2]
        if (sat <= 0.002f) {
            rgbOut[0] = r
            rgbOut[1] = g
            rgbOut[2] = b
            return
        }

        var dHue = 0f
        var dSat = 0f
        var dLum = 0f
        var totalWeight = 0f

        for (band in 0 until 8) {
            val center = HSL_BAND_CENTERS[band]
            val span = HSL_BAND_SPANS[band]
            var dist = abs(hueDeg - center)
            if (dist > 180f) dist = 360f - dist
            if (dist < span) {
                val w = 1f - dist / span
                totalWeight += w
                dHue += hueAdj[band] * w
                dSat += satAdj[band] * w
                dLum += lumAdj[band] * w
            }
        }

        if (totalWeight > 1e-4f) {
            dHue /= totalWeight
            dSat /= totalWeight
            dLum /= totalWeight
        }

        val newHue = ((hueDeg + (dHue / 100f) * 32f) % 360f + 360f) % 360f
        val newSat = (sat * (1f + dSat / 100f)).coerceIn(0f, 1f)
        val newLight = (light + (dLum / 100f) * 0.22f * sat).coerceIn(0f, 1f)
        hslToRgb(newHue, newSat, newLight, rgbOut)
    }

    private fun rgbToHsl(r: Float, g: Float, b: Float, out: FloatArray) {
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val delta = maxC - minC
        val l = (maxC + minC) * 0.5f

        if (delta < 1e-5f) {
            out[0] = 0f
            out[1] = 0f
            out[2] = l
            return
        }

        val s = if (l > 0.5f) delta / (2f - maxC - minC) else delta / (maxC + minC)
        var h = when (maxC) {
            r -> ((g - b) / delta) + (if (g < b) 6f else 0f)
            g -> ((b - r) / delta) + 2f
            else -> ((r - g) / delta) + 4f
        }
        h *= 60f
        out[0] = (h % 360f + 360f) % 360f
        out[1] = s.coerceIn(0f, 1f)
        out[2] = l.coerceIn(0f, 1f)
    }

    private fun hslToRgb(h: Float, s: Float, l: Float, out: FloatArray) {
        if (s <= 1e-5f) {
            out[0] = l
            out[1] = l
            out[2] = l
            return
        }
        val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
        val p = 2f * l - q
        val hk = h / 360f
        out[0] = hue2rgb(p, q, hk + 1f / 3f).coerceIn(0f, 1f)
        out[1] = hue2rgb(p, q, hk).coerceIn(0f, 1f)
        out[2] = hue2rgb(p, q, hk - 1f / 3f).coerceIn(0f, 1f)
    }

    private fun hue2rgb(p: Float, q: Float, tIn: Float): Float {
        var t = tIn
        if (t < 0f) t += 1f
        if (t > 1f) t -= 1f
        return when {
            t < 1f / 6f -> p + (q - p) * 6f * t
            t < 0.5f -> q
            t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
            else -> p
        }
    }

    private fun hueSatToRgbTint(hueDeg: Float, sat: Float): FloatArray {
        if (sat <= 0f) return floatArrayOf(0f, 0f, 0f)
        val h = (hueDeg % 360f) / 60f
        val x = 1f - abs(h % 2f - 1f)
        val (r, g, b) = when {
            h < 1f -> Triple(1f, x, 0f)
            h < 2f -> Triple(x, 1f, 0f)
            h < 3f -> Triple(0f, 1f, x)
            h < 4f -> Triple(0f, x, 1f)
            h < 5f -> Triple(x, 0f, 1f)
            else -> Triple(1f, 0f, x)
        }
        val avg = (r + g + b) / 3f
        return floatArrayOf(r - avg, g - avg, b - avg)
    }

    private fun emptyHistogram(): HistogramData = HistogramData(
        luma = FloatArray(HIST_BINS),
        red = FloatArray(HIST_BINS),
        green = FloatArray(HIST_BINS),
        blue = FloatArray(HIST_BINS)
    )

    private fun computeHistogram(bitmap: Bitmap): HistogramData {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return emptyHistogram()
        if (NativeBridge.isAvailable && bitmap.config == Bitmap.Config.ARGB_8888) {
            val nativeHist = try {
                NativeBridge.computeHistogramNative(bitmap)
            } catch (_: Throwable) {
                null
            }
            if (nativeHist != null) {
                reduceNativeHistogram(nativeHist)?.let { return it }
            }
        }
        return computeHistogramKotlin(bitmap)
    }

    internal fun reduceNativeHistogram(rawHist: IntArray): HistogramData? {
        if (rawHist.size < 1024) return null
        val lBins = IntArray(HIST_BINS)
        val rBins = IntArray(HIST_BINS)
        val gBins = IntArray(HIST_BINS)
        val bBins = IntArray(HIST_BINS)

        for (v in 0 until 256) {
            val bin = (v * (HIST_BINS - 1)) / 255
            rBins[bin] += max(0, rawHist[v])
            gBins[bin] += max(0, rawHist[256 + v])
            bBins[bin] += max(0, rawHist[512 + v])
            lBins[bin] += max(0, rawHist[768 + v])
        }

        var maxVal = 1
        for (bin in 0 until HIST_BINS) {
            if (lBins[bin] > maxVal) maxVal = lBins[bin]
            if (rBins[bin] > maxVal) maxVal = rBins[bin]
            if (gBins[bin] > maxVal) maxVal = gBins[bin]
            if (bBins[bin] > maxVal) maxVal = bBins[bin]
        }

        val norm = maxVal.toFloat()
        return HistogramData(
            luma = FloatArray(HIST_BINS) { lBins[it] / norm },
            red = FloatArray(HIST_BINS) { rBins[it] / norm },
            green = FloatArray(HIST_BINS) { gBins[it] / norm },
            blue = FloatArray(HIST_BINS) { bBins[it] / norm }
        )
    }

    private fun computeHistogramKotlin(bitmap: Bitmap): HistogramData {
        val w = bitmap.width
        val h = bitmap.height
        val total = w * h
        val pixels = IntArray(total)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val lBins = IntArray(HIST_BINS)
        val rBins = IntArray(HIST_BINS)
        val gBins = IntArray(HIST_BINS)
        val bBins = IntArray(HIST_BINS)

        val step = max(1, total / 24_000)
        var i = 0
        while (i < total) {
            val px = pixels[i]
            val r = (px ushr 16) and 0xFF
            val g = (px ushr 8) and 0xFF
            val b = px and 0xFF
            val luma = (0.2126f * r + 0.7152f * g + 0.0722f * b).toInt().coerceIn(0, 255)

            lBins[(luma * (HIST_BINS - 1)) / 255]++
            rBins[(r * (HIST_BINS - 1)) / 255]++
            gBins[(g * (HIST_BINS - 1)) / 255]++
            bBins[(b * (HIST_BINS - 1)) / 255]++
            i += step
        }

        var maxVal = 1
        for (bin in 0 until HIST_BINS) {
            if (lBins[bin] > maxVal) maxVal = lBins[bin]
            if (rBins[bin] > maxVal) maxVal = rBins[bin]
            if (gBins[bin] > maxVal) maxVal = gBins[bin]
            if (bBins[bin] > maxVal) maxVal = bBins[bin]
        }

        val norm = maxVal.toFloat()
        return HistogramData(
            luma = FloatArray(HIST_BINS) { lBins[it] / norm },
            red = FloatArray(HIST_BINS) { rBins[it] / norm },
            green = FloatArray(HIST_BINS) { gBins[it] / norm },
            blue = FloatArray(HIST_BINS) { bBins[it] / norm }
        )
    }

    private fun cpuGrainHash(ix: Int, iy: Int): Float {
        val h = ((ix * 374761393) xor (iy * 668265263)) * 1274126177
        return ((h ushr 16) and 0xFFFF) / 65535f
    }

    private fun cpuSmoothNoise(fx: Float, fy: Float): Float {
        val ix = kotlin.math.floor(fx).toInt()
        val iy = kotlin.math.floor(fy).toInt()
        val tx = fx - ix
        val ty = fy - iy
        val ux = tx * tx * (3f - 2f * tx)
        val uy = ty * ty * (3f - 2f * ty)
        val a = cpuGrainHash(ix, iy)
        val b = cpuGrainHash(ix + 1, iy)
        val c = cpuGrainHash(ix, iy + 1)
        val d = cpuGrainHash(ix + 1, iy + 1)
        val ab = a + (b - a) * ux
        val cd = c + (d - c) * ux
        return ab + (cd - ab) * uy
    }
}
