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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.BlurCircular
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Deblur
import androidx.compose.material.icons.filled.Details
import androidx.compose.material.icons.filled.FilterBAndW
import androidx.compose.material.icons.filled.Flare
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.InvertColors
import androidx.compose.material.icons.filled.Lens
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Texture
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Tonality
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Vignette
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.editor.EyedropperMode
import com.example.editor.GradingParams
import com.example.editor.HistogramData
import com.example.editor.HslBand
import com.example.editor.ToneCurveChannel
import com.example.editor.ToneCurveData
import com.example.editor.ToneCurvePoint
import com.example.editor.VignetteStyle
import com.example.editor.WhiteBalancePreset
import com.example.ui.theme.StudioAmber
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
import kotlin.math.hypot
import kotlin.math.roundToInt

private enum class AdjustSection {
    LIGHT,
    COLOR,
    EFFECTS,
    DETAIL,
    OPTICS,
    LENS_BLUR,
    CALIBRATION
}

@Composable
fun AdjustPanel(
    params: GradingParams,
    histogram: HistogramData?,
    canPasteEdits: Boolean,
    hasActiveLut: Boolean,
    activeEyedropperMode: EyedropperMode,
    onParamsChange: ((GradingParams) -> GradingParams) -> Unit,
    onDraggingChanged: (Boolean) -> Unit,
    onOpenProfileMenu: () -> Unit,
    onOpenProfileBrowser: () -> Unit,
    onProfileAmountChange: (Float) -> Unit,
    onSelectWhiteBalancePreset: (WhiteBalancePreset) -> Unit,
    onToggleEyedropper: (EyedropperMode) -> Unit,
    onAutoTone: () -> Unit,
    onToggleBlackAndWhite: () -> Unit,
    onResetAll: () -> Unit,
    onCopyEdits: () -> Unit,
    onPasteEdits: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expandedSections by remember { mutableStateOf(setOf(AdjustSection.LIGHT)) }
    var isCurveExpanded by remember { mutableStateOf(false) }
    var isHslExpanded by remember { mutableStateOf(false) }
    var isPointColorExpanded by remember { mutableStateOf(false) }
    var isColorGradingExpanded by remember { mutableStateOf(false) }
    var activeCurveChannel by remember { mutableStateOf(ToneCurveChannel.MASTER) }
    var activeHslBand by remember { mutableStateOf(HslBand.RED) }

    fun toggleSection(section: AdjustSection) {
        expandedSections = if (section in expandedSections) {
            expandedSections - section
        } else {
            expandedSections + section
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // Compact Top Action Bar: Auto | B&W | Reset | Copy | Paste
        AdjustQuickActionsBar(
            isGrayscale = params.isGrayscale,
            canReset = !params.isDefault || hasActiveLut,
            canPasteEdits = canPasteEdits,
            onAutoTone = onAutoTone,
            onToggleBw = onToggleBlackAndWhite,
            onResetAll = onResetAll,
            onCopyEdits = onCopyEdits,
            onPasteEdits = onPasteEdits
        )

        HorizontalDivider(color = StudioBorderSubtle, thickness = 1.dp)

        // Profile selector bar directly below QuickActionsBar and above Light
        ProfileSelectorBar(
            profileId = params.profileId,
            profileAmount = params.profileAmount,
            onOpenMenu = onOpenProfileMenu,
            onOpenBrowser = onOpenProfileBrowser,
            onAmountChange = onProfileAmountChange,
            onDraggingChanged = onDraggingChanged
        )

        HorizontalDivider(color = StudioBorderSubtle, thickness = 1.dp)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 1. LIGHT
            AccordionSection(
                title = "Light",
                icon = Icons.Default.WbSunny,
                isExpanded = AdjustSection.LIGHT in expandedSections,
                hasActiveEdits = params.hasLightEdits,
                testTag = "accordion_light",
                onToggle = { toggleSection(AdjustSection.LIGHT) },
                onResetSection = { onParamsChange { it.resetLight() } }
            ) {
                PrecisionSlider(
                    label = "Exposure",
                    icon = Icons.Default.Brightness6,
                    value = params.exposure,
                    valueRange = -2f..2f,
                    formattedValue = String.format(Locale.US, "%+.2f EV", params.exposure),
                    isModified = params.exposure != 0f,
                    testTag = "slider_exposure",
                    onValueChange = { v ->
                        onDraggingChanged(true)
                        onParamsChange { it.copy(exposure = v) }
                    },
                    onValueChangeFinished = { onDraggingChanged(false) },
                    onReset = {
                        onDraggingChanged(false)
                        onParamsChange { it.copy(exposure = 0f) }
                    }
                )
                PrecisionSlider(
                    label = "Contrast",
                    icon = Icons.Default.Contrast,
                    value = params.contrast,
                    valueRange = -100f..100f,
                    formattedValue = String.format(Locale.US, "%+d", params.contrast.roundToInt()),
                    isModified = params.contrast != 0f,
                    testTag = "slider_contrast",
                    onValueChange = { v ->
                        onDraggingChanged(true)
                        onParamsChange { it.copy(contrast = v) }
                    },
                    onValueChangeFinished = { onDraggingChanged(false) },
                    onReset = {
                        onDraggingChanged(false)
                        onParamsChange { it.copy(contrast = 0f) }
                    }
                )
                PrecisionSlider(
                    label = "Highlights",
                    icon = Icons.Default.Flare,
                    value = params.highlights,
                    valueRange = -100f..100f,
                    formattedValue = String.format(Locale.US, "%+d", params.highlights.roundToInt()),
                    isModified = params.highlights != 0f,
                    testTag = "slider_highlights",
                    onValueChange = { v ->
                        onDraggingChanged(true)
                        onParamsChange { it.copy(highlights = v) }
                    },
                    onValueChangeFinished = { onDraggingChanged(false) },
                    onReset = {
                        onDraggingChanged(false)
                        onParamsChange { it.copy(highlights = 0f) }
                    }
                )
                PrecisionSlider(
                    label = "Shadows",
                    icon = Icons.Default.Tonality,
                    value = params.shadows,
                    valueRange = -100f..100f,
                    formattedValue = String.format(Locale.US, "%+d", params.shadows.roundToInt()),
                    isModified = params.shadows != 0f,
                    testTag = "slider_shadows",
                    onValueChange = { v ->
                        onDraggingChanged(true)
                        onParamsChange { it.copy(shadows = v) }
                    },
                    onValueChangeFinished = { onDraggingChanged(false) },
                    onReset = {
                        onDraggingChanged(false)
                        onParamsChange { it.copy(shadows = 0f) }
                    }
                )
                PrecisionSlider(
                    label = "Whites",
                    icon = Icons.Default.WbSunny,
                    value = params.whites,
                    valueRange = -100f..100f,
                    formattedValue = String.format(Locale.US, "%+d", params.whites.roundToInt()),
                    isModified = params.whites != 0f,
                    testTag = "slider_whites",
                    onValueChange = { v ->
                        onDraggingChanged(true)
                        onParamsChange { it.copy(whites = v) }
                    },
                    onValueChangeFinished = { onDraggingChanged(false) },
                    onReset = {
                        onDraggingChanged(false)
                        onParamsChange { it.copy(whites = 0f) }
                    }
                )
                PrecisionSlider(
                    label = "Blacks",
                    icon = Icons.Default.Contrast,
                    value = params.blacks,
                    valueRange = -100f..100f,
                    formattedValue = String.format(Locale.US, "%+d", params.blacks.roundToInt()),
                    isModified = params.blacks != 0f,
                    testTag = "slider_blacks",
                    onValueChange = { v ->
                        onDraggingChanged(true)
                        onParamsChange { it.copy(blacks = v) }
                    },
                    onValueChangeFinished = { onDraggingChanged(false) },
                    onReset = {
                        onDraggingChanged(false)
                        onParamsChange { it.copy(blacks = 0f) }
                    }
                )

                // Collapsible Curve sub-panel directly below Blacks
                CollapsibleSubPanel(
                    title = "Curve",
                    icon = Icons.AutoMirrored.Filled.ShowChart,
                    isExpanded = isCurveExpanded,
                    hasActiveEdits = !params.toneCurve.isDefault,
                    testTag = "subpanel_curve",
                    onToggle = { isCurveExpanded = !isCurveExpanded },
                    onReset = {
                        onDraggingChanged(false)
                        onParamsChange { it.copy(toneCurve = ToneCurveData()) }
                    }
                ) {
                    ToneCurveEditor(
                        toneCurve = params.toneCurve,
                        histogram = histogram,
                        activeChannel = activeCurveChannel,
                        onSelectChannel = { activeCurveChannel = it },
                        onPointsChange = { channel, nextPts ->
                            onParamsChange { it.copy(toneCurve = it.toneCurve.withChannel(channel, nextPts)) }
                        },
                        onDraggingChanged = onDraggingChanged,
                        onResetChannel = { channel ->
                            onDraggingChanged(false)
                            onParamsChange { it.copy(toneCurve = it.toneCurve.resetChannel(channel)) }
                        }
                    )
                }
            }

            // 2. COLOR
            AccordionSection(
                title = "Color",
                icon = Icons.Default.Palette,
                isExpanded = AdjustSection.COLOR in expandedSections,
                hasActiveEdits = params.hasColorEdits,
                testTag = "accordion_color",
                onToggle = { toggleSection(AdjustSection.COLOR) },
                onResetSection = { onParamsChange { it.resetColor() } }
            ) {
                ColorSectionContent(
                    params = params,
                    activeEyedropperMode = activeEyedropperMode,
                    isHslExpanded = isHslExpanded,
                    onToggleHsl = { isHslExpanded = !isHslExpanded },
                    isPointColorExpanded = isPointColorExpanded,
                    onTogglePointColor = { isPointColorExpanded = !isPointColorExpanded },
                    isColorGradingExpanded = isColorGradingExpanded,
                    onToggleColorGrading = { isColorGradingExpanded = !isColorGradingExpanded },
                    activeHslBand = activeHslBand,
                    onSelectHslBand = { activeHslBand = it },
                    onSelectWhiteBalancePreset = onSelectWhiteBalancePreset,
                    onToggleEyedropper = onToggleEyedropper,
                    onParamsChange = onParamsChange,
                    onDraggingChanged = onDraggingChanged
                )
            }

            // 3. EFFECTS
            AccordionSection(
                title = "Effects",
                icon = Icons.Default.AutoFixHigh,
                isExpanded = AdjustSection.EFFECTS in expandedSections,
                hasActiveEdits = params.hasEffectsEdits,
                testTag = "accordion_effects",
                onToggle = { toggleSection(AdjustSection.EFFECTS) },
                onResetSection = { onParamsChange { it.resetEffects() } }
            ) {
                PrecisionSlider(
                    label = "Texture",
                    icon = Icons.Default.Texture,
                    value = params.texture,
                    valueRange = -100f..100f,
                    formattedValue = String.format(Locale.US, "%+d", params.texture.roundToInt()),
                    isModified = params.texture != 0f,
                    testTag = "slider_texture",
                    onValueChange = { v ->
                        onDraggingChanged(true)
                        onParamsChange { it.copy(texture = v) }
                    },
                    onValueChangeFinished = { onDraggingChanged(false) },
                    onReset = {
                        onDraggingChanged(false)
                        onParamsChange { it.copy(texture = 0f) }
                    }
                )
                PrecisionSlider(
                    label = "Clarity",
                    icon = Icons.Default.Details,
                    value = params.clarity,
                    valueRange = -100f..100f,
                    formattedValue = String.format(Locale.US, "%+d", params.clarity.roundToInt()),
                    isModified = params.clarity != 0f,
                    testTag = "slider_clarity",
                    onValueChange = { v ->
                        onDraggingChanged(true)
                        onParamsChange { it.copy(clarity = v) }
                    },
                    onValueChangeFinished = { onDraggingChanged(false) },
                    onReset = {
                        onDraggingChanged(false)
                        onParamsChange { it.copy(clarity = 0f) }
                    }
                )
                PrecisionSlider(
                    label = "Dehaze",
                    icon = Icons.Default.BlurOn,
                    value = params.dehaze,
                    valueRange = -100f..100f,
                    formattedValue = String.format(Locale.US, "%+d", params.dehaze.roundToInt()),
                    isModified = params.dehaze != 0f,
                    testTag = "slider_dehaze",
                    onValueChange = { v ->
                        onDraggingChanged(true)
                        onParamsChange { it.copy(dehaze = v) }
                    },
                    onValueChangeFinished = { onDraggingChanged(false) },
                    onReset = {
                        onDraggingChanged(false)
                        onParamsChange { it.copy(dehaze = 0f) }
                    }
                )

                HorizontalDivider(color = StudioBorderSubtle, thickness = 1.dp)

                // Vignette Sub-Section
                val vignetteActive = params.vignette != 0f
                EffectsSubHeader(
                    title = "Vignette",
                    icon = Icons.Default.Vignette,
                    isActive = vignetteActive
                )
                VignetteStyleSelector(
                    selected = params.vignetteStyle,
                    onSelect = { style ->
                        onDraggingChanged(false)
                        onParamsChange { it.copy(vignetteStyle = style) }
                    }
                )
                PrecisionSlider(
                    label = "Vignette",
                    icon = Icons.Default.Vignette,
                    value = params.vignette,
                    valueRange = -100f..100f,
                    formattedValue = String.format(Locale.US, "%+d", params.vignette.roundToInt()),
                    isModified = vignetteActive,
                    testTag = "slider_vignette",
                    onValueChange = { v ->
                        onDraggingChanged(true)
                        onParamsChange { it.copy(vignette = v) }
                    },
                    onValueChangeFinished = { onDraggingChanged(false) },
                    onReset = {
                        onDraggingChanged(false)
                        onParamsChange { it.copy(vignette = 0f) }
                    }
                )
                PrecisionSlider(
                    label = "Midpoint",
                    icon = Icons.Default.CenterFocusStrong,
                    value = params.vignetteMidpoint,
                    valueRange = 0f..100f,
                    formattedValue = "${params.vignetteMidpoint.roundToInt()}",
                    isModified = vignetteActive && params.vignetteMidpoint != 50f,
                    enabled = vignetteActive,
                    testTag = "slider_vignette_midpoint",
                    onValueChange = { v ->
                        onDraggingChanged(true)
                        onParamsChange { it.copy(vignetteMidpoint = v) }
                    },
                    onValueChangeFinished = { onDraggingChanged(false) },
                    onReset = {
                        onDraggingChanged(false)
                        onParamsChange { it.copy(vignetteMidpoint = 50f) }
                    }
                )
                PrecisionSlider(
                    label = "Feather",
                    icon = Icons.Default.BlurCircular,
                    value = params.vignetteFeather,
                    valueRange = 0f..100f,
                    formattedValue = "${params.vignetteFeather.roundToInt()}",
                    isModified = vignetteActive && params.vignetteFeather != 50f,
                    enabled = vignetteActive,
                    testTag = "slider_vignette_feather",
                    onValueChange = { v ->
                        onDraggingChanged(true)
                        onParamsChange { it.copy(vignetteFeather = v) }
                    },
                    onValueChangeFinished = { onDraggingChanged(false) },
                    onReset = {
                        onDraggingChanged(false)
                        onParamsChange { it.copy(vignetteFeather = 50f) }
                    }
                )
                PrecisionSlider(
                    label = "Roundness",
                    icon = Icons.Default.Lens,
                    value = params.vignetteRoundness,
                    valueRange = -100f..100f,
                    formattedValue = String.format(Locale.US, "%+d", params.vignetteRoundness.roundToInt()),
                    isModified = vignetteActive && params.vignetteRoundness != 0f,
                    enabled = vignetteActive,
                    testTag = "slider_vignette_roundness",
                    onValueChange = { v ->
                        onDraggingChanged(true)
                        onParamsChange { it.copy(vignetteRoundness = v) }
                    },
                    onValueChangeFinished = { onDraggingChanged(false) },
                    onReset = {
                        onDraggingChanged(false)
                        onParamsChange { it.copy(vignetteRoundness = 0f) }
                    }
                )
                PrecisionSlider(
                    label = "Highlights",
                    icon = Icons.Default.Flare,
                    value = params.vignetteHighlights,
                    valueRange = 0f..100f,
                    formattedValue = "${params.vignetteHighlights.roundToInt()}",
                    isModified = vignetteActive && params.vignetteHighlights != 0f,
                    enabled = vignetteActive,
                    testTag = "slider_vignette_highlights",
                    onValueChange = { v ->
                        onDraggingChanged(true)
                        onParamsChange { it.copy(vignetteHighlights = v) }
                    },
                    onValueChangeFinished = { onDraggingChanged(false) },
                    onReset = {
                        onDraggingChanged(false)
                        onParamsChange { it.copy(vignetteHighlights = 0f) }
                    }
                )

                HorizontalDivider(color = StudioBorderSubtle, thickness = 1.dp)

                // Grain Sub-Section
                val grainActive = params.grain > 0f
                EffectsSubHeader(
                    title = "Grain",
                    icon = Icons.Default.Grain,
                    isActive = grainActive
                )
                PrecisionSlider(
                    label = "Grain",
                    icon = Icons.Default.Grain,
                    value = params.grain,
                    valueRange = 0f..100f,
                    formattedValue = "${params.grain.roundToInt()}",
                    isModified = grainActive,
                    testTag = "slider_grain",
                    onValueChange = { v ->
                        onDraggingChanged(true)
                        onParamsChange { it.copy(grain = v) }
                    },
                    onValueChangeFinished = { onDraggingChanged(false) },
                    onReset = {
                        onDraggingChanged(false)
                        onParamsChange { it.copy(grain = 0f) }
                    }
                )
                PrecisionSlider(
                    label = "Size",
                    icon = Icons.Default.BlurOn,
                    value = params.grainSize,
                    valueRange = 0f..100f,
                    formattedValue = "${params.grainSize.roundToInt()}",
                    isModified = grainActive && params.grainSize != 25f,
                    enabled = grainActive,
                    testTag = "slider_grain_size",
                    onValueChange = { v ->
                        onDraggingChanged(true)
                        onParamsChange { it.copy(grainSize = v) }
                    },
                    onValueChangeFinished = { onDraggingChanged(false) },
                    onReset = {
                        onDraggingChanged(false)
                        onParamsChange { it.copy(grainSize = 25f) }
                    }
                )
                PrecisionSlider(
                    label = "Roughness",
                    icon = Icons.Default.Texture,
                    value = params.grainRoughness,
                    valueRange = 0f..100f,
                    formattedValue = "${params.grainRoughness.roundToInt()}",
                    isModified = grainActive && params.grainRoughness != 50f,
                    enabled = grainActive,
                    testTag = "slider_grain_roughness",
                    onValueChange = { v ->
                        onDraggingChanged(true)
                        onParamsChange { it.copy(grainRoughness = v) }
                    },
                    onValueChangeFinished = { onDraggingChanged(false) },
                    onReset = {
                        onDraggingChanged(false)
                        onParamsChange { it.copy(grainRoughness = 50f) }
                    }
                )
            }

            // 4. DETAIL
            AccordionSection(
                title = "Detail",
                icon = Icons.Default.Deblur,
                isExpanded = AdjustSection.DETAIL in expandedSections,
                hasActiveEdits = params.hasDetailEdits,
                testTag = "accordion_detail",
                onToggle = { toggleSection(AdjustSection.DETAIL) },
                onResetSection = { onParamsChange { it.resetDetail() } }
            ) {
                // Sharpening Group
                val sharpenActive = params.sharpening > 0f
                PrecisionSlider(
                    label = "Sharpening",
                    icon = Icons.Default.CenterFocusStrong,
                    value = params.sharpening,
                    valueRange = 0f..150f,
                    formattedValue = "${params.sharpening.roundToInt()}",
                    isModified = sharpenActive,
                    accentColor = StudioCyan,
                    testTag = "slider_sharpening",
                    onValueChange = { v ->
                        onDraggingChanged(true)
                        onParamsChange { it.copy(sharpening = v) }
                    },
                    onValueChangeFinished = { onDraggingChanged(false) },
                    onReset = {
                        onDraggingChanged(false)
                        onParamsChange { it.copy(sharpening = 0f) }
                    }
                )
                PrecisionSlider(
                    label = "Radius",
                    icon = Icons.Default.Lens,
                    value = params.sharpenRadius,
                    valueRange = 0.5f..3.0f,
                    formattedValue = String.format(java.util.Locale.US, "%.1f", params.sharpenRadius),
                    isModified = sharpenActive && kotlin.math.abs(params.sharpenRadius - 1.0f) > 0.04f,
                    enabled = sharpenActive,
                    accentColor = StudioCyan,
                    testTag = "slider_sharpen_radius",
                    onValueChange = { v ->
                        onDraggingChanged(true)
                        onParamsChange { it.copy(sharpenRadius = (v * 10f).roundToInt() / 10f) }
                    },
                    onValueChangeFinished = { onDraggingChanged(false) },
                    onReset = {
                        onDraggingChanged(false)
                        onParamsChange { it.copy(sharpenRadius = 1.0f) }
                    }
                )
                PrecisionSlider(
                    label = "Detail",
                    icon = Icons.Default.Details,
                    value = params.sharpenDetail,
                    valueRange = 0f..100f,
                    formattedValue = "${params.sharpenDetail.roundToInt()}",
                    isModified = sharpenActive && params.sharpenDetail != 25f,
                    enabled = sharpenActive,
                    accentColor = StudioCyan,
                    testTag = "slider_sharpen_detail",
                    onValueChange = { v ->
                        onDraggingChanged(true)
                        onParamsChange { it.copy(sharpenDetail = v) }
                    },
                    onValueChangeFinished = { onDraggingChanged(false) },
                    onReset = {
                        onDraggingChanged(false)
                        onParamsChange { it.copy(sharpenDetail = 25f) }
                    }
                )
                PrecisionSlider(
                    label = "Masking",
                    icon = Icons.Default.Tonality,
                    value = params.sharpenMasking,
                    valueRange = 0f..100f,
                    formattedValue = "${params.sharpenMasking.roundToInt()}",
                    isModified = sharpenActive && params.sharpenMasking > 0f,
                    enabled = sharpenActive,
                    accentColor = StudioCyan,
                    testTag = "slider_sharpen_masking",
                    onValueChange = { v ->
                        onDraggingChanged(true)
                        onParamsChange { it.copy(sharpenMasking = v) }
                    },
                    onValueChangeFinished = { onDraggingChanged(false) },
                    onReset = {
                        onDraggingChanged(false)
                        onParamsChange { it.copy(sharpenMasking = 0f) }
                    }
                )

                HorizontalDivider(color = StudioBorderSubtle, thickness = 1.dp)

                // Noise Reduction Group
                val nrActive = params.noiseReduction > 0f
                EffectsSubHeader(
                    title = "Noise Reduction",
                    icon = Icons.Default.BlurOn,
                    isActive = nrActive,
                    accentColor = StudioCyan
                )
                PrecisionSlider(
                    label = "Noise Reduction",
                    icon = Icons.Default.BlurOn,
                    value = params.noiseReduction,
                    valueRange = 0f..100f,
                    formattedValue = "${params.noiseReduction.roundToInt()}",
                    isModified = nrActive,
                    accentColor = StudioCyan,
                    testTag = "slider_noise_reduction",
                    onValueChange = { v ->
                        onDraggingChanged(true)
                        onParamsChange { it.copy(noiseReduction = v) }
                    },
                    onValueChangeFinished = { onDraggingChanged(false) },
                    onReset = {
                        onDraggingChanged(false)
                        onParamsChange { it.copy(noiseReduction = 0f) }
                    }
                )
                PrecisionSlider(
                    label = "Detail",
                    icon = Icons.Default.Details,
                    value = params.noiseReductionDetail,
                    valueRange = 0f..100f,
                    formattedValue = "${params.noiseReductionDetail.roundToInt()}",
                    isModified = nrActive && params.noiseReductionDetail != 50f,
                    enabled = nrActive,
                    accentColor = StudioCyan,
                    testTag = "slider_nr_detail",
                    onValueChange = { v ->
                        onDraggingChanged(true)
                        onParamsChange { it.copy(noiseReductionDetail = v) }
                    },
                    onValueChangeFinished = { onDraggingChanged(false) },
                    onReset = {
                        onDraggingChanged(false)
                        onParamsChange { it.copy(noiseReductionDetail = 50f) }
                    }
                )
                PrecisionSlider(
                    label = "Contrast",
                    icon = Icons.Default.Contrast,
                    value = params.noiseReductionContrast,
                    valueRange = 0f..100f,
                    formattedValue = "${params.noiseReductionContrast.roundToInt()}",
                    isModified = nrActive && params.noiseReductionContrast > 0f,
                    enabled = nrActive,
                    accentColor = StudioCyan,
                    testTag = "slider_nr_contrast",
                    onValueChange = { v ->
                        onDraggingChanged(true)
                        onParamsChange { it.copy(noiseReductionContrast = v) }
                    },
                    onValueChangeFinished = { onDraggingChanged(false) },
                    onReset = {
                        onDraggingChanged(false)
                        onParamsChange { it.copy(noiseReductionContrast = 0f) }
                    }
                )

                HorizontalDivider(color = StudioBorderSubtle, thickness = 1.dp)

                // Color Noise Reduction Group
                val cnrActive = params.colorNoiseReduction > 0f
                EffectsSubHeader(
                    title = "Color Noise Reduction",
                    icon = Icons.Default.Palette,
                    isActive = cnrActive,
                    accentColor = StudioCyan
                )
                PrecisionSlider(
                    label = "Color Noise Reduction",
                    icon = Icons.Default.Palette,
                    value = params.colorNoiseReduction,
                    valueRange = 0f..100f,
                    formattedValue = "${params.colorNoiseReduction.roundToInt()}",
                    isModified = cnrActive,
                    accentColor = StudioCyan,
                    testTag = "slider_color_noise_reduction",
                    onValueChange = { v ->
                        onDraggingChanged(true)
                        onParamsChange { it.copy(colorNoiseReduction = v) }
                    },
                    onValueChangeFinished = { onDraggingChanged(false) },
                    onReset = {
                        onDraggingChanged(false)
                        onParamsChange { it.copy(colorNoiseReduction = 0f) }
                    }
                )
                PrecisionSlider(
                    label = "Detail",
                    icon = Icons.Default.Details,
                    value = params.colorNoiseReductionDetail,
                    valueRange = 0f..100f,
                    formattedValue = "${params.colorNoiseReductionDetail.roundToInt()}",
                    isModified = cnrActive && params.colorNoiseReductionDetail != 50f,
                    enabled = cnrActive,
                    accentColor = StudioCyan,
                    testTag = "slider_color_nr_detail",
                    onValueChange = { v ->
                        onDraggingChanged(true)
                        onParamsChange { it.copy(colorNoiseReductionDetail = v) }
                    },
                    onValueChangeFinished = { onDraggingChanged(false) },
                    onReset = {
                        onDraggingChanged(false)
                        onParamsChange { it.copy(colorNoiseReductionDetail = 50f) }
                    }
                )
                PrecisionSlider(
                    label = "Smoothness",
                    icon = Icons.Default.BlurCircular,
                    value = params.colorNoiseReductionSmoothness,
                    valueRange = 0f..100f,
                    formattedValue = "${params.colorNoiseReductionSmoothness.roundToInt()}",
                    isModified = cnrActive && params.colorNoiseReductionSmoothness != 50f,
                    enabled = cnrActive,
                    accentColor = StudioCyan,
                    testTag = "slider_color_nr_smoothness",
                    onValueChange = { v ->
                        onDraggingChanged(true)
                        onParamsChange { it.copy(colorNoiseReductionSmoothness = v) }
                    },
                    onValueChangeFinished = { onDraggingChanged(false) },
                    onReset = {
                        onDraggingChanged(false)
                        onParamsChange { it.copy(colorNoiseReductionSmoothness = 50f) }
                    }
                )
            }

            // 5. OPTICS
            AccordionSection(
                title = "Optics",
                icon = Icons.Default.Camera,
                isExpanded = AdjustSection.OPTICS in expandedSections,
                hasActiveEdits = params.hasOpticsEdits,
                testTag = "accordion_optics",
                onToggle = { toggleSection(AdjustSection.OPTICS) },
                onResetSection = { onParamsChange { it.resetOptics() } }
            ) {
                PrecisionSlider(
                    label = "Distortion",
                    icon = Icons.Default.Lens,
                    value = params.lensDistortion,
                    valueRange = -100f..100f,
                    formattedValue = String.format(Locale.US, "%+d", params.lensDistortion.roundToInt()),
                    isModified = params.lensDistortion != 0f,
                    accentColor = StudioCyan,
                    testTag = "slider_lens_distortion",
                    onValueChange = { v ->
                        onDraggingChanged(true)
                        onParamsChange { it.copy(lensDistortion = v) }
                    },
                    onValueChangeFinished = { onDraggingChanged(false) },
                    onReset = {
                        onDraggingChanged(false)
                        onParamsChange { it.copy(lensDistortion = 0f) }
                    }
                )
                PrecisionSlider(
                    label = "Chromatic Aberration",
                    icon = Icons.Default.Camera,
                    value = params.chromaticAberration,
                    valueRange = 0f..100f,
                    formattedValue = "${params.chromaticAberration.roundToInt()}",
                    isModified = params.chromaticAberration > 0f,
                    accentColor = StudioCyan,
                    testTag = "slider_chromatic_aberration",
                    onValueChange = { v ->
                        onDraggingChanged(true)
                        onParamsChange { it.copy(chromaticAberration = v) }
                    },
                    onValueChangeFinished = { onDraggingChanged(false) },
                    onReset = {
                        onDraggingChanged(false)
                        onParamsChange { it.copy(chromaticAberration = 0f) }
                    }
                )
            }

            // 6. LENS BLUR
            AccordionSection(
                title = "Lens Blur",
                icon = Icons.Default.BlurCircular,
                isExpanded = AdjustSection.LENS_BLUR in expandedSections,
                hasActiveEdits = params.hasLensBlurEdits,
                testTag = "accordion_lens_blur",
                onToggle = { toggleSection(AdjustSection.LENS_BLUR) },
                onResetSection = { onParamsChange { it.resetLensBlur() } }
            ) {
                PrecisionSlider(
                    label = "Blur Amount",
                    icon = Icons.Default.BlurCircular,
                    value = params.lensBlurAmount,
                    valueRange = 0f..100f,
                    formattedValue = "${params.lensBlurAmount.roundToInt()}",
                    isModified = params.lensBlurAmount > 0f,
                    accentColor = StudioCyan,
                    testTag = "slider_lens_blur_amount",
                    onValueChange = { v ->
                        onDraggingChanged(true)
                        onParamsChange { it.copy(lensBlurAmount = v) }
                    },
                    onValueChangeFinished = { onDraggingChanged(false) },
                    onReset = {
                        onDraggingChanged(false)
                        onParamsChange { it.copy(lensBlurAmount = 0f) }
                    }
                )
                PrecisionSlider(
                    label = "Bokeh Focus",
                    icon = Icons.Default.CenterFocusStrong,
                    value = params.bokehFocus,
                    valueRange = 0f..100f,
                    formattedValue = "${params.bokehFocus.roundToInt()}%",
                    isModified = params.bokehFocus != 50f,
                    accentColor = StudioCyan,
                    testTag = "slider_bokeh_focus",
                    onValueChange = { v ->
                        onDraggingChanged(true)
                        onParamsChange { it.copy(bokehFocus = v) }
                    },
                    onValueChangeFinished = { onDraggingChanged(false) },
                    onReset = {
                        onDraggingChanged(false)
                        onParamsChange { it.copy(bokehFocus = 50f) }
                    }
                )
            }

            // 7. CALIBRATION
            AccordionSection(
                title = "Calibration",
                icon = Icons.Default.Tune,
                isExpanded = AdjustSection.CALIBRATION in expandedSections,
                hasActiveEdits = params.hasCalibrationEdits,
                testTag = "accordion_calibration",
                onToggle = { toggleSection(AdjustSection.CALIBRATION) },
                onResetSection = { onParamsChange { it.resetCalibration() } }
            ) {
                val cal = params.calibration
                PrecisionSlider(
                    label = "Red Primary Hue",
                    icon = Icons.Default.Palette,
                    value = cal.redHue,
                    valueRange = -100f..100f,
                    formattedValue = String.format(Locale.US, "%+d", cal.redHue.roundToInt()),
                    isModified = cal.redHue != 0f,
                    accentColor = Color(0xFFEF4444),
                    testTag = "slider_cal_red_hue",
                    onValueChange = { v ->
                        onDraggingChanged(true)
                        onParamsChange { it.copy(calibration = it.calibration.copy(redHue = v)) }
                    },
                    onValueChangeFinished = { onDraggingChanged(false) },
                    onReset = {
                        onDraggingChanged(false)
                        onParamsChange { it.copy(calibration = it.calibration.copy(redHue = 0f)) }
                    }
                )
                PrecisionSlider(
                    label = "Red Primary Saturation",
                    icon = Icons.Default.InvertColors,
                    value = cal.redSaturation,
                    valueRange = -100f..100f,
                    formattedValue = String.format(Locale.US, "%+d", cal.redSaturation.roundToInt()),
                    isModified = cal.redSaturation != 0f,
                    accentColor = Color(0xFFEF4444),
                    testTag = "slider_cal_red_sat",
                    onValueChange = { v ->
                        onDraggingChanged(true)
                        onParamsChange { it.copy(calibration = it.calibration.copy(redSaturation = v)) }
                    },
                    onValueChangeFinished = { onDraggingChanged(false) },
                    onReset = {
                        onDraggingChanged(false)
                        onParamsChange { it.copy(calibration = it.calibration.copy(redSaturation = 0f)) }
                    }
                )
                PrecisionSlider(
                    label = "Green Primary Hue",
                    icon = Icons.Default.Palette,
                    value = cal.greenHue,
                    valueRange = -100f..100f,
                    formattedValue = String.format(Locale.US, "%+d", cal.greenHue.roundToInt()),
                    isModified = cal.greenHue != 0f,
                    accentColor = Color(0xFF22C55E),
                    testTag = "slider_cal_green_hue",
                    onValueChange = { v ->
                        onDraggingChanged(true)
                        onParamsChange { it.copy(calibration = it.calibration.copy(greenHue = v)) }
                    },
                    onValueChangeFinished = { onDraggingChanged(false) },
                    onReset = {
                        onDraggingChanged(false)
                        onParamsChange { it.copy(calibration = it.calibration.copy(greenHue = 0f)) }
                    }
                )
                PrecisionSlider(
                    label = "Green Primary Saturation",
                    icon = Icons.Default.InvertColors,
                    value = cal.greenSaturation,
                    valueRange = -100f..100f,
                    formattedValue = String.format(Locale.US, "%+d", cal.greenSaturation.roundToInt()),
                    isModified = cal.greenSaturation != 0f,
                    accentColor = Color(0xFF22C55E),
                    testTag = "slider_cal_green_sat",
                    onValueChange = { v ->
                        onDraggingChanged(true)
                        onParamsChange { it.copy(calibration = it.calibration.copy(greenSaturation = v)) }
                    },
                    onValueChangeFinished = { onDraggingChanged(false) },
                    onReset = {
                        onDraggingChanged(false)
                        onParamsChange { it.copy(calibration = it.calibration.copy(greenSaturation = 0f)) }
                    }
                )
                PrecisionSlider(
                    label = "Blue Primary Hue",
                    icon = Icons.Default.Palette,
                    value = cal.blueHue,
                    valueRange = -100f..100f,
                    formattedValue = String.format(Locale.US, "%+d", cal.blueHue.roundToInt()),
                    isModified = cal.blueHue != 0f,
                    accentColor = Color(0xFF3B82F6),
                    testTag = "slider_cal_blue_hue",
                    onValueChange = { v ->
                        onDraggingChanged(true)
                        onParamsChange { it.copy(calibration = it.calibration.copy(blueHue = v)) }
                    },
                    onValueChangeFinished = { onDraggingChanged(false) },
                    onReset = {
                        onDraggingChanged(false)
                        onParamsChange { it.copy(calibration = it.calibration.copy(blueHue = 0f)) }
                    }
                )
                PrecisionSlider(
                    label = "Blue Primary Saturation",
                    icon = Icons.Default.InvertColors,
                    value = cal.blueSaturation,
                    valueRange = -100f..100f,
                    formattedValue = String.format(Locale.US, "%+d", cal.blueSaturation.roundToInt()),
                    isModified = cal.blueSaturation != 0f,
                    accentColor = Color(0xFF3B82F6),
                    testTag = "slider_cal_blue_sat",
                    onValueChange = { v ->
                        onDraggingChanged(true)
                        onParamsChange { it.copy(calibration = it.calibration.copy(blueSaturation = v)) }
                    },
                    onValueChangeFinished = { onDraggingChanged(false) },
                    onReset = {
                        onDraggingChanged(false)
                        onParamsChange { it.copy(calibration = it.calibration.copy(blueSaturation = 0f)) }
                    }
                )
            }
        }
    }
}

