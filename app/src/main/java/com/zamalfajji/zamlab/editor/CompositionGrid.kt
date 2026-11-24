package com.zamalfajji.zamlab.editor

import kotlin.math.min

/**
 * Standard photography and cinema composition overlay guides.
 * Strictly a live viewport framing aid — never stored in [GradingParams] and never baked into exports.
 */
enum class CompositionGridMode(
    val label: String,
    val shortLabel: String
) {
    OFF("Off", "Off"),
    THIRDS("Rule of Thirds", "Thirds"),
    SQUARE_GRID("Square Grid", "Grid"),
    GOLDEN_RATIO("Golden Ratio", "Phi"),
    GOLDEN_SPIRAL("Golden Spiral", "Spiral"),
    DIAGONAL_SYMMETRY("Dynamic Symmetry", "Diagonal"),
    CENTER_SAFE_ZONES("Center & Safe Zones", "Safe");

    fun toCropOverlayMode(): CropOverlayMode = when (this) {
        OFF -> CropOverlayMode.NONE
        THIRDS -> CropOverlayMode.THIRDS
        SQUARE_GRID -> CropOverlayMode.GRID
        GOLDEN_RATIO -> CropOverlayMode.GOLDEN
        GOLDEN_SPIRAL -> CropOverlayMode.SPIRAL
        DIAGONAL_SYMMETRY -> CropOverlayMode.DIAGONAL
        CENTER_SAFE_ZONES -> CropOverlayMode.GRID
    }

    fun next(): CompositionGridMode {
        val all = entries
        return all[(ordinal + 1) % all.size]
    }
}

fun CropOverlayMode.toCompositionGridMode(): CompositionGridMode = when (this) {
    CropOverlayMode.NONE -> CompositionGridMode.OFF
    CropOverlayMode.THIRDS -> CompositionGridMode.THIRDS
    CropOverlayMode.GRID -> CompositionGridMode.SQUARE_GRID
    CropOverlayMode.GOLDEN -> CompositionGridMode.GOLDEN_RATIO
    CropOverlayMode.SPIRAL -> CompositionGridMode.GOLDEN_SPIRAL
    CropOverlayMode.DIAGONAL, CropOverlayMode.TRIANGLE -> CompositionGridMode.DIAGONAL_SYMMETRY
}

/**
 * Corner orientation for the Golden / Fibonacci Spiral overlay.
 * Cycles clockwise around all 4 corners of the frame.
 */
enum class SpiralOrientation(
    val label: String,
    val shortLabel: String,
    val flipX: Boolean,
    val flipY: Boolean
) {
    BOTTOM_RIGHT("Bottom Right", "BR", flipX = false, flipY = false),
    BOTTOM_LEFT("Bottom Left", "BL", flipX = true, flipY = false),
    TOP_LEFT("Top Left", "TL", flipX = true, flipY = true),
    TOP_RIGHT("Top Right", "TR", flipX = false, flipY = true);

    fun next(): SpiralOrientation {
        val all = entries
        return all[(ordinal + 1) % all.size]
    }
}

data class GridLineSegment(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val isPrimary: Boolean = true
)

data class GridRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

data class GridPoint(
    val x: Float,
    val y: Float
)

/**
 * Pure geometry calculations for all standard photography and cinema composition guides.
 */
object CompositionGridMath {
    /** Inverse golden ratio 1 / phi ≈ 0.61803398. */
    const val PHI_INV: Float = 0.618034f

    /** Complementary golden ratio division 1 - (1 / phi) ≈ 0.38196602 (yields 1 : 0.618 : 1). */
    const val PHI_COMP: Float = 1f - PHI_INV

    /** Fine square/alignment grid subdivisions (8x8 for crisp horizon & architectural leveling). */
    const val SQUARE_GRID_DIVISIONS: Int = 8

    /** SMPTE broadcast/cinema 90% Action Safe frame inset (5% per side). */
    const val ACTION_SAFE_INSET: Float = 0.05f

    /** SMPTE broadcast/cinema 80% Title Safe frame inset (10% per side). */
    const val TITLE_SAFE_INSET: Float = 0.10f

    /** Rule of Thirds (3x3): 2 vertical lines + 2 horizontal lines at 1/3 and 2/3. */
    fun ruleOfThirdsLines(l: Float, t: Float, r: Float, b: Float): List<GridLineSegment> {
        val w = r - l
        val h = b - t
        return listOf(
            GridLineSegment(l + w / 3f, t, l + w / 3f, b, isPrimary = true),
            GridLineSegment(l + w * 2f / 3f, t, l + w * 2f / 3f, b, isPrimary = true),
            GridLineSegment(l, t + h / 3f, r, t + h / 3f, isPrimary = true),
            GridLineSegment(l, t + h * 2f / 3f, r, t + h * 2f / 3f, isPrimary = true)
        )
    }

    /** Square / Alignment Grid (8x8): fine alignment grid with emphasized center axes. */
    fun squareGridLines(
        l: Float,
        t: Float,
        r: Float,
        b: Float,
        divisions: Int = SQUARE_GRID_DIVISIONS
    ): List<GridLineSegment> {
        val w = r - l
        val h = b - t
        val half = divisions / 2
        val lines = ArrayList<GridLineSegment>((divisions - 1) * 2)
        for (i in 1 until divisions) {
            val frac = i.toFloat() / divisions
            val x = l + w * frac
            val y = t + h * frac
            lines += GridLineSegment(x, t, x, b, isPrimary = i == half)
            lines += GridLineSegment(l, y, r, y, isPrimary = i == half)
        }
        return lines
    }

