package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BlurCircular
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Gradient
import androidx.compose.material.icons.filled.InvertColors
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.editor.LocalMask
import com.example.editor.MAX_LOCAL_MASKS
import com.example.editor.MaskType
import com.example.ui.theme.StudioAmber
import com.example.ui.theme.StudioBackground
import com.example.ui.theme.StudioBorder
import com.example.ui.theme.StudioBorderSubtle
import com.example.ui.theme.StudioCyan
import com.example.ui.theme.StudioEraserCrimson
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioSurfaceElevated
import com.example.ui.theme.StudioSurfaceHighest
import com.example.ui.theme.StudioTextMuted
import com.example.ui.theme.StudioTextPrimary
import com.example.ui.theme.StudioTextSecondary
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun MaskPanel(
    masks: List<LocalMask>,
    activeMask: LocalMask?,
    showMaskOverlay: Boolean,
    onAddMask: (MaskType) -> Unit,
    onSelectMask: (String) -> Unit,
    onUpdateMask: (String, (LocalMask) -> LocalMask) -> Unit,
    onToggleMaskEnabled: (String) -> Unit,
    onToggleMaskInverted: (String) -> Unit,
    onToggleShowOverlay: () -> Unit,
    onDeleteMask: (String) -> Unit,
    onDraggingChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val canAddMore = masks.size < MAX_LOCAL_MASKS

    Column(modifier = modifier.fillMaxSize()) {
        // Top action bar: + Radial, + Linear, Overlay toggle, Invert toggle, Delete active mask
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF12151C))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(
                    onClick = { onAddMask(MaskType.RADIAL) },
                    enabled = canAddMore,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier
                        .defaultMinSize(minHeight = 40.dp)
                        .testTag("mask_add_radial_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = if (canAddMore) StudioAmber else StudioTextMuted,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = "+ Radial",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (canAddMore) StudioAmber else StudioTextMuted
                    )
                }

                OutlinedButton(
                    onClick = { onAddMask(MaskType.LINEAR) },
                    enabled = canAddMore,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier
                        .defaultMinSize(minHeight = 40.dp)
                        .testTag("mask_add_linear_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Gradient,
                        contentDescription = null,
                        tint = if (canAddMore) StudioCyan else StudioTextMuted,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = "+ Linear",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (canAddMore) StudioCyan else StudioTextMuted
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                IconButton(
                    onClick = onToggleShowOverlay,
                    enabled = activeMask != null,
                    modifier = Modifier.testTag("mask_overlay_toggle")
                ) {
                    Icon(
                        imageVector = if (showMaskOverlay) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = if (showMaskOverlay) "Hide mask overlay" else "Show mask overlay",
                        tint = when {
                            activeMask == null -> StudioTextMuted
                            showMaskOverlay -> StudioEraserCrimson
                            else -> StudioTextSecondary
                        },
                        modifier = Modifier.size(20.dp)
                    )
                }

                IconButton(
                    onClick = { activeMask?.let { onToggleMaskInverted(it.id) } },
                    enabled = activeMask != null,
                    modifier = Modifier.testTag("mask_invert_toggle")
                ) {
                    Icon(
                        imageVector = Icons.Default.InvertColors,
                        contentDescription = "Invert mask",
                        tint = when {
                            activeMask == null -> StudioTextMuted
                            activeMask.inverted -> StudioAmber
                            else -> StudioTextSecondary
                        },
                        modifier = Modifier.size(20.dp)
                    )
                }

                IconButton(
                    onClick = { activeMask?.let { onDeleteMask(it.id) } },
                    enabled = activeMask != null,
                    modifier = Modifier.testTag("mask_delete_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Delete selected mask",
                        tint = if (activeMask != null) StudioTextSecondary else StudioTextMuted,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        HorizontalDivider(color = StudioBorderSubtle, thickness = 1.dp)

        if (masks.isEmpty() || activeMask == null) {
            // Empty state explaining local gradient masks
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = StudioSurfaceElevated,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, StudioBorderSubtle, RoundedCornerShape(12.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Selective Local Masks",
                            style = MaterialTheme.typography.titleSmall,
                            color = StudioTextPrimary
                        )
                        Text(
                            text = "Add a Radial or Linear Gradient to shape Exposure, Contrast, Warmth, Saturation & Sharpness selectively at 120Hz.",
                            style = MaterialTheme.typography.bodySmall,
                            color = StudioTextSecondary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = { onAddMask(MaskType.RADIAL) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = StudioAmber,
                                    contentColor = StudioBackground
                                ),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                                modifier = Modifier
                                    .defaultMinSize(minHeight = 48.dp)
                                    .testTag("mask_empty_add_radial")
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Radial Gradient", style = MaterialTheme.typography.labelLarge)
                            }
                            OutlinedButton(
                                onClick = { onAddMask(MaskType.LINEAR) },
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                                modifier = Modifier
                                    .defaultMinSize(minHeight = 48.dp)
                                    .testTag("mask_empty_add_linear")
                            ) {
                                Icon(Icons.Default.Gradient, contentDescription = null, tint = StudioCyan, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Linear Gradient", style = MaterialTheme.typography.labelLarge, color = StudioCyan)
                            }
                        }
                    }
                }
            }
        } else {
            // Scrollable mask layer selector chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                masks.forEachIndexed { idx, m ->
                    val isSelected = m.id == activeMask.id
                    val accent = if (m.type == MaskType.RADIAL) StudioAmber else StudioCyan
                    Box(
                        modifier = Modifier
                            .defaultMinSize(minHeight = 40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                when {
                                    isSelected -> accent.copy(alpha = 0.2f)
                                    else -> StudioSurfaceElevated
                                }
                            )
                            .border(
                                width = 1.dp,
                                color = if (isSelected) accent else StudioBorderSubtle,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable { onSelectMask(m.id) }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .testTag("mask_chip_$idx"),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(if (m.enabled) accent else StudioTextMuted)
                            )
                            Text(
                                text = m.name + if (m.inverted) " (Inv)" else "",
                                style = MaterialTheme.typography.labelMedium,
                                color = when {
                                    !m.enabled -> StudioTextMuted
                                    isSelected -> accent
                                    else -> StudioTextPrimary
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // Active mask enable/bypass toggle chip
                Box(
                    modifier = Modifier
                        .defaultMinSize(minHeight = 40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (activeMask.enabled) StudioSurfaceElevated else StudioEraserCrimson.copy(alpha = 0.14f))
                        .border(
                            1.dp,
                            if (activeMask.enabled) StudioBorder else StudioEraserCrimson,
                            RoundedCornerShape(8.dp)
                        )
                        .clickable { onToggleMaskEnabled(activeMask.id) }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .testTag("mask_enabled_toggle"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (activeMask.enabled) "Active" else "Bypassed",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (activeMask.enabled) StudioTextSecondary else StudioEraserCrimson
                    )
                }
            }

            HorizontalDivider(color = StudioBorderSubtle, thickness = 1.dp)

            // Independent local adjustments for the selected mask
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (activeMask.type == MaskType.RADIAL) {
                    MaskSliderRow(
                        label = "Feather",
                        icon = Icons.Default.BlurCircular,
                        value = activeMask.feather * 100f,
                        valueRange = 0f..100f,
                        formattedValue = "${(activeMask.feather * 100f).roundToInt()}%",
                        isModified = abs(activeMask.feather - 0.5f) > 0.02f,
                        accentColor = StudioCyan,
                        testTag = "mask_slider_feather",
                        onValueChange = { v ->
                            onDraggingChanged(true)
                            onUpdateMask(activeMask.id) { it.copy(feather = (v / 100f).coerceIn(0f, 1f)) }
                        },
                        onValueChangeFinished = { onDraggingChanged(false) },
                        onReset = {
                            onDraggingChanged(false)
                            onUpdateMask(activeMask.id) { it.copy(feather = 0.5f) }
                        }
                    )
                }

                MaskSliderRow(
                    label = "Exposure",
                    icon = Icons.Default.Brightness6,
                    value = activeMask.exposure,
                    valueRange = -2f..2f,
                    formattedValue = String.format(java.util.Locale.US, "%+.2f EV", activeMask.exposure),
                    isModified = abs(activeMask.exposure) > 0.005f,
                    accentColor = StudioAmber,
                    testTag = "mask_slider_exposure",
                    onValueChange = { v ->
                        onDraggingChanged(true)
                        onUpdateMask(activeMask.id) { it.copy(exposure = v) }
                    },
                    onValueChangeFinished = { onDraggingChanged(false) },
                    onReset = {
                        onDraggingChanged(false)
                        onUpdateMask(activeMask.id) { it.copy(exposure = 0f) }
                    }
                )

                MaskSliderRow(
                    label = "Contrast",
                    icon = Icons.Default.Contrast,
                    value = activeMask.contrast,
                    valueRange = -100f..100f,
                    formattedValue = String.format(java.util.Locale.US, "%+d", activeMask.contrast.roundToInt()),
                    isModified = abs(activeMask.contrast) > 0.4f,
                    accentColor = StudioAmber,
                    testTag = "mask_slider_contrast",
                    onValueChange = { v ->
                        onDraggingChanged(true)
                        onUpdateMask(activeMask.id) { it.copy(contrast = v) }
                    },
                    onValueChangeFinished = { onDraggingChanged(false) },
                    onReset = {
                        onDraggingChanged(false)
                        onUpdateMask(activeMask.id) { it.copy(contrast = 0f) }
                    }
                )

                MaskSliderRow(
                    label = "Warmth",
                    icon = Icons.Default.Thermostat,
                    value = activeMask.warmth,
                    valueRange = -100f..100f,
                    formattedValue = String.format(java.util.Locale.US, "%+d", activeMask.warmth.roundToInt()),
                    isModified = abs(activeMask.warmth) > 0.4f,
                    accentColor = StudioAmber,
                    testTag = "mask_slider_warmth",
                    onValueChange = { v ->
                        onDraggingChanged(true)
                        onUpdateMask(activeMask.id) { it.copy(warmth = v) }
                    },
                    onValueChangeFinished = { onDraggingChanged(false) },
                    onReset = {
                        onDraggingChanged(false)
                        onUpdateMask(activeMask.id) { it.copy(warmth = 0f) }
                    }
                )

                MaskSliderRow(
                    label = "Saturation",
                    icon = Icons.Default.Palette,
                    value = activeMask.saturation,
                    valueRange = -100f..100f,
                    formattedValue = String.format(java.util.Locale.US, "%+d", activeMask.saturation.roundToInt()),
                    isModified = abs(activeMask.saturation) > 0.4f,
                    accentColor = StudioCyan,
                    testTag = "mask_slider_saturation",
                    onValueChange = { v ->
                        onDraggingChanged(true)
                        onUpdateMask(activeMask.id) { it.copy(saturation = v) }
                    },
                    onValueChangeFinished = { onDraggingChanged(false) },
                    onReset = {
                        onDraggingChanged(false)
                        onUpdateMask(activeMask.id) { it.copy(saturation = 0f) }
                    }
                )

                MaskSliderRow(
                    label = "Sharpness",
                    icon = Icons.Default.CenterFocusStrong,
                    value = activeMask.sharpness,
                    valueRange = 0f..100f,
                    formattedValue = "${activeMask.sharpness.roundToInt()}",
                    isModified = activeMask.sharpness > 0.4f,
                    accentColor = StudioCyan,
                    testTag = "mask_slider_sharpness",
                    onValueChange = { v ->
                        onDraggingChanged(true)
                        onUpdateMask(activeMask.id) { it.copy(sharpness = v) }
                    },
                    onValueChangeFinished = { onDraggingChanged(false) },
                    onReset = {
                        onDraggingChanged(false)
                        onUpdateMask(activeMask.id) { it.copy(sharpness = 0f) }
                    }
                )
            }
        }
    }
}

@Composable
private fun MaskSliderRow(
    label: String,
    icon: ImageVector,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    formattedValue: String,
    isModified: Boolean,
    accentColor: Color,
    testTag: String,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    onReset: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isModified) accentColor else StudioTextSecondary,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (isModified) StudioTextPrimary else StudioTextSecondary
                )
            }
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = if (isModified) accentColor.copy(alpha = 0.14f) else StudioSurfaceHighest,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onReset)
            ) {
                Text(
                    text = formattedValue,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isModified) accentColor else StudioTextSecondary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = accentColor,
                activeTrackColor = accentColor,
                inactiveTrackColor = StudioSurfaceHighest
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .testTag(testTag)
        )
    }
}
