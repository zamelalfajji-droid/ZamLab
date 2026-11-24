package com.zamalfajji.zamlab.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Flare
import androidx.compose.material.icons.rounded.Title
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zamalfajji.zamlab.R
import com.zamalfajji.zamlab.editor.CuratedOledDrawSwatches
import com.zamalfajji.zamlab.editor.DrawBrushType
import com.zamalfajji.zamlab.editor.DrawingEngine
import com.zamalfajji.zamlab.editor.StudioSubMode
import com.zamalfajji.zamlab.editor.StudioTextBackdropStyle
import com.zamalfajji.zamlab.editor.StudioTextFontStyle
import com.zamalfajji.zamlab.ui.theme.StudioCinemaAmber
import com.zamalfajji.zamlab.ui.theme.StudioHairline
import com.zamalfajji.zamlab.ui.theme.StudioObsidian
import com.zamalfajji.zamlab.ui.theme.StudioTextMuted
import com.zamalfajji.zamlab.ui.theme.StudioTextPrimary
import com.zamalfajji.zamlab.ui.theme.StudioTextSecondary
import com.zamalfajji.zamlab.ui.theme.StudioTileBorder
import com.zamalfajji.zamlab.ui.theme.StudioTileSurface
import kotlin.math.roundToInt

/**
 * 3-pill OLED segmented selector at the top of STUDIO: Stickers | Draw | Text.
 */