@Composable
private fun AdjustQuickActionsBar(
    isGrayscale: Boolean,
    canReset: Boolean,
    canPasteEdits: Boolean,
    onAutoTone: () -> Unit,
    onToggleBw: () -> Unit,
    onResetAll: () -> Unit,
    onCopyEdits: () -> Unit,
    onPasteEdits: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF12151C))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        QuickActionPill(
            label = "Auto",
            icon = Icons.Default.AutoAwesome,
            active = false,
            enabled = true,
            accentColor = StudioAmber,
            testTag = "adjust_auto_button",
            onClick = onAutoTone
        )
        QuickActionPill(
            label = "B&W",
            icon = Icons.Default.FilterBAndW,
            active = isGrayscale,
            enabled = true,
            accentColor = StudioCyan,
            testTag = "adjust_bw_button",
            onClick = onToggleBw
        )
        QuickActionPill(
            label = "Reset",
            icon = Icons.Default.RestartAlt,
            active = false,
            enabled = canReset,
            accentColor = StudioAmber,
            testTag = "adjust_reset_button",
            onClick = onResetAll
        )
        QuickActionPill(
            label = "Copy",
            icon = Icons.Default.ContentCopy,
            active = false,
            enabled = true,
            accentColor = StudioCyan,
            testTag = "adjust_copy_button",
            onClick = onCopyEdits
        )
        QuickActionPill(
            label = "Paste",
            icon = Icons.Default.ContentPaste,
            active = canPasteEdits,
            enabled = canPasteEdits,
            accentColor = StudioCyan,
            testTag = "adjust_paste_button",
            onClick = onPasteEdits
        )
    }
}

