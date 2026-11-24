package com.zamalfajji.zamlab.editor

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.compose.runtime.Immutable
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

@Immutable
sealed interface PhotoSourceRef {
    data object ProceduralStudioChart : PhotoSourceRef
    data class Resource(val resId: Int) : PhotoSourceRef
    data class ContentUri(val uri: Uri) : PhotoSourceRef
    data class LocalFile(val file: File) : PhotoSourceRef
}

@Immutable
data class PhotoFormatInfo(
    val badgeLabel: String? = null,
    val isRaw: Boolean = false,
    val isEmbeddedPreviewOnly: Boolean = false,
    val extension: String = ""
)

data class ProxyDecodeResult(
    val proxyBitmap: Bitmap,
    val nativeWidth: Int,
    val nativeHeight: Int
)

@Immutable
data class ExportSaveResult(
    val filename: String,
    val uri: Uri,
    val mimeType: String,
    val format: ExportFormat,
    val destinationLabel: String
) {
    val savedSnackbarMessage: String
        get() = format.savedSnackbarMessage
}

enum class ExportFormat(
    val label: String,
    val subtitle: String,
    val mimeType: String,
    val extension: String,
    val supportsQuality: Boolean,
    val defaultSaveLabel: String,
    val defaultFolderDescription: String,
    val isDownloadsDestination: Boolean
) {
    JPG(
        label = "JPG",
        subtitle = "High Quality",
        mimeType = "image/jpeg",
        extension = "jpg",
        supportsQuality = true,
        defaultSaveLabel = "Export to Gallery",
        defaultFolderDescription = "Pictures/Z-Effect",
        isDownloadsDestination = false
    ),
    PNG(
        label = "PNG",
        subtitle = "Lossless 8-bit",
        mimeType = "image/png",
        extension = "png",
        supportsQuality = false,
        defaultSaveLabel = "Export to Gallery",
        defaultFolderDescription = "Pictures/Z-Effect",
        isDownloadsDestination = false
    ),
    WEBP(
        label = "WebP",
        subtitle = "Compact / Lossless",
        mimeType = "image/webp",
        extension = "webp",
        supportsQuality = true,
        defaultSaveLabel = "Export to Gallery",
        defaultFolderDescription = "Pictures/Z-Effect",
        isDownloadsDestination = false
    ),
    TIFF(
        label = "TIFF (16-Bit)",
        subtitle = "Uncompressed 16-Bit",
        mimeType = "image/tiff",
        extension = "tiff",
        supportsQuality = false,
        defaultSaveLabel = "Export to Downloads",
        defaultFolderDescription = "Downloads/Z-Effect",
        isDownloadsDestination = true
    ),

    PDF(
        label = "PDF",
        subtitle = "Print-Ready",
        mimeType = "application/pdf",
        extension = "pdf",
        supportsQuality = false,
        defaultSaveLabel = "Export to Downloads",
        defaultFolderDescription = "Downloads/Z-Effect",
        isDownloadsDestination = true
    );

    val savedSnackbarMessage: String
        get() = if (isDownloadsDestination) "Saved to Downloads" else "Saved to Gallery"

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
            PDF -> Bitmap.CompressFormat.PNG
            TIFF -> Bitmap.CompressFormat.PNG

        }
    }
}

