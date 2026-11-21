package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.InvertColors
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WbAuto
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.editor.ColorGradingData
import com.example.editor.ColorWheelValue
import com.example.editor.EyedropperMode
import com.example.editor.GradingParams
import com.example.editor.HslBand
import com.example.editor.HslBandAdjustment
import com.example.editor.HslMixerData
import com.example.editor.PointColorData
import com.example.editor.WhiteBalancePreset
import com.example.ui.theme.StudioAmber
import com.example.ui.theme.StudioBackground
import com.example.ui.theme.StudioBorder
import com.example.ui.theme.StudioBorderSubtle
import com.example.ui.theme.StudioCyan
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioSurfaceElevated
import com.example.ui.theme.StudioSurfaceHighest
import com.example.ui.theme.StudioTextMuted
import com.example.ui.theme.StudioTextPrimary
import com.example.ui.theme.StudioTextSecondary
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

private enum class ColorWheelTab(val label: String) {
    THREE_WHEELS("3-Wheels View"),
    SHADOWS("Shadows"),
    MIDTONES("Midtones"),
    HIGHLIGHTS("Highlights"),
    GLOBAL("Global")
}

private enum class ActiveWheelTarget(val label: String) {
    SHADOWS("Shadows"),
    MIDTONES("Midtones"),
    HIGHLIGHTS("Highlights"),
    GLOBAL("Global")
}

