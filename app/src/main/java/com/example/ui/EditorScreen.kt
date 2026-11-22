@file:Suppress("UnusedBoxWithConstraintsScope")

package com.example.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CropRotate
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FilterVintage
import androidx.compose.material.icons.filled.Gradient
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CropRotate
import androidx.compose.material.icons.outlined.FilterVintage
import androidx.compose.material.icons.outlined.Gradient
import androidx.compose.material.icons.outlined.Healing
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.editor.EditorUiState
import com.example.editor.EditorViewModel
import com.example.editor.ExportFormat
import com.example.editor.ExportOptions
import com.example.editor.ExportStatus
import com.example.editor.HslBandAdjustment
import com.example.editor.ImageIoHelper
import com.example.editor.PhotoFormatInfo
import com.example.editor.StudioTab
import com.example.ui.components.AdjustPanel
import com.example.ui.components.AllParametersPanel
import com.example.ui.components.CropPanel
import com.example.ui.components.ExportBottomSheet
import com.example.ui.components.HealPanel
import com.example.ui.components.LutPanel
import com.example.ui.components.MaskPanel
import com.example.ui.components.PhotoViewport
import com.example.ui.components.ProfileBrowserSheet
import com.example.ui.components.ProfileMenuSheet
import com.example.ui.components.StudioIcons
import com.example.ui.theme.StudioActiveOffWhite
import com.example.ui.theme.StudioAmberPillFill
import com.example.ui.theme.StudioBackground
import com.example.ui.theme.StudioCharcoal
import com.example.ui.theme.StudioCinemaAmber
import com.example.ui.theme.StudioCoolWhite
import com.example.ui.theme.StudioDockActivePill
import com.example.ui.theme.StudioGlassHairline
import com.example.ui.theme.StudioGlassHighlight
import com.example.ui.theme.StudioGlassSurface
import com.example.ui.theme.StudioGraphite
import com.example.ui.theme.StudioHairline
import com.example.ui.theme.StudioLuxuryGold
import com.example.ui.theme.StudioMutedSlate
import com.example.ui.theme.StudioObsidian
import com.example.ui.theme.StudioSuccessGreen
import com.example.ui.theme.StudioSurfaceElevated
import com.example.ui.theme.StudioTextMuted
import com.example.ui.theme.StudioTextPrimary
import com.example.ui.theme.StudioTextSecondary
import com.example.ui.theme.StudioTileBorder
import com.example.ui.theme.StudioTileSurface
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds


@Composable