    /** Golden Ratio / Phi Grid (1 : 0.618 : 1 proportions -> lines at 0.382 and 0.618). */
    fun goldenRatioLines(l: Float, t: Float, r: Float, b: Float): List<GridLineSegment> {
        val w = r - l
        val h = b - t
        return listOf(
            GridLineSegment(l + w * PHI_COMP, t, l + w * PHI_COMP, b, isPrimary = true),
            GridLineSegment(l + w * PHI_INV, t, l + w * PHI_INV, b, isPrimary = true),
            GridLineSegment(l, t + h * PHI_COMP, r, t + h * PHI_COMP, isPrimary = true),
            GridLineSegment(l, t + h * PHI_INV, r, t + h * PHI_INV, isPrimary = true)
        )
    }

    /**
     * Diagonal / Dynamic Symmetry armature:
     * - 2 primary corner-to-corner diagonals (baroque & sinister)
     * - 4 perpendicular reciprocal diagonals dropped from each corner to the opposite main diagonal,
     *   extended cleanly to the frame border for any aspect ratio (landscape, square, or portrait).
     */
    fun dynamicSymmetryLines(l: Float, t: Float, r: Float, b: Float): List<GridLineSegment> {
        val w = (r - l).coerceAtLeast(1e-3f)
        val h = (b - t).coerceAtLeast(1e-3f)

        val lines = ArrayList<GridLineSegment>(6)
        // Main diagonals: sinister (TL -> BR) and baroque (BL -> TR)
        lines += GridLineSegment(l, t, r, b, isPrimary = true)
        lines += GridLineSegment(l, b, r, t, isPrimary = true)

        if (w >= h) {
            // Landscape or square: perpendicular offset along horizontal border is h^2 / w
            val dx = (h * h) / w
            // Reciprocals perpendicular to baroque diagonal (BL -> TR)
            lines += GridLineSegment(l, t, l + dx, b, isPrimary = false)
            lines += GridLineSegment(r, b, r - dx, t, isPrimary = false)
            // Reciprocals perpendicular to sinister diagonal (TL -> BR)
            lines += GridLineSegment(l, b, l + dx, t, isPrimary = false)
            lines += GridLineSegment(r, t, r - dx, b, isPrimary = false)
        } else {
            // Portrait: perpendicular offset along vertical border is w^2 / h
            val dy = (w * w) / h
            // Reciprocals perpendicular to baroque diagonal (BL -> TR)
            lines += GridLineSegment(l, t, r, t + dy, isPrimary = false)
            lines += GridLineSegment(r, b, l, b - dy, isPrimary = false)
            // Reciprocals perpendicular to sinister diagonal (TL -> BR)
            lines += GridLineSegment(l, b, r, b - dy, isPrimary = false)
            lines += GridLineSegment(r, t, l, t + dy, isPrimary = false)
        }

        return lines
    }

    /** 90% Action Safe cinema framing rectangle (inset 5% per edge). */
    fun actionSafeRect(l: Float, t: Float, r: Float, b: Float): GridRect {
        val w = r - l
        val h = b - t
        return GridRect(
            left = l + w * ACTION_SAFE_INSET,
            top = t + h * ACTION_SAFE_INSET,
            right = r - w * ACTION_SAFE_INSET,
            bottom = b - h * ACTION_SAFE_INSET
        )
    }

    /** 80% Title Safe cinema framing rectangle (inset 10% per edge). */
    fun titleSafeRect(l: Float, t: Float, r: Float, b: Float): GridRect {
        val w = r - l
        val h = b - t
        return GridRect(
            left = l + w * TITLE_SAFE_INSET,
            top = t + h * TITLE_SAFE_INSET,
            right = r - w * TITLE_SAFE_INSET,
            bottom = b - h * TITLE_SAFE_INSET
        )
    }

    /** Center crosshair axes + crisp central reticle ticks for [CompositionGridMode.CENTER_SAFE_ZONES]. */
    fun centerCrosshairLines(l: Float, t: Float, r: Float, b: Float): List<GridLineSegment> {
        val w = r - l
        val h = b - t
        val cx = (l + r) * 0.5f
        val cy = (t + b) * 0.5f
        val reticleHalf = min(w, h) * 0.045f
        return listOf(
            // Subtle full center reference axes
            GridLineSegment(cx, t, cx, b, isPrimary = false),
            GridLineSegment(l, cy, r, cy, isPrimary = false),
            // Crisp high-visibility center crosshair reticle
            GridLineSegment(cx - reticleHalf, cy, cx + reticleHalf, cy, isPrimary = true),
            GridLineSegment(cx, cy - reticleHalf, cx, cy + reticleHalf, isPrimary = true)
        )
    }

    /**
     * Computes the focal eye convergence point of the Golden / Fibonacci Spiral
     * inside [l, t, r, b] for the given [SpiralOrientation].
     */
    fun goldenSpiralEye(
        l: Float,
        t: Float,
        r: Float,
        b: Float,
        orientation: SpiralOrientation
    ): GridPoint {
        var x0 = 0f
        var y0 = 0f
        var x1 = 1f
        var y1 = 1f
        for (step in 0 until 8) {
            val rw = x1 - x0
            val rh = y1 - y0
            when (step % 4) {
                0 -> x0 += rw * PHI_INV
                1 -> y0 += rh * PHI_INV
                2 -> x1 -= rw * PHI_INV
                else -> y1 -= rh * PHI_INV
            }
        }
        val baseX = (x0 + x1) * 0.5f
        val baseY = (y0 + y1) * 0.5f
        val normX = if (orientation.flipX) 1f - baseX else baseX
        val normY = if (orientation.flipY) 1f - baseY else baseY
        return GridPoint(
            x = l + (r - l) * normX,
            y = t + (b - t) * normY
        )
    }
}
