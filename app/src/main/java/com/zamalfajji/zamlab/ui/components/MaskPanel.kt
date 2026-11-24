package com.zamalfajji.zamlab.ui.components

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Gradient
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zamalfajji.zamlab.R
import com.zamalfajji.zamlab.editor.EyedropperMode
import com.zamalfajji.zamlab.editor.LocalMask
import com.zamalfajji.zamlab.editor.MAX_LOCAL_MASKS
import com.zamalfajji.zamlab.editor.MAX_SUB_MASKS_PER_LAYER
import com.zamalfajji.zamlab.editor.MaskBooleanOp
import com.zamalfajji.zamlab.editor.MaskSubMask
import com.zamalfajji.zamlab.editor.MaskType
import com.zamalfajji.zamlab.ui.theme.StudioAmber
import com.zamalfajji.zamlab.ui.theme.StudioAmberPillFill
import com.zamalfajji.zamlab.ui.theme.StudioBackground
import com.zamalfajji.zamlab.ui.theme.StudioBorder
import com.zamalfajji.zamlab.ui.theme.StudioCinemaAmber
import com.zamalfajji.zamlab.ui.theme.StudioCyan
import com.zamalfajji.zamlab.ui.theme.StudioGraphite
import com.zamalfajji.zamlab.ui.theme.StudioHairline
import com.zamalfajji.zamlab.ui.theme.StudioLuxuryGold
import com.zamalfajji.zamlab.ui.theme.StudioSurface
import com.zamalfajji.zamlab.ui.theme.StudioSurfaceElevated
import com.zamalfajji.zamlab.ui.theme.StudioTextMuted
import com.zamalfajji.zamlab.ui.theme.StudioTextPrimary
import com.zamalfajji.zamlab.ui.theme.StudioTextSecondary
import androidx.compose.ui.text.font.FontFamily
import kotlin.math.abs
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.roundToInt

private val RubylithAccent = Color(0xFFFF2A2A)

/**
 * Dark-minimalist professional non-destructive Masking Studio Panel supporting:
 *  - 5 mask modes: Vector Brush, Rotatable Radial, Rotatable Linear, Rec. 709 Luminance Range, CIELAB Color Range
 *  - Boolean sub-mask compositing per layer (Add, Subtract, Intersect) + Layer Invert
 *  - GPU Guided Image Filter "Feather / Edge Snap" (0–100)
 *  - Real-time 50% Rubylith red (#FF2A2A) overlay toggle (hotkey 'O')
 *  - All 8 selective local adjustments: Exposure, Contrast, Highlights, Shadows, Temperature, Tint, Saturation, Sharpness & Clarity
 */
