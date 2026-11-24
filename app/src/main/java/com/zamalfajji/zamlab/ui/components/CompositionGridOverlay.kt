package com.zamalfajji.zamlab.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zamalfajji.zamlab.R
import com.zamalfajji.zamlab.editor.CompositionGridMath
import com.zamalfajji.zamlab.editor.CompositionGridMode
import com.zamalfajji.zamlab.editor.GridLineSegment
import com.zamalfajji.zamlab.editor.GridRect
import com.zamalfajji.zamlab.editor.SpiralOrientation
import com.zamalfajji.zamlab.ui.theme.StudioActiveOffWhite
import com.zamalfajji.zamlab.ui.theme.StudioAmber
import com.zamalfajji.zamlab.ui.theme.StudioBorderSubtle
import com.zamalfajji.zamlab.ui.theme.StudioCinemaAmber
import com.zamalfajji.zamlab.ui.theme.StudioGlassHairline
import com.zamalfajji.zamlab.ui.theme.StudioGlassHighlight
import com.zamalfajji.zamlab.ui.theme.StudioGlassSurface
import com.zamalfajji.zamlab.ui.theme.StudioLuxuryGold
import com.zamalfajji.zamlab.ui.theme.StudioSurfaceElevated
import com.zamalfajji.zamlab.ui.theme.StudioTextPrimary
import com.zamalfajji.zamlab.ui.theme.StudioTextSecondary

/**
 * Pure non-interactive composition guide overlay drawn directly over the live photo viewport.
 * Uses crisp 1px semi-transparent white lines with a subtle 1px dark shadow outline so guides
 * stay clearly legible across bright highlights and deep shadows, and are never baked into exports.
 */
@Composable
fun CompositionGridOverlay(
    mode: CompositionGridMode,
    spiralOrientation: SpiralOrientation,
    modifier: Modifier = Modifier
) {
    if (mode == CompositionGridMode.OFF) return

    val overlayDescription = stringResource(R.string.cd_composition_grid_overlay, mode.label)
    Canvas(
        modifier = modifier
            .semantics { contentDescription = overlayDescription }
            .testTag("composition_grid_canvas")
    ) {
        if (size.width <= 1f || size.height <= 1f) return@Canvas
        clipRect(0f, 0f, size.width, size.height) {
            drawCompositionGridGuide(
                mode = mode,
                spiralOrientation = spiralOrientation,
                l = 0f,
                t = 0f,
                r = size.width,
                b = size.height
            )
        }
    }
}

/**
 * Shared crisp dual-stroke renderer (subtle dark shadow outline + 1px semi-transparent white line)
 * used both on the main photo canvas and inside the Crop & Rotate framing rectangle.
 */