enum class ExportResolution(
    val label: String,
    val description: String
) {
    FULL(label = "Full (Up to 4K)", description = "Original up to 4096px"),
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
     * Longest-edge cap for the single GPU-resident real-time editing proxy texture (120Hz scrubbing).
     */
    const val PROXY_MAX_DIMENSION = 2048

    /**
     * Maximum longest-edge preserved on full-resolution decode & export (safe across mobile GPUs
     * and memory limits while preserving sharp 4K detail for RAW & high-MP camera files).
     */
    const val EXPORT_MAX_DIMENSION = 4096

    const val PREVIEW_IDLE_DIMENSION = PROXY_MAX_DIMENSION
    const val PREVIEW_DRAG_DIMENSION = 640
    const val HISTOGRAM_DIMENSION = 160
    const val THUMBNAIL_DIMENSION = 96

    /**
     * MIME types passed to `ActivityResultContracts.OpenDocument` so users can select standard
     * photos alongside camera RAW (DNG, CR2, NEF, ARW, ORF, RW2, RAF), HEIC/HEIF, TIFF, WebP, BMP.
     */
    val SUPPORTED_IMPORT_MIME_TYPES: Array<String> = arrayOf(
        "image/*",
        "image/x-adobe-dng",
        "image/dng",
        "image/heic",
        "image/heif",
        "image/tiff",
        "image/webp",
        "image/bmp",
        "image/png",
        "image/jpeg",
        "image/x-canon-cr2",
        "image/x-canon-cr3",
        "image/x-nikon-nef",
        "image/x-sony-arw",
        "image/x-olympus-orf",
        "image/x-panasonic-rw2",
        "image/x-fuji-raf",
        "image/x-dcraw"
    )

    private val RAW_EXTENSIONS = setOf(
        "dng", "cr2", "cr3", "nef", "arw", "orf", "rw2", "raf", "pef", "srw"
    )

    private val STANDARD_IMAGE_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "webp", "bmp", "heic", "heif", "tif", "tiff", "avif", "gif"
    )

    /**
     * Checks whether [uri] refers to a supported standard or camera RAW image by MIME or extension.
     */
    fun isSupportedImageUri(context: Context, uri: Uri): Boolean {
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()?.lowercase(Locale.ROOT)
        if (mime != null && mime.startsWith("image/")) return true
        val name = queryDisplayName(context, uri, "")
        val ext = extractExtension(name).ifEmpty { extractExtension(uri.lastPathSegment.orEmpty()) }
        return ext in RAW_EXTENSIONS || ext in STANDARD_IMAGE_EXTENSIONS
    }

    /**
     * Detects whether a loaded photo is DNG, a proprietary camera RAW (where Android extracts the
     * embedded JPEG preview via `piex`), or a modern high-efficiency format (`HEIC`, `TIFF`, `AVIF`).
     */
    fun detectPhotoFormat(context: Context, uri: Uri, displayName: String): PhotoFormatInfo {
        val ext = extractExtension(displayName)
            .ifEmpty { extractExtension(uri.lastPathSegment.orEmpty()) }
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()?.lowercase(Locale.ROOT).orEmpty()

        val rawTag = when {
            ext == "dng" || mime.contains("dng") -> "DNG"
            ext == "cr2" || mime.contains("cr2") -> "CR2"
            ext == "cr3" || mime.contains("cr3") -> "CR3"
            ext == "nef" || mime.contains("nef") -> "NEF"
            ext == "arw" || mime.contains("arw") -> "ARW"
            ext == "orf" || mime.contains("orf") -> "ORF"
            ext == "rw2" || mime.contains("rw2") -> "RW2"
            ext == "raf" || mime.contains("raf") -> "RAF"
            ext == "pef" -> "PEF"
            ext == "srw" -> "SRW"
            mime.contains("raw") -> "RAW"
            else -> null
        }
        if (rawTag != null) {
            val isDng = rawTag == "DNG"
            val badge = if (isDng) "RAW · DNG" else "PREVIEW · $rawTag"
            return PhotoFormatInfo(
                badgeLabel = badge,
                isRaw = true,
                isEmbeddedPreviewOnly = !isDng,
                extension = ext.ifEmpty { rawTag.lowercase(Locale.ROOT) }
            )
        }

        return when {
            ext == "heic" || ext == "heif" || mime.contains("heic") || mime.contains("heif") ->
                PhotoFormatInfo(badgeLabel = "HEIC", isRaw = false, extension = ext.ifEmpty { "heic" })
            ext == "tif" || ext == "tiff" || mime.contains("tiff") ->
                PhotoFormatInfo(badgeLabel = "TIFF", isRaw = false, extension = ext.ifEmpty { "tiff" })
            ext == "avif" || mime.contains("avif") ->
                PhotoFormatInfo(badgeLabel = "AVIF", isRaw = false, extension = "avif")
            else ->
                PhotoFormatInfo(badgeLabel = null, isRaw = false, extension = ext)
        }
    }

    private fun extractExtension(name: String): String {
        val clean = name.substringAfterLast('/').substringAfterLast(':')
        val dot = clean.lastIndexOf('.')
        if (dot <= 0 || dot >= clean.length - 1) return ""
        return clean.substring(dot + 1).lowercase(Locale.ROOT)
    }

    /**
     * Computes a power-of-2 [BitmapFactory.Options.inSampleSize] that subsamples high-MP
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
        val safeMaxDim = maxDim.coerceIn(64, PROXY_MAX_DIMENSION)
        val boundsOpts = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeResource(context.resources, resId, boundsOpts)
        val rawW = boundsOpts.outWidth.coerceAtLeast(1)
        val rawH = boundsOpts.outHeight.coerceAtLeast(1)

        var sampleSize = calculateInSampleSize(rawW, rawH, safeMaxDim)
        var targetDim = safeMaxDim
        var lastError: Throwable? = null
        repeat(3) {
            try {
                val decodeOpts = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val decoded = BitmapFactory.decodeResource(context.resources, resId, decodeOpts)
                    ?: throw IllegalStateException("Failed to load starter photograph.")
                val nativeW = if (boundsOpts.outWidth > 0) boundsOpts.outWidth else decoded.width
                val nativeH = if (boundsOpts.outHeight > 0) boundsOpts.outHeight else decoded.height
                val proxy = clampAndEnsureArgb8888(decoded, targetDim)
                return@withContext ProxyDecodeResult(
                    proxyBitmap = proxy,
                    nativeWidth = max(nativeW, proxy.width),
                    nativeHeight = max(nativeH, proxy.height)
                )
            } catch (oom: OutOfMemoryError) {
                lastError = oom
                sampleSize *= 2
                targetDim = max(512, targetDim / 2)
            }
        }
        throw IllegalStateException("Insufficient memory to decode starter photograph.", lastError)
    }

    /**
     * Decodes a screen-sized proxy (<= [maxDim] on the longest edge) from a content URI.
     * On API 28+, `ImageDecoder` demosaics `.dng` via `libdng_sdk` (into 8-bit ARGB_8888) and
     * extracts embedded JPEG previews for proprietary RAWs (CR2/NEF/ARW/ORF/RW2/RAF) via `piex`,
     * alongside native HEIC/HEIF, TIFF, WebP, and BMP decoding with automatic EXIF orientation.
     */
    suspend fun loadProxyFromUri(
        context: Context,
        uri: Uri,
        maxDim: Int = PROXY_MAX_DIMENSION
    ): ProxyDecodeResult = withContext(Dispatchers.IO) {
        val safeMaxDim = maxDim.coerceIn(64, PROXY_MAX_DIMENSION)
        // 1. Primary path on API 28+: ImageDecoder (DNG demosaic / piex embedded RAW preview / HEIC / TIFF / WebP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            var decoderTarget = safeMaxDim
            repeat(2) { attempt ->
                try {
                    return@withContext decodeWithImageDecoder(
                        source = ImageDecoder.createSource(context.contentResolver, uri),
                        targetMaxDim = decoderTarget
                    )
                } catch (_: OutOfMemoryError) {
                    decoderTarget = max(512, decoderTarget / 2)
                } catch (sec: SecurityException) {
                    throw IllegalStateException("Permission denied reading selected image.", sec)
                } catch (_: Throwable) {
                    if (attempt == 0) return@repeat
                }
            }
        }

        // 2. Fallback path: BitmapFactory with inSampleSize + ExifInterface orientation
        val boundsOpts = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        try {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, boundsOpts)
            }
        } catch (sec: SecurityException) {
            throw IllegalStateException("Permission denied reading selected image.", sec)
        } catch (iae: IllegalArgumentException) {
            throw IllegalStateException("Invalid image URI.", iae)
        }

        val rawW = boundsOpts.outWidth
        val rawH = boundsOpts.outHeight
        val orientation = readExifOrientation(context, uri)

        if (rawW > 0 && rawH > 0) {
            var sampleSize = calculateInSampleSize(rawW, rawH, safeMaxDim)
            var targetDim = safeMaxDim
            var lastOom: OutOfMemoryError? = null
            repeat(3) {
                try {
                    val decodeOpts = BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    val decoded = context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, decodeOpts)
                    }
                    if (decoded != null) {
                        val oriented = applyExifOrientation(decoded, orientation)
                        val proxy = clampAndEnsureArgb8888(oriented, targetDim)
                        val (nativeW, nativeH) = orientedBounds(rawW, rawH, orientation)
                        val (clampedNativeW, clampedNativeH) = clampLongestEdge(nativeW, nativeH, EXPORT_MAX_DIMENSION)
                        return@withContext ProxyDecodeResult(
                            proxyBitmap = proxy,
                            nativeWidth = max(clampedNativeW, proxy.width),
                            nativeHeight = max(clampedNativeH, proxy.height)
                        )
                    }
                } catch (oom: OutOfMemoryError) {
                    lastOom = oom
                    sampleSize *= 2
                    targetDim = max(512, targetDim / 2)
                } catch (sec: SecurityException) {
                    throw IllegalStateException("Permission denied reading selected image.", sec)
                }
            }
            if (lastOom != null) {
                throw IllegalStateException("Insufficient memory to open selected image.", lastOom)
            }
        }

        val ext = extractExtension(queryDisplayName(context, uri, ""))
        if (ext in RAW_EXTENSIONS && ext != "dng") {
            throw IllegalStateException(
                "Cannot decode .${ext.uppercase(Locale.ROOT)} RAW: Android's decoder only supports DNG or proprietary RAWs containing an embedded JPEG preview."
            )
        }
        throw IllegalStateException("Unable to decode selected image format.")
    }

    /**
     * Copies a transient shared/opened image URI into an app-private cache file while the
     * incoming Intent's temporary `FLAG_GRANT_READ_URI_PERMISSION` is active, ensuring full-res
     * export continues to work reliably even after configuration changes or backgrounding.
     */
    suspend fun cacheIncomingPhotoToAppStorage(
        context: Context,
        uri: Uri,
        extensionHint: String = ""
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "shared_imports").apply { mkdirs() }
        val safeExt = extensionHint.filter { it.isLetterOrDigit() }.take(8).ifEmpty { "img" }
        val dest = File(dir, "shared_photo_${System.currentTimeMillis()}_${System.nanoTime()}.$safeExt")
        val input = try {
            context.contentResolver.openInputStream(uri)
        } catch (sec: SecurityException) {
            throw IllegalStateException("Permission denied reading shared image.", sec)
        } catch (iae: IllegalArgumentException) {
            throw IllegalStateException("Invalid shared image URI.", iae)
        } ?: throw IllegalStateException("Cannot read shared image URI.")
        try {
            input.use { src ->
                FileOutputStream(dest).use { out ->
                    src.copyTo(out, bufferSize = 64 * 1024)
                    out.flush()
                }
            }
            if (!dest.exists() || dest.length() <= 0L) {
                dest.delete()
                throw IllegalStateException("Shared image stream was empty.")
            }
            // Prune very old orphaned cache files (> 24h) without touching any currently exporting session file
            val staleCutoffMs = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
            dir.listFiles()?.forEach { existing ->
                if (existing != dest && existing.lastModified() < staleCutoffMs) {
                    runCatching { existing.delete() }
                }
            }
            dest
        } catch (t: Throwable) {
            runCatching { dest.delete() }
            throw t
        }
    }

    /**
     * Decodes a screen-sized proxy (<= [maxDim] on the longest edge) from a local cached file,
     * prioritizing `ImageDecoder` (API 28+) for RAW/HEIC/TIFF with `BitmapFactory` + `ExifInterface` fallback.
     */
    suspend fun loadProxyFromFile(
        file: File,
        maxDim: Int = PROXY_MAX_DIMENSION
    ): ProxyDecodeResult = withContext(Dispatchers.IO) {
        val safeMaxDim = maxDim.coerceIn(64, PROXY_MAX_DIMENSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            var decoderTarget = safeMaxDim
            repeat(2) { attempt ->
                try {
                    return@withContext decodeWithImageDecoder(
                        source = ImageDecoder.createSource(file),
                        targetMaxDim = decoderTarget
                    )
                } catch (_: OutOfMemoryError) {
                    decoderTarget = max(512, decoderTarget / 2)
                } catch (_: Throwable) {
                    if (attempt == 0) return@repeat
                }
            }
        }

        val boundsOpts = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(file.absolutePath, boundsOpts)

        val rawW = boundsOpts.outWidth
        val rawH = boundsOpts.outHeight
        val orientation = readExifOrientationFromFile(file)

        if (rawW > 0 && rawH > 0) {
            var sampleSize = calculateInSampleSize(rawW, rawH, safeMaxDim)
            var targetDim = safeMaxDim
            var lastOom: OutOfMemoryError? = null
            repeat(3) {
                try {
                    val decodeOpts = BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    val decoded = BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
                    if (decoded != null) {
                        val oriented = applyExifOrientation(decoded, orientation)
                        val proxy = clampAndEnsureArgb8888(oriented, targetDim)
                        val (nativeW, nativeH) = orientedBounds(rawW, rawH, orientation)
                        val (clampedNativeW, clampedNativeH) = clampLongestEdge(nativeW, nativeH, EXPORT_MAX_DIMENSION)
                        return@withContext ProxyDecodeResult(
                            proxyBitmap = proxy,
                            nativeWidth = max(clampedNativeW, proxy.width),
                            nativeHeight = max(clampedNativeH, proxy.height)
                        )
                    }
                } catch (oom: OutOfMemoryError) {
                    lastOom = oom
                    sampleSize *= 2
                    targetDim = max(512, targetDim / 2)
                }
            }
            if (lastOom != null) {
                throw IllegalStateException("Insufficient memory to decode shared image.", lastOom)
            }
        }

        throw IllegalStateException("Unable to decode shared image.")
    }

    /**
     * Decodes the untouched original source at full resolution up to [EXPORT_MAX_DIMENSION] (4096px)
     * — or safely subsampled if [targetMaxSourceDim] is smaller — exclusively during Export.
     */
    suspend fun loadNativeForExport(
        context: Context,
        sourceRef: PhotoSourceRef,
        targetMaxSourceDim: Int? = null
    ): Bitmap = withContext(Dispatchers.IO) {
        val effectiveMaxDim = if (targetMaxSourceDim != null && targetMaxSourceDim > 0) {
            minOf(targetMaxSourceDim, EXPORT_MAX_DIMENSION)
        } else {
            EXPORT_MAX_DIMENSION
        }
        when (sourceRef) {
            PhotoSourceRef.ProceduralStudioChart -> {
                val (targetW, targetH) = clampLongestEdge(2048, 1536, effectiveMaxDim)
                createStudioCalibrationBitmap(targetW, targetH)
            }
            is PhotoSourceRef.Resource -> decodeResourceForExport(context, sourceRef.resId, effectiveMaxDim)
            is PhotoSourceRef.ContentUri -> decodeUriForExport(context, sourceRef.uri, effectiveMaxDim)
            is PhotoSourceRef.LocalFile -> decodeFileForExport(sourceRef.file, effectiveMaxDim)
        }
    }

    /**
     * Generates a 100% original, copyright-safe procedural studio calibration & tonal reference chart
     * (color checker swatches, luminance/chroma ramps, skin/sky/foliage tones, and optical micro-contrast bars)
     * so ZamLab never depends on any third-party bundled photograph.
     */
    suspend fun loadProceduralStudioStarterProxy(): ProxyDecodeResult = withContext(Dispatchers.Default) {
        val proxy = createStudioCalibrationBitmap(width = 1280, height = 960)
        ProxyDecodeResult(
            proxyBitmap = proxy,
            nativeWidth = 2048,
            nativeHeight = 1536
        )
    }

    fun createStudioCalibrationBitmap(width: Int, height: Int): Bitmap {
        val w = width.coerceAtLeast(64)
        val h = height.coerceAtLeast(64)
        val bmp = createBitmap(w, h)
        val pixels = IntArray(w * h)
        val wf = w.toFloat()
        val hf = h.toFloat()

        // 1. Calibrated Studio Backdrop with warm key-light glow and cool fill-light corner
        val keyCx = wf * 0.28f
        val keyCy = hf * 0.24f
        val invKeyR = 1f / (wf * 0.62f).coerceAtLeast(1f)
        val fillCx = wf * 0.84f
        val fillCy = hf * 0.78f
        val invFillR = 1f / (wf * 0.55f).coerceAtLeast(1f)

        val chromaStopsR = floatArrayOf(218f, 232f, 220f, 62f, 44f, 74f, 174f, 218f)
        val chromaStopsG = floatArrayOf(62f, 158f, 212f, 178f, 168f, 102f, 76f, 62f)
        val chromaStopsB = floatArrayOf(62f, 42f, 54f, 92f, 198f, 216f, 206f, 62f)

        val cardLeft = wf * 0.10f
        val cardRight = wf * 0.90f
        val cardTop = hf * 0.11f
        val cardBottom = hf * 0.73f
        val innerPadX = wf * 0.032f
        val innerPadY = hf * 0.036f
        val gap = wf * 0.015f
        val cols = 6
        val rows = 4
        val gridLeft = cardLeft + innerPadX
        val gridTop = cardTop + innerPadY
        val gridW = (cardRight - cardLeft) - innerPadX * 2f
        val gridH = (cardBottom - cardTop) - innerPadY * 2f
        val cellW = (gridW - gap * (cols - 1)) / cols
        val cellH = (gridH - gap * (rows - 1)) / rows
        val stepX = cellW + gap
        val stepY = cellH + gap

        val swatches = intArrayOf(
            Color.rgb(115, 82, 68),   Color.rgb(194, 150, 130), Color.rgb(98, 122, 157),
            Color.rgb(87, 108, 67),   Color.rgb(133, 128, 177), Color.rgb(103, 189, 170),
            Color.rgb(214, 126, 44),  Color.rgb(80, 91, 166),   Color.rgb(193, 90, 99),
            Color.rgb(94, 60, 108),   Color.rgb(157, 188, 64),  Color.rgb(224, 163, 46),
            Color.rgb(56, 61, 150),   Color.rgb(70, 148, 73),   Color.rgb(175, 54, 60),
            Color.rgb(231, 199, 31),  Color.rgb(187, 86, 149),  Color.rgb(8, 133, 161),
            Color.rgb(242, 242, 242), Color.rgb(200, 200, 200), Color.rgb(160, 160, 160),
            Color.rgb(122, 122, 122), Color.rgb(74, 74, 74),    Color.rgb(26, 26, 26)
        )

        val lumaTop = hf * 0.77f
        val lumaBottom = hf * 0.835f
        val chromaTop = hf * 0.855f
        val chromaBottom = hf * 0.905f
        val barWidth = (cardRight - cardLeft).coerceAtLeast(1f)
        val borderThick = (wf * 0.0022f).coerceAtLeast(1.5f)
        val headerY0 = hf * 0.042f
        val headerY1 = hf * 0.082f
        val barEnd = cardLeft + wf * 0.20f
        val stripePeriod = (wf / 160f).coerceAtLeast(3f)
        val targetCx = cardRight - wf * 0.045f
        val targetCy = hf * 0.062f
        val targetMaxR = hf * 0.028f

        for (y in 0 until h) {
            val yf = y.toFloat()
            val ny = yf / hf
            val rowOff = y * w
            val inCardY = yf in cardTop..cardBottom
            val inLumaBarY = yf in lumaTop..lumaBottom
            val inChromaBarY = yf in chromaTop..chromaBottom
            val inHeaderY = yf in headerY0..headerY1

            var swatchRow = -1
            if (inCardY && yf >= gridTop) {
                val relY = yf - gridTop
                val rIdx = (relY / stepY).toInt()
                if (rIdx in 0 until rows && (relY - rIdx * stepY) <= cellH) {
                    swatchRow = rIdx
                }
            }

            for (x in 0 until w) {
                val xf = x.toFloat()
                val nx = xf / wf

                // Base studio gradient
                val diag = (nx * 0.55f + ny * 0.45f).coerceIn(0f, 1f)
                var r = 24f + diag * 40f
                var g = 34f + diag * 16f
                var b = 52f - diag * 12f

                // Warm amber key light radial glow
                val dxK = xf - keyCx
                val dyK = yf - keyCy
                val dk = (kotlin.math.sqrt(dxK * dxK + dyK * dyK) * invKeyR).coerceIn(0f, 1f)
                val wKey = (1f - dk) * (1f - dk) * 0.58f
                r += (255f - r) * wKey
                g += (220f - g) * wKey
                b += (178f - b) * wKey

                // Cool fill light radial glow
                val dxF = xf - fillCx
                val dyF = yf - fillCy
                val df = (kotlin.math.sqrt(dxF * dxF + dyF * dyF) * invFillR).coerceIn(0f, 1f)
                val wFill = (1f - df) * (1f - df) * 0.44f
                r += (76f - r) * wFill
                g += (152f - g) * wFill
                b += (224f - b) * wFill

                // Darkroom calibration card + 24 ColorChecker swatches
                if (inCardY && xf in cardLeft..cardRight) {
                    val onBorder = (xf - cardLeft) < borderThick ||
                        (cardRight - xf) < borderThick ||
                        (yf - cardTop) < borderThick ||
                        (cardBottom - yf) < borderThick
                    if (onBorder) {
                        r = 74f; g = 80f; b = 92f
                    } else {
                        r = 22f; g = 24f; b = 29f
                        if (swatchRow >= 0 && xf >= gridLeft) {
                            val relX = xf - gridLeft
                            val cIdx = (relX / stepX).toInt()
                            if (cIdx in 0 until cols && (relX - cIdx * stepX) <= cellW) {
                                val argb = swatches[swatchRow * cols + cIdx]
                                r = Color.red(argb).toFloat()
                                g = Color.green(argb).toFloat()
                                b = Color.blue(argb).toFloat()
                            }
                        }
                    }
                } else if (inLumaBarY && xf in cardLeft..cardRight) {
                    val t = ((xf - cardLeft) / barWidth).coerceIn(0f, 1f)
                    val v = 12f + t * 236f
                    r = v; g = v; b = v + 2f
                } else if (inChromaBarY && xf in cardLeft..cardRight) {
                    val t = ((xf - cardLeft) / barWidth).coerceIn(0f, 1f) * 7f
                    val idx0 = t.toInt().coerceIn(0, 6)
                    val f = t - idx0
                    r = chromaStopsR[idx0] + (chromaStopsR[idx0 + 1] - chromaStopsR[idx0]) * f
                    g = chromaStopsG[idx0] + (chromaStopsG[idx0 + 1] - chromaStopsG[idx0]) * f
                    b = chromaStopsB[idx0] + (chromaStopsB[idx0 + 1] - chromaStopsB[idx0]) * f
                }

                // Optical resolution bars + concentric aperture target
                if (inHeaderY && xf in cardLeft..barEnd) {
                    val phase = ((xf - cardLeft) % stripePeriod) / stripePeriod
                    if (phase < 0.48f) {
                        r += (224f - r) * 0.72f
                        g += (228f - g) * 0.72f
                        b += (236f - b) * 0.72f
                    }
                }
                val dxT = xf - targetCx
                val dyT = yf - targetCy
                val distT = kotlin.math.sqrt(dxT * dxT + dyT * dyT)
                if (distT <= targetMaxR * 1.25f) {
                    val onRing = (1..4).any { ring ->
                        kotlin.math.abs(distT - targetMaxR * (ring / 4f)) < 1.2f
                    }
                    val onCross = kotlin.math.abs(dxT) < 1.0f || kotlin.math.abs(dyT) < 1.0f
                    if (onRing || onCross) {
                        r += (224f - r) * 0.72f
                        g += (228f - g) * 0.72f
                        b += (236f - b) * 0.72f
                    }
                }

                val ri = r.roundToInt().coerceIn(0, 255)
                val gi = g.roundToInt().coerceIn(0, 255)
                val bi = b.roundToInt().coerceIn(0, 255)
                pixels[rowOff + x] = (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
            }
        }

        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return bmp
    }

    private fun decodeWithImageDecoder(
        source: ImageDecoder.Source,
        targetMaxDim: Int
    ): ProxyDecodeResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            throw UnsupportedOperationException("ImageDecoder requires API 28+")
        }
        var nativeW = 0
        var nativeH = 0
        val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            nativeW = info.size.width
            nativeH = info.size.height
            val maxSide = max(nativeW, nativeH)
            if (targetMaxDim > 0 && maxSide > targetMaxDim) {
                val scale = targetMaxDim.toFloat() / maxSide.toFloat()
                decoder.setTargetSize(
                    max(1, (nativeW * scale).roundToInt()),
                    max(1, (nativeH * scale).roundToInt())
                )
            }
        }
        val proxy = clampAndEnsureArgb8888(decoded, targetMaxDim)
        val (clampedNativeW, clampedNativeH) = clampLongestEdge(
            nativeW.coerceAtLeast(proxy.width),
            nativeH.coerceAtLeast(proxy.height),
            EXPORT_MAX_DIMENSION
        )
        return ProxyDecodeResult(
            proxyBitmap = proxy,
            nativeWidth = max(clampedNativeW, proxy.width),
            nativeHeight = max(clampedNativeH, proxy.height)
        )
    }

    private fun decodeResourceForExport(
        context: Context,
        resId: Int,
        maxDim: Int
    ): Bitmap {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeResource(context.resources, resId, boundsOpts)
        val rawW = boundsOpts.outWidth.coerceAtLeast(1)
        val rawH = boundsOpts.outHeight.coerceAtLeast(1)
        val sampleSize = calculateInSampleSize(rawW, rawH, maxDim)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeResource(context.resources, resId, opts)
            ?: throw IllegalStateException("Failed to decode original photo for export.")
        return clampAndEnsureArgb8888(decoded, maxDim)
    }

    private fun decodeUriForExport(
        context: Context,
        uri: Uri,
        maxDim: Int
    ): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val decoded = runCatching {
                decodeWithImageDecoder(
                    source = ImageDecoder.createSource(context.contentResolver, uri),
                    targetMaxDim = maxDim
                ).proxyBitmap
            }.getOrNull()
            if (decoded != null) {
                return decoded
            }
        }

        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, boundsOpts)
        }
        val rawW = boundsOpts.outWidth
        val rawH = boundsOpts.outHeight
        val orientation = readExifOrientation(context, uri)

        var sampleSize = if (rawW > 0 && rawH > 0) {
            calculateInSampleSize(rawW, rawH, maxDim)
        } else {
            1
        }

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
                        return clampAndEnsureArgb8888(oriented, maxDim)
                    }
                }
            } catch (oom: OutOfMemoryError) {
                lastError = oom
                sampleSize *= 2
            }
        }

        throw IllegalStateException(
            "Unable to decode full-resolution image for export.",
            lastError
        )
    }

    private fun decodeFileForExport(
        file: File,
        maxDim: Int
    ): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val decoded = runCatching {
                decodeWithImageDecoder(
                    source = ImageDecoder.createSource(file),
                    targetMaxDim = maxDim
                ).proxyBitmap
            }.getOrNull()
            if (decoded != null) {
                return decoded
            }
        }

        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, boundsOpts)
        val rawW = boundsOpts.outWidth
        val rawH = boundsOpts.outHeight
        val orientation = readExifOrientationFromFile(file)

        var sampleSize = if (rawW > 0 && rawH > 0) {
            calculateInSampleSize(rawW, rawH, maxDim)
        } else {
            1
        }

        var lastError: Throwable? = null
        repeat(3) {
            try {
                if (rawW > 0 && rawH > 0) {
                    val opts = BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    val decoded = BitmapFactory.decodeFile(file.absolutePath, opts)
                    if (decoded != null) {
                        val oriented = applyExifOrientation(decoded, orientation)
                        return clampAndEnsureArgb8888(oriented, maxDim)
                    }
                }
            } catch (oom: OutOfMemoryError) {
                lastError = oom
                sampleSize *= 2
            }
        }

        throw IllegalStateException(
            "Unable to decode full-resolution shared image for export.",
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

    private fun readExifOrientationFromFile(file: File): Int {
        return runCatching {
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
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
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return bitmap
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
        return try {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated !== bitmap && !bitmap.isRecycled) {
                bitmap.recycle()
            }
            rotated
        } catch (_: OutOfMemoryError) {
            bitmap
        }
    }

    private fun clampAndEnsureArgb8888(bitmap: Bitmap, maxDim: Int): Bitmap {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            throw IllegalStateException("Decoded bitmap is empty or recycled.")
        }
        val longest = max(bitmap.width, bitmap.height)
        val scaled = if (longest > maxDim && maxDim > 0) {
            val scale = maxDim.toFloat() / longest.toFloat()
            val w = max(1, (bitmap.width * scale).roundToInt())
            val h = max(1, (bitmap.height * scale).roundToInt())
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
        if (bitmap.isRecycled || bitmap.config == Bitmap.Config.ARGB_8888) return bitmap
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return bitmap
        if (copy !== bitmap && !bitmap.isRecycled) {
            bitmap.recycle()
        }
        return copy
    }

    fun createPreviewBitmap(source: Bitmap, maxDim: Int = PROXY_MAX_DIMENSION): Bitmap {
        if (source.isRecycled || source.width <= 0 || source.height <= 0) return source
        val longest = max(source.width, source.height)
        if (longest <= maxDim || maxDim <= 0) return source
        val scale = maxDim.toFloat() / longest.toFloat()
        val w = max(1, (source.width * scale).roundToInt())
        val h = max(1, (source.height * scale).roundToInt())
        return try {
            source.scale(w, h)
        } catch (_: OutOfMemoryError) {
            source
        }
    }

    fun createThumbnailBitmap(source: Bitmap, maxDim: Int = THUMBNAIL_DIMENSION): Bitmap {
        if (source.isRecycled || source.width <= 0 || source.height <= 0) return source
        val longest = max(source.width, source.height).coerceAtLeast(1)
        val scale = maxDim.coerceAtLeast(1).toFloat() / longest.toFloat()
        val w = max(1, (source.width * scale).roundToInt())
        val h = max(1, (source.height * scale).roundToInt())
        return try {
            source.scale(w, h)
        } catch (_: OutOfMemoryError) {
            source
        }
    }

    fun queryDisplayName(context: Context, uri: Uri, fallback: String): String {
        runCatching {
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
        }
        val segment = uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.substringAfterLast(':')
            ?.trim()
        if (!segment.isNullOrBlank()) {
            return segment
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
            ExportResolution.FULL -> clampLongestEdge(sourceWidth, sourceHeight, EXPORT_MAX_DIMENSION)
            ExportResolution.HALF -> max(1, (sourceWidth * 0.5f).toInt()) to max(1, (sourceHeight * 0.5f).toInt())
            ExportResolution.PX_2048 -> clampLongestEdge(sourceWidth, sourceHeight, 2048)
            ExportResolution.PX_1080 -> clampLongestEdge(sourceWidth, sourceHeight, 1080)
        }
    }

    private fun clampLongestEdge(w: Int, h: Int, maxEdge: Int): Pair<Int, Int> {
        val longest = max(w, h)
        if (longest <= maxEdge || maxEdge <= 0) return w to h
        val scale = maxEdge.toFloat() / longest.toFloat()
        return max(1, (w * scale).roundToInt()) to max(1, (h * scale).roundToInt())
    }

    fun scaleToExportResolution(source: Bitmap, resolution: ExportResolution): Bitmap {
        val (targetW, targetH) = computeTargetDimensions(source.width, source.height, resolution)
        if (targetW == source.width && targetH == source.height) return source
        return source.scale(targetW, targetH)
    }

    fun generateExportFilename(format: ExportFormat): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "Z-Effect_$stamp.${format.extension}"
    }

    /**
     * Immediately writes the rendered bitmap to public device storage via [MediaStore]:
     * - JPG, PNG, WebP -> `Pictures/Z-Effect` via [MediaStore.Images.Media.EXTERNAL_CONTENT_URI]
     * - PDF, 16-bit TIFF -> `Downloads/Z-Effect` via [MediaStore.Downloads.EXTERNAL_CONTENT_URI] (API 29+)
     *
     * Sets `IS_PENDING = 1` during encoding and publishes with `IS_PENDING = 0` upon completion,
     * returning the newly created public `content://` [Uri] inside [ExportSaveResult].
     */
    suspend fun saveToGalleryMediaStore(
        context: Context,
        bitmap: Bitmap,
        options: ExportOptions
    ): ExportSaveResult = withContext(Dispatchers.IO) {
        val filename = generateExportFilename(options.format)
        val resolver = context.contentResolver

        val (collectionUri, relativePath, destinationLabel) = when (options.format) {
            ExportFormat.PDF, ExportFormat.TIFF -> {
                val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI
                } else {
                    MediaStore.Files.getContentUri("external")
                }
                Triple(collection, "${Environment.DIRECTORY_DOWNLOADS}/Z-Effect", "Downloads/Z-Effect")
            }
            ExportFormat.JPG, ExportFormat.PNG, ExportFormat.WEBP -> {
                Triple(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    "${Environment.DIRECTORY_PICTURES}/Z-Effect",
                    "Pictures/Z-Effect"
                )
            }
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, options.format.mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val itemUri: Uri? = runCatching { resolver.insert(collectionUri, values) }.getOrNull()

        val resolvedUri = itemUri
            ?: throw IllegalStateException("Could not create MediaStore entry for $filename.")

        try {
            resolver.openOutputStream(resolvedUri)?.use { out ->
                encodeExportBitmap(bitmap, options, out)
            } ?: throw IllegalStateException("Could not open output stream for export.")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val publishValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                resolver.update(resolvedUri, publishValues, null, null)
            }
            ExportSaveResult(
                filename = filename,
                uri = resolvedUri,
                mimeType = options.format.mimeType,
                format = options.format,
                destinationLabel = destinationLabel
            )
        } catch (t: Throwable) {
            runCatching { resolver.delete(resolvedUri, null, null) }
            throw t
        }
    }

    suspend fun saveToDocumentUri(
        context: Context,
        bitmap: Bitmap,
        uri: Uri,
        options: ExportOptions
    ): ExportSaveResult = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri)?.use { out ->
            encodeExportBitmap(bitmap, options, out)
        } ?: throw IllegalStateException("Could not open selected destination file.")
        val savedName = queryDisplayName(context, uri, generateExportFilename(options.format))
        ExportSaveResult(
            filename = savedName,
            uri = uri,
            mimeType = options.format.mimeType,
            format = options.format,
            destinationLabel = "Custom Location"
        )
    }

    private fun encodeExportBitmap(bitmap: Bitmap, options: ExportOptions, out: OutputStream) {
        when (options.format) {
            ExportFormat.PDF -> encodePdfToStream(bitmap, out)
            ExportFormat.JPG, ExportFormat.PNG, ExportFormat.WEBP , ExportFormat.TIFF  -> compressBitmap(bitmap, options, out)
        }
    }

    /**
     * Generates a full-bleed, high-resolution print-ready PDF using Android's native [PdfDocument]
     * matching the edited photograph's exact pixel dimensions with zero margins.
     */
    private fun encodePdfToStream(bitmap: Bitmap, out: OutputStream) {
        val width = bitmap.width.coerceAtLeast(1)
        val height = bitmap.height.coerceAtLeast(1)
        val pdfDocument = PdfDocument()
        try {
            val pageInfo = PdfDocument.PageInfo.Builder(width, height, 1).create()
            val page = pdfDocument.startPage(pageInfo)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val destRect = Rect(0, 0, width, height)
            page.canvas.drawBitmap(bitmap, null, destRect, paint)
            pdfDocument.finishPage(page)
            pdfDocument.writeTo(out)
            out.flush()
        } finally {
            pdfDocument.close()
        }
    }

    private fun compressBitmap(bitmap: Bitmap, options: ExportOptions, out: OutputStream) {
        if (options.format == ExportFormat.TIFF) {
            val tmpFile = java.io.File.createTempFile("zamlab_16bit_", ".tiff")
            try {
                val ok = com.zamalfajji.zamlab.nativebridge.NativeBridge.export16BitTiffNative(bitmap, tmpFile.absolutePath)
                if (!ok) {
                    throw IllegalStateException("Failed to encode 16-bit TIFF in native engine")
                }
                tmpFile.inputStream().use { input -> input.copyTo(out) }
                out.flush()
            } finally {
                tmpFile.delete()
            }
            return
        }
        val quality = options.effectiveQuality
        val compressFormat = options.format.resolveCompressFormat(quality)
        val ok = bitmap.compress(compressFormat, quality, out)
        if (!ok) {
            throw IllegalStateException("Failed to encode image as ${options.format.extension.uppercase(Locale.ROOT)}")
        }
        out.flush()
    }

}

