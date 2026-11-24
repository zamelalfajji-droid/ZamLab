package com.zamalfajji.zamlab.editor

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 3-pill sub-mode selector inside Z-Effect's STUDIO tab.
 */
enum class StudioSubMode(val label: String) {
    STICKERS("Stickers"),
    DRAW("Draw"),
    TEXT("Text")
}

/**
 * Freehand brush styles available on transparent Drawing layers.
 */
enum class DrawBrushType(val label: String) {
    PEN("Pen"),
    MARKER("Marker"),
    NEON_GLOW("Neon Glow")
}

/**
 * A single 2D coordinate in the Drawing layer's local bitmap pixel space.
 */
data class DrawStrokePoint(
    val x: Float,
    val y: Float
)

/**
 * Immutable freehand brush stroke stored per Drawing layer so Stroke Undo/Redo
 * re-renders crisp vector-backed strokes without bitmap drift.
 */
data class DrawStroke(
    val points: List<DrawStrokePoint>,
    val brushType: DrawBrushType,
    val colorArgb: Int,
    val radiusPx: Float,
    val opacity: Float
)

/**
 * 5 clean typographic styles for Styled Text & Callout Stamp layers.
 */
enum class StudioTextFontStyle(val label: String) {
    MODERN_SANS("Modern Sans"),
    BOLD_DISPLAY("Bold Display"),
    SERIF("Serif"),
    MONOSPACE("Monospace"),
    BRUSH("Brush")
}

/**
 * Background / outline styling options for Styled Text stamps.
 */
enum class StudioTextBackdropStyle(val label: String) {
    NONE("Plain"),
    PILL("Pill"),
    OUTLINE("Outline")
}

/**
 * 10 curated high-contrast OLED color swatches shared by Freehand Drawing and Styled Text.
 */
val CuratedOledDrawSwatches: List<Int> = listOf(
    0xFFFFFFFF.toInt(), // Pure White
    0xFF141416.toInt(), // Obsidian Ink
    0xFFF5A623.toInt(), // Cinema Amber
    0xFFFF3B5C.toInt(), // Crimson Flare
    0xFFFF7A45.toInt(), // Coral Sunset
    0xFFB8FF3B.toInt(), // Electric Lime
    0xFF00E5FF.toInt(), // Cyan Pulse
    0xFF3B82F6.toInt(), // Cobalt Glow
    0xFFA855F7.toInt(), // Violet Neon
    0xFFFF2ED1.toInt()  // Magenta Spark
)

object CreativeColorMath {
    fun hueToColorArgb(hueDegrees: Float, saturation: Float = 0.92f, value: Float = 1.0f): Int {
        val wrapped = ((hueDegrees % 360f) + 360f) % 360f
        return Color.HSVToColor(floatArrayOf(wrapped, saturation.coerceIn(0f, 1f), value.coerceIn(0f, 1f)))
    }

    fun colorArgbToHue(colorArgb: Int): Float {
        val hsv = FloatArray(3)
        Color.colorToHSV(colorArgb, hsv)
        return hsv[0]
    }

    fun isLightColor(colorArgb: Int): Boolean {
        val r = Color.red(colorArgb) / 255f
        val g = Color.green(colorArgb) / 255f
        val b = Color.blue(colorArgb) / 255f
        val luminance = 0.2126f * r + 0.7152f * g + 0.0722f * b
        return luminance >= 0.56f
    }

    fun blendTowardWhite(colorArgb: Int, factor: Float): Int {
        val f = factor.coerceIn(0f, 1f)
        val r = (Color.red(colorArgb) + (255 - Color.red(colorArgb)) * f).roundToInt().coerceIn(0, 255)
        val g = (Color.green(colorArgb) + (255 - Color.green(colorArgb)) * f).roundToInt().coerceIn(0, 255)
        val b = (Color.blue(colorArgb) + (255 - Color.blue(colorArgb)) * f).roundToInt().coerceIn(0, 255)
        return Color.argb(255, r, g, b)
    }
}

/**
 * Pure-Android offscreen Canvas freehand drawing engine supporting Pen, Marker, and Neon Glow
 * brushes onto transparent ARGB_8888 layers with deterministic stroke replay for Undo/Redo.
 */
