package com.zamalfajji.zamlab.ai

import ai.onnxruntime.NodeInfo
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import ai.onnxruntime.ValueInfo
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.graphics.createBitmap
import com.zamalfajji.zamlab.editor.GradingParams
import java.io.File
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Professional non-destructive RAW/photo noise reduction engine modeled after
 * Adobe Lightroom Classic's Detail / Denoise pipeline, with optional offline ONNX AI Clean:
 *
 * 1. **Chroma Noise Pipeline (BT.709 YCbCr edge-stopping joint bilateral)**:
 *    Converts RGB to YCbCr and applies a two-stage edge-aware chroma filter governed by
 *    [GradingParams.colorNoiseReduction] (0..100) and [GradingParams.colorNoiseReductionSmoothness]
 *    (0..100). Stage 1 removes isolated high-frequency color speckles; Stage 2 suppresses
 *    wide low-frequency red/green/blue chroma blotches in shadows while stopping cleanly across
 *    luminance and coherent object color boundaries.
 *
 * 2. **Luminance Noise Pipeline (Multi-scale edge-stopping joint bilateral + detail protection)**:
 *    Applies an edge-preserving multi-scale bilateral filter on luminance Y governed by
 *    [GradingParams.noiseReduction] (0..100) and [GradingParams.noiseReductionDetail] (0..100),
 *    preserving fine hair/texture micro-contrast while removing monochrome grain without a
 *    waxy/painterly appearance. [GradingParams.noiseReductionContrast] (0..100) restores local
 *    midtone punch via halo-clamped midtone unsharp clarity recovery.
 *
 * 3. **Optional Offline ONNX AI Clean (`nafnet_denoise.onnx` / `scunet_denoise.onnx`)**:
 *    When [GradingParams.denoiseAiClean] is active, lazily checks `app/src/main/assets/` for
 *    `nafnet_denoise.onnx` or `scunet_denoise.onnx`, extracts it once to `cacheDir/onnx/`,
 *    dynamically inspects `session.inputInfo`/`outputInfo` (NCHW/NHWC float `[0,1]` or uint8),
 *    and processes images in overlapping 256×256 tiles with 16px feathering. Automatically falls
 *    back to the calibrated YCbCr edge-preserving solver when no ONNX model is bundled.
 */
object LocalDenoiseEngine {

    private const val TAG = "LocalDenoiseEngine"
    val MODEL_ASSET_CANDIDATES: List<String> = listOf(
        "nafnet_denoise.onnx",
        "scunet_denoise.onnx"
    )
    const val TILE_SIZE = 256
    const val TILE_OVERLAP = 16

    const val DEFAULT_LUMINANCE = 0f
    const val DEFAULT_DETAIL = 50f
    const val DEFAULT_CONTRAST = 0f
    const val DEFAULT_COLOR = 25f
    const val DEFAULT_SMOOTHNESS = 50f

    // BT.709 luminance & chroma conversion constants
    private const val KR = 0.2126f
    private const val KG = 0.7152f
    private const val KB = 0.0722f
    private const val INV_CB_SCALE = 1f / 1.8556f
    private const val INV_CR_SCALE = 1f / 1.5748f
    private const val G_FROM_CB = 0.18732427f
    private const val G_FROM_CR = 0.46812427f

    enum class DenoiseBackend {
        ONNX_AI_CLEAN,
        YCBCR_EDGE_PRESERVING
    }

    data class DenoiseResult(
        val bitmap: Bitmap,
        val backend: DenoiseBackend,
        val modelName: String? = null,
        val accelerationProvider: String? = null
    )

    enum class TensorLayout {
        NCHW,
        NHWC
    }

