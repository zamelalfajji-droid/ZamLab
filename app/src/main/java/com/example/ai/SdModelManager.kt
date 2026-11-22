package com.example.ai

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.math.max
import kotlin.math.roundToInt

enum class AiAspectRatio(
    val label: String,
    val ratioW: Int,
    val ratioH: Int,
    val outputWidth: Int,
    val outputHeight: Int
) {
    SQUARE(label = "1:1", ratioW = 1, ratioH = 1, outputWidth = 512, outputHeight = 512),
    PORTRAIT_4_5(label = "4:5", ratioW = 4, ratioH = 5, outputWidth = 410, outputHeight = 512),
    LANDSCAPE_16_9(label = "16:9", ratioW = 16, ratioH = 9, outputWidth = 512, outputHeight = 288)
}

@Immutable
data class CropPixelRect(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int
)

@Immutable
data class InstalledAiModel(
    val id: String,
    val displayName: String,
    val sourceUrl: String,
    val sizeBytes: Long
) {
    val sourceBadge: String
        get() = SdModelManager.describeSource(sourceUrl)
}

@Immutable
data class AiModelCatalogState(
    val models: List<InstalledAiModel> = emptyList(),
    val activeModelId: String? = null
) {
    val activeModel: InstalledAiModel?
        get() = models.firstOrNull { it.id == activeModelId } ?: models.firstOrNull()
}

enum class ModelInstallPhase {
    IDLE,
    DOWNLOADING,
    EXTRACTING,
    READY,
    ERROR
}

