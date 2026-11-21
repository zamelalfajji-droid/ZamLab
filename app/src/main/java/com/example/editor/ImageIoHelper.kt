package com.example.editor

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.compose.runtime.Immutable
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

@Immutable
sealed interface PhotoSourceRef {
    data class Resource(val resId: Int) : PhotoSourceRef
    data class ContentUri(val uri: Uri) : PhotoSourceRef
}

data class ProxyDecodeResult(
    val proxyBitmap: Bitmap,
    val nativeWidth: Int,
    val nativeHeight: Int
)

enum class ExportFormat(
    val label: String,
    val subtitle: String,
    val mimeType: String,
    val extension: String,
    val supportsQuality: Boolean
) {
    JPG(
        label = "JPG",
        subtitle = "Universal",
        mimeType = "image/jpeg",
        extension = "jpg",
        supportsQuality = true
    ),
    PNG(
        label = "PNG",
        subtitle = "Lossless",
        mimeType = "image/png",
        extension = "png",
        supportsQuality = false
    ),
    WEBP(
        label = "WEBP",
        subtitle = "Modern",
        mimeType = "image/webp",
        extension = "webp",
        supportsQuality = true
    );

    fun resolveCompressFormat(quality: Int): Bitmap.CompressFormat {
        return when (this) {
            JPG -> Bitmap.CompressFormat.JPEG
            PNG -> Bitmap.CompressFormat.PNG
            WEBP -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (quality >= 100) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
        }
    }
}

enum class ExportResolution(
    val label: String,
    val description: String
) {
    FULL(label = "Full (100%)", description = "Original pixels"),
    PX_2048(label = "2048 px", description = "HD share"),
    PX_1080(label = "1080 px", description = "Web / Social"),
    HALF(label = "50% Scale", description = "Half dimensions")
}

@Immutable
data class ExportOptions(
    val format: ExportFormat = ExportFormat.JPG,
    val quality: Int = 95,
    val resolution: ExportResolution = ExportResolution.FULL
) {
    val effectiveQuality: Int
        get() = if (format.supportsQuality) quality.coerceIn(10, 100) else 100
}

object ImageIoHelper {

    /**
     * Longest-edge cap for the single GPU-resident real-time editing proxy texture.
     * Ensures 50MP/200MP camera files decode cleanly via `inSampleSize` and scrub at 120Hz.
     */
    const val PROXY_MAX_DIMENSION = 2048
    const val PREVIEW_IDLE_DIMENSION = PROXY_MAX_DIMENSION
    const val PREVIEW_DRAG_DIMENSION = 640
    const val HISTOGRAM_DIMENSION = 160
    const val THUMBNAIL_DIMENSION = 96

    /**
     * Computes a power-of-2 [BitmapFactory.Options.inSampleSize] that subsamples 12MP/50MP/200MP
     * camera photos directly in the native decoder without allocating full-resolution RAM, while
     * preserving at least 75% of [maxDim] before any exact clamp so previews remain razor-sharp.
     */
    fun calculateInSampleSize(rawWidth: Int, rawHeight: Int, maxDim: Int): Int {
        val longest = max(rawWidth, rawHeight)
        if (longest <= maxDim || maxDim <= 0) return 1
        var sampleSize = 1
        val minAcceptable = max(1, (maxDim * 3) / 4)
        while ((longest / (sampleSize * 2)) >= minAcceptable) {
            sampleSize *= 2
        }
        return sampleSize
    }