object DrawingEngine {
    const val MIN_BRUSH_SIZE_DP: Float = 4f
    const val MAX_BRUSH_SIZE_DP: Float = 64f
    const val DEFAULT_BRUSH_SIZE_DP: Float = 14f
    const val MAX_DRAWING_DIMENSION: Int = 1280

    fun resolveDrawingDimensions(canvasW: Int, canvasH: Int, maxDim: Int = MAX_DRAWING_DIMENSION): Pair<Int, Int> {
        val safeW = canvasW.coerceAtLeast(64)
        val safeH = canvasH.coerceAtLeast(64)
        val longest = max(safeW, safeH)
        if (longest <= maxDim) return safeW to safeH
        val scale = maxDim.toFloat() / longest.toFloat()
        val outW = (safeW * scale).roundToInt().coerceAtLeast(64)
        val outH = (safeH * scale).roundToInt().coerceAtLeast(64)
        return outW to outH
    }

    fun createTransparentDrawingBitmap(width: Int, height: Int): Bitmap {
        val bmp = Bitmap.createBitmap(width.coerceAtLeast(16), height.coerceAtLeast(16), Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.TRANSPARENT)
        return bmp
    }

    fun renderStrokesBitmap(width: Int, height: Int, strokes: List<DrawStroke>): Bitmap {
        val out = createTransparentDrawingBitmap(width, height)
        if (strokes.isEmpty()) return out
        val canvas = Canvas(out)
        for (stroke in strokes) {
            drawStroke(canvas, stroke)
        }
        return out
    }

    fun drawStroke(canvas: Canvas, stroke: DrawStroke) {
        if (stroke.points.isEmpty()) return
        val clampedOpacity = stroke.opacity.coerceIn(0.05f, 1f)
        val baseRadius = stroke.radiusPx.coerceAtLeast(1.2f)
        val path = buildSmoothPath(stroke.points)
        val isSingleDot = stroke.points.size == 1

        when (stroke.brushType) {
            DrawBrushType.PEN -> {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = withAlpha(stroke.colorArgb, clampedOpacity)
                    isDither = true
                    if (isSingleDot) {
                        style = Paint.Style.FILL
                    } else {
                        style = Paint.Style.STROKE
                        strokeWidth = baseRadius * 2f
                        strokeCap = Paint.Cap.ROUND
                        strokeJoin = Paint.Join.ROUND
                    }
                }
                if (isSingleDot) {
                    val p = stroke.points.first()
                    canvas.drawCircle(p.x, p.y, baseRadius, paint)
                } else {
                    canvas.drawPath(path, paint)
                }
            }

            DrawBrushType.MARKER -> {
                // Broad, translucent chisel/studio marker stroke
                val markerWidth = baseRadius * 2.75f
                val markerAlpha = (clampedOpacity * 0.58f).coerceIn(0.08f, 0.85f)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = withAlpha(stroke.colorArgb, markerAlpha)
                    isDither = true
                    if (isSingleDot) {
                        style = Paint.Style.FILL
                    } else {
                        style = Paint.Style.STROKE
                        strokeWidth = markerWidth
                        strokeCap = Paint.Cap.SQUARE
                        strokeJoin = Paint.Join.BEVEL
                    }
                }
                if (isSingleDot) {
                    val p = stroke.points.first()
                    val half = markerWidth * 0.5f
                    canvas.drawRoundRect(
                        RectF(p.x - half, p.y - half, p.x + half, p.y + half),
                        half * 0.25f,
                        half * 0.25f,
                        paint
                    )
                } else {
                    canvas.drawPath(path, paint)
                }
            }