fun EditorScreen(


    viewModel: EditorViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var showExportSheet by remember { mutableStateOf(false) }
    var showProfileMenu by remember { mutableStateOf(false) }
    var showProfileBrowser by remember { mutableStateOf(false) }
    var isViewportZoomed by remember { mutableStateOf(false) }
    var pendingSafOptions by remember { mutableStateOf(ExportOptions()) }

    // Primary photo import: opens Android system photo gallery directly (zero storage permissions)
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri ->
            uri?.let { viewModel.openPhotoFromUri(it) }
        }
    )

    // Secondary RAW / File picker via SAF for non-indexed camera RAW formats (.cr2, .nef, .arw, .orf, .rw2, .raf, .dng)
    val rawFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            uri?.let { viewModel.openPhotoFromUri(it) }
        }
    )

    // LUT & .xmp preset picker via SAF using */* so Android does not hide .cube or .xmp files
    val lutPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            uri?.let { viewModel.importCubeLutFromUri(it) }
        }
    )

    val createPngLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("image/png"),
        onResult = { uri ->
            uri?.let { viewModel.exportToDocumentUri(it, pendingSafOptions) }
        }
    )

    val createJpgLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("image/jpeg"),
        onResult = { uri ->
            uri?.let { viewModel.exportToDocumentUri(it, pendingSafOptions) }
        }
    )

    val createWebpLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("image/webp"),
        onResult = { uri ->
            uri?.let { viewModel.exportToDocumentUri(it, pendingSafOptions) }
        }
    )

    val createPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf"),
        onResult = { uri ->
            uri?.let { viewModel.exportToDocumentUri(it, pendingSafOptions) }
        }
    )

    val createTiffLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("image/tiff"),
        onResult = { uri ->
            uri?.let { viewModel.exportToDocumentUri(it, pendingSafOptions) }
        }
    )


    val shareActionLabel = stringResource(R.string.action_share_upper)
    val savedGalleryMsg = stringResource(R.string.snackbar_saved_to_gallery)
    val savedDownloadsMsg = stringResource(R.string.snackbar_saved_to_downloads)
    val shareChooserTitle = stringResource(R.string.chooser_share_photo)

    LaunchedEffect(uiState.statusBannerMessage) {
        val msg = uiState.statusBannerMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(
                message = msg,
                duration = SnackbarDuration.Short
            )
            viewModel.dismissStatusBanner()
        }
    }

    // Lightroom-style instant export feedback: close sheet immediately and show non-blocking Snackbar with SHARE
    LaunchedEffect(uiState.exportConfirmationToast) {
        val exported = uiState.exportConfirmationToast
        if (exported != null) {
            if (showExportSheet) {
                showExportSheet = false
            }
            viewModel.dismissExportStatus()

            if (exported.openShareImmediately) {
                launchShareExportedFile(context, exported, shareChooserTitle)
            }

            val message = if (exported.format.isDownloadsDestination) {
                savedDownloadsMsg
            } else {
                savedGalleryMsg
            }
            snackbarHostState.currentSnackbarData?.dismiss()
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = shareActionLabel,
                withDismissAction = false,
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                launchShareExportedFile(context, exported, shareChooserTitle)
            }
            viewModel.dismissExportConfirmationToast()
        }
    }

    val openPhotoAction = {
        photoPickerLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    val openRawFileAction = {
        rawFilePickerLauncher.launch(arrayOf("image/*", "application/octet-stream"))
    }

    val importLutAction = {
        lutPickerLauncher.launch(arrayOf("*/*"))
    }

    val canReset = !uiState.gradingParams.isDefault ||
        uiState.selectedLutId != null ||
        uiState.selectedUserPresetId != null ||
        isViewportZoomed

    val isTouchAdjustPillVisible = uiState.isTouchAdjustEnabled &&
        uiState.touchAdjustSelectedBand != null &&
        uiState.activeTab != StudioTab.CROP &&
        uiState.activeTab != StudioTab.HEAL &&
        !uiState.isComparingOriginal

    val canShowTouchAdjust = uiState.activeTab != StudioTab.CROP &&
        uiState.activeTab != StudioTab.HEAL &&
        uiState.activeEyedropperMode == com.example.editor.EyedropperMode.NONE

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(StudioBackground)
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isWideLayout = maxWidth >= 720.dp
            val activeTexture = uiState.activePreviewTexture

            if (isWideLayout) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ImmersiveCanvasStage(
                        uiState = uiState,
                        activeTexture = activeTexture,
                        canReset = canReset,
                        canShowTouchAdjust = canShowTouchAdjust,
                        isTouchAdjustPillVisible = isTouchAdjustPillVisible,
                        viewModel = viewModel,
                        onZoomChanged = { isViewportZoomed = it },
                        onOpenPhoto = openPhotoAction,
                        onOpenRawFile = openRawFileAction,
                        onQuickShare = viewModel::exportAndShareNow,
                        onOpenExport = { showExportSheet = true },
                        modifier = Modifier
                            .weight(1.48f)
                            .fillMaxHeight()
                    )

                    Column(
                        modifier = Modifier
                            .width(360.dp)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(16.dp))
                            .background(StudioGlassSurface)
                            .border(1.dp, StudioGlassHairline, RoundedCornerShape(16.dp))
                            .padding(top = 8.dp, bottom = 10.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        ) {
                            ActiveToolPanel(
                                uiState = uiState,
                                viewModel = viewModel,
                                onImportLut = importLutAction,
                                onOpenProfileMenu = { showProfileMenu = true }
                            )
                        }

                        FloatingCapsuleNavDock(
                            uiState = uiState,
                            onSelectTab = viewModel::selectTab,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }

                StudioDarkroomSnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 84.dp)
                )
            } else {
                var allPanelFraction by remember { mutableFloatStateOf(0.48f) }
                var isDraggingAllHandle by remember { mutableStateOf(false) }
                val animatedAllFraction by animateFloatAsState(
                    targetValue = allPanelFraction,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium
                    ),
                    label = "all_panel_split_fraction"
                )
                val activeAllFraction = if (isDraggingAllHandle) allPanelFraction else animatedAllFraction
                val haptic = LocalHapticFeedback.current
                val density = LocalDensity.current

                Scaffold(
                    containerColor = StudioCharcoal,
                    contentWindowInsets = WindowInsets.statusBars,
                    snackbarHost = {
                        // Positioned directly above the 68dp floating capsule dock
                        StudioDarkroomSnackbarHost(hostState = snackbarHostState)
                    },
                    bottomBar = {
                        // Pinned 68dp Floating Frosted-Glass Capsule Navigation Dock
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(StudioCharcoal)
                                .navigationBarsPadding()
                                .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            FloatingCapsuleNavDock(
                                uiState = uiState,
                                onSelectTab = viewModel::selectTab,
                                modifier = Modifier
                                    .widthIn(max = 500.dp)
                                    .fillMaxWidth()
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->
                    // Dynamic Split Workspace above the pinned 68dp bottom dock
                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        val workspaceHeight = maxHeight
                        val workspaceHeightPx = with(density) { workspaceHeight.toPx() }.coerceAtLeast(1f)
                        val isAllTab = uiState.activeTab == StudioTab.ALL

                        Column(modifier = Modifier.fillMaxSize()) {
                            // 1. Dynamic Photo Viewport Canvas (weight(1f) shrinks smoothly when tool panel opens)
                            ImmersiveCanvasStage(
                                uiState = uiState,
                                activeTexture = activeTexture,
                                canReset = canReset,
                                canShowTouchAdjust = canShowTouchAdjust,
                                isTouchAdjustPillVisible = isTouchAdjustPillVisible,
                                viewModel = viewModel,
                                onZoomChanged = { isViewportZoomed = it },
                                onOpenPhoto = openPhotoAction,
                                onOpenRawFile = openRawFileAction,
                                onQuickShare = viewModel::exportAndShareNow,
                                onOpenExport = { showExportSheet = true },
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)
                            )

                            // 2. Active Tool Panel (bounded so it never overlaps or hides the pinned bottom dock)
                            val maxAllPanelHeight = (workspaceHeight * 0.50f).coerceAtLeast(156.dp)
                            val cappedToolMax = minOf(240.dp, (workspaceHeight * 0.48f).coerceAtLeast(140.dp))
                            val cappedToolMin = minOf(140.dp, cappedToolMax)

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .animateContentSize(
                                        animationSpec = if (isDraggingAllHandle) {
                                            snap()
                                        } else {
                                            spring(
                                                dampingRatio = Spring.DampingRatioNoBouncy,
                                                stiffness = Spring.StiffnessMediumLow
                                            )
                                        }
                                    )
                            ) {
                                if (isAllTab) {
                                    val minAllHeight = minOf(180.dp, maxAllPanelHeight)
                                    val panelHeight = (workspaceHeight * activeAllFraction)
                                        .coerceIn(minAllHeight, maxAllPanelHeight)
                                    val sheetShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
                                    val dragHandleCd = stringResource(R.string.cd_drag_resize_panel)

                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(panelHeight)
                                            .padding(horizontal = 8.dp)
                                            .clip(sheetShape)
                                            .background(StudioGlassSurface)
                                            .border(1.dp, StudioGlassHairline, sheetShape)
                                            .testTag("all_adaptive_sheet")
                                    ) {
                                        // Frosted-glass drag handle bar (48dp touch target: drag to resize, tap to cycle height)
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(48.dp)
                                                .semantics { contentDescription = dragHandleCd }
                                                .clickable {
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    allPanelFraction = when {
                                                        allPanelFraction < 0.38f -> 0.42f
                                                        allPanelFraction < 0.46f -> 0.48f
                                                        else -> 0.32f
                                                    }
                                                }
                                                .pointerInput(workspaceHeightPx) {
                                                    detectVerticalDragGestures(
                                                        onDragStart = { isDraggingAllHandle = true },
                                                        onVerticalDrag = { change, dragAmount ->
                                                            change.consume()
                                                            val deltaFraction = -dragAmount / workspaceHeightPx
                                                            allPanelFraction = (allPanelFraction + deltaFraction)
                                                                .coerceIn(0.28f, 0.50f)
                                                        },
                                                        onDragEnd = {
                                                            isDraggingAllHandle = false
                                                            if (kotlin.math.abs(allPanelFraction - 0.48f) < 0.03f) {
                                                                allPanelFraction = 0.48f
                                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                            }
                                                        },
                                                        onDragCancel = { isDraggingAllHandle = false }
                                                    )
                                                }
                                                .testTag("all_panel_drag_handle"),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .width(if (isDraggingAllHandle) 48.dp else 38.dp)
                                                    .height(4.dp)
                                                    .clip(CircleShape)
                                                    .background(
                                                        if (isDraggingAllHandle) {
                                                            StudioLuxuryGold
                                                        } else {
                                                            Color.White.copy(alpha = 0.24f)
                                                        }
                                                    )
                                            )
                                        }

                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxWidth()
                                        ) {
                                            ActiveToolPanel(
                                                uiState = uiState,
                                                viewModel = viewModel,
                                                onImportLut = importLutAction,
                                                onOpenProfileMenu = { showProfileMenu = true }
                                            )
                                        }
                                    }
                                } else {
                                    // Constrain active tool panel (including LUTs, Adjust, Crop, Heal, Masks) to 140..240dp
                                    val toolPanelModifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = cappedToolMin, max = cappedToolMax)

                                    Box(modifier = toolPanelModifier) {
                                        ActiveToolPanel(
                                            uiState = uiState,
                                            viewModel = viewModel,
                                            onImportLut = importLutAction,
                                            onOpenProfileMenu = { showProfileMenu = true }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showExportSheet) {
        val activeLutTitle = uiState.selectedPresetTitle
        ExportBottomSheet(
            photoName = uiState.photoName,
            width = uiState.effectiveOutputWidth,
            height = uiState.effectiveOutputHeight,
            activeLutTitle = activeLutTitle,
            exportStatus = uiState.exportStatus,
            onExportToGallery = { options ->
                viewModel.exportToGallery(options)
            },
            onExportWithFilePicker = { options ->
                pendingSafOptions = options
                val suggestedName = ImageIoHelper.generateExportFilename(options.format)
                when (options.format) {
                    ExportFormat.PNG -> createPngLauncher.launch(suggestedName)
                    ExportFormat.JPG -> createJpgLauncher.launch(suggestedName)
                    ExportFormat.WEBP -> createWebpLauncher.launch(suggestedName)
                    ExportFormat.PDF -> createPdfLauncher.launch(suggestedName)
                    ExportFormat.TIFF -> createTiffLauncher.launch(suggestedName)
                }
            },
            onDismiss = {
                viewModel.dismissExportStatus()
                showExportSheet = false
            }
        )
    }

    if (showProfileMenu) {
        ProfileMenuSheet(
            activeProfileId = uiState.gradingParams.profileId,
            profileAmount = uiState.gradingParams.profileAmount,
            recentProfileIds = uiState.recentProfileIds,
            favoriteProfileIds = uiState.favoriteProfileIds,
            onSelectProfile = viewModel::selectProfile,
            onAmountChange = viewModel::updateProfileAmount,
            onDraggingChanged = viewModel::setSliderDragging,
            onToggleFavorite = viewModel::toggleFavoriteProfile,
            onOpenBrowser = { showProfileBrowser = true },
            onDismiss = { showProfileMenu = false }
        )
    }

    if (showProfileBrowser) {
        ProfileBrowserSheet(
            activeProfileId = uiState.gradingParams.profileId,
            profileAmount = uiState.gradingParams.profileAmount,
            favoriteProfileIds = uiState.favoriteProfileIds,
            thumbnails = uiState.profileThumbnails,
            isLoadingThumbnails = uiState.isLoadingProfileThumbnails,
            onRequestThumbnails = viewModel::ensureProfileThumbnailsLoaded,
            onSelectProfile = viewModel::selectProfile,
            onAmountChange = viewModel::updateProfileAmount,
            onDraggingChanged = viewModel::setSliderDragging,
            onToggleFavorite = viewModel::toggleFavoriteProfile,
            onDismiss = { showProfileBrowser = false }
        )
    }
}

