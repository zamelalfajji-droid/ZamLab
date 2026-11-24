package com.zamalfajji.zamlab.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zamalfajji.zamlab.R
import com.zamalfajji.zamlab.editor.AiStickerStyle
import com.zamalfajji.zamlab.editor.BuiltInSticker
import com.zamalfajji.zamlab.editor.CuratedOledDrawSwatches
import com.zamalfajji.zamlab.editor.DrawBrushType
import com.zamalfajji.zamlab.editor.DrawingEngine
import com.zamalfajji.zamlab.editor.LayerBlendMode
import com.zamalfajji.zamlab.editor.OnlineStickerResult
import com.zamalfajji.zamlab.editor.OnlineStickerSource
import com.zamalfajji.zamlab.editor.SavedCutoutSticker
import com.zamalfajji.zamlab.editor.StickerCategory
import com.zamalfajji.zamlab.editor.StickerLibrary
import com.zamalfajji.zamlab.editor.StickerSearchFilter
import com.zamalfajji.zamlab.editor.StickerSearchRepository
import com.zamalfajji.zamlab.editor.StudioSubMode
import com.zamalfajji.zamlab.editor.StudioTextBackdropStyle
import com.zamalfajji.zamlab.editor.StudioTextFontStyle
import com.zamalfajji.zamlab.ui.theme.StudioCinemaAmber
import com.zamalfajji.zamlab.ui.theme.StudioCyan
import com.zamalfajji.zamlab.ui.theme.StudioHairline
import com.zamalfajji.zamlab.ui.theme.StudioObsidian
import com.zamalfajji.zamlab.ui.theme.StudioSuccessGreen
import com.zamalfajji.zamlab.ui.theme.StudioTextMuted
import com.zamalfajji.zamlab.ui.theme.StudioTextPrimary
import com.zamalfajji.zamlab.ui.theme.StudioTextSecondary
import com.zamalfajji.zamlab.ui.theme.StudioTileBorder
import com.zamalfajji.zamlab.ui.theme.StudioTileSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

private val BuiltInThumbCache = ConcurrentHashMap<String, Bitmap>()
private val OnlineThumbMemCache = ConcurrentHashMap<String, Bitmap>()
private const val CATEGORY_TRENDING_ONLINE = "TRENDING_ONLINE"

private val ONLINE_QUICK_SEARCH_TAGS: List<String> = listOf(
    "Trending",
    "Fire",
    "Wings",
    "Crown",
    "Neon",
    "Glasses",
    "Lightning",
    "Car",
    "Smoke",
    "Camera"
)

private enum class OnlineThumbState {
    LOADING,
    LOADED,
    FAILED
}

private data class OnlineThumbUiState(
    val state: OnlineThumbState,
    val bitmap: Bitmap? = null
)

/**
 * OLED-styled STUDIO (Stickers & Creative Layers) panel replacing the redundant ALL tab.
 *
 * Combines:
 * 1. Live keyless Online Sticker Search (OpenMoji 618x618 transparent PNGs + Wikimedia Commons
 *    transparent PNG cutouts) with local `cacheDir/stickers/` disk caching.
 * 2. 36 built-in offline vector/canvas-rendered transparent stickers across 6 categories.
 * 3. Local "My Cutouts" shelf powered by `+ Sticker from Photo` (SmartCutProcessor).
 */
