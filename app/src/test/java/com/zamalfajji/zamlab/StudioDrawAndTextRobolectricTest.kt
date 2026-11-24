package com.zamalfajji.zamlab

import android.app.Application
import android.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.zamalfajji.zamlab.editor.CuratedOledDrawSwatches
import com.zamalfajji.zamlab.editor.DrawBrushType
import com.zamalfajji.zamlab.editor.DrawStroke
import com.zamalfajji.zamlab.editor.DrawStrokePoint
import com.zamalfajji.zamlab.editor.DrawingEngine
import com.zamalfajji.zamlab.editor.EditorViewModel
import com.zamalfajji.zamlab.editor.LayerBlendMode
import com.zamalfajji.zamlab.editor.LayerCompositor
import com.zamalfajji.zamlab.editor.StudioSubMode
import com.zamalfajji.zamlab.editor.StudioTab
import com.zamalfajji.zamlab.editor.StudioTextBackdropStyle
import com.zamalfajji.zamlab.editor.StudioTextFontStyle
import com.zamalfajji.zamlab.editor.TextStampRenderer
import com.zamalfajji.zamlab.ui.EditorScreen
import com.zamalfajji.zamlab.ui.theme.ZamLabTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StudioDrawAndTextRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun awaitCondition(timeoutMs: Long = 10_000L, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            testDispatcher.scheduler.advanceUntilIdle()
            if (predicate()) return
            Thread.sleep(20)
        }
        throw AssertionError("Timed out waiting for condition")
    }

    @Test
    fun `DrawingEngine renders Pen, Marker, and Neon Glow strokes cleanly onto transparent bitmaps`() {
        val points = listOf(
            DrawStrokePoint(24f, 64f),
            DrawStrokePoint(64f, 52f),
            DrawStrokePoint(104f, 64f)
        )
        DrawBrushType.entries.forEach { brush ->
            val stroke = DrawStroke(
                points = points,
                brushType = brush,
                colorArgb = CuratedOledDrawSwatches[2],
                radiusPx = 8f,
                opacity = 0.9f
            )
            val bmp = DrawingEngine.renderStrokesBitmap(128, 128, listOf(stroke))
            val cornerAlpha = Color.alpha(bmp.getPixel(2, 2))
            val centerAlpha = Color.alpha(bmp.getPixel(64, 58))
            assertEquals("Corner outside stroke must remain transparent for $brush", 0, cornerAlpha)
            assertTrue("Center of stroke must have visible ink for $brush (alpha=$centerAlpha)", centerAlpha > 40)
            bmp.recycle()
        }
    }

    @Test
    fun `TextStampRenderer renders all 5 font styles and Plain Pill Outline modes onto transparent bitmaps`() {
        StudioTextFontStyle.entries.forEach { font ->
            StudioTextBackdropStyle.entries.forEach { backdrop ->
                val bmp = TextStampRenderer.renderTextStampBitmap(
                    rawText = "Z-Effect",
                    fontStyle = font,
                    colorArgb = CuratedOledDrawSwatches[0],
                    backdropStyle = backdrop
                )
                assertTrue("Text stamp width should be positive", bmp.width >= 120)
                assertTrue("Text stamp height should be positive", bmp.height >= 88)
                // Top-left corner outside pill/text must remain transparent
                assertEquals(0, Color.alpha(bmp.getPixel(0, 0)))
                var visiblePixels = 0
                val pixels = IntArray(bmp.width * bmp.height)
                bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                for (px in pixels) {
                    if (((px ushr 24) and 0xFF) > 100) visiblePixels++
                }
                assertTrue("Rendered text stamp for $font/$backdrop must contain visible pixels", visiblePixels > 50)
                bmp.recycle()
            }
        }
    }

    @Test
    fun `Draw sub-mode creates Drawing layer, paints strokes with Undo Redo, and unlocks cleanly on Done`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = EditorViewModel(app)
        awaitCondition { vm.uiState.value.fullWidth > 0 && vm.uiState.value.layers.isNotEmpty() }
        val baseCount = vm.uiState.value.layers.size

        // Entering DRAW creates a transparent full-fit "Drawing" layer and locks pan/zoom
        vm.selectStudioSubMode(StudioSubMode.DRAW)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value.isDrawingMode)
        assertEquals(StudioSubMode.DRAW, vm.uiState.value.studioSubMode)
        assertEquals(baseCount + 1, vm.uiState.value.layers.size)
        val drawLayerId = vm.uiState.value.activeLayerId!!
        assertEquals("Drawing", vm.uiState.value.activeLayer!!.name)

        // Paint stroke 1 with Neon Glow + custom hue
        vm.selectDrawBrushType(DrawBrushType.NEON_GLOW)
        vm.updateDrawCustomHue(190f)
        vm.updateDrawBrushSizeDp(22f)
        vm.updateDrawOpacity(0.95f)
        vm.beginDrawStroke(0.25f, 0.50f, 400f, 400f, 11f)
        vm.appendDrawStroke(0.50f, 0.50f, 400f, 400f, 11f)
        vm.appendDrawStroke(0.75f, 0.50f, 400f, 400f, 11f)
        vm.finishDrawStroke()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.canUndoDrawStroke)
        assertFalse(vm.uiState.value.canRedoDrawStroke)
        val paintedBmp = vm.uiState.value.activeLayer!!.bitmap
        val paintedMidAlpha = Color.alpha(paintedBmp.getPixel(paintedBmp.width / 2, paintedBmp.height / 2))
        assertTrue("Painted Drawing layer center should have visible neon ink", paintedMidAlpha > 60)

        // Stroke Undo clears stroke cleanly; Stroke Redo brings it right back
        vm.undoDrawStroke()
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.uiState.value.canUndoDrawStroke)
        assertTrue(vm.uiState.value.canRedoDrawStroke)
        val undoneBmp = vm.uiState.value.activeLayer!!.bitmap
        assertEquals(0, Color.alpha(undoneBmp.getPixel(undoneBmp.width / 2, undoneBmp.height / 2)))

        vm.redoDrawStroke()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value.canUndoDrawStroke)
        assertFalse(vm.uiState.value.canRedoDrawStroke)
        val redoneBmp = vm.uiState.value.activeLayer!!.bitmap
        assertTrue(Color.alpha(redoneBmp.getPixel(redoneBmp.width / 2, redoneBmp.height / 2)) > 60)

        // Done unlocks canvas gestures and keeps Drawing layer in the stack
        vm.finishDrawingMode()
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.uiState.value.isDrawingMode)
        assertEquals(StudioSubMode.STICKERS, vm.uiState.value.studioSubMode)
        assertTrue(vm.uiState.value.layers.any { it.id == drawLayerId })

        // Re-entering Draw reuses the existing Drawing layer rather than duplicating it
        vm.selectStudioSubMode(StudioSubMode.DRAW)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(baseCount + 1, vm.uiState.value.layers.size)
        assertEquals(drawLayerId, vm.uiState.value.activeLayerId)
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp")
    fun `STUDIO 3-pill selector switches Stickers Draw Text and adds transformable blendable Text layer`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = EditorViewModel(app)
        awaitCondition { vm.uiState.value.fullWidth > 0 && vm.uiState.value.layers.isNotEmpty() }

        composeTestRule.setContent {
            ZamLabTheme {
                EditorScreen(viewModel = vm)
            }
        }
        composeTestRule.waitForIdle()

        // Open STUDIO tab
        composeTestRule.onNodeWithTag("tab_studio").performClick()
        testDispatcher.scheduler.advanceUntilIdle()
        composeTestRule.waitForIdle()
        assertEquals(StudioTab.STUDIO, vm.uiState.value.activeTab)

        // 1. Verify 3-pill segmented selector: Stickers | Draw | Text
        composeTestRule.onNodeWithTag("studio_submode_stickers").assertIsDisplayed()
        composeTestRule.onNodeWithTag("studio_submode_draw").assertIsDisplayed()
        composeTestRule.onNodeWithTag("studio_submode_text").assertIsDisplayed()

        // 2. Tap "Draw" sub-mode and verify controls
        composeTestRule.onNodeWithTag("studio_submode_draw").performClick()
        testDispatcher.scheduler.advanceUntilIdle()
        composeTestRule.waitForIdle()
        assertTrue(vm.uiState.value.isDrawingMode)
        composeTestRule.onNodeWithTag("studio_draw_panel").assertIsDisplayed()
        composeTestRule.onNodeWithTag("draw_brush_pen").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("draw_brush_marker").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("draw_brush_neon_glow").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("draw_swatch_0").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("draw_swatch_9").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("draw_hue_slider").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("draw_size_slider").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("draw_opacity_slider").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("draw_undo_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("draw_redo_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("draw_done_button").assertIsDisplayed().performClick()
        testDispatcher.scheduler.advanceUntilIdle()
        composeTestRule.waitForIdle()
        assertFalse(vm.uiState.value.isDrawingMode)

        // 3. Tap "Text" sub-mode, type styled text, pick Bold Display + Outline, and add layer
        composeTestRule.onNodeWithTag("studio_submode_text").performClick()
        testDispatcher.scheduler.advanceUntilIdle()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("studio_text_panel").assertIsDisplayed()
        composeTestRule.onNodeWithTag("studio_text_input").performTextInput("Golden Hour")
        composeTestRule.onNodeWithTag("text_font_bold_display").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("text_font_serif").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("text_font_monospace").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("text_font_brush").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("text_backdrop_outline").performClick()
        composeTestRule.onNodeWithTag("text_swatch_2").performScrollTo().performClick()

        val layersBeforeText = vm.uiState.value.layers.size
        composeTestRule.onNodeWithTag("add_text_layer_button").performClick()
        awaitCondition { vm.uiState.value.layers.size == layersBeforeText + 1 }

        val textLayer = vm.uiState.value.activeLayer
        assertNotNull(textLayer)
        assertEquals("Text: Golden Hour", textLayer!!.name)
        assertFalse(textLayer.isBackground)

        // Text layer can be moved, scaled, rotated, blend-mode changed, and exported cleanly
        vm.transformActiveLayer(deltaOffsetX = 0.12f, deltaOffsetY = -0.10f, scaleFactor = 1.25f, deltaRotationDegrees = -15f)
        vm.updateActiveLayerBlendMode(LayerBlendMode.SCREEN)
        testDispatcher.scheduler.advanceUntilIdle()
        val updatedTextLayer = vm.uiState.value.activeLayer!!
        assertEquals(0.12f, updatedTextLayer.offsetX, 0.001f)
        assertEquals(-15f, updatedTextLayer.rotation, 0.001f)
        assertEquals(LayerBlendMode.SCREEN, updatedTextLayer.blendMode)

        val base = vm.uiState.value.layers.first().bitmap
        val exported = runBlocking {
            LayerCompositor.compositeForExport(
                context = app,
                layers = vm.uiState.value.layers,
                outWidth = base.width,
                outHeight = base.height,
                preRenderedBackground = base,
                preferGpuOffscreen = false
            )
        }
        assertEquals(base.width, exported.width)
        assertEquals(base.height, exported.height)
        exported.recycle()
    }
}