@Composable
fun ColorSectionContent(
    params: GradingParams,
    activeEyedropperMode: EyedropperMode,
    isHslExpanded: Boolean,
    onToggleHsl: () -> Unit,
    isPointColorExpanded: Boolean,
    onTogglePointColor: () -> Unit,
    isColorGradingExpanded: Boolean,
    onToggleColorGrading: () -> Unit,
    activeHslBand: HslBand,
    onSelectHslBand: (HslBand) -> Unit,
    onSelectWhiteBalancePreset: (WhiteBalancePreset) -> Unit,
    onToggleEyedropper: (EyedropperMode) -> Unit,
    onParamsChange: ((GradingParams) -> GradingParams) -> Unit,
    onDraggingChanged: (Boolean) -> Unit
) {
    var showWbDropdown by remember { mutableStateOf(false) }

    // 1. White Balance Dropdown + Eyedropper Button
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(modifier = Modifier.weight(1f)) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = StudioSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .border(
                        width = 1.dp,
                        color = if (params.whiteBalancePreset != WhiteBalancePreset.AS_SHOT) {
                            StudioAmber.copy(alpha = 0.6f)
                        } else {
                            StudioBorderSubtle
                        },
                        shape = RoundedCornerShape(10.dp)
                    )
                    .clickable { showWbDropdown = true }
                    .testTag("wb_preset_dropdown")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.WbAuto,
                            contentDescription = null,
                            tint = if (params.whiteBalancePreset != WhiteBalancePreset.AS_SHOT) StudioAmber else StudioTextSecondary,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = "WB:",
                            style = MaterialTheme.typography.labelMedium,
                            color = StudioTextSecondary
                        )
                        Text(
                            text = params.whiteBalancePreset.label,
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = StudioTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.cd_wb_dropdown),
                        tint = StudioTextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            DropdownMenu(
                expanded = showWbDropdown,
                onDismissRequest = { showWbDropdown = false },
                modifier = Modifier.background(StudioSurfaceElevated)
            ) {
                WhiteBalancePreset.MENU_OPTIONS.forEach { preset ->
                    val isSelected = preset == params.whiteBalancePreset
                    DropdownMenuItem(
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = preset.label,
                                    color = if (isSelected) StudioAmber else StudioTextPrimary,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                if (isSelected) {
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = StudioAmber,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        },
                        onClick = {
                            showWbDropdown = false
                            onSelectWhiteBalancePreset(preset)
                        },
                        modifier = Modifier.testTag("wb_option_${preset.name.lowercase()}")
                    )
                }
            }
        }

        val isWbPickerActive = activeEyedropperMode == EyedropperMode.WHITE_BALANCE
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = if (isWbPickerActive) StudioCyan.copy(alpha = 0.22f) else StudioSurface,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(10.dp))
                .border(
                    width = 1.dp,
                    color = if (isWbPickerActive) StudioCyan else StudioBorderSubtle,
                    shape = RoundedCornerShape(10.dp)
                )
        ) {
            IconButton(
                onClick = { onToggleEyedropper(EyedropperMode.WHITE_BALANCE) },
                modifier = Modifier.testTag("wb_eyedropper_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Colorize,
                    contentDescription = stringResource(R.string.cd_wb_eyedropper),
                    tint = if (isWbPickerActive) StudioCyan else StudioTextPrimary,
                    modifier = Modifier.size(19.dp)
                )
            }
        }
    }

    // 2. Core Color Sliders with Lightroom-style Gradient Tracks
    GradientRangeSlider(
        label = "Temp",
        icon = Icons.Default.Thermostat,
        value = params.temperature,
        valueRange = -100f..100f,
        formattedValue = String.format(Locale.US, "%+d", params.temperature.roundToInt()),
        isModified = params.temperature != 0f,
        accentColor = if (params.temperature < 0f) StudioCyan else StudioAmber,
        gradientColors = listOf(
            Color(0xFF2563EB),
            Color(0xFF64748B),
            Color(0xFFF59E0B)
        ),
        testTag = "slider_temperature",
        onValueChange = { v ->
            onDraggingChanged(true)
            onParamsChange {
                it.copy(
                    temperature = v,
                    whiteBalancePreset = WhiteBalancePreset.CUSTOM
                )
            }
        },
        onValueChangeFinished = { onDraggingChanged(false) },
        onReset = {
            onDraggingChanged(false)
            onParamsChange {
                val nextPreset = if (it.tint == 0f) WhiteBalancePreset.AS_SHOT else WhiteBalancePreset.CUSTOM
                it.copy(temperature = 0f, whiteBalancePreset = nextPreset)
            }
        }
    )

    GradientRangeSlider(
        label = "Tint",
        icon = Icons.Default.Tune,
        value = params.tint,
        valueRange = -100f..100f,
        formattedValue = String.format(Locale.US, "%+d", params.tint.roundToInt()),
        isModified = params.tint != 0f,
        accentColor = if (params.tint < 0f) Color(0xFF4ADE80) else Color(0xFFF472B6),
        gradientColors = listOf(
            Color(0xFF22C55E),
            Color(0xFF64748B),
            Color(0xFFEC4899)
        ),
        testTag = "slider_tint",
        onValueChange = { v ->
            onDraggingChanged(true)
            onParamsChange {
                it.copy(
                    tint = v,
                    whiteBalancePreset = WhiteBalancePreset.CUSTOM
                )
            }
        },
        onValueChangeFinished = { onDraggingChanged(false) },
        onReset = {
            onDraggingChanged(false)
            onParamsChange {
                val nextPreset = if (it.temperature == 0f) WhiteBalancePreset.AS_SHOT else WhiteBalancePreset.CUSTOM
                it.copy(tint = 0f, whiteBalancePreset = nextPreset)
            }
        }
    )

    GradientRangeSlider(
        label = "Vibrance",
        icon = Icons.Default.InvertColors,
        value = params.vibrance,
        valueRange = -100f..100f,
        formattedValue = String.format(Locale.US, "%+d", params.vibrance.roundToInt()),
        isModified = params.vibrance != 0f,
        accentColor = StudioCyan,
        gradientColors = listOf(
            Color(0xFF475569),
            Color(0xFF38BDF8),
            Color(0xFFA855F7)
        ),
        testTag = "slider_vibrance",
        onValueChange = { v ->
            onDraggingChanged(true)
            onParamsChange { it.copy(vibrance = v) }
        },
        onValueChangeFinished = { onDraggingChanged(false) },
        onReset = {
            onDraggingChanged(false)
            onParamsChange { it.copy(vibrance = 0f) }
        }
    )

    GradientRangeSlider(
        label = "Saturation",
        icon = Icons.Default.InvertColors,
        value = params.saturation,
        valueRange = -100f..100f,
        formattedValue = String.format(Locale.US, "%+d", params.saturation.roundToInt()),
        isModified = params.saturation != 0f,
        accentColor = StudioAmber,
        gradientColors = listOf(
            Color(0xFF52525B),
            Color(0xFF94A3B8),
            Color(0xFFF59E0B),
            Color(0xFFF43F5E)
        ),
        testTag = "slider_saturation",
        onValueChange = { v ->
            onDraggingChanged(true)
            onParamsChange { it.copy(saturation = v) }
        },
        onValueChangeFinished = { onDraggingChanged(false) },
        onReset = {
            onDraggingChanged(false)
            onParamsChange { it.copy(saturation = 0f) }
        }
    )

    // 3. Three Expandable Sub-Bars Directly Under Saturation:
    // (a) Color Mixer
    ColorSubBar(
        title = "Color Mixer",
        icon = Icons.Default.Palette,
        isExpanded = isHslExpanded,
        hasActiveEdits = !params.hsl.isDefault,
        testTag = "subpanel_hsl",
        onToggle = onToggleHsl,
        onReset = {
            onDraggingChanged(false)
            onParamsChange { it.copy(hsl = HslMixerData()) }
        }
    ) {
        HslColorMixerContent(
            hsl = params.hsl,
            activeBand = activeHslBand,
            onSelectBand = onSelectHslBand,
            onBandChange = { band, transform ->
                onParamsChange { it.copy(hsl = it.hsl.withBand(band, transform)) }
            },
            onDraggingChanged = onDraggingChanged
        )
    }

    // (b) Point Color
    ColorSubBar(
        title = "Point Color",
        icon = Icons.Default.Colorize,
        isExpanded = isPointColorExpanded,
        hasActiveEdits = params.pointColor.isModified,
        testTag = "subpanel_point_color",
        onToggle = onTogglePointColor,
        onReset = {
            onDraggingChanged(false)
            onParamsChange { it.copy(pointColor = PointColorData()) }
        }
    ) {
        PointColorEditor(
            pointColor = params.pointColor,
            isEyedropperActive = activeEyedropperMode == EyedropperMode.POINT_COLOR,
            onToggleEyedropper = { onToggleEyedropper(EyedropperMode.POINT_COLOR) },
            onPointColorChange = { next ->
                onParamsChange { it.copy(pointColor = next) }
            },
            onDraggingChanged = onDraggingChanged
        )
    }

    // (c) Color Grading (3-Way Color Wheels)
    ColorSubBar(
        title = "Color Grading",
        icon = Icons.Default.Adjust,
        isExpanded = isColorGradingExpanded,
        hasActiveEdits = !params.colorGrading.isDefault,
        testTag = "subpanel_color_grading",
        onToggle = onToggleColorGrading,
        onReset = {
            onDraggingChanged(false)
            onParamsChange { it.copy(colorGrading = ColorGradingData()) }
        }
    ) {
        ColorGradingWheelsEditor(
            grading = params.colorGrading,
            onGradingChange = { next ->
                onParamsChange { it.copy(colorGrading = next) }
            },
            onDraggingChanged = onDraggingChanged
        )
    }
}

