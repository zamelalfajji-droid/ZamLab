package com.zamalfajji.zamlab

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.zamalfajji.zamlab.editor.ColorGradingEngine
import com.zamalfajji.zamlab.editor.CompositionGridMath
import com.zamalfajji.zamlab.editor.CompositionGridMode
import com.zamalfajji.zamlab.editor.CropOverlayMode
import com.zamalfajji.zamlab.editor.EditorViewModel
import com.zamalfajji.zamlab.editor.GradingParams
import com.zamalfajji.zamlab.editor.SpiralOrientation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
class CompositionGridRobolectricTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `all 7 composition grid modes exist with accurate geometry math`() {
        assertEquals(
            listOf(
                CompositionGridMode.OFF,
                CompositionGridMode.THIRDS,
                CompositionGridMode.SQUARE_GRID,
                CompositionGridMode.GOLDEN_RATIO,
                CompositionGridMode.GOLDEN_SPIRAL,
                CompositionGridMode.DIAGONAL_SYMMETRY,
                CompositionGridMode.CENTER_SAFE_ZONES
            ),
            CompositionGridMode.entries
        )

        // 1. Rule of Thirds (3x3 -> 2 vertical + 2 horizontal lines at 1/3 and 2/3)
        val thirds = CompositionGridMath.ruleOfThirdsLines(0f, 0f, 900f, 600f)
        assertEquals(4, thirds.size)
        assertEquals(300f, thirds[0].x1, 0.01f)
        assertEquals(600f, thirds[1].x1, 0.01f)
        assertEquals(200f, thirds[2].y1, 0.01f)
        assertEquals(400f, thirds[3].y1, 0.01f)

        // 2. Square / Alignment Grid (8x8 -> 7 vertical + 7 horizontal lines, center axis primary)
        val sq = CompositionGridMath.squareGridLines(0f, 0f, 800f, 800f)
        assertEquals(14, sq.size)
        val primarySq = sq.filter { it.isPrimary }
        assertEquals(2, primarySq.size)
        assertEquals(400f, primarySq[0].x1, 0.01f)
        assertEquals(400f, primarySq[1].y1, 0.01f)

        // 3. Golden Ratio / Phi Grid (1 : 0.618 : 1 proportions)
        val phi = CompositionGridMath.goldenRatioLines(0f, 0f, 1000f, 1000f)
        assertEquals(4, phi.size)
        val leftBand = phi[0].x1
        val middleBand = phi[1].x1 - phi[0].x1
        val rightBand = 1000f - phi[1].x1
        assertEquals(leftBand, rightBand, 0.05f)
        assertEquals(CompositionGridMath.PHI_INV, middleBand / leftBand, 0.002f)

        // 4. Golden Spiral 4-corner focal eye positions occupy all 4 distinct quadrants
        val brEye = CompositionGridMath.goldenSpiralEye(0f, 0f, 1000f, 1000f, SpiralOrientation.BOTTOM_RIGHT)
        val blEye = CompositionGridMath.goldenSpiralEye(0f, 0f, 1000f, 1000f, SpiralOrientation.BOTTOM_LEFT)
        val tlEye = CompositionGridMath.goldenSpiralEye(0f, 0f, 1000f, 1000f, SpiralOrientation.TOP_LEFT)
        val trEye = CompositionGridMath.goldenSpiralEye(0f, 0f, 1000f, 1000f, SpiralOrientation.TOP_RIGHT)
        assertTrue("BR eye should be in bottom-right quadrant", brEye.x > 500f && brEye.y > 500f)
        assertTrue("BL eye should be in bottom-left quadrant", blEye.x < 500f && blEye.y > 500f)
        assertTrue("TL eye should be in top-left quadrant", tlEye.x < 500f && tlEye.y < 500f)
        assertTrue("TR eye should be in top-right quadrant", trEye.x > 500f && trEye.y < 500f)

