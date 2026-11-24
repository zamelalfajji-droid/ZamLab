package com.zamalfajji.zamlab.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zamalfajji.zamlab.R
import com.zamalfajji.zamlab.editor.LutFolderGroup
import com.zamalfajji.zamlab.editor.LutPresetItem
import com.zamalfajji.zamlab.lut.CubeLut
import com.zamalfajji.zamlab.lut.LutSourceFormat
import com.zamalfajji.zamlab.lut.UserPreset
import com.zamalfajji.zamlab.ui.theme.StudioAmber
import com.zamalfajji.zamlab.ui.theme.StudioAmberPillFill
import com.zamalfajji.zamlab.ui.theme.StudioBackground
import com.zamalfajji.zamlab.ui.theme.StudioBorder
import com.zamalfajji.zamlab.ui.theme.StudioCinemaAmber
import com.zamalfajji.zamlab.ui.theme.StudioCoolWhite
import com.zamalfajji.zamlab.ui.theme.StudioCyan
import com.zamalfajji.zamlab.ui.theme.StudioGraphite
import com.zamalfajji.zamlab.ui.theme.StudioHairline
import com.zamalfajji.zamlab.ui.theme.StudioMutedSlate
import com.zamalfajji.zamlab.ui.theme.StudioSurfaceElevated
import com.zamalfajji.zamlab.ui.theme.StudioSurfaceHighest
import com.zamalfajji.zamlab.ui.theme.StudioTextMuted
import com.zamalfajji.zamlab.ui.theme.StudioTextPrimary
import com.zamalfajji.zamlab.ui.theme.StudioTextSecondary
import com.zamalfajji.zamlab.ui.theme.StudioTileBorder
import com.zamalfajji.zamlab.ui.theme.StudioTileSurface
import kotlin.math.roundToInt

private const val MY_PRESETS_CATEGORY_KEY = "__my_presets__"

/**
 * Compact darkroom LUT & Preset panel engineered so the 0%–100% LUT Strength slider and
 * the horizontally scrollable square LUT thumbnail strip remain permanently visible above the
 * bottom capsule navigation dock on all mobile screen heights with zero page scrolling.
 */