@Composable
private fun ColorSubBar(
    title: String,
    icon: ImageVector,
    isExpanded: Boolean,
    hasActiveEdits: Boolean,
    testTag: String,
    onToggle: () -> Unit,
    onReset: () -> Unit,
    content: @Composable () -> Unit
) {
    val arrowRotation by animateFloatAsState(
        targetValue = if (isExpanded) 90f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "color_subbar_arrow"
    )

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = StudioSurface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp)
            .border(
                width = 1.dp,
                color = if (isExpanded) StudioBorder else StudioBorderSubtle,
                shape = RoundedCornerShape(10.dp)
            )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .testTag(testTag),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = if (isExpanded) "Collapse $title" else "Expand $title",
                        tint = if (isExpanded) StudioAmber else StudioTextSecondary,
                        modifier = Modifier
                            .size(16.dp)
                            .rotate(arrowRotation)
                    )
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (hasActiveEdits || isExpanded) StudioCyan else StudioTextSecondary,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isExpanded || hasActiveEdits) StudioTextPrimary else StudioTextSecondary
                    )
                    if (hasActiveEdits) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(StudioAmber)
                                .testTag("${testTag}_active_dot")
                        )
                    }
                }

                if (hasActiveEdits) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onReset)
                            .testTag("${testTag}_reset"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.RestartAlt,
                            contentDescription = "Reset $title",
                            tint = StudioAmber,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
                exit = shrinkVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HorizontalDivider(color = StudioBorderSubtle, thickness = 1.dp)
                    content()
                }
            }
        }
    }
}

@Composable
private fun HslColorMixerContent(
    hsl: HslMixerData,
    activeBand: HslBand,
    onSelectBand: (HslBand) -> Unit,
    onBandChange: (HslBand, (HslBandAdjustment) -> HslBandAdjustment) -> Unit,
    onDraggingChanged: (Boolean) -> Unit
) {
    val bandAdj = hsl[activeBand]
    val bandColor = Color(activeBand.swatchArgb)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Channel: ${activeBand.label}",
                style = MaterialTheme.typography.labelMedium,
                color = bandColor
            )
            if (!bandAdj.isDefault) {
                Text(
                    text = "Reset ${activeBand.label}",
                    style = MaterialTheme.typography.labelSmall,
                    color = bandColor,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            onDraggingChanged(false)
                            onBandChange(activeBand) { HslBandAdjustment() }
                        }
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            HslBand.entries.forEach { band ->
                val selected = band == activeBand
                val modified = !hsl[band].isDefault
                val swatch = Color(band.swatchArgb)
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .clickable { onSelectBand(band) }
                        .testTag("hsl_chip_${band.name.lowercase()}"),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(if (selected) 30.dp else 24.dp)
                            .clip(CircleShape)
                            .background(swatch)
                            .border(
                                width = if (selected) 2.5.dp else 1.dp,
                                color = if (selected) Color.White else StudioBorder,
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (modified) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(Color.White)
                            )
                        }
                    }
                }
            }
        }

        val leftHueColor = remember(activeBand) {
            Color.hsv(((activeBand.centerHueDeg - 32f) + 360f) % 360f, 0.85f, 0.9f)
        }
        val rightHueColor = remember(activeBand) {
            Color.hsv((activeBand.centerHueDeg + 32f) % 360f, 0.85f, 0.9f)
        }

        GradientRangeSlider(
            label = "Hue",
            icon = Icons.Default.Palette,
            value = bandAdj.hue,
            valueRange = -100f..100f,
            formattedValue = String.format(Locale.US, "%+d", bandAdj.hue.roundToInt()),
            isModified = bandAdj.hue != 0f,
            accentColor = bandColor,
            gradientColors = listOf(leftHueColor, bandColor, rightHueColor),
            testTag = "slider_hsl_hue",
            onValueChange = { v ->
                onDraggingChanged(true)
                onBandChange(activeBand) { it.copy(hue = v) }
            },
            onValueChangeFinished = { onDraggingChanged(false) },
            onReset = {
                onDraggingChanged(false)
                onBandChange(activeBand) { it.copy(hue = 0f) }
            }
        )
        GradientRangeSlider(
            label = "Saturation",
            icon = Icons.Default.InvertColors,
            value = bandAdj.saturation,
            valueRange = -100f..100f,
            formattedValue = String.format(Locale.US, "%+d", bandAdj.saturation.roundToInt()),
            isModified = bandAdj.saturation != 0f,
            accentColor = bandColor,
            gradientColors = listOf(Color(0xFF52525B), bandColor),
            testTag = "slider_hsl_saturation",
            onValueChange = { v ->
                onDraggingChanged(true)
                onBandChange(activeBand) { it.copy(saturation = v) }
            },
            onValueChangeFinished = { onDraggingChanged(false) },
            onReset = {
                onDraggingChanged(false)
                onBandChange(activeBand) { it.copy(saturation = 0f) }
            }
        )
        GradientRangeSlider(
            label = "Luminance",
            icon = Icons.Default.Brightness6,
            value = bandAdj.luminance,
            valueRange = -100f..100f,
            formattedValue = String.format(Locale.US, "%+d", bandAdj.luminance.roundToInt()),
            isModified = bandAdj.luminance != 0f,
            accentColor = bandColor,
            gradientColors = listOf(Color(0xFF1E293B), bandColor, Color(0xFFF8FAFC)),
            testTag = "slider_hsl_luminance",
            onValueChange = { v ->
                onDraggingChanged(true)
                onBandChange(activeBand) { it.copy(luminance = v) }
            },
            onValueChangeFinished = { onDraggingChanged(false) },
            onReset = {
                onDraggingChanged(false)
                onBandChange(activeBand) { it.copy(luminance = 0f) }
            }
        )
    }
}

