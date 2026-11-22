package com.example.lut

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Immutable
import com.example.editor.ColorGradingEngine
import com.example.editor.ImageIoHelper
import com.example.nativebridge.NativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipInputStream

private const val TAG = "LutImporter"
private const val CUSTOM_LUT_DIR = "custom_luts"
private const val MAX_ENTRY_BYTES = 25 * 1024 * 1024 // 25 MB safety limit per LUT file
private const val MAX_ZIP_DEPTH = 4

enum class PresetLocationType {
    ASSET,
    FILE
}

@Immutable
data class PresetDescriptor(
    val id: String,
    val title: String,
    val category: String,
    val sourceFormat: LutSourceFormat,
    val isCustom: Boolean,
    val locationType: PresetLocationType,
    val locationPath: String
)

data class LutImportSummary(
    val importedLuts: List<CubeLut>,
    val skippedMessages: List<String>
)

object LutImporter {

    private val uniqueCounter = AtomicLong(0L)

    /**
     * Fast directory/asset index scan that does NOT open or parse any .xmp or .cube file.
     * Used on startup so closed preset folders incur zero XML/3D LUT parsing or thumbnail CPU cost.
     */
    suspend fun indexPresetCatalog(context: Context): Pair<List<PresetDescriptor>, List<PresetDescriptor>> =
        withContext(Dispatchers.IO) {
            val bundled = mutableListOf<PresetDescriptor>()
            val custom = mutableListOf<PresetDescriptor>()

            // 1. Index assets/luts/ and assets/presets/ purely from asset directory listing
            val assetPaths = mutableListOf<String>()
            collectAssetFilesRecursively(context, "luts", assetPaths)
            collectAssetFilesRecursively(context, "presets", assetPaths)

            for (assetPath in assetPaths) {
                val fileName = assetPath.substringAfterLast('/')
                val lower = fileName.lowercase(Locale.US)
                val format = if (lower.endsWith(".xmp")) LutSourceFormat.XMP else LutSourceFormat.CUBE
                bundled += PresetDescriptor(
                    id = "builtin_$assetPath",
                    title = deriveDisplayTitle(fileName),
                    category = deriveAssetCategory(assetPath),
                    sourceFormat = format,
                    isCustom = false,
                    locationType = PresetLocationType.ASSET,
                    locationPath = assetPath
                )
            }

            // 2. Index previously imported custom presets/LUTs in internal storage without parsing files
            val customDir = File(context.filesDir, CUSTOM_LUT_DIR)
            if (customDir.exists() && customDir.isDirectory) {
                val files = collectLocalFilesRecursively(customDir)
                    .sortedByDescending { it.lastModified() }
                for (file in files) {
                    val lower = file.name.lowercase(Locale.US)
                    if (!lower.endsWith(".cube") && !lower.endsWith(".xmp")) continue
                    val relPath = customDir.toURI().relativize(file.toURI()).path
                        .trimStart('/')
                    val category = deriveSavedCustomCategory(customDir, file)
                    val format = if (lower.endsWith(".xmp")) LutSourceFormat.XMP else LutSourceFormat.CUBE
                    custom += PresetDescriptor(
                        id = "custom_$relPath",
                        title = deriveDisplayTitle(file.name),
                        category = category,
                        sourceFormat = format,
                        isCustom = true,
                        locationType = PresetLocationType.FILE,
                        locationPath = relPath
                    )
                }
            }

            bundled to custom
        }

    /**
     * Parses a single indexed preset descriptor on Dispatchers.IO when its folder is expanded or selected.
     */
    suspend fun loadPreset(
        context: Context,
        descriptor: PresetDescriptor,
        bakeXmpTable: Boolean = false
    ): CubeLut = withContext(Dispatchers.IO) {
        val stream: InputStream = when (descriptor.locationType) {
            PresetLocationType.ASSET -> context.assets.open(descriptor.locationPath)
            PresetLocationType.FILE -> File(File(context.filesDir, CUSTOM_LUT_DIR), descriptor.locationPath).inputStream()
        }
        val fileName = descriptor.locationPath.substringAfterLast('/')
        stream.use {
            parseByExtension(
                stream = it,
                fileName = fileName,
                id = descriptor.id,
                fallbackCategory = descriptor.category,
                bakeXmpTable = bakeXmpTable
            )
        }
    }

