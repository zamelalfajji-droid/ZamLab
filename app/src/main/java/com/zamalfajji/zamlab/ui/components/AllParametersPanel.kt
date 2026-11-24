package com.zamalfajji.zamlab.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.FilterBAndW
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Texture
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zamalfajji.zamlab.R
import com.zamalfajji.zamlab.editor.CameraProfiles
import com.zamalfajji.zamlab.editor.ColorGradingData
import com.zamalfajji.zamlab.editor.ColorWheelValue
import com.zamalfajji.zamlab.editor.EyedropperMode
import com.zamalfajji.zamlab.editor.GradingParams
import com.zamalfajji.zamlab.editor.HistogramData
import com.zamalfajji.zamlab.editor.HslBand
import com.zamalfajji.zamlab.editor.HslBandAdjustment
import com.zamalfajji.zamlab.editor.PointColorData
import com.zamalfajji.zamlab.editor.ToneCurveChannel
import com.zamalfajji.zamlab.editor.ToneCurveData
import com.zamalfajji.zamlab.editor.WhiteBalancePreset
import com.zamalfajji.zamlab.ui.theme.StudioActiveOffWhite
import com.zamalfajji.zamlab.ui.theme.StudioAmberPillFill
import com.zamalfajji.zamlab.ui.theme.StudioCharcoal
import com.zamalfajji.zamlab.ui.theme.StudioCinemaAmber
import com.zamalfajji.zamlab.ui.theme.StudioCoolWhite
import com.zamalfajji.zamlab.ui.theme.StudioGlassHairline
import com.zamalfajji.zamlab.ui.theme.StudioGlassHighlight
import com.zamalfajji.zamlab.ui.theme.StudioGlassSurface
import com.zamalfajji.zamlab.ui.theme.StudioGraphite
import com.zamalfajji.zamlab.ui.theme.StudioHairline
import com.zamalfajji.zamlab.ui.theme.StudioLuxuryGold
import com.zamalfajji.zamlab.ui.theme.StudioMutedSlate
import com.zamalfajji.zamlab.ui.theme.StudioObsidian
import com.zamalfajji.zamlab.ui.theme.StudioSurfaceElevated
import com.zamalfajji.zamlab.ui.theme.StudioTextMuted
import com.zamalfajji.zamlab.ui.theme.StudioTextPrimary
import com.zamalfajji.zamlab.ui.theme.StudioTextSecondary
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class AllFilterChip(val label: String, val tag: String) {
    ALL("All", "all_filter_all"),
    EDITED("Edited", "all_filter_edited"),
    LIGHT("Light", "all_filter_light"),
    COLOR("Color", "all_filter_color"),
    CURVE("Curve", "all_filter_curve"),
    EFFECTS("Effects", "all_filter_effects")
}

private val ClassicLightTools = listOf(
    FocusSliderTool.EXPOSURE,
    FocusSliderTool.CONTRAST,
    FocusSliderTool.HIGHLIGHTS,
    FocusSliderTool.SHADOWS,
    FocusSliderTool.WHITES,
    FocusSliderTool.BLACKS
)

private val ClassicColorCoreTools = listOf(
    FocusSliderTool.TEMP,
    FocusSliderTool.TINT,
    FocusSliderTool.VIBRANCE,
    FocusSliderTool.SATURATION
)

private val ClassicEffectsDetailTools = listOf(
    FocusSliderTool.TEXTURE,
    FocusSliderTool.CLARITY,
    FocusSliderTool.DEHAZE,
    FocusSliderTool.VIGNETTE,
    FocusSliderTool.SHARPEN,
    FocusSliderTool.GRAIN,
    FocusSliderTool.HALATION,
    FocusSliderTool.BLOOM,
    FocusSliderTool.CHROMATIC_ABERRATION,
    FocusSliderTool.ANAMORPHIC_STREAK
)

private fun countModifiedParameters(params: GradingParams): Int {
    var count = 0
    count += ClassicLightTools.count { params.isModified(it) }
    count += ClassicColorCoreTools.count { params.isModified(it) }
    if (params.isGrayscale) count += 1
    if (params.hasProfileEdits) count += 1
    if (params.whiteBalancePreset != WhiteBalancePreset.AS_SHOT &&
        params.whiteBalancePreset != WhiteBalancePreset.CUSTOM &&
        !params.isModified(FocusSliderTool.TEMP) &&
        !params.isModified(FocusSliderTool.TINT)
    ) {
        count += 1
    }
    HslBand.entries.forEach { band ->
        val adj = params.hsl[band]
        if (abs(adj.hue) > 0.01f) count += 1
        if (abs(adj.saturation) > 0.01f) count += 1
        if (abs(adj.luminance) > 0.01f) count += 1
    }
    if (abs(params.pointColor.hueShift) > 0.01f) count += 1
    if (abs(params.pointColor.saturationShift) > 0.01f) count += 1
    if (abs(params.pointColor.luminanceShift) > 0.01f) count += 1
    val cg = params.colorGrading
    if (!cg.shadows.isDefault) count += 1
    if (!cg.midtones.isDefault) count += 1
    if (!cg.highlights.isDefault) count += 1
    if (!cg.global.isDefault) count += 1
    if (abs(cg.blending - 50f) > 0.01f) count += 1
    if (abs(cg.balance) > 0.01f) count += 1
    count += ToneCurveChannel.entries.count { params.toneCurve.isChannelModified(it) }
    count += ClassicEffectsDetailTools.count { params.isModified(it) }
    if (params.noiseReduction > 0.01f) count += 1
    if (params.colorNoiseReduction > 0.01f) count += 1
    return count
}

/**
 * Spacious, fast Pro Studio view for the "All" tab.
 *
 * Features:
 * - Pinned header with active edit count badge and a compact "Save Recipe" button
 *   that saves the current adjustment stack into the LUTs tab's reusable presets.
 * - Sticky horizontal frosted-glass filter bar (All, Edited (count), Light, Color, Curve, Effects).
 * - "Edited" mode isolates only non-zero parameters across the entire photo for effortless review,
 *   fine-tuning, or double-tap-to-reset.
 * - Clean all-open pro sections with compact frosted-glass slider rows, 1.dp hairline borders,
 *   warm brushed-gold (#F5B841) modified dots, and universal double-tap-to-reset.
 */
