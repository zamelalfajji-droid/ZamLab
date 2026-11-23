package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zamalfajji.zamlab.R
import com.example.editor.HealMode
import com.example.editor.OfflineHealEngine
import com.example.ui.theme.StudioAmber
import com.example.ui.theme.StudioBorder
import com.example.ui.theme.StudioBorderSubtle
import com.example.ui.theme.StudioCyan
import com.example.ui.theme.StudioEraserCrimson
import com.example.ui.theme.StudioSurfaceElevated
import com.example.ui.theme.StudioSurfaceHighest
import com.example.ui.theme.StudioTextMuted
import com.example.ui.theme.StudioTextPrimary
import com.example.ui.theme.StudioTextSecondary
import kotlin.math.roundToInt

@Composable
fun HealPanel(
    healMode: HealMode,
    brushSizePx: Float,
    feather: Float,
    opacity: Float,
    showHealMask: Boolean,
    isPickingCloneSource: Boolean,
    hasCloneSource: Boolean,
    canUndoStroke: Boolean,
    canRedoStroke: Boolean,
    hasHealEdits: Boolean,
    isProcessing: Boolean,
    onSelectMode: (HealMode) -> Unit,
    onBrushSizeChange: (Float) -> Unit,
    onFeatherChange: (Float) -> Unit,
    onOpacityChange: (Float) -> Unit,
    onTogglePickCloneSource: () -> Unit,
    onToggleShowMask: () -> Unit,
    onUndoStroke: () -> Unit,
    onRedoStroke: () -> Unit,
    onResetHeal: () -> Unit,
    modifier: Modifier = Modifier
) {
    val activeAccent = when (healMode) {
        HealMode.SPOT_HEAL -> StudioEraserCrimson
        HealMode.CLONE_STAMP -> StudioAmber
        HealMode.PATCH_SMOOTH -> StudioCyan
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 3 Offline Retouching Modes: Spot Heal | Clone Stamp | Patch / Smooth
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            HealModePill(
                label = HealMode.SPOT_HEAL.label,
                icon = Icons.Default.AutoFixHigh,
                selected = healMode == HealMode.SPOT_HEAL,
                accentColor = StudioEraserCrimson,
                enabled = !isProcessing,
                onClick = { onSelectMode(HealMode.SPOT_HEAL) },
                modifier = Modifier
                    .weight(1f)
                    .testTag("heal_mode_spot")
            )
            HealModePill(
                label = HealMode.CLONE_STAMP.label,
                icon = Icons.Default.ContentCopy,
                selected = healMode == HealMode.CLONE_STAMP,
                accentColor = StudioAmber,
                enabled = !isProcessing,
                onClick = { onSelectMode(HealMode.CLONE_STAMP) },
                modifier = Modifier
                    .weight(1f)
                    .testTag("heal_mode_clone")
            )
            HealModePill(
                label = HealMode.PATCH_SMOOTH.label,
                icon = Icons.Default.BlurOn,
                selected = healMode == HealMode.PATCH_SMOOTH,
                accentColor = StudioCyan,
                enabled = !isProcessing,
                onClick = { onSelectMode(HealMode.PATCH_SMOOTH) },
                modifier = Modifier
                    .weight(1f)
                    .testTag("heal_mode_smooth")
            )
        }

        // Clone Stamp source helper bar (tap "Set Source" or long-press photo to set crosshair)
        if (healMode == HealMode.CLONE_STAMP) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (isPickingCloneSource) StudioAmber.copy(alpha = 0.16f) else StudioSurfaceElevated,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .border(
                        1.dp,
                        if (isPickingCloneSource) StudioAmber else StudioBorderSubtle,
                        RoundedCornerShape(10.dp)
                    )
                    .clickable(enabled = !isProcessing, onClick = onTogglePickCloneSource)
                    .testTag("set_clone_source_button")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.GpsFixed,
                            contentDescription = stringResource(R.string.cd_set_clone_source),
                            tint = if (isPickingCloneSource || hasCloneSource) StudioAmber else StudioTextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = when {
                                isPickingCloneSource -> "Tap photo to place Clone Source crosshair"
                                hasCloneSource -> "Source locked • Drag to clone or long-press to move"
                                else -> "Tap Set Source or tap-and-hold photo to pick source"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = StudioTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isPickingCloneSource) StudioAmber else StudioSurfaceHighest
                    ) {
                        Text(
                            text = stringResource(R.string.action_set_clone_source),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isPickingCloneSource) Color(0xFF0B0E14) else StudioAmber,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }

        // Live Brush Preview + Sliders (Size, Feather / Softness, Opacity) with double-tap reset
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier.size(44.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.size(44.dp)) {
                    val maxR = size.minDimension * 0.46f
                    val minR = 4.dp.toPx()
                    val tSize = (
                        (brushSizePx - OfflineHealEngine.MIN_BRUSH_SIZE_PX) /
                            (OfflineHealEngine.MAX_BRUSH_SIZE_PX - OfflineHealEngine.MIN_BRUSH_SIZE_PX)
                        ).coerceIn(0f, 1f)
                    val radius = minR + (maxR - minR) * tSize
                    val featherStop = (1f - feather.coerceIn(0f, 1f) * 0.82f).coerceIn(0.15f, 1f)
                    drawCircle(
                        brush = Brush.radialGradient(
                            colorStops = arrayOf(
                                0f to activeAccent.copy(alpha = 0.72f * opacity),
                                featherStop to activeAccent.copy(alpha = 0.65f * opacity),
                                1f to Color.Transparent
                            ),
                            center = center,
                            radius = radius
                        ),
                        radius = radius
                    )
                    drawCircle(
                        color = Color.White.copy(alpha = 0.88f),
                        radius = radius,
                        style = Stroke(width = 1.4.dp.toPx())
                    )
                    if (feather > 0.03f) {
                        drawCircle(
                            color = activeAccent.copy(alpha = 0.7f),
                            radius = radius * (1f - feather * 0.85f),
                            style = Stroke(width = 1.dp.toPx())
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                HealBrushSliderRow(
                    label = "Brush Size",
                    formattedValue = "${brushSizePx.roundToInt()} px",
                    value = brushSizePx,
                    valueRange = OfflineHealEngine.MIN_BRUSH_SIZE_PX..OfflineHealEngine.MAX_BRUSH_SIZE_PX,
                    accentColor = activeAccent,
                    enabled = !isProcessing,
                    sliderTag = "slider_heal_brush_size",
                    labelTag = "label_heal_brush_size",
                    onValueChange = onBrushSizeChange,
                    onReset = { onBrushSizeChange(OfflineHealEngine.DEFAULT_BRUSH_SIZE_PX) }
                )

                HealBrushSliderRow(
                    label = "Feather",
                    formattedValue = "${(feather * 100f).roundToInt()}%",
                    value = feather,
                    valueRange = 0f..1f,
                    accentColor = activeAccent,
                    enabled = !isProcessing,
                    sliderTag = "slider_heal_feather",
                    labelTag = "label_heal_feather",
                    onValueChange = onFeatherChange,
                    onReset = { onFeatherChange(OfflineHealEngine.DEFAULT_FEATHER) }
                )

                HealBrushSliderRow(
                    label = "Opacity",
                    formattedValue = "${(opacity * 100f).roundToInt()}%",
                    value = opacity,
                    valueRange = 0.10f..1.0f,
                    accentColor = activeAccent,
                    enabled = !isProcessing,
                    sliderTag = "slider_heal_opacity",
                    labelTag = "label_heal_opacity",
                    onValueChange = onOpacityChange,
                    onReset = { onOpacityChange(OfflineHealEngine.DEFAULT_OPACITY) }
                )
            }
        }

        // Dedicated Stroke & Mask Controls: Undo Stroke | Redo Stroke | Show/Hide Mask | Reset Heal
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onUndoStroke,
                enabled = canUndoStroke && !isProcessing,
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    .testTag("heal_undo_stroke_button"),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Undo,
                    contentDescription = stringResource(R.string.cd_heal_undo),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = stringResource(R.string.action_heal_undo),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }

            OutlinedButton(
                onClick = onRedoStroke,
                enabled = canRedoStroke && !isProcessing,
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    .testTag("heal_redo_stroke_button"),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Redo,
                    contentDescription = stringResource(R.string.cd_heal_redo),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = stringResource(R.string.action_heal_redo),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (showHealMask) activeAccent.copy(alpha = 0.16f) else StudioSurfaceElevated,
                modifier = Modifier
                    .weight(1.05f)
                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .border(
                        1.dp,
                        if (showHealMask) activeAccent else StudioBorder,
                        RoundedCornerShape(10.dp)
                    )
                    .clickable(enabled = !isProcessing, onClick = onToggleShowMask)
                    .testTag("heal_toggle_mask_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = if (showHealMask) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = stringResource(R.string.cd_heal_toggle_mask),
                        tint = if (showHealMask) activeAccent else StudioTextSecondary,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (showHealMask) {
                            stringResource(R.string.action_heal_hide_mask)
                        } else {
                            stringResource(R.string.action_heal_show_mask)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (showHealMask) activeAccent else StudioTextSecondary,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            OutlinedButton(
                onClick = onResetHeal,
                enabled = hasHealEdits && !isProcessing,
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    .testTag("heal_reset_button"),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.RestartAlt,
                    contentDescription = stringResource(R.string.cd_heal_reset),
                    tint = if (hasHealEdits && !isProcessing) StudioEraserCrimson else StudioTextMuted,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = stringResource(R.string.action_heal_reset),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (hasHealEdits && !isProcessing) StudioEraserCrimson else StudioTextMuted,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (isProcessing) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(
                    color = activeAccent,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Applying offline ${healMode.label.lowercase()}…",
                    style = MaterialTheme.typography.labelSmall,
                    color = StudioTextSecondary
                )
            }
        }
    }
}