@Composable
private fun QuickActionPill(
    label: String,
    icon: ImageVector,
    active: Boolean,
    enabled: Boolean,
    accentColor: Color,
    testTag: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = when {
                active -> accentColor.copy(alpha = 0.2f)
                enabled -> StudioSurfaceElevated
                else -> StudioSurfaceElevated.copy(alpha = 0.45f)
            },
            modifier = Modifier.border(
                width = 1.dp,
                color = if (active) accentColor else StudioBorderSubtle,
                shape = RoundedCornerShape(8.dp)
            )
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = when {
                        active -> accentColor
                        enabled -> StudioTextPrimary
                        else -> StudioTextMuted
                    },
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        active -> accentColor
                        enabled -> StudioTextPrimary
                        else -> StudioTextMuted
                    }
                )
            }
        }
    }
}

@Composable
private fun AccordionSection(
    title: String,
    icon: ImageVector,
    isExpanded: Boolean,
    hasActiveEdits: Boolean,
    testTag: String,
    onToggle: () -> Unit,
    onResetSection: () -> Unit,
    content: @Composable () -> Unit
) {
    val arrowRotation by animateFloatAsState(
        targetValue = if (isExpanded) 90f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "accordion_arrow"
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
                        tint = if (isExpanded) StudioAmber else StudioTextSecondary,
                        modifier = Modifier
                            .size(18.dp)
                            .rotate(arrowRotation)
                    )
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (hasActiveEdits || isExpanded) StudioAmber else StudioTextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (isExpanded || hasActiveEdits) StudioTextPrimary else StudioTextSecondary
                    )
                    if (hasActiveEdits) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(StudioAmber)
                                .testTag("${testTag}_active_dot")
                        )
                    }
                }

                if (hasActiveEdits && isExpanded) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = StudioSurfaceHighest,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(onClick = onResetSection)
                            .testTag("${testTag}_reset")
                    ) {
                        Text(
                            text = "Reset",
                            style = MaterialTheme.typography.labelSmall,
                            color = StudioTextSecondary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
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
                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
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
private fun CollapsibleSubPanel(
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
        label = "subpanel_arrow"
    )

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = StudioSurface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
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
                    .defaultMinSize(minHeight = 44.dp)
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
                            .size(34.dp)
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
private fun ToneCurveEditor(
    toneCurve: ToneCurveData,
    histogram: HistogramData?,
    activeChannel: ToneCurveChannel,
    onSelectChannel: (ToneCurveChannel) -> Unit,
    onPointsChange: (ToneCurveChannel, List<ToneCurvePoint>) -> Unit,
    onDraggingChanged: (Boolean) -> Unit,
    onResetChannel: (ToneCurveChannel) -> Unit
) {
    val points = toneCurve.pointsFor(activeChannel)
    val channelColor = when (activeChannel) {
        ToneCurveChannel.MASTER -> Color.White
        ToneCurveChannel.RED -> Color(0xFFEF4444)
        ToneCurveChannel.GREEN -> Color(0xFF22C55E)
        ToneCurveChannel.BLUE -> Color(0xFF38BDF8)
    }

    var draggingIndex by remember { mutableIntStateOf(-1) }
    val currentPoints by rememberUpdatedState(points)
    val currentChannel by rememberUpdatedState(activeChannel)

    val curveLut = remember(points) { ToneCurveData.buildLut(points) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Explicit channel tabs (RGB, Red, Green, Blue) + active channel reset icon
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ToneCurveChannel.entries.forEach { ch ->
                    val selected = ch == activeChannel
                    val modified = toneCurve.isChannelModified(ch)
                    val dotColor = when (ch) {
                        ToneCurveChannel.MASTER -> Color.White
                        ToneCurveChannel.RED -> Color(0xFFEF4444)
                        ToneCurveChannel.GREEN -> Color(0xFF22C55E)
                        ToneCurveChannel.BLUE -> Color(0xFF38BDF8)
                    }
                    Box(
                        modifier = Modifier
                            .defaultMinSize(minHeight = 40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) dotColor.copy(alpha = 0.16f) else Color.Transparent)
                            .border(
                                width = 1.dp,
                                color = if (selected) dotColor.copy(alpha = 0.75f) else StudioBorderSubtle,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable { onSelectChannel(ch) }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                            .testTag("curve_channel_${ch.name.lowercase()}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(dotColor)
                            )
                            Text(
                                text = ch.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) dotColor else StudioTextSecondary
                            )
                            if (modified) {
                                Box(
                                    modifier = Modifier
                                        .size(4.dp)
                                        .clip(CircleShape)
                                        .background(StudioAmber)
                                )
                            }
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .defaultMinSize(minWidth = 40.dp, minHeight = 40.dp)
                    .clip(CircleShape)
                    .clickable(enabled = toneCurve.isChannelModified(activeChannel)) {
                        onResetChannel(activeChannel)
                    }
                    .testTag("curve_reset_channel"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.RestartAlt,
                    contentDescription = "Reset ${activeChannel.label} curve",
                    tint = if (toneCurve.isChannelModified(activeChannel)) StudioAmber else StudioTextMuted,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        // Square interactive spline canvas with live faint luminance histogram behind grid
        Box(
            modifier = Modifier
                .widthIn(max = 220.dp)
                .fillMaxWidth()
                .aspectRatio(1f)
                .align(Alignment.CenterHorizontally)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF0C0F14))
                .border(1.dp, StudioBorder, RoundedCornerShape(10.dp))
        ) {
            Canvas(
                modifier = Modifier
                    .matchParentSize()
                    .pointerInput(activeChannel) {
                        detectTapGestures(
                            onTap = { offset ->
                                val w = size.width.toFloat().coerceAtLeast(1f)
                                val h = size.height.toFloat().coerceAtLeast(1f)
                                val nx = (offset.x / w).coerceIn(0.02f, 0.98f)
                                val ny = (1f - offset.y / h).coerceIn(0f, 1f)
                                val pts = currentPoints
                                val nearExisting = pts.any { absDist(it.x, it.y, nx, ny) < 0.08f }
                                if (!nearExisting && pts.size < 10) {
                                    onPointsChange(currentChannel, pts + ToneCurvePoint(nx, ny))
                                }
                            },
                            onDoubleTap = { offset ->
                                val w = size.width.toFloat().coerceAtLeast(1f)
                                val h = size.height.toFloat().coerceAtLeast(1f)
                                val pts = currentPoints
                                var bestIdx = -1
                                var bestDist = 36.dp.toPx()
                                pts.forEachIndexed { idx, pt ->
                                    val px = pt.x * w
                                    val py = (1f - pt.y) * h
                                    val d = hypot(px - offset.x, py - offset.y)
                                    if (d < bestDist) {
                                        bestDist = d
                                        bestIdx = idx
                                    }
                                }
                                if (bestIdx in pts.indices) {
                                    if (bestIdx == 0) {
                                        val next = pts.toMutableList()
                                        next[0] = ToneCurvePoint(0f, 0f)
                                        onPointsChange(currentChannel, next)
                                    } else if (bestIdx == pts.lastIndex) {
                                        val next = pts.toMutableList()
                                        next[pts.lastIndex] = ToneCurvePoint(1f, 1f)
                                        onPointsChange(currentChannel, next)
                                    } else if (pts.size > 2) {
                                        val next = pts.toMutableList()
                                        next.removeAt(bestIdx)
                                        onPointsChange(currentChannel, next)
                                    }
                                }
                            }
                        )
                    }
                    .pointerInput(activeChannel) {
                        detectDragGestures(
                            onDragStart = { start ->
                                val w = size.width.toFloat().coerceAtLeast(1f)
                                val h = size.height.toFloat().coerceAtLeast(1f)
                                val pts = currentPoints
                                var bestIdx = -1
                                var bestDist = 42.dp.toPx()
                                pts.forEachIndexed { idx, p ->
                                    val px = p.x * w
                                    val py = (1f - p.y) * h
                                    val d = hypot(px - start.x, py - start.y)
                                    if (d < bestDist) {
                                        bestDist = d
                                        bestIdx = idx
                                    }
                                }
                                if (bestIdx < 0 && pts.size < 10) {
                                    val nx = (start.x / w).coerceIn(0.02f, 0.98f)
                                    val ny = (1f - start.y / h).coerceIn(0f, 1f)
                                    val updated = (pts + ToneCurvePoint(nx, ny)).sortedBy { it.x }
                                    onDraggingChanged(true)
                                    onPointsChange(currentChannel, updated)
                                    draggingIndex = updated.indexOfFirst { it.x == nx && it.y == ny }
                                } else {
                                    draggingIndex = bestIdx
                                    if (bestIdx >= 0) onDraggingChanged(true)
                                }
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                val idx = draggingIndex
                                val pts = currentPoints
                                if (idx in pts.indices) {
                                    val w = size.width.toFloat().coerceAtLeast(1f)
                                    val h = size.height.toFloat().coerceAtLeast(1f)
                                    val minX = if (idx == 0) 0f else (pts[idx - 1].x + 0.03f)
                                    val maxX = if (idx == pts.lastIndex) 1f else (pts[idx + 1].x - 0.03f)
                                    val nx = if (idx == 0) 0f else if (idx == pts.lastIndex) 1f
                                    else (change.position.x / w).coerceIn(minX, maxX.coerceAtLeast(minX))
                                    val ny = (1f - change.position.y / h).coerceIn(0f, 1f)
                                    val next = pts.toMutableList()
                                    next[idx] = ToneCurvePoint(nx, ny)
                                    onPointsChange(currentChannel, next)
                                }
                            },
                            onDragEnd = {
                                draggingIndex = -1
                                onDraggingChanged(false)
                            },
                            onDragCancel = {
                                draggingIndex = -1
                                onDraggingChanged(false)
                            }
                        )
                    }
                    .testTag("tone_curve_canvas")
            ) {
                val w = size.width
                val h = size.height

                // Faint live luminance histogram behind the grid
                val histBins = when (activeChannel) {
                    ToneCurveChannel.MASTER -> histogram?.luma
                    ToneCurveChannel.RED -> histogram?.red
                    ToneCurveChannel.GREEN -> histogram?.green
                    ToneCurveChannel.BLUE -> histogram?.blue
                }
                if (histBins != null && histBins.isNotEmpty()) {
                    val histPath = Path().apply {
                        moveTo(0f, h)
                        val count = histBins.size
                        for (i in 0 until count) {
                            val x = (i.toFloat() / (count - 1).coerceAtLeast(1)) * w
                            val y = h - histBins[i].coerceIn(0f, 1f) * (h * 0.85f)
                            lineTo(x, y)
                        }
                        lineTo(w, h)
                        close()
                    }
                    val histTint = when (activeChannel) {
                        ToneCurveChannel.MASTER -> Color.White.copy(alpha = 0.10f)
                        ToneCurveChannel.RED -> Color(0xFFEF4444).copy(alpha = 0.14f)
                        ToneCurveChannel.GREEN -> Color(0xFF22C55E).copy(alpha = 0.14f)
                        ToneCurveChannel.BLUE -> Color(0xFF38BDF8).copy(alpha = 0.14f)
                    }
                    drawPath(path = histPath, color = histTint)
                }

                // 4x4 grid
                for (i in 1..3) {
                    val f = i / 4f
                    drawLine(
                        color = StudioBorderSubtle,
                        start = Offset(w * f, 0f),
                        end = Offset(w * f, h),
                        strokeWidth = 1.dp.toPx()
                    )
                    drawLine(
                        color = StudioBorderSubtle,
                        start = Offset(0f, h * f),
                        end = Offset(w, h * f),
                        strokeWidth = 1.dp.toPx()
                    )
                }

                // Diagonal identity reference line
                drawLine(
                    color = Color.White.copy(alpha = 0.18f),
                    start = Offset(0f, h),
                    end = Offset(w, 0f),
                    strokeWidth = 1.dp.toPx()
                )

                // Smooth spline curve path
                val curvePath = Path()
                for (i in 0..96) {
                    val x = i / 96f
                    val y = curveLut[(x * 255f).roundToInt().coerceIn(0, 255)]
                    val px = x * w
                    val py = (1f - y) * h
                    if (i == 0) curvePath.moveTo(px, py) else curvePath.lineTo(px, py)
                }
                drawPath(
                    path = curvePath,
                    color = channelColor,
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                )

                // Draggable control points
                points.forEach { pt ->
                    val center = Offset(pt.x * w, (1f - pt.y) * h)
                    drawCircle(color = Color(0xFF0C0F14), radius = 6.dp.toPx(), center = center)
                    drawCircle(
                        color = channelColor,
                        radius = 5.dp.toPx(),
                        center = center,
                        style = Stroke(width = 2.dp.toPx())
                    )
                }
            }
        }

        Text(
            text = "Tap to add • Drag to shape • Double-tap point to remove",
            style = MaterialTheme.typography.labelSmall,
            color = StudioTextMuted
        )
    }
}

private fun absDist(x1: Float, y1: Float, x2: Float, y2: Float): Float =
    hypot(x1 - x2, y1 - y2)

@Composable
private fun EffectsSubHeader(
    title: String,
    icon: ImageVector,
    isActive: Boolean,
    accentColor: Color = StudioAmber
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isActive) accentColor else StudioTextSecondary,
            modifier = Modifier.size(15.dp)
        )
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = if (isActive) StudioTextPrimary else StudioTextSecondary
        )
    }
}