    /**
     * Decodes a screen-sized proxy (<= [maxDim] on the longest edge) from a bundled drawable
     * resource using `BitmapFactory.Options.inSampleSize`, while returning the true native
     * pixel dimensions for non-destructive export metadata.
     */
    suspend fun loadProxyFromResource(
        context: Context,
        resId: Int,
        maxDim: Int = PROXY_MAX_DIMENSION
    ): ProxyDecodeResult = withContext(Dispatchers.IO) {
        val boundsOpts = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeResource(context.resources, resId, boundsOpts)
        val rawW = boundsOpts.outWidth.coerceAtLeast(1)
        val rawH = boundsOpts.outHeight.coerceAtLeast(1)

        val sampleSize = calculateInSampleSize(rawW, rawH, maxDim)
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeResource(context.resources, resId, decodeOpts)
            ?: throw IllegalStateException("Failed to load starter photograph.")

        val nativeW = if (boundsOpts.outWidth > 0) boundsOpts.outWidth else decoded.width
        val nativeH = if (boundsOpts.outHeight > 0) boundsOpts.outHeight else decoded.height
        val proxy = clampAndEnsureArgb8888(decoded, maxDim)
        ProxyDecodeResult(
            proxyBitmap = proxy,
            nativeWidth = max(nativeW, proxy.width),
            nativeHeight = max(nativeH, proxy.height)
        )
    }