@Composable
private fun ImmersiveCanvasStage(
    uiState: EditorUiState,
    activeTexture: android.graphics.Bitmap?,
    canReset: Boolean,
    canShowTouchAdjust: Boolean,
    isTouchAdjustPillVisible: Boolean,
    viewModel: EditorViewModel,
    onZoomChanged: (Boolean) -> Unit,
    onOpenPhoto: () -> Unit,
    onOpenRawFile: () -> Unit,
    onQuickShare: () -> Unit,
    onOpenExport: () -> Unit,
    modifier: Modifier = Modifier
) {
    val frameShape = RoundedCornerShape(16.dp)
    Box(
        modifier = modifier
            .clip(frameShape)
            .background(StudioBackground)
            .border(1.dp, StudioGlassHairline, frameShape)
    ) {
        PhotoViewport(
            previewBitmap = activeTexture,
            fullWidth = uiState.fullWidth,
            fullHeight = uiState.fullHeight,
            gradingParams = uiState.gradingParams,
            activeLut = uiState.activeLut,
            activeTab = uiState.activeTab,
            isComparingOriginal = uiState.isComparingOriginal,
            isSplitCompareEnabled = uiState.isSplitCompareEnabled,
            splitCompareRatio = uiState.splitCompareRatio,
            showHistogram = uiState.showHistogram,
            histogram = uiState.histogram,
            healMode = uiState.healMode,
            healStrokes = uiState.healStrokes,
            activeHealStroke = uiState.activeHealStroke,
            healBrushRadiusNorm = uiState.healBrushRadiusNorm,
            healFeather = uiState.healFeather,
            showHealMask = uiState.showHealMask,
            cloneSourcePoint = uiState.cloneSourcePoint,
            isPickingCloneSource = uiState.isPickingCloneSource,
            isHealProcessing = uiState.isHealProcessing,
            activeEyedropperMode = uiState.activeEyedropperMode,
            cropOverlayMode = uiState.cropOverlayMode,
            activeMask = uiState.activeMask,
            showMaskOverlay = uiState.showMaskOverlay,
            viewportResetToken = uiState.viewportResetToken,
            isTouchAdjustEnabled = uiState.isTouchAdjustEnabled,
            touchAdjustSelectedBand = uiState.touchAdjustSelectedBand,
            touchAdjustSwatchArgb = uiState.touchAdjustSwatchArgb,
            touchAdjustBandAdjustment = uiState.touchAdjustSelectedBand?.let { uiState.gradingParams.hsl[it] }
                ?: HslBandAdjustment(),
            touchAdjustHudLabel = uiState.touchAdjustHudLabel,
            touchAdjustHudValueText = uiState.touchAdjustHudValueText,
            isTouchAdjustSwiping = uiState.isTouchAdjustSwiping,
            onZoomChanged = onZoomChanged,
            onCompareChanged = viewModel::setComparingOriginal,
            onSplitRatioChange = viewModel::setSplitCompareRatio,
            onToggleHistogram = viewModel::toggleHistogram,
            onStartHealStroke = viewModel::startHealStroke,
            onAppendHealStroke = viewModel::appendHealStroke,
            onFinishHealStroke = viewModel::finishHealStroke,
            onCancelHealStroke = viewModel::cancelHealStroke,
            onSetCloneSource = viewModel::setCloneSourcePoint,
            onEyedropperPick = viewModel::pickColorFromCanvas,
            onCancelEyedropper = viewModel::cancelEyedropper,
            onCropRectChange = viewModel::updateCropRect,
            onCropDragStateChanged = viewModel::setSliderDragging,
            onUpdateMask = viewModel::updateMask,
            onMaskDragStateChanged = viewModel::setSliderDragging,
            onToggleTouchAdjust = viewModel::toggleTouchAdjust,
            onTouchAdjustTap = viewModel::onTouchAdjustTap,
            onTouchAdjustSwipeStart = viewModel::onTouchAdjustSwipeStart,
            onTouchAdjustSwipeUpdate = viewModel::onTouchAdjustSwipeUpdate,
            onTouchAdjustSwipeEnd = viewModel::onTouchAdjustSwipeEnd,
            onUpdateTouchAdjustBand = viewModel::updateTouchAdjustBand,
            onResetTouchAdjustBand = viewModel::resetTouchAdjustBand,
            onDismissTouchAdjustPill = viewModel::dismissTouchAdjustPill,
            onTouchAdjustSliderDraggingChanged = viewModel::setSliderDragging,
            topOverlayInset = 64.dp,
            bottomOverlayInset = 62.dp,
            modifier = Modifier.fillMaxSize()
        )

        // Top Floating Frosted-Glass Header: Emblem + Gallery / RAW Import | Undo / Redo / Before-After | Quick Share + Export
        TopFloatingGlassHeader(
            photoFormatInfo = uiState.photoFormatInfo,
            canExport = uiState.fullWidth > 0,
            isExporting = uiState.exportStatus is ExportStatus.Exporting,
            canUndo = uiState.canUndo,
            canRedo = uiState.canRedo,
            isComparingOriginal = uiState.isComparingOriginal,
            isSplitCompareEnabled = uiState.isSplitCompareEnabled,
            onOpenPhoto = onOpenPhoto,
            onOpenRawFile = onOpenRawFile,
            onUndo = viewModel::undo,
            onRedo = viewModel::redo,
            onCompareChanged = viewModel::setComparingOriginal,
            onToggleSplitCompare = viewModel::toggleSplitCompare,
            onQuickShare = onQuickShare,
            onOpenExport = onOpenExport,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp)
        )

        // Tiny Frosted-Glass Histogram toggle at bottom-start corner (48dp touch target)
        AnimatedVisibility(
            visible = !isTouchAdjustPillVisible && !uiState.isSplitCompareEnabled && uiState.histogram != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 8.dp, bottom = 8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(onClick = viewModel::toggleHistogram)
                    .testTag("histogram_toggle_button"),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = CircleShape,
                    color = StudioGlassSurface,
                    shadowElevation = 6.dp,
                    modifier = Modifier
                        .size(40.dp)
                        .border(
                            width = 1.dp,
                            color = if (uiState.showHistogram) StudioActiveOffWhite.copy(alpha = 0.28f) else StudioGlassHairline,
                            shape = CircleShape
                        )
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = StudioIcons.HistogramBars,
                            contentDescription = stringResource(R.string.cd_toggle_histogram),
                            tint = if (uiState.showHistogram) StudioCinemaAmber else StudioMutedSlate,
                            modifier = Modifier.size(22.dp)
                        )
                        if (uiState.showHistogram) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 5.dp)
                                    .size(3.5.dp)
                                    .clip(CircleShape)
                                    .background(StudioCinemaAmber)
                            )
                        }
                    }
                }
            }
        }

        // Tiny On-Photo Frosted-Glass Utility Pill at Bottom-End Corner: Touch Adjust, Copy/Paste, Reset
        AnimatedVisibility(
            visible = !isTouchAdjustPillVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 10.dp, bottom = 10.dp)
        ) {
            CornerUtilityPill(
                canShowTouchAdjust = canShowTouchAdjust,
                isTouchAdjustEnabled = uiState.isTouchAdjustEnabled,
                canPasteEdits = uiState.canPasteEdits,
                canReset = canReset,
                onToggleTouchAdjust = viewModel::toggleTouchAdjust,
                onCopyEdits = viewModel::copyEdits,
                onPasteEdits = viewModel::pasteEdits,
                onResetAll = viewModel::resetAllAdjustments
            )
        }
    }
}

