package com.zamalfajji.zamlab

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.zamalfajji.zamlab.editor.EditorViewModel
import com.zamalfajji.zamlab.editor.ImageLayer
import com.zamalfajji.zamlab.editor.LayerBlendMode
import com.zamalfajji.zamlab.editor.LayerCompositor
import com.zamalfajji.zamlab.editor.LayerIds
import com.zamalfajji.zamlab.editor.LayerMaskBrushMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LayerFinishingToolsRobolectricTest {

    private fun solidBitmap(w: Int, h: Int, argb: Int): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            eraseColor(argb)
        }

    private fun waitForEditorReady(vm: EditorViewModel) {
        val deadline = System.currentTimeMillis() + 5_000L
        while (vm.uiState.value.layers.isEmpty() && System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(20L)
        }
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Test
    fun compositeForExport_allFiveBlendModesProduceDistinctExpectedLuminance() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val w = 64
        val h = 64
        val baseGray = solidBitmap(w, h, Color.rgb(160, 160, 160))
        val topMidBlue = solidBitmap(w, h, Color.rgb(120, 140, 180))

        suspend fun renderCenterColor(mode: LayerBlendMode): Int {
            val bg = ImageLayer(
                id = LayerIds.BACKGROUND,
                name = "Background",
                bitmap = baseGray,
                isBackground = true
            )
            val top = ImageLayer(
                id = "layer_blend",
                name = "Overlay",
                bitmap = topMidBlue,
                scale = 1f,
                isBackground = false,
                blendMode = mode
            )
            val out = LayerCompositor.compositeForExport(
                context = app,
                layers = listOf(bg, top),
                outWidth = w,
                outHeight = h,
                preRenderedBackground = baseGray,
                preferGpuOffscreen = false
            )
            val pixel = out.getPixel(w / 2, h / 2)
            out.recycle()
            return pixel
        }

        val normalPx = renderCenterColor(LayerBlendMode.NORMAL)
        val multiplyPx = renderCenterColor(LayerBlendMode.MULTIPLY)
        val screenPx = renderCenterColor(LayerBlendMode.SCREEN)
        val overlayPx = renderCenterColor(LayerBlendMode.OVERLAY)
        val softLightPx = renderCenterColor(LayerBlendMode.SOFT_LIGHT)

        // Normal replaces base with top (120, 140, 180)
        assertTrue("Normal red should be ~120, was ${Color.red(normalPx)}", abs(Color.red(normalPx) - 120) <= 2)
        // Multiply darkens: 160 * 120 / 255 ≈ 75
        assertTrue(
            "Multiply should darken red below Normal (${Color.red(multiplyPx)} vs ${Color.red(normalPx)})",
            Color.red(multiplyPx) < Color.red(normalPx) - 30
        )
        // Screen brightens: 255 - (95 * 135 / 255) ≈ 205
        assertTrue(
            "Screen should brighten red above base 160 (${Color.red(screenPx)})",
            Color.red(screenPx) > 190
        )
        // Overlay on bright base (>128) lifts channels above Normal
        assertTrue(
            "Overlay should lift blue above Normal (${Color.blue(overlayPx)} vs ${Color.blue(normalPx)})",
            Color.blue(overlayPx) > Color.blue(normalPx) + 15
        )
        // Soft Light lifts base 160 gently (~177), staying above base 160 and gentler than Overlay
        assertTrue(
            "Soft Light blue should lift above base 160 and stay below Overlay (${Color.blue(softLightPx)} vs ${Color.blue(overlayPx)})",
            Color.blue(softLightPx) in 165 until Color.blue(overlayPx)
        )

        baseGray.recycle()
        topMidBlue.recycle()
    }

    @Test
    fun bokehBlurAndEdgeFeather_smoothSharpTransitions() {
        val w = 96
        val h = 96
        // Left half black, right half white (sharp vertical step at x = 48)
        val sharpStep = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) {
            for (x in 0 until w) {
                sharpStep.setPixel(x, y, if (x < w / 2) Color.BLACK else Color.WHITE)
            }
        }

        val blurred = LayerCompositor.applyFastBokehBlur(sharpStep, radiusPx = 12f)
        assertEquals(w, blurred.width)
        assertEquals(h, blurred.height)
        val leftNearEdge = Color.red(blurred.getPixel(w / 2 - 4, h / 2))
        val rightNearEdge = Color.red(blurred.getPixel(w / 2 + 4, h / 2))
        assertTrue("Bokeh blur should lift dark side near edge (> 20), got $leftNearEdge", leftNearEdge > 20)
        assertTrue("Bokeh blur should soften bright side near edge (< 235), got $rightNearEdge", rightNearEdge < 235)
        blurred.recycle()
        sharpStep.recycle()

        // Hard alpha cutout: opaque center square (24..72), transparent surround
        val hardCutout = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        hardCutout.eraseColor(Color.TRANSPARENT)
        for (y in 24 until 72) {
            for (x in 24 until 72) {
                hardCutout.setPixel(x, y, Color.argb(255, 240, 180, 60))
            }
        }

        val feathered = LayerCompositor.applyAlphaEdgeFeather(
            source = hardCutout,
            featherRadiusPx = 10f,
            hasAlphaMask = true
        )
        val centerAlpha = Color.alpha(feathered.getPixel(w / 2, h / 2))
        val boundaryAlpha = Color.alpha(feathered.getPixel(24, h / 2))
        val outerCornerAlpha = Color.alpha(feathered.getPixel(2, 2))

        assertTrue("Center of feathered cutout should remain opaque (> 230), got $centerAlpha", centerAlpha > 230)
        assertTrue(
            "Boundary pixel should have smooth intermediate alpha (15..240), got $boundaryAlpha",
            boundaryAlpha in 15..240
        )
        assertEquals("Far transparent corner should remain transparent", 0, outerCornerAlpha)

        feathered.recycle()
        hardCutout.recycle()
    }

    @Test
    fun maskBrush_eraseRestoreAndUndoRedoWorkCleanly() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = EditorViewModel(app)
        waitForEditorReady(vm)

        val overlayBmp = solidBitmap(120, 120, Color.rgb(220, 90, 60))
        val layerId = vm.addPhotoLayerBitmap(overlayBmp, name = "Subject")
        assertEquals(layerId, vm.uiState.value.activeLayerId)
        assertEquals(null, vm.uiState.value.activeLayer?.maskBitmap)

        // Toggle Mask Brush mode -> initializes full opaque mask
        vm.toggleLayerMaskBrushMode()
        assertTrue(vm.uiState.value.isLayerMaskBrushMode)
        val initialMask = vm.uiState.value.activeLayer?.maskBitmap
        assertNotNull("Entering Mask Brush should initialize an opaque alpha mask", initialMask)
        assertEquals(255, Color.alpha(initialMask!!.getPixel(60, 60)))

        // 1. Erase stroke across center of the active layer
        vm.setLayerMaskBrushMode(LayerMaskBrushMode.ERASE)
        vm.updateLayerMaskBrushSoftness(0.1f)
        vm.beginLayerMaskBrushStroke(
            normX = 0.5f,
            normY = 0.5f,
            canvasW = 400f,
            canvasH = 400f,
            brushRadiusCanvasPx = 40f
        )
        vm.appendLayerMaskBrushStroke(
            normX = 0.52f,
            normY = 0.5f,
            canvasW = 400f,
            canvasH = 400f,
            brushRadiusCanvasPx = 40f
        )
        vm.finishLayerMaskBrushStroke()

        val afterEraseMask = vm.uiState.value.activeLayer?.maskBitmap
        assertNotNull(afterEraseMask)
        assertEquals(
            "Erase brush stroke should punch transparent alpha at layer center",
            0,
            Color.alpha(afterEraseMask!!.getPixel(60, 60))
        )
        assertTrue("Undo should be enabled after mask brush stroke", vm.uiState.value.canUndo)

        // 2. Restore stroke directly over the erased hole
        vm.setLayerMaskBrushMode(LayerMaskBrushMode.RESTORE)
        vm.beginLayerMaskBrushStroke(
            normX = 0.5f,
            normY = 0.5f,
            canvasW = 400f,
            canvasH = 400f,
            brushRadiusCanvasPx = 40f
        )
        vm.finishLayerMaskBrushStroke()

        val afterRestoreMask = vm.uiState.value.activeLayer?.maskBitmap
        assertNotNull(afterRestoreMask)
        assertEquals(
            "Restore brush stroke should restore full opacity at layer center",
            255,
            Color.alpha(afterRestoreMask!!.getPixel(60, 60))
        )

        // 3. Undo Restore -> center is erased again
        vm.undo()
        val afterUndoRestoreMask = vm.uiState.value.activeLayer?.maskBitmap
        assertNotNull(afterUndoRestoreMask)
        assertEquals(
            "Undoing Restore should bring back the erased transparent hole",
            0,
            Color.alpha(afterUndoRestoreMask!!.getPixel(60, 60))
        )

        // 4. Undo Erase -> center is opaque again
        vm.undo()
        val afterUndoEraseMask = vm.uiState.value.activeLayer?.maskBitmap
        assertNotNull(afterUndoEraseMask)
        assertEquals(
            "Undoing Erase should restore the pre-stroke opaque mask",
            255,
            Color.alpha(afterUndoEraseMask!!.getPixel(60, 60))
        )

        // 5. Redo Erase -> center is erased again
        vm.redo()
        val afterRedoEraseMask = vm.uiState.value.activeLayer?.maskBitmap
        assertNotNull(afterRedoEraseMask)
        assertEquals(
            "Redoing Erase should re-apply the erased hole",
            0,
            Color.alpha(afterRedoEraseMask!!.getPixel(60, 60))
        )

        vm.exitLayerMaskBrushMode()
        assertFalse(vm.uiState.value.isLayerMaskBrushMode)
    }

    @Test
    fun layerFx_blendModeBokehAndFeatherUpdateUndoAndExport() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = EditorViewModel(app)
        waitForEditorReady(vm)

        val overlayBmp = solidBitmap(128, 128, Color.rgb(180, 120, 60))
        vm.addPhotoLayerBitmap(overlayBmp, name = "Warm Tint")

        vm.toggleLayerFxPanel()
        assertTrue(vm.uiState.value.isLayerFxExpanded)

        vm.updateActiveLayerBlendMode(LayerBlendMode.SCREEN)
        vm.updateActiveLayerBokehBlur(12.5f)
        vm.updateActiveLayerEdgeFeather(8.0f)

        val active = vm.uiState.value.activeLayer
        assertNotNull(active)
        assertEquals(LayerBlendMode.SCREEN, active!!.blendMode)
        assertEquals(12.5f, active.bokehBlurRadius, 0.05f)
        assertEquals(8.0f, active.edgeFeather, 0.05f)
        assertTrue(active.hasLayerFx)

        val exported = vm.renderCompositeExportBitmap(preferGpuOffscreen = false)
        assertNotNull("Multi-layer export with Layer FX should succeed", exported)
        assertTrue(exported!!.width > 0 && exported.height > 0)
        exported.recycle()

        // Undo Edge Feather -> 0f while keeping Bokeh Blur and Screen blend
        vm.undo()
        assertEquals(0f, vm.uiState.value.activeLayer!!.edgeFeather, 0.05f)
        assertEquals(12.5f, vm.uiState.value.activeLayer!!.bokehBlurRadius, 0.05f)
        assertEquals(LayerBlendMode.SCREEN, vm.uiState.value.activeLayer!!.blendMode)

        // Undo Bokeh Blur -> 0f while keeping Screen blend
        vm.undo()
        assertEquals(0f, vm.uiState.value.activeLayer!!.bokehBlurRadius, 0.05f)
        assertEquals(LayerBlendMode.SCREEN, vm.uiState.value.activeLayer!!.blendMode)

        // Undo Blend Mode -> NORMAL
        vm.undo()
        assertEquals(LayerBlendMode.NORMAL, vm.uiState.value.activeLayer!!.blendMode)
        assertFalse(vm.uiState.value.activeLayer!!.hasLayerFx)
    }
}
