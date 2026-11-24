package com.zamalfajji.zamlab.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Rotate90DegreesCcw
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Transform
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zamalfajji.zamlab.R
import com.zamalfajji.zamlab.editor.CompositionGridMode
import com.zamalfajji.zamlab.editor.CropAspectPreset
import com.zamalfajji.zamlab.editor.CropGeometryParams
import com.zamalfajji.zamlab.editor.CropOverlayMode
import com.zamalfajji.zamlab.editor.SpiralOrientation
import com.zamalfajji.zamlab.editor.toCompositionGridMode
import com.zamalfajji.zamlab.ui.theme.StudioActiveOffWhite
import com.zamalfajji.zamlab.ui.theme.StudioAmber
import com.zamalfajji.zamlab.ui.theme.StudioAmberPillFill
import com.zamalfajji.zamlab.ui.theme.StudioBorder
import com.zamalfajji.zamlab.ui.theme.StudioBorderSubtle
import com.zamalfajji.zamlab.ui.theme.StudioCinemaAmber
import com.zamalfajji.zamlab.ui.theme.StudioCyan
import com.zamalfajji.zamlab.ui.theme.StudioGlassHairline
import com.zamalfajji.zamlab.ui.theme.StudioGlassSurface
import com.zamalfajji.zamlab.ui.theme.StudioGraphite
import com.zamalfajji.zamlab.ui.theme.StudioHairline
import com.zamalfajji.zamlab.ui.theme.StudioObsidian
import com.zamalfajji.zamlab.ui.theme.StudioSurface
import com.zamalfajji.zamlab.ui.theme.StudioSurfaceElevated
import com.zamalfajji.zamlab.ui.theme.StudioSurfaceHighest
import com.zamalfajji.zamlab.ui.theme.StudioTextMuted
import com.zamalfajji.zamlab.ui.theme.StudioTextPrimary
import com.zamalfajji.zamlab.ui.theme.StudioTextSecondary
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun CropPanel(
    crop: CropGeometryParams,
    overlayMode: CropOverlayMode,
    compositionGridMode: CompositionGridMode = overlayMode.toCompositionGridMode(),
    spiralOrientation: SpiralOrientation = SpiralOrientation.BOTTOM_RIGHT,
    onOverlayModeChange: (CropOverlayMode) -> Unit,
    onSelectCompositionGrid: (CompositionGridMode) -> Unit = { onOverlayModeChange(it.toCropOverlayMode()) },
    onFlipSpiralOrientation: () -> Unit = {},
    onAspectPresetSelect: (CropAspectPreset) -> Unit,
    onToggleAspectLock: () -> Unit,
    onSwapAspect: () -> Unit,
    onRotateLeft: () -> Unit,
    onRotateRight: () -> Unit,
    onFlipHorizontal: () -> Unit,
    onFlipVertical: () -> Unit,
    onAutoStraighten: () -> Unit,
    onToggleConstrainCrop: () -> Unit,
    onCropChange: ((CropGeometryParams) -> CropGeometryParams) -> Unit,
    onDraggingChanged: (Boolean) -> Unit,
    onApplyCrop: () -> Unit,
    onResetAllCrop: () -> Unit,
    onResetCropOrientation: () -> Unit,
    onResetGeometry: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isCropExpanded by remember { mutableStateOf(true) }
    var isGeometryExpanded by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        // Prominent Pinned Action Bar: Reset Crop (frosted glass) + Apply Crop (crisp off-white pill)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val canResetCrop = !crop.isDefault
            Surface(
                shape = RoundedCornerShape(50),
                color = StudioGlassSurface,
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    .border(1.dp, StudioGlassHairline, RoundedCornerShape(50))
                    .clip(RoundedCornerShape(50))
                    .clickable(enabled = canResetCrop, onClick = onResetAllCrop)
                    .testTag("reset_crop_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.RestartAlt,
                        contentDescription = stringResource(R.string.cd_reset_crop),
                        tint = if (canResetCrop) StudioActiveOffWhite else StudioTextMuted.copy(alpha = 0.5f),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.action_reset_crop),
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 0.2.sp
                        ),
                        color = if (canResetCrop) StudioActiveOffWhite else StudioTextMuted.copy(alpha = 0.5f),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Button(
                onClick = onApplyCrop,
                colors = ButtonDefaults.buttonColors(
                    containerColor = StudioActiveOffWhite,
                    contentColor = StudioObsidian
                ),
                shape = RoundedCornerShape(50),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                modifier = Modifier
                    .weight(1.25f)
                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    .testTag("apply_crop_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = stringResource(R.string.cd_apply_crop),
                    modifier = Modifier.size(17.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.action_apply_crop),
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.2.sp
                    ),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        HorizontalDivider(color = StudioGlassHairline, thickness = 1.dp)

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Unified Composition Grid & Cinema Safe-Zone selector bar (with 4-corner Spiral flip)
            CropCompositionGridBar(
                selectedMode = compositionGridMode,
                spiralOrientation = spiralOrientation,
                onSelectMode = onSelectCompositionGrid,
                onFlipSpiral = onFlipSpiralOrientation
            )
            // 1. CROP & ORIENTATION SUB-HEADER
            CropAccordionSection(
                title = "Crop & Orientation",
                icon = Icons.Default.Crop,
                isExpanded = isCropExpanded,
                hasActiveEdits = crop.hasCropOrOrientationEdits ||
                    crop.aspectPreset != CropAspectPreset.ORIGINAL ||
                    crop.aspectSwapped,
                accentColor = StudioAmber,
                testTag = "accordion_crop_orientation",
                onToggle = { isCropExpanded = !isCropExpanded },
                onResetSection = onResetCropOrientation
            ) {
                // Aspect Ratio selector + Lock + Swap
                AspectRatioControlsRow(
                    aspectPreset = crop.aspectPreset,
                    aspectLocked = crop.aspectLocked,
                    aspectSwapped = crop.aspectSwapped,
                    onSelectPreset = onAspectPresetSelect,
                    onToggleLock = onToggleAspectLock,
                    onSwapAspect = onSwapAspect
                )

                // Quick Transform Actions: Rotate Left, Rotate Right, Flip H, Flip V, Reset
                TransformActionsRow(
                    flipH = crop.flipH,
                    flipV = crop.flipV,
                    hasAnyOrientationOrCrop = crop.hasCropOrOrientationEdits ||
                        crop.aspectPreset != CropAspectPreset.ORIGINAL ||
                        crop.aspectSwapped,
                    onRotateLeft = onRotateLeft,
                    onRotateRight = onRotateRight,
                    onFlipH = onFlipHorizontal,
                    onFlipV = onFlipVertical,
                    onReset = onResetCropOrientation
                )

                // Straighten slider (-45.0° .. +45.0°) + Auto Straighten button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        CropPrecisionSlider(
                            label = "Straighten",
                            icon = Icons.Default.Straighten,
                            value = crop.straightenDeg,
                            valueRange = -45f..45f,
                            step = 0.1f,
                            formattedValue = formatSignedDegrees(crop.straightenDeg, 2),
                            isModified = abs(crop.straightenDeg) > 0.01f,
                            accentColor = StudioAmber,
                            testTag = "slider_straighten",
                            onValueChange = { v ->
                                onDraggingChanged(true)
                                onCropChange { it.copy(straightenDeg = v) }
                            },
                            onValueChangeFinished = { onDraggingChanged(false) },
                            onReset = {
                                onDraggingChanged(false)
                                onCropChange { it.copy(straightenDeg = 0f) }
                            }
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = StudioCyan.copy(alpha = 0.14f),
                        modifier = Modifier
                            .padding(bottom = 2.dp)
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .border(1.dp, StudioCyan.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onAutoStraighten)
                            .testTag("crop_auto_straighten")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoFixHigh,
                                contentDescription = "Auto Straighten",
                                tint = StudioCyan,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = "Auto",
                                style = MaterialTheme.typography.labelSmall,
                                color = StudioCyan,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }

            // 2. GEOMETRY & PERSPECTIVE SUB-HEADER
            CropAccordionSection(
                title = "Geometry & Perspective",
                icon = Icons.Default.Transform,
                isExpanded = isGeometryExpanded,
                hasActiveEdits = crop.hasGeometryEdits || !crop.constrainCrop,
                accentColor = StudioCyan,
                testTag = "accordion_geometry_perspective",
                onToggle = { isGeometryExpanded = !isGeometryExpanded },
                onResetSection = onResetGeometry
            ) {
                // Constrain Crop checkbox row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(StudioSurface)
                        .border(1.dp, StudioBorderSubtle, RoundedCornerShape(8.dp))
                        .clickable(onClick = onToggleConstrainCrop)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                        .testTag("checkbox_constrain_crop"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Checkbox(
                            checked = crop.constrainCrop,
                            onCheckedChange = { onToggleConstrainCrop() },
                            colors = CheckboxDefaults.colors(
                                checkedColor = StudioCyan,
                                uncheckedColor = StudioTextSecondary,
                                checkmarkColor = StudioSurface
                            )
                        )
                        Text(
                            text = "Constrain Crop",
                            style = MaterialTheme.typography.titleSmall,
                            color = if (crop.constrainCrop) StudioTextPrimary else StudioTextSecondary,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = if (crop.constrainCrop) "Auto-trim" else "Show edges",
                        style = MaterialTheme.typography.labelSmall,
                        color = StudioTextMuted,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(end = 6.dp)
                    )
                }

                CropPrecisionSlider(
                    label = "Vertical",
                    icon = Icons.Default.Transform,
                    value = crop.verticalPerspective,
                    valueRange = -100f..100f,
                    formattedValue = String.format(Locale.US, "%+d", crop.verticalPerspective.roundToInt()),
                    isModified = abs(crop.verticalPerspective) > 0.1f,
                    accentColor = StudioCyan,
                    testTag = "slider_geom_vertical",
                    onValueChange = { v ->
                        onDraggingChanged(true)
                        onCropChange { it.copy(verticalPerspective = v) }
                    },
                    onValueChangeFinished = { onDraggingChanged(false) },
                    onReset = {
                        onDraggingChanged(false)
                        onCropChange { it.copy(verticalPerspective = 0f) }
                    }
                )

                CropPrecisionSlider(
                    label = "Horizontal",
                    icon = Icons.Default.Transform,
                    value = crop.horizontalPerspective,
                    valueRange = -100f..100f,
                    formattedValue = String.format(Locale.US, "%+d", crop.horizontalPerspective.roundToInt()),
                    isModified = abs(crop.horizontalPerspective) > 0.1f,
                    accentColor = StudioCyan,
                    testTag = "slider_geom_horizontal",
                    onValueChange = { v ->
                        onDraggingChanged(true)
                        onCropChange { it.copy(horizontalPerspective = v) }
                    },
                    onValueChangeFinished = { onDraggingChanged(false) },
                    onReset = {
                        onDraggingChanged(false)
                        onCropChange { it.copy(horizontalPerspective = 0f) }
                    }
                )

                CropPrecisionSlider(
                    label = "Rotate",
                    icon = Icons.Default.Rotate90DegreesCcw,
                    value = crop.geometryRotateDeg,
                    valueRange = -180f..180f,
                    step = 0.5f,
                    formattedValue = formatSignedDegrees(crop.geometryRotateDeg, 1),
                    isModified = abs(crop.geometryRotateDeg) > 0.05f,
                    accentColor = StudioCyan,
                    testTag = "slider_geom_rotate",
                    onValueChange = { v ->
                        onDraggingChanged(true)
                        onCropChange { it.copy(geometryRotateDeg = v) }
                    },
                    onValueChangeFinished = { onDraggingChanged(false) },
                    onReset = {
                        onDraggingChanged(false)
                        onCropChange { it.copy(geometryRotateDeg = 0f) }
                    }
                )

                CropPrecisionSlider(
                    label = "Aspect",
                    icon = Icons.Default.AspectRatio,
                    value = crop.aspectStretch,
                    valueRange = -100f..100f,
                    formattedValue = String.format(Locale.US, "%+d", crop.aspectStretch.roundToInt()),
                    isModified = abs(crop.aspectStretch) > 0.1f,
                    accentColor = StudioCyan,
                    testTag = "slider_geom_aspect",
                    onValueChange = { v ->
                        onDraggingChanged(true)
                        onCropChange { it.copy(aspectStretch = v) }
                    },
                    onValueChangeFinished = { onDraggingChanged(false) },
                    onReset = {
                        onDraggingChanged(false)
                        onCropChange { it.copy(aspectStretch = 0f) }
                    }
                )

                CropPrecisionSlider(
                    label = "Scale",
                    icon = Icons.Default.ZoomOutMap,
                    value = crop.geometryScale,
                    valueRange = 50f..200f,
                    formattedValue = "${crop.geometryScale.roundToInt()}%",
                    isModified = abs(crop.geometryScale - 100f) > 0.2f,
                    accentColor = StudioCyan,
                    testTag = "slider_geom_scale",
                    onValueChange = { v ->
                        onDraggingChanged(true)
                        onCropChange { it.copy(geometryScale = v) }
                    },
                    onValueChangeFinished = { onDraggingChanged(false) },
                    onReset = {
                        onDraggingChanged(false)
                        onCropChange { it.copy(geometryScale = 100f) }
                    }
                )

                CropPrecisionSlider(
                    label = "Offset X",
                    icon = Icons.Default.OpenWith,
                    value = crop.offsetX,
                    valueRange = -100f..100f,
                    formattedValue = formatSignedFloat(crop.offsetX),
                    isModified = abs(crop.offsetX) > 0.1f,
                    accentColor = StudioCyan,
                    testTag = "slider_geom_offset_x",
                    onValueChange = { v ->
                        onDraggingChanged(true)
                        onCropChange { it.copy(offsetX = v) }
                    },
                    onValueChangeFinished = { onDraggingChanged(false) },
                    onReset = {
                        onDraggingChanged(false)
                        onCropChange { it.copy(offsetX = 0f) }
                    }
                )

                CropPrecisionSlider(
                    label = "Offset Y",
                    icon = Icons.Default.OpenWith,
                    value = crop.offsetY,
                    valueRange = -100f..100f,
                    formattedValue = formatSignedFloat(crop.offsetY),
                    isModified = abs(crop.offsetY) > 0.1f,
                    accentColor = StudioCyan,
                    testTag = "slider_geom_offset_y",
                    onValueChange = { v ->
                        onDraggingChanged(true)
                        onCropChange { it.copy(offsetY = v) }
                    },
                    onValueChangeFinished = { onDraggingChanged(false) },
                    onReset = {
                        onDraggingChanged(false)
                        onCropChange { it.copy(offsetY = 0f) }
                    }
                )
            }
        }
    }
}

