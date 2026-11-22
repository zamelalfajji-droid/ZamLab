package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ai.AiModelCatalogState
import com.example.ai.InstalledAiModel
import com.example.ai.ModelDownloadProgress
import com.example.ai.ModelInstallPhase
import com.example.ai.SdModelManager
import com.example.ui.theme.StudioActiveOffWhite
import com.example.ui.theme.StudioEraserCrimson
import com.example.ui.theme.StudioGlassHairline
import com.example.ui.theme.StudioGlassHighlight
import com.example.ui.theme.StudioLuxuryGold
import com.example.ui.theme.StudioObsidian
import com.example.ui.theme.StudioSurfaceElevated
import com.example.ui.theme.StudioTextMuted
import com.example.ui.theme.StudioTextPrimary
import com.example.ui.theme.StudioTextSecondary

private val ModelManagerSheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiModelManagerSheet(
    catalog: AiModelCatalogState,
    modelProgress: ModelDownloadProgress,
    onSelectModel: (String) -> Unit,
    onDeleteModel: (String) -> Unit,
    onDownloadBuiltinSd15: () -> Unit,
    onDownloadCustomUrl: (displayName: String, url: String) -> Unit,
    onPickLocalZip: () -> Unit,
    onCancelDownload: () -> Unit,
    onDismiss: () -> Unit
) {
    var customName by rememberSaveable { mutableStateOf("") }
    var customUrl by rememberSaveable { mutableStateOf("") }
    var pendingDeleteModel by remember { mutableStateOf<InstalledAiModel?>(null) }

    val isInstalling = modelProgress.phase == ModelInstallPhase.DOWNLOADING ||
        modelProgress.phase == ModelInstallPhase.EXTRACTING
    val hasBuiltinInstalled = catalog.models.any { it.id == SdModelManager.BUILTIN_SD15_ID }
    val totalInstalledBytes = remember(catalog.models) {
        catalog.models.sumOf { it.sizeBytes }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.Transparent,
        contentColor = StudioTextPrimary,
        shape = ModelManagerSheetShape,
        dragHandle = null
    ) {
        Surface(
            shape = ModelManagerSheetShape,
            color = AiStudioGlassSurface,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, StudioGlassHairline, ModelManagerSheetShape)
                .testTag("ai_model_manager_sheet")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(top = 10.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = 2.dp)
                        .width(38.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(StudioGlassHighlight)
                )

                // Title & Total Storage Pill
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = StudioLuxuryGold,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Offline AI Models",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = StudioActiveOffWhite,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = StudioObsidian.copy(alpha = 0.85f),
                        modifier = Modifier.border(1.dp, StudioGlassHairline, RoundedCornerShape(50))
                    ) {
                        Text(
                            text = if (totalInstalledBytes > 0L) {
                                SdModelManager.formatBytes(totalInstalledBytes)
                            } else {
                                "0 MB"
                            },
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                            color = StudioLuxuryGold,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }

                // Active Download / Extract Progress or Error
                if (isInstalling) {
                    ActiveModelInstallProgressCard(
                        progress = modelProgress,
                        onCancelClick = onCancelDownload
                    )
                } else if (modelProgress.phase == ModelInstallPhase.ERROR && !modelProgress.errorMessage.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = StudioEraserCrimson.copy(alpha = 0.14f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, StudioEraserCrimson.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                    ) {
                        Text(
                            text = modelProgress.errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = StudioActiveOffWhite,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                // Installed Models Catalog (ai_models/<id>/)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Installed Models (${catalog.models.size})",
                        style = MaterialTheme.typography.labelLarge,
                        color = StudioTextSecondary
                    )

                    if (catalog.models.isEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = StudioObsidian.copy(alpha = 0.65f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, StudioGlassHairline, RoundedCornerShape(14.dp))
                        ) {
                            Text(
                                text = "No offline AI models installed in ai_models/ yet. Download Stable Diffusion v1.5 below, paste a Hugging Face / GitHub .zip URL, or import a local .zip.",
                                style = MaterialTheme.typography.bodySmall,
                                color = StudioTextSecondary,
                                modifier = Modifier.padding(14.dp)
                            )
                        }
                    } else {
                        catalog.models.forEach { model ->
                            InstalledModelRow(
                                model = model,
                                isActive = catalog.activeModel?.id == model.id,
                                enabled = !isInstalling,
                                onSelect = { onSelectModel(model.id) },
                                onDeleteClick = { pendingDeleteModel = model }
                            )
                        }
                    }
                }

                // Built-in Stable Diffusion v1.5 One-Tap Bundle Option
                if (!hasBuiltinInstalled) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = StudioObsidian.copy(alpha = 0.72f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, StudioGlassHairline, RoundedCornerShape(14.dp))
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = SdModelManager.BUILTIN_SD15_NAME,
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = StudioActiveOffWhite
                                )
                                Text(
                                    text = SdModelManager.APPROXIMATE_MODEL_LABEL,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = StudioLuxuryGold
                                )
                            }
                            Text(
                                text = "Built-in MediaPipe Hugging Face bundle for 100% offline generation.",
                                style = MaterialTheme.typography.bodySmall,
                                color = StudioTextSecondary
                            )
                            Button(
                                onClick = onDownloadBuiltinSd15,
                                enabled = !isInstalling,
                                shape = RoundedCornerShape(50),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = StudioActiveOffWhite,
                                    contentColor = StudioObsidian
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minHeight = 48.dp)
                                    .testTag("ai_manager_download_builtin_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CloudDownload,
                                    contentDescription = null,
                                    modifier = Modifier.size(17.dp)
                                )
                                Spacer(modifier = Modifier.width(7.dp))
                                Text(
                                    text = "Download SD v1.5 (${SdModelManager.APPROXIMATE_MODEL_LABEL})",
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }

                // Add from Hugging Face / GitHub URL
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = StudioObsidian.copy(alpha = 0.72f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, StudioGlassHairline, RoundedCornerShape(14.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Link,
                                contentDescription = null,
                                tint = StudioLuxuryGold,
                                modifier = Modifier.size(17.dp)
                            )
                            Text(
                                text = "Add from Hugging Face / GitHub",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = StudioActiveOffWhite
                            )
                        }

                        OutlinedTextField(
                            value = customName,
                            onValueChange = { customName = it },
                            enabled = !isInstalling,
                            singleLine = true,
                            label = { Text("Model Name") },
                            placeholder = { Text("e.g., SD 1.5 Realistic", color = StudioTextMuted) },
                            colors = managerFieldColors(),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("ai_custom_model_name_input")
                        )

                        OutlinedTextField(
                            value = customUrl,
                            onValueChange = { customUrl = it },
                            enabled = !isInstalling,
                            minLines = 1,
                            maxLines = 3,
                            label = { Text(".zip URL (Hugging Face or GitHub)") },
                            placeholder = {
                                Text("https://huggingface.co/.../model.zip", color = StudioTextMuted)
                            },
                            colors = managerFieldColors(),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("ai_custom_model_url_input")
                        )

                        Text(
                            text = "Hugging Face /blob/ links automatically convert to /resolve/ direct downloads.",
                            style = MaterialTheme.typography.labelSmall,
                            color = StudioTextMuted
                        )

                        Button(
                            onClick = { onDownloadCustomUrl(customName, customUrl) },
                            enabled = !isInstalling && customUrl.isNotBlank(),
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
                                .testTag("ai_custom_url_download_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudDownload,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp)
                            )
                            Spacer(modifier = Modifier.width(7.dp))
                            Text(
                                text = "Download & Install Model",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }

                // Import Local .zip from Phone Storage
                OutlinedButton(
                    onClick = onPickLocalZip,
                    enabled = !isInstalling,
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(1.dp, StudioGlassHairline),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("ai_import_local_zip_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = null,
                        tint = StudioActiveOffWhite,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Import Local .zip from Storage",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                        color = StudioActiveOffWhite,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }

    val modelToConfirm = pendingDeleteModel
    if (modelToConfirm != null) {
        AlertDialog(
            onDismissRequest = { pendingDeleteModel = null },
            containerColor = StudioSurfaceElevated,
            titleContentColor = StudioActiveOffWhite,
            textContentColor = StudioTextSecondary,
            title = {
                Text(
                    text = "Delete Offline Model?",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
            },
            text = {
                Text(
                    text = "Permanently delete \"${modelToConfirm.displayName}\" (${SdModelManager.formatBytes(modelToConfirm.sizeBytes)}) from ai_models/${modelToConfirm.id}/ to free phone storage?"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteModel(modelToConfirm.id)
                        pendingDeleteModel = null
                    },
                    modifier = Modifier
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("ai_confirm_delete_model_button")
                ) {
                    Text(
                        text = "Delete",
                        color = StudioEraserCrimson,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingDeleteModel = null },
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                ) {
                    Text("Cancel", color = StudioActiveOffWhite)
                }
            }
        )
    }
}

@Composable
private fun InstalledModelRow(
    model: InstalledAiModel,
    isActive: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    val bgColor = if (isActive) StudioGlassHighlight else StudioObsidian.copy(alpha = 0.72f)
    val borderColor = if (isActive) StudioLuxuryGold.copy(alpha = 0.55f) else StudioGlassHairline

    Surface(
        shape = shape,
        color = bgColor,
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .clip(shape)
            .border(1.dp, borderColor, shape)
            .clickable(enabled = enabled, onClick = onSelect)
            .testTag("ai_installed_model_${model.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (isActive) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Active model",
                    tint = StudioLuxuryGold,
                    modifier = Modifier.size(20.dp)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .border(1.5.dp, StudioTextMuted, CircleShape)
                )
            }

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = model.displayName,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = StudioActiveOffWhite,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${SdModelManager.formatBytes(model.sizeBytes)} • ${model.sourceBadge}",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = StudioTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            IconButton(
                onClick = onDeleteClick,
                enabled = enabled,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("ai_delete_model_${model.id}")
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = "Delete ${model.displayName}",
                    tint = StudioEraserCrimson,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun managerFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = StudioActiveOffWhite,
    unfocusedTextColor = StudioActiveOffWhite,
    disabledTextColor = StudioTextSecondary,
    focusedContainerColor = StudioObsidian.copy(alpha = 0.72f),
    unfocusedContainerColor = StudioObsidian.copy(alpha = 0.60f),
    disabledContainerColor = StudioObsidian.copy(alpha = 0.45f),
    focusedBorderColor = StudioActiveOffWhite.copy(alpha = 0.45f),
    unfocusedBorderColor = StudioGlassHairline,
    disabledBorderColor = StudioGlassHairline.copy(alpha = 0.5f),
    focusedLabelColor = StudioActiveOffWhite,
    unfocusedLabelColor = StudioTextSecondary,
    cursorColor = StudioLuxuryGold
)