@Composable
fun MaskPanel(
    masks: List<LocalMask>,
    activeMask: LocalMask?,
    activeSubMask: MaskSubMask?,
    showMaskOverlay: Boolean,
    activeEyedropperMode: EyedropperMode,
    maskBrushRadius: Float,
    maskBrushHardness: Float,
    maskBrushFlow: Float,
    onAddMask: (MaskType) -> Unit,
    onSelectMask: (String) -> Unit,
    onSelectSubMask: (String) -> Unit,
    onAddSubMask: (String, MaskType, MaskBooleanOp) -> Unit,
    onDeleteSubMask: (String, String) -> Unit,
    onSetSubMaskOp: (String, String, MaskBooleanOp) -> Unit,
    onUpdateSubMask: (String, String, (MaskSubMask) -> MaskSubMask) -> Unit,
    onClearBrushStrokes: (String, String) -> Unit,
    onUpdateBrushSettings: (Float?, Float?, Float?) -> Unit,
    onToggleColorRangeEyedropper: () -> Unit,
    onUpdateMask: (String, (LocalMask) -> LocalMask) -> Unit,
    onToggleMaskEnabled: (String) -> Unit,
    onToggleMaskInverted: (String) -> Unit,
    onToggleShowOverlay: () -> Unit,
    onDeleteMask: (String) -> Unit,
    onDraggingChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var pendingComposeOp by remember { mutableStateOf<MaskBooleanOp?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(StudioBackground)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag("mask_panel"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. Header + Rubylith Overlay (O) toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "NON-DESTRUCTIVE MASKS",
                    style = MaterialTheme.typography.labelLarge,
                    color = StudioTextPrimary
                )
                Text(
                    text = if (masks.isEmpty()) {
                        "Create a Brush, Gradient, Luminance, or Color Range mask"
                    } else {
                        "${masks.size}/$MAX_LOCAL_MASKS layers • GPU Edge-Aware"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = StudioTextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (masks.isNotEmpty()) {
                RubylithOverlayTogglePill(
                    active = showMaskOverlay,
                    onClick = onToggleShowOverlay
                )
            }
        }

        // 2. Create New Mask Layer Strip (all 5 modes)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val canAddLayer = masks.size < MAX_LOCAL_MASKS
            MaskType.entries.forEach { type ->
                MaskToolPill(
                    icon = iconForMaskType(type),
                    label = "+ ${type.label}",
                    accent = accentForMaskType(type),
                    enabled = canAddLayer,
                    onClick = { onAddMask(type) },
                    testTag = "add_${type.name.lowercase()}_mask_button"
                )
            }
        }

        if (masks.isEmpty() || activeMask == null) {
            EmptyMaskStudioCard()
        } else {
            // 3. Mask Layer Selector Strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                masks.forEach { mask ->
                    val selected = mask.id == activeMask.id
                    val shape = RoundedCornerShape(10.dp)
                    Row(
                        modifier = Modifier
                            .defaultMinSize(minHeight = 48.dp)
                            .clip(shape)
                            .background(if (selected) StudioSurfaceElevated else StudioSurface)
                            .border(
                                width = 1.dp,
                                color = if (selected) StudioLuxuryGold else StudioHairline,
                                shape = shape
                            )
                            .clickable {
                                pendingComposeOp = null
                                onSelectMask(mask.id)
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .testTag("mask_chip_${mask.id}"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(
                                    if (!mask.enabled) StudioTextMuted
                                    else accentForMaskType(mask.type)
                                )
                        )
                        Text(
                            text = mask.name,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (mask.enabled) StudioTextPrimary else StudioTextMuted,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                        )
                        val count = mask.resolvedSubMasks().size
                        if (count > 1) {
                            Text(
                                text = "×$count",
                                style = MaterialTheme.typography.labelSmall,
                                color = StudioTextSecondary
                            )
                        }
                    }
                }
            }

            // 4. Active Layer Actions: Enable / Invert / Delete
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MaskActionChip(
                    icon = if (activeMask.enabled) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                    label = if (activeMask.enabled) "Active" else "Bypassed",
                    selected = activeMask.enabled,
                    accent = StudioCyan,
                    onClick = { onToggleMaskEnabled(activeMask.id) },
                    modifier = Modifier.weight(1f),
                    testTag = "mask_toggle_enabled_button"
                )
                MaskActionChip(
                    icon = Icons.Default.Flip,
                    label = if (activeMask.inverted) "Inverted" else "Invert",
                    selected = activeMask.inverted,
                    accent = StudioAmber,
                    onClick = { onToggleMaskInverted(activeMask.id) },
                    modifier = Modifier.weight(1f),
                    testTag = "mask_toggle_invert_button"
                )
                val deleteCd = stringResource(R.string.cd_mask_delete_submask)
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(StudioSurface)
                        .border(1.dp, StudioHairline, RoundedCornerShape(10.dp))
                        .semantics { contentDescription = deleteCd }
                        .clickable { onDeleteMask(activeMask.id) }
                        .testTag("mask_delete_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete mask layer",
                        tint = RubylithAccent,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // 5. Boolean Sub-Mask Compositing Stack (Add / Subtract / Intersect)
            val subMasks = activeMask.resolvedSubMasks()
            val canAddSub = subMasks.size < MAX_SUB_MASKS_PER_LAYER
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(StudioSurface)
                    .border(1.dp, StudioHairline, RoundedCornerShape(12.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "SUB-MASKS (${subMasks.size})",
                        style = MaterialTheme.typography.labelSmall,
                        color = StudioTextSecondary
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        MaskBooleanOp.entries.forEach { op ->
                            val isPickingThis = pendingComposeOp == op
                            val opColor = colorForBooleanOp(op)
                            Box(
                                modifier = Modifier
                                    .defaultMinSize(minWidth = 48.dp, minHeight = 36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isPickingThis) opColor.copy(alpha = 0.22f) else StudioSurfaceElevated)
                                    .border(
                                        1.dp,
                                        if (isPickingThis) opColor else StudioBorder,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .clickable(enabled = canAddSub) {
                                        pendingComposeOp = if (isPickingThis) null else op
                                    }
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                                    .testTag("submask_compose_${op.name.lowercase()}_button"),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "${op.symbol} ${op.label}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isPickingThis) opColor else StudioTextPrimary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }

                // Inline tool picker when user tapped Add / Subtract / Intersect
                pendingComposeOp?.let { chosenOp ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        MaskType.entries.forEach { toolType ->
                            MaskToolPill(
                                icon = iconForMaskType(toolType),
                                label = "${chosenOp.symbol} ${toolType.label}",
                                accent = colorForBooleanOp(chosenOp),
                                enabled = true,
                                onClick = {
                                    onAddSubMask(activeMask.id, toolType, chosenOp)
                                    pendingComposeOp = null
                                },
                                testTag = "submask_pick_${chosenOp.name.lowercase()}_${toolType.name.lowercase()}"
                            )
                        }
                    }
                }

                // Existing sub-mask chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    subMasks.forEachIndexed { idx, sub ->
                        val isSubSelected = sub.id == activeSubMask?.id
                        val opColor = colorForBooleanOp(sub.op)
                        Row(
                            modifier = Modifier
                                .defaultMinSize(minHeight = 44.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(if (isSubSelected) StudioSurfaceElevated else StudioBackground)
                                .border(
                                    1.dp,
                                    if (isSubSelected) opColor else StudioHairline,
                                    RoundedCornerShape(9.dp)
                                )
                                .clickable { onSelectSubMask(sub.id) }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                .testTag("submask_chip_${sub.id}"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            if (idx > 0) {
                                Text(
                                    text = sub.op.symbol,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = opColor,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .clickable {
                                            val nextOp = MaskBooleanOp.entries[(sub.op.ordinal + 1) % MaskBooleanOp.entries.size]
                                            onSetSubMaskOp(activeMask.id, sub.id, nextOp)
                                        }
                                        .padding(horizontal = 3.dp)
                                )
                            }
                            Icon(
                                imageVector = iconForMaskType(sub.type),
                                contentDescription = null,
                                tint = if (isSubSelected) accentForMaskType(sub.type) else StudioTextSecondary,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = sub.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isSubSelected) StudioTextPrimary else StudioTextSecondary
                            )
                            if (subMasks.size > 1) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = stringResource(R.string.cd_mask_delete_submask),
                                    tint = StudioTextMuted,
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clickable { onDeleteSubMask(activeMask.id, sub.id) }
                                        .testTag("delete_submask_${sub.id}")
                                )
                            }
                        }
                    }
                }
            }

            // 6. Active Sub-Mask Parameters (mode-specific)
            if (activeSubMask != null) {
                SubMaskControlsSection(
                    layerId = activeMask.id,
                    subMask = activeSubMask,
                    activeEyedropperMode = activeEyedropperMode,
                    maskBrushRadius = maskBrushRadius,
                    maskBrushHardness = maskBrushHardness,
                    maskBrushFlow = maskBrushFlow,
                    onUpdateSubMask = onUpdateSubMask,
                    onClearBrushStrokes = onClearBrushStrokes,
                    onUpdateBrushSettings = onUpdateBrushSettings,
                    onToggleColorRangeEyedropper = onToggleColorRangeEyedropper,
                    onDraggingChanged = onDraggingChanged
                )
            }

            // 7. GPU Edge-Aware Guided Image Filter ("Feather / Edge Snap" 0–100)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(StudioSurface)
                    .border(1.dp, StudioHairline, RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                StudioSliderRow(
                    label = stringResource(R.string.mask_feather_edge_snap),
                    value = activeMask.edgeSnap,
                    valueRange = 0f..100f,
                    defaultValue = 0f,
                    valueFormatter = { v -> "${v.roundToInt()}" },
                    onValueChange = { v -> onUpdateMask(activeMask.id) { it.copy(edgeSnap = v) } },
                    onDraggingChanged = onDraggingChanged,
                    sliderTestTag = "mask_edge_snap_slider"
                )
                Text(
                    text = "GPU Guided Image Filter snaps soft boundaries to real edges without halos",
                    style = MaterialTheme.typography.labelSmall,
                    color = StudioTextMuted
                )
            }

            HorizontalDivider(color = StudioHairline)

            // 8. Selective Local Adjustments (All 8 professional controls)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "LOCAL ADJUSTMENTS",
                    style = MaterialTheme.typography.labelSmall,
                    color = StudioTextSecondary
                )
                if (activeMask.hasAdjustments) {
                    Row(
                        modifier = Modifier
                            .defaultMinSize(minHeight = 36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                onUpdateMask(activeMask.id) {
                                    it.copy(
                                        exposure = 0f,
                                        contrast = 0f,
                                        highlights = 0f,
                                        shadows = 0f,
                                        temperature = 0f,
                                        warmth = 0f,
                                        tint = 0f,
                                        saturation = 0f,
                                        sharpness = 0f,
                                        clarity = 0f
                                    )
                                }
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .testTag("mask_reset_adjustments_button"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.RestartAlt,
                            contentDescription = "Reset local adjustments",
                            tint = StudioTextSecondary,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "Reset",
                            style = MaterialTheme.typography.labelSmall,
                            color = StudioTextSecondary
                        )
                    }
                }
            }

            StudioSliderRow(
                label = "Exposure",
                value = activeMask.exposure,
                valueRange = -2f..2f,
                defaultValue = 0f,
                valueFormatter = { v -> String.format(Locale.US, "%+.2f EV", v) },
                onValueChange = { v -> onUpdateMask(activeMask.id) { it.copy(exposure = v) } },
                onDraggingChanged = onDraggingChanged,
                sliderTestTag = "mask_exposure_slider"
            )
            StudioSliderRow(
                label = "Contrast",
                value = activeMask.contrast,
                valueRange = -100f..100f,
                defaultValue = 0f,
                onValueChange = { v -> onUpdateMask(activeMask.id) { it.copy(contrast = v) } },
                onDraggingChanged = onDraggingChanged,
                sliderTestTag = "mask_contrast_slider"
            )
            StudioSliderRow(
                label = "Highlights",
                value = activeMask.highlights,
                valueRange = -100f..100f,
                defaultValue = 0f,
                onValueChange = { v -> onUpdateMask(activeMask.id) { it.copy(highlights = v) } },
                onDraggingChanged = onDraggingChanged,
                sliderTestTag = "mask_highlights_slider"
            )
            StudioSliderRow(
                label = "Shadows",
                value = activeMask.shadows,
                valueRange = -100f..100f,
                defaultValue = 0f,
                onValueChange = { v -> onUpdateMask(activeMask.id) { it.copy(shadows = v) } },
                onDraggingChanged = onDraggingChanged,
                sliderTestTag = "mask_shadows_slider"
            )
            StudioSliderRow(
                label = "Temperature",
                value = activeMask.effectiveTemperature,
                valueRange = -100f..100f,
                defaultValue = 0f,
                onValueChange = { v -> onUpdateMask(activeMask.id) { it.copy(temperature = v, warmth = 0f) } },
                onDraggingChanged = onDraggingChanged,
                sliderTestTag = "mask_temperature_slider"
            )
            StudioSliderRow(
                label = "Tint",
                value = activeMask.tint,
                valueRange = -100f..100f,
                defaultValue = 0f,
                onValueChange = { v -> onUpdateMask(activeMask.id) { it.copy(tint = v) } },
                onDraggingChanged = onDraggingChanged,
                sliderTestTag = "mask_tint_slider"
            )
            StudioSliderRow(
                label = "Saturation",
                value = activeMask.saturation,
                valueRange = -100f..100f,
                defaultValue = 0f,
                onValueChange = { v -> onUpdateMask(activeMask.id) { it.copy(saturation = v) } },
                onDraggingChanged = onDraggingChanged,
                sliderTestTag = "mask_saturation_slider"
            )
            StudioSliderRow(
                label = "Sharpness",
                value = activeMask.sharpness,
                valueRange = 0f..100f,
                defaultValue = 0f,
                onValueChange = { v -> onUpdateMask(activeMask.id) { it.copy(sharpness = v) } },
                onDraggingChanged = onDraggingChanged,
                sliderTestTag = "mask_sharpness_slider"
            )
            StudioSliderRow(
                label = "Clarity",
                value = activeMask.clarity,
                valueRange = -100f..100f,
                defaultValue = 0f,
                onValueChange = { v -> onUpdateMask(activeMask.id) { it.copy(clarity = v) } },
                onDraggingChanged = onDraggingChanged,
                sliderTestTag = "mask_clarity_slider"
            )
        }
    }
}

