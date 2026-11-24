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
import java.io.File
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 100% offline 2x and 4x AI super-resolution engine:
 *
 * - Primary backend (when `app/src/main/assets/realesrgan_x2.onnx` is present):
 *   Lazily checks for `realesrgan_x2.onnx` on first use, extracts it once to
 *   `cacheDir/onnx/realesrgan_x2.onnx`, and initializes an ONNX Runtime [OrtSession]
 *   with XNNPACK / CPU execution providers.
 *   Dynamically inspects `session.inputInfo` and `session.outputInfo` to support
 *   NCHW/NHWC float `[0, 1]` or uint8 `[0, 255]` tensors without hardcoding tensor names.
 *   Processes images in overlapping 256×256 tiles with 16px overlap feathering
 *   (and row-band streaming output accumulation) to prevent OutOfMemoryError on high-res photos.
 *   For 4x mode, executes the tiled 2x pass twice and immediately recycles the intermediate bitmap.
 *
 * - Automatic fallback backend (when `realesrgan_x2.onnx` is not bundled in assets):
 *   Multi-pass separable Lanczos3 (`a = 3`) 2x resampling (run twice for 4x) combined with
 *   edge-aware halo-clamped unsharp sharpening, executed through the same 256×256 tiled pipeline.
 */
object LocalUpscaleEngine {

    private const val TAG = "LocalUpscaleEngine"
    const val MODEL_ASSET_NAME = "realesrgan_x2.onnx"
    const val TILE_SIZE = 256
    const val TILE_OVERLAP = 16
    private const val PASS_SCALE = 2
    private const val LANCZOS_A = 3

    enum class UpscaleScale(val factor: Int, val label: String) {
        X2(2, "2x"),
        X4(4, "4x")
    }

    enum class UpscaleBackend {
        ONNX_REALESRGAN,
        LANCZOS3_FALLBACK
    }

    data class UpscaleResult(
        val bitmap: Bitmap,
        val backend: UpscaleBackend,
        val scaleFactor: Int,
        val accelerationProvider: String? = null
    )

    enum class TensorLayout {
        NCHW,
        NHWC
    }

    internal data class UpscaleTensorSpec(
        val name: String,
        val javaType: OnnxJavaType,
        val shape: LongArray,
        val layout: TensorLayout,
        val fixedHeight: Int?,
        val fixedWidth: Int?
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is UpscaleTensorSpec) return false
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

    internal data class UpscaleModelIoSpec(
        val input: UpscaleTensorSpec,
        val output: UpscaleTensorSpec
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
        val providerName: String,
        val ioSpec: UpscaleModelIoSpec
    )

    private val sessionLock = Any()

    @Volatile
    private var onnxInitAttempted = false

    @Volatile
    private var loadedSession: LoadedOnnxSession? = null

    @Volatile
    private var cachedAssetAvailable: Boolean? = null

    @VisibleForTesting
    internal fun resetOnnxStateForTests() {
        synchronized(sessionLock) {
            runCatching { loadedSession?.session?.close() }
            loadedSession = null
            onnxInitAttempted = false
            cachedAssetAvailable = null
        }
    }

    /**
     * Lightweight check for whether `app/src/main/assets/realesrgan_x2.onnx` is bundled.
     * Does not initialize ONNX Runtime or copy the model until the first upscale invocation.
     */
    fun isOnnxModelAssetAvailable(context: Context): Boolean {
        cachedAssetAvailable?.let { return it }
        val exists = try {
            context.assets.open(MODEL_ASSET_NAME).use { true }
        } catch (_: FileNotFoundException) {
            false
        } catch (_: Throwable) {
            false
        }
        cachedAssetAvailable = exists
        return exists
    }

    /**
     * Upscales [source] by [scale] (2x or 4x) 100% offline.
     * Calls [onProgress] with values in `[0f, 1f]` as tiles/passes complete.
     */
    fun upscale(
        context: Context,
        source: Bitmap,
        scale: UpscaleScale,
        onProgress: ((Float) -> Unit)? = null
    ): UpscaleResult {
        require(!source.isRecycled) { "Source bitmap is recycled" }
        val srcBitmap = if (source.config == Bitmap.Config.ARGB_8888) {
            source
        } else {
            source.copy(Bitmap.Config.ARGB_8888, false)
        }
        val ownsConvertedSource = srcBitmap !== source

        try {
            onProgress?.invoke(0f)
            val onnx = getOrInitSession(context)
            if (onnx != null) {
                try {
                    val out = runMultiPass(
                        source = srcBitmap,
                        scale = scale,
                        onProgress = onProgress
                    ) { passInput, passProgress ->
                        runTiled2xPass(
                            source = passInput,
                            tileProcessor = { tileSrc, tileWindow ->
                                runOnnxTile2x(onnx, tileSrc, tileWindow)
                            },
                            onProgress = passProgress
                        )
                    }
                    onProgress?.invoke(1f)
                    return UpscaleResult(
                        bitmap = out,
                        backend = UpscaleBackend.ONNX_REALESRGAN,
                        scaleFactor = scale.factor,
                        accelerationProvider = onnx.providerName
                    )
                } catch (oom: OutOfMemoryError) {
                    throw oom
                } catch (t: Throwable) {
                    Log.w(TAG, "ONNX Real-ESRGAN pass failed; falling back to Lanczos3 + sharpening", t)
                }
            }

            val fallbackOut = runMultiPass(
                source = srcBitmap,
                scale = scale,
                onProgress = onProgress
            ) { passInput, passProgress ->
                runTiled2xPass(
                    source = passInput,
                    tileProcessor = { _, tileWindow ->
                        runLanczos3SharpenTile2x(passInput, tileWindow)
                    },
                    onProgress = passProgress
                )
            }
            onProgress?.invoke(1f)
            return UpscaleResult(
                bitmap = fallbackOut,
                backend = UpscaleBackend.LANCZOS3_FALLBACK,
                scaleFactor = scale.factor,
                accelerationProvider = null
            )
        } finally {
            if (ownsConvertedSource && !srcBitmap.isRecycled) {
                srcBitmap.recycle()
            }
        }
    }