@Composable
private fun TopFloatingGlassHeader(
    photoFormatInfo: PhotoFormatInfo,
    canExport: Boolean,
    isExporting: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    isComparingOriginal: Boolean,
    isSplitCompareEnabled: Boolean,
    onOpenPhoto: () -> Unit,
    onOpenRawFile: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onCompareChanged: (Boolean) -> Unit,
    onToggleSplitCompare: () -> Unit,
    onQuickShare: () -> Unit,
    onOpenExport: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val currentCompareChanged by rememberUpdatedState(onCompareChanged)
    val currentToggleSplit by rememberUpdatedState(onToggleSplitCompare)
    val compareActive = isComparingOriginal || isSplitCompareEnabled

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val useCompactScroll = maxWidth < 436.dp
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (useCompactScroll) {
                            Modifier.horizontalScroll(rememberScrollState())
                        } else {
                            Modifier
                        }
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (useCompactScroll) {
                    Arrangement.spacedBy(6.dp)
                } else {
                    Arrangement.SpaceBetween
                }
            ) {
                // Left Elevated Graphite Pill: ZamLab Emblem + Gallery Open + Secondary Import RAW / File
                Surface(
                    shape = RoundedCornerShape(50),
                    color = StudioGraphite,
                    shadowElevation = 6.dp,
                    modifier = Modifier.border(1.dp, StudioHairline, RoundedCornerShape(50))
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 6.dp, end = 0.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = StudioObsidian,
                            modifier = Modifier
                                .size(25.dp)
                                .border(1.dp, StudioHairline, CircleShape)
                                .testTag("zamlab_brand_emblem")
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.ic_zamlab_foreground),
                                contentDescription = stringResource(R.string.cd_zamlab_emblem),
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(2.dp)
                                    .clip(CircleShape)
                            )
                        }

                        Spacer(modifier = Modifier.width(5.dp))
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(16.dp)
                                .background(StudioHairline)
                        )

                        IconButton(
                            onClick = onOpenPhoto,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("open_photo_button")
                        ) {
                            Icon(
                                imageVector = StudioIcons.OpenPhoto,
                                contentDescription = stringResource(R.string.cd_open_photo),
                                tint = StudioCoolWhite,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(16.dp)
                                .background(StudioHairline)
                        )

                        IconButton(
                            onClick = onOpenRawFile,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("import_raw_file_button")
                        ) {
                            Icon(
                                imageVector = StudioIcons.ImportRawFile,
                                contentDescription = stringResource(R.string.cd_import_raw_file),
                                tint = StudioMutedSlate,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }

        // Center Elevated Graphite Pill: Undo | Redo | Hold-to-Compare Before/After
        Surface(
            shape = RoundedCornerShape(50),
            color = StudioGraphite,
            shadowElevation = 6.dp,
            modifier = Modifier.border(1.dp, StudioHairline, RoundedCornerShape(50))
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onUndo,
                    enabled = canUndo,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("undo_button")
                ) {
                    Icon(
                        imageVector = StudioIcons.Undo,
                        contentDescription = stringResource(R.string.cd_undo_history),
                        tint = if (canUndo) StudioCoolWhite else StudioTextMuted.copy(alpha = 0.5f),
                        modifier = Modifier.size(24.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(16.dp)
                        .background(StudioHairline)
                )

                IconButton(
                    onClick = onRedo,
                    enabled = canRedo,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("redo_button")
                ) {
                    Icon(
                        imageVector = StudioIcons.Redo,
                        contentDescription = stringResource(R.string.cd_redo_history),
                        tint = if (canRedo) StudioCoolWhite else StudioTextMuted.copy(alpha = 0.5f),
                        modifier = Modifier.size(24.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(16.dp)
                        .background(StudioHairline)
                )

                // Compact Before/After hold-to-compare toggle (hold = Original, tap = Split)
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(if (compareActive) StudioAmberPillFill else Color.Transparent)
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                var holdingOriginal = false
                                val holdJob = scope.launch {
                                    delay(160L.milliseconds)
                                    holdingOriginal = true
                                    currentCompareChanged(true)
                                }
                                try {
                                    do {
                                        val event = awaitPointerEvent()
                                    } while (event.changes.any { it.pressed })
                                } finally {
                                    holdJob.cancel()
                                    if (holdingOriginal) {
                                        currentCompareChanged(false)
                                    } else {
                                        currentToggleSplit()
                                    }
                                }
                            }
                        }
                        .testTag("compare_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = StudioIcons.SplitCompare,
                        contentDescription = stringResource(R.string.cd_compare_before_after),
                        tint = if (compareActive) StudioCinemaAmber else StudioMutedSlate,
                        modifier = Modifier.size(24.dp)
                    )
                    if (compareActive) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 5.dp)
                                .size(3.5.dp)
                                .clip(CircleShape)
                                .background(StudioCinemaAmber)
                        )
                    }
                }
            }
        }

        // Right Cluster: Direct One-Tap Quick Share + Bold Uppercase Cinema Amber EXPORT Pill
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = StudioGraphite,
                shadowElevation = 6.dp,
                modifier = Modifier.border(1.dp, StudioHairline, CircleShape)
            ) {
                IconButton(
                    onClick = onQuickShare,
                    enabled = canExport && !isExporting,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("top_share_button")
                ) {
                    if (isExporting) {
                        CircularProgressIndicator(
                            color = StudioCinemaAmber,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp)
                        )
                    } else {
                        Icon(
                            imageVector = StudioIcons.SharePhoto,
                            contentDescription = stringResource(R.string.cd_quick_share),
                            tint = if (canExport) StudioCoolWhite else StudioTextMuted.copy(alpha = 0.5f),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            Button(
                onClick = onOpenExport,
                enabled = canExport && !isExporting,
                colors = ButtonDefaults.buttonColors(
                    containerColor = StudioCinemaAmber,
                    contentColor = StudioCharcoal,
                    disabledContainerColor = StudioCinemaAmber.copy(alpha = 0.28f),
                    disabledContentColor = StudioCharcoal.copy(alpha = 0.6f)
                ),
                shape = RoundedCornerShape(20.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                modifier = Modifier
                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    .height(48.dp)
                    .testTag("export_button")
            ) {
                Icon(
                    imageVector = StudioIcons.ExportArrow,
                    contentDescription = stringResource(R.string.cd_export_photo),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = stringResource(R.string.action_export).uppercase(Locale.ROOT),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.6.sp
                    ),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        }
        }

        // Subtle Format Badge in the Top Bar when a RAW / HEIC / TIFF photo is loaded
        val formatLabel = photoFormatInfo.badgeLabel
        if (!formatLabel.isNullOrBlank()) {
            val isRaw = photoFormatInfo.isRaw
            Surface(
                shape = RoundedCornerShape(50),
                color = StudioGlassSurface,
                shadowElevation = 4.dp,
                modifier = Modifier
                    .border(
                        width = 1.dp,
                        color = if (isRaw) StudioLuxuryGold.copy(alpha = 0.42f) else StudioGlassHairline,
                        shape = RoundedCornerShape(50)
                    )
                    .testTag("photo_format_badge")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.5.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(if (isRaw) StudioLuxuryGold else StudioActiveOffWhite.copy(alpha = 0.8f))
                    )
                    Text(
                        text = formatLabel,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.6.sp
                        ),
                        color = if (isRaw) StudioLuxuryGold else StudioActiveOffWhite,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * Tiny frosted-glass utility pill placed at the bottom-end corner of the photo canvas:
 * Touch Adjust toggle, Copy/Paste edits, and Reset.
 */
@Composable
private fun CornerUtilityPill(
    canShowTouchAdjust: Boolean,
    isTouchAdjustEnabled: Boolean,
    canPasteEdits: Boolean,
    canReset: Boolean,
    onToggleTouchAdjust: () -> Unit,
    onCopyEdits: () -> Unit,
    onPasteEdits: () -> Unit,
    onResetAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = StudioGlassSurface,
        shadowElevation = 6.dp,
        modifier = modifier
            .border(1.dp, StudioGlassHairline, RoundedCornerShape(50))
            .testTag("floating_utility_pill")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            if (canShowTouchAdjust) {
                CornerPillIconButton(
                    icon = Icons.Default.TouchApp,
                    contentDescription = stringResource(R.string.cd_touch_adjust_toggle),
                    active = isTouchAdjustEnabled,
                    showGoldDot = isTouchAdjustEnabled,
                    enabled = true,
                    testTag = "touch_adjust_toggle_button",
                    onClick = onToggleTouchAdjust
                )
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(15.dp)
                        .background(StudioGlassHairline)
                )
            }

            CornerPillIconButton(
                icon = Icons.Default.ContentCopy,
                contentDescription = stringResource(R.string.cd_copy_edits),
                active = false,
                showGoldDot = false,
                enabled = true,
                testTag = "adjust_copy_button",
                onClick = onCopyEdits
            )

            CornerPillIconButton(
                icon = Icons.Default.ContentPaste,
                contentDescription = stringResource(R.string.cd_paste_edits),
                active = canPasteEdits,
                showGoldDot = canPasteEdits,
                enabled = canPasteEdits,
                testTag = "adjust_paste_button",
                onClick = onPasteEdits
            )

            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(15.dp)
                    .background(StudioGlassHairline)
            )

            CornerPillIconButton(
                icon = StudioIcons.ResetArc,
                contentDescription = stringResource(R.string.cd_reset_adjustments),
                active = false,
                showGoldDot = false,
                enabled = canReset,
                testTag = "reset_button",
                onClick = onResetAll
            )
        }
    }
}