@Composable
fun StudioStickersPanel(
    studioSubMode: StudioSubMode = StudioSubMode.STICKERS,
    onSelectStudioSubMode: (StudioSubMode) -> Unit = {},
    drawBrushType: DrawBrushType = DrawBrushType.PEN,
    drawColorArgb: Int = CuratedOledDrawSwatches[2],
    drawCustomHue: Float = 38f,
    drawBrushSizeDp: Float = DrawingEngine.DEFAULT_BRUSH_SIZE_DP,
    drawOpacity: Float = 1f,
    canUndoDrawStroke: Boolean = false,
    canRedoDrawStroke: Boolean = false,
    onSelectDrawBrushType: (DrawBrushType) -> Unit = {},
    onSelectDrawColorSwatch: (Int) -> Unit = {},
    onDrawCustomHueChange: (Float) -> Unit = {},
    onDrawBrushSizeChange: (Float) -> Unit = {},
    onDrawOpacityChange: (Float) -> Unit = {},
    onUndoDrawStroke: () -> Unit = {},
    onRedoDrawStroke: () -> Unit = {},
    onFinishDrawing: () -> Unit = {},
    textStampInput: String = "",
    textStampFontStyle: StudioTextFontStyle = StudioTextFontStyle.MODERN_SANS,
    textStampColorArgb: Int = CuratedOledDrawSwatches[0],
    textStampBackdropStyle: StudioTextBackdropStyle = StudioTextBackdropStyle.PILL,
    onTextStampInputChange: (String) -> Unit = {},
    onSelectTextStampFontStyle: (StudioTextFontStyle) -> Unit = {},
    onSelectTextStampColor: (Int) -> Unit = {},
    onSelectTextStampBackdropStyle: (StudioTextBackdropStyle) -> Unit = {},
    onAddTextLayer: () -> Unit = {},
    savedCutouts: List<SavedCutoutSticker>,
    isCreatingCutoutSticker: Boolean,
    cutoutStickerStatusLabel: String?,
    stickerSearchQuery: String,
    stickerSearchSourceFilter: StickerSearchFilter,
    stickerSearchResults: List<OnlineStickerResult>,
    isSearchingOnlineStickers: Boolean,
    stickerSearchError: String?,
    downloadingOnlineStickerId: String?,
    savedOnlineStickerIds: Set<String> = emptySet(),
    aiStickerPrompt: String = "",
    aiStickerStyle: AiStickerStyle = AiStickerStyle.DIE_CUT_VECTOR,
    isGeneratingAiSticker: Boolean = false,
    aiStickerError: String? = null,
    onAiStickerPromptChange: (String) -> Unit = {},
    onSelectAiStickerStyle: (AiStickerStyle) -> Unit = {},
    onGenerateAiSticker: () -> Unit = {},
    onSearchQueryChange: (String) -> Unit,
    onSubmitSearch: () -> Unit,
    onClearSearch: () -> Unit,
    onSelectSearchSource: (StickerSearchFilter) -> Unit,
    onSelectOnlineTag: (String) -> Unit = { tag -> onSearchQueryChange(tag) },
    onAddOnlineSticker: (OnlineStickerResult) -> Unit,
    onSaveOnlineStickerToCutouts: (OnlineStickerResult) -> Unit = {},
    onAddBuiltInSticker: (BuiltInSticker) -> Unit,
    onAddSavedCutout: (SavedCutoutSticker) -> Unit,
    onDeleteSavedCutout: (String) -> Unit,
    onCreateStickerFromPhoto: () -> Unit,
    onImportStickerPack: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var selectedCategoryName by rememberSaveable { mutableStateOf(CATEGORY_TRENDING_ONLINE) }
    var isAiGeneratorExpanded by rememberSaveable { mutableStateOf(false) }
    val isTrendingSelected = selectedCategoryName == CATEGORY_TRENDING_ONLINE
    val selectedCategory = remember(selectedCategoryName) {
        StickerCategory.entries.firstOrNull { it.name == selectedCategoryName } ?: StickerCategory.SHAPES
    }
    val trendingOnlineStickers = remember { StickerSearchRepository.featuredTrendingStickers() }
    val trendingLocalFallbackStickers = remember { StickerSearchRepository.curatedLocalTrendingFallbackStickers() }
    var useOnlineTrending by remember {
        mutableStateOf(
            StickerSearchRepository.hasCachedTrendingCdnPng(context) ||
                StickerSearchRepository.cachedTrendingCdnReachability() == true
        )
    }
    var trendingCdnFailureCount by remember { mutableIntStateOf(0) }
    var previousCutoutCount by remember { mutableIntStateOf(savedCutouts.size) }
    var wasCreatingOrImportingCutout by remember { mutableStateOf(isCreatingCutoutSticker) }

    // Probe external OpenMoji CDN availability in the background whenever Trending is active.
    // While offline or in a CDN-blocked sandbox, Trending seamlessly shows rich locally rendered
    // StickerLibrary stickers with zero blank placeholders or offline error banners.
    LaunchedEffect(isTrendingSelected, stickerSearchQuery) {
        if (isTrendingSelected && stickerSearchQuery.isBlank()) {
            val reachable = StickerSearchRepository.probeTrendingCdnAvailability(context)
            useOnlineTrending = reachable
            if (reachable) {
                trendingCdnFailureCount = 0
            }
        }
    }

    // Auto-focus "My Cutouts" right after "+ Sticker from Photo" or "+ Import ZIP / Pack" finishes,
    // without disrupting active online search when starring/bookmarking an online result.
    LaunchedEffect(savedCutouts.size, isCreatingCutoutSticker) {
        val finishedImportOrCutout = wasCreatingOrImportingCutout && !isCreatingCutoutSticker
        if (savedCutouts.size > previousCutoutCount && finishedImportOrCutout) {
            selectedCategoryName = StickerCategory.MY_CUTOUTS.name
            if (stickerSearchQuery.isNotBlank()) {
                onClearSearch()
            }
        }
        previousCutoutCount = savedCutouts.size
        wasCreatingOrImportingCutout = isCreatingCutoutSticker
    }

    val createBtnCd = stringResource(R.string.cd_sticker_from_photo)
    val importPackBtnCd = stringResource(R.string.cd_import_zip_pack)
    val defaultProcessingLabel = stringResource(R.string.studio_cutout_processing_default)
    val isSearchActive = stickerSearchQuery.isNotBlank()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .testTag("studio_stickers_panel"),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        // 3-Pill Segmented Sub-Mode Selector: Stickers | Draw | Text
        StudioSubModeSelector(
            selectedMode = studioSubMode,
            onSelectMode = { mode ->
                keyboardController?.hide()
                onSelectStudioSubMode(mode)
            }
        )

        when (studioSubMode) {
            StudioSubMode.DRAW -> {
                StudioDrawPanel(
                    brushType = drawBrushType,
                    colorArgb = drawColorArgb,
                    customHue = drawCustomHue,
                    brushSizeDp = drawBrushSizeDp,
                    opacity = drawOpacity,
                    canUndoStroke = canUndoDrawStroke,
                    canRedoStroke = canRedoDrawStroke,
                    onSelectBrushType = onSelectDrawBrushType,
                    onSelectColorSwatch = onSelectDrawColorSwatch,
                    onCustomHueChange = onDrawCustomHueChange,
                    onBrushSizeChange = onDrawBrushSizeChange,
                    onOpacityChange = onDrawOpacityChange,
                    onUndoStroke = onUndoDrawStroke,
                    onRedoStroke = onRedoDrawStroke,
                    onDone = onFinishDrawing,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
                return@Column
            }

            StudioSubMode.TEXT -> {
                StudioTextPanel(
                    textInput = textStampInput,
                    fontStyle = textStampFontStyle,
                    colorArgb = textStampColorArgb,
                    backdropStyle = textStampBackdropStyle,
                    onTextInputChange = onTextStampInputChange,
                    onSelectFontStyle = onSelectTextStampFontStyle,
                    onSelectColor = onSelectTextStampColor,
                    onSelectBackdropStyle = onSelectTextStampBackdropStyle,
                    onAddTextLayer = onAddTextLayer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
                return@Column
            }

            StudioSubMode.STICKERS -> Unit
        }

        // Top Action Bar: "AI Generate" toggle pill + "+ Sticker from Photo" + "+ Import ZIP / Pack"
        val aiToggleCd = stringResource(R.string.cd_ai_generate_toggle)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(11.dp),
                color = if (isAiGeneratorExpanded) StudioCinemaAmber else StudioObsidian,
                modifier = Modifier
                    .height(31.dp)
                    .border(
                        width = 1.dp,
                        color = if (isAiGeneratorExpanded) StudioCinemaAmber else StudioCinemaAmber.copy(alpha = 0.72f),
                        shape = RoundedCornerShape(11.dp)
                    )
                    .clip(RoundedCornerShape(11.dp))
                    .clickable(role = Role.Button) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        isAiGeneratorExpanded = !isAiGeneratorExpanded
                    }
                    .semantics { contentDescription = aiToggleCd }
                    .testTag("ai_generate_toggle_pill")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (isGeneratingAiSticker) {
                        CircularProgressIndicator(
                            color = if (isAiGeneratorExpanded) Color.Black else StudioCinemaAmber,
                            strokeWidth = 1.8.dp,
                            modifier = Modifier.size(12.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.AutoAwesome,
                            contentDescription = null,
                            tint = if (isAiGeneratorExpanded) Color.Black else StudioCinemaAmber,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                    Text(
                        text = stringResource(R.string.action_ai_generate_toggle),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.5.sp
                        ),
                        color = if (isAiGeneratorExpanded) Color.Black else StudioCinemaAmber,
                        maxLines = 1
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(11.dp),
                color = if (isCreatingCutoutSticker) StudioTileSurface else StudioCinemaAmber,
                modifier = Modifier
                    .height(31.dp)
                    .border(
                        width = 1.dp,
                        color = if (isCreatingCutoutSticker) StudioCinemaAmber.copy(alpha = 0.45f) else StudioCinemaAmber,
                        shape = RoundedCornerShape(11.dp)
                    )
                    .clip(RoundedCornerShape(11.dp))
                    .clickable(enabled = !isCreatingCutoutSticker, role = Role.Button) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onCreateStickerFromPhoto()
                    }
                    .semantics { contentDescription = createBtnCd }
                    .testTag("create_sticker_from_photo_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (isCreatingCutoutSticker) {
                        CircularProgressIndicator(
                            color = StudioCinemaAmber,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(12.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.AddPhotoAlternate,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                    Text(
                        text = stringResource(R.string.action_sticker_from_photo),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.5.sp
                        ),
                        color = if (isCreatingCutoutSticker) StudioCinemaAmber else Color.Black,
                        maxLines = 1
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(11.dp),
                color = StudioTileSurface,
                modifier = Modifier
                    .height(31.dp)
                    .border(
                        width = 1.dp,
                        color = if (isCreatingCutoutSticker) StudioTileBorder else StudioCyan.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(11.dp)
                    )
                    .clip(RoundedCornerShape(11.dp))
                    .clickable(enabled = !isCreatingCutoutSticker, role = Role.Button) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onImportStickerPack()
                    }
                    .semantics { contentDescription = importPackBtnCd }
                    .testTag("import_sticker_pack_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FolderZip,
                        contentDescription = null,
                        tint = if (isCreatingCutoutSticker) StudioTextMuted else StudioCyan,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = stringResource(R.string.action_import_zip_pack),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 10.5.sp
                        ),
                        color = if (isCreatingCutoutSticker) StudioTextMuted else StudioTextPrimary,
                        maxLines = 1
                    )
                }
            }
        }

        if (isAiGeneratorExpanded) {
            AiStickerGeneratorPanel(
                prompt = aiStickerPrompt,
                selectedStyle = aiStickerStyle,
                isGenerating = isGeneratingAiSticker,
                errorMessage = aiStickerError,
                onPromptChange = onAiStickerPromptChange,
                onSelectStyle = onSelectAiStickerStyle,
                onGenerate = {
                    keyboardController?.hide()
                    onGenerateAiSticker()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
            return@Column
        }

        // OLED Online Sticker Search Bar (Iconify + Emoji CDNs + Wikimedia Commons)
        OledStickerSearchBar(
            query = stickerSearchQuery,
            isSearching = isSearchingOnlineStickers,
            onQueryChange = onSearchQueryChange,
            onSearchSubmit = {
                keyboardController?.hide()
                onSubmitSearch()
            },
            onClear = {
                keyboardController?.hide()
                onClearSearch()
            }
        )

        // One-Tap Live ONLINE Search Tag Pills
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .testTag("online_quick_search_tags_row"),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ONLINE_QUICK_SEARCH_TAGS.forEach { tag ->
                val isTagSelected = if (tag == "Trending") {
                    !isSearchActive && isTrendingSelected
                } else {
                    stickerSearchQuery.trim().equals(tag, ignoreCase = true)
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isTagSelected) StudioCyan.copy(alpha = 0.22f) else StudioObsidian,
                    modifier = Modifier
                        .height(25.dp)
                        .border(
                            width = 1.dp,
                            color = if (isTagSelected) StudioCyan else StudioTileBorder,
                            shape = RoundedCornerShape(12.dp)
                        )
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(role = Role.Button) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            keyboardController?.hide()
                            if (tag == "Trending") {
                                selectedCategoryName = CATEGORY_TRENDING_ONLINE
                                if (isSearchActive) {
                                    onClearSearch()
                                }
                            } else {
                                onSelectOnlineTag(tag)
                            }
                        }
                        .testTag("online_tag_${tag.lowercase()}")
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 9.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = tag,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = if (isTagSelected) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 10.sp
                            ),
                            color = if (isTagSelected) StudioCyan else StudioTextSecondary,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        // Animated OLED processing banner while SmartCutProcessor extracts a photo sticker
        if (isCreatingCutoutSticker) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(StudioObsidian)
                    .border(1.dp, StudioCinemaAmber.copy(alpha = 0.40f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 7.dp)
                    .testTag("sticker_cutout_progress"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(
                    color = StudioCinemaAmber,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = cutoutStickerStatusLabel ?: defaultProcessingLabel,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                    color = StudioTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (isSearchActive) {
            // Online Search Source Filter Pills (All | Stickers & Emoji | Web Cutouts)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OnlineSourceFilterPill(
                    label = stringResource(R.string.studio_search_source_all),
                    selected = stickerSearchSourceFilter == StickerSearchFilter.ALL,
                    testTag = "online_source_all",
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelectSearchSource(StickerSearchFilter.ALL)
                    }
                )
                OnlineSourceFilterPill(
                    label = stringResource(R.string.studio_search_source_stickers_emoji),
                    selected = stickerSearchSourceFilter == StickerSearchFilter.STICKERS_AND_EMOJI,
                    testTag = "online_source_stickers_emoji",
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelectSearchSource(StickerSearchFilter.STICKERS_AND_EMOJI)
                    }
                )
                OnlineSourceFilterPill(
                    label = stringResource(R.string.studio_search_source_web_cutouts),
                    selected = stickerSearchSourceFilter == StickerSearchFilter.WEB_CUTOUTS,
                    testTag = "online_source_web_cutouts",
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelectSearchSource(StickerSearchFilter.WEB_CUTOUTS)
                    }
                )
                if (!isSearchingOnlineStickers && stickerSearchResults.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(4.dp))
                    val allLocalFallback = stickerSearchResults.all { it.builtInStickerId != null }
                    Text(
                        text = if (allLocalFallback) {
                            "${stickerSearchResults.size} Local"
                        } else {
                            "${stickerSearchResults.size} PNGs"
                        },
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = StudioTextMuted
                    )
                }
            }

            // Online Search Results / Shimmer Skeleton Loading / Offline / Empty States
            when {
                isSearchingOnlineStickers && stickerSearchResults.isEmpty() -> {
                    OnlineSearchShimmerSkeletonGrid(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                }

                stickerSearchResults.isEmpty() -> {
                    OnlineSearchEmptyOrErrorCard(
                        errorMessage = stickerSearchError,
                        onRetry = onSubmitSearch,
                        onBrowseOffline = {
                            selectedCategoryName = StickerCategory.SHAPES.name
                            onClearSearch()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                }

                else -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (!isSearchingOnlineStickers && !stickerSearchError.isNullOrBlank()) {
                            StudioStickerDiagnosticBanner(
                                errorDetails = stickerSearchError,
                                onRetry = onSubmitSearch
                            )
                        }
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 76.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .testTag("online_sticker_results_grid"),
                            contentPadding = PaddingValues(bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(stickerSearchResults, key = { it.id }) { sticker ->
                                OnlineStickerTile(
                                    sticker = sticker,
                                    isDownloading = downloadingOnlineStickerId == sticker.id,
                                    isSavedToCutouts = sticker.id in savedOnlineStickerIds,
                                    onSaveToCutouts = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onSaveOnlineStickerToCutouts(sticker)
                                    },
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        keyboardController?.hide()
                                        onAddOnlineSticker(sticker)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // Category Pill Strip: Trending Online (24 OpenMoji PNGs) + My Cutouts + 6 Offline Built-in Categories
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Instant Trending Online PNGs pill (default active shelf on empty search box)
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (isTrendingSelected) StudioCinemaAmber else StudioTileSurface,
                    modifier = Modifier
                        .height(30.dp)
                        .border(
                            width = 1.dp,
                            color = if (isTrendingSelected) StudioCinemaAmber else StudioTileBorder,
                            shape = RoundedCornerShape(14.dp)
                        )
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(role = Role.Tab) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            selectedCategoryName = CATEGORY_TRENDING_ONLINE
                        }
                        .testTag("sticker_category_trending")
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 11.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.studio_category_trending),
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = if (isTrendingSelected) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 11.sp
                            ),
                            color = if (isTrendingSelected) Color.Black else StudioTextSecondary,
                            maxLines = 1
                        )
                    }
                }

                StickerCategory.entries.forEach { category ->
                    val isSelected = !isTrendingSelected && category == selectedCategory
                    val pillLabel = if (category == StickerCategory.MY_CUTOUTS && savedCutouts.isNotEmpty()) {
                        "${category.label} (${savedCutouts.size})"
                    } else {
                        category.label
                    }
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (isSelected) StudioCinemaAmber else StudioTileSurface,
                        modifier = Modifier
                            .height(30.dp)
                            .border(
                                width = 1.dp,
                                color = if (isSelected) StudioCinemaAmber else StudioTileBorder,
                                shape = RoundedCornerShape(14.dp)
                            )
                            .clip(RoundedCornerShape(14.dp))
                            .clickable(role = Role.Tab) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                selectedCategoryName = category.name
                            }
                            .testTag("sticker_category_${category.name.lowercase()}")
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 11.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = pillLabel,
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 11.sp
                                ),
                                color = if (isSelected) Color.Black else StudioTextSecondary,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            // Content Grid: Trending (Online OpenMoji PNGs with automatic local StickerLibrary fallback),
            // Saved "My Cutouts", or built-in offline vector sticker grid
            when {
                isTrendingSelected -> {
                    if (useOnlineTrending) {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 76.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .testTag("trending_online_stickers_grid"),
                            contentPadding = PaddingValues(bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(trendingOnlineStickers, key = { it.id }) { sticker ->
                                OnlineStickerTile(
                                    sticker = sticker,
                                    isDownloading = downloadingOnlineStickerId == sticker.id,
                                    isSavedToCutouts = sticker.id in savedOnlineStickerIds,
                                    onThumbLoadFailed = {
                                        trendingCdnFailureCount += 1
                                        if (trendingCdnFailureCount >= 2) {
                                            StickerSearchRepository.markTrendingCdnUnreachable()
                                            useOnlineTrending = false
                                        }
                                    },
                                    onSaveToCutouts = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onSaveOnlineStickerToCutouts(sticker)
                                    },
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        keyboardController?.hide()
                                        onAddOnlineSticker(sticker)
                                    }
                                )
                            }
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 76.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .testTag("trending_local_fallback_grid"),
                            contentPadding = PaddingValues(bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(trendingLocalFallbackStickers, key = { "trending_local_${it.id}" }) { sticker ->
                                BuiltInStickerTile(
                                    sticker = sticker,
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        keyboardController?.hide()
                                        onAddBuiltInSticker(sticker)
                                    }
                                )
                            }
                        }
                    }
                }

                selectedCategory == StickerCategory.MY_CUTOUTS -> {
                    if (savedCutouts.isEmpty()) {
                        MyCutoutsEmptyCard(
                            enabled = !isCreatingCutoutSticker,
                            onCreateStickerFromPhoto = onCreateStickerFromPhoto,
                            onImportStickerPack = onImportStickerPack,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        )
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 76.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .testTag("my_cutouts_grid"),
                            contentPadding = PaddingValues(bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(savedCutouts, key = { it.id }) { cutout ->
                                SavedCutoutTile(
                                    cutout = cutout,
                                    onSelect = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onAddSavedCutout(cutout)
                                    },
                                    onDelete = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onDeleteSavedCutout(cutout.id)
                                    }
                                )
                            }
                        }
                    }
                }

                else -> {
                    val categoryStickers = remember(selectedCategory) {
                        StickerLibrary.stickersForCategory(selectedCategory)
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 76.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .testTag("sticker_grid_${selectedCategory.name.lowercase()}"),
                        contentPadding = PaddingValues(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(categoryStickers, key = { it.id }) { sticker ->
                            BuiltInStickerTile(
                                sticker = sticker,
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onAddBuiltInSticker(sticker)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AiStickerGeneratorPanel(
    prompt: String,
    selectedStyle: AiStickerStyle,
    isGenerating: Boolean,
    errorMessage: String?,
    onPromptChange: (String) -> Unit,
    onSelectStyle: (AiStickerStyle) -> Unit,
    onGenerate: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val panelShape = RoundedCornerShape(14.dp)
    val inputShape = RoundedCornerShape(11.dp)
    val placeholder = stringResource(R.string.ai_sticker_prompt_placeholder)
    val generateCd = stringResource(R.string.cd_generate_ai_sticker)
    val canGenerate = prompt.trim().isNotEmpty() && !isGenerating

    Column(
        modifier = modifier
            .clip(panelShape)
            .background(StudioObsidian)
            .border(1.dp, StudioTileBorder, panelShape)
            .padding(10.dp)
            .testTag("ai_sticker_generator_panel"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Prompt Input Field
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .clip(inputShape)
                .background(StudioTileSurface)
                .border(
                    width = 1.dp,
                    color = if (prompt.isNotBlank()) StudioCinemaAmber.copy(alpha = 0.72f) else StudioTileBorder,
                    shape = inputShape
                )
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.AutoAwesome,
                contentDescription = null,
                tint = StudioCinemaAmber,
                modifier = Modifier.size(15.dp)
            )
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart
            ) {
                if (prompt.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                        color = StudioTextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                BasicTextField(
                    value = prompt,
                    onValueChange = onPromptChange,
                    enabled = !isGenerating,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        color = StudioTextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    cursorBrush = SolidColor(StudioCinemaAmber),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (canGenerate) onGenerate() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ai_sticker_prompt_input")
                )
            }
            if (prompt.isNotEmpty() && !isGenerating) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(StudioObsidian)
                        .clickable(role = Role.Button) { onPromptChange("") }
                        .testTag("clear_ai_sticker_prompt_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = null,
                        tint = StudioTextSecondary,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }

        // Style Chip Selector ("Die-Cut Vector", "3D Glossy", "Neon Glow", "Retro Badge", "Anime")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .testTag("ai_sticker_style_chips_row"),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AiStickerStyle.entries.forEach { style ->
                val isSelected = style == selectedStyle
                Surface(
                    shape = RoundedCornerShape(13.dp),
                    color = if (isSelected) StudioCyan.copy(alpha = 0.22f) else StudioTileSurface,
                    modifier = Modifier
                        .height(27.dp)
                        .border(
                            width = 1.dp,
                            color = if (isSelected) StudioCyan else StudioTileBorder,
                            shape = RoundedCornerShape(13.dp)
                        )
                        .clip(RoundedCornerShape(13.dp))
                        .clickable(enabled = !isGenerating, role = Role.Tab) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelectStyle(style)
                        }
                        .testTag("ai_style_${style.name.lowercase()}")
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = style.label,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 10.5.sp
                            ),
                            color = if (isSelected) StudioCyan else StudioTextSecondary,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        // Generate Sticker Primary Action Button + OLED Loading Indicator
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (canGenerate) StudioCinemaAmber else StudioTileSurface,
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .border(
                    width = 1.dp,
                    color = if (isGenerating) StudioCinemaAmber.copy(alpha = 0.7f)
                    else if (canGenerate) StudioCinemaAmber
                    else StudioTileBorder,
                    shape = RoundedCornerShape(12.dp)
                )
                .clip(RoundedCornerShape(12.dp))
                .clickable(enabled = canGenerate, role = Role.Button) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onGenerate()
                }
                .semantics { contentDescription = generateCd }
                .testTag("generate_ai_sticker_button")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
            ) {
                if (isGenerating) {
                    CircularProgressIndicator(
                        color = StudioCinemaAmber,
                        strokeWidth = 2.dp,
                        modifier = Modifier
                            .size(15.dp)
                            .testTag("ai_sticker_loading_indicator")
                    )
                    Text(
                        text = stringResource(R.string.ai_sticker_generating_label),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 11.5.sp
                        ),
                        color = StudioCinemaAmber,
                        maxLines = 1
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = if (canGenerate) Color.Black else StudioTextMuted,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = stringResource(R.string.action_generate_sticker),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        ),
                        color = if (canGenerate) Color.Black else StudioTextMuted,
                        maxLines = 1
                    )
                }
            }
        }

        if (!errorMessage.isNullOrBlank()) {
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = StudioCinemaAmber,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("ai_sticker_error_banner")
            )
        }
    }
}

