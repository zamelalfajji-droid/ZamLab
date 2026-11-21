package com.example.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.editor.ExportFormat
import com.example.editor.ExportOptions
import com.example.editor.ExportResolution
import com.example.editor.ExportStatus
import com.example.editor.ImageIoHelper
import com.example.ui.theme.StudioAmber
import com.example.ui.theme.StudioBackground
import com.example.ui.theme.StudioBorder
import com.example.ui.theme.StudioCyan
import com.example.ui.theme.StudioEraserCrimson
import com.example.ui.theme.StudioSuccessGreen
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioSurfaceElevated
import com.example.ui.theme.StudioSurfaceHighest
import com.example.ui.theme.StudioTextPrimary
import com.example.ui.theme.StudioTextSecondary
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportBottomSheet(
    photoName: String,
    width: Int,
    height: Int,
    activeLutTitle: String?,
    exportStatus: ExportStatus,
    onExportToGallery: (ExportOptions) -> Unit,
    onExportWithFilePicker: (ExportOptions) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedFormat by remember { mutableStateOf(ExportFormat.JPG) }
    var qualityPercent by remember { mutableIntStateOf(95) }
    var selectedResolution by remember { mutableStateOf(ExportResolution.FULL) }

    val currentOptions = ExportOptions(
        format = selectedFormat,
        quality = qualityPercent,
        resolution = selectedResolution
    )
    val (targetW, targetH) = remember(width, height, selectedResolution) {
        ImageIoHelper.computeTargetDimensions(width, height, selectedResolution)
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = {
            if (exportStatus !is ExportStatus.Exporting) onDismiss()
        },
        sheetState = sheetState,
        containerColor = StudioSurface,
        contentColor = StudioTextPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Export Settings",
                style = MaterialTheme.typography.titleLarge,
                color = StudioTextPrimary
            )

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = StudioSurfaceElevated,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, StudioBorder, RoundedCornerShape(12.dp))
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = photoName,
                        style = MaterialTheme.typography.titleSmall,
                        color = StudioTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val qualityBadge = if (selectedFormat.supportsQuality) {
                        "${currentOptions.effectiveQuality}% Quality"
                    } else {
                        "Lossless"
                    }
                    Text(
                        text = "Output: $targetW × $targetH px • ${selectedFormat.label} ($qualityBadge)",
                        style = MaterialTheme.typography.labelSmall,
                        color = StudioAmber
                    )
                    Text(
                        text = "LUT: ${activeLutTitle ?: "None"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioTextSecondary
                    )
                }
            }

            // 1. File Format selector (JPG, PNG, WEBP)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "File Format",
                    style = MaterialTheme.typography.labelLarge,
                    color = StudioTextSecondary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ExportFormat.entries.forEach { format ->
                        val isSelected = selectedFormat == format
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (isSelected) StudioAmber.copy(alpha = 0.14f) else StudioSurfaceElevated
                                )
                                .border(
                                    width = if (isSelected) 1.5.dp else 1.dp,
                                    color = if (isSelected) StudioAmber else StudioBorder,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable(enabled = exportStatus !is ExportStatus.Exporting) {
                                    selectedFormat = format
                                }
                                .padding(horizontal = 10.dp, vertical = 10.dp)
                                .testTag("format_${format.extension}")
                        ) {
                            Text(
                                text = format.label,
                                style = MaterialTheme.typography.titleMedium,
                                color = if (isSelected) StudioAmber else StudioTextPrimary
                            )
                            Text(
                                text = format.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = StudioTextSecondary
                            )
                        }
                    }
                }
            }

            // 2. Compression Quality
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Compression Quality",
                        style = MaterialTheme.typography.labelLarge,
                        color = StudioTextSecondary
                    )
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (selectedFormat.supportsQuality) {
                            StudioAmber.copy(alpha = 0.14f)
                        } else {
                            StudioSurfaceHighest
                        }
                    ) {
                        val tier = when {
                            !selectedFormat.supportsQuality -> "Lossless 100%"
                            qualityPercent >= 90 -> "$qualityPercent% • Archive"
                            qualityPercent >= 75 -> "$qualityPercent% • Balanced"
                            else -> "$qualityPercent% • Compact"
                        }
                        Text(
                            text = tier,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selectedFormat.supportsQuality) StudioAmber else StudioTextSecondary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }

                if (selectedFormat.supportsQuality) {
                    Slider(
                        value = qualityPercent.toFloat(),
                        onValueChange = { v -> qualityPercent = ((v / 5f).roundToInt() * 5).coerceIn(10, 100) },
                        valueRange = 10f..100f,
                        enabled = exportStatus !is ExportStatus.Exporting,
                        colors = SliderDefaults.colors(
                            thumbColor = StudioAmber,
                            activeTrackColor = StudioAmber,
                            inactiveTrackColor = StudioSurfaceHighest
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(34.dp)
                            .testTag("export_quality_slider")
                    )
                } else {
                    Text(
                        text = "PNG always uses lossless RGBA compression.",
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioTextSecondary,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }

            // 3. Output Resolution selector
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Output Resolution",
                    style = MaterialTheme.typography.labelLarge,
                    color = StudioTextSecondary
                )
                val resEntries = ExportResolution.entries
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    resEntries.chunked(2).forEach { rowItems ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowItems.forEach { res ->
                                val isSelected = selectedResolution == res
                                val (rw, rh) = ImageIoHelper.computeTargetDimensions(width, height, res)
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            if (isSelected) StudioCyan.copy(alpha = 0.14f) else StudioSurfaceElevated
                                        )
                                        .border(
                                            width = if (isSelected) 1.5.dp else 1.dp,
                                            color = if (isSelected) StudioCyan else StudioBorder,
                                            shape = RoundedCornerShape(10.dp)
                                        )
                                        .clickable(enabled = exportStatus !is ExportStatus.Exporting) {
                                            selectedResolution = res
                                        }
                                        .padding(horizontal = 10.dp, vertical = 8.dp)
                                        .testTag("resolution_${res.name.lowercase()}")
                                ) {
                                    Text(
                                        text = res.label,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (isSelected) StudioCyan else StudioTextPrimary
                                    )
                                    Text(
                                        text = "$rw × $rh px",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = StudioTextSecondary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            when (exportStatus) {
                is ExportStatus.Exporting -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            color = StudioAmber,
                            strokeWidth = 2.5.dp,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Rendering $targetW×$targetH ${selectedFormat.label}…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = StudioTextPrimary
                        )
                    }
                }

                is ExportStatus.Success -> {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = StudioSuccessGreen.copy(alpha = 0.14f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, StudioSuccessGreen.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = StudioSuccessGreen,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "Saved ${exportStatus.filename} (${exportStatus.width}×${exportStatus.height})",
                                style = MaterialTheme.typography.bodySmall,
                                color = StudioTextPrimary
                            )
                        }
                    }
                }

                is ExportStatus.Error -> {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = StudioEraserCrimson.copy(alpha = 0.14f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, StudioEraserCrimson.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                    ) {
                        Text(
                            text = exportStatus.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = StudioTextPrimary,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                ExportStatus.Idle -> Unit
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = { onExportWithFilePicker(currentOptions) },
                    enabled = exportStatus !is ExportStatus.Exporting,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("confirm_export_saf_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Save As…", style = MaterialTheme.typography.labelLarge)
                }

                Button(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            onExportToGallery(currentOptions)
                        } else {
                            onExportWithFilePicker(currentOptions)
                        }
                    },
                    enabled = exportStatus !is ExportStatus.Exporting,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StudioAmber,
                        contentColor = StudioBackground
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("confirm_export_gallery_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Save to Photos", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