@Composable
private fun CropOverlaySelectorBar(
    selectedMode: CropOverlayMode,
    onSelectMode: (CropOverlayMode) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(StudioGraphite)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.GridOn,
            contentDescription = "Composition Overlay",
            tint = StudioTextSecondary,
            modifier = Modifier
                .padding(start = 2.dp, end = 2.dp)
                .size(16.dp)
        )
        CropOverlayMode.entries.forEach { mode ->
            val selected = mode == selectedMode
            Box(
                modifier = Modifier
                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onSelectMode(mode) }
                    .testTag("overlay_${mode.name.lowercase()}"),
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
                        text = mode.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) StudioAmber else StudioTextPrimary,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AspectRatioControlsRow(
    aspectPreset: CropAspectPreset,
    aspectLocked: Boolean,
    aspectSwapped: Boolean,
    onSelectPreset: (CropAspectPreset) -> Unit,
    onToggleLock: () -> Unit,
    onSwapAspect: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Aspect Ratio Dropdown Selector
        Box(modifier = Modifier.weight(1f)) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = StudioSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .border(1.dp, StudioBorderSubtle, RoundedCornerShape(8.dp))
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { menuExpanded = true }
                    .testTag("crop_aspect_dropdown")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AspectRatio,
                            contentDescription = "Aspect Ratio",
                            tint = StudioAmber,
                            modifier = Modifier.size(16.dp)
                        )
                        val displayLabel = if (aspectSwapped &&
                            aspectPreset.ratioWidth != null &&
                            aspectPreset.ratioHeight != null &&
                            aspectPreset.ratioWidth != aspectPreset.ratioHeight
                        ) {
                            "${aspectPreset.ratioHeight.toInt()}:${aspectPreset.ratioWidth.toInt()}"
                        } else {
                            aspectPreset.label
                        }
                        Text(
                            text = "Aspect: $displayLabel",
                            style = MaterialTheme.typography.labelMedium,
                            color = StudioTextPrimary,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Select Aspect Ratio",
                        tint = StudioTextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                modifier = Modifier.background(StudioSurfaceElevated)
            ) {
                CropAspectPreset.entries.forEach { preset ->
                    val isSelected = preset == aspectPreset
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = preset.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isSelected) StudioAmber else StudioTextPrimary
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onSelectPreset(preset)
                        },
                        modifier = Modifier.testTag("aspect_option_${preset.name.lowercase()}")
                    )
                }
            }
        }

        // Aspect Lock Toggle
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = if (aspectLocked) StudioAmber.copy(alpha = 0.16f) else StudioSurface,
            modifier = Modifier
                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                .border(
                    width = 1.dp,
                    color = if (aspectLocked) StudioAmber.copy(alpha = 0.65f) else StudioBorderSubtle,
                    shape = RoundedCornerShape(8.dp)
                )
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onToggleLock)
                .testTag("crop_lock_toggle")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = if (aspectLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                    contentDescription = if (aspectLocked) "Unlock aspect ratio" else "Lock aspect ratio",
                    tint = if (aspectLocked) StudioAmber else StudioTextSecondary,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = if (aspectLocked) "Locked" else "Free",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (aspectLocked) StudioAmber else StudioTextSecondary,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }

        // Swap Portrait <-> Landscape Aspect Button
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = if (aspectSwapped) StudioCyan.copy(alpha = 0.16f) else StudioSurface,
            modifier = Modifier
                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                .border(
                    width = 1.dp,
                    color = if (aspectSwapped) StudioCyan.copy(alpha = 0.65f) else StudioBorderSubtle,
                    shape = RoundedCornerShape(8.dp)
                )
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onSwapAspect)
                .testTag("crop_swap_aspect")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SwapHoriz,
                    contentDescription = "Swap aspect orientation",
                    tint = if (aspectSwapped) StudioCyan else StudioTextSecondary,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "Swap",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (aspectSwapped) StudioCyan else StudioTextSecondary,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