@Composable
private fun HealModePill(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    accentColor: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (selected) accentColor.copy(alpha = 0.16f) else StudioSurfaceElevated,
        modifier = modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(
                1.dp,
                if (selected) accentColor else StudioBorderSubtle,
                RoundedCornerShape(10.dp)
            )
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (selected) accentColor else StudioTextSecondary,
                modifier = Modifier.size(15.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) accentColor else StudioTextSecondary,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun HealBrushSliderRow(
    label: String,
    formattedValue: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    accentColor: Color,
    enabled: Boolean,
    sliderTag: String,
    labelTag: String,
    onValueChange: (Float) -> Unit,
    onReset: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .doubleTapToReset(enabled = enabled, onDoubleTapReset = onReset)
                .padding(vertical = 2.dp)
                .testTag(labelTag),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = StudioTextPrimary,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = formattedValue,
                style = MaterialTheme.typography.labelSmall,
                color = accentColor,
                maxLines = 1,
                softWrap = false
            )
        }
        StudioPrecisionTrack(
            value = value,
            valueRange = valueRange,
            step = SliderStepMath.defaultStepForRange(valueRange),
            enabled = enabled,
            accentColor = accentColor,
            compact = true,
            testTag = sliderTag,
            onValueChange = onValueChange,
            onReset = onReset
        )
    }
}