@Composable
private fun CornerPillIconButton(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean,
    showGoldDot: Boolean,
    enabled: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    val tint = when {
        !enabled -> StudioTextMuted.copy(alpha = 0.42f)
        active -> StudioActiveOffWhite
        else -> StudioTextSecondary
    }
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(if (active && enabled) StudioGlassHighlight else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(17.dp)
        )
        if (showGoldDot && enabled) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 7.dp)
                    .size(3.5.dp)
                    .clip(CircleShape)
                    .background(StudioLuxuryGold)
            )
        }
    }
}

@Composable
private fun ActiveToolPanel(
    uiState: EditorUiState,
    viewModel: EditorViewModel,
    onImportLut: () -> Unit,
    onOpenProfileMenu: () -> Unit
) {
    when (uiState.activeTab) {
        StudioTab.ADJUST -> AdjustPanel(
            params = uiState.gradingParams,
            histogram = uiState.histogram,
            activeEyedropperMode = uiState.activeEyedropperMode,
            onParamsChange = viewModel::updateGradingParams,
            onDraggingChanged = viewModel::setSliderDragging,
            onOpenProfileMenu = onOpenProfileMenu,
            onSelectWhiteBalancePreset = viewModel::selectWhiteBalancePreset,
            onToggleEyedropper = viewModel::toggleEyedropperMode,
            onAutoTone = viewModel::applyAutoTone,
            onToggleBlackAndWhite = viewModel::toggleBlackAndWhite,
            onUpdateChromaticAberration = viewModel::updateChromaticAberration,
            onResetChromaticAberration = viewModel::resetChromaticAberration,
            onUpdateAnamorphicStreak = viewModel::updateAnamorphicStreak,
            onResetAnamorphicStreak = viewModel::resetAnamorphicStreak
        )

        StudioTab.ALL -> AllParametersPanel(
            params = uiState.gradingParams,
            histogram = uiState.histogram,
            activeEyedropperMode = uiState.activeEyedropperMode,
            savedRecipeCount = uiState.userPresets.size,
            onParamsChange = viewModel::updateGradingParams,
            onDraggingChanged = viewModel::setSliderDragging,
            onOpenProfileMenu = onOpenProfileMenu,
            onSelectWhiteBalancePreset = viewModel::selectWhiteBalancePreset,
            onToggleEyedropper = viewModel::toggleEyedropperMode,
            onAutoTone = viewModel::applyAutoTone,
            onToggleBlackAndWhite = viewModel::toggleBlackAndWhite,
            onSaveRecipe = viewModel::saveCurrentLookAsPreset,
            onUpdateChromaticAberration = viewModel::updateChromaticAberration,
            onResetChromaticAberration = viewModel::resetChromaticAberration,
            onUpdateAnamorphicStreak = viewModel::updateAnamorphicStreak,
            onResetAnamorphicStreak = viewModel::resetAnamorphicStreak
        )

        StudioTab.LUTS -> LutPanel(
            folders = uiState.lutFolders,
            selectedLutId = uiState.selectedLutId,
            activeLut = uiState.activeLut,
            lutIntensity = uiState.gradingParams.lutIntensity,
            userPresets = uiState.userPresets,
            userPresetThumbnails = uiState.userPresetThumbnails,
            selectedUserPresetId = uiState.selectedUserPresetId,
            onToggleFolder = viewModel::toggleLutFolder,
            onSelectLut = viewModel::selectLut,
            onIntensityChange = viewModel::updateLutIntensity,
            onDraggingChanged = viewModel::setSliderDragging,
            onImportCubeClick = onImportLut,
            onDeleteCustomLut = viewModel::deleteCustomLut,
            onSaveCurrentLook = viewModel::saveCurrentLookAsPreset,
            onApplyUserPreset = viewModel::applyUserPreset,
            onDeleteUserPreset = viewModel::deleteUserPreset
        )

        StudioTab.CROP -> CropPanel(
            crop = uiState.gradingParams.crop,
            overlayMode = uiState.cropOverlayMode,
            onOverlayModeChange = viewModel::setCropOverlayMode,
            onAspectPresetSelect = viewModel::selectCropAspectPreset,
            onToggleAspectLock = viewModel::toggleCropAspectLocked,
            onSwapAspect = viewModel::swapCropAspect,
            onRotateLeft = viewModel::rotateCropLeft,
            onRotateRight = viewModel::rotateCropRight,
            onFlipHorizontal = viewModel::flipCropHorizontal,
            onFlipVertical = viewModel::flipCropVertical,
            onAutoStraighten = viewModel::applyAutoStraighten,
            onToggleConstrainCrop = viewModel::toggleConstrainCrop,
            onCropChange = { transform ->
                viewModel.updateGradingParams { gp -> gp.copy(crop = transform(gp.crop)) }
            },
            onDraggingChanged = viewModel::setSliderDragging,
            onApplyCrop = viewModel::applyCrop,
            onResetAllCrop = viewModel::resetAllCrop,
            onResetCropOrientation = viewModel::resetCropAndOrientation,
            onResetGeometry = viewModel::resetGeometryPerspective
        )

        StudioTab.HEAL -> HealPanel(
            healMode = uiState.healMode,
            brushSizePx = uiState.healBrushSizePx,
            feather = uiState.healFeather,
            opacity = uiState.healOpacity,
            showHealMask = uiState.showHealMask,
            isPickingCloneSource = uiState.isPickingCloneSource,
            hasCloneSource = uiState.cloneSourcePoint != null,
            canUndoStroke = uiState.canUndoHealStroke,
            canRedoStroke = uiState.canRedoHealStroke,
            hasHealEdits = uiState.hasHealEdits,
            isProcessing = uiState.isHealProcessing,
            onSelectMode = viewModel::setHealMode,
            onBrushSizeChange = viewModel::setHealBrushSizePx,
            onFeatherChange = viewModel::setHealFeather,
            onOpacityChange = viewModel::setHealOpacity,
            onTogglePickCloneSource = viewModel::togglePickCloneSource,
            onToggleShowMask = viewModel::toggleShowHealMask,
            onUndoStroke = viewModel::undoHealStroke,
            onRedoStroke = viewModel::redoHealStroke,
            onResetHeal = viewModel::resetHeal
        )

        StudioTab.MASK -> MaskPanel(
            masks = uiState.gradingParams.masks,
            activeMask = uiState.activeMask,
            showMaskOverlay = uiState.showMaskOverlay,
            onAddMask = viewModel::addLocalMask,
            onSelectMask = { viewModel.selectMask(it) },
            onUpdateMask = viewModel::updateMask,
            onToggleMaskEnabled = viewModel::toggleMaskEnabled,
            onToggleMaskInverted = viewModel::toggleMaskInverted,
            onToggleShowOverlay = viewModel::toggleShowMaskOverlay,
            onDeleteMask = viewModel::deleteMask,
            onDraggingChanged = viewModel::setSliderDragging
        )
    }
}

