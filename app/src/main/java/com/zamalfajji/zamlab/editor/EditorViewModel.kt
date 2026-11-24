package com.zamalfajji.zamlab.editor

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.core.content.edit
import androidx.core.graphics.scale
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zamalfajji.zamlab.gl.GlOffscreenRenderer
import com.zamalfajji.zamlab.lut.CubeLut
import com.zamalfajji.zamlab.lut.LutImporter
import com.zamalfajji.zamlab.lut.LutSourceFormat
import com.zamalfajji.zamlab.lut.PresetDescriptor
import com.zamalfajji.zamlab.lut.PresetLocationType
import com.zamalfajji.zamlab.lut.UserPreset
import com.zamalfajji.zamlab.lut.UserPresetStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

private const val TAG = "EditorViewModel"
private const val DEFAULT_LUT_INTENSITY = 1.0f

enum class StudioTab {
    ADJUST,
    ALL,
    LUTS,
    CROP,
    HEAL,
    MASK
}

enum class EyedropperMode {
    NONE,
    WHITE_BALANCE,
    POINT_COLOR
}

@Immutable
data class LutPresetItem(
    val id: String,
    val title: String,
    val category: String,
    val sourceFormat: LutSourceFormat,
    val isCustom: Boolean,
    val lut: CubeLut? = null,
    val previewThumbnail: Bitmap? = null
)

@Immutable
data class LutFolderGroup(
    val name: String,
    val isCustomFolder: Boolean,
    val descriptors: List<PresetDescriptor>,
    val items: List<LutPresetItem> = emptyList(),
    val isExpanded: Boolean = false,
    val isLoading: Boolean = false,
    val isLoadedForCurrentPhoto: Boolean = false
) {
    val totalCount: Int get() = descriptors.size
}

@Immutable
sealed interface ExportStatus {
    data object Idle : ExportStatus
    data object Exporting : ExportStatus
    data class Success(
        val filename: String,
        val width: Int,
        val height: Int,
        val uri: Uri,
        val mimeType: String,
        val format: ExportFormat,
        val destinationLabel: String,
        val openShareImmediately: Boolean = false,
        val eventId: Long = System.nanoTime()
    ) : ExportStatus {
        val snackbarMessage: String
            get() = format.savedSnackbarMessage
    }
    data class Error(val message: String) : ExportStatus
}

data class EditorSnapshot(
    val sourceBitmap: Bitmap,
    val gradingParams: GradingParams,
    val selectedLutId: String?,
    val healStrokes: List<HealStroke>,
    val label: String,
    val selectedUserPresetId: String? = null
)

data class CopiedEdits(
    val gradingParams: GradingParams,
    val selectedLutId: String?,
    val selectedUserPresetId: String? = null
)

@Immutable
data class EditorUiState(
    val activeTab: StudioTab = StudioTab.ADJUST,
    val photoName: String = "Lisbon Terrace (Sample)",
    val photoFormatInfo: PhotoFormatInfo = PhotoFormatInfo(),
    val fullWidth: Int = 0,
    val fullHeight: Int = 0,
    val previewIdleBitmap: Bitmap? = null,
    val previewDragBitmap: Bitmap? = null,
    val isDraggingSlider: Boolean = false,
    val histogram: HistogramData? = null,
    val gradingParams: GradingParams = GradingParams(),
    val lutFolders: List<LutFolderGroup> = emptyList(),
    val selectedLutId: String? = null,
    val activeLut: CubeLut? = null,
    val userPresets: List<UserPreset> = emptyList(),
    val userPresetThumbnails: Map<String, Bitmap> = emptyMap(),
    val selectedUserPresetId: String? = null,
    val recentProfileIds: List<String> = emptyList(),
    val favoriteProfileIds: Set<String> = emptySet(),
    val profileThumbnails: Map<String, Bitmap> = emptyMap(),
    val isLoadingProfileThumbnails: Boolean = false,
    val activeEyedropperMode: EyedropperMode = EyedropperMode.NONE,
    val selectedMaskId: String? = null,
    val showMaskOverlay: Boolean = true,
    val preHealPreviewBitmap: Bitmap? = null,
    val canPasteEdits: Boolean = false,
    val isComparingOriginal: Boolean = false,
    val isSplitCompareEnabled: Boolean = false,
    val splitCompareRatio: Float = 0.5f,
    val showHistogram: Boolean = true,
    val showHighlightClipping: Boolean = false,
    val showShadowClipping: Boolean = false,
    val cropOverlayMode: CropOverlayMode = CropOverlayMode.THIRDS,
    val healMode: HealMode = HealMode.SPOT_HEAL,
    val healBrushSizePx: Float = OfflineHealEngine.DEFAULT_BRUSH_SIZE_PX,
    val healFeather: Float = OfflineHealEngine.DEFAULT_FEATHER,
    val healOpacity: Float = OfflineHealEngine.DEFAULT_OPACITY,
    val healStrokes: List<HealStroke> = emptyList(),
    val activeHealStroke: HealStroke? = null,
    val showHealMask: Boolean = false,
    val cloneSourcePoint: Pair<Float, Float>? = null,
    val isPickingCloneSource: Boolean = false,
    val isHealProcessing: Boolean = false,
    val canUndoHealStroke: Boolean = false,
    val canRedoHealStroke: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val undoLabel: String? = null,
    val redoLabel: String? = null,
    val viewportResetToken: Long = 0L,
    val exportStatus: ExportStatus = ExportStatus.Idle,
    val exportConfirmationToast: ExportStatus.Success? = null,
    val statusBannerMessage: String? = null,
    val isTouchAdjustEnabled: Boolean = false,
    val touchAdjustSelectedBand: HslBand? = null,
    val touchAdjustSwatchArgb: Long? = null,
    val touchAdjustHudLabel: String? = null,
    val touchAdjustHudValueText: String? = null,
    val isTouchAdjustSwiping: Boolean = false
) {
    val healBrushRadiusNorm: Float
        get() = OfflineHealEngine.brushSizePxToRadiusNorm(healBrushSizePx)

    val hasHealEdits: Boolean
        get() = healStrokes.isNotEmpty() || preHealPreviewBitmap != null

    val chromaticAberration: Float
        get() = gradingParams.chromaticAberration

    val anamorphicStreak: Float
        get() = gradingParams.anamorphicStreak

    val activeMask: LocalMask?
        get() = gradingParams.masks.firstOrNull { it.id == selectedMaskId }
            ?: gradingParams.masks.firstOrNull()

    val effectiveOutputWidth: Int
        get() = if (fullWidth > 0 && fullHeight > 0) {
            GeometryMath.computeOutputDimensions(fullWidth, fullHeight, gradingParams.crop).first
        } else {
            fullWidth
        }

    val effectiveOutputHeight: Int
        get() = if (fullWidth > 0 && fullHeight > 0) {
            GeometryMath.computeOutputDimensions(fullWidth, fullHeight, gradingParams.crop).second
        } else {
            fullHeight
        }

    /**
     * Single GPU-resident proxy texture (<=2048px) kept steady across idle and slider drags,
     * eliminating GPU texture re-uploads on every drag start/stop for smooth 120Hz scrubbing.
     */
    val activePreviewTexture: Bitmap?
        get() = when {
            isComparingOriginal && preHealPreviewBitmap != null -> preHealPreviewBitmap
            else -> previewIdleBitmap
        }

    val selectedPresetTitle: String?
        get() = userPresets.firstOrNull { it.id == selectedUserPresetId }?.name
            ?: activeLut?.title
            ?: lutFolders.asSequence()
                .flatMap { it.descriptors.asSequence() }
                .firstOrNull { it.id == selectedLutId }?.title

    val totalPresetCount: Int
        get() = lutFolders.sumOf { it.totalCount } + userPresets.size
}