@Composable
private fun OledStickerSearchBar(
    query: String,
    isSearching: Boolean,
    onQueryChange: (String) -> Unit,
    onSearchSubmit: () -> Unit,
    onClear: () -> Unit
) {
    val barShape = RoundedCornerShape(12.dp)
    val clearCd = stringResource(R.string.cd_clear_sticker_search)
    val placeholderText = stringResource(R.string.studio_search_placeholder)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .clip(barShape)
            .background(StudioObsidian)
            .border(
                width = 1.dp,
                color = if (query.isNotBlank()) StudioCinemaAmber.copy(alpha = 0.65f) else StudioTileBorder,
                shape = barShape
            )
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
            tint = if (query.isNotBlank()) StudioCinemaAmber else StudioTextMuted,
            modifier = Modifier.size(16.dp)
        )

        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterStart
        ) {
            if (query.isEmpty()) {
                Text(
                    text = placeholderText,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = StudioTextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    color = StudioTextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                ),
                cursorBrush = SolidColor(StudioCinemaAmber),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearchSubmit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("online_sticker_search_input")
            )
        }

        if (isSearching) {
            CircularProgressIndicator(
                color = StudioCinemaAmber,
                strokeWidth = 1.8.dp,
                modifier = Modifier.size(14.dp)
            )
        }

        if (query.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(StudioTileSurface)
                    .clickable(role = Role.Button, onClick = onClear)
                    .semantics { contentDescription = clearCd }
                    .testTag("clear_sticker_search_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = null,
                    tint = StudioTextSecondary,
                    modifier = Modifier.size(13.dp)
                )
            }
        }
    }
}

