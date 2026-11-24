package com.zamalfajji.zamlab

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.zamalfajji.zamlab.editor.ColorGradingEngine
import com.zamalfajji.zamlab.editor.EditorViewModel
import com.zamalfajji.zamlab.editor.EyedropperMode
import com.zamalfajji.zamlab.editor.GradingParams
import com.zamalfajji.zamlab.editor.LocalMask
import com.zamalfajji.zamlab.editor.MaskBooleanOp
import com.zamalfajji.zamlab.editor.MaskBrushStroke
import com.zamalfajji.zamlab.editor.MaskEngine
import com.zamalfajji.zamlab.editor.MaskSubMask
import com.zamalfajji.zamlab.editor.MaskType
import com.zamalfajji.zamlab.gl.GlGradingShaders
import com.zamalfajji.zamlab.gl.shaders.GuidedFilterShaders
import com.zamalfajji.zamlab.gl.shaders.MaskLocalAdjustShader
import com.zamalfajji.zamlab.gl.shaders.MaskRubylithOverlayShader
import com.zamalfajji.zamlab.gl.shaders.MaskShaderCommon
import com.zamalfajji.zamlab.gl.shaders.MaskSubMaskShaders
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
@Config(sdk = [36])
class MaskEngineRobolectricTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // -------------------------------------------------------------------------
    // 1. Modular GLSL ES 3.00 / WebGL2 Shader Structure & Precision Headers
    // -------------------------------------------------------------------------

    @Test
    fun `modular GLSL ES 300 shaders declare standard version and precision headers`() {
        val es3Shaders = listOf(
            MaskShaderCommon.FULLSCREEN_VERTEX_SHADER_ES3,
            MaskSubMaskShaders.SUBMASK_COMPOSITE_FRAGMENT_SHADER_ES3,
            MaskSubMaskShaders.BRUSH_STROKE_STAMP_FRAGMENT_SHADER_ES3,
            MaskSubMaskShaders.MASK_INVERT_FRAGMENT_SHADER_ES3,
            GuidedFilterShaders.GUIDED_MOMENTS_H_FRAGMENT_SHADER_ES3,
            GuidedFilterShaders.GUIDED_COEFFS_V_FRAGMENT_SHADER_ES3,
            GuidedFilterShaders.GUIDED_AB_H_FRAGMENT_SHADER_ES3,
            GuidedFilterShaders.GUIDED_APPLY_V_FRAGMENT_SHADER_ES3,
            MaskLocalAdjustShader.MASK_LOCAL_ADJUST_FRAGMENT_SHADER_ES3,
            MaskRubylithOverlayShader.RUBYLITH_OVERLAY_FRAGMENT_SHADER_ES3
        )
        for (src in es3Shaders) {
            assertTrue("Missing #version 300 es header", src.trimStart().startsWith("#version 300 es"))
            assertTrue("Missing precision highp float;", src.contains("precision highp float;"))
        }

        // Verify Rec. 709 luminance weights, CIELAB conversion, Rubylith #FF2A2A, and ES2 down-translator
        assertTrue(MaskShaderCommon.COLOR_SCIENCE_GLSL.contains("0.2126"))
        assertTrue(MaskShaderCommon.COLOR_SCIENCE_GLSL.contains("0.7152"))
        assertTrue(MaskShaderCommon.COLOR_SCIENCE_GLSL.contains("0.0722"))
        assertTrue(MaskShaderCommon.COLOR_SCIENCE_GLSL.contains("srgbToLab"))
        assertEquals("#FF2A2A", MaskRubylithOverlayShader.RUBYLITH_HEX)
        assertTrue(MaskRubylithOverlayShader.RUBYLITH_OVERLAY_FRAGMENT_SHADER_ES3.contains("#FF2A2A"))

        val es2Frag = MaskShaderCommon.adaptForGlContext(
            sourceEs3 = MaskRubylithOverlayShader.RUBYLITH_OVERLAY_FRAGMENT_SHADER_ES3,
            isVertex = false,
            isEs3 = false
        )
        assertFalse("ES2 down-translated shader must not retain #version 300 es", es2Frag.contains("#version 300 es"))
        assertTrue("ES2 down-translated shader must use texture2D", es2Frag.contains("texture2D("))
        assertTrue("ES2 down-translated shader must write gl_FragColor", es2Frag.contains("gl_FragColor"))
    }

    @Test
    fun `glGradingShaders declare every referenced uniform including uMaskOverlayTex and uMaskOverlayParams`() {
        val uniformDeclRegex = Regex("""uniform\s+\w+\s+(u[A-Z][A-Za-z0-9_]*)\s*(?:\[[^\]]+\])?\s*;""")
        val localDeclRegex = Regex("""\b(?:float|int|bool|vec[234]|ivec[234]|mat[234])\s+(u[A-Z][A-Za-z0-9_]*)\b""")
        val uniformUseRegex = Regex("""\b(u[A-Z][A-Za-z0-9_]*)\b""")

        for ((name, src) in listOf(
            "TONE_COLOR_FRAGMENT_SHADER" to GlGradingShaders.TONE_COLOR_FRAGMENT_SHADER,
            "SPATIAL_FINISH_FRAGMENT_SHADER" to GlGradingShaders.SPATIAL_FINISH_FRAGMENT_SHADER
        )) {
            assertTrue(
                "$name must start cleanly with #ifdef at column 0",
                src.startsWith("#ifdef GL_FRAGMENT_PRECISION_HIGH")
            )
            // Strip single-line comments before scanning identifiers
            val stripped = src.lines().joinToString("\n") { line -> line.substringBefore("//") }
            val declared = uniformDeclRegex.findAll(stripped).map { it.groupValues[1] }.toSet()
            val locals = localDeclRegex.findAll(stripped).map { it.groupValues[1] }.toSet()
            val referenced = uniformUseRegex.findAll(stripped).map { it.groupValues[1] }.toSet()
            val undeclared = referenced - declared - locals
            assertTrue(
                "$name contains undeclared uniform identifiers: $undeclared",
                undeclared.isEmpty()
            )
        }

        // Explicitly confirm Rubylith mask overlay uniforms exist in Pass 2 shader
        assertTrue(GlGradingShaders.SPATIAL_FINISH_FRAGMENT_SHADER.contains("uniform sampler2D uMaskOverlayTex;"))
        assertTrue(GlGradingShaders.SPATIAL_FINISH_FRAGMENT_SHADER.contains("uniform vec4 uMaskOverlayParams;"))
    }

    // -------------------------------------------------------------------------
    // 2. GPU Edge-Aware Feathering (Guided Image Filter)
    // -------------------------------------------------------------------------

    @Test
    fun `guided image filter snaps soft mask cleanly to real high-contrast image edge without bleeding`() {
        val w = 96
        val h = 64
        val n = w * h
        // Guide image: sharp dark left half (0.08) and bright right half (0.94) split at x = 48
        val guideR = FloatArray(n)
        val guideG = FloatArray(n)
        val guideB = FloatArray(n)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                val v = if (x < 48) 0.08f else 0.94f
                guideR[idx] = v
                guideG[idx] = v
                guideB[idx] = v
            }
        }

        // Soft linear gradient crossing the vertical edge x=48 from u=0.30 (1.0) to u=0.70 (0.0)
        val softSub = MaskSubMask(
            id = "lin1",
            name = "Linear",
            type = MaskType.LINEAR,
            startX = 0.30f,
            startY = 0.50f,
            endX = 0.70f,
            endY = 0.50f,
            feather = 1.0f
        )
        val rawMask = LocalMask(
            id = "m_raw",
            name = "Raw",
            type = MaskType.LINEAR,
            edgeSnap = 0f,
            subMasks = listOf(softSub)
        )
        val snappedMask = rawMask.copy(id = "m_snap", edgeSnap = 90f)

        val rawAlpha = MaskEngine.computeMaskAlphaBuffer(rawMask, guideR, guideG, guideB, w, h)
        val snappedAlpha = MaskEngine.computeMaskAlphaBuffer(snappedMask, guideR, guideG, guideB, w, h)

        val row = h / 2
        // Measure jump right across the true image edge (x=47 vs x=48)
        val rawEdgeJump = rawAlpha[row * w + 47] - rawAlpha[row * w + 48]
        val snappedEdgeJump = snappedAlpha[row * w + 47] - snappedAlpha[row * w + 48]

        assertTrue(
            "Guided Filter edge-snap must sharpen transition tightly across the real luminance edge " +
                "(rawJump=$rawEdgeJump, snappedJump=$snappedEdgeJump)",
            snappedEdgeJump > rawEdgeJump + 0.30f
        )
        // Dark side just left of boundary should remain strongly masked; bright side just right should drop sharply
        assertTrue("Left of edge should stay high (${snappedAlpha[row * w + 46]})", snappedAlpha[row * w + 46] > 0.70f)
        assertTrue("Right of edge should snap low (${snappedAlpha[row * w + 49]})", snappedAlpha[row * w + 49] < 0.30f)
    }

    // -------------------------------------------------------------------------
    // 3. Luminance Range (Rec. 709) & Color Range (CIELAB + Eyedropper)
    // -------------------------------------------------------------------------

    @Test
    fun `luminance range mask uses Rec 709 weights and isolates bright band with smooth falloff`() {
        // Pure green has higher Rec. 709 luma (0.7152) than pure red (0.2126) or pure blue (0.0722)
        assertEquals(0.2126f, MaskEngine.luma709(1f, 0f, 0f), 1e-4f)
        assertEquals(0.7152f, MaskEngine.luma709(0f, 1f, 0f), 1e-4f)
        assertEquals(0.0722f, MaskEngine.luma709(0f, 0f, 1f), 1e-4f)

        val lumaSub = MaskSubMask(
            id = "luma1",
            name = "Highlights",
            type = MaskType.LUMINANCE_RANGE,
            lumMin = 0.60f,
            lumMax = 1.00f,
            lumFalloff = 0.10f
        )
        val greenWeight = MaskEngine.evaluateSubMaskAtPixel(lumaSub, 0.5f, 0.5f, 1f, 0f, 1f, 0f)
        val redWeight = MaskEngine.evaluateSubMaskAtPixel(lumaSub, 0.5f, 0.5f, 1f, 1f, 0f, 0f)
        val blueWeight = MaskEngine.evaluateSubMaskAtPixel(lumaSub, 0.5f, 0.5f, 1f, 0f, 0f, 1f)
        val midTransition = MaskEngine.evaluateSubMaskAtPixel(lumaSub, 0.5f, 0.5f, 1f, 0.55f, 0.55f, 0.55f)

        assertEquals("Pure green (luma 0.7152) must be inside [0.60..1.00]", 1f, greenWeight, 1e-3f)
        assertEquals("Pure red (luma 0.2126) must be excluded", 0f, redWeight, 1e-3f)
        assertEquals("Pure blue (luma 0.0722) must be excluded", 0f, blueWeight, 1e-3f)
        assertTrue("Luminance 0.55 within falloff ramp must produce partial alpha ($midTransition)", midTransition in 0.15f..0.85f)
    }

    @Test
    fun `color range mask matches sampled target color in CIELAB perceptual space`() {
        val targetR = 0.15f
        val targetG = 0.45f
        val targetB = 0.88f
        val colorSub = MaskSubMask(
            id = "col1",
            name = "Sky Blue",
            type = MaskType.COLOR_RANGE,
            targetR = targetR,
            targetG = targetG,
            targetB = targetB,
            colorTolerance = 22f,
            colorSmoothness = 0.35f,
            colorSampled = true
        )

        val exactMatch = MaskEngine.evaluateSubMaskAtPixel(colorSub, 0.5f, 0.5f, 1f, targetR, targetG, targetB)
        val nearBlue = MaskEngine.evaluateSubMaskAtPixel(colorSub, 0.5f, 0.5f, 1f, 0.18f, 0.48f, 0.84f)
        val warmOrange = MaskEngine.evaluateSubMaskAtPixel(colorSub, 0.5f, 0.5f, 1f, 0.92f, 0.42f, 0.12f)
        val neutralGray = MaskEngine.evaluateSubMaskAtPixel(colorSub, 0.5f, 0.5f, 1f, 0.50f, 0.50f, 0.50f)

        assertEquals("Exact sampled pixel must yield 1.0 alpha", 1f, exactMatch, 1e-3f)
        assertTrue("Nearby sky blue must have high alpha ($nearBlue)", nearBlue > 0.85f)
        assertEquals("Opposite warm orange must be 0.0", 0f, warmOrange, 1e-3f)
        assertEquals("Neutral gray must be excluded at tight tolerance", 0f, neutralGray, 1e-3f)
    }

    // -------------------------------------------------------------------------
    // 4. Resolution-Independent Vector Brush & Rotatable Gradients
    // -------------------------------------------------------------------------

    @Test
    fun `vector brush stored in normalized UV space produces identical coverage at low and high resolutions`() {
        val stroke = MaskBrushStroke(
            id = "stroke_1",
            points = listOf(0.25f to 0.50f, 0.75f to 0.50f),
            radius = 0.10f,
            hardness = 0.70f,
            flow = 1.0f
        )
        val brushMask = LocalMask(
            id = "b1",
            name = "Brush",
            type = MaskType.BRUSH,
            subMasks = listOf(
                MaskSubMask(id = "sb1", name = "Brush", type = MaskType.BRUSH, strokes = listOf(stroke))
            )
        )

        fun meanAlphaAt(size: Int): Float {
            val n = size * size
            val zero = FloatArray(n) { 0.5f }
            val alpha = MaskEngine.computeMaskAlphaBuffer(brushMask, zero, zero, zero, size, size)
            return alpha.sum() / n.toFloat()
        }

        val lowResCoverage = meanAlphaAt(64)
        val highResCoverage = meanAlphaAt(256)
        assertTrue("Brush stroke should cover meaningful area ($lowResCoverage)", lowResCoverage > 0.08f)
        assertEquals(
            "Normalized UV vector brush must match coverage across 64px preview and 256px export",
            lowResCoverage,
            highResCoverage,
            0.015f
        )
    }

    @Test
    fun `rotatable radial gradient rotates elliptical axis accurately by 90 degrees`() {
        val wideEllipse = MaskSubMask(
            id = "r1",
            name = "Radial",
            type = MaskType.RADIAL,
            centerX = 0.50f,
            centerY = 0.50f,
            radiusX = 0.32f,
            radiusY = 0.08f,
            rotationDeg = 0f,
            feather = 0.10f
        )
        val rotatedEllipse = wideEllipse.copy(rotationDeg = 90f)

        // Point to the right of center (0.72, 0.50): inside unrotated wide ellipse, outside 90°-rotated tall ellipse
        val unrotRight = MaskEngine.evaluateRadialSubMask(wideEllipse, 0.72f, 0.50f)
        val rotRight = MaskEngine.evaluateRadialSubMask(rotatedEllipse, 0.72f, 0.50f)

        // Point below center (0.50, 0.72): outside unrotated wide ellipse, inside 90°-rotated tall ellipse
        val unrotBottom = MaskEngine.evaluateRadialSubMask(wideEllipse, 0.50f, 0.72f)
        val rotBottom = MaskEngine.evaluateRadialSubMask(rotatedEllipse, 0.50f, 0.72f)

        assertTrue("Right point must be inside 0° ellipse ($unrotRight)", unrotRight > 0.90f)
        assertTrue("Right point must be outside 90° ellipse ($rotRight)", rotRight < 0.05f)
        assertTrue("Bottom point must be outside 0° ellipse ($unrotBottom)", unrotBottom < 0.05f)
        assertTrue("Bottom point must be inside 90° ellipse ($rotBottom)", rotBottom > 0.90f)
    }

    // -------------------------------------------------------------------------
    // 5. Boolean Sub-Mask Compositing (Add, Subtract, Intersect) + Invert
    // -------------------------------------------------------------------------

    @Test
    fun `boolean sub-mask compositing executes Add, Subtract, Intersect, and Invert accurately`() {
        assertEquals(1.0f, MaskEngine.combineMaskOp(0.7f, 0.6f, MaskBooleanOp.ADD), 1e-4f)
        assertEquals(0.2f, MaskEngine.combineMaskOp(0.7f, 0.5f, MaskBooleanOp.SUBTRACT), 1e-4f)
        assertEquals(0.0f, MaskEngine.combineMaskOp(0.3f, 0.8f, MaskBooleanOp.SUBTRACT), 1e-4f)
        assertEquals(0.35f, MaskEngine.combineMaskOp(0.7f, 0.5f, MaskBooleanOp.INTERSECT), 1e-4f)

        // Composite layer: Left radial + Right radial (ADD) - Center radial (SUBTRACT), intersected with bright luminance
        val leftRadial = MaskSubMask(
            id = "s1", name = "Left", type = MaskType.RADIAL, op = MaskBooleanOp.ADD,
            centerX = 0.30f, centerY = 0.50f, radiusX = 0.25f, radiusY = 0.25f, feather = 0.05f
        )
        val centerHole = MaskSubMask(
            id = "s2", name = "Hole", type = MaskType.RADIAL, op = MaskBooleanOp.SUBTRACT,
            centerX = 0.30f, centerY = 0.50f, radiusX = 0.10f, radiusY = 0.10f, feather = 0.05f
        )
        val rightRadial = MaskSubMask(
            id = "s3", name = "Right", type = MaskType.RADIAL, op = MaskBooleanOp.ADD,
            centerX = 0.75f, centerY = 0.50f, radiusX = 0.15f, radiusY = 0.15f, feather = 0.05f
        )
        val brightOnly = MaskSubMask(
            id = "s4", name = "Bright", type = MaskType.LUMINANCE_RANGE, op = MaskBooleanOp.INTERSECT,
            lumMin = 0.50f, lumMax = 1.0f, lumFalloff = 0.05f
        )

        val layer = LocalMask(
            id = "comp1",
            name = "Composite",
            type = MaskType.RADIAL,
            subMasks = listOf(leftRadial, centerHole, rightRadial, brightOnly)
        )

        // Center of left radial was subtracted -> 0.0
        assertEquals(0f, MaskEngine.evaluateCompositeMaskAtPixel(layer, 0.30f, 0.50f, 1f, 0.9f, 0.9f, 0.9f), 0.02f)
        // Ring around left radial on bright pixel -> ~1.0
        assertTrue(MaskEngine.evaluateCompositeMaskAtPixel(layer, 0.45f, 0.50f, 1f, 0.9f, 0.9f, 0.9f) > 0.90f)
        // Right added radial on bright pixel -> ~1.0
        assertTrue(MaskEngine.evaluateCompositeMaskAtPixel(layer, 0.75f, 0.50f, 1f, 0.9f, 0.9f, 0.9f) > 0.90f)
        // Right added radial on DARK pixel -> 0.0 because of INTERSECT with brightOnly
        assertEquals(0f, MaskEngine.evaluateCompositeMaskAtPixel(layer, 0.75f, 0.50f, 1f, 0.1f, 0.1f, 0.1f), 0.02f)

        // Inverted layer flips 0 -> 1 and 1 -> 0
        val inverted = layer.copy(inverted = true)
        assertEquals(1f, MaskEngine.evaluateCompositeMaskAtPixel(inverted, 0.30f, 0.50f, 1f, 0.9f, 0.9f, 0.9f), 0.02f)
        assertEquals(0f, MaskEngine.evaluateCompositeMaskAtPixel(inverted, 0.75f, 0.50f, 1f, 0.9f, 0.9f, 0.9f), 0.02f)
    }

    // -------------------------------------------------------------------------
    // 6. Rubylith 50% #FF2A2A Overlay & All 8 Selective Local Adjustments
    // -------------------------------------------------------------------------

    @Test
    fun `rubylith overlay blends 50 percent FF2A2A red inside mask and preserves unmasked pixels`() {
        val w = 40
        val h = 40
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.rgb(40, 120, 200))

        val mask = LocalMask(
            id = "ruby1",
            name = "Center",
            type = MaskType.RADIAL,
            subMasks = listOf(
                MaskSubMask(
                    id = "r", name = "Center", type = MaskType.RADIAL,
                    centerX = 0.5f, centerY = 0.5f, radiusX = 0.25f, radiusY = 0.25f, feather = 0.05f
                )
            )
        )

        val overlaid = MaskEngine.applyRubylithOverlayBitmap(bmp, mask)
        val cornerPx = overlaid.getPixel(1, 1)
        val centerPx = overlaid.getPixel(w / 2, h / 2)

        // Corner (unmasked) must remain bit-for-bit identical
        assertEquals(40, Color.red(cornerPx))
        assertEquals(120, Color.green(cornerPx))
        assertEquals(200, Color.blue(cornerPx))

        // Center (alpha=1.0 -> 50% mix with #FF2A2A = (255, 42, 42)):
        // expected R = (40 + 255)/2 = 148, G = (120 + 42)/2 = 81, B = (200 + 42)/2 = 121
        assertTrue("Expected R~148, got ${Color.red(centerPx)}", abs(148 - Color.red(centerPx)) <= 2)
        assertTrue("Expected G~81, got ${Color.green(centerPx)}", abs(81 - Color.green(centerPx)) <= 2)
        assertTrue("Expected B~121, got ${Color.blue(centerPx)}", abs(121 - Color.blue(centerPx)) <= 2)
    }

    @Test
    fun `full resolution color pipeline applies all selective local mask adjustments inside mask only`() = runTest {
        val w = 64
        val h = 64
        val src = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        // Fill neutral mid-gray with a small subtle detail pattern in the center so sharpness/clarity have signal
        for (y in 0 until h) {
            for (x in 0 until w) {
                val base = if ((x + y) % 4 == 0) 136 else 120
                src.setPixel(x, y, Color.rgb(base, base, base))
            }
        }

        val mask = LocalMask(
            id = "adj_mask",
            name = "Selective",
            type = MaskType.RADIAL,
            exposure = 0.8f,
            contrast = 25f,
            highlights = -15f,
            shadows = 20f,
            temperature = 40f,
            tint = -15f,
            saturation = 30f,
            sharpness = 50f,
            clarity = 35f,
            subMasks = listOf(
                MaskSubMask(
                    id = "s1", name = "Radial", type = MaskType.RADIAL,
                    centerX = 0.5f, centerY = 0.5f, radiusX = 0.25f, radiusY = 0.25f, feather = 0.05f
                )
            )
        )

        val out = ColorGradingEngine.processFullResolution(
            source = src,
            params = GradingParams(masks = listOf(mask)),
            lut = null
        )

        val cornerSrc = src.getPixel(2, 2)
        val cornerOut = out.getPixel(2, 2)
        assertTrue("Unmasked corner R should stay unchanged", abs(Color.red(cornerSrc) - Color.red(cornerOut)) <= 2)
        assertTrue("Unmasked corner G should stay unchanged", abs(Color.green(cornerSrc) - Color.green(cornerOut)) <= 2)
        assertTrue("Unmasked corner B should stay unchanged", abs(Color.blue(cornerSrc) - Color.blue(cornerOut)) <= 2)

        val centerOut = out.getPixel(w / 2, h / 2)
        // Positive exposure + warm temperature should brighten center and shift R > B
        assertTrue("Masked center must be noticeably brighter (R=${Color.red(centerOut)})", Color.red(centerOut) > 165)
        assertTrue(
            "Positive mask temperature must make R > B (R=${Color.red(centerOut)}, B=${Color.blue(centerOut)})",
            Color.red(centerOut) > Color.blue(centerOut) + 12
        )
    }

    // -------------------------------------------------------------------------
    // 7. EditorViewModel Workflow: Sub-Masks, Brush Strokes, Color Range Picker, Overlay Toggle
    // -------------------------------------------------------------------------

    @Test
    fun `viewModel manages sub-mask boolean ops, vector brush strokes, color eyedropper, and overlay hotkey`() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = EditorViewModel(app)

        // Wait for initial sample photo load so pickColorFromCanvas can sample the active bitmap
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline && vm.uiState.value.fullWidth == 0) {
            testDispatcher.scheduler.advanceUntilIdle()
            Thread.sleep(20)
        }
        assertTrue("Expected initial sample photo to load", vm.uiState.value.fullWidth > 0)

        // Create a Color Range mask layer (automatically arms MASK_COLOR_RANGE eyedropper)
        vm.addLocalMask(MaskType.COLOR_RANGE)
        val layer1 = vm.uiState.value.activeMask
        assertNotNull(layer1)
        assertEquals(MaskType.COLOR_RANGE, layer1!!.type)

        // Toggle Rubylith overlay (shortcut 'O')
        val initialOverlay = vm.uiState.value.showMaskOverlay
        vm.toggleShowMaskOverlay()
        assertEquals(!initialOverlay, vm.uiState.value.showMaskOverlay)

        // Ensure Color Range eyedropper mode is active and sample the center of the loaded canvas
        if (vm.uiState.value.activeEyedropperMode != EyedropperMode.MASK_COLOR_RANGE) {
            vm.toggleEyedropperMode(EyedropperMode.MASK_COLOR_RANGE)
        }
        assertEquals(EyedropperMode.MASK_COLOR_RANGE, vm.uiState.value.activeEyedropperMode)

        vm.pickColorFromCanvas(0.5f, 0.5f)
        val updatedColorSub = vm.uiState.value.activeSubMask
        assertNotNull(updatedColorSub)
        assertTrue("Color range sub-mask must be marked sampled", updatedColorSub!!.colorSampled)
        assertEquals(EyedropperMode.NONE, vm.uiState.value.activeEyedropperMode)

        // Add a SUBTRACT Vector Brush sub-mask and paint a normalized UV stroke
        vm.addSubMask(layer1.id, MaskType.BRUSH, MaskBooleanOp.SUBTRACT)
        val brushSub = vm.uiState.value.activeSubMask
        assertNotNull(brushSub)
        assertEquals(MaskType.BRUSH, brushSub!!.type)
        assertEquals(MaskBooleanOp.SUBTRACT, brushSub.op)

        vm.startMaskBrushStroke(0.2f, 0.3f)
        vm.appendMaskBrushStroke(0.4f, 0.5f)
        vm.finishMaskBrushStroke()

        val afterBrush = vm.uiState.value.activeSubMask!!
        assertEquals(1, afterBrush.strokes.size)
        assertEquals(2, afterBrush.strokes.first().points.size)

        // Change boolean op to INTERSECT and verify undo restores SUBTRACT
        vm.setSubMaskBooleanOp(layer1.id, brushSub.id, MaskBooleanOp.INTERSECT)
        assertEquals(MaskBooleanOp.INTERSECT, vm.uiState.value.activeSubMask!!.op)

        vm.undo()
        assertEquals(MaskBooleanOp.SUBTRACT, vm.uiState.value.activeSubMask!!.op)
    }
}
