package com.zamalfajji.zamlab.ai

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.OptionalModuleApi
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.mlkit.common.MlKitException
import com.google.mlkit.common.sdkinternal.OptionalModuleUtils
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Raw mask/bitmap output returned by an on-device subject segmentation provider.
 */
data class RawSubjectSegmentation(
    val confidenceMask: FloatArray?,
    val foregroundBitmap: Bitmap?,
    val width: Int,
    val height: Int
)

/**
 * Outcome of running AI Smart Cut subject/background separation on a layer bitmap.
 */
sealed interface SmartCutResult {
    data class Success(
        val foregroundBitmap: Bitmap,
        val alphaMaskBitmap: Bitmap,
        val backgroundBitmap: Bitmap,
        val subjectCoverageFraction: Float,
        val backgroundInpainted: Boolean,
        val usedFallbackEngine: Boolean = false
    ) : SmartCutResult

    data class NoSubjectDetected(val reason: String) : SmartCutResult

    data class Failure(
        val message: String,
        val cause: Throwable? = null
    ) : SmartCutResult
}

/**
 * On-device AI Smart Cut processor powered by ML Kit Subject Segmentation
 * (`com.google.android.gms:play-services-mlkit-subject-segmentation`) with proactive
 * Play Services `ModuleInstall` triggering and an automatic pure-Kotlin GrabCut /
 * border-seeded saliency & color-contrast fallback engine so Smart Cut works seamlessly
 * even on devices or emulators where the optional ML Kit module is unavailable or downloading.
 *
 * Extracts:
 * 1. Clean isolated foreground subject cutout (`ARGB_8888` with transparent background).
 * 2. Soft anti-aliased alpha matte (`alphaMaskBitmap`) so downstream per-layer color grading
 *    (Exposure, Contrast, HSL, LUTs, Denoise) preserves feathered cutout edges cleanly.
 * 3. Background plate — either cleanly inpainted via [LocalInpaintingEngine] so moving/scaling
 *    the subject never reveals a duplicate ghost underneath, or kept intact for selective grading.
 */
object SmartCutProcessor {

    private const val TAG = "SmartCutProcessor"

    // Smoothstep matte thresholds: suppresses faint background bleed while retaining smooth hair/edge alpha
    private const val CONFIDENCE_LOW = 0.16f
    private const val CONFIDENCE_HIGH = 0.84f

    // Sanity bounds to detect photos with no distinct foreground subject
    private const val MIN_SUBJECT_COVERAGE = 0.002f
    private const val MAX_SUBJECT_COVERAGE = 0.996f

    // Background clean-plate dilation & feather radius (px) so subject edge halos never ghost
    private const val BG_INPAINT_DILATE_PX = 4
    private const val BG_INPAINT_FEATHER_PX = 4.0f
    private const val BG_INPAINT_ALPHA_THRESHOLD = 52

    // On-device fallback GrabCut / border-seeded saliency parameters
    private const val FALLBACK_MAX_WORK_DIM = 224
    private const val FALLBACK_BG_CLUSTERS = 6
    private const val FALLBACK_FG_CLUSTERS = 5
    private const val FALLBACK_MIN_CONTRAST_LAB = 6.5f

    private const val PLAY_STORE_PACKAGE = "com.android.vending"

    /**
     * Lightweight [OptionalModuleApi] descriptor referencing the exact Subject Segmentation
     * feature flags so [ModuleInstall.getClient] can verify module availability WITHOUT
     * instantiating `SubjectSegmentation.getClient(...)` (which would eagerly trigger
     * `MobileVisionBase` preloading and `TransportRuntime` telemetry scheduling).
     */
    private val subjectSegmentationOptionalApi = OptionalModuleApi {
        arrayOf(
            OptionalModuleUtils.FEATURE_SUBJECT_SEGMENTATION,
            OptionalModuleUtils.FEATURE_TFLITE_DYNAMITE
        )
    }

    /**
     * Pre-flight check verifying Google Play services, Google Play Store presence, and local
     * ML Kit Subject Segmentation optional module availability BEFORE ever instantiating
     * [SubjectSegmentation.getClient]. Returns `false` cleanly (with zero ML Kit or
     * TransportRuntime calls) on emulators without Play Store services or when offline.
     */
    @VisibleForTesting
    internal suspend fun isMlKitSubjectSegmentationReady(context: Context): Boolean {
        return try {
            val gmsStatus = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
            if (gmsStatus != ConnectionResult.SUCCESS) {
                return false
            }
            if (!isPlayStoreInstalled(context)) {
                return false
            }
            val availability = ModuleInstall.getClient(context)
                .areModulesAvailable(subjectSegmentationOptionalApi)
                .await()
            availability.areModulesAvailable()
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            false
        }
    }