@Composable
fun AllParametersPanel(
    params: GradingParams,
    histogram: HistogramData?,
    activeEyedropperMode: EyedropperMode,
    savedRecipeCount: Int,
    onParamsChange: ((GradingParams) -> GradingParams) -> Unit,
    onDraggingChanged: (Boolean) -> Unit,
    onOpenProfileMenu: () -> Unit,
    onSelectWhiteBalancePreset: (WhiteBalancePreset) -> Unit,
    onToggleEyedropper: (EyedropperMode) -> Unit,
    onAutoTone: () -> Unit,
    onToggleBlackAndWhite: () -> Unit,
    onSaveRecipe: (String) -> Unit,
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
    var selectedFilter by remember { mutableStateOf(AllFilterChip.ALL) }
    var showSaveRecipeDialog by remember { mutableStateOf(false) }

    var isLightExpanded by remember { mutableStateOf(true) }
    var isColorExpanded by remember { mutableStateOf(true) }
    var isCurveExpanded by remember { mutableStateOf(true) }
    var isEffectsExpanded by remember { mutableStateOf(true) }

    var activeCurveChannel by remember { mutableStateOf(ToneCurveChannel.MASTER) }
    var activeHslBand by remember { mutableStateOf(HslBand.RED) }
    var isHslExpanded by remember { mutableStateOf(false) }
    var isPointColorExpanded by remember { mutableStateOf(false) }
    var isColorGradingExpanded by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()
    LaunchedEffect(selectedFilter) {
        scrollState.scrollTo(0)
    }

    val hasLightSlidersModified = remember(params) {
        ClassicLightTools.any { params.isModified(it) }
    }
    val hasColorSectionModified = params.hasColorEdits || params.hasProfileEdits
    val hasCurveModified = !params.toneCurve.isDefault
    val hasEffectsOrDetailModified = params.hasEffectsEdits || params.hasDetailEdits
    val editedCount = remember(params) { countModifiedParameters(params) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("all_parameters_panel")
    ) {
        // 1. Pinned Compact Studio Header + "Save Recipe" Pill
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Studio Controls",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.2.sp
                    ),
                    color = StudioActiveOffWhite,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (editedCount > 0) {
                    Box(
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .clip(RoundedCornerShape(50))
                            .clickable {
                                selectedFilter = if (selectedFilter == AllFilterChip.EDITED) {
                                    AllFilterChip.ALL
                                } else {
                                    AllFilterChip.EDITED
                                }
                            }
                            .testTag("all_edited_count_badge"),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = StudioLuxuryGold.copy(alpha = 0.14f),
                            modifier = Modifier.border(
                                width = 1.dp,
                                color = StudioLuxuryGold.copy(alpha = 0.32f),
                                shape = RoundedCornerShape(50)
                            )
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(5.dp)
                                        .clip(CircleShape)
                                        .background(StudioLuxuryGold)
                                )
                                Text(
                                    text = "$editedCount edited",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        fontFeatureSettings = "tnum"
                                    ),
                                    color = StudioLuxuryGold,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }
            }

            // Compact "Save Recipe" pill button
            Box(
                modifier = Modifier
                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    .clip(RoundedCornerShape(50))
                    .clickable { showSaveRecipeDialog = true }
                    .testTag("all_save_recipe_button"),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (editedCount > 0) {
                        StudioGlassHighlight
                    } else {
                        StudioObsidian.copy(alpha = 0.65f)
                    },
                    modifier = Modifier
                        .defaultMinSize(minHeight = 34.dp)
                        .border(
                            width = 1.dp,
                            color = if (editedCount > 0) {
                                StudioLuxuryGold.copy(alpha = 0.42f)
                            } else {
                                StudioGlassHairline
                            },
                            shape = RoundedCornerShape(50)
                        )
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = StudioIcons.SaveRecipe,
                            contentDescription = stringResource(R.string.cd_save_recipe),
                            tint = StudioCinemaAmber,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = stringResource(R.string.action_save_recipe),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.1.sp
                            ),
                            color = StudioCoolWhite,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }

        // 2. Sticky Frosted-Glass Category Jump & "Edited Only" Filter Bar
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp)
                .testTag("all_sticky_filter_bar"),
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items(
                items = AllFilterChip.entries,
                key = { it.name }
            ) { chip ->
                val isSelected = selectedFilter == chip
                val showGoldDot = when (chip) {
                    AllFilterChip.ALL -> false
                    AllFilterChip.EDITED -> editedCount > 0
                    AllFilterChip.LIGHT -> hasLightSlidersModified
                    AllFilterChip.COLOR -> hasColorSectionModified
                    AllFilterChip.CURVE -> hasCurveModified
                    AllFilterChip.EFFECTS -> hasEffectsOrDetailModified
                }
                val labelText = if (chip == AllFilterChip.EDITED) {
                    "Edited ($editedCount)"
                } else {
                    chip.label
                }

                AllFilterPill(
                    label = labelText,
                    selected = isSelected,
                    showGoldDot = showGoldDot,
                    testTag = chip.tag,
                    onClick = { selectedFilter = chip }
                )
            }
        }

        HorizontalDivider(
            color = StudioGlassHairline,
            thickness = 1.dp,
            modifier = Modifier.padding(horizontal = 12.dp)
        )

        // 3. Scrollable Pro Parameter Body
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            when (selectedFilter) {
                AllFilterChip.EDITED -> {
                    EditedOnlyContent(
                        params = params,
                        editedCount = editedCount,
                        histogram = histogram,
                        activeCurveChannel = activeCurveChannel,
                        onSelectCurveChannel = { activeCurveChannel = it },
                        onParamsChange = onParamsChange,
                        onDraggingChanged = onDraggingChanged,
                        onOpenProfileMenu = onOpenProfileMenu,
                        onAutoTone = onAutoTone,
                        onToggleBlackAndWhite = onToggleBlackAndWhite,
                        onBrowseAll = { selectedFilter = AllFilterChip.ALL },
                        onUpdateChromaticAberration = onUpdateChromaticAberration,
                        onResetChromaticAberration = onResetChromaticAberration,
                        onUpdateAnamorphicStreak = onUpdateAnamorphicStreak,
                        onResetAnamorphicStreak = onResetAnamorphicStreak
                    )
                }

                else -> {
                    val isSingleGroup = selectedFilter != AllFilterChip.ALL

                    if (selectedFilter == AllFilterChip.ALL || selectedFilter == AllFilterChip.LIGHT) {
                        FrostedStudioSection(
                            title = "Light",
                            icon = StudioIcons.Exposure,
                            isExpanded = isSingleGroup || isLightExpanded,
                            collapsible = !isSingleGroup,
                            hasEdits = hasLightSlidersModified,
                            onToggleExpand = { isLightExpanded = !isLightExpanded },
                            onResetSection = if (hasLightSlidersModified) {
                                {
                                    onDraggingChanged(false)
                                    onParamsChange {
                                        it.copy(
                                            exposure = 0f,
                                            contrast = 0f,
                                            highlights = 0f,
                                            shadows = 0f,
                                            whites = 0f,
                                            blacks = 0f
                                        )
                                    }
                                }
                            } else null,
                            sectionTag = "all_section_light"
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                                        .clip(RoundedCornerShape(50))
                                        .clickable(onClick = onAutoTone)
                                        .testTag("all_auto_button"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(50),
                                        color = StudioGlassHighlight,
                                        modifier = Modifier
                                            .defaultMinSize(minHeight = 34.dp)
                                            .border(
                                                width = 1.dp,
                                                color = StudioActiveOffWhite.copy(alpha = 0.22f),
                                                shape = RoundedCornerShape(50)
                                            )
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
                                        ) {
                                            Icon(
                                                imageVector = StudioIcons.AutoTone,
                                                contentDescription = "Auto Tone",
                                                tint = StudioCinemaAmber,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Text(
                                                text = "Auto Tone",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontWeight = FontWeight.SemiBold
                                                ),
                                                color = StudioCoolWhite,
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }
                                    }
                                }

                                Text(
                                    text = "Double-tap any slider to reset",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = StudioTextMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.End,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            ClassicLightTools.forEach { tool ->
                                ClassicPrecisionSliderRow(
                                    tool = tool,
                                    value = params.valueFor(tool),
                                    isModified = params.isModified(tool),
                                    onValueChange = { next ->
                                        onDraggingChanged(true)
                                        onParamsChange { it.withToolValue(tool, next) }
                                    },
                                    onValueChangeFinished = { onDraggingChanged(false) },
                                    onReset = {
                                        onDraggingChanged(false)
                                        onParamsChange { it.withToolValue(tool, 0f) }
                                    }
                                )
                            }
                        }
                    }

                    if (selectedFilter == AllFilterChip.ALL || selectedFilter == AllFilterChip.COLOR) {
                        FrostedStudioSection(
                            title = "Color",
                            icon = StudioIcons.Saturation,
                            isExpanded = isSingleGroup || isColorExpanded,
                            collapsible = !isSingleGroup,
                            hasEdits = hasColorSectionModified,
                            onToggleExpand = { isColorExpanded = !isColorExpanded },
                            onResetSection = if (params.hasColorEdits) {
                                {
                                    onDraggingChanged(false)
                                    onParamsChange { it.resetColor() }
                                }
                            } else null,
                            sectionTag = "all_section_color"
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                                        .clip(RoundedCornerShape(50))
                                        .clickable(onClick = onToggleBlackAndWhite)
                                        .testTag("all_bw_toggle"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(50),
                                        color = if (params.isGrayscale) {
                                            StudioActiveOffWhite
                                        } else {
                                            StudioObsidian.copy(alpha = 0.72f)
                                        },
                                        modifier = Modifier
                                            .defaultMinSize(minHeight = 34.dp)
                                            .border(
                                                width = 1.dp,
                                                color = if (params.isGrayscale) {
                                                    StudioActiveOffWhite
                                                } else {
                                                    StudioGlassHairline
                                                },
                                                shape = RoundedCornerShape(50)
                                            )
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.FilterBAndW,
                                                contentDescription = "Toggle Black and White",
                                                tint = if (params.isGrayscale) StudioObsidian else StudioActiveOffWhite,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Text(
                                                text = "B&W",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontWeight = FontWeight.SemiBold
                                                ),
                                                color = if (params.isGrayscale) StudioObsidian else StudioActiveOffWhite,
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                            if (params.isGrayscale) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(4.5.dp)
                                                        .clip(CircleShape)
                                                        .background(StudioLuxuryGold)
                                                )
                                            }
                                        }
                                    }
                                }

                                val activeProfile = remember(params.profileId) {
                                    CameraProfiles.byId(params.profileId)
                                }
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .defaultMinSize(minHeight = 48.dp)
                                        .clip(RoundedCornerShape(50))
                                        .clickable(onClick = onOpenProfileMenu)
                                        .testTag("all_profile_button"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(50),
                                        color = StudioObsidian.copy(alpha = 0.72f),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .defaultMinSize(minHeight = 34.dp)
                                            .border(
                                                width = 1.dp,
                                                color = if (params.hasProfileEdits) {
                                                    StudioLuxuryGold.copy(alpha = 0.4f)
                                                } else {
                                                    StudioGlassHairline
                                                },
                                                shape = RoundedCornerShape(50)
                                            )
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Camera,
                                                contentDescription = "Camera Profile",
                                                tint = if (params.hasProfileEdits) StudioLuxuryGold else StudioTextSecondary,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Text(
                                                text = if (params.hasProfileEdits) {
                                                    "Profile: ${activeProfile.name}"
                                                } else {
                                                    "Camera Profile"
                                                },
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontWeight = if (params.hasProfileEdits) FontWeight.SemiBold else FontWeight.Medium
                                                ),
                                                color = StudioActiveOffWhite,
                                                maxLines = 1,
                                                softWrap = false,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                            if (params.hasProfileEdits) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(4.5.dp)
                                                        .clip(CircleShape)
                                                        .background(StudioLuxuryGold)
                                                )
                                            }
                                        }
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
                                onToggleEyedropper = onToggleEyedropper,
                                onParamsChange = onParamsChange,
                                onDraggingChanged = onDraggingChanged
                            )
                        }
                    }

                    if (selectedFilter == AllFilterChip.ALL || selectedFilter == AllFilterChip.CURVE) {
                        FrostedStudioSection(
                            title = "Tone Curve",
                            icon = StudioIcons.ToneCurve,
                            isExpanded = isSingleGroup || isCurveExpanded,
                            collapsible = !isSingleGroup,
                            hasEdits = hasCurveModified,
                            onToggleExpand = { isCurveExpanded = !isCurveExpanded },
                            onResetSection = if (hasCurveModified) {
                                {
                                    onDraggingChanged(false)
                                    onParamsChange { it.copy(toneCurve = ToneCurveData()) }
                                }
                            } else null,
                            sectionTag = "all_section_curve"
                        ) {
                            ToneCurveEditor(
                                toneCurve = params.toneCurve,
                                histogram = histogram,
                                activeChannel = activeCurveChannel,
                                onSelectChannel = { activeCurveChannel = it },
                                onPointsChange = { channel, nextPts ->
                                    onParamsChange {
                                        it.copy(toneCurve = it.toneCurve.withChannel(channel, nextPts))
                                    }
                                },
                                onDraggingChanged = onDraggingChanged,
                                onResetChannel = { channel ->
                                    onDraggingChanged(false)
                                    onParamsChange {
                                        it.copy(toneCurve = it.toneCurve.resetChannel(channel))
                                    }
                                }
                            )
                        }
                    }

                    if (selectedFilter == AllFilterChip.ALL || selectedFilter == AllFilterChip.EFFECTS) {
                        FrostedStudioSection(
                            title = "Effects & Detail",
                            icon = StudioIcons.Texture,
                            isExpanded = isSingleGroup || isEffectsExpanded,
                            collapsible = !isSingleGroup,
                            hasEdits = hasEffectsOrDetailModified,
                            onToggleExpand = { isEffectsExpanded = !isEffectsExpanded },
                            onResetSection = if (hasEffectsOrDetailModified) {
                                {
                                    onDraggingChanged(false)
                                    onParamsChange { it.resetEffects().resetDetail() }
                                }
                            } else null,
                            sectionTag = "all_section_effects_detail"
                        ) {
                            ClassicEffectsDetailTools.forEach { tool ->
                                ClassicPrecisionSliderRow(
                                    tool = tool,
                                    value = params.valueFor(tool),
                                    isModified = params.isModified(tool),
                                    onValueChange = { next ->
                                        onDraggingChanged(true)
                                        when (tool) {
                                            FocusSliderTool.CHROMATIC_ABERRATION -> onUpdateChromaticAberration(next)
                                            FocusSliderTool.ANAMORPHIC_STREAK -> onUpdateAnamorphicStreak(next)
                                            else -> onParamsChange { it.withToolValue(tool, next) }
                                        }
                                    },
                                    onValueChangeFinished = { onDraggingChanged(false) },
                                    onReset = {
                                        when (tool) {
                                            FocusSliderTool.CHROMATIC_ABERRATION -> onResetChromaticAberration()
                                            FocusSliderTool.ANAMORPHIC_STREAK -> onResetAnamorphicStreak()
                                            else -> {
                                                onDraggingChanged(false)
                                                onParamsChange { it.withToolValue(tool, 0f) }
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
        }
    }

    if (showSaveRecipeDialog) {
        SaveRecipeDialog(
            suggestedName = "My Recipe ${savedRecipeCount + 1}",
            editedCount = editedCount,
            onDismiss = { showSaveRecipeDialog = false },
            onConfirm = { name ->
                onSaveRecipe(name)
                showSaveRecipeDialog = false
            }
        )
    }
}

@Composable
private fun AllFilterPill(
    label: String,
    selected: Boolean,
    showGoldDot: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    val bgColor by animateColorAsState(
        targetValue = if (selected) StudioAmberPillFill else StudioGraphite,
        animationSpec = tween(durationMillis = 140),
        label = "filter_pill_bg"
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            selected -> StudioCinemaAmber
            showGoldDot -> StudioCinemaAmber.copy(alpha = 0.42f)
            else -> StudioHairline
        },
        animationSpec = tween(durationMillis = 140),
        label = "filter_pill_border"
    )
    val textColor by animateColorAsState(
        targetValue = if (selected) StudioCinemaAmber else StudioMutedSlate,
        animationSpec = tween(durationMillis = 140),
        label = "filter_pill_text"
    )

    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = bgColor,
            modifier = Modifier
                .defaultMinSize(minHeight = 32.dp)
                .border(1.dp, borderColor, RoundedCornerShape(50))
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 11.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        fontFeatureSettings = "tnum"
                    ),
                    color = textColor,
                    maxLines = 1,
                    softWrap = false
                )
                if (showGoldDot) {
                    Box(
                        modifier = Modifier
                            .size(4.5.dp)
                            .clip(CircleShape)
                            .background(StudioCinemaAmber)
                    )
                }
            }
        }
    }
}