@Composable
private fun PointColorEditor(
    pointColor: PointColorData,
    isEyedropperActive: Boolean,
    onToggleEyedropper: () -> Unit,
    onPointColorChange: (PointColorData) -> Unit,
    onDraggingChanged: (Boolean) -> Unit
) {
    val swatchColor = Color(pointColor.swatchArgb)
    val leftShiftHue = remember(pointColor.sourceHueDeg) {
        Color.hsv(((pointColor.sourceHueDeg - 45f) + 360f) % 360f, 0.85f, 0.9f)
    }
    val rightShiftHue = remember(pointColor.sourceHueDeg) {
        Color.hsv((pointColor.sourceHueDeg + 45f) % 360f, 0.85f, 0.9f)
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (isEyedropperActive) StudioCyan.copy(alpha = 0.22f) else StudioSurfaceElevated,
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .border(
                        width = 1.dp,
                        color = if (isEyedropperActive) StudioCyan else StudioBorder,
                        shape = RoundedCornerShape(10.dp)
                    )
                    .clickable(onClick = onToggleEyedropper)
                    .testTag("point_color_eyedropper_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Colorize,
                        contentDescription = stringResource(R.string.cd_point_color_eyedropper),
                        tint = if (isEyedropperActive) StudioCyan else StudioTextPrimary,
                        modifier = Modifier.size(17.dp)
                    )
                    Text(
                        text = if (isEyedropperActive) "Picking…" else "Eyedropper",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isEyedropperActive) StudioCyan else StudioTextPrimary
                    )
                }
            }

            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(swatchColor)
                        .border(1.5.dp, Color.White.copy(alpha = 0.8f), CircleShape)
                        .testTag("point_color_swatch")
                )
                Column {
                    Text(
                        text = if (pointColor.isSampled) {
                            "Target ${pointColor.sourceHueDeg.roundToInt()}°"
                        } else {
                            "Default Red"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = StudioTextPrimary
                    )
                    Text(
                        text = if (pointColor.isSampled) "Isolated color swatch" else "Tap eyedropper to sample photo",
                        style = MaterialTheme.typography.labelSmall,
                        color = StudioTextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        GradientRangeSlider(
            label = "Hue Shift",
            icon = Icons.Default.Palette,
            value = pointColor.hueShift,
            valueRange = -100f..100f,
            formattedValue = String.format(Locale.US, "%+d", pointColor.hueShift.roundToInt()),
            isModified = pointColor.hueShift != 0f,
            accentColor = swatchColor,
            gradientColors = listOf(leftShiftHue, swatchColor, rightShiftHue),
            testTag = "slider_point_hue",
            onValueChange = { v ->
                onDraggingChanged(true)
                onPointColorChange(pointColor.copy(hueShift = v))
            },
            onValueChangeFinished = { onDraggingChanged(false) },
            onReset = {
                onDraggingChanged(false)
                onPointColorChange(pointColor.copy(hueShift = 0f))
            }
        )

        GradientRangeSlider(
            label = "Saturation Shift",
            icon = Icons.Default.InvertColors,
            value = pointColor.saturationShift,
            valueRange = -100f..100f,
            formattedValue = String.format(Locale.US, "%+d", pointColor.saturationShift.roundToInt()),
            isModified = pointColor.saturationShift != 0f,
            accentColor = swatchColor,
            gradientColors = listOf(Color(0xFF52525B), swatchColor),
            testTag = "slider_point_saturation",
            onValueChange = { v ->
                onDraggingChanged(true)
                onPointColorChange(pointColor.copy(saturationShift = v))
            },
            onValueChangeFinished = { onDraggingChanged(false) },
            onReset = {
                onDraggingChanged(false)
                onPointColorChange(pointColor.copy(saturationShift = 0f))
            }
        )

        GradientRangeSlider(
            label = "Luminance Shift",
            icon = Icons.Default.Brightness6,
            value = pointColor.luminanceShift,
            valueRange = -100f..100f,
            formattedValue = String.format(Locale.US, "%+d", pointColor.luminanceShift.roundToInt()),
            isModified = pointColor.luminanceShift != 0f,
            accentColor = swatchColor,
            gradientColors = listOf(Color(0xFF0F172A), swatchColor, Color(0xFFF8FAFC)),
            testTag = "slider_point_luminance",
            onValueChange = { v ->
                onDraggingChanged(true)
                onPointColorChange(pointColor.copy(luminanceShift = v))
            },
            onValueChangeFinished = { onDraggingChanged(false) },
            onReset = {
                onDraggingChanged(false)
                onPointColorChange(pointColor.copy(luminanceShift = 0f))
            }
        )

        GradientRangeSlider(
            label = "Range",
            icon = Icons.Default.Tune,
            value = pointColor.range,
            valueRange = 0f..100f,
            formattedValue = "${pointColor.range.roundToInt()}",
            isModified = pointColor.range != 50f,
            accentColor = StudioCyan,
            gradientColors = listOf(Color(0xFF1E293B), swatchColor.copy(alpha = 0.85f), StudioCyan),
            testTag = "slider_point_range",
            onValueChange = { v ->
                onDraggingChanged(true)
                onPointColorChange(pointColor.copy(range = v))
            },
            onValueChangeFinished = { onDraggingChanged(false) },
            onReset = {
                onDraggingChanged(false)
                onPointColorChange(pointColor.copy(range = 50f))
            }
        )
    }
}

@Composable
private fun ColorGradingWheelsEditor(
    grading: ColorGradingData,
    onGradingChange: (ColorGradingData) -> Unit,
    onDraggingChanged: (Boolean) -> Unit
) {
    var selectedTab by remember { mutableStateOf(ColorWheelTab.THREE_WHEELS) }
    var activeTarget by remember { mutableStateOf(ActiveWheelTarget.MIDTONES) }

    val wheelTarget = when (selectedTab) {
        ColorWheelTab.THREE_WHEELS -> activeTarget
        ColorWheelTab.SHADOWS -> ActiveWheelTarget.SHADOWS
        ColorWheelTab.MIDTONES -> ActiveWheelTarget.MIDTONES
        ColorWheelTab.HIGHLIGHTS -> ActiveWheelTarget.HIGHLIGHTS
        ColorWheelTab.GLOBAL -> ActiveWheelTarget.GLOBAL
    }

    val activeWheelValue = when (wheelTarget) {
        ActiveWheelTarget.SHADOWS -> grading.shadows
        ActiveWheelTarget.MIDTONES -> grading.midtones
        ActiveWheelTarget.HIGHLIGHTS -> grading.highlights
        ActiveWheelTarget.GLOBAL -> grading.global
    }

    fun updateWheel(target: ActiveWheelTarget, next: ColorWheelValue) {
        val updated = when (target) {
            ActiveWheelTarget.SHADOWS -> grading.copy(shadows = next)
            ActiveWheelTarget.MIDTONES -> grading.copy(midtones = next)
            ActiveWheelTarget.HIGHLIGHTS -> grading.copy(highlights = next)
            ActiveWheelTarget.GLOBAL -> grading.copy(global = next)
        }
        onGradingChange(updated)
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // View-Switcher Row: 3-Wheels View, Shadows, Midtones, Highlights, Global
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ColorWheelTab.entries.forEach { tab ->
                val selected = tab == selectedTab
                val hasMod = when (tab) {
                    ColorWheelTab.THREE_WHEELS ->
                        !grading.shadows.isDefault || !grading.midtones.isDefault || !grading.highlights.isDefault
                    ColorWheelTab.SHADOWS -> !grading.shadows.isDefault
                    ColorWheelTab.MIDTONES -> !grading.midtones.isDefault
                    ColorWheelTab.HIGHLIGHTS -> !grading.highlights.isDefault
                    ColorWheelTab.GLOBAL -> !grading.global.isDefault
                }
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = if (selected) StudioAmber.copy(alpha = 0.2f) else StudioSurfaceElevated,
                    modifier = Modifier
                        .defaultMinSize(minHeight = 36.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .border(
                            width = 1.dp,
                            color = if (selected) StudioAmber else StudioBorderSubtle,
                            shape = RoundedCornerShape(18.dp)
                        )
                        .clickable {
                            selectedTab = tab
                            when (tab) {
                                ColorWheelTab.SHADOWS -> activeTarget = ActiveWheelTarget.SHADOWS
                                ColorWheelTab.MIDTONES -> activeTarget = ActiveWheelTarget.MIDTONES
                                ColorWheelTab.HIGHLIGHTS -> activeTarget = ActiveWheelTarget.HIGHLIGHTS
                                ColorWheelTab.GLOBAL -> activeTarget = ActiveWheelTarget.GLOBAL
                                ColorWheelTab.THREE_WHEELS -> Unit
                            }
                        }
                        .testTag("cg_tab_${tab.name.lowercase()}")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text(
                            text = tab.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) StudioAmber else StudioTextSecondary
                        )
                        if (hasMod) {
                            Box(
                                modifier = Modifier
                                    .size(5.dp)
                                    .clip(CircleShape)
                                    .background(StudioAmber)
                            )
                        }
                    }
                }
            }
        }

        if (selectedTab == ColorWheelTab.THREE_WHEELS) {
            // LightCraft-style Triangle Layout: Midtones centered on top, Shadows bottom-left, Highlights bottom-right
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LabeledColorWheel(
                    title = "Midtones",
                    value = grading.midtones,
                    wheelSize = 108.dp,
                    isActive = activeTarget == ActiveWheelTarget.MIDTONES,
                    testTag = "cg_wheel_midtones",
                    onFocus = { activeTarget = ActiveWheelTarget.MIDTONES },
                    onEnlarge = {
                        activeTarget = ActiveWheelTarget.MIDTONES
                        selectedTab = ColorWheelTab.MIDTONES
                    },
                    onValueChange = { next ->
                        activeTarget = ActiveWheelTarget.MIDTONES
                        updateWheel(ActiveWheelTarget.MIDTONES, next)
                    },
                    onDraggingChanged = onDraggingChanged
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LabeledColorWheel(
                        title = "Shadows",
                        value = grading.shadows,
                        wheelSize = 104.dp,
                        isActive = activeTarget == ActiveWheelTarget.SHADOWS,
                        testTag = "cg_wheel_shadows",
                        onFocus = { activeTarget = ActiveWheelTarget.SHADOWS },
                        onEnlarge = {
                            activeTarget = ActiveWheelTarget.SHADOWS
                            selectedTab = ColorWheelTab.SHADOWS
                        },
                        onValueChange = { next ->
                            activeTarget = ActiveWheelTarget.SHADOWS
                            updateWheel(ActiveWheelTarget.SHADOWS, next)
                        },
                        onDraggingChanged = onDraggingChanged
                    )

                    LabeledColorWheel(
                        title = "Highlights",
                        value = grading.highlights,
                        wheelSize = 104.dp,
                        isActive = activeTarget == ActiveWheelTarget.HIGHLIGHTS,
                        testTag = "cg_wheel_highlights",
                        onFocus = { activeTarget = ActiveWheelTarget.HIGHLIGHTS },
                        onEnlarge = {
                            activeTarget = ActiveWheelTarget.HIGHLIGHTS
                            selectedTab = ColorWheelTab.HIGHLIGHTS
                        },
                        onValueChange = { next ->
                            activeTarget = ActiveWheelTarget.HIGHLIGHTS
                            updateWheel(ActiveWheelTarget.HIGHLIGHTS, next)
                        },
                        onDraggingChanged = onDraggingChanged
                    )
                }
            }
        } else {
            // Single Enlarged Color Wheel for Precision Editing
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                LabeledColorWheel(
                    title = wheelTarget.label,
                    value = activeWheelValue,
                    wheelSize = 180.dp,
                    isActive = true,
                    testTag = "cg_wheel_single_${wheelTarget.name.lowercase()}",
                    onFocus = {},
                    onEnlarge = null,
                    onValueChange = { next ->
                        updateWheel(wheelTarget, next)
                    },
                    onDraggingChanged = onDraggingChanged
                )
            }
        }

        // Active Wheel Hue/Sat status + Double-tap hint
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "${wheelTarget.label}: H ${activeWheelValue.hueDeg.roundToInt()}° • S ${activeWheelValue.saturation.roundToInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = if (activeWheelValue.isDefault) StudioTextMuted else StudioAmber
            )
            Text(
                text = "Double-tap wheel to center",
                style = MaterialTheme.typography.labelSmall,
                color = StudioTextMuted
            )
        }

        // Luminance slider for active wheel
        GradientRangeSlider(
            label = "${wheelTarget.label} Luminance",
            icon = Icons.Default.Brightness6,
            value = activeWheelValue.luminance,
            valueRange = -100f..100f,
            formattedValue = String.format(Locale.US, "%+d", activeWheelValue.luminance.roundToInt()),
            isModified = activeWheelValue.luminance != 0f,
            accentColor = StudioAmber,
            gradientColors = listOf(Color(0xFF0B0F17), Color(0xFF64748B), Color(0xFFF8FAFC)),
            testTag = "slider_cg_luminance",
            onValueChange = { v ->
                onDraggingChanged(true)
                updateWheel(wheelTarget, activeWheelValue.copy(luminance = v))
            },
            onValueChangeFinished = { onDraggingChanged(false) },
            onReset = {
                onDraggingChanged(false)
                updateWheel(wheelTarget, activeWheelValue.copy(luminance = 0f))
            }
        )

        // Blending & Balance sliders at bottom
        GradientRangeSlider(
            label = "Blending",
            icon = Icons.Default.Tune,
            value = grading.blending,
            valueRange = 0f..100f,
            formattedValue = "${grading.blending.roundToInt()}",
            isModified = grading.blending != 50f,
            accentColor = StudioCyan,
            gradientColors = listOf(Color(0xFF1E293B), StudioCyan),
            testTag = "slider_cg_blending",
            onValueChange = { v ->
                onDraggingChanged(true)
                onGradingChange(grading.copy(blending = v))
            },
            onValueChangeFinished = { onDraggingChanged(false) },
            onReset = {
                onDraggingChanged(false)
                onGradingChange(grading.copy(blending = 50f))
            }
        )

        GradientRangeSlider(
            label = "Balance",
            icon = Icons.Default.Adjust,
            value = grading.balance,
            valueRange = -100f..100f,
            formattedValue = String.format(Locale.US, "%+d", grading.balance.roundToInt()),
            isModified = grading.balance != 0f,
            accentColor = StudioAmber,
            gradientColors = listOf(Color(0xFF38BDF8), Color(0xFF64748B), Color(0xFFF59E0B)),
            testTag = "slider_cg_balance",
            onValueChange = { v ->
                onDraggingChanged(true)
                onGradingChange(grading.copy(balance = v))
            },
            onValueChangeFinished = { onDraggingChanged(false) },
            onReset = {
                onDraggingChanged(false)
                onGradingChange(grading.copy(balance = 0f))
            }
        )
    }
}

