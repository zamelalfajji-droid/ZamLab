package com.example.ui.components

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ai.AiAspectRatio
import com.example.ai.AiModelCatalogState
import com.example.ai.ModelDownloadProgress
import com.example.ai.ModelInstallPhase
import com.example.ai.SdModelManager
import com.example.editor.AiGenerationState
import com.example.ui.theme.StudioActiveOffWhite
import com.example.ui.theme.StudioEraserCrimson
import com.example.ui.theme.StudioGlassHairline
import com.example.ui.theme.StudioGlassHighlight
import com.example.ui.theme.StudioLuxuryGold
import com.example.ui.theme.StudioObsidian
import com.example.ui.theme.StudioSuccessGreen
import com.example.ui.theme.StudioTextMuted
import com.example.ui.theme.StudioTextPrimary
import com.example.ui.theme.StudioTextSecondary
import kotlin.math.roundToInt

// #16181F at 82% opacity (0.82 * 255 = 209 = 0xD1)
internal val AiStudioGlassSurface = Color(0xD116181F)

@Composable
fun AiCreatePanel(
    catalog: AiModelCatalogState,
    modelProgress: ModelDownloadProgress,
    generationState: AiGenerationState,
    onOpenModelManager: () -> Unit,
    onDownloadBuiltinSd15: () -> Unit,
    onCancelModelDownload: () -> Unit,
    onGenerate: (prompt: String, aspectRatio: AiAspectRatio, steps: Int) -> Unit,
    onCancelGeneration: () -> Unit,
    onClearError: () -> Unit,
    modifier: Modifier = Modifier
) {
    var promptText by rememberSaveable { mutableStateOf("") }
    var selectedAspectRatioName by rememberSaveable { mutableStateOf(AiAspectRatio.SQUARE.name) }
    var inferenceSteps by rememberSaveable { mutableIntStateOf(SdModelManager.DEFAULT_STEPS) }

    val selectedAspectRatio = AiAspectRatio.entries.firstOrNull { it.name == selectedAspectRatioName }
        ?: AiAspectRatio.SQUARE
    val activeModel = catalog.activeModel
    val isGenerating = generationState is AiGenerationState.Generating
    val isDownloadingOrExtracting = modelProgress.phase == ModelInstallPhase.DOWNLOADING ||
        modelProgress.phase == ModelInstallPhase.EXTRACTING

    val panelShape = RoundedCornerShape(20.dp)

    Surface(
        shape = panelShape,
        color = AiStudioGlassSurface,
        contentColor = StudioTextPrimary,
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, StudioGlassHairline, panelShape)
            .testTag("ai_create_panel")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. Top Row: Active Model Selector Pill (with disk size) + Models Manager Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = StudioObsidian.copy(alpha = 0.78f),
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp)
                        .clip(RoundedCornerShape(50))
                        .border(
                            width = 1.dp,
                            color = if (activeModel != null) StudioLuxuryGold.copy(alpha = 0.35f) else StudioGlassHairline,
                            shape = RoundedCornerShape(50)
                        )
                        .clickable(onClick = onOpenModelManager)
                        .testTag("ai_active_model_pill")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(if (activeModel != null) StudioSuccessGreen else StudioLuxuryGold)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = activeModel?.displayName ?: "No AI Model Installed",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                color = StudioActiveOffWhite,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (activeModel != null) {
                                    "${SdModelManager.formatBytes(activeModel.sizeBytes)} • Offline Ready"
                                } else {
                                    "Download SD v1.5 or add in Models"
                                },
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = StudioTextSecondary,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                OutlinedButton(
                    onClick = onOpenModelManager,
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(1.dp, StudioGlassHairline),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                    modifier = Modifier
                        .defaultMinSize(minHeight = 48.dp)
                        .height(48.dp)
                        .testTag("ai_models_manager_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = stringResource(R.string.cd_ai_models_manager),
                        tint = StudioActiveOffWhite,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (catalog.models.isNotEmpty()) {
                            "Models (${catalog.models.size})"
                        } else {
                            stringResource(R.string.action_ai_models)
                        },
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = StudioActiveOffWhite,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            // 2. Built-in One-Tap Download Card or Live Download/Extract Progress
            if (isDownloadingOrExtracting) {
                ActiveModelInstallProgressCard(
                    progress = modelProgress,
                    onCancelClick = onCancelModelDownload
                )
            } else if (activeModel == null) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = StudioObsidian.copy(alpha = 0.72f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, StudioGlassHairline, RoundedCornerShape(14.dp))
                        .testTag("ai_builtin_download_banner")
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Install an offline model to generate images with zero network calls.",
                            style = MaterialTheme.typography.bodySmall,
                            color = StudioTextSecondary
                        )
                        if (modelProgress.phase == ModelInstallPhase.ERROR && !modelProgress.errorMessage.isNullOrBlank()) {
                            Text(
                                text = modelProgress.errorMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = StudioEraserCrimson
                            )
                        }
                        Button(
                            onClick = onDownloadBuiltinSd15,
                            shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = StudioActiveOffWhite,
                                contentColor = StudioObsidian
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("ai_download_model_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudDownload,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp)
                            )
                            Spacer(modifier = Modifier.width(7.dp))
                            Text(
                                text = stringResource(R.string.action_download_ai_model),
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }

            // 3. Prompt Input
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    text = "Prompt",
                    style = MaterialTheme.typography.labelMedium,
                    color = StudioTextSecondary
                )
                OutlinedTextField(
                    value = promptText,
                    onValueChange = {
                        promptText = it
                        if (generationState is AiGenerationState.Error) onClearError()
                    },
                    enabled = !isGenerating,
                    minLines = 2,
                    maxLines = 4,
                    placeholder = {
                        Text(
                            text = "Golden hour coastal cliffside, moody 35mm film contrast...",
                            color = StudioTextMuted,
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
                    trailingIcon = if (promptText.isNotEmpty() && !isGenerating) {
                        {
                            IconButton(
                                onClick = { promptText = "" },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Clear prompt",
                                    tint = StudioTextSecondary,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                        }
                    } else null,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = StudioActiveOffWhite,
                        unfocusedTextColor = StudioActiveOffWhite,
                        disabledTextColor = StudioTextSecondary,
                        focusedContainerColor = StudioObsidian.copy(alpha = 0.72f),
                        unfocusedContainerColor = StudioObsidian.copy(alpha = 0.60f),
                        disabledContainerColor = StudioObsidian.copy(alpha = 0.45f),
                        focusedBorderColor = StudioActiveOffWhite.copy(alpha = 0.45f),
                        unfocusedBorderColor = StudioGlassHairline,
                        disabledBorderColor = StudioGlassHairline.copy(alpha = 0.5f),
                        cursorColor = StudioLuxuryGold
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ai_prompt_input")
                )
            }

            // 4. Aspect Ratio Selector (1:1, 4:5, 16:9)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Aspect Ratio",
                        style = MaterialTheme.typography.labelMedium,
                        color = StudioTextSecondary
                    )
                    Text(
                        text = "${selectedAspectRatio.outputWidth} × ${selectedAspectRatio.outputHeight} px",
                        style = MaterialTheme.typography.labelSmall,
                        color = StudioLuxuryGold
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AiAspectRatio.entries.forEach { ratio ->
                        val tag = when (ratio) {
                            AiAspectRatio.SQUARE -> "ai_aspect_1_1"
                            AiAspectRatio.PORTRAIT_4_5 -> "ai_aspect_4_5"
                            AiAspectRatio.LANDSCAPE_16_9 -> "ai_aspect_16_9"
                        }
                        AiAspectRatioPill(
                            ratio = ratio,
                            selected = selectedAspectRatio == ratio,
                            enabled = !isGenerating,
                            testTag = tag,
                            onClick = { selectedAspectRatioName = ratio.name },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // 5. Inference Steps Slider (10–25)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Inference Steps",
                        style = MaterialTheme.typography.labelMedium,
                        color = StudioTextSecondary
                    )
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = StudioObsidian.copy(alpha = 0.8f),
                        modifier = Modifier.border(1.dp, StudioGlassHairline, RoundedCornerShape(50))
                    ) {
                        Text(
                            text = "$inferenceSteps steps",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = StudioActiveOffWhite,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                        )
                    }
                }

                Slider(
                    value = inferenceSteps.toFloat(),
                    onValueChange = { raw ->
                        inferenceSteps = SdModelManager.clampInferenceSteps(raw.roundToInt())
                    },
                    enabled = !isGenerating,
                    valueRange = SdModelManager.MIN_STEPS.toFloat()..SdModelManager.MAX_STEPS.toFloat(),
                    steps = (SdModelManager.MAX_STEPS - SdModelManager.MIN_STEPS) - 1,
                    colors = SliderDefaults.colors(
                        thumbColor = StudioActiveOffWhite,
                        activeTrackColor = StudioLuxuryGold,
                        inactiveTrackColor = StudioGlassHighlight,
                        activeTickColor = Color.Transparent,
                        inactiveTickColor = Color.Transparent
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("ai_steps_slider")
                )
            }

            // 6. Error Banner
            if (generationState is AiGenerationState.Error) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = StudioEraserCrimson.copy(alpha = 0.14f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, StudioEraserCrimson.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = StudioEraserCrimson,
                            modifier = Modifier.size(17.dp)
                        )
                        Text(
                            text = generationState.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = StudioActiveOffWhite,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // 7. Generate Photo Button or Live Generation Progress
            if (generationState is AiGenerationState.Generating) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = StudioObsidian.copy(alpha = 0.78f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, StudioGlassHairline, RoundedCornerShape(14.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (generationState.currentStep <= 0) {
                                    "Initializing on-device diffusion..."
                                } else {
                                    "Step ${generationState.currentStep} of ${generationState.totalSteps}"
                                },
                                style = MaterialTheme.typography.labelLarge,
                                color = StudioActiveOffWhite
                            )
                            Text(
                                text = "${(generationState.progressFraction * 100f).roundToInt()}%",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = StudioLuxuryGold
                            )
                        }
                        LinearProgressIndicator(
                            progress = { generationState.progressFraction },
                            color = StudioLuxuryGold,
                            trackColor = StudioGlassHighlight,
                            strokeCap = StrokeCap.Round,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(CircleShape)
                        )
                        OutlinedButton(
                            onClick = onCancelGeneration,
                            border = BorderStroke(1.dp, StudioGlassHairline),
                            shape = RoundedCornerShape(50),
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("ai_cancel_generate_button")
                        ) {
                            Text(
                                text = "Cancel Generation",
                                color = StudioActiveOffWhite,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            } else {
                Button(
                    onClick = {
                        onGenerate(promptText, selectedAspectRatio, inferenceSteps)
                    },
                    enabled = activeModel != null && promptText.isNotBlank(),
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StudioActiveOffWhite,
                        contentColor = StudioObsidian,
                        disabledContainerColor = StudioActiveOffWhite.copy(alpha = 0.25f),
                        disabledContentColor = StudioObsidian.copy(alpha = 0.6f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("ai_generate_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(modifier = Modifier.width(7.dp))
                    Text(
                        text = stringResource(R.string.action_generate_photo),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}

@Composable
internal fun ActiveModelInstallProgressCard(
    progress: ModelDownloadProgress,
    onCancelClick: () -> Unit
) {
    val labelPrefix = progress.targetLabel.ifBlank { "AI Model" }
    val statusText = when (progress.phase) {
        ModelInstallPhase.DOWNLOADING -> {
            val done = SdModelManager.formatBytes(progress.bytesProcessed)
            val total = SdModelManager.formatBytes(progress.totalBytes)
            "Downloading $labelPrefix: $done / $total"
        }
        ModelInstallPhase.EXTRACTING -> "Extracting $labelPrefix... ${progress.extractPercent}%"
        else -> "Preparing $labelPrefix..."
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = StudioObsidian.copy(alpha = 0.78f),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, StudioGlassHairline, RoundedCornerShape(14.dp))
            .testTag("ai_install_progress_card")
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelMedium,
                    color = StudioActiveOffWhite,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${(progress.overallFraction * 100f).roundToInt()}%",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = StudioLuxuryGold
                )
            }
            LinearProgressIndicator(
                progress = { progress.overallFraction },
                color = StudioLuxuryGold,
                trackColor = StudioGlassHighlight,
                strokeCap = StrokeCap.Round,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(CircleShape)
                    .testTag("ai_download_progress")
            )
            OutlinedButton(
                onClick = onCancelClick,
                border = BorderStroke(1.dp, StudioGlassHairline),
                shape = RoundedCornerShape(50),
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .testTag("ai_cancel_download_button")
            ) {
                Text(
                    text = "Cancel",
                    color = StudioActiveOffWhite,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

@Composable
private fun AiAspectRatioPill(
    ratio: AiAspectRatio,
    selected: Boolean,
    enabled: Boolean,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(14.dp)
    val bgColor = if (selected) StudioGlassHighlight else StudioObsidian.copy(alpha = 0.72f)
    val borderColor = if (selected) StudioActiveOffWhite.copy(alpha = 0.45f) else StudioGlassHairline

    Surface(
        shape = shape,
        color = bgColor,
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .clip(shape)
            .border(1.dp, borderColor, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .testTag(testTag)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    text = ratio.label,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = if (selected) StudioActiveOffWhite else StudioTextSecondary,
                    maxLines = 1,
                    softWrap = false
                )
                if (selected) {
                    Box(
                        modifier = Modifier
                            .size(4.5.dp)
                            .clip(CircleShape)
                            .background(StudioLuxuryGold)
                    )
                }
            }
            Text(
                text = "${ratio.outputWidth}×${ratio.outputHeight}",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = StudioTextMuted,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}