@Composable
private fun SubMaskControlsSection(
    layerId: String,
    subMask: MaskSubMask,
    activeEyedropperMode: EyedropperMode,
    maskBrushRadius: Float,
    maskBrushHardness: Float,
    maskBrushFlow: Float,
    onUpdateSubMask: (String, String, (MaskSubMask) -> MaskSubMask) -> Unit,
    onClearBrushStrokes: (String, String) -> Unit,
    onUpdateBrushSettings: (Float?, Float?, Float?) -> Unit,
    onToggleColorRangeEyedropper: () -> Unit,
    onDraggingChanged: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(StudioSurfaceElevated.copy(alpha = 0.7f))
            .border(1.dp, StudioHairline, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        when (subMask.type) {
            MaskType.BRUSH -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "VECTOR BRUSH (${subMask.strokes.size} strokes)",
                        style = MaterialTheme.typography.labelSmall,
                        color = StudioTextSecondary
                    )
                    if (subMask.strokes.isNotEmpty()) {
                        Text(
                            text = "Clear",
                            style = MaterialTheme.typography.labelSmall,
                            color = RubylithAccent,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { onClearBrushStrokes(layerId, subMask.id) }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                .testTag("mask_clear_brush_button")
                        )
                    }
                }
                StudioSliderRow(
                    label = "Brush Size",
                    value = maskBrushRadius * 100f,
                    valueRange = 1f..25f,
                    defaultValue = 5.5f,
                    valueFormatter = { v -> String.format(Locale.US, "%.1f%%", v) },
                    onValueChange = { v -> onUpdateBrushSettings(v / 100f, null, null) },
                    onDraggingChanged = onDraggingChanged,
                    sliderTestTag = "mask_brush_size_slider"
                )
                StudioSliderRow(
                    label = "Hardness",
                    value = maskBrushHardness * 100f,
                    valueRange = 0f..100f,
                    defaultValue = 55f,
                    valueFormatter = { v -> "${v.roundToInt()}%" },
                    onValueChange = { v -> onUpdateBrushSettings(null, v / 100f, null) },
                    onDraggingChanged = onDraggingChanged,
                    sliderTestTag = "mask_brush_hardness_slider"
                )
                StudioSliderRow(
                    label = "Flow",
                    value = maskBrushFlow * 100f,
                    valueRange = 5f..100f,
                    defaultValue = 85f,
                    valueFormatter = { v -> "${v.roundToInt()}%" },
                    onValueChange = { v -> onUpdateBrushSettings(null, null, v / 100f) },
                    onDraggingChanged = onDraggingChanged,
                    sliderTestTag = "mask_brush_flow_slider"
                )
            }

            MaskType.RADIAL -> {
                StudioSliderRow(
                    label = "Radial Feather",
                    value = subMask.feather * 100f,
                    valueRange = 0f..100f,
                    defaultValue = 50f,
                    valueFormatter = { v -> "${v.roundToInt()}%" },
                    onValueChange = { v ->
                        onUpdateSubMask(layerId, subMask.id) { it.copy(feather = (v / 100f).coerceIn(0f, 1f)) }
                    },
                    onDraggingChanged = onDraggingChanged,
                    sliderTestTag = "mask_feather_slider"
                )
                StudioSliderRow(
                    label = "Rotation",
                    value = subMask.rotationDeg,
                    valueRange = -180f..180f,
                    defaultValue = 0f,
                    valueFormatter = { v -> "${v.roundToInt()}°" },
                    onValueChange = { v ->
                        onUpdateSubMask(layerId, subMask.id) { it.copy(rotationDeg = v.coerceIn(-180f, 180f)) }
                    },
                    onDraggingChanged = onDraggingChanged,
                    sliderTestTag = "mask_rotation_slider"
                )
            }

            MaskType.LINEAR -> {
                val angleDeg = Math.toDegrees(
                    atan2((subMask.endY - subMask.startY).toDouble(), (subMask.endX - subMask.startX).toDouble())
                ).roundToInt()
                Text(
                    text = "LINEAR GRADIENT • Drag canvas pins to move & rotate ($angleDeg°)",
                    style = MaterialTheme.typography.labelSmall,
                    color = StudioTextSecondary
                )
                StudioSliderRow(
                    label = "Gradient Feather",
                    value = subMask.feather * 100f,
                    valueRange = 5f..100f,
                    defaultValue = 55f,
                    valueFormatter = { v -> "${v.roundToInt()}%" },
                    onValueChange = { v ->
                        onUpdateSubMask(layerId, subMask.id) { it.copy(feather = (v / 100f).coerceIn(0.05f, 1f)) }
                    },
                    onDraggingChanged = onDraggingChanged,
                    sliderTestTag = "mask_linear_feather_slider"
                )
            }

            MaskType.LUMINANCE_RANGE -> {
                Text(
                    text = "LUMINANCE RANGE (REC. 709)",
                    style = MaterialTheme.typography.labelSmall,
                    color = StudioTextSecondary
                )
                // Tonal band visualizer bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color(0xFF0A0A0A), Color(0xFF7D7D7D), Color(0xFFFAFAFA))
                            )
                        )
                        .border(1.dp, StudioHairline, RoundedCornerShape(5.dp))
                )
                StudioSliderRow(
                    label = "Min Luminance",
                    value = subMask.lumMin,
                    valueRange = 0f..1f,
                    defaultValue = 0.20f,
                    valueFormatter = { v -> String.format(Locale.US, "%.2f", v) },
                    onValueChange = { v ->
                        onUpdateSubMask(layerId, subMask.id) { cur ->
                            val lo = v.coerceIn(0f, 1f)
                            cur.copy(lumMin = lo, lumMax = maxOf(cur.lumMax, lo))
                        }
                    },
                    onDraggingChanged = onDraggingChanged,
                    sliderTestTag = "mask_luma_min_slider"
                )
                StudioSliderRow(
                    label = "Max Luminance",
                    value = subMask.lumMax,
                    valueRange = 0f..1f,
                    defaultValue = 0.85f,
                    valueFormatter = { v -> String.format(Locale.US, "%.2f", v) },
                    onValueChange = { v ->
                        onUpdateSubMask(layerId, subMask.id) { cur ->
                            val hi = v.coerceIn(0f, 1f)
                            cur.copy(lumMax = hi, lumMin = minOf(cur.lumMin, hi))
                        }
                    },
                    onDraggingChanged = onDraggingChanged,
                    sliderTestTag = "mask_luma_max_slider"
                )
                StudioSliderRow(
                    label = "Falloff",
                    value = subMask.lumFalloff,
                    valueRange = 0.01f..0.50f,
                    defaultValue = 0.15f,
                    valueFormatter = { v -> String.format(Locale.US, "%.2f", v) },
                    onValueChange = { v ->
                        onUpdateSubMask(layerId, subMask.id) { it.copy(lumFalloff = v.coerceIn(0.01f, 0.50f)) }
                    },
                    onDraggingChanged = onDraggingChanged,
                    sliderTestTag = "mask_luma_falloff_slider"
                )
            }

            MaskType.COLOR_RANGE -> {
                val isPicking = activeEyedropperMode == EyedropperMode.MASK_COLOR_RANGE
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val swatchColor = if (subMask.colorSampled) {
                        Color(subMask.targetR, subMask.targetG, subMask.targetB)
                    } else {
                        StudioSurface
                    }
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(swatchColor)
                            .border(1.5.dp, if (subMask.colorSampled) Color.White else StudioBorder, CircleShape)
                    )
                    val cd = stringResource(R.string.cd_mask_color_eyedropper)
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isPicking) StudioCyan.copy(alpha = 0.22f) else StudioSurface)
                            .border(
                                1.dp,
                                if (isPicking) StudioCyan else StudioBorder,
                                RoundedCornerShape(10.dp)
                            )
                            .semantics { contentDescription = cd }
                            .clickable(onClick = onToggleColorRangeEyedropper)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .testTag("mask_color_eyedropper_button"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Colorize,
                            contentDescription = null,
                            tint = if (isPicking) StudioCyan else StudioTextPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isPicking) "Tap Photo to Sample…"
                            else if (subMask.colorSampled) "Resample Color (CIELAB)"
                            else stringResource(R.string.mask_sample_color),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isPicking) StudioCyan else StudioTextPrimary
                        )
                    }
                }
                StudioSliderRow(
                    label = "Tolerance (ΔE)",
                    value = subMask.colorTolerance,
                    valueRange = 2f..100f,
                    defaultValue = 26f,
                    valueFormatter = { v -> "${v.roundToInt()}" },
                    onValueChange = { v ->
                        onUpdateSubMask(layerId, subMask.id) { it.copy(colorTolerance = v.coerceIn(2f, 100f)) }
                    },
                    onDraggingChanged = onDraggingChanged,
                    sliderTestTag = "mask_color_tolerance_slider"
                )
                StudioSliderRow(
                    label = "Smoothness",
                    value = subMask.colorSmoothness * 100f,
                    valueRange = 2f..100f,
                    defaultValue = 35f,
                    valueFormatter = { v -> "${v.roundToInt()}%" },
                    onValueChange = { v ->
                        onUpdateSubMask(layerId, subMask.id) { it.copy(colorSmoothness = (v / 100f).coerceIn(0.02f, 1f)) }
                    },
                    onDraggingChanged = onDraggingChanged,
                    sliderTestTag = "mask_color_smoothness_slider"
                )
            }
        }
    }
}