            DrawBrushType.NEON_GLOW -> {
                val outerBlur = (baseRadius * 1.45f).coerceAtLeast(2f)
                val midBlur = (baseRadius * 0.65f).coerceAtLeast(1.2f)
                val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = withAlpha(stroke.colorArgb, clampedOpacity * 0.26f)
                    style = if (isSingleDot) Paint.Style.FILL else Paint.Style.STROKE
                    strokeWidth = baseRadius * 3.5f
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                    maskFilter = BlurMaskFilter(outerBlur, BlurMaskFilter.Blur.NORMAL)
                }
                val midPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = withAlpha(stroke.colorArgb, clampedOpacity * 0.56f)
                    style = if (isSingleDot) Paint.Style.FILL else Paint.Style.STROKE
                    strokeWidth = baseRadius * 2.05f
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                    maskFilter = BlurMaskFilter(midBlur, BlurMaskFilter.Blur.NORMAL)
                }
                val coreColor = CreativeColorMath.blendTowardWhite(stroke.colorArgb, 0.72f)
                val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = withAlpha(coreColor, clampedOpacity)
                    style = if (isSingleDot) Paint.Style.FILL else Paint.Style.STROKE
                    strokeWidth = (baseRadius * 0.88f).coerceAtLeast(1.5f)
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                }

                if (isSingleDot) {
                    val p = stroke.points.first()
                    canvas.drawCircle(p.x, p.y, baseRadius * 1.75f, outerPaint)
                    canvas.drawCircle(p.x, p.y, baseRadius * 1.05f, midPaint)
                    canvas.drawCircle(p.x, p.y, baseRadius * 0.48f, corePaint)
                } else {
                    canvas.drawPath(path, outerPaint)
                    canvas.drawPath(path, midPaint)
                    canvas.drawPath(path, corePaint)
                }
            }
        }
    }

    private fun buildSmoothPath(points: List<DrawStrokePoint>): Path {
        val path = Path()
        if (points.isEmpty()) return path
        val first = points.first()
        path.moveTo(first.x, first.y)
        if (points.size == 1) {
            path.lineTo(first.x + 0.01f, first.y + 0.01f)
            return path
        }
        if (points.size == 2) {
            val second = points[1]
            path.lineTo(second.x, second.y)
            return path
        }
        for (i in 1 until points.size - 1) {
            val p0 = points[i]
            val p1 = points[i + 1]
            val midX = (p0.x + p1.x) * 0.5f
            val midY = (p0.y + p1.y) * 0.5f
            path.quadTo(p0.x, p0.y, midX, midY)
        }
        val last = points.last()
        path.lineTo(last.x, last.y)
        return path
    }

    private fun withAlpha(colorArgb: Int, alphaFraction: Float): Int {
        val a = (alphaFraction.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
        return Color.argb(a, Color.red(colorArgb), Color.green(colorArgb), Color.blue(colorArgb))
    }
}

/**
 * Renders Styled Text & Callout Stamps onto crisp, tightly-fitted transparent ARGB_8888 bitmaps
 * so they drop directly onto the canvas as moveable, zoomable, rotatable, blendable ImageLayers.
 */
object TextStampRenderer {
    fun resolveTypeface(style: StudioTextFontStyle): Typeface {
        return when (style) {
            StudioTextFontStyle.MODERN_SANS -> Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            StudioTextFontStyle.BOLD_DISPLAY -> Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            StudioTextFontStyle.SERIF -> Typeface.create(Typeface.SERIF, Typeface.NORMAL)
            StudioTextFontStyle.MONOSPACE -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            StudioTextFontStyle.BRUSH -> Typeface.create("cursive", Typeface.BOLD_ITALIC)
        }
    }

    fun renderTextStampBitmap(
        rawText: String,
        fontStyle: StudioTextFontStyle,
        colorArgb: Int,
        backdropStyle: StudioTextBackdropStyle,
        maxWidthPx: Int = 920
    ): Bitmap {
        val trimmed = rawText.trim().ifEmpty { "Z-Effect" }
        val displayText = if (fontStyle == StudioTextFontStyle.BOLD_DISPLAY) {
            trimmed.uppercase()
        } else {
            trimmed
        }

        val baseTextSize = when (fontStyle) {
            StudioTextFontStyle.BOLD_DISPLAY -> 108f
            StudioTextFontStyle.BRUSH -> 112f
            StudioTextFontStyle.MONOSPACE -> 88f
            StudioTextFontStyle.SERIF -> 98f
            StudioTextFontStyle.MODERN_SANS -> 96f
        }
        val letterSpacing = when (fontStyle) {
            StudioTextFontStyle.BOLD_DISPLAY -> 0.08f
            StudioTextFontStyle.MONOSPACE -> 0.04f
            StudioTextFontStyle.MODERN_SANS -> 0.01f
            StudioTextFontStyle.SERIF -> 0.02f
            StudioTextFontStyle.BRUSH -> 0.0f
        }

        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorArgb
            textSize = baseTextSize
            typeface = resolveTypeface(fontStyle)
            this.letterSpacing = letterSpacing
            isSubpixelText = true
            if (backdropStyle == StudioTextBackdropStyle.NONE) {
                val shadowColor = if (CreativeColorMath.isLightColor(colorArgb)) {
                    Color.argb(175, 0, 0, 0)
                } else {
                    Color.argb(150, 255, 255, 255)
                }
                setShadowLayer(12f, 0f, 5f, shadowColor)
            }
        }