    /**
     * Decodes a screen-sized proxy (<= [maxDim] on the longest edge) from a content URI using
     * `BitmapFactory.Options.inSampleSize` + EXIF orientation handling, keeping the original
     * full-resolution file untouched on disk until Export.
     */
    suspend fun loadProxyFromUri(
        context: Context,
        uri: Uri,
        maxDim: Int = PROXY_MAX_DIMENSION
    ): ProxyDecodeResult = withContext(Dispatchers.IO) {
        val boundsOpts = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, boundsOpts)
        }

        val rawW = boundsOpts.outWidth
        val rawH = boundsOpts.outHeight
        val orientation = readExifOrientation(context, uri)

        if (rawW > 0 && rawH > 0) {
            val sampleSize = calculateInSampleSize(rawW, rawH, maxDim)
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOpts)
            }
            if (decoded != null) {
                val oriented = applyExifOrientation(decoded, orientation)
                val proxy = clampAndEnsureArgb8888(oriented, maxDim)
                val (nativeW, nativeH) = orientedBounds(rawW, rawH, orientation)
                return@withContext ProxyDecodeResult(
                    proxyBitmap = proxy,
                    nativeWidth = nativeW,
                    nativeHeight = nativeH
                )
            }
        }

        // Fallback to ImageDecoder on API 28+ for formats BitmapFactory may not decode directly (e.g. HEIF/AVIF)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            var nativeW = 0
            var nativeH = 0
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                nativeW = info.size.width
                nativeH = info.size.height
                val maxSide = max(nativeW, nativeH)
                if (maxSide > maxDim) {
                    val scale = maxDim.toFloat() / maxSide.toFloat()
                    decoder.setTargetSize(
                        max(1, (nativeW * scale).toInt()),
                        max(1, (nativeH * scale).toInt())
                    )
                }
            }
            val proxy = ensureArgb8888(decoded)
            return@withContext ProxyDecodeResult(
                proxyBitmap = proxy,
                nativeWidth = nativeW.coerceAtLeast(proxy.width),
                nativeHeight = nativeH.coerceAtLeast(proxy.height)
            )
        }

        throw IllegalStateException("Unable to decode selected image.")
    }

    /**
     * Decodes the untouched original source at 100% native resolution (or safely subsampled if
     * [targetMaxSourceDim] is specified for smaller export sizes) exclusively during Export.
     */
    suspend fun loadNativeForExport(
        context: Context,
        sourceRef: PhotoSourceRef,
        targetMaxSourceDim: Int? = null
    ): Bitmap = withContext(Dispatchers.IO) {
        when (sourceRef) {
            is PhotoSourceRef.Resource -> decodeResourceForExport(context, sourceRef.resId, targetMaxSourceDim)
            is PhotoSourceRef.ContentUri -> decodeUriForExport(context, sourceRef.uri, targetMaxSourceDim)
        }
    }

    private fun decodeResourceForExport(
        context: Context,
        resId: Int,
        targetMaxSourceDim: Int?
    ): Bitmap {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeResource(context.resources, resId, boundsOpts)
        val rawW = boundsOpts.outWidth.coerceAtLeast(1)
        val rawH = boundsOpts.outHeight.coerceAtLeast(1)
        val sampleSize = if (targetMaxSourceDim != null && targetMaxSourceDim > 0) {
            calculateInSampleSize(rawW, rawH, targetMaxSourceDim)
        } else {
            1
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeResource(context.resources, resId, opts)
            ?: throw IllegalStateException("Failed to decode original photo for export.")
        return ensureArgb8888(decoded)
    }

    private fun decodeUriForExport(
        context: Context,
        uri: Uri,
        targetMaxSourceDim: Int?
    ): Bitmap {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, boundsOpts)
        }
        val rawW = boundsOpts.outWidth
        val rawH = boundsOpts.outHeight
        val orientation = readExifOrientation(context, uri)

        var sampleSize = if (rawW > 0 && rawH > 0 && targetMaxSourceDim != null && targetMaxSourceDim > 0) {
            calculateInSampleSize(rawW, rawH, targetMaxSourceDim)
        } else {
            1
        }

        // Decode at 100% native resolution (with automatic OOM step-down fallback on ultra-low-RAM devices)
        var lastError: Throwable? = null
        repeat(3) {
            try {
                if (rawW > 0 && rawH > 0) {
                    val opts = BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    val decoded = context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, opts)
                    }
                    if (decoded != null) {
                        val oriented = applyExifOrientation(decoded, orientation)
                        return ensureArgb8888(oriented)
                    }
                }
            } catch (oom: OutOfMemoryError) {
                lastError = oom
                sampleSize *= 2
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                if (targetMaxSourceDim != null && targetMaxSourceDim > 0) {
                    val maxSide = max(info.size.width, info.size.height)
                    if (maxSide > targetMaxSourceDim) {
                        val scale = targetMaxSourceDim.toFloat() / maxSide.toFloat()
                        decoder.setTargetSize(
                            max(1, (info.size.width * scale).toInt()),
                            max(1, (info.size.height * scale).toInt())
                        )
                    }
                }
            }
            return ensureArgb8888(decoded)
        }

        throw IllegalStateException(
            "Unable to decode full-resolution image for export.",
            lastError
        )
    }

    suspend fun loadBitmapFromResource(context: Context, resId: Int): Bitmap =
        loadProxyFromResource(context, resId, PROXY_MAX_DIMENSION).proxyBitmap

    suspend fun loadBitmapFromUri(context: Context, uri: Uri): Bitmap =
        loadProxyFromUri(context, uri, PROXY_MAX_DIMENSION).proxyBitmap

    private fun readExifOrientation(context: Context, uri: Uri): Int {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
    }

    private fun orientedBounds(rawW: Int, rawH: Int, orientation: Int): Pair<Int, Int> {
        return when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_ROTATE_270,
            ExifInterface.ORIENTATION_TRANSPOSE,
            ExifInterface.ORIENTATION_TRANSVERSE -> rawH to rawW
            else -> rawW to rawH
        }
    }

    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap && !bitmap.isRecycled) {
            bitmap.recycle()
        }
        return rotated
    }

    private fun clampAndEnsureArgb8888(bitmap: Bitmap, maxDim: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        val scaled = if (longest > maxDim && maxDim > 0) {
            val scale = maxDim.toFloat() / longest.toFloat()
            val w = max(1, (bitmap.width * scale).toInt())
            val h = max(1, (bitmap.height * scale).toInt())
            val resized = bitmap.scale(w, h)
            if (resized !== bitmap && !bitmap.isRecycled) {
                bitmap.recycle()
            }
            resized
        } else {
            bitmap
        }
        return ensureArgb8888(scaled)
    }

    private fun ensureArgb8888(bitmap: Bitmap): Bitmap {
        if (bitmap.config == Bitmap.Config.ARGB_8888) return bitmap
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        if (copy !== bitmap && !bitmap.isRecycled) {
            bitmap.recycle()
        }
        return copy
    }

    fun createPreviewBitmap(source: Bitmap, maxDim: Int = PROXY_MAX_DIMENSION): Bitmap {
        val longest = max(source.width, source.height)
        if (longest <= maxDim) return source
        val scale = maxDim.toFloat() / longest.toFloat()
        val w = max(1, (source.width * scale).toInt())
        val h = max(1, (source.height * scale).toInt())
        return source.scale(w, h)
    }

    fun createThumbnailBitmap(source: Bitmap, maxDim: Int = THUMBNAIL_DIMENSION): Bitmap {
        val longest = max(source.width, source.height)
        val scale = maxDim.toFloat() / longest.toFloat()
        val w = max(1, (source.width * scale).toInt())
        val h = max(1, (source.height * scale).toInt())
        return source.scale(w, h)
    }

    fun queryDisplayName(context: Context, uri: Uri, fallback: String): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        val name = cursor.getString(idx)
                        if (!name.isNullOrBlank()) return name
                    }
                }
            }
        return fallback
    }

    fun computeTargetDimensions(
        sourceWidth: Int,
        sourceHeight: Int,
        resolution: ExportResolution
    ): Pair<Int, Int> {
        if (sourceWidth <= 0 || sourceHeight <= 0) return 0 to 0
        return when (resolution) {
            ExportResolution.FULL -> sourceWidth to sourceHeight
            ExportResolution.HALF -> max(1, (sourceWidth * 0.5f).toInt()) to max(1, (sourceHeight * 0.5f).toInt())
            ExportResolution.PX_2048 -> clampLongestEdge(sourceWidth, sourceHeight, 2048)
            ExportResolution.PX_1080 -> clampLongestEdge(sourceWidth, sourceHeight, 1080)
        }
    }

    private fun clampLongestEdge(w: Int, h: Int, maxEdge: Int): Pair<Int, Int> {
        val longest = max(w, h)
        if (longest <= maxEdge) return w to h
        val scale = maxEdge.toFloat() / longest.toFloat()
        return max(1, (w * scale).toInt()) to max(1, (h * scale).toInt())
    }

    fun scaleToExportResolution(source: Bitmap, resolution: ExportResolution): Bitmap {
        val (targetW, targetH) = computeTargetDimensions(source.width, source.height, resolution)
        if (targetW == source.width && targetH == source.height) return source
        return source.scale(targetW, targetH)
    }

    fun generateExportFilename(format: ExportFormat): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "ZamLab_$stamp.${format.extension}"
    }

    suspend fun saveToGalleryMediaStore(
        context: Context,
        bitmap: Bitmap,
        options: ExportOptions
    ): String = withContext(Dispatchers.IO) {
        val filename = generateExportFilename(options.format)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, options.format.mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/ZamLab")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val itemUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Could not create MediaStore entry for export.")

        try {
            resolver.openOutputStream(itemUri)?.use { out ->
                compressBitmap(bitmap, options, out)
            } ?: throw IllegalStateException("Could not open output stream for export.")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(itemUri, values, null, null)
            }
            filename
        } catch (e: Exception) {
            resolver.delete(itemUri, null, null)
            throw e
        }
    }

    suspend fun saveToDocumentUri(
        context: Context,
        bitmap: Bitmap,
        uri: Uri,
        options: ExportOptions
    ): String = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri)?.use { out ->
            compressBitmap(bitmap, options, out)
        } ?: throw IllegalStateException("Could not open selected destination file.")
        queryDisplayName(context, uri, generateExportFilename(options.format))
    }

    private fun compressBitmap(bitmap: Bitmap, options: ExportOptions, out: OutputStream) {
        val quality = options.effectiveQuality
        val compressFormat = options.format.resolveCompressFormat(quality)
        val ok = bitmap.compress(compressFormat, quality, out)
        if (!ok) {
            throw IllegalStateException("Failed to encode image as ${options.format.extension.uppercase(Locale.ROOT)}.")
        }
        out.flush()
    }
}