@Composable
private fun VignetteStyleSelector(
    selected: VignetteStyle,
    onSelect: (VignetteStyle) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(StudioSurface)
            .border(1.dp, StudioBorderSubtle, RoundedCornerShape(10.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        VignetteStyle.entries.forEach { style ->
            val isSelected = style == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) StudioAmber.copy(alpha = 0.22f) else Color.Transparent)
                    .border(
                        width = if (isSelected) 1.dp else 0.dp,
                        color = if (isSelected) StudioAmber else Color.Transparent,
                        shape = RoundedCornerShape(8.dp)
                    )
                    .clickable { onSelect(style) }
                    .testTag("vignette_style_${style.name.lowercase()}"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = style.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSelected) StudioAmber else StudioTextSecondary
                )
            }
        }
    }
}

@Composable
private fun PrecisionSlider(
    label: String,
    icon: ImageVector,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    formattedValue: String,
    isModified: Boolean,
    enabled: Boolean = true,
    accentColor: Color = StudioAmber,
    testTag: String,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    onReset: () -> Unit
) {
    val labelTint = when {
        !enabled -> StudioTextMuted.copy(alpha = 0.55f)
        isModified -> StudioTextPrimary
        else -> StudioTextSecondary
    }
    val iconTint = when {
        !enabled -> StudioTextMuted.copy(alpha = 0.55f)
        isModified -> accentColor
        else -> StudioTextSecondary
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .pointerInput(enabled, onReset) {
                        if (enabled) {
                            detectTapGestures(
                                onDoubleTap = { onReset() }
                            )
                        }
                    }
                    .padding(vertical = 2.dp, horizontal = 2.dp)
                    .testTag("${testTag}_label")
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    color = labelTint
                )
            }

            Surface(
                shape = RoundedCornerShape(6.dp),
                color = when {
                    !enabled -> StudioSurfaceHighest.copy(alpha = 0.5f)
                    isModified -> accentColor.copy(alpha = 0.14f)
                    else -> StudioSurfaceHighest
                },
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = enabled, onClick = onReset)
            ) {
                Text(
                    text = formattedValue,
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        !enabled -> StudioTextMuted.copy(alpha = 0.55f)
                        isModified -> accentColor
                        else -> StudioTextSecondary
                    },
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = accentColor,
                activeTrackColor = accentColor,
                inactiveTrackColor = StudioSurfaceHighest,
                disabledThumbColor = StudioTextMuted.copy(alpha = 0.45f),
                disabledActiveTrackColor = StudioTextMuted.copy(alpha = 0.35f),
                disabledInactiveTrackColor = StudioSurfaceHighest.copy(alpha = 0.5f)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .testTag(testTag)
        )
    }
}