@Composable
private fun OnlineSourceFilterPill(
    label: String,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (selected) StudioCinemaAmber else StudioTileSurface,
        modifier = Modifier
            .height(28.dp)
            .border(
                width = 1.dp,
                color = if (selected) StudioCinemaAmber else StudioTileBorder,
                shape = RoundedCornerShape(14.dp)
            )
            .clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Tab, onClick = onClick)
            .testTag(testTag)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 11.sp
                ),
                color = if (selected) Color.Black else StudioTextSecondary,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun OnlineSearchShimmerSkeletonGrid(
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.testTag("online_sticker_search_loading"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = stringResource(R.string.studio_search_loading),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = StudioTextMuted
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 76.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(8) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(StudioTileSurface)
                        .border(1.dp, StudioTileBorder, RoundedCornerShape(12.dp))
                        .padding(6.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        OledShimmerBox(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(8.dp))
                        )
                        OledShimmerBox(
                            modifier = Modifier
                                .fillMaxWidth(0.7f)
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OledShimmerBox(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "sticker_shimmer")
    val progress by transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1150, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sticker_shimmer_progress"
    )
    val shimmerBrush = Brush.linearGradient(
        colors = listOf(
            Color(0xFF17171D),
            Color(0xFF2B2D38),
            Color(0xFF17171D)
        ),
        start = Offset(progress * 180f, 0f),
        end = Offset((progress + 1f) * 180f, 180f)
    )
    Box(modifier = modifier.background(shimmerBrush))
}

@Composable
private fun OnlineStickerTile(
    sticker: OnlineStickerResult,
    isDownloading: Boolean,
    isSavedToCutouts: Boolean = false,
    onThumbLoadFailed: () -> Unit = {},
    onSaveToCutouts: () -> Unit = {},
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val initialCached = OnlineThumbMemCache[sticker.id]?.takeIf { !it.isRecycled }
    val thumbState by produceState(
        initialValue = if (initialCached != null) {
            OnlineThumbUiState(OnlineThumbState.LOADED, initialCached)
        } else {
            OnlineThumbUiState(OnlineThumbState.LOADING, null)
        },
        key1 = sticker.id,
        key2 = sticker.downloadUrl
    ) {
        val cached = OnlineThumbMemCache[sticker.id]?.takeIf { !it.isRecycled }
        if (cached != null) {
            value = OnlineThumbUiState(OnlineThumbState.LOADED, cached)
        } else {
            val loaded = StickerSearchRepository.loadStickerThumbnail(context, sticker)
            if (loaded != null && !loaded.isRecycled) {
                OnlineThumbMemCache[sticker.id] = loaded
                value = OnlineThumbUiState(OnlineThumbState.LOADED, loaded)
            } else {
                value = OnlineThumbUiState(OnlineThumbState.FAILED, null)
                onThumbLoadFailed()
            }
        }
    }

    val tileCd = stringResource(R.string.cd_add_sticker_layer, sticker.title)
    val tileShape = RoundedCornerShape(12.dp)
    val isLocalFallback = sticker.builtInStickerId != null
    val badgeLabel = if (isLocalFallback) "Local" else sticker.source.badge
    val badgeColor = when {
        isLocalFallback -> StudioCinemaAmber
        sticker.source == OnlineStickerSource.OPENMOJI -> StudioCinemaAmber
        sticker.source == OnlineStickerSource.NOTO_EMOJI -> StudioSuccessGreen
        sticker.source == OnlineStickerSource.FLUENT_TWEMOJI -> StudioCyan
        else -> StudioTextSecondary
    }

    Box(
        modifier = Modifier
            .clip(tileShape)
            .background(StudioTileSurface)
            .border(
                width = 1.dp,
                color = if (isDownloading) StudioCinemaAmber else StudioTileBorder,
                shape = tileShape
            )
            .clickable(enabled = !isDownloading, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = tileCd }
            .padding(6.dp)
            .testTag("online_sticker_item_${sticker.id}")
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                OledCheckerboardBackdrop(modifier = Modifier.fillMaxSize())
                when (thumbState.state) {
                    OnlineThumbState.LOADED -> {
                        val bmp = thumbState.bitmap
                        if (bmp != null && !bmp.isRecycled) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(5.dp)
                            )
                        }
                    }

                    OnlineThumbState.LOADING -> {
                        OledShimmerBox(modifier = Modifier.fillMaxSize())
                    }

                    OnlineThumbState.FAILED -> {
                        Icon(
                            imageVector = Icons.Rounded.CloudOff,
                            contentDescription = null,
                            tint = StudioTextMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                if (isDownloading) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = StudioCinemaAmber,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
            Text(
                text = sticker.title,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                ),
                color = StudioTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }

        // Compact source pill badge ("Emoji", "3D", or "Wiki")
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(2.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = 0.72f))
                .border(0.5.dp, StudioHairline, RoundedCornerShape(6.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp)
        ) {
            Text(
                text = badgeLabel,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 8.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                color = badgeColor
            )
        }

        // Star / Bookmark button to permanently save this online sticker PNG into "My Cutouts"
        val saveCd = stringResource(R.string.cd_save_online_sticker, sticker.title)
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(22.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.74f))
                .border(
                    width = 0.75.dp,
                    color = if (isSavedToCutouts) StudioCinemaAmber else StudioHairline,
                    shape = CircleShape
                )
                .clickable(enabled = !isDownloading, role = Role.Button, onClick = onSaveToCutouts)
                .semantics { contentDescription = saveCd }
                .testTag("save_online_sticker_${sticker.id}"),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isSavedToCutouts) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                contentDescription = null,
                tint = if (isSavedToCutouts) StudioCinemaAmber else StudioTextSecondary,
                modifier = Modifier.size(13.dp)
            )
        }
    }
}

