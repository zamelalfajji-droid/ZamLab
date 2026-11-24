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
import androidx.core.graphics.scale
import com.zamalfajji.zamlab.editor.HealStroke
import java.io.File
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 100% offline AI + algorithmic object removal engine with strict pixel preservation:
 *
 * - Primary backend (when `app/src/main/assets/migan_pipeline_v2.onnx` is present):
 *   Lazily loads `migan_pipeline_v2.onnx` on the first Heal stroke using ONNX Runtime
 *   (`onnxruntime-android`) with NNAPI / XNNPACK hardware acceleration.
 *   Inputs:
 *   - `image`: uint8 `[1, 3, H, W]` RGB
 *   - `mask`: uint8 `[1, 1, H, W]` (`255 = keep`, `0 = remove/fill`)
 *   Output:
 *   - `result`: uint8 `[1, 3, H, W]` RGB
 *
 * - Automatic fallback backend (when `.onnx` asset is absent or unavailable):
 *   3-level coarse-to-fine Directional Edge-Aware PatchMatch + Red-Black SOR Poisson
 *   gradient-domain seamless blend.
 *
 * Both backends crop around the masked region and strictly preserve 100% of unmasked
 * pixels (`alpha == 0`) bit-for-bit, feathering only across a 3px mask boundary.
 */
object LocalInpaintingEngine {

    private const val TAG = "LocalInpaintingEngine"
    const val MODEL_ASSET_NAME = "migan_pipeline_v2.onnx"
    const val ONNX_INPUT_IMAGE = "image"
    const val ONNX_INPUT_MASK = "mask"
    const val ONNX_OUTPUT_RESULT = "result"

    private const val ONNX_MIN_CROP_SIDE = 512
    private const val ONNX_MAX_WORK_DIM = 768
    const val ONNX_CONTEXT_PAD_PX = 64
    const val ONNX_MASK_DILATE_PX = 4
    private const val ONNX_BOUNDARY_FEATHER_PX = 3.0f
    private const val CROP_WORK_MAX_DIM = 448
    const val FALLBACK_BOUNDARY_FEATHER_PX = 8.0f
    private const val BOUNDARY_FEATHER_PX = FALLBACK_BOUNDARY_FEATHER_PX

    enum class InpaintBackend {
        ONNX_MIGAN,
        ALGORITHMIC_FALLBACK
    }

    data class InpaintResult(
        val bitmap: Bitmap,
        val backend: InpaintBackend,
        val accelerationProvider: String? = null
    )

    enum class TensorLayout {
        NCHW,
        NHWC
    }

    enum class FloatRange {
        NEGATIVE_ONE_TO_ONE,
        ZERO_TO_ONE,
        ZERO_TO_255
    }

    internal data class TensorSpec(
        val name: String,
        val javaType: OnnxJavaType,
        val shape: LongArray,
        val layout: TensorLayout,
        val fixedHeight: Int?,
        val fixedWidth: Int?
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is TensorSpec) return false
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

    internal data class ModelIoSpec(
        val imageInput: TensorSpec,
        val maskInput: TensorSpec,
        val resultOutput: TensorSpec
    )

    internal data class CropBounds(
        val x0: Int,
        val y0: Int,
        val x1: Int,
        val y1: Int,
        val width: Int,
        val height: Int
    )

    private data class SessionBundle(
        val session: OrtSession,
        val accelLabel: String,
        val ioSpec: ModelIoSpec,
        val usedNnapi: Boolean
    )

    private val sessionLock = Any()
    private val inferenceLock = Any()

    @Volatile
    private var ortEnv: OrtEnvironment? = null

    @Volatile
    private var sessionBundle: SessionBundle? = null

    @Volatile
    private var activeAccelerationLabel: String? = null

    @Volatile
    private var modelLoadAttempted = false

    @Volatile
    private var modelAssetPresent: Boolean? = null

    private class PyramidLevel(
        val width: Int,
        val height: Int,
        val r: FloatArray,
        val g: FloatArray,
        val b: FloatArray,
        val gx: FloatArray,
        val gy: FloatArray,
        val mask: BooleanArray
    )

    fun isModelLoaded(): Boolean = sessionBundle != null

    fun activeAcceleration(): String? = activeAccelerationLabel

    fun isModelAssetPresent(context: Context): Boolean {
        modelAssetPresent?.let { return it }
        val appCtx = context.applicationContext ?: context
        val present = try {
            appCtx.assets.open(MODEL_ASSET_NAME).use { true }
        } catch (_: FileNotFoundException) {
            false
        } catch (_: Throwable) {
            false
        }
        modelAssetPresent = present
        return present
    }

    @VisibleForTesting
    internal fun resetOnnxStateForTests() {
        synchronized(sessionLock) {
            runCatching { sessionBundle?.session?.close() }
            sessionBundle = null
            activeAccelerationLabel = null
            modelLoadAttempted = false
            modelAssetPresent = null
        }
    }

    private fun ensureOnnxSessionBundle(context: Context?): SessionBundle? {
        if (context == null) return null
        sessionBundle?.let { return it }
        if (modelLoadAttempted && modelAssetPresent == false) return null

        synchronized(sessionLock) {
            sessionBundle?.let { return it }
            if (modelLoadAttempted && modelAssetPresent == false) return null

            val appCtx = context.applicationContext ?: context
            if (!isModelAssetPresent(appCtx)) {
                modelLoadAttempted = true
                Log.i(TAG, "$MODEL_ASSET_NAME not present in assets; using algorithmic inpainting fallback.")
                return null
            }

            return try {
                val cachedModelFile = extractAssetModelIfNeeded(appCtx)
                    ?: run {
                        modelLoadAttempted = true
                        return null
                    }
                val env = ortEnv ?: OrtEnvironment.getEnvironment().also { ortEnv = it }
                val created = createAcceleratedSession(env, cachedModelFile)
                sessionBundle = created
                activeAccelerationLabel = created.accelLabel
                modelLoadAttempted = true
                Log.i(
                    TAG,
                    "Lazily initialized $MODEL_ASSET_NAME via ONNX Runtime (${created.accelLabel}) | " +
                        "image=${created.ioSpec.imageInput.name}:${created.ioSpec.imageInput.javaType}${created.ioSpec.imageInput.shape.contentToString()} | " +
                        "mask=${created.ioSpec.maskInput.name}:${created.ioSpec.maskInput.javaType}${created.ioSpec.maskInput.shape.contentToString()} | " +
                        "result=${created.ioSpec.resultOutput.name}:${created.ioSpec.resultOutput.javaType}${created.ioSpec.resultOutput.shape.contentToString()}"
                )
                created
            } catch (t: Throwable) {
                modelLoadAttempted = true
                Log.w(TAG, "Failed to initialize ONNX Runtime for $MODEL_ASSET_NAME; falling back to algorithmic inpainting", t)
                null
            }
        }
    }