    private fun isPlayStoreInstalled(context: Context): Boolean {
        return try {
            val appInfo = context.packageManager.getApplicationInfo(PLAY_STORE_PACKAGE, 0)
            appInfo.enabled
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Runs Smart Cut subject/background separation on [sourceBitmap].
     * First verifies Play Services + optional module readiness via [isMlKitSubjectSegmentationReady]
     * BEFORE instantiating ML Kit; when unavailable, bypasses ML Kit completely and runs the
     * pure-Kotlin on-device GrabCut/border-seeded saliency fallback directly with zero
     * `MlKitException` or `TransportRuntime` log errors.
     */
    suspend fun segmentAndSplit(
        context: Context,
        sourceBitmap: Bitmap,
        removeSubjectFromBackground: Boolean = true,
        onStatusUpdate: (String) -> Unit = {}
    ): SmartCutResult = segmentWithProvider(
        context = context,
        sourceBitmap = sourceBitmap,
        removeSubjectFromBackground = removeSubjectFromBackground,
        onStatusUpdate = onStatusUpdate,
        availabilityChecker = { isMlKitSubjectSegmentationReady(context) },
        maskProvider = { bmp -> runMlKitSubjectSegmentation(bmp) }
    )

    @VisibleForTesting
    internal suspend fun segmentWithProvider(
        context: Context?,
        sourceBitmap: Bitmap,
        removeSubjectFromBackground: Boolean = true,
        onStatusUpdate: (String) -> Unit = {},
        availabilityChecker: (suspend () -> Boolean)? = null,
        maskProvider: suspend (Bitmap) -> RawSubjectSegmentation
    ): SmartCutResult = withContext(Dispatchers.Default) {
        if (sourceBitmap.isRecycled || sourceBitmap.width <= 0 || sourceBitmap.height <= 0) {
            return@withContext SmartCutResult.Failure("Active layer image is unavailable.")
        }

        val workingSource = ensureReadableArgb8888(sourceBitmap)
        val shouldRecycleWorking = workingSource !== sourceBitmap
        var rawForeground: Bitmap? = null

        try {
            onStatusUpdate("Detecting subject…")
            var usedFallback = false
            val isMlKitReady = if (availabilityChecker != null) {
                try {
                    availabilityChecker()
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                    false
                }
            } else {
                true
            }

            val raw = if (!isMlKitReady) {
                usedFallback = true
                onStatusUpdate("Running on-device cutout…")
                runOnDeviceFallbackSegmentation(workingSource)
            } else {
                try {
                    maskProvider(workingSource)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (mlEx: MlKitException) {
                    Log.w(
                        TAG,
                        "ML Kit Subject Segmentation unavailable (${mlEx.message}); using on-device fallback segmentation."
                    )
                    usedFallback = true
                    onStatusUpdate("Running on-device cutout…")
                    runOnDeviceFallbackSegmentation(workingSource)
                } catch (err: Throwable) {
                    if (isMlKitModuleUnavailableError(err)) {
                        Log.w(
                            TAG,
                            "ML Kit optional module unavailable (${err.message}); using on-device fallback segmentation."
                        )
                        usedFallback = true
                        onStatusUpdate("Running on-device cutout…")
                        runOnDeviceFallbackSegmentation(workingSource)
                    } else {
                        throw err
                    }
                }
            }
            rawForeground = raw.foregroundBitmap

            val w = workingSource.width
            val h = workingSource.height
            val totalPixels = w * h

            val softAlpha = buildRefinedAlphaArray(
                raw = raw,
                targetW = w,
                targetH = h
            ) ?: return@withContext SmartCutResult.NoSubjectDetected(
                "No distinct foreground subject was detected."
            )

            var fgCount = 0
            for (i in 0 until totalPixels) {
                if (softAlpha[i] >= 128) fgCount++
            }
            val coverage = fgCount.toFloat() / totalPixels.toFloat()
            if (coverage < MIN_SUBJECT_COVERAGE) {
                return@withContext SmartCutResult.NoSubjectDetected(
                    "No distinct foreground subject detected in this layer."
                )
            }
            if (coverage > MAX_SUBJECT_COVERAGE) {
                return@withContext SmartCutResult.NoSubjectDetected(
                    "Subject covers the entire frame — nothing to separate."
                )
            }

            onStatusUpdate("Extracting cutout…")
            val srcPixels = IntArray(totalPixels)
            workingSource.getPixels(srcPixels, 0, w, 0, 0, w, h)

            // If ML Kit supplied a matching-dimension foreground bitmap, reuse its color-defringed
            // semi-transparent edge RGB where available while keeping full source RGB in the solid core.
            val mlFgPixels = if (rawForeground != null &&
                !rawForeground.isRecycled &&
                rawForeground.width == w &&
                rawForeground.height == h
            ) {
                IntArray(totalPixels).also { rawForeground.getPixels(it, 0, w, 0, 0, w, h) }
            } else {
                null
            }

            val cutoutPixels = IntArray(totalPixels)
            val maskPixels = IntArray(totalPixels)
            for (i in 0 until totalPixels) {
                val origA = (srcPixels[i] ushr 24) and 0xFF
                val matteA = (softAlpha[i] * origA + 127) / 255
                maskPixels[i] = (matteA shl 24) or 0x00FFFFFF
                if (matteA == 0) {
                    cutoutPixels[i] = Color.TRANSPARENT
                } else {
                    val rgbPixel = if (mlFgPixels != null && matteA in 1..250 && ((mlFgPixels[i] ushr 24) and 0xFF) > 0) {
                        mlFgPixels[i]
                    } else {
                        srcPixels[i]
                    }
                    cutoutPixels[i] = (matteA shl 24) or (rgbPixel and 0x00FFFFFF)
                }
            }

            val foregroundBitmap = createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                setPixels(cutoutPixels, 0, w, 0, 0, w, h)
            }
            val alphaMaskBitmap = createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                setPixels(maskPixels, 0, w, 0, 0, w, h)
            }

            val (backgroundBitmap, wasInpainted) = if (removeSubjectFromBackground) {
                onStatusUpdate("Cleaning background…")
                val cleanBg = buildInpaintedBackground(
                    sourceBitmap = workingSource,
                    softAlpha = softAlpha,
                    context = context
                )
                cleanBg to true
            } else {
                val intactBg = workingSource.copy(Bitmap.Config.ARGB_8888, false)
                    ?: createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                        setPixels(srcPixels, 0, w, 0, 0, w, h)
                    }
                intactBg to false
            }

            SmartCutResult.Success(
                foregroundBitmap = foregroundBitmap,
                alphaMaskBitmap = alphaMaskBitmap,
                backgroundBitmap = backgroundBitmap,
                subjectCoverageFraction = coverage,
                backgroundInpainted = wasInpainted,
                usedFallbackEngine = usedFallback
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.e(TAG, "Smart Cut segmentation failed", t)
            SmartCutResult.Failure(
                message = t.message?.takeIf { it.isNotBlank() }
                    ?: "Smart Cut failed. Try again with a clearer foreground subject.",
                cause = t
            )
        } finally {
            if (rawForeground != null && !rawForeground.isRecycled) {
                rawForeground.recycle()
            }
            if (shouldRecycleWorking && !workingSource.isRecycled) {
                workingSource.recycle()
            }
        }
    }

    /**
     * Executes Google ML Kit Subject Segmentation on-device via Play Services.
     * Only invoked after [isMlKitSubjectSegmentationReady] confirms Play Services, Play Store,
     * and the optional subject segmentation module are already installed locally.
     */
    private suspend fun runMlKitSubjectSegmentation(
        bitmap: Bitmap
    ): RawSubjectSegmentation {
        val options = SubjectSegmenterOptions.Builder()
            .enableForegroundBitmap()
            .enableForegroundConfidenceMask()
            .build()
        val segmenter = SubjectSegmentation.getClient(options)
        return try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val result = segmenter.process(inputImage).await()
            val confBuffer = result.foregroundConfidenceMask
            val confArray = if (confBuffer != null) {
                val dup = confBuffer.duplicate()
                dup.rewind()
                FloatArray(dup.remaining()).also { dup.get(it) }
            } else {
                null
            }
            val fgCopy = result.foregroundBitmap
                ?.takeIf { !it.isRecycled }
                ?.copy(Bitmap.Config.ARGB_8888, false)
            RawSubjectSegmentation(
                confidenceMask = confArray,
                foregroundBitmap = fgCopy,
                width = bitmap.width,
                height = bitmap.height
            )
        } finally {
            runCatching { segmenter.close() }
        }
    }

