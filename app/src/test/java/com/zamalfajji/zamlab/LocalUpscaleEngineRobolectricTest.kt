package com.zamalfajji.zamlab

import ai.onnxruntime.OnnxJavaType
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.createBitmap
import androidx.test.core.app.ApplicationProvider
import com.zamalfajji.zamlab.ai.LocalUpscaleEngine
import com.zamalfajji.zamlab.ai.LocalUpscaleEngine.TensorLayout
import com.zamalfajji.zamlab.ai.LocalUpscaleEngine.UpscaleBackend
import com.zamalfajji.zamlab.ai.LocalUpscaleEngine.UpscaleScale
import com.zamalfajji.zamlab.editor.EditorViewModel
import com.zamalfajji.zamlab.editor.StudioTab
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocalUpscaleEngineRobolectricTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        LocalUpscaleEngine.resetOnnxStateForTests()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        LocalUpscaleEngine.resetOnnxStateForTests()
    }

    private suspend fun awaitInitialLoad(vm: EditorViewModel) {
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            testDispatcher.scheduler.advanceUntilIdle()
            val state = vm.uiState.value
            if (state.fullWidth > 0 && state.previewIdleBitmap != null) {
                return
            }
            Thread.sleep(20)
        }
        throw AssertionError("Timed out waiting for initial EditorViewModel load")
    }

    private suspend fun awaitUpscaleFinish(vm: EditorViewModel) {
        val deadline = System.currentTimeMillis() + 15_000L
        while (System.currentTimeMillis() < deadline) {
            testDispatcher.scheduler.advanceUntilIdle()
            if (!vm.uiState.value.isUpscaling) {
                return
            }
            Thread.sleep(20)
        }
        throw AssertionError("Timed out waiting for EditorViewModel upscale to complete")
    }

    @Test
    fun `upscale tab is positioned immediately next to STUDIO in StudioTab order`() {
        val tabs = StudioTab.entries
        val studioIdx = tabs.indexOf(StudioTab.STUDIO)
        val upscaleIdx = tabs.indexOf(StudioTab.UPSCALE)
        assertEquals(studioIdx + 1, upscaleIdx)
        assertEquals(
            listOf(
                StudioTab.ADJUST,
                StudioTab.STUDIO,
                StudioTab.UPSCALE,
                StudioTab.DENOISE,
                StudioTab.LUTS,
                StudioTab.CROP,
                StudioTab.HEAL,
                StudioTab.MASK
            ),
            tabs
        )
    }

    @Test
    fun `fallback 2x and 4x upscaling produces exact target dimensions and preserves colors`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val src = createBitmap(48, 32, Bitmap.Config.ARGB_8888)
        // Left half warm terracotta, right half cool teal -> tests both flat areas and crisp center edge
        for (y in 0 until src.height) {
            for (x in 0 until src.width) {
                src.setPixel(x, y, if (x < 24) Color.rgb(196, 108, 72) else Color.rgb(54, 148, 172))
            }
        }

        val progress2x = mutableListOf<Float>()
        val res2x = LocalUpscaleEngine.upscale(app, src, UpscaleScale.X2) { progress2x.add(it) }
        assertEquals(UpscaleBackend.LANCZOS3_FALLBACK, res2x.backend)
        assertEquals(96, res2x.bitmap.width)
        assertEquals(64, res2x.bitmap.height)
        assertTrue(progress2x.isNotEmpty())
        assertEquals(1f, progress2x.last(), 1e-4f)

        // Check interior swatch fidelity and zero black border artifact
        val leftSample = res2x.bitmap.getPixel(16, 32)
        assertTrue(abs(Color.red(leftSample) - 196) <= 3)
        assertTrue(abs(Color.green(leftSample) - 108) <= 3)
        assertTrue(abs(Color.blue(leftSample) - 72) <= 3)

        val cornerSample = res2x.bitmap.getPixel(0, 0)
        assertTrue(abs(Color.red(cornerSample) - 196) <= 4)

        val rightSample = res2x.bitmap.getPixel(80, 32)
        assertTrue(abs(Color.red(rightSample) - 54) <= 3)
        assertTrue(abs(Color.green(rightSample) - 148) <= 3)
        assertTrue(abs(Color.blue(rightSample) - 172) <= 3)

        val progress4x = mutableListOf<Float>()
        val res4x = LocalUpscaleEngine.upscale(app, src, UpscaleScale.X4) { progress4x.add(it) }
        assertEquals(UpscaleBackend.LANCZOS3_FALLBACK, res4x.backend)
        assertEquals(192, res4x.bitmap.width)
        assertEquals(128, res4x.bitmap.height)
        assertEquals(1f, progress4x.last(), 1e-4f)
        // Progress must be monotonic non-decreasing across both 2x passes
        for (i in 1 until progress4x.size) {
            assertTrue(
                "Expected monotonic progress: ${progress4x[i - 1]} -> ${progress4x[i]}",
                progress4x[i] >= progress4x[i - 1] - 1e-5f
            )
        }

        res2x.bitmap.recycle()
        res4x.bitmap.recycle()
        src.recycle()
    }

    @Test
    fun `tiled 256x256 upscaling with 16px overlap feathering produces seamless tile boundaries`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val srcW = 320
        val srcH = 300
        val tiles = LocalUpscaleEngine.computeTilePlan(srcW, srcH, tileSize = 256, overlap = 16)
        assertEquals(4, tiles.size)
        // Adjacent tiles in X overlap by at least 16px
        val t00 = tiles[0]
        val t01 = tiles[1]
        assertTrue(t00.srcX1 - t01.srcX0 >= LocalUpscaleEngine.TILE_OVERLAP)
        // Adjacent tiles in Y overlap by at least 16px
        val t10 = tiles[2]
        assertTrue(t00.srcY1 - t10.srcY0 >= LocalUpscaleEngine.TILE_OVERLAP)

        // Smooth 2D RGB gradient across the entire 320x300 image
        val src = createBitmap(srcW, srcH, Bitmap.Config.ARGB_8888)
        for (y in 0 until srcH) {
            for (x in 0 until srcW) {
                val r = (x * 255) / (srcW - 1)
                val g = (y * 255) / (srcH - 1)
                val b = ((x + y) * 255) / (srcW + srcH - 2)
                src.setPixel(x, y, Color.rgb(r, g, b))
            }
        }

        val res = LocalUpscaleEngine.upscale(app, src, UpscaleScale.X2)
        assertEquals(srcW * 2, res.bitmap.width)
        assertEquals(srcH * 2, res.bitmap.height)

        // Inspect horizontal continuity across the vertical tile seam region (x = 460..540)
        val midY = 300
        for (x in 460 until 540) {
            val p1 = res.bitmap.getPixel(x, midY)
            val p2 = res.bitmap.getPixel(x + 1, midY)
            assertTrue("Seam spike in R at x=$x", abs(Color.red(p1) - Color.red(p2)) <= 3)
            assertTrue("Seam spike in G at x=$x", abs(Color.green(p1) - Color.green(p2)) <= 3)
            assertTrue("Seam spike in B at x=$x", abs(Color.blue(p1) - Color.blue(p2)) <= 3)
        }

        // Inspect vertical continuity across the horizontal tile seam region (y = 460..540)
        val midX = 320
        for (y in 460 until 540) {
            val p1 = res.bitmap.getPixel(midX, y)
            val p2 = res.bitmap.getPixel(midX, y + 1)
            assertTrue("Seam spike in R at y=$y", abs(Color.red(p1) - Color.red(p2)) <= 3)
            assertTrue("Seam spike in G at y=$y", abs(Color.green(p1) - Color.green(p2)) <= 3)
            assertTrue("Seam spike in B at y=$y", abs(Color.blue(p1) - Color.blue(p2)) <= 3)
        }

        res.bitmap.recycle()
        src.recycle()
    }

    @Test
    fun `inferLayout detects NCHW and NHWC tensor shapes accurately`() {
        assertEquals(TensorLayout.NCHW, LocalUpscaleEngine.inferLayout(longArrayOf(1L, 3L, -1L, -1L)))
        assertEquals(TensorLayout.NCHW, LocalUpscaleEngine.inferLayout(longArrayOf(1L, 3L, 256L, 256L)))
        assertEquals(TensorLayout.NHWC, LocalUpscaleEngine.inferLayout(longArrayOf(1L, -1L, -1L, 3L)))
        assertEquals(TensorLayout.NHWC, LocalUpscaleEngine.inferLayout(longArrayOf(1L, 256L, 256L, 3L)))
        // Verify feather weights remain 1.0 at outer perimeter and taper smoothly at interior overlaps
        val weights = LocalUpscaleEngine.buildFeatherWeights(
            length = 128,
            featherStart = false,
            featherEnd = true,
            featherPx = 32
        )
        assertEquals(1f, weights[0], 1e-5f)
        assertEquals(1f, weights[95], 1e-5f)
        assertTrue(weights[127] < 0.05f)
        assertTrue(OnnxJavaType.FLOAT != OnnxJavaType.UINT8)
    }

    @Test
    fun `EditorViewModel applyUpscale updates dimensions and supports single-step global Undo and Redo`() = runTest(testDispatcher) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = EditorViewModel(app)
        try {
            awaitInitialLoad(vm)

            val initialW = vm.uiState.value.fullWidth
            val initialH = vm.uiState.value.fullHeight
            assertTrue(initialW > 0 && initialH > 0)
            assertFalse(vm.uiState.value.hasUpscaleEdits)
            assertFalse(vm.uiState.value.canUndo)

            vm.selectTab(StudioTab.UPSCALE)
            assertEquals(StudioTab.UPSCALE, vm.uiState.value.activeTab)

            vm.selectUpscaleScale(UpscaleScale.X2)
            assertEquals(UpscaleScale.X2, vm.uiState.value.upscaleScale)

            vm.applyUpscale()
            awaitUpscaleFinish(vm)

            val upscaledState = vm.uiState.value
            assertEquals(initialW * 2, upscaledState.fullWidth)
            assertEquals(initialH * 2, upscaledState.fullHeight)
            assertTrue(upscaledState.hasUpscaleEdits)
            assertEquals(2, upscaledState.appliedUpscaleFactor)
            assertTrue(upscaledState.canUndo)
            assertEquals("Upscale 2x", upscaledState.undoLabel)

            // Single-step global Undo restores exact original dimensions and bitmap
            vm.undo()
            testDispatcher.scheduler.advanceUntilIdle()
            val undoneState = vm.uiState.value
            assertEquals(initialW, undoneState.fullWidth)
            assertEquals(initialH, undoneState.fullHeight)
            assertFalse(undoneState.hasUpscaleEdits)
            assertEquals(1, undoneState.appliedUpscaleFactor)
            assertFalse(undoneState.canUndo)
            assertTrue(undoneState.canRedo)

            // Single-step global Redo restores exact upscaled dimensions
            vm.redo()
            testDispatcher.scheduler.advanceUntilIdle()
            val redoneState = vm.uiState.value
            assertEquals(initialW * 2, redoneState.fullWidth)
            assertEquals(initialH * 2, redoneState.fullHeight)
            assertTrue(redoneState.hasUpscaleEdits)
            assertEquals(2, redoneState.appliedUpscaleFactor)
            assertTrue(redoneState.canUndo)
            assertFalse(redoneState.canRedo)
        } finally {
            vm.viewModelScope.cancel()
            testDispatcher.scheduler.advanceUntilIdle()
        }
    }
}
