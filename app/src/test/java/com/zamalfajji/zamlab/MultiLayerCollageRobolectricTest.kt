package com.zamalfajji.zamlab

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.zamalfajji.zamlab.editor.ColorGradingState
import com.zamalfajji.zamlab.editor.EditorViewModel
import com.zamalfajji.zamlab.editor.GradingParams
import com.zamalfajji.zamlab.editor.ImageLayer
import com.zamalfajji.zamlab.editor.LayerCompositor
import com.zamalfajji.zamlab.editor.LayerIds
import com.zamalfajji.zamlab.editor.LayerTransformMath
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MultiLayerCollageRobolectricTest {

    private val testDispatcher = StandardTestDispatcher()
    private val trackedViewModels = mutableListOf<EditorViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        trackedViewModels.forEach { it.viewModelScope.cancel() }
        trackedViewModels.clear()
        testDispatcher.scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
    }

    private fun awaitInitialLoad(vm: EditorViewModel) {
        trackedViewModels.add(vm)
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            testDispatcher.scheduler.advanceUntilIdle()
            val state = vm.uiState.value
            if (state.fullWidth > 0 && state.layers.isNotEmpty()) {
                return
            }
            Thread.sleep(20)
        }
        throw AssertionError(
            "Timed out waiting for initial load: fullWidth=${vm.uiState.value.fullWidth}, layers=${vm.uiState.value.layers.size}"
        )
    }

    private fun solidBitmap(width: Int, height: Int, color: Int): Bitmap {
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(color)
        }
    }

    @Test
    fun initialLoad_createsSingleBackgroundLayerAsActive() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = EditorViewModel(app)
        awaitInitialLoad(vm)

        val state = vm.uiState.value
        assertEquals(1, state.layers.size)
        val bg = state.layers.first()
        assertEquals(LayerIds.BACKGROUND, bg.id)
        assertEquals("Background", bg.name)
        assertTrue(bg.isBackground)
        assertTrue(bg.isVisible)
        assertEquals(1f, bg.opacity, 0.001f)
        assertEquals(LayerIds.BACKGROUND, state.activeLayerId)
        assertFalse(state.hasMultipleLayers)
        assertFalse(state.isActiveLayerTransformable)
    }

    @Test
    fun addPhotoLayer_isolatesColorGradingPerLayerAndSwitchesActiveTarget() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = EditorViewModel(app)
        awaitInitialLoad(vm)

        // Edit Background first
        vm.updateGradingParams { it.copy(exposure = 0.75f) }
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(0.75f, vm.uiState.value.gradingParams.exposure, 0.001f)
        assertEquals(0.75f, vm.uiState.value.backgroundGradingParams.exposure, 0.001f)

        // Add overlay layer — should become active with clean default grading
        val overlayBmp = solidBitmap(64, 64, Color.rgb(40, 210, 60))
        vm.addPhotoLayerBitmap(overlayBmp, name = "Photo 2")
        testDispatcher.scheduler.advanceUntilIdle()

        val afterAdd = vm.uiState.value
        assertEquals(2, afterAdd.layers.size)
        assertTrue(afterAdd.hasMultipleLayers)
        assertTrue(afterAdd.isActiveLayerTransformable)
        val overlayId = afterAdd.activeLayerId!!
        assertTrue(overlayId != LayerIds.BACKGROUND)
        assertEquals(LayerTransformMath.DEFAULT_OVERLAY_SCALE, afterAdd.activeLayer!!.scale, 0.001f)
        assertEquals(0f, afterAdd.gradingParams.exposure, 0.001f)

        // Adjust overlay exposure and contrast — Background must remain strictly untouched
        vm.updateGradingParams { it.copy(exposure = -1.5f, contrast = 35f) }
        testDispatcher.scheduler.advanceUntilIdle()

        val afterOverlayEdit = vm.uiState.value
        assertEquals(-1.5f, afterOverlayEdit.gradingParams.exposure, 0.001f)
        assertEquals(35f, afterOverlayEdit.gradingParams.contrast, 0.001f)
        assertEquals(0.75f, afterOverlayEdit.backgroundGradingParams.exposure, 0.001f)
        assertEquals(0f, afterOverlayEdit.backgroundGradingParams.contrast, 0.001f)

        // Switch back to Background — active sliders must reflect Background state
        vm.selectLayer(LayerIds.BACKGROUND)
        testDispatcher.scheduler.advanceUntilIdle()
        val backToBg = vm.uiState.value
        assertEquals(LayerIds.BACKGROUND, backToBg.activeLayerId)
        assertEquals(0.75f, backToBg.gradingParams.exposure, 0.001f)
        assertEquals(0f, backToBg.gradingParams.contrast, 0.001f)

        // Switch back to overlay — overlay adjustments must still be intact
        vm.selectLayer(overlayId)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(-1.5f, vm.uiState.value.gradingParams.exposure, 0.001f)
        assertEquals(35f, vm.uiState.value.gradingParams.contrast, 0.001f)
    }

    @Test
    fun layerOperations_reorderVisibilityOpacityDeleteAndUndoRedo() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = EditorViewModel(app)
        awaitInitialLoad(vm)

        vm.addPhotoLayerBitmap(solidBitmap(48, 48, Color.GREEN), name = "Layer 2")
        vm.addPhotoLayerBitmap(solidBitmap(48, 48, Color.BLUE), name = "Layer 3")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(3, vm.uiState.value.layers.size)
        val layer2Id = vm.uiState.value.layers[1].id
        val layer3Id = vm.uiState.value.layers[2].id

        // Move Layer 2 up (above Layer 3)
        vm.moveLayerUp(layer2Id)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(LayerIds.BACKGROUND, layer3Id, layer2Id), vm.uiState.value.layers.map { it.id })

        // Move Layer 2 back down
        vm.moveLayerDown(layer2Id)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(LayerIds.BACKGROUND, layer2Id, layer3Id), vm.uiState.value.layers.map { it.id })

        // Toggle visibility & opacity
        vm.toggleLayerVisibility(layer3Id)
        vm.updateLayerOpacity(layer2Id, 0.45f)
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.uiState.value.layers.first { it.id == layer3Id }.isVisible)
        assertEquals(0.45f, vm.uiState.value.layers.first { it.id == layer2Id }.opacity, 0.001f)

        // Background layer cannot be deleted
        vm.deleteLayer(LayerIds.BACKGROUND)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(3, vm.uiState.value.layers.size)

        // Deleting an overlay removes it and can be undone/redone cleanly
        vm.deleteLayer(layer3Id)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(2, vm.uiState.value.layers.size)
        assertFalse(vm.uiState.value.layers.any { it.id == layer3Id })

        vm.undo()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(3, vm.uiState.value.layers.size)
        assertTrue(vm.uiState.value.layers.any { it.id == layer3Id })

        vm.redo()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(2, vm.uiState.value.layers.size)

        // Drawer starts collapsed as a compact floating pill and expands/collapses on demand
        assertFalse(vm.uiState.value.isLayerDrawerExpanded)
        vm.toggleLayerDrawer()
        assertTrue(vm.uiState.value.isLayerDrawerExpanded)
        vm.toggleLayerDrawer()
        assertFalse(vm.uiState.value.isLayerDrawerExpanded)
    }

    @Test
    fun gestureTransform_movesScalesRotatesClampsAndResetsActiveOverlay() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = EditorViewModel(app)
        awaitInitialLoad(vm)

        vm.addPhotoLayerBitmap(solidBitmap(64, 64, Color.YELLOW), name = "Sticker")
        testDispatcher.scheduler.advanceUntilIdle()
        val overlayId = vm.uiState.value.activeLayerId

        vm.beginLayerTransformGesture()
        vm.transformActiveLayer(
            deltaOffsetX = 0.25f,
            deltaOffsetY = -0.15f,
            scaleFactor = 1.5f,
            deltaRotationDegrees = 45f
        )
        testDispatcher.scheduler.advanceUntilIdle()

        val transformed = vm.uiState.value.layers.first { it.id == overlayId }
        assertEquals(0.25f, transformed.offsetX, 0.001f)
        assertEquals(-0.15f, transformed.offsetY, 0.001f)
        assertEquals(LayerTransformMath.DEFAULT_OVERLAY_SCALE * 1.5f, transformed.scale, 0.001f)
        assertEquals(45f, transformed.rotation, 0.001f)

        // Reset restores defaults
        vm.resetActiveLayerTransform()
        testDispatcher.scheduler.advanceUntilIdle()
        val reset = vm.uiState.value.layers.first { it.id == overlayId }
        assertEquals(0f, reset.offsetX, 0.001f)
        assertEquals(0f, reset.offsetY, 0.001f)
        assertEquals(LayerTransformMath.DEFAULT_OVERLAY_SCALE, reset.scale, 0.001f)
        assertEquals(0f, reset.rotation, 0.001f)

        // Undo brings back transformed geometry
        vm.undo()
        testDispatcher.scheduler.advanceUntilIdle()
        val undone = vm.uiState.value.layers.first { it.id == overlayId }
        assertEquals(0.25f, undone.offsetX, 0.001f)
        assertEquals(45f, undone.rotation, 0.001f)
    }

    @Test
    fun compositeExport_blendsVisibleLayersRespectsVisibilityPerLayerGradingAndMask() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val bgBitmap = solidBitmap(120, 120, Color.rgb(180, 20, 20))
        val overlayBitmap = solidBitmap(120, 120, Color.rgb(20, 180, 20))

        val bgLayer = ImageLayer(
            id = LayerIds.BACKGROUND,
            name = "Background",
            bitmap = bgBitmap,
            isBackground = true
        )
        val overlayLayer = ImageLayer(
            id = "layer_2",
            name = "Overlay",
            bitmap = overlayBitmap,
            scale = 0.5f,
            opacity = 1f,
            isVisible = true,
            isBackground = false
        )

        // 1. Both visible: center pixel should be green, corner pixel should remain red
        val composed = LayerCompositor.compositeForExport(
            context = app,
            layers = listOf(bgLayer, overlayLayer),
            outWidth = 120,
            outHeight = 120,
            preRenderedBackground = bgBitmap,
            preferGpuOffscreen = false
        )
        assertNotNull(composed)
        val centerPixel = composed.getPixel(60, 60)
        val cornerPixel = composed.getPixel(4, 4)
        assertTrue("Center should be green: r=${Color.red(centerPixel)} g=${Color.green(centerPixel)}", Color.green(centerPixel) > 140 && Color.red(centerPixel) < 60)
        assertTrue("Corner should be red: r=${Color.red(cornerPixel)} g=${Color.green(cornerPixel)}", Color.red(cornerPixel) > 140 && Color.green(cornerPixel) < 60)
        composed.recycle()

        // 2. Per-layer exposure boost on overlay brightens center without touching red corners
        val brightOverlay = overlayLayer.copy(
            colorGradingState = ColorGradingState(gradingParams = GradingParams(exposure = 1.5f))
        )
        val composedBright = LayerCompositor.compositeForExport(
            context = app,
            layers = listOf(bgLayer, brightOverlay),
            outWidth = 120,
            outHeight = 120,
            preRenderedBackground = bgBitmap,
            preferGpuOffscreen = false
        )
        val brightCenter = composedBright.getPixel(60, 60)
        val brightCorner = composedBright.getPixel(4, 4)
        assertTrue("Brightened overlay green channel should increase", Color.green(brightCenter) > Color.green(centerPixel))
        assertEquals(Color.red(cornerPixel), Color.red(brightCorner))
        composedBright.recycle()

        // 3. Hidden overlay leaves center purely red
        val hiddenOverlay = overlayLayer.copy(isVisible = false)
        val composedHidden = LayerCompositor.compositeForExport(
            context = app,
            layers = listOf(bgLayer, hiddenOverlay),
            outWidth = 120,
            outHeight = 120,
            preRenderedBackground = bgBitmap,
            preferGpuOffscreen = false
        )
        val hiddenCenter = composedHidden.getPixel(60, 60)
        assertTrue("Hidden overlay center must show red background", Color.red(hiddenCenter) > 140 && Color.green(hiddenCenter) < 60)
        composedHidden.recycle()

        // 4. Transparent maskBitmap punches out the overlay completely
        val emptyMask = solidBitmap(120, 120, Color.TRANSPARENT)
        val maskedOverlay = overlayLayer.copy(maskBitmap = emptyMask)
        val composedMasked = LayerCompositor.compositeForExport(
            context = app,
            layers = listOf(bgLayer, maskedOverlay),
            outWidth = 120,
            outHeight = 120,
            preRenderedBackground = bgBitmap,
            preferGpuOffscreen = false
        )
        val maskedCenter = composedMasked.getPixel(60, 60)
        assertTrue("Transparent mask should reveal red background", Color.red(maskedCenter) > 140 && Color.green(maskedCenter) < 60)
        composedMasked.recycle()
        emptyMask.recycle()
        bgBitmap.recycle()
        overlayBitmap.recycle()
    }
}