@Composable
private fun LabeledColorWheel(
    title: String,
    value: ColorWheelValue,
    wheelSize: Dp,
    isActive: Boolean,
    testTag: String,
    onFocus: () -> Unit,
    onEnlarge: (() -> Unit)?,
    onValueChange: (ColorWheelValue) -> Unit,
    onDraggingChanged: (Boolean) -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable {
                    onFocus()
                    onEnlarge?.invoke()
                }
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium
                ),
                color = if (isActive) StudioAmber else StudioTextSecondary
            )
            if (!value.isDefault) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(StudioAmber)
                )
            }
        }

        InteractiveColorWheel(
            value = value,
            size = wheelSize,
            isActive = isActive,
            testTag = testTag,
            onValueChange = onValueChange,
            onDraggingChanged = onDraggingChanged
        )
    }
}

@Composable
private fun InteractiveColorWheel(
    value: ColorWheelValue,
    size: Dp,
    isActive: Boolean,
    testTag: String,
    onValueChange: (ColorWheelValue) -> Unit,
    onDraggingChanged: (Boolean) -> Unit
) {
    val currentValue by rememberUpdatedState(value)
    val currentOnChange by rememberUpdatedState(onValueChange)
    val currentOnDrag by rememberUpdatedState(onDraggingChanged)

    val wheelBorder = if (isActive) StudioAmber else StudioBorder

    val sweepColors = remember {
        listOf(
            Color(0xFFFF3B30), // 0° Red
            Color(0xFFFFCC00), // 60° Yellow
            Color(0xFF34C759), // 120° Green
            Color(0xFF00C7BE), // 180° Cyan
            Color(0xFF007AFF), // 240° Blue
            Color(0xFFAF52DE), // 300° Magenta
            Color(0xFFFF3B30)  // 360° Red
        )
    }

    fun updateFromOffset(pos: Offset, widthPx: Float, heightPx: Float) {
        val cx = widthPx * 0.5f
        val cy = heightPx * 0.5f
        val maxRadius = (minOf(widthPx, heightPx) * 0.5f - 8f).coerceAtLeast(1f)
        val dx = pos.x - cx
        val dy = pos.y - cy
        val dist = hypot(dx, dy)
        val sat = ((dist / maxRadius) * 100f).coerceIn(0f, 100f)
        val rawDeg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        val hueDeg = ((rawDeg % 360f) + 360f) % 360f
        currentOnChange(currentValue.copy(hueDeg = hueDeg, saturation = sat))
    }

    Canvas(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .border(width = if (isActive) 1.8.dp else 1.dp, color = wheelBorder, shape = CircleShape)
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        currentOnDrag(false)
                        currentOnChange(currentValue.copy(hueDeg = 0f, saturation = 0f))
                    },
                    onTap = { offset ->
                        updateFromOffset(offset, this.size.width.toFloat(), this.size.height.toFloat())
                    }
                )
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { start ->
                        currentOnDrag(true)
                        updateFromOffset(start, this.size.width.toFloat(), this.size.height.toFloat())
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        updateFromOffset(change.position, this.size.width.toFloat(), this.size.height.toFloat())
                    },
                    onDragEnd = { currentOnDrag(false) },
                    onDragCancel = { currentOnDrag(false) }
                )
            }
            .testTag(testTag)
    ) {
        val cx = this.size.width * 0.5f
        val cy = this.size.height * 0.5f
        val radius = (minOf(this.size.width, this.size.height) * 0.5f).coerceAtLeast(1f)
        val usableRadius = (radius - 8f).coerceAtLeast(1f)

        // Subtle dark base so wheel colors feel calibrated on OLED dark UI
        drawCircle(
            brush = Brush.sweepGradient(colors = sweepColors, center = Offset(cx, cy)),
            radius = radius,
            center = Offset(cx, cy),
            alpha = 0.88f
        )

        // Radial neutral white center blend
        drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0.0f to Color(0xFFF1F5F9),
                    0.35f to Color(0x99E2E8F0),
                    1.0f to Color.Transparent
                ),
                center = Offset(cx, cy),
                radius = radius
            ),
            radius = radius,
            center = Offset(cx, cy)
        )

        // Crosshairs through center
        drawLine(
            color = Color.Black.copy(alpha = 0.22f),
            start = Offset(cx - radius * 0.82f, cy),
            end = Offset(cx + radius * 0.82f, cy),
            strokeWidth = 1.dp.toPx()
        )
        drawLine(
            color = Color.Black.copy(alpha = 0.22f),
            start = Offset(cx, cy - radius * 0.82f),
            end = Offset(cx, cy + radius * 0.82f),
            strokeWidth = 1.dp.toPx()
        )

        // Center target ring
        drawCircle(
            color = Color.Black.copy(alpha = 0.28f),
            radius = 5.dp.toPx(),
            center = Offset(cx, cy),
            style = Stroke(width = 1.dp.toPx())
        )

        // Draggable white ball handle
        val rad = Math.toRadians(value.hueDeg.toDouble())
        val normDist = (value.saturation / 100f).coerceIn(0f, 1f)
        val hx = cx + (cos(rad) * normDist * usableRadius).toFloat()
        val hy = cy + (sin(rad) * normDist * usableRadius).toFloat()

        val ballRadius = if (radius > 160f) 9.dp.toPx() else 7.dp.toPx()
        val tintedCenter = if (normDist > 0.04f) {
            Color.hsv(value.hueDeg.coerceIn(0f, 360f), normDist * 0.65f, 1f)
        } else {
            Color.White
        }

        // Drop shadow + crisp white ball + dark outer outline
        drawCircle(
            color = Color.Black.copy(alpha = 0.45f),
            radius = ballRadius + 2.dp.toPx(),
            center = Offset(hx, hy)
        )
        drawCircle(
            color = Color.White,
            radius = ballRadius,
            center = Offset(hx, hy)
        )
        drawCircle(
            color = tintedCenter,
            radius = (ballRadius - 2.5.dp.toPx()).coerceAtLeast(2f),
            center = Offset(hx, hy)
        )
    }
}