@Composable
private fun EditedOnlyContent(
    params: GradingParams,
    editedCount: Int,
    histogram: HistogramData?,
    activeCurveChannel: ToneCurveChannel,
    onSelectCurveChannel: (ToneCurveChannel) -> Unit,
    onParamsChange: ((GradingParams) -> GradingParams) -> Unit,
    onDraggingChanged: (Boolean) -> Unit,
    onOpenProfileMenu: () -> Unit,
    onAutoTone: () -> Unit,
    onToggleBlackAndWhite: () -> Unit,
    onBrowseAll: () -> Unit,
    onUpdateChromaticAberration: (Float) -> Unit,
    onResetChromaticAberration: () -> Unit,
    onUpdateAnamorphicStreak: (Float) -> Unit,
    onResetAnamorphicStreak: () -> Unit
) {
    if (editedCount == 0) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = StudioGlassSurface,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, StudioGlassHairline, RoundedCornerShape(16.dp))
                .testTag("all_edited_empty_state")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(StudioLuxuryGold.copy(alpha = 0.12f))
                        .border(1.dp, StudioLuxuryGold.copy(alpha = 0.28f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        tint = StudioLuxuryGold,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Text(
                    text = "No Active Adjustments",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = StudioActiveOffWhite
                )
                Text(
                    text = "Modified sliders, color curves, and HSL tweaks appear here for fast review and double-tap reset.",
                    style = MaterialTheme.typography.bodySmall,
                    color = StudioTextSecondary,
                    textAlign = TextAlign.Center
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = StudioActiveOffWhite,
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .clip(RoundedCornerShape(50))
                            .clickable(onClick = onAutoTone)
                            .testTag("edited_empty_auto_btn")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = StudioObsidian,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = "Auto Tone",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = StudioObsidian,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(50),
                        color = StudioObsidian.copy(alpha = 0.75f),
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .clip(RoundedCornerShape(50))
                            .border(1.dp, StudioGlassHairline, RoundedCornerShape(50))
                            .clickable(onClick = onBrowseAll)
                            .testTag("edited_empty_browse_btn")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "Browse All",
                                style = MaterialTheme.typography.labelMedium,
                                color = StudioActiveOffWhite,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }
        }
        return
    }

    val modifiedLight = ClassicLightTools.filter { params.isModified(it) }
    val modifiedColorCore = ClassicColorCoreTools.filter { params.isModified(it) }
    val modifiedHslBands = HslBand.entries.filter { !params.hsl[it].isDefault }
    val hasModifiedColorCard = modifiedColorCore.isNotEmpty() ||
        params.isGrayscale ||
        params.hasProfileEdits ||
        (params.whiteBalancePreset != WhiteBalancePreset.AS_SHOT && params.whiteBalancePreset != WhiteBalancePreset.CUSTOM) ||
        modifiedHslBands.isNotEmpty() ||
        params.pointColor.isModified ||
        !params.colorGrading.isDefault
    val modifiedEffects = ClassicEffectsDetailTools.filter { params.isModified(it) }
    val hasModifiedDetailNr = params.noiseReduction > 0.01f || params.colorNoiseReduction > 0.01f

    // Modified Light
    if (modifiedLight.isNotEmpty()) {
        FrostedStudioSection(
            title = "Light (${modifiedLight.size})",
            icon = Icons.Default.Brightness6,
            isExpanded = true,
            collapsible = false,
            hasEdits = true,
            onToggleExpand = {},
            onResetSection = {
                onDraggingChanged(false)
                onParamsChange {
                    it.copy(
                        exposure = 0f,
                        contrast = 0f,
                        highlights = 0f,
                        shadows = 0f,
                        whites = 0f,
                        blacks = 0f
                    )
                }
            },
            sectionTag = "edited_section_light"
        ) {
            modifiedLight.forEach { tool ->
                ClassicPrecisionSliderRow(
                    tool = tool,
                    value = params.valueFor(tool),
                    isModified = true,
                    onValueChange = { next ->
                        onDraggingChanged(true)
                        onParamsChange { it.withToolValue(tool, next) }
                    },
                    onValueChangeFinished = { onDraggingChanged(false) },
                    onReset = {
                        onDraggingChanged(false)
                        onParamsChange { it.withToolValue(tool, 0f) }
                    }
                )
            }
        }
    }

    // Modified Color (Profile, B&W, WB, Core Color, HSL, Point Color, Color Grading)
    if (hasModifiedColorCard) {
        FrostedStudioSection(
            title = "Color",
            icon = Icons.Default.Palette,
            isExpanded = true,
            collapsible = false,
            hasEdits = true,
            onToggleExpand = {},
            onResetSection = {
                onDraggingChanged(false)
                onParamsChange {
                    it.resetColor().copy(
                        profileId = CameraProfiles.DEFAULT_ID,
                        profileAmount = 1f
                    )
                }
            },
            sectionTag = "edited_section_color"
        ) {
            // Active status pills (B&W, White Balance preset, Camera Profile)
            if (params.isGrayscale ||
                params.hasProfileEdits ||
                (params.whiteBalancePreset != WhiteBalancePreset.AS_SHOT && params.whiteBalancePreset != WhiteBalancePreset.CUSTOM)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (params.isGrayscale) {
                        EditedStatusPill(
                            icon = Icons.Default.FilterBAndW,
                            label = "B&W On",
                            testTag = "edited_bw_pill",
                            onClick = onToggleBlackAndWhite
                        )
                    }
                    if (params.whiteBalancePreset != WhiteBalancePreset.AS_SHOT &&
                        params.whiteBalancePreset != WhiteBalancePreset.CUSTOM
                    ) {
                        EditedStatusPill(
                            icon = Icons.Default.WbSunny,
                            label = "WB: ${params.whiteBalancePreset.label}",
                            testTag = "edited_wb_pill",
                            onClick = {
                                onDraggingChanged(false)
                                onParamsChange {
                                    it.copy(
                                        whiteBalancePreset = WhiteBalancePreset.AS_SHOT,
                                        temperature = 0f,
                                        tint = 0f
                                    )
                                }
                            }
                        )
                    }
                    if (params.hasProfileEdits) {
                        val activeProfile = CameraProfiles.byId(params.profileId)
                        EditedStatusPill(
                            icon = Icons.Default.Camera,
                            label = activeProfile.name,
                            testTag = "edited_profile_pill",
                            onClick = onOpenProfileMenu
                        )
                    }
                }
            }

            if (params.hasProfileEdits) {
                val activeProfile = CameraProfiles.byId(params.profileId)
                val pct = (params.profileAmount * 100f).roundToInt()
                GradientRangeSlider(
                    label = "${activeProfile.name} Amount",
                    icon = Icons.Default.Camera,
                    value = params.profileAmount,
                    valueRange = 0f..2f,
                    step = 0.05f,
                    formattedValue = "$pct%",
                    isModified = true,
                    accentColor = StudioLuxuryGold,
                    gradientColors = listOf(Color(0xFF475569), StudioLuxuryGold),
                    testTag = "edited_profile_amount",
                    compact = true,
                    onValueChange = { next ->
                        onDraggingChanged(true)
                        onParamsChange { it.copy(profileAmount = next) }
                    },
                    onValueChangeFinished = { onDraggingChanged(false) },
                    onReset = {
                        onDraggingChanged(false)
                        onParamsChange { it.copy(profileId = CameraProfiles.DEFAULT_ID, profileAmount = 1f) }
                    }
                )
            }

            modifiedColorCore.forEach { tool ->
                ClassicPrecisionSliderRow(
                    tool = tool,
                    value = params.valueFor(tool),
                    isModified = true,
                    onValueChange = { next ->
                        onDraggingChanged(true)
                        onParamsChange { it.withToolValue(tool, next) }
                    },
                    onValueChangeFinished = { onDraggingChanged(false) },
                    onReset = {
                        onDraggingChanged(false)
                        onParamsChange { it.withToolValue(tool, 0f) }
                    }
                )
            }

            // Modified HSL Channels
            modifiedHslBands.forEach { band ->
                val adj = params.hsl[band]
                val bandColor = Color(band.swatchArgb)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(StudioObsidian.copy(alpha = 0.52f))
                        .border(1.dp, StudioGlassHairline, RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .doubleTapToReset {
                                onDraggingChanged(false)
                                onParamsChange { gp -> gp.copy(hsl = gp.hsl.withBand(band) { HslBandAdjustment() }) }
                            },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(9.dp)
                                    .clip(CircleShape)
                                    .background(bandColor)
                            )
                            Text(
                                text = "HSL · ${band.label}",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = StudioActiveOffWhite,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .clickable {
                                    onDraggingChanged(false)
                                    onParamsChange { gp ->
                                        gp.copy(hsl = gp.hsl.withBand(band) { HslBandAdjustment() })
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.RestartAlt,
                                contentDescription = "Reset ${band.label} HSL",
                                tint = StudioLuxuryGold,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    if (abs(adj.hue) > 0.01f) {
                        GradientRangeSlider(
                            label = "Hue",
                            icon = Icons.Default.Palette,
                            value = adj.hue,
                            valueRange = -100f..100f,
                            formattedValue = String.format(Locale.US, "%+d", adj.hue.roundToInt()),
                            isModified = true,
                            accentColor = bandColor,
                            gradientColors = listOf(Color(0xFF38BDF8), bandColor, Color(0xFFF59E0B)),
                            testTag = "edited_hsl_${band.name.lowercase()}_hue",
                            compact = true,
                            onValueChange = { next ->
                                onDraggingChanged(true)
                                onParamsChange { gp -> gp.copy(hsl = gp.hsl.withBand(band) { it.copy(hue = next) }) }
                            },
                            onValueChangeFinished = { onDraggingChanged(false) },
                            onReset = {
                                onDraggingChanged(false)
                                onParamsChange { gp -> gp.copy(hsl = gp.hsl.withBand(band) { it.copy(hue = 0f) }) }
                            }
                        )
                    }
                    if (abs(adj.saturation) > 0.01f) {
                        GradientRangeSlider(
                            label = "Saturation",
                            icon = Icons.Default.Palette,
                            value = adj.saturation,
                            valueRange = -100f..100f,
                            formattedValue = String.format(Locale.US, "%+d", adj.saturation.roundToInt()),
                            isModified = true,
                            accentColor = bandColor,
                            gradientColors = listOf(Color(0xFF64748B), bandColor),
                            testTag = "edited_hsl_${band.name.lowercase()}_sat",
                            compact = true,
                            onValueChange = { next ->
                                onDraggingChanged(true)
                                onParamsChange { gp ->
                                    gp.copy(hsl = gp.hsl.withBand(band) { it.copy(saturation = next) })
                                }
                            },
                            onValueChangeFinished = { onDraggingChanged(false) },
                            onReset = {
                                onDraggingChanged(false)
                                onParamsChange { gp ->
                                    gp.copy(hsl = gp.hsl.withBand(band) { it.copy(saturation = 0f) })
                                }
                            }
                        )
                    }
                    if (abs(adj.luminance) > 0.01f) {
                        GradientRangeSlider(
                            label = "Luminance",
                            icon = Icons.Default.Brightness6,
                            value = adj.luminance,
                            valueRange = -100f..100f,
                            formattedValue = String.format(Locale.US, "%+d", adj.luminance.roundToInt()),
                            isModified = true,
                            accentColor = bandColor,
                            gradientColors = listOf(Color(0xFF0F172A), bandColor, Color(0xFFF8FAFC)),
                            testTag = "edited_hsl_${band.name.lowercase()}_lum",
                            compact = true,
                            onValueChange = { next ->
                                onDraggingChanged(true)
                                onParamsChange { gp ->
                                    gp.copy(hsl = gp.hsl.withBand(band) { it.copy(luminance = next) })
                                }
                            },
                            onValueChangeFinished = { onDraggingChanged(false) },
                            onReset = {
                                onDraggingChanged(false)
                                onParamsChange { gp ->
                                    gp.copy(hsl = gp.hsl.withBand(band) { it.copy(luminance = 0f) })
                                }
                            }
                        )
                    }
                }
            }

            // Modified Point Color
            if (params.pointColor.isModified) {
                val pc = params.pointColor
                val swatchColor = Color(pc.swatchArgb)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(StudioObsidian.copy(alpha = 0.52f))
                        .border(1.dp, StudioGlassHairline, RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(9.dp)
                                    .clip(CircleShape)
                                    .background(swatchColor)
                            )
                            Text(
                                text = "Point Color",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = StudioActiveOffWhite,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .clickable {
                                    onDraggingChanged(false)
                                    onParamsChange { it.copy(pointColor = PointColorData()) }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.RestartAlt,
                                contentDescription = "Reset Point Color",
                                tint = StudioLuxuryGold,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    if (abs(pc.hueShift) > 0.01f) {
                        GradientRangeSlider(
                            label = "Hue Shift",
                            icon = Icons.Default.Palette,
                            value = pc.hueShift,
                            valueRange = -100f..100f,
                            formattedValue = String.format(Locale.US, "%+d", pc.hueShift.roundToInt()),
                            isModified = true,
                            accentColor = swatchColor,
                            gradientColors = listOf(Color(0xFF38BDF8), swatchColor, Color(0xFFF59E0B)),
                            testTag = "edited_point_hue",
                            compact = true,
                            onValueChange = { next ->
                                onDraggingChanged(true)
                                onParamsChange { it.copy(pointColor = it.pointColor.copy(hueShift = next)) }
                            },
                            onValueChangeFinished = { onDraggingChanged(false) },
                            onReset = {
                                onDraggingChanged(false)
                                onParamsChange { it.copy(pointColor = it.pointColor.copy(hueShift = 0f)) }
                            }
                        )
                    }
                    if (abs(pc.saturationShift) > 0.01f) {
                        GradientRangeSlider(
                            label = "Saturation Shift",
                            icon = Icons.Default.Palette,
                            value = pc.saturationShift,
                            valueRange = -100f..100f,
                            formattedValue = String.format(Locale.US, "%+d", pc.saturationShift.roundToInt()),
                            isModified = true,
                            accentColor = swatchColor,
                            gradientColors = listOf(Color(0xFF64748B), swatchColor),
                            testTag = "edited_point_sat",
                            compact = true,
                            onValueChange = { next ->
                                onDraggingChanged(true)
                                onParamsChange { it.copy(pointColor = it.pointColor.copy(saturationShift = next)) }
                            },
                            onValueChangeFinished = { onDraggingChanged(false) },
                            onReset = {
                                onDraggingChanged(false)
                                onParamsChange { it.copy(pointColor = it.pointColor.copy(saturationShift = 0f)) }
                            }
                        )
                    }
                    if (abs(pc.luminanceShift) > 0.01f) {
                        GradientRangeSlider(
                            label = "Luminance Shift",
                            icon = Icons.Default.Brightness6,
                            value = pc.luminanceShift,
                            valueRange = -100f..100f,
                            formattedValue = String.format(Locale.US, "%+d", pc.luminanceShift.roundToInt()),
                            isModified = true,
                            accentColor = swatchColor,
                            gradientColors = listOf(Color(0xFF0F172A), swatchColor, Color(0xFFF8FAFC)),
                            testTag = "edited_point_lum",
                            compact = true,
                            onValueChange = { next ->
                                onDraggingChanged(true)
                                onParamsChange { it.copy(pointColor = it.pointColor.copy(luminanceShift = next)) }
                            },
                            onValueChangeFinished = { onDraggingChanged(false) },
                            onReset = {
                                onDraggingChanged(false)
                                onParamsChange { it.copy(pointColor = it.pointColor.copy(luminanceShift = 0f)) }
                            }
                        )
                    }
                }
            }

            // Modified 3-Wheel Color Grading
            if (!params.colorGrading.isDefault) {
                val cg = params.colorGrading
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(StudioObsidian.copy(alpha = 0.52f))
                        .border(1.dp, StudioGlassHairline, RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Color Grading Wheels",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = StudioActiveOffWhite,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .clickable {
                                    onDraggingChanged(false)
                                    onParamsChange { it.copy(colorGrading = ColorGradingData()) }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.RestartAlt,
                                contentDescription = "Reset Color Grading",
                                tint = StudioLuxuryGold,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    if (!cg.shadows.isDefault) {
                        EditedWheelSummaryRow(
                            label = "Shadows Wheel",
                            wheel = cg.shadows,
                            onReset = {
                                onDraggingChanged(false)
                                onParamsChange { it.copy(colorGrading = it.colorGrading.copy(shadows = ColorWheelValue())) }
                            }
                        )
                    }
                    if (!cg.midtones.isDefault) {
                        EditedWheelSummaryRow(
                            label = "Midtones Wheel",
                            wheel = cg.midtones,
                            onReset = {
                                onDraggingChanged(false)
                                onParamsChange { it.copy(colorGrading = it.colorGrading.copy(midtones = ColorWheelValue())) }
                            }
                        )
                    }
                    if (!cg.highlights.isDefault) {
                        EditedWheelSummaryRow(
                            label = "Highlights Wheel",
                            wheel = cg.highlights,
                            onReset = {
                                onDraggingChanged(false)
                                onParamsChange { it.copy(colorGrading = it.colorGrading.copy(highlights = ColorWheelValue())) }
                            }
                        )
                    }
                    if (!cg.global.isDefault) {
                        EditedWheelSummaryRow(
                            label = "Global Wheel",
                            wheel = cg.global,
                            onReset = {
                                onDraggingChanged(false)
                                onParamsChange { it.copy(colorGrading = it.colorGrading.copy(global = ColorWheelValue())) }
                            }
                        )
                    }
                    if (abs(cg.blending - 50f) > 0.01f) {
                        GradientRangeSlider(
                            label = "Blending",
                            icon = Icons.Default.Tune,
                            value = cg.blending,
                            valueRange = 0f..100f,
                            formattedValue = "${cg.blending.roundToInt()}",
                            isModified = true,
                            accentColor = StudioLuxuryGold,
                            gradientColors = listOf(Color(0xFF475569), StudioLuxuryGold),
                            testTag = "edited_cg_blending",
                            compact = true,
                            onValueChange = { next ->
                                onDraggingChanged(true)
                                onParamsChange { it.copy(colorGrading = it.colorGrading.copy(blending = next)) }
                            },
                            onValueChangeFinished = { onDraggingChanged(false) },
                            onReset = {
                                onDraggingChanged(false)
                                onParamsChange { it.copy(colorGrading = it.colorGrading.copy(blending = 50f)) }
                            }
                        )
                    }
                    if (abs(cg.balance) > 0.01f) {
                        GradientRangeSlider(
                            label = "Balance",
                            icon = Icons.Default.Tune,
                            value = cg.balance,
                            valueRange = -100f..100f,
                            formattedValue = String.format(Locale.US, "%+d", cg.balance.roundToInt()),
                            isModified = true,
                            accentColor = StudioLuxuryGold,
                            gradientColors = listOf(Color(0xFF38BDF8), StudioActiveOffWhite, StudioLuxuryGold),
                            testTag = "edited_cg_balance",
                            compact = true,
                            onValueChange = { next ->
                                onDraggingChanged(true)
                                onParamsChange { it.copy(colorGrading = it.colorGrading.copy(balance = next)) }
                            },
                            onValueChangeFinished = { onDraggingChanged(false) },
                            onReset = {
                                onDraggingChanged(false)
                                onParamsChange { it.copy(colorGrading = it.colorGrading.copy(balance = 0f)) }
                            }
                        )
                    }
                }
            }
        }
    }

    // Modified Tone Curve
    if (!params.toneCurve.isDefault) {
        FrostedStudioSection(
            title = "Tone Curve",
            icon = Icons.AutoMirrored.Filled.ShowChart,
            isExpanded = true,
            collapsible = false,
            hasEdits = true,
            onToggleExpand = {},
            onResetSection = {
                onDraggingChanged(false)
                onParamsChange { it.copy(toneCurve = ToneCurveData()) }
            },
            sectionTag = "edited_section_curve"
        ) {
            ToneCurveEditor(
                toneCurve = params.toneCurve,
                histogram = histogram,
                activeChannel = activeCurveChannel,
                onSelectChannel = onSelectCurveChannel,
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

    // Modified Effects & Detail
    if (modifiedEffects.isNotEmpty() || hasModifiedDetailNr) {
        val effectsCount = modifiedEffects.size +
            (if (params.noiseReduction > 0.01f) 1 else 0) +
            (if (params.colorNoiseReduction > 0.01f) 1 else 0)
        FrostedStudioSection(
            title = "Effects & Detail ($effectsCount)",
            icon = Icons.Default.Texture,
            isExpanded = true,
            collapsible = false,
            hasEdits = true,
            onToggleExpand = {},
            onResetSection = {
                onDraggingChanged(false)
                onParamsChange { it.resetEffects().resetDetail() }
            },
            sectionTag = "edited_section_effects"
        ) {
            modifiedEffects.forEach { tool ->
                ClassicPrecisionSliderRow(
                    tool = tool,
                    value = params.valueFor(tool),
                    isModified = true,
                    onValueChange = { next ->
                        onDraggingChanged(true)
                        when (tool) {
                            FocusSliderTool.CHROMATIC_ABERRATION -> onUpdateChromaticAberration(next)
                            FocusSliderTool.ANAMORPHIC_STREAK -> onUpdateAnamorphicStreak(next)
                            else -> onParamsChange { it.withToolValue(tool, next) }
                        }
                    },
                    onValueChangeFinished = { onDraggingChanged(false) },
                    onReset = {
                        when (tool) {
                            FocusSliderTool.CHROMATIC_ABERRATION -> onResetChromaticAberration()
                            FocusSliderTool.ANAMORPHIC_STREAK -> onResetAnamorphicStreak()
                            else -> {
                                onDraggingChanged(false)
                                onParamsChange { it.withToolValue(tool, 0f) }
                            }
                        }
                    }
                )
            }
            if (params.noiseReduction > 0.01f) {
                GradientRangeSlider(
                    label = "Noise Reduction",
                    icon = Icons.Default.Texture,
                    value = params.noiseReduction,
                    valueRange = 0f..100f,
                    formattedValue = "${params.noiseReduction.roundToInt()}",
                    isModified = true,
                    accentColor = StudioActiveOffWhite,
                    gradientColors = listOf(Color(0xFF475569), StudioActiveOffWhite),
                    testTag = "edited_noise_reduction",
                    compact = true,
                    onValueChange = { next ->
                        onDraggingChanged(true)
                        onParamsChange { it.copy(noiseReduction = next) }
                    },
                    onValueChangeFinished = { onDraggingChanged(false) },
                    onReset = {
                        onDraggingChanged(false)
                        onParamsChange { it.copy(noiseReduction = 0f) }
                    }
                )
            }
            if (params.colorNoiseReduction > 0.01f) {
                GradientRangeSlider(
                    label = "Color NR",
                    icon = Icons.Default.Palette,
                    value = params.colorNoiseReduction,
                    valueRange = 0f..100f,
                    formattedValue = "${params.colorNoiseReduction.roundToInt()}",
                    isModified = true,
                    accentColor = StudioActiveOffWhite,
                    gradientColors = listOf(Color(0xFF475569), StudioActiveOffWhite),
                    testTag = "edited_color_nr",
                    compact = true,
                    onValueChange = { next ->
                        onDraggingChanged(true)
                        onParamsChange { it.copy(colorNoiseReduction = next) }
                    },
                    onValueChangeFinished = { onDraggingChanged(false) },
                    onReset = {
                        onDraggingChanged(false)
                        onParamsChange { it.copy(colorNoiseReduction = 0f) }
                    }
                )
            }
        }
    }
}

@Composable
private fun EditedStatusPill(
    icon: ImageVector,
    label: String,
    testTag: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = StudioGlassHighlight,
            modifier = Modifier
                .defaultMinSize(minHeight = 32.dp)
                .border(1.dp, StudioLuxuryGold.copy(alpha = 0.36f), RoundedCornerShape(50))
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = StudioLuxuryGold,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = StudioActiveOffWhite,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun EditedWheelSummaryRow(
    label: String,
    wheel: ColorWheelValue,
    onReset: () -> Unit
) {
    val swatch = Color.hsv(
        hue = ((wheel.hueDeg % 360f) + 360f) % 360f,
        saturation = (wheel.saturation / 100f).coerceIn(0f, 1f),
        value = 0.92f
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .doubleTapToReset(onDoubleTapReset = onReset)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .size(11.dp)
                    .clip(CircleShape)
                    .background(swatch)
                    .border(1.dp, StudioActiveOffWhite.copy(alpha = 0.5f), CircleShape)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = StudioActiveOffWhite,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "H ${wheel.hueDeg.roundToInt()}° · S ${wheel.saturation.roundToInt()} · L ${
                    String.format(Locale.US, "%+d", wheel.luminance.roundToInt())
                }",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontFeatureSettings = "tnum"
                ),
                color = StudioLuxuryGold,
                maxLines = 1,
                softWrap = false
            )
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onReset),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.RestartAlt,
                    contentDescription = "Reset $label",
                    tint = StudioTextSecondary,
                    modifier = Modifier.size(15.dp)
                )
            }
        }
    }
}