fun DrawScope.drawCompositionGridGuide(
    mode: CompositionGridMode,
    spiralOrientation: SpiralOrientation,
    l: Float,
    t: Float,
    r: Float,
    b: Float
) {
    if (mode == CompositionGridMode.OFF || r - l <= 1f || b - t <= 1f) return

    // Crisp ~1px white line with subtle ~1px dark shadow halo on each side
    val primaryLinePx = 1f.coerceAtLeast(0.5.dp.toPx()).coerceAtMost(1.5f)
    val secondaryLinePx = 1f
    val shadowPadPx = 1.6f

    val primaryWhite = Color.White.copy(alpha = 0.80f)
    val primaryShadow = Color.Black.copy(alpha = 0.44f)
    val secondaryWhite = Color.White.copy(alpha = 0.48f)
    val secondaryShadow = Color.Black.copy(alpha = 0.30f)

    fun drawSegments(segments: List<GridLineSegment>) {
        for (seg in segments) {
            val lineW = if (seg.isPrimary) primaryLinePx else secondaryLinePx
            val fg = if (seg.isPrimary) primaryWhite else secondaryWhite
            val bg = if (seg.isPrimary) primaryShadow else secondaryShadow
            drawCrispLine(
                start = Offset(seg.x1, seg.y1),
                end = Offset(seg.x2, seg.y2),
                lineColor = fg,
                shadowColor = bg,
                lineWidth = lineW,
                shadowWidth = lineW + shadowPadPx
            )
        }
    }

    when (mode) {
        CompositionGridMode.OFF -> Unit

        CompositionGridMode.THIRDS -> {
            drawSegments(CompositionGridMath.ruleOfThirdsLines(l, t, r, b))
        }

        CompositionGridMode.SQUARE_GRID -> {
            drawSegments(CompositionGridMath.squareGridLines(l, t, r, b))
        }

        CompositionGridMode.GOLDEN_RATIO -> {
            drawSegments(CompositionGridMath.goldenRatioLines(l, t, r, b))
        }

        CompositionGridMode.DIAGONAL_SYMMETRY -> {
            drawSegments(CompositionGridMath.dynamicSymmetryLines(l, t, r, b))
        }

        CompositionGridMode.GOLDEN_SPIRAL -> {
            val pivot = Offset((l + r) * 0.5f, (t + b) * 0.5f)
            scale(
                scaleX = if (spiralOrientation.flipX) -1f else 1f,
                scaleY = if (spiralOrientation.flipY) -1f else 1f,
                pivot = pivot
            ) {
                drawGoldenSpiralCanonical(
                    l = l,
                    t = t,
                    r = r,
                    b = b,
                    primaryWhite = primaryWhite,
                    primaryShadow = primaryShadow,
                    secondaryWhite = secondaryWhite,
                    secondaryShadow = secondaryShadow,
                    primaryLinePx = primaryLinePx,
                    secondaryLinePx = secondaryLinePx,
                    shadowPadPx = shadowPadPx
                )
            }
        }

        CompositionGridMode.CENTER_SAFE_ZONES -> {
            // 1. Center crosshair axes + bold central reticle
            drawSegments(CompositionGridMath.centerCrosshairLines(l, t, r, b))

            // 2. 90% Action Safe frame (solid crisp dual-stroke)
            val actionSafe = CompositionGridMath.actionSafeRect(l, t, r, b)
            drawCrispRect(
                rect = actionSafe,
                lineColor = primaryWhite,
                shadowColor = primaryShadow,
                lineWidth = primaryLinePx,
                shadowWidth = primaryLinePx + shadowPadPx,
                pathEffect = null
            )

            // 3. 80% Title Safe frame (dashed crisp dual-stroke)
            val titleSafe = CompositionGridMath.titleSafeRect(l, t, r, b)
            val dashEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 7f), 0f)
            drawCrispRect(
                rect = titleSafe,
                lineColor = primaryWhite.copy(alpha = 0.70f),
                shadowColor = primaryShadow.copy(alpha = 0.36f),
                lineWidth = primaryLinePx,
                shadowWidth = primaryLinePx + shadowPadPx,
                pathEffect = dashEffect
            )
        }
    }
}

private fun DrawScope.drawCrispLine(
    start: Offset,
    end: Offset,
    lineColor: Color,
    shadowColor: Color,
    lineWidth: Float,
    shadowWidth: Float
) {
    drawLine(
        color = shadowColor,
        start = start,
        end = end,
        strokeWidth = shadowWidth,
        cap = StrokeCap.Butt
    )
    drawLine(
        color = lineColor,
        start = start,
        end = end,
        strokeWidth = lineWidth,
        cap = StrokeCap.Butt
    )
}

private fun DrawScope.drawCrispRect(
    rect: GridRect,
    lineColor: Color,
    shadowColor: Color,
    lineWidth: Float,
    shadowWidth: Float,
    pathEffect: PathEffect?
) {
    val topLeft = Offset(rect.left, rect.top)
    val rectSize = Size(rect.width, rect.height)
    drawRect(
        color = shadowColor,
        topLeft = topLeft,
        size = rectSize,
        style = Stroke(width = shadowWidth, pathEffect = pathEffect)
    )
    drawRect(
        color = lineColor,
        topLeft = topLeft,
        size = rectSize,
        style = Stroke(width = lineWidth, pathEffect = pathEffect)
    )
}