@Composable
private fun TransformActionsRow(
    flipH: Boolean,
    flipV: Boolean,
    hasAnyOrientationOrCrop: Boolean,
    onRotateLeft: () -> Unit,
    onRotateRight: () -> Unit,
    onFlipH: () -> Unit,
    onFlipV: () -> Unit,
    onReset: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TransformActionButton(
            label = "-90°",
            icon = Icons.AutoMirrored.Filled.RotateLeft,
            active = false,
            enabled = true,
            testTag = "crop_rotate_left",
            onClick = onRotateLeft
        )
        TransformActionButton(
            label = "+90°",
            icon = Icons.AutoMirrored.Filled.RotateRight,
            active = false,
            enabled = true,
            testTag = "crop_rotate_right",
            onClick = onRotateRight
        )
        TransformActionButton(
            label = "Flip H",
            icon = Icons.Default.Flip,
            active = flipH,
            enabled = true,
            testTag = "crop_flip_h",
            onClick = onFlipH
        )
        TransformActionButton(
            label = "Flip V",
            icon = Icons.Default.Flip,
            iconRotationDeg = 90f,
            active = flipV,
            enabled = true,
            testTag = "crop_flip_v",
            onClick = onFlipV
        )
        TransformActionButton(
            label = "Reset",
            icon = Icons.Default.RestartAlt,
            active = false,
            enabled = hasAnyOrientationOrCrop,
            testTag = "crop_reset_orientation",
            onClick = onReset
        )
    }
}

