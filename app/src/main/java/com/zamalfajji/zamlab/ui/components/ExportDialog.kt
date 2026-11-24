package com.zamalfajji.zamlab.ui.components

import android.os.Build
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zamalfajji.zamlab.editor.ExportFormat
import com.zamalfajji.zamlab.editor.ExportOptions
import com.zamalfajji.zamlab.editor.ExportResolution
import com.zamalfajji.zamlab.editor.ExportStatus
import com.zamalfajji.zamlab.editor.ImageIoHelper
import com.zamalfajji.zamlab.ui.theme.StudioActiveOffWhite
import com.zamalfajji.zamlab.ui.theme.StudioCharcoal
import com.zamalfajji.zamlab.ui.theme.StudioCinemaAmber
import com.zamalfajji.zamlab.ui.theme.StudioEraserCrimson
import com.zamalfajji.zamlab.ui.theme.StudioGlassHairline
import com.zamalfajji.zamlab.ui.theme.StudioGlassHighlight
import com.zamalfajji.zamlab.ui.theme.StudioGlassSurface
import com.zamalfajji.zamlab.ui.theme.StudioLuxuryGold
import com.zamalfajji.zamlab.ui.theme.StudioObsidian
import com.zamalfajji.zamlab.ui.theme.StudioSurfaceElevated
import com.zamalfajji.zamlab.ui.theme.StudioTextMuted
import com.zamalfajji.zamlab.ui.theme.StudioTextPrimary
import com.zamalfajji.zamlab.ui.theme.StudioTextSecondary
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
    val isExporting = exportStatus is ExportStatus.Exporting

    ModalBottomSheet(
        onDismissRequest = {
            if (!isExporting) onDismiss()
        },
        sheetState = sheetState,
        containerColor = StudioGlassSurface.copy(alpha = 0.96f),
        contentColor = StudioTextPrimary,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 6.dp)
                    .width(38.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(StudioGlassHighlight)
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Export Studio",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = StudioActiveOffWhite,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    shape = RoundedCornerShape(50),
                    color = StudioObsidian,
                    modifier = Modifier.border(1.dp, StudioGlassHairline, RoundedCornerShape(50))
                ) {
                    Text(
                        text = "$targetW × $targetH px",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 0.3.sp
                        ),
                        color = StudioLuxuryGold,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            // Frosted-glass photo & output summary card
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = StudioObsidian.copy(alpha = 0.72f),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, StudioGlassHairline, RoundedCornerShape(14.dp))
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = photoName,
                        style = MaterialTheme.typography.titleSmall,
                        color = StudioActiveOffWhite,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val qualityBadge = when {
                        selectedFormat == ExportFormat.PDF -> "Full-Bleed Print PDF"
                        selectedFormat == ExportFormat.TIFF -> "16-Bit Uncompressed TIFF"
                        !selectedFormat.supportsQuality -> "Lossless 8-bit RGBA"
                        selectedFormat == ExportFormat.WEBP && currentOptions.effectiveQuality >= 100 -> "Lossless WebP"
                        else -> "${currentOptions.effectiveQuality}% Quality"
                    }
                    Text(
                        text = "${selectedFormat.label} • $qualityBadge • ${selectedFormat.defaultFolderDescription}",
                        style = MaterialTheme.typography.labelSmall,
                        color = StudioLuxuryGold
                    )
                    Text(
                        text = "Look / LUT: ${activeLutTitle ?: "Custom Grade"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioTextSecondary
                    )
                }
            }

            // 1. Output Format Selector: JPG, PNG, WebP, 16-bit TIFF, PDF
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Output Format",
                    style = MaterialTheme.typography.labelLarge,
                    color = StudioTextSecondary
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(ExportFormat.JPG, ExportFormat.PNG).forEach { format ->
                        FormatOptionTile(
                            format = format,
                            isSelected = selectedFormat == format,
                            enabled = !isExporting,
                            onClick = { selectedFormat = format },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(ExportFormat.WEBP, ExportFormat.TIFF).forEach { format ->
                        FormatOptionTile(
                            format = format,
                            isSelected = selectedFormat == format,
                            enabled = !isExporting,
                            onClick = { selectedFormat = format },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                FormatOptionTile(
                    format = ExportFormat.PDF,
                    isSelected = selectedFormat == ExportFormat.PDF,
                    enabled = !isExporting,
                    showProIcon = true,
                    onClick = { selectedFormat = ExportFormat.PDF },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // 2. Compression Quality (JPG / WebP) or Format Spec Card (PNG / TIFF / PDF)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .doubleTapToReset(
                            enabled = selectedFormat.supportsQuality && !isExporting,
                            onDoubleTapReset = { qualityPercent = 95 }
                        )
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (selectedFormat.supportsQuality) "Quality" else "Format Pipeline",
                        style = MaterialTheme.typography.labelLarge,
                        color = StudioTextSecondary
                    )
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (selectedFormat.supportsQuality) {
                            StudioLuxuryGold.copy(alpha = 0.14f)
                        } else {
                            StudioObsidian
                        },
                        modifier = Modifier.border(
                            1.dp,
                            if (selectedFormat.supportsQuality) StudioLuxuryGold.copy(alpha = 0.35f) else StudioGlassHairline,
                            RoundedCornerShape(50)
                        )
                    ) {
                        val tier = when {
                            selectedFormat == ExportFormat.PDF -> "Print 1:1 Vector Page"
                            selectedFormat == ExportFormat.TIFF -> "16-Bit Native C++ RGB"
                            !selectedFormat.supportsQuality -> "Lossless 100%"
                            selectedFormat == ExportFormat.WEBP && qualityPercent >= 100 -> "100% • Lossless"
                            qualityPercent >= 90 -> "$qualityPercent% • Max"
                            qualityPercent >= 75 -> "$qualityPercent% • High"
                            else -> "$qualityPercent% • Compact"
                        }
                        Text(
                            text = tier,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selectedFormat.supportsQuality) StudioLuxuryGold else StudioTextPrimary,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                        )
                    }
                }

                if (selectedFormat.supportsQuality) {
                    StudioPrecisionTrack(
                        value = qualityPercent.toFloat(),
                        valueRange = 10f..100f,
                        step = 5f,
                        enabled = !isExporting,
                        accentColor = StudioActiveOffWhite,
                        compact = true,
                        testTag = "export_quality_slider",
                        onValueChange = { v -> qualityPercent = v.roundToInt().coerceIn(10, 100) },
                        onReset = { qualityPercent = 95 }
                    )
                } else {
                    val formatNote = when (selectedFormat) {
                        ExportFormat.PNG -> "Lossless full-resolution 8-bit RGBA PNG saved to Pictures/Z-Effect."
                        ExportFormat.TIFF -> "Uncompressed 16-bit RGB TIFF encoded by the native C++ engine and saved to Downloads/Z-Effect."
                        ExportFormat.PDF -> "Full-bleed, margin-free print PDF matching exact photo dimensions saved to Downloads/Z-Effect."
                        else -> ""
                    }
                    Text(
                        text = formatNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioTextSecondary,
                        modifier = Modifier.padding(vertical = 3.dp)
                    )
                }
            }

            // 3. Output Resolution Selector
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Output Resolution",
                    style = MaterialTheme.typography.labelLarge,
                    color = StudioTextSecondary
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExportResolution.entries.chunked(2).forEach { rowItems ->
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
                                        .defaultMinSize(minHeight = 48.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            if (isSelected) StudioActiveOffWhite.copy(alpha = 0.12f)
                                            else StudioObsidian.copy(alpha = 0.68f)
                                        )
                                        .border(
                                            width = if (isSelected) 1.2.dp else 1.dp,
                                            color = if (isSelected) StudioActiveOffWhite.copy(alpha = 0.65f) else StudioGlassHairline,
                                            shape = RoundedCornerShape(12.dp)
                                        )
                                        .clickable(enabled = !isExporting) {
                                            selectedResolution = res
                                        }
                                        .padding(horizontal = 11.dp, vertical = 9.dp)
                                        .testTag("resolution_${res.name.lowercase()}")
                                ) {
                                    Text(
                                        text = res.label,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (isSelected) StudioActiveOffWhite else StudioTextPrimary,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "$rw × $rh px",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isSelected) StudioLuxuryGold else StudioTextMuted,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Status Feedback Area (Exporting / Error only — Success closes immediately into non-blocking snackbar)
            when (exportStatus) {
                is ExportStatus.Exporting -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(StudioObsidian.copy(alpha = 0.72f))
                            .border(1.dp, StudioGlassHairline, RoundedCornerShape(12.dp))
                            .padding(vertical = 12.dp, horizontal = 14.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            color = StudioCinemaAmber,
                            strokeWidth = 2.2.dp,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Encoding $targetW×$targetH ${selectedFormat.label}…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = StudioActiveOffWhite
                        )
                    }
                }

                is ExportStatus.Error -> {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = StudioEraserCrimson.copy(alpha = 0.14f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, StudioEraserCrimson.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                    ) {
                        Text(
                            text = exportStatus.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = StudioTextPrimary,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                ExportStatus.Idle, is ExportStatus.Success -> Unit
            }

            // Action Buttons: Save As… (SAF) + Instant MediaStore Export
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = { onExportWithFilePicker(currentOptions) },
                    enabled = !isExporting,
                    shape = RoundedCornerShape(50),
                    border = androidx.compose.foundation.BorderStroke(1.dp, SolidColor(StudioGlassHighlight)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = StudioSurfaceElevated.copy(alpha = 0.5f),
                        contentColor = StudioActiveOffWhite
                    ),
                    modifier = Modifier
                        .weight(0.85f)
                        .height(48.dp)
                        .testTag("confirm_export_saf_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Save As…",
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Button(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            onExportToGallery(currentOptions)
                        } else {
                            onExportWithFilePicker(currentOptions)
                        }
                    },
                    enabled = !isExporting,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StudioCinemaAmber,
                        contentColor = StudioCharcoal
                    ),
                    shape = RoundedCornerShape(50),
                    modifier = Modifier
                        .weight(1.15f)
                        .height(48.dp)
                        .testTag("confirm_export_gallery_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = selectedFormat.defaultSaveLabel,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun FormatOptionTile(
    format: ExportFormat,
    isSelected: Boolean,
    enabled: Boolean,
    showProIcon: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accentColor = if (format.isDownloadsDestination) {
        StudioLuxuryGold
    } else {
        StudioActiveOffWhite
    }
    Column(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isSelected) accentColor.copy(alpha = 0.14f)
                else StudioObsidian.copy(alpha = 0.68f)
            )
            .border(
                width = if (isSelected) 1.3.dp else 1.dp,
                color = if (isSelected) accentColor.copy(alpha = 0.8f) else StudioGlassHairline,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 10.dp)
            .testTag("format_${format.extension}")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            if (showProIcon && format == ExportFormat.PDF) {
                Icon(
                    imageVector = Icons.Default.PictureAsPdf,
                    contentDescription = null,
                    tint = if (isSelected) accentColor else StudioTextSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }
            Text(
                text = format.label,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = if (isSelected) accentColor else StudioActiveOffWhite,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = format.subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = if (isSelected) StudioTextPrimary else StudioTextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