@Composable
private fun RubylithOverlayTogglePill(
    active: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(10.dp)
    val cd = stringResource(R.string.cd_mask_overlay_toggle)
    Row(
        modifier = Modifier
            .defaultMinSize(minHeight = 48.dp)
            .clip(shape)
            .background(if (active) RubylithAccent.copy(alpha = 0.22f) else StudioSurface)
            .border(1.dp, if (active) RubylithAccent else StudioHairline, shape)
            .semantics { contentDescription = cd }
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag("mask_overlay_toggle_button"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (active) RubylithAccent else StudioTextMuted)
        )
        Text(
            text = if (active) "Overlay ON (O)" else "Overlay (O)",
            style = MaterialTheme.typography.labelSmall,
            color = if (active) Color(0xFFFFB4B4) else StudioTextSecondary,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun MaskToolPill(
    icon: ImageVector,
    label: String,
    accent: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .defaultMinSize(minHeight = 48.dp)
            .clip(shape)
            .background(if (enabled) StudioSurfaceElevated else StudioSurface)
            .border(1.dp, if (enabled) accent.copy(alpha = 0.45f) else StudioHairline, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 8.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) accent else StudioTextMuted,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) StudioTextPrimary else StudioTextMuted,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun MaskActionChip(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String
) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .clip(shape)
            .background(if (selected) accent.copy(alpha = 0.16f) else StudioSurface)
            .border(1.dp, if (selected) accent else StudioHairline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) accent else StudioTextSecondary,
            modifier = Modifier.size(15.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) StudioTextPrimary else StudioTextSecondary
        )
    }
}