    suspend fun loadBundledAndSavedLuts(context: Context): Pair<List<CubeLut>, List<CubeLut>> =
        withContext(Dispatchers.IO) {
            val (bundledDescriptors, customDescriptors) = indexPresetCatalog(context)
            val bundled = bundledDescriptors.mapNotNull { desc ->
                runCatching { loadPreset(context, desc, bakeXmpTable = true) }
                    .onFailure { Log.e(TAG, "Failed to parse bundled preset ${desc.locationPath}", it) }
                    .getOrNull()
            }
            val custom = customDescriptors.mapNotNull { desc ->
                runCatching { loadPreset(context, desc, bakeXmpTable = true) }
                    .onFailure { Log.e(TAG, "Failed to load saved custom LUT ${desc.locationPath}", it) }
                    .getOrNull()
            }
            bundled to custom
        }

    /**
     * Validates the user-selected preset/LUT filename extension after opening Android SAF with wildcard MIME type.
     * Returns `"cube"`, `"xmp"`, or `"zip"` for supported extensions; returns `null` if the provider
     * omitted an extension so content headers can be inspected; throws [IllegalArgumentException]
     * for unsupported explicit extensions (e.g. `.jpg`, `.txt`, `.pdf`).
     */
    fun validatePresetExtension(fileName: String): String? {
        val trimmed = fileName.trim()
        val dotIdx = trimmed.lastIndexOf('.')
        if (dotIdx <= 0 || dotIdx == trimmed.length - 1) return null
        return when (val ext = trimmed.substring(dotIdx + 1).lowercase(Locale.US)) {
            "cube", "xmp", "zip" -> ext
            else -> throw IllegalArgumentException(
                "Unsupported file \".$ext\". Please choose a .cube, .xmp, or .zip preset file."
            )
        }
    }