    internal data class DenoiseTensorSpec(
        val name: String,
        val javaType: OnnxJavaType,
        val shape: LongArray,
        val layout: TensorLayout,
        val fixedHeight: Int?,
        val fixedWidth: Int?
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DenoiseTensorSpec) return false
            return name == other.name &&
                javaType == other.javaType &&
                shape.contentEquals(other.shape) &&
                layout == other.layout &&
                fixedHeight == other.fixedHeight &&
                fixedWidth == other.fixedWidth
        }

        override fun hashCode(): Int {
            var result = name.hashCode()
            result = 31 * result + javaType.hashCode()
            result = 31 * result + shape.contentHashCode()
            result = 31 * result + layout.hashCode()
            result = 31 * result + (fixedHeight ?: 0)
            result = 31 * result + (fixedWidth ?: 0)
            return result
        }
    }

    internal data class DenoiseModelIoSpec(
        val input: DenoiseTensorSpec,
        val output: DenoiseTensorSpec
    )

    internal data class TileWindow(
        val srcX0: Int,
        val srcY0: Int,
        val srcX1: Int,
        val srcY1: Int,
        val rowIndex: Int,
        val colIndex: Int,
        val hasLeftNeighbor: Boolean,
        val hasRightNeighbor: Boolean,
        val hasTopNeighbor: Boolean,
        val hasBottomNeighbor: Boolean
    ) {
        val width: Int get() = srcX1 - srcX0
        val height: Int get() = srcY1 - srcY0
    }

    private data class LoadedOnnxSession(
        val env: OrtEnvironment,
        val session: OrtSession,
        val modelName: String,
        val providerName: String,
        val ioSpec: DenoiseModelIoSpec
    )

    private val sessionLock = Any()

    @Volatile
    private var onnxInitAttempted = false

    @Volatile
    private var loadedSession: LoadedOnnxSession? = null

    @Volatile
    private var cachedAvailableModelName: String? = null

    @Volatile
    private var assetAvailabilityChecked = false

    @VisibleForTesting
    internal fun resetOnnxStateForTests() {
        synchronized(sessionLock) {
            runCatching { loadedSession?.session?.close() }
            loadedSession = null
            onnxInitAttempted = false
            cachedAvailableModelName = null
            assetAvailabilityChecked = false
        }
    }

    /**
     * Lightweight check for whether `nafnet_denoise.onnx` or `scunet_denoise.onnx` is bundled
     * in `app/src/main/assets/`. Does not initialize ONNX Runtime or copy model weights.
     */
    fun isOnnxModelAssetAvailable(context: Context): Boolean = findBundledModelAssetName(context) != null

    fun findBundledModelAssetName(context: Context): String? {
        if (assetAvailabilityChecked) return cachedAvailableModelName
        val found = MODEL_ASSET_CANDIDATES.firstOrNull { candidate ->
            try {
                context.assets.open(candidate).use { true }
            } catch (_: FileNotFoundException) {
                false
            } catch (_: Throwable) {
                false
            }
        }
        cachedAvailableModelName = found
        assetAvailabilityChecked = true
        return found
    }

    /**
     * Applies full-precision YCbCr edge-preserving denoising directly in-place onto linear/sRGB
     * `[0f, 1f]` RGB float planes used by [com.zamalfajji.zamlab.editor.ColorGradingEngine].
     *
     * Used by both CPU export rendering and fallback bitmap denoising to guarantee exact parity
     * across live preview, export, and unit tests.
     */
    fun applyDenoiseToRgbPlanes(
        r: FloatArray,
        g: FloatArray,
        b: FloatArray,
        width: Int,
        height: Int,
        params: GradingParams
    ) {
        val total = width * height
        if (width <= 1 || height <= 1 || r.size < total || g.size < total || b.size < total) return

        val aiBoost = params.denoiseAiClean
        val lumaNorm = (params.noiseReduction / 100f).coerceIn(0f, 1f)
        val detailNorm = (params.noiseReductionDetail / 100f).coerceIn(0f, 1f)
        val contrastNorm = (params.noiseReductionContrast / 100f).coerceIn(0f, 1f)
        val colorNorm = (params.colorNoiseReduction / 100f).coerceIn(0f, 1f)
        val smoothnessNorm = (params.colorNoiseReductionSmoothness / 100f).coerceIn(0f, 1f)

        val effectiveLuma = if (aiBoost && lumaNorm <= 0.005f) 0.50f else lumaNorm
        val effectiveColor = if (aiBoost && colorNorm <= 0.005f) 0.35f else colorNorm

        if (effectiveLuma <= 0.005f && effectiveColor <= 0.005f) return

        // 1. RGB -> BT.709 YCbCr planes
        val yPlane = FloatArray(total)
        val cbPlane = FloatArray(total)
        val crPlane = FloatArray(total)
        for (i in 0 until total) {
            val rv = r[i]
            val gv = g[i]
            val bv = b[i]
            val yv = KR * rv + KG * gv + KB * bv
            yPlane[i] = yv
            cbPlane[i] = (bv - yv) * INV_CB_SCALE
            crPlane[i] = (rv - yv) * INV_CR_SCALE
        }

        // 2. Chroma Noise Pipeline: 2-stage Y-guided & chroma-edge-stopping bilateral filter
        var cbClean = cbPlane
        var crClean = crPlane
        if (effectiveColor > 0.005f) {
            val stage1Cb = FloatArray(total)
            val stage1Cr = FloatArray(total)
            filterChromaStage1(
                y = yPlane,
                cb = cbPlane,
                cr = crPlane,
                outCb = stage1Cb,
                outCr = stage1Cr,
                width = width,
                height = height,
                colorNorm = effectiveColor,
                smoothnessNorm = smoothnessNorm
            )

            // Wide edge-stopping chroma blotch smoothing controlled by Color + Smoothness
            val wideStrength = effectiveColor * (0.35f + 0.65f * smoothnessNorm)
            if (wideStrength > 0.05f && width >= 4 && height >= 4) {
                val stage2Cb = FloatArray(total)
                val stage2Cr = FloatArray(total)
                filterChromaWideBlotches(
                    y = yPlane,
                    cb = stage1Cb,
                    cr = stage1Cr,
                    outCb = stage2Cb,
                    outCr = stage2Cr,
                    width = width,
                    height = height,
                    colorNorm = effectiveColor,
                    smoothnessNorm = smoothnessNorm
                )
                cbClean = stage2Cb
                crClean = stage2Cr
            } else {
                cbClean = stage1Cb
                crClean = stage1Cr
            }
        }

        // 3. Luminance Noise Pipeline: multi-scale edge-stopping bilateral + Detail protection + Contrast recovery
        var yFinal = yPlane
        if (effectiveLuma > 0.005f) {
            val yDenoised = FloatArray(total)
            filterLuminanceEdgePreserving(
                y = yPlane,
                outY = yDenoised,
                width = width,
                height = height,
                lumaNorm = effectiveLuma,
                detailNorm = detailNorm,
                aiPrecision = aiBoost
            )

            if (contrastNorm > 0.005f) {
                val yRecovered = FloatArray(total)
                applyMidtoneContrastRecovery(
                    yClean = yDenoised,
                    outY = yRecovered,
                    width = width,
                    height = height,
                    contrastNorm = contrastNorm,
                    lumaNorm = effectiveLuma
                )
                yFinal = yRecovered
            } else {
                yFinal = yDenoised
            }
        }

        // 4. Reconstruct RGB [0..1] from (Y_final, Cb_clean, Cr_clean)
        for (i in 0 until total) {
            val yv = yFinal[i]
            val cbv = cbClean[i]
            val crv = crClean[i]
            r[i] = (yv + 1.5748f * crv).coerceIn(0f, 1f)
            g[i] = (yv - G_FROM_CB * cbv - G_FROM_CR * crv).coerceIn(0f, 1f)
            b[i] = (yv + 1.8556f * cbv).coerceIn(0f, 1f)
        }
    }

    /**
     * Stage 1 Chroma Filter: 3×3 Y-guided + speckle-aware bilateral filter.
     * Eliminates isolated single-pixel chroma noise spikes while preserving coherent color edges.
     */
    private fun filterChromaStage1(
        y: FloatArray,
        cb: FloatArray,
        cr: FloatArray,
        outCb: FloatArray,
        outCr: FloatArray,
        width: Int,
        height: Int,
        colorNorm: Float,
        smoothnessNorm: Float
    ) {
        val sigmaY = 0.055f + 0.055f * colorNorm
        val invTwoSigmaY2 = 1f / (2f * sigmaY * sigmaY)
        // Base chroma range sigma stops across crisp saturated object boundaries (dC > ~0.42)
        // while absorbing shadow chroma noise (dC ~ 0.04..0.24).
        val baseSigmaC = 0.08f + 0.14f * colorNorm + 0.06f * smoothnessNorm
        val baseBlend = (colorNorm * (0.78f + 0.18f * smoothnessNorm)).coerceIn(0f, 0.96f)

        for (py in 0 until height) {
            val ym1 = max(0, py - 1)
            val yp1 = min(height - 1, py + 1)
            val rowBase = py * width
            val rowU = ym1 * width
            val rowD = yp1 * width

            for (px in 0 until width) {
                val xm1 = max(0, px - 1)
                val xp1 = min(width - 1, px + 1)
                val idx = rowBase + px

                val cy = y[idx]
                val cCb = cb[idx]
                val cCr = cr[idx]

                // Detect isolated chroma speckle vs coherent color region using 4-neighbors
                val iL = rowBase + xm1
                val iR = rowBase + xp1
                val iU = rowU + px
                val iD = rowD + px

                val dL = hypotChroma(cCb - cb[iL], cCr - cr[iL])
                val dR = hypotChroma(cCb - cb[iR], cCr - cr[iR])
                val dU = hypotChroma(cCb - cb[iU], cCr - cr[iU])
                val dD = hypotChroma(cCb - cb[iD], cCr - cr[iD])
                val dMin = min(min(dL, dR), min(dU, dD))
                val isIsolatedSpeckle = dMin > 0.09f

                val sigmaC = if (isIsolatedSpeckle) baseSigmaC * 3.8f else baseSigmaC
                val invTwoSigmaC2 = 1f / (2f * sigmaC * sigmaC)

                // Center weight reduced for isolated speckles so neighbor chroma dominates cleanly
                var wSum = if (isIsolatedSpeckle) 0.06f else 0.85f
                var cbAcc = cCb * wSum
                var crAcc = cCr * wSum

                for (dy in -1..1) {
                    val ny = (py + dy).coerceIn(0, height - 1)
                    val nRow = ny * width
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = (px + dx).coerceIn(0, width - 1)
                        val ni = nRow + nx
                        val dY = y[ni] - cy
                        val dCb = cb[ni] - cCb
                        val dCr = cr[ni] - cCr
                        val dC2 = dCb * dCb + dCr * dCr
                        val spatialW = if (dx == 0 || dy == 0) 1.0f else 0.707f
                        val w = spatialW * exp(-(dY * dY) * invTwoSigmaY2 - dC2 * invTwoSigmaC2)
                        wSum += w
                        cbAcc += cb[ni] * w
                        crAcc += cr[ni] * w
                    }
                }

                val filteredCb = cbAcc / wSum
                val filteredCr = crAcc / wSum
                val blend = if (isIsolatedSpeckle) {
                    (colorNorm * 0.96f).coerceIn(0f, 0.98f)
                } else {
                    baseBlend
                }
                outCb[idx] = cCb + (filteredCb - cCb) * blend
                outCr[idx] = cCr + (filteredCr - cCr) * blend
            }
        }
    }

    /**
     * Stage 2 Chroma Filter: Wide multi-ring edge-stopping joint bilateral filter.
     * Smooths low-frequency multi-pixel shadow chroma mottling / blotches without bleeding
     * color across sharp luminance or saturated color boundaries.
     */
    private fun filterChromaWideBlotches(
        y: FloatArray,
        cb: FloatArray,
        cr: FloatArray,
        outCb: FloatArray,
        outCr: FloatArray,
        width: Int,
        height: Int,
        colorNorm: Float,
        smoothnessNorm: Float
    ) {
        val innerR = 2
        val outerR = (2 + (smoothnessNorm * 3.0f).roundToInt()).coerceIn(2, 5)
        // Tight luminance edge-stopper + shadow-aware chroma tolerance (stronger in shadows where ISO blotches live)
        val sigmaY = 0.048f + 0.042f * colorNorm
        val invTwoSigmaY2 = 1f / (2f * sigmaY * sigmaY)
        val baseSigmaC = 0.095f + 0.145f * colorNorm + 0.08f * smoothnessNorm
        val blend = (colorNorm * (0.48f + 0.48f * smoothnessNorm)).coerceIn(0f, 0.95f)

        val dirX = intArrayOf(1, -1, 0, 0, 1, -1, 1, -1)
        val dirY = intArrayOf(0, 0, 1, -1, 1, 1, -1, -1)

        for (py in 0 until height) {
            val rowBase = py * width
            for (px in 0 until width) {
                val idx = rowBase + px
                val cy = y[idx]
                val cCb = cb[idx]
                val cCr = cr[idx]

                // Shadow regions receive slightly wider chroma tolerance because high-ISO blotches concentrate there
                val shadowBoost = 1f + (1f - cy).coerceIn(0f, 1f) * 0.28f * smoothnessNorm
                val sigmaC = baseSigmaC * shadowBoost
                val invTwoSigmaC2 = 1f / (2f * sigmaC * sigmaC)

                var wSum = 1.0f
                var cbAcc = cCb
                var crAcc = cCr

                // Inner ring (radius = 2)
                for (k in 0 until 8) {
                    val nx = (px + dirX[k] * innerR).coerceIn(0, width - 1)
                    val ny = (py + dirY[k] * innerR).coerceIn(0, height - 1)
                    val ni = ny * width + nx
                    val dY = y[ni] - cy
                    val dCb = cb[ni] - cCb
                    val dCr = cr[ni] - cCr
                    val dC2 = dCb * dCb + dCr * dCr
                    val spatialW = if (k < 4) 0.90f else 0.72f
                    val w = spatialW * exp(-(dY * dY) * invTwoSigmaY2 - dC2 * invTwoSigmaC2)
                    wSum += w
                    cbAcc += cb[ni] * w
                    crAcc += cr[ni] * w
                }

                // Outer ring (radius = 2..5 scaled by Smoothness)
                if (outerR > innerR) {
                    for (k in 0 until 8) {
                        val nx = (px + dirX[k] * outerR).coerceIn(0, width - 1)
                        val ny = (py + dirY[k] * outerR).coerceIn(0, height - 1)
                        val ni = ny * width + nx
                        val dY = y[ni] - cy
                        val dCb = cb[ni] - cCb
                        val dCr = cr[ni] - cCr
                        val dC2 = dCb * dCb + dCr * dCr
                        val spatialW = if (k < 4) 0.68f else 0.52f
                        val w = spatialW * exp(-(dY * dY) * invTwoSigmaY2 - dC2 * invTwoSigmaC2)
                        wSum += w
                        cbAcc += cb[ni] * w
                        crAcc += cr[ni] * w
                    }
                }

                val smoothedCb = cbAcc / wSum
                val smoothedCr = crAcc / wSum
                outCb[idx] = cCb + (smoothedCb - cCb) * blend
                outCr[idx] = cCr + (smoothedCr - cCr) * blend
            }
        }
    }

    /**
     * Multi-scale edge-stopping joint bilateral luminance denoiser.
     * - [detailNorm] (`Detail` 0..100) tightly governs the edge-stopping threshold `sigmaR` and
     *   high-frequency micro-texture protection so hair/fabric never turn plastic or waxy.
     * - Detects isolated single-pixel impulse grain spikes via minimum 4-neighbor difference so
     *   salt-and-pepper noise is suppressed without blurring coherent step edges.
     */
    private fun filterLuminanceEdgePreserving(
        y: FloatArray,
        outY: FloatArray,
        width: Int,
        height: Int,
        lumaNorm: Float,
        detailNorm: Float,
        aiPrecision: Boolean
    ) {
        // Higher Detail -> tighter sigmaR -> stronger protection of edges & micro-texture.
        val detailTighten = 1f - detailNorm * 0.78f
        val baseSigmaR = (0.022f + 0.125f * lumaNorm * detailTighten).coerceIn(0.016f, 0.16f)
        val outerRadius = if (lumaNorm > 0.35f || aiPrecision) 2 else 0
        val blendBase = (lumaNorm * (0.92f - detailNorm * 0.24f)).coerceIn(0f, 0.94f)

        val outerDx = intArrayOf(2, -2, 0, 0, 2, -2, 2, -2)
        val outerDy = intArrayOf(0, 0, 2, -2, 2, 2, -2, -2)

        for (py in 0 until height) {
            val ym1 = max(0, py - 1)
            val yp1 = min(height - 1, py + 1)
            val rowBase = py * width
            val rowU = ym1 * width
            val rowD = yp1 * width

            for (px in 0 until width) {
                val xm1 = max(0, px - 1)
                val xp1 = min(width - 1, px + 1)
                val idx = rowBase + px
                val cy = y[idx]

                val yL = y[rowBase + xm1]
                val yR = y[rowBase + xp1]
                val yU = y[rowU + px]
                val yD = y[rowD + px]

                // Isolated impulse grain detection:
                // On a coherent edge, at least 1-2 immediate neighbors share nearly the same luminance
                // (dMin ≈ 0). On an isolated noise spike, ALL 4 neighbors differ strongly (dMin large).
                val dMin = min(min(abs(cy - yL), abs(cy - yR)), min(abs(cy - yU), abs(cy - yD)))
                val isImpulseSpike = dMin > (0.075f + detailNorm * 0.06f)

                // Local directional gradient strength protects crisp edges and fine micro-contrast
                val gradH = abs(yR - yL)
                val gradV = abs(yD - yU)
                val edgeMag = max(gradH, gradV)
                val detailEdgeGuard = if (isImpulseSpike) {
                    1.0f
                } else {
                    // When Detail is high, steep coherent edges get tighter range protection
                    val guardScale = 1.6f + detailNorm * 5.2f
                    (1f - edgeMag * guardScale).coerceIn(0.10f, 1.0f)
                }

                val sigmaR = if (isImpulseSpike) {
                    max(baseSigmaR * 3.5f, dMin * 1.45f)
                } else {
                    (baseSigmaR * (0.45f + 0.55f * detailEdgeGuard)).coerceAtLeast(0.012f)
                }
                val invTwoSigmaR2 = 1f / (2f * sigmaR * sigmaR)

                var wSum = if (isImpulseSpike) 0.08f else (1.0f + detailNorm * 0.45f)
                var yAcc = cy * wSum

                // Inner 3×3 bilateral neighborhood
                for (dy in -1..1) {
                    val ny = (py + dy).coerceIn(0, height - 1)
                    val nRow = ny * width
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = (px + dx).coerceIn(0, width - 1)
                        val nyVal = y[nRow + nx]
                        val dY = nyVal - cy
                        val spatialW = if (dx == 0 || dy == 0) 1.0f else 0.72f
                        val w = spatialW * exp(-(dY * dY) * invTwoSigmaR2)
                        wSum += w
                        yAcc += nyVal * w
                    }
                }

                // Multi-scale outer ring (stride 2) for broader low-frequency grain suppression
                if (outerRadius > 0 && !isImpulseSpike) {
                    val outerScale = (0.45f + 0.40f * lumaNorm) * (1f - detailNorm * 0.35f)
                    for (k in 0 until 8) {
                        val nx = (px + outerDx[k]).coerceIn(0, width - 1)
                        val ny = (py + outerDy[k]).coerceIn(0, height - 1)
                        val nyVal = y[ny * width + nx]
                        val dY = nyVal - cy
                        val spatialW = if (k < 4) 0.55f else 0.38f
                        val w = spatialW * outerScale * exp(-(dY * dY) * invTwoSigmaR2)
                        wSum += w
                        yAcc += nyVal * w
                    }
                }

                val bilateralY = yAcc / wSum
                val effectiveBlend = if (isImpulseSpike) {
                    (lumaNorm * 0.92f).coerceIn(0f, 0.96f)
                } else {
                    (blendBase * (0.25f + 0.75f * detailEdgeGuard)).coerceIn(0f, 0.94f)
                }

                outY[idx] = (cy + (bilateralY - cy) * effectiveBlend).coerceIn(0f, 1f)
            }
        }
    }

    /**
     * Restores local midtone punch lost during heavy luminance denoising via a wide
     * edge-respecting unsharp/clarity pass weighted toward midtones so shadow noise is never
     * re-amplified and bright highlights never halo.
     */
    private fun applyMidtoneContrastRecovery(
        yClean: FloatArray,
        outY: FloatArray,
        width: Int,
        height: Int,
        contrastNorm: Float,
        lumaNorm: Float
    ) {
        val radius = 3
        val gain = contrastNorm * (0.42f + 0.45f * lumaNorm)
        val dxOff = intArrayOf(radius, -radius, 0, 0, radius, -radius, radius, -radius)
        val dyOff = intArrayOf(0, 0, radius, -radius, radius, radius, -radius, -radius)

        for (py in 0 until height) {
            val rowBase = py * width
            for (px in 0 until width) {
                val idx = rowBase + px
                val cy = yClean[idx]
                var acc = cy * 2.0f
                var wSum = 2.0f
                for (k in 0 until 8) {
                    val nx = (px + dxOff[k]).coerceIn(0, width - 1)
                    val ny = (py + dyOff[k]).coerceIn(0, height - 1)
                    val nyVal = yClean[ny * width + nx]
                    val spatialW = if (k < 4) 1.0f else 0.707f
                    // Gentle edge guard prevents dark/bright halos around high-contrast boundaries
                    val edgeW = exp(-((nyVal - cy) * (nyVal - cy)) * 18f)
                    val w = spatialW * edgeW
                    acc += nyVal * w
                    wSum += w
                }
                val localMean = acc / wSum
                val midtoneMask = (4f * cy * (1f - cy)).coerceIn(0f, 1f)
                val delta = ((cy - localMean) * gain * (0.35f + 0.65f * midtoneMask))
                    .coerceIn(-0.14f, 0.14f)
                outY[idx] = (cy + delta).coerceIn(0f, 1f)
            }
        }
    }

    private fun hypotChroma(a: Float, b: Float): Float = sqrt(a * a + b * b)

    /**
     * Denoises [source] into a newly allocated ARGB_8888 [Bitmap] 100% offline.
     *
     * When [params.denoiseAiClean] is true and `nafnet_denoise.onnx` (or `scunet_denoise.onnx`)
     * is bundled in `app/src/main/assets/`, runs tiled 256×256 ONNX inference with 16px overlap
     * feathering and falls back automatically to [applyDenoiseToRgbPlanes] if no ONNX asset is
     * bundled or if runtime inference fails.
     */
    fun denoise(
        context: Context,
        source: Bitmap,
        params: GradingParams,
        onProgress: ((Float) -> Unit)? = null
    ): DenoiseResult {
        require(!source.isRecycled) { "Source bitmap is recycled" }
        val srcBitmap = if (source.config == Bitmap.Config.ARGB_8888) {
            source
        } else {
            source.copy(Bitmap.Config.ARGB_8888, false)
        }
        val ownsConvertedSource = srcBitmap !== source

        try {
            onProgress?.invoke(0f)

            if (params.denoiseAiClean) {
                val onnx = getOrInitSession(context)
                if (onnx != null) {
                    try {
                        val aiBitmap = runTiledOnnxDenoise(
                            onnx = onnx,
                            source = srcBitmap,
                            onProgress = onProgress
                        )
                        onProgress?.invoke(1f)
                        return DenoiseResult(
                            bitmap = aiBitmap,
                            backend = DenoiseBackend.ONNX_AI_CLEAN,
                            modelName = onnx.modelName,
                            accelerationProvider = onnx.providerName
                        )
                    } catch (oom: OutOfMemoryError) {
                        throw oom
                    } catch (t: Throwable) {
                        Log.w(TAG, "ONNX AI denoise failed; falling back to YCbCr edge-preserving solver", t)
                    }
                }
            }

            val fallbackBitmap = denoiseBitmapYCbCr(srcBitmap, params, onProgress)
            onProgress?.invoke(1f)
            return DenoiseResult(
                bitmap = fallbackBitmap,
                backend = DenoiseBackend.YCBCR_EDGE_PRESERVING,
                modelName = null,
                accelerationProvider = null
            )
        } finally {
            if (ownsConvertedSource && !srcBitmap.isRecycled) {
                srcBitmap.recycle()
            }
        }
    }

    private fun denoiseBitmapYCbCr(
        source: Bitmap,
        params: GradingParams,
        onProgress: ((Float) -> Unit)?
    ): Bitmap {
        val w = source.width
        val h = source.height
        val total = w * h
        val pixels = IntArray(total)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        onProgress?.invoke(0.15f)

        val r = FloatArray(total)
        val g = FloatArray(total)
        val b = FloatArray(total)
        val inv255 = 1f / 255f
        for (i in 0 until total) {
            val px = pixels[i]
            r[i] = ((px shr 16) and 0xFF) * inv255
            g[i] = ((px shr 8) and 0xFF) * inv255
            b[i] = (px and 0xFF) * inv255
        }
        onProgress?.invoke(0.35f)

        applyDenoiseToRgbPlanes(r, g, b, w, h, params)
        onProgress?.invoke(0.85f)

        for (i in 0 until total) {
            val a = pixels[i] and 0xFF000000.toInt()
            val ri = (r[i].coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)
            val gi = (g[i].coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)
            val bi = (b[i].coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)
            pixels[i] = a or (ri shl 16) or (gi shl 8) or bi
        }

        val out = createBitmap(w, h)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    // -------------------------------------------------------------------------
    // Tiled 256×256 ONNX Inference with 16px Overlap Feathering
    // -------------------------------------------------------------------------

    @VisibleForTesting
    internal fun computeTilePlan(
        srcWidth: Int,
        srcHeight: Int,
        tileSize: Int = TILE_SIZE,
        overlap: Int = TILE_OVERLAP
    ): List<TileWindow> {
        require(srcWidth > 0 && srcHeight > 0) { "Invalid image dimensions ${srcWidth}x$srcHeight" }
        val effectiveTile = tileSize.coerceAtLeast(32)
        val safeOverlap = overlap.coerceIn(0, effectiveTile / 4)
        val stride = (effectiveTile - safeOverlap).coerceAtLeast(1)

        val xStarts = buildAxisTileStarts(srcWidth, effectiveTile, stride)
        val yStarts = buildAxisTileStarts(srcHeight, effectiveTile, stride)

        val plan = ArrayList<TileWindow>(xStarts.size * yStarts.size)
        for (rowIdx in yStarts.indices) {
            val y0 = yStarts[rowIdx]
            val y1 = min(srcHeight, y0 + effectiveTile)
            for (colIdx in xStarts.indices) {
                val x0 = xStarts[colIdx]
                val x1 = min(srcWidth, x0 + effectiveTile)
                plan.add(
                    TileWindow(
                        srcX0 = x0,
                        srcY0 = y0,
                        srcX1 = x1,
                        srcY1 = y1,
                        rowIndex = rowIdx,
                        colIndex = colIdx,
                        hasLeftNeighbor = colIdx > 0,
                        hasRightNeighbor = colIdx < xStarts.lastIndex,
                        hasTopNeighbor = rowIdx > 0,
                        hasBottomNeighbor = rowIdx < yStarts.lastIndex
                    )
                )
            }
        }
        return plan
    }

    private fun buildAxisTileStarts(length: Int, tileSize: Int, stride: Int): IntArray {
        if (length <= tileSize) return intArrayOf(0)
        val starts = ArrayList<Int>(ceil(length.toDouble() / stride.toDouble()).toInt() + 1)
        var pos = 0
        while (true) {
            if (pos + tileSize >= length) {
                val clamped = max(0, length - tileSize)
                if (starts.isEmpty() || starts.last() != clamped) {
                    starts.add(clamped)
                }
                break
            }
            starts.add(pos)
            pos += stride
        }
        return starts.toIntArray()
    }

    @VisibleForTesting
    internal fun buildFeatherWeights(
        length: Int,
        featherLeft: Int,
        featherRight: Int
    ): FloatArray {
        val weights = FloatArray(length) { 1f }
        val leftSpan = featherLeft.coerceIn(0, length)
        val rightSpan = featherRight.coerceIn(0, length)
        if (leftSpan > 0) {
            for (i in 0 until leftSpan) {
                val t = (i + 0.5f) / leftSpan.toFloat()
                weights[i] *= (t * t * (3f - 2f * t)).coerceIn(0.01f, 1f)
            }
        }
        if (rightSpan > 0) {
            for (i in 0 until rightSpan) {
                val idx = length - 1 - i
                val t = (i + 0.5f) / rightSpan.toFloat()
                weights[idx] *= (t * t * (3f - 2f * t)).coerceIn(0.01f, 1f)
            }
        }
        return weights
    }

    private fun runTiledOnnxDenoise(
        onnx: LoadedOnnxSession,
        source: Bitmap,
        onProgress: ((Float) -> Unit)?
    ): Bitmap {
        val srcW = source.width
        val srcH = source.height
        val plan = computeTilePlan(srcW, srcH, TILE_SIZE, TILE_OVERLAP)
        val output = createBitmap(srcW, srcH)

        // Row-band accumulation keeps peak RAM proportional to a single tile row (<= 256px tall)
        val rowsByIndex = plan.groupBy { it.rowIndex }.toSortedMap()
        val rowKeys = rowsByIndex.keys.toList()
        val totalTiles = plan.size.coerceAtLeast(1)
        var completedTiles = 0

        var activeBandY0 = 0
        var activeBandH = 0
        var accR = FloatArray(0)
        var accG = FloatArray(0)
        var accB = FloatArray(0)
        var accW = FloatArray(0)
        var flushRowBuf = IntArray(srcW)

        fun flushRows(fromY: Int, toYExclusive: Int) {
            if (toYExclusive <= fromY) return
            for (y in fromY until toYExclusive) {
                val localY = y - activeBandY0
                val rowOff = localY * srcW
                for (x in 0 until srcW) {
                    val idx = rowOff + x
                    val w = accW[idx]
                    if (w > 1e-6f) {
                        val invW = 1f / w
                        val r = (accR[idx] * invW + 0.5f).toInt().coerceIn(0, 255)
                        val g = (accG[idx] * invW + 0.5f).toInt().coerceIn(0, 255)
                        val b = (accB[idx] * invW + 0.5f).toInt().coerceIn(0, 255)
                        flushRowBuf[x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    } else {
                        flushRowBuf[x] = 0xFF000000.toInt()
                    }
                }
                output.setPixels(flushRowBuf, 0, srcW, 0, y, srcW, 1)
            }
        }

        for (rIdx in rowKeys.indices) {
            val rowTiles = rowsByIndex[rowKeys[rIdx]] ?: continue
            val rowY0 = rowTiles.first().srcY0
            val rowY1 = rowTiles.first().srcY1
            val nextRowY0 = if (rIdx + 1 < rowKeys.size) {
                rowsByIndex[rowKeys[rIdx + 1]]?.firstOrNull()?.srcY0 ?: srcH
            } else {
                srcH
            }

            val prevEndY = activeBandY0 + activeBandH
            val overlapCarry = max(0, prevEndY - rowY0)
            val newBandH = rowY1 - rowY0
            val newSize = srcW * newBandH

            val nextAccR = FloatArray(newSize)
            val nextAccG = FloatArray(newSize)
            val nextAccB = FloatArray(newSize)
            val nextAccW = FloatArray(newSize)

            if (overlapCarry > 0 && activeBandH > 0) {
                val srcOffsetRow = rowY0 - activeBandY0
                val copyRows = min(overlapCarry, newBandH)
                val count = copyRows * srcW
                System.arraycopy(accR, srcOffsetRow * srcW, nextAccR, 0, count)
                System.arraycopy(accG, srcOffsetRow * srcW, nextAccG, 0, count)
                System.arraycopy(accB, srcOffsetRow * srcW, nextAccB, 0, count)
                System.arraycopy(accW, srcOffsetRow * srcW, nextAccW, 0, count)
            }

            activeBandY0 = rowY0
            activeBandH = newBandH
            accR = nextAccR
            accG = nextAccG
            accB = nextAccB
            accW = nextAccW

            for (tile in rowTiles) {
                val tileRgb = runOnnxTile(onnx, source, tile)
                val tileW = tile.width
                val tileH = tile.height

                val wx = buildFeatherWeights(
                    length = tileW,
                    featherLeft = if (tile.hasLeftNeighbor) TILE_OVERLAP else 0,
                    featherRight = if (tile.hasRightNeighbor) TILE_OVERLAP else 0
                )
                val wy = buildFeatherWeights(
                    length = tileH,
                    featherLeft = if (tile.hasTopNeighbor) TILE_OVERLAP else 0,
                    featherRight = if (tile.hasBottomNeighbor) TILE_OVERLAP else 0
                )

                for (ty in 0 until tileH) {
                    val outY = tile.srcY0 + ty
                    val bandRowOff = (outY - activeBandY0) * srcW + tile.srcX0
                    val tileRowOff = ty * tileW
                    val wY = wy[ty]
                    for (tx in 0 until tileW) {
                        val px = tileRgb[tileRowOff + tx]
                        val w = wY * wx[tx]
                        val dstIdx = bandRowOff + tx
                        accR[dstIdx] += ((px shr 16) and 0xFF) * w
                        accG[dstIdx] += ((px shr 8) and 0xFF) * w
                        accB[dstIdx] += (px and 0xFF) * w
                        accW[dstIdx] += w
                    }
                }

                completedTiles++
                onProgress?.invoke((completedTiles.toFloat() / totalTiles.toFloat()).coerceIn(0f, 0.99f))
            }

            val flushEndY = min(rowY1, nextRowY0)
            flushRows(rowY0, flushEndY)
        }

        return output
    }

    private fun runOnnxTile(
        onnx: LoadedOnnxSession,
        source: Bitmap,
        tile: TileWindow
    ): IntArray {
        val rawW = tile.width
        val rawH = tile.height
        val rawPixels = IntArray(rawW * rawH)
        source.getPixels(rawPixels, 0, rawW, tile.srcX0, tile.srcY0, rawW, rawH)

        val inSpec = onnx.ioSpec.input
        val modelW = inSpec.fixedWidth ?: rawW
        val modelH = inSpec.fixedHeight ?: rawH

        val paddedPixels = if (modelW == rawW && modelH == rawH) {
            rawPixels
        } else {
            padTileReflect(rawPixels, rawW, rawH, modelW, modelH)
        }

        val inputTensor = createInputTensor(onnx.env, inSpec, paddedPixels, modelW, modelH)
        inputTensor.use { tensor ->
            onnx.session.run(mapOf(inSpec.name to tensor)).use { results ->
                val outOpt = results.get(onnx.ioSpec.output.name)
                val outValue = if (outOpt.isPresent) outOpt.get() else results.get(0)
                val outTensor = outValue as? OnnxTensor
                    ?: throw IllegalStateException("Expected OnnxTensor output from denoise model")
                val outInfo = outTensor.info
                val fullOut = decodeOutputTensorToArgb(
                    tensor = outTensor,
                    info = outInfo,
                    fallbackLayout = onnx.ioSpec.output.layout,
                    expectedW = modelW,
                    expectedH = modelH
                )
                return if (modelW == rawW && modelH == rawH) {
                    fullOut
                } else {
                    cropArgb(fullOut, modelW, rawW, rawH)
                }
            }
        }
    }

    private fun padTileReflect(
        src: IntArray,
        srcW: Int,
        srcH: Int,
        dstW: Int,
        dstH: Int
    ): IntArray {
        val out = IntArray(dstW * dstH)
        for (y in 0 until dstH) {
            val sy = min(y, srcH - 1)
            val sRow = sy * srcW
            val dRow = y * dstW
            for (x in 0 until dstW) {
                val sx = min(x, srcW - 1)
                out[dRow + x] = src[sRow + sx]
            }
        }
        return out
    }

    private fun cropArgb(src: IntArray, srcW: Int, cropW: Int, cropH: Int): IntArray {
        val out = IntArray(cropW * cropH)
        for (y in 0 until cropH) {
            System.arraycopy(src, y * srcW, out, y * cropW, cropW)
        }
        return out
    }

    private fun createInputTensor(
        env: OrtEnvironment,
        spec: DenoiseTensorSpec,
        pixels: IntArray,
        width: Int,
        height: Int
    ): OnnxTensor {
        val count = width * height
        val shape = when (spec.layout) {
            TensorLayout.NCHW -> longArrayOf(1L, 3L, height.toLong(), width.toLong())
            TensorLayout.NHWC -> longArrayOf(1L, height.toLong(), width.toLong(), 3L)
        }

        return when (spec.javaType) {
            OnnxJavaType.UINT8 -> {
                val buf = ByteBuffer.allocateDirect(count * 3).order(ByteOrder.nativeOrder())
                when (spec.layout) {
                    TensorLayout.NCHW -> {
                        for (i in 0 until count) buf.put(((pixels[i] shr 16) and 0xFF).toByte())
                        for (i in 0 until count) buf.put(((pixels[i] shr 8) and 0xFF).toByte())
                        for (i in 0 until count) buf.put((pixels[i] and 0xFF).toByte())
                    }
                    TensorLayout.NHWC -> {
                        for (i in 0 until count) {
                            val px = pixels[i]
                            buf.put(((px shr 16) and 0xFF).toByte())
                            buf.put(((px shr 8) and 0xFF).toByte())
                            buf.put((px and 0xFF).toByte())
                        }
                    }
                }
                buf.rewind()
                OnnxTensor.createTensor(env, buf, shape, OnnxJavaType.UINT8)
            }
            else -> {
                val data = FloatArray(count * 3)
                val inv255 = 1f / 255f
                when (spec.layout) {
                    TensorLayout.NCHW -> {
                        val gOff = count
                        val bOff = count * 2
                        for (i in 0 until count) {
                            val px = pixels[i]
                            data[i] = ((px shr 16) and 0xFF) * inv255
                            data[gOff + i] = ((px shr 8) and 0xFF) * inv255
                            data[bOff + i] = (px and 0xFF) * inv255
                        }
                    }
                    TensorLayout.NHWC -> {
                        var o = 0
                        for (i in 0 until count) {
                            val px = pixels[i]
                            data[o++] = ((px shr 16) and 0xFF) * inv255
                            data[o++] = ((px shr 8) and 0xFF) * inv255
                            data[o++] = (px and 0xFF) * inv255
                        }
                    }
                }
                OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape)
            }
        }
    }

    private fun decodeOutputTensorToArgb(
        tensor: OnnxTensor,
        info: TensorInfo,
        fallbackLayout: TensorLayout,
        expectedW: Int,
        expectedH: Int
    ): IntArray {
        val shape = info.shape
        val layout = inferLayout(shape, fallbackLayout)
        val outH: Int
        val outW: Int
        if (shape.size == 4) {
            if (layout == TensorLayout.NCHW) {
                outH = shape[2].toInt().takeIf { it > 0 } ?: expectedH
                outW = shape[3].toInt().takeIf { it > 0 } ?: expectedW
            } else {
                outH = shape[1].toInt().takeIf { it > 0 } ?: expectedH
                outW = shape[2].toInt().takeIf { it > 0 } ?: expectedW
            }
        } else {
            outH = expectedH
            outW = expectedW
        }

        val count = outW * outH
        val pixels = IntArray(count)

        when (info.type) {
            OnnxJavaType.UINT8 -> {
                val bb = tensor.byteBuffer.duplicate()
                bb.rewind()
                when (layout) {
                    TensorLayout.NCHW -> {
                        for (i in 0 until count) {
                            val r = bb.get(i).toInt() and 0xFF
                            val g = bb.get(count + i).toInt() and 0xFF
                            val b = bb.get(count * 2 + i).toInt() and 0xFF
                            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                        }
                    }
                    TensorLayout.NHWC -> {
                        var o = 0
                        for (i in 0 until count) {
                            val r = bb.get(o++).toInt() and 0xFF
                            val g = bb.get(o++).toInt() and 0xFF
                            val b = bb.get(o++).toInt() and 0xFF
                            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                        }
                    }
                }
            }
            else -> {
                val fb = tensor.floatBuffer.duplicate()
                fb.rewind()
                val raw = FloatArray(min(fb.remaining(), count * 3))
                fb.get(raw)

                // Detect [0,1] vs [-1,1] vs [0,255] float output dynamically
                var minVal = Float.POSITIVE_INFINITY
                var maxVal = Float.NEGATIVE_INFINITY
                val step = max(1, raw.size / 1024)
                var idx = 0
                while (idx < raw.size) {
                    val v = raw[idx]
                    if (v < minVal) minVal = v
                    if (v > maxVal) maxVal = v
                    idx += step
                }
                val isMinusOneToOne = minVal < -0.05f && maxVal <= 1.25f
                val isZeroTo255 = maxVal > 2.0f

                fun toByte(v: Float): Int {
                    val scaled = when {
                        isZeroTo255 -> v
                        isMinusOneToOne -> (v + 1f) * 127.5f
                        else -> v * 255f
                    }
                    return (scaled + 0.5f).toInt().coerceIn(0, 255)
                }

                when (layout) {
                    TensorLayout.NCHW -> {
                        val gOff = count
                        val bOff = count * 2
                        for (i in 0 until count) {
                            val r = toByte(raw[i])
                            val g = toByte(raw[gOff + i])
                            val b = toByte(raw[bOff + i])
                            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                        }
                    }
                    TensorLayout.NHWC -> {
                        var o = 0
                        for (i in 0 until count) {
                            val r = toByte(raw[o++])
                            val g = toByte(raw[o++])
                            val b = toByte(raw[o++])
                            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                        }
                    }
                }
            }
        }
        return pixels
    }

    // -------------------------------------------------------------------------
    // Lazy ONNX Model Extraction & Session Initialization (XNNPACK / CPU)
    // -------------------------------------------------------------------------

    private fun getOrInitSession(context: Context): LoadedOnnxSession? {
        loadedSession?.let { return it }
        if (onnxInitAttempted) return null

        synchronized(sessionLock) {
            loadedSession?.let { return it }
            if (onnxInitAttempted) return null
            onnxInitAttempted = true

            val modelName = findBundledModelAssetName(context) ?: return null
            val modelFile = extractModelOnce(context, modelName) ?: return null

            return try {
                val env = OrtEnvironment.getEnvironment()
                val (session, providerName) = createAcceleratedSession(env, modelFile)
                val ioSpec = inspectModelIo(session)
                LoadedOnnxSession(
                    env = env,
                    session = session,
                    modelName = modelName,
                    providerName = providerName,
                    ioSpec = ioSpec
                ).also {
                    loadedSession = it
                    Log.i(TAG, "Loaded offline ONNX denoiser ($modelName) via $providerName")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Unable to initialize ONNX denoiser ($modelName); using YCbCr fallback", t)
                null
            }
        }
    }

    private fun extractModelOnce(context: Context, assetName: String): File? {
        val dir = File(context.cacheDir, "onnx").apply { mkdirs() }
        val dst = File(dir, assetName)
        if (dst.exists() && dst.length() > 0L) return dst

        val tmp = File(dir, "$assetName.tmp")
        return try {
            context.assets.open(assetName).use { input ->
                tmp.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
            }
            if (tmp.renameTo(dst)) dst else {
                tmp.copyTo(dst, overwrite = true)
                tmp.delete()
                dst
            }
        } catch (t: Throwable) {
            tmp.delete()
            Log.w(TAG, "Failed extracting $assetName to cacheDir/onnx/", t)
            null
        }
    }

    private fun createAcceleratedSession(
        env: OrtEnvironment,
        modelFile: File
    ): Pair<OrtSession, String> {
        val numThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

        try {
            val opts = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setIntraOpNumThreads(numThreads)
                addXnnpack(mapOf("intra_op_num_threads" to numThreads.toString()))
            }
            return env.createSession(modelFile.absolutePath, opts) to "XNNPACK"
        } catch (_: Throwable) {
            // Fall through to standard CPU EP
        }

        val cpuOpts = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(numThreads)
        }
        return env.createSession(modelFile.absolutePath, cpuOpts) to "CPU"
    }

    private fun inspectModelIo(session: OrtSession): DenoiseModelIoSpec {
        val inputEntry = session.inputInfo.entries.firstOrNull()
            ?: throw IllegalStateException("Denoise ONNX model exposes no inputs")
        val outputEntry = session.outputInfo.entries.firstOrNull()
            ?: throw IllegalStateException("Denoise ONNX model exposes no outputs")
        val inSpec = parseTensorSpec(inputEntry.key, inputEntry.value)
        val outSpec = parseTensorSpec(outputEntry.key, outputEntry.value)
        return DenoiseModelIoSpec(inSpec, outSpec)
    }

    private fun parseTensorSpec(name: String, nodeInfo: NodeInfo): DenoiseTensorSpec {
        val valueInfo: ValueInfo = nodeInfo.info
        val tensorInfo = valueInfo as? TensorInfo
            ?: throw IllegalStateException("ONNX tensor '$name' is not a TensorInfo")
        val shape = tensorInfo.shape ?: longArrayOf(1L, 3L, -1L, -1L)
        val layout = inferLayout(shape, TensorLayout.NCHW)
        val fixedH: Int?
        val fixedW: Int?
        if (shape.size == 4) {
            if (layout == TensorLayout.NCHW) {
                fixedH = shape[2].toInt().takeIf { it > 0 }
                fixedW = shape[3].toInt().takeIf { it > 0 }
            } else {
                fixedH = shape[1].toInt().takeIf { it > 0 }
                fixedW = shape[2].toInt().takeIf { it > 0 }
            }
        } else {
            fixedH = null
            fixedW = null
        }
        return DenoiseTensorSpec(
            name = name,
            javaType = tensorInfo.type,
            shape = shape,
            layout = layout,
            fixedHeight = fixedH,
            fixedWidth = fixedW
        )
    }

    @VisibleForTesting
    internal fun inferLayout(
        shape: LongArray,
        defaultLayout: TensorLayout = TensorLayout.NCHW
    ): TensorLayout {
        if (shape.size != 4) return defaultLayout
        val c1 = shape[1]
        val c3 = shape[3]
        return when {
            c1 == 3L && c3 != 3L -> TensorLayout.NCHW
            c3 == 3L && c1 != 3L -> TensorLayout.NHWC
            else -> defaultLayout
        }
    }
}