@Composable
private fun TransformActionButton(
    label: String,
    icon: ImageVector,
    iconRotationDeg: Float = 0f,
    active: Boolean,
    enabled: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = when {
            active -> StudioAmber.copy(alpha = 0.18f)
            enabled -> StudioSurface
            else -> StudioSurface.copy(alpha = 0.45f)
        },
        modifier = Modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .border(
                width = 1.dp,
                color = if (active) StudioAmber else StudioBorderSubtle,
                shape = RoundedCornerShape(8.dp)
            )
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = when {
                    active -> StudioAmber
                    enabled -> StudioTextPrimary
                    else -> StudioTextMuted
                },
                modifier = Modifier
                    .size(16.dp)
                    .rotate(iconRotationDeg)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    active -> StudioAmber
                    enabled -> StudioTextPrimary
                    else -> StudioTextMuted
                },
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

@Composable
private fun CropAccordionSection(
    title: String,
    icon: ImageVector,
    isExpanded: Boolean,
    hasActiveEdits: Boolean,
    accentColor: Color,
    testTag: String,
    onToggle: () -> Unit,
    onResetSection: () -> Unit,
    content: @Composable () -> Unit
) {
    val arrowRotation by animateFloatAsState(
        targetValue = if (isExpanded) 90f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "crop_accordion_arrow"
    )

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = StudioSurfaceElevated,
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = if (isExpanded) StudioBorder else StudioBorderSubtle,
                shape = RoundedCornerShape(12.dp)
            )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag(testTag),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = if (isExpanded) "Collapse $title" else "Expand $title",
                        tint = if (isExpanded) accentColor else StudioTextSecondary,
                        modifier = Modifier
                            .size(18.dp)
                            .rotate(arrowRotation)
                    )
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (hasActiveEdits || isExpanded) accentColor else StudioTextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = StudioTextPrimary,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (hasActiveEdits) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(accentColor)
                        )
                    }
                }

                if (hasActiveEdits) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onResetSection),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.RestartAlt,
                            contentDescription = "Reset $title",
                            tint = StudioTextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
                exit = shrinkVertically(animationSpec = spring(stiffness = Spring.StiffnessMedium)) + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HorizontalDivider(color = StudioBorderSubtle, thickness = 1.dp)
                    Spacer(modifier = Modifier.height(2.dp))
                    content()
                }
            }
        }
    }
}

