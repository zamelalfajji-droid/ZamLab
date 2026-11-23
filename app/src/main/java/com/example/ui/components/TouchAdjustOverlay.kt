package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.InvertColors
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zamalfajji.zamlab.R
import com.example.editor.HslBand
import com.example.editor.HslBandAdjustment
import com.example.ui.theme.StudioAmber
import com.example.ui.theme.StudioBackground
import com.example.ui.theme.StudioBorder
import com.example.ui.theme.StudioLuxuryGold
import com.example.ui.theme.StudioObsidian
import com.example.ui.theme.StudioSurfaceElevated
import com.example.ui.theme.StudioTextPrimary
import com.example.ui.theme.StudioTextSecondary
import java.util.Locale
import kotlin.math.abs

@Composable
internal fun TouchAdjustToggleButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val toggleDescription = stringResource(R.string.cd_touch_adjust_toggle)
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (enabled) StudioLuxuryGold else StudioObsidian.copy(alpha = 0.78f),
        shadowElevation = if (enabled) 6.dp else 2.dp,
        modifier = modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .border(
                width = 1.dp,
                color = if (enabled) StudioLuxuryGold else Color.White.copy(alpha = 0.14f),
                shape = RoundedCornerShape(50)
            )
            .semantics { contentDescription = toggleDescription }
            .testTag("touch_adjust_toggle_button")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                imageVector = Icons.Default.TouchApp,
                contentDescription = null,
                tint = if (enabled) StudioObsidian else StudioLuxuryGold,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = stringResource(R.string.action_touch_adjust),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (enabled) FontWeight.SemiBold else FontWeight.Medium,
                color = if (enabled) StudioObsidian else StudioTextPrimary
            )
        }
    }
}

@Composable
internal fun TouchAdjustSwipeHud(
    visible: Boolean,
    label: String?,
    valueText: String?,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible && label != null && valueText != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = StudioSurfaceElevated.copy(alpha = 0.95f),
            shadowElevation = 8.dp,
            modifier = Modifier
                .border(1.2.dp, StudioAmber, RoundedCornerShape(24.dp))
                .testTag("touch_adjust_hud")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(StudioAmber)
                )
                Text(
                    text = (label ?: "").uppercase(Locale.US),
                    style = MaterialTheme.typography.labelMedium,
                    color = StudioTextSecondary
                )
                Text(
                    text = valueText ?: "",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = StudioAmber
                )
            }
        }
    }
}

@Composable
internal fun TouchAdjustHintPill(
    visible: Boolean,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = StudioSurfaceElevated.copy(alpha = 0.92f),
            shadowElevation = 4.dp,
            modifier = Modifier
                .border(1.dp, StudioAmber.copy(alpha = 0.65f), RoundedCornerShape(20.dp))
                .testTag("touch_adjust_hint_pill")
        ) {
            Text(
                text = "Tap color · Drag ↑↓ tone · Drag ←→ sat",
                style = MaterialTheme.typography.labelSmall,
                color = StudioTextPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
internal fun TouchAdjustHslPillBar(
    visible: Boolean,
    band: HslBand?,
    swatchArgb: Long?,
    adjustment: HslBandAdjustment,
    onUpdateBand: ((HslBandAdjustment) -> HslBandAdjustment) -> Unit,
    onResetBand: () -> Unit,
    onDismiss: () -> Unit,
    onDraggingChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible && band != null,
        enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
        modifier = modifier
    ) {
        val activeBand = band ?: return@AnimatedVisibility
        val bandColor = Color(activeBand.swatchArgb)
        val sampledColor = Color(swatchArgb ?: activeBand.swatchArgb)
        val leftHueColor = remember(activeBand) {
            Color.hsv(((activeBand.centerHueDeg - 32f) + 360f) % 360f, 0.85f, 0.9f)
        }
        val rightHueColor = remember(activeBand) {
            Color.hsv((activeBand.centerHueDeg + 32f) % 360f, 0.85f, 0.9f)
        }

        Surface(
            shape = RoundedCornerShape(18.dp),
            color = StudioSurfaceElevated.copy(alpha = 0.95f),
            shadowElevation = 10.dp,
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .widthIn(max = 460.dp)
                .fillMaxWidth()
                .border(1.dp, bandColor.copy(alpha = 0.65f), RoundedCornerShape(18.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
                .testTag("touch_adjust_hsl_pill")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(sampledColor)
                                .border(1.5.dp, Color.White, CircleShape)
                        )
                        Text(
                            text = "${activeBand.label} HSL",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = StudioTextPrimary,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!adjustment.isDefault) {
                            IconButton(
                                onClick = {
                                    onDraggingChanged(false)
                                    onResetBand()
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("touch_adjust_pill_reset")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.RestartAlt,
                                    contentDescription = stringResource(R.string.cd_touch_adjust_reset_band),
                                    tint = bandColor,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("touch_adjust_pill_close")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.cd_touch_adjust_close),
                                tint = StudioTextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                GradientRangeSlider(
                    label = "Hue",
                    icon = Icons.Default.Palette,
                    value = adjustment.hue,
                    valueRange = -100f..100f,
                    formattedValue = formatSignedInt(adjustment.hue),
                    isModified = abs(adjustment.hue) > 0.5f,
                    accentColor = bandColor,
                    gradientColors = listOf(leftHueColor, bandColor, rightHueColor),
                    testTag = "touch_adjust_hue_slider",
                    onValueChange = { v ->
                        onDraggingChanged(true)
                        onUpdateBand { it.copy(hue = v) }
                    },
                    onValueChangeFinished = { onDraggingChanged(false) },
                    onReset = {
                        onDraggingChanged(false)
                        onUpdateBand { it.copy(hue = 0f) }
                    },
                    compact = true
                )

                GradientRangeSlider(
                    label = "Saturation",
                    icon = Icons.Default.InvertColors,
                    value = adjustment.saturation,
                    valueRange = -100f..100f,
                    formattedValue = formatSignedInt(adjustment.saturation),
                    isModified = abs(adjustment.saturation) > 0.5f,
                    accentColor = bandColor,
                    gradientColors = listOf(Color(0xFF6B7280), bandColor),
                    testTag = "touch_adjust_sat_slider",
                    onValueChange = { v ->
                        onDraggingChanged(true)
                        onUpdateBand { it.copy(saturation = v) }
                    },
                    onValueChangeFinished = { onDraggingChanged(false) },
                    onReset = {
                        onDraggingChanged(false)
                        onUpdateBand { it.copy(saturation = 0f) }
                    },
                    compact = true
                )

                GradientRangeSlider(
                    label = "Luminance",
                    icon = Icons.Default.Brightness6,
                    value = adjustment.luminance,
                    valueRange = -100f..100f,
                    formattedValue = formatSignedInt(adjustment.luminance),
                    isModified = abs(adjustment.luminance) > 0.5f,
                    accentColor = bandColor,
                    gradientColors = listOf(Color(0xFF1A1C22), bandColor, Color(0xFFF5F6F8)),
                    testTag = "touch_adjust_lum_slider",
                    onValueChange = { v ->
                        onDraggingChanged(true)
                        onUpdateBand { it.copy(luminance = v) }
                    },
                    onValueChangeFinished = { onDraggingChanged(false) },
                    onReset = {
                        onDraggingChanged(false)
                        onUpdateBand { it.copy(luminance = 0f) }
                    },
                    compact = true
                )
            }
        }
    }
}

private fun formatSignedInt(v: Float): String {
    val rounded = v.toInt()
    return when {
        rounded > 0 -> "+$rounded"
        rounded < 0 -> "$rounded"
        else -> "0"
    }
}