@Immutable
data class ModelDownloadProgress(
    val phase: ModelInstallPhase = ModelInstallPhase.IDLE,
    val targetLabel: String = "",
    val bytesProcessed: Long = 0L,
    val totalBytes: Long = SdModelManager.APPROXIMATE_MODEL_BYTES,
    val extractPercent: Int = 0,
    val errorMessage: String? = null
) {
    val downloadFraction: Float
        get() = if (totalBytes > 0L) {
            (bytesProcessed.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
        } else {
            0f
        }

    val overallFraction: Float
        get() = when (phase) {
            ModelInstallPhase.IDLE -> 0f
            ModelInstallPhase.DOWNLOADING -> (downloadFraction * 0.85f).coerceIn(0f, 0.85f)
            ModelInstallPhase.EXTRACTING -> (0.85f + (extractPercent / 100f) * 0.15f).coerceIn(0.85f, 1f)
            ModelInstallPhase.READY -> 1f
            ModelInstallPhase.ERROR -> 0f
        }
}

object SdModelManager {

    const val AI_MODELS_FOLDER_NAME = "ai_models"
    const val LEGACY_MODEL_FOLDER_NAME = "sd_model"
    const val CATALOG_FILE_NAME = "models_catalog.json"
    const val BUILTIN_SD15_ID = "sd15_mediapipe"
    const val BUILTIN_SD15_NAME = "Stable Diffusion v1.5"
    const val TEMP_ZIP_NAME = "zamlab_ai_model.zip.part"
    const val READY_MARKER_NAME = ".sd_model_ready"
    const val APPROXIMATE_MODEL_BYTES = 2_050_846_917L // ~1.91 GiB
    const val APPROXIMATE_MODEL_LABEL = "~1.91 GB"
    const val MIN_STEPS = 10
    const val MAX_STEPS = 25
    const val DEFAULT_STEPS = 20

    const val MODEL_ZIP_URL =
        "https://huggingface.co/na5h13/stable-diffusion-v1-5-mediapipe/resolve/main/sd15-mediapipe.zip"

    private const val MIN_REQUIRED_MODEL_BYTES = 10L * 1024L * 1024L
    private const val DEFAULT_MIN_FREE_SPACE_BYTES = 2_400_000_000L
    private const val MAX_HTTP_REDIRECTS = 10
    private val MODEL_FILE_EXTENSIONS = setOf("tflite", "bin", "pb", "model", "task")

    fun aiModelsBaseDir(context: Context): File = File(context.filesDir, AI_MODELS_FOLDER_NAME)

    fun modelFolderForId(context: Context, id: String): File =
        File(aiModelsBaseDir(context), sanitizeIdSegment(id))

    fun clampInferenceSteps(steps: Int): Int = steps.coerceIn(MIN_STEPS, MAX_STEPS)

    fun computeCenterCropRect(
        srcWidth: Int,
        srcHeight: Int,
        aspectRatio: AiAspectRatio
    ): CropPixelRect {
        val safeW = srcWidth.coerceAtLeast(1)
        val safeH = srcHeight.coerceAtLeast(1)
        val targetAspect = aspectRatio.ratioW.toFloat() / aspectRatio.ratioH.toFloat()
        val srcAspect = safeW.toFloat() / safeH.toFloat()

        return if (kotlin.math.abs(srcAspect - targetAspect) < 0.002f) {
            CropPixelRect(left = 0, top = 0, width = safeW, height = safeH)
        } else if (srcAspect > targetAspect) {
            val cropW = (safeH * targetAspect).roundToInt().coerceIn(1, safeW)
            val left = ((safeW - cropW) / 2).coerceAtLeast(0)
            CropPixelRect(left = left, top = 0, width = cropW, height = safeH)
        } else {
            val cropH = (safeW / targetAspect).roundToInt().coerceIn(1, safeH)
            val top = ((safeH - cropH) / 2).coerceAtLeast(0)
            CropPixelRect(left = 0, top = top, width = safeW, height = cropH)
        }
    }

    /**
     * Normalizes remote model links for direct ZIP download:
     * - Converts Hugging Face `/blob/` links to `/resolve/`
     * - Converts GitHub `/blob/` links to `/raw/`
     */
    fun normalizeModelDownloadUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) {
            throw IllegalArgumentException("Enter a Hugging Face or GitHub .zip URL.")
        }
        val parsed = runCatching { URL(trimmed) }.getOrNull()
            ?: throw IllegalArgumentException("Invalid URL format.")
        val protocol = parsed.protocol.lowercase(Locale.ROOT)
        if (protocol != "https" && protocol != "http") {
            throw IllegalArgumentException("URL must start with https:// or http://.")
        }

        val host = parsed.host.lowercase(Locale.ROOT)
        val path = parsed.path
        val querySuffix = parsed.query?.let { "?$it" }.orEmpty()

        if ((host == "huggingface.co" || host.endsWith(".huggingface.co") || host == "hf.co") &&
            path.contains("/blob/")
        ) {
            val normalizedPath = path.replaceFirst("/blob/", "/resolve/")
            val portPart = if (parsed.port != -1) ":${parsed.port}" else ""
            return "$protocol://${parsed.host}$portPart$normalizedPath$querySuffix"
        }

        if ((host == "github.com" || host == "www.github.com") && path.contains("/blob/")) {
            val normalizedPath = path.replaceFirst("/blob/", "/raw/")
            val portPart = if (parsed.port != -1) ":${parsed.port}" else ""
            return "$protocol://${parsed.host}$portPart$normalizedPath$querySuffix"
        }

        return trimmed
    }

    fun describeSource(sourceUrl: String): String {
        val lower = sourceUrl.lowercase(Locale.ROOT)
        return when {
            sourceUrl == MODEL_ZIP_URL -> "Built-in • Hugging Face"
            lower.startsWith("local://") -> "Local ZIP"
            lower.contains("huggingface.co") || lower.contains("hf.co") -> "Hugging Face"
            lower.contains("github.com") || lower.contains("githubusercontent.com") -> "GitHub"
            else -> runCatching { URL(sourceUrl).host }.getOrNull().orEmpty().ifBlank { "Custom URL" }
        }
    }

    fun generateModelId(displayName: String, existingIds: Set<String>): String {
        val baseSlug = displayName.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .take(36)
            .ifEmpty { "custom_model" }
        var candidate = baseSlug
        var suffix = 2
        while (candidate in existingIds) {
            candidate = "${baseSlug.take(30)}_$suffix"
            suffix++
        }
        return candidate
    }

    private fun sanitizeIdSegment(id: String): String {
        val clean = id.replace(Regex("[^a-zA-Z0-9._-]"), "_").trim('.')
        return clean.ifEmpty { "model" }
    }

    fun loadCatalog(context: Context): AiModelCatalogState {
        val rootDir = aiModelsBaseDir(context)
        migrateLegacySdModelIfNeeded(context, rootDir)
        return loadCatalogFromDir(rootDir)
    }

    fun resolveActiveModelDirectory(context: Context): File? {
        val catalog = loadCatalog(context)
        val active = catalog.activeModel ?: return null
        val modelDir = File(aiModelsBaseDir(context), sanitizeIdSegment(active.id))
        return resolveModelDirectory(modelDir)
    }

    fun resolveModelDirectoryForId(context: Context, modelId: String): File? {
        val modelDir = File(aiModelsBaseDir(context), sanitizeIdSegment(modelId))
        return resolveModelDirectory(modelDir)
    }

    private fun migrateLegacySdModelIfNeeded(context: Context, aiModelsRootDir: File) {
        val legacyDir = File(context.filesDir, LEGACY_MODEL_FOLDER_NAME)
        if (!legacyDir.exists() || !legacyDir.isDirectory) return
        if (resolveModelDirectory(legacyDir) == null) return

        val builtinTarget = File(aiModelsRootDir, BUILTIN_SD15_ID)
        if (builtinTarget.exists() && resolveModelDirectory(builtinTarget) != null) {
            runCatching { legacyDir.deleteRecursively() }
            return
        }

        if (!aiModelsRootDir.exists()) aiModelsRootDir.mkdirs()
        if (legacyDir.renameTo(builtinTarget)) {
            val size = calculateDirectorySize(builtinTarget)
            val existing = loadCatalogFromDir(aiModelsRootDir)
            if (existing.models.none { it.id == BUILTIN_SD15_ID }) {
                val migrated = InstalledAiModel(
                    id = BUILTIN_SD15_ID,
                    displayName = BUILTIN_SD15_NAME,
                    sourceUrl = MODEL_ZIP_URL,
                    sizeBytes = size
                )
                saveCatalogToDir(
                    aiModelsRootDir,
                    existing.copy(
                        models = listOf(migrated) + existing.models,
                        activeModelId = existing.activeModelId ?: BUILTIN_SD15_ID
                    )
                )
            }
        }
    }

    fun loadCatalogFromDir(
        aiModelsRootDir: File,
        minModelBytes: Long = MIN_REQUIRED_MODEL_BYTES
    ): AiModelCatalogState {
        if (!aiModelsRootDir.exists() || !aiModelsRootDir.isDirectory) {
            return AiModelCatalogState()
        }

        val catalogFile = File(aiModelsRootDir, CATALOG_FILE_NAME)
        val parsed = if (catalogFile.isFile) {
            runCatching {
                parseCatalogJson(catalogFile.readText(Charsets.UTF_8))
            }.getOrDefault(AiModelCatalogState())
        } else {
            AiModelCatalogState()
        }

        val validatedModels = mutableListOf<InstalledAiModel>()
        var catalogDirty = false

        for (entry in parsed.models) {
            val folder = File(aiModelsRootDir, sanitizeIdSegment(entry.id))
            if (resolveModelDirectory(folder, minModelBytes) != null) {
                val diskBytes = if (entry.sizeBytes > 0L) entry.sizeBytes else calculateDirectorySize(folder)
                if (diskBytes != entry.sizeBytes) catalogDirty = true
                validatedModels.add(entry.copy(sizeBytes = diskBytes))
            } else {
                catalogDirty = true
            }
        }

        // Also discover any unregistered valid model folders on disk
        val knownIds = validatedModels.map { it.id }.toSet()
        aiModelsRootDir.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") && it.name !in knownIds }
            ?.sortedBy { it.name.lowercase(Locale.ROOT) }
            ?.forEach { orphanFolder ->
                if (resolveModelDirectory(orphanFolder, minModelBytes) != null) {
                    val isBuiltin = orphanFolder.name == BUILTIN_SD15_ID
                    validatedModels.add(
                        InstalledAiModel(
                            id = orphanFolder.name,
                            displayName = if (isBuiltin) BUILTIN_SD15_NAME else orphanFolder.name,
                            sourceUrl = if (isBuiltin) MODEL_ZIP_URL else "local://discovered",
                            sizeBytes = calculateDirectorySize(orphanFolder)
                        )
                    )
                    catalogDirty = true
                }
            }

        val resolvedActiveId = parsed.activeModelId?.takeIf { active ->
            validatedModels.any { it.id == active }
        } ?: validatedModels.firstOrNull()?.id

        if (resolvedActiveId != parsed.activeModelId) {
            catalogDirty = true
        }

        val finalState = AiModelCatalogState(
            models = validatedModels,
            activeModelId = resolvedActiveId
        )
        if (catalogDirty && (validatedModels.isNotEmpty() || catalogFile.exists())) {
            saveCatalogToDir(aiModelsRootDir, finalState)
        }
        return finalState
    }

    fun saveCatalogToDir(aiModelsRootDir: File, catalog: AiModelCatalogState) {
        if (!aiModelsRootDir.exists()) {
            aiModelsRootDir.mkdirs()
        }
        val json = serializeCatalogJson(catalog)
        val target = File(aiModelsRootDir, CATALOG_FILE_NAME)
        val tmp = File(aiModelsRootDir, "$CATALOG_FILE_NAME.tmp")
        tmp.writeText(json, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.writeText(json, Charsets.UTF_8)
            runCatching { tmp.delete() }
        }
    }

    fun setActiveModel(context: Context, modelId: String): AiModelCatalogState {
        return setActiveModelInDir(aiModelsBaseDir(context), modelId)
    }

    fun setActiveModelInDir(
        aiModelsRootDir: File,
        modelId: String,
        minModelBytes: Long = MIN_REQUIRED_MODEL_BYTES
    ): AiModelCatalogState {
        val current = loadCatalogFromDir(aiModelsRootDir, minModelBytes)
        if (current.models.none { it.id == modelId }) return current
        val updated = current.copy(activeModelId = modelId)
        saveCatalogToDir(aiModelsRootDir, updated)
        return updated
    }

    fun deleteModel(context: Context, modelId: String): AiModelCatalogState {
        return deleteModelFromDir(aiModelsBaseDir(context), modelId)
    }

    fun deleteModelFromDir(
        aiModelsRootDir: File,
        modelId: String,
        minModelBytes: Long = MIN_REQUIRED_MODEL_BYTES
    ): AiModelCatalogState {
        val current = loadCatalogFromDir(aiModelsRootDir, minModelBytes)
        val targetFolder = File(aiModelsRootDir, sanitizeIdSegment(modelId))
        if (targetFolder.exists()) {
            targetFolder.deleteRecursively()
        }
        val remaining = current.models.filterNot { it.id == modelId }
        val nextActive = if (current.activeModelId == modelId) {
            remaining.firstOrNull()?.id
        } else {
            current.activeModelId?.takeIf { id -> remaining.any { it.id == id } }
                ?: remaining.firstOrNull()?.id
        }
        val updated = AiModelCatalogState(models = remaining, activeModelId = nextActive)
        saveCatalogToDir(aiModelsRootDir, updated)
        return updated
    }

    /**
     * Locates the directory containing extracted MediaPipe Stable Diffusion model files inside [baseDir].
     */
    fun resolveModelDirectory(
        baseDir: File,
        minModelBytes: Long = MIN_REQUIRED_MODEL_BYTES
    ): File? {
        if (!baseDir.exists() || !baseDir.isDirectory) return null
        val candidate = findModelRootDirectory(baseDir) ?: return null
        val totalModelBytes = calculateDirectorySize(candidate)
        return if (totalModelBytes >= minModelBytes) candidate else null
    }

    fun findModelRootDirectory(rootDir: File): File? {
        if (!rootDir.exists() || !rootDir.isDirectory) return null
        val queue = ArrayDeque<File>()
        queue.add(rootDir)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val children = current.listFiles()?.filter { !isIgnoredSystemEntry(it.name) }.orEmpty()
            val hasModelBinary = children.any { file ->
                file.isFile && file.length() > 0L && isModelWeightFile(file.name)
            }
            if (hasModelBinary) {
                return current
            }
            children
                .filter { it.isDirectory }
                .sortedBy { it.name.lowercase(Locale.ROOT) }
                .forEach { queue.add(it) }
        }
        return null
    }

    private fun isModelWeightFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return ext in MODEL_FILE_EXTENSIONS
    }

    private fun isIgnoredSystemEntry(name: String): Boolean {
        return name == "__MACOSX" ||
            name == ".DS_Store" ||
            name == READY_MARKER_NAME ||
            name == TEMP_ZIP_NAME ||
            name == CATALOG_FILE_NAME
    }

    fun calculateDirectorySize(dir: File): Long {
        if (!dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        var total = 0L
        dir.walkTopDown().forEach { entry ->
            if (entry.isFile && !isIgnoredSystemEntry(entry.name)) {
                total += entry.length()
            }
        }
        return total
    }

    suspend fun downloadAndInstallBuiltinSd15(
        context: Context,
        onProgress: (ModelDownloadProgress) -> Unit
    ): AiModelCatalogState {
        return downloadAndInstallFromUrl(
            context = context,
            displayName = BUILTIN_SD15_NAME,
            rawUrl = MODEL_ZIP_URL,
            fixedId = BUILTIN_SD15_ID,
            expectedApproximateBytes = APPROXIMATE_MODEL_BYTES,
            onProgress = onProgress
        )
    }

    suspend fun downloadAndInstallFromUrl(
        context: Context,
        displayName: String,
        rawUrl: String,
        fixedId: String? = null,
        expectedApproximateBytes: Long = APPROXIMATE_MODEL_BYTES,
        onProgress: (ModelDownloadProgress) -> Unit
    ): AiModelCatalogState = withContext(Dispatchers.IO) {
        val cleanName = displayName.trim().ifEmpty {
            deriveNameFromUrl(rawUrl) ?: "Custom AI Model"
        }
        val normalizedUrl = normalizeModelDownloadUrl(rawUrl)
        val rootDir = aiModelsBaseDir(context)
        if (!rootDir.exists() && !rootDir.mkdirs()) {
            throw IOException("Unable to create ai_models directory.")
        }

        val currentCatalog = loadCatalogFromDir(rootDir)
        val modelId = fixedId?.let(::sanitizeIdSegment)
            ?: generateModelId(cleanName, currentCatalog.models.map { it.id }.toSet())
        val finalModelDir = File(rootDir, modelId)

        if (fixedId != null && resolveModelDirectory(finalModelDir) != null) {
            val updated = setActiveModelInDir(rootDir, modelId)
            onProgress(
                ModelDownloadProgress(
                    phase = ModelInstallPhase.READY,
                    targetLabel = cleanName,
                    bytesProcessed = expectedApproximateBytes,
                    totalBytes = expectedApproximateBytes,
                    extractPercent = 100
                )
            )
            return@withContext updated
        }

        val stagingDir = File(rootDir, ".staging_${modelId}_${System.nanoTime()}")
        val tempZip = File(context.cacheDir, "${TEMP_ZIP_NAME}_${modelId}")
        if (tempZip.exists()) tempZip.delete()
        if (stagingDir.exists()) stagingDir.deleteRecursively()

        try {
            downloadFileWithRedirects(
                sourceUrl = normalizedUrl,
                destFile = tempZip,
                freeSpaceAnchor = rootDir,
                fallbackApproxBytes = expectedApproximateBytes,
                onDownloadProgress = { downloaded, total ->
                    onProgress(
                        ModelDownloadProgress(
                            phase = ModelInstallPhase.DOWNLOADING,
                            targetLabel = cleanName,
                            bytesProcessed = downloaded,
                            totalBytes = total
                        )
                    )
                }
            )

            onProgress(
                ModelDownloadProgress(
                    phase = ModelInstallPhase.EXTRACTING,
                    targetLabel = cleanName,
                    bytesProcessed = tempZip.length(),
                    totalBytes = max(tempZip.length(), 1L),
                    extractPercent = 0
                )
            )

            unzipSafely(
                zipFile = tempZip,
                destDir = stagingDir,
                onProgress = { pct ->
                    onProgress(
                        ModelDownloadProgress(
                            phase = ModelInstallPhase.EXTRACTING,
                            targetLabel = cleanName,
                            bytesProcessed = tempZip.length(),
                            totalBytes = max(tempZip.length(), 1L),
                            extractPercent = pct
                        )
                    )
                }
            )

            if (tempZip.exists()) {
                tempZip.delete()
            }

            unwrapSingleChildDirectories(stagingDir)

            if (resolveModelDirectory(stagingDir) == null) {
                throw IOException("Archive did not contain valid MediaPipe Stable Diffusion model files (.tflite/.bin).")
            }

            File(stagingDir, READY_MARKER_NAME).writeText("ready")
            if (finalModelDir.exists()) {
                finalModelDir.deleteRecursively()
            }
            if (!stagingDir.renameTo(finalModelDir)) {
                throw IOException("Failed to finalize installed model folder for $cleanName.")
            }

            val actualSizeBytes = calculateDirectorySize(finalModelDir)
            val refreshed = loadCatalogFromDir(rootDir)
            val newEntry = InstalledAiModel(
                id = modelId,
                displayName = cleanName,
                sourceUrl = normalizedUrl,
                sizeBytes = actualSizeBytes
            )
            val updatedList = refreshed.models.filterNot { it.id == modelId } + newEntry
            val updatedCatalog = AiModelCatalogState(
                models = updatedList,
                activeModelId = modelId
            )
            saveCatalogToDir(rootDir, updatedCatalog)

            onProgress(
                ModelDownloadProgress(
                    phase = ModelInstallPhase.READY,
                    targetLabel = cleanName,
                    bytesProcessed = actualSizeBytes,
                    totalBytes = max(actualSizeBytes, 1L),
                    extractPercent = 100
                )
            )
            updatedCatalog
        } catch (t: Throwable) {
            runCatching { stagingDir.deleteRecursively() }
            runCatching { tempZip.delete() }
            throw t
        } finally {
            if (tempZip.exists()) {
                runCatching { tempZip.delete() }
            }
        }
    }

    /**
     * Imports a MediaPipe Stable Diffusion `.zip` bundle selected from local phone storage via
     * Android's Document Picker (`OpenDocument`), streaming directly into `filesDir/ai_models/<id>/`.
     */
    suspend fun importLocalZip(
        context: Context,
        uri: Uri,
        customDisplayName: String? = null,
        onProgress: (ModelDownloadProgress) -> Unit
    ): AiModelCatalogState = withContext(Dispatchers.IO) {
        val queriedMeta = queryUriNameAndSize(context, uri)
        val rawName = customDisplayName?.trim()?.takeIf { it.isNotEmpty() }
            ?: queriedMeta.first
            ?: "Local AI Model"
        val cleanName = rawName.removeSuffix(".zip").removeSuffix(".ZIP").trim().ifEmpty { "Local AI Model" }
        val expectedZipBytes = queriedMeta.second.takeIf { it > 0L } ?: APPROXIMATE_MODEL_BYTES

        val rootDir = aiModelsBaseDir(context)
        if (!rootDir.exists() && !rootDir.mkdirs()) {
            throw IOException("Unable to create ai_models directory.")
        }

        val freeBytes = availableFreeBytes(rootDir)
        val neededBytes = if (queriedMeta.second > 0L) queriedMeta.second + 128L * 1024L * 1024L else 1_500_000_000L
        if (freeBytes < neededBytes) {
            throw IOException("Not enough free storage (${formatBytes(freeBytes)} available) to extract $cleanName.")
        }

        val currentCatalog = loadCatalogFromDir(rootDir)
        val modelId = generateModelId(cleanName, currentCatalog.models.map { it.id }.toSet())
        val stagingDir = File(rootDir, ".staging_${modelId}_${System.nanoTime()}")
        val finalModelDir = File(rootDir, modelId)

        try {
            onProgress(
                ModelDownloadProgress(
                    phase = ModelInstallPhase.EXTRACTING,
                    targetLabel = cleanName,
                    bytesProcessed = 0L,
                    totalBytes = expectedZipBytes,
                    extractPercent = 0
                )
            )

            val inputStream = context.contentResolver.openInputStream(uri)
                ?: throw IOException("Unable to open selected ZIP file.")

            inputStream.use { rawStream ->
                unzipStreamSafely(
                    inputStream = rawStream,
                    totalBytes = expectedZipBytes,
                    destDir = stagingDir,
                    onProgress = { pct, bytesRead ->
                        onProgress(
                            ModelDownloadProgress(
                                phase = ModelInstallPhase.EXTRACTING,
                                targetLabel = cleanName,
                                bytesProcessed = bytesRead,
                                totalBytes = max(expectedZipBytes, bytesRead),
                                extractPercent = pct
                            )
                        )
                    }
                )
            }

            unwrapSingleChildDirectories(stagingDir)

            if (resolveModelDirectory(stagingDir) == null) {
                throw IOException("Selected ZIP does not contain valid MediaPipe Stable Diffusion model files (.tflite/.bin).")
            }

            File(stagingDir, READY_MARKER_NAME).writeText("ready")
            if (finalModelDir.exists()) {
                finalModelDir.deleteRecursively()
            }
            if (!stagingDir.renameTo(finalModelDir)) {
                throw IOException("Failed to save extracted model directory.")
            }

            val actualSizeBytes = calculateDirectorySize(finalModelDir)
            val refreshed = loadCatalogFromDir(rootDir)
            val newEntry = InstalledAiModel(
                id = modelId,
                displayName = cleanName,
                sourceUrl = "local://$rawName",
                sizeBytes = actualSizeBytes
            )
            val updatedCatalog = AiModelCatalogState(
                models = refreshed.models + newEntry,
                activeModelId = modelId
            )
            saveCatalogToDir(rootDir, updatedCatalog)

            onProgress(
                ModelDownloadProgress(
                    phase = ModelInstallPhase.READY,
                    targetLabel = cleanName,
                    bytesProcessed = actualSizeBytes,
                    totalBytes = max(actualSizeBytes, 1L),
                    extractPercent = 100
                )
            )
            updatedCatalog
        } catch (t: Throwable) {
            runCatching { stagingDir.deleteRecursively() }
            throw t
        }
    }

    private fun queryUriNameAndSize(context: Context, uri: Uri): Pair<String?, Long> {
        var name: String? = null
        var size = -1L
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0) name = cursor.getString(nameIdx)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
                }
            }
        }
        return name to size
    }

    private fun deriveNameFromUrl(rawUrl: String): String? {
        return runCatching {
            val seg = URL(rawUrl.trim()).path.substringAfterLast('/').substringBefore('?')
            seg.removeSuffix(".zip").removeSuffix(".ZIP").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun availableFreeBytes(anchorDir: File): Long {
        return runCatching {
            var dir: File? = anchorDir
            while (dir != null && !dir.exists()) {
                dir = dir.parentFile
            }
            val target = dir ?: return Long.MAX_VALUE
            StatFs(target.absolutePath).availableBytes
        }.getOrDefault(Long.MAX_VALUE)
    }

    private suspend fun downloadFileWithRedirects(
        sourceUrl: String,
        destFile: File,
        freeSpaceAnchor: File,
        fallbackApproxBytes: Long,
        onDownloadProgress: (bytesRead: Long, totalBytes: Long) -> Unit
    ) {
        val coroutineContext = currentCoroutineContext()
        var currentUrl = sourceUrl
        var connection: HttpURLConnection? = null
        var redirectCount = 0

        try {
            while (true) {
                coroutineContext.ensureActive()
                val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 20_000
                    readTimeout = 30_000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "ZamLab-Android/1.0")
                    setRequestProperty("Accept", "*/*")
                }
                connection = conn
                val code = conn.responseCode
                if (code in setOf(301, 302, 303, 307, 308)) {
                    val location = conn.getHeaderField("Location")
                        ?: throw IOException("Redirect ($code) missing Location header.")
                    conn.disconnect()
                    connection = null
                    redirectCount++
                    if (redirectCount > MAX_HTTP_REDIRECTS) {
                        throw IOException("Too many HTTP redirects while downloading AI model.")
                    }
                    currentUrl = URL(URL(currentUrl), location).toString()
                    continue
                }
                if (code !in 200..299) {
                    throw IOException("Model download failed with HTTP $code.")
                }
                break
            }

            val activeConn = connection ?: throw IOException("Failed to open download connection.")
            val contentType = activeConn.contentType?.lowercase(Locale.ROOT).orEmpty()
            if (contentType.contains("text/html")) {
                throw IOException("Link returned an HTML web page instead of a .zip archive. Check the URL or use a Hugging Face /resolve/ or GitHub /raw/ link.")
            }

            val exactLength = activeConn.contentLengthLong
            val contentLength = exactLength.takeIf { it > 0L } ?: fallbackApproxBytes
            val minNeeded = if (exactLength > 0L) {
                exactLength * 2L + 128L * 1024L * 1024L
            } else {
                DEFAULT_MIN_FREE_SPACE_BYTES
            }
            val freeBytes = availableFreeBytes(freeSpaceAnchor)
            if (freeBytes < minNeeded) {
                throw IOException("Not enough free storage (${formatBytes(freeBytes)} available). At least ${formatBytes(minNeeded)} needed.")
            }

            destFile.parentFile?.mkdirs()
            val buffer = ByteArray(64 * 1024)
            var downloaded = 0L
            var lastEmitTimeMs = 0L
            onDownloadProgress(0L, contentLength)

            BufferedInputStream(activeConn.inputStream, 64 * 1024).use { input ->
                FileOutputStream(destFile).use { output ->
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        if (now - lastEmitTimeMs >= 120L || downloaded >= contentLength) {
                            lastEmitTimeMs = now
                            onDownloadProgress(downloaded, max(contentLength, downloaded))
                        }
                    }
                    output.flush()
                }
            }
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Unzips [zipFile] into [destDir] safely with Zip Slip protection and nested directory creation.
     */
    fun unzipSafely(
        zipFile: File,
        destDir: File,
        onProgress: (percent: Int) -> Unit = {}
    ) {
        val totalZipBytes = max(zipFile.length(), 1L)
        FileInputStream(zipFile).use { fis ->
            unzipStreamSafely(
                inputStream = fis,
                totalBytes = totalZipBytes,
                destDir = destDir,
                onProgress = { pct, _ -> onProgress(pct) }
            )
        }
    }

    fun unzipStreamSafely(
        inputStream: InputStream,
        totalBytes: Long,
        destDir: File,
        onProgress: (percent: Int, bytesRead: Long) -> Unit = { _, _ -> }
    ) {
        if (!destDir.exists()) {
            destDir.mkdirs()
        }
        val destCanonical = destDir.canonicalPath
        val safeTotalBytes = max(totalBytes, 1L)
        val buffer = ByteArray(64 * 1024)
        var lastPercent = -1
        var entryCount = 0

        val countingStream = CountingInputStream(BufferedInputStream(inputStream, 64 * 1024))
        ZipInputStream(countingStream).use { zis ->
            while (true) {
                if (Thread.currentThread().isInterrupted) {
                    throw InterruptedException("Model extraction cancelled.")
                }
                val entry = zis.nextEntry ?: break
                entryCount++
                val entryName = entry.name.replace('\\', '/').trimStart('/')
                if (entryName.isEmpty() ||
                    entryName.startsWith("__MACOSX") ||
                    entryName.endsWith("/.DS_Store") ||
                    entryName == ".DS_Store"
                ) {
                    zis.closeEntry()
                    continue
                }

                val outFile = File(destDir, entryName)
                val outCanonical = outFile.canonicalPath
                if (outCanonical != destCanonical &&
                    !outCanonical.startsWith(destCanonical + File.separator)
                ) {
                    throw SecurityException("Blocked unsafe zip entry path: ${entry.name}")
                }

                if (entry.isDirectory) {
                    if (!outFile.exists() && !outFile.mkdirs()) {
                        throw IOException("Failed to create directory: ${outFile.absolutePath}")
                    }
                } else {
                    val parent = outFile.parentFile
                    if (parent != null && !parent.exists() && !parent.mkdirs()) {
                        throw IOException("Failed to create parent directory: ${parent.absolutePath}")
                    }
                    FileOutputStream(outFile).use { fos ->
                        while (true) {
                            if (Thread.currentThread().isInterrupted) {
                                throw InterruptedException("Model extraction cancelled.")
                            }
                            val count = zis.read(buffer)
                            if (count == -1) break
                            fos.write(buffer, 0, count)
                        }
                        fos.flush()
                    }
                }
                zis.closeEntry()

                val pct = ((countingStream.bytesRead.toDouble() / safeTotalBytes.toDouble()) * 100.0)
                    .roundToInt()
                    .coerceIn(0, 99)
                if (pct != lastPercent) {
                    lastPercent = pct
                    onProgress(pct, countingStream.bytesRead)
                }
            }
        }
        if (entryCount == 0) {
            throw IOException("File is not a valid ZIP archive.")
        }
        onProgress(100, countingStream.bytesRead)
    }

    /**
     * Flattens redundant single-child wrapper folders (for example `sd15-mediapipe/bins/...`)
     * directly into [rootDir].
     */
    fun unwrapSingleChildDirectories(rootDir: File) {
        if (!rootDir.exists() || !rootDir.isDirectory) return
        while (true) {
            val entries = rootDir.listFiles()?.filter { !isIgnoredSystemEntry(it.name) }.orEmpty()
            if (entries.size != 1 || !entries[0].isDirectory) break
            val nestedDir = entries[0]
            val tempDir = File(rootDir.parentFile ?: rootDir, ".zamlab_unwrap_${System.nanoTime()}")
            if (tempDir.exists()) tempDir.deleteRecursively()
            if (!nestedDir.renameTo(tempDir)) {
                break
            }
            val nestedChildren = tempDir.listFiles().orEmpty()
            var movedAll = true
            for (child in nestedChildren) {
                val target = File(rootDir, child.name)
                if (!child.renameTo(target)) {
                    movedAll = false
                    break
                }
            }
            if (movedAll) {
                tempDir.deleteRecursively()
            } else {
                rootDir.listFiles()?.forEach { moved ->
                    if (!isIgnoredSystemEntry(moved.name)) {
                        moved.renameTo(File(tempDir, moved.name))
                    }
                }
                tempDir.renameTo(nestedDir)
                break
            }
        }
    }

    fun formatBytes(bytes: Long): String {
        val clamped = bytes.coerceAtLeast(0L)
        val gb = clamped / (1024.0 * 1024.0 * 1024.0)
        return if (gb >= 1.0) {
            String.format(Locale.US, "%.2f GB", gb)
        } else {
            val mb = clamped / (1024.0 * 1024.0)
            String.format(Locale.US, "%.0f MB", mb)
        }
    }

    // Pure-Kotlin JSON catalog persistence (works identically on Android and pure-JVM unit tests)

    private fun serializeCatalogJson(catalog: AiModelCatalogState): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"activeModelId\": ")
        if (catalog.activeModelId == null) {
            sb.append("null")
        } else {
            sb.append('"').append(escapeJsonString(catalog.activeModelId)).append('"')
        }
        sb.append(",\n  \"models\": [")
        catalog.models.forEachIndexed { index, model ->
            if (index > 0) sb.append(',')
            sb.append("\n    {")
            sb.append("\"id\":\"").append(escapeJsonString(model.id)).append("\",")
            sb.append("\"displayName\":\"").append(escapeJsonString(model.displayName)).append("\",")
            sb.append("\"sourceUrl\":\"").append(escapeJsonString(model.sourceUrl)).append("\",")
            sb.append("\"sizeBytes\":").append(model.sizeBytes)
            sb.append('}')
        }
        if (catalog.models.isNotEmpty()) sb.append('\n')
        sb.append("  ]\n}")
        return sb.toString()
    }

    private fun escapeJsonString(value: String): String {
        val out = StringBuilder(value.length)
        for (ch in value) {
            when (ch) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> out.append(ch)
            }
        }
        return out.toString()
    }

    internal fun parseCatalogJson(raw: String): AiModelCatalogState {
        val root = JsonMiniParser(raw).parseValue() as? Map<*, *> ?: return AiModelCatalogState()
        val activeId = root["activeModelId"] as? String
        val modelsRaw = root["models"] as? List<*> ?: emptyList<Any?>()
        val models = modelsRaw.mapNotNull { item ->
            val obj = item as? Map<*, *> ?: return@mapNotNull null
            val id = (obj["id"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val displayName = (obj["displayName"] as? String)?.takeIf { it.isNotBlank() } ?: id
            val sourceUrl = (obj["sourceUrl"] as? String).orEmpty()
            val sizeBytes = (obj["sizeBytes"] as? Number)?.toLong() ?: 0L
            InstalledAiModel(
                id = id,
                displayName = displayName,
                sourceUrl = sourceUrl,
                sizeBytes = sizeBytes
            )
        }
        return AiModelCatalogState(models = models, activeModelId = activeId)
    }

    private class JsonMiniParser(private val text: String) {
        private var pos = 0

        fun parseValue(): Any? {
            skipWhitespace()
            if (pos >= text.length) return null
            return when (val ch = text[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                'n' -> parseNull()
                't', 'f' -> parseBoolean()
                '-', in '0'..'9' -> parseNumber()
                else -> throw IllegalArgumentException("Unexpected JSON token '$ch' at $pos")
            }
        }

        private fun parseObject(): Map<String, Any?> {
            pos++ // '{'
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (pos < text.length && text[pos] == '}') {
                pos++
                return map
            }
            while (pos < text.length) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                if (pos >= text.length || text[pos] != ':') {
                    throw IllegalArgumentException("Expected ':' at $pos")
                }
                pos++
                map[key] = parseValue()
                skipWhitespace()
                if (pos < text.length && text[pos] == ',') {
                    pos++
                    continue
                }
                if (pos < text.length && text[pos] == '}') {
                    pos++
                    return map
                }
                throw IllegalArgumentException("Expected ',' or '}' at $pos")
            }
            return map
        }

        private fun parseArray(): List<Any?> {
            pos++ // '['
            val list = ArrayList<Any?>()
            skipWhitespace()
            if (pos < text.length && text[pos] == ']') {
                pos++
                return list
            }
            while (pos < text.length) {
                list.add(parseValue())
                skipWhitespace()
                if (pos < text.length && text[pos] == ',') {
                    pos++
                    continue
                }
                if (pos < text.length && text[pos] == ']') {
                    pos++
                    return list
                }
                throw IllegalArgumentException("Expected ',' or ']' at $pos")
            }
            return list
        }

        private fun parseString(): String {
            if (pos >= text.length || text[pos] != '"') {
                throw IllegalArgumentException("Expected '\"' at $pos")
            }
            pos++
            val sb = StringBuilder()
            while (pos < text.length) {
                val ch = text[pos++]
                when (ch) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= text.length) break
                        val esc = text[pos++]
                        when (esc) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 <= text.length) {
                                    val code = text.substring(pos, pos + 4).toInt(16)
                                    sb.append(code.toChar())
                                    pos += 4
                                }
                            }
                            else -> sb.append(esc)
                        }
                    }
                    else -> sb.append(ch)
                }
            }
            throw IllegalArgumentException("Unterminated string literal")
        }

        private fun parseNumber(): Number {
            val start = pos
            while (pos < text.length) {
                val ch = text[pos]
                if (ch in '0'..'9' || ch == '-' || ch == '+' || ch == '.' || ch == 'e' || ch == 'E') {
                    pos++
                } else {
                    break
                }
            }
            val token = text.substring(start, pos)
            return if (token.contains('.') || token.contains('e') || token.contains('E')) {
                token.toDouble()
            } else {
                token.toLong()
            }
        }

        private fun parseNull(): Any? {
            if (text.startsWith("null", pos)) {
                pos += 4
                return null
            }
            throw IllegalArgumentException("Invalid null literal at $pos")
        }

        private fun parseBoolean(): Boolean {
            return if (text.startsWith("true", pos)) {
                pos += 4
                true
            } else if (text.startsWith("false", pos)) {
                pos += 5
                false
            } else {
                throw IllegalArgumentException("Invalid boolean literal at $pos")
            }
        }

        private fun skipWhitespace() {
            while (pos < text.length && text[pos].isWhitespace()) {
                pos++
            }
        }
    }

    private class CountingInputStream(
        delegate: BufferedInputStream
    ) : FilterInputStream(delegate) {
        var bytesRead: Long = 0L
            private set

        override fun read(): Int {
            val b = super.read()
            if (b != -1) bytesRead++
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val count = super.read(b, off, len)
            if (count > 0) bytesRead += count
            return count
        }

        override fun skip(n: Long): Long {
            val skipped = super.skip(n)
            if (skipped > 0) bytesRead += skipped
            return skipped
        }
    }
}