    suspend fun importFromUri(context: Context, uri: Uri): LutImportSummary =
        withContext(Dispatchers.IO) {
            try {
                val displayName = ImageIoHelper.queryDisplayName(context, uri, "imported_lut")
                val validatedExt = validatePresetExtension(displayName)
                val customDir = File(context.filesDir, CUSTOM_LUT_DIR).apply { mkdirs() }

                val rawBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytesLimited() }
                    ?: throw IllegalStateException("Cannot read selected file.")

                when {
                    validatedExt == "zip" || (validatedExt == null && isZipHeader(rawBytes)) -> {
                        importZipBytes(rawBytes, customDir)
                    }
                    validatedExt == "xmp" || (validatedExt == null && looksLikeXml(rawBytes)) -> {
                        val nativeXmpData = if (NativeBridge.isAvailable) {
                            runCatching {
                                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                                    NativeBridge.nativeParseXmpPreset(pfd.fd)
                                }
                            }.getOrNull()
                        } else {
                            null
                        }
                        val lut = persistAndParseSingle(
                            bytes = rawBytes,
                            originalName = ensureExtension(displayName, ".xmp"),
                            customDir = customDir,
                            fallbackCategory = "Custom",
                            nativeXmpOverlay = nativeXmpData
                        )
                        LutImportSummary(listOf(lut), emptyList())
                    }
                    validatedExt == "cube" || (validatedExt == null && looksLikeCube(rawBytes)) -> {
                        val lut = persistAndParseSingle(
                            bytes = rawBytes,
                            originalName = ensureExtension(displayName, ".cube"),
                            customDir = customDir,
                            fallbackCategory = "Custom"
                        )
                        LutImportSummary(listOf(lut), emptyList())
                    }
                    else -> {
                        throw IllegalArgumentException(
                            "Unsupported preset file \"$displayName\". Please select a .cube, .xmp, or .zip file."
                        )
                    }
                }
            } catch (e: OutOfMemoryError) {
                throw IllegalStateException("Selected LUT file is too large to import.", e)
            } catch (e: SecurityException) {
                throw IllegalStateException("Permission denied reading selected LUT file.", e)
            }
        }

    suspend fun deleteCustomLut(context: Context, lutId: String) = withContext(Dispatchers.IO) {
        if (!lutId.startsWith("custom_")) return@withContext
        val relPath = lutId.removePrefix("custom_")
        val customDir = File(context.filesDir, CUSTOM_LUT_DIR)
        val target = File(customDir, relPath)
        if (target.exists()) {
            target.delete()
            // Remove empty parent folder if it was a subfolder under custom_luts
            val parent = target.parentFile
            if (parent != null && parent != customDir && parent.listFiles().isNullOrEmpty()) {
                parent.delete()
            }
        }
    }

    internal fun importZipBytes(zipBytes: ByteArray, customDir: File): LutImportSummary {
        val imported = mutableListOf<CubeLut>()
        val skipped = mutableListOf<String>()

        importZipBytesRecursive(
            zipBytes = zipBytes,
            customDir = customDir,
            depth = 0,
            imported = imported,
            skipped = skipped
        )

        if (imported.isEmpty()) {
            val detail = skipped.firstOrNull() ?: "No valid .cube or .xmp LUT files found inside ZIP archive."
            throw IllegalArgumentException(detail)
        }
        return LutImportSummary(imported, skipped)
    }

    private fun importZipBytesRecursive(
        zipBytes: ByteArray,
        customDir: File,
        depth: Int,
        imported: MutableList<CubeLut>,
        skipped: MutableList<String>
    ) {
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val normalizedPath = entry.name.replace('\\', '/')
                val segments = normalizedPath.split('/').filter { it.isNotBlank() }

                val isHiddenOrMacMeta = entry.isDirectory ||
                    segments.isEmpty() ||
                    segments.any { seg ->
                        seg.equals("__MACOSX", ignoreCase = true) || seg.startsWith(".")
                    }

                if (!isHiddenOrMacMeta) {
                    val baseName = segments.last()
                    val lowerBase = baseName.lowercase(Locale.US)
                    val folderSegments = segments.dropLast(1)
                    val fallbackCategory = deriveZipFolderCategory(folderSegments)

                    when {
                        lowerBase.endsWith(".zip") -> {
                            if (depth < MAX_ZIP_DEPTH) {
                                runCatching {
                                    val nestedBytes = zis.readEntryBytesLimited()
                                    importZipBytesRecursive(
                                        zipBytes = nestedBytes,
                                        customDir = customDir,
                                        depth = depth + 1,
                                        imported = imported,
                                        skipped = skipped
                                    )
                                }.onFailure { err ->
                                    skipped.add("$baseName: ${err.localizedMessage ?: "Invalid nested ZIP"}")
                                }
                            } else {
                                skipped.add("$baseName: Nested ZIP exceeds max depth ($MAX_ZIP_DEPTH)")
                            }
                        }
                        lowerBase.endsWith(".cube") || lowerBase.endsWith(".xmp") -> {
                            runCatching {
                                val entryBytes = zis.readEntryBytesLimited()
                                persistAndParseSingle(
                                    bytes = entryBytes,
                                    originalName = baseName,
                                    customDir = customDir,
                                    fallbackCategory = fallbackCategory
                                )
                            }.onSuccess { lut ->
                                imported.add(lut)
                            }.onFailure { err ->
                                skipped.add("$baseName: ${err.localizedMessage ?: "Unsupported"}")
                            }
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun persistAndParseSingle(
        bytes: ByteArray,
        originalName: String,
        customDir: File,
        fallbackCategory: String,
        nativeXmpOverlay: FloatArray? = null
    ): CubeLut {
        customDir.mkdirs()
        val sanitizedFile = originalName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val uniqueName = "${System.currentTimeMillis()}_${uniqueCounter.incrementAndGet()}_$sanitizedFile"

        val parsedTemp = parseByExtension(
            stream = ByteArrayInputStream(bytes),
            fileName = originalName,
            id = "temp",
            fallbackCategory = fallbackCategory,
            bakeXmpTable = false
        )
        val mergedTemp = if (nativeXmpOverlay != null &&
            nativeXmpOverlay.size >= ColorGradingEngine.NATIVE_XMP_HEADER_SIZE &&
            parsedTemp.xmpSettings != null
        ) {
            val decoded = ColorGradingEngine.decodeNativeXmpToGradingParams(nativeXmpOverlay)
            val curvePairs = if (!decoded.toneCurve.isDefault) {
                decoded.toneCurve.master.map { it.x to it.y }
            } else {
                parsedTemp.xmpSettings.toneCurveMaster
            }
            parsedTemp.copy(
                xmpSettings = parsedTemp.xmpSettings.copy(
                    exposure = decoded.exposure,
                    contrast = decoded.contrast,
                    highlights = decoded.highlights,
                    shadows = decoded.shadows,
                    whites = decoded.whites,
                    blacks = decoded.blacks,
                    temperature = decoded.temperature,
                    tint = decoded.tint,
                    vibrance = decoded.vibrance,
                    saturation = decoded.saturation,
                    clarity = decoded.clarity,
                    dehaze = decoded.dehaze,
                    toneCurveMaster = curvePairs
                )
            )
        } else {
            parsedTemp
        }
        val resolvedFolder = mergedTemp.category.ifBlank { fallbackCategory }.ifBlank { "Custom" }
        val encodedFolder = URLEncoder.encode(resolvedFolder, "UTF-8")
        val targetDir = if (resolvedFolder == "Custom") customDir else File(customDir, encodedFolder).apply { mkdirs() }
        val relPath = if (resolvedFolder == "Custom") uniqueName else "$encodedFolder/$uniqueName"

        File(targetDir, uniqueName).writeBytes(bytes)
        return mergedTemp.copy(id = "custom_$relPath", category = resolvedFolder)
    }

    private fun parseByExtension(
        stream: InputStream,
        fileName: String,
        id: String,
        fallbackCategory: String,
        bakeXmpTable: Boolean = true
    ): CubeLut {
        val lower = fileName.lowercase(Locale.US)
        val fallbackTitle = deriveDisplayTitle(fileName)

        return if (lower.endsWith(".xmp")) {
            XmpPresetParser.parseToCubeLut(
                inputStream = stream,
                id = id,
                fallbackTitle = fallbackTitle,
                fallbackCategory = fallbackCategory,
                bakeTable = bakeXmpTable
            )
        } else {
            CubeLutParser.parse(stream, id, fallbackTitle, fallbackCategory)
        }
    }

    private fun deriveDisplayTitle(fileName: String): String {
        val rawStem = fileName
            .substringBeforeLast('.')
            .replace(Regex("^\\d+_\\d+_"), "")
            .replace(Regex("^\\d+_"), "")
            .replace('_', ' ')
            .trim()
        return rawStem.ifEmpty { "Custom LUT" }
    }

    private fun deriveSavedCustomCategory(customDir: File, file: File): String {
        val parent = file.parentFile ?: return "Custom"
        if (parent == customDir) return "Custom"
        return runCatching {
            URLDecoder.decode(parent.name, "UTF-8")
        }.getOrDefault(parent.name).ifBlank { "Custom" }
    }

    private fun collectAssetFilesRecursively(
        context: Context,
        rootDir: String,
        outPaths: MutableList<String>
    ) {
        val entries = runCatching { context.assets.list(rootDir)?.toList().orEmpty() }
            .getOrDefault(emptyList())
            .sorted()
        for (entry in entries) {
            if (entry.startsWith(".")) continue
            val fullPath = if (rootDir.isEmpty()) entry else "$rootDir/$entry"
            val lower = entry.lowercase(Locale.US)
            if (lower.endsWith(".cube") || lower.endsWith(".xmp")) {
                outPaths.add(fullPath)
            } else {
                val children = runCatching { context.assets.list(fullPath) }.getOrNull()
                if (!children.isNullOrEmpty()) {
                    collectAssetFilesRecursively(context, fullPath, outPaths)
                }
            }
        }
    }

    private fun collectLocalFilesRecursively(dir: File): List<File> {
        val result = mutableListOf<File>()
        dir.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                result.addAll(collectLocalFilesRecursively(child))
            } else if (child.isFile) {
                result.add(child)
            }
        }
        return result
    }

    private fun deriveAssetCategory(assetPath: String): String {
        val segments = assetPath.split('/').filter { it.isNotBlank() }
        if (segments.size <= 2) {
            return if (segments.firstOrNull() == "luts") "Built-in LUTs" else "Presets"
        }
        // e.g. presets/<Pack Folder>/<File> -> clean pack folder name
        val folderName = segments[segments.size - 2]
        return cleanCategoryName(folderName).ifEmpty { "Presets" }
    }

    private fun deriveZipFolderCategory(folderSegments: List<String>): String {
        val rawFolder = folderSegments.lastOrNull { seg ->
            val cleaned = cleanCategoryName(seg)
            cleaned.isNotBlank() &&
                !cleaned.equals("luts", ignoreCase = true) &&
                !cleaned.equals("presets", ignoreCase = true)
        } ?: return "Custom"
        return cleanCategoryName(rawFolder).ifEmpty { "Custom" }
    }

    private fun cleanCategoryName(raw: String): String {
        return raw
            .replace(Regex("\\s*-\\s*Desktop Presets\\s*\\(XMP\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*-\\s*Desktop Presets", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*-\\s*Mobile Presets", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*-\\s*Presets", RegexOption.IGNORE_CASE), "")
            .replace('_', ' ')
            .trim()
    }

    private fun ensureExtension(name: String, ext: String): String {
        return if (name.lowercase(Locale.US).endsWith(ext)) name else "$name$ext"
    }

    private fun isZipHeader(bytes: ByteArray): Boolean {
        return bytes.size >= 4 &&
            bytes[0] == 0x50.toByte() &&
            bytes[1] == 0x4B.toByte()
    }

    private fun looksLikeXml(bytes: ByteArray): Boolean {
        val sample = String(bytes, 0, minOf(bytes.size, 256), Charsets.UTF_8).trimStart()
        return sample.startsWith("<") && (sample.contains("xmp", ignoreCase = true) || sample.contains("adobe", ignoreCase = true) || sample.contains("crs:", ignoreCase = true))
    }

    private fun looksLikeCube(bytes: ByteArray): Boolean {
        val sample = String(bytes, 0, minOf(bytes.size, 1024), Charsets.UTF_8)
        return sample.contains("LUT_3D_SIZE", ignoreCase = true) ||
            sample.contains("LUT_1D_SIZE", ignoreCase = true)
    }

    private fun InputStream.readBytesLimited(): ByteArray {
        val buffer = ByteArray(8192)
        val out = ByteArrayOutputStream()
        var total = 0
        while (true) {
            val n = read(buffer)
            if (n <= 0) break
            total += n
            if (total > MAX_ENTRY_BYTES) {
                throw IllegalArgumentException("Selected file exceeds 25 MB limit.")
            }
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun ZipInputStream.readEntryBytesLimited(): ByteArray {
        val buffer = ByteArray(8192)
        val out = ByteArrayOutputStream()
        var total = 0
        while (true) {
            val n = read(buffer)
            if (n <= 0) break
            total += n
            if (total > MAX_ENTRY_BYTES) {
                throw IllegalArgumentException("LUT entry inside ZIP exceeds 25 MB limit.")
            }
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}
