package com.example.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.CropRotate
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FilterVintage
import androidx.compose.material.icons.filled.Gradient
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material.icons.outlined.CropRotate
import androidx.compose.material.icons.outlined.FilterVintage
import androidx.compose.material.icons.outlined.Gradient
import androidx.compose.material.icons.outlined.Healing
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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
import com.example.editor.ImageIoHelper
import com.example.editor.StudioTab
import com.example.ui.components.AdjustPanel
import com.example.ui.components.CropPanel
import com.example.ui.components.ExportBottomSheet
import com.example.ui.components.HealPanel
import com.example.ui.components.LutPanel
import com.example.ui.components.MaskPanel
import com.example.ui.components.PhotoViewport
import com.example.ui.components.ProfileBrowserSheet
import com.example.ui.components.ProfileMenuSheet
import com.example.ui.theme.StudioAmber
import com.example.ui.theme.StudioBackground
import com.example.ui.theme.StudioBorderSubtle
import com.example.ui.theme.StudioCyan
import com.example.ui.theme.StudioEraserCrimson
import com.example.ui.theme.StudioLuxuryGold
import com.example.ui.theme.StudioObsidian
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioSurfaceElevated
import com.example.ui.theme.StudioTextMuted
import com.example.ui.theme.StudioTextPrimary
import com.example.ui.theme.StudioTextSecondary