@Composable
private fun FrostedStudioSection(
    title: String,
    icon: ImageVector,
    isExpanded: Boolean,
    collapsible: Boolean,
    hasEdits: Boolean,
    onToggleExpand: () -> Unit,
    onResetSection: (() -> Unit)?,
    sectionTag: String,
    content: @Composable () -> Unit
) {
    val arrowRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = tween(durationMillis = 160),
        label = "studio_section_arrow"
    )
    val cardShape = RoundedCornerShape(14.dp)

    Surface(
        shape = cardShape,
        color = StudioGlassSurface,
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = if (isExpanded) StudioActiveOffWhite.copy(alpha = 0.11f) else StudioGlassHairline,
                shape = cardShape
            )
            .testTag(sectionTag)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .then(
                        if (collapsible) Modifier.clickable(onClick = onToggleExpand) else Modifier
                    )
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isExpanded || hasEdits) StudioActiveOffWhite else StudioTextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.2.sp
                        ),
                        color = StudioActiveOffWhite,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (hasEdits) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(StudioLuxuryGold)
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (onResetSection != null) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .clickable(onClick = onResetSection)
                                .testTag("${sectionTag}_reset"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.RestartAlt,
                                contentDescription = "Reset $title",
                                tint = StudioLuxuryGold,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                    if (collapsible) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = if (isExpanded) "Collapse $title" else "Expand $title",
                            tint = StudioTextSecondary,
                            modifier = Modifier
                                .size(19.dp)
                                .rotate(arrowRotation)
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(tween(160)) + fadeIn(tween(130)),
                exit = shrinkVertically(tween(130)) + fadeOut(tween(110))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 11.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HorizontalDivider(color = StudioGlassHairline, thickness = 1.dp)
                    content()
                }
            }
        }
    }
}