private fun DrawScope.drawGoldenSpiralCanonical(
    l: Float,
    t: Float,
    r: Float,
    b: Float,
    primaryWhite: Color,
    primaryShadow: Color,
    secondaryWhite: Color,
    secondaryShadow: Color,
    primaryLinePx: Float,
    secondaryLinePx: Float,
    shadowPadPx: Float
) {
    var x0 = l
    var y0 = t
    var x1 = r
    var y1 = b
    val phiInv = CompositionGridMath.PHI_INV

    for (step in 0 until 8) {
        val rw = (x1 - x0).coerceAtLeast(1f)
        val rh = (y1 - y0).coerceAtLeast(1f)
        when (step % 4) {
            0 -> {
                val sqW = rw * phiInv
                drawCrispLine(
                    Offset(x0 + sqW, y0),
                    Offset(x0 + sqW, y1),
                    secondaryWhite,
                    secondaryShadow,
                    secondaryLinePx,
                    secondaryLinePx + shadowPadPx
                )
                drawCrispArc(
                    startAngle = 180f,
                    sweepAngle = 90f,
                    topLeft = Offset(x0, y0),
                    arcSize = Size(sqW * 2f, rh * 2f),
                    lineColor = primaryWhite,
                    shadowColor = primaryShadow,
                    lineWidth = primaryLinePx,
                    shadowWidth = primaryLinePx + shadowPadPx
                )
                x0 += sqW
            }

            1 -> {
                val sqH = rh * phiInv
                drawCrispLine(
                    Offset(x0, y0 + sqH),
                    Offset(x1, y0 + sqH),
                    secondaryWhite,
                    secondaryShadow,
                    secondaryLinePx,
                    secondaryLinePx + shadowPadPx
                )
                drawCrispArc(
                    startAngle = 270f,
                    sweepAngle = 90f,
                    topLeft = Offset(x0 - rw, y0),
                    arcSize = Size(rw * 2f, sqH * 2f),
                    lineColor = primaryWhite,
                    shadowColor = primaryShadow,
                    lineWidth = primaryLinePx,
                    shadowWidth = primaryLinePx + shadowPadPx
                )
                y0 += sqH
            }

            2 -> {
                val sqW = rw * phiInv
                val splitX = x1 - sqW
                drawCrispLine(
                    Offset(splitX, y0),
                    Offset(splitX, y1),
                    secondaryWhite,
                    secondaryShadow,
                    secondaryLinePx,
                    secondaryLinePx + shadowPadPx
                )
                drawCrispArc(
                    startAngle = 0f,
                    sweepAngle = 90f,
                    topLeft = Offset(splitX - sqW, y0 - rh),
                    arcSize = Size(sqW * 2f, rh * 2f),
                    lineColor = primaryWhite,
                    shadowColor = primaryShadow,
                    lineWidth = primaryLinePx,
                    shadowWidth = primaryLinePx + shadowPadPx
                )
                x1 = splitX
            }

            else -> {
                val sqH = rh * phiInv
                val splitY = y1 - sqH
                drawCrispLine(
                    Offset(x0, splitY),
                    Offset(x1, splitY),
                    secondaryWhite,
                    secondaryShadow,
                    secondaryLinePx,
                    secondaryLinePx + shadowPadPx
                )
                drawCrispArc(
                    startAngle = 90f,
                    sweepAngle = 90f,
                    topLeft = Offset(x0, splitY - sqH),
                    arcSize = Size(rw * 2f, sqH * 2f),
                    lineColor = primaryWhite,
                    shadowColor = primaryShadow,
                    lineWidth = primaryLinePx,
                    shadowWidth = primaryLinePx + shadowPadPx
                )
                y1 = splitY
            }
        }
    }
}

private fun DrawScope.drawCrispArc(
    startAngle: Float,
    sweepAngle: Float,
    topLeft: Offset,
    arcSize: Size,
    lineColor: Color,
    shadowColor: Color,
    lineWidth: Float,
    shadowWidth: Float
) {
    drawArc(
        color = shadowColor,
        startAngle = startAngle,
        sweepAngle = sweepAngle,
        useCenter = false,
        topLeft = topLeft,
        size = arcSize,
        style = Stroke(width = shadowWidth, cap = StrokeCap.Round)
    )
    drawArc(
        color = lineColor,
        startAngle = startAngle,
        sweepAngle = sweepAngle,
        useCenter = false,
        topLeft = topLeft,
        size = arcSize,
        style = Stroke(width = lineWidth, cap = StrokeCap.Round)
    )
}

/**
 * Compact dark-minimalist floating pill selector shown directly above the canvas utility strip
 * when the user taps the Grid icon button. Includes all 7 standard composition guides and a
 * tap-to-flip corner orientation button whenever [CompositionGridMode.GOLDEN_SPIRAL] is active.
 */
