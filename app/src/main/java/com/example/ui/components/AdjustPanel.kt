package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BlurCircular
import androidx.compose.material.icons.filled.BlurLinear
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Details
import androidx.compose.material.icons.filled.FilterBAndW
import androidx.compose.material.icons.filled.FilterTiltShift
import androidx.compose.material.icons.filled.Flare
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.InvertColors
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Texture
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Tonality
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Vignette
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.editor.EyedropperMode
import com.example.editor.GradingParams
import com.example.editor.HistogramData
import com.example.editor.HslBand
import com.example.editor.ToneCurveChannel
import com.example.editor.ToneCurveData
import com.example.editor.ToneCurvePoint
import com.example.editor.WhiteBalancePreset
import com.example.ui.theme.StudioActiveOffWhite
import com.example.ui.theme.StudioAmber
import com.example.ui.theme.StudioBorder
import com.example.ui.theme.StudioBorderSubtle
import com.example.ui.theme.StudioGlassHairline
import com.example.ui.theme.StudioGlassHighlight
import com.example.ui.theme.StudioGlassSurface
import com.example.ui.theme.StudioLuxuryGold
import com.example.ui.theme.StudioObsidian
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioTextMuted
import com.example.ui.theme.StudioTextPrimary
import com.example.ui.theme.StudioTextSecondary
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

internal enum class FocusSliderTool(
    val label: String,
    val icon: ImageVector,
    val valueRange: ClosedFloatingPointRange<Float>,
    val step: Float,
    val testTag: String,
    val tileTag: String,
    val shortLabel: String = label
) {
    EXPOSURE("Exposure", Icons.Default.Brightness6, -2f..2f, 0.05f, "slider_exposure", "tool_exposure"),
    CONTRAST("Contrast", Icons.Default.Contrast, -100f..100f, 1f, "slider_contrast", "tool_contrast"),
    HIGHLIGHTS("Highlights", Icons.Default.Flare, -100f..100f, 1f, "slider_highlights", "tool_highlights"),
    SHADOWS("Shadows", Icons.Default.Tonality, -100f..100f, 1f, "slider_shadows", "tool_shadows"),
    WHITES("Whites", Icons.Default.WbSunny, -100f..100f, 1f, "slider_whites", "tool_whites"),
    BLACKS("Blacks", Icons.Default.Contrast, -100f..100f, 1f, "slider_blacks", "tool_blacks"),
    TEMP("Temp", Icons.Default.Thermostat, -100f..100f, 1f, "slider_temperature", "tool_temp"),
    TINT("Tint", Icons.Default.Tune, -100f..100f, 1f, "slider_tint", "tool_tint"),
    VIBRANCE("Vibrance", Icons.Default.InvertColors, -100f..100f, 1f, "slider_vibrance", "tool_vibrance"),
    SATURATION("Saturation", Icons.Default.Palette, -100f..100f, 1f, "slider_saturation", "tool_saturation"),
    TEXTURE("Texture", Icons.Default.Texture, -100f..100f, 1f, "slider_texture", "tool_texture"),
    CLARITY("Clarity", Icons.Default.Details, -100f..100f, 1f, "slider_clarity", "tool_clarity"),
    DEHAZE("Dehaze", Icons.Default.BlurOn, -100f..100f, 1f, "slider_dehaze", "tool_dehaze"),
    VIGNETTE("Vignette", Icons.Default.Vignette, -100f..100f, 1f, "slider_vignette", "tool_vignette"),
    SHARPEN("Sharpen", Icons.Default.CenterFocusStrong, 0f..150f, 1f, "slider_sharpening", "tool_sharpen"),
    GRAIN("Grain", Icons.Default.Grain, 0f..100f, 1f, "slider_grain", "tool_grain"),
    HALATION("Halation", Icons.Default.WbTwilight, 0f..100f, 1f, "slider_halation", "tool_halation"),
    BLOOM("Bloom", Icons.Default.BlurCircular, 0f..100f, 1f, "slider_bloom", "tool_bloom"),
    CHROMATIC_ABERRATION("Chromatic Aberration", Icons.Default.FilterTiltShift, -50f..50f, 1f, "slider_chromatic_aberration", "tool_chromatic_aberration", "Chroma"),
    ANAMORPHIC_STREAK("Anamorphic Streak", Icons.Default.BlurLinear, 0f..100f, 1f, "slider_anamorphic_streak", "tool_anamorphic_streak", "Streak")
}