@Composable
fun EditorScreen(
    viewModel: EditorViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var showExportSheet by remember { mutableStateOf(false) }
    var showProfileMenu by remember { mutableStateOf(false) }
    var showProfileBrowser by remember { mutableStateOf(false) }
    var isViewportZoomed by remember { mutableStateOf(false) }
    var pendingSafOptions by remember { mutableStateOf(ExportOptions()) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri ->
            uri?.let { viewModel.openPhotoFromUri(it) }
        }
    )

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

    LaunchedEffect(uiState.statusBannerMessage) {
        val msg = uiState.statusBannerMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            viewModel.dismissStatusBanner()
        }
    }

    val openPhotoAction = {
        photoPickerLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    val importLutAction = {
        lutPickerLauncher.launch(arrayOf("*/*"))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(StudioBackground)
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isWideLayout = maxWidth >= 720.dp

            Column(modifier = Modifier.fillMaxSize()) {
                StudioTopBar(
                    fullWidth = uiState.fullWidth,
                    effectiveOutputWidth = uiState.effectiveOutputWidth,
                    effectiveOutputHeight = uiState.effectiveOutputHeight,
                    onOpenPhoto = openPhotoAction,
                    onOpenExport = { showExportSheet = true }
                )

                HorizontalDivider(color = StudioBorderSubtle, thickness = 1.dp)

                val activeTexture = uiState.activePreviewTexture

                if (isWideLayout) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .navigationBarsPadding()
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
                            onZoomChanged = { isViewportZoomed = it },
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
                            modifier = Modifier
                                .weight(1.4f)
                                .fillMaxHeight()
                        )

                        VerticalDivider(color = StudioBorderSubtle, thickness = 1.dp)

                        Column(
                            modifier = Modifier
                                .width(360.dp)
                                .fillMaxHeight()
                                .background(StudioSurface)
                        ) {
                            StudioHistoryBar(
                                canUndo = uiState.canUndo,
                                canRedo = uiState.canRedo,
                                undoLabel = uiState.undoLabel,
                                isSplitCompareEnabled = uiState.isSplitCompareEnabled,
                                isComparingOriginal = uiState.isComparingOriginal,
                                canReset = !uiState.gradingParams.isDefault || uiState.selectedLutId != null || uiState.selectedUserPresetId != null || isViewportZoomed,
                                onUndo = viewModel::undo,
                                onRedo = viewModel::redo,
                                onCompareChanged = viewModel::setComparingOriginal,
                                onToggleSplitCompare = viewModel::toggleSplitCompare,
                                onResetAll = viewModel::resetAllAdjustments
                            )
                            HorizontalDivider(color = StudioBorderSubtle, thickness = 1.dp)
                            StudioTabBar(
                                activeTab = uiState.activeTab,
                                onSelectTab = viewModel::selectTab
                            )
                            HorizontalDivider(color = StudioBorderSubtle, thickness = 1.dp)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                            ) {
                                ActiveToolPanel(
                                    uiState = uiState,
                                    viewModel = viewModel,
                                    onImportLut = importLutAction,
                                    onOpenProfileMenu = { showProfileMenu = true },
                                    onOpenProfileBrowser = { showProfileBrowser = true }
                                )
                            }
                        }
                    }
                } else {
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
                        onZoomChanged = { isViewportZoomed = it },
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
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )

                    Surface(
                        color = StudioSurface,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                        ) {
                            HorizontalDivider(color = StudioBorderSubtle, thickness = 1.dp)
                            StudioHistoryBar(
                                canUndo = uiState.canUndo,
                                canRedo = uiState.canRedo,
                                undoLabel = uiState.undoLabel,
                                isSplitCompareEnabled = uiState.isSplitCompareEnabled,
                                isComparingOriginal = uiState.isComparingOriginal,
                                canReset = !uiState.gradingParams.isDefault || uiState.selectedLutId != null || uiState.selectedUserPresetId != null || isViewportZoomed,
                                onUndo = viewModel::undo,
                                onRedo = viewModel::redo,
                                onCompareChanged = viewModel::setComparingOriginal,
                                onToggleSplitCompare = viewModel::toggleSplitCompare,
                                onResetAll = viewModel::resetAllAdjustments
                            )
                            HorizontalDivider(color = StudioBorderSubtle, thickness = 1.dp)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 230.dp, max = 310.dp)
                            ) {
                                ActiveToolPanel(
                                    uiState = uiState,
                                    viewModel = viewModel,
                                    onImportLut = importLutAction,
                                    onOpenProfileMenu = { showProfileMenu = true },
                                    onOpenProfileBrowser = { showProfileBrowser = true }
                                )
                            }
                            HorizontalDivider(color = StudioBorderSubtle, thickness = 1.dp)
                            StudioTabBar(
                                activeTab = uiState.activeTab,
                                onSelectTab = viewModel::selectTab
                            )
                        }
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 60.dp, start = 16.dp, end = 16.dp)
        ) { data ->
            Snackbar(
                snackbarData = data,
                containerColor = StudioSurfaceElevated,
                contentColor = StudioTextPrimary,
                shape = RoundedCornerShape(10.dp)
            )
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
private fun ActiveToolPanel(
    uiState: EditorUiState,
    viewModel: EditorViewModel,
    onImportLut: () -> Unit,
    onOpenProfileMenu: () -> Unit,
    onOpenProfileBrowser: () -> Unit
) {
    when (uiState.activeTab) {
        StudioTab.ADJUST -> AdjustPanel(
            params = uiState.gradingParams,
            histogram = uiState.histogram,
            canPasteEdits = uiState.canPasteEdits,
            hasActiveLut = uiState.selectedLutId != null,
            activeEyedropperMode = uiState.activeEyedropperMode,
            onParamsChange = viewModel::updateGradingParams,
            onDraggingChanged = viewModel::setSliderDragging,
            onOpenProfileMenu = onOpenProfileMenu,
            onOpenProfileBrowser = onOpenProfileBrowser,
            onProfileAmountChange = viewModel::updateProfileAmount,
            onSelectWhiteBalancePreset = viewModel::selectWhiteBalancePreset,
            onToggleEyedropper = viewModel::toggleEyedropperMode,
            onAutoTone = viewModel::applyAutoTone,
            onToggleBlackAndWhite = viewModel::toggleBlackAndWhite,
            onResetAll = viewModel::resetAllAdjustments,
            onCopyEdits = viewModel::copyEdits,
            onPasteEdits = viewModel::pasteEdits
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

@Composable
private fun StudioTopBar(
    fullWidth: Int,
    effectiveOutputWidth: Int,
    effectiveOutputHeight: Int,
    onOpenPhoto: () -> Unit,
    onOpenExport: () -> Unit
) {
    Surface(
        color = StudioSurface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Surface(
                    shape = RoundedCornerShape(9.dp),
                    color = StudioObsidian,
                    modifier = Modifier
                        .size(34.dp)
                        .border(1.dp, StudioLuxuryGold.copy(alpha = 0.55f), RoundedCornerShape(9.dp))
                        .testTag("zamlab_brand_emblem")
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_zamlab_foreground),
                        contentDescription = stringResource(R.string.cd_zamlab_emblem),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(2.dp)
                            .clip(RoundedCornerShape(7.dp))
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.4.sp
                        ),
                        color = StudioTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("top_bar_brand_title")
                    )
                    if (fullWidth > 0) {
                        Text(
                            text = "${effectiveOutputWidth}×${effectiveOutputHeight}",
                            style = MaterialTheme.typography.labelSmall,
                            color = StudioTextMuted,
                            maxLines = 1
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {

                IconButton(
                    onClick = onOpenPhoto,
                    modifier = Modifier.testTag("open_photo_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.AddPhotoAlternate,
                        contentDescription = stringResource(R.string.cd_open_photo),
                        tint = StudioCyan
                    )
                }

                Button(
                    onClick = onOpenExport,
                    enabled = fullWidth > 0,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StudioAmber,
                        contentColor = StudioBackground
                    ),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier
                        .height(38.dp)
                        .testTag("export_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = stringResource(R.string.cd_export_photo),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.action_export),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

@Composable
private fun StudioHistoryBar(
    canUndo: Boolean,
    canRedo: Boolean,
    undoLabel: String?,
    isSplitCompareEnabled: Boolean,
    isComparingOriginal: Boolean,
    canReset: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onCompareChanged: (Boolean) -> Unit,
    onToggleSplitCompare: () -> Unit,
    onResetAll: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF12151C))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            IconButton(
                onClick = onUndo,
                enabled = canUndo,
                modifier = Modifier.testTag("undo_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Undo,
                    contentDescription = stringResource(R.string.cd_undo_history),
                    tint = if (canUndo) StudioTextPrimary else StudioTextMuted,
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(
                onClick = onRedo,
                enabled = canRedo,
                modifier = Modifier.testTag("redo_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Redo,
                    contentDescription = stringResource(R.string.cd_redo_history),
                    tint = if (canRedo) StudioTextPrimary else StudioTextMuted,
                    modifier = Modifier.size(20.dp)
                )
            }
            if (undoLabel != null) {
                Text(
                    text = undoLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = StudioTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // Split-screen Before/After wipe slider toggle
            IconButton(
                onClick = onToggleSplitCompare,
                modifier = Modifier.testTag("split_compare_button")
            ) {
                Icon(
                    imageVector = Icons.Default.VerticalSplit,
                    contentDescription = stringResource(R.string.cd_split_compare),
                    tint = if (isSplitCompareEnabled) StudioAmber else StudioTextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Hold to compare Original vs Graded
            Box(
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (isComparingOriginal) StudioAmber.copy(alpha = 0.2f) else Color.Transparent
                    )
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                onCompareChanged(true)
                                tryAwaitRelease()
                                onCompareChanged(false)
                            }
                        )
                    }
                    .padding(10.dp)
                    .testTag("compare_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Compare,
                    contentDescription = stringResource(R.string.cd_compare_before_after),
                    tint = if (isComparingOriginal) StudioAmber else StudioTextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }

            IconButton(
                onClick = onResetAll,
                enabled = canReset,
                modifier = Modifier.testTag("reset_button")
            ) {
                Icon(
                    imageVector = Icons.Default.RestartAlt,
                    contentDescription = stringResource(R.string.cd_reset_adjustments),
                    tint = if (canReset) {
                        StudioTextPrimary
                    } else {
                        StudioTextMuted
                    },
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun StudioTabBar(
    activeTab: StudioTab,
    onSelectTab: (StudioTab) -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .background(StudioSurface)
            .padding(horizontal = 4.dp, vertical = 6.dp)
    ) {
        val compactTabs = maxWidth < 400.dp
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            StudioTabItem(
                label = stringResource(R.string.tab_adjust),
                selectedIcon = Icons.Filled.Tune,
                unselectedIcon = Icons.Outlined.Tune,
                selected = activeTab == StudioTab.ADJUST,
                accentColor = StudioAmber,
                compact = compactTabs,
                testTag = "tab_adjust",
                onClick = { onSelectTab(StudioTab.ADJUST) },
                modifier = Modifier.weight(0.94f)
            )
            StudioTabItem(
                label = stringResource(R.string.tab_luts),
                selectedIcon = Icons.Filled.FilterVintage,
                unselectedIcon = Icons.Outlined.FilterVintage,
                selected = activeTab == StudioTab.LUTS,
                accentColor = StudioCyan,
                compact = compactTabs,
                testTag = "tab_luts",
                onClick = { onSelectTab(StudioTab.LUTS) },
                modifier = Modifier.weight(0.84f)
            )
            StudioTabItem(
                label = stringResource(R.string.tab_crop),
                selectedIcon = Icons.Filled.CropRotate,
                unselectedIcon = Icons.Outlined.CropRotate,
                selected = activeTab == StudioTab.CROP,
                accentColor = StudioAmber,
                compact = compactTabs,
                testTag = "tab_crop",
                onClick = { onSelectTab(StudioTab.CROP) },
                modifier = Modifier.weight(1.40f)
            )
            StudioTabItem(
                label = stringResource(R.string.tab_heal),
                selectedIcon = Icons.Filled.Healing,
                unselectedIcon = Icons.Outlined.Healing,
                selected = activeTab == StudioTab.HEAL,
                accentColor = StudioEraserCrimson,
                compact = compactTabs,
                testTag = "tab_heal",
                onClick = { onSelectTab(StudioTab.HEAL) },
                modifier = Modifier.weight(0.86f)
            )
            StudioTabItem(
                label = stringResource(R.string.tab_mask),
                selectedIcon = Icons.Filled.Gradient,
                unselectedIcon = Icons.Outlined.Gradient,
                selected = activeTab == StudioTab.MASK,
                accentColor = StudioAmber,
                compact = compactTabs,
                testTag = "tab_mask",
                onClick = { onSelectTab(StudioTab.MASK) },
                modifier = Modifier.weight(0.96f)
            )
        }
    }
}

@Composable
private fun StudioTabItem(
    label: String,
    selectedIcon: ImageVector,
    unselectedIcon: ImageVector,
    selected: Boolean,
    accentColor: Color,
    compact: Boolean,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val labelStyle = if (compact) {
        MaterialTheme.typography.labelSmall.copy(
            fontSize = 10.sp,
            letterSpacing = (-0.2).sp
        )
    } else {
        MaterialTheme.typography.labelSmall
    }
    Column(
        modifier = modifier
            .heightIn(min = 48.dp)
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) accentColor.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp, horizontal = 1.dp)
            .testTag(testTag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Icon(
            imageVector = if (selected) selectedIcon else unselectedIcon,
            contentDescription = label,
            tint = if (selected) accentColor else StudioTextSecondary,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = label,
            style = labelStyle,
            color = if (selected) accentColor else StudioTextSecondary,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}