@Composable
fun CompositionGridSelectorPill(
    selectedMode: CompositionGridMode,
    spiralOrientation: SpiralOrientation,
    onSelectMode: (CompositionGridMode) -> Unit,
    onFlipSpiral: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = StudioGlassSurface,
        shadowElevation = 8.dp,
        modifier = modifier
            .border(1.dp, StudioGlassHairline, RoundedCornerShape(50))
            .testTag("composition_grid_selector_pill")
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            CompositionGridMode.entries.forEach { mode ->
                val isSelected = mode == selectedMode
                val isOff = mode == CompositionGridMode.OFF
                Box(
                    modifier = Modifier
                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        .clip(RoundedCornerShape(50))
                        .clickable { onSelectMode(mode) }
                        .testTag("grid_mode_${mode.name.lowercase()}"),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = when {
                            isSelected && !isOff -> StudioGlassHighlight
                            isSelected && isOff -> StudioSurfaceElevated
                            else -> Color.Transparent
                        },
                        modifier = Modifier.border(
                            width = 1.dp,
                            color = when {
                                isSelected && !isOff -> StudioCinemaAmber.copy(alpha = 0.72f)
                                isSelected && isOff -> StudioGlassHairline
                                else -> Color.Transparent
                            },
                            shape = RoundedCornerShape(50)
                        )
                    ) {
                        Text(
                            text = compositionGridShortLabel(mode),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                letterSpacing = 0.25.sp
                            ),
                            color = when {
                                isSelected && !isOff -> StudioCinemaAmber
                                isSelected && isOff -> StudioActiveOffWhite
                                else -> StudioTextSecondary
                            },
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = selectedMode == CompositionGridMode.GOLDEN_SPIRAL,
                enter = fadeIn() + expandHorizontally(),
                exit = fadeOut() + shrinkHorizontally()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 2.dp)
                            .width(1.dp)
                            .height(16.dp)
                            .background(StudioGlassHairline)
                    )
                    SpiralFlipPillButton(
                        orientation = spiralOrientation,
                        onFlip = onFlipSpiral,
                        testTag = "grid_spiral_flip_button"
                    )
                }
            }
        }
    }
}

/**
 * Compact one-thumb Composition Grid selector bar inside the Crop panel,
 * synchronized with the floating canvas utility strip.
 */
@Composable
fun CropCompositionGridBar(
    selectedMode: CompositionGridMode,
    spiralOrientation: SpiralOrientation,
    onSelectMode: (CompositionGridMode) -> Unit,
    onFlipSpiral: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(StudioGlassSurface)
            .border(1.dp, StudioGlassHairline, RoundedCornerShape(10.dp))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .testTag("crop_composition_grid_bar"),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = StudioIcons.CompositionGrid,
            contentDescription = stringResource(R.string.cd_composition_grid_label),
            tint = if (selectedMode != CompositionGridMode.OFF) StudioCinemaAmber else StudioTextSecondary,
            modifier = Modifier
                .padding(start = 4.dp, end = 2.dp)
                .size(16.dp)
        )

        CompositionGridMode.entries.forEach { mode ->
            val selected = mode == selectedMode
            Box(
                modifier = Modifier
                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onSelectMode(mode) }
                    .testTag("crop_grid_mode_${mode.name.lowercase()}"),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (selected) StudioAmber.copy(alpha = 0.2f) else StudioSurfaceElevated,
                    modifier = Modifier.border(
                        width = 1.dp,
                        color = if (selected) StudioAmber else StudioBorderSubtle,
                        shape = RoundedCornerShape(8.dp)
                    )
                ) {
                    Text(
                        text = compositionGridShortLabel(mode),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                        ),
                        color = if (selected) StudioAmber else StudioTextPrimary,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }

        if (selectedMode == CompositionGridMode.GOLDEN_SPIRAL) {
            Box(
                modifier = Modifier
                    .padding(horizontal = 2.dp)
                    .width(1.dp)
                    .height(18.dp)
                    .background(StudioGlassHairline)
            )
            SpiralFlipPillButton(
                orientation = spiralOrientation,
                onFlip = onFlipSpiral,
                testTag = "crop_spiral_flip_button"
            )
        }
    }
}

@Composable
fun SpiralFlipPillButton(
    orientation: SpiralOrientation,
    onFlip: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    val flipCd = stringResource(R.string.cd_flip_spiral_corner, orientation.label)
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onFlip)
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = StudioGlassHighlight,
            modifier = Modifier.border(
                width = 1.dp,
                color = StudioLuxuryGold.copy(alpha = 0.55f),
                shape = RoundedCornerShape(50)
            )
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = StudioIcons.FlipSpiral,
                    contentDescription = flipCd,
                    tint = StudioCinemaAmber,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = orientation.shortLabel,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.4.sp
                    ),
                    color = StudioActiveOffWhite,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

@Composable
private fun compositionGridShortLabel(mode: CompositionGridMode): String = when (mode) {
    CompositionGridMode.OFF -> stringResource(R.string.grid_mode_off)
    CompositionGridMode.THIRDS -> stringResource(R.string.grid_mode_thirds)
    CompositionGridMode.SQUARE_GRID -> stringResource(R.string.grid_mode_square)
    CompositionGridMode.GOLDEN_RATIO -> stringResource(R.string.grid_mode_golden_ratio)
    CompositionGridMode.GOLDEN_SPIRAL -> stringResource(R.string.grid_mode_golden_spiral)
    CompositionGridMode.DIAGONAL_SYMMETRY -> stringResource(R.string.grid_mode_diagonal)
    CompositionGridMode.CENTER_SAFE_ZONES -> stringResource(R.string.grid_mode_safe_zones)
}