@Composable
private fun StudioStickerDiagnosticBanner(
    errorDetails: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bannerShape = RoundedCornerShape(10.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(bannerShape)
            .background(StudioObsidian)
            .border(1.dp, StudioCinemaAmber.copy(alpha = 0.45f), bannerShape)
            .padding(horizontal = 9.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) {}
            .testTag("studio_sticker_status_banner"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Icon(
            imageVector = Icons.Rounded.CloudOff,
            contentDescription = null,
            tint = StudioCinemaAmber,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = errorDetails,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium
            ),
            color = StudioTextPrimary,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(StudioTileSurface)
                .border(1.dp, StudioTileBorder, CircleShape)
                .clickable(role = Role.Button, onClick = onRetry)
                .testTag("studio_sticker_status_retry"),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Refresh,
                contentDescription = stringResource(R.string.studio_search_retry),
                tint = StudioCinemaAmber,
                modifier = Modifier.size(13.dp)
            )
        }
    }
}

@Composable
private fun OnlineSearchEmptyOrErrorCard(
    errorMessage: String?,
    onRetry: () -> Unit,
    onBrowseOffline: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cardShape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .clip(cardShape)
            .background(StudioObsidian)
            .border(1.dp, StudioTileBorder, cardShape)
            .padding(12.dp)
            .testTag("online_sticker_search_empty"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = if (errorMessage != null) Icons.Rounded.CloudOff else Icons.Rounded.Search,
                contentDescription = null,
                tint = StudioCinemaAmber,
                modifier = Modifier.size(22.dp)
            )
            Text(
                text = errorMessage ?: stringResource(R.string.studio_search_empty_title),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = StudioTextPrimary,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = if (errorMessage != null) {
                    Modifier.testTag("studio_sticker_status_banner")
                } else {
                    Modifier
                }
            )
            if (errorMessage == null) {
                Text(
                    text = stringResource(R.string.studio_search_empty_body),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = StudioTextMuted,
                    textAlign = TextAlign.Center
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (errorMessage != null) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = StudioCinemaAmber,
                        modifier = Modifier
                            .height(30.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(role = Role.Button, onClick = onRetry)
                            .testTag("online_sticker_retry_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = null,
                                tint = Color.Black,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = stringResource(R.string.studio_search_retry),
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = Color.Black
                            )
                        }
                    }
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = StudioTileSurface,
                    modifier = Modifier
                        .height(30.dp)
                        .border(1.dp, StudioTileBorder, RoundedCornerShape(10.dp))
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(role = Role.Button, onClick = onBrowseOffline)
                        .testTag("online_sticker_browse_offline_button")
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.studio_search_use_offline),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                            color = StudioTextSecondary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BuiltInStickerTile(
    sticker: BuiltInSticker,
    onClick: () -> Unit
) {
    val thumb by produceState<Bitmap?>(
        initialValue = BuiltInThumbCache[sticker.id]?.takeIf { !it.isRecycled },
        key1 = sticker.id
    ) {
        val cached = BuiltInThumbCache[sticker.id]?.takeIf { !it.isRecycled }
        if (cached != null) {
            value = cached
        } else {
            val rendered = withContext(Dispatchers.Default) {
                StickerLibrary.renderBuiltInSticker(sticker, sizePx = StickerLibrary.THUMBNAIL_SIZE_PX)
            }
            BuiltInThumbCache[sticker.id] = rendered
            value = rendered
        }
    }

    val tileCd = stringResource(R.string.cd_add_sticker_layer, sticker.name)
    val tileShape = RoundedCornerShape(12.dp)

    Column(
        modifier = Modifier
            .clip(tileShape)
            .background(StudioTileSurface)
            .border(1.dp, StudioTileBorder, tileShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = tileCd }
            .padding(6.dp)
            .testTag("sticker_item_${sticker.id}"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (sticker.defaultBlendMode == LayerBlendMode.SCREEN) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                )
            } else {
                OledCheckerboardBackdrop(modifier = Modifier.fillMaxSize())
            }
            val bmp = thumb
            if (bmp != null && !bmp.isRecycled) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(5.dp)
                )
            }
        }
        Text(
            text = sticker.name,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium
            ),
            color = StudioTextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun SavedCutoutTile(
    cutout: SavedCutoutSticker,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    val thumb by produceState<Bitmap?>(initialValue = null, key1 = cutout.file.absolutePath, key2 = cutout.createdAtMs) {
        value = StickerLibrary.decodeCutoutBitmap(cutout.file, maxDimension = StickerLibrary.THUMBNAIL_SIZE_PX)
    }
    val addCd = stringResource(R.string.cd_add_sticker_layer, cutout.name)
    val deleteCd = stringResource(R.string.cd_delete_saved_cutout, cutout.name)
    val tileShape = RoundedCornerShape(12.dp)

    Box(
        modifier = Modifier
            .clip(tileShape)
            .background(StudioTileSurface)
            .border(1.dp, StudioTileBorder, tileShape)
            .clickable(role = Role.Button, onClick = onSelect)
            .semantics { contentDescription = addCd }
            .padding(6.dp)
            .testTag("saved_cutout_${cutout.id}")
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                OledCheckerboardBackdrop(modifier = Modifier.fillMaxSize())
                val bmp = thumb
                if (bmp != null && !bmp.isRecycled) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(4.dp)
                    )
                }
            }
            Text(
                text = cutout.name,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                ),
                color = StudioTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(22.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.78f))
                .border(1.dp, StudioHairline, CircleShape)
                .clickable(role = Role.Button, onClick = onDelete)
                .semantics { contentDescription = deleteCd }
                .testTag("delete_cutout_${cutout.id}"),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = null,
                tint = StudioTextPrimary,
                modifier = Modifier.size(12.dp)
            )
        }
    }
}