@Composable
private fun ClassicPrecisionSliderRow(
    tool: FocusSliderTool,
    value: Float,
    isModified: Boolean,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    onReset: () -> Unit
) {
    val formatted = formatToolValue(tool, value)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .doubleTapToReset(onDoubleTapReset = onReset)
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .weight(1f)
                    .testTag("all_${tool.testTag}_label")
            ) {
                Icon(
                    imageVector = tool.icon,
                    contentDescription = null,
                    tint = if (isModified) StudioCinemaAmber else StudioActiveOffWhite.copy(alpha = 0.78f),
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = tool.label,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.2.sp
                    ),
                    color = if (isModified) StudioActiveOffWhite else StudioActiveOffWhite.copy(alpha = 0.88f),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
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
                color = if (isModified) {
                    StudioAmberPillFill
                } else {
                    StudioGraphite
                },
                modifier = Modifier
                    .border(
                        width = 1.dp,
                        color = if (isModified) StudioCinemaAmber.copy(alpha = 0.72f) else StudioHairline,
                        shape = RoundedCornerShape(8.dp)
                    )
                    .testTag("all_${tool.testTag}_value")
            ) {
                Text(
                    text = formatted,
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
            valueRange = tool.valueRange,
            step = tool.step,
            accentColor = StudioCinemaAmber,
            gradientColors = gradientForTool(tool),
            compact = true,
            testTag = "all_${tool.testTag}",
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            onReset = onReset
        )
    }
}