/**
 * Compact 6-tab floating frosted-glass capsule bottom navigation:
 * Adjust | All | LUTs | Crop | Heal | Masks.
 */
@Composable
private fun FloatingCapsuleNavDock(
    uiState: EditorUiState,
    onSelectTab: (StudioTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val capsuleShape = RoundedCornerShape(34.dp)
    val gp = uiState.gradingParams
    val hasAdjustEdits = gp.hasLightEdits || gp.hasColorEdits || gp.hasEffectsEdits || gp.hasDetailEdits || gp.hasProfileEdits
    val hasLutEdits = uiState.selectedLutId != null || uiState.selectedUserPresetId != null
    val hasCropEdits = !gp.crop.isDefault
    val hasHealEdits = uiState.hasHealEdits
    val hasMaskEdits = gp.hasMaskEdits

    Surface(
        shape = capsuleShape,
        color = StudioGraphite,
        shadowElevation = 10.dp,
        modifier = modifier
            .height(68.dp)
            .border(1.dp, StudioHairline, capsuleShape)
            .testTag("floating_capsule_nav")
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val useHorizontalScroll = maxWidth < 388.dp
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (useHorizontalScroll) {
                            Modifier.horizontalScroll(rememberScrollState())
                        } else {
                            Modifier
                        }
                    )
                    .padding(horizontal = 6.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val itemModifier = if (useHorizontalScroll) {
                    Modifier
                        .fillMaxHeight()
                        .widthIn(min = 60.dp)
                } else {
                    Modifier
                        .fillMaxHeight()
                        .weight(1f)
                }
                CapsuleNavTabItem(
                    label = stringResource(R.string.tab_adjust),
                    selectedIcon = StudioIcons.TabAdjust,
                    unselectedIcon = StudioIcons.TabAdjust,
                    selected = uiState.activeTab == StudioTab.ADJUST,
                    hasEdits = hasAdjustEdits,
                    testTag = "tab_adjust",
                    onClick = { onSelectTab(StudioTab.ADJUST) },
                    modifier = itemModifier
                )
                CapsuleNavTabItem(
                    label = stringResource(R.string.tab_all),
                    selectedIcon = StudioIcons.TabAll,
                    unselectedIcon = StudioIcons.TabAll,
                    selected = uiState.activeTab == StudioTab.ALL,
                    hasEdits = hasAdjustEdits,
                    testTag = "tab_all",
                    onClick = { onSelectTab(StudioTab.ALL) },
                    modifier = itemModifier
                )
                CapsuleNavTabItem(
                    label = stringResource(R.string.tab_luts),
                    selectedIcon = StudioIcons.TabLuts,
                    unselectedIcon = StudioIcons.TabLuts,
                    selected = uiState.activeTab == StudioTab.LUTS,
                    hasEdits = hasLutEdits,
                    testTag = "tab_luts",
                    onClick = { onSelectTab(StudioTab.LUTS) },
                    modifier = itemModifier
                )
                CapsuleNavTabItem(
                    label = stringResource(R.string.tab_crop),
                    selectedIcon = StudioIcons.TabCrop,
                    unselectedIcon = StudioIcons.TabCrop,
                    selected = uiState.activeTab == StudioTab.CROP,
                    hasEdits = hasCropEdits,
                    testTag = "tab_crop",
                    onClick = { onSelectTab(StudioTab.CROP) },
                    modifier = itemModifier
                )
                CapsuleNavTabItem(
                    label = stringResource(R.string.tab_heal),
                    selectedIcon = StudioIcons.TabHeal,
                    unselectedIcon = StudioIcons.TabHeal,
                    selected = uiState.activeTab == StudioTab.HEAL,
                    hasEdits = hasHealEdits,
                    testTag = "tab_heal",
                    onClick = { onSelectTab(StudioTab.HEAL) },
                    modifier = itemModifier
                )
                CapsuleNavTabItem(
                    label = stringResource(R.string.tab_mask),
                    selectedIcon = StudioIcons.TabMasks,
                    unselectedIcon = StudioIcons.TabMasks,
                    selected = uiState.activeTab == StudioTab.MASK,
                    hasEdits = hasMaskEdits,
                    testTag = "tab_mask",
                    onClick = { onSelectTab(StudioTab.MASK) },
                    modifier = itemModifier
                )
            }
        }
    }
}