private sealed interface StripEntry {
    data object Auto : StripEntry
    data class Slider(val tool: FocusSliderTool) : StripEntry
    data object Divider : StripEntry
    data object BlackAndWhite : StripEntry
    data object Curve : StripEntry
    data object HslColor : StripEntry
    data object Profile : StripEntry
}

private val AdjustStripEntries: List<StripEntry> = listOf(
    StripEntry.Auto,
    StripEntry.Slider(FocusSliderTool.EXPOSURE),
    StripEntry.Slider(FocusSliderTool.CONTRAST),
    StripEntry.Slider(FocusSliderTool.HIGHLIGHTS),
    StripEntry.Slider(FocusSliderTool.SHADOWS),
    StripEntry.Slider(FocusSliderTool.WHITES),
    StripEntry.Slider(FocusSliderTool.BLACKS),
    StripEntry.Slider(FocusSliderTool.TEMP),
    StripEntry.Slider(FocusSliderTool.TINT),
    StripEntry.Slider(FocusSliderTool.VIBRANCE),
    StripEntry.Slider(FocusSliderTool.SATURATION),
    StripEntry.Slider(FocusSliderTool.SHARPEN),
    StripEntry.Divider,
    StripEntry.Slider(FocusSliderTool.TEXTURE),
    StripEntry.Slider(FocusSliderTool.CLARITY),
    StripEntry.Slider(FocusSliderTool.DEHAZE),
    StripEntry.Slider(FocusSliderTool.VIGNETTE),
    StripEntry.Slider(FocusSliderTool.GRAIN),
    StripEntry.Slider(FocusSliderTool.HALATION),
    StripEntry.Slider(FocusSliderTool.BLOOM),
    StripEntry.Slider(FocusSliderTool.CHROMATIC_ABERRATION),
    StripEntry.Slider(FocusSliderTool.ANAMORPHIC_STREAK),
    StripEntry.BlackAndWhite,
    StripEntry.Curve,
    StripEntry.HslColor,
    StripEntry.Profile
)

