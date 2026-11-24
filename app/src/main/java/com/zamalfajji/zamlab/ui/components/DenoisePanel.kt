package com.zamalfajji.zamlab.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zamalfajji.zamlab.R
import com.zamalfajji.zamlab.ai.LocalDenoiseEngine
import com.zamalfajji.zamlab.editor.DenoisePreset
import com.zamalfajji.zamlab.editor.GradingParams
import com.zamalfajji.zamlab.ui.theme.StudioAmberPillFill
import com.zamalfajji.zamlab.ui.theme.StudioBorderSubtle
import com.zamalfajji.zamlab.ui.theme.StudioCinemaAmber
import com.zamalfajji.zamlab.ui.theme.StudioCoolWhite
import com.zamalfajji.zamlab.ui.theme.StudioGraphite
import com.zamalfajji.zamlab.ui.theme.StudioHairline
import com.zamalfajji.zamlab.ui.theme.StudioMutedSlate
import com.zamalfajji.zamlab.ui.theme.StudioSurfaceElevated
import com.zamalfajji.zamlab.ui.theme.StudioSurfaceHighest
import com.zamalfajji.zamlab.ui.theme.StudioTextMuted
import com.zamalfajji.zamlab.ui.theme.StudioTextPrimary
import com.zamalfajji.zamlab.ui.theme.StudioTextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

/**
 * Dark-minimalist non-destructive RAW/photo Denoise panel modeled after Adobe Lightroom Classic's
 * Detail / Denoise pipeline:
 * - One-tap presets ("Off", "Subtle", "Natural", "High ISO / Night", "AI Clean")
 * - Press-and-hold "Before / After" split comparison button (hold = Original, tap = Split View)
 * - 5 precision sliders (0..100) with double-tap reset and numeric tabular readouts:
 *   Luminance, Detail, Contrast, Color, and Smoothness.
 */