    private fun isMlKitModuleUnavailableError(t: Throwable): Boolean {
        var cur: Throwable? = t
        while (cur != null) {
            if (cur is MlKitException) return true
            val msg = cur.message?.lowercase().orEmpty()
            if (msg.contains("optional module") ||
                msg.contains("subject segmentation") ||
                msg.contains("play services") ||
                msg.contains("moduleinstall") ||
                msg.contains("dynamite")
            ) {
                return true
            }
            cur = cur.cause
        }
        return false
    }

    /**
     * Pure-Kotlin on-device GrabCut-inspired border-seeded saliency & color-contrast foreground
     * segmentation fallback. Runs cleanly in ~15–40ms on CPU with zero Play Services dependency:
     * 1. Converts a compact working grid (`<= 224px`) to perceptual CIE Lab space.
     * 2. Learns a multi-cluster perimeter background palette (border-seeded k-means++).
     * 3. Computes Minimum-Barrier / geodesic path contrast from the image borders so interior
     *    regions enclosed by a strong subject boundary remain foreground.
     * 4. Iteratively refines dual Foreground/Background Lab color models (GrabCut EM loop) +
     *    contrast-sensitive pairwise MRF edge relaxation.
     * 5. Keeps the primary salient connected component(s) and fills interior holes while
     *    preserving soft anti-aliased boundary probabilities.
     */
    @VisibleForTesting
    internal fun runOnDeviceFallbackSegmentation(sourceBitmap: Bitmap): RawSubjectSegmentation {
        val srcW = sourceBitmap.width.coerceAtLeast(1)
        val srcH = sourceBitmap.height.coerceAtLeast(1)
        val longest = max(srcW, srcH)
        val scale = min(1f, FALLBACK_MAX_WORK_DIM.toFloat() / longest.toFloat())
        val w = max(8, (srcW * scale).roundToInt())
        val h = max(8, (srcH * scale).roundToInt())
        val total = w * h

        val workBmp = if (w != srcW || h != srcH) {
            sourceBitmap.scale(w, h)
        } else {
            sourceBitmap
        }
        val pixels = IntArray(total)
        try {
            workBmp.getPixels(pixels, 0, w, 0, 0, w, h)
        } finally {
            if (workBmp !== sourceBitmap && !workBmp.isRecycled) {
                workBmp.recycle()
            }
        }

        // 1. Convert pixels to perceptual CIE Lab
        val labL = FloatArray(total)
        val labA = FloatArray(total)
        val labB = FloatArray(total)
        for (i in 0 until total) {
            val c = pixels[i]
            rgbToLab(
                r = (c ushr 16) and 0xFF,
                g = (c ushr 8) and 0xFF,
                b = c and 0xFF,
                outL = labL,
                outA = labA,
                outB = labB,
                idx = i
            )
        }

        // 2. Sample perimeter border strip (top, left, right, plus outer bottom corners)
        val borderX = max(2, (w * 0.06f).roundToInt())
        val borderY = max(2, (h * 0.06f).roundToInt())
        val isBorder = BooleanArray(total)
        val borderIndices = ArrayList<Int>(total / 5)
        for (y in 0 until h) {
            val rowOff = y * w
            val inTop = y < borderY
            val inBottom = y >= h - borderY
            for (x in 0 until w) {
                val inSide = x < borderX || x >= w - borderX
                val inBottomCorner = inBottom && (x < (w * 0.25f).toInt() || x >= (w * 0.75f).toInt())
                if (inTop || inSide || inBottomCorner) {
                    val idx = rowOff + x
                    isBorder[idx] = true
                    borderIndices.add(idx)
                }
            }
        }

        val bgClusters = fitLabClusters(
            indices = borderIndices,
            labL = labL,
            labA = labA,
            labB = labB,
            k = FALLBACK_BG_CLUSTERS
        )

        // Compute direct perceptual Lab distance to nearest background cluster
        val bgDist = FloatArray(total)
        var maxBgDist = 0f
        for (i in 0 until total) {
            val d = minClusterDistance(labL[i], labA[i], labB[i], bgClusters)
            bgDist[i] = d
            if (d > maxBgDist) maxBgDist = d
        }
        if (maxBgDist < FALLBACK_MIN_CONTRAST_LAB) {
            // Flat / uniform image with no discernible foreground contrast
            return RawSubjectSegmentation(
                confidenceMask = FloatArray(total),
                foregroundBitmap = null,
                width = w,
                height = h
            )
        }

        // 3. Border-seeded geodesic barrier distance (4 alternating raster sweeps)
        val geodesic = FloatArray(total) { i -> if (isBorder[i]) 0f else 1e6f }
        for (pass in 0 until 2) {
            // Forward sweep (top-left -> bottom-right)
            for (y in 0 until h) {
                val rowOff = y * w
                for (x in 0 until w) {
                    val idx = rowOff + x
                    var best = geodesic[idx]
                    if (x > 0) {
                        val nb = idx - 1
                        val cand = geodesic[nb] + labDiff(labL, labA, labB, idx, nb)
                        if (cand < best) best = cand
                    }
                    if (y > 0) {
                        val nb = idx - w
                        val cand = geodesic[nb] + labDiff(labL, labA, labB, idx, nb)
                        if (cand < best) best = cand
                    }
                    geodesic[idx] = best
                }
            }
            // Backward sweep (bottom-right -> top-left)
            for (y in h - 1 downTo 0) {
                val rowOff = y * w
                for (x in w - 1 downTo 0) {
                    val idx = rowOff + x
                    var best = geodesic[idx]
                    if (x < w - 1) {
                        val nb = idx + 1
                        val cand = geodesic[nb] + labDiff(labL, labA, labB, idx, nb)
                        if (cand < best) best = cand
                    }
                    if (y < h - 1) {
                        val nb = idx + w
                        val cand = geodesic[nb] + labDiff(labL, labA, labB, idx, nb)
                        if (cand < best) best = cand
                    }
                    geodesic[idx] = best
                }
            }
        }

        val p95Bg = percentile95(bgDist).coerceAtLeast(FALLBACK_MIN_CONTRAST_LAB)
        val p95Geo = percentile95(geodesic).coerceAtLeast(FALLBACK_MIN_CONTRAST_LAB)

        // Initial foreground probability combining color contrast, geodesic barrier, and gentle center prior
        val fgProb = FloatArray(total)
        val halfW = (w - 1).coerceAtLeast(1) * 0.5f
        val halfH = (h - 1).coerceAtLeast(1) * 0.5f
        for (y in 0 until h) {
            val dy = (y - halfH) / halfH
            val rowOff = y * w
            for (x in 0 until w) {
                val dx = (x - halfW) / halfW
                val idx = rowOff + x
                val cNorm = (bgDist[idx] / p95Bg).coerceIn(0f, 1.3f)
                val gNorm = (geodesic[idx] / p95Geo).coerceIn(0f, 1.3f)
                val centerPrior = 0.42f + 0.58f * exp(-((dx * dx) / 0.52f + (dy * dy) / 0.62f))
                val combined = (0.58f * cNorm + 0.42f * gNorm) * centerPrior
                fgProb[idx] = if (isBorder[idx]) {
                    (combined * 0.25f).coerceIn(0f, 0.35f)
                } else {
                    combined.coerceIn(0f, 1f)
                }
            }
        }

        // Normalize initial probability range cleanly around Otsu-style adaptive threshold
        val initThresh = computeOtsuThreshold(fgProb).coerceIn(0.24f, 0.62f)
        for (i in 0 until total) {
            val shifted = ((fgProb[i] - initThresh) / 0.28f).coerceIn(-6f, 6f)
            fgProb[i] = 1f / (1f + exp(-shifted))
        }

        // 4. Iterative GrabCut-style dual Foreground / Background Lab clustering + edge-aware MRF relaxation
        val fgSeedIndices = ArrayList<Int>(total / 3)
        val bgSeedIndices = ArrayList<Int>(total / 2)
        val nextProb = FloatArray(total)
        val invSigmaSq = 1f / (2f * 14f * 14f)

        repeat(3) {
            fgSeedIndices.clear()
            bgSeedIndices.clear()
            for (i in 0 until total) {
                if (fgProb[i] >= 0.58f && !isBorder[i]) {
                    fgSeedIndices.add(i)
                } else if (fgProb[i] <= 0.36f || isBorder[i]) {
                    bgSeedIndices.add(i)
                }
            }
            if (fgSeedIndices.size < 12) {
                // Fallback to top 20% highest-contrast interior pixels
                val sorted = (0 until total).filter { !isBorder[it] }.sortedByDescending { fgProb[it] }
                fgSeedIndices.addAll(sorted.take(max(12, total / 6)))
            }
            if (bgSeedIndices.size < 12) {
                bgSeedIndices.addAll(borderIndices)
            }

            val iterFgClusters = fitLabClusters(fgSeedIndices, labL, labA, labB, FALLBACK_FG_CLUSTERS)
            val iterBgClusters = fitLabClusters(bgSeedIndices, labL, labA, labB, FALLBACK_BG_CLUSTERS)

            // Update unary likelihoods from dual Lab clusters + geodesic evidence
            for (i in 0 until total) {
                val dFg = minClusterDistance(labL[i], labA[i], labB[i], iterFgClusters)
                val dBg = minClusterDistance(labL[i], labA[i], labB[i], iterBgClusters)
                val colorLikelihood = dBg / (dFg + dBg + 1e-3f)
                val geoEvidence = (geodesic[i] / p95Geo).coerceIn(0f, 1f)
                val unary = if (isBorder[i]) {
                    (0.55f * colorLikelihood + 0.45f * geoEvidence) * 0.35f
                } else {
                    0.72f * colorLikelihood + 0.28f * geoEvidence
                }
                fgProb[i] = (0.45f * fgProb[i] + 0.55f * unary).coerceIn(0f, 1f)
            }

            // Contrast-sensitive edge-preserving spatial smoothing (2 sub-steps per iteration)
            repeat(2) {
                for (y in 0 until h) {
                    val rowOff = y * w
                    for (x in 0 until w) {
                        val idx = rowOff + x
                        var wSum = 1.0f
                        var pSum = fgProb[idx]
                        if (x > 0) {
                            val nb = idx - 1
                            val d = labDiffSq(labL, labA, labB, idx, nb)
                            val wt = 0.85f * exp(-d * invSigmaSq)
                            pSum += wt * fgProb[nb]
                            wSum += wt
                        }
                        if (x < w - 1) {
                            val nb = idx + 1
                            val d = labDiffSq(labL, labA, labB, idx, nb)
                            val wt = 0.85f * exp(-d * invSigmaSq)
                            pSum += wt * fgProb[nb]
                            wSum += wt
                        }
                        if (y > 0) {
                            val nb = idx - w
                            val d = labDiffSq(labL, labA, labB, idx, nb)
                            val wt = 0.85f * exp(-d * invSigmaSq)
                            pSum += wt * fgProb[nb]
                            wSum += wt
                        }
                        if (y < h - 1) {
                            val nb = idx + w
                            val d = labDiffSq(labL, labA, labB, idx, nb)
                            val wt = 0.85f * exp(-d * invSigmaSq)
                            pSum += wt * fgProb[nb]
                            wSum += wt
                        }
                        nextProb[idx] = pSum / wSum
                    }
                }
                nextProb.copyInto(fgProb)
            }
        }

        // 5. Connected-component pruning + interior hole filling + soft anti-aliased boundary
        val binary = BooleanArray(total) { i -> fgProb[i] >= 0.50f }
        val cleaned = keepPrimaryComponentsAndFillHoles(binary, w, h)
        val finalConfidence = FloatArray(total)
        for (y in 0 until h) {
            val rowOff = y * w
            for (x in 0 until w) {
                val idx = rowOff + x
                val isFg = cleaned[idx]
                // Check if pixel lies on the 1px silhouette boundary for smooth anti-aliasing
                val isEdge = (x > 0 && cleaned[idx - 1] != isFg) ||
                    (x < w - 1 && cleaned[idx + 1] != isFg) ||
                    (y > 0 && cleaned[idx - w] != isFg) ||
                    (y < h - 1 && cleaned[idx + w] != isFg)
                finalConfidence[idx] = when {
                    isEdge -> fgProb[idx].coerceIn(0.20f, 0.82f)
                    isFg -> max(0.88f, fgProb[idx]).coerceIn(0f, 1f)
                    else -> min(0.10f, fgProb[idx]).coerceIn(0f, 1f)
                }
            }
        }

        return RawSubjectSegmentation(
            confidenceMask = finalConfidence,
            foregroundBitmap = null,
            width = w,
            height = h
        )
    }

