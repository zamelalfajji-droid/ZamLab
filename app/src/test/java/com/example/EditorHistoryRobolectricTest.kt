package com.example

import android.app.Application
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.ai.LocalInpaintingEngine
import com.example.editor.CameraProfiles
import com.example.editor.ColorGradingData
import com.example.editor.ColorGradingEngine
import com.example.editor.ColorWheelValue
import com.example.editor.CopiedEdits
import com.example.editor.CropAspectPreset
import com.example.editor.CropGeometryParams
import com.example.editor.CropOverlayMode
import com.example.editor.EditorViewModel
import com.example.editor.ExportFormat
import com.example.editor.ExportOptions
import com.example.editor.ExportResolution
import com.example.editor.EyedropperMode
import com.example.editor.GeometryMath
import com.example.editor.GradingParams
import com.example.editor.HealMode
import com.example.editor.HealStroke
import com.example.editor.ImageIoHelper
import com.example.editor.LocalMask
import com.example.editor.MaskType
import com.example.editor.NormalizedCropRect
import com.example.editor.OfflineHealEngine
import com.example.editor.PhotoSourceRef
import com.example.editor.PointColorData
import com.example.editor.StudioTab
import com.example.editor.ToneCurveChannel
import com.example.editor.ToneCurveData
import com.example.editor.ToneCurvePoint
import com.example.editor.VignetteStyle
import com.example.editor.WhiteBalancePreset
import com.example.editor.evaluateLocalMaskWeight
import com.example.gl.DirtyRenderGate
import com.example.gl.GlRenderState
import com.example.lut.LutSourceFormat
import com.example.lut.UserPreset
import com.example.lut.UserPresetStore
import com.example.lut.XmpPresetParser
import com.example.ui.components.ViewportZoomMath
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EditorHistoryRobolectricTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private suspend fun awaitInitialLoad(vm: EditorViewModel) {
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            testDispatcher.scheduler.advanceUntilIdle()
            val state = vm.uiState.value
            if (state.fullWidth > 0 && state.lutFolders.isNotEmpty()) {
                return
            }
            Thread.sleep(20)
        }
        throw AssertionError(
            "Timed out waiting for initial load: fullWidth=${vm.uiState.value.fullWidth}, folders=${vm.uiState.value.lutFolders.size}"
        )
    }

    private suspend fun awaitFolderLoaded(vm: EditorViewModel, folderName: String) {
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            testDispatcher.scheduler.advanceUntilIdle()
            val folder = vm.uiState.value.lutFolders.firstOrNull { it.name == folderName }
            if (folder != null && !folder.isLoading && folder.isLoadedForCurrentPhoto) {
                return
            }
            Thread.sleep(20)
        }
        val f = vm.uiState.value.lutFolders.firstOrNull { it.name == folderName }
        throw AssertionError("Timed out waiting for folder '$folderName': loading=${f?.isLoading}, loaded=${f?.isLoadedForCurrentPhoto}")
    }

    @Test
    fun `folders start collapsed with zero startup XMP parsing and lazy-load only when expanded`() = runTest(testDispatcher) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = EditorViewModel(app)
        awaitInitialLoad(vm)

        val folders = vm.uiState.value.lutFolders
        assertTrue("Expected Built-in LUTs and XMP folders", folders.size >= 8)
        assertTrue(folders.any { it.name == "Built-in LUTs" })
        assertTrue(folders.any { it.name == "35mm" })

        // All folder bars collapsed by default
        assertTrue(folders.none { it.isExpanded })
        // Zero startup parsing or thumbnail rendering for closed folders
        assertTrue(folders.all { !it.isLoadedForCurrentPhoto })
        assertTrue(folders.flatMap { it.items }.all { it.lut == null && it.previewThumbnail == null })

        // Expanding one folder loads only that folder's presets & thumbnails on background thread
        vm.toggleLutFolder("35mm")
        awaitFolderLoaded(vm, "35mm")

        val expandedFolder = vm.uiState.value.lutFolders.first { it.name == "35mm" }
        assertTrue(expandedFolder.isExpanded)
        assertTrue(expandedFolder.items.all { it.lut != null && it.previewThumbnail != null })

        // Other closed folders stay unparsed with zero thumbnails rendered
        val closedFolders = vm.uiState.value.lutFolders.filter { it.name != "35mm" }
        assertTrue(closedFolders.all { !it.isExpanded && !it.isLoadedForCurrentPhoto })
        assertTrue(closedFolders.flatMap { it.items }.all { it.lut == null && it.previewThumbnail == null })

        // Collapsing and re-expanding serves cached thumbnails immediately
        val cachedThumb = expandedFolder.items.first().previewThumbnail
        vm.toggleLutFolder("35mm")
        assertFalse(vm.uiState.value.lutFolders.first { it.name == "35mm" }.isExpanded)

        vm.toggleLutFolder("35mm")
        val reExpanded = vm.uiState.value.lutFolders.first { it.name == "35mm" }
        assertTrue(reExpanded.isExpanded)
        assertFalse(reExpanded.isLoading)
        assertTrue(reExpanded.items.first().previewThumbnail === cachedThumb)
    }

    @Test
    fun `slider drag batches into single undo step and supports redo`() = runTest(testDispatcher) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = EditorViewModel(app)
        awaitInitialLoad(vm)

        assertFalse(vm.uiState.value.canUndo)
        assertFalse(vm.uiState.value.canRedo)

        vm.setSliderDragging(true)
        vm.updateGradingParams { it.copy(exposure = 0.5f) }
        vm.updateGradingParams { it.copy(exposure = 1.25f) }
        vm.setSliderDragging(false)

        assertTrue(vm.uiState.value.canUndo)
        assertFalse(vm.uiState.value.canRedo)
        assertEquals(1.25f, vm.uiState.value.gradingParams.exposure, 0.001f)

        vm.undo()
        assertEquals(0f, vm.uiState.value.gradingParams.exposure, 0.001f)
        assertFalse(vm.uiState.value.canUndo)
        assertTrue(vm.uiState.value.canRedo)

        vm.redo()
        assertEquals(1.25f, vm.uiState.value.gradingParams.exposure, 0.001f)
        assertTrue(vm.uiState.value.canUndo)
        assertFalse(vm.uiState.value.canRedo)
    }

    @Test
    fun `lut presets and heal strokes support multi-step undo and redo`() = runTest(testDispatcher) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = EditorViewModel(app)
        awaitInitialLoad(vm)

        val targetFolder = vm.uiState.value.lutFolders.first { it.totalCount >= 2 }.name
        vm.toggleLutFolder(targetFolder)
        awaitFolderLoaded(vm, targetFolder)

        val loadedItems = vm.uiState.value.lutFolders.first { it.name == targetFolder }.items
        val firstLutId = loadedItems[0].id
        val secondLutId = loadedItems[1].id

        vm.selectLut(firstLutId)
        assertEquals(firstLutId, vm.uiState.value.selectedLutId)

        vm.selectLut(secondLutId)
        assertEquals(secondLutId, vm.uiState.value.selectedLutId)

        vm.setHealMode(HealMode.SPOT_HEAL)
        vm.startHealStroke(0.2f, 0.3f)
        vm.appendHealStroke(0.35f, 0.4f)
        vm.finishHealStroke()
        val healDeadline = System.currentTimeMillis() + 8_000L
        while (System.currentTimeMillis() < healDeadline &&
            (vm.uiState.value.isHealProcessing || vm.uiState.value.healStrokes.isEmpty())
        ) {
            testDispatcher.scheduler.advanceUntilIdle()
            Thread.sleep(20)
        }
        assertEquals(1, vm.uiState.value.healStrokes.size)

        vm.resetHeal()
        assertEquals(0, vm.uiState.value.healStrokes.size)

        // Undo resetHeal -> restores 1 heal stroke
        vm.undo()
        assertEquals(1, vm.uiState.value.healStrokes.size)

        // Undo heal stroke -> 0 heal strokes
        vm.undo()
        assertEquals(0, vm.uiState.value.healStrokes.size)

        // Undo secondLutId -> back to firstLutId
        vm.undo()
        assertEquals(firstLutId, vm.uiState.value.selectedLutId)

        // Undo firstLutId -> no LUT selected
        vm.undo()
        assertNull(vm.uiState.value.selectedLutId)
        assertFalse(vm.uiState.value.canUndo)
        assertTrue(vm.uiState.value.canRedo)

        // Redo all the way forward
        vm.redo()
        assertEquals(firstLutId, vm.uiState.value.selectedLutId)
        vm.redo()
        assertEquals(secondLutId, vm.uiState.value.selectedLutId)
        vm.redo()
        assertEquals(1, vm.uiState.value.healStrokes.size)
        vm.redo()
        assertEquals(0, vm.uiState.value.healStrokes.size)
        assertFalse(vm.uiState.value.canRedo)
    }

    @Test
    fun `new edit after undo clears redo stack and resetAllAdjustments is undoable`() = runTest(testDispatcher) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = EditorViewModel(app)
        awaitInitialLoad(vm)

        vm.setSliderDragging(true)
        vm.updateGradingParams { it.copy(contrast = 35f) }
        vm.setSliderDragging(false)

        vm.resetAllAdjustments()
        assertEquals(0f, vm.uiState.value.gradingParams.contrast, 0.001f)
        assertTrue(vm.uiState.value.canUndo)

        vm.undo()
        assertEquals(35f, vm.uiState.value.gradingParams.contrast, 0.001f)
        assertTrue(vm.uiState.value.canRedo)

        // Performing a new edit clears redo stack
        vm.setSliderDragging(true)
        vm.updateGradingParams { it.copy(saturation = -20f) }
        vm.setSliderDragging(false)
        assertFalse(vm.uiState.value.canRedo)
    }

    @Test
    fun `split compare toggle and ratio clamp behave properly`() = runTest(testDispatcher) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = EditorViewModel(app)
        awaitInitialLoad(vm)

        assertFalse(vm.uiState.value.isSplitCompareEnabled)
        assertEquals(0.5f, vm.uiState.value.splitCompareRatio, 0.001f)

        vm.setComparingOriginal(true)
        vm.toggleSplitCompare()
        assertTrue(vm.uiState.value.isSplitCompareEnabled)
        assertFalse(vm.uiState.value.isComparingOriginal)

        vm.setSplitCompareRatio(0.0f)
        assertEquals(0.05f, vm.uiState.value.splitCompareRatio, 0.001f)
        vm.setSplitCompareRatio(1.5f)
        assertEquals(0.95f, vm.uiState.value.splitCompareRatio, 0.001f)
        vm.setSplitCompareRatio(0.62f)
        assertEquals(0.62f, vm.uiState.value.splitCompareRatio, 0.001f)
    }

    @Test
    fun `export resolution and quality options compute accurate dimensions and formats`() {
        assertEquals(
            4000 to 3000,
            ImageIoHelper.computeTargetDimensions(4000, 3000, ExportResolution.FULL)
        )
        assertEquals(
            2048 to 1536,
            ImageIoHelper.computeTargetDimensions(4000, 3000, ExportResolution.PX_2048)
        )
        assertEquals(
            1080 to 810,
            ImageIoHelper.computeTargetDimensions(4000, 3000, ExportResolution.PX_1080)
        )
        assertEquals(
            2000 to 1500,
            ImageIoHelper.computeTargetDimensions(4000, 3000, ExportResolution.HALF)
        )
        assertEquals(
            1200 to 800,
            ImageIoHelper.computeTargetDimensions(1200, 800, ExportResolution.PX_2048)
        )

        assertEquals(85, ExportOptions(ExportFormat.JPG, quality = 85).effectiveQuality)
        assertEquals(80, ExportOptions(ExportFormat.WEBP, quality = 80).effectiveQuality)
        assertEquals(100, ExportOptions(ExportFormat.PNG, quality = 60).effectiveQuality)
        assertEquals(10, ExportOptions(ExportFormat.WEBP, quality = 1).effectiveQuality)
    }

    @Test
    fun `selecting XMP preset populates Adjust sliders and supports fine-tuning and intensity scaling`() = runTest(testDispatcher) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = EditorViewModel(app)
        awaitInitialLoad(vm)

        val xmpFolder = vm.uiState.value.lutFolders.first { folder ->
            folder.descriptors.any { it.sourceFormat == LutSourceFormat.XMP }
        }.name
        vm.toggleLutFolder(xmpFolder)
        awaitFolderLoaded(vm, xmpFolder)

        val xmpPreset = vm.uiState.value.lutFolders.first { it.name == xmpFolder }.items.first()
        val xmpSettings = requireNotNull(xmpPreset.lut?.xmpSettings)
        val expectedDefault = GradingParams.fromXmpSettings(xmpSettings, 0.75f)

        vm.selectLut(xmpPreset.id)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(xmpPreset.id, vm.uiState.value.selectedLutId)
        assertNotNull(vm.uiState.value.activeLut)
        assertEquals(0.75f, vm.uiState.value.gradingParams.lutIntensity, 0.001f)
        assertEquals(expectedDefault.exposure, vm.uiState.value.gradingParams.exposure, 0.001f)
        assertEquals(expectedDefault.contrast, vm.uiState.value.gradingParams.contrast, 0.001f)
        assertEquals(expectedDefault.clarity, vm.uiState.value.gradingParams.clarity, 0.001f)
        assertEquals(expectedDefault.grain, vm.uiState.value.gradingParams.grain, 0.001f)

        // Fine-tune one slider after applying the XMP preset without losing the preset's other sliders
        vm.setSliderDragging(true)
        vm.updateGradingParams { it.copy(exposure = 0.77f) }
        vm.setSliderDragging(false)
        assertEquals(0.77f, vm.uiState.value.gradingParams.exposure, 0.001f)
        assertEquals(expectedDefault.grain, vm.uiState.value.gradingParams.grain, 0.001f)

        // Scaling preset intensity rescales from xmpSettings
        vm.setSliderDragging(true)
        vm.updateLutIntensity(0.5f)
        vm.setSliderDragging(false)
        val expectedHalf = GradingParams.fromXmpSettings(xmpSettings, 0.5f)
        assertEquals(0.5f, vm.uiState.value.gradingParams.lutIntensity, 0.001f)
        assertEquals(expectedHalf.contrast, vm.uiState.value.gradingParams.contrast, 0.01f)
        assertEquals(expectedHalf.grain, vm.uiState.value.gradingParams.grain, 0.01f)

        // Deselecting XMP preset clears the preset's sliders cleanly and is undoable
        vm.selectLut(null)
        assertNull(vm.uiState.value.selectedLutId)
        assertTrue(vm.uiState.value.gradingParams.isDefault)

        vm.undo()
        assertEquals(xmpPreset.id, vm.uiState.value.selectedLutId)
    }

    @Test
    fun `auto tone, B and W toggle, and copy paste edits work across resets`() = runTest(testDispatcher) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = EditorViewModel(app)
        awaitInitialLoad(vm)

        vm.applyAutoTone()
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline && !vm.uiState.value.canUndo) {
            testDispatcher.scheduler.advanceUntilIdle()
            Thread.sleep(20)
        }
        assertTrue(vm.uiState.value.gradingParams.hasLightEdits || vm.uiState.value.gradingParams.hasColorEdits)
        assertTrue(vm.uiState.value.canUndo)

        vm.toggleBlackAndWhite()
        assertTrue(vm.uiState.value.gradingParams.isGrayscale)
        vm.toggleBlackAndWhite()
        assertFalse(vm.uiState.value.gradingParams.isGrayscale)

        vm.setSliderDragging(true)
        vm.updateGradingParams {
            it.copy(
                exposure = 0.45f,
                clarity = 22f,
                vignette = -18f,
                sharpening = 35f
            )
        }
        vm.setSliderDragging(false)
        assertFalse(vm.uiState.value.canPasteEdits)
        vm.copyEdits()
        assertTrue(vm.uiState.value.canPasteEdits)

        vm.resetAllAdjustments()
        assertTrue(vm.uiState.value.gradingParams.isDefault)

        vm.pasteEdits()
        val pasteDeadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < pasteDeadline && vm.uiState.value.gradingParams.exposure != 0.45f) {
            testDispatcher.scheduler.advanceUntilIdle()
            Thread.sleep(20)
        }
        assertEquals(0.45f, vm.uiState.value.gradingParams.exposure, 0.001f)
        assertEquals(22f, vm.uiState.value.gradingParams.clarity, 0.001f)
        assertEquals(-18f, vm.uiState.value.gradingParams.vignette, 0.001f)
        assertEquals(35f, vm.uiState.value.gradingParams.sharpening, 0.001f)
    }

    @Test
    fun `camera profiles apply non-destructively, support favorites, recents, amount, and lazy browser thumbnails`() = runTest(testDispatcher) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("zamlight_profile_prefs", android.content.Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()

        val vm = EditorViewModel(app)
        awaitInitialLoad(vm)

        // User edits Light, Color, Effects, and Curve sliders first
        vm.setSliderDragging(true)
        vm.updateGradingParams {
            it.copy(
                exposure = 0.65f,
                contrast = 18f,
                temperature = -14f,
                clarity = 24f,
                toneCurve = it.toneCurve.withChannel(
                    com.example.editor.ToneCurveChannel.MASTER,
                    listOf(
                        ToneCurvePoint(0f, 0f),
                        ToneCurvePoint(0.5f, 0.62f),
                        ToneCurvePoint(1f, 1f)
                    )
                )
            )
        }
        vm.setSliderDragging(false)

        // Selecting a Profile must NOT reset or alter any existing sliders
        vm.selectProfile("profile_teal_amber")
        val p1 = vm.uiState.value.gradingParams
        assertEquals("profile_teal_amber", p1.profileId)
        assertEquals(1f, p1.profileAmount, 0.001f)
        assertEquals(0.65f, p1.exposure, 0.001f)
        assertEquals(18f, p1.contrast, 0.001f)
        assertEquals(-14f, p1.temperature, 0.001f)
        assertEquals(24f, p1.clarity, 0.001f)
        assertFalse(p1.toneCurve.isDefault)
        assertEquals(listOf("profile_teal_amber"), vm.uiState.value.recentProfileIds)

        // Adjusting Profile Amount preserves other sliders and scales 0..2
        vm.setSliderDragging(true)
        vm.updateProfileAmount(1.45f)
        vm.setSliderDragging(false)
        assertEquals(1.45f, vm.uiState.value.gradingParams.profileAmount, 0.001f)
        assertEquals(0.65f, vm.uiState.value.gradingParams.exposure, 0.001f)

        // Star favorites & verify persistence
        vm.toggleFavoriteProfile("profile_teal_amber")
        vm.toggleFavoriteProfile("profile_warm_print")
        assertTrue("profile_teal_amber" in vm.uiState.value.favoriteProfileIds)
        assertTrue("profile_warm_print" in vm.uiState.value.favoriteProfileIds)

        // Unfavorite & verify persisted across new ViewModel instance
        vm.toggleFavoriteProfile("profile_warm_print")
        assertFalse("profile_warm_print" in vm.uiState.value.favoriteProfileIds)

        val vm2 = EditorViewModel(app)
        awaitInitialLoad(vm2)
        assertEquals(listOf("profile_teal_amber"), vm2.uiState.value.recentProfileIds)
        assertTrue("profile_teal_amber" in vm2.uiState.value.favoriteProfileIds)

        // Lazy visual browser thumbnails only populate when requested
        assertTrue(vm.uiState.value.profileThumbnails.isEmpty())
        vm.ensureProfileThumbnailsLoaded()
        val thumbDeadline = System.currentTimeMillis() + 8_000L
        while (System.currentTimeMillis() < thumbDeadline &&
            vm.uiState.value.profileThumbnails.size < CameraProfiles.ALL.size
        ) {
            testDispatcher.scheduler.advanceUntilIdle()
            Thread.sleep(25)
        }
        assertEquals(CameraProfiles.ALL.size, vm.uiState.value.profileThumbnails.size)

        // Verify GPU-mirrored CPU engine renders monochrome & creative profiles accurately
        val src = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(220, 90, 40))
        }
        val monoOut = ColorGradingEngine.processFullResolution(
            src,
            GradingParams(profileId = "profile_mono_red_filter", profileAmount = 1f),
            null
        )
        val monoPx = monoOut.getPixel(1, 1)
        assertEquals(Color.red(monoPx), Color.green(monoPx))
        assertEquals(Color.green(monoPx), Color.blue(monoPx))

        // Verify user Warmth slider still tints on top of a monochrome base profile
        val warmedMonoOut = ColorGradingEngine.processFullResolution(
            src,
            GradingParams(profileId = "profile_mono_red_filter", profileAmount = 1f, temperature = 45f),
            null
        )
        val warmedPx = warmedMonoOut.getPixel(1, 1)
        assertTrue("Expected Temp +45 to warm Mono base (R > B)", Color.red(warmedPx) > Color.blue(warmedPx))
    }

    @Test
    fun `white balance presets, auto WB, and eyedropper picking update params and history`() = runTest(testDispatcher) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = EditorViewModel(app)
        awaitInitialLoad(vm)

        vm.selectWhiteBalancePreset(WhiteBalancePreset.TUNGSTEN)
        assertEquals(WhiteBalancePreset.TUNGSTEN, vm.uiState.value.gradingParams.whiteBalancePreset)
        assertEquals(WhiteBalancePreset.TUNGSTEN.temperature, vm.uiState.value.gradingParams.temperature, 0.01f)
        assertEquals(WhiteBalancePreset.TUNGSTEN.tint, vm.uiState.value.gradingParams.tint, 0.01f)
        assertTrue(vm.uiState.value.canUndo)

        // Auto WB analyzes image and populates temperature & tint
        vm.selectWhiteBalancePreset(WhiteBalancePreset.AUTO)
        val autoDeadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < autoDeadline &&
            vm.uiState.value.gradingParams.whiteBalancePreset != WhiteBalancePreset.AUTO
        ) {
            testDispatcher.scheduler.advanceUntilIdle()
            Thread.sleep(20)
        }
        assertEquals(WhiteBalancePreset.AUTO, vm.uiState.value.gradingParams.whiteBalancePreset)

        // Eyedropper mode toggle + auto-clear on tab switch
        vm.toggleEyedropperMode(EyedropperMode.WHITE_BALANCE)
        assertEquals(EyedropperMode.WHITE_BALANCE, vm.uiState.value.activeEyedropperMode)
        vm.selectTab(StudioTab.LUTS)
        assertEquals(EyedropperMode.NONE, vm.uiState.value.activeEyedropperMode)
        vm.selectTab(StudioTab.ADJUST)

        // Canvas eyedropper pick sets Custom WB and clears eyedropper mode
        vm.toggleEyedropperMode(EyedropperMode.WHITE_BALANCE)
        vm.pickColorFromCanvas(0.5f, 0.5f)
        val wbDeadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < wbDeadline &&
            vm.uiState.value.activeEyedropperMode != EyedropperMode.NONE
        ) {
            testDispatcher.scheduler.advanceUntilIdle()
            Thread.sleep(20)
        }
        assertEquals(EyedropperMode.NONE, vm.uiState.value.activeEyedropperMode)
        assertEquals(WhiteBalancePreset.CUSTOM, vm.uiState.value.gradingParams.whiteBalancePreset)

        // Undo reverts WB Eyedropper
        vm.undo()
        assertEquals(WhiteBalancePreset.AUTO, vm.uiState.value.gradingParams.whiteBalancePreset)

        // Point Color eyedropper samples pixel & populates PointColorData
        vm.toggleEyedropperMode(EyedropperMode.POINT_COLOR)
        vm.pickColorFromCanvas(0.25f, 0.25f)
        val pcDeadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < pcDeadline &&
            !vm.uiState.value.gradingParams.pointColor.isSampled
        ) {
            testDispatcher.scheduler.advanceUntilIdle()
            Thread.sleep(20)
        }
        assertEquals(EyedropperMode.NONE, vm.uiState.value.activeEyedropperMode)
        assertTrue(vm.uiState.value.gradingParams.pointColor.isSampled)
    }

    @Test
    fun `Point Color and 3-Way Color Grading wheels isolate hues and tonal ranges in render engine`() = runTest(testDispatcher) {
        // 1. Point Color isolation: target pure blue (hue ~224°) and shift hue +60 / saturation +25; pure red (~0°) stays untouched
        val colorTestBmp = Bitmap.createBitmap(24, 12, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until 12) {
                for (x in 0 until 12) {
                    setPixel(x, y, Color.rgb(30, 80, 220)) // Blue patch
                }
                for (x in 12 until 24) {
                    setPixel(x, y, Color.rgb(220, 45, 35)) // Red patch
                }
            }
        }
        val sampleBlue = ColorGradingEngine.sampleNormalizedPatch(colorTestBmp, 0.15f, 0.5f, null)
        assertTrue("Expected sampled blue hue around 210-250°, got ${sampleBlue.hueDeg}", sampleBlue.hueDeg in 200f..260f)

        val pointColorParams = GradingParams(
            pointColor = PointColorData(
                sourceHueDeg = sampleBlue.hueDeg,
                sourceSat = sampleBlue.sat,
                sourceLuma = sampleBlue.luma,
                swatchArgb = sampleBlue.swatchArgb,
                isSampled = true,
                hueShift = 60f,
                saturationShift = 25f,
                range = 50f
            )
        )
        val pcOut = ColorGradingEngine.processFullResolution(colorTestBmp, pointColorParams, null)
        val shiftedBlue = pcOut.getPixel(3, 6)
        val untouchedRed = pcOut.getPixel(20, 6)
        // Red patch should stay essentially unchanged (within 2 LSB)
        assertEquals(220f, Color.red(untouchedRed).toFloat(), 2f)
        assertEquals(45f, Color.green(untouchedRed).toFloat(), 2f)
        // Blue patch should shift hue significantly (red or green channel changes cleanly)
        assertTrue(
            "Expected targeted blue pixel to shift color",
            kotlin.math.abs(Color.red(shiftedBlue) - 30) > 15 || kotlin.math.abs(Color.green(shiftedBlue) - 80) > 15
        )

        // 2. 3-Way Color Grading Wheels: cool blue tint in Shadows, warm amber tint in Highlights
        val toneBmp = Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888).apply {
            for (x in 0..1) {
                setPixel(x, 0, Color.rgb(35, 35, 35)) // Dark shadow gray
                setPixel(x, 1, Color.rgb(35, 35, 35))
            }
            for (x in 2..3) {
                setPixel(x, 0, Color.rgb(210, 210, 210)) // Bright highlight gray
                setPixel(x, 1, Color.rgb(210, 210, 210))
            }
        }
        val cgParams = GradingParams(
            colorGrading = ColorGradingData(
                shadows = ColorWheelValue(hueDeg = 240f, saturation = 80f, luminance = 0f),
                highlights = ColorWheelValue(hueDeg = 35f, saturation = 80f, luminance = 0f),
                blending = 50f,
                balance = 0f
            )
        )
        val cgOut = ColorGradingEngine.processFullResolution(toneBmp, cgParams, null)
        val shadowPx = cgOut.getPixel(0, 0)
        val highlightPx = cgOut.getPixel(3, 0)
        assertTrue(
            "Expected blue shadows (B > R), got R=${Color.red(shadowPx)} B=${Color.blue(shadowPx)}",
            Color.blue(shadowPx) > Color.red(shadowPx) + 8
        )
        assertTrue(
            "Expected warm highlights (R > B), got R=${Color.red(highlightPx)} B=${Color.blue(highlightPx)}",
            Color.red(highlightPx) > Color.blue(highlightPx) + 8
        )
    }

    @Test
    fun `app declares zero INTERNET permission and replaces Magic Eraser with offline Heal tab`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        @Suppress("DEPRECATION")
        val pkgInfo = app.packageManager.getPackageInfo(
            app.packageName,
            PackageManager.GET_PERMISSIONS
        )
        val requestedPermissions = pkgInfo.requestedPermissions?.toList().orEmpty()
        assertFalse(
            "Expected 100% offline manifest with no INTERNET permission, found $requestedPermissions",
            android.Manifest.permission.INTERNET in requestedPermissions
        )
        assertEquals(
            listOf(StudioTab.ADJUST, StudioTab.ALL, StudioTab.LUTS, StudioTab.CROP, StudioTab.HEAL, StudioTab.MASK),
            StudioTab.entries
        )
    }

    @Test
    fun `LocalInpaintingEngine preserves 100 percent of unmasked pixels bit-for-bit with crisp structure fill`() {
        val w = 200
        val h = 200
        // Sharp multi-frequency cobblestone/brick checkerboard on left half + fine stripe wall on right half + shadow gradient
        val source = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val shade = 0.72f + 0.28f * (y / h.toFloat())
                    val color = if (x < 100) {
                        val isMortar = (x % 8 == 0) || (y % 8 == 0)
                        if (isMortar) {
                            Color.rgb((48 * shade).toInt(), (46 * shade).toInt(), (44 * shade).toInt())
                        } else {
                            val alt = ((x / 8) + (y / 8)) % 2 == 0
                            val r = if (alt) 175 else 142
                            val g = if (alt) 168 else 136
                            val b = if (alt) 156 else 128
                            Color.rgb((r * shade).toInt(), (g * shade).toInt(), (b * shade).toInt())
                        }
                    } else {
                        val isMortar = (y % 6 == 0)
                        if (isMortar) {
                            Color.rgb((70 * shade).toInt(), (58 * shade).toInt(), (52 * shade).toInt())
                        } else {
                            Color.rgb((186 * shade).toInt(), (112 * shade).toInt(), (84 * shade).toInt())
                        }
                    }
                    setPixel(x, y, color)
                }
            }
            // Place an unwanted bright neon-magenta object off-center inside the cobblestone region (x=42..62, y=88..108)
            for (y in 88..108) {
                for (x in 42..62) {
                    setPixel(x, y, Color.rgb(250, 20, 220))
                }
            }
        }

        val strokes = listOf(
            HealStroke(
                mode = HealMode.SPOT_HEAL,
                points = listOf(52f / w to 98f / h),
                radiusNorm = 0.08f,
                feather = 0.50f,
                opacity = 1.0f
            )
        )
        val alphaMask = LocalInpaintingEngine.rasterizeAlphaMask(w, h, strokes)
        val result = LocalInpaintingEngine.inpaint(source, strokes)

        assertEquals(w, result.width)
        assertEquals(h, result.height)

        var unmaskedCount = 0
        var featherCount = 0
        var coreCount = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val m = alphaMask[y * w + x]
                when {
                    m == 0 -> {
                        unmaskedCount++
                        // Strict Pixel Preservation: every unmasked pixel must be bit-identical
                        assertEquals(
                            "Unmasked pixel ($x, $y) was modified!",
                            source.getPixel(x, y),
                            result.getPixel(x, y)
                        )
                    }
                    m in 1..254 -> featherCount++
                    else -> coreCount++
                }
            }
        }

        assertTrue("Expected most pixels untouched", unmaskedCount > w * h - 1800)
        assertTrue("Expected smooth feathered boundary ring (got $featherCount)", featherCount > 250)
        assertTrue("Expected solid masked core (got $coreCount)", coreCount > 180)

        // Verify Feather=0% produces a crisper edge with a larger solid core than Feather=50%
        val hardAlphaMask = LocalInpaintingEngine.rasterizeAlphaMask(
            w, h, listOf(strokes[0].copy(feather = 0f))
        )
        val hardCoreCount = hardAlphaMask.count { it == 255 }
        assertTrue(
            "Expected Feather=0% core ($hardCoreCount) > Feather=50% core ($coreCount)",
            hardCoreCount > coreCount
        )

        // Verify unwanted magenta object inside core is cleanly replaced with cobblestone tones (no magenta bleed)
        var minLuma = 255f
        var maxLuma = 0f
        for (y in 91..105) {
            for (x in 45..59) {
                val px = result.getPixel(x, y)
                val r = Color.red(px)
                val g = Color.green(px)
                val b = Color.blue(px)
                assertTrue(
                    "Magenta bleed remained at ($x,$y): R=$r G=$g B=$b",
                    r - g < 45 && b - g < 45
                )
                val luma = 0.299f * r + 0.587f * g + 0.114f * b
                if (luma < minLuma) minLuma = luma
                if (luma > maxLuma) maxLuma = luma
            }
        }
        assertTrue(
            "Expected cobblestone texture contrast in synthesized patch (maxLuma=$maxLuma, minLuma=$minLuma)",
            (maxLuma - minLuma) > 15f
        )
    }

    @Test
    fun `OfflineHealEngine supports Clone Stamp, Patch Smooth edge-preservation, feather, and opacity`() {
        val w = 80
        val h = 80

        // 1. Clone Stamp copies pixel block from source offset onto target while preserving unmasked pixels
        val cloneSrc = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until h) {
                for (x in 0 until w) {
                    // Left quadrant (source area) is bright teal; right quadrant (target area) is dark red
                    val col = if (x < 40) Color.rgb(30, 200, 195) else Color.rgb(185, 35, 35)
                    setPixel(x, y, col)
                }
            }
        }
        val cloneStroke = HealStroke(
            mode = HealMode.CLONE_STAMP,
            points = listOf(0.75f to 0.50f), // target at x=60, y=40
            radiusNorm = 0.12f,
            feather = 0.20f,
            opacity = 1.0f,
            cloneSourceNorm = 0.25f to 0.50f // source at x=20, y=40 (teal)
        )
        val cloneOut = OfflineHealEngine.applyStroke(cloneSrc, cloneStroke)
        val clonedCenter = cloneOut.getPixel(60, 40)
        assertTrue(
            "Expected Clone Stamp target (60,40) to become teal from source (20,40), got R=${Color.red(clonedCenter)} G=${Color.green(clonedCenter)}",
            Color.green(clonedCenter) > Color.red(clonedCenter) + 80
        )
        // Unmasked corner on right side stays untouched red
        assertEquals(cloneSrc.getPixel(78, 2), cloneOut.getPixel(78, 2))

        // Partial opacity (50%) blends half source, half original
        val halfCloneOut = OfflineHealEngine.applyStroke(
            cloneSrc,
            cloneStroke.copy(opacity = 0.50f, feather = 0.0f)
        )
        val halfPx = halfCloneOut.getPixel(60, 40)
        assertEquals((185 + 30) / 2f, Color.red(halfPx).toFloat(), 6f)
        assertEquals((35 + 200) / 2f, Color.green(halfPx).toFloat(), 6f)

        // 2. Patch / Smooth dampens mottled skin noise on left half inside brush while preserving sharp architectural step edge
        val smoothSrc = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until h) {
                for (x in 0 until w) {
                    if (x < 48) {
                        // Subtle mottled skin blemishes (+/- 12 variation around warm peach)
                        val delta = if ((x + y) % 2 == 0) 12 else -12
                        setPixel(x, y, Color.rgb(200 + delta, 155 + delta, 130 + delta))
                    } else {
                        // Strong dark eyebrow/eyeliner step edge on the right side
                        setPixel(x, y, Color.rgb(32, 28, 26))
                    }
                }
            }
        }
        val smoothStroke = HealStroke(
            mode = HealMode.PATCH_SMOOTH,
            points = listOf(0.55f to 0.50f), // covers boundary around x=44 across skin and strong edge at x=48
            radiusNorm = 0.16f,
            feather = 0.40f,
            opacity = 1.0f
        )
        val smoothOut = OfflineHealEngine.applyStroke(smoothSrc, smoothStroke)
        val smoothedSkinA = Color.red(smoothOut.getPixel(40, 40))
        val smoothedSkinB = Color.red(smoothOut.getPixel(41, 40))
        assertTrue(
            "Expected Patch / Smooth to dampen +/-12 speckle diff (diff=${kotlin.math.abs(smoothedSkinA - smoothedSkinB)})",
            kotlin.math.abs(smoothedSkinA - smoothedSkinB) < 8
        )
        // Edge preservation: dark edge just inside mask at x=49 remains dark (< 65), not blurred to skin gray
        val preservedEdge = Color.red(smoothOut.getPixel(49, 40))
        assertTrue("Expected bilateral Patch/Smooth to preserve crisp dark edge (<65), got $preservedEdge", preservedEdge < 65)
        // Unmasked border pixel untouched
        assertEquals(smoothSrc.getPixel(2, 2), smoothOut.getPixel(2, 2))
    }

    @Test
    fun `Offline HealViewModel workflow manages brush sliders, clone source, auto-heal, undo redo, mask toggle, compare, and export`() = runTest(testDispatcher) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = EditorViewModel(app)
        awaitInitialLoad(vm)

        val originalPreview = requireNotNull(vm.uiState.value.previewIdleBitmap)
        vm.selectTab(StudioTab.HEAL)
        assertEquals(StudioTab.HEAL, vm.uiState.value.activeTab)

        // Brush parameters clamp to specified bounds
        vm.setHealBrushSizePx(96f)
        vm.setHealFeather(0.75f)
        vm.setHealOpacity(0.85f)
        assertEquals(96f, vm.uiState.value.healBrushSizePx, 0.01f)
        assertEquals(0.75f, vm.uiState.value.healFeather, 0.01f)
        assertEquals(0.85f, vm.uiState.value.healOpacity, 0.01f)

        // Show/Hide Mask toggle
        assertFalse(vm.uiState.value.showHealMask)
        vm.toggleShowHealMask()
        assertTrue(vm.uiState.value.showHealMask)

        // Spot Heal finger-lift auto-applies offline
        vm.setHealMode(HealMode.SPOT_HEAL)
        vm.startHealStroke(0.45f, 0.45f)
        vm.appendHealStroke(0.55f, 0.50f)
        vm.finishHealStroke()
        val d1 = System.currentTimeMillis() + 8_000L
        while (System.currentTimeMillis() < d1 &&
            (vm.uiState.value.isHealProcessing || vm.uiState.value.healStrokes.size != 1)
        ) {
            testDispatcher.scheduler.advanceUntilIdle()
            Thread.sleep(20)
        }
        assertFalse(vm.uiState.value.isHealProcessing)
        assertEquals(1, vm.uiState.value.healStrokes.size)
        assertTrue(vm.uiState.value.canUndoHealStroke)
        assertFalse(vm.uiState.value.canRedoHealStroke)
        assertNotNull(vm.uiState.value.preHealPreviewBitmap)

        // Clone Stamp picking & second stroke
        vm.togglePickCloneSource()
        assertEquals(HealMode.CLONE_STAMP, vm.uiState.value.healMode)
        assertTrue(vm.uiState.value.isPickingCloneSource)
        vm.setCloneSourcePoint(0.25f, 0.25f)
        assertFalse(vm.uiState.value.isPickingCloneSource)
        assertNotNull(vm.uiState.value.cloneSourcePoint)

        vm.startHealStroke(0.65f, 0.65f)
        vm.finishHealStroke()
        val d2 = System.currentTimeMillis() + 8_000L
        while (System.currentTimeMillis() < d2 &&
            (vm.uiState.value.isHealProcessing || vm.uiState.value.healStrokes.size != 2)
        ) {
            testDispatcher.scheduler.advanceUntilIdle()
            Thread.sleep(20)
        }
        assertEquals(2, vm.uiState.value.healStrokes.size)

        // Compare Original shows preHealPreviewBitmap cleanly
        val healedTexture = vm.uiState.value.activePreviewTexture
        vm.setComparingOriginal(true)
        assertTrue(vm.uiState.value.activePreviewTexture === vm.uiState.value.preHealPreviewBitmap)
        vm.setComparingOriginal(false)
        assertTrue(vm.uiState.value.activePreviewTexture === healedTexture)

        // Dedicated Undo / Redo stroke stack
        vm.undoHealStroke()
        assertEquals(1, vm.uiState.value.healStrokes.size)
        assertTrue(vm.uiState.value.canRedoHealStroke)
        vm.redoHealStroke()
        assertEquals(2, vm.uiState.value.healStrokes.size)

        // Reset Heal restores clean unretouched original bitmap
        vm.resetHeal()
        assertEquals(0, vm.uiState.value.healStrokes.size)
        assertNull(vm.uiState.value.cloneSourcePoint)
        assertEquals(originalPreview.width, vm.uiState.value.previewIdleBitmap?.width)
    }

    @Test
    fun `Effects Vignette styles, sub-sliders, Grain size and roughness, XMP parsing, and resetEffects work accurately`() = runTest(testDispatcher) {
        // 1. Defaults check
        val defaults = GradingParams()
        assertEquals(VignetteStyle.HIGHLIGHT, defaults.vignetteStyle)
        assertEquals(0f, defaults.vignette, 0.001f)
        assertEquals(50f, defaults.vignetteMidpoint, 0.001f)
        assertEquals(50f, defaults.vignetteFeather, 0.001f)
        assertEquals(0f, defaults.vignetteRoundness, 0.001f)
        assertEquals(0f, defaults.vignetteHighlights, 0.001f)
        assertEquals(0f, defaults.grain, 0.001f)
        assertEquals(25f, defaults.grainSize, 0.001f)
        assertEquals(50f, defaults.grainRoughness, 0.001f)

        // 2. XMP parsing & resetEffects
        val sampleXmp = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description
                  xmlns:crs="http://ns.adobe.com/camera-raw-settings/1.0/"
                  crs:Texture="18"
                  crs:Clarity2012="22"
                  crs:Dehaze="14"
                  crs:PostCropVignetteAmount="-45"
                  crs:PostCropVignetteStyle="2"
                  crs:PostCropVignetteMidpoint="35"
                  crs:PostCropVignetteFeather="72"
                  crs:PostCropVignetteRoundness="-60"
                  crs:PostCropVignetteHighlights="80"
                  crs:GrainAmount="40"
                  crs:GrainSize="68"
                  crs:GrainRoughness="82" />
              </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()
        val parsedXmp = XmpPresetParser.parseXmp(sampleXmp.byteInputStream(), "EffectsTest")
        val mapped = GradingParams.fromXmpSettings(parsedXmp, intensity = 1f)
        assertEquals(VignetteStyle.COLOR, mapped.vignetteStyle)
        assertEquals(-45f, mapped.vignette, 0.001f)
        assertEquals(35f, mapped.vignetteMidpoint, 0.001f)
        assertEquals(72f, mapped.vignetteFeather, 0.001f)
        assertEquals(-60f, mapped.vignetteRoundness, 0.001f)
        assertEquals(80f, mapped.vignetteHighlights, 0.001f)
        assertEquals(40f, mapped.grain, 0.001f)
        assertEquals(68f, mapped.grainSize, 0.001f)
        assertEquals(82f, mapped.grainRoughness, 0.001f)

        val reset = mapped.resetEffects()
        assertEquals(defaults.vignetteStyle, reset.vignetteStyle)
        assertEquals(defaults.vignetteMidpoint, reset.vignetteMidpoint, 0.001f)
        assertEquals(defaults.grainSize, reset.grainSize, 0.001f)
        assertEquals(defaults.grainRoughness, reset.grainRoughness, 0.001f)

        // 3. Vignette Highlights protection protects bright corner pixels compared to 0 protection
        val brightBmp = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(240, 236, 230))
        }
        val vigNoProtect = ColorGradingEngine.processFullResolution(
            brightBmp,
            GradingParams(vignette = -80f, vignetteMidpoint = 35f, vignetteHighlights = 0f),
            null
        )
        val vigProtected = ColorGradingEngine.processFullResolution(
            brightBmp,
            GradingParams(vignette = -80f, vignetteMidpoint = 35f, vignetteHighlights = 100f),
            null
        )
        assertTrue(
            "Expected Highlights=100 to keep bright corner lighter than Highlights=0",
            Color.red(vigProtected.getPixel(1, 1)) > Color.red(vigNoProtect.getPixel(1, 1)) + 30
        )

        // 4. Paint Overlay vs Color Priority style produces distinct chromatic handling on saturated corners
        val satBmp = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(210, 90, 40))
        }
        val vigColor = ColorGradingEngine.processFullResolution(
            satBmp,
            GradingParams(vignetteStyle = VignetteStyle.COLOR, vignette = -75f, vignetteMidpoint = 40f),
            null
        )
        val vigPaint = ColorGradingEngine.processFullResolution(
            satBmp,
            GradingParams(vignetteStyle = VignetteStyle.PAINT, vignette = -75f, vignetteMidpoint = 40f),
            null
        )
        assertTrue(vigColor.getPixel(1, 1) != vigPaint.getPixel(1, 1))

        // 5. Grain Size & Roughness alter spatial grain contrast across a uniform midtone image
        val grayBmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(128, 128, 128))
        }
        val fineGrain = ColorGradingEngine.processFullResolution(
            grayBmp,
            GradingParams(grain = 70f, grainSize = 5f, grainRoughness = 10f),
            null
        )
        val coarseRoughGrain = ColorGradingEngine.processFullResolution(
            grayBmp,
            GradingParams(grain = 70f, grainSize = 95f, grainRoughness = 95f),
            null
        )
        var neighborDiffFine = 0
        var neighborDiffCoarse = 0
        for (y in 8 until 56) {
            for (x in 8 until 55) {
                neighborDiffFine += kotlin.math.abs(Color.red(fineGrain.getPixel(x, y)) - Color.red(fineGrain.getPixel(x + 1, y)))
                neighborDiffCoarse += kotlin.math.abs(Color.red(coarseRoughGrain.getPixel(x, y)) - Color.red(coarseRoughGrain.getPixel(x + 1, y)))
            }
        }
        assertTrue("Expected grain to introduce texture variation", neighborDiffFine > 0 && neighborDiffCoarse > 0)
        // Larger grainSize creates smoother adjacent pixel-to-pixel transitions than tiny 1px grain
        assertTrue(
            "Expected coarse grainSize=95 to have smoother adjacent pixel delta ($neighborDiffCoarse) than ultra-fine grainSize=5 ($neighborDiffFine)",
            neighborDiffFine > neighborDiffCoarse
        )
    }

    @Test
    fun `Detail Sharpening Radius Masking, Luminance NR, Color NR, XMP mapping, and resetDetail work accurately`() = runTest(testDispatcher) {
        // 1. Defaults check matching LightCraft
        val defaults = GradingParams()
        assertEquals(0f, defaults.sharpening, 0.001f)
        assertEquals(1.0f, defaults.sharpenRadius, 0.001f)
        assertEquals(25f, defaults.sharpenDetail, 0.001f)
        assertEquals(0f, defaults.sharpenMasking, 0.001f)
        assertEquals(0f, defaults.noiseReduction, 0.001f)
        assertEquals(50f, defaults.noiseReductionDetail, 0.001f)
        assertEquals(0f, defaults.noiseReductionContrast, 0.001f)
        assertEquals(0f, defaults.colorNoiseReduction, 0.001f)
        assertEquals(50f, defaults.colorNoiseReductionDetail, 0.001f)
        assertEquals(50f, defaults.colorNoiseReductionSmoothness, 0.001f)
        assertFalse(defaults.hasDetailEdits)

        // 2. XMP parsing + resetDetail
        val detailXmp = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description
                  xmlns:crs="http://ns.adobe.com/camera-raw-settings/1.0/"
                  crs:Sharpness="85"
                  crs:SharpenRadius="1.8"
                  crs:SharpenDetail="62"
                  crs:SharpenEdgeMasking="45"
                  crs:LuminanceSmoothing="40"
                  crs:LuminanceNoiseReductionDetail="70"
                  crs:LuminanceNoiseReductionContrast="32"
                  crs:ColorNoiseReduction="55"
                  crs:ColorNoiseReductionDetail="64"
                  crs:ColorNoiseReductionSmoothness="78" />
              </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()
        val parsed = XmpPresetParser.parseXmp(detailXmp.byteInputStream(), "DetailTest")
        val mapped = GradingParams.fromXmpSettings(parsed, 1f)
        assertTrue(mapped.hasDetailEdits)
        assertEquals(85f, mapped.sharpening, 0.001f)
        assertEquals(1.8f, mapped.sharpenRadius, 0.001f)
        assertEquals(62f, mapped.sharpenDetail, 0.001f)
        assertEquals(45f, mapped.sharpenMasking, 0.001f)
        assertEquals(40f, mapped.noiseReduction, 0.001f)
        assertEquals(70f, mapped.noiseReductionDetail, 0.001f)
        assertEquals(32f, mapped.noiseReductionContrast, 0.001f)
        assertEquals(55f, mapped.colorNoiseReduction, 0.001f)
        assertEquals(64f, mapped.colorNoiseReductionDetail, 0.001f)
        assertEquals(78f, mapped.colorNoiseReductionSmoothness, 0.001f)

        val reset = mapped.resetDetail()
        assertFalse(reset.hasDetailEdits)
        assertEquals(1.0f, reset.sharpenRadius, 0.001f)
        assertEquals(25f, reset.sharpenDetail, 0.001f)
        assertEquals(0f, reset.sharpenMasking, 0.001f)
        assertEquals(50f, reset.noiseReductionDetail, 0.001f)
        assertEquals(0f, reset.noiseReductionContrast, 0.001f)
        assertEquals(50f, reset.colorNoiseReductionDetail, 0.001f)
        assertEquals(50f, reset.colorNoiseReductionSmoothness, 0.001f)

        // 3. Sharpening Masking protects smooth noisy region (e.g. skin/sky) while still sharpening strong edges
        val w = 48
        val h = 24
        val sharpTestBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until h) {
                for (x in 0 until 24) {
                    // Subtle sky micro-ripple noise (+/- 3 luma)
                    val l = if ((x + y) % 2 == 0) 143 else 137
                    setPixel(x, y, Color.rgb(l, l, l))
                }
                for (x in 24 until w) {
                    // Strong dark object edge at x=24..25
                    val l = if (x >= 32) 220 else 55
                    setPixel(x, y, Color.rgb(l, l, l))
                }
            }
        }
        val unmaskedSharp = ColorGradingEngine.processFullResolution(
            sharpTestBmp,
            GradingParams(sharpening = 120f, sharpenRadius = 1.2f, sharpenDetail = 50f, sharpenMasking = 0f),
            null
        )
        val maskedSharp = ColorGradingEngine.processFullResolution(
            sharpTestBmp,
            GradingParams(sharpening = 120f, sharpenRadius = 1.2f, sharpenDetail = 50f, sharpenMasking = 85f),
            null
        )
        // Smooth sky region (x=10, y=12): Masking=85 keeps ripple variation untouched, while Masking=0 boosts ripple contrast
        val unmaskedSkyDelta = kotlin.math.abs(Color.red(unmaskedSharp.getPixel(10, 12)) - Color.red(unmaskedSharp.getPixel(11, 12)))
        val maskedSkyDelta = kotlin.math.abs(Color.red(maskedSharp.getPixel(10, 12)) - Color.red(maskedSharp.getPixel(11, 12)))
        assertTrue(
            "Expected Masking=85 to suppress sharpening on subtle sky texture ($maskedSkyDelta vs $unmaskedSkyDelta)",
            maskedSkyDelta < unmaskedSkyDelta
        )
        // Strong architectural step edge at x=31 -> 32 should still be sharpened (> original 55 vs 220 spread)
        val maskedEdgeRight = Color.red(maskedSharp.getPixel(32, 12))
        val maskedEdgeLeft = Color.red(maskedSharp.getPixel(31, 12))
        assertTrue(
            "Expected strong edge at x=31..32 to remain sharpened with Masking=85 (left=$maskedEdgeLeft, right=$maskedEdgeRight)",
            maskedEdgeRight > 220 && maskedEdgeLeft < 55
        )

        // 4. Noise Reduction smooths salt-and-pepper luminance grain & Detail=90 protects strong edge more than Detail=0
        val nrBmp = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(120, 120, 120))
            // Isolated luminance grain spike at (8,8)
            setPixel(8, 8, Color.rgb(185, 185, 185))
        }
        val nrApplied = ColorGradingEngine.processFullResolution(
            nrBmp,
            GradingParams(noiseReduction = 85f, noiseReductionDetail = 20f, noiseReductionContrast = 0f),
            null
        )
        assertTrue(
            "Expected NR to dampen luminance noise spike at (8,8) below 150, got ${Color.red(nrApplied.getPixel(8, 8))}",
            Color.red(nrApplied.getPixel(8, 8)) < 150
        )

        // 5. Color Noise Reduction eliminates chromatic magenta speckle on neutral gray while preserving luminance
        val cnrBmp = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(128, 128, 128))
            // Chromatic noise speckle with same luminance (~128) at (16, 16)
            setPixel(16, 16, Color.rgb(210, 95, 200))
        }
        val cnrApplied = ColorGradingEngine.processFullResolution(
            cnrBmp,
            GradingParams(colorNoiseReduction = 90f, colorNoiseReductionDetail = 50f, colorNoiseReductionSmoothness = 60f),
            null
        )
        val cnrPx = cnrApplied.getPixel(16, 16)
        val origSpread = kotlin.math.abs(210 - 95)
        val afterSpread = kotlin.math.max(
            kotlin.math.abs(Color.red(cnrPx) - Color.green(cnrPx)),
            kotlin.math.abs(Color.blue(cnrPx) - Color.green(cnrPx))
        )
        assertTrue(
            "Expected Color Noise Reduction to collapse chroma noise spread ($afterSpread vs original $origSpread)",
            afterSpread < origSpread / 3
        )
    }

    @Test
    fun `Crop and Rotate tab placement, aspect presets, transforms, auto straighten, perspective constrain, and export work cleanly`() = runTest(testDispatcher) {
        // 1. StudioTab order puts Adjust, All, LUTs, Crop & Rotate, Heal, and Masks
        assertEquals(
            listOf(StudioTab.ADJUST, StudioTab.ALL, StudioTab.LUTS, StudioTab.CROP, StudioTab.HEAL, StudioTab.MASK),
            StudioTab.entries
        )
        assertEquals(
            listOf("Thirds", "Grid", "Golden", "Diagonal", "Triangle", "Spiral", "None"),
            CropOverlayMode.entries.map { it.label }
        )

        // 2. Aspect presets, Lock/Unlock, Swap orientation on a 400x200 landscape image
        var c = CropGeometryParams()
        c = GeometryMath.applyAspectPreset(400, 200, c, CropAspectPreset.RATIO_1_1)
        assertTrue(c.aspectLocked)
        val (sqW, sqH) = GeometryMath.computeOutputDimensions(400, 200, c)
        assertEquals(sqW.toFloat(), sqH.toFloat(), 2f)

        c = GeometryMath.applyAspectPreset(400, 200, c, CropAspectPreset.RATIO_4_5)
        val (p45W, p45H) = GeometryMath.computeOutputDimensions(400, 200, c)
        assertTrue("Expected 4:5 portrait crop (H > W), got ${p45W}x${p45H}", p45H > p45W)
        assertEquals(0.8f, p45W.toFloat() / p45H.toFloat(), 0.03f)

        val swapped = GeometryMath.swapCropAspect(400, 200, c)
        assertTrue(swapped.aspectSwapped)
        val (s54W, s54H) = GeometryMath.computeOutputDimensions(400, 200, swapped)
        assertTrue("Expected swapped 5:4 landscape crop (W > H), got ${s54W}x${s54H}", s54W > s54H)
        assertEquals(1.25f, s54W.toFloat() / s54H.toFloat(), 0.03f)

        // 3. Rotate +/-90°, Flip H/V & pixel verification in ColorGradingEngine
        val quadrantBmp = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until 20) {
                for (x in 0 until 40) {
                    val col = when {
                        x < 20 && y < 10 -> Color.RED    // Top-Left
                        x >= 20 && y < 10 -> Color.GREEN // Top-Right
                        x < 20 && y >= 10 -> Color.BLUE  // Bottom-Left
                        else -> Color.YELLOW             // Bottom-Right
                    }
                    setPixel(x, y, col)
                }
            }
        }
        val rotatedRight = ColorGradingEngine.processFullResolution(
            quadrantBmp,
            GradingParams(crop = CropGeometryParams(rotationSteps = 1)),
            null
        )
        assertEquals(20, rotatedRight.width)
        assertEquals(40, rotatedRight.height)
        // Old Top-Left (RED) moves to Top-Right after +90° clockwise rotation
        val topRightAfter = rotatedRight.getPixel(16, 4)
        assertTrue("Expected RED at Top-Right after +90 rotation", Color.red(topRightAfter) > 200 && Color.green(topRightAfter) < 40)

        val flippedH = ColorGradingEngine.processFullResolution(
            quadrantBmp,
            GradingParams(crop = CropGeometryParams(flipH = true)),
            null
        )
        assertEquals(40, flippedH.width)
        assertEquals(20, flippedH.height)
        val tlAfterFlip = flippedH.getPixel(4, 4)
        assertTrue("Expected GREEN at Top-Left after Flip H", Color.green(tlAfterFlip) > 200 && Color.red(tlAfterFlip) < 40)

        // 4. Straighten auto-scale fills crop box & Auto Straighten detects synthetic 8° tilted horizon
        val straightScale = GeometryMath.computeStraightenAutoScale(1.5f, 10f)
        assertTrue("Expected straighten auto-scale > 1.0, got $straightScale", straightScale > 1.15f)

        val horizonBmp = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888).apply {
            val rad = Math.toRadians(8.0) // Horizon rises rightwards by +8° -> counter-rotate -8° (or opposite sign)
            val slope = kotlin.math.tan(rad)
            for (y in 0 until 120) {
                for (x in 0 until 160) {
                    val horizonY = 60.0 + (x - 80.0) * slope
                    setPixel(x, y, if (y < horizonY) Color.rgb(195, 220, 245) else Color.rgb(35, 55, 30))
                }
            }
        }
        val detectedAngle = GeometryMath.computeAutoStraightenAngle(horizonBmp)
        assertTrue("Expected Auto Straighten magnitude ~8°, got $detectedAngle", kotlin.math.abs(detectedAngle) in 5.5f..10.5f)
        // Applying detectedAngle rotates output horizontal edges flat at center
        val leveled = ColorGradingEngine.processFullResolution(
            horizonBmp,
            GradingParams(crop = CropGeometryParams(straightenDeg = detectedAngle)),
            null
        )
        // Middle row pixels far left vs far right should either both be sky or stay consistent rather than crossing horizon heavily
        val leftMid = Color.red(leveled.getPixel(16, 48))
        val rightMid = Color.red(leveled.getPixel(144, 48))
        assertTrue(
            "Expected leveled horizon row y=48 to stay consistently above horizon (L=$leftMid, R=$rightMid)",
            leftMid > 140 && rightMid > 140
        )

        // 5. Perspective + Constrain Crop auto-trims empty margins, whereas unconstrained reveals dark #0B0E14 margin
        val solidWhite = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val constrainedCrop = CropGeometryParams(verticalPerspective = 65f, constrainCrop = true)
        val effConstrained = GeometryMath.effectiveCropRect(64, 64, constrainedCrop)
        assertTrue("Expected constrainCrop to trim bounds strictly inside [0..1]", effConstrained.width < 0.99f || effConstrained.height < 0.99f)
        val outConstrained = ColorGradingEngine.processFullResolution(solidWhite, GradingParams(crop = constrainedCrop), null)
        // Corner pixel in constrained output must contain real photo (bright white), never empty margin
        assertTrue(Color.red(outConstrained.getPixel(0, 0)) > 235)

        val unconstrainedCrop = CropGeometryParams(verticalPerspective = 65f, constrainCrop = false)
        val outUnconstrained = ColorGradingEngine.processFullResolution(solidWhite, GradingParams(crop = unconstrainedCrop), null)
        assertEquals(64, outUnconstrained.width)
        assertEquals(64, outUnconstrained.height)
        // Bottom-left or top-left corner outside keystone trapezoid turns dark background margin
        val cornerDark = kotlin.math.min(
            Color.red(outUnconstrained.getPixel(0, 0)),
            Color.red(outUnconstrained.getPixel(0, 63))
        )
        assertTrue("Expected empty edge margin outside unconstrained keystone warp, got luma $cornerDark", cornerDark < 35)

        // 6. EditorViewModel Crop integration, ViewModel Undo/Redo, XMP preset preserving Crop, and reset helpers
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = EditorViewModel(app)
        awaitInitialLoad(vm)

        vm.selectTab(StudioTab.CROP)
        assertEquals(StudioTab.CROP, vm.uiState.value.activeTab)

        vm.setCropOverlayMode(CropOverlayMode.SPIRAL)
        assertEquals(CropOverlayMode.SPIRAL, vm.uiState.value.cropOverlayMode)

        vm.selectCropAspectPreset(CropAspectPreset.RATIO_1_1)
        assertEquals(vm.uiState.value.effectiveOutputWidth, vm.uiState.value.effectiveOutputHeight)
        assertTrue(vm.uiState.value.canUndo)

        vm.rotateCropRight()
        assertEquals(1, vm.uiState.value.gradingParams.crop.rotationSteps)

        vm.flipCropHorizontal()
        assertTrue(vm.uiState.value.gradingParams.crop.flipH)

        // Undo flip & rotate
        vm.undo()
        assertFalse(vm.uiState.value.gradingParams.crop.flipH)
        vm.undo()
        assertEquals(0, vm.uiState.value.gradingParams.crop.rotationSteps)
        vm.redo()
        assertEquals(1, vm.uiState.value.gradingParams.crop.rotationSteps)

        // Geometry slider batch + Constrain toggle + resetGeometryPerspective
        vm.setSliderDragging(true)
        vm.updateGradingParams { gp ->
            gp.copy(crop = gp.crop.copy(verticalPerspective = 28f, horizontalPerspective = -15f, geometryScale = 120f, offsetX = 10f))
        }
        vm.setSliderDragging(false)
        vm.toggleConstrainCrop()
        assertFalse(vm.uiState.value.gradingParams.crop.constrainCrop)

        // Selecting XMP LUT preserves existing crop/geometry edits
        val xmpFolder = vm.uiState.value.lutFolders.first { f -> f.descriptors.any { it.sourceFormat == LutSourceFormat.XMP } }.name
        vm.toggleLutFolder(xmpFolder)
        awaitFolderLoaded(vm, xmpFolder)
        val xmpItem = vm.uiState.value.lutFolders.first { it.name == xmpFolder }.items.first()
        vm.selectLut(xmpItem.id)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(28f, vm.uiState.value.gradingParams.crop.verticalPerspective, 0.001f)
        assertEquals(1, vm.uiState.value.gradingParams.crop.rotationSteps)

        vm.resetGeometryPerspective()
        assertFalse(vm.uiState.value.gradingParams.crop.hasGeometryEdits)
        assertTrue(vm.uiState.value.gradingParams.crop.constrainCrop)
        assertEquals(1, vm.uiState.value.gradingParams.crop.rotationSteps)

        vm.resetCropAndOrientation()
        assertTrue(vm.uiState.value.gradingParams.crop.isDefault)
    }

    @Test
    fun `ViewportZoomMath pinch focal 1x to 8x, constrained pan, double-tap 3x toggle, and reset token work cleanly`() = runTest(testDispatcher) {
        val vpW = 1000f
        val vpH = 1200f
        val fitW = 960f
        val fitH = 720f

        // 1. Pinch focal point preservation & [1.0x, 8.0x] clamp
        val focal = Offset(620f, 540f)
        val (zoom3x, pan3x) = ViewportZoomMath.applyPinchAndPan(
            currentZoom = 1f,
            currentPan = Offset.Zero,
            zoomChange = 3f,
            panChange = Offset.Zero,
            focalPoint = focal,
            viewportW = vpW,
            viewportH = vpH,
            fittedW = fitW,
            fittedH = fitH
        )
        assertEquals(3f, zoom3x, 0.001f)
        // Verify focal point stays fixed on screen: C + (focal - C) * 3 + pan3x == focal
        val c = Offset(vpW * 0.5f, vpH * 0.5f)
        val focalAfter = c + (focal - c) * zoom3x + pan3x
        assertEquals(focal.x, focalAfter.x, 0.5f)
        assertEquals(focal.y, focalAfter.y, 0.5f)

        // Clamps strictly at 8.0x max magnification and 1.0x min magnification
        val (zoomMax, _) = ViewportZoomMath.applyPinchAndPan(
            currentZoom = zoom3x,
            currentPan = pan3x,
            zoomChange = 4.5f,
            panChange = Offset.Zero,
            focalPoint = c,
            viewportW = vpW,
            viewportH = vpH,
            fittedW = fitW,
            fittedH = fitH
        )
        assertEquals(8.0f, zoomMax, 0.001f)

        val (zoomMin, panMin) = ViewportZoomMath.applyPinchAndPan(
            currentZoom = 1.5f,
            currentPan = Offset(50f, -30f),
            zoomChange = 0.5f,
            panChange = Offset(10f, 10f),
            focalPoint = c,
            viewportW = vpW,
            viewportH = vpH,
            fittedW = fitW,
            fittedH = fitH
        )
        assertEquals(1.0f, zoomMin, 0.001f)
        assertEquals(Offset.Zero, panMin)

        // 2. Constrained pan keeps zoomed image edges inside viewport (cannot drag off-screen)
        val extremePan = ViewportZoomMath.clampPan(
            pan = Offset(10_000f, -10_000f),
            zoom = 4.0f,
            viewportW = vpW,
            viewportH = vpH,
            fittedW = fitW,
            fittedH = fitH
        )
        val expectedMaxX = (fitW * 4.0f - vpW) * 0.5f
        val expectedMaxY = (fitH * 4.0f - vpH) * 0.5f
        assertEquals(expectedMaxX, extremePan.x, 0.1f)
        assertEquals(-expectedMaxY, extremePan.y, 0.1f)

        // 3. Double-tap toggles smoothly between 1.0x -> 3.0x (centered on tap) and zoomed -> 1.0x
        val (dtZoomIn, dtPanIn) = ViewportZoomMath.computeDoubleTapTarget(
            currentZoom = 1.0f,
            tapPosition = Offset(600f, 600f),
            viewportW = vpW,
            viewportH = vpH,
            fittedW = fitW,
            fittedH = fitH
        )
        assertEquals(3.0f, dtZoomIn, 0.001f)
        assertEquals(-200f, dtPanIn.x, 0.5f)
        assertEquals(0f, dtPanIn.y, 0.5f)

        val (dtZoomOut, dtPanOut) = ViewportZoomMath.computeDoubleTapTarget(
            currentZoom = dtZoomIn,
            tapPosition = Offset(600f, 600f),
            viewportW = vpW,
            viewportH = vpH,
            fittedW = fitW,
            fittedH = fitH
        )
        assertEquals(1.0f, dtZoomOut, 0.001f)
        assertEquals(Offset.Zero, dtPanOut)

        // 4. EditorViewModel increments viewportResetToken when tapping Reset or loading a new photo
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = EditorViewModel(app)
        awaitInitialLoad(vm)

        val initialToken = vm.uiState.value.viewportResetToken
        assertTrue(initialToken >= 1L)

        // Editing sliders does NOT disturb current zoom/pan token
        vm.setSliderDragging(true)
        vm.updateGradingParams { it.copy(exposure = 0.7f) }
        vm.setSliderDragging(false)
        assertEquals(initialToken, vm.uiState.value.viewportResetToken)

        // Tapping main Reset increments viewportResetToken cleanly
        vm.resetAllAdjustments()
        assertEquals(initialToken + 1L, vm.uiState.value.viewportResetToken)

        // Opening a new image from URI also increments viewportResetToken
        val tempFile = java.io.File(app.cacheDir, "test_zoom_reset_photo.png")
        val testBmp = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
        tempFile.outputStream().use { testBmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        try {
            vm.openPhotoFromUri(Uri.fromFile(tempFile))
            val loadDeadline = System.currentTimeMillis() + 5_000L
            while (System.currentTimeMillis() < loadDeadline &&
                vm.uiState.value.viewportResetToken < initialToken + 2L
            ) {
                testDispatcher.scheduler.advanceUntilIdle()
                Thread.sleep(20)
            }
            assertEquals(initialToken + 2L, vm.uiState.value.viewportResetToken)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun dualResolutionProxyPipeline_subsamplesHighResAndPreservesNativeExport() = runTest {
        // 1. Optimal power-of-2 inSampleSize calculation for flagship 12MP, 50MP, and 200MP cameras
        assertEquals(1, ImageIoHelper.calculateInSampleSize(1024, 768, ImageIoHelper.PROXY_MAX_DIMENSION))
        assertEquals(2, ImageIoHelper.calculateInSampleSize(4032, 3024, ImageIoHelper.PROXY_MAX_DIMENSION))
        assertEquals(4, ImageIoHelper.calculateInSampleSize(8160, 6120, ImageIoHelper.PROXY_MAX_DIMENSION))
        assertEquals(8, ImageIoHelper.calculateInSampleSize(16320, 12240, ImageIoHelper.PROXY_MAX_DIMENSION))

        // 2. Real high-res PNG round-trip: proxy capped at <=2048px, native export restores full res
        val app = ApplicationProvider.getApplicationContext<Application>()
        val tempFile = java.io.File(app.cacheDir, "test_highres_proxy_3600x2700.png")
        val hiRes = Bitmap.createBitmap(3600, 2700, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(40, 120, 200))
        }
        tempFile.outputStream().use { hiRes.compress(Bitmap.CompressFormat.PNG, 100, it) }
        hiRes.recycle()

        try {
            val uri = Uri.fromFile(tempFile)
            val result = ImageIoHelper.loadProxyFromUri(app, uri, ImageIoHelper.PROXY_MAX_DIMENSION)
            assertEquals(3600, result.nativeWidth)
            assertEquals(2700, result.nativeHeight)
            assertTrue(
                "Proxy longest edge (${maxOf(result.proxyBitmap.width, result.proxyBitmap.height)}) must be <= 2048",
                maxOf(result.proxyBitmap.width, result.proxyBitmap.height) <= ImageIoHelper.PROXY_MAX_DIMENSION
            )
            result.proxyBitmap.recycle()

            val nativeBitmap = ImageIoHelper.loadNativeForExport(
                context = app,
                sourceRef = PhotoSourceRef.ContentUri(uri),
                targetMaxSourceDim = null
            )
            assertEquals(3600, nativeBitmap.width)
            assertEquals(2700, nativeBitmap.height)
            nativeBitmap.recycle()
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun dirtyRenderGate_coalescesRapidUpdatesAndSkipsIdleFrames() {
        val gate = DirtyRenderGate()
        val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        try {
            val baseState = GlRenderState(
                imageBitmap = bmp,
                params = GradingParams(exposure = 0.0f),
                lut = null,
                isOriginal = false,
                splitRatio = 0.5f,
                applyCrop = true
            )
            // First submission schedules exactly one frame
            assertTrue(gate.submit(baseState))
            // Submitting an identical state skips GPU wakeup
            assertFalse(gate.submit(GlRenderState(bmp, GradingParams(exposure = 0.0f), null, false, 0.5f, true)))

            // Multiple rapid 120Hz slider updates while frame is queued coalesce into 0 extra runnables
            for (i in 1..20) {
                val tick = GlRenderState(
                    imageBitmap = bmp,
                    params = GradingParams(exposure = i * 0.05f),
                    lut = null,
                    isOriginal = false,
                    splitRatio = 0.5f,
                    applyCrop = true
                )
                assertFalse("Rapid tick $i should coalesce into already-scheduled render pass", gate.submit(tick))
            }

            // Consuming on GL thread yields the latest state (exposure = 1.0f)
            val consumed = gate.consumeForRender()
            assertNotNull(consumed)
            assertEquals(1.0f, consumed!!.params.exposure, 1e-4f)
            assertFalse(gate.shouldRescheduleAfterDraw())
            assertNull("Second consume with no new changes must return null", gate.consumeForRender())
        } finally {
            bmp.recycle()
        }
    }

    @Test
    fun zeroAllocShaderHelpers_matchAllocatingEquivalentsExactly() {
        val crop = CropGeometryParams(
            rotationSteps = 1,
            flipH = true,
            flipV = false,
            straightenDeg = 12.5f,
            verticalPerspective = 35f,
            horizontalPerspective = -20f,
            aspectStretch = 15f
        )
        val expectedMat = GeometryMath.buildOrientToRawSourceMatrix(2048, 1365, crop)
        val outMat = FloatArray(9)
        val scratchA = FloatArray(9)
        val scratchB = FloatArray(9)
        GeometryMath.buildOrientToRawSourceMatrixInto(2048, 1365, crop, outMat, scratchA, scratchB)
        for (i in 0 until 9) {
            assertEquals("Matrix element $i must match", expectedMat[i], outMat[i], 1e-6f)
        }

        val curve = ToneCurveData(
            master = listOf(ToneCurvePoint(0f, 0.05f), ToneCurvePoint(0.5f, 0.62f), ToneCurvePoint(1f, 0.96f)),
            red = listOf(ToneCurvePoint(0f, 0f), ToneCurvePoint(0.4f, 0.48f), ToneCurvePoint(1f, 1f))
        )
        val expectedPacked = curve.buildPackedRgba256()
        val reusablePacked = IntArray(256)
        curve.buildPackedRgba256Into(reusablePacked)
        for (i in 0 until 256) {
            assertEquals("Curve LUT entry $i must match", expectedPacked[i], reusablePacked[i])
        }
    }

    @Test
    fun sliderDrag_keepsSteadyProxyTextureAndDebouncesUndoHistory() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = EditorViewModel(app)
        awaitInitialLoad(vm)

        val initialProxy = vm.uiState.value.activePreviewTexture
        assertNotNull(initialProxy)
        assertFalse(vm.uiState.value.canUndo)

        vm.setSliderDragging(true)
        for (i in 1..20) {
            vm.updateGradingParams { it.copy(exposure = i * 0.1f) }
            // Proxy GPU texture reference must remain steady (zero drag/idle swap overhead)
            assertTrue(
                "Proxy texture reference must stay identical during drag tick $i",
                vm.uiState.value.activePreviewTexture === initialProxy
            )
            // Undo stack must NOT commit intermediate ticks during active drag
            assertFalse("No undo snapshot should be pushed mid-drag (tick $i)", vm.uiState.value.canUndo)
        }

        // Releasing slider commits exactly ONE batched history snapshot
        vm.setSliderDragging(false)
        assertTrue(vm.uiState.value.canUndo)
        assertTrue(vm.uiState.value.activePreviewTexture === initialProxy)
        assertEquals(2.0f, vm.uiState.value.gradingParams.exposure, 1e-4f)

        // A single undo reverts the entire 20-tick drag cleanly back to 0.0f
        vm.undo()
        assertEquals(0.0f, vm.uiState.value.gradingParams.exposure, 1e-4f)
        assertFalse(vm.uiState.value.canUndo)

        // Verify ViewModel onCleared() cleans up cleanly without throwing
        val onClearedMethod = androidx.lifecycle.ViewModel::class.java.getDeclaredMethod("onCleared")
        onClearedMethod.isAccessible = true
        onClearedMethod.invoke(vm)
    }

    @Test
    fun localMasks_radialFeatherInvertAndLinearGradientAdjustStrictlyInsideMaskedRegion() = runTest(testDispatcher) {
        // 1. Evaluate spatial weights (normal & inverted Radial; top->bottom Linear)
        val radial = LocalMask(
            id = "m_radial",
            name = "Radial 1",
            type = MaskType.RADIAL,
            centerX = 0.25f,
            centerY = 0.50f,
            radiusX = 0.16f,
            radiusY = 0.25f,
            feather = 0.45f,
            exposure = 1.2f,
            warmth = 60f,
            saturation = 40f
        )
        assertEquals(1.0f, evaluateLocalMaskWeight(radial, 0.25f, 0.50f, 1.5f), 0.01f)
        assertEquals(0.0f, evaluateLocalMaskWeight(radial, 0.85f, 0.50f, 1.5f), 0.01f)

        val invertedRadial = radial.copy(inverted = true)
        assertEquals(0.0f, evaluateLocalMaskWeight(invertedRadial, 0.25f, 0.50f, 1.5f), 0.01f)
        assertEquals(1.0f, evaluateLocalMaskWeight(invertedRadial, 0.85f, 0.50f, 1.5f), 0.01f)

        // 2. Render test bitmap with neutral gray left & right halves: Radial on left brightens & warms left only
        val bmp = Bitmap.createBitmap(100, 50, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(96, 96, 96))
        }
        val radialOut = ColorGradingEngine.processFullResolution(
            bmp,
            GradingParams(masks = listOf(radial)),
            null
        )
        val insidePx = radialOut.getPixel(25, 25)
        val outsidePx = radialOut.getPixel(85, 25)
        assertTrue(
            "Expected radial mask to brighten inside pixel (${Color.red(insidePx)}) while keeping outside pixel near 96 (${Color.red(outsidePx)})",
            Color.red(insidePx) > 155 && Color.red(insidePx) > Color.blue(insidePx) + 15 && Color.red(outsidePx) in 94..98
        )

        // 3. Linear gradient from top (y=0.15) fading to bottom (y=0.55) with Sharpness & Contrast
        val linear = LocalMask(
            id = "m_linear",
            name = "Linear 1",
            type = MaskType.LINEAR,
            startX = 0.5f,
            startY = 0.15f,
            endX = 0.5f,
            endY = 0.55f,
            exposure = -1.0f,
            contrast = 30f
        )
        val linearOut = ColorGradingEngine.processFullResolution(
            bmp,
            GradingParams(masks = listOf(linear)),
            null
        )
        val topDarkened = linearOut.getPixel(50, 3)
        val bottomUntouched = linearOut.getPixel(50, 45)
        assertTrue(
            "Expected Linear Gradient top to darken (${Color.red(topDarkened)}) vs untouched bottom (${Color.red(bottomUntouched)})",
            Color.red(topDarkened) < 60 && Color.red(bottomUntouched) in 94..98
        )

        // 4. ViewModel Mask CRUD + undo support
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = EditorViewModel(app)
        awaitInitialLoad(vm)

        vm.addLocalMask(MaskType.RADIAL)
        assertEquals(StudioTab.MASK, vm.uiState.value.activeTab)
        assertEquals(1, vm.uiState.value.gradingParams.masks.size)
        val mId = requireNotNull(vm.uiState.value.activeMask?.id)

        vm.toggleMaskInverted(mId)
        assertTrue(vm.uiState.value.activeMask?.inverted == true)

        vm.setSliderDragging(true)
        vm.updateMask(mId) { it.copy(saturation = 45f, sharpness = 60f) }
        vm.setSliderDragging(false)
        assertEquals(45f, vm.uiState.value.activeMask?.saturation ?: 0f, 0.01f)
        assertEquals(60f, vm.uiState.value.activeMask?.sharpness ?: 0f, 0.01f)

        vm.toggleMaskEnabled(mId)
        assertFalse(vm.uiState.value.activeMask?.enabled == true)

        vm.deleteMask(mId)
        assertTrue(vm.uiState.value.gradingParams.masks.isEmpty())
        vm.undo()
        assertEquals(1, vm.uiState.value.gradingParams.masks.size)
    }

    @Test
    fun customUserPresets_saveApplyDeleteAndJsonPersistenceWorkCleanly() = runTest(testDispatcher) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        UserPresetStore.savePresets(app, emptyList())

        val vm = EditorViewModel(app)
        awaitInitialLoad(vm)

        // Dial in a custom look combining sliders + RGB/Red curve + LUT
        val customCurve = ToneCurveData(
            master = listOf(ToneCurvePoint(0f, 0.04f), ToneCurvePoint(0.55f, 0.66f), ToneCurvePoint(1f, 0.98f)),
            red = listOf(ToneCurvePoint(0f, 0f), ToneCurvePoint(0.4f, 0.49f), ToneCurvePoint(1f, 1f))
        )
        val firstFolder = vm.uiState.value.lutFolders.first().name
        vm.toggleLutFolder(firstFolder)
        awaitFolderLoaded(vm, firstFolder)
        val lutId = vm.uiState.value.lutFolders.first { it.name == firstFolder }.items.first().id
        vm.selectLut(lutId)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setSliderDragging(true)
        vm.updateGradingParams {
            it.copy(
                exposure = 0.55f,
                clarity = 26f,
                vibrance = 34f,
                toneCurve = customCurve
            )
        }
        vm.setSliderDragging(false)

        vm.saveCurrentLookAsPreset("Warm Golden Film")
        val saveDeadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < saveDeadline &&
            (vm.uiState.value.userPresets.isEmpty() || vm.uiState.value.userPresetThumbnails.isEmpty())
        ) {
            testDispatcher.scheduler.advanceUntilIdle()
            Thread.sleep(20)
        }

        assertEquals(1, vm.uiState.value.userPresets.size)
        val saved = vm.uiState.value.userPresets.first()
        assertEquals("Warm Golden Film", saved.name)
        assertEquals(lutId, saved.lutId)
        assertEquals(0.55f, saved.gradingParams.exposure, 0.001f)
        assertEquals(0.66f, saved.gradingParams.toneCurve.master[1].y, 0.001f)
        assertNotNull(vm.uiState.value.userPresetThumbnails[saved.id])

        // Reload fresh ViewModel and confirm persistent disk load + apply
        val vm2 = EditorViewModel(app)
        awaitInitialLoad(vm2)
        val reloadDeadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < reloadDeadline && vm2.uiState.value.userPresets.isEmpty()) {
            testDispatcher.scheduler.advanceUntilIdle()
            Thread.sleep(20)
        }
        assertEquals(1, vm2.uiState.value.userPresets.size)

        vm2.applyUserPreset(saved.id)
        val applyDeadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < applyDeadline && vm2.uiState.value.selectedUserPresetId != saved.id) {
            testDispatcher.scheduler.advanceUntilIdle()
            Thread.sleep(20)
        }
        assertEquals(saved.id, vm2.uiState.value.selectedUserPresetId)
        assertEquals(lutId, vm2.uiState.value.selectedLutId)
        assertEquals(0.55f, vm2.uiState.value.gradingParams.exposure, 0.001f)
        assertEquals(0.66f, vm2.uiState.value.gradingParams.toneCurve.master[1].y, 0.001f)

        vm2.deleteUserPreset(saved.id)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm2.uiState.value.userPresets.isEmpty())
        assertNull(vm2.uiState.value.selectedUserPresetId)
    }

    @Test
    fun batchCopyPaste_persistsCopiedLookAcrossGalleryPhotosWithoutOverwritingTargetSpatialEdits() = runTest(testDispatcher) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        UserPresetStore.clearClipboardEdits(app)

        val vm = EditorViewModel(app)
        awaitInitialLoad(vm)

        val folder = vm.uiState.value.lutFolders.first().name
        vm.toggleLutFolder(folder)
        awaitFolderLoaded(vm, folder)
        val lutId = vm.uiState.value.lutFolders.first { it.name == folder }.items.first().id
        vm.selectLut(lutId)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setSliderDragging(true)
        vm.updateGradingParams {
            it.copy(
                exposure = 0.72f,
                contrast = 25f,
                temperature = 18f,
                toneCurve = it.toneCurve.withChannel(
                    ToneCurveChannel.BLUE,
                    listOf(ToneCurvePoint(0f, 0.05f), ToneCurvePoint(0.5f, 0.42f), ToneCurvePoint(1f, 0.95f))
                )
            )
        }
        vm.setSliderDragging(false)
        vm.copyEdits()
        assertTrue(vm.uiState.value.canPasteEdits)

        // Open a brand new photo from disk/URI -> edits reset, clipboard MUST remain available!
        val photo2File = java.io.File(app.cacheDir, "test_batch_paste_photo2.png")
        val photo2Bmp = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(120, 145, 165))
        }
        photo2File.outputStream().use { photo2Bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        photo2Bmp.recycle()

        try {
            val startToken = vm.uiState.value.viewportResetToken
            vm.openPhotoFromUri(Uri.fromFile(photo2File))
            val loadDeadline = System.currentTimeMillis() + 5_000L
            while (System.currentTimeMillis() < loadDeadline && vm.uiState.value.viewportResetToken <= startToken) {
                testDispatcher.scheduler.advanceUntilIdle()
                Thread.sleep(20)
            }
            assertTrue("Expected copied edits to remain pasteable on newly opened photo", vm.uiState.value.canPasteEdits)
            assertEquals(0f, vm.uiState.value.gradingParams.exposure, 0.001f)
            assertNull(vm.uiState.value.selectedLutId)

            // Give the new photo its own 1:1 crop and local mask, then Paste the copied edits
            vm.selectCropAspectPreset(CropAspectPreset.RATIO_1_1)
            vm.addLocalMask(MaskType.RADIAL)
            val targetCrop = vm.uiState.value.gradingParams.crop
            val targetMaskId = requireNotNull(vm.uiState.value.gradingParams.masks.firstOrNull()?.id)

            vm.pasteEdits()
            val pasteDeadline = System.currentTimeMillis() + 5_000L
            while (System.currentTimeMillis() < pasteDeadline &&
                vm.uiState.value.gradingParams.exposure != 0.72f
            ) {
                testDispatcher.scheduler.advanceUntilIdle()
                Thread.sleep(20)
            }

            assertEquals(0.72f, vm.uiState.value.gradingParams.exposure, 0.001f)
            assertEquals(25f, vm.uiState.value.gradingParams.contrast, 0.001f)
            assertEquals(18f, vm.uiState.value.gradingParams.temperature, 0.001f)
            assertEquals(lutId, vm.uiState.value.selectedLutId)
            assertEquals(0.42f, vm.uiState.value.gradingParams.toneCurve.blue[1].y, 0.001f)
            // Target photo's own spatial crop and local mask remain intact
            assertEquals(targetCrop, vm.uiState.value.gradingParams.crop)
            assertEquals(targetMaskId, vm.uiState.value.gradingParams.masks.firstOrNull()?.id)
        } finally {
            photo2File.delete()
        }
    }

    @Test
    fun toneCurve512_packsFullyOpaque256x2WithoutAlphaPremultiplicationCrush() {
        val curve = ToneCurveData(
            master = listOf(
                ToneCurvePoint(0f, 34f / 255f),
                ToneCurvePoint(56f / 255f, 53f / 255f),
                ToneCurvePoint(130f / 255f, 127f / 255f),
                ToneCurvePoint(1f, 241f / 255f)
            ),
            red = listOf(
                ToneCurvePoint(0f, 0f),
                ToneCurvePoint(0.5f, 0.56f),
                ToneCurvePoint(1f, 1f)
            )
        )
        val packed = curve.buildPackedRgba512()
        assertEquals(512, packed.size)

        for (i in packed.indices) {
            val alpha = (packed[i] ushr 24) and 0xFF
            assertEquals("Pixel $i must have opaque 0xFF alpha", 0xFF, alpha)
        }

        val rMid = (packed[128] ushr 16) and 0xFF
        val gMid = (packed[128] ushr 8) and 0xFF
        assertTrue("Red midtone should be lifted above 138, got $rMid", rMid in 138..148)
        assertTrue("Green midtone should remain near linear ~128, got $gMid", gMid in 125..131)

        val masterBlack = (packed[256 + 0] ushr 16) and 0xFF
        val masterWhite = (packed[256 + 255] ushr 16) and 0xFF
        assertEquals(34, masterBlack)
        assertEquals(241, masterWhite)
    }

    @Test
    fun ninetiesVibesXmpAndBuiltInCubeLut_renderBalancedTonesAndDefaultTo75Percent() = runTest(testDispatcher) {
        val app = ApplicationProvider.getApplicationContext<Application>()

        val vibesFolder = "presets/90s Vibes - Desktop Presets"
        val vibesAsset = app.assets.list(vibesFolder)?.firstOrNull { it.endsWith(".xmp", ignoreCase = true) }
        assertNotNull("Expected bundled 90s Vibes .xmp preset", vibesAsset)
        val vibesLut = app.assets.open("$vibesFolder/$vibesAsset").use { stream ->
            XmpPresetParser.parseToCubeLut(
                inputStream = stream,
                id = "test_90s_vibes",
                fallbackTitle = "90's Vibes 01",
                fallbackCategory = "90s Vibes",
                bakeTable = true
            )
        }
        val settings = requireNotNull(vibesLut.xmpSettings)
        val params75 = GradingParams.fromXmpSettings(settings, 0.75f)

        val src = Bitmap.createBitmap(16, 8, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until 8) {
                for (x in 0 until 8) setPixel(x, y, Color.rgb(128, 128, 128))
                for (x in 8 until 16) setPixel(x, y, Color.rgb(55, 55, 55))
            }
        }
        val graded = ColorGradingEngine.processFullResolution(src, params75, null)
        val midPx = graded.getPixel(4, 4)
        val shadowPx = graded.getPixel(12, 4)
        val midLuma = 0.2126f * Color.red(midPx) + 0.7152f * Color.green(midPx) + 0.0722f * Color.blue(midPx)
        val shadowLuma = 0.2126f * Color.red(shadowPx) + 0.7152f * Color.green(shadowPx) + 0.0722f * Color.blue(shadowPx)

        assertTrue("90's Vibes midtone must NOT crush to dark shadows (got luma=$midLuma)", midLuma > 75f)
        assertTrue("90's Vibes shadows must retain detail and not crush to 0 (got luma=$shadowLuma)", shadowLuma > 24f)

        val vm = EditorViewModel(app)
        awaitInitialLoad(vm)
        val cubeFolder = vm.uiState.value.lutFolders.first { folder ->
            folder.descriptors.any { it.sourceFormat == LutSourceFormat.CUBE }
        }.name
        vm.toggleLutFolder(cubeFolder)
        awaitFolderLoaded(vm, cubeFolder)
        val cubeItem = vm.uiState.value.lutFolders.first { it.name == cubeFolder }.items
            .first { it.sourceFormat == LutSourceFormat.CUBE }
        vm.selectLut(cubeItem.id)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(0.75f, vm.uiState.value.gradingParams.lutIntensity, 0.001f)
        val cubeGraded = ColorGradingEngine.processFullResolution(src, vm.uiState.value.gradingParams, vm.uiState.value.activeLut)
        val cubeMidPx = cubeGraded.getPixel(4, 4)
        val cubeMidLuma = 0.2126f * Color.red(cubeMidPx) + 0.7152f * Color.green(cubeMidPx) + 0.0722f * Color.blue(cubeMidPx)
        assertTrue("Built-in .cube LUT midtone must stay balanced (got luma=$cubeMidLuma)", cubeMidLuma in 65f..200f)

        src.recycle()
        graded.recycle()
        cubeGraded.recycle()
    }

    @Test
    fun touchAdjust_tapDetectsColorHslBandAndSwipesAdjustToneZoneAndSaturationWithUndo() = runTest(testDispatcher) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = EditorViewModel(app)
        awaitInitialLoad(vm)

        val syntheticFile = java.io.File(app.cacheDir, "test_touch_adjust_zones.png")
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until 64) {
                for (x in 0 until 64) {
                    val c = when {
                        x < 32 && y < 32 -> Color.rgb(28, 30, 34)
                        x >= 32 && y < 32 -> Color.rgb(225, 222, 218)
                        x < 32 && y >= 32 -> Color.rgb(35, 110, 235)
                        else -> Color.rgb(225, 42, 38)
                    }
                    setPixel(x, y, c)
                }
            }
        }
        syntheticFile.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()

        try {
            val startToken = vm.uiState.value.viewportResetToken
            vm.openPhotoFromUri(Uri.fromFile(syntheticFile))
            val loadDeadline = System.currentTimeMillis() + 5_000L
            while (System.currentTimeMillis() < loadDeadline && vm.uiState.value.viewportResetToken <= startToken) {
                testDispatcher.scheduler.advanceUntilIdle()
                Thread.sleep(20)
            }

            assertFalse(vm.uiState.value.isTouchAdjustEnabled)
            vm.toggleTouchAdjust()
            assertTrue(vm.uiState.value.isTouchAdjustEnabled)

            vm.onTouchAdjustTap(0.25f, 0.75f)
            val blueDeadline = System.currentTimeMillis() + 5_000L
            while (System.currentTimeMillis() < blueDeadline && vm.uiState.value.touchAdjustSelectedBand == null) {
                testDispatcher.scheduler.advanceUntilIdle()
                Thread.sleep(20)
            }
            assertEquals(com.example.editor.HslBand.BLUE, vm.uiState.value.touchAdjustSelectedBand)
            assertNotNull(vm.uiState.value.touchAdjustSwatchArgb)

            vm.setSliderDragging(true)
            vm.updateTouchAdjustBand { it.copy(hue = -24f, saturation = 30f, luminance = -15f) }
            vm.setSliderDragging(false)
            val blueAdj = vm.uiState.value.gradingParams.hsl[com.example.editor.HslBand.BLUE]
            assertEquals(-24f, blueAdj.hue, 0.01f)
            assertEquals(30f, blueAdj.saturation, 0.01f)
            assertEquals(-15f, blueAdj.luminance, 0.01f)
            assertTrue(vm.uiState.value.canUndo)

            vm.resetTouchAdjustBand()
            assertTrue(vm.uiState.value.gradingParams.hsl[com.example.editor.HslBand.BLUE].isDefault)

            vm.onTouchAdjustTap(0.75f, 0.75f)
            val redDeadline = System.currentTimeMillis() + 5_000L
            while (System.currentTimeMillis() < redDeadline &&
                vm.uiState.value.touchAdjustSelectedBand != com.example.editor.HslBand.RED
            ) {
                testDispatcher.scheduler.advanceUntilIdle()
                Thread.sleep(20)
            }
            assertEquals(com.example.editor.HslBand.RED, vm.uiState.value.touchAdjustSelectedBand)

            vm.onTouchAdjustSwipeStart(0.20f, 0.20f)
            vm.onTouchAdjustSwipeUpdate(isVertical = true, totalFraction = 0.25f)
            assertTrue(vm.uiState.value.isTouchAdjustSwiping)
            assertEquals("Shadows", vm.uiState.value.touchAdjustHudLabel)
            assertTrue(vm.uiState.value.gradingParams.shadows > 20f)
            vm.onTouchAdjustSwipeEnd(commit = true)
            assertFalse(vm.uiState.value.isTouchAdjustSwiping)
            assertNull(vm.uiState.value.touchAdjustHudLabel)
            val committedShadows = vm.uiState.value.gradingParams.shadows

            vm.onTouchAdjustSwipeStart(0.80f, 0.20f)
            vm.onTouchAdjustSwipeUpdate(isVertical = true, totalFraction = -0.30f)
            assertEquals("Highlights", vm.uiState.value.touchAdjustHudLabel)
            assertTrue(vm.uiState.value.gradingParams.highlights < -20f)
            vm.onTouchAdjustSwipeEnd(commit = false)
            assertEquals(0f, vm.uiState.value.gradingParams.highlights, 0.01f)
            assertEquals(committedShadows, vm.uiState.value.gradingParams.shadows, 0.01f)

            vm.onTouchAdjustSwipeStart(0.50f, 0.50f)
            vm.onTouchAdjustSwipeUpdate(isVertical = false, totalFraction = 0.25f)
            assertEquals("Saturation", vm.uiState.value.touchAdjustHudLabel)
            assertTrue(vm.uiState.value.gradingParams.saturation > 20f)
            vm.onTouchAdjustSwipeEnd(commit = true)
            assertTrue(vm.uiState.value.gradingParams.saturation > 20f)

            vm.undo()
            assertEquals(0f, vm.uiState.value.gradingParams.saturation, 0.01f)

            vm.selectTab(StudioTab.CROP)
            assertFalse(vm.uiState.value.isTouchAdjustEnabled)
            assertNull(vm.uiState.value.touchAdjustSelectedBand)
        } finally {
            syntheticFile.delete()
        }
    }
}