@Composable
fun DenoisePanel(
    params: GradingParams,
    isComparingOriginal: Boolean,
    isSplitCompareEnabled: Boolean,
    isProcessing: Boolean,
    progress: Float,
    onSelectPreset: (DenoisePreset) -> Unit,
    onParamsChange: ((GradingParams) -> GradingParams) -> Unit,
    onDraggingChanged: (Boolean) -> Unit,
    onCompareChanged: (Boolean) -> Unit,
    onToggleSplitCompare: () -> Unit,
    modifier: Modifier = Modifier
) {
    val activePreset = params.activeDenoisePreset()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag("denoise_panel"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Row 1: One-tap presets + Press-and-hold "Before / After" comparison pill
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState())
                    .testTag("denoise_presets_row"),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DenoisePresetChip(
                    label = stringResource(R.string.denoise_preset_off),
                    selected = activePreset == DenoisePreset.OFF,
                    enabled = !isProcessing,
                    testTag = "denoise_preset_off",
                    onClick = { onSelectPreset(DenoisePreset.OFF) }
                )
                DenoisePresetChip(
                    label = stringResource(R.string.denoise_preset_subtle),
                    selected = activePreset == DenoisePreset.SUBTLE,
                    enabled = !isProcessing,
                    testTag = "denoise_preset_subtle",
                    onClick = { onSelectPreset(DenoisePreset.SUBTLE) }
                )
                DenoisePresetChip(
                    label = stringResource(R.string.denoise_preset_natural),
                    selected = activePreset == DenoisePreset.NATURAL,
                    enabled = !isProcessing,
                    testTag = "denoise_preset_natural",
                    onClick = { onSelectPreset(DenoisePreset.NATURAL) }
                )
                DenoisePresetChip(
                    label = stringResource(R.string.denoise_preset_high_iso),
                    selected = activePreset == DenoisePreset.HIGH_ISO_NIGHT,
                    enabled = !isProcessing,
                    testTag = "denoise_preset_high_iso",
                    onClick = { onSelectPreset(DenoisePreset.HIGH_ISO_NIGHT) }
                )
                DenoisePresetChip(
                    label = stringResource(R.string.denoise_preset_ai_clean),
                    selected = activePreset == DenoisePreset.AI_CLEAN || params.denoiseAiClean,
                    enabled = !isProcessing,
                    testTag = "denoise_preset_ai_clean",
                    onClick = { onSelectPreset(DenoisePreset.AI_CLEAN) }
                )
            }

            DenoiseBeforeAfterButton(
                isComparingOriginal = isComparingOriginal,
                isSplitCompareEnabled = isSplitCompareEnabled,
                onCompareChanged = onCompareChanged,
                onToggleSplitCompare = onToggleSplitCompare
            )
        }

        if (isProcessing) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "${stringResource(R.string.denoise_processing_ai)} ${(progress.coerceIn(0f, 1f) * 100f).roundToInt()}%",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontFeatureSettings = "tnum"
                    ),
                    color = StudioCinemaAmber,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    color = StudioCinemaAmber,
                    trackColor = StudioSurfaceHighest,
                    strokeCap = StrokeCap.Round,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .testTag("denoise_progress_bar")
                )
            }
        }

        // 5 Precision Lightroom-style Denoise Sliders (0..100) with double-tap reset
        DenoiseSliderRow(
            label = stringResource(R.string.denoise_slider_luminance),
            value = params.noiseReduction,
            defaultValue = LocalDenoiseEngine.DEFAULT_LUMINANCE,
            enabled = !isProcessing,
            testTag = "denoise_slider_luminance",
            onValueChange = { v ->
                onDraggingChanged(true)
                onParamsChange { gp ->
                    val nextAi = if (v <= 0.5f && gp.colorNoiseReduction <= 0.5f) false else gp.denoiseAiClean
                    gp.copy(noiseReduction = v, denoiseAiClean = nextAi)
                }
            },
            onValueChangeFinished = { onDraggingChanged(false) },
            onReset = {
                onParamsChange { gp ->
                    val nextAi = if (gp.colorNoiseReduction <= 0.5f) false else gp.denoiseAiClean
                    gp.copy(noiseReduction = LocalDenoiseEngine.DEFAULT_LUMINANCE, denoiseAiClean = nextAi)
                }
            }
        )

        DenoiseSliderRow(
            label = stringResource(R.string.denoise_slider_detail),
            value = params.noiseReductionDetail,
            defaultValue = LocalDenoiseEngine.DEFAULT_DETAIL,
            enabled = !isProcessing,
            testTag = "denoise_slider_detail",
            onValueChange = { v ->
                onDraggingChanged(true)
                onParamsChange { gp -> gp.copy(noiseReductionDetail = v) }
            },
            onValueChangeFinished = { onDraggingChanged(false) },
            onReset = {
                onParamsChange { gp -> gp.copy(noiseReductionDetail = LocalDenoiseEngine.DEFAULT_DETAIL) }
            }
        )

        DenoiseSliderRow(
            label = stringResource(R.string.denoise_slider_contrast),
            value = params.noiseReductionContrast,
            defaultValue = LocalDenoiseEngine.DEFAULT_CONTRAST,
            enabled = !isProcessing,
            testTag = "denoise_slider_contrast",
            onValueChange = { v ->
                onDraggingChanged(true)
                onParamsChange { gp -> gp.copy(noiseReductionContrast = v) }
            },
            onValueChangeFinished = { onDraggingChanged(false) },
            onReset = {
                onParamsChange { gp -> gp.copy(noiseReductionContrast = LocalDenoiseEngine.DEFAULT_CONTRAST) }
            }
        )

        DenoiseSliderRow(
            label = stringResource(R.string.denoise_slider_color),
            value = params.colorNoiseReduction,
            defaultValue = LocalDenoiseEngine.DEFAULT_COLOR,
            enabled = !isProcessing,
            testTag = "denoise_slider_color",
            onValueChange = { v ->
                onDraggingChanged(true)
                onParamsChange { gp ->
                    val nextAi = if (v <= 0.5f && gp.noiseReduction <= 0.5f) false else gp.denoiseAiClean
                    gp.copy(colorNoiseReduction = v, denoiseAiClean = nextAi)
                }
            },
            onValueChangeFinished = { onDraggingChanged(false) },
            onReset = {
                onParamsChange { gp -> gp.copy(colorNoiseReduction = LocalDenoiseEngine.DEFAULT_COLOR) }
            }
        )

        DenoiseSliderRow(
            label = stringResource(R.string.denoise_slider_smoothness),
            value = params.colorNoiseReductionSmoothness,
            defaultValue = LocalDenoiseEngine.DEFAULT_SMOOTHNESS,
            enabled = !isProcessing,
            testTag = "denoise_slider_smoothness",
            onValueChange = { v ->
                onDraggingChanged(true)
                onParamsChange { gp -> gp.copy(colorNoiseReductionSmoothness = v) }
            },
            onValueChangeFinished = { onDraggingChanged(false) },
            onReset = {
                onParamsChange { gp -> gp.copy(colorNoiseReductionSmoothness = LocalDenoiseEngine.DEFAULT_SMOOTHNESS) }
            }
        )
    }
}

