package com.zamalfajji.zamlab.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Custom 1.75dp rounded-stroke vector icon suite for ZamLab's Calibrated Darkroom Studio.
 *
 * All icons share a 24x24dp grid with uniform 1.75f stroke width, round line caps,
 * and round joins so top-bar actions, tool strip tiles, and the 6-tab bottom dock
 * feel bold, crisp, and instrument-grade.
 */
object StudioIcons {

    private val StrokeBrush = SolidColor(Color.White)
    private const val STROKE_W = 1.75f

    private fun buildIcon(
        name: String,
        autoMirror: Boolean = false,
        block: ImageVector.Builder.() -> Unit
    ): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
        autoMirror = autoMirror
    ).apply(block).build()

    private fun ImageVector.Builder.strokePath(
        strokeWidth: Float = STROKE_W,
        block: PathBuilder.() -> Unit
    ) {
        path(
            fill = null,
            stroke = StrokeBrush,
            strokeLineWidth = strokeWidth,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block
        )
    }

    private fun PathBuilder.addCircle(cx: Float, cy: Float, r: Float) {
        moveTo(cx - r, cy)
        arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, cx + r, cy)
        arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, cx - r, cy)
        close()
    }

    private fun PathBuilder.addRoundedBox(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        r: Float
    ) {
        moveTo(left + r, top)
        lineTo(right - r, top)
        arcTo(r, r, 0f, false, true, right, top + r)
        lineTo(right, bottom - r)
        arcTo(r, r, 0f, false, true, right - r, bottom)
        lineTo(left + r, bottom)
        arcTo(r, r, 0f, false, true, left, bottom - r)
        lineTo(left, top + r)
        arcTo(r, r, 0f, false, true, left + r, top)
        close()
    }

    // ---- Top Utility Bar ----

    val OpenPhoto: ImageVector by lazy {
        buildIcon("StudioOpenPhoto") {
            strokePath {
                // Rounded landscape frame with open top-right corner for "+"
                moveTo(13.5f, 4.5f)
                lineTo(5.5f, 4.5f)
                arcTo(2f, 2f, 0f, false, false, 3.5f, 6.5f)
                lineTo(3.5f, 17.5f)
                arcTo(2f, 2f, 0f, false, false, 5.5f, 19.5f)
                lineTo(17.5f, 19.5f)
                arcTo(2f, 2f, 0f, false, false, 19.5f, 17.5f)
                lineTo(19.5f, 10.5f)
                // Mountain contour inside frame
                moveTo(3.5f, 16.2f)
                lineTo(8.5f, 11.4f)
                lineTo(12.2f, 15.0f)
                lineTo(15.2f, 12.2f)
                lineTo(19.5f, 16.5f)
                // Corner "+" indicator
                moveTo(18.5f, 3.0f)
                lineTo(18.5f, 8.0f)
                moveTo(16.0f, 5.5f)
                lineTo(21.0f, 5.5f)
            }
        }
    }

    /** Folder/document RAW file import icon for SAF non-indexed RAW files */
    val ImportRawFile: ImageVector by lazy {
        buildIcon("StudioImportRawFile") {
            strokePath {
                // Folder silhouette
                moveTo(3.5f, 7.2f)
                arcTo(1.8f, 1.8f, 0f, false, true, 5.3f, 5.4f)
                lineTo(9.4f, 5.4f)
                lineTo(11.2f, 7.5f)
                lineTo(18.7f, 7.5f)
                arcTo(1.8f, 1.8f, 0f, false, true, 20.5f, 9.3f)
                lineTo(20.5f, 17.6f)
                arcTo(1.8f, 1.8f, 0f, false, true, 18.7f, 19.4f)
                lineTo(5.3f, 19.4f)
                arcTo(1.8f, 1.8f, 0f, false, true, 3.5f, 17.6f)
                close()
                // Crisp camera aperture / RAW dot mark inside folder
                addCircle(12.0f, 13.4f, 2.4f)
            }
        }
    }

    val Undo: ImageVector by lazy {
        buildIcon("StudioUndo", autoMirror = true) {
            strokePath {
                moveTo(8.5f, 13.5f)
                lineTo(4.0f, 9.0f)
                lineTo(8.5f, 4.5f)
                moveTo(4.0f, 9.0f)
                lineTo(14.5f, 9.0f)
                arcTo(5.25f, 5.25f, 0f, false, true, 19.75f, 14.25f)
                arcTo(5.25f, 5.25f, 0f, false, true, 14.5f, 19.5f)
                lineTo(11.0f, 19.5f)
            }
        }
    }

    val Redo: ImageVector by lazy {
        buildIcon("StudioRedo", autoMirror = true) {
            strokePath {
                moveTo(15.5f, 13.5f)
                lineTo(20.0f, 9.0f)
                lineTo(15.5f, 4.5f)
                moveTo(20.0f, 9.0f)
                lineTo(9.5f, 9.0f)
                arcTo(5.25f, 5.25f, 0f, false, false, 4.25f, 14.25f)
                arcTo(5.25f, 5.25f, 0f, false, false, 9.5f, 19.5f)
                lineTo(13.0f, 19.5f)
            }
        }
    }

    val SplitCompare: ImageVector by lazy {
        buildIcon("StudioSplitCompare") {
            strokePath {
                addRoundedBox(4.0f, 4.5f, 20.0f, 19.5f, 2.2f)
                moveTo(12.0f, 2.5f)
                lineTo(12.0f, 21.5f)
                // Tonal hatch marks on "Before" half
                moveTo(6.6f, 8.8f)
                lineTo(9.4f, 8.8f)
                moveTo(6.6f, 12.0f)
                lineTo(9.4f, 12.0f)
                moveTo(6.6f, 15.2f)
                lineTo(9.4f, 15.2f)
            }
        }
    }

    val ExportArrow: ImageVector by lazy {
        buildIcon("StudioExportArrow") {
            strokePath {
                moveTo(12.0f, 4.0f)
                lineTo(12.0f, 14.5f)
                moveTo(8.2f, 10.7f)
                lineTo(12.0f, 14.5f)
                lineTo(15.8f, 10.7f)
                moveTo(4.5f, 15.0f)
                lineTo(4.5f, 18.0f)
                arcTo(1.8f, 1.8f, 0f, false, false, 6.3f, 19.8f)
                lineTo(17.7f, 19.8f)
                arcTo(1.8f, 1.8f, 0f, false, false, 19.5f, 18.0f)
                lineTo(19.5f, 15.0f)
            }
        }
    }

    /** Upward share-out arrow from tray matching the darkroom 1.75dp rounded-stroke set */
    val SharePhoto: ImageVector by lazy {
        buildIcon("StudioSharePhoto") {
            strokePath {
                moveTo(12.0f, 14.8f)
                lineTo(12.0f, 4.2f)
                moveTo(8.2f, 8.0f)
                lineTo(12.0f, 4.2f)
                lineTo(15.8f, 8.0f)
                moveTo(4.5f, 14.8f)
                lineTo(4.5f, 18.0f)
                arcTo(1.8f, 1.8f, 0f, false, false, 6.3f, 19.8f)
                lineTo(17.7f, 19.8f)
                arcTo(1.8f, 1.8f, 0f, false, false, 19.5f, 18.0f)
                lineTo(19.5f, 14.8f)
            }
        }
    }

    val HistogramBars: ImageVector by lazy {
        buildIcon("StudioHistogramBars") {
            strokePath {
                moveTo(4.5f, 19.0f)
                lineTo(19.5f, 19.0f)
                moveTo(6.0f, 19.0f)
                lineTo(6.0f, 13.5f)
                moveTo(10.0f, 19.0f)
                lineTo(10.0f, 7.0f)
                moveTo(14.0f, 19.0f)
                lineTo(14.0f, 10.5f)
                moveTo(18.0f, 19.0f)
                lineTo(18.0f, 5.5f)
            }
        }
    }

    val ResetArc: ImageVector by lazy {
        buildIcon("StudioResetArc") {
            strokePath {
                moveTo(4.5f, 9.5f)
                lineTo(4.5f, 5.0f)
                lineTo(9.0f, 5.0f)
                moveTo(4.8f, 5.5f)
                arcTo(7.8f, 7.8f, 0f, true, true, 5.1f, 15.8f)
            }
        }
    }

    // ---- 6-Tab Floating Bottom Capsule Dock ----

    /** Precision horizontal sliders for Adjust */
    val TabAdjust: ImageVector by lazy {
        buildIcon("StudioTabAdjust") {
            strokePath {
                // Top track
                moveTo(3.5f, 6.0f)
                lineTo(7.5f, 6.0f)
                addCircle(9.8f, 6.0f, 2.0f)
                moveTo(12.2f, 6.0f)
                lineTo(20.5f, 6.0f)
                // Middle track
                moveTo(3.5f, 12.0f)
                lineTo(13.2f, 12.0f)
                addCircle(15.5f, 12.0f, 2.0f)
                moveTo(17.8f, 12.0f)
                lineTo(20.5f, 12.0f)
                // Bottom track
                moveTo(3.5f, 18.0f)
                lineTo(6.2f, 18.0f)
                addCircle(8.5f, 18.0f, 2.0f)
                moveTo(10.8f, 18.0f)
                lineTo(20.5f, 18.0f)
            }
        }
    }

    /** 4-square grid for All */
    val TabAll: ImageVector by lazy {
        buildIcon("StudioTabAll") {
            strokePath {
                addRoundedBox(3.8f, 3.8f, 10.3f, 10.3f, 1.8f)
                addRoundedBox(13.7f, 3.8f, 20.2f, 10.3f, 1.8f)
                addRoundedBox(3.8f, 13.7f, 10.3f, 20.2f, 1.8f)
                addRoundedBox(13.7f, 13.7f, 20.2f, 20.2f, 1.8f)
            }
        }
    }

    /** 3D LUT isometric cube for LUTs */
    val TabLuts: ImageVector by lazy {
        buildIcon("StudioTabLuts") {
            strokePath {
                // Outer isometric cube hexagon
                moveTo(12.0f, 3.0f)
                lineTo(19.8f, 7.5f)
                lineTo(19.8f, 16.5f)
                lineTo(12.0f, 21.0f)
                lineTo(4.2f, 16.5f)
                lineTo(4.2f, 7.5f)
                close()
                // Internal Y axes
                moveTo(4.2f, 7.5f)
                lineTo(12.0f, 12.0f)
                lineTo(19.8f, 7.5f)
                moveTo(12.0f, 12.0f)
                lineTo(12.0f, 21.0f)
            }
        }
    }

    /** Crop frame for Crop */
    val TabCrop: ImageVector by lazy {
        buildIcon("StudioTabCrop") {
            strokePath {
                moveTo(6.5f, 3.0f)
                lineTo(6.5f, 15.5f)
                arcTo(2.0f, 2.0f, 0f, false, false, 8.5f, 17.5f)
                lineTo(21.0f, 17.5f)
                moveTo(3.0f, 6.5f)
                lineTo(15.5f, 6.5f)
                arcTo(2.0f, 2.0f, 0f, false, true, 17.5f, 8.5f)
                lineTo(17.5f, 21.0f)
            }
        }
    }

    /** Diagonal band-aid healing plaster for Heal */
    val TabHeal: ImageVector by lazy {
        buildIcon("StudioTabHeal") {
            strokePath {
                // Tilted capsule at 45 degrees centered at (12, 12)
                moveTo(6.34f, 12.28f)
                lineTo(12.28f, 6.34f)
                arcTo(3.8f, 3.8f, 0f, false, true, 17.66f, 11.72f)
                lineTo(11.72f, 17.66f)
                arcTo(3.8f, 3.8f, 0f, false, true, 6.34f, 12.28f)
                close()
                // Gauze pad seams & micro dots
                moveTo(8.3f, 10.3f)
                lineTo(13.7f, 15.7f)
                moveTo(10.3f, 8.3f)
                lineTo(15.7f, 13.7f)
                addCircle(12.0f, 12.0f, 0.55f)
            }
        }
    }

    /** Radial mask frame for Masks */
    val TabMasks: ImageVector by lazy {
        buildIcon("StudioTabMasks") {
            strokePath {
                addRoundedBox(3.5f, 4.5f, 20.5f, 19.5f, 2.4f)
                addCircle(12.0f, 12.0f, 4.1f)
                // Cardinal radial tick marks outside mask ring
                moveTo(12.0f, 5.8f)
                lineTo(12.0f, 6.6f)
                moveTo(12.0f, 17.4f)
                lineTo(12.0f, 18.2f)
                moveTo(5.8f, 12.0f)
                lineTo(6.6f, 12.0f)
                moveTo(17.4f, 12.0f)
                lineTo(18.2f, 12.0f)
            }
        }
    }

    // ---- Adjust Strip & Section Tool Icons ----

    val AutoTone: ImageVector by lazy {
        buildIcon("StudioAutoTone") {
            strokePath {
                // Main 4-point star
                moveTo(10.5f, 3.5f)
                quadTo(11.6f, 9.4f, 17.5f, 10.5f)
                quadTo(11.6f, 11.6f, 10.5f, 17.5f)
                quadTo(9.4f, 11.6f, 3.5f, 10.5f)
                quadTo(9.4f, 9.4f, 10.5f, 3.5f)
                close()
                // Small companion sparkle
                moveTo(18.0f, 15.0f)
                lineTo(18.0f, 20.0f)
                moveTo(15.5f, 17.5f)
                lineTo(20.5f, 17.5f)
            }
        }
    }

    val Exposure: ImageVector by lazy {
        buildIcon("StudioExposure") {
            strokePath {
                addCircle(12.0f, 12.0f, 8.8f)
                moveTo(6.2f, 17.8f)
                lineTo(17.8f, 6.2f)
                // '+' top-left
                moveTo(7.4f, 9.0f)
                lineTo(10.6f, 9.0f)
                moveTo(9.0f, 7.4f)
                lineTo(9.0f, 10.6f)
                // '-' bottom-right
                moveTo(13.4f, 15.0f)
                lineTo(16.6f, 15.0f)
            }
        }
    }

    val Contrast: ImageVector by lazy {
        buildIcon("StudioContrast") {
            strokePath {
                addCircle(12.0f, 12.0f, 8.8f)
                moveTo(12.0f, 3.2f)
                lineTo(12.0f, 20.8f)
                moveTo(14.5f, 8.0f)
                lineTo(19.0f, 8.0f)
                moveTo(14.5f, 12.0f)
                lineTo(20.5f, 12.0f)
                moveTo(14.5f, 16.0f)
                lineTo(19.0f, 16.0f)
            }
        }
    }

    val Highlights: ImageVector by lazy {
        buildIcon("StudioHighlights") {
            strokePath {
                moveTo(4.0f, 16.5f)
                lineTo(20.0f, 16.5f)
                moveTo(7.0f, 16.5f)
                arcTo(5.0f, 5.0f, 0f, false, true, 17.0f, 16.5f)
                moveTo(12.0f, 4.5f)
                lineTo(12.0f, 8.0f)
                moveTo(6.2f, 7.5f)
                lineTo(8.3f, 9.6f)
                moveTo(17.8f, 7.5f)
                lineTo(15.7f, 9.6f)
            }
        }
    }

    val Shadows: ImageVector by lazy {
        buildIcon("StudioShadows") {
            strokePath {
                moveTo(4.0f, 8.5f)
                lineTo(20.0f, 8.5f)
                moveTo(7.0f, 8.5f)
                arcTo(5.0f, 5.0f, 0f, false, false, 17.0f, 8.5f)
                moveTo(6.5f, 16.2f)
                lineTo(17.5f, 16.2f)
                moveTo(9.0f, 19.2f)
                lineTo(15.0f, 19.2f)
            }
        }
    }

    val Whites: ImageVector by lazy {
        buildIcon("StudioWhites") {
            strokePath {
                addCircle(12.0f, 12.0f, 4.2f)
                moveTo(12.0f, 3.2f)
                lineTo(12.0f, 5.2f)
                moveTo(12.0f, 18.8f)
                lineTo(12.0f, 20.8f)
                moveTo(3.2f, 12.0f)
                lineTo(5.2f, 12.0f)
                moveTo(18.8f, 12.0f)
                lineTo(20.8f, 12.0f)
                moveTo(5.8f, 5.8f)
                lineTo(7.2f, 7.2f)
                moveTo(16.8f, 16.8f)
                lineTo(18.2f, 18.2f)
                moveTo(18.2f, 5.8f)
                lineTo(16.8f, 7.2f)
                moveTo(7.2f, 16.8f)
                lineTo(5.8f, 18.2f)
            }
        }
    }

    val Blacks: ImageVector by lazy {
        buildIcon("StudioBlacks") {
            strokePath {
                addCircle(12.0f, 12.0f, 8.5f)
                moveTo(4.5f, 13.0f)
                lineTo(19.5f, 13.0f)
                moveTo(6.2f, 16.2f)
                lineTo(17.8f, 16.2f)
                moveTo(9.0f, 19.0f)
                lineTo(15.0f, 19.0f)
            }
        }
    }

    val Temp: ImageVector by lazy {
        buildIcon("StudioTemp") {
            strokePath {
                moveTo(10.0f, 13.3f)
                lineTo(10.0f, 5.2f)
                arcTo(2.0f, 2.0f, 0f, false, true, 14.0f, 5.2f)
                lineTo(14.0f, 13.3f)
                arcTo(3.6f, 3.6f, 0f, true, true, 10.0f, 13.3f)
                close()
                // Mercury column & right tick marks
                moveTo(12.0f, 9.5f)
                lineTo(12.0f, 15.2f)
                addCircle(12.0f, 16.2f, 1.1f)
                moveTo(16.5f, 6.5f)
                lineTo(18.5f, 6.5f)
                moveTo(16.5f, 10.0f)
                lineTo(18.5f, 10.0f)
            }
        }
    }

    val Tint: ImageVector by lazy {
        buildIcon("StudioTint") {
            strokePath {
                moveTo(12.0f, 3.4f)
                curveTo(12.0f, 3.4f, 6.2f, 10.2f, 6.2f, 14.4f)
                arcTo(5.8f, 5.8f, 0f, false, false, 17.8f, 14.4f)
                curveTo(17.8f, 10.2f, 12.0f, 3.4f, 12.0f, 3.4f)
                close()
                moveTo(9.4f, 14.6f)
                arcTo(2.8f, 2.8f, 0f, false, false, 12.0f, 17.2f)
            }
        }
    }

    val Vibrance: ImageVector by lazy {
        buildIcon("StudioVibrance") {
            strokePath {
                moveTo(12.0f, 4.0f)
                lineTo(20.2f, 18.5f)
                lineTo(3.8f, 18.5f)
                close()
                moveTo(8.2f, 15.0f)
                quadTo(12.0f, 10.5f, 15.8f, 15.0f)
            }
        }
    }

    val Saturation: ImageVector by lazy {
        buildIcon("StudioSaturation") {
            strokePath {
                addCircle(12.0f, 8.6f, 4.3f)
                addCircle(8.6f, 14.6f, 4.3f)
                addCircle(15.4f, 14.6f, 4.3f)
            }
        }
    }

    val Sharpen: ImageVector by lazy {
        buildIcon("StudioSharpen") {
            strokePath {
                moveTo(12.0f, 3.8f)
                lineTo(19.6f, 19.2f)
                lineTo(4.4f, 19.2f)
                close()
                moveTo(12.0f, 9.2f)
                lineTo(12.0f, 19.2f)
            }
        }
    }

    val Texture: ImageVector by lazy {
        buildIcon("StudioTexture") {
            strokePath {
                addRoundedBox(4.0f, 4.0f, 20.0f, 20.0f, 2.2f)
                // Crisp woven cross-hatch texture
                moveTo(7.5f, 4.0f)
                lineTo(4.0f, 7.5f)
                moveTo(12.5f, 4.0f)
                lineTo(4.0f, 12.5f)
                moveTo(17.5f, 4.0f)
                lineTo(4.0f, 17.5f)
                moveTo(20.0f, 6.5f)
                lineTo(6.5f, 20.0f)
                moveTo(20.0f, 11.5f)
                lineTo(11.5f, 20.0f)
                moveTo(20.0f, 16.5f)
                lineTo(16.5f, 20.0f)
            }
        }
    }

    val Clarity: ImageVector by lazy {
        buildIcon("StudioClarity") {
            strokePath {
                moveTo(7.2f, 4.8f)
                lineTo(16.8f, 4.8f)
                lineTo(20.5f, 9.6f)
                lineTo(12.0f, 19.8f)
                lineTo(3.5f, 9.6f)
                close()
                moveTo(3.5f, 9.6f)
                lineTo(20.5f, 9.6f)
                moveTo(7.2f, 4.8f)
                lineTo(12.0f, 19.8f)
                lineTo(16.8f, 4.8f)
            }
        }
    }

    val Dehaze: ImageVector by lazy {
        buildIcon("StudioDehaze") {
            strokePath {
                moveTo(3.5f, 8.2f)
                quadTo(7.7f, 6.2f, 12.0f, 8.2f)
                quadTo(16.3f, 10.2f, 20.5f, 8.2f)
                moveTo(3.5f, 12.5f)
                quadTo(7.7f, 10.5f, 12.0f, 12.5f)
                quadTo(16.3f, 14.5f, 20.5f, 12.5f)
                moveTo(5.0f, 16.8f)
                lineTo(19.0f, 16.8f)
            }
        }
    }

    val Vignette: ImageVector by lazy {
        buildIcon("StudioVignette") {
            strokePath {
                addRoundedBox(3.5f, 4.5f, 20.5f, 19.5f, 2.2f)
                moveTo(12.0f, 7.4f)
                arcTo(6.0f, 4.6f, 0f, true, true, 11.99f, 7.4f)
                close()
            }
        }
    }

    val Grain: ImageVector by lazy {
        buildIcon("StudioGrain") {
            strokePath {
                addRoundedBox(4.0f, 4.0f, 20.0f, 20.0f, 2.4f)
                // Stippled film-grain dots
                addCircle(8.3f, 8.3f, 0.55f)
                addCircle(12.0f, 8.3f, 0.55f)
                addCircle(15.7f, 8.3f, 0.55f)
                addCircle(10.1f, 12.0f, 0.55f)
                addCircle(13.9f, 12.0f, 0.55f)
                addCircle(8.3f, 15.7f, 0.55f)
                addCircle(12.0f, 15.7f, 0.55f)
                addCircle(15.7f, 15.7f, 0.55f)
            }
        }
    }

    /** Concentric glowing halo ring + subtle cardinal flare ticks for Halation */
    val Halation: ImageVector by lazy {
        buildIcon("StudioHalation") {
            strokePath {
                addCircle(12.0f, 12.0f, 8.2f)
                addCircle(12.0f, 12.0f, 4.2f)
                moveTo(12.0f, 2.2f)
                lineTo(12.0f, 3.8f)
                moveTo(12.0f, 20.2f)
                lineTo(12.0f, 21.8f)
                moveTo(2.2f, 12.0f)
                lineTo(3.8f, 12.0f)
                moveTo(20.2f, 12.0f)
                lineTo(21.8f, 12.0f)
            }
        }
    }

    /** Soft radiant burst around core for Bloom */
    val Bloom: ImageVector by lazy {
        buildIcon("StudioBloom") {
            strokePath {
                addCircle(12.0f, 12.0f, 3.6f)
                // Soft 4-directional radiant petal arcs
                moveTo(12.0f, 3.2f)
                lineTo(12.0f, 6.2f)
                moveTo(12.0f, 17.8f)
                lineTo(12.0f, 20.8f)
                moveTo(3.2f, 12.0f)
                lineTo(6.2f, 12.0f)
                moveTo(17.8f, 12.0f)
                lineTo(20.8f, 12.0f)
                moveTo(6.0f, 6.0f)
                lineTo(8.1f, 8.1f)
                moveTo(15.9f, 15.9f)
                lineTo(18.0f, 18.0f)
                moveTo(18.0f, 6.0f)
                lineTo(15.9f, 8.1f)
                moveTo(8.1f, 15.9f)
                lineTo(6.0f, 18.0f)
            }
        }
    }

    /** Offset RGB circles + lateral prism fringe ticks for Chromatic Aberration */
    val ChromaticAberration: ImageVector by lazy {
        buildIcon("StudioChromaticAberration") {
            strokePath {
                addCircle(9.8f, 12.0f, 5.6f)
                addCircle(14.2f, 12.0f, 5.6f)
                moveTo(2.8f, 9.2f)
                lineTo(2.8f, 14.8f)
                moveTo(21.2f, 9.2f)
                lineTo(21.2f, 14.8f)
            }
        }
    }

    /** Horizontal cinema lens flare streak across core for Anamorphic Streak */
    val AnamorphicStreak: ImageVector by lazy {
        buildIcon("StudioAnamorphicStreak") {
            strokePath {
                addCircle(12.0f, 12.0f, 3.4f)
                moveTo(2.2f, 12.0f)
                lineTo(21.8f, 12.0f)
                moveTo(5.8f, 9.2f)
                lineTo(8.8f, 9.2f)
                moveTo(15.2f, 9.2f)
                lineTo(18.2f, 9.2f)
                moveTo(5.8f, 14.8f)
                lineTo(8.8f, 14.8f)
                moveTo(15.2f, 14.8f)
                lineTo(18.2f, 14.8f)
                moveTo(12.0f, 5.8f)
                lineTo(12.0f, 8.6f)
                moveTo(12.0f, 15.4f)
                lineTo(12.0f, 18.2f)
            }
        }
    }

    val BlackAndWhite: ImageVector by lazy {
        buildIcon("StudioBlackAndWhite") {
            strokePath {
                addCircle(12.0f, 12.0f, 8.5f)
                moveTo(6.0f, 18.0f)
                lineTo(18.0f, 6.0f)
                moveTo(11.5f, 16.5f)
                lineTo(16.5f, 11.5f)
                moveTo(14.2f, 18.2f)
                lineTo(18.2f, 14.2f)
            }
        }
    }

    val ToneCurve: ImageVector by lazy {
        buildIcon("StudioToneCurve") {
            strokePath {
                addRoundedBox(3.5f, 3.5f, 20.5f, 20.5f, 2.2f)
                moveTo(5.5f, 18.2f)
                curveTo(9.8f, 18.2f, 8.8f, 12.0f, 12.0f, 12.0f)
                curveTo(15.2f, 12.0f, 14.2f, 5.8f, 18.5f, 5.8f)
            }
        }
    }

    val Profile: ImageVector by lazy {
        buildIcon("StudioProfile") {
            strokePath {
                moveTo(3.8f, 8.8f)
                arcTo(2.0f, 2.0f, 0f, false, true, 5.8f, 6.8f)
                lineTo(8.4f, 6.8f)
                lineTo(9.8f, 4.6f)
                lineTo(14.2f, 4.6f)
                lineTo(15.6f, 6.8f)
                lineTo(18.2f, 6.8f)
                arcTo(2.0f, 2.0f, 0f, false, true, 20.2f, 8.8f)
                lineTo(20.2f, 17.4f)
                arcTo(2.0f, 2.0f, 0f, false, true, 18.2f, 19.4f)
                lineTo(5.8f, 19.4f)
                arcTo(2.0f, 2.0f, 0f, false, true, 3.8f, 17.4f)
                close()
                addCircle(12.0f, 12.8f, 3.3f)
            }
        }
    }

    val SaveRecipe: ImageVector by lazy {
        buildIcon("StudioSaveRecipe") {
            strokePath {
                moveTo(6.0f, 4.8f)
                arcTo(1.8f, 1.8f, 0f, false, true, 7.8f, 3.0f)
                lineTo(16.2f, 3.0f)
                arcTo(1.8f, 1.8f, 0f, false, true, 18.0f, 4.8f)
                lineTo(18.0f, 20.4f)
                lineTo(12.0f, 16.5f)
                lineTo(6.0f, 20.4f)
                close()
                moveTo(12.0f, 7.5f)
                lineTo(12.0f, 12.5f)
                moveTo(9.5f, 10.0f)
                lineTo(14.5f, 10.0f)
            }
        }
    }
}