        // 5. Dynamic Symmetry: main diagonals + 4 strictly perpendicular reciprocal diagonals (landscape & portrait)
        for ((w, h) in listOf(1600f to 900f, 900f to 1600f, 1000f to 1000f)) {
            val dyn = CompositionGridMath.dynamicSymmetryLines(0f, 0f, w, h)
            assertEquals(6, dyn.size)
            val sinister = dyn[0] // (0,0) -> (w,h)
            val baroque = dyn[1]  // (0,h) -> (w,0)
            val sDx = sinister.x2 - sinister.x1
            val sDy = sinister.y2 - sinister.y1
            val bDx = baroque.x2 - baroque.x1
            val bDy = baroque.y2 - baroque.y1

            // Reciprocals 2 & 3 are perpendicular to baroque diagonal
            for (idx in 2..3) {
                val dot = (dyn[idx].x2 - dyn[idx].x1) * bDx + (dyn[idx].y2 - dyn[idx].y1) * bDy
                assertEquals("Reciprocal $idx must be perpendicular to baroque diagonal", 0f, abs(dot), 0.1f)
            }
            // Reciprocals 4 & 5 are perpendicular to sinister diagonal
            for (idx in 4..5) {
                val dot = (dyn[idx].x2 - dyn[idx].x1) * sDx + (dyn[idx].y2 - dyn[idx].y1) * sDy
                assertEquals("Reciprocal $idx must be perpendicular to sinister diagonal", 0f, abs(dot), 0.1f)
            }
        }

        // 6. Center Crosshair & 90% Action Safe / 80% Title Safe cinema framing guides
        val actionSafe = CompositionGridMath.actionSafeRect(0f, 0f, 1920f, 1080f)
        val titleSafe = CompositionGridMath.titleSafeRect(0f, 0f, 1920f, 1080f)
        assertEquals(1920f * 0.90f, actionSafe.width, 0.01f)
        assertEquals(1080f * 0.90f, actionSafe.height, 0.01f)
        assertEquals(1920f * 0.80f, titleSafe.width, 0.01f)
        assertEquals(1080f * 0.80f, titleSafe.height, 0.01f)

