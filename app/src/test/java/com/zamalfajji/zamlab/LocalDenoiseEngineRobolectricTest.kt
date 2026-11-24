package com.zamalfajji.zamlab

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.createBitmap
import androidx.test.core.app.ApplicationProvider
import com.zamalfajji.zamlab.ai.LocalDenoiseEngine
import com.zamalfajji.zamlab.editor.ColorGradingEngine
import com.zamalfajji.zamlab.editor.DenoisePreset
import com.zamalfajji.zamlab.editor.EditorViewModel
import com.zamalfajji.zamlab.editor.GradingParams
import com.zamalfajji.zamlab.editor.StudioTab
import com.zamalfajji.zamlab.gl.GlGradingShaders
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
import kotlin.math.abs

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalDenoiseEngineRobolectricTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        LocalDenoiseEngine.resetOnnxStateForTests()
    }

    @After
    fun tearDown() {
        LocalDenoiseEngine.resetOnnxStateForTests()
        Dispatchers.resetMain()
    }

    private fun awaitInitialLoad(vm: EditorViewModel) {
        val deadline = System.currentTimeMillis() + 10_000L
        while (System.currentTimeMillis() < deadline) {
            testDispatcher.scheduler.advanceUntilIdle()
            if (vm.uiState.value.fullWidth > 0 && vm.uiState.value.previewIdleBitmap != null) {
                return
            }
            Thread.sleep(20)
        }
        throw AssertionError("Timed out waiting for EditorViewModel initial sample load")
    }

    @Test
    fun `denoise tab is positioned immediately next to UPSCALE in StudioTab order`() {
        val tabs = StudioTab.entries
        val upscaleIdx = tabs.indexOf(StudioTab.UPSCALE)
        val denoiseIdx = tabs.indexOf(StudioTab.DENOISE)
        assertEquals(upscaleIdx + 1, denoiseIdx)
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
    fun `chroma noise reduction suppresses shadow blotches while preserving sharp color boundaries`() {
        val w = 80
        val h = 64
        val bmp = createBitmap(w, h, Bitmap.Config.ARGB_8888)

        // Top half (y < 32): dark shadow region (Y ~ 48) contaminated with 2x2 high-ISO R/G/B chroma blotches
        // Bottom half (y >= 32): crisp object boundary with saturated Red on left (x < 40) and Cyan on right (x >= 40)
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (y < 32) {
                    val block = ((x / 2) + (y / 2) * 3) % 3
                    val c = when (block) {
                        0 -> Color.rgb(74, 38, 42) // magenta/red blotch
                        1 -> Color.rgb(36, 62, 40) // green blotch
                        else -> Color.rgb(38, 42, 78) // blue blotch
                    }
                    bmp.setPixel(x, y, c)
                } else {
                    val c = if (x < 40) Color.rgb(215, 42, 42) else Color.rgb(38, 198, 208)
                    bmp.setPixel(x, y, c)
                }
            }
        }

        fun measureShadowChromaSpread(target: Bitmap): Double {
            var sum = 0.0
            var count = 0
            for (y in 6 until 26) {
                for (x in 6 until w - 6) {
                    val px = target.getPixel(x, y)
                    val r = Color.red(px)
                    val g = Color.green(px)
                    val b = Color.blue(px)
                    sum += (maxOf(r, g, b) - minOf(r, g, b)).toDouble()
                    count++
                }
            }
            return sum / count
        }

        val beforeSpread = measureShadowChromaSpread(bmp)
        val app = ApplicationProvider.getApplicationContext<Application>()
        val params = GradingParams(
            colorNoiseReduction = 75f,
            colorNoiseReductionSmoothness = 70f
        )
        val result = LocalDenoiseEngine.denoise(app, bmp, params)
        assertEquals(LocalDenoiseEngine.DenoiseBackend.YCBCR_EDGE_PRESERVING, result.backend)

        val afterSpread = measureShadowChromaSpread(result.bitmap)
        assertTrue(
            "Expected chroma blotch spread to drop sharply (before=$beforeSpread, after=$afterSpread)",
            afterSpread < beforeSpread * 0.45
        )

        // Verify sharp Red vs Cyan boundary on bottom half stays crisp (no bleeding across x=39|40)
        val leftEdgePx = result.bitmap.getPixel(38, 48)
        val rightEdgePx = result.bitmap.getPixel(41, 48)
        assertTrue(
            "Red side near edge should stay strongly red (was R=${Color.red(leftEdgePx)}, B=${Color.blue(leftEdgePx)})",
            Color.red(leftEdgePx) > 190 && Color.blue(leftEdgePx) < 65
        )
        assertTrue(
            "Cyan side near edge should stay strongly cyan (was R=${Color.red(rightEdgePx)}, B=${Color.blue(rightEdgePx)})",
            Color.blue(rightEdgePx) > 185 && Color.red(rightEdgePx) < 65
        )

        result.bitmap.recycle()
        bmp.recycle()
    }

    @Test
    fun `luminance denoise reduces grain while Detail preserves edges and Contrast restores midtone punch`() = runTest(testDispatcher) {
        val w = 72
        val h = 48
        val bmp = createBitmap(w, h, Bitmap.Config.ARGB_8888)

        // Left half (x < 36): mid-dark noisy flat surface (base 92 +/- 18 checkerboard grain)
        // Right half (x >= 36): crisp bright step surface (base 196 +/- 18 grain)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val base = if (x < 36) 92 else 196
                val grain = if (((x + y) and 1) == 0) 16 else -16
                val v = (base + grain).coerceIn(0, 255)
                bmp.setPixel(x, y, Color.rgb(v, v, v))
            }
        }

        fun measureFlatRegionRoughness(target: Bitmap): Double {
            var sum = 0.0
            var count = 0
            for (y in 6 until h - 6) {
                for (x in 6 until 28) {
                    val v = Color.red(target.getPixel(x, y))
                    val vR = Color.red(target.getPixel(x + 1, y))
                    sum += abs(v - vR).toDouble()
                    count++
                }
            }
            return sum / count
        }

        fun measureStepEdgeJump(target: Bitmap): Double {
            var sum = 0.0
            var count = 0
            for (y in 8 until h - 8) {
                val left = Color.red(target.getPixel(35, y))
                val right = Color.red(target.getPixel(36, y))
                sum += abs(right - left).toDouble()
                count++
            }
            return sum / count
        }

        val rawRoughness = measureFlatRegionRoughness(bmp)
        val lowDetailParams = GradingParams(
            noiseReduction = 70f,
            noiseReductionDetail = 10f,
            noiseReductionContrast = 0f
        )
        val highDetailParams = GradingParams(
            noiseReduction = 70f,
            noiseReductionDetail = 90f,
            noiseReductionContrast = 0f
        )
        val contrastRecoveryParams = GradingParams(
            noiseReduction = 65f,
            noiseReductionDetail = 45f,
            noiseReductionContrast = 80f
        )

        val lowDetailOut = ColorGradingEngine.processFullResolution(bmp, lowDetailParams, null)
        val highDetailOut = ColorGradingEngine.processFullResolution(bmp, highDetailParams, null)
        val contrastOut = ColorGradingEngine.processFullResolution(bmp, contrastRecoveryParams, null)

        val denoisedRoughness = measureFlatRegionRoughness(lowDetailOut)
        assertTrue(
            "Expected luminance denoise to smooth flat-region grain (raw=$rawRoughness, denoised=$denoisedRoughness)",
            denoisedRoughness < rawRoughness * 0.45
        )

        val lowDetailEdge = measureStepEdgeJump(lowDetailOut)
        val highDetailEdge = measureStepEdgeJump(highDetailOut)
        assertTrue(
            "Expected sharp step edge to survive denoising (edgeJump=$highDetailEdge)",
            highDetailEdge > 75.0
        )
        assertTrue(
            "Expected Detail=90 to preserve more edge/micro-contrast than Detail=10 (high=$highDetailEdge vs low=$lowDetailEdge)",
            highDetailEdge >= lowDetailEdge
        )

        val noContrastEdge = measureStepEdgeJump(lowDetailOut)
        val recoveredEdge = measureStepEdgeJump(contrastOut)
        assertTrue(
            "Expected Contrast=80 to recover local midtone step punch (recovered=$recoveredEdge vs base=$noContrastEdge)",
            recoveredEdge > noContrastEdge
        )

        lowDetailOut.recycle()
        highDetailOut.recycle()
        contrastOut.recycle()
        bmp.recycle()
    }

    @Test
    fun `tile planner, tensor layout inference, and AI Clean fallback without bundled ONNX work cleanly`() {
        val tiles = LocalDenoiseEngine.computeTilePlan(600, 420, tileSize = 256, overlap = 16)
        assertTrue("Expected multiple overlapping tiles", tiles.size >= 6)
        assertEquals(0, tiles.first().srcX0)
        assertEquals(0, tiles.first().srcY0)
        assertEquals(600, tiles.maxOf { it.srcX1 })
        assertEquals(420, tiles.maxOf { it.srcY1 })

        val firstRow = tiles.filter { it.rowIndex == 0 }
        assertTrue(firstRow.size >= 2)
        val overlapPx = firstRow[0].srcX1 - firstRow[1].srcX0
        assertTrue("Expected >= 16px overlap (was $overlapPx)", overlapPx >= 16)

        assertEquals(
            LocalDenoiseEngine.TensorLayout.NCHW,
            LocalDenoiseEngine.inferLayout(longArrayOf(1L, 3L, 256L, 256L))
        )
        assertEquals(
            LocalDenoiseEngine.TensorLayout.NHWC,
            LocalDenoiseEngine.inferLayout(longArrayOf(1L, 256L, 256L, 3L))
        )

        val app = ApplicationProvider.getApplicationContext<Application>()
        assertFalse(LocalDenoiseEngine.isOnnxModelAssetAvailable(app))

        val src = createBitmap(48, 36, Bitmap.Config.ARGB_8888)
        src.eraseColor(Color.rgb(90, 96, 104))
        val progressEvents = mutableListOf<Float>()
        val res = LocalDenoiseEngine.denoise(
            context = app,
            source = src,
            params = GradingParams().applyDenoisePreset(DenoisePreset.AI_CLEAN),
            onProgress = { progressEvents.add(it) }
        )
        assertEquals(LocalDenoiseEngine.DenoiseBackend.YCBCR_EDGE_PRESERVING, res.backend)
        assertEquals(48, res.bitmap.width)
        assertEquals(36, res.bitmap.height)
        assertEquals(1f, progressEvents.last(), 0.001f)
        res.bitmap.recycle()
        src.recycle()
    }

    @Test
    fun `ViewModel denoise presets update GradingParams and record single-step global Undo and Redo`() = runTest(testDispatcher) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = EditorViewModel(app)
        awaitInitialLoad(vm)

        assertFalse(vm.uiState.value.gradingParams.hasDenoiseEdits)
        assertFalse(vm.uiState.value.canUndo)

        // Apply "High ISO / Night" preset
        vm.applyDenoisePreset(DenoisePreset.HIGH_ISO_NIGHT)
        testDispatcher.scheduler.advanceUntilIdle()

        val highIso = vm.uiState.value.gradingParams
        assertEquals(DenoisePreset.HIGH_ISO_NIGHT, highIso.activeDenoisePreset())
        assertEquals(DenoisePreset.HIGH_ISO_NIGHT.luminance, highIso.noiseReduction, 0.01f)
        assertEquals(DenoisePreset.HIGH_ISO_NIGHT.detail, highIso.noiseReductionDetail, 0.01f)
        assertEquals(DenoisePreset.HIGH_ISO_NIGHT.contrast, highIso.noiseReductionContrast, 0.01f)
        assertEquals(DenoisePreset.HIGH_ISO_NIGHT.color, highIso.colorNoiseReduction, 0.01f)
        assertEquals(DenoisePreset.HIGH_ISO_NIGHT.smoothness, highIso.colorNoiseReductionSmoothness, 0.01f)
        assertTrue(highIso.hasDenoiseEdits)
        assertTrue(vm.uiState.value.canUndo)

        // Apply "AI Clean" preset (falls back cleanly to calibrated non-destructive YCbCr params when no ONNX asset)
        vm.applyDenoisePreset(DenoisePreset.AI_CLEAN)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(DenoisePreset.AI_CLEAN, vm.uiState.value.gradingParams.activeDenoisePreset())
        assertTrue(vm.uiState.value.gradingParams.denoiseAiClean)

        // Single Undo reverts back to High ISO / Night
        vm.undo()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(DenoisePreset.HIGH_ISO_NIGHT, vm.uiState.value.gradingParams.activeDenoisePreset())
        assertFalse(vm.uiState.value.gradingParams.denoiseAiClean)
        assertTrue(vm.uiState.value.canRedo)

        // Second Undo reverts back to pristine default (Off)
        vm.undo()
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.uiState.value.gradingParams.hasDenoiseEdits)
        assertEquals(0f, vm.uiState.value.gradingParams.noiseReduction, 0.01f)
        assertEquals(0f, vm.uiState.value.gradingParams.colorNoiseReduction, 0.01f)

        // Redo restores High ISO / Night in one step
        vm.redo()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(DenoisePreset.HIGH_ISO_NIGHT, vm.uiState.value.gradingParams.activeDenoisePreset())
    }

    @Test
    fun `GPU spatial shader declares uDenoiseParams for live preview parity`() {
        val shader = GlGradingShaders.SPATIAL_FINISH_FRAGMENT_SHADER
        assertNotNull(shader)
        assertTrue(
            "Expected uDenoiseParams uniform declaration in SPATIAL_FINISH_FRAGMENT_SHADER",
            shader.contains("uniform vec4 uDenoiseParams;")
        )
        assertTrue(
            "Expected uDenoiseParams detail/contrast/smoothness usage in SPATIAL_FINISH_FRAGMENT_SHADER",
            shader.contains("uDenoiseParams.x") &&
                shader.contains("uDenoiseParams.y") &&
                shader.contains("uDenoiseParams.z") &&
                shader.contains("uDenoiseParams.w")
        )
    }
}