@Composable
fun StudioSubModeSelector(
    selectedMode: StudioSubMode,
    onSelectMode: (StudioSubMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val containerShape = RoundedCornerShape(14.dp)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(containerShape)
            .background(StudioObsidian)
            .border(1.dp, StudioTileBorder, containerShape)
            .padding(3.dp)
            .testTag("studio_submode_selector"),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StudioSubMode.entries.forEach { mode ->
            val selected = mode == selectedMode
            val label = when (mode) {
                StudioSubMode.STICKERS -> stringResource(R.string.studio_submode_stickers)
                StudioSubMode.DRAW -> stringResource(R.string.studio_submode_draw)
                StudioSubMode.TEXT -> stringResource(R.string.studio_submode_text)
            }
            val icon = when (mode) {
                StudioSubMode.STICKERS -> Icons.Rounded.AutoAwesome
                StudioSubMode.DRAW -> Icons.Rounded.Draw
                StudioSubMode.TEXT -> Icons.Rounded.Title
            }
            val pillShape = RoundedCornerShape(11.dp)

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(34.dp)
                    .clip(pillShape)
                    .background(if (selected) StudioCinemaAmber else Color.Transparent)
                    .clickable(role = Role.Tab) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelectMode(mode)
                    }
                    .testTag("studio_submode_${mode.name.lowercase()}"),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (selected) Color.Black else StudioTextSecondary,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                            fontSize = 11.5.sp
                        ),
                        color = if (selected) Color.Black else StudioTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * Freehand Drawing Layer controls ("Draw" sub-mode):
 * - 3 Brush Types: Pen, Marker, Neon Glow
 * - 10 Curated OLED Color Swatches + Custom Full-Spectrum Hue Slider
 * - Brush Size Slider + Opacity Slider
 * - Stroke Undo / Redo + Gold "Done" CTA
 */
@Composable
fun StudioDrawPanel(
    brushType: DrawBrushType,
    colorArgb: Int,
    customHue: Float,
    brushSizeDp: Float,
    opacity: Float,
    canUndoStroke: Boolean,
    canRedoStroke: Boolean,
    onSelectBrushType: (DrawBrushType) -> Unit,
    onSelectColorSwatch: (Int) -> Unit,
    onCustomHueChange: (Float) -> Unit,
    onBrushSizeChange: (Float) -> Unit,
    onOpacityChange: (Float) -> Unit,
    onUndoStroke: () -> Unit,
    onRedoStroke: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val undoCd = stringResource(R.string.cd_draw_undo_stroke)
    val redoCd = stringResource(R.string.cd_draw_redo_stroke)
    val doneCd = stringResource(R.string.cd_draw_done)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 6.dp)
            .testTag("studio_draw_panel"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Row 1: Brush Types (Pen | Marker | Neon Glow) + Stroke Undo / Redo + Done
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DrawBrushType.entries.forEach { type ->
                    val selected = type == brushType
                    val label = when (type) {
                        DrawBrushType.PEN -> stringResource(R.string.draw_brush_pen)
                        DrawBrushType.MARKER -> stringResource(R.string.draw_brush_marker)
                        DrawBrushType.NEON_GLOW -> stringResource(R.string.draw_brush_neon)
                    }
                    val icon = when (type) {
                        DrawBrushType.PEN -> Icons.Rounded.Edit
                        DrawBrushType.MARKER -> Icons.Rounded.Brush
                        DrawBrushType.NEON_GLOW -> Icons.Rounded.Flare
                    }
                    val chipShape = RoundedCornerShape(12.dp)
                    Surface(
                        shape = chipShape,
                        color = if (selected) StudioCinemaAmber else StudioTileSurface,
                        modifier = Modifier
                            .height(34.dp)
                            .border(
                                width = 1.dp,
                                color = if (selected) StudioCinemaAmber else StudioTileBorder,
                                shape = chipShape
                            )
                            .clip(chipShape)
                            .clickable(role = Role.RadioButton) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSelectBrushType(type)
                            }
                            .testTag("draw_brush_${type.name.lowercase()}")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = if (selected) Color.Black else StudioTextSecondary,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 11.sp
                                ),
                                color = if (selected) Color.Black else StudioTextSecondary,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            // Stroke Undo / Redo + Done
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                DrawStrokeActionIconButton(
                    icon = Icons.AutoMirrored.Rounded.Undo,
                    contentDescription = undoCd,
                    enabled = canUndoStroke,
                    testTag = "draw_undo_button",
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onUndoStroke()
                    }
                )
                DrawStrokeActionIconButton(
                    icon = Icons.AutoMirrored.Rounded.Redo,
                    contentDescription = redoCd,
                    enabled = canRedoStroke,
                    testTag = "draw_redo_button",
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onRedoStroke()
                    }
                )
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = StudioCinemaAmber,
                    modifier = Modifier
                        .height(34.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(role = Role.Button) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onDone()
                        }
                        .semantics { contentDescription = doneCd }
                        .testTag("draw_done_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = stringResource(R.string.action_done),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            ),
                            color = Color.Black
                        )
                    }
                }
            }
        }

        // Row 2: 10 Curated OLED Color Swatches
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CuratedOledDrawSwatches.forEachIndexed { idx, swatchArgb ->
                val isSelected = swatchArgb == colorArgb
                val swatchCd = stringResource(R.string.cd_draw_color_swatch, idx + 1)
                OledColorSwatchDot(
                    colorArgb = swatchArgb,
                    selected = isSelected,
                    contentDescription = swatchCd,
                    testTag = "draw_swatch_$idx",
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelectColorSwatch(swatchArgb)
                    }
                )
            }
        }

        // Row 3: Custom Full-Spectrum Hue Slider with live color preview badge
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Color(colorArgb))
                    .border(1.5.dp, Color.White.copy(alpha = 0.85f), CircleShape)
            )
            Text(
                text = stringResource(R.string.draw_hue_label),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                color = StudioTextSecondary
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(28.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(7.dp)
                        .clip(CircleShape)
                ) {
                    drawRoundRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color(0xFFFF0000),
                                Color(0xFFFFFF00),
                                Color(0xFF00FF00),
                                Color(0xFF00FFFF),
                                Color(0xFF0000FF),
                                Color(0xFFFF00FF),
                                Color(0xFFFF0000)
                            )
                        ),
                        cornerRadius = CornerRadius(size.height / 2f, size.height / 2f)
                    )
                }
                Slider(
                    value = customHue.coerceIn(0f, 360f),
                    onValueChange = onCustomHueChange,
                    valueRange = 0f..360f,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(colorArgb),
                        activeTrackColor = Color.Transparent,
                        inactiveTrackColor = Color.Transparent
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("draw_hue_slider")
                )
            }
            Text(
                text = "${customHue.roundToInt()}°",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp
                ),
                color = StudioTextMuted
            )
        }

        // Row 4: Brush Size + Opacity Sliders side-by-side
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CompactOledSlider(
                label = stringResource(R.string.draw_size_label),
                valueText = "${brushSizeDp.roundToInt()}px",
                value = brushSizeDp.coerceIn(DrawingEngine.MIN_BRUSH_SIZE_DP, DrawingEngine.MAX_BRUSH_SIZE_DP),
                valueRange = DrawingEngine.MIN_BRUSH_SIZE_DP..DrawingEngine.MAX_BRUSH_SIZE_DP,
                onValueChange = onBrushSizeChange,
                testTag = "draw_size_slider",
                modifier = Modifier.weight(1f)
            )
            CompactOledSlider(
                label = stringResource(R.string.draw_opacity_label),
                valueText = "${(opacity * 100f).roundToInt()}%",
                value = opacity.coerceIn(0.10f, 1f),
                valueRange = 0.10f..1f,
                onValueChange = onOpacityChange,
                testTag = "draw_opacity_slider",
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * Styled Text & Callout Stamp controls ("Text" sub-mode):
 * - Quick OLED text input + bold gold "Add Text Layer" button
 * - 5 clean font styles: Modern Sans, Bold Display, Serif, Monospace, Brush
 * - Text color swatches + Plain / Pill / Outline backdrop toggle
 */
@Composable
fun StudioTextPanel(
    textInput: String,
    fontStyle: StudioTextFontStyle,
    colorArgb: Int,
    backdropStyle: StudioTextBackdropStyle,
    onTextInputChange: (String) -> Unit,
    onSelectFontStyle: (StudioTextFontStyle) -> Unit,
    onSelectColor: (Int) -> Unit,
    onSelectBackdropStyle: (StudioTextBackdropStyle) -> Unit,
    onAddTextLayer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val addBtnCd = stringResource(R.string.cd_add_text_layer)
    val placeholder = stringResource(R.string.text_stamp_placeholder)
    val canAdd = textInput.isNotBlank()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 6.dp)
            .testTag("studio_text_panel"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Row 1: OLED Text Input Field + "Add Text Layer" CTA
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val inputShape = RoundedCornerShape(12.dp)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(inputShape)
                    .background(StudioObsidian)
                    .border(
                        width = 1.dp,
                        color = if (canAdd) StudioCinemaAmber.copy(alpha = 0.65f) else StudioTileBorder,
                        shape = inputShape
                    )
                    .padding(horizontal = 11.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (textInput.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                        color = StudioTextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                BasicTextField(
                    value = textInput,
                    onValueChange = onTextInputChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        color = Color(colorArgb),
                        fontSize = 13.sp,
                        fontWeight = when (fontStyle) {
                            StudioTextFontStyle.BOLD_DISPLAY, StudioTextFontStyle.MONOSPACE, StudioTextFontStyle.BRUSH -> FontWeight.Bold
                            else -> FontWeight.Medium
                        },
                        fontFamily = when (fontStyle) {
                            StudioTextFontStyle.SERIF -> FontFamily.Serif
                            StudioTextFontStyle.MONOSPACE -> FontFamily.Monospace
                            StudioTextFontStyle.BRUSH -> FontFamily.Cursive
                            else -> FontFamily.SansSerif
                        },
                        fontStyle = if (fontStyle == StudioTextFontStyle.BRUSH) FontStyle.Italic else FontStyle.Normal
                    ),
                    cursorBrush = SolidColor(StudioCinemaAmber),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            keyboardController?.hide()
                            if (canAdd) onAddTextLayer()
                        }
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("studio_text_input")
                )
            }

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (canAdd) StudioCinemaAmber else StudioTileSurface,
                modifier = Modifier
                    .height(40.dp)
                    .border(
                        width = 1.dp,
                        color = if (canAdd) StudioCinemaAmber else StudioTileBorder,
                        shape = RoundedCornerShape(12.dp)
                    )
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = canAdd, role = Role.Button) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        keyboardController?.hide()
                        onAddTextLayer()
                    }
                    .semantics { contentDescription = addBtnCd }
                    .testTag("add_text_layer_button")
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.action_add_text_layer),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        ),
                        color = if (canAdd) Color.Black else StudioTextMuted,
                        maxLines = 1
                    )
                }
            }
        }

        // Row 2: 5 Clean Font Style Pills (Modern Sans, Bold Display, Serif, Monospace, Brush)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StudioTextFontStyle.entries.forEach { style ->
                val selected = style == fontStyle
                val pillShape = RoundedCornerShape(12.dp)
                Surface(
                    shape = pillShape,
                    color = if (selected) StudioCinemaAmber else StudioTileSurface,
                    modifier = Modifier
                        .height(32.dp)
                        .border(
                            width = 1.dp,
                            color = if (selected) StudioCinemaAmber else StudioTileBorder,
                            shape = pillShape
                        )
                        .clip(pillShape)
                        .clickable(role = Role.RadioButton) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelectFontStyle(style)
                        }
                        .testTag("text_font_${style.name.lowercase()}")
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = style.label,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 11.sp,
                                fontWeight = when (style) {
                                    StudioTextFontStyle.BOLD_DISPLAY -> FontWeight.ExtraBold
                                    StudioTextFontStyle.MONOSPACE, StudioTextFontStyle.BRUSH -> FontWeight.Bold
                                    else -> if (selected) FontWeight.Bold else FontWeight.Medium
                                },
                                fontFamily = when (style) {
                                    StudioTextFontStyle.SERIF -> FontFamily.Serif
                                    StudioTextFontStyle.MONOSPACE -> FontFamily.Monospace
                                    StudioTextFontStyle.BRUSH -> FontFamily.Cursive
                                    else -> FontFamily.SansSerif
                                },
                                fontStyle = if (style == StudioTextFontStyle.BRUSH) FontStyle.Italic else FontStyle.Normal
                            ),
                            color = if (selected) Color.Black else StudioTextPrimary,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        // Row 3: Backdrop Toggle (Plain | Pill | Outline) + Curated OLED Color Swatches
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Plain / Pill / Outline segmented pills
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(11.dp))
                    .background(StudioObsidian)
                    .border(1.dp, StudioTileBorder, RoundedCornerShape(11.dp))
                    .padding(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StudioTextBackdropStyle.entries.forEach { mode ->
                    val selected = mode == backdropStyle
                    val label = when (mode) {
                        StudioTextBackdropStyle.NONE -> stringResource(R.string.text_style_plain)
                        StudioTextBackdropStyle.PILL -> stringResource(R.string.text_style_pill)
                        StudioTextBackdropStyle.OUTLINE -> stringResource(R.string.text_style_outline)
                    }
                    Box(
                        modifier = Modifier
                            .height(28.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (selected) StudioCinemaAmber else Color.Transparent)
                            .clickable(role = Role.RadioButton) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSelectBackdropStyle(mode)
                            }
                            .padding(horizontal = 9.dp)
                            .testTag("text_backdrop_${mode.name.lowercase()}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.5.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                            ),
                            color = if (selected) Color.Black else StudioTextSecondary,
                            maxLines = 1
                        )
                    }
                }
            }

            // Text color swatches
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CuratedOledDrawSwatches.forEachIndexed { idx, swatchArgb ->
                    val swatchCd = stringResource(R.string.cd_draw_color_swatch, idx + 1)
                    OledColorSwatchDot(
                        colorArgb = swatchArgb,
                        selected = swatchArgb == colorArgb,
                        contentDescription = swatchCd,
                        testTag = "text_swatch_$idx",
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelectColor(swatchArgb)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun DrawStrokeActionIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(StudioTileSurface)
            .border(1.dp, if (enabled) StudioHairline else StudioTileBorder, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription }
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) StudioTextPrimary else StudioTextMuted.copy(alpha = 0.45f),
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun OledColorSwatchDot(
    colorArgb: Int,
    selected: Boolean,
    contentDescription: String,
    testTag: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) StudioCinemaAmber else StudioHairline,
                shape = CircleShape
            )
            .padding(3.dp)
            .clip(CircleShape)
            .background(Color(colorArgb))
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.contentDescription = contentDescription }
            .testTag(testTag)
    )
}

@Composable
private fun CompactOledSlider(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                color = StudioTextSecondary
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp
                ),
                color = StudioCinemaAmber
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = StudioCinemaAmber,
                activeTrackColor = StudioCinemaAmber,
                inactiveTrackColor = StudioTileBorder
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(26.dp)
                .testTag(testTag)
        )
    }
}