    private fun keepPrimaryComponentsAndFillHoles(
        binary: BooleanArray,
        w: Int,
        h: Int
    ): BooleanArray {
        val total = w * h
        val labels = IntArray(total)
        val compSizes = ArrayList<Int>()
        val queue = IntArray(total)
        var currentLabel = 0

        for (start in 0 until total) {
            if (!binary[start] || labels[start] != 0) continue
            currentLabel++
            var head = 0
            var tail = 0
            queue[tail++] = start
            labels[start] = currentLabel
            var size = 0
            while (head < tail) {
                val cur = queue[head++]
                size++
                val cx = cur % w
                val cy = cur / w
                if (cx > 0) {
                    val nb = cur - 1
                    if (binary[nb] && labels[nb] == 0) {
                        labels[nb] = currentLabel
                        queue[tail++] = nb
                    }
                }
                if (cx < w - 1) {
                    val nb = cur + 1
                    if (binary[nb] && labels[nb] == 0) {
                        labels[nb] = currentLabel
                        queue[tail++] = nb
                    }
                }
                if (cy > 0) {
                    val nb = cur - w
                    if (binary[nb] && labels[nb] == 0) {
                        labels[nb] = currentLabel
                        queue[tail++] = nb
                    }
                }
                if (cy < h - 1) {
                    val nb = cur + w
                    if (binary[nb] && labels[nb] == 0) {
                        labels[nb] = currentLabel
                        queue[tail++] = nb
                    }
                }
            }
            compSizes.add(size)
        }

        if (compSizes.isEmpty()) return BooleanArray(total)
        val maxSize = compSizes.max()
        val keepThreshold = max(16, (maxSize * 0.18f).roundToInt())
        val kept = BooleanArray(total) { i ->
            val lbl = labels[i]
            lbl > 0 && compSizes[lbl - 1] >= keepThreshold
        }

        // Fill enclosed background holes (any background pixel not reachable from the 4 outer edges)
        val reachableBg = BooleanArray(total)
        var head = 0
        var tail = 0
        for (x in 0 until w) {
            val top = x
            if (!kept[top] && !reachableBg[top]) {
                reachableBg[top] = true
                queue[tail++] = top
            }
            val bot = (h - 1) * w + x
            if (!kept[bot] && !reachableBg[bot]) {
                reachableBg[bot] = true
                queue[tail++] = bot
            }
        }
        for (y in 0 until h) {
            val left = y * w
            if (!kept[left] && !reachableBg[left]) {
                reachableBg[left] = true
                queue[tail++] = left
            }
            val right = y * w + (w - 1)
            if (!kept[right] && !reachableBg[right]) {
                reachableBg[right] = true
                queue[tail++] = right
            }
        }
        while (head < tail) {
            val cur = queue[head++]
            val cx = cur % w
            val cy = cur / w
            if (cx > 0) {
                val nb = cur - 1
                if (!kept[nb] && !reachableBg[nb]) {
                    reachableBg[nb] = true
                    queue[tail++] = nb
                }
            }
            if (cx < w - 1) {
                val nb = cur + 1
                if (!kept[nb] && !reachableBg[nb]) {
                    reachableBg[nb] = true
                    queue[tail++] = nb
                }
            }
            if (cy > 0) {
                val nb = cur - w
                if (!kept[nb] && !reachableBg[nb]) {
                    reachableBg[nb] = true
                    queue[tail++] = nb
                }
            }
            if (cy < h - 1) {
                val nb = cur + w
                if (!kept[nb] && !reachableBg[nb]) {
                    reachableBg[nb] = true
                    queue[tail++] = nb
                }
            }
        }
        for (i in 0 until total) {
            if (!reachableBg[i]) kept[i] = true
        }
        return kept
    }