    private fun extractAssetModelIfNeeded(context: Context): File? {
        val onnxDir = File(context.cacheDir, "onnx").apply { mkdirs() }
        val targetFile = File(onnxDir, MODEL_ASSET_NAME)
        val expectedAssetLen = runCatching {
            context.assets.openFd(MODEL_ASSET_NAME).use { it.length }
        }.getOrNull()?.takeIf { it > 0L }

        if (targetFile.exists() && targetFile.length() > 0L &&
            (expectedAssetLen == null || targetFile.length() == expectedAssetLen)
        ) {
            return targetFile
        }

        val tempFile = File(onnxDir, "$MODEL_ASSET_NAME.tmp")
        context.assets.open(MODEL_ASSET_NAME).use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output, bufferSize = 64 * 1024)
            }
        }
        if (tempFile.length() <= 0L) {
            tempFile.delete()
            return null
        }
        if (targetFile.exists()) targetFile.delete()
        if (!tempFile.renameTo(targetFile)) {
            tempFile.copyTo(targetFile, overwrite = true)
            tempFile.delete()
        }
        return targetFile.takeIf { it.exists() && it.length() > 0L }
    }

    private fun createAcceleratedSession(
        env: OrtEnvironment,
        modelFile: File,
        allowNnapi: Boolean = true
    ): SessionBundle {
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        // Prefer XNNPACK ahead of NNAPI because MI-GAN's dynamic Resize/Slice/UINT8 pipeline ops
        // can output zeroed/black tensors under some Android NNAPI HAL drivers.
        val tiers = buildList {
            add(Triple(false, true, "XNNPACK"))
            if (allowNnapi) {
                add(Triple(true, true, "NNAPI+XNNPACK"))
                add(Triple(true, false, "NNAPI"))
            }
            add(Triple(false, false, "CPU"))
        }
        var lastError: Throwable? = null
        for ((useNnapi, useXnnpack, label) in tiers) {
            val opts = OrtSession.SessionOptions()
            try {
                opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                opts.setIntraOpNumThreads(threads)
                runCatching { opts.addConfigEntry("session.intra_op.allow_spinning", "0") }
                if (useXnnpack) {
                    opts.addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
                }
                if (useNnapi) {
                    opts.addNnapi()
                }
                val session = env.createSession(modelFile.absolutePath, opts)
                val ioSpec = inspectModelIoSpec(session)
                return SessionBundle(
                    session = session,
                    accelLabel = label,
                    ioSpec = ioSpec,
                    usedNnapi = useNnapi
                )
            } catch (t: Throwable) {
                lastError = t
                Log.w(TAG, "ONNX Runtime session creation with $label failed; trying next provider", t)
            } finally {
                runCatching { opts.close() }
            }
        }
        throw lastError ?: IllegalStateException("Unable to initialize ONNX Runtime session.")
    }

    internal fun inspectModelIoSpec(session: OrtSession): ModelIoSpec {
        return inspectModelIoSpec(
            inputInfo = session.inputInfo,
            outputInfo = session.outputInfo
        )
    }

    internal fun inspectModelIoSpec(
        inputInfo: Map<String, NodeInfo>,
        outputInfo: Map<String, NodeInfo>
    ): ModelIoSpec {
        val inputSpecs = inputInfo.entries.map { (name, node) ->
            val info = node.info
            val tInfo = info as? TensorInfo
            val channelsHint = if (name.contains("mask", ignoreCase = true)) 1 else 3
            inspectTensorSpec(name, tInfo?.type, tInfo?.shape, expectedChannels = channelsHint)
        }
        val outputSpecs = outputInfo.entries.map { (name, node) ->
            val tInfo = node.info as? TensorInfo
            inspectTensorSpec(name, tInfo?.type, tInfo?.shape, expectedChannels = 3)
        }

        val imageInput = inputSpecs.firstOrNull { it.name.equals(ONNX_INPUT_IMAGE, ignoreCase = true) }
            ?: inputSpecs.firstOrNull { it.name.contains("image", ignoreCase = true) || it.name.contains("img", ignoreCase = true) }
            ?: inputSpecs.firstOrNull { hasChannelCount(it.shape, 3L) }
            ?: inputSpecs.firstOrNull()
            ?: TensorSpec(
                name = ONNX_INPUT_IMAGE,
                javaType = OnnxJavaType.UINT8,
                shape = longArrayOf(1L, 3L, -1L, -1L),
                layout = TensorLayout.NCHW,
                fixedHeight = null,
                fixedWidth = null
            )

        val maskInput = inputSpecs.firstOrNull { it.name.equals(ONNX_INPUT_MASK, ignoreCase = true) }
            ?: inputSpecs.firstOrNull { it.name.contains("mask", ignoreCase = true) }
            ?: inputSpecs.firstOrNull { it.name != imageInput.name && hasChannelCount(it.shape, 1L) }
            ?: inputSpecs.lastOrNull { it.name != imageInput.name }
            ?: TensorSpec(
                name = ONNX_INPUT_MASK,
                javaType = OnnxJavaType.UINT8,
                shape = longArrayOf(1L, 1L, -1L, -1L),
                layout = TensorLayout.NCHW,
                fixedHeight = null,
                fixedWidth = null
            )

        val resultOutput = outputSpecs.firstOrNull { it.name.equals(ONNX_OUTPUT_RESULT, ignoreCase = true) }
            ?: outputSpecs.firstOrNull {
                it.name.contains("result", ignoreCase = true) ||
                    it.name.contains("output", ignoreCase = true) ||
                    it.name.contains("out", ignoreCase = true)
            }
            ?: outputSpecs.firstOrNull { hasChannelCount(it.shape, 3L) }
            ?: outputSpecs.firstOrNull()
            ?: TensorSpec(
                name = ONNX_OUTPUT_RESULT,
                javaType = OnnxJavaType.UINT8,
                shape = longArrayOf(1L, 3L, -1L, -1L),
                layout = TensorLayout.NCHW,
                fixedHeight = null,
                fixedWidth = null
            )

        return ModelIoSpec(
            imageInput = imageInput,
            maskInput = maskInput,
            resultOutput = resultOutput
        )
    }

    internal fun inspectTensorSpec(
        name: String,
        javaType: OnnxJavaType?,
        shape: LongArray?,
        expectedChannels: Int
    ): TensorSpec {
        val resolvedType = javaType ?: OnnxJavaType.UINT8
        val resolvedShape = shape ?: longArrayOf(1L, expectedChannels.toLong(), -1L, -1L)
        val channels = expectedChannels.toLong()
        var layout = TensorLayout.NCHW
        var fixedH: Int? = null
        var fixedW: Int? = null

        if (resolvedShape.size == 4) {
            val dim1 = resolvedShape[1]
            val dim2 = resolvedShape[2]
            val dim3 = resolvedShape[3]
            if (dim3 == channels && dim1 != channels) {
                layout = TensorLayout.NHWC
                if (dim1 > 0L) fixedH = dim1.toInt()
                if (dim2 > 0L) fixedW = dim2.toInt()
            } else {
                layout = TensorLayout.NCHW
                if (dim2 > 0L) fixedH = dim2.toInt()
                if (dim3 > 0L) fixedW = dim3.toInt()
            }
        }
        return TensorSpec(
            name = name,
            javaType = resolvedType,
            shape = resolvedShape,
            layout = layout,
            fixedHeight = fixedH,
            fixedWidth = fixedW
        )
    }

    private fun hasChannelCount(shape: LongArray, expectedChannels: Long): Boolean {
        if (shape.size != 4) return false
        return shape[1] == expectedChannels || shape[3] == expectedChannels
    }

    /**
     * Packs ARGB [pixels] into a direct uint8 `[1, 3, H, W]` RGB buffer (`image`)
     * and a direct uint8 `[1, 1, H, W]` mask buffer (`mask`, where `255 = keep` and `0 = remove/fill`).
     */
    internal fun packOnnxInputTensors(
        pixels: IntArray,
        removeMask: BooleanArray,
        width: Int,
        height: Int
    ): Pair<ByteBuffer, ByteBuffer> {
        val imageBuf = packOnnxImageUint8(pixels, width, height, TensorLayout.NCHW)
        val maskBuf = packOnnxMaskUint8(removeMask, width, height, invertPolarity = false)
        return imageBuf to maskBuf
    }

    internal fun packOnnxImageUint8(
        pixels: IntArray,
        width: Int,
        height: Int,
        layout: TensorLayout = TensorLayout.NCHW
    ): ByteBuffer {
        val hw = width * height
        require(width > 0 && height > 0 && pixels.size >= hw) {
            "Invalid image tensor dimensions ${width}x${height} (pixels=${pixels.size})"
        }
        val imageBuf = ByteBuffer.allocateDirect(3 * hw).order(ByteOrder.nativeOrder())
        if (layout == TensorLayout.NHWC) {
            for (i in 0 until hw) {
                val px = pixels[i]
                val base = i * 3
                imageBuf.put(base, ((px ushr 16) and 0xFF).toByte())
                imageBuf.put(base + 1, ((px ushr 8) and 0xFF).toByte())
                imageBuf.put(base + 2, (px and 0xFF).toByte())
            }
        } else {
            for (i in 0 until hw) {
                val px = pixels[i]
                imageBuf.put(i, ((px ushr 16) and 0xFF).toByte())
                imageBuf.put(hw + i, ((px ushr 8) and 0xFF).toByte())
                imageBuf.put(2 * hw + i, (px and 0xFF).toByte())
            }
        }
        imageBuf.rewind()
        return imageBuf
    }

    internal fun packOnnxMaskUint8(
        removeMask: BooleanArray,
        width: Int,
        height: Int,
        invertPolarity: Boolean = false
    ): ByteBuffer {
        val hw = width * height
        require(width > 0 && height > 0 && removeMask.size >= hw) {
            "Invalid mask tensor dimensions ${width}x${height} (mask=${removeMask.size})"
        }
        val maskBuf = ByteBuffer.allocateDirect(hw).order(ByteOrder.nativeOrder())
        val keepByte = if (invertPolarity) 0.toByte() else 0xFF.toByte()
        val removeByte = if (invertPolarity) 0xFF.toByte() else 0.toByte()
        for (i in 0 until hw) {
            // Standard MI-GAN contract: 255 = keep (unmasked), 0 = remove/fill (masked)
            maskBuf.put(i, if (removeMask[i]) removeByte else keepByte)
        }
        maskBuf.rewind()
        return maskBuf
    }

    internal fun packOnnxImageFloat(
        pixels: IntArray,
        width: Int,
        height: Int,
        layout: TensorLayout = TensorLayout.NCHW,
        floatRange: FloatRange = FloatRange.NEGATIVE_ONE_TO_ONE
    ): FloatBuffer {
        val hw = width * height
        require(width > 0 && height > 0 && pixels.size >= hw) {
            "Invalid float image tensor dimensions ${width}x${height} (pixels=${pixels.size})"
        }
        val floatBuf = ByteBuffer.allocateDirect(3 * hw * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        for (i in 0 until hw) {
            val px = pixels[i]
            val rRaw = ((px ushr 16) and 0xFF).toFloat()
            val gRaw = ((px ushr 8) and 0xFF).toFloat()
            val bRaw = (px and 0xFF).toFloat()
            val r = normalizeChannelFloat(rRaw, floatRange)
            val g = normalizeChannelFloat(gRaw, floatRange)
            val b = normalizeChannelFloat(bRaw, floatRange)
            if (layout == TensorLayout.NHWC) {
                val base = i * 3
                floatBuf.put(base, r)
                floatBuf.put(base + 1, g)
                floatBuf.put(base + 2, b)
            } else {
                floatBuf.put(i, r)
                floatBuf.put(hw + i, g)
                floatBuf.put(2 * hw + i, b)
            }
        }
        floatBuf.rewind()
        return floatBuf
    }

    internal fun packOnnxMaskFloat(
        removeMask: BooleanArray,
        width: Int,
        height: Int,
        invertPolarity: Boolean = false
    ): FloatBuffer {
        val hw = width * height
        require(width > 0 && height > 0 && removeMask.size >= hw) {
            "Invalid float mask tensor dimensions ${width}x${height} (mask=${removeMask.size})"
        }
        val floatBuf = ByteBuffer.allocateDirect(hw * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        val keepVal = if (invertPolarity) 0f else 1f
        val removeVal = if (invertPolarity) 1f else 0f
        for (i in 0 until hw) {
            // MI-GAN float convention: 1.0 = keep, 0.0 = remove/fill
            floatBuf.put(i, if (removeMask[i]) removeVal else keepVal)
        }
        floatBuf.rewind()
        return floatBuf
    }

    private fun normalizeChannelFloat(v: Float, range: FloatRange): Float {
        return when (range) {
            FloatRange.NEGATIVE_ONE_TO_ONE -> (v / 127.5f) - 1.0f
            FloatRange.ZERO_TO_ONE -> v / 255.0f
            FloatRange.ZERO_TO_255 -> v
        }
    }

    /**
     * Unpacks a uint8 `[1, 3, H, W]` RGB `result` tensor buffer back into ARGB IntArray pixels.
     */
    internal fun unpackOnnxResultTensor(
        resultBuffer: ByteBuffer,
        width: Int,
        height: Int,
        layout: TensorLayout = TensorLayout.NCHW
    ): IntArray {
        val hw = width * height
        val buf = resultBuffer.duplicate()
        val base = buf.position()
        require(width > 0 && height > 0 && buf.remaining() >= 3 * hw) {
            "Unexpected ONNX output byte count: remaining=${buf.remaining()}, expected=${3 * hw}"
        }
        return if (layout == TensorLayout.NHWC) {
            IntArray(hw) { i ->
                val p = base + i * 3
                val r = buf.get(p).toInt() and 0xFF
                val g = buf.get(p + 1).toInt() and 0xFF
                val b = buf.get(p + 2).toInt() and 0xFF
                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        } else {
            IntArray(hw) { i ->
                val r = buf.get(base + i).toInt() and 0xFF
                val g = buf.get(base + hw + i).toInt() and 0xFF
                val b = buf.get(base + 2 * hw + i).toInt() and 0xFF
                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    internal fun unpackOnnxFloatResultTensor(
        resultBuffer: FloatBuffer,
        width: Int,
        height: Int,
        layout: TensorLayout = TensorLayout.NCHW,
        referencePixels: IntArray? = null,
        referenceMask: BooleanArray? = null,
        forcedRange: FloatRange? = null
    ): IntArray {
        val hw = width * height
        val buf = resultBuffer.duplicate()
        val base = buf.position()
        require(width > 0 && height > 0 && buf.remaining() >= 3 * hw) {
            "Unexpected ONNX float output count: remaining=${buf.remaining()}, expected=${3 * hw}"
        }
        val values = FloatArray(3 * hw) { idx ->
            val v = buf.get(base + idx)
            if (v.isFinite()) v else 0f
        }
        val detectedRange = forcedRange ?: inferFloatOutputRange(
            values = values,
            width = width,
            height = height,
            layout = layout,
            referencePixels = referencePixels,
            referenceMask = referenceMask
        )
        return IntArray(hw) { i ->
            val rRaw: Float
            val gRaw: Float
            val bRaw: Float
            if (layout == TensorLayout.NHWC) {
                val p = i * 3
                rRaw = values[p]
                gRaw = values[p + 1]
                bRaw = values[p + 2]
            } else {
                rRaw = values[i]
                gRaw = values[hw + i]
                bRaw = values[2 * hw + i]
            }
            val r = denormalizeFloatToByte(rRaw, detectedRange)
            val g = denormalizeFloatToByte(gRaw, detectedRange)
            val b = denormalizeFloatToByte(bRaw, detectedRange)
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    internal fun inferFloatOutputRange(
        values: FloatArray,
        width: Int,
        height: Int,
        layout: TensorLayout,
        referencePixels: IntArray? = null,
        referenceMask: BooleanArray? = null
    ): FloatRange {
        var minVal = Float.POSITIVE_INFINITY
        var maxVal = Float.NEGATIVE_INFINITY
        for (v in values) {
            if (!v.isFinite()) continue
            if (v < minVal) minVal = v
            if (v > maxVal) maxVal = v
        }
        if (!minVal.isFinite() || !maxVal.isFinite()) return FloatRange.ZERO_TO_ONE
        if (minVal < -0.05f && maxVal <= 1.5f) return FloatRange.NEGATIVE_ONE_TO_ONE
        if (maxVal > 2.0f) return FloatRange.ZERO_TO_255

        // Values are in [0, 1.5]: could be [0, 1] normalized OR [-1, 1] on a bright crop (>= 128).
        // Disambiguate using unmasked reference pixels when available.
        val hw = width * height
        if (referencePixels != null && referenceMask != null &&
            referencePixels.size >= hw && referenceMask.size >= hw
        ) {
            var errZeroToOne = 0.0
            var errNegOneToOne = 0.0
            var samples = 0
            val stride = max(1, hw / 256)
            var i = 0
            while (i < hw && samples < 256) {
                if (!referenceMask[i]) {
                    val px = referencePixels[i]
                    val refR = ((px ushr 16) and 0xFF).toFloat()
                    val refG = ((px ushr 8) and 0xFF).toFloat()
                    val refB = (px and 0xFF).toFloat()
                    val outR: Float
                    val outG: Float
                    val outB: Float
                    if (layout == TensorLayout.NHWC) {
                        val p = i * 3
                        outR = values[p]
                        outG = values[p + 1]
                        outB = values[p + 2]
                    } else {
                        outR = values[i]
                        outG = values[hw + i]
                        outB = values[2 * hw + i]
                    }
                    errZeroToOne += abs(outR * 255f - refR) + abs(outG * 255f - refG) + abs(outB * 255f - refB)
                    errNegOneToOne += abs((outR + 1f) * 127.5f - refR) +
                        abs((outG + 1f) * 127.5f - refG) +
                        abs((outB + 1f) * 127.5f - refB)
                    samples++
                }
                i += stride
            }
            if (samples > 0 && errNegOneToOne + 1e-3 < errZeroToOne) {
                return FloatRange.NEGATIVE_ONE_TO_ONE
            }
        }
        return if (minVal < 0f) FloatRange.NEGATIVE_ONE_TO_ONE else FloatRange.ZERO_TO_ONE
    }

    private fun denormalizeFloatToByte(v: Float, range: FloatRange): Int {
        if (!v.isFinite()) return 0
        return when (range) {
            FloatRange.NEGATIVE_ONE_TO_ONE -> ((v + 1.0f) * 127.5f).roundToInt().coerceIn(0, 255)
            FloatRange.ZERO_TO_ONE -> (v * 255.0f).roundToInt().coerceIn(0, 255)
            FloatRange.ZERO_TO_255 -> v.roundToInt().coerceIn(0, 255)
        }
    }

    fun inpaint(
        sourceBitmap: Bitmap,
        strokes: List<HealStroke>,
        context: Context? = null,
        fallbackBoundaryFeatherPx: Float = FALLBACK_BOUNDARY_FEATHER_PX
    ): Bitmap {
        return inpaintWithResult(
            sourceBitmap = sourceBitmap,
            strokes = strokes,
            context = context,
            fallbackBoundaryFeatherPx = fallbackBoundaryFeatherPx
        ).bitmap
    }

    fun inpaintWithResult(
        sourceBitmap: Bitmap,
        strokes: List<HealStroke>,
        context: Context? = null,
        fallbackBoundaryFeatherPx: Float = FALLBACK_BOUNDARY_FEATHER_PX
    ): InpaintResult {
        if (sourceBitmap.isRecycled || sourceBitmap.width <= 0 || sourceBitmap.height <= 0) {
            throw IllegalStateException("Source image unavailable for healing.")
        }
        val srcW = sourceBitmap.width
        val srcH = sourceBitmap.height
        val fallbackMask = rasterizeAlphaMask(
            width = srcW,
            height = srcH,
            strokes = strokes,
            defaultFeatherPx = fallbackBoundaryFeatherPx,
            keepFullCore = true
        )
        val effectiveOpacity = strokes.maxOfOrNull { it.opacity.coerceIn(0.10f, 1.0f) } ?: 1.0f

        // 1. Lazily initialize ONNX Runtime session on first Heal stroke (if asset exists)
        val bundle = ensureOnnxSessionBundle(context)

        // When MI-GAN is present in assets, dilate the removal mask by 4px so anti-aliased edge
        // pixels around small objects are completely removed and replaced with synthesized texture.
        val activeMask = if (bundle != null) {
            dilateAlphaMaskForOnnx(
                alphaMask = fallbackMask,
                width = srcW,
                height = srcH,
                dilatePx = ONNX_MASK_DILATE_PX,
                edgeFeatherPx = ONNX_BOUNDARY_FEATHER_PX
            )
        } else {
            fallbackMask
        }

        // 2. Find exact full-resolution mask bounding box
        var fullMinX = srcW
        var fullMaxX = -1
        var fullMinY = srcH
        var fullMaxY = -1
        for (y in 0 until srcH) {
            val rowOff = y * srcW
            for (x in 0 until srcW) {
                if (activeMask[rowOff + x] > 0) {
                    if (x < fullMinX) fullMinX = x
                    if (x > fullMaxX) fullMaxX = x
                    if (y < fullMinY) fullMinY = y
                    if (y > fullMaxY) fullMaxY = y
                }
            }
        }
        if (fullMaxX < fullMinX || fullMaxY < fullMinY) {
            val copy = sourceBitmap.copy(Bitmap.Config.ARGB_8888, false)
                ?: throw IllegalStateException("Unable to copy source bitmap.")
            return InpaintResult(
                bitmap = copy,
                backend = InpaintBackend.ALGORITHMIC_FALLBACK
            )
        }

        // 3. Smart padded square bounding-box crop around the masked object
        //    (when MI-GAN is present, expand context crop by >= 64px around the dilated brush mask)
        val holeW = fullMaxX - fullMinX + 1
        val holeH = fullMaxY - fullMinY + 1
        val holeSpan = max(holeW, holeH)
        val pad = if (bundle != null) {
            max(ONNX_CONTEXT_PAD_PX, (holeSpan * 1.0f).roundToInt())
        } else {
            max(36, (holeSpan * 0.75f).roundToInt())
        }
        val bounds = computeContextCropBounds(
            minX = fullMinX,
            minY = fullMinY,
            maxX = fullMaxX,
            maxY = fullMaxY,
            srcW = srcW,
            srcH = srcH,
            minPadPx = pad,
            minSquareSide = if (bundle != null) ONNX_MIN_CROP_SIDE else 0
        )
        val sqX0 = bounds.x0
        val sqY0 = bounds.y0
        val sqW = bounds.width
        val sqH = bounds.height

        // Read ONLY the cropped square pixels directly from full-res sourceBitmap
        var cropOrigPixels: IntArray? = IntArray(sqW * sqH)
        sourceBitmap.getPixels(cropOrigPixels!!, 0, sqW, sqX0, sqY0, sqW, sqH)

        // Extract binary mask inside the full-res square crop (> 0 so every pixel touched by the
        // feathered composite is explicitly synthesized and no dark outer-feather fringe leaks in)
        val cropOrigMask = BooleanArray(sqW * sqH) { i ->
            val lx = i % sqW
            val ly = i / sqW
            activeMask[(sqY0 + ly) * srcW + (sqX0 + lx)] > 0
        }

        val maxWorkDim = if (bundle != null) ONNX_MAX_WORK_DIM else CROP_WORK_MAX_DIM
        val workScale = min(1f, maxWorkDim.toFloat() / max(sqW, sqH).toFloat())
        val workW = max(8, (sqW * workScale).roundToInt())
        val workH = max(8, (sqH * workScale).roundToInt())
        val workLen = workW * workH

        val workPixels = IntArray(workLen)
        val workMask = BooleanArray(workLen)

        if (workW == sqW && workH == sqH) {
            cropOrigPixels.copyInto(workPixels)
            cropOrigMask.copyInto(workMask)
        } else {
            var cropSrcBmp: Bitmap? = null
            var scaledWorkBmp: Bitmap? = null
            try {
                cropSrcBmp = createBitmap(sqW, sqH).apply {
                    setPixels(cropOrigPixels, 0, sqW, 0, 0, sqW, sqH)
                }
                scaledWorkBmp = cropSrcBmp.scale(workW, workH)
                scaledWorkBmp.getPixels(workPixels, 0, workW, 0, 0, workW, workH)
                for (wy in 0 until workH) {
                    val sy = ((wy + 0.5f) * sqH / workH).toInt().coerceIn(0, sqH - 1)
                    val rowOff = wy * workW
                    for (wx in 0 until workW) {
                        val sx = ((wx + 0.5f) * sqW / workW).toInt().coerceIn(0, sqW - 1)
                        workMask[rowOff + wx] = cropOrigMask[sy * sqW + sx]
                    }
                }
            } finally {
                if (scaledWorkBmp != null && scaledWorkBmp !== cropSrcBmp && !scaledWorkBmp.isRecycled) {
                    scaledWorkBmp.recycle()
                }
                if (cropSrcBmp != null && !cropSrcBmp.isRecycled) {
                    cropSrcBmp.recycle()
                }
            }
            cropOrigPixels = null
        }

        var usedBackend = InpaintBackend.ALGORITHMIC_FALLBACK
        var usedAccel: String? = null
        var workOutPixels: IntArray? = null

        if (bundle != null) {
            workOutPixels = try {
                var activeBundle = bundle
                var inferred = synchronized(inferenceLock) {
                    runOnnxInpaintCrop(
                        bundle = activeBundle,
                        workPixels = workPixels,
                        workMask = workMask,
                        workW = workW,
                        workH = workH
                    )
                }
                if (isSynthesizedHoleCollapsedBlack(inferred, workPixels, workMask) && activeBundle.usedNnapi && context != null) {
                    Log.w(TAG, "ONNX output collapsed to black under ${activeBundle.accelLabel}; retrying with XNNPACK/CPU session.")
                    val appCtx = context.applicationContext ?: context
                    val cpuBundle = synchronized(sessionLock) {
                        val current = sessionBundle
                        if (current != null && !current.usedNnapi) {
                            current
                        } else {
                            val file = extractAssetModelIfNeeded(appCtx)
                            val env = ortEnv ?: OrtEnvironment.getEnvironment().also { ortEnv = it }
                            if (file != null) {
                                createAcceleratedSession(env, file, allowNnapi = false).also { rebuilt ->
                                    runCatching { current?.session?.close() }
                                    sessionBundle = rebuilt
                                    activeAccelerationLabel = rebuilt.accelLabel
                                }
                            } else null
                        }
                    }
                    if (cpuBundle != null) {
                        activeBundle = cpuBundle
                        inferred = synchronized(inferenceLock) {
                            runOnnxInpaintCrop(
                                bundle = activeBundle,
                                workPixels = workPixels,
                                workMask = workMask,
                                workW = workW,
                                workH = workH
                            )
                        }
                    }
                }
                if (isSynthesizedHoleCollapsedBlack(inferred, workPixels, workMask)) {
                    Log.w(TAG, "ONNX output produced collapsed black hole; falling back to algorithmic inpainting.")
                    null
                } else {
                    usedBackend = InpaintBackend.ONNX_MIGAN
                    usedAccel = activeBundle.accelLabel
                    inferred
                }
            } catch (t: Throwable) {
                Log.w(TAG, "ONNX inference failed on crop (${workW}x${workH}); falling back to algorithmic inpainting", t)
                null
            }
        }

        val activeCompositeMask: IntArray
        val activeWorkMask: BooleanArray
        if (workOutPixels == null) {
            // If ONNX failed/collapsed after initial bundle check, run algorithmic solver with
            // the 8px feathered fallback mask rather than leaving the 4px dilated ONNX mask.
            activeCompositeMask = fallbackMask
            activeWorkMask = if (bundle == null) {
                workMask
            } else if (workW == sqW && workH == sqH) {
                BooleanArray(workLen) { i ->
                    val lx = i % sqW
                    val ly = i / sqW
                    fallbackMask[(sqY0 + ly) * srcW + (sqX0 + lx)] > 0
                }
            } else {
                BooleanArray(workLen) { i ->
                    val wx = i % workW
                    val wy = i / workW
                    val sx = ((wx + 0.5f) * sqW / workW).toInt().coerceIn(0, sqW - 1)
                    val sy = ((wy + 0.5f) * sqH / workH).toInt().coerceIn(0, sqH - 1)
                    fallbackMask[(sqY0 + sy) * srcW + (sqX0 + sx)] > 0
                }
            }
            val cropFallbackMask = IntArray(workLen) { i ->
                val wx = i % workW
                val wy = i / workW
                val sx = if (workW == sqW) wx else ((wx + 0.5f) * sqW / workW).toInt().coerceIn(0, sqW - 1)
                val sy = if (workH == sqH) wy else ((wy + 0.5f) * sqH / workH).toInt().coerceIn(0, sqH - 1)
                fallbackMask[(sqY0 + sy) * srcW + (sqX0 + sx)]
            }
            workOutPixels = runAlgorithmicCropInpaint(
                workPixels = workPixels,
                workMask = activeWorkMask,
                workW = workW,
                workH = workH,
                featherMaskHint = cropFallbackMask
            )
            usedBackend = InpaintBackend.ALGORITHMIC_FALLBACK
            usedAccel = null
        } else {
            activeCompositeMask = activeMask
            activeWorkMask = workMask
        }

        // Map synthesized pixels cleanly back into the full-res square crop (high-res unscaled if 1:1)
        val synthesizedCropPixels = if (workW == sqW && workH == sqH) {
            val localOrig = cropOrigPixels!!
            IntArray(workLen) { i ->
                if (!activeWorkMask[i]) {
                    localOrig[i]
                } else {
                    val a = localOrig[i] and (0xFF shl 24)
                    a or (workOutPixels[i] and 0x00FFFFFF)
                }
            }
        } else {
            var workOutBmp: Bitmap? = null
            var upscaledCropBmp: Bitmap? = null
            try {
                // Ensure opaque alpha across workOutPixels before premultiplied Bitmap.scale()
                val opaqueOut = IntArray(workLen) { i ->
                    (0xFF shl 24) or (workOutPixels[i] and 0x00FFFFFF)
                }
                workOutBmp = createBitmap(workW, workH).apply {
                    setPixels(opaqueOut, 0, workW, 0, 0, workW, workH)
                }
                upscaledCropBmp = workOutBmp.scale(sqW, sqH)
                IntArray(sqW * sqH).also { arr ->
                    upscaledCropBmp.getPixels(arr, 0, sqW, 0, 0, sqW, sqH)
                }
            } finally {
                if (upscaledCropBmp != null && upscaledCropBmp !== workOutBmp && !upscaledCropBmp.isRecycled) {
                    upscaledCropBmp.recycle()
                }
                if (workOutBmp != null && !workOutBmp.isRecycled) {
                    workOutBmp.recycle()
                }
            }
        }

        // 5. Strict Pixel Preservation Composite:
        // Copy exact full-res original pixels everywhere, blending ONLY across the feathered boundary
        val finalPixels = IntArray(srcW * srcH)
        sourceBitmap.getPixels(finalPixels, 0, srcW, 0, 0, srcW, srcH)

        for (ly in 0 until sqH) {
            val fy = sqY0 + ly
            val fullRow = fy * srcW
            val cropRow = ly * sqW
            for (lx in 0 until sqW) {
                val fx = sqX0 + lx
                val fullIdx = fullRow + fx
                val m = activeCompositeMask[fullIdx]
                if (m <= 0) continue // 100% untouched original pixel outside mask

                val fillPx = synthesizedCropPixels[cropRow + lx]
                val effectiveM = (m * effectiveOpacity).coerceIn(0f, 255f)
                if (effectiveM >= 254.5f) {
                    val origA = finalPixels[fullIdx] and (0xFF shl 24)
                    finalPixels[fullIdx] = origA or (fillPx and 0x00FFFFFF)
                } else {
                    val t = effectiveM / 255f
                    val inv = 1f - t
                    val orig = finalPixels[fullIdx]
                    val a = (orig ushr 24) and 0xFF
                    val r = (((orig ushr 16) and 0xFF) * inv + ((fillPx ushr 16) and 0xFF) * t).roundToInt().coerceIn(0, 255)
                    val g = (((orig ushr 8) and 0xFF) * inv + ((fillPx ushr 8) and 0xFF) * t).roundToInt().coerceIn(0, 255)
                    val b = ((orig and 0xFF) * inv + (fillPx and 0xFF) * t).roundToInt().coerceIn(0, 255)
                    finalPixels[fullIdx] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }

        val outBitmap = createBitmap(srcW, srcH).apply {
            setPixels(finalPixels, 0, srcW, 0, 0, srcW, srcH)
        }
        return InpaintResult(
            bitmap = outBitmap,
            backend = usedBackend,
            accelerationProvider = usedAccel
        )
    }

    internal fun isSynthesizedHoleCollapsedBlack(
        synthesizedPixels: IntArray,
        origPixels: IntArray,
        removeMask: BooleanArray
    ): Boolean {
        val len = min(synthesizedPixels.size, min(origPixels.size, removeMask.size))
        if (len <= 0) return false
        var unmaskedLumaSum = 0.0
        var unmaskedCount = 0
        var maskedMaxChannel = 0
        var maskedCount = 0
        for (i in 0 until len) {
            if (removeMask[i]) {
                val px = synthesizedPixels[i]
                val r = (px ushr 16) and 0xFF
                val g = (px ushr 8) and 0xFF
                val b = px and 0xFF
                val mx = max(r, max(g, b))
                if (mx > maskedMaxChannel) maskedMaxChannel = mx
                maskedCount++
            } else {
                val px = origPixels[i]
                val r = (px ushr 16) and 0xFF
                val g = (px ushr 8) and 0xFF
                val b = px and 0xFF
                unmaskedLumaSum += 0.299 * r + 0.587 * g + 0.114 * b
                unmaskedCount++
            }
        }
        if (maskedCount == 0 || unmaskedCount == 0) return false
        val unmaskedMeanLuma = unmaskedLumaSum / unmaskedCount.toDouble()
        return unmaskedMeanLuma > 12.0 && maskedMaxChannel <= 2
    }

    private fun runOnnxInpaintCrop(
        bundle: SessionBundle,
        workPixels: IntArray,
        workMask: BooleanArray,
        workW: Int,
        workH: Int
    ): IntArray {
        val session = bundle.session
        val ioSpec = bundle.ioSpec
        val env = ortEnv ?: OrtEnvironment.getEnvironment()

        // Match fixed TensorInfo spatial shape if specified; otherwise reflect-pad crops < 512 up to >= 512
        // so dynamic pipeline models (e.g., migan_pipeline_v2.onnx) always run on valid >=512 inputs.
        val fixedW = ioSpec.imageInput.fixedWidth ?: ioSpec.maskInput.fixedWidth
        val fixedH = ioSpec.imageInput.fixedHeight ?: ioSpec.maskInput.fixedHeight

        val inferW: Int
        val inferH: Int
        val inferPixels: IntArray
        val inferMask: BooleanArray
        val usedEdgePad: Boolean

        if (fixedW != null && fixedH != null && fixedW > 0 && fixedH > 0 && (fixedW != workW || fixedH != workH)) {
            inferW = fixedW
            inferH = fixedH
            inferPixels = scalePixels(workPixels, workW, workH, inferW, inferH)
            inferMask = scaleMask(workMask, workW, workH, inferW, inferH)
            usedEdgePad = false
        } else if (workW < ONNX_MIN_CROP_SIDE || workH < ONNX_MIN_CROP_SIDE) {
            inferW = max(workW, ONNX_MIN_CROP_SIDE)
            inferH = max(workH, ONNX_MIN_CROP_SIDE)
            inferPixels = IntArray(inferW * inferH)
            inferMask = BooleanArray(inferW * inferH)
            for (y in 0 until inferH) {
                val sy = y.coerceAtMost(workH - 1)
                for (x in 0 until inferW) {
                    val sx = x.coerceAtMost(workW - 1)
                    val dstIdx = y * inferW + x
                    inferPixels[dstIdx] = workPixels[sy * workW + sx]
                    // Padded border pixels must be marked KEEP (false in removeMask)
                    inferMask[dstIdx] = if (x < workW && y < workH) workMask[sy * workW + sx] else false
                }
            }
            usedEdgePad = true
        } else {
            inferW = workW
            inferH = workH
            inferPixels = workPixels
            inferMask = workMask
            usedEdgePad = false
        }

        var imageTensor: OnnxTensor? = null
        var maskTensor: OnnxTensor? = null
        try {
            imageTensor = createImageInputTensor(
                env = env,
                spec = ioSpec.imageInput,
                pixels = inferPixels,
                width = inferW,
                height = inferH
            )
            maskTensor = createMaskInputTensor(
                env = env,
                spec = ioSpec.maskInput,
                removeMask = inferMask,
                width = inferW,
                height = inferH
            )

            val inputs = mapOf(
                ioSpec.imageInput.name to imageTensor,
                ioSpec.maskInput.name to maskTensor
            )

            session.run(inputs).use { results ->
                val opt = results.get(ioSpec.resultOutput.name)
                val outValue = if (opt.isPresent) opt.get() else results[0]
                val outTensor = outValue as? OnnxTensor
                    ?: throw IllegalStateException("Expected OnnxTensor output for '${ioSpec.resultOutput.name}'.")
                val outInfo = outTensor.info
                val outShape = outInfo.shape
                val outSpec = inspectTensorSpec(
                    name = ioSpec.resultOutput.name,
                    javaType = outInfo.type,
                    shape = outShape,
                    expectedChannels = 3
                )
                val outH = outSpec.fixedHeight ?: inferH
                val outW = outSpec.fixedWidth ?: inferW
                if (outShape.size != 4 || outW <= 0 || outH <= 0) {
                    throw IllegalStateException("Unexpected ONNX output tensor shape: ${outShape.contentToString()}")
                }

                val unpacked = unpackTypedOutputTensor(
                    outTensor = outTensor,
                    outSpec = outSpec,
                    outW = outW,
                    outH = outH,
                    referencePixels = if (outW == inferW && outH == inferH) inferPixels else null,
                    referenceMask = if (outW == inferW && outH == inferH) inferMask else null
                )

                val inferSizedOut = if (outW == inferW && outH == inferH) {
                    unpacked
                } else {
                    scalePixels(unpacked, outW, outH, inferW, inferH)
                }

                return if (usedEdgePad) {
                    IntArray(workW * workH).also { cropped ->
                        for (y in 0 until workH) {
                            System.arraycopy(inferSizedOut, y * inferW, cropped, y * workW, workW)
                        }
                    }
                } else if (inferW != workW || inferH != workH) {
                    scalePixels(inferSizedOut, inferW, inferH, workW, workH)
                } else {
                    inferSizedOut
                }
            }
        } finally {
            runCatching { imageTensor?.close() }
            runCatching { maskTensor?.close() }
        }
    }

    private fun createImageInputTensor(
        env: OrtEnvironment,
        spec: TensorSpec,
        pixels: IntArray,
        width: Int,
        height: Int
    ): OnnxTensor {
        val shape = if (spec.layout == TensorLayout.NHWC) {
            longArrayOf(1L, height.toLong(), width.toLong(), 3L)
        } else {
            longArrayOf(1L, 3L, height.toLong(), width.toLong())
        }
        return when (spec.javaType) {
            OnnxJavaType.FLOAT -> {
                val buf = packOnnxImageFloat(
                    pixels = pixels,
                    width = width,
                    height = height,
                    layout = spec.layout,
                    floatRange = FloatRange.NEGATIVE_ONE_TO_ONE
                )
                OnnxTensor.createTensor(env, buf, shape)
            }
            OnnxJavaType.UINT8 -> {
                val buf = packOnnxImageUint8(pixels, width, height, spec.layout)
                OnnxTensor.createTensor(env, buf, shape, OnnxJavaType.UINT8)
            }
            else -> {
                val buf = packOnnxImageUint8(pixels, width, height, spec.layout)
                OnnxTensor.createTensor(env, buf, shape, spec.javaType)
            }
        }
    }

    private fun createMaskInputTensor(
        env: OrtEnvironment,
        spec: TensorSpec,
        removeMask: BooleanArray,
        width: Int,
        height: Int
    ): OnnxTensor {
        val shape = if (spec.layout == TensorLayout.NHWC) {
            longArrayOf(1L, height.toLong(), width.toLong(), 1L)
        } else {
            longArrayOf(1L, 1L, height.toLong(), width.toLong())
        }
        return when (spec.javaType) {
            OnnxJavaType.FLOAT -> {
                val buf = packOnnxMaskFloat(removeMask, width, height, invertPolarity = false)
                OnnxTensor.createTensor(env, buf, shape)
            }
            OnnxJavaType.UINT8 -> {
                val buf = packOnnxMaskUint8(removeMask, width, height, invertPolarity = false)
                OnnxTensor.createTensor(env, buf, shape, OnnxJavaType.UINT8)
            }
            else -> {
                val buf = packOnnxMaskUint8(removeMask, width, height, invertPolarity = false)
                OnnxTensor.createTensor(env, buf, shape, spec.javaType)
            }
        }
    }

    private fun unpackTypedOutputTensor(
        outTensor: OnnxTensor,
        outSpec: TensorSpec,
        outW: Int,
        outH: Int,
        referencePixels: IntArray?,
        referenceMask: BooleanArray?
    ): IntArray {
        val actualType = outTensor.info.type
        return when (actualType) {
            OnnxJavaType.FLOAT -> {
                unpackOnnxFloatResultTensor(
                    resultBuffer = outTensor.floatBuffer,
                    width = outW,
                    height = outH,
                    layout = outSpec.layout,
                    referencePixels = referencePixels,
                    referenceMask = referenceMask
                )
            }
            OnnxJavaType.DOUBLE -> {
                val hw = outW * outH
                val db = outTensor.doubleBuffer.duplicate()
                val base = db.position()
                require(db.remaining() >= 3 * hw) {
                    "Unexpected ONNX double output count: remaining=${db.remaining()}, expected=${3 * hw}"
                }
                val fb = FloatBuffer.allocate(3 * hw)
                for (i in 0 until 3 * hw) {
                    fb.put(i, db.get(base + i).toFloat())
                }
                fb.rewind()
                unpackOnnxFloatResultTensor(
                    resultBuffer = fb,
                    width = outW,
                    height = outH,
                    layout = outSpec.layout,
                    referencePixels = referencePixels,
                    referenceMask = referenceMask
                )
            }
            OnnxJavaType.FLOAT16 -> {
                val hw = outW * outH
                val sb = outTensor.shortBuffer.duplicate()
                val base = sb.position()
                require(sb.remaining() >= 3 * hw) {
                    "Unexpected ONNX float16 output count: remaining=${sb.remaining()}, expected=${3 * hw}"
                }
                val fb = FloatBuffer.allocate(3 * hw)
                for (i in 0 until 3 * hw) {
                    fb.put(i, float16ToFloat(sb.get(base + i).toInt() and 0xFFFF))
                }
                fb.rewind()
                unpackOnnxFloatResultTensor(
                    resultBuffer = fb,
                    width = outW,
                    height = outH,
                    layout = outSpec.layout,
                    referencePixels = referencePixels,
                    referenceMask = referenceMask
                )
            }
            OnnxJavaType.INT32 -> {
                val hw = outW * outH
                val ib = outTensor.intBuffer.duplicate()
                val base = ib.position()
                require(ib.remaining() >= 3 * hw) {
                    "Unexpected ONNX int32 output count: remaining=${ib.remaining()}, expected=${3 * hw}"
                }
                if (outSpec.layout == TensorLayout.NHWC) {
                    IntArray(hw) { i ->
                        val p = base + i * 3
                        val r = ib.get(p).coerceIn(0, 255)
                        val g = ib.get(p + 1).coerceIn(0, 255)
                        val b = ib.get(p + 2).coerceIn(0, 255)
                        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                } else {
                    IntArray(hw) { i ->
                        val r = ib.get(base + i).coerceIn(0, 255)
                        val g = ib.get(base + hw + i).coerceIn(0, 255)
                        val b = ib.get(base + 2 * hw + i).coerceIn(0, 255)
                        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
            }
            else -> {
                unpackOnnxResultTensor(
                    resultBuffer = outTensor.byteBuffer,
                    width = outW,
                    height = outH,
                    layout = outSpec.layout
                )
            }
        }
    }

    private fun float16ToFloat(halfBits: Int): Float {
        val sign = (halfBits and 0x8000) shl 16
        val exp = (halfBits ushr 10) and 0x1F
        val mant = halfBits and 0x03FF
        val bits = when (exp) {
            0 -> {
                if (mant == 0) {
                    sign
                } else {
                    var m = mant
                    var e = 127 - 14
                    while ((m and 0x0400) == 0) {
                        m = m shl 1
                        e--
                    }
                    sign or (e shl 23) or ((m and 0x03FF) shl 13)
                }
            }
            31 -> sign or 0x7F800000 or (mant shl 13)
            else -> sign or ((exp + (127 - 15)) shl 23) or (mant shl 13)
        }
        return java.lang.Float.intBitsToFloat(bits)
    }

    private fun scalePixels(
        srcPixels: IntArray,
        srcW: Int,
        srcH: Int,
        dstW: Int,
        dstH: Int
    ): IntArray {
        val opaque = IntArray(srcW * srcH) { i -> (0xFF shl 24) or (srcPixels[i] and 0x00FFFFFF) }
        var srcBmp: Bitmap? = null
        var dstBmp: Bitmap? = null
        return try {
            srcBmp = createBitmap(srcW, srcH).apply {
                setPixels(opaque, 0, srcW, 0, 0, srcW, srcH)
            }
            dstBmp = srcBmp.scale(dstW, dstH)
            IntArray(dstW * dstH).also { out ->
                dstBmp.getPixels(out, 0, dstW, 0, 0, dstW, dstH)
            }
        } finally {
            if (dstBmp != null && dstBmp !== srcBmp && !dstBmp.isRecycled) dstBmp.recycle()
            if (srcBmp != null && !srcBmp.isRecycled) srcBmp.recycle()
        }
    }

    private fun scaleMask(
        srcMask: BooleanArray,
        srcW: Int,
        srcH: Int,
        dstW: Int,
        dstH: Int
    ): BooleanArray {
        return BooleanArray(dstW * dstH) { i ->
            val dx = i % dstW
            val dy = i / dstW
            val sx = ((dx + 0.5f) * srcW / dstW).toInt().coerceIn(0, srcW - 1)
            val sy = ((dy + 0.5f) * srcH / dstH).toInt().coerceIn(0, srcH - 1)
            srcMask[sy * srcW + sx]
        }
    }

    private fun runAlgorithmicCropInpaint(
        workPixels: IntArray,
        workMask: BooleanArray,
        workW: Int,
        workH: Int,
        featherMaskHint: IntArray? = null
    ): IntArray {
        val workLen = workW * workH
        val workR = FloatArray(workLen)
        val workG = FloatArray(workLen)
        val workB = FloatArray(workLen)
        for (i in 0 until workLen) {
            val px = workPixels[i]
            workR[i] = ((px ushr 16) and 0xFF).toFloat()
            workG[i] = ((px ushr 8) and 0xFF).toFloat()
            workB[i] = (px and 0xFF).toFloat()
        }

        val baseLevel = PyramidLevel(
            width = workW,
            height = workH,
            r = workR,
            g = workG,
            b = workB,
            gx = FloatArray(workLen),
            gy = FloatArray(workLen),
            mask = workMask.copyOf()
        )
        recomputeGradients(baseLevel)
        val baseCrossBorder = computeInteriorBorderMask(baseLevel, featherMaskHint)

        // Directional Edge-Aware Multi-Scale PatchMatch + Sharp Exemplar Synthesis
        runEdgeAwarePatchMatch(baseLevel, baseCrossBorder)

        // Gradient-domain Poisson seamless shadow/lighting blend anchored to untouched boundary
        runPoissonSeamlessBlend(baseLevel, baseCrossBorder)

        return IntArray(workLen) { i ->
            val ri = baseLevel.r[i].roundToInt().coerceIn(0, 255)
            val gi = baseLevel.g[i].roundToInt().coerceIn(0, 255)
            val bi = baseLevel.b[i].roundToInt().coerceIn(0, 255)
            (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
        }
    }

    private fun runEdgeAwarePatchMatch(
        baseLevel: PyramidLevel,
        baseCrossBorder: BooleanArray
    ) {
        val pyramid = buildPyramid(baseLevel, maxLevels = 3)
        val crossBorderPyramid = buildCrossBorderPyramid(baseCrossBorder, pyramid)
        val coarsest = pyramid.last()
        val coarsestCrossBorder = crossBorderPyramid.last()
        initializeDirectionalStructure(coarsest, coarsestCrossBorder)
        recomputeGradients(coarsest)

        val rng = Random(0x5A31C0DE)
        var prevNnfX: IntArray? = null
        var prevNnfY: IntArray? = null
        var prevW = 0
        var prevH = 0

        for (lvlIndex in pyramid.lastIndex downTo 0) {
            val lvl = pyramid[lvlIndex]
            val w = lvl.width
            val h = lvl.height
            val total = w * h

            if (lvlIndex < pyramid.lastIndex) {
                val coarser = pyramid[lvlIndex + 1]
                upsampleColorsToMasked(coarser, lvl)
                recomputeGradients(lvl)
            }

            val patchR = if (lvlIndex == 0 && min(w, h) >= 24) 3 else 2
            if (w <= patchR * 2 + 2 || h <= patchR * 2 + 2) continue

            val crossBorder = crossBorderPyramid[lvlIndex]

            val validPatch = BooleanArray(total)
            val validIndicesTmp = IntArray(total)
            var validCount = 0
            // First restrict valid exemplar patches strictly to interior pixels (never crossing high-contrast borders)
            for (y in patchR until h - patchR) {
                val rowOff = y * w
                for (x in patchR until w - patchR) {
                    var allKnownInterior = true
                    loopY@ for (dy in -patchR..patchR) {
                        val pRow = (y + dy) * w
                        for (dx in -patchR..patchR) {
                            val pIdx = pRow + x + dx
                            if (lvl.mask[pIdx] || crossBorder[pIdx]) {
                                allKnownInterior = false
                                break@loopY
                            }
                        }
                    }
                    if (allKnownInterior) {
                        val idx = rowOff + x
                        validPatch[idx] = true
                        validIndicesTmp[validCount++] = idx
                    }
                }
            }
            val hasInteriorValid = validCount > 0
            if (!hasInteriorValid) {
                for (y in patchR until h - patchR) {
                    val rowOff = y * w
                    for (x in patchR until w - patchR) {
                        var allKnown = true
                        loopFallbackY@ for (dy in -patchR..patchR) {
                            val pRow = (y + dy) * w
                            for (dx in -patchR..patchR) {
                                if (lvl.mask[pRow + x + dx]) {
                                    allKnown = false
                                    break@loopFallbackY
                                }
                            }
                        }
                        if (allKnown) {
                            val idx = rowOff + x
                            validPatch[idx] = true
                            validIndicesTmp[validCount++] = idx
                        }
                    }
                }
            }

            if (validCount == 0) {
                prevNnfX = null
                prevNnfY = null
                continue
            }
            val validSources = validIndicesTmp.copyOf(validCount)
            val ssdBorderFilter = if (hasInteriorValid) crossBorder else null

            val nnfX = IntArray(total)
            val nnfY = IntArray(total)
            val nnfErr = FloatArray(total)

            for (y in 0 until h) {
                val rowOff = y * w
                for (x in 0 until w) {
                    val idx = rowOff + x
                    if (!lvl.mask[idx]) {
                        nnfX[idx] = x
                        nnfY[idx] = y
                        continue
                    }
                    var initX = -1
                    var initY = -1
                    if (prevNnfX != null && prevNnfY != null && prevW > 0 && prevH > 0) {
                        val cx = (x / 2).coerceIn(0, prevW - 1)
                        val cy = (y / 2).coerceIn(0, prevH - 1)
                        val cIdx = cy * prevW + cx
                        val candX = (prevNnfX[cIdx] * 2).coerceIn(patchR, w - 1 - patchR)
                        val candY = (prevNnfY[cIdx] * 2).coerceIn(patchR, h - 1 - patchR)
                        if (validPatch[candY * w + candX]) {
                            initX = candX
                            initY = candY
                        }
                    }
                    if (initX < 0) {
                        val sIdx = validSources[rng.nextInt(validCount)]
                        initX = sIdx % w
                        initY = sIdx / w
                    }
                    nnfX[idx] = initX
                    nnfY[idx] = initY
                }
            }

            val patchSize = patchR * 2 + 1
            val gaussWeights = buildGaussianKernel(patchR)
            val emPasses = if (lvlIndex == 0) 2 else 3
            val pmPasses = if (lvlIndex == 0) 4 else 5

            repeat(emPasses) { emStep ->
                recomputeGradients(lvl)

                for (y in patchR until h - patchR) {
                    val rowOff = y * w
                    for (x in patchR until w - patchR) {
                        val idx = rowOff + x
                        if (!lvl.mask[idx]) continue
                        nnfErr[idx] = edgeAwarePatchSsd(lvl, x, y, nnfX[idx], nnfY[idx], patchR, ssdBorderFilter)
                    }
                }

                // Bidirectional PatchMatch propagation + random search
                for (pass in 0 until pmPasses) {
                    val forward = (pass and 1) == 0
                    val yStart = if (forward) patchR else h - 1 - patchR
                    val yEnd = if (forward) h - patchR else patchR - 1
                    val step = if (forward) 1 else -1

                    var y = yStart
                    while (y != yEnd) {
                        var x = if (forward) patchR else w - 1 - patchR
                        val xEnd = if (forward) w - patchR else patchR - 1
                        while (x != xEnd) {
                            val idx = y * w + x
                            if (lvl.mask[idx]) {
                                var bestX = nnfX[idx]
                                var bestY = nnfY[idx]
                                var bestErr = nnfErr[idx]

                                // Horizontal propagation
                                val nxH = x - step
                                if (nxH in patchR until w - patchR) {
                                    val nIdx = y * w + nxH
                                    if (lvl.mask[nIdx]) {
                                        val candX = nnfX[nIdx] + step
                                        val candY = nnfY[nIdx]
                                        if (candX in patchR until w - patchR &&
                                            candY in patchR until h - patchR &&
                                            validPatch[candY * w + candX]
                                        ) {
                                            val err = edgeAwarePatchSsd(lvl, x, y, candX, candY, patchR, ssdBorderFilter, bestErr)
                                            if (err < bestErr) {
                                                bestErr = err
                                                bestX = candX
                                                bestY = candY
                                            }
                                        }
                                    }
                                }

                                // Vertical propagation
                                val nyV = y - step
                                if (nyV in patchR until h - patchR) {
                                    val nIdx = nyV * w + x
                                    if (lvl.mask[nIdx]) {
                                        val candX = nnfX[nIdx]
                                        val candY = nnfY[nIdx] + step
                                        if (candX in patchR until w - patchR &&
                                            candY in patchR until h - patchR &&
                                            validPatch[candY * w + candX]
                                        ) {
                                            val err = edgeAwarePatchSsd(lvl, x, y, candX, candY, patchR, ssdBorderFilter, bestErr)
                                            if (err < bestErr) {
                                                bestErr = err
                                                bestX = candX
                                                bestY = candY
                                            }
                                        }
                                    }
                                }

                                // Multi-scale random search around best offset
                                var radius = max(w, h) / 2
                                while (radius >= 1) {
                                    val candX = (bestX + rng.nextInt(-radius, radius + 1)).coerceIn(patchR, w - 1 - patchR)
                                    val candY = (bestY + rng.nextInt(-radius, radius + 1)).coerceIn(patchR, h - 1 - patchR)
                                    if (validPatch[candY * w + candX]) {
                                        val err = edgeAwarePatchSsd(lvl, x, y, candX, candY, patchR, ssdBorderFilter, bestErr)
                                        if (err < bestErr) {
                                            bestErr = err
                                            bestX = candX
                                            bestY = candY
                                        }
                                    }
                                    radius /= 2
                                }

                                // Global exemplar jump probe
                                val globalCand = validSources[rng.nextInt(validCount)]
                                val gx = globalCand % w
                                val gy = globalCand / w
                                val gErr = edgeAwarePatchSsd(lvl, x, y, gx, gy, patchR, ssdBorderFilter, bestErr)
                                if (gErr < bestErr) {
                                    bestErr = gErr
                                    bestX = gx
                                    bestY = gy
                                }

                                nnfX[idx] = bestX
                                nnfY[idx] = bestY
                                nnfErr[idx] = bestErr
                            }
                            x += step
                        }
                        y += step
                    }
                }

                // Sharpened Exemplar Reconstruction:
                // Use smooth Gaussian voting in coarse passes, and sharp exemplar copy
                // on the final finest pass so cobbles, brick mortar, and solid fills stay clean!
                val isFinalSharpenedPass = (lvlIndex == 0 && emStep == emPasses - 1)
                if (isFinalSharpenedPass) {
                    val sharpR = lvl.r.copyOf()
                    val sharpG = lvl.g.copyOf()
                    val sharpB = lvl.b.copyOf()
                    for (y in patchR until h - patchR) {
                        val rowOff = y * w
                        for (x in patchR until w - patchR) {
                            val idx = rowOff + x
                            if (!lvl.mask[idx]) continue
                            val sx = nnfX[idx]
                            val sy = nnfY[idx]
                            if (sx !in patchR until w - patchR || sy !in patchR until h - patchR) continue
                            val sIdx = sy * w + sx
                            if (lvl.mask[sIdx] || (hasInteriorValid && crossBorder[sIdx])) continue
                            // Crisp exemplar copy with subtle 4-connected detail enhancement (never mix unhealed lvl.r[idx])
                            val avgVoteR = 0.25f * (lvl.r[sIdx - 1] + lvl.r[sIdx + 1] + lvl.r[sIdx - w] + lvl.r[sIdx + w])
                            val detailR = lvl.r[sIdx] - avgVoteR
                            val avgVoteG = 0.25f * (lvl.g[sIdx - 1] + lvl.g[sIdx + 1] + lvl.g[sIdx - w] + lvl.g[sIdx + w])
                            val detailG = lvl.g[sIdx] - avgVoteG
                            val avgVoteB = 0.25f * (lvl.b[sIdx - 1] + lvl.b[sIdx + 1] + lvl.b[sIdx - w] + lvl.b[sIdx + w])
                            val detailB = lvl.b[sIdx] - avgVoteB

                            sharpR[idx] = (lvl.r[sIdx] + 0.25f * detailR).coerceIn(0f, 255f)
                            sharpG[idx] = (lvl.g[sIdx] + 0.25f * detailG).coerceIn(0f, 255f)
                            sharpB[idx] = (lvl.b[sIdx] + 0.25f * detailB).coerceIn(0f, 255f)
                        }
                    }
                    // Clamp any border-band masked pixels inside [0 until patchR / w-patchR..w] to nearest valid NNF interior
                    for (y in 0 until h) {
                        val rowOff = y * w
                        val cy = y.coerceIn(patchR, h - 1 - patchR)
                        for (x in 0 until w) {
                            val idx = rowOff + x
                            if (!lvl.mask[idx]) continue
                            if (x in patchR until w - patchR && y in patchR until h - patchR) continue
                            val cx = x.coerceIn(patchR, w - 1 - patchR)
                            val cIdx = cy * w + cx
                            val sIdx = if (!lvl.mask[cIdx] && !(hasInteriorValid && crossBorder[cIdx])) {
                                cIdx
                            } else if (lvl.mask[cIdx]) {
                                val sx = nnfX[cIdx].coerceIn(patchR, w - 1 - patchR)
                                val sy = nnfY[cIdx].coerceIn(patchR, h - 1 - patchR)
                                sy * w + sx
                            } else {
                                validSources[0]
                            }
                            if (!lvl.mask[sIdx] && !(hasInteriorValid && crossBorder[sIdx])) {
                                sharpR[idx] = lvl.r[sIdx]
                                sharpG[idx] = lvl.g[sIdx]
                                sharpB[idx] = lvl.b[sIdx]
                            }
                        }
                    }
                    sharpR.copyInto(lvl.r)
                    sharpG.copyInto(lvl.g)
                    sharpB.copyInto(lvl.b)
                } else {
                    val accR = FloatArray(total)
                    val accG = FloatArray(total)
                    val accB = FloatArray(total)
                    val accW = FloatArray(total)

                    for (py in patchR until h - patchR) {
                        val pRow = py * w
                        for (px in patchR until w - patchR) {
                            val pIdx = pRow + px
                            if (!lvl.mask[pIdx] && hasInteriorValid && crossBorder[pIdx]) continue
                            val sx = if (lvl.mask[pIdx]) nnfX[pIdx] else px
                            val sy = if (lvl.mask[pIdx]) nnfY[pIdx] else py
                            if (sx !in patchR until w - patchR || sy !in patchR until h - patchR) continue

                            val matchConf = if (!lvl.mask[pIdx]) {
                                2.4f
                            } else {
                                1.0f / (1.0f + nnfErr[pIdx] * 0.0003f)
                            }

                            for (dy in -patchR..patchR) {
                                val ty = py + dy
                                val targetRow = ty * w
                                val srcPatchRow = (sy + dy) * w
                                val kRow = (dy + patchR) * patchSize
                                for (dx in -patchR..patchR) {
                                    val tx = px + dx
                                    val tIdx = targetRow + tx
                                    if (!lvl.mask[tIdx]) continue
                                    val sIdx = srcPatchRow + sx + dx
                                    // Never vote masked hole pixels or cross-border pixels into the interior
                                    if (lvl.mask[sIdx] || (hasInteriorValid && crossBorder[sIdx])) continue
                                    val wt = gaussWeights[kRow + dx + patchR] * matchConf
                                    accR[tIdx] += lvl.r[sIdx] * wt
                                    accG[tIdx] += lvl.g[sIdx] * wt
                                    accB[tIdx] += lvl.b[sIdx] * wt
                                    accW[tIdx] += wt
                                }
                            }
                        }
                    }

                    for (i in 0 until total) {
                        if (lvl.mask[i] && accW[i] > 1e-5f) {
                            val invW = 1f / accW[i]
                            lvl.r[i] = (accR[i] * invW).coerceIn(0f, 255f)
                            lvl.g[i] = (accG[i] * invW).coerceIn(0f, 255f)
                            lvl.b[i] = (accB[i] * invW).coerceIn(0f, 255f)
                        }
                    }
                }
            }

            prevNnfX = nnfX
            prevNnfY = nnfY
            prevW = w
            prevH = h
        }
    }

    /**
     * Gradient-Domain Poisson Seamless Solver (Red-Black Gauss-Seidel SOR):
     * Preserves all internal exemplar gradients (cobblestones, brick grain, texture)
     * while seamlessly matching lighting and shadow continuity to the interior boundary,
     * treating cross-border high-contrast edges as zero-flux Neumann boundaries.
     */
    private fun runPoissonSeamlessBlend(
        level: PyramidLevel,
        crossBorder: BooleanArray
    ) {
        val w = level.width
        val h = level.height
        val total = w * h

        val divR = FloatArray(total)
        val divG = FloatArray(total)
        val divB = FloatArray(total)
        val activeNeighbors = IntArray(total) { 4 }

        // Compute internal-only guidance divergence from the synthesized exemplar field G;
        // cross-boundary interior edges get 0 gradient so fill aligns smoothly with interior brightness,
        // while high-contrast crossBorder pixels are excluded (Neumann zero-flux BC) so dark borders never bleed in.
        for (y in 1 until h - 1) {
            val rowOff = y * w
            for (x in 1 until w - 1) {
                val idx = rowOff + x
                if (!level.mask[idx]) continue
                val nLeft = idx - 1
                val nRight = idx + 1
                val nUp = idx - w
                val nDown = idx + w

                var dR = 0f
                var dG = 0f
                var dB = 0f
                var activeCount = 0
                if (level.mask[nLeft]) {
                    dR += level.r[idx] - level.r[nLeft]
                    dG += level.g[idx] - level.g[nLeft]
                    dB += level.b[idx] - level.b[nLeft]
                    activeCount++
                } else if (!crossBorder[nLeft]) {
                    activeCount++
                }
                if (level.mask[nRight]) {
                    dR += level.r[idx] - level.r[nRight]
                    dG += level.g[idx] - level.g[nRight]
                    dB += level.b[idx] - level.b[nRight]
                    activeCount++
                } else if (!crossBorder[nRight]) {
                    activeCount++
                }
                if (level.mask[nUp]) {
                    dR += level.r[idx] - level.r[nUp]
                    dG += level.g[idx] - level.g[nUp]
                    dB += level.b[idx] - level.b[nUp]
                    activeCount++
                } else if (!crossBorder[nUp]) {
                    activeCount++
                }
                if (level.mask[nDown]) {
                    dR += level.r[idx] - level.r[nDown]
                    dG += level.g[idx] - level.g[nDown]
                    dB += level.b[idx] - level.b[nDown]
                    activeCount++
                } else if (!crossBorder[nDown]) {
                    activeCount++
                }
                divR[idx] = dR
                divG[idx] = dG
                divB[idx] = dB
                activeNeighbors[idx] = activeCount
            }
        }

        val solR = level.r.copyOf()
        val solG = level.g.copyOf()
        val solB = level.b.copyOf()
        val omega = 1.65f
        val oneMinusOmega = 1f - omega
        val sorIterations = max(48, min(96, min(w, h) / 2))

        // Red-Black Gauss-Seidel SOR converges ~3x faster than standard Jacobi
        repeat(sorIterations) {
            for (parity in 0..1) {
                for (y in 1 until h - 1) {
                    val rowOff = y * w
                    var x = 1 + ((y + parity) and 1)
                    while (x < w - 1) {
                        val idx = rowOff + x
                        if (level.mask[idx]) {
                            val activeCount = activeNeighbors[idx]
                            if (activeCount > 0) {
                                var sumR = divR[idx]
                                var sumG = divG[idx]
                                var sumB = divB[idx]
                                val nL = idx - 1
                                val nR = idx + 1
                                val nU = idx - w
                                val nD = idx + w
                                if (level.mask[nL] || !crossBorder[nL]) {
                                    sumR += solR[nL]
                                    sumG += solG[nL]
                                    sumB += solB[nL]
                                }
                                if (level.mask[nR] || !crossBorder[nR]) {
                                    sumR += solR[nR]
                                    sumG += solG[nR]
                                    sumB += solB[nR]
                                }
                                if (level.mask[nU] || !crossBorder[nU]) {
                                    sumR += solR[nU]
                                    sumG += solG[nU]
                                    sumB += solB[nU]
                                }
                                if (level.mask[nD] || !crossBorder[nD]) {
                                    sumR += solR[nD]
                                    sumG += solG[nD]
                                    sumB += solB[nD]
                                }
                                val invActive = 1f / activeCount
                                val targetR = sumR * invActive
                                val targetG = sumG * invActive
                                val targetB = sumB * invActive

                                solR[idx] = (oneMinusOmega * solR[idx] + omega * targetR).coerceIn(0f, 255f)
                                solG[idx] = (oneMinusOmega * solG[idx] + omega * targetG).coerceIn(0f, 255f)
                                solB[idx] = (oneMinusOmega * solB[idx] + omega * targetB).coerceIn(0f, 255f)
                            }
                        }
                        x += 2
                    }
                }
            }
        }

        // Match any masked image-edge pixels cleanly to their converged inward neighbors (Neumann BC)
        if (w >= 3 && h >= 3) {
            for (y in 1 until h - 1) {
                val leftIdx = y * w
                if (level.mask[leftIdx]) {
                    solR[leftIdx] = (solR[leftIdx + 1] + divR[leftIdx]).coerceIn(0f, 255f)
                    solG[leftIdx] = (solG[leftIdx + 1] + divG[leftIdx]).coerceIn(0f, 255f)
                    solB[leftIdx] = (solB[leftIdx + 1] + divB[leftIdx]).coerceIn(0f, 255f)
                }
                val rightIdx = y * w + (w - 1)
                if (level.mask[rightIdx]) {
                    solR[rightIdx] = (solR[rightIdx - 1] + divR[rightIdx]).coerceIn(0f, 255f)
                    solG[rightIdx] = (solG[rightIdx - 1] + divG[rightIdx]).coerceIn(0f, 255f)
                    solB[rightIdx] = (solB[rightIdx - 1] + divB[rightIdx]).coerceIn(0f, 255f)
                }
            }
            for (x in 0 until w) {
                if (level.mask[x]) {
                    val ref = w + x.coerceIn(1, w - 2)
                    solR[x] = solR[ref]
                    solG[x] = solG[ref]
                    solB[x] = solB[ref]
                }
                val botIdx = (h - 1) * w + x
                if (level.mask[botIdx]) {
                    val ref = (h - 2) * w + x.coerceIn(1, w - 2)
                    solR[botIdx] = solR[ref]
                    solG[botIdx] = solG[ref]
                    solB[botIdx] = solB[ref]
                }
            }
        }

        // Write converged Poisson solution cleanly into masked interior pixels
        for (i in 0 until total) {
            if (level.mask[i]) {
                level.r[i] = solR[i].coerceIn(0f, 255f)
                level.g[i] = solG[i].coerceIn(0f, 255f)
                level.b[i] = solB[i].coerceIn(0f, 255f)
            }
        }
    }

    private fun buildPyramid(base: PyramidLevel, maxLevels: Int): List<PyramidLevel> {
        val list = mutableListOf(base)
        var current = base
        while (list.size < maxLevels && min(current.width, current.height) >= 24) {
            val nextW = max(12, current.width / 2)
            val nextH = max(12, current.height / 2)
            val nextLen = nextW * nextH
            val nr = FloatArray(nextLen)
            val ng = FloatArray(nextLen)
            val nb = FloatArray(nextLen)
            val nm = BooleanArray(nextLen)

            for (y in 0 until nextH) {
                val fy0 = min(current.height - 1, y * 2)
                val fy1 = min(current.height - 1, fy0 + 1)
                for (x in 0 until nextW) {
                    val fx0 = min(current.width - 1, x * 2)
                    val fx1 = min(current.width - 1, fx0 + 1)
                    val coords = intArrayOf(
                        fy0 * current.width + fx0,
                        fy0 * current.width + fx1,
                        fy1 * current.width + fx0,
                        fy1 * current.width + fx1
                    )
                    var anyMasked = false
                    var knownCount = 0
                    var sr = 0f
                    var sg = 0f
                    var sb = 0f
                    for (ci in coords) {
                        if (current.mask[ci]) {
                            anyMasked = true
                        } else {
                            sr += current.r[ci]
                            sg += current.g[ci]
                            sb += current.b[ci]
                            knownCount++
                        }
                    }
                    val di = y * nextW + x
                    if (knownCount > 0 && !anyMasked) {
                        nr[di] = sr / knownCount
                        ng[di] = sg / knownCount
                        nb[di] = sb / knownCount
                        nm[di] = false
                    } else if (knownCount > 0) {
                        nr[di] = sr / knownCount
                        ng[di] = sg / knownCount
                        nb[di] = sb / knownCount
                        nm[di] = true
                    } else {
                        nr[di] = current.r[coords[0]]
                        ng[di] = current.g[coords[0]]
                        nb[di] = current.b[coords[0]]
                        nm[di] = true
                    }
                }
            }
            val nextLvl = PyramidLevel(
                width = nextW,
                height = nextH,
                r = nr,
                g = ng,
                b = nb,
                gx = FloatArray(nextLen),
                gy = FloatArray(nextLen),
                mask = nm
            )
            recomputeGradients(nextLvl)
            current = nextLvl
            list += current
        }
        return list
    }

    /**
     * Directional isophote-guided boundary propagation + harmonic smoothing at coarsest scale,
     * restricting sampling to interior pixels along the dominant gradient direction rather than
     * crossing high-contrast border lines.
     */
    private fun initializeDirectionalStructure(
        level: PyramidLevel,
        crossBorder: BooleanArray = computeInteriorBorderMask(level)
    ) {
        val w = level.width
        val h = level.height
        val total = w * h
        val remaining = level.mask.copyOf()
        val front = IntArray(total)
        var remainingCount = remaining.count { it }
        val dx8 = intArrayOf(-1, 0, 1, -1, 1, -1, 0, 1)
        val dy8 = intArrayOf(-1, -1, -1, 0, 0, 1, 1, 1)

        while (remainingCount > 0) {
            // Prefer propagating from interior (non-cross-border) known neighbors first
            var frontLen = 0
            for (y in 0 until h) {
                val rowOff = y * w
                for (x in 0 until w) {
                    val idx = rowOff + x
                    if (!remaining[idx]) continue
                    var hasInteriorKnown = false
                    for (n in 0 until 8) {
                        val nx = x + dx8[n]
                        val ny = y + dy8[n]
                        if (nx in 0 until w && ny in 0 until h) {
                            val nIdx = ny * w + nx
                            if (!remaining[nIdx] && !crossBorder[nIdx]) {
                                hasInteriorKnown = true
                                break
                            }
                        }
                    }
                    if (hasInteriorKnown) front[frontLen++] = idx
                }
            }
            if (frontLen == 0) {
                for (y in 0 until h) {
                    val rowOff = y * w
                    for (x in 0 until w) {
                        val idx = rowOff + x
                        if (!remaining[idx]) continue
                        var hasKnown = false
                        for (n in 0 until 8) {
                            val nx = x + dx8[n]
                            val ny = y + dy8[n]
                            if (nx in 0 until w && ny in 0 until h && !remaining[ny * w + nx]) {
                                hasKnown = true
                                break
                            }
                        }
                        if (hasKnown) front[frontLen++] = idx
                    }
                }
            }
            if (frontLen == 0) {
                // Fallback if every pixel in the coarsest grid was masked
                var kSumR = 0f
                var kSumG = 0f
                var kSumB = 0f
                var kCount = 0
                for (i in 0 until total) {
                    if (!level.mask[i] && !crossBorder[i]) {
                        kSumR += level.r[i]
                        kSumG += level.g[i]
                        kSumB += level.b[i]
                        kCount++
                    }
                }
                if (kCount == 0) {
                    for (i in 0 until total) {
                        if (!level.mask[i]) {
                            kSumR += level.r[i]
                            kSumG += level.g[i]
                            kSumB += level.b[i]
                            kCount++
                        }
                    }
                }
                val fallbackR = if (kCount > 0) kSumR / kCount else 128f
                val fallbackG = if (kCount > 0) kSumG / kCount else 128f
                val fallbackB = if (kCount > 0) kSumB / kCount else 128f
                for (i in 0 until total) {
                    if (remaining[i]) {
                        level.r[i] = fallbackR
                        level.g[i] = fallbackG
                        level.b[i] = fallbackB
                        remaining[i] = false
                    }
                }
                break
            }

            for (f in 0 until frontLen) {
                val idx = front[f]
                val x = idx % w
                val y = idx / w

                // Estimate boundary gradient direction for structure-aligned weights
                val gx = level.gx[idx]
                val gy = level.gy[idx]
                val isoX = -gy
                val isoY = gx
                val isoNorm = sqrt(isoX * isoX + isoY * isoY)

                var hasInteriorNeighbor = false
                for (n in 0 until 8) {
                    val nx = x + dx8[n]
                    val ny = y + dy8[n]
                    if (nx in 0 until w && ny in 0 until h) {
                        val nIdx = ny * w + nx
                        if (!remaining[nIdx] && !crossBorder[nIdx]) {
                            hasInteriorNeighbor = true
                            break
                        }
                    }
                }

                var wSum = 0f
                var rSum = 0f
                var gSum = 0f
                var bSum = 0f
                for (n in 0 until 8) {
                    val nx = x + dx8[n]
                    val ny = y + dy8[n]
                    if (nx in 0 until w && ny in 0 until h) {
                        val nIdx = ny * w + nx
                        if (!remaining[nIdx] && (!hasInteriorNeighbor || !crossBorder[nIdx])) {
                            val baseWt = if (dx8[n] == 0 || dy8[n] == 0) 1.414f else 1f
                            val dirWt = if (isoNorm > 1f) {
                                val dot = abs(dx8[n] * isoX + dy8[n] * isoY) / isoNorm
                                0.5f + 1.5f * dot
                            } else {
                                1f
                            }
                            val nGradSq = level.gx[nIdx] * level.gx[nIdx] + level.gy[nIdx] * level.gy[nIdx]
                            val smoothInteriorWt = 1f / (1f + nGradSq / (22f * 22f))
                            val wt = baseWt * dirWt * smoothInteriorWt
                            rSum += level.r[nIdx] * wt
                            gSum += level.g[nIdx] * wt
                            bSum += level.b[nIdx] * wt
                            wSum += wt
                        }
                    }
                }
                if (wSum > 0f) {
                    level.r[idx] = rSum / wSum
                    level.g[idx] = gSum / wSum
                    level.b[idx] = bSum / wSum
                }
            }
            for (f in 0 until frontLen) remaining[front[f]] = false
            recomputeGradients(level)
            remainingCount -= frontLen
        }

        // Mild harmonic smoothing so lighting transitions softly before PatchMatch locks texture
        // (never pulling across high-contrast crossBorder pixels)
        val nextR = level.r.copyOf()
        val nextG = level.g.copyOf()
        val nextB = level.b.copyOf()
        repeat(10) {
            for (y in 1 until h - 1) {
                val rowOff = y * w
                for (x in 1 until w - 1) {
                    val idx = rowOff + x
                    if (!level.mask[idx]) continue
                    var sumR = 0f
                    var sumG = 0f
                    var sumB = 0f
                    var cnt = 0
                    val neighbors = intArrayOf(idx - 1, idx + 1, idx - w, idx + w)
                    for (nIdx in neighbors) {
                        if (level.mask[nIdx] || !crossBorder[nIdx]) {
                            sumR += level.r[nIdx]
                            sumG += level.g[nIdx]
                            sumB += level.b[nIdx]
                            cnt++
                        }
                    }
                    if (cnt > 0) {
                        val inv = 1f / cnt
                        nextR[idx] = sumR * inv
                        nextG[idx] = sumG * inv
                        nextB[idx] = sumB * inv
                    }
                }
            }
            for (i in 0 until total) {
                if (level.mask[i]) {
                    level.r[i] = nextR[i]
                    level.g[i] = nextG[i]
                    level.b[i] = nextB[i]
                }
            }
        }
    }

    private fun upsampleColorsToMasked(coarser: PyramidLevel, finer: PyramidLevel) {
        val cw = coarser.width
        val ch = coarser.height
        val fw = finer.width
        val fh = finer.height
        for (y in 0 until fh) {
            val cyF = (y * ch.toFloat() / fh.toFloat()).coerceIn(0f, (ch - 1).toFloat())
            val y0 = cyF.toInt()
            val y1 = min(ch - 1, y0 + 1)
            val ty = cyF - y0
            val rowOff = y * fw
            for (x in 0 until fw) {
                val idx = rowOff + x
                if (!finer.mask[idx]) continue
                val cxF = (x * cw.toFloat() / fw.toFloat()).coerceIn(0f, (cw - 1).toFloat())
                val x0 = cxF.toInt()
                val x1 = min(cw - 1, x0 + 1)
                val tx = cxF - x0

                val i00 = y0 * cw + x0
                val i10 = y0 * cw + x1
                val i01 = y1 * cw + x0
                val i11 = y1 * cw + x1
                val w00 = (1f - tx) * (1f - ty)
                val w10 = tx * (1f - ty)
                val w01 = (1f - tx) * ty
                val w11 = tx * ty

                finer.r[idx] = coarser.r[i00] * w00 + coarser.r[i10] * w10 + coarser.r[i01] * w01 + coarser.r[i11] * w11
                finer.g[idx] = coarser.g[i00] * w00 + coarser.g[i10] * w10 + coarser.g[i01] * w01 + coarser.g[i11] * w11
                finer.b[idx] = coarser.b[i00] * w00 + coarser.b[i10] * w10 + coarser.b[i01] * w01 + coarser.b[i11] * w11
            }
        }
    }

    private fun recomputeGradients(level: PyramidLevel) {
        val w = level.width
        val h = level.height
        for (y in 1 until h - 1) {
            val rowOff = y * w
            for (x in 1 until w - 1) {
                val idx = rowOff + x
                val lumaL = 0.299f * level.r[idx - 1] + 0.587f * level.g[idx - 1] + 0.114f * level.b[idx - 1]
                val lumaR = 0.299f * level.r[idx + 1] + 0.587f * level.g[idx + 1] + 0.114f * level.b[idx + 1]
                val lumaU = 0.299f * level.r[idx - w] + 0.587f * level.g[idx - w] + 0.114f * level.b[idx - w]
                val lumaD = 0.299f * level.r[idx + w] + 0.587f * level.g[idx + w] + 0.114f * level.b[idx + w]
                level.gx[idx] = 0.5f * (lumaR - lumaL)
                level.gy[idx] = 0.5f * (lumaD - lumaU)
            }
        }
    }

    private fun edgeAwarePatchSsd(
        level: PyramidLevel,
        tx: Int,
        ty: Int,
        sx: Int,
        sy: Int,
        patchR: Int,
        crossBorder: BooleanArray? = null,
        earlyOutLimit: Float = Float.POSITIVE_INFINITY
    ): Float {
        val w = level.width
        var ssd = 0f
        for (dy in -patchR..patchR) {
            val tRow = (ty + dy) * w
            val sRow = (sy + dy) * w
            for (dx in -patchR..patchR) {
                val tIdx = tRow + tx + dx
                val sIdx = sRow + sx + dx
                if (crossBorder != null) {
                    // Never anchor an interior hole patch to known pixels across a high-contrast border
                    if (!level.mask[tIdx] && crossBorder[tIdx]) continue
                    if (crossBorder[sIdx]) {
                        ssd += 1e4f
                        if (ssd >= earlyOutLimit) return ssd
                        continue
                    }
                }
                val dr = level.r[tIdx] - level.r[sIdx]
                val dg = level.g[tIdx] - level.g[sIdx]
                val db = level.b[tIdx] - level.b[sIdx]
                val dgx = level.gx[tIdx] - level.gx[sIdx]
                val dgy = level.gy[tIdx] - level.gy[sIdx]
                val anchorWt = if (!level.mask[tIdx]) 2.2f else 1.0f
                ssd += (dr * dr + dg * dg + db * db + 1.5f * (dgx * dgx + dgy * dgy)) * anchorWt
            }
            if (ssd >= earlyOutLimit) return ssd
        }
        return ssd
    }

    private fun buildCrossBorderPyramid(
        baseCrossBorder: BooleanArray,
        pyramid: List<PyramidLevel>
    ): List<BooleanArray> {
        val list = mutableListOf(baseCrossBorder)
        var prev = baseCrossBorder
        for (i in 1 until pyramid.size) {
            val prevLvl = pyramid[i - 1]
            val curLvl = pyramid[i]
            val next = BooleanArray(curLvl.width * curLvl.height)
            for (y in 0 until curLvl.height) {
                val fy0 = min(prevLvl.height - 1, y * 2)
                val fy1 = min(prevLvl.height - 1, fy0 + 1)
                for (x in 0 until curLvl.width) {
                    val fx0 = min(prevLvl.width - 1, x * 2)
                    val fx1 = min(prevLvl.width - 1, fx0 + 1)
                    val coords = intArrayOf(
                        fy0 * prevLvl.width + fx0,
                        fy0 * prevLvl.width + fx1,
                        fy1 * prevLvl.width + fx0,
                        fy1 * prevLvl.width + fx1
                    )
                    var knownCount = 0
                    var crossCount = 0
                    for (ci in coords) {
                        if (!prevLvl.mask[ci]) {
                            knownCount++
                            if (prev[ci]) crossCount++
                        }
                    }
                    val di = y * curLvl.width + x
                    next[di] = !curLvl.mask[di] && knownCount > 0 && crossCount * 2 >= knownCount
                }
            }
            list += next
            prev = next
        }
        return list
    }

    private fun computeInteriorBorderMask(
        level: PyramidLevel,
        featherMaskHint: IntArray? = null
    ): BooleanArray {
        val w = level.width
        val h = level.height
        val total = w * h
        val crossBorder = BooleanArray(total)
        if (w < 6 || h < 6) return crossBorder

        val gMag = FloatArray(total)
        for (i in 0 until total) {
            gMag[i] = kotlin.math.hypot(level.gx[i], level.gy[i])
        }

        // 1. Collect known boundary ring pixels within 3px of the masked hole, plus low-gradient
        //    outer-feather pixels (dist around brush radius) when available so corners don't flip to exterior background.
        val ringIndices = IntArray(total)
        val ringWeights = FloatArray(total)
        var ringCount = 0
        var maskMinX = w
        var maskMaxX = -1
        var maskMinY = h
        var maskMaxY = -1
        for (y in 0 until h) {
            val rowOff = y * w
            for (x in 0 until w) {
                val idx = rowOff + x
                if (level.mask[idx]) {
                    if (x < maskMinX) maskMinX = x
                    if (x > maskMaxX) maskMaxX = x
                    if (y < maskMinY) maskMinY = y
                    if (y > maskMaxY) maskMaxY = y
                    continue
                }
                var nearHole = false
                val y0 = max(0, y - 3)
                val y1 = min(h - 1, y + 3)
                val x0 = max(0, x - 3)
                val x1 = min(w - 1, x + 3)
                loop@ for (ny in y0..y1) {
                    val nRow = ny * w
                    for (nx in x0..x1) {
                        if (level.mask[nRow + nx]) {
                            nearHole = true
                            break@loop
                        }
                    }
                }
                if (nearHole) {
                    val gm = gMag[idx]
                    val wt = 1f / (1f + (gm / 18f) * (gm / 18f))
                    ringIndices[ringCount] = idx
                    ringWeights[ringCount] = wt
                    ringCount++
                }
            }
        }
        if (ringCount < 4) return crossBorder

        // Also incorporate outer-feather rim samples (1..254) that sit inside the brush radius band
        // closer to the interior surface than the exterior border
        val pureRingCount = ringCount
        if (featherMaskHint != null && featherMaskHint.size >= total && maskMaxX >= maskMinX) {
            val cx = 0.5f * (maskMinX + maskMaxX)
            val cy = 0.5f * (maskMinY + maskMaxY)
            val halfSpan = max(4f, 0.5f * max(maskMaxX - maskMinX, maskMaxY - maskMinY))
            for (y in 0 until h) {
                val rowOff = y * w
                for (x in 0 until w) {
                    val idx = rowOff + x
                    val fm = featherMaskHint[idx]
                    if (fm in 1..254 && gMag[idx] < 24f) {
                        val dx = x - cx
                        val dy = y - cy
                        val normDist = sqrt(dx * dx + dy * dy) / halfSpan
                        val wt = (1.6f / (1f + normDist * normDist)) / (1f + (gMag[idx] / 16f) * (gMag[idx] / 16f))
                        ringIndices[ringCount] = idx
                        ringWeights[ringCount] = wt
                        ringCount++
                    }
                }
            }
        }

        // 2. Dominant interior surface color (mode weighted toward smooth low-gradient samples)
        val sampleStep = max(1, ringCount / 80)
        var bestIdx = ringIndices[0]
        var bestScore = -1f
        val sigmaColorSq = 2f * 36f * 36f
        var s = 0
        while (s < ringCount) {
            val sIdx = ringIndices[s]
            val sr = level.r[sIdx]
            val sg = level.g[sIdx]
            val sb = level.b[sIdx]
            var score = 0f
            var j = 0
            while (j < ringCount) {
                val jIdx = ringIndices[j]
                val dr = sr - level.r[jIdx]
                val dg = sg - level.g[jIdx]
                val db = sb - level.b[jIdx]
                val distSq = dr * dr + dg * dg + db * db
                score += ringWeights[j] * exp(-distSq / sigmaColorSq)
                j += sampleStep
            }
            score *= (0.35f + 0.65f * ringWeights[s].coerceAtMost(1f))
            if (score > bestScore) {
                bestScore = score
                bestIdx = sIdx
            }
            s += sampleStep
        }

        var domR = 0f
        var domG = 0f
        var domB = 0f
        var domW = 0f
        val seedR = level.r[bestIdx]
        val seedG = level.g[bestIdx]
        val seedB = level.b[bestIdx]
        for (k in 0 until ringCount) {
            val kIdx = ringIndices[k]
            val dr = level.r[kIdx] - seedR
            val dg = level.g[kIdx] - seedG
            val db = level.b[kIdx] - seedB
            if (dr * dr + dg * dg + db * db <= 45f * 45f) {
                val wgt = ringWeights[k]
                domR += level.r[kIdx] * wgt
                domG += level.g[kIdx] * wgt
                domB += level.b[kIdx] * wgt
                domW += wgt
            }
        }
        if (domW <= 1e-4f) return crossBorder
        domR /= domW
        domG /= domW
        domB /= domW

        // 3. Verify a high-contrast border line exists near the hole (sharp gradient jump + contrasting color)
        var maxRingGrad = 0f
        var hasContrastingSide = false
        val contrastDistSqThresh = 58f * 58f
        for (k in 0 until pureRingCount) {
            val kIdx = ringIndices[k]
            if (gMag[kIdx] > maxRingGrad) maxRingGrad = gMag[kIdx]
            val dr = level.r[kIdx] - domR
            val dg = level.g[kIdx] - domG
            val db = level.b[kIdx] - domB
            if (dr * dr + dg * dg + db * db > contrastDistSqThresh) {
                hasContrastingSide = true
            }
        }
        if (!hasContrastingSide || maxRingGrad < 28f) return crossBorder

        // 4. Mark contiguous contrasting pixels across high-contrast border lines (>= 50% in 5x5)
        //    so thin 1px repeating texture/mortar lines and intersections flanked by interior pixels
        //    are preserved (a 1px '+' intersection has only 9/25 = 36% in 5x5, vs >= 60% for real borders)
        val rawContrast = BooleanArray(total)
        for (i in 0 until total) {
            if (level.mask[i]) continue
            val dr = level.r[i] - domR
            val dg = level.g[i] - domG
            val db = level.b[i] - domB
            val dSq = dr * dr + dg * dg + db * db
            if (dSq > contrastDistSqThresh || (gMag[i] >= 35f && dSq > 32f * 32f)) {
                rawContrast[i] = true
            }
        }

        for (y in 0 until h) {
            val rowOff = y * w
            for (x in 0 until w) {
                val idx = rowOff + x
                if (!rawContrast[idx]) continue
                var totalKnown = 0
                var contrastCount = 0
                for (dy in -2..2) {
                    val ny = y + dy
                    if (ny !in 0 until h) continue
                    val nRow = ny * w
                    for (dx in -2..2) {
                        val nx = x + dx
                        if (nx !in 0 until w) continue
                        val nIdx = nRow + nx
                        if (!level.mask[nIdx]) {
                            totalKnown++
                            if (rawContrast[nIdx]) contrastCount++
                        }
                    }
                }
                if (totalKnown > 0 && contrastCount * 2 >= totalKnown) {
                    crossBorder[idx] = true
                }
            }
        }
        return crossBorder
    }

    @VisibleForTesting
    internal fun computeContextCropBounds(
        minX: Int,
        minY: Int,
        maxX: Int,
        maxY: Int,
        srcW: Int,
        srcH: Int,
        minPadPx: Int,
        minSquareSide: Int = 0
    ): CropBounds {
        val clampedMinX = minX.coerceIn(0, srcW - 1)
        val clampedMaxX = maxX.coerceIn(clampedMinX, srcW - 1)
        val clampedMinY = minY.coerceIn(0, srcH - 1)
        val clampedMaxY = maxY.coerceIn(clampedMinY, srcH - 1)
        val holeW = clampedMaxX - clampedMinX + 1
        val holeH = clampedMaxY - clampedMinY + 1
        val holeSpan = max(holeW, holeH)
        val pad = max(0, minPadPx)
        val targetSquareSide = max(minSquareSide, holeSpan + pad * 2)
        val halfSide = targetSquareSide / 2
        val centerX = clampedMinX + holeW / 2
        val centerY = clampedMinY + holeH / 2

        val maxStartX = max(0, srcW - min(srcW, targetSquareSide))
        val maxStartY = max(0, srcH - min(srcH, targetSquareSide))
        var x0 = (centerX - halfSide).coerceIn(0, maxStartX)
        var y0 = (centerY - halfSide).coerceIn(0, maxStartY)
        var x1 = (x0 + targetSquareSide - 1).coerceIn(x0, srcW - 1)
        var y1 = (y0 + targetSquareSide - 1).coerceIn(y0, srcH - 1)

        // Guarantee at least `pad` context pixels on every side when available within image bounds
        x0 = min(x0, max(0, clampedMinX - pad))
        y0 = min(y0, max(0, clampedMinY - pad))
        x1 = max(x1, min(srcW - 1, clampedMaxX + pad))
        y1 = max(y1, min(srcH - 1, clampedMaxY + pad))

        return CropBounds(
            x0 = x0,
            y0 = y0,
            x1 = x1,
            y1 = y1,
            width = x1 - x0 + 1,
            height = y1 - y0 + 1
        )
    }

    @VisibleForTesting
    internal fun dilateBinaryMask(
        mask: BooleanArray,
        width: Int,
        height: Int,
        radiusPx: Int = ONNX_MASK_DILATE_PX
    ): BooleanArray {
        if (width <= 0 || height <= 0 || radiusPx <= 0 || mask.size < width * height) {
            return mask.copyOf()
        }
        val rSq = radiusPx * radiusPx
        val out = mask.copyOf()
        for (y in 0 until height) {
            val rowOff = y * width
            for (x in 0 until width) {
                if (!mask[rowOff + x]) continue
                val y0 = max(0, y - radiusPx)
                val y1 = min(height - 1, y + radiusPx)
                val x0 = max(0, x - radiusPx)
                val x1 = min(width - 1, x + radiusPx)
                for (ny in y0..y1) {
                    val dy = ny - y
                    val nRow = ny * width
                    for (nx in x0..x1) {
                        val dx = nx - x
                        if (dx * dx + dy * dy <= rSq) {
                            out[nRow + nx] = true
                        }
                    }
                }
            }
        }
        return out
    }

    @VisibleForTesting
    internal fun dilateAlphaMaskForOnnx(
        alphaMask: IntArray,
        width: Int,
        height: Int,
        dilatePx: Int = ONNX_MASK_DILATE_PX,
        edgeFeatherPx: Float = ONNX_BOUNDARY_FEATHER_PX
    ): IntArray {
        val total = width * height
        if (width <= 0 || height <= 0 || alphaMask.size < total) return alphaMask.copyOf()
        val dilatedCore = dilateBinaryMask(
            mask = BooleanArray(total) { i -> alphaMask[i] > 0 },
            width = width,
            height = height,
            radiusPx = dilatePx
        )
        val out = IntArray(total)
        val featherR = ceil(edgeFeatherPx).toInt().coerceAtLeast(1)
        val maxDist = dilatePx + edgeFeatherPx
        for (y in 0 until height) {
            val rowOff = y * width
            for (x in 0 until width) {
                val idx = rowOff + x
                if (dilatedCore[idx]) {
                    out[idx] = 255
                } else {
                    // Subtle outer anti-aliased edge around the 4px dilated removal core
                    var minDistSq = Float.POSITIVE_INFINITY
                    val y0 = max(0, y - featherR)
                    val y1 = min(height - 1, y + featherR)
                    val x0 = max(0, x - featherR)
                    val x1 = min(width - 1, x + featherR)
                    for (ny in y0..y1) {
                        val nRow = ny * width
                        for (nx in x0..x1) {
                            if (dilatedCore[nRow + nx]) {
                                val dx = (nx - x).toFloat()
                                val dy = (ny - y).toFloat()
                                val dSq = dx * dx + dy * dy
                                if (dSq < minDistSq) minDistSq = dSq
                            }
                        }
                    }
                    if (minDistSq <= edgeFeatherPx * edgeFeatherPx && maxDist > 0f) {
                        val dist = sqrt(minDistSq)
                        val t = ((edgeFeatherPx - dist) / edgeFeatherPx).coerceIn(0f, 1f)
                        val cov = t * t * (3f - 2f * t)
                        out[idx] = (cov * 255f).roundToInt().coerceIn(0, 255)
                    }
                }
            }
        }
        return out
    }

    private fun buildGaussianKernel(patchR: Int): FloatArray {
        val size = patchR * 2 + 1
        val sigma = max(1f, patchR * 0.7f)
        val denom = 2f * sigma * sigma
        return FloatArray(size * size) { i ->
            val dx = (i % size) - patchR
            val dy = (i / size) - patchR
            exp(-(dx * dx + dy * dy).toFloat() / denom)
        }
    }

    /**
     * Platform-independent anti-aliased capsule rasterizer producing a crisp mask with an
     * exact smooth alpha feather around the brush boundary (so surrounding pixels keep
     * strict alpha = 0 and stay 100% untouched).
     */
    fun rasterizeAlphaMask(
        width: Int,
        height: Int,
        strokes: List<HealStroke>,
        defaultFeatherPx: Float = BOUNDARY_FEATHER_PX,
        keepFullCore: Boolean = false
    ): IntArray {
        val alpha = IntArray(width * height)
        if (width <= 0 || height <= 0 || strokes.isEmpty()) return alpha

        val minSide = minOf(width, height).toFloat()

        for (stroke in strokes) {
            if (stroke.points.isEmpty()) continue
            val radiusPx = (stroke.radiusNorm * minSide).coerceAtLeast(3f)
            val featherFrac = stroke.feather.coerceIn(0f, 1f)
            val innerHalf = min(1.5f, radiusPx * 0.45f)
            val outerHalf = (defaultFeatherPx - innerHalf).coerceAtLeast(0.5f)
            val outerR = radiusPx + outerHalf
            val innerR = if (keepFullCore || featherFrac <= 0.02f) {
                max(0f, radiusPx - innerHalf)
            } else {
                max(0f, (radiusPx - innerHalf) * (1f - featherFrac * 0.85f))
            }
            val outerRSq = outerR * outerR
            val featherSpan = (outerR - innerR).coerceAtLeast(1f)

            var sMinX = Float.POSITIVE_INFINITY
            var sMaxX = Float.NEGATIVE_INFINITY
            var sMinY = Float.POSITIVE_INFINITY
            var sMaxY = Float.NEGATIVE_INFINITY
            for ((nx, ny) in stroke.points) {
                val px = nx * width
                val py = ny * height
                if (px < sMinX) sMinX = px
                if (px > sMaxX) sMaxX = px
                if (py < sMinY) sMinY = py
                if (py > sMaxY) sMaxY = py
            }

            val bx0 = max(0, floor(sMinX - outerR - 1f).toInt())
            val bx1 = min(width - 1, ceil(sMaxX + outerR + 1f).toInt())
            val by0 = max(0, floor(sMinY - outerR - 1f).toInt())
            val by1 = min(height - 1, ceil(sMaxY + outerR + 1f).toInt())
            if (bx0 > bx1 || by0 > by1) continue

            val pts = stroke.points
            for (py in by0..by1) {
                val cy = py + 0.5f
                val rowOff = py * width
                for (px in bx0..bx1) {
                    val cx = px + 0.5f
                    var minDistSq = Float.POSITIVE_INFINITY
                    if (pts.size == 1) {
                        val dx = cx - pts[0].first * width
                        val dy = cy - pts[0].second * height
                        minDistSq = dx * dx + dy * dy
                    } else {
                        for (i in 0 until pts.size - 1) {
                            val x0 = pts[i].first * width
                            val y0 = pts[i].second * height
                            val x1 = pts[i + 1].first * width
                            val y1 = pts[i + 1].second * height
                            val segDx = x1 - x0
                            val segDy = y1 - y0
                            val lenSq = segDx * segDx + segDy * segDy
                            val t = if (lenSq <= 1e-6f) {
                                0f
                            } else {
                                (((cx - x0) * segDx + (cy - y0) * segDy) / lenSq).coerceIn(0f, 1f)
                            }
                            val projX = x0 + t * segDx
                            val projY = y0 + t * segDy
                            val dSq = (cx - projX) * (cx - projX) + (cy - projY) * (cy - projY)
                            if (dSq < minDistSq) minDistSq = dSq
                        }
                    }

                    if (minDistSq < outerRSq) {
                        val dist = sqrt(minDistSq)
                        val linearCov = ((outerR - dist) / featherSpan).coerceIn(0f, 1f)
                        val coverage = linearCov * linearCov * (3f - 2f * linearCov)
                        val idx = rowOff + px
                        val covInt = (coverage * 255f).roundToInt().coerceIn(0, 255)
                        if (covInt > alpha[idx]) {
                            alpha[idx] = covInt
                        }
                    }
                }
            }
        }
        return alpha
    }
}