        val padH = when (backdropStyle) {
            StudioTextBackdropStyle.PILL -> 58
            StudioTextBackdropStyle.OUTLINE -> 38
            StudioTextBackdropStyle.NONE -> 32
        }
        val padV = when (backdropStyle) {
            StudioTextBackdropStyle.PILL -> 38
            StudioTextBackdropStyle.OUTLINE -> 34
            StudioTextBackdropStyle.NONE -> 28
        }

        val maxLayoutWidth = (maxWidthPx - padH * 2).coerceAtLeast(180)
        // Measure natural widest line first so short stamps don't become overly wide
        val naturalMaxLine = displayText
            .split('\n')
            .maxOfOrNull { line -> ceil(textPaint.measureText(line)).toInt() }
            ?.coerceAtLeast(48)
            ?: 120
        val layoutWidth = min(naturalMaxLine + 16, maxLayoutWidth)

        val staticLayout = StaticLayout.Builder
            .obtain(displayText, 0, displayText.length, textPaint, layoutWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(6f, 1.08f)
            .setIncludePad(true)
            .build()

        var maxDrawnLineWidth = 0f
        for (i in 0 until staticLayout.lineCount) {
            maxDrawnLineWidth = max(maxDrawnLineWidth, staticLayout.getLineWidth(i))
        }
        val contentW = ceil(max(maxDrawnLineWidth, 40f)).toInt().coerceAtMost(layoutWidth)
        val contentH = staticLayout.height.coerceAtLeast(48)

        val outW = (contentW + padH * 2).coerceAtLeast(120)
        val outH = (contentH + padV * 2).coerceAtLeast(88)

        val bitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(bitmap)

        val isLightText = CreativeColorMath.isLightColor(colorArgb)
        val contrastColor = if (isLightText) 0xFF0E0E10.toInt() else 0xFFF8F8FA.toInt()

        if (backdropStyle == StudioTextBackdropStyle.PILL) {
            val pillRect = RectF(6f, 6f, outW - 6f, outH - 6f)
            val cornerRadius = min(pillRect.height() * 0.42f, 44f)
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = if (isLightText) {
                    Color.argb(226, 16, 16, 20)
                } else {
                    Color.argb(236, 248, 248, 250)
                }
            }
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 3.5f
                color = if (isLightText) {
                    Color.argb(120, 245, 166, 35)
                } else {
                    Color.argb(95, 20, 20, 24)
                }
            }
            canvas.drawRoundRect(pillRect, cornerRadius, cornerRadius, fillPaint)
            canvas.drawRoundRect(pillRect, cornerRadius, cornerRadius, borderPaint)
        }

        // Center the StaticLayout horizontally inside outW
        val offsetX = (outW - layoutWidth) * 0.5f
        val offsetY = padV.toFloat()

        if (backdropStyle == StudioTextBackdropStyle.OUTLINE) {
            val outlinePaint = TextPaint(textPaint).apply {
                clearShadowLayer()
                style = Paint.Style.STROKE
                strokeWidth = (baseTextSize * 0.14f).coerceIn(10f, 18f)
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
                color = contrastColor
            }
            val outlineLayout = StaticLayout.Builder
                .obtain(displayText, 0, displayText.length, outlinePaint, layoutWidth)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(6f, 1.08f)
                .setIncludePad(true)
                .build()
            canvas.save()
            canvas.translate(offsetX, offsetY)
            outlineLayout.draw(canvas)
            canvas.restore()
        }

        canvas.save()
        canvas.translate(offsetX, offsetY)
        staticLayout.draw(canvas)
        canvas.restore()

        return bitmap
    }
}