@Composable
private fun GradientRangeSlider(
    label: String,
    icon: ImageVector,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    formattedValue: String,
    isModified: Boolean,
    accentColor: Color,
    gradientColors: List<Color>,
    testTag: String,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    onReset: () -> Unit
) {
    val currentOnChange by rememberUpdatedState(onValueChange)
    val currentOnFinish by rememberUpdatedState(onValueChangeFinished)
    val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(1e-4f)
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)

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
                color = if (isModified) accentColor.copy(alpha = 0.16f) else StudioSurfaceHighest,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onReset)
                    .testTag("${testTag}_value")
            ) {
                Text(
                    text = formattedValue,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isModified) accentColor else StudioTextSecondary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .pointerInput(valueRange) {
                    detectTapGestures(
                        onDoubleTap = { onReset() },
                        onTap = { pos ->
                            val w = size.width.toFloat().coerceAtLeast(1f)
                            val pad = 10.dp.toPx()
                            val usable = (w - pad * 2f).coerceAtLeast(1f)
                            val f = ((pos.x - pad) / usable).coerceIn(0f, 1f)
                            currentOnChange(valueRange.start + f * span)
                            currentOnFinish()
                        }
                    )
                }
                .pointerInput(valueRange) {
                    detectDragGestures(
                        onDragStart = { start ->
                            val w = size.width.toFloat().coerceAtLeast(1f)
                            val pad = 10.dp.toPx()
                            val usable = (w - pad * 2f).coerceAtLeast(1f)
                            val f = ((start.x - pad) / usable).coerceIn(0f, 1f)
                            currentOnChange(valueRange.start + f * span)
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val w = size.width.toFloat().coerceAtLeast(1f)
                            val pad = 10.dp.toPx()
                            val usable = (w - pad * 2f).coerceAtLeast(1f)
                            val f = ((change.position.x - pad) / usable).coerceIn(0f, 1f)
                            currentOnChange(valueRange.start + f * span)
                        },
                        onDragEnd = { currentOnFinish() },
                        onDragCancel = { currentOnFinish() }
                    )
                }
                .testTag(testTag),
            contentAlignment = Alignment.Center
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
            ) {
                val pad = 10.dp.toPx()
                val trackW = (size.width - pad * 2f).coerceAtLeast(1f)
                val trackH = 6.dp.toPx()
                val cy = size.height * 0.5f

                // Gradient track
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        colors = gradientColors,
                        startX = pad,
                        endX = pad + trackW
                    ),
                    topLeft = Offset(pad, cy - trackH * 0.5f),
                    size = Size(trackW, trackH),
                    cornerRadius = CornerRadius(trackH * 0.5f, trackH * 0.5f)
                )

                // Zero center tick mark for symmetric bipolar sliders (-100..+100)
                if (valueRange.start < 0f && valueRange.endInclusive > 0f) {
                    val zeroF = -valueRange.start / span
                    val zx = pad + zeroF * trackW
                    drawLine(
                        color = StudioBackground.copy(alpha = 0.8f),
                        start = Offset(zx, cy - trackH * 0.85f),
                        end = Offset(zx, cy + trackH * 0.85f),
                        strokeWidth = 1.5.dp.toPx()
                    )
                }

                // Thumb indicator
                val thumbX = pad + fraction * trackW
                val thumbR = 8.5.dp.toPx()
                drawCircle(
                    color = Color.Black.copy(alpha = 0.4f),
                    radius = thumbR + 2.dp.toPx(),
                    center = Offset(thumbX, cy)
                )
                drawCircle(
                    color = Color.White,
                    radius = thumbR,
                    center = Offset(thumbX, cy)
                )
                drawCircle(
                    color = accentColor,
                    radius = (thumbR - 2.5.dp.toPx()).coerceAtLeast(2f),
                    center = Offset(thumbX, cy)
                )
            }
        }
    }
}
