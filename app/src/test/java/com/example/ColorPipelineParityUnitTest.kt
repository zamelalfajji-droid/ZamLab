package com.example

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.example.editor.ColorGradingEngine
import com.example.editor.EditorViewModel
import com.example.editor.GradingParams
import com.example.editor.StudioTab
import com.example.gl.DirtyRenderGate
import com.example.gl.GlGradingShaders
import com.example.gl.GlRenderState
import com.example.lut.CubeLut
import com.example.lut.LutType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.math.pow

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ColorPipelineParityUnitTest {

    private fun solidBitmap(r: Int, g: Int, b: Int, w: Int = 24, h: Int = 24): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.rgb(r, g, b))
        return bmp
    }

    private fun srgbToLinear(c: Float): Float {
        val cc = c.coerceIn(0f, 1f)
        return if (cc <= 0.04045f) cc / 12.92f else ((cc + 0.055f) / 1.055f).pow(2.4f)
    }

    @Test
    fun exposureUsesExactLinearStopMathAndSoftHighlightShoulder() = runBlocking {
        // Mid-gray patch (128 -> linear ~0.21586)
        val midSrc = solidBitmap(128, 128, 128)
        val plus1 = ColorGradingEngine.processFullResolution(midSrc, GradingParams(exposure = 1.0f), null)
        val minus1 = ColorGradingEngine.processFullResolution(midSrc, GradingParams(exposure = -1.0f), null)

        val linSrc = srgbToLinear(128f / 255f)
        val linPlus1 = srgbToLinear(Color.red(plus1.getPixel(12, 12)) / 255f)
        val linMinus1 = srgbToLinear(Color.red(minus1.getPixel(12, 12)) / 255f)

        // +1 EV doubles linear light (~2x); -1 EV halves linear light (~0.5x)
        assertEquals(linSrc * 2f, linPlus1, 0.025f)
        assertEquals(linSrc * 0.5f, linMinus1, 0.015f)

        // Bright sky gradient: +1.5 EV should keep smooth highlight separation instead of hard clipping both to 255
        val bright1 = solidBitmap(210, 210, 210)
        val bright2 = solidBitmap(240, 240, 240)
        val out1 = ColorGradingEngine.processFullResolution(bright1, GradingParams(exposure = 1.5f), null)
        val out2 = ColorGradingEngine.processFullResolution(bright2, GradingParams(exposure = 1.5f), null)
        val v1 = Color.red(out1.getPixel(12, 12))
        val v2 = Color.red(out2.getPixel(12, 12))
        assertTrue("Soft shoulder should prevent hard clipping bright highlights together (v1=$v1, v2=$v2)", v2 > v1 && v1 < 254)
    }

    @Test
    fun whiteBalanceIsLuminanceBalancedAndShiftsWarmCoolGreenMagenta() = runBlocking {
        val mult = FloatArray(3)
        GlGradingShaders.computeLinearWhiteBalanceMultipliers(0.6f, 0f, mult)
        val luma = 0.2126f * mult[0] + 0.7152f * mult[1] + 0.0722f * mult[2]
        assertEquals("Linear WB multipliers must preserve neutral luminance", 1.0f, luma, 0.001f)
        assertTrue("Warm temp must boost red over blue", mult[0] > mult[2])

        val gray = solidBitmap(140, 140, 140)
        val warm = ColorGradingEngine.processFullResolution(gray, GradingParams(temperature = 60f), null)
        val cool = ColorGradingEngine.processFullResolution(gray, GradingParams(temperature = -60f), null)
        val magenta = ColorGradingEngine.processFullResolution(gray, GradingParams(tint = 60f), null)
        val green = ColorGradingEngine.processFullResolution(gray, GradingParams(tint = -60f), null)

        val warmPx = warm.getPixel(12, 12)
        val coolPx = cool.getPixel(12, 12)
        val magPx = magenta.getPixel(12, 12)
        val grnPx = green.getPixel(12, 12)

        assertTrue("Warm temp must yield R > B", Color.red(warmPx) > Color.blue(warmPx) + 25)
        assertTrue("Cool temp must yield B > R", Color.blue(coolPx) > Color.red(coolPx) + 25)
        assertTrue("Positive tint must shift magenta (R,B > G)", Color.red(magPx) > Color.green(magPx) + 15 && Color.blue(magPx) > Color.green(magPx) + 15)
        assertTrue("Negative tint must shift green (G > R,B)", Color.green(grnPx) > Color.red(grnPx) + 15 && Color.green(grnPx) > Color.blue(grnPx) + 15)
    }

    @Test
    fun smoothContrastPreservesNearBlacksAndHighlightsWithoutHardClipping() = runBlocking {
        val dark1 = solidBitmap(16, 16, 16)
        val dark2 = solidBitmap(38, 38, 38)
        val hi1 = solidBitmap(218, 218, 218)
        val hi2 = solidBitmap(240, 240, 240)
        val highContrast = GradingParams(contrast = 85f)

        val d1 = Color.red(ColorGradingEngine.processFullResolution(dark1, highContrast, null).getPixel(8, 8))
        val d2 = Color.red(ColorGradingEngine.processFullResolution(dark2, highContrast, null).getPixel(8, 8))
        val h1 = Color.red(ColorGradingEngine.processFullResolution(hi1, highContrast, null).getPixel(8, 8))
        val h2 = Color.red(ColorGradingEngine.processFullResolution(hi2, highContrast, null).getPixel(8, 8))

        assertTrue("Smooth contrast should not crush dark shadows to identical 0 (d1=$d1, d2=$d2)", d2 > d1 && d2 > 6)
        assertTrue("Smooth contrast should not hard-clip bright highlights together (h1=$h1, h2=$h2)", h2 > h1 && h1 < 252)
    }

    @Test
    fun vibranceProtectsSkinTonesAndSaturatedColorsMoreThanMutedColors() = runBlocking {
        // Muted dusty blue (low sat, cool hue) vs warm peach skin tone (similar low/mid sat)
        val mutedBlue = solidBitmap(125, 138, 165)
        val peachSkin = solidBitmap(204, 162, 136)
        val params = GradingParams(vibrance = 80f)

        val outBlue = ColorGradingEngine.processFullResolution(mutedBlue, params, null).getPixel(12, 12)
        val outSkin = ColorGradingEngine.processFullResolution(peachSkin, params, null).getPixel(12, 12)

        val blueSatBefore = (165 - 125) / 165f
        val blueSatAfter = (Color.blue(outBlue) - Color.red(outBlue)) / Color.blue(outBlue).coerceAtLeast(1).toFloat()
        val skinSatBefore = (204 - 136) / 204f
        val skinSatAfter = (Color.red(outSkin) - Color.blue(outSkin)) / Color.red(outSkin).coerceAtLeast(1).toFloat()

        val blueBoost = blueSatAfter - blueSatBefore
        val skinBoost = skinSatAfter - skinSatBefore
        assertTrue("Vibrance must visibly boost muted blue (boost=$blueBoost)", blueBoost > 0.08f)
        assertTrue(
            "Vibrance must protect peach skin tones relative to muted blue (blueBoost=$blueBoost, skinBoost=$skinBoost)",
            blueBoost > skinBoost * 1.4f
        )
    }

    @Test
    fun spatialDetailPassesOperateOnGradedMultiPixelNeighborhoods() = runBlocking {
        // 1. Dehaze on a milky low-contrast hazy patch should lower minimum veil and expand contrast
        val hazy = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        for (y in 0 until 32) {
            for (x in 0 until 32) {
                val c = if (x < 16) Color.rgb(145, 155, 168) else Color.rgb(185, 194, 205)
                hazy.setPixel(x, y, c)
            }
        }
        val dehazed = ColorGradingEngine.processFullResolution(hazy, GradingParams(dehaze = 75f), null)
        val leftBefore = Color.red(hazy.getPixel(6, 16))
        val rightBefore = Color.red(hazy.getPixel(24, 16))
        val leftAfter = Color.red(dehazed.getPixel(6, 16))
        val rightAfter = Color.red(dehazed.getPixel(24, 16))
        assertTrue("Dehaze must deepen milky haze veil ($leftAfter < $leftBefore)", leftAfter < leftBefore - 15)
        assertTrue(
            "Dehaze must expand local contrast (${rightAfter - leftAfter} > ${rightBefore - leftBefore})",
            (rightAfter - leftAfter) > (rightBefore - leftBefore) + 10
        )

        // 2. Texture on alternating 2px micro-stripes should increase fine detail contrast
        val finePattern = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        for (y in 0 until 32) {
            for (x in 0 until 32) {
                val v = if ((x / 2) % 2 == 0) 110 else 146
                finePattern.setPixel(x, y, Color.rgb(v, v, v))
            }
        }
        val textured = ColorGradingEngine.processFullResolution(finePattern, GradingParams(texture = 85f), null)
        val texDiff = abs(Color.red(textured.getPixel(16, 16)) - Color.red(textured.getPixel(18, 16)))
        assertTrue("Texture must boost fine micro-contrast (diff=$texDiff > 36)", texDiff > 44)

        // 3. Clarity boosts midtone local contrast while protecting pure black/white regions
        val claritySrc = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        for (y in 0 until 48) {
            for (x in 0 until 48) {
                val v = when {
                    y < 16 -> if (x < 24) 0 else 255 // Pure black & white extremes
                    else -> if (x < 24) 105 else 155 // Midtone edge
                }
                claritySrc.setPixel(x, y, Color.rgb(v, v, v))
            }
        }
        val clarified = ColorGradingEngine.processFullResolution(claritySrc, GradingParams(clarity = 85f), null)
        val pureBlackAfter = Color.red(clarified.getPixel(12, 8))
        val pureWhiteAfter = Color.red(clarified.getPixel(36, 8))
        val midDarkAfter = Color.red(clarified.getPixel(22, 32))
        val midLightAfter = Color.red(clarified.getPixel(25, 32))
        assertEquals("Clarity must preserve pure blacks", 0, pureBlackAfter)
        assertEquals("Clarity must preserve pure whites", 255, pureWhiteAfter)
        assertTrue(
            "Clarity must boost midtone local contrast across edge (${midLightAfter - midDarkAfter} > 50)",
            (midLightAfter - midDarkAfter) > 58
        )

        // 4. Sharpen boosts real high-contrast edge while protecting flat smooth region via noise threshold
        val sharpSrc = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        for (y in 0 until 32) {
            for (x in 0 until 32) {
                val v = if (y < 16) {
                    // Subtle 1-LSB flat noise
                    if ((x + y) % 2 == 0) 128 else 129
                } else {
                    // Real step edge
                    if (x < 16) 90 else 165
                }
                sharpSrc.setPixel(x, y, Color.rgb(v, v, v))
            }
        }
        val sharpened = ColorGradingEngine.processFullResolution(
            sharpSrc,
            GradingParams(sharpening = 120f, sharpenRadius = 1.2f, sharpenDetail = 50f, sharpenMasking = 30f),
            null
        )
        val flatDiffAfter = abs(Color.red(sharpened.getPixel(15, 8)) - Color.red(sharpened.getPixel(16, 8)))
        val edgeDiffAfter = abs(Color.red(sharpened.getPixel(15, 24)) - Color.red(sharpened.getPixel(16, 24)))
        assertTrue("Sharpen noise threshold must protect flat regions (flatDiff=$flatDiffAfter)", flatDiffAfter <= 2)
        assertTrue("Sharpen must enhance real edge contrast (edgeDiff=$edgeDiffAfter > 75)", edgeDiffAfter > 90)
    }

    @Test
    fun liveHistogramUpdatesImmediatelyAcrossAdjustments() = runBlocking {
        val src = solidBitmap(128, 128, 128, w = 48, h = 48)
        val baseHist = ColorGradingEngine.computePreviewHistogram(src, GradingParams(), null)
        val brightHist = ColorGradingEngine.computePreviewHistogram(src, GradingParams(exposure = 1.0f), null)
        val warmHist = ColorGradingEngine.computePreviewHistogram(src, GradingParams(temperature = 70f), null)

        val basePeak = baseHist.luma.indices.maxByOrNull { baseHist.luma[it] } ?: 0
        val brightPeak = brightHist.luma.indices.maxByOrNull { brightHist.luma[it] } ?: 0
        val warmRedPeak = warmHist.red.indices.maxByOrNull { warmHist.red[it] } ?: 0
        val warmBluePeak = warmHist.blue.indices.maxByOrNull { warmHist.blue[it] } ?: 0

        assertTrue("Positive exposure must shift histogram luma peak right ($brightPeak > $basePeak)", brightPeak > basePeak + 5)
        assertTrue("Warm temperature must shift red peak right of blue peak ($warmRedPeak > $warmBluePeak)", warmRedPeak > warmBluePeak + 4)
    }

    @Test
    fun dirtyRenderGateRecoversWhenSubmittedBeforeSurfaceReady() {
        val gate = DirtyRenderGate()
        val state = GlRenderState(
            imageBitmap = null,
            params = GradingParams(exposure = 0.5f),
            lut = null,
            isOriginal = false,
            splitRatio = -1f,
            applyCrop = true
        )
        // Simulate updateState arriving before TextureView surface is created (no GL handler yet)
        assertTrue(gate.submit(state))
        gate.releaseScheduledLatch()

        // Once surface becomes available, markSurfaceDirty must schedule an immediate draw
        assertTrue("Surface-ready draw must not be blocked by pre-surface submit", gate.markSurfaceDirty())
        val consumed = gate.consumeForRender()
        assertEquals(0.5f, consumed!!.params.exposure, 0.0001f)
        assertFalse(gate.shouldRescheduleAfterDraw())
    }

    @Test
    fun clippingWarningOverlayFlagsPureWhiteRedAndPureBlackBlueOnly() = runBlocking {
        val bmp = Bitmap.createBitmap(4, 1, Bitmap.Config.ARGB_8888).apply {
            setPixel(0, 0, Color.rgb(255, 255, 255)) // Pure white -> highlight clip
            setPixel(1, 0, Color.rgb(0, 0, 0))       // Pure black -> shadow clip
            setPixel(2, 0, Color.rgb(128, 128, 128)) // Midtone gray -> untouched
            setPixel(3, 0, Color.rgb(255, 10, 10))   // Saturated red (min=10) -> untouched
        }

        val flagged = ColorGradingEngine.applyClippingWarningOverlay(
            source = bmp,
            showHighlightClipping = true,
            showShadowClipping = true
        )

        val whiteOut = flagged.getPixel(0, 0)
        val blackOut = flagged.getPixel(1, 0)
        val grayOut = flagged.getPixel(2, 0)
        val satRedOut = flagged.getPixel(3, 0)

        assertTrue("Blown white pixel should be highlighted red", Color.red(whiteOut) >= 245 && Color.green(whiteOut) < 60 && Color.blue(whiteOut) < 60)
        assertTrue("Crushed black pixel should be highlighted blue", Color.blue(blackOut) >= 245 && Color.red(blackOut) < 60)
        assertEquals("Midtone gray must stay untouched", Color.rgb(128, 128, 128), grayOut)
        assertEquals("Saturated non-white red must stay untouched", Color.rgb(255, 10, 10), satRedOut)

        // Verify GlRenderState.matches detects highlight/shadow clipping toggle changes
        val s1 = GlRenderState(null, GradingParams(), null, false, -1f, true, false, false)
        val s2 = GlRenderState(null, GradingParams(), null, false, -1f, true, true, false)
        val s2Same = GlRenderState(null, GradingParams(), null, false, -1f, true, true, false)
        val s3 = GlRenderState(null, GradingParams(), null, false, -1f, true, false, true)
        assertFalse(s1.matches(s2))
        assertFalse(s1.matches(s3))
        assertTrue(s2.matches(s2Same))
    }

    @Test
    fun fullResExportAppliesGradingAndLutIntensityWithoutClippingOverlay() = runBlocking {
        // Simple 2x2x2 identity-warm CubeLut that shifts mid-gray strongly warm (R=+0.3, B=-0.3)
        val size = 2
        val data = FloatArray(size * size * size * 3)
        var idx = 0
        for (b in 0 until size) {
            for (g in 0 until size) {
                for (r in 0 until size) {
                    val rf = r.toFloat()
                    val gf = g.toFloat()
                    val bf = b.toFloat()
                    data[idx++] = (rf + 0.35f).coerceIn(0f, 1f)
                    data[idx++] = gf
                    data[idx++] = (bf * 0.55f).coerceIn(0f, 1f)
                }
            }
        }
        val warmLut = CubeLut(
            id = "warm_test",
            title = "WarmTest",
            type = LutType.LUT_3D,
            size = size,
            table = data
        )

        val src = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until 16) {
                for (x in 0 until 16) {
                    val col = when {
                        x < 4 -> Color.rgb(255, 255, 255) // Pure white
                        x < 8 -> Color.rgb(0, 0, 0)       // Pure black
                        else -> Color.rgb(128, 128, 128)  // Mid gray
                    }
                    setPixel(x, y, col)
                }
            }
        }

        val halfStrength = ColorGradingEngine.processFullResolution(
            source = src,
            params = GradingParams(exposure = 0.5f, lutIntensity = 0.5f),
            lut = warmLut
        )
        val fullStrength = ColorGradingEngine.processFullResolution(
            source = src,
            params = GradingParams(exposure = 0.5f, lutIntensity = 1.0f),
            lut = warmLut
        )

        val midHalf = halfStrength.getPixel(12, 8)
        val midFull = fullStrength.getPixel(12, 8)
        val diffHalf = Color.red(midHalf) - Color.blue(midHalf)
        val diffFull = Color.red(midFull) - Color.blue(midFull)
        assertTrue("Full LUT strength must warm more than half strength ($diffFull > $diffHalf)", diffFull > diffHalf + 15)

        // Verify pure white is never baked with red clipping overlay (R,G,B remain bright, not red-only)
        val exportedWhite = fullStrength.getPixel(1, 8)
        assertTrue(
            "Exported bright pixel must not contain preview clipping red mask (G=${Color.green(exportedWhite)})",
            Color.green(exportedWhite) > 200
        )
    }

    @Test
    fun lutTabAutoLoadsThumbnailsAndClippingAndCompareTogglesUpdateState() {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            runTest(dispatcher) {
                val app = ApplicationProvider.getApplicationContext<Application>()
                val vm = EditorViewModel(app)

                // Wait for initial sample photo + folder indexing
                val initDeadline = System.currentTimeMillis() + 5_000L
                while (System.currentTimeMillis() < initDeadline) {
                    dispatcher.scheduler.advanceUntilIdle()
                    if (vm.uiState.value.fullWidth > 0 && vm.uiState.value.lutFolders.isNotEmpty()) break
                    Thread.sleep(20)
                }
                assertTrue(vm.uiState.value.lutFolders.isNotEmpty())

                // Opening LUTS tab must auto-expand default folder and render live photo preview thumbnails
                vm.selectTab(StudioTab.LUTS)
                val lutDeadline = System.currentTimeMillis() + 6_000L
                while (System.currentTimeMillis() < lutDeadline) {
                    dispatcher.scheduler.advanceUntilIdle()
                    val expanded = vm.uiState.value.lutFolders.firstOrNull { it.isExpanded }
                    if (expanded != null && !expanded.isLoading && expanded.isLoadedForCurrentPhoto) break
                    Thread.sleep(20)
                }

                val activeFolder = vm.uiState.value.lutFolders.firstOrNull { it.isExpanded }
                assertNotNull("Expected an expanded LUT folder when opening LUTS tab", activeFolder)
                assertTrue("Expected loaded live thumbnails in active LUT folder", activeFolder!!.items.isNotEmpty())
                assertTrue(
                    "Expected live photo preview thumbnails rendered for active LUT folder",
                    activeFolder.items.all { it.previewThumbnail != null }
                )

                // Clipping warning toggles update EditorUiState independently
                assertFalse(vm.uiState.value.showHighlightClipping)
                assertFalse(vm.uiState.value.showShadowClipping)
                vm.toggleHighlightClipping()
                assertTrue(vm.uiState.value.showHighlightClipping)
                assertFalse(vm.uiState.value.showShadowClipping)
                vm.toggleShadowClipping()
                assertTrue(vm.uiState.value.showHighlightClipping)
                assertTrue(vm.uiState.value.showShadowClipping)

                // Press-and-hold before/after original compare toggles cleanly
                vm.setComparingOriginal(true)
                assertTrue(vm.uiState.value.isComparingOriginal)
                vm.setComparingOriginal(false)
                assertFalse(vm.uiState.value.isComparingOriginal)
            }
        } finally {
            Dispatchers.resetMain()
        }
    }
}