/**
 * Single-Slider Focus Dock for the Adjust tab.
 *
 * Replaces long vertical accordion lists with a focused one-handed workflow:
 * 1. One large precision ruler-style slider above the strip with live numeric readout and double-tap reset.
 * 2. One horizontal scrollable brushed-gold icon strip for instant tool switching.
 * 3. Advanced multi-control tools (Tone Curve, HSL Color Mixer, Camera Profiles) launch cleanly in sheets.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdjustPanel(
    params: GradingParams,
    histogram: HistogramData?,
    activeEyedropperMode: EyedropperMode,
    onParamsChange: ((GradingParams) -> GradingParams) -> Unit,
    onDraggingChanged: (Boolean) -> Unit,
    onOpenProfileMenu: () -> Unit,
    onSelectWhiteBalancePreset: (WhiteBalancePreset) -> Unit,
    onToggleEyedropper: (EyedropperMode) -> Unit,
    onAutoTone: () -> Unit,
    onToggleBlackAndWhite: () -> Unit,
    onUpdateChromaticAberration: (Float) -> Unit = { v ->
        onParamsChange { it.copy(chromaticAberration = v.coerceIn(-50f, 50f)) }
    },
    onResetChromaticAberration: () -> Unit = {
        onDraggingChanged(false)
        onParamsChange { it.copy(chromaticAberration = 0f) }
    },
    onUpdateAnamorphicStreak: (Float) -> Unit = { v ->
        onParamsChange { it.copy(anamorphicStreak = v.coerceIn(0f, 100f)) }
    },
    onResetAnamorphicStreak: () -> Unit = {
        onDraggingChanged(false)
        onParamsChange { it.copy(anamorphicStreak = 0f) }
    },
    modifier: Modifier = Modifier
) {
    var selectedTool by remember { mutableStateOf(FocusSliderTool.EXPOSURE) }
    var autoPulseActive by remember { mutableStateOf(false) }
    var showCurveSheet by remember { mutableStateOf(false) }
    var showHslSheet by remember { mutableStateOf(false) }
    var activeCurveChannel by remember { mutableStateOf(ToneCurveChannel.MASTER) }
    var activeHslBand by remember { mutableStateOf(HslBand.RED) }
    var isHslExpanded by remember { mutableStateOf(true) }
    var isPointColorExpanded by remember { mutableStateOf(false) }
    var isColorGradingExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(autoPulseActive) {
        if (autoPulseActive) {
            delay(480L)
            autoPulseActive = false
        }
    }

    val activeValue = params.valueFor(selectedTool)
    val activeModified = params.isModified(selectedTool)
    val formattedValue = formatToolValue(selectedTool, activeValue)
    val gradientColors = gradientForTool(selectedTool)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. Single Wide Scrolling Tick-Mark Ruler Slider
        RulerPrecisionSlider(
            label = selectedTool.label,
            icon = selectedTool.icon,
            value = activeValue,
            valueRange = selectedTool.valueRange,
            step = selectedTool.step,
            formattedValue = formattedValue,
            isModified = activeModified,
            gradientColors = gradientColors,
            testTag = selectedTool.testTag,
            onValueChange = { next ->
                onDraggingChanged(true)
                when (selectedTool) {
                    FocusSliderTool.CHROMATIC_ABERRATION -> onUpdateChromaticAberration(next)
                    FocusSliderTool.ANAMORPHIC_STREAK -> onUpdateAnamorphicStreak(next)
                    else -> onParamsChange { it.withToolValue(selectedTool, next) }
                }
            },
            onValueChangeFinished = { onDraggingChanged(false) },
            onReset = {
                when (selectedTool) {
                    FocusSliderTool.CHROMATIC_ABERRATION -> onResetChromaticAberration()
                    FocusSliderTool.ANAMORPHIC_STREAK -> onResetAnamorphicStreak()
                    else -> {
                        onDraggingChanged(false)
                        onParamsChange { it.withToolValue(selectedTool, 0f) }
                    }
                }
            },
            modifier = Modifier.padding(horizontal = 12.dp)
        )

        // 2. Horizontal Scrollable Icon Strip (Auto, Exposure, Contrast, ..., Saturation + Pro Tools)
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("adjust_tool_strip"),
            contentPadding = PaddingValues(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items(
                items = AdjustStripEntries,
                key = { entry ->
                    when (entry) {
                        StripEntry.Auto -> "auto"
                        is StripEntry.Slider -> entry.tool.name
                        StripEntry.Divider -> "divider"
                        StripEntry.BlackAndWhite -> "bw"
                        StripEntry.Curve -> "curve"
                        StripEntry.HslColor -> "hsl"
                        StripEntry.Profile -> "profile"
                    }
                }
            ) { entry ->
                when (entry) {
                    StripEntry.Auto -> {
                        FocusToolTile(
                            label = "Auto",
                            icon = Icons.Default.AutoAwesome,
                            selected = autoPulseActive,
                            hasActiveEdit = false,
                            testTag = "adjust_auto_button",
                            onClick = {
                                autoPulseActive = true
                                onAutoTone()
                            }
                        )
                    }

                    is StripEntry.Slider -> {
                        FocusToolTile(
                            label = entry.tool.shortLabel,
                            icon = entry.tool.icon,
                            selected = selectedTool == entry.tool,
                            hasActiveEdit = params.isModified(entry.tool),
                            testTag = entry.tool.tileTag,
                            contentDescription = entry.tool.label,
                            onClick = { selectedTool = entry.tool },
                            onDoubleTapReset = {
                                selectedTool = entry.tool
                                when (entry.tool) {
                                    FocusSliderTool.CHROMATIC_ABERRATION -> onResetChromaticAberration()
                                    FocusSliderTool.ANAMORPHIC_STREAK -> onResetAnamorphicStreak()
                                    else -> {
                                        onDraggingChanged(false)
                                        onParamsChange { it.withToolValue(entry.tool, 0f) }
                                    }
                                }
                            }
                        )
                    }

                    StripEntry.Divider -> {
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 2.dp)
                                .width(1.dp)
                                .height(34.dp)
                                .background(Color.White.copy(alpha = 0.10f))
                        )
                    }

                    StripEntry.BlackAndWhite -> {
                        FocusToolTile(
                            label = "B&W",
                            icon = Icons.Default.FilterBAndW,
                            selected = params.isGrayscale,
                            hasActiveEdit = params.isGrayscale,
                            testTag = "adjust_bw_button",
                            onClick = onToggleBlackAndWhite
                        )
                    }

                    StripEntry.Curve -> {
                        FocusToolTile(
                            label = "Curve",
                            icon = Icons.AutoMirrored.Filled.ShowChart,
                            selected = showCurveSheet,
                            hasActiveEdit = !params.toneCurve.isDefault,
                            testTag = "subpanel_curve",
                            onClick = { showCurveSheet = true }
                        )
                    }

                    StripEntry.HslColor -> {
                        val hasAdvancedColor = !params.hsl.isDefault ||
                            params.pointColor.isModified ||
                            !params.colorGrading.isDefault
                        FocusToolTile(
                            label = "HSL",
                            icon = Icons.Default.Palette,
                            selected = showHslSheet,
                            hasActiveEdit = hasAdvancedColor,
                            testTag = "tool_hsl_mixer",
                            onClick = { showHslSheet = true }
                        )
                    }

                    StripEntry.Profile -> {
                        FocusToolTile(
                            label = "Profile",
                            icon = Icons.Default.Camera,
                            selected = false,
                            hasActiveEdit = params.hasProfileEdits,
                            testTag = "tool_profile",
                            onClick = onOpenProfileMenu
                        )
                    }
                }
            }
        }
    }

    // Tone Curve Sheet (RGB, Red, Green, Blue spline editor)
    if (showCurveSheet) {
        ModalBottomSheet(
            onDismissRequest = { showCurveSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = StudioSurface,
            contentColor = StudioTextPrimary
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Tone Curve",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = StudioTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (!params.toneCurve.isDefault) {
                        Box(
                            modifier = Modifier
                                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    onDraggingChanged(false)
                                    onParamsChange { it.copy(toneCurve = ToneCurveData()) }
                                }
                                .padding(horizontal = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Reset All",
                                style = MaterialTheme.typography.labelMedium,
                                color = StudioLuxuryGold,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
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
    }

    // Advanced Color & HSL Mixer Sheet
    if (showHslSheet) {
        ModalBottomSheet(
            onDismissRequest = { showHslSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = StudioSurface,
            contentColor = StudioTextPrimary
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Color & HSL Mixer",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = StudioTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (params.hasColorEdits) {
                        Box(
                            modifier = Modifier
                                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    onDraggingChanged(false)
                                    onParamsChange { it.resetColor() }
                                }
                                .padding(horizontal = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Reset Color",
                                style = MaterialTheme.typography.labelMedium,
                                color = StudioLuxuryGold,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
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
                    onToggleEyedropper = { mode ->
                        showHslSheet = false
                        onToggleEyedropper(mode)
                    },
                    onParamsChange = onParamsChange,
                    onDraggingChanged = onDraggingChanged
                )
            }
        }
    }
}

@Composable
private fun FocusToolTile(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    hasActiveEdit: Boolean,
    testTag: String,
    contentDescription: String = label,
    onClick: () -> Unit,
    onDoubleTapReset: (() -> Unit)? = null
) {
    val containerColor by animateColorAsState(
        targetValue = if (selected) StudioGlassHighlight else StudioGlassSurface,
        animationSpec = tween(durationMillis = 150),
        label = "tool_tile_bg"
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            selected -> StudioActiveOffWhite.copy(alpha = 0.28f)
            else -> StudioGlassHairline
        },
        animationSpec = tween(durationMillis = 150),
        label = "tool_tile_border"
    )
    val contentTint by animateColorAsState(
        targetValue = when {
            selected -> StudioActiveOffWhite
            hasActiveEdit -> StudioActiveOffWhite.copy(alpha = 0.86f)
            else -> StudioTextSecondary
        },
        animationSpec = tween(durationMillis = 150),
        label = "tool_tile_tint"
    )

    Column(
        modifier = Modifier
            .width(60.dp)
            .height(64.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(containerColor)
            .border(
                width = 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(14.dp)
            )
            .tapAndDoubleTapReset(
                onTap = onClick,
                onDoubleTapReset = onDoubleTapReset
            )
            .padding(horizontal = 3.dp, vertical = 6.dp)
            .testTag(testTag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = contentTint,
            modifier = Modifier.size(19.dp)
        )
        Box(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                letterSpacing = (-0.1).sp
            ),
            color = contentTint,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
        Box(modifier = Modifier.height(4.dp))
        // Subtle warm-gold indicator dot under any modified tool (reserves height so icons stay aligned)
        Box(
            modifier = Modifier
                .size(4.dp)
                .clip(CircleShape)
                .background(if (hasActiveEdit) StudioLuxuryGold else Color.Transparent)
        )
    }
}

internal fun GradingParams.valueFor(tool: FocusSliderTool): Float = when (tool) {
    FocusSliderTool.EXPOSURE -> exposure
    FocusSliderTool.CONTRAST -> contrast
    FocusSliderTool.HIGHLIGHTS -> highlights
    FocusSliderTool.SHADOWS -> shadows
    FocusSliderTool.WHITES -> whites
    FocusSliderTool.BLACKS -> blacks
    FocusSliderTool.TEMP -> temperature
    FocusSliderTool.TINT -> tint
    FocusSliderTool.VIBRANCE -> vibrance
    FocusSliderTool.SATURATION -> saturation
    FocusSliderTool.TEXTURE -> texture
    FocusSliderTool.CLARITY -> clarity
    FocusSliderTool.DEHAZE -> dehaze
    FocusSliderTool.VIGNETTE -> vignette
    FocusSliderTool.SHARPEN -> sharpening
    FocusSliderTool.GRAIN -> grain
    FocusSliderTool.HALATION -> halation
    FocusSliderTool.BLOOM -> bloom
    FocusSliderTool.CHROMATIC_ABERRATION -> chromaticAberration
    FocusSliderTool.ANAMORPHIC_STREAK -> anamorphicStreak
}

internal fun GradingParams.isModified(tool: FocusSliderTool): Boolean =
    abs(valueFor(tool)) > 0.001f

internal fun GradingParams.withToolValue(tool: FocusSliderTool, value: Float): GradingParams = when (tool) {
    FocusSliderTool.EXPOSURE -> copy(exposure = value)
    FocusSliderTool.CONTRAST -> copy(contrast = value)
    FocusSliderTool.HIGHLIGHTS -> copy(highlights = value)
    FocusSliderTool.SHADOWS -> copy(shadows = value)
    FocusSliderTool.WHITES -> copy(whites = value)
    FocusSliderTool.BLACKS -> copy(blacks = value)
    FocusSliderTool.TEMP -> copy(temperature = value)
    FocusSliderTool.TINT -> copy(tint = value)
    FocusSliderTool.VIBRANCE -> copy(vibrance = value)
    FocusSliderTool.SATURATION -> copy(saturation = value)
    FocusSliderTool.TEXTURE -> copy(texture = value)
    FocusSliderTool.CLARITY -> copy(clarity = value)
    FocusSliderTool.DEHAZE -> copy(dehaze = value)
    FocusSliderTool.VIGNETTE -> copy(vignette = value)
    FocusSliderTool.SHARPEN -> copy(sharpening = value)
    FocusSliderTool.GRAIN -> copy(grain = value)
    FocusSliderTool.HALATION -> copy(halation = value.coerceIn(0f, 100f))
    FocusSliderTool.BLOOM -> copy(bloom = value.coerceIn(0f, 100f))
    FocusSliderTool.CHROMATIC_ABERRATION -> copy(chromaticAberration = value.coerceIn(-50f, 50f))
    FocusSliderTool.ANAMORPHIC_STREAK -> copy(anamorphicStreak = value.coerceIn(0f, 100f))
}

internal fun formatToolValue(tool: FocusSliderTool, value: Float): String = when (tool) {
    FocusSliderTool.EXPOSURE -> String.format(Locale.US, "%+.2f EV", value)
    FocusSliderTool.SHARPEN,
    FocusSliderTool.GRAIN,
    FocusSliderTool.HALATION,
    FocusSliderTool.BLOOM,
    FocusSliderTool.ANAMORPHIC_STREAK -> "${value.roundToInt()}"
    else -> String.format(Locale.US, "%+d", value.roundToInt())
}

internal fun gradientForTool(tool: FocusSliderTool): List<Color>? = when (tool) {
    FocusSliderTool.TEMP -> listOf(
        Color(0xFF3B82F6),
        Color(0xFF8FA3BF),
        Color(0xFFF5B841)
    )
    FocusSliderTool.TINT -> listOf(
        Color(0xFF22C55E),
        Color(0xFF9CA3AF),
        Color(0xFFD946EF)
    )
    FocusSliderTool.VIBRANCE -> listOf(
        Color(0xFF6B7280),
        Color(0xFFA78BFA),
        Color(0xFFF5B841)
    )
    FocusSliderTool.SATURATION -> listOf(
        Color(0xFF6B7280),
        Color(0xFF38BDF8),
        Color(0xFFF59E0B),
        Color(0xFFEF4444)
    )
    FocusSliderTool.HALATION -> listOf(
        Color(0xFF475569),
        Color(0xFFE05336),
        Color(0xFFFFB74D)
    )
    FocusSliderTool.BLOOM -> listOf(
        Color(0xFF475569),
        Color(0xFFFDE68A),
        Color(0xFFFFFBEB)
    )
    FocusSliderTool.CHROMATIC_ABERRATION -> listOf(
        Color(0xFF06B6D4),
        Color(0xFF9CA3AF),
        Color(0xFFEC4899)
    )
    FocusSliderTool.ANAMORPHIC_STREAK -> listOf(
        Color(0xFF334155),
        Color(0xFF0EA5E9),
        Color(0xFF7DD3FC)
    )
    else -> null
}

@Composable
internal fun ToneCurveEditor(
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
    val haptic = LocalHapticFeedback.current
    val currentPoints by rememberUpdatedState(points)
    val currentChannel by rememberUpdatedState(activeChannel)
    val currentToneCurve by rememberUpdatedState(toneCurve)
    val currentOnResetChannel by rememberUpdatedState(onResetChannel)
    val curveLut = remember(points) { ToneCurveData.buildLut(points) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
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
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) dotColor.copy(alpha = 0.16f) else Color.Transparent)
                            .border(
                                width = 1.dp,
                                color = if (selected) dotColor.copy(alpha = 0.75f) else StudioBorderSubtle,
                                shape = RoundedCornerShape(10.dp)
                            )
                            .tapAndDoubleTapReset(
                                onTap = { onSelectChannel(ch) },
                                onDoubleTapReset = {
                                    onSelectChannel(ch)
                                    onResetChannel(ch)
                                }
                            )
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .testTag("curve_channel_${ch.name.lowercase()}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
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
                                color = if (selected) dotColor else StudioTextSecondary,
                                maxLines = 1,
                                softWrap = false
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
                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
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
                    tint = if (toneCurve.isChannelModified(activeChannel)) StudioLuxuryGold else StudioTextMuted,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Box(
            modifier = Modifier
                .widthIn(max = 260.dp)
                .fillMaxWidth()
                .aspectRatio(1f)
                .align(Alignment.CenterHorizontally)
                .clip(RoundedCornerShape(14.dp))
                .background(StudioObsidian)
                .border(1.dp, StudioBorder, RoundedCornerShape(14.dp))
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
                                val nearExisting = pts.any { hypot(it.x - nx, it.y - ny) < 0.08f }
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
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
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
                                } else if (currentToneCurve.isChannelModified(currentChannel)) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    currentOnResetChannel(currentChannel)
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

                drawLine(
                    color = Color.White.copy(alpha = 0.18f),
                    start = Offset(0f, h),
                    end = Offset(w, 0f),
                    strokeWidth = 1.dp.toPx()
                )

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

                points.forEach { pt ->
                    val center = Offset(pt.x * w, (1f - pt.y) * h)
                    drawCircle(color = StudioObsidian, radius = 6.dp.toPx(), center = center)
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
            text = "Tap to add • Drag to shape • Double-tap point or channel to reset",
            style = MaterialTheme.typography.labelSmall,
            color = StudioTextMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
    }
}