@Composable
private fun DenoisePresetChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    val bgColor by animateColorAsState(
        targetValue = if (selected) StudioAmberPillFill else StudioSurfaceElevated,
        animationSpec = tween(durationMillis = 150),
        label = "denoise_chip_bg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) StudioCinemaAmber else StudioBorderSubtle,
        animationSpec = tween(durationMillis = 150),
        label = "denoise_chip_border"
    )
    val textColor by animateColorAsState(
        targetValue = if (selected) StudioCinemaAmber else StudioTextPrimary,
        animationSpec = tween(durationMillis = 150),
        label = "denoise_chip_text"
    )
    val shape = RoundedCornerShape(10.dp)

    Box(
        modifier = Modifier
            .defaultMinSize(minHeight = 48.dp)
            .minimumInteractiveComponentSize()
            .clip(shape)
            .background(bgColor)
            .border(1.dp, borderColor, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                letterSpacing = 0.2.sp
            ),
            color = if (enabled) textColor else StudioTextMuted,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun DenoiseBeforeAfterButton(
    isComparingOriginal: Boolean,
    isSplitCompareEnabled: Boolean,
    onCompareChanged: (Boolean) -> Unit,
    onToggleSplitCompare: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val currentCompareChanged by rememberUpdatedState(onCompareChanged)
    val currentToggleSplit by rememberUpdatedState(onToggleSplitCompare)
    val active = isComparingOriginal || isSplitCompareEnabled
    val compareCd = stringResource(R.string.cd_denoise_compare)

    val bgColor by animateColorAsState(
        targetValue = if (active) StudioAmberPillFill else StudioSurfaceElevated,
        animationSpec = tween(durationMillis = 150),
        label = "denoise_compare_bg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (active) StudioCinemaAmber else StudioBorderSubtle,
        animationSpec = tween(durationMillis = 150),
        label = "denoise_compare_border"
    )
    val tintColor by animateColorAsState(
        targetValue = if (active) StudioCinemaAmber else StudioCoolWhite,
        animationSpec = tween(durationMillis = 150),
        label = "denoise_compare_tint"
    )
    val shape = RoundedCornerShape(10.dp)

    Row(
        modifier = Modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .minimumInteractiveComponentSize()
            .clip(shape)
            .background(bgColor)
            .border(1.dp, borderColor, shape)
            .semantics { contentDescription = compareCd }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var holdingOriginal = false
                    val holdJob = scope.launch {
                        delay(160L.milliseconds)
                        holdingOriginal = true
                        currentCompareChanged(true)
                    }
                    try {
                        do {
                            val event = awaitPointerEvent()
                        } while (event.changes.any { it.pressed })
                    } finally {
                        holdJob.cancel()
                        if (holdingOriginal) {
                            currentCompareChanged(false)
                        } else {
                            currentToggleSplit()
                        }
                    }
                }
            }
            .padding(horizontal = 10.dp, vertical = 10.dp)
            .testTag("denoise_compare_button"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = StudioIcons.SplitCompare,
            contentDescription = null,
            tint = tintColor,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = stringResource(R.string.denoise_compare_label),
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.2.sp
            ),
            color = tintColor,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun DenoiseSliderRow(
    label: String,
    value: Float,
    defaultValue: Float,
    enabled: Boolean,
    testTag: String,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    onReset: () -> Unit
) {
    val isModified = abs(value - defaultValue) > 0.5f
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .doubleTapToReset(
                    enabled = enabled,
                    onDoubleTapReset = onReset
                )
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
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.2.sp
                    ),
                    color = if (isModified) StudioCoolWhite else StudioCoolWhite.copy(alpha = 0.88f),
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
                modifier = Modifier
                    .border(
                        width = 1.dp,
                        color = if (isModified) StudioCinemaAmber.copy(alpha = 0.72f) else StudioHairline,
                        shape = RoundedCornerShape(8.dp)
                    )
                    .testTag("${testTag}_value")
            ) {
                Text(
                    text = value.roundToInt().toString(),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontFeatureSettings = "tnum"
                    ),
                    color = if (isModified) StudioCoolWhite else StudioMutedSlate,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
        StudioPrecisionTrack(
            value = value,
            valueRange = 0f..100f,
            step = 1f,
            enabled = enabled,
            accentColor = StudioCinemaAmber,
            compact = true,
            testTag = testTag,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            onReset = onReset
        )
    }
}