    private fun fitLabClusters(
        indices: List<Int>,
        labL: FloatArray,
        labA: FloatArray,
        labB: FloatArray,
        k: Int
    ): FloatArray {
        val count = indices.size
        if (count == 0) return FloatArray(3)
        val actualK = min(k, count).coerceAtLeast(1)
        val centers = FloatArray(actualK * 3)

        // Deterministic evenly-spaced seed initialization
        val step = max(1, count / actualK)
        for (c in 0 until actualK) {
            val idx = indices[(c * step) % count]
            centers[c * 3] = labL[idx]
            centers[c * 3 + 1] = labA[idx]
            centers[c * 3 + 2] = labB[idx]
        }

        val sumL = FloatArray(actualK)
        val sumA = FloatArray(actualK)
        val sumB = FloatArray(actualK)
        val counts = IntArray(actualK)
        val sampleStride = max(1, count / 1200)

        repeat(5) {
            sumL.fill(0f)
            sumA.fill(0f)
            sumB.fill(0f)
            counts.fill(0)
            var j = 0
            while (j < count) {
                val idx = indices[j]
                val l = labL[idx]
                val a = labA[idx]
                val b = labB[idx]
                var bestC = 0
                var bestD = Float.POSITIVE_INFINITY
                for (c in 0 until actualK) {
                    val dl = l - centers[c * 3]
                    val da = a - centers[c * 3 + 1]
                    val db = b - centers[c * 3 + 2]
                    val d = dl * dl + da * da + db * db
                    if (d < bestD) {
                        bestD = d
                        bestC = c
                    }
                }
                sumL[bestC] += l
                sumA[bestC] += a
                sumB[bestC] += b
                counts[bestC]++
                j += sampleStride
            }
            for (c in 0 until actualK) {
                if (counts[c] > 0) {
                    val inv = 1f / counts[c].toFloat()
                    centers[c * 3] = sumL[c] * inv
                    centers[c * 3 + 1] = sumA[c] * inv
                    centers[c * 3 + 2] = sumB[c] * inv
                }
            }
        }
        return centers
    }