@Composable
private fun EmptyMaskStudioCard() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(StudioSurface)
            .border(1.dp, StudioHairline, RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "GPU Edge-Aware Masking Ready",
                style = MaterialTheme.typography.labelLarge,
                color = StudioTextPrimary
            )
            Text(
                text = "Combine Vector Brush, Rotatable Radial/Linear gradients, Rec. 709 Luminance Range, and CIELAB Color Range with Add, Subtract, and Intersect.",
                style = MaterialTheme.typography.bodySmall,
                color = StudioTextSecondary
            )
        }
    }
}

private fun iconForMaskType(type: MaskType): ImageVector = when (type) {
    MaskType.BRUSH -> Icons.Default.Brush
    MaskType.RADIAL -> Icons.Default.RadioButtonUnchecked
    MaskType.LINEAR -> Icons.Default.Gradient
    MaskType.LUMINANCE_RANGE -> Icons.Default.Contrast
    MaskType.COLOR_RANGE -> Icons.Default.Colorize
}

private fun accentForMaskType(type: MaskType): Color = when (type) {
    MaskType.BRUSH -> RubylithAccent
    MaskType.RADIAL -> StudioAmber
    MaskType.LINEAR -> StudioCyan
    MaskType.LUMINANCE_RANGE -> StudioLuxuryGold
    MaskType.COLOR_RANGE -> Color(0xFF9C7CFF)
}