@Composable
private fun MyCutoutsEmptyCard(
    enabled: Boolean,
    onCreateStickerFromPhoto: () -> Unit,
    onImportStickerPack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cardShape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .clip(cardShape)
            .background(StudioObsidian)
            .border(1.dp, StudioTileBorder, cardShape)
            .padding(12.dp)
            .testTag("my_cutouts_empty_state"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.AddPhotoAlternate,
                contentDescription = null,
                tint = StudioCinemaAmber,
                modifier = Modifier.size(22.dp)
            )
            Text(
                text = stringResource(R.string.studio_cutouts_empty_title),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = StudioTextPrimary,
                textAlign = TextAlign.Center
            )
            Text(
                text = stringResource(R.string.studio_cutouts_empty_body),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.5.sp),
                color = StudioTextMuted,
                textAlign = TextAlign.Center
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = StudioCinemaAmber,
                    modifier = Modifier
                        .height(28.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = enabled, role = Role.Button, onClick = onCreateStickerFromPhoto)
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.action_sticker_from_photo),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = Color.Black
                        )
                    }
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = StudioTileSurface,
                    modifier = Modifier
                        .height(28.dp)
                        .border(1.dp, StudioCyan.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = enabled, role = Role.Button, onClick = onImportStickerPack)
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.action_import_zip_pack),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = StudioCyan
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OledCheckerboardBackdrop(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val cell = 8.dp.toPx().coerceAtLeast(4f)
        val darkA = Color(0xFF141417)
        val darkB = Color(0xFF1F1F24)
        val cols = (size.width / cell).toInt() + 1
        val rows = (size.height / cell).toInt() + 1
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                drawRect(
                    color = if ((r + c) % 2 == 0) darkA else darkB,
                    topLeft = Offset(c * cell, r * cell),
                    size = Size(cell, cell)
                )
            }
        }
    }
}