    private fun runMultiPass(
        source: Bitmap,
        scale: UpscaleScale,
        onProgress: ((Float) -> Unit)?,
        pass2x: (Bitmap, ((Float) -> Unit)?) -> Bitmap
    ): Bitmap {
        return when (scale) {
            UpscaleScale.X2 -> pass2x(source, onProgress)
            UpscaleScale.X4 -> {
                // Pass 1 processes 1/4 the pixels of Pass 2 -> weight progress 0..0.20 and 0.20..1.0
                val pass1 = pass2x(source) { p ->
                    onProgress?.invoke((p * 0.20f).coerceIn(0f, 0.20f))
                }
                try {
                    pass2x(pass1) { p ->
                        onProgress?.invoke((0.20f + p * 0.80f).coerceIn(0.20f, 1f))
                    }
                } finally {
                    if (pass1 !== source && !pass1.isRecycled) {
                        pass1.recycle()
                    }
                }
            }
        }
    }

    /**
     * Builds overlapping tile windows of size up to [tileSize] with [overlap] px overlap
     * between adjacent tiles so boundary feathering eliminates all visible tile seams.
     */
    @VisibleForTesting
    internal fun computeTilePlan(
        width: Int,
        height: Int,
        tileSize: Int = TILE_SIZE,
        overlap: Int = TILE_OVERLAP
    ): List<TileWindow> {
        require(width > 0 && height > 0) { "Invalid image size: ${width}x${height}" }
        val safeTile = max(32, tileSize)
        val safeOverlap = overlap.coerceIn(0, safeTile / 4)
        val xRanges = computeAxisRanges(width, safeTile, safeOverlap)
        val yRanges = computeAxisRanges(height, safeTile, safeOverlap)
        val tiles = ArrayList<TileWindow>(xRanges.size * yRanges.size)
        for (ry in yRanges.indices) {
            val (y0, y1) = yRanges[ry]
            for (cx in xRanges.indices) {
                val (x0, x1) = xRanges[cx]
                tiles.add(
                    TileWindow(
                        srcX0 = x0,
                        srcY0 = y0,
                        srcX1 = x1,
                        srcY1 = y1,
                        rowIndex = ry,
                        colIndex = cx,
                        hasLeftNeighbor = cx > 0,
                        hasRightNeighbor = cx < xRanges.lastIndex,
                        hasTopNeighbor = ry > 0,
                        hasBottomNeighbor = ry < yRanges.lastIndex
                    )
                )
            }
        }
        return tiles
    }

    private fun computeAxisRanges(length: Int, tileSize: Int, overlap: Int): List<Pair<Int, Int>> {
        if (length <= tileSize) {
            return listOf(0 to length)
        }
        val step = max(1, tileSize - overlap)
        val ranges = ArrayList<Pair<Int, Int>>()
        var start = 0
        while (start < length) {
            var end = min(length, start + tileSize)
            if (end == length && end - start < overlap * 2 && ranges.isNotEmpty()) {
                // Anchor final tile so it has full overlap with its predecessor
                start = max(0, length - tileSize)
                end = length
                if (start == ranges.last().first && end == ranges.last().second) {
                    break
                }
                ranges.add(start to end)
                break
            }
            ranges.add(start to end)
            if (end >= length) break
            start += step
        }
        return ranges
    }