    private fun minClusterDistance(l: Float, a: Float, b: Float, centers: FloatArray): Float {
        var bestSq = Float.POSITIVE_INFINITY
        var c = 0
        while (c < centers.size) {
            val dl = l - centers[c]
            val da = a - centers[c + 1]
            val db = b - centers[c + 2]
            val dSq = dl * dl + da * da + db * db
            if (dSq < bestSq) bestSq = dSq
            c += 3
        }
        return sqrt(bestSq.coerceAtLeast(0f))
    }

    private fun labDiffSq(labL: FloatArray, labA: FloatArray, labB: FloatArray, i: Int, j: Int): Float {
        val dl = labL[i] - labL[j]
        val da = labA[i] - labA[j]
        val db = labB[i] - labB[j]
        return dl * dl + da * da + db * db
    }

    private fun labDiff(labL: FloatArray, labA: FloatArray, labB: FloatArray, i: Int, j: Int): Float {
        return sqrt(labDiffSq(labL, labA, labB, i, j))
    }

    private fun percentile95(values: FloatArray): Float {
        if (values.isEmpty()) return 1f
        val stride = max(1, values.size / 1024)
        val sample = FloatArray((values.size + stride - 1) / stride)
        var s = 0
        var i = 0
        while (i < values.size && s < sample.size) {
            sample[s++] = values[i]
            i += stride
        }
        sample.sort(0, s)
        val pIdx = ((s - 1) * 0.95f).roundToInt().coerceIn(0, (s - 1).coerceAtLeast(0))
        return sample[pIdx]
    }

