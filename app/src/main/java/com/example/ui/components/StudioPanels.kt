package com.example.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.editor.LutFolderGroup
import com.example.editor.LutPresetItem
import com.example.lut.CubeLut
import com.example.lut.LutSourceFormat
import com.example.lut.LutType
import com.example.lut.UserPreset
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
import kotlin.math.roundToInt

@Composable
fun LutPanel(
    folders: List<LutFolderGroup>,
    selectedLutId: String?,
    activeLut: CubeLut?,
    lutIntensity: Float,
    userPresets: List<UserPreset> = emptyList(),
    userPresetThumbnails: Map<String, Bitmap> = emptyMap(),
    selectedUserPresetId: String? = null,
    onToggleFolder: (String) -> Unit,
    onSelectLut: (String?) -> Unit,
    onIntensityChange: (Float) -> Unit,
    onDraggingChanged: (Boolean) -> Unit,
    onImportCubeClick: () -> Unit,
    onDeleteCustomLut: (String) -> Unit,
    onSaveCurrentLook: (String) -> Unit = {},
    onApplyUserPreset: (String) -> Unit = {},
    onDeleteUserPreset: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val totalCount = remember(folders) { folders.sumOf { it.totalCount } + userPresets.size }
    var showSaveDialog by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        // Header row: active preset info + Off button (if active) + Import button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Color LUTs & Presets",
                    style = MaterialTheme.typography.titleSmall,
                    color = StudioTextPrimary
                )
                Text(
                    text = activeLut?.let { lut ->
                        val fmt = when {
                            lut.sourceFormat == LutSourceFormat.XMP -> "XMP"
                            lut.type == LutType.LUT_3D -> "${lut.size}³ 3D"
                            else -> "${lut.size} 1D"
                        }
                        "${lut.title} • ${lut.category} • $fmt"
                    } ?: "${folders.size} folders • $totalCount presets",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (activeLut != null) StudioAmber else StudioTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (selectedLutId != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .defaultMinSize(minWidth = 48.dp, minHeight = 40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onSelectLut(null) }
                        .testTag("lut_clear_active_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = StudioSurfaceElevated,
                        modifier = Modifier.border(1.dp, StudioBorder, RoundedCornerShape(8.dp))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.RestartAlt,
                                contentDescription = "Turn off preset",
                                tint = StudioTextSecondary,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "Off",
                                style = MaterialTheme.typography.labelSmall,
                                color = StudioTextSecondary
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(6.dp))
            OutlinedButton(
                onClick = onImportCubeClick,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                modifier = Modifier.testTag("import_lut_button")
            ) {
                Icon(
                    imageVector = Icons.Default.FileUpload,
                    contentDescription = null,
                    tint = StudioCyan,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = stringResource(R.string.action_import_lut),
                    style = MaterialTheme.typography.labelMedium,
                    color = StudioCyan
                )
            }
        }

        HorizontalDivider(color = StudioBorderSubtle, thickness = 1.dp)

        // Stacked vertical list: My Presets + collapsible folder bars + 3-column grid inside open folders
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item(key = "my_presets_section") {
                MyPresetsSection(
                    userPresets = userPresets,
                    userPresetThumbnails = userPresetThumbnails,
                    selectedUserPresetId = selectedUserPresetId,
                    onSaveLookClick = { showSaveDialog = true },
                    onApplyUserPreset = onApplyUserPreset,
                    onDeleteUserPreset = onDeleteUserPreset
                )
            }

            folders.forEach { folder ->
                val folderSlug = folder.name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
                val folderHasSelected = folder.descriptors.any { it.id == selectedLutId }

                item(key = "folder_bar_${folder.name}") {
                    FolderAccordionBar(
                        name = folder.name,
                        count = folder.totalCount,
                        isExpanded = folder.isExpanded,
                        isCustom = folder.isCustomFolder,
                        hasSelectedPreset = folderHasSelected,
                        testTag = "lut_folder_$folderSlug",
                        onClick = { onToggleFolder(folder.name) }
                    )
                }

                if (folder.isExpanded) {
                    if (folder.isLoading && folder.items.all { it.lut == null }) {
                        item(key = "folder_loading_${folder.name}") {
                            FolderLoadingRow(folderName = folder.name)
                        }
                    } else {
                        val rows = folder.items.chunked(3)
                        items(
                            items = rows,
                            key = { rowItems -> "row_${folder.name}_${rowItems.first().id}" }
                        ) { rowItems ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                rowItems.forEach { item ->
                                    LutGridThumbnailCard(
                                        item = item,
                                        selected = selectedLutId == item.id,
                                        onClick = { onSelectLut(item.id) },
                                        onDelete = if (item.isCustom) {
                                            { onDeleteCustomLut(item.id) }
                                        } else null,
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("lut_card_${item.id}")
                                    )
                                }
                                repeat(3 - rowItems.size) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = StudioBorderSubtle, thickness = 1.dp)

        // Pinned LUT / Preset Intensity slider at the bottom
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF12151C))
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (activeLut?.sourceFormat == LutSourceFormat.XMP) {
                        "Preset Intensity"
                    } else {
                        "LUT Intensity"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selectedLutId != null) StudioTextPrimary else StudioTextMuted
                )
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (selectedLutId != null) StudioAmber.copy(alpha = 0.14f) else StudioSurfaceHighest
                ) {
                    Text(
                        text = "${(lutIntensity * 100f).roundToInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selectedLutId != null) StudioAmber else StudioTextMuted,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
            Slider(
                value = lutIntensity,
                onValueChange = { v ->
                    onDraggingChanged(true)
                    onIntensityChange(v)
                },
                onValueChangeFinished = { onDraggingChanged(false) },
                valueRange = 0f..1f,
                enabled = selectedLutId != null,
                colors = SliderDefaults.colors(
                    thumbColor = StudioAmber,
                    activeTrackColor = StudioAmber,
                    inactiveTrackColor = StudioSurfaceHighest
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(30.dp)
                    .testTag("lut_intensity_slider")
            )
        }
    }

    if (showSaveDialog) {
        SavePresetDialog(
            suggestedName = "My Look ${userPresets.size + 1}",
            onConfirm = { name ->
                showSaveDialog = false
                onSaveCurrentLook(name)
            },
            onDismiss = { showSaveDialog = false }
        )
    }
}