        val crosshairs = CompositionGridMath.centerCrosshairLines(0f, 0f, 1920f, 1080f)
        assertEquals(4, crosshairs.size)
        assertEquals(960f, crosshairs[0].x1, 0.01f)
        assertEquals(540f, crosshairs[1].y1, 0.01f)
    }

    @Test
    fun `EditorViewModel manages composition grid selection, 4-corner spiral flip, clear, and crop sync`() = runTest(testDispatcher) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = EditorViewModel(app)

        // Default: OFF on general viewport, THIRDS in Crop mode
        assertEquals(CompositionGridMode.OFF, vm.uiState.value.compositionGridMode)
        assertEquals(SpiralOrientation.BOTTOM_RIGHT, vm.uiState.value.spiralOrientation)
        assertEquals(CompositionGridMode.THIRDS, vm.uiState.value.effectiveCropGridMode)

        // Selecting Golden Spiral updates both viewport & Crop overlay mode
        vm.setCompositionGridMode(CompositionGridMode.GOLDEN_SPIRAL)
        assertEquals(CompositionGridMode.GOLDEN_SPIRAL, vm.uiState.value.compositionGridMode)
        assertEquals(CropOverlayMode.SPIRAL, vm.uiState.value.cropOverlayMode)
        assertEquals(CompositionGridMode.GOLDEN_SPIRAL, vm.uiState.value.effectiveCropGridMode)

        // Tap-to-flip rotates Golden Spiral through all 4 corners and wraps cleanly
        vm.flipSpiralOrientation()
        assertEquals(SpiralOrientation.BOTTOM_LEFT, vm.uiState.value.spiralOrientation)
        vm.flipSpiralOrientation()
        assertEquals(SpiralOrientation.TOP_LEFT, vm.uiState.value.spiralOrientation)
        vm.flipSpiralOrientation()
        assertEquals(SpiralOrientation.TOP_RIGHT, vm.uiState.value.spiralOrientation)
        vm.flipSpiralOrientation()
        assertEquals(SpiralOrientation.BOTTOM_RIGHT, vm.uiState.value.spiralOrientation)

        // Selecting Safe Zones keeps effectiveCropGridMode synced
        vm.setCompositionGridMode(CompositionGridMode.CENTER_SAFE_ZONES)
        assertEquals(CompositionGridMode.CENTER_SAFE_ZONES, vm.uiState.value.compositionGridMode)
        assertEquals(CompositionGridMode.CENTER_SAFE_ZONES, vm.uiState.value.effectiveCropGridMode)

        // Long-press clear / Off hides overlay on both general canvas and Crop tab
        vm.clearCompositionGrid()
        assertEquals(CompositionGridMode.OFF, vm.uiState.value.compositionGridMode)
        assertEquals(CropOverlayMode.NONE, vm.uiState.value.cropOverlayMode)
        assertEquals(CompositionGridMode.OFF, vm.uiState.value.effectiveCropGridMode)

        // Cycle visits every mode and returns to OFF
        val visited = mutableListOf<CompositionGridMode>()
        repeat(CompositionGridMode.entries.size) {
            vm.cycleCompositionGridMode()
            visited += vm.uiState.value.compositionGridMode
        }
        assertEquals(CompositionGridMode.OFF, visited.last())
        assertEquals(CompositionGridMode.entries.size, visited.distinct().size)
    }

    @Test
    fun `composition grid is strictly viewport-only and never baked into exported images`() = runTest(testDispatcher) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = EditorViewModel(app)

        val src = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        for (y in 0 until 64) {
            for (x in 0 until 64) {
                src.setPixel(x, y, Color.rgb((x * 4).coerceAtMost(255), (y * 4).coerceAtMost(255), 120))
            }
        }

        vm.setCompositionGridMode(CompositionGridMode.OFF)
        val paramsOff: GradingParams = vm.uiState.value.gradingParams
        val exportOff = ColorGradingEngine.processFullResolution(src, paramsOff, null)

        vm.setCompositionGridMode(CompositionGridMode.CENTER_SAFE_ZONES)
        val paramsSafe: GradingParams = vm.uiState.value.gradingParams
        val exportSafe = ColorGradingEngine.processFullResolution(src, paramsSafe, null)

        assertEquals(paramsOff, paramsSafe)
        val pxOff = IntArray(64 * 64)
        val pxSafe = IntArray(64 * 64)
        exportOff.getPixels(pxOff, 0, 64, 0, 0, 64, 64)
        exportSafe.getPixels(pxSafe, 0, 64, 0, 0, 64, 64)
        assertArrayEquals("Exported pixels must be bit-for-bit identical when grid overlay is active", pxOff, pxSafe)

        src.recycle()
        exportOff.recycle()
        exportSafe.recycle()
    }

    @Test
    fun `composition grid string resources are properly defined`() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val labels = listOf(
            ctx.getString(R.string.grid_mode_off),
            ctx.getString(R.string.grid_mode_thirds),
            ctx.getString(R.string.grid_mode_square),
            ctx.getString(R.string.grid_mode_golden_ratio),
            ctx.getString(R.string.grid_mode_golden_spiral),
            ctx.getString(R.string.grid_mode_diagonal),
            ctx.getString(R.string.grid_mode_safe_zones)
        )
        assertEquals(listOf("Off", "Thirds", "Grid", "Phi", "Spiral", "Diagonal", "Safe"), labels)
        assertTrue(ctx.getString(R.string.cd_composition_grid_toggle).isNotBlank())
        assertTrue(ctx.getString(R.string.cd_flip_spiral_corner, "Top Left").contains("Top Left"))
    }
}