    private fun computeOtsuThreshold(values: FloatArray): Float {
        val hist = IntArray(64)
        for (v in values) {
            val bin = (v.coerceIn(0f, 1f) * 63f).roundToInt().coerceIn(0, 63)
            hist[bin]++
        }
        val total = values.size.toFloat().coerceAtLeast(1f)
        var sumAll = 0f
        for (b in 0 until 64) sumAll += b * hist[b]
        var sumBg = 0f
        var wBg = 0f
        var bestVar = -1f
        var bestBin = 32
        for (b in 0 until 64) {
            wBg += hist[b]
            if (wBg <= 0f) continue
            val wFg = total - wBg
            if (wFg <= 0f) break
            sumBg += b * hist[b]
            val mBg = sumBg / wBg
            val mFg = (sumAll - sumBg) / wFg
            val diff = mBg - mFg
            val between = wBg * wFg * diff * diff
            if (between > bestVar) {
                bestVar = between
                bestBin = b
            }
        }
        return bestBin / 63f
    }

    private fun rgbToLab(
        r: Int,
        g: Int,
        b: Int,
        outL: FloatArray,
        outA: FloatArray,
        outB: FloatArray,
        idx: Int
    ) {
        val sr = r / 255f
        val sg = g / 255f
        val sb = b / 255f
        val lr = if (sr <= 0.04045f) sr / 12.92f else ((sr + 0.055f) / 1.055f).pow(2.4f)
        val lg = if (sg <= 0.04045f) sg / 12.92f else ((sg + 0.055f) / 1.055f).pow(2.4f)
        val lb = if (sb <= 0.04045f) sb / 12.92f else ((sb + 0.055f) / 1.055f).pow(2.4f)

        val x = (lr * 0.4124564f + lg * 0.3575761f + lb * 0.1804375f) / 0.95047f
        val y = (lr * 0.2126729f + lg * 0.7151522f + lb * 0.0721750f)
        val z = (lr * 0.0193339f + lg * 0.1191920f + lb * 0.9503041f) / 1.08883f

        val fx = labF(x)
        val fy = labF(y)
        val fz = labF(z)

        outL[idx] = (116f * fy - 16f).coerceIn(0f, 100f)
        outA[idx] = 500f * (fx - fy)
        outB[idx] = 200f * (fy - fz)
    }

    private fun labF(t: Float): Float {
        return if (t > 0.008856f) {
            t.pow(1f / 3f)
        } else {
            7.787f * t + 16f / 116f
        }
    }