class EditorViewModel(
    application: Application
) : AndroidViewModel(application) {

    private data class HealHistoryState(
        val bitmap: Bitmap,
        val strokes: List<HealStroke>
    )

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    // Dual-resolution proxy pipeline state: untouched full-res source ref + <=2048px GPU proxy
    private var activePhotoSourceRef: PhotoSourceRef? = null
    private var activeCachedImportFile: File? = null
    private var nativePhotoWidth: Int = 0
    private var nativePhotoHeight: Int = 0
    private val fullSourceBitmap = MutableStateFlow<Bitmap?>(null) // <=2048px proxy for real-time editing
    private val histSourceBitmap = MutableStateFlow<Bitmap?>(null)
    private var initialPhotoSourceBitmap: Bitmap? = null
    private var initialPhotoPreviewIdle: Bitmap? = null
    private val gradingInput = MutableStateFlow(Pair(GradingParams(), null as CubeLut?))
    private val isDraggingFlow = MutableStateFlow(false)
    private val healUndoStack = ArrayDeque<HealHistoryState>()
    private val healRedoStack = ArrayDeque<HealHistoryState>()
    private val undoStack = ArrayDeque<EditorSnapshot>()
    private val redoStack = ArrayDeque<EditorSnapshot>()
    private var pendingDragSnapshot: EditorSnapshot? = null
    private var copiedEdits: CopiedEdits? = null
    private val maxHistorySize = 40

    // Lazy folder catalog + memory caches
    private val descriptorById = ConcurrentHashMap<String, PresetDescriptor>()
    private val parsedLutCache = ConcurrentHashMap<String, CubeLut>()
    private val thumbnailCache = ConcurrentHashMap<String, Bitmap>()
    private val userPresetThumbnailCache = ConcurrentHashMap<String, Bitmap>()
    private val profileThumbnailCache = ConcurrentHashMap<String, Bitmap>()
    private val folderLoadJobs = ConcurrentHashMap<String, Job>()
    private var profileThumbJob: Job? = null
    private var userPresetThumbJob: Job? = null
    private var histPrepareJob: Job? = null
    private var healJob: Job? = null
    private val photoGeneration = AtomicLong(0L)
    private val photoLoadRequestSeq = AtomicLong(0L)
    private val lutSelectionSeq = AtomicLong(0L)
    private val hasExternalOrUserPhoto = AtomicBoolean(false)
    private val maskIdCounter = AtomicLong(0L)
    private var touchSwipeZone: TouchToneZone = TouchToneZone.MIDTONES
    private var touchSwipeStartVal: Float = 0f
    private var touchSwipeStartSat: Float = 0f
    private var touchSwipeSnapshot: EditorSnapshot? = null

    init {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                loadProfilePreferences()
                loadClipboardPreferences()
            }
        }
        loadStarterLutsAndSamplePhoto()
        observeHistogramPipeline()
    }

    override fun onCleared() {
        super.onCleared()
        folderLoadJobs.values.forEach { it.cancel() }
        folderLoadJobs.clear()
        profileThumbJob?.cancel()
        profileThumbJob = null
        userPresetThumbJob?.cancel()
        userPresetThumbJob = null
        histPrepareJob?.cancel()
        histPrepareJob = null
        healJob?.cancel()
        healJob = null

        val toRecycle = Collections.newSetFromMap(IdentityHashMap<Bitmap, Boolean>())
        toRecycle.addAll(thumbnailCache.values)
        toRecycle.addAll(userPresetThumbnailCache.values)
        toRecycle.addAll(profileThumbnailCache.values)
        histSourceBitmap.value?.let { toRecycle.add(it) }
        fullSourceBitmap.value?.let { toRecycle.add(it) }
        initialPhotoSourceBitmap?.let { toRecycle.add(it) }
        undoStack.forEach { toRecycle.add(it.sourceBitmap) }
        redoStack.forEach { toRecycle.add(it.sourceBitmap) }
        healUndoStack.forEach { toRecycle.add(it.bitmap) }
        healRedoStack.forEach { toRecycle.add(it.bitmap) }

        thumbnailCache.clear()
        userPresetThumbnailCache.clear()
        profileThumbnailCache.clear()
        parsedLutCache.clear()
        undoStack.clear()
        redoStack.clear()
        healUndoStack.clear()
        healRedoStack.clear()
        fullSourceBitmap.value = null
        histSourceBitmap.value = null
        initialPhotoSourceBitmap = null
        initialPhotoPreviewIdle = null

        toRecycle.forEach { bmp ->
            if (!bmp.isRecycled) {
                bmp.recycle()
            }
        }

        activeCachedImportFile?.let { file ->
            runCatching { file.delete() }
        }
        activeCachedImportFile = null
    }

    private fun replaceActiveCachedImportFile(nextFile: File?) {
        val prev = activeCachedImportFile
        if (prev != null && prev != nextFile) {
            runCatching { prev.delete() }
        }
        activeCachedImportFile = nextFile
    }

    private fun profilePrefs() =
        getApplication<Application>().getSharedPreferences("zamlight_profile_prefs", Context.MODE_PRIVATE)

    private fun loadProfilePreferences() {
        val prefs = profilePrefs()
        val recentRaw = prefs.getString("recent_profiles", "").orEmpty()
        val recents = recentRaw.split(",")
            .map { it.trim() }
            .filter { id -> id.isNotEmpty() && CameraProfiles.ALL.any { it.id == id } }
            .distinct()
            .take(6)
        val favs = prefs.getStringSet("favorite_profiles", emptySet()).orEmpty()
            .filter { id -> CameraProfiles.ALL.any { it.id == id } }
            .toSet()
        _uiState.update {
            it.copy(
                recentProfileIds = recents,
                favoriteProfileIds = favs
            )
        }
    }

    private fun persistRecentProfiles(recents: List<String>) {
        profilePrefs().edit {
            putString("recent_profiles", recents.joinToString(","))
        }
    }

    private fun persistFavoriteProfiles(favorites: Set<String>) {
        profilePrefs().edit {
            putStringSet("favorite_profiles", favorites)
        }
    }

    private fun loadClipboardPreferences() {
        val persisted = UserPresetStore.loadClipboardEdits(getApplication())
        if (persisted != null) {
            copiedEdits = persisted
            _uiState.update { it.copy(canPasteEdits = true) }
        }
    }

    private fun loadStarterLutsAndSamplePhoto() {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val starterRef = PhotoSourceRef.ProceduralStudioChart
            val sampleDeferred = async {
                runCatching { ImageIoHelper.loadProceduralStudioStarterProxy() }
            }
            // Fast directory index only — zero .xmp / .cube parsing or thumbnail rendering at startup
            val catalogDeferred = async { LutImporter.indexPresetCatalog(ctx) }
            val userPresetsDeferred = async { UserPresetStore.loadPresets(ctx) }

            val (bundledDescriptors, customDescriptors) = catalogDeferred.await()
            val savedUserPresets = userPresetsDeferred.await()
            val allDescriptors = customDescriptors + bundledDescriptors
            descriptorById.clear()
            allDescriptors.forEach { descriptorById[it.id] = it }

            val folders = buildFolderGroups(
                descriptors = allDescriptors,
                previousExpanded = emptySet()
            )
            _uiState.update { it.copy(lutFolders = folders, userPresets = savedUserPresets) }

            sampleDeferred.await()
                .onSuccess { decoded ->
                    // Never overwrite a photo opened via Share sheet / "Open with" / Photo Picker
                    if (hasExternalOrUserPhoto.get() || photoLoadRequestSeq.get() > 0L) {
                        if (!decoded.proxyBitmap.isRecycled) {
                            decoded.proxyBitmap.recycle()
                        }
                        return@onSuccess
                    }
                    activePhotoSourceRef = starterRef
                    nativePhotoWidth = decoded.nativeWidth
                    nativePhotoHeight = decoded.nativeHeight
                    applyLoadedSourceBitmap(
                        bitmap = decoded.proxyBitmap,
                        name = "Studio Calibration Chart",
                        clearHealSession = true,
                        nativeWidth = decoded.nativeWidth,
                        nativeHeight = decoded.nativeHeight
                    )
                }
                .onFailure { e ->
                    Log.e(TAG, "Failed to load sample photo", e)
                }
        }
    }

    private fun buildFolderGroups(
        descriptors: List<PresetDescriptor>,
        previousExpanded: Set<String>
    ): List<LutFolderGroup> {
        val grouped = descriptors.groupBy { it.category.ifBlank { "Presets" } }
        val sortedNames = grouped.keys.sortedWith(
            compareBy<String>(
                { name ->
                    val groupList = grouped[name].orEmpty()
                    when {
                        groupList.any { it.isCustom } -> 0
                        name == "Built-in LUTs" -> 1
                        else -> 2
                    }
                },
                { it.lowercase() }
            )
        )
        return sortedNames.map { folderName ->
            val folderDescriptors = grouped[folderName].orEmpty()
            val isCustom = folderDescriptors.any { it.isCustom }
            val isExpanded = folderName in previousExpanded
            val allCachedForFolder = folderDescriptors.all { desc ->
                parsedLutCache.containsKey(desc.id) && thumbnailCache.containsKey(desc.id)
            }
            val items = folderDescriptors.map { desc ->
                val cachedLut = parsedLutCache[desc.id]
                LutPresetItem(
                    id = desc.id,
                    title = cachedLut?.title ?: desc.title,
                    category = folderName,
                    sourceFormat = desc.sourceFormat,
                    isCustom = desc.isCustom,
                    lut = cachedLut,
                    previewThumbnail = thumbnailCache[desc.id]
                )
            }
            LutFolderGroup(
                name = folderName,
                isCustomFolder = isCustom,
                descriptors = folderDescriptors,
                items = items,
                isExpanded = isExpanded,
                isLoading = false,
                isLoadedForCurrentPhoto = allCachedForFolder
            )
        }
    }

    private fun observeHistogramPipeline() {
        viewModelScope.launch {
            combine(histSourceBitmap, gradingInput) { histSource, (params, lut) ->
                Triple(histSource, params, lut)
            }
                .conflate()
                .collect { (histSource, params, lut) ->
                    if (histSource == null || histSource.isRecycled) return@collect
                    try {
                        val histogram = ColorGradingEngine.computePreviewHistogram(histSource, params, lut)
                        _uiState.update { it.copy(histogram = histogram) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Histogram update failed", e)
                    }
                }
        }
    }

    private fun scheduleHistogramSourcePrep(proxyBitmap: Bitmap, expectedToken: Long) {
        histPrepareJob?.cancel()
        histPrepareJob = viewModelScope.launch {
            val hist = withContext(Dispatchers.Default) {
                if (proxyBitmap.isRecycled) null
                else ImageIoHelper.createPreviewBitmap(proxyBitmap, ImageIoHelper.HISTOGRAM_DIMENSION)
            }
            if (hist != null && photoGeneration.get() == expectedToken) {
                histSourceBitmap.value = hist
            } else if (hist != null && hist !== proxyBitmap && !hist.isRecycled) {
                hist.recycle()
            }
        }
    }

    private fun applyLoadedSourceBitmap(
        bitmap: Bitmap,
        name: String,
        clearHealSession: Boolean,
        nextHealStrokes: List<HealStroke>? = null,
        nativeWidth: Int = nativePhotoWidth.takeIf { it > 0 } ?: bitmap.width,
        nativeHeight: Int = nativePhotoHeight.takeIf { it > 0 } ?: bitmap.height
    ) {
        if (bitmap.isRecycled) return

        if (clearHealSession) {
            healUndoStack.clear()
            healRedoStack.clear()
            undoStack.clear()
            redoStack.clear()
            pendingDragSnapshot = null
            nativePhotoWidth = nativeWidth
            nativePhotoHeight = nativeHeight
        }

        // Invalidate thumbnail cache for the new source photo (keep parsedLutCache & copiedEdits intact!)
        val token = photoGeneration.incrementAndGet()
        folderLoadJobs.values.forEach { it.cancel() }
        folderLoadJobs.clear()
        profileThumbJob?.cancel()
        profileThumbJob = null
        userPresetThumbJob?.cancel()
        userPresetThumbJob = null
        thumbnailCache.clear()
        userPresetThumbnailCache.clear()
        profileThumbnailCache.clear()

        // Single GPU-resident proxy texture (<=2048px): zero drag/idle texture swaps
        val proxyTexture = bitmap

        fullSourceBitmap.value = proxyTexture
        if (clearHealSession) {
            initialPhotoSourceBitmap = proxyTexture
            initialPhotoPreviewIdle = proxyTexture
        }
        val preHealPreview = if (proxyTexture !== initialPhotoSourceBitmap) initialPhotoPreviewIdle else null

        _uiState.update { state ->
            val updatedFolders = state.lutFolders.map { folder ->
                folder.copy(
                    isLoadedForCurrentPhoto = false,
                    isLoading = false,
                    items = folder.items.map { it.copy(previewThumbnail = null) }
                )
            }
            val resolvedHealStrokes = when {
                clearHealSession -> emptyList()
                nextHealStrokes != null -> nextHealStrokes
                else -> state.healStrokes
            }
            val nextParams = if (clearHealSession) GradingParams() else state.gradingParams
            val nextLutId = if (clearHealSession) null else state.selectedLutId
            val nextLut = if (clearHealSession) null else state.activeLut
            val nextUserPresetId = if (clearHealSession) null else state.selectedUserPresetId
            val nextSelectedMaskId = if (clearHealSession) null else state.selectedMaskId
            gradingInput.value = nextParams to nextLut
            state.copy(
                photoName = name,
                fullWidth = nativeWidth,
                fullHeight = nativeHeight,
                previewIdleBitmap = proxyTexture,
                previewDragBitmap = proxyTexture,
                preHealPreviewBitmap = preHealPreview,
                gradingParams = nextParams,
                selectedLutId = nextLutId,
                activeLut = nextLut,
                selectedUserPresetId = nextUserPresetId,
                selectedMaskId = nextSelectedMaskId,
                lutFolders = updatedFolders,
                userPresetThumbnails = emptyMap(),
                profileThumbnails = emptyMap(),
                isLoadingProfileThumbnails = false,
                activeEyedropperMode = EyedropperMode.NONE,
                healStrokes = resolvedHealStrokes,
                activeHealStroke = null,
                cloneSourcePoint = if (clearHealSession) null else state.cloneSourcePoint,
                isPickingCloneSource = if (clearHealSession) false else state.isPickingCloneSource,
                isHealProcessing = false,
                canUndoHealStroke = healUndoStack.isNotEmpty(),
                canRedoHealStroke = healRedoStack.isNotEmpty(),
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty(),
                undoLabel = undoStack.lastOrNull()?.label,
                redoLabel = redoStack.lastOrNull()?.label,
                viewportResetToken = if (clearHealSession) state.viewportResetToken + 1L else state.viewportResetToken,
                isTouchAdjustEnabled = if (clearHealSession) false else state.isTouchAdjustEnabled,
                touchAdjustSelectedBand = if (clearHealSession) null else state.touchAdjustSelectedBand,
                touchAdjustSwatchArgb = if (clearHealSession) null else state.touchAdjustSwatchArgb,
                touchAdjustHudLabel = null,
                touchAdjustHudValueText = null,
                isTouchAdjustSwiping = false
            )
        }

        // Prepare 160px histogram source on Dispatchers.Default (never blocking Main thread)
        scheduleHistogramSourcePrep(proxyTexture, token)

        if (_uiState.value.activeTab == StudioTab.LUTS) {
            ensureUserPresetThumbnailsLoaded()
            ensureDefaultLutFolderLoaded(token)
        } else {
            // Only refresh thumbnails for any folder that is currently open
            _uiState.value.lutFolders
                .filter { it.isExpanded }
                .forEach { loadFolderThumbnailsIfNeeded(it.name, token) }
        }
    }

    private fun ensureDefaultLutFolderLoaded(expectedPhotoToken: Long) {
        val state = _uiState.value
        val folders = state.lutFolders
        if (folders.isEmpty()) return
        val expanded = folders.filter { it.isExpanded }
        if (expanded.isNotEmpty()) {
            expanded.forEach { loadFolderThumbnailsIfNeeded(it.name, expectedPhotoToken) }
            return
        }
        val targetFolderName = folders.firstOrNull { f ->
            state.selectedLutId != null && f.descriptors.any { it.id == state.selectedLutId }
        }?.name ?: folders.first().name
        selectLutFolder(targetFolderName)
    }

    fun selectLutFolder(folderName: String) {
        val targetFolder = _uiState.value.lutFolders.firstOrNull { it.name == folderName } ?: return
        if (!targetFolder.isExpanded) {
            _uiState.update { state ->
                state.copy(
                    lutFolders = state.lutFolders.map { folder ->
                        if (folder.name == folderName) {
                            folder.copy(isExpanded = true)
                        } else {
                            folder
                        }
                    }
                )
            }
        }
        loadFolderThumbnailsIfNeeded(folderName, photoGeneration.get())
    }

    fun toggleLutFolder(folderName: String) {
        val targetFolder = _uiState.value.lutFolders.firstOrNull { it.name == folderName } ?: return
        val nextExpanded = !targetFolder.isExpanded

        _uiState.update { state ->
            state.copy(
                lutFolders = state.lutFolders.map { folder ->
                    if (folder.name == folderName) {
                        folder.copy(isExpanded = nextExpanded)
                    } else {
                        folder
                    }
                }
            )
        }

        if (nextExpanded) {
            loadFolderThumbnailsIfNeeded(folderName, photoGeneration.get())
        }
    }

    private fun loadFolderThumbnailsIfNeeded(folderName: String, expectedPhotoToken: Long) {
        val folder = _uiState.value.lutFolders.firstOrNull { it.name == folderName } ?: return
        val dragSource = _uiState.value.previewDragBitmap?.takeIf { !it.isRecycled }

        // Fast memory-cache hit: if all LUTs and thumbnails for this photo are already cached, use them immediately
        val allLutsCached = folder.descriptors.all { parsedLutCache.containsKey(it.id) }
        val allThumbsCached = dragSource == null || folder.descriptors.all { thumbnailCache.containsKey(it.id) }
        if (allLutsCached && allThumbsCached) {
            val cachedItems = folder.descriptors.map { desc ->
                val lut = parsedLutCache[desc.id]
                LutPresetItem(
                    id = desc.id,
                    title = lut?.title ?: desc.title,
                    category = folder.name,
                    sourceFormat = desc.sourceFormat,
                    isCustom = desc.isCustom,
                    lut = lut,
                    previewThumbnail = thumbnailCache[desc.id]
                )
            }
            _uiState.update { state ->
                state.copy(
                    lutFolders = state.lutFolders.map { f ->
                        if (f.name == folderName) {
                            f.copy(
                                items = cachedItems,
                                isLoading = false,
                                isLoadedForCurrentPhoto = dragSource != null
                            )
                        } else f
                    }
                )
            }
            return
        }

        folderLoadJobs[folderName]?.cancel()
        _uiState.update { state ->
            state.copy(
                lutFolders = state.lutFolders.map { f ->
                    if (f.name == folderName) f.copy(isLoading = true) else f
                }
            )
        }

        val ctx = getApplication<Application>()
        val job = viewModelScope.launch {
            try {
                val loadedItems = withContext(Dispatchers.IO) {
                    val thumbBase = dragSource?.takeIf { !it.isRecycled }?.let {
                        ImageIoHelper.createThumbnailBitmap(it)
                    }
                    try {
                        coroutineScope {
                            folder.descriptors.map { desc ->
                                async(Dispatchers.IO) {
                                    val lut = parsedLutCache[desc.id] ?: runCatching {
                                        LutImporter.loadPreset(ctx, desc, bakeXmpTable = false)
                                    }.onFailure { err ->
                                        Log.e(TAG, "Failed lazy load for ${desc.locationPath}", err)
                                    }.getOrNull()?.also { parsed ->
                                        parsedLutCache[desc.id] = parsed
                                    }

                                    val thumb = thumbnailCache[desc.id] ?: if (thumbBase != null && !thumbBase.isRecycled && lut != null) {
                                        runCatching {
                                            renderPresetThumbnail(thumbBase, lut)
                                        }.getOrNull()?.also { rendered ->
                                            if (photoGeneration.get() == expectedPhotoToken) {
                                                thumbnailCache[desc.id] = rendered
                                            }
                                        }
                                    } else {
                                        null
                                    }

                                    LutPresetItem(
                                        id = desc.id,
                                        title = lut?.title ?: desc.title,
                                        category = folder.name,
                                        sourceFormat = desc.sourceFormat,
                                        isCustom = desc.isCustom,
                                        lut = lut,
                                        previewThumbnail = thumb
                                    )
                                }
                            }.awaitAll()
                        }
                    } finally {
                        if (thumbBase != null && thumbBase !== dragSource && !thumbBase.isRecycled) {
                            thumbBase.recycle()
                        }
                    }
                }

                if (photoGeneration.get() != expectedPhotoToken) return@launch

                _uiState.update { state ->
                    state.copy(
                        lutFolders = state.lutFolders.map { f ->
                            if (f.name == folderName) {
                                f.copy(
                                    items = loadedItems,
                                    isLoading = false,
                                    isLoadedForCurrentPhoto = dragSource != null
                                )
                            } else f
                        }
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "Folder thumbnail load failed for $folderName", t)
                _uiState.update { state ->
                    state.copy(
                        lutFolders = state.lutFolders.map { f ->
                            if (f.name == folderName) f.copy(isLoading = false) else f
                        }
                    )
                }
            }
        }
        folderLoadJobs[folderName] = job
    }

    private suspend fun renderPresetThumbnail(thumbBase: Bitmap, lut: CubeLut): Bitmap {
        val xmp = lut.xmpSettings
        return if (xmp != null) {
            ColorGradingEngine.processFullResolution(
                thumbBase,
                GradingParams.fromXmpSettings(xmp, DEFAULT_LUT_INTENSITY),
                null
            )
        } else {
            ColorGradingEngine.processFullResolution(
                thumbBase,
                GradingParams(lutIntensity = DEFAULT_LUT_INTENSITY),
                lut
            )
        }
    }

    private fun captureCurrentSnapshot(label: String): EditorSnapshot? {
        val src = fullSourceBitmap.value ?: return null
        val state = _uiState.value
        return EditorSnapshot(
            sourceBitmap = src,
            gradingParams = state.gradingParams,
            selectedLutId = state.selectedLutId,
            healStrokes = state.healStrokes,
            label = label,
            selectedUserPresetId = state.selectedUserPresetId
        )
    }

    private fun pushUndoSnapshot(snapshot: EditorSnapshot?) {
        if (snapshot == null) return
        if (undoStack.size >= maxHistorySize) {
            undoStack.removeFirst()
        }
        undoStack.addLast(snapshot)
        redoStack.clear()
    }

    private fun syncHistoryFlags(state: EditorUiState): EditorUiState {
        return state.copy(
            canUndoHealStroke = healUndoStack.isNotEmpty(),
            canRedoHealStroke = healRedoStack.isNotEmpty(),
            canUndo = undoStack.isNotEmpty(),
            canRedo = redoStack.isNotEmpty(),
            undoLabel = undoStack.lastOrNull()?.label,
            redoLabel = redoStack.lastOrNull()?.label
        )
    }

    fun openPhotoFromUri(uri: Uri) {
        hasExternalOrUserPhoto.set(true)
        healJob?.cancel()
        healJob = null
        val requestToken = photoLoadRequestSeq.incrementAndGet()
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            runCatching {
                val hasPersistableGrant = runCatching {
                    ctx.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                    true
                }.getOrDefault(false)

                // If provider does not support persistable URI grants, immediately cache locally so full-res export stays valid
                val cachedFile = if (!hasPersistableGrant && uri.scheme == ContentResolver.SCHEME_CONTENT) {
                    runCatching { ImageIoHelper.cacheIncomingPhotoToAppStorage(ctx, uri) }.getOrNull()
                } else {
                    null
                }

                val displayName = withContext(Dispatchers.IO) {
                    ImageIoHelper.queryDisplayName(ctx, uri, "Imported Photo")
                }
                val formatInfo = withContext(Dispatchers.IO) {
                    ImageIoHelper.detectPhotoFormat(ctx, uri, displayName)
                }

                val (decoded, sourceRef, resolvedCachedFile) = if (cachedFile != null) {
                    val fileResult = runCatching {
                        ImageIoHelper.loadProxyFromFile(cachedFile, ImageIoHelper.PROXY_MAX_DIMENSION)
                    }
                    if (fileResult.isSuccess) {
                        Triple(fileResult.getOrThrow(), PhotoSourceRef.LocalFile(cachedFile), cachedFile)
                    } else {
                        runCatching { cachedFile.delete() }
                        Triple(
                            ImageIoHelper.loadProxyFromUri(ctx, uri, ImageIoHelper.PROXY_MAX_DIMENSION),
                            PhotoSourceRef.ContentUri(uri),
                            null
                        )
                    }
                } else {
                    Triple(
                        ImageIoHelper.loadProxyFromUri(ctx, uri, ImageIoHelper.PROXY_MAX_DIMENSION),
                        PhotoSourceRef.ContentUri(uri),
                        null
                    )
                }
                Pair(Triple(decoded, displayName, formatInfo), sourceRef to resolvedCachedFile)
            }.onSuccess { (triple, refAndCache) ->
                val (decoded, name, formatInfo) = triple
                val (sourceRef, resolvedCachedFile) = refAndCache
                if (photoLoadRequestSeq.get() != requestToken) {
                    if (!decoded.proxyBitmap.isRecycled) {
                        decoded.proxyBitmap.recycle()
                    }
                    resolvedCachedFile?.let { runCatching { it.delete() } }
                    return@onSuccess
                }
                replaceActiveCachedImportFile(resolvedCachedFile)
                activePhotoSourceRef = sourceRef
                nativePhotoWidth = decoded.nativeWidth
                nativePhotoHeight = decoded.nativeHeight
                applyLoadedSourceBitmap(
                    bitmap = decoded.proxyBitmap,
                    name = name,
                    clearHealSession = true,
                    nativeWidth = decoded.nativeWidth,
                    nativeHeight = decoded.nativeHeight
                )
                val statusMsg = if (formatInfo.isEmbeddedPreviewOnly) {
                    "Loaded ${formatInfo.badgeLabel} ($name, ${decoded.nativeWidth}×${decoded.nativeHeight}) — embedded JPEG preview"
                } else {
                    val badgeTag = formatInfo.badgeLabel?.let { " [$it]" }.orEmpty()
                    "Loaded $name$badgeTag (${decoded.nativeWidth}×${decoded.nativeHeight})"
                }
                _uiState.update {
                    it.copy(
                        photoFormatInfo = formatInfo,
                        statusBannerMessage = statusMsg
                    )
                }
            }.onFailure { e ->
                Log.e(TAG, "Failed to open photo from URI", e)
                val msg = if (e is OutOfMemoryError) {
                    "Image resolution exceeded device memory."
                } else {
                    e.localizedMessage ?: "Could not open image: Unsupported format"
                }
                _uiState.update {
                    it.copy(statusBannerMessage = msg)
                }
            }
        }
    }

    /**
     * Loads a photo delivered from Android's Share sheet (`ACTION_SEND`) or "Open with" / "Edit"
     * (`ACTION_EDIT`, `ACTION_VIEW`). Immediately claims photo-load priority so the starter sample
     * never overwrites the incoming photo, and copies `content://` streams to app cache as the
     * very first I/O step so full-resolution export never throws `SecurityException` after
     * configuration changes or backgrounding.
     */
    fun openIncomingPhotoFromIntent(uri: Uri) {
        hasExternalOrUserPhoto.set(true)
        healJob?.cancel()
        healJob = null
        val requestToken = photoLoadRequestSeq.incrementAndGet()
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            runCatching {
                // Immediately copy transient content:// streams before querying metadata so temporary intent grants cannot expire mid-flight
                val cachedFile = if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
                    runCatching {
                        ImageIoHelper.cacheIncomingPhotoToAppStorage(ctx, uri)
                    }.getOrNull()
                } else {
                    null
                }

                val displayName = withContext(Dispatchers.IO) {
                    ImageIoHelper.queryDisplayName(ctx, uri, "Shared Photo")
                }
                val formatInfo = withContext(Dispatchers.IO) {
                    ImageIoHelper.detectPhotoFormat(ctx, uri, displayName)
                }

                val (decoded, sourceRef, resolvedCachedFile) = if (cachedFile != null) {
                    val fileResult = runCatching {
                        ImageIoHelper.loadProxyFromFile(cachedFile, ImageIoHelper.PROXY_MAX_DIMENSION)
                    }
                    if (fileResult.isSuccess) {
                        Triple(fileResult.getOrThrow(), PhotoSourceRef.LocalFile(cachedFile), cachedFile)
                    } else {
                        runCatching { cachedFile.delete() }
                        Triple(
                            ImageIoHelper.loadProxyFromUri(ctx, uri, ImageIoHelper.PROXY_MAX_DIMENSION),
                            PhotoSourceRef.ContentUri(uri),
                            null
                        )
                    }
                } else {
                    Triple(
                        ImageIoHelper.loadProxyFromUri(ctx, uri, ImageIoHelper.PROXY_MAX_DIMENSION),
                        PhotoSourceRef.ContentUri(uri),
                        null
                    )
                }
                Pair(Triple(decoded, displayName, formatInfo), sourceRef to resolvedCachedFile)
            }.onSuccess { (triple, refAndCache) ->
                val (decoded, name, formatInfo) = triple
                val (sourceRef, resolvedCachedFile) = refAndCache
                if (photoLoadRequestSeq.get() != requestToken) {
                    if (!decoded.proxyBitmap.isRecycled) {
                        decoded.proxyBitmap.recycle()
                    }
                    resolvedCachedFile?.let { runCatching { it.delete() } }
                    return@onSuccess
                }
                replaceActiveCachedImportFile(resolvedCachedFile)
                activePhotoSourceRef = sourceRef
                nativePhotoWidth = decoded.nativeWidth
                nativePhotoHeight = decoded.nativeHeight
                applyLoadedSourceBitmap(
                    bitmap = decoded.proxyBitmap,
                    name = name,
                    clearHealSession = true,
                    nativeWidth = decoded.nativeWidth,
                    nativeHeight = decoded.nativeHeight
                )
                val statusMsg = if (formatInfo.isEmbeddedPreviewOnly) {
                    "Loaded ${formatInfo.badgeLabel} ($name, ${decoded.nativeWidth}×${decoded.nativeHeight}) — embedded JPEG preview"
                } else {
                    val badgeTag = formatInfo.badgeLabel?.let { " [$it]" }.orEmpty()
                    "Loaded $name$badgeTag (${decoded.nativeWidth}×${decoded.nativeHeight})"
                }
                _uiState.update {
                    it.copy(
                        activeTab = StudioTab.ADJUST,
                        isComparingOriginal = false,
                        photoFormatInfo = formatInfo,
                        statusBannerMessage = statusMsg
                    )
                }
            }.onFailure { e ->
                Log.e(TAG, "Failed to open shared/external photo from intent", e)
                val msg = if (e is OutOfMemoryError) {
                    "Shared image exceeded device memory."
                } else {
                    e.localizedMessage ?: "Could not open shared image: Unsupported format"
                }
                _uiState.update {
                    it.copy(statusBannerMessage = msg)
                }
            }
        }
    }

    fun importCubeLutFromUri(uri: Uri) {
        val seq = lutSelectionSeq.incrementAndGet()
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            runCatching {
                LutImporter.importFromUri(ctx, uri)
            }.onSuccess { summary ->
                if (lutSelectionSeq.get() != seq) return@launch
                val newDescriptors = summary.importedLuts.map { lut ->
                    parsedLutCache[lut.id] = lut
                    val desc = PresetDescriptor(
                        id = lut.id,
                        title = lut.title,
                        category = lut.category.ifBlank { "Custom" },
                        sourceFormat = lut.sourceFormat,
                        isCustom = true,
                        locationType = PresetLocationType.FILE,
                        locationPath = lut.id.removePrefix("custom_")
                    )
                    descriptorById[desc.id] = desc
                    desc
                }

                val firstLut = summary.importedLuts.first()
                val firstFolderName = firstLut.category.ifBlank { "Custom" }

                pushUndoSnapshot(captureCurrentSnapshot("LUT: ${firstLut.title}"))
                _uiState.update { state ->
                    val allDescriptors = (newDescriptors + descriptorById.values.filter { d ->
                        newDescriptors.none { it.id == d.id }
                    })
                    val previouslyExpanded = state.lutFolders
                        .filter { it.isExpanded }
                        .map { it.name }
                        .toSet() + firstFolderName

                    val updatedFolders = buildFolderGroups(
                        descriptors = allDescriptors,
                        previousExpanded = previouslyExpanded
                    )
                    val updatedParams = buildReplacedLutParams(
                        currentParams = state.gradingParams,
                        previousLut = state.activeLut,
                        nextLut = firstLut,
                        targetIntensity = DEFAULT_LUT_INTENSITY
                    )
                    // Clear previous active LUT from grading pipeline before applying the imported LUT
                    gradingInput.value = updatedParams.copy(lutIntensity = 0f) to null
                    gradingInput.value = updatedParams to firstLut
                    val statusMsg = if (summary.importedLuts.size == 1) {
                        val fmt = firstLut.sourceFormat.name
                        "Imported $fmt \"${firstLut.title}\""
                    } else {
                        "Imported ${summary.importedLuts.size} presets (${summary.skippedMessages.size} skipped)"
                    }
                    syncHistoryFlags(
                        state.copy(
                            lutFolders = updatedFolders,
                            selectedLutId = firstLut.id,
                            activeLut = firstLut,
                            selectedUserPresetId = null,
                            gradingParams = updatedParams,
                            activeTab = StudioTab.LUTS,
                            statusBannerMessage = statusMsg
                        )
                    )
                }

                // Render thumbnails only for the newly imported/expanded folder(s)
                val token = photoGeneration.get()
                newDescriptors.map { it.category.ifBlank { "Custom" } }.distinct().forEach { folder ->
                    if (folder == firstFolderName) {
                        loadFolderThumbnailsIfNeeded(folder, token)
                    }
                }
            }.onFailure { e ->
                Log.e(TAG, "Failed to import LUT/ZIP", e)
                _uiState.update {
                    it.copy(statusBannerMessage = e.localizedMessage ?: "Unsupported .cube, .xmp, or .zip file")
                }
            }
        }
    }

    fun deleteCustomLut(lutId: String) {
        viewModelScope.launch {
            val removingActive = _uiState.value.selectedLutId == lutId
            val removedLut = parsedLutCache[lutId] ?: if (removingActive) _uiState.value.activeLut else null
            LutImporter.deleteCustomLut(getApplication(), lutId)
            descriptorById.remove(lutId)
            parsedLutCache.remove(lutId)
            thumbnailCache.remove(lutId)

            _uiState.update { state ->
                val updatedFolders = state.lutFolders.mapNotNull { folder ->
                    val nextDescriptors = folder.descriptors.filterNot { it.id == lutId }
                    if (nextDescriptors.isEmpty()) {
                        null
                    } else {
                        folder.copy(
                            descriptors = nextDescriptors,
                            items = folder.items.filterNot { it.id == lutId }
                        )
                    }
                }
                val wasActive = state.selectedLutId == lutId
                val nextSelectedId = if (wasActive) null else state.selectedLutId
                val nextLut = if (wasActive) null else nextSelectedId?.let { parsedLutCache[it] }
                val nextParams = if (wasActive) {
                    buildReplacedLutParams(
                        currentParams = state.gradingParams,
                        previousLut = removedLut ?: state.activeLut,
                        nextLut = null,
                        targetIntensity = DEFAULT_LUT_INTENSITY
                    )
                } else {
                    state.gradingParams
                }
                gradingInput.value = nextParams to nextLut
                state.copy(
                    lutFolders = updatedFolders,
                    selectedLutId = nextSelectedId,
                    activeLut = nextLut,
                    selectedUserPresetId = if (wasActive) null else state.selectedUserPresetId,
                    gradingParams = nextParams,
                    statusBannerMessage = "Removed custom preset"
                )
            }
        }
    }

    fun selectTab(tab: StudioTab) {
        _uiState.update { state ->
            val disableTouchAdjust = tab == StudioTab.CROP || tab == StudioTab.HEAL
            state.copy(
                activeTab = tab,
                activeEyedropperMode = EyedropperMode.NONE,
                isPickingCloneSource = false,
                isTouchAdjustEnabled = if (disableTouchAdjust) false else state.isTouchAdjustEnabled,
                touchAdjustSelectedBand = if (disableTouchAdjust) null else state.touchAdjustSelectedBand,
                touchAdjustSwatchArgb = if (disableTouchAdjust) null else state.touchAdjustSwatchArgb,
                touchAdjustHudLabel = if (disableTouchAdjust) null else state.touchAdjustHudLabel,
                touchAdjustHudValueText = if (disableTouchAdjust) null else state.touchAdjustHudValueText,
                isTouchAdjustSwiping = if (disableTouchAdjust) false else state.isTouchAdjustSwiping
            )
        }
        if (tab == StudioTab.LUTS) {
            ensureUserPresetThumbnailsLoaded()
            ensureDefaultLutFolderLoaded(photoGeneration.get())
        }
    }

    // --- Custom User Presets ("My Presets" + Save Current Look) ---

    fun ensureUserPresetThumbnailsLoaded() {
        val presets = _uiState.value.userPresets
        if (presets.isEmpty()) return
        if (presets.all { userPresetThumbnailCache.containsKey(it.id) }) return

        val source = fullSourceBitmap.value?.takeIf { !it.isRecycled } ?: return
        val token = photoGeneration.get()
        if (userPresetThumbJob?.isActive == true) return

        userPresetThumbJob = viewModelScope.launch {
            val thumbBase = withContext(Dispatchers.Default) {
                if (source.isRecycled) null
                else ImageIoHelper.createPreviewBitmap(source, ImageIoHelper.THUMBNAIL_DIMENSION)
            } ?: return@launch
            val ctx = getApplication<Application>()
            try {
                for (preset in presets) {
                    if (photoGeneration.get() != token) return@launch
                    if (userPresetThumbnailCache.containsKey(preset.id)) continue
                    val linkedLut = preset.lutId?.let { lid ->
                        parsedLutCache[lid] ?: descriptorById[lid]?.let { desc ->
                            runCatching { LutImporter.loadPreset(ctx, desc, bakeXmpTable = false) }
                                .getOrNull()?.also { parsedLutCache[lid] = it }
                        }
                    }
                    val rendered = ColorGradingEngine.processFullResolution(
                        thumbBase,
                        preset.gradingParams.withoutSpatialEdits(),
                        linkedLut
                    )
                    if (photoGeneration.get() == token) {
                        userPresetThumbnailCache[preset.id] = rendered
                    } else if (!rendered.isRecycled) {
                        rendered.recycle()
                    }
                }
                if (photoGeneration.get() == token) {
                    _uiState.update { it.copy(userPresetThumbnails = userPresetThumbnailCache.toMap()) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "User preset thumbnail rendering failed", t)
            } finally {
                if (thumbBase !== source && !thumbBase.isRecycled) thumbBase.recycle()
            }
        }
    }

    fun saveCurrentLookAsPreset(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val currentState = _uiState.value
        val preset = UserPreset(
            id = UserPresetStore.newPresetId(),
            name = trimmed,
            createdAtMs = System.currentTimeMillis(),
            gradingParams = currentState.gradingParams.withoutSpatialEdits(),
            lutId = currentState.selectedLutId
        )
        val nextPresets = listOf(preset) + currentState.userPresets
        _uiState.update {
            it.copy(
                userPresets = nextPresets,
                selectedUserPresetId = preset.id,
                statusBannerMessage = "Saved preset \"$trimmed\""
            )
        }
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            UserPresetStore.savePresets(ctx, nextPresets)
            val src = fullSourceBitmap.value
            val token = photoGeneration.get()
            if (src != null && !src.isRecycled) {
                val thumbBase = withContext(Dispatchers.Default) {
                    ImageIoHelper.createPreviewBitmap(src, ImageIoHelper.THUMBNAIL_DIMENSION)
                }
                try {
                    val rendered = ColorGradingEngine.processFullResolution(
                        thumbBase,
                        preset.gradingParams,
                        currentState.activeLut
                    )
                    if (photoGeneration.get() == token) {
                        userPresetThumbnailCache[preset.id] = rendered
                        _uiState.update { it.copy(userPresetThumbnails = userPresetThumbnailCache.toMap()) }
                    } else if (!rendered.isRecycled) {
                        rendered.recycle()
                    }
                } finally {
                    if (!thumbBase.isRecycled) thumbBase.recycle()
                }
            }
        }
    }

    fun applyUserPreset(presetId: String) {
        val currentState = _uiState.value
        val preset = currentState.userPresets.firstOrNull { it.id == presetId } ?: return
        val seq = lutSelectionSeq.incrementAndGet()
        viewModelScope.launch {
            if (lutSelectionSeq.get() != seq) return@launch
            val ctx = getApplication<Application>()
            val lid = preset.lutId
            val resolvedLut = if (lid != null) {
                parsedLutCache[lid] ?: descriptorById[lid]?.let { desc ->
                    runCatching { LutImporter.loadPreset(ctx, desc, bakeXmpTable = false) }
                        .getOrNull()?.also { parsedLutCache[lid] = it }
                }
            } else {
                null
            }
            pushUndoSnapshot(captureCurrentSnapshot("My Preset: ${preset.name}"))
            val mergedParams = preset.gradingParams.copy(
                crop = _uiState.value.gradingParams.crop,
                masks = _uiState.value.gradingParams.masks
            )
            val resolvedLutId = if (resolvedLut != null) lid else null
            gradingInput.value = mergedParams to resolvedLut
            _uiState.update {
                syncHistoryFlags(
                    it.copy(
                        gradingParams = mergedParams,
                        selectedLutId = resolvedLutId,
                        activeLut = resolvedLut,
                        selectedUserPresetId = preset.id,
                        statusBannerMessage = "Applied \"${preset.name}\""
                    )
                )
            }
        }
    }

    fun deleteUserPreset(presetId: String) {
        userPresetThumbnailCache.remove(presetId)
        val current = _uiState.value
        val nextPresets = current.userPresets.filterNot { it.id == presetId }
        _uiState.update { state ->
            state.copy(
                userPresets = nextPresets,
                userPresetThumbnails = userPresetThumbnailCache.toMap(),
                selectedUserPresetId = if (state.selectedUserPresetId == presetId) null else state.selectedUserPresetId,
                statusBannerMessage = "Deleted saved preset"
            )
        }
        viewModelScope.launch {
            UserPresetStore.savePresets(getApplication(), nextPresets)
        }
    }

    // --- Selective Local Masks (Radial & Linear Gradient) ---

    fun addLocalMask(type: MaskType) {
        val current = _uiState.value
        val existing = current.gradingParams.masks
        if (existing.size >= MAX_LOCAL_MASKS) {
            _uiState.update {
                it.copy(statusBannerMessage = "Up to $MAX_LOCAL_MASKS local masks supported for 120Hz real-time grading")
            }
            return
        }
        val nextNum = existing.size + 1
        val newMask = if (type == MaskType.RADIAL) {
            LocalMask(
                id = "mask_${System.currentTimeMillis()}_${maskIdCounter.incrementAndGet()}",
                name = "Radial $nextNum",
                type = MaskType.RADIAL,
                centerX = 0.5f,
                centerY = 0.5f,
                radiusX = 0.30f,
                radiusY = 0.30f,
                feather = 0.50f,
                exposure = 0.35f
            )
        } else {
            LocalMask(
                id = "mask_${System.currentTimeMillis()}_${maskIdCounter.incrementAndGet()}",
                name = "Linear $nextNum",
                type = MaskType.LINEAR,
                startX = 0.5f,
                startY = 0.14f,
                endX = 0.5f,
                endY = 0.58f,
                exposure = -0.45f
            )
        }
        pushUndoSnapshot(captureCurrentSnapshot("Add ${type.label} Mask"))
        val updated = current.gradingParams.copy(masks = existing + newMask)
        gradingInput.value = updated to current.activeLut
        _uiState.update {
            syncHistoryFlags(
                it.copy(
                    gradingParams = updated,
                    selectedMaskId = newMask.id,
                    showMaskOverlay = true,
                    activeTab = StudioTab.MASK
                )
            )
        }
    }

    fun selectMask(maskId: String?) {
        _uiState.update { it.copy(selectedMaskId = maskId) }
    }

    fun updateMask(maskId: String, transform: (LocalMask) -> LocalMask) {
        val currentState = _uiState.value
        val curMasks = currentState.gradingParams.masks
        val idx = curMasks.indexOfFirst { it.id == maskId }
        if (idx < 0) return
        val nextMask = transform(curMasks[idx])
        if (nextMask == curMasks[idx]) return
        val nextMasks = curMasks.toMutableList().apply { this[idx] = nextMask }
        val updated = currentState.gradingParams.copy(masks = nextMasks)
        if (!currentState.isDraggingSlider) {
            pushUndoSnapshot(captureCurrentSnapshot("Mask Adjustment"))
        }
        gradingInput.value = updated to currentState.activeLut
        _uiState.update { syncHistoryFlags(it.copy(gradingParams = updated)) }
    }

    fun toggleMaskEnabled(maskId: String) {
        pushUndoSnapshot(captureCurrentSnapshot("Toggle Mask"))
        val currentState = _uiState.value
        val nextMasks = currentState.gradingParams.masks.map {
            if (it.id == maskId) it.copy(enabled = !it.enabled) else it
        }
        val updated = currentState.gradingParams.copy(masks = nextMasks)
        gradingInput.value = updated to currentState.activeLut
        _uiState.update { syncHistoryFlags(it.copy(gradingParams = updated)) }
    }

    fun toggleMaskInverted(maskId: String) {
        pushUndoSnapshot(captureCurrentSnapshot("Invert Mask"))
        val currentState = _uiState.value
        val nextMasks = currentState.gradingParams.masks.map {
            if (it.id == maskId) it.copy(inverted = !it.inverted) else it
        }
        val updated = currentState.gradingParams.copy(masks = nextMasks)
        gradingInput.value = updated to currentState.activeLut
        _uiState.update { syncHistoryFlags(it.copy(gradingParams = updated)) }
    }

    fun toggleShowMaskOverlay() {
        _uiState.update { it.copy(showMaskOverlay = !it.showMaskOverlay) }
    }

    fun deleteMask(maskId: String) {
        val currentState = _uiState.value
        val nextMasks = currentState.gradingParams.masks.filterNot { it.id == maskId }
        if (nextMasks.size == currentState.gradingParams.masks.size) return
        pushUndoSnapshot(captureCurrentSnapshot("Delete Mask"))
        val updated = currentState.gradingParams.copy(masks = nextMasks)
        gradingInput.value = updated to currentState.activeLut
        _uiState.update { state ->
            val nextSelected = if (state.selectedMaskId == maskId) nextMasks.lastOrNull()?.id else state.selectedMaskId
            syncHistoryFlags(
                state.copy(
                    gradingParams = updated,
                    selectedMaskId = nextSelected
                )
            )
        }
    }

    fun toggleHistogram() {
        _uiState.update { it.copy(showHistogram = !it.showHistogram) }
    }

    fun toggleHighlightClipping() {
        _uiState.update { it.copy(showHighlightClipping = !it.showHighlightClipping) }
    }

    fun toggleShadowClipping() {
        _uiState.update { it.copy(showShadowClipping = !it.showShadowClipping) }
    }

    fun setSliderDragging(dragging: Boolean) {
        if (_uiState.value.isDraggingSlider == dragging) return
        if (dragging) {
            if (pendingDragSnapshot == null) {
                pendingDragSnapshot = captureCurrentSnapshot("Color Adjustment")
            }
            isDraggingFlow.value = true
            _uiState.update { it.copy(isDraggingSlider = true) }
        } else {
            val pending = pendingDragSnapshot
            pendingDragSnapshot = null
            if (pending != null && pending.gradingParams != _uiState.value.gradingParams) {
                pushUndoSnapshot(pending)
            }
            isDraggingFlow.value = false
            _uiState.update { syncHistoryFlags(it.copy(isDraggingSlider = false)) }
        }
    }

    fun setComparingOriginal(comparing: Boolean) {
        _uiState.update { it.copy(isComparingOriginal = comparing) }
    }

    fun toggleSplitCompare() {
        _uiState.update {
            val nextEnabled = !it.isSplitCompareEnabled
            it.copy(
                isSplitCompareEnabled = nextEnabled,
                isComparingOriginal = false
            )
        }
    }

    fun setSplitCompareRatio(ratio: Float) {
        _uiState.update { it.copy(splitCompareRatio = ratio.coerceIn(0.05f, 0.95f)) }
    }

    fun updateGradingParams(transform: (GradingParams) -> GradingParams) {
        val currentState = _uiState.value
        val updated = transform(currentState.gradingParams)
        if (!currentState.isDraggingSlider && updated != currentState.gradingParams) {
            pushUndoSnapshot(captureCurrentSnapshot("Color Adjustment"))
        }
        gradingInput.value = updated to currentState.activeLut
        _uiState.update { syncHistoryFlags(it.copy(gradingParams = updated)) }
    }

    fun updateHalation(value: Float) {
        val clamped = value.coerceIn(0f, 100f)
        updateGradingParams { it.copy(halation = clamped) }
    }

    fun resetHalation() {
        setSliderDragging(false)
        updateGradingParams { it.copy(halation = 0f) }
    }

    fun updateBloom(value: Float) {
        val clamped = value.coerceIn(0f, 100f)
        updateGradingParams { it.copy(bloom = clamped) }
    }

    fun resetBloom() {
        setSliderDragging(false)
        updateGradingParams { it.copy(bloom = 0f) }
    }

    fun updateChromaticAberration(value: Float) {
        val clamped = value.coerceIn(-50f, 50f)
        updateGradingParams { it.copy(chromaticAberration = clamped) }
    }

    fun resetChromaticAberration() {
        setSliderDragging(false)
        updateGradingParams { it.copy(chromaticAberration = 0f) }
    }

    fun updateAnamorphicStreak(value: Float) {
        val clamped = value.coerceIn(0f, 100f)
        updateGradingParams { it.copy(anamorphicStreak = clamped) }
    }

    fun resetAnamorphicStreak() {
        setSliderDragging(false)
        updateGradingParams { it.copy(anamorphicStreak = 0f) }
    }

    // --- Crop, Orientation & Geometry / Perspective Actions ---

    fun setCropOverlayMode(mode: CropOverlayMode) {
        _uiState.update { it.copy(cropOverlayMode = mode) }
    }

    fun selectCropAspectPreset(preset: CropAspectPreset) {
        val state = _uiState.value
        val w = state.fullWidth.coerceAtLeast(1)
        val h = state.fullHeight.coerceAtLeast(1)
        val nextCrop = GeometryMath.applyAspectPreset(w, h, state.gradingParams.crop, preset)
        val updated = state.gradingParams.copy(crop = nextCrop)
        if (updated != state.gradingParams) {
            pushUndoSnapshot(captureCurrentSnapshot("Aspect: ${preset.label}"))
        }
        gradingInput.value = updated to state.activeLut
        _uiState.update { syncHistoryFlags(it.copy(gradingParams = updated)) }
    }

    fun toggleCropAspectLocked() {
        val state = _uiState.value
        val nextLocked = !state.gradingParams.crop.aspectLocked
        val nextPreset = if (!nextLocked && state.gradingParams.crop.aspectPreset != CropAspectPreset.FREE) {
            state.gradingParams.crop.aspectPreset
        } else {
            state.gradingParams.crop.aspectPreset
        }
        val updated = state.gradingParams.copy(
            crop = state.gradingParams.crop.copy(
                aspectLocked = nextLocked,
                aspectPreset = nextPreset
            )
        )
        gradingInput.value = updated to state.activeLut
        _uiState.update { it.copy(gradingParams = updated) }
    }

    fun swapCropAspect() {
        val state = _uiState.value
        val w = state.fullWidth.coerceAtLeast(1)
        val h = state.fullHeight.coerceAtLeast(1)
        val nextCrop = GeometryMath.swapCropAspect(w, h, state.gradingParams.crop)
        val updated = state.gradingParams.copy(crop = nextCrop)
        if (updated != state.gradingParams) {
            pushUndoSnapshot(captureCurrentSnapshot("Swap Aspect"))
        }
        gradingInput.value = updated to state.activeLut
        _uiState.update { syncHistoryFlags(it.copy(gradingParams = updated)) }
    }

    fun rotateCropLeft() {
        val state = _uiState.value
        pushUndoSnapshot(captureCurrentSnapshot("Rotate -90°"))
        val updated = state.gradingParams.copy(
            crop = GeometryMath.rotateCropSteps(state.gradingParams.crop, -1)
        )
        gradingInput.value = updated to state.activeLut
        _uiState.update { syncHistoryFlags(it.copy(gradingParams = updated)) }
    }

    fun rotateCropRight() {
        val state = _uiState.value
        pushUndoSnapshot(captureCurrentSnapshot("Rotate +90°"))
        val updated = state.gradingParams.copy(
            crop = GeometryMath.rotateCropSteps(state.gradingParams.crop, 1)
        )
        gradingInput.value = updated to state.activeLut
        _uiState.update { syncHistoryFlags(it.copy(gradingParams = updated)) }
    }

    fun flipCropHorizontal() {
        val state = _uiState.value
        pushUndoSnapshot(captureCurrentSnapshot("Flip Horizontal"))
        val updated = state.gradingParams.copy(
            crop = GeometryMath.flipHorizontal(state.gradingParams.crop)
        )
        gradingInput.value = updated to state.activeLut
        _uiState.update { syncHistoryFlags(it.copy(gradingParams = updated)) }
    }

    fun flipCropVertical() {
        val state = _uiState.value
        pushUndoSnapshot(captureCurrentSnapshot("Flip Vertical"))
        val updated = state.gradingParams.copy(
            crop = GeometryMath.flipVertical(state.gradingParams.crop)
        )
        gradingInput.value = updated to state.activeLut
        _uiState.update { syncHistoryFlags(it.copy(gradingParams = updated)) }
    }

    fun updateCropRect(rect: NormalizedCropRect) {
        val state = _uiState.value
        val updated = state.gradingParams.copy(crop = state.gradingParams.crop.withCropRect(rect))
        if (!state.isDraggingSlider && updated != state.gradingParams) {
            pushUndoSnapshot(captureCurrentSnapshot("Crop"))
        }
        gradingInput.value = updated to state.activeLut
        _uiState.update { syncHistoryFlags(it.copy(gradingParams = updated)) }
    }

    fun toggleConstrainCrop() {
        val state = _uiState.value
        pushUndoSnapshot(captureCurrentSnapshot("Constrain Crop"))
        val nextConstrain = !state.gradingParams.crop.constrainCrop
        val updated = state.gradingParams.copy(
            crop = state.gradingParams.crop.copy(constrainCrop = nextConstrain)
        )
        gradingInput.value = updated to state.activeLut
        _uiState.update { syncHistoryFlags(it.copy(gradingParams = updated)) }
    }

    fun applyAutoStraighten() {
        val state = _uiState.value
        val sample = state.previewDragBitmap ?: histSourceBitmap.value ?: fullSourceBitmap.value ?: return
        viewModelScope.launch {
            val angle = withContext(Dispatchers.Default) {
                GeometryMath.computeAutoStraightenAngle(sample, state.gradingParams.crop)
            }
            val latest = _uiState.value
            pushUndoSnapshot(captureCurrentSnapshot("Auto Straighten"))
            val updated = latest.gradingParams.copy(
                crop = latest.gradingParams.crop.copy(straightenDeg = angle)
            )
            gradingInput.value = updated to latest.activeLut
            val msg = if (kotlin.math.abs(angle) < 0.05f) {
                "Horizon already level"
            } else {
                String.format(Locale.US, "Auto straightened: %+.2f°", angle)
            }
            _uiState.update {
                syncHistoryFlags(
                    it.copy(
                        gradingParams = updated,
                        statusBannerMessage = msg
                    )
                )
            }
        }
    }

    fun applyCrop() {
        setSliderDragging(false)
        val state = _uiState.value
        val hasCropChanges = !state.gradingParams.crop.isDefault
        _uiState.update { current ->
            current.copy(
                activeTab = StudioTab.ADJUST,
                activeEyedropperMode = EyedropperMode.NONE,
                isPickingCloneSource = false,
                viewportResetToken = current.viewportResetToken + 1L,
                statusBannerMessage = if (hasCropChanges) "Crop applied" else null
            )
        }
        // Re-trigger live histogram for the newly cropped region
        gradingInput.value = state.gradingParams to state.activeLut
    }

    fun resetAllCrop() {
        val state = _uiState.value
        if (state.gradingParams.crop.isDefault) return
        pushUndoSnapshot(captureCurrentSnapshot("Reset Crop"))
        val updated = state.gradingParams.copy(crop = CropGeometryParams())
        gradingInput.value = updated to state.activeLut
        _uiState.update {
            syncHistoryFlags(
                it.copy(
                    gradingParams = updated,
                    statusBannerMessage = "Crop reset"
                )
            )
        }
    }

    fun resetCropAndOrientation() {
        val state = _uiState.value
        if (!state.gradingParams.crop.hasCropOrOrientationEdits &&
            state.gradingParams.crop.aspectPreset == CropAspectPreset.ORIGINAL &&
            state.gradingParams.crop.aspectLocked &&
            !state.gradingParams.crop.aspectSwapped
        ) return
        pushUndoSnapshot(captureCurrentSnapshot("Reset Crop & Orientation"))
        val updated = state.gradingParams.resetCropAndOrientation()
        gradingInput.value = updated to state.activeLut
        _uiState.update {
            syncHistoryFlags(
                it.copy(
                    gradingParams = updated,
                    statusBannerMessage = "Crop & orientation reset"
                )
            )
        }
    }

    fun resetGeometryPerspective() {
        val state = _uiState.value
        if (!state.gradingParams.crop.hasGeometryEdits && state.gradingParams.crop.constrainCrop) return
        pushUndoSnapshot(captureCurrentSnapshot("Reset Geometry"))
        val updated = state.gradingParams.resetCropGeometry()
        gradingInput.value = updated to state.activeLut
        _uiState.update {
            syncHistoryFlags(
                it.copy(
                    gradingParams = updated,
                    statusBannerMessage = "Geometry & perspective reset"
                )
            )
        }
    }

    fun selectWhiteBalancePreset(preset: WhiteBalancePreset) {
        val currentState = _uiState.value
        if (preset == WhiteBalancePreset.AUTO) {
            val sample = histSourceBitmap.value ?: currentState.previewDragBitmap ?: fullSourceBitmap.value ?: return
            viewModelScope.launch {
                val (autoTemp, autoTint) = ColorGradingEngine.computeAutoWhiteBalance(sample)
                val latest = _uiState.value
                pushUndoSnapshot(captureCurrentSnapshot("WB: Auto"))
                val updated = latest.gradingParams.copy(
                    whiteBalancePreset = WhiteBalancePreset.AUTO,
                    temperature = autoTemp,
                    tint = autoTint
                )
                gradingInput.value = updated to latest.activeLut
                _uiState.update {
                    syncHistoryFlags(
                        it.copy(
                            gradingParams = updated,
                            activeEyedropperMode = EyedropperMode.NONE,
                            statusBannerMessage = "Auto White Balance applied"
                        )
                    )
                }
            }
            return
        }

        pushUndoSnapshot(captureCurrentSnapshot("WB: ${preset.label}"))
        val updated = currentState.gradingParams.copy(
            whiteBalancePreset = preset,
            temperature = preset.temperature,
            tint = preset.tint
        )
        gradingInput.value = updated to currentState.activeLut
        _uiState.update {
            syncHistoryFlags(
                it.copy(
                    gradingParams = updated,
                    activeEyedropperMode = EyedropperMode.NONE
                )
            )
        }
    }

    fun toggleEyedropperMode(mode: EyedropperMode) {
        _uiState.update { state ->
            val next = if (state.activeEyedropperMode == mode) EyedropperMode.NONE else mode
            state.copy(
                activeEyedropperMode = next,
                isComparingOriginal = false,
                isTouchAdjustEnabled = if (next != EyedropperMode.NONE) false else state.isTouchAdjustEnabled,
                touchAdjustSelectedBand = if (next != EyedropperMode.NONE) null else state.touchAdjustSelectedBand,
                touchAdjustSwatchArgb = if (next != EyedropperMode.NONE) null else state.touchAdjustSwatchArgb
            )
        }
    }

    fun cancelEyedropper() {
        _uiState.update { it.copy(activeEyedropperMode = EyedropperMode.NONE) }
    }

    // --- Interactive On-Photo Touch Color & Tone Editing ---

    fun toggleTouchAdjust() {
        _uiState.update { state ->
            val next = !state.isTouchAdjustEnabled
            state.copy(
                isTouchAdjustEnabled = next,
                activeEyedropperMode = if (next) EyedropperMode.NONE else state.activeEyedropperMode,
                isComparingOriginal = false,
                touchAdjustSelectedBand = if (next) state.touchAdjustSelectedBand else null,
                touchAdjustSwatchArgb = if (next) state.touchAdjustSwatchArgb else null,
                touchAdjustHudLabel = if (next) state.touchAdjustHudLabel else null,
                touchAdjustHudValueText = if (next) state.touchAdjustHudValueText else null,
                isTouchAdjustSwiping = if (next) state.isTouchAdjustSwiping else false
            )
        }
    }

    fun dismissTouchAdjustPill() {
        _uiState.update {
            it.copy(
                touchAdjustSelectedBand = null,
                touchAdjustSwatchArgb = null
            )
        }
    }

    fun updateTouchAdjustBand(transform: (HslBandAdjustment) -> HslBandAdjustment) {
        val currentState = _uiState.value
        val band = currentState.touchAdjustSelectedBand ?: return
        val updated = currentState.gradingParams.copy(
            hsl = currentState.gradingParams.hsl.withBand(band, transform)
        )
        if (!currentState.isDraggingSlider && updated != currentState.gradingParams) {
            pushUndoSnapshot(captureCurrentSnapshot("Touch HSL: ${band.label}"))
        }
        gradingInput.value = updated to currentState.activeLut
        _uiState.update { syncHistoryFlags(it.copy(gradingParams = updated)) }
    }

    fun resetTouchAdjustBand() {
        val currentState = _uiState.value
        val band = currentState.touchAdjustSelectedBand ?: return
        if (currentState.gradingParams.hsl[band].isDefault) return
        pushUndoSnapshot(captureCurrentSnapshot("Reset ${band.label} HSL"))
        val updated = currentState.gradingParams.copy(
            hsl = currentState.gradingParams.hsl.withBand(band) { HslBandAdjustment() }
        )
        gradingInput.value = updated to currentState.activeLut
        _uiState.update { syncHistoryFlags(it.copy(gradingParams = updated)) }
    }

    fun onTouchAdjustTap(viewportNx: Float, viewportNy: Float) {
        val state = _uiState.value
        if (!state.isTouchAdjustEnabled) return
        val sampleSource = (state.previewIdleBitmap ?: fullSourceBitmap.value)?.takeIf { !it.isRecycled } ?: return

        viewModelScope.launch {
            val sample = withContext(Dispatchers.Default) {
                if (sampleSource.isRecycled) return@withContext null
                val mappedCoords = GeometryMath.mapOutputUvToSourceUv(
                    u = viewportNx,
                    v = viewportNy,
                    srcW = sampleSource.width,
                    srcH = sampleSource.height,
                    crop = state.gradingParams.crop,
                    applyCrop = state.activeTab != StudioTab.CROP
                ) ?: (viewportNx to viewportNy)
                ColorGradingEngine.sampleNormalizedPatch(
                    source = sampleSource,
                    nx = mappedCoords.first,
                    ny = mappedCoords.second,
                    paramsForRenderedSample = state.gradingParams
                )
            } ?: return@launch

            val latest = _uiState.value
            if (!latest.isTouchAdjustEnabled) return@launch
            val band = HslBand.closestToHue(sample.hueDeg)
            _uiState.update {
                it.copy(
                    touchAdjustSelectedBand = band,
                    touchAdjustSwatchArgb = sample.swatchArgb
                )
            }
        }
    }

    fun onTouchAdjustSwipeStart(viewportNx: Float, viewportNy: Float) {
        val state = _uiState.value
        if (!state.isTouchAdjustEnabled) return
        val sampleSource = (histSourceBitmap.value ?: state.previewIdleBitmap ?: fullSourceBitmap.value)
            ?.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 }
        val luma = if (sampleSource == null) {
            0.5f
        } else {
            val u = viewportNx.coerceIn(0f, 1f)
            val v = viewportNy.coerceIn(0f, 1f)
            val mapped = GeometryMath.mapOutputUvToSourceUv(
                u = u,
                v = v,
                srcW = sampleSource.width,
                srcH = sampleSource.height,
                crop = state.gradingParams.crop,
                applyCrop = state.activeTab != StudioTab.CROP
            ) ?: (u to v)
            val px = (mapped.first.coerceIn(0f, 1f) * (sampleSource.width - 1)).toInt()
                .coerceIn(0, sampleSource.width - 1)
            val py = (mapped.second.coerceIn(0f, 1f) * (sampleSource.height - 1)).toInt()
                .coerceIn(0, sampleSource.height - 1)
            val c = sampleSource.getPixel(px, py)
            val r = ((c ushr 16) and 0xFF) / 255f
            val g = ((c ushr 8) and 0xFF) / 255f
            val b = (c and 0xFF) / 255f
            (0.2126f * r + 0.7152f * g + 0.0722f * b).coerceIn(0f, 1f)
        }
        val zone = when {
            luma < 0.36f -> TouchToneZone.SHADOWS
            luma > 0.66f -> TouchToneZone.HIGHLIGHTS
            else -> TouchToneZone.MIDTONES
        }
        touchSwipeZone = zone
        touchSwipeStartVal = when (zone) {
            TouchToneZone.SHADOWS -> state.gradingParams.shadows
            TouchToneZone.MIDTONES -> state.gradingParams.exposure
            TouchToneZone.HIGHLIGHTS -> state.gradingParams.highlights
        }
        touchSwipeStartSat = state.gradingParams.saturation
        touchSwipeSnapshot = captureCurrentSnapshot("Touch ${zone.label}")
        isDraggingFlow.value = true
        _uiState.update {
            it.copy(
                isDraggingSlider = true,
                isTouchAdjustSwiping = true
            )
        }
    }

    fun onTouchAdjustSwipeUpdate(isVertical: Boolean, totalFraction: Float) {
        val state = _uiState.value
        if (!state.isTouchAdjustEnabled || touchSwipeSnapshot == null) return

        val (updated, label, valueText) = if (isVertical) {
            when (touchSwipeZone) {
                TouchToneZone.MIDTONES -> {
                    val nextExp = com.zamalfajji.zamlab.ui.components.SliderStepMath.quantize(
                        raw = touchSwipeStartVal + totalFraction * 1.8f,
                        valueRange = -2f..2f,
                        step = 0.05f
                    )
                    Triple(
                        state.gradingParams.copy(exposure = nextExp),
                        TouchToneZone.MIDTONES.label,
                        String.format(Locale.US, "%+.2f EV", nextExp)
                    )
                }
                TouchToneZone.SHADOWS -> {
                    val nextSh = com.zamalfajji.zamlab.ui.components.SliderStepMath.quantize(
                        raw = touchSwipeStartVal + totalFraction * 100f,
                        valueRange = -100f..100f,
                        step = 1f
                    )
                    Triple(
                        state.gradingParams.copy(shadows = nextSh),
                        TouchToneZone.SHADOWS.label,
                        String.format(Locale.US, "%+.0f", nextSh)
                    )
                }
                TouchToneZone.HIGHLIGHTS -> {
                    val nextHi = com.zamalfajji.zamlab.ui.components.SliderStepMath.quantize(
                        raw = touchSwipeStartVal + totalFraction * 100f,
                        valueRange = -100f..100f,
                        step = 1f
                    )
                    Triple(
                        state.gradingParams.copy(highlights = nextHi),
                        TouchToneZone.HIGHLIGHTS.label,
                        String.format(Locale.US, "%+.0f", nextHi)
                    )
                }
            }
        } else {
            val nextSat = com.zamalfajji.zamlab.ui.components.SliderStepMath.quantize(
                raw = touchSwipeStartSat + totalFraction * 100f,
                valueRange = -100f..100f,
                step = 1f
            )
            Triple(
                state.gradingParams.copy(saturation = nextSat),
                "Saturation",
                String.format(Locale.US, "%+.0f", nextSat)
            )
        }

        gradingInput.value = updated to state.activeLut
        _uiState.update {
            it.copy(
                gradingParams = updated,
                touchAdjustHudLabel = label,
                touchAdjustHudValueText = valueText
            )
        }
    }

    fun onTouchAdjustSwipeEnd(commit: Boolean) {
        val baseline = touchSwipeSnapshot
        touchSwipeSnapshot = null
        isDraggingFlow.value = false
        if (baseline == null) {
            _uiState.update {
                it.copy(
                    isDraggingSlider = false,
                    isTouchAdjustSwiping = false,
                    touchAdjustHudLabel = null,
                    touchAdjustHudValueText = null
                )
            }
            return
        }

        val currentState = _uiState.value
        if (commit) {
            if (baseline.gradingParams != currentState.gradingParams) {
                val label = currentState.touchAdjustHudLabel?.let { "Touch $it" } ?: baseline.label
                pushUndoSnapshot(baseline.copy(label = label))
            }
            _uiState.update {
                syncHistoryFlags(
                    it.copy(
                        isDraggingSlider = false,
                        isTouchAdjustSwiping = false,
                        touchAdjustHudLabel = null,
                        touchAdjustHudValueText = null
                    )
                )
            }
        } else {
            gradingInput.value = baseline.gradingParams to currentState.activeLut
            _uiState.update {
                syncHistoryFlags(
                    it.copy(
                        gradingParams = baseline.gradingParams,
                        isDraggingSlider = false,
                        isTouchAdjustSwiping = false,
                        touchAdjustHudLabel = null,
                        touchAdjustHudValueText = null
                    )
                )
            }
        }
    }

    fun pickColorFromCanvas(nx: Float, ny: Float) {
        val state = _uiState.value
        val mode = state.activeEyedropperMode
        if (mode == EyedropperMode.NONE) return
        val sampleSource = (state.previewIdleBitmap ?: fullSourceBitmap.value)?.takeIf { !it.isRecycled } ?: return

        viewModelScope.launch {
            val mappedCoords = GeometryMath.mapOutputUvToSourceUv(
                u = nx,
                v = ny,
                srcW = sampleSource.width,
                srcH = sampleSource.height,
                crop = state.gradingParams.crop,
                applyCrop = state.activeTab != StudioTab.CROP
            ) ?: (nx to ny)
            val sampleNx = mappedCoords.first
            val sampleNy = mappedCoords.second

            when (mode) {
                EyedropperMode.WHITE_BALANCE -> {
                    val sample = withContext(Dispatchers.Default) {
                        if (sampleSource.isRecycled) null
                        else ColorGradingEngine.sampleNormalizedPatch(sampleSource, sampleNx, sampleNy, null)
                    } ?: return@launch
                    val avgGray = ((sample.r + sample.g + sample.b) / 3f).coerceAtLeast(0.05f)
                    val targetTemp = (((sample.b - sample.r) / avgGray) * 115f).coerceIn(-80f, 80f)
                    val targetTint = (((sample.g - (sample.r + sample.b) * 0.5f) / avgGray) * 90f).coerceIn(-65f, 65f)
                    val latest = _uiState.value
                    pushUndoSnapshot(captureCurrentSnapshot("WB Eyedropper"))
                    val updated = latest.gradingParams.copy(
                        whiteBalancePreset = WhiteBalancePreset.CUSTOM,
                        temperature = targetTemp,
                        tint = targetTint
                    )
                    gradingInput.value = updated to latest.activeLut
                    _uiState.update {
                        syncHistoryFlags(
                            it.copy(
                                gradingParams = updated,
                                activeEyedropperMode = EyedropperMode.NONE,
                                statusBannerMessage = "White Balance sampled from photo"
                            )
                        )
                    }
                }

                EyedropperMode.POINT_COLOR -> {
                    val latest = _uiState.value
                    val sample = withContext(Dispatchers.Default) {
                        if (sampleSource.isRecycled) null
                        else ColorGradingEngine.sampleNormalizedPatch(
                            source = sampleSource,
                            nx = sampleNx,
                            ny = sampleNy,
                            paramsForRenderedSample = latest.gradingParams
                        )
                    } ?: return@launch
                    pushUndoSnapshot(captureCurrentSnapshot("Point Color Pick"))
                    val updated = latest.gradingParams.copy(
                        pointColor = latest.gradingParams.pointColor.copy(
                            sourceHueDeg = sample.hueDeg,
                            sourceSat = sample.sat,
                            sourceLuma = sample.luma,
                            swatchArgb = sample.swatchArgb,
                            isSampled = true
                        )
                    )
                    gradingInput.value = updated to latest.activeLut
                    _uiState.update {
                        syncHistoryFlags(
                            it.copy(
                                gradingParams = updated,
                                activeEyedropperMode = EyedropperMode.NONE,
                                statusBannerMessage = "Point Color sampled (${sample.hueDeg.toInt()}°)"
                            )
                        )
                    }
                }

                EyedropperMode.NONE -> Unit
            }
        }
    }

    fun selectProfile(profileId: String) {
        val profile = CameraProfiles.byId(profileId)
        val currentState = _uiState.value
        val currentParams = currentState.gradingParams
        if (profile.id == currentParams.profileId) return

        pushUndoSnapshot(captureCurrentSnapshot("Profile: ${profile.name}"))
        val nextAmount = when {
            !profile.isCreative -> 1f
            currentParams.profileId == CameraProfiles.DEFAULT_ID || currentParams.profileAmount <= 0.001f -> 1f
            else -> currentParams.profileAmount
        }
        val updatedParams = currentParams.copy(
            profileId = profile.id,
            profileAmount = nextAmount
        )
        val nextRecents = (listOf(profile.id) + currentState.recentProfileIds)
            .distinct()
            .take(6)
        persistRecentProfiles(nextRecents)

        gradingInput.value = updatedParams to currentState.activeLut
        _uiState.update {
            syncHistoryFlags(
                it.copy(
                    gradingParams = updatedParams,
                    recentProfileIds = nextRecents
                )
            )
        }
    }

    fun updateProfileAmount(amount: Float) {
        val clamped = amount.coerceIn(0f, 2f)
        val currentState = _uiState.value
        if (currentState.gradingParams.profileId == CameraProfiles.DEFAULT_ID) return
        val updated = currentState.gradingParams.copy(profileAmount = clamped)
        if (!currentState.isDraggingSlider && updated != currentState.gradingParams) {
            pushUndoSnapshot(captureCurrentSnapshot("Profile Amount"))
        }
        gradingInput.value = updated to currentState.activeLut
        _uiState.update { syncHistoryFlags(it.copy(gradingParams = updated)) }
    }

    fun toggleFavoriteProfile(profileId: String) {
        if (CameraProfiles.ALL.none { it.id == profileId }) return
        val currentFavs = _uiState.value.favoriteProfileIds
        val nextFavs = if (profileId in currentFavs) {
            currentFavs - profileId
        } else {
            currentFavs + profileId
        }
        persistFavoriteProfiles(nextFavs)
        _uiState.update { it.copy(favoriteProfileIds = nextFavs) }
    }

    fun ensureProfileThumbnailsLoaded() {
        val dragSource = _uiState.value.previewDragBitmap?.takeIf { !it.isRecycled } ?: return
        if (profileThumbnailCache.size == CameraProfiles.ALL.size) {
            _uiState.update {
                it.copy(
                    profileThumbnails = profileThumbnailCache.toMap(),
                    isLoadingProfileThumbnails = false
                )
            }
            return
        }
        if (profileThumbJob?.isActive == true) return

        val expectedToken = photoGeneration.get()
        _uiState.update { it.copy(isLoadingProfileThumbnails = true) }

        profileThumbJob = viewModelScope.launch {
            try {
                val renderedMap = withContext(Dispatchers.Default) {
                    if (dragSource.isRecycled) return@withContext emptyMap()
                    val thumbBase = ImageIoHelper.createThumbnailBitmap(dragSource)
                    try {
                        coroutineScope {
                            CameraProfiles.ALL.map { profile ->
                                async(Dispatchers.Default) {
                                    val existing = profileThumbnailCache[profile.id]
                                    val bmp = existing ?: ColorGradingEngine.processFullResolution(
                                        thumbBase,
                                        GradingParams(profileId = profile.id, profileAmount = 1f),
                                        null
                                    ).also { created ->
                                        if (photoGeneration.get() == expectedToken) {
                                            profileThumbnailCache[profile.id] = created
                                        }
                                    }
                                    profile.id to bmp
                                }
                            }.awaitAll().toMap()
                        }
                    } finally {
                        if (thumbBase !== dragSource && !thumbBase.isRecycled) {
                            thumbBase.recycle()
                        }
                    }
                }
                if (photoGeneration.get() != expectedToken) return@launch
                _uiState.update {
                    it.copy(
                        profileThumbnails = renderedMap,
                        isLoadingProfileThumbnails = false
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "Profile thumbnail generation failed", t)
                _uiState.update { it.copy(isLoadingProfileThumbnails = false) }
            }
        }
    }

    fun updateLutIntensity(intensity: Float) {
        val clamped = intensity.coerceIn(0f, 1f)
        val currentState = _uiState.value
        val activeXmp = currentState.activeLut?.xmpSettings
        val updated = if (activeXmp != null) {
            GradingParams.fromXmpSettings(activeXmp, clamped).copy(
                profileId = currentState.gradingParams.profileId,
                profileAmount = currentState.gradingParams.profileAmount,
                crop = currentState.gradingParams.crop,
                masks = currentState.gradingParams.masks
            )
        } else {
            currentState.gradingParams.copy(lutIntensity = clamped)
        }
        if (!currentState.isDraggingSlider && updated != currentState.gradingParams) {
            pushUndoSnapshot(captureCurrentSnapshot("Preset Intensity"))
        }
        gradingInput.value = updated to currentState.activeLut
        _uiState.update { syncHistoryFlags(it.copy(gradingParams = updated)) }
    }

    private fun buildReplacedLutParams(
        currentParams: GradingParams,
        previousLut: CubeLut?,
        nextLut: CubeLut?,
        targetIntensity: Float
    ): GradingParams {
        val curProfileId = currentParams.profileId
        val curProfileAmount = currentParams.profileAmount
        val curCrop = currentParams.crop
        val curMasks = currentParams.masks
        return when {
            nextLut?.xmpSettings != null -> {
                GradingParams.fromXmpSettings(nextLut.xmpSettings, targetIntensity).copy(
                    profileId = curProfileId,
                    profileAmount = curProfileAmount,
                    crop = curCrop,
                    masks = curMasks
                )
            }
            previousLut?.xmpSettings != null -> {
                GradingParams(
                    profileId = curProfileId,
                    profileAmount = curProfileAmount,
                    crop = curCrop,
                    masks = curMasks,
                    lutIntensity = targetIntensity
                )
            }
            else -> {
                currentParams.copy(lutIntensity = targetIntensity)
            }
        }
    }

    fun selectLut(lutId: String?) {
        val currentState = _uiState.value
        val seq = lutSelectionSeq.incrementAndGet()

        // Selecting "None" or tapping the currently active LUT again clears the active LUT cleanly
        if (lutId == null || lutId == currentState.selectedLutId) {
            if (currentState.selectedLutId == null &&
                currentState.activeLut == null &&
                currentState.selectedUserPresetId == null
            ) {
                return
            }
            applyResolvedLutSelection(lutId = null, selectedLut = null)
            return
        }

        val cached = parsedLutCache[lutId]
        if (cached != null) {
            applyResolvedLutSelection(lutId = lutId, selectedLut = cached)
            return
        }

        val descriptor = descriptorById[lutId] ?: return
        val preSwitchLabel = if (descriptor.sourceFormat == LutSourceFormat.XMP) {
            "Preset: ${descriptor.title}"
        } else {
            "LUT: ${descriptor.title}"
        }
        val preSwitchSnapshot = captureCurrentSnapshot(preSwitchLabel)
        val previousLut = currentState.activeLut
        val previousParams = currentState.gradingParams
        val clearedParams = buildReplacedLutParams(
            currentParams = previousParams,
            previousLut = previousLut,
            nextLut = null,
            targetIntensity = DEFAULT_LUT_INTENSITY
        )
        // Immediately clear previously active LUT from the color grading pipeline while loading next LUT
        gradingInput.value = clearedParams to null
        _uiState.update {
            it.copy(
                selectedLutId = lutId,
                activeLut = null,
                selectedUserPresetId = null,
                gradingParams = clearedParams
            )
        }

        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val loaded = runCatching {
                LutImporter.loadPreset(ctx, descriptor, bakeXmpTable = false)
            }.onFailure { err ->
                Log.e(TAG, "Failed to load preset $lutId", err)
            }.getOrNull()

            if (lutSelectionSeq.get() != seq) return@launch

            if (loaded == null) {
                if (preSwitchSnapshot != null) {
                    restoreSnapshot(preSwitchSnapshot)
                }
                return@launch
            }

            parsedLutCache[lutId] = loaded
            applyResolvedLutSelection(
                lutId = lutId,
                selectedLut = loaded,
                snapshotOverride = preSwitchSnapshot,
                previousLutOverride = previousLut,
                previousParamsOverride = previousParams
            )
        }
    }

    private fun applyResolvedLutSelection(
        lutId: String?,
        selectedLut: CubeLut?,
        snapshotOverride: EditorSnapshot? = null,
        previousLutOverride: CubeLut? = null,
        previousParamsOverride: GradingParams? = null
    ) {
        val currentState = _uiState.value
        val label = selectedLut?.let {
            if (it.sourceFormat == LutSourceFormat.XMP) "Preset: ${it.title}" else "LUT: ${it.title}"
        } ?: "LUT: Off"
        pushUndoSnapshot(snapshotOverride ?: captureCurrentSnapshot(label))

        val baseParams = previousParamsOverride ?: currentState.gradingParams
        val prevLut = previousLutOverride ?: currentState.activeLut
        val updatedParams = buildReplacedLutParams(
            currentParams = baseParams,
            previousLut = prevLut,
            nextLut = selectedLut,
            targetIntensity = DEFAULT_LUT_INTENSITY
        )

        // Ensure the previously active LUT is cleared from the grading pipeline before applying the new LUT
        if (currentState.activeLut != null && selectedLut != null && currentState.activeLut !== selectedLut) {
            gradingInput.value = updatedParams.copy(lutIntensity = 0f) to null
        }
        gradingInput.value = updatedParams to selectedLut
        _uiState.update {
            syncHistoryFlags(
                it.copy(
                    selectedLutId = lutId,
                    activeLut = selectedLut,
                    selectedUserPresetId = null,
                    gradingParams = updatedParams
                )
            )
        }
    }

    fun applyAutoTone() {
        val sample = histSourceBitmap.value ?: _uiState.value.previewDragBitmap ?: fullSourceBitmap.value ?: return
        viewModelScope.launch {
            val currentState = _uiState.value
            val autoParams = ColorGradingEngine.computeAutoToneParams(sample, currentState.gradingParams)
            pushUndoSnapshot(captureCurrentSnapshot("Auto Tone"))
            gradingInput.value = autoParams to currentState.activeLut
            _uiState.update {
                syncHistoryFlags(
                    it.copy(
                        gradingParams = autoParams,
                        statusBannerMessage = "Auto tone applied"
                    )
                )
            }
        }
    }

    fun toggleBlackAndWhite() {
        val currentState = _uiState.value
        val nextBw = !currentState.gradingParams.isGrayscale
        pushUndoSnapshot(captureCurrentSnapshot(if (nextBw) "B&W On" else "B&W Off"))
        val updated = currentState.gradingParams.copy(isGrayscale = nextBw)
        gradingInput.value = updated to currentState.activeLut
        _uiState.update {
            syncHistoryFlags(
                it.copy(
                    gradingParams = updated,
                    statusBannerMessage = if (nextBw) "Converted to Black & White" else "Restored color"
                )
            )
        }
    }

    fun copyEdits() {
        val state = _uiState.value
        val copied = CopiedEdits(
            gradingParams = state.gradingParams.withoutSpatialEdits(),
            selectedLutId = state.selectedLutId,
            selectedUserPresetId = state.selectedUserPresetId
        )
        copiedEdits = copied
        UserPresetStore.saveClipboardEdits(getApplication(), copied)
        _uiState.update {
            it.copy(
                canPasteEdits = true,
                statusBannerMessage = "Edits copied — ready to paste across photos"
            )
        }
    }

    fun pasteEdits() {
        val clipboard = copiedEdits ?: return
        val seq = lutSelectionSeq.incrementAndGet()
        viewModelScope.launch {
            if (lutSelectionSeq.get() != seq) return@launch
            val targetId = clipboard.selectedLutId
            val resolvedLut = if (targetId != null) {
                parsedLutCache[targetId] ?: descriptorById[targetId]?.let { desc ->
                    runCatching { LutImporter.loadPreset(getApplication(), desc, bakeXmpTable = false) }
                        .getOrNull()?.also { parsedLutCache[targetId] = it }
                }
            } else null

            pushUndoSnapshot(captureCurrentSnapshot("Paste Edits"))
            val resolvedLutId = if (resolvedLut != null) targetId else null
            val currentState = _uiState.value
            val mergedParams = clipboard.gradingParams.copy(
                crop = currentState.gradingParams.crop,
                masks = currentState.gradingParams.masks
            )
            val resolvedUserPresetId = clipboard.selectedUserPresetId?.takeIf { uid ->
                currentState.userPresets.any { it.id == uid }
            }
            gradingInput.value = mergedParams to resolvedLut
            _uiState.update {
                syncHistoryFlags(
                    it.copy(
                        gradingParams = mergedParams,
                        selectedLutId = resolvedLutId,
                        activeLut = resolvedLut,
                        selectedUserPresetId = resolvedUserPresetId,
                        statusBannerMessage = "Edits pasted to photo"
                    )
                )
            }
        }
    }

    fun resetAllAdjustments() {
        if (_uiState.value.isHealProcessing) return
        lutSelectionSeq.incrementAndGet()
        val currentState = _uiState.value
        if (currentState.gradingParams.isDefault && currentState.selectedLutId == null) {
            _uiState.update { it.copy(viewportResetToken = it.viewportResetToken + 1L) }
            return
        }
        pushUndoSnapshot(captureCurrentSnapshot("Reset Adjustments"))

        val reset = GradingParams()
        gradingInput.value = reset to null
        _uiState.update {
            syncHistoryFlags(
                it.copy(
                    gradingParams = reset,
                    selectedLutId = null,
                    activeLut = null,
                    selectedUserPresetId = null,
                    selectedMaskId = null,
                    touchAdjustSelectedBand = null,
                    touchAdjustSwatchArgb = null,
                    touchAdjustHudLabel = null,
                    touchAdjustHudValueText = null,
                    isTouchAdjustSwiping = false,
                    viewportResetToken = it.viewportResetToken + 1L,
                    statusBannerMessage = "Color adjustments and LUT reset"
                )
            )
        }
    }

    fun undo() {
        if (_uiState.value.isHealProcessing) return
        lutSelectionSeq.incrementAndGet()
        val target = undoStack.removeLastOrNull() ?: return
        val current = captureCurrentSnapshot(target.label)
        if (current != null) {
            redoStack.addLast(current)
        }
        restoreSnapshot(target)
    }

    fun redo() {
        if (_uiState.value.isHealProcessing) return
        lutSelectionSeq.incrementAndGet()
        val target = redoStack.removeLastOrNull() ?: return
        val current = captureCurrentSnapshot(target.label)
        if (current != null) {
            undoStack.addLast(current)
        }
        restoreSnapshot(target)
    }

    private fun restoreSnapshot(snapshot: EditorSnapshot) {
        val bitmapChanged = fullSourceBitmap.value !== snapshot.sourceBitmap
        val resolvedLut = snapshot.selectedLutId?.let { parsedLutCache[it] }
        val resolvedLutId = if (resolvedLut != null) snapshot.selectedLutId else null

        isDraggingFlow.value = false

        if (bitmapChanged) {
            val token = photoGeneration.incrementAndGet()
            folderLoadJobs.values.forEach { it.cancel() }
            folderLoadJobs.clear()
            profileThumbJob?.cancel()
            profileThumbJob = null
            thumbnailCache.clear()
            profileThumbnailCache.clear()

            val proxyTexture = snapshot.sourceBitmap
            fullSourceBitmap.value = proxyTexture
            val preHealPreview = if (proxyTexture !== initialPhotoSourceBitmap) initialPhotoPreviewIdle else null
            _uiState.update { state ->
                gradingInput.value = snapshot.gradingParams to resolvedLut
                val staleFolders = state.lutFolders.map { f ->
                    f.copy(
                        isLoadedForCurrentPhoto = false,
                        isLoading = false,
                        items = f.items.map { it.copy(previewThumbnail = null) }
                    )
                }
                syncHistoryFlags(
                    state.copy(
                        fullWidth = nativePhotoWidth.takeIf { it > 0 } ?: proxyTexture.width,
                        fullHeight = nativePhotoHeight.takeIf { it > 0 } ?: proxyTexture.height,
                        previewIdleBitmap = proxyTexture,
                        previewDragBitmap = proxyTexture,
                        preHealPreviewBitmap = preHealPreview,
                        lutFolders = staleFolders,
                        profileThumbnails = emptyMap(),
                        isLoadingProfileThumbnails = false,
                        gradingParams = snapshot.gradingParams,
                        selectedLutId = resolvedLutId,
                        activeLut = resolvedLut,
                        selectedUserPresetId = snapshot.selectedUserPresetId,
                        healStrokes = snapshot.healStrokes,
                        activeHealStroke = null,
                        isHealProcessing = false,
                        isDraggingSlider = false
                    )
                )
            }
            scheduleHistogramSourcePrep(proxyTexture, token)
            _uiState.value.lutFolders
                .filter { it.isExpanded }
                .forEach { loadFolderThumbnailsIfNeeded(it.name, token) }
        } else {
            _uiState.update { state ->
                gradingInput.value = snapshot.gradingParams to resolvedLut
                syncHistoryFlags(
                    state.copy(
                        gradingParams = snapshot.gradingParams,
                        selectedLutId = resolvedLutId,
                        activeLut = resolvedLut,
                        selectedUserPresetId = snapshot.selectedUserPresetId,
                        healStrokes = snapshot.healStrokes,
                        activeHealStroke = null,
                        isHealProcessing = false,
                        isDraggingSlider = false
                    )
                )
            }
        }
    }

    // --- 100% Offline Heal Retouching (Spot Heal, Clone Stamp, Patch / Smooth) ---

    fun setHealMode(mode: HealMode) {
        _uiState.update {
            it.copy(
                healMode = mode,
                isPickingCloneSource = false
            )
        }
    }

    fun setHealBrushSizePx(sizePx: Float) {
        _uiState.update {
            it.copy(
                healBrushSizePx = sizePx.coerceIn(
                    OfflineHealEngine.MIN_BRUSH_SIZE_PX,
                    OfflineHealEngine.MAX_BRUSH_SIZE_PX
                )
            )
        }
    }

    fun setHealFeather(feather: Float) {
        _uiState.update { it.copy(healFeather = feather.coerceIn(0f, 1f)) }
    }

    fun setHealOpacity(opacity: Float) {
        _uiState.update { it.copy(healOpacity = opacity.coerceIn(0.10f, 1f)) }
    }

    fun togglePickCloneSource() {
        _uiState.update { state ->
            state.copy(
                healMode = HealMode.CLONE_STAMP,
                isPickingCloneSource = !state.isPickingCloneSource
            )
        }
    }

    fun setCloneSourcePoint(viewportNx: Float, viewportNy: Float) {
        val state = _uiState.value
        val source = fullSourceBitmap.value
        val clampedU = viewportNx.coerceIn(0f, 1f)
        val clampedV = viewportNy.coerceIn(0f, 1f)
        val sourcePoint = if (source != null && !state.gradingParams.crop.isDefault) {
            GeometryMath.mapOutputUvToSourceUv(
                u = clampedU,
                v = clampedV,
                srcW = source.width,
                srcH = source.height,
                crop = state.gradingParams.crop,
                applyCrop = state.activeTab != StudioTab.CROP
            ) ?: (clampedU to clampedV)
        } else {
            clampedU to clampedV
        }
        _uiState.update {
            it.copy(
                cloneSourcePoint = sourcePoint,
                isPickingCloneSource = false,
                statusBannerMessage = "Clone source set — paint to clone texture"
            )
        }
    }

    fun toggleShowHealMask() {
        _uiState.update { it.copy(showHealMask = !it.showHealMask) }
    }

    fun startHealStroke(viewportNx: Float, viewportNy: Float) {
        val state = _uiState.value
        if (state.isHealProcessing) return
        if (state.isPickingCloneSource) {
            setCloneSourcePoint(viewportNx, viewportNy)
            return
        }
        val radius = state.healBrushRadiusNorm
        val pt = viewportNx.coerceIn(0f, 1f) to viewportNy.coerceIn(0f, 1f)
        _uiState.update {
            it.copy(
                activeHealStroke = HealStroke(
                    mode = state.healMode,
                    points = listOf(pt),
                    radiusNorm = radius,
                    feather = state.healFeather,
                    opacity = state.healOpacity,
                    cloneSourceNorm = state.cloneSourcePoint
                )
            )
        }
    }

    fun appendHealStroke(viewportNx: Float, viewportNy: Float) {
        val current = _uiState.value.activeHealStroke ?: return
        val clamped = viewportNx.coerceIn(0f, 1f) to viewportNy.coerceIn(0f, 1f)
        _uiState.update {
            it.copy(activeHealStroke = current.copy(points = current.points + clamped))
        }
    }

    fun cancelHealStroke() {
        if (_uiState.value.activeHealStroke == null) return
        _uiState.update { it.copy(activeHealStroke = null) }
    }

    fun finishHealStroke() {
        val state = _uiState.value
        val finishedViewportStroke = state.activeHealStroke ?: return
        val source = fullSourceBitmap.value?.takeIf { !it.isRecycled }
        if (source == null) {
            _uiState.update { it.copy(activeHealStroke = null) }
            return
        }

        if (finishedViewportStroke.mode == HealMode.CLONE_STAMP && finishedViewportStroke.cloneSourceNorm == null) {
            _uiState.update {
                it.copy(
                    activeHealStroke = null,
                    statusBannerMessage = "Tap Set Source or tap-and-hold photo to pick Clone Source"
                )
            }
            return
        }

        val mappedStroke = mapViewportStrokeToSourceSpace(
            stroke = finishedViewportStroke,
            source = source,
            crop = state.gradingParams.crop
        )
        if (mappedStroke.points.isEmpty()) {
            _uiState.update { it.copy(activeHealStroke = null) }
            return
        }

        val expectedPhotoGen = photoGeneration.get()
        _uiState.update { it.copy(isHealProcessing = true) }

        healJob?.cancel()
        healJob = viewModelScope.launch {
            var healedBitmap: Bitmap? = null
            try {
                val appContext = getApplication<Application>()
                healedBitmap = withContext(Dispatchers.Default) {
                    OfflineHealEngine.applyStroke(source, mappedStroke, appContext)
                }
                if (photoGeneration.get() != expectedPhotoGen) {
                    if (healedBitmap !== source && !healedBitmap.isRecycled) {
                        healedBitmap.recycle()
                    }
                    _uiState.update { it.copy(activeHealStroke = null, isHealProcessing = false) }
                    return@launch
                }
                val latestState = _uiState.value
                pushUndoSnapshot(captureCurrentSnapshot("Heal: ${mappedStroke.mode.label}"))
                if (healUndoStack.size >= maxHistorySize) {
                    healUndoStack.removeFirst()
                }
                healUndoStack.addLast(HealHistoryState(bitmap = source, strokes = latestState.healStrokes))
                healRedoStack.clear()

                val nextStrokes = latestState.healStrokes + mappedStroke
                applyLoadedSourceBitmap(
                    bitmap = healedBitmap,
                    name = latestState.photoName,
                    clearHealSession = false,
                    nextHealStrokes = nextStrokes
                )
            } catch (e: CancellationException) {
                if (healedBitmap != null && healedBitmap !== source && !healedBitmap.isRecycled) {
                    healedBitmap.recycle()
                }
                _uiState.update { it.copy(activeHealStroke = null, isHealProcessing = false) }
                throw e
            } catch (oom: OutOfMemoryError) {
                Log.e(TAG, "Offline heal stroke ran out of memory", oom)
                _uiState.update {
                    it.copy(
                        activeHealStroke = null,
                        isHealProcessing = false,
                        statusBannerMessage = "Heal skipped: insufficient device memory"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Offline heal stroke failed", e)
                _uiState.update {
                    it.copy(
                        activeHealStroke = null,
                        isHealProcessing = false,
                        statusBannerMessage = "Heal failed: ${e.localizedMessage ?: "Unexpected error"}"
                    )
                }
            }
        }
    }

    fun undoHealStroke() {
        if (_uiState.value.isHealProcessing) return
        val currentBitmap = fullSourceBitmap.value ?: return
        val previous = healUndoStack.removeLastOrNull() ?: return
        val currentStrokes = _uiState.value.healStrokes

        if (healRedoStack.size >= maxHistorySize) {
            healRedoStack.removeFirst()
        }
        healRedoStack.addLast(HealHistoryState(bitmap = currentBitmap, strokes = currentStrokes))
        pushUndoSnapshot(captureCurrentSnapshot("Undo Heal Stroke"))

        applyLoadedSourceBitmap(
            bitmap = previous.bitmap,
            name = _uiState.value.photoName,
            clearHealSession = false,
            nextHealStrokes = previous.strokes
        )
    }

    fun redoHealStroke() {
        if (_uiState.value.isHealProcessing) return
        val currentBitmap = fullSourceBitmap.value ?: return
        val next = healRedoStack.removeLastOrNull() ?: return
        val currentStrokes = _uiState.value.healStrokes

        if (healUndoStack.size >= maxHistorySize) {
            healUndoStack.removeFirst()
        }
        healUndoStack.addLast(HealHistoryState(bitmap = currentBitmap, strokes = currentStrokes))
        pushUndoSnapshot(captureCurrentSnapshot("Redo Heal Stroke"))

        applyLoadedSourceBitmap(
            bitmap = next.bitmap,
            name = _uiState.value.photoName,
            clearHealSession = false,
            nextHealStrokes = next.strokes
        )
    }

    fun resetHeal() {
        if (_uiState.value.isHealProcessing) return
        val initial = initialPhotoSourceBitmap ?: return
        val state = _uiState.value
        if (!state.hasHealEdits && fullSourceBitmap.value === initial) return

        pushUndoSnapshot(captureCurrentSnapshot("Reset Heal"))
        healUndoStack.clear()
        healRedoStack.clear()
        applyLoadedSourceBitmap(
            bitmap = initial,
            name = state.photoName,
            clearHealSession = false,
            nextHealStrokes = emptyList()
        )
        _uiState.update {
            syncHistoryFlags(
                it.copy(
                    cloneSourcePoint = null,
                    isPickingCloneSource = false,
                    statusBannerMessage = "Heal strokes reset"
                )
            )
        }
    }

    private fun mapViewportStrokeToSourceSpace(
        stroke: HealStroke,
        source: Bitmap,
        crop: CropGeometryParams
    ): HealStroke {
        if (crop.isDefault) return stroke
        val eff = GeometryMath.effectiveCropRect(source.width, source.height, crop)
        val scaleFactor = maxOf(eff.width, eff.height).coerceIn(0.15f, 1f)
        val mappedPts = stroke.points.mapNotNull { (u, v) ->
            GeometryMath.mapOutputUvToSourceUv(u, v, source.width, source.height, crop, applyCrop = true)
        }
        return stroke.copy(
            points = mappedPts,
            radiusNorm = (stroke.radiusNorm * scaleFactor).coerceIn(0.004f, 0.15f)
        )
    }

    // --- Dual-Resolution Full-Native Export Pipeline ---

    /**
     * Computes the maximum source-bitmap dimension needed when decoding the original untouched
     * file for export: `null` means decode at 100% native resolution (`ExportResolution.FULL`),
     * whereas smaller export presets allow safe `inSampleSize` decoding even for 200MP camera files.
     */
    private fun computeExportDecodeMaxDim(
        nativeW: Int,
        nativeH: Int,
        params: GradingParams,
        options: ExportOptions
    ): Int? {
        if (options.resolution == ExportResolution.FULL || nativeW <= 0 || nativeH <= 0) {
            return null
        }
        val (fullOutW, fullOutH) = GeometryMath.computeOutputDimensions(nativeW, nativeH, params.crop)
        val (targetOutW, _) = ImageIoHelper.computeTargetDimensions(fullOutW, fullOutH, options.resolution)
        if (fullOutW <= 0 || targetOutW >= fullOutW) return null
        val scale = (targetOutW.toFloat() / fullOutW.toFloat()).coerceIn(0.05f, 1f)
        val sw = (nativeW * scale).toInt().coerceAtLeast(1)
        val sh = (nativeH * scale).toInt().coerceAtLeast(1)
        return max(sw, sh)
    }

    private fun prepareExportSource(source: Bitmap, params: GradingParams, options: ExportOptions): Bitmap {
        val (fullOutW, fullOutH) = GeometryMath.computeOutputDimensions(source.width, source.height, params.crop)
        val (targetOutW, _) = ImageIoHelper.computeTargetDimensions(fullOutW, fullOutH, options.resolution)
        if (fullOutW <= 0 || targetOutW >= fullOutW) return source
        val scale = (targetOutW.toFloat() / fullOutW.toFloat()).coerceIn(0.05f, 1f)
        val sw = (source.width * scale).toInt().coerceAtLeast(1)
        val sh = (source.height * scale).toInt().coerceAtLeast(1)
        return source.scale(sw, sh)
    }

    /**
     * Executes the full-resolution export pipeline off the main thread:
     * 1. Decodes the untouched original source URI/resource at 100% native resolution (or target export cap).
     * 2. Replays all recorded offline Heal strokes at native resolution.
     * 3. Renders the exact same OpenGL shader uniforms, crop matrix, and LUT at full resolution.
     * 4. Saves to destination and recycles all temporary native/intermediate bitmaps cleanly.
     */
    private suspend fun renderNativeExportBitmap(
        proxyFallback: Bitmap,
        sourceRef: PhotoSourceRef?,
        nativeW: Int,
        nativeH: Int,
        params: GradingParams,
        lut: CubeLut?,
        healStrokes: List<HealStroke>,
        options: ExportOptions,
        saveBlock: suspend (Bitmap) -> ExportSaveResult
    ): Triple<ExportSaveResult, Int, Int> {
        var decodedNative: Bitmap? = null
        var healedNative: Bitmap? = null
        var scaledTarget: Bitmap? = null
        var gradedOutput: Bitmap? = null

        try {
            val baseSource = if (sourceRef != null) {
                val maxDecodeDim = computeExportDecodeMaxDim(nativeW, nativeH, params, options)
                val decoded = try {
                    ImageIoHelper.loadNativeForExport(
                        context = getApplication(),
                        sourceRef = sourceRef,
                        targetMaxSourceDim = maxDecodeDim
                    )
                } catch (sec: SecurityException) {
                    Log.w(TAG, "URI read grant expired before export; falling back to 2048px proxy", sec)
                    null
                } catch (ise: IllegalStateException) {
                    if (ise.cause is SecurityException) {
                        Log.w(TAG, "URI permission revoked before export; falling back to 2048px proxy", ise)
                        null
                    } else {
                        throw ise
                    }
                }
                if (decoded != null) {
                    decodedNative = decoded
                    if (healStrokes.isNotEmpty()) {
                        val appContext = getApplication<Application>()
                        val healed = withContext(Dispatchers.Default) {
                            OfflineHealEngine.applyAllStrokes(decoded, healStrokes, appContext)
                        }
                        healedNative = healed
                        healed
                    } else {
                        decoded
                    }
                } else {
                    proxyFallback
                }
            } else {
                proxyFallback
            }

            val targetSource = withContext(Dispatchers.Default) {
                prepareExportSource(baseSource, params, options)
            }
            if (targetSource !== baseSource && targetSource !== proxyFallback) {
                scaledTarget = targetSource
            }

            val rendered = GlOffscreenRenderer.renderFullResolution(targetSource, params, lut)
            gradedOutput = rendered
            val outW = rendered.width
            val outH = rendered.height
            val saved = saveBlock(rendered)
            return Triple(saved, outW, outH)
        } finally {
            val ownedTemps = listOfNotNull(gradedOutput, scaledTarget, healedNative, decodedNative)
            ownedTemps.forEach { bmp ->
                if (bmp !== proxyFallback && bmp !== initialPhotoSourceBitmap && !bmp.isRecycled) {
                    bmp.recycle()
                }
            }
        }
    }

    /**
     * Immediately renders and writes the current edited photo to public device storage via MediaStore
     * (`Pictures/ZamLab` for JPG/PNG/WebP, `Downloads/ZamLab` for PDF/16-bit TIFF) and returns the
     * newly published public `content://` [Uri].
     */
    suspend fun exportToMediaStore(
        options: ExportOptions,
        openShareImmediately: Boolean = false
    ): Uri? {
        val proxyFallback = fullSourceBitmap.value?.takeIf { !it.isRecycled } ?: return null
        val sourceRef = activePhotoSourceRef
        val nativeW = nativePhotoWidth
        val nativeH = nativePhotoHeight
        val state = _uiState.value
        val params = state.gradingParams
        val lut = state.activeLut
        val strokes = state.healStrokes

        _uiState.update { it.copy(exportStatus = ExportStatus.Exporting) }
        return try {
            val (saved, outW, outH) = renderNativeExportBitmap(
                proxyFallback = proxyFallback,
                sourceRef = sourceRef,
                nativeW = nativeW,
                nativeH = nativeH,
                params = params,
                lut = lut,
                healStrokes = strokes,
                options = options
            ) { gradedBitmap ->
                ImageIoHelper.saveToGalleryMediaStore(
                    context = getApplication(),
                    bitmap = gradedBitmap,
                    options = options
                )
            }
            val success = ExportStatus.Success(
                filename = saved.filename,
                width = outW,
                height = outH,
                uri = saved.uri,
                mimeType = saved.mimeType,
                format = saved.format,
                destinationLabel = saved.destinationLabel,
                openShareImmediately = openShareImmediately
            )
            _uiState.update {
                it.copy(
                    exportStatus = success,
                    exportConfirmationToast = success
                )
            }
            saved.uri
        } catch (e: CancellationException) {
            throw e
        } catch (oom: OutOfMemoryError) {
            Log.e(TAG, "Gallery export out of memory", oom)
            val msg = "Insufficient memory for full-resolution export. Try 2048 px or 1080 px resolution."
            _uiState.update {
                it.copy(
                    exportStatus = ExportStatus.Error(msg),
                    statusBannerMessage = if (openShareImmediately) msg else it.statusBannerMessage
                )
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Gallery export failed", e)
            val msg = e.localizedMessage ?: "Export failed."
            _uiState.update {
                it.copy(
                    exportStatus = ExportStatus.Error(msg),
                    statusBannerMessage = if (openShareImmediately) msg else it.statusBannerMessage
                )
            }
            null
        }
    }

    fun exportToGallery(options: ExportOptions, openShareImmediately: Boolean = false) {
        if (_uiState.value.exportStatus is ExportStatus.Exporting) return
        viewModelScope.launch {
            exportToMediaStore(options = options, openShareImmediately = openShareImmediately)
        }
    }

    /**
     * One-tap direct share from the top app bar: immediately writes full-resolution JPG
     * to `Pictures/ZamLab` via MediaStore and opens the Android share sheet with the returned Uri.
     */
    fun exportAndShareNow() {
        exportToGallery(
            options = ExportOptions(
                format = ExportFormat.JPG,
                quality = 95,
                resolution = ExportResolution.FULL
            ),
            openShareImmediately = true
        )
    }

    fun exportToDocumentUri(uri: Uri, options: ExportOptions) {
        if (_uiState.value.exportStatus is ExportStatus.Exporting) return
        val proxyFallback = fullSourceBitmap.value?.takeIf { !it.isRecycled } ?: return
        val sourceRef = activePhotoSourceRef
        val nativeW = nativePhotoWidth
        val nativeH = nativePhotoHeight
        val state = _uiState.value
        val params = state.gradingParams
        val lut = state.activeLut
        val strokes = state.healStrokes

        viewModelScope.launch {
            _uiState.update { it.copy(exportStatus = ExportStatus.Exporting) }
            try {
                val (saved, outW, outH) = renderNativeExportBitmap(
                    proxyFallback = proxyFallback,
                    sourceRef = sourceRef,
                    nativeW = nativeW,
                    nativeH = nativeH,
                    params = params,
                    lut = lut,
                    healStrokes = strokes,
                    options = options
                ) { gradedBitmap ->
                    ImageIoHelper.saveToDocumentUri(
                        context = getApplication(),
                        bitmap = gradedBitmap,
                        uri = uri,
                        options = options
                    )
                }
                val success = ExportStatus.Success(
                    filename = saved.filename,
                    width = outW,
                    height = outH,
                    uri = saved.uri,
                    mimeType = saved.mimeType,
                    format = saved.format,
                    destinationLabel = saved.destinationLabel
                )
                _uiState.update {
                    it.copy(
                        exportStatus = success,
                        exportConfirmationToast = success
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (oom: OutOfMemoryError) {
                Log.e(TAG, "Document export out of memory", oom)
                _uiState.update {
                    it.copy(
                        exportStatus = ExportStatus.Error(
                            "Insufficient memory for full-resolution export. Try 2048 px or 1080 px resolution."
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Document export failed", e)
                _uiState.update {
                    it.copy(exportStatus = ExportStatus.Error(e.localizedMessage ?: "Export failed."))
                }
            }
        }
    }

    fun dismissExportStatus() {
        _uiState.update { it.copy(exportStatus = ExportStatus.Idle) }
    }

    fun dismissExportConfirmationToast() {
        _uiState.update { it.copy(exportConfirmationToast = null) }
    }

    fun dismissStatusBanner() {
        _uiState.update { it.copy(statusBannerMessage = null) }
    }
}