@Composable
private fun SaveRecipeDialog(
    suggestedName: String,
    editedCount: Int,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var recipeName by remember(suggestedName) { mutableStateOf(suggestedName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = StudioGlassSurface,
        titleContentColor = StudioTextPrimary,
        textContentColor = StudioTextSecondary,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.border(1.dp, StudioGlassHairline, RoundedCornerShape(20.dp)),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.BookmarkAdd,
                    contentDescription = null,
                    tint = StudioLuxuryGold,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "Save Look Recipe",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = StudioActiveOffWhite
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = if (editedCount > 0) {
                        "Saves your $editedCount active adjustments and LUT look as a reusable preset in the LUTs tab."
                    } else {
                        "Saves the current look as a reusable preset in the LUTs tab."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = StudioTextSecondary
                )
                OutlinedTextField(
                    value = recipeName,
                    onValueChange = { recipeName = it },
                    singleLine = true,
                    label = { Text("Recipe Name") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = StudioLuxuryGold,
                        unfocusedBorderColor = StudioGlassHairline,
                        focusedLabelColor = StudioLuxuryGold,
                        unfocusedLabelColor = StudioTextSecondary,
                        focusedTextColor = StudioActiveOffWhite,
                        unfocusedTextColor = StudioActiveOffWhite,
                        cursorColor = StudioLuxuryGold
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("save_recipe_name_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(recipeName.trim().ifBlank { suggestedName }) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = StudioActiveOffWhite,
                    contentColor = StudioObsidian
                ),
                shape = RoundedCornerShape(50),
                modifier = Modifier.testTag("save_recipe_confirm_button")
            ) {
                Text(
                    text = "Save to LUTs",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = StudioTextSecondary)
            }
        }
    )
}