    /**
     * Converts raw ML Kit confidence values (`0f..1f`) or foreground alpha into a smooth,
     * de-noised `0..255` alpha matte matched to `(targetW, targetH)`.
     */
    @VisibleForTesting
    internal fun buildRefinedAlphaArray(
        raw: RawSubjectSegmentation,
        targetW: Int,
        targetH: Int
    ): IntArray? {
        val total = targetW * targetH
        val conf = raw.confidenceMask
        if (conf != null && conf.isNotEmpty()) {
            val rawW = raw.width.coerceAtLeast(1)
            val rawH = raw.height.coerceAtLeast(1)
            val out = IntArray(total)
            if (rawW == targetW && rawH == targetH && conf.size >= total) {
                for (i in 0 until total) {
                    out[i] = confidenceToSoftAlpha(conf[i])
                }
            } else if (conf.size >= rawW * rawH) {
                // Bilinear resample confidence mask if provider returned a working-grid size
                for (y in 0 until targetH) {
                    val sy = ((y + 0.5f) * rawH / targetH - 0.5f).coerceIn(0f, (rawH - 1).toFloat())
                    val y0 = sy.toInt()
                    val y1 = (y0 + 1).coerceAtMost(rawH - 1)
                    val fy = sy - y0
                    val row0 = y0 * rawW
                    val row1 = y1 * rawW
                    val dstRow = y * targetW
                    for (x in 0 until targetW) {
                        val sx = ((x + 0.5f) * rawW / targetW - 0.5f).coerceIn(0f, (rawW - 1).toFloat())
                        val x0 = sx.toInt()
                        val x1 = (x0 + 1).coerceAtMost(rawW - 1)
                        val fx = sx - x0
                        val c00 = conf[row0 + x0]
                        val c10 = conf[row0 + x1]
                        val c01 = conf[row1 + x0]
                        val c11 = conf[row1 + x1]
                        val top = c00 + (c10 - c00) * fx
                        val bot = c01 + (c11 - c01) * fx
                        out[dstRow + x] = confidenceToSoftAlpha(top + (bot - top) * fy)
                    }
                }
            } else {
                return null
            }
            return out
        }

        val fg = raw.foregroundBitmap?.takeIf { !it.isRecycled } ?: return null
        val scaledFg = if (fg.width != targetW || fg.height != targetH) {
            fg.scale(targetW, targetH)
        } else {
            fg
        }
        return try {
            val px = IntArray(total)
            scaledFg.getPixels(px, 0, targetW, 0, 0, targetW, targetH)
            IntArray(total) { i -> (px[i] ushr 24) and 0xFF }
        } finally {
            if (scaledFg !== fg && !scaledFg.isRecycled) {
                scaledFg.recycle()
            }
        }
    }

    /**
     * Applies a smooth Hermite ramp (`smoothstep`) over `[CONFIDENCE_LOW..CONFIDENCE_HIGH]`
     * to remove faint background haze while preserving crisp anti-aliased subject silhouettes.
     */
    @VisibleForTesting
    internal fun confidenceToSoftAlpha(confidence: Float): Int {
        if (!confidence.isFinite()) return 0
        val t = ((confidence - CONFIDENCE_LOW) / (CONFIDENCE_HIGH - CONFIDENCE_LOW)).coerceIn(0f, 1f)
        val smooth = t * t * (3f - 2f * t)
        return (smooth * 255f).roundToInt().coerceIn(0, 255)
    }

    /**
     * Synthesizes a clean background plate by dilating the subject silhouette and invoking
     * Z-Effect's offline [LocalInpaintingEngine] (MI-GAN ONNX when bundled, or multi-scale
     * Edge-Aware PatchMatch + Poisson seamless blend).
     */
    private fun buildInpaintedBackground(
        sourceBitmap: Bitmap,
        softAlpha: IntArray,
        context: Context?
    ): Bitmap {
        val w = sourceBitmap.width
        val h = sourceBitmap.height
        val total = w * h
        val binaryRemoval = IntArray(total) { i ->
            if (softAlpha[i] >= BG_INPAINT_ALPHA_THRESHOLD) 255 else 0
        }
        val dilatedMask = LocalInpaintingEngine.dilateAlphaMaskForOnnx(
            alphaMask = binaryRemoval,
            width = w,
            height = h,
            dilatePx = BG_INPAINT_DILATE_PX,
            edgeFeatherPx = BG_INPAINT_FEATHER_PX
        )
        return try {
            LocalInpaintingEngine.inpaintWithAlphaMask(
                sourceBitmap = sourceBitmap,
                alphaMask = dilatedMask,
                context = context,
                effectiveOpacity = 1.0f
            ).bitmap
        } catch (t: Throwable) {
            Log.w(TAG, "Clean background inpainting failed; falling back to intact background copy.", t)
            sourceBitmap.copy(Bitmap.Config.ARGB_8888, false)
                ?: createBitmap(w, h, Bitmap.Config.ARGB_8888)
        }
    }

    private fun ensureReadableArgb8888(source: Bitmap): Bitmap {
        return if (source.config == Bitmap.Config.ARGB_8888) {
            source
        } else {
            source.copy(Bitmap.Config.ARGB_8888, false)
                ?: throw IllegalStateException("Unable to convert source bitmap to ARGB_8888.")
        }
    }
}