private fun colorForBooleanOp(op: MaskBooleanOp): Color = when (op) {
    MaskBooleanOp.ADD -> StudioCyan
    MaskBooleanOp.SUBTRACT -> RubylithAccent
    MaskBooleanOp.INTERSECT -> StudioLuxuryGold
}

@Composable
private fun StudioSliderRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    defaultValue: Float = 0f,
    valueFormatter: (Float) -> String = { v ->
        val rounded = v.roundToInt()
        if (rounded > 0) "+$rounded" else "$rounded"
    },
    onValueChange: (Float) -> Unit,
    onDraggingChanged: (Boolean) -> Unit,
    sliderTestTag: String
) {
    val isModified = abs(value - defaultValue) > 0.004f
    val resetAction: () -> Unit = {
        onDraggingChanged(true)
        onValueChange(defaultValue)
        onDraggingChanged(false)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .doubleTapToReset(onDoubleTapReset = resetAction)
                .padding(vertical = 1.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = if (isModified) StudioTextPrimary else StudioTextPrimary.copy(alpha = 0.88f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (isModified) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(StudioCinemaAmber)
                    )
                }
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (isModified) StudioAmberPillFill else StudioGraphite,
                modifier = Modifier.border(
                    width = 1.dp,
                    color = if (isModified) StudioCinemaAmber.copy(alpha = 0.72f) else StudioHairline,
                    shape = RoundedCornerShape(8.dp)
                )
            ) {
                Text(
                    text = valueFormatter(value),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontFeatureSettings = "tnum"
                    ),
                    color = if (isModified) StudioTextPrimary else StudioTextSecondary,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
        StudioPrecisionTrack(
            value = value,
            valueRange = valueRange,
            accentColor = StudioCinemaAmber,
            compact = true,
            testTag = sliderTestTag,
            onValueChange = { next ->
                onDraggingChanged(true)
                onValueChange(next)
            },
            onValueChangeFinished = {
                onDraggingChanged(false)
            },
            onReset = resetAction
        )
    }
}