    /**
     * Executes a 2x tiled pass using row-band float accumulation so memory usage stays bounded
     * to a single tile row band regardless of total image height.
     */
    private fun runTiled2xPass(
        source: Bitmap,
        tileProcessor: (Bitmap, TileWindow) -> IntArray,
        onProgress: ((Float) -> Unit)?
    ): Bitmap {
        val srcW = source.width
        val srcH = source.height
        val outW = srcW * PASS_SCALE
        val outH = srcH * PASS_SCALE
        val outBitmap = createBitmap(outW, outH, Bitmap.Config.ARGB_8888)

        val tiles = computeTilePlan(srcW, srcH, TILE_SIZE, TILE_OVERLAP)
        val totalTiles = tiles.size.coerceAtLeast(1)
        val tilesByRow = tiles.groupBy { it.rowIndex }.toSortedMap()
        val featherOutPx = TILE_OVERLAP * PASS_SCALE

        // Keep only the active vertical row band in float accumulators (<= 512 rows at a time)
        var prevTailY0 = outH
        var prevTailH = 0
        var prevTailR = FloatArray(0)
        var prevTailG = FloatArray(0)
        var prevTailB = FloatArray(0)
        var prevTailW = FloatArray(0)

        var completedTiles = 0
        val rowOutPixels = IntArray(outW)

        for ((_, rowTiles) in tilesByRow) {
            val firstTile = rowTiles.first()
            val bandY0 = firstTile.srcY0 * PASS_SCALE
            val bandY1 = firstTile.srcY1 * PASS_SCALE
            val bandH = bandY1 - bandY0
            val bandLen = bandH * outW

            val accR = FloatArray(bandLen)
            val accG = FloatArray(bandLen)
            val accB = FloatArray(bandLen)
            val accW = FloatArray(bandLen)

            // Carry over overlapping tail rows from the previous tile band
            if (prevTailH > 0) {
                val overlapStartY = max(bandY0, prevTailY0)
                val overlapEndY = min(bandY1, prevTailY0 + prevTailH)
                for (gy in overlapStartY until overlapEndY) {
                    val dstRowOff = (gy - bandY0) * outW
                    val srcRowOff = (gy - prevTailY0) * outW
                    System.arraycopy(prevTailR, srcRowOff, accR, dstRowOff, outW)
                    System.arraycopy(prevTailG, srcRowOff, accG, dstRowOff, outW)
                    System.arraycopy(prevTailB, srcRowOff, accB, dstRowOff, outW)
                    System.arraycopy(prevTailW, srcRowOff, accW, dstRowOff, outW)
                }
            }

            // Process every tile in this horizontal band
            for (tile in rowTiles) {
                val tileOutW = tile.width * PASS_SCALE
                val tileOutH = tile.height * PASS_SCALE
                val tileOutPixels = tileProcessor(source, tile)

                val weightsX = buildFeatherWeights(
                    length = tileOutW,
                    featherStart = tile.hasLeftNeighbor,
                    featherEnd = tile.hasRightNeighbor,
                    featherPx = featherOutPx
                )
                val weightsY = buildFeatherWeights(
                    length = tileOutH,
                    featherStart = tile.hasTopNeighbor,
                    featherEnd = tile.hasBottomNeighbor,
                    featherPx = featherOutPx
                )

                val tileOutX0 = tile.srcX0 * PASS_SCALE
                for (ly in 0 until tileOutH) {
                    val wy = weightsY[ly]
                    val tileRowOff = ly * tileOutW
                    val bandRowOff = ly * outW + tileOutX0
                    for (lx in 0 until tileOutW) {
                        val w = wy * weightsX[lx]
                        val px = tileOutPixels[tileRowOff + lx]
                        val idx = bandRowOff + lx
                        accR[idx] += ((px ushr 16) and 0xFF) * w
                        accG[idx] += ((px ushr 8) and 0xFF) * w
                        accB[idx] += (px and 0xFF) * w
                        accW[idx] += w
                    }
                }

                completedTiles++
                onProgress?.invoke((completedTiles.toFloat() / totalTiles.toFloat()).coerceIn(0f, 1f))
            }

            val hasNextBand = firstTile.hasBottomNeighbor
            val finalizeEndY = if (hasNextBand) {
                (bandY1 - featherOutPx).coerceIn(bandY0, bandY1)
            } else {
                bandY1
            }

            // Flush finalized rows directly into the destination ARGB_8888 Bitmap
            for (gy in bandY0 until finalizeEndY) {
                val rowOff = (gy - bandY0) * outW
                for (gx in 0 until outW) {
                    val idx = rowOff + gx
                    val w = accW[idx]
                    val invW = if (w > 1e-6f) 1f / w else 0f
                    val r = (accR[idx] * invW).roundToInt().coerceIn(0, 255)
                    val g = (accG[idx] * invW).roundToInt().coerceIn(0, 255)
                    val b = (accB[idx] * invW).roundToInt().coerceIn(0, 255)
                    rowOutPixels[gx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
                outBitmap.setPixels(rowOutPixels, 0, outW, 0, gy, outW, 1)
            }

            // Save unfinalized overlap strip for the next band
            if (hasNextBand && finalizeEndY < bandY1) {
                prevTailY0 = finalizeEndY
                prevTailH = bandY1 - finalizeEndY
                val tailLen = prevTailH * outW
                if (prevTailR.size != tailLen) {
                    prevTailR = FloatArray(tailLen)
                    prevTailG = FloatArray(tailLen)
                    prevTailB = FloatArray(tailLen)
                    prevTailW = FloatArray(tailLen)
                }
                val srcStartOff = (finalizeEndY - bandY0) * outW
                System.arraycopy(accR, srcStartOff, prevTailR, 0, tailLen)
                System.arraycopy(accG, srcStartOff, prevTailG, 0, tailLen)
                System.arraycopy(accB, srcStartOff, prevTailB, 0, tailLen)
                System.arraycopy(accW, srcStartOff, prevTailW, 0, tailLen)
            } else {
                prevTailH = 0
            }
        }

        return outBitmap
    }

    /**
     * Smooth linear ramp feathering weights across [featherPx] pixels at interior tile borders.
     * Boundary edges that touch the outer image perimeter keep full weight 1.0.
     */
    @VisibleForTesting
    internal fun buildFeatherWeights(
        length: Int,
        featherStart: Boolean,
        featherEnd: Boolean,
        featherPx: Int
    ): FloatArray {
        val weights = FloatArray(length) { 1f }
        if (length <= 1 || featherPx <= 0) return weights
        val effectiveFeather = min(featherPx, length / 2).coerceAtLeast(1)
        if (featherStart) {
            for (i in 0 until effectiveFeather) {
                val t = (i + 0.5f) / effectiveFeather.toFloat()
                weights[i] = min(weights[i], t.coerceIn(0.01f, 1f))
            }
        }
        if (featherEnd) {
            for (i in 0 until effectiveFeather) {
                val idx = length - 1 - i
                val t = (i + 0.5f) / effectiveFeather.toFloat()
                weights[idx] = min(weights[idx], t.coerceIn(0.01f, 1f))
            }
        }
        return weights
    }

    // -------------------------------------------------------------------------
    // ONNX Runtime Session Initialization & Dynamic Tensor Inspection
    // -------------------------------------------------------------------------

    private fun getOrInitSession(context: Context): LoadedOnnxSession? {
        loadedSession?.let { return it }
        if (onnxInitAttempted) return null

        synchronized(sessionLock) {
            loadedSession?.let { return it }
            if (onnxInitAttempted) return null
            onnxInitAttempted = true

            val modelFile = extractModelToCacheIfPresent(context) ?: return null
            return try {
                val env = OrtEnvironment.getEnvironment()
                val (session, provider) = createAcceleratedSession(env, modelFile)
                val ioSpec = inspectModelIoSpec(session.inputInfo, session.outputInfo)
                Log.i(
                    TAG,
                    "Initialized $MODEL_ASSET_NAME ($provider): " +
                        "in=${ioSpec.input.name}[${ioSpec.input.javaType},${ioSpec.input.layout},${ioSpec.input.shape.contentToString()}], " +
                        "out=${ioSpec.output.name}[${ioSpec.output.javaType},${ioSpec.output.layout},${ioSpec.output.shape.contentToString()}]"
                )
                LoadedOnnxSession(env, session, provider, ioSpec).also { loadedSession = it }
            } catch (t: Throwable) {
                Log.w(TAG, "Unable to initialize ONNX Runtime for $MODEL_ASSET_NAME; using Lanczos3 fallback", t)
                null
            }
        }
    }

    private fun extractModelToCacheIfPresent(context: Context): File? {
        val onnxDir = File(context.cacheDir, "onnx")
        if (!onnxDir.exists()) {
            onnxDir.mkdirs()
        }
        val targetFile = File(onnxDir, MODEL_ASSET_NAME)
        if (targetFile.exists() && targetFile.length() > 0L) {
            cachedAssetAvailable = true
            return targetFile
        }
        val tmpFile = File(onnxDir, "$MODEL_ASSET_NAME.tmp")
        return try {
            context.assets.open(MODEL_ASSET_NAME).use { input ->
                tmpFile.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            }
            if (tmpFile.length() <= 0L) {
                tmpFile.delete()
                cachedAssetAvailable = false
                null
            } else {
                if (targetFile.exists()) targetFile.delete()
                if (!tmpFile.renameTo(targetFile)) {
                    tmpFile.copyTo(targetFile, overwrite = true)
                    tmpFile.delete()
                }
                cachedAssetAvailable = true
                targetFile
            }
        } catch (_: FileNotFoundException) {
            tmpFile.delete()
            cachedAssetAvailable = false
            null
        } catch (t: Throwable) {
            tmpFile.delete()
            Log.w(TAG, "Failed extracting $MODEL_ASSET_NAME to cache", t)
            null
        }
    }

    private fun createAcceleratedSession(
        env: OrtEnvironment,
        modelFile: File
    ): Pair<OrtSession, String> {
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

        // 1. Prefer XNNPACK execution provider on mobile CPUs
        runCatching {
            OrtSession.SessionOptions().use { opts ->
                opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                opts.setIntraOpNumThreads(threads)
                opts.addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
                return env.createSession(modelFile.absolutePath, opts) to "XNNPACK"
            }
        }.onFailure { err ->
            Log.d(TAG, "XNNPACK EP unavailable (${err.message}); falling back to CPU EP")
        }

        // 2. Standard CPU execution provider fallback
        OrtSession.SessionOptions().use { opts ->
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            opts.setIntraOpNumThreads(threads)
            return env.createSession(modelFile.absolutePath, opts) to "CPU"
        }
    }

    /**
     * Dynamically inspects ONNX session input/output metadata to determine tensor names,
     * data types (FLOAT `[0, 1]` vs UINT8 `[0, 255]`), and channel layout (NCHW vs NHWC).
     */
    @VisibleForTesting
    internal fun inspectModelIoSpec(
        inputInfo: Map<String, NodeInfo>,
        outputInfo: Map<String, NodeInfo>
    ): UpscaleModelIoSpec {
        val inputEntry = inputInfo.entries.firstOrNull { (k, _) ->
            k.contains("image", ignoreCase = true) || k.contains("input", ignoreCase = true)
        } ?: inputInfo.entries.firstOrNull()
            ?: throw IllegalStateException("ONNX upscale model has no inputs")

        val outputEntry = outputInfo.entries.firstOrNull { (k, _) ->
            k.contains("output", ignoreCase = true) || k.contains("result", ignoreCase = true)
        } ?: outputInfo.entries.firstOrNull()
            ?: throw IllegalStateException("ONNX upscale model has no outputs")

        return UpscaleModelIoSpec(
            input = parseTensorSpec(inputEntry.key, inputEntry.value.info),
            output = parseTensorSpec(outputEntry.key, outputEntry.value.info)
        )
    }

    internal fun parseTensorSpec(name: String, info: ValueInfo): UpscaleTensorSpec {
        val tensorInfo = info as? TensorInfo
            ?: return UpscaleTensorSpec(
                name = name,
                javaType = OnnxJavaType.FLOAT,
                shape = longArrayOf(1L, 3L, -1L, -1L),
                layout = TensorLayout.NCHW,
                fixedHeight = null,
                fixedWidth = null
            )

        val shape = tensorInfo.shape ?: longArrayOf(1L, 3L, -1L, -1L)
        val layout = inferLayout(shape)
        val (fixedH, fixedW) = if (shape.size == 4) {
            if (layout == TensorLayout.NHWC) {
                shape[1].takeIf { it > 0L }?.toInt() to shape[2].takeIf { it > 0L }?.toInt()
            } else {
                shape[2].takeIf { it > 0L }?.toInt() to shape[3].takeIf { it > 0L }?.toInt()
            }
        } else {
            null to null
        }

        return UpscaleTensorSpec(
            name = name,
            javaType = tensorInfo.type,
            shape = shape,
            layout = layout,
            fixedHeight = fixedH,
            fixedWidth = fixedW
        )
    }

    internal fun inferLayout(shape: LongArray): TensorLayout {
        if (shape.size != 4) return TensorLayout.NCHW
        val d1 = shape[1]
        val d3 = shape[3]
        return when {
            d3 == 3L && d1 != 3L -> TensorLayout.NHWC
            d1 == 3L -> TensorLayout.NCHW
            else -> TensorLayout.NCHW
        }
    }

    // -------------------------------------------------------------------------
    // ONNX Tile Inference
    // -------------------------------------------------------------------------

    private fun runOnnxTile2x(
        loaded: LoadedOnnxSession,
        source: Bitmap,
        tile: TileWindow
    ): IntArray {
        val tileW = tile.width
        val tileH = tile.height
        val srcPixels = IntArray(tileW * tileH)
        source.getPixels(srcPixels, 0, tileW, tile.srcX0, tile.srcY0, tileW, tileH)

        val inSpec = loaded.ioSpec.input
        val outSpec = loaded.ioSpec.output

        // Handle models with fixed spatial input size by padding on bottom/right with edge replication
        val runW = inSpec.fixedWidth ?: tileW
        val runH = inSpec.fixedHeight ?: tileH
        val workPixels = if (runW == tileW && runH == tileH) {
            srcPixels
        } else {
            padPixelsReflectEdge(srcPixels, tileW, tileH, runW, runH)
        }

        val inputTensor = createInputTensor(loaded.env, workPixels, runW, runH, inSpec)
        try {
            loaded.session.run(mapOf(inSpec.name to inputTensor)).use { results ->
                val outValue = results.get(outSpec.name).orElseGet { results.get(0) }
                val outTensor = outValue as? OnnxTensor
                    ?: throw IllegalStateException("Expected OnnxTensor output for ${outSpec.name}")
                val fullOut = decodeOutputTensorToArgb(outTensor, outSpec)
                val targetOutW = tileW * PASS_SCALE
                val targetOutH = tileH * PASS_SCALE
                return if (fullOut.width == targetOutW && fullOut.height == targetOutH) {
                    fullOut.pixels
                } else {
                    cropOutputPixels(fullOut.pixels, fullOut.width, fullOut.height, targetOutW, targetOutH)
                }
            }
        } finally {
            inputTensor.close()
        }
    }

    private fun createInputTensor(
        env: OrtEnvironment,
        pixels: IntArray,
        w: Int,
        h: Int,
        spec: UpscaleTensorSpec
    ): OnnxTensor {
        val count = w * h
        val shape = if (spec.layout == TensorLayout.NHWC) {
            longArrayOf(1L, h.toLong(), w.toLong(), 3L)
        } else {
            longArrayOf(1L, 3L, h.toLong(), w.toLong())
        }

        return when (spec.javaType) {
            OnnxJavaType.UINT8 -> {
                val buf = ByteBuffer.allocateDirect(count * 3).order(ByteOrder.nativeOrder())
                if (spec.layout == TensorLayout.NHWC) {
                    for (i in 0 until count) {
                        val px = pixels[i]
                        buf.put(((px ushr 16) and 0xFF).toByte())
                        buf.put(((px ushr 8) and 0xFF).toByte())
                        buf.put((px and 0xFF).toByte())
                    }
                } else {
                    for (i in 0 until count) buf.put(((pixels[i] ushr 16) and 0xFF).toByte())
                    for (i in 0 until count) buf.put(((pixels[i] ushr 8) and 0xFF).toByte())
                    for (i in 0 until count) buf.put((pixels[i] and 0xFF).toByte())
                }
                buf.rewind()
                OnnxTensor.createTensor(env, buf, shape, OnnxJavaType.UINT8)
            }

            else -> {
                val data = FloatArray(count * 3)
                val inv255 = 1f / 255f
                if (spec.layout == TensorLayout.NHWC) {
                    var dst = 0
                    for (i in 0 until count) {
                        val px = pixels[i]
                        data[dst++] = ((px ushr 16) and 0xFF) * inv255
                        data[dst++] = ((px ushr 8) and 0xFF) * inv255
                        data[dst++] = (px and 0xFF) * inv255
                    }
                } else {
                    val gOff = count
                    val bOff = count * 2
                    for (i in 0 until count) {
                        val px = pixels[i]
                        data[i] = ((px ushr 16) and 0xFF) * inv255
                        data[gOff + i] = ((px ushr 8) and 0xFF) * inv255
                        data[bOff + i] = (px and 0xFF) * inv255
                    }
                }
                OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape)
            }
        }
    }

    private data class DecodedRaster(
        val pixels: IntArray,
        val width: Int,
        val height: Int
    )

    private fun decodeOutputTensorToArgb(
        tensor: OnnxTensor,
        spec: UpscaleTensorSpec
    ): DecodedRaster {
        val info = tensor.info
        val shape = info.shape
        val layout = if (shape.size == 4) inferLayout(shape) else spec.layout
        val outH = if (shape.size == 4) {
            if (layout == TensorLayout.NHWC) shape[1].toInt() else shape[2].toInt()
        } else {
            throw IllegalStateException("Unexpected output tensor rank: ${shape.contentToString()}")
        }
        val outW = if (shape.size == 4) {
            if (layout == TensorLayout.NHWC) shape[2].toInt() else shape[3].toInt()
        } else {
            throw IllegalStateException("Unexpected output tensor rank: ${shape.contentToString()}")
        }

        val count = outW * outH
        val pixels = IntArray(count)

        when (info.type) {
            OnnxJavaType.UINT8 -> {
                val bb = tensor.byteBuffer
                bb.rewind()
                val raw = ByteArray(bb.remaining())
                bb.get(raw)
                if (layout == TensorLayout.NHWC) {
                    var src = 0
                    for (i in 0 until count) {
                        val r = raw[src++].toInt() and 0xFF
                        val g = raw[src++].toInt() and 0xFF
                        val b = raw[src++].toInt() and 0xFF
                        pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                } else {
                    val gOff = count
                    val bOff = count * 2
                    for (i in 0 until count) {
                        val r = raw[i].toInt() and 0xFF
                        val g = raw[gOff + i].toInt() and 0xFF
                        val b = raw[bOff + i].toInt() and 0xFF
                        pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
            }

            else -> {
                val fb = tensor.floatBuffer
                fb.rewind()
                val raw = FloatArray(fb.remaining())
                fb.get(raw)

                // Detect float normalization range (standard Real-ESRGAN is [0, 1])
                var minVal = Float.POSITIVE_INFINITY
                var maxVal = Float.NEGATIVE_INFINITY
                val sampleStep = max(1, raw.size / 1024)
                var idx = 0
                while (idx < raw.size) {
                    val v = raw[idx]
                    if (v.isFinite()) {
                        if (v < minVal) minVal = v
                        if (v > maxVal) maxVal = v
                    }
                    idx += sampleStep
                }
                val isZeroTo255 = maxVal > 2.0f
                val isNegOneToOne = !isZeroTo255 && minVal < -0.25f

                fun toByte(v: Float): Int {
                    val scaled = when {
                        !v.isFinite() -> 0f
                        isZeroTo255 -> v
                        isNegOneToOne -> (v + 1f) * 127.5f
                        else -> v * 255f
                    }
                    return scaled.roundToInt().coerceIn(0, 255)
                }

                if (layout == TensorLayout.NHWC) {
                    var src = 0
                    for (i in 0 until count) {
                        val r = toByte(raw[src++])
                        val g = toByte(raw[src++])
                        val b = toByte(raw[src++])
                        pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                } else {
                    val gOff = count
                    val bOff = count * 2
                    for (i in 0 until count) {
                        val r = toByte(raw[i])
                        val g = toByte(raw[gOff + i])
                        val b = toByte(raw[bOff + i])
                        pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
            }
        }

        return DecodedRaster(pixels = pixels, width = outW, height = outH)
    }

    private fun padPixelsReflectEdge(
        src: IntArray,
        srcW: Int,
        srcH: Int,
        dstW: Int,
        dstH: Int
    ): IntArray {
        val out = IntArray(dstW * dstH)
        for (y in 0 until dstH) {
            val sy = min(y, srcH - 1)
            val srcRow = sy * srcW
            val dstRow = y * dstW
            for (x in 0 until dstW) {
                val sx = min(x, srcW - 1)
                out[dstRow + x] = src[srcRow + sx]
            }
        }
        return out
    }

    private fun cropOutputPixels(
        src: IntArray,
        srcW: Int,
        srcH: Int,
        dstW: Int,
        dstH: Int
    ): IntArray {
        val out = IntArray(dstW * dstH)
        val copyW = min(srcW, dstW)
        val copyH = min(srcH, dstH)
        for (y in 0 until copyH) {
            System.arraycopy(src, y * srcW, out, y * dstW, copyW)
        }
        return out
    }

    // -------------------------------------------------------------------------
    // Offline Fallback: Separable Lanczos3 2x + Edge-Aware Unsharp Sharpening
    // -------------------------------------------------------------------------

    /**
     * Upscales a single tile 2x using true separable Lanczos3 (`a = 3`) resampling
     * with a 4px global source context halo (so Lanczos taps and sharpening kernels never
     * see artificial tile borders), followed by edge-aware halo-clamped unsharp sharpening.
     */
    private fun runLanczos3SharpenTile2x(
        source: Bitmap,
        tile: TileWindow
    ): IntArray {
        val imgW = source.width
        val imgH = source.height
        val pad = LANCZOS_A + 2 // Extra context for Lanczos3 support + sharpening 3x3 border

        val ctxX0 = max(0, tile.srcX0 - pad)
        val ctxY0 = max(0, tile.srcY0 - pad)
        val ctxX1 = min(imgW, tile.srcX1 + pad)
        val ctxY1 = min(imgH, tile.srcY1 + pad)
        val ctxW = ctxX1 - ctxX0
        val ctxH = ctxY1 - ctxY0

        val srcPixels = IntArray(ctxW * ctxH)
        source.getPixels(srcPixels, 0, ctxW, ctxX0, ctxY0, ctxW, ctxH)

        // Include a 1px output halo around the tile so the 3x3 unsharp kernel has exact neighbors
        val outX0 = tile.srcX0 * PASS_SCALE
        val outY0 = tile.srcY0 * PASS_SCALE
        val tileOutW = tile.width * PASS_SCALE
        val tileOutH = tile.height * PASS_SCALE

        val sharpHalo = 1
        val workOutX0 = outX0 - sharpHalo
        val workOutY0 = outY0 - sharpHalo
        val workOutW = tileOutW + sharpHalo * 2
        val workOutH = tileOutH + sharpHalo * 2

        // Pass A: Horizontal Lanczos3 (ctxH rows -> workOutW cols)
        val horizR = FloatArray(ctxH * workOutW)
        val horizG = FloatArray(ctxH * workOutW)
        val horizB = FloatArray(ctxH * workOutW)

        // Precompute horizontal Lanczos3 weights for each column in workOutW
        val tapCount = LANCZOS_A * 2
        val hTapIndices = IntArray(workOutW * tapCount)
        val hTapWeights = FloatArray(workOutW * tapCount)
        for (wx in 0 until workOutW) {
            val globalOutX = workOutX0 + wx
            // Pixel-center mapping: out pixel gx corresponds to source coord (gx + 0.5) / 2 - 0.5
            val srcCoord = (globalOutX + 0.5f) * 0.5f - 0.5f
            val baseIdx = floor(srcCoord.toDouble()).toInt() - (LANCZOS_A - 1)
            var sumW = 0f
            val baseOff = wx * tapCount
            for (t in 0 until tapCount) {
                val globalSx = (baseIdx + t).coerceIn(0, imgW - 1)
                val localSx = (globalSx - ctxX0).coerceIn(0, ctxW - 1)
                val w = lanczos3Weight(srcCoord - (baseIdx + t))
                hTapIndices[baseOff + t] = localSx
                hTapWeights[baseOff + t] = w
                sumW += w
            }
            val invSum = if (abs(sumW) > 1e-6f) 1f / sumW else 0f
            for (t in 0 until tapCount) {
                hTapWeights[baseOff + t] *= invSum
            }
        }

        for (ly in 0 until ctxH) {
            val srcRow = ly * ctxW
            val dstRow = ly * workOutW
            for (wx in 0 until workOutW) {
                var r = 0f
                var g = 0f
                var b = 0f
                val baseOff = wx * tapCount
                for (t in 0 until tapCount) {
                    val w = hTapWeights[baseOff + t]
                    val px = srcPixels[srcRow + hTapIndices[baseOff + t]]
                    r += ((px ushr 16) and 0xFF) * w
                    g += ((px ushr 8) and 0xFF) * w
                    b += (px and 0xFF) * w
                }
                horizR[dstRow + wx] = r
                horizG[dstRow + wx] = g
                horizB[dstRow + wx] = b
            }
        }

        // Pass B: Vertical Lanczos3 (workOutH rows x workOutW cols)
        val upR = FloatArray(workOutH * workOutW)
        val upG = FloatArray(workOutH * workOutW)
        val upB = FloatArray(workOutH * workOutW)
        val vLocalIndices = IntArray(tapCount)
        val vWeights = FloatArray(tapCount)

        for (wy in 0 until workOutH) {
            val globalOutY = workOutY0 + wy
            val srcCoord = (globalOutY + 0.5f) * 0.5f - 0.5f
            val baseIdx = floor(srcCoord.toDouble()).toInt() - (LANCZOS_A - 1)
            var sumW = 0f
            for (t in 0 until tapCount) {
                val globalSy = (baseIdx + t).coerceIn(0, imgH - 1)
                val localSy = (globalSy - ctxY0).coerceIn(0, ctxH - 1)
                val w = lanczos3Weight(srcCoord - (baseIdx + t))
                vLocalIndices[t] = localSy
                vWeights[t] = w
                sumW += w
            }
            val invSum = if (abs(sumW) > 1e-6f) 1f / sumW else 0f
            for (t in 0 until tapCount) {
                vWeights[t] *= invSum
            }

            val dstRow = wy * workOutW
            for (wx in 0 until workOutW) {
                var r = 0f
                var g = 0f
                var b = 0f
                for (t in 0 until tapCount) {
                    val w = vWeights[t]
                    val srcIdx = vLocalIndices[t] * workOutW + wx
                    r += horizR[srcIdx] * w
                    g += horizG[srcIdx] * w
                    b += horizB[srcIdx] * w
                }
                upR[dstRow + wx] = r.coerceIn(0f, 255f)
                upG[dstRow + wx] = g.coerceIn(0f, 255f)
                upB[dstRow + wx] = b.coerceIn(0f, 255f)
            }
        }

        // Pass C: Edge-aware halo-clamped unsharp sharpening (crop off the 1px halo)
        return applyEdgeAwareUnsharpMask(
            upR = upR,
            upG = upG,
            upB = upB,
            workW = workOutW,
            tileOutW = tileOutW,
            tileOutH = tileOutH,
            halo = sharpHalo
        )
    }

    /**
     * Applies luminance-guided edge-aware unsharp masking with soft noise floor thresholding
     * and local 3×3 min/max anti-ringing clamping so fine edges crisp up cleanly without halos.
     */
    private fun applyEdgeAwareUnsharpMask(
        upR: FloatArray,
        upG: FloatArray,
        upB: FloatArray,
        workW: Int,
        tileOutW: Int,
        tileOutH: Int,
        halo: Int
    ): IntArray {
        val out = IntArray(tileOutW * tileOutH)
        val sharpStrength = 0.42f
        val noiseThreshold = 2.5f
        val edgeScale = 14.0f

        for (y in 0 until tileOutH) {
            val wy = y + halo
            val rowAbove = (wy - 1) * workW
            val rowMid = wy * workW
            val rowBelow = (wy + 1) * workW
            val outRow = y * tileOutW

            for (x in 0 until tileOutW) {
                val wx = x + halo
                val cIdx = rowMid + wx
                val cr = upR[cIdx]
                val cg = upG[cIdx]
                val cb = upB[cIdx]
                val cLuma = 0.299f * cr + 0.587f * cg + 0.114f * cb

                // 3x3 Gaussian-weighted luminance neighborhood + local min/max for anti-ringing
                val lTL = lumaAt(upR, upG, upB, rowAbove + wx - 1)
                val lTC = lumaAt(upR, upG, upB, rowAbove + wx)
                val lTR = lumaAt(upR, upG, upB, rowAbove + wx + 1)
                val lML = lumaAt(upR, upG, upB, rowMid + wx - 1)
                val lMR = lumaAt(upR, upG, upB, rowMid + wx + 1)
                val lBL = lumaAt(upR, upG, upB, rowBelow + wx - 1)
                val lBC = lumaAt(upR, upG, upB, rowBelow + wx)
                val lBR = lumaAt(upR, upG, upB, rowBelow + wx + 1)

                val blurLuma = (
                    lTL + 2f * lTC + lTR +
                        2f * lML + 4f * cLuma + 2f * lMR +
                        lBL + 2f * lBC + lBR
                    ) * 0.0625f

                val detail = cLuma - blurLuma
                val absDetail = abs(detail)

                val deltaLuma = if (absDetail <= noiseThreshold) {
                    0f
                } else {
                    // Smooth ramp above noise floor, tapering on extreme contrast edges to prevent halos
                    val edgeWeight = ((absDetail - noiseThreshold) / edgeScale).coerceIn(0f, 1f)
                    val localMin = min(
                        min(min(lTL, lTC), min(lTR, lML)),
                        min(min(cLuma, lMR), min(lBL, min(lBC, lBR)))
                    )
                    val localMax = max(
                        max(max(lTL, lTC), max(lTR, lML)),
                        max(max(cLuma, lMR), max(lBL, max(lBC, lBR)))
                    )
                    val targetLuma = (cLuma + detail * sharpStrength * edgeWeight)
                        .coerceIn(localMin - 6f, localMax + 6f)
                    targetLuma - cLuma
                }

                val fr = (cr + deltaLuma).roundToInt().coerceIn(0, 255)
                val fg = (cg + deltaLuma).roundToInt().coerceIn(0, 255)
                val fb = (cb + deltaLuma).roundToInt().coerceIn(0, 255)
                out[outRow + x] = (0xFF shl 24) or (fr shl 16) or (fg shl 8) or fb
            }
        }
        return out
    }

    private fun lumaAt(r: FloatArray, g: FloatArray, b: FloatArray, idx: Int): Float {
        return 0.299f * r[idx] + 0.587f * g[idx] + 0.114f * b[idx]
    }

    private fun lanczos3Weight(x: Float): Float {
        val ax = abs(x)
        if (ax < 1e-6f) return 1f
        if (ax >= LANCZOS_A.toFloat()) return 0f
        val piX = PI * ax
        val piXOverA = piX / LANCZOS_A.toDouble()
        return ((sin(piX) / piX) * (sin(piXOverA) / piXOverA)).toFloat()
    }
}