@Composable
private fun CropPrecisionSlider(
    label: String,
    icon: ImageVector,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    step: Float = SliderStepMath.defaultStepForRange(valueRange),
    formattedValue: String,
    isModified: Boolean,
    accentColor: Color,
    testTag: String,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    onReset: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .doubleTapToReset(onDoubleTapReset = onReset)
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 2.dp)
                    .testTag("${testTag}_label")
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isModified) StudioCinemaAmber else StudioTextPrimary.copy(alpha = 0.78f),
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = if (isModified) StudioTextPrimary else StudioTextPrimary.copy(alpha = 0.88f),
                    maxLines = 1,
                    softWrap = false,
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
                modifier = Modifier
                    .border(
                        width = 1.dp,
                        color = if (isModified) StudioCinemaAmber.copy(alpha = 0.72f) else StudioHairline,
                        shape = RoundedCornerShape(8.dp)
                    )
                    .testTag("${testTag}_value")
            ) {
                Text(
                    text = formattedValue,
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
            step = step,
            accentColor = accentColor,
            compact = true,
            testTag = testTag,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            onReset = onReset
        )
    }
}

private fun formatSignedDegrees(value: Float, decimals: Int): String {
    val clean = if (abs(value) < 0.005f) 0f else value
    return when {
        clean == 0f -> if (decimals >= 2) "0.00°" else "0.0°"
        decimals >= 2 -> String.format(Locale.US, "%+.2f°", clean)
        else -> String.format(Locale.US, "%+.1f°", clean)
    }
}

private fun formatSignedFloat(value: Float): String {
    val clean = if (abs(value) < 0.05f) 0f else value
    return if (clean == 0f) "0.0" else String.format(Locale.US, "%+.1f", clean)
}