@Composable
fun LutPanel(
    folders: List<LutFolderGroup>,
    selectedLutId: String?,
    activeLut: CubeLut?,
    lutIntensity: Float,
    sourcePreviewBitmap: Bitmap? = null,
    userPresets: List<UserPreset> = emptyList(),
    userPresetThumbnails: Map<String, Bitmap> = emptyMap(),
    selectedUserPresetId: String? = null,
    onSelectFolder: (String) -> Unit = {},
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
    var showSaveDialog by remember { mutableStateOf(false) }

    // Default to the folder containing the active LUT, an already-expanded folder, or the first folder
    var activeCategoryKey by remember {
        mutableStateOf(
            folders.firstOrNull { f -> selectedLutId != null && f.descriptors.any { it.id == selectedLutId } }?.name
                ?: folders.firstOrNull { it.isExpanded }?.name
                ?: folders.firstOrNull()?.name
                ?: MY_PRESETS_CATEGORY_KEY
        )
    }

    // Keep activeCategoryKey valid when folders finish async indexing or custom folders are added/deleted
    LaunchedEffect(folders, selectedLutId) {
        if (activeCategoryKey == MY_PRESETS_CATEGORY_KEY) return@LaunchedEffect
        val validFolder = folders.firstOrNull { it.name == activeCategoryKey }
        if (validFolder == null && folders.isNotEmpty()) {
            val preferred = folders.firstOrNull { f ->
                selectedLutId != null && f.descriptors.any { it.id == selectedLutId }
            } ?: folders.firstOrNull { it.isExpanded } ?: folders.first()
            activeCategoryKey = preferred.name
            onSelectFolder(preferred.name)
        } else if (validFolder != null && !validFolder.isLoadedForCurrentPhoto && !validFolder.isLoading) {
            onSelectFolder(validFolder.name)
        }
    }

    val activeFolder = remember(folders, activeCategoryKey) {
        folders.firstOrNull { it.name == activeCategoryKey }
    }
    val isViewingMyPresets = activeCategoryKey == MY_PRESETS_CATEGORY_KEY

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(StudioBackground)
            .padding(top = 4.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Row 1: Compact Category Filter Chips + Save Look + Import .cube/.xmp
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            LazyRow(
                modifier = Modifier
                    .weight(1f)
                    .testTag("lut_category_bar"),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(items = folders, key = { it.name }) { folder ->
                    val folderSlug = folder.name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
                    val selected = activeCategoryKey == folder.name
                    val hasActiveLut = folder.descriptors.any { it.id == selectedLutId }
                    LutCategoryChip(
                        label = folder.name,
                        count = folder.totalCount,
                        selected = selected,
                        hasActiveDot = hasActiveLut,
                        testTag = "lut_folder_$folderSlug",
                        onClick = {
                            activeCategoryKey = folder.name
                            onSelectFolder(folder.name)
                        }
                    )
                }

                item(key = MY_PRESETS_CATEGORY_KEY) {
                    LutCategoryChip(
                        label = stringResource(R.string.lut_my_presets_tab),
                        count = userPresets.size,
                        selected = isViewingMyPresets,
                        hasActiveDot = selectedUserPresetId != null,
                        showBookmarkIcon = true,
                        testTag = "my_presets_section",
                        onClick = { activeCategoryKey = MY_PRESETS_CATEGORY_KEY }
                    )
                }
            }

            // Compact Save Look action (48dp touch target)
            Box(
                modifier = Modifier
                    .defaultMinSize(minWidth = 48.dp, minHeight = 40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(StudioTileSurface)
                    .border(1.dp, StudioTileBorder, RoundedCornerShape(10.dp))
                    .clickable { showSaveDialog = true }
                    .padding(horizontal = 9.dp, vertical = 6.dp)
                    .testTag("save_current_look_button"),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(R.string.action_save_look),
                        tint = StudioCinemaAmber,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = stringResource(R.string.lut_action_save),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = StudioCoolWhite,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            // Compact Import .cube / .xmp action (48dp touch target)
            Box(
                modifier = Modifier
                    .defaultMinSize(minWidth = 48.dp, minHeight = 40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(StudioTileSurface)
                    .border(1.dp, StudioCyan.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                    .clickable(onClick = onImportCubeClick)
                    .padding(horizontal = 9.dp, vertical = 6.dp)
                    .testTag("import_lut_button"),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FileUpload,
                        contentDescription = stringResource(R.string.action_import_lut),
                        tint = StudioCyan,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = stringResource(R.string.lut_action_import),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = StudioCyan,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }

        // Row 2: 0%–100% LUT Strength / Intensity Slider directly above the LUT thumbnail strip
        val hasSelectedLut = selectedLutId != null
        val strengthLabel = if (activeLut?.sourceFormat == LutSourceFormat.XMP) {
            stringResource(R.string.preset_strength_label)
        } else {
            stringResource(R.string.lut_strength_label)
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(StudioGraphite)
                .border(1.dp, StudioHairline, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .doubleTapToReset(
                        enabled = hasSelectedLut,
                        onDoubleTapReset = {
                            onDraggingChanged(false)
                            onIntensityChange(1.0f)
                        }
                    )
                    .testTag("lut_intensity_label"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = strengthLabel,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = if (hasSelectedLut) StudioCoolWhite else StudioMutedSlate,
                    maxLines = 1,
                    softWrap = false
                )
                if (activeLut != null) {
                    Text(
                        text = "• ${activeLut.title}",
                        style = MaterialTheme.typography.labelSmall,
                        color = StudioCinemaAmber,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (hasSelectedLut) StudioAmberPillFill else StudioSurfaceHighest,
                    modifier = Modifier
                        .border(
                            1.dp,
                            if (hasSelectedLut) StudioCinemaAmber.copy(alpha = 0.65f) else StudioHairline,
                            RoundedCornerShape(6.dp)
                        )
                        .testTag("lut_intensity_value")
                ) {
                    Text(
                        text = "${(lutIntensity * 100f).roundToInt()}%",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            fontFeatureSettings = "tnum"
                        ),
                        color = if (hasSelectedLut) StudioCoolWhite else StudioTextMuted,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                }
            }
            StudioPrecisionTrack(
                value = lutIntensity,
                valueRange = 0f..1f,
                step = 0.01f,
                enabled = hasSelectedLut,
                accentColor = StudioCinemaAmber,
                compact = true,
                testTag = "lut_intensity_slider",
                onValueChange = { v ->
                    onDraggingChanged(true)
                    onIntensityChange(v)
                },
                onValueChangeFinished = { onDraggingChanged(false) },
                onReset = {
                    onDraggingChanged(false)
                    onIntensityChange(1.0f)
                }
            )
        }

        // Row 3: Permanently Visible Horizontal Scrollable Compact Square LUT Thumbnail Strip
        if (isViewingMyPresets) {
            if (userPresets.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(94.dp)
                        .padding(horizontal = 12.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(StudioTileSurface)
                        .border(1.dp, StudioTileBorder, RoundedCornerShape(12.dp))
                        .testTag("lut_thumbnail_strip"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.lut_empty_my_presets),
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioMutedSlate,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            } else {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(96.dp)
                        .testTag("lut_thumbnail_strip"),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items(items = userPresets, key = { it.id }) { preset ->
                        CompactUserPresetSquareCard(
                            preset = preset,
                            thumbnail = userPresetThumbnails[preset.id] ?: sourcePreviewBitmap,
                            selected = selectedUserPresetId == preset.id,
                            onClick = { onApplyUserPreset(preset.id) },
                            onDelete = { onDeleteUserPreset(preset.id) }
                        )
                    }
                }
            }
        } else {
            val folderItems = activeFolder?.items.orEmpty()
            val isFolderLoading = activeFolder?.isLoading == true && folderItems.all { it.previewThumbnail == null }

            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .testTag("lut_thumbnail_strip"),
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // First card: Original / None (one-tap LUT off)
                item(key = "lut_none_card") {
                    CompactLutSquareCard(
                        title = stringResource(R.string.lut_none_label),
                        thumbnail = sourcePreviewBitmap,
                        badgeText = null,
                        selected = selectedLutId == null && selectedUserPresetId == null,
                        isLoadingPreview = false,
                        onClick = { onSelectLut(null) },
                        onDelete = null,
                        modifier = Modifier.testTag("lut_clear_active_button")
                    )
                }

                items(items = folderItems, key = { it.id }) { item ->
                    val badge = when {
                        item.sourceFormat == LutSourceFormat.XMP -> "XMP"
                        item.lut != null -> "${item.lut.size}³"
                        else -> "LUT"
                    }
                    CompactLutSquareCard(
                        title = item.title,
                        thumbnail = item.previewThumbnail ?: sourcePreviewBitmap,
                        badgeText = badge,
                        selected = selectedLutId == item.id,
                        isLoadingPreview = item.previewThumbnail == null && isFolderLoading,
                        onClick = { onSelectLut(item.id) },
                        onDelete = if (item.isCustom) {
                            { onDeleteCustomLut(item.id) }
                        } else {
                            null
                        },
                        modifier = Modifier.testTag("lut_card_${item.id}")
                    )
                }
            }
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
private fun LutCategoryChip(
    label: String,
    count: Int,
    selected: Boolean,
    hasActiveDot: Boolean,
    showBookmarkIcon: Boolean = false,
    testTag: String,
    onClick: () -> Unit
) {
    val bgColor by animateColorAsState(
        targetValue = if (selected) StudioAmberPillFill else StudioTileSurface,
        animationSpec = tween(durationMillis = 140),
        label = "lut_cat_bg"
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            selected -> StudioCinemaAmber
            hasActiveDot -> StudioCinemaAmber.copy(alpha = 0.55f)
            else -> StudioTileBorder
        },
        animationSpec = tween(durationMillis = 140),
        label = "lut_cat_border"
    )
    val textColor by animateColorAsState(
        targetValue = if (selected) StudioCinemaAmber else StudioCoolWhite.copy(alpha = 0.85f),
        animationSpec = tween(durationMillis = 140),
        label = "lut_cat_text"
    )

    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            if (showBookmarkIcon) {
                Icon(
                    imageVector = Icons.Default.Bookmark,
                    contentDescription = null,
                    tint = if (selected) StudioCinemaAmber else StudioMutedSlate,
                    modifier = Modifier.size(13.dp)
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                ),
                color = textColor,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "$count",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = if (selected) StudioCinemaAmber else StudioMutedSlate,
                maxLines = 1,
                softWrap = false
            )
            if (hasActiveDot) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(StudioCinemaAmber)
                        .testTag("${testTag}_active_dot")
                )
            }
        }
    }
}

/**
 * Compact 68dp × 68dp square live-preview LUT thumbnail card with active Cinema Amber border
 * and single-line LUT name directly underneath.
 */
@Composable
private fun CompactLutSquareCard(
    title: String,
    thumbnail: Bitmap?,
    badgeText: String?,
    selected: Boolean,
    isLoadingPreview: Boolean,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val borderColor by animateColorAsState(
        targetValue = if (selected) StudioCinemaAmber else StudioTileBorder,
        animationSpec = tween(durationMillis = 140),
        label = "lut_card_border"
    )
    val cardBg by animateColorAsState(
        targetValue = if (selected) StudioAmberPillFill else StudioTileSurface,
        animationSpec = tween(durationMillis = 140),
        label = "lut_card_bg"
    )

    Column(
        modifier = modifier
            .width(74.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(cardBg)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Box(
            modifier = Modifier
                .size(68.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF1F2735), Color(0xFF2B3548))
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            if (thumbnail != null && !thumbnail.isRecycled) {
                val imgBmp = remember(thumbnail) { thumbnail.asImageBitmap() }
                Image(
                    bitmap = imgBmp,
                    contentDescription = "$title LUT preview",
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

            if (isLoadingPreview) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(Color.Black.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = StudioCinemaAmber,
                        strokeWidth = 1.8.dp,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            if (onDelete != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onDelete),
                    contentAlignment = Alignment.TopStart
                ) {
                    Surface(
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.72f),
                        modifier = Modifier
                            .padding(2.dp)
                            .size(18.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Remove $title",
                                tint = StudioCoolWhite,
                                modifier = Modifier.size(11.dp)
                            )
                        }
                    }
                }
            }

            if (!badgeText.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Color.Black.copy(alpha = 0.72f),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(3.dp)
                ) {
                    Text(
                        text = badgeText,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 8.sp,
                            lineHeight = 10.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = if (selected) StudioCinemaAmber else StudioCoolWhite,
                        modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp)
                    )
                }
            }
        }

        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.5.sp,
                lineHeight = 13.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
            ),
            color = if (selected) StudioCinemaAmber else StudioCoolWhite,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp)
        )
    }
}

@Composable
private fun CompactUserPresetSquareCard(
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
    CompactLutSquareCard(
        title = preset.name,
        thumbnail = thumbnail,
        badgeText = badge,
        selected = selected,
        isLoadingPreview = false,
        onClick = onClick,
        onDelete = onDelete,
        modifier = Modifier.testTag("user_preset_card_${preset.id}")
    )
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
