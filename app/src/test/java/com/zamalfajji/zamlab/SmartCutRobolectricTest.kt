package com.zamalfajji.zamlab

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import com.google.mlkit.common.MlKitException
import com.zamalfajji.zamlab.ai.RawSubjectSegmentation
import com.zamalfajji.zamlab.ai.SmartCutProcessor
import com.zamalfajji.zamlab.ai.SmartCutResult
import com.zamalfajji.zamlab.editor.EditorViewModel
import com.zamalfajji.zamlab.editor.LayerCompositor
import com.zamalfajji.zamlab.editor.LayerIds
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
class SmartCutRobolectricTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun awaitInitialLoad(vm: EditorViewModel) {
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            testDispatcher.scheduler.advanceUntilIdle()
            val state = vm.uiState.value
            if (state.fullWidth > 0 && state.layers.isNotEmpty()) {
                return
            }
            Thread.sleep(20)
        }
        throw AssertionError("Timed out waiting for initial photo load")
    }

    private fun awaitSmartCutDone(vm: EditorViewModel) {
        val deadline = System.currentTimeMillis() + 10_000L
        while (System.currentTimeMillis() < deadline) {
            testDispatcher.scheduler.advanceUntilIdle()
            if (!vm.uiState.value.isSmartCutProcessing && vm.uiState.value.layers.size >= 2) {
                return
            }
            Thread.sleep(25)
        }
        throw AssertionError(
            "Timed out waiting for Smart Cut completion: processing=${vm.uiState.value.isSmartCutProcessing}, layers=${vm.uiState.value.layers.size}"
        )
    }

    private fun createCenteredSubjectBitmap(
        width: Int = 96,
        height: Int = 96,
        bgColor: Int = Color.rgb(30, 80, 210),
        subjectColor: Int = Color.rgb(220, 40, 40)
    ): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(bgColor)
        val paint = Paint().apply { color = subjectColor }
        canvas.drawRect(width * 0.35f, height * 0.35f, width * 0.65f, height * 0.65f, paint)
        return bmp
    }

    private fun buildCenterRectConfidenceMask(width: Int, height: Int): FloatArray {
        val x0 = (width * 0.35f).toInt()
        val x1 = (width * 0.65f).toInt()
        val y0 = (height * 0.35f).toInt()
        val y1 = (height * 0.65f).toInt()
        return FloatArray(width * height) { idx ->
            val x = idx % width
            val y = idx / width
            when {
                x in (x0 + 1) until (x1 - 1) && y in (y0 + 1) until (y1 - 1) -> 0.98f
                x in x0..x1 && y in y0..y1 -> 0.50f
                else -> 0.02f
            }
        }
    }

    @Test
    fun confidenceToSoftAlpha_suppressesLowNoiseAndPreservesSoftEdges() {
        assertEquals(0, SmartCutProcessor.confidenceToSoftAlpha(0.0f))
        assertEquals(0, SmartCutProcessor.confidenceToSoftAlpha(0.10f))
        val mid = SmartCutProcessor.confidenceToSoftAlpha(0.50f)
        assertTrue("Expected soft mid-edge alpha near 128, got $mid", mid in 115..140)
        assertEquals(255, SmartCutProcessor.confidenceToSoftAlpha(0.95f))
        assertEquals(255, SmartCutProcessor.confidenceToSoftAlpha(1.0f))
        assertEquals(0, SmartCutProcessor.confidenceToSoftAlpha(Float.NaN))
    }

    @Test
    fun segmentWithProvider_cleanBgExtractsTransparentCutoutAndInpaintsGhostFromBackground() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val src = createCenteredSubjectBitmap(96, 96)
        val mask = buildCenterRectConfidenceMask(96, 96)

        val result = SmartCutProcessor.segmentWithProvider(
            context = app,
            sourceBitmap = src,
            removeSubjectFromBackground = true,
            maskProvider = { bmp ->
                RawSubjectSegmentation(
                    confidenceMask = mask,
                    foregroundBitmap = null,
                    width = bmp.width,
                    height = bmp.height
                )
            }
        )

        assertTrue("Expected Success but got $result", result is SmartCutResult.Success)
        val success = result as SmartCutResult.Success
        assertTrue(success.backgroundInpainted)
        assertTrue(success.subjectCoverageFraction in 0.05f..0.25f)

        // Subject cutout: center is opaque red, corner is fully transparent
        val fgCenter = success.foregroundBitmap.getPixel(48, 48)
        val fgCorner = success.foregroundBitmap.getPixel(4, 4)
        assertEquals(255, Color.alpha(fgCenter))
        assertTrue("Subject center should remain red", Color.red(fgCenter) > 190 && Color.blue(fgCenter) < 70)
        assertEquals(0, Color.alpha(fgCorner))

        // Soft alpha mask matches cutout geometry
        assertEquals(255, Color.alpha(success.alphaMaskBitmap.getPixel(48, 48)))
        assertEquals(0, Color.alpha(success.alphaMaskBitmap.getPixel(4, 4)))

        // Clean background plate: center red subject was cleanly inpainted with surrounding blue!
        val bgCenter = success.backgroundBitmap.getPixel(48, 48)
        val bgCorner = success.backgroundBitmap.getPixel(4, 4)
        assertTrue("Clean BG center should be blue (no red ghost): r=${Color.red(bgCenter)} b=${Color.blue(bgCenter)}", Color.blue(bgCenter) > 160 && Color.red(bgCenter) < 90)
        assertTrue("Clean BG corner remains untouched blue", Color.blue(bgCorner) > 180 && Color.red(bgCorner) < 60)

        success.foregroundBitmap.recycle()
        success.alphaMaskBitmap.recycle()
        success.backgroundBitmap.recycle()
        src.recycle()
    }

    @Test
    fun segmentWithProvider_detectsMissingSubjectGracefully() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val src = createCenteredSubjectBitmap(64, 64)
        val emptyMask = FloatArray(64 * 64) { 0.01f }

        val result = SmartCutProcessor.segmentWithProvider(
            context = app,
            sourceBitmap = src,
            removeSubjectFromBackground = false,
            maskProvider = { bmp ->
                RawSubjectSegmentation(emptyMask, null, bmp.width, bmp.height)
            }
        )

        assertTrue("Expected NoSubjectDetected for empty mask", result is SmartCutResult.NoSubjectDetected)
        src.recycle()
    }

    @Test
    fun viewModelSmartCut_splitsIntoIndependentSubjectAndBackgroundLayersWithUndoRedoAndGrading() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = EditorViewModel(app)
        awaitInitialLoad(vm)

        // Disable heavy inpainting on the large starter proxy just for fast deterministic test,
        // then also verify clean-BG toggle works properly
        assertTrue(vm.uiState.value.smartCutRemoveSubjectFromBg)
        vm.toggleSmartCutRemoveSubjectFromBg()
        assertFalse(vm.uiState.value.smartCutRemoveSubjectFromBg)

        val originalPreview = vm.uiState.value.previewIdleBitmap
        assertEquals(1, vm.uiState.value.layers.size)

        vm.runSmartCutOnActiveLayerWithProvider { bmp ->
            RawSubjectSegmentation(
                confidenceMask = buildCenterRectConfidenceMask(bmp.width, bmp.height),
                foregroundBitmap = null,
                width = bmp.width,
                height = bmp.height
            )
        }
        awaitSmartCutDone(vm)

        val splitState = vm.uiState.value
        assertEquals(2, splitState.layers.size)
        val bgLayer = splitState.layers[0]
        val subjectLayer = splitState.layers[1]

        assertEquals(LayerIds.BACKGROUND, bgLayer.id)
        assertEquals("Background - Background", bgLayer.name)
        assertTrue(bgLayer.isBackground)

        assertEquals("Background - Subject", subjectLayer.name)
        assertFalse(subjectLayer.isBackground)
        assertNotNull(subjectLayer.maskBitmap)
        assertEquals(1f, subjectLayer.scale, 0.001f)
        assertEquals(1f, subjectLayer.defaultScale, 0.001f)
        assertFalse(subjectLayer.hasNonDefaultTransform)
        assertEquals(subjectLayer.id, splitState.activeLayerId)

        // Independent grading on Subject does NOT alter Background
        vm.updateGradingParams { it.copy(exposure = 1.4f, contrast = 28f) }
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(1.4f, vm.uiState.value.activeLayer!!.colorGradingState.gradingParams.exposure, 0.001f)
        assertEquals(0f, vm.uiState.value.backgroundGradingParams.exposure, 0.001f)

        // Switch to Background and grade it independently — Subject stays at +1.4 EV
        vm.selectLayer(bgLayer.id)
        vm.updateGradingParams { it.copy(exposure = -1.2f, saturation = -50f) }
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(-1.2f, vm.uiState.value.backgroundGradingParams.exposure, 0.001f)
        assertEquals(
            1.4f,
            vm.uiState.value.layers.first { it.id == subjectLayer.id }.colorGradingState.gradingParams.exposure,
            0.001f
        )

        // Select Subject and transform it (drag + scale + rotate), then reset back to aligned 1.0x
        vm.selectLayer(subjectLayer.id)
        vm.beginLayerTransformGesture()
        vm.transformActiveLayer(deltaOffsetX = 0.2f, deltaOffsetY = -0.1f, scaleFactor = 1.25f, deltaRotationDegrees = 22f)
        testDispatcher.scheduler.advanceUntilIdle()
        val moved = vm.uiState.value.layers.first { it.id == subjectLayer.id }
        assertTrue(moved.hasNonDefaultTransform)
        assertEquals(0.2f, moved.offsetX, 0.001f)
        assertEquals(1.25f, moved.scale, 0.001f)

        vm.resetActiveLayerTransform()
        testDispatcher.scheduler.advanceUntilIdle()
        val resetSubject = vm.uiState.value.layers.first { it.id == subjectLayer.id }
        assertEquals(0f, resetSubject.offsetX, 0.001f)
        assertEquals(1f, resetSubject.scale, 0.001f)
        assertFalse(resetSubject.hasNonDefaultTransform)

        // Composite export succeeds cleanly with both graded Subject (masked) & graded Background
        val exported = LayerCompositor.compositeForExport(
            context = app,
            layers = vm.uiState.value.layers,
            outWidth = 128,
            outHeight = 128,
            preRenderedBackground = null,
            preferGpuOffscreen = false
        )
        assertEquals(128, exported.width)
        assertEquals(128, exported.height)
        exported.recycle()

        // Undo back through transform + grading + split in sequence until we revert the Smart Cut split
        var safety = 12
        while (vm.uiState.value.layers.size > 1 && vm.uiState.value.canUndo && safety-- > 0) {
            vm.undo()
            testDispatcher.scheduler.advanceUntilIdle()
        }
        assertEquals(1, vm.uiState.value.layers.size)
        assertEquals("Background", vm.uiState.value.layers.first().name)
        assertEquals(originalPreview, vm.uiState.value.previewIdleBitmap)

        // Redo restores the 2-layer Smart Cut split in one tap
        vm.redo()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(2, vm.uiState.value.layers.size)
        assertEquals("Background - Subject", vm.uiState.value.layers[1].name)
    }

    @Test
    fun mlKitModuleWaitingException_fallsBackToOnDeviceGrabCutSaliencyAndProducesCleanCutout() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val src = createCenteredSubjectBitmap(
            width = 112,
            height = 112,
            bgColor = Color.rgb(25, 75, 200),
            subjectColor = Color.rgb(230, 50, 35)
        )

        val result = SmartCutProcessor.segmentWithProvider(
            context = app,
            sourceBitmap = src,
            removeSubjectFromBackground = true,
            maskProvider = {
                throw MlKitException(
                    "Waiting for the subject segmentation optional module to be downloaded.",
                    MlKitException.UNAVAILABLE
                )
            }
        )

        assertTrue("Expected fallback Success but got $result", result is SmartCutResult.Success)
        val success = result as SmartCutResult.Success
        assertTrue("Expected usedFallbackEngine=true", success.usedFallbackEngine)
        assertTrue(success.backgroundInpainted)

        // Fallback engine must keep the red center subject opaque and corners transparent
        val fgCenter = success.foregroundBitmap.getPixel(56, 56)
        val fgCorner = success.foregroundBitmap.getPixel(4, 4)
        assertTrue("Fallback cutout center should be opaque: alpha=${Color.alpha(fgCenter)}", Color.alpha(fgCenter) >= 220)
        assertTrue("Fallback cutout center should preserve red subject", Color.red(fgCenter) > 180 && Color.blue(fgCenter) < 80)
        assertEquals("Fallback cutout corner must be transparent", 0, Color.alpha(fgCorner))

        // Clean background plate must inpaint the red center back to blue
        val bgCenter = success.backgroundBitmap.getPixel(56, 56)
        assertTrue(
            "Fallback clean BG center should be blue (no red ghost): r=${Color.red(bgCenter)} b=${Color.blue(bgCenter)}",
            Color.blue(bgCenter) > 150 && Color.red(bgCenter) < 95
        )

        success.foregroundBitmap.recycle()
        success.alphaMaskBitmap.recycle()
        success.backgroundBitmap.recycle()
        src.recycle()
    }

    @Test
    fun fallbackSegmentation_uniformFlatImageReturnsNoSubjectDetected() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val flat = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(90, 95, 100))
        }

        val result = SmartCutProcessor.segmentWithProvider(
            context = app,
            sourceBitmap = flat,
            removeSubjectFromBackground = false,
            maskProvider = {
                throw MlKitException(
                    "Waiting for the subject segmentation optional module to be downloaded.",
                    MlKitException.UNAVAILABLE
                )
            }
        )

        assertTrue("Expected NoSubjectDetected for uniform flat frame, got $result", result is SmartCutResult.NoSubjectDetected)
        flat.recycle()
    }

    @Test
    fun preFlightUnavailable_bypassesMlKitClientCompletelyAndRunsFallbackDirectly() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        // On JVM/Robolectric without Play Store services, pre-flight readiness must return false cleanly
        assertFalse(SmartCutProcessor.isMlKitSubjectSegmentationReady(app))

        val src = createCenteredSubjectBitmap(
            width = 112,
            height = 112,
            bgColor = Color.rgb(30, 80, 200),
            subjectColor = Color.rgb(225, 45, 35)
        )
        var mlKitProviderCalled = false

        val result = SmartCutProcessor.segmentWithProvider(
            context = app,
            sourceBitmap = src,
            removeSubjectFromBackground = true,
            availabilityChecker = { false },
            maskProvider = {
                mlKitProviderCalled = true
                throw AssertionError("ML Kit client must NOT be instantiated or called when pre-flight check is false")
            }
        )

        assertFalse("ML Kit provider must be bypassed completely", mlKitProviderCalled)
        assertTrue("Expected Success via direct fallback, got $result", result is SmartCutResult.Success)
        val success = result as SmartCutResult.Success
        assertTrue(success.usedFallbackEngine)
        assertTrue(Color.alpha(success.foregroundBitmap.getPixel(56, 56)) >= 220)
        assertEquals(0, Color.alpha(success.foregroundBitmap.getPixel(4, 4)))

        success.foregroundBitmap.recycle()
        success.alphaMaskBitmap.recycle()
        success.backgroundBitmap.recycle()
        src.recycle()
    }

    @Test
    fun preFlightException_bypassesMlKitClientAndRunsFallbackWithoutError() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val src = createCenteredSubjectBitmap(96, 96)
        var mlKitProviderCalled = false

        val result = SmartCutProcessor.segmentWithProvider(
            context = app,
            sourceBitmap = src,
            removeSubjectFromBackground = false,
            availabilityChecker = { throw SecurityException("Simulated Play Services unavailable") },
            maskProvider = {
                mlKitProviderCalled = true
                throw AssertionError("ML Kit client must NOT be called when availability check throws")
            }
        )

        assertFalse(mlKitProviderCalled)
        assertTrue("Expected Success via direct fallback, got $result", result is SmartCutResult.Success)
        val success = result as SmartCutResult.Success
        assertTrue(success.usedFallbackEngine)

        success.foregroundBitmap.recycle()
        success.alphaMaskBitmap.recycle()
        success.backgroundBitmap.recycle()
        src.recycle()
    }
}
