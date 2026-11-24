package com.zamalfajji.zamlab.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zamalfajji.zamlab.R
import com.zamalfajji.zamlab.ai.LocalUpscaleEngine.UpscaleScale
import com.zamalfajji.zamlab.ui.theme.StudioAmberPillFill
import com.zamalfajji.zamlab.ui.theme.StudioBorderSubtle
import com.zamalfajji.zamlab.ui.theme.StudioCharcoal
import com.zamalfajji.zamlab.ui.theme.StudioCinemaAmber
import com.zamalfajji.zamlab.ui.theme.StudioSurfaceElevated
import com.zamalfajji.zamlab.ui.theme.StudioSurfaceHighest
import com.zamalfajji.zamlab.ui.theme.StudioTextMuted
import com.zamalfajji.zamlab.ui.theme.StudioTextPrimary
import com.zamalfajji.zamlab.ui.theme.StudioTextSecondary
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Compact dark-minimalist offline super-resolution panel for 2x and 4x upscaling.
 * Shows scale selection pills ("2x" / "4x"), current vs. target pixel dimensions,
 * an "Apply Upscale" CTA button, and live tiled progress feedback.
 */
@Composable
fun UpscalePanel(
    selectedScale: UpscaleScale,
    currentWidth: Int,
    currentHeight: Int,
    isProcessing: Boolean,
    progress: Float,
    isAiModelAvailable: Boolean,
    onSelectScale: (UpscaleScale) -> Unit,
    onApplyUpscale: () -> Unit,
    modifier: Modifier = Modifier
) {
    val safeW = currentWidth.coerceAtLeast(0)
    val safeH = currentHeight.coerceAtLeast(0)
    val targetW = safeW * selectedScale.factor
    val targetH = safeH * selectedScale.factor
    val hasPhoto = safeW > 0 && safeH > 0

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag("upscale_panel"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Row 1: 2x / 4x scale selector buttons + offline engine status badge
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            UpscaleScalePill(
                label = stringResource(R.string.upscale_scale_2x),
                contentDesc = stringResource(R.string.cd_upscale_2x),
                selected = selectedScale == UpscaleScale.X2,
                enabled = !isProcessing,
                onClick = { onSelectScale(UpscaleScale.X2) },
                modifier = Modifier
                    .weight(1f)
                    .testTag("upscale_2x_button")
            )
            UpscaleScalePill(
                label = stringResource(R.string.upscale_scale_4x),
                contentDesc = stringResource(R.string.cd_upscale_4x),
                selected = selectedScale == UpscaleScale.X4,
                enabled = !isProcessing,
                onClick = { onSelectScale(UpscaleScale.X4) },
                modifier = Modifier
                    .weight(1f)
                    .testTag("upscale_4x_button")
            )
        }

        // Row 2: Current vs. Target output pixel dimensions readout
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = StudioSurfaceElevated,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, StudioBorderSubtle, RoundedCornerShape(12.dp))
                .testTag("upscale_dimensions_readout")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                DimensionReadoutBlock(
                    caption = stringResource(R.string.upscale_current_label),
                    width = safeW,
                    height = safeH,
                    highlighted = false,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = stringResource(R.string.cd_upscale_arrow),
                    tint = StudioCinemaAmber,
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .size(18.dp)
                )
                DimensionReadoutBlock(
                    caption = stringResource(R.string.upscale_target_label),
                    width = targetW,
                    height = targetH,
                    highlighted = true,
                    horizontalAlignment = Alignment.End,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Row 3: Apply Upscale action button + inline progress bar while processing
        val applyCd = stringResource(R.string.cd_upscale_apply)
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Button(
                onClick = onApplyUpscale,
                enabled = hasPhoto && !isProcessing,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = StudioCinemaAmber,
                    contentColor = StudioCharcoal,
                    disabledContainerColor = StudioSurfaceHighest,
                    disabledContentColor = StudioTextSecondary
                ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .semantics { contentDescription = applyCd }
                    .testTag("upscale_apply_button")
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = StudioCinemaAmber
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    val pct = (progress.coerceIn(0f, 1f) * 100f).roundToInt()
                    Text(
                        text = "${stringResource(R.string.upscale_processing_label)} $pct%",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontFeatureSettings = "tnum"
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Icon(
                        imageVector = StudioIcons.TabUpscale,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${stringResource(R.string.upscale_apply_action)} (${selectedScale.label})",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (isProcessing) {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    color = StudioCinemaAmber,
                    trackColor = StudioSurfaceHighest,
                    strokeCap = StrokeCap.Round,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .testTag("upscale_progress_bar")
                )
            } else {
                Text(
                    text = if (isAiModelAvailable) {
                        stringResource(R.string.upscale_engine_onnx)
                    } else {
                        stringResource(R.string.upscale_engine_lanczos)
                    },
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 11.sp,
                        letterSpacing = 0.2.sp
                    ),
                    color = StudioTextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .testTag("upscale_engine_label")
                )
            }
        }
    }
}

@Composable
private fun UpscaleScalePill(
    label: String,
    contentDesc: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor by animateColorAsState(
        targetValue = if (selected) StudioAmberPillFill else StudioSurfaceElevated,
        animationSpec = tween(durationMillis = 150),
        label = "upscale_pill_bg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) StudioCinemaAmber else StudioBorderSubtle,
        animationSpec = tween(durationMillis = 150),
        label = "upscale_pill_border"
    )
    val textColor by animateColorAsState(
        targetValue = if (selected) StudioCinemaAmber else StudioTextPrimary,
        animationSpec = tween(durationMillis = 150),
        label = "upscale_pill_text"
    )
    val shape = RoundedCornerShape(12.dp)

    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .minimumInteractiveComponentSize()
            .clip(shape)
            .background(bgColor)
            .border(1.2.dp, borderColor, shape)
            .semantics { contentDescription = contentDesc }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            ),
            color = if (enabled) textColor else StudioTextMuted,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun DimensionReadoutBlock(
    caption: String,
    width: Int,
    height: Int,
    highlighted: Boolean,
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start
) {
    val dimsText = if (width > 0 && height > 0) "$width × $height px" else "—"
    val mpText = if (width > 0 && height > 0) {
        val mp = (width.toLong() * height.toLong()) / 1_000_000.0
        String.format(Locale.US, "%.1f MP", mp)
    } else {
        ""
    }

    Column(
        modifier = modifier,
        horizontalAlignment = horizontalAlignment,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = caption,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.7.sp
            ),
            color = if (highlighted) StudioCinemaAmber else StudioTextMuted,
            maxLines = 1
        )
        Text(
            text = dimsText,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.SemiBold,
                fontFeatureSettings = "tnum"
            ),
            color = if (highlighted) StudioTextPrimary else StudioTextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (mpText.isNotEmpty()) {
            Text(
                text = mpText,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    fontFeatureSettings = "tnum"
                ),
                color = StudioTextMuted,
                maxLines = 1
            )
        }
    }
}