@Composable
private fun MyPresetsSection(
    userPresets: List<UserPreset>,
    userPresetThumbnails: Map<String, Bitmap>,
    selectedUserPresetId: String?,
    onSaveLookClick: () -> Unit,
    onApplyUserPreset: (String) -> Unit,
    onDeleteUserPreset: (String) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = StudioSurfaceElevated,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, StudioAmber.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
            .testTag("my_presets_section")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Bookmark,
                        contentDescription = null,
                        tint = StudioAmber,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "My Presets",
                        style = MaterialTheme.typography.titleSmall,
                        color = StudioTextPrimary
                    )
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = StudioSurfaceHighest
                    ) {
                        Text(
                            text = "${userPresets.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = StudioAmber,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                        )
                    }
                }

                Button(
                    onClick = onSaveLookClick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StudioAmber,
                        contentColor = StudioBackground
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier
                        .defaultMinSize(minHeight = 36.dp)
                        .testTag("save_current_look_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = stringResource(R.string.action_save_look).removePrefix("+ "),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            if (userPresets.isEmpty()) {
                Text(
                    text = "Save your custom sliders, tone curves, and LUT mixes for one-tap reuse.",
                    style = MaterialTheme.typography.bodySmall,
                    color = StudioTextSecondary
                )
            } else {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(items = userPresets, key = { it.id }) { preset ->
                        UserPresetThumbnailCard(
                            preset = preset,
                            thumbnail = userPresetThumbnails[preset.id],
                            selected = selectedUserPresetId == preset.id,
                            onClick = { onApplyUserPreset(preset.id) },
                            onDelete = { onDeleteUserPreset(preset.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UserPresetThumbnailCard(
    preset: UserPreset,
    thumbnail: Bitmap?,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val badge = when {
        preset.lutId != null && !preset.gradingParams.toneCurve.isDefault -> "PRO"
        preset.lutId != null -> "LUT"
        !preset.gradingParams.toneCurve.isDefault -> "CRV"
        else -> "LOOK"
    }
    Column(
        modifier = Modifier
            .width(98.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(StudioSurface)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) StudioAmber else StudioBorder,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onClick)
            .padding(5.dp)
            .testTag("user_preset_card_${preset.id}"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(
                    Brush.linearGradient(listOf(Color(0xFF212633), Color(0xFF2F374B)))
                ),
            contentAlignment = Alignment.Center
        ) {
            if (thumbnail != null && !thumbnail.isRecycled) {
                val imgBmp = remember(thumbnail) { thumbnail.asImageBitmap() }
                Image(
                    bitmap = imgBmp,
                    contentDescription = "${preset.name} custom preset preview",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Default.ColorLens,
                    contentDescription = null,
                    tint = StudioAmber,
                    modifier = Modifier.size(18.dp)
                )
            }

            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.72f),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(3.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onDelete)
                    .testTag("delete_user_preset_${preset.id}")
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Delete ${preset.name}",
                        tint = StudioTextPrimary,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(4.dp),
                color = Color.Black.copy(alpha = 0.7f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(3.dp)
            ) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) StudioAmber else StudioCyan,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
        }

        Text(
            text = preset.name,
            style = MaterialTheme.typography.bodySmall,
            color = if (selected) StudioAmber else StudioTextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SavePresetDialog(
    suggestedName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var nameText by remember { mutableStateOf(suggestedName) }
    val trimmed = nameText.trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = StudioSurfaceElevated,
        title = {
            Text(
                text = "Save Current Look",
                style = MaterialTheme.typography.titleMedium,
                color = StudioTextPrimary
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Saves your active sliders, Tone Curve, Camera Profile, and LUT into My Presets.",
                    style = MaterialTheme.typography.bodySmall,
                    color = StudioTextSecondary
                )
                OutlinedTextField(
                    value = nameText,
                    onValueChange = { nameText = it },
                    label = { Text("Preset Name") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("save_preset_name_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (trimmed.isNotEmpty()) onConfirm(trimmed) },
                enabled = trimmed.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = StudioAmber,
                    contentColor = StudioBackground
                ),
                modifier = Modifier.testTag("save_preset_confirm_button")
            ) {
                Text("Save Preset")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("save_preset_cancel_button")
            ) {
                Text("Cancel", color = StudioTextSecondary)
            }
        },
        modifier = Modifier.testTag("save_preset_dialog")
    )
}

@Composable
private fun FolderAccordionBar(
    name: String,
    count: Int,
    isExpanded: Boolean,
    isCustom: Boolean,
    hasSelectedPreset: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    val arrowRotation by animateFloatAsState(
        targetValue = if (isExpanded) 90f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "folder_arrow"
    )
    val borderColor = when {
        hasSelectedPreset -> StudioAmber.copy(alpha = 0.7f)
        isExpanded -> StudioBorder
        else -> StudioBorderSubtle
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = StudioSurfaceElevated,
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .testTag(testTag)
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
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = if (isExpanded) "Collapse $name" else "Expand $name",
                    tint = if (isExpanded) StudioAmber else StudioTextSecondary,
                    modifier = Modifier
                        .size(18.dp)
                        .rotate(arrowRotation)
                )
                Icon(
                    imageVector = if (isExpanded) Icons.Default.FolderOpen else Icons.Default.Folder,
                    contentDescription = null,
                    tint = when {
                        hasSelectedPreset -> StudioAmber
                        isCustom -> StudioCyan
                        else -> StudioTextSecondary
                    },
                    modifier = Modifier.size(17.dp)
                )
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (isExpanded || hasSelectedPreset) StudioTextPrimary else StudioTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (hasSelectedPreset) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(StudioAmber)
                            .testTag("${testTag}_active_dot")
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(50),
                color = StudioSurfaceHighest
            ) {
                Text(
                    text = "$count",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (hasSelectedPreset) StudioAmber else StudioTextSecondary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun FolderLoadingRow(folderName: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .testTag("lut_folder_loading"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            color = StudioAmber,
            strokeWidth = 2.dp,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "Loading $folderName previews…",
            style = MaterialTheme.typography.bodySmall,
            color = StudioTextSecondary
        )
    }
}

@Composable
private fun LutGridThumbnailCard(
    item: LutPresetItem,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val borderColor = if (selected) StudioAmber else StudioBorder
    val badge = when {
        item.sourceFormat == LutSourceFormat.XMP -> "XMP"
        item.lut != null -> "${item.lut.size}³"
        else -> "LUT"
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(StudioSurfaceElevated)
            .border(if (selected) 2.dp else 1.dp, borderColor, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF1F2735), Color(0xFF2B3548))
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            val thumbnail = item.previewThumbnail
            if (thumbnail != null) {
                val imgBmp = remember(thumbnail) { thumbnail.asImageBitmap() }
                Image(
                    bitmap = imgBmp,
                    contentDescription = "${item.title} preset preview",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Default.ColorLens,
                    contentDescription = null,
                    tint = StudioTextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
            if (item.isCustom && onDelete != null) {
                Surface(
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.72f),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(3.dp)
                        .size(20.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onDelete)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Remove ${item.title}",
                            tint = StudioTextPrimary,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = Color.Black.copy(alpha = 0.68f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(3.dp)
            ) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) StudioAmber else StudioTextPrimary,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
        }
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodySmall,
            color = if (selected) StudioAmber else StudioTextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