@Composable
private fun CapsuleNavTabItem(
    label: String,
    selectedIcon: ImageVector,
    unselectedIcon: ImageVector,
    selected: Boolean,
    hasEdits: Boolean,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor by animateColorAsState(
        targetValue = if (selected) StudioDockActivePill else Color.Transparent,
        animationSpec = tween(durationMillis = 150),
        label = "capsule_tab_bg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) StudioCinemaAmber else Color.Transparent,
        animationSpec = tween(durationMillis = 150),
        label = "capsule_tab_border"
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) StudioCinemaAmber else StudioMutedSlate,
        animationSpec = tween(durationMillis = 150),
        label = "capsule_tab_tint"
    )

    val pillShape = RoundedCornerShape(26.dp)
    Box(
        modifier = modifier
            .heightIn(min = 56.dp)
            .minimumInteractiveComponentSize()
            .clip(pillShape)
            .background(bgColor)
            .border(1.2.dp, borderColor, pillShape)
            .clickable(onClick = onClick)
            .padding(vertical = 5.dp, horizontal = 8.dp)
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Icon(
                imageVector = if (selected) selectedIcon else unselectedIcon,
                contentDescription = label,
                tint = contentColor,
                modifier = Modifier.size(22.dp)
            )
            Text(
                text = label.uppercase(Locale.ROOT),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.4.sp
                ),
                color = contentColor,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip
            )
        }
        if (hasEdits) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 6.dp, end = 7.dp)
                    .size(4.5.dp)
                    .clip(CircleShape)
                    .background(StudioCinemaAmber)
            )
        }
    }
}

/**
 * Lightroom-style non-blocking Material 3 Snackbar host anchored directly above
 * the 68dp floating capsule dock, styled in Calibrated Studio Darkroom tokens
 * (#1E2026 container, #2E323C 1dp hairline border, #F2F4F8 message, #F5A623 SHARE action).
 */
@Composable
private fun StudioDarkroomSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier
) {
    SnackbarHost(
        hostState = hostState,
        modifier = modifier
    ) { data ->
        val snackbarShape = RoundedCornerShape(14.dp)
        Snackbar(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .widthIn(max = 460.dp)
                .border(1.dp, StudioTileBorder, snackbarShape)
                .testTag("export_snackbar"),
            shape = snackbarShape,
            containerColor = StudioTileSurface,
            contentColor = StudioCoolWhite,
            actionContentColor = StudioCinemaAmber,
            action = data.visuals.actionLabel?.let { actionLabel ->
                {
                    TextButton(
                        onClick = { data.performAction() },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = StudioCinemaAmber
                        ),
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .testTag("export_snackbar_share_action")
                    ) {
                        Text(
                            text = actionLabel,
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 0.6.sp
                            ),
                            color = StudioCinemaAmber,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        ) {
            Text(
                text = data.visuals.message,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Medium
                ),
                color = StudioCoolWhite,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun launchShareExportedFile(
    context: Context,
    exported: ExportStatus.Success,
    chooserTitle: String = "Share Photo"
) {
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = exported.mimeType
        putExtra(Intent.EXTRA_STREAM, exported.uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(sendIntent, chooserTitle)
    runCatching {
        context.startActivity(chooser)
    }
}
