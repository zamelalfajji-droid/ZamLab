@file:Suppress("UnusedBoxWithConstraintsScope")

package com.zamalfajji.zamlab.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zamalfajji.zamlab.R
import com.zamalfajji.zamlab.editor.CameraCaptureHelper
import com.zamalfajji.zamlab.editor.CameraPermissionUiState
import com.zamalfajji.zamlab.editor.CompositionGridMode
import com.zamalfajji.zamlab.editor.EditorUiState
import com.zamalfajji.zamlab.editor.EditorViewModel
import com.zamalfajji.zamlab.editor.ExportFormat
import com.zamalfajji.zamlab.editor.ExportOptions
import com.zamalfajji.zamlab.editor.ExportStatus
import com.zamalfajji.zamlab.editor.HslBandAdjustment
import com.zamalfajji.zamlab.editor.ImageIoHelper
import com.zamalfajji.zamlab.editor.PhotoFormatInfo
import com.zamalfajji.zamlab.editor.StudioTab
import com.zamalfajji.zamlab.ui.components.AdjustPanel
import com.zamalfajji.zamlab.ui.components.AllParametersPanel
import com.zamalfajji.zamlab.ui.components.CompositionGridSelectorPill
import com.zamalfajji.zamlab.ui.components.CropPanel
import com.zamalfajji.zamlab.ui.components.DenoisePanel
import com.zamalfajji.zamlab.ui.components.ExportBottomSheet
import com.zamalfajji.zamlab.ui.components.HealPanel
import com.zamalfajji.zamlab.ui.components.LutPanel
import com.zamalfajji.zamlab.ui.components.MaskPanel
import com.zamalfajji.zamlab.ui.components.PhotoViewport
import com.zamalfajji.zamlab.ui.components.ProfileBrowserSheet
import com.zamalfajji.zamlab.ui.components.ProfileMenuSheet
import com.zamalfajji.zamlab.ui.components.SpiralFlipPillButton
import com.zamalfajji.zamlab.ui.components.StudioIcons
import com.zamalfajji.zamlab.ui.components.UpscalePanel
import com.zamalfajji.zamlab.ui.theme.StudioActiveOffWhite
import com.zamalfajji.zamlab.ui.theme.StudioAmberPillFill
import com.zamalfajji.zamlab.ui.theme.StudioBackground
import com.zamalfajji.zamlab.ui.theme.StudioCharcoal
import com.zamalfajji.zamlab.ui.theme.StudioCinemaAmber
import com.zamalfajji.zamlab.ui.theme.StudioCoolWhite
import com.zamalfajji.zamlab.ui.theme.StudioDockActivePill
import com.zamalfajji.zamlab.ui.theme.StudioGlassHairline
import com.zamalfajji.zamlab.ui.theme.StudioGlassHighlight
import com.zamalfajji.zamlab.ui.theme.StudioGlassSurface
import com.zamalfajji.zamlab.ui.theme.StudioGraphite
import com.zamalfajji.zamlab.ui.theme.StudioHairline
import com.zamalfajji.zamlab.ui.theme.StudioLuxuryGold
import com.zamalfajji.zamlab.ui.theme.StudioMutedSlate
import com.zamalfajji.zamlab.ui.theme.StudioObsidian
import com.zamalfajji.zamlab.ui.theme.StudioSuccessGreen
import com.zamalfajji.zamlab.ui.theme.StudioSurfaceElevated
import com.zamalfajji.zamlab.ui.theme.StudioTextMuted
import com.zamalfajji.zamlab.ui.theme.StudioTextPrimary
import com.zamalfajji.zamlab.ui.theme.StudioTextSecondary
import com.zamalfajji.zamlab.ui.theme.StudioTileBorder
import com.zamalfajji.zamlab.ui.theme.StudioTileSurface
import java.io.File
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
    val uiScope = rememberCoroutineScope()

    var showExportSheet by remember { mutableStateOf(false) }
    var showProfileMenu by remember { mutableStateOf(false) }
    var showProfileBrowser by remember { mutableStateOf(false) }
    var isViewportZoomed by remember { mutableStateOf(false) }
    var pendingSafOptions by remember { mutableStateOf(ExportOptions()) }
    var cameraPermissionUiState by remember { mutableStateOf(CameraPermissionUiState.IDLE) }
    var hasRequestedCameraPermission by rememberSaveable { mutableStateOf(false) }
    var pendingCaptureUriString by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingCaptureFilePath by rememberSaveable { mutableStateOf<String?>(null) }

    val cameraUnavailableMsg = stringResource(R.string.camera_unavailable_error)
    val cameraCaptureErrorMsg = stringResource(R.string.camera_capture_error)
    val galleryPickerUnavailableMsg = stringResource(R.string.gallery_picker_unavailable_error)

    // Built-in system camera capture saving full-resolution JPEG via FileProvider
    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
        onResult = { captured ->
            val capturedUri = pendingCaptureUriString?.let { runCatching { Uri.parse(it) }.getOrNull() }
            val capturedFile = pendingCaptureFilePath?.let { File(it) }
            pendingCaptureUriString = null
            pendingCaptureFilePath = null
            if (captured && capturedUri != null) {
                viewModel.onCameraPhotoCaptured(capturedUri, capturedFile)
            } else {
                CameraCaptureHelper.deleteCaptureFileQuietly(capturedFile)
            }
        }
    )

    val launchSystemCamera: () -> Unit = {
        runCatching { CameraCaptureHelper.createCaptureOutput(context) }
            .onSuccess { output ->
                pendingCaptureFilePath = output.file.absolutePath
                pendingCaptureUriString = output.uri.toString()
                try {
                    takePictureLauncher.launch(output.uri)
                } catch (_: ActivityNotFoundException) {
                    CameraCaptureHelper.deleteCaptureFileQuietly(output.file)
                    pendingCaptureFilePath = null
                    pendingCaptureUriString = null
                    uiScope.launch {
                        snackbarHostState.showSnackbar(
                            message = cameraUnavailableMsg,
                            duration = SnackbarDuration.Short
                        )
                    }
                } catch (_: Exception) {
                    CameraCaptureHelper.deleteCaptureFileQuietly(output.file)
                    pendingCaptureFilePath = null
                    pendingCaptureUriString = null
                    uiScope.launch {
                        snackbarHostState.showSnackbar(
                            message = cameraCaptureErrorMsg,
                            duration = SnackbarDuration.Short
                        )
                    }
                }
            }
            .onFailure {
                uiScope.launch {
                    snackbarHostState.showSnackbar(
                        message = cameraCaptureErrorMsg,
                        duration = SnackbarDuration.Short
                    )
                }
            }
    }

    // Runtime CAMERA permission launcher for direct camera capture
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            if (granted) {
                cameraPermissionUiState = CameraPermissionUiState.IDLE
                launchSystemCamera()
            } else {
                val activity = CameraCaptureHelper.findActivity(context)
                val shouldShowRationale = activity != null &&
                    ActivityCompat.shouldShowRequestPermissionRationale(
                        activity,
                        Manifest.permission.CAMERA
                    )
                cameraPermissionUiState = CameraCaptureHelper.resolveCameraPermissionDenial(
                    shouldShowRationale = shouldShowRationale,
                    hasRequestedBefore = hasRequestedCameraPermission
                )
            }
        }
    )

    // Primary photo import: opens Android system Photo Picker directly (zero storage permissions)
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri ->
            uri?.let { viewModel.openPhotoFromUri(it) }
        }
    )

    // Zero-permission SAF fallback when Photo Picker is unavailable on older/custom ROMs
    val fallbackGalleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
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

    val capturePhotoAction: () -> Unit = {
        val hasCameraPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (hasCameraPermission) {
            launchSystemCamera()
        } else {
            hasRequestedCameraPermission = true
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val openPhotoAction: () -> Unit = {
        val launched = if (ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(context)) {
            runCatching {
                photoPickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            }.isSuccess
        } else {
            false
        }
        if (!launched) {
            runCatching {
                fallbackGalleryLauncher.launch(arrayOf("image/*"))
            }.onFailure {
                uiScope.launch {
                    snackbarHostState.showSnackbar(
                        message = galleryPickerUnavailableMsg,
                        duration = SnackbarDuration.Short
                    )
                }
            }
        }
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
        uiState.activeTab != StudioTab.UPSCALE &&
        uiState.activeTab != StudioTab.DENOISE &&
        !uiState.isComparingOriginal

    val canShowTouchAdjust = uiState.activeTab != StudioTab.CROP &&
        uiState.activeTab != StudioTab.HEAL &&
        uiState.activeTab != StudioTab.UPSCALE &&
        uiState.activeTab != StudioTab.DENOISE &&
        uiState.activeEyedropperMode == com.zamalfajji.zamlab.editor.EyedropperMode.NONE

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(StudioBackground)
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown &&
                    keyEvent.key == Key.O &&
                    (uiState.activeTab == StudioTab.MASK || uiState.gradingParams.masks.isNotEmpty())
                ) {
                    viewModel.toggleShowMaskOverlay()
                    true
                } else {
                    false
                }
            }
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isWideLayout = maxWidth >= 720.dp
            val activeTexture = uiState.activePreviewTexture

            if (isWideLayout) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .displayCutoutPadding()
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
                        applyTopStatusBarPadding = false,
                        viewModel = viewModel,
                        onZoomChanged = { isViewportZoomed = it },
                        onCapturePhoto = capturePhotoAction,
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
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
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
                                applyTopStatusBarPadding = true,
                                viewModel = viewModel,
                                onZoomChanged = { isViewportZoomed = it },
                                onCapturePhoto = capturePhotoAction,
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
                                    // Responsive tool panel sizing so photo preview scales down cleanly
                                    // while keeping the LUT strength slider & horizontal thumbnail strip
                                    // permanently visible above the bottom navigation bar on all screen heights.
                                    val isLutsTab = uiState.activeTab == StudioTab.LUTS
                                    val panelMax = if (isLutsTab) {
                                        minOf(228.dp, (workspaceHeight * 0.48f).coerceAtLeast(190.dp))
                                    } else {
                                        cappedToolMax
                                    }
                                    val panelMin = if (isLutsTab) {
                                        minOf(190.dp, panelMax)
                                    } else {
                                        cappedToolMin
                                    }
                                    val toolPanelModifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = panelMin, max = panelMax)

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

    if (cameraPermissionUiState != CameraPermissionUiState.IDLE) {
        CameraPermissionDialog(
            permissionState = cameraPermissionUiState,
            onRetryRequest = {
                cameraPermissionUiState = CameraPermissionUiState.IDLE
                hasRequestedCameraPermission = true
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            },
            onOpenAppSettings = {
                cameraPermissionUiState = CameraPermissionUiState.IDLE
                runCatching {
                    context.startActivity(CameraCaptureHelper.buildAppSettingsIntent(context))
                }
            },
            onDismiss = {
                cameraPermissionUiState = CameraPermissionUiState.IDLE
            }
        )
    }
}

@Composable
private fun CameraPermissionDialog(
    permissionState: CameraPermissionUiState,
    onRetryRequest: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    val isPermanentlyDenied = permissionState == CameraPermissionUiState.PERMANENTLY_DENIED
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = StudioGraphite,
        titleContentColor = StudioTextPrimary,
        textContentColor = StudioTextSecondary,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .border(1.dp, StudioHairline, RoundedCornerShape(18.dp))
            .testTag("camera_permission_dialog"),
        icon = {
            Icon(
                imageVector = StudioIcons.CameraCapture,
                contentDescription = null,
                tint = StudioCinemaAmber,
                modifier = Modifier.size(28.dp)
            )
        },
        title = {
            Text(
                text = stringResource(R.string.camera_permission_title),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = StudioTextPrimary
                )
            )
        },
        text = {
            Text(
                text = stringResource(
                    if (isPermanentlyDenied) {
                        R.string.camera_permission_permanently_denied
                    } else {
                        R.string.camera_permission_rationale
                    }
                ),
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = StudioTextSecondary,
                    lineHeight = 20.sp
                )
            )
        },
        confirmButton = {
            Button(
                onClick = if (isPermanentlyDenied) onOpenAppSettings else onRetryRequest,
                colors = ButtonDefaults.buttonColors(
                    containerColor = StudioCinemaAmber,
                    contentColor = StudioObsidian
                ),
                shape = RoundedCornerShape(50),
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .testTag(
                        if (isPermanentlyDenied) {
                            "camera_permission_settings_button"
                        } else {
                            "camera_permission_retry_button"
                        }
                    )
            ) {
                Text(
                    text = stringResource(
                        if (isPermanentlyDenied) {
                            R.string.action_open_settings
                        } else {
                            R.string.action_allow_camera
                        }
                    ),
                    fontWeight = FontWeight.SemiBold
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .testTag("camera_permission_dismiss_button")
            ) {
                Text(
                    text = stringResource(R.string.action_not_now),
                    color = StudioTextSecondary
                )
            }
        }
    )
}

@Composable
private fun ImmersiveCanvasStage(
    uiState: EditorUiState,
    activeTexture: android.graphics.Bitmap?,
    canReset: Boolean,
    canShowTouchAdjust: Boolean,
    isTouchAdjustPillVisible: Boolean,
    applyTopStatusBarPadding: Boolean,
    viewModel: EditorViewModel,
    onZoomChanged: (Boolean) -> Unit,
    onCapturePhoto: () -> Unit,
    onOpenPhoto: () -> Unit,
    onOpenRawFile: () -> Unit,
    onQuickShare: () -> Unit,
    onOpenExport: () -> Unit,
    modifier: Modifier = Modifier
) {
    val frameShape = RoundedCornerShape(16.dp)
    val topSystemInset = if (applyTopStatusBarPadding) {
        WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()
    } else {
        0.dp
    }
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
            showHighlightClipping = uiState.showHighlightClipping,
            showShadowClipping = uiState.showShadowClipping,
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
            compositionGridMode = uiState.compositionGridMode,
            spiralOrientation = uiState.spiralOrientation,
            activeMask = uiState.activeMask,
            activeSubMask = uiState.activeSubMask,
            showMaskOverlay = uiState.showMaskOverlay,
            maskBrushRadius = uiState.maskBrushRadius,
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
            onToggleHighlightClipping = viewModel::toggleHighlightClipping,
            onToggleShadowClipping = viewModel::toggleShadowClipping,
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
            onUpdateSubMask = { maskId, subId, fn -> viewModel.updateSubMask(maskId, subId, false, fn) },
            onStartMaskBrushStroke = viewModel::startMaskBrushStroke,
            onAppendMaskBrushStroke = viewModel::appendMaskBrushStroke,
            onFinishMaskBrushStroke = viewModel::finishMaskBrushStroke,
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
            topOverlayInset = 64.dp + topSystemInset,
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
            onCapturePhoto = onCapturePhoto,
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
                .then(
                    if (applyTopStatusBarPadding) {
                        Modifier
                            .statusBarsPadding()
                            .displayCutoutPadding()
                    } else {
                        Modifier
                    }
                )
                .padding(horizontal = 8.dp, vertical = 8.dp)
        )

        // Unified OLED Floating Utility Pill centered at bottom of photo viewport:
        // Touch Adjust, Histogram, Info HUD, Mask Overlay, Grid Toggle + Copy/Paste/Reset
        var isGridSelectorOpen by remember { mutableStateOf(false) }
        var showPhotoInfoHud by remember { mutableStateOf(false) }
        val haptic = LocalHapticFeedback.current
        LaunchedEffect(isTouchAdjustPillVisible) {
            if (isTouchAdjustPillVisible) {
                isGridSelectorOpen = false
                showPhotoInfoHud = false
            }
        }
        val activeGridMode = if (uiState.activeTab == StudioTab.CROP) {
            uiState.effectiveCropGridMode
        } else {
            uiState.compositionGridMode
        }
        val hasMasksOrMaskTab = uiState.activeTab == StudioTab.MASK || uiState.gradingParams.masks.isNotEmpty()
        val isMaskOverlayHighlighted = uiState.showMaskOverlay && hasMasksOrMaskTab

        AnimatedVisibility(
            visible = !isTouchAdjustPillVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 10.dp, end = 10.dp, bottom = 10.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AnimatedVisibility(
                    visible = isGridSelectorOpen,
                    enter = fadeIn() + slideInVertically { it / 2 },
                    exit = fadeOut() + slideOutVertically { it / 2 }
                ) {
                    CompositionGridSelectorPill(
                        selectedMode = activeGridMode,
                        spiralOrientation = uiState.spiralOrientation,
                        onSelectMode = { mode ->
                            viewModel.setCompositionGridMode(mode)
                            if (mode == CompositionGridMode.OFF) {
                                isGridSelectorOpen = false
                            }
                        },
                        onFlipSpiral = viewModel::flipSpiralOrientation
                    )
                }

                AnimatedVisibility(
                    visible = showPhotoInfoHud && !isGridSelectorOpen,
                    enter = fadeIn() + slideInVertically { it / 2 },
                    exit = fadeOut() + slideOutVertically { it / 2 }
                ) {
                    PhotoInfoHudPill(
                        fullWidth = uiState.fullWidth,
                        fullHeight = uiState.fullHeight,
                        photoFormatInfo = uiState.photoFormatInfo,
                        appliedUpscaleFactor = uiState.appliedUpscaleFactor,
                        maskCount = uiState.gradingParams.masks.size,
                        activeLutTitle = uiState.activeLut?.title
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (activeGridMode == CompositionGridMode.GOLDEN_SPIRAL && !isGridSelectorOpen) {
                        SpiralFlipPillButton(
                            orientation = uiState.spiralOrientation,
                            onFlip = viewModel::flipSpiralOrientation,
                            testTag = "floating_spiral_flip_button"
                        )
                    }

                    CornerUtilityPill(
                        canShowTouchAdjust = canShowTouchAdjust,
                        isTouchAdjustEnabled = uiState.isTouchAdjustEnabled,
                        canShowHistogram = !uiState.isSplitCompareEnabled && uiState.histogram != null,
                        isHistogramVisible = uiState.showHistogram,
                        isPhotoInfoVisible = showPhotoInfoHud,
                        isMaskOverlayActive = isMaskOverlayHighlighted,
                        canToggleMaskOverlay = hasMasksOrMaskTab,
                        isCompositionGridActive = activeGridMode != CompositionGridMode.OFF,
                        isGridSelectorOpen = isGridSelectorOpen,
                        canPasteEdits = uiState.canPasteEdits,
                        canReset = canReset,
                        onToggleTouchAdjust = viewModel::toggleTouchAdjust,
                        onToggleHistogram = viewModel::toggleHistogram,
                        onTogglePhotoInfo = {
                            showPhotoInfoHud = !showPhotoInfoHud
                            if (showPhotoInfoHud) isGridSelectorOpen = false
                        },
                        onToggleMaskOverlay = viewModel::toggleShowMaskOverlay,
                        onToggleGridSelector = {
                            isGridSelectorOpen = !isGridSelectorOpen
                            if (isGridSelectorOpen) showPhotoInfoHud = false
                        },
                        onLongPressGridClear = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.clearCompositionGrid()
                            isGridSelectorOpen = false
                        },
                        onCopyEdits = viewModel::copyEdits,
                        onPasteEdits = viewModel::pasteEdits,
                        onResetAll = viewModel::resetAllAdjustments
                    )
                }
            }
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
    onCapturePhoto: () -> Unit,
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
            val useCompactScroll = maxWidth < 486.dp
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
                // Left Elevated Graphite Pill: Z-Effect Emblem + Camera Capture + Gallery Picker + RAW / File Import
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
                            onClick = onCapturePhoto,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("camera_capture_button")
                        ) {
                            Icon(
                                imageVector = StudioIcons.CameraCapture,
                                contentDescription = stringResource(R.string.cd_capture_photo),
                                tint = StudioCoolWhite,
                                modifier = Modifier.size(23.dp)
                            )
                        }

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
 * Compact OLED Photo Info HUD pill displayed directly above the floating utility pill
 * when the user taps the Info icon.
 */
@Composable
private fun PhotoInfoHudPill(
    fullWidth: Int,
    fullHeight: Int,
    photoFormatInfo: PhotoFormatInfo,
    appliedUpscaleFactor: Int,
    maskCount: Int,
    activeLutTitle: String?,
    modifier: Modifier = Modifier
) {
    val dimsText = if (fullWidth > 0 && fullHeight > 0) "${fullWidth}×${fullHeight}" else "—"
    Surface(
        shape = RoundedCornerShape(50),
        color = StudioGlassSurface,
        shadowElevation = 8.dp,
        modifier = modifier
            .border(1.dp, StudioGlassHairline, RoundedCornerShape(50))
            .testTag("photo_info_hud_pill")
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val formatLabel = photoFormatInfo.badgeLabel?.takeIf { it.isNotBlank() } ?: "PHOTO"
            Text(
                text = formatLabel,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                ),
                color = StudioCinemaAmber,
                maxLines = 1,
                softWrap = false
            )
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(11.dp)
                    .background(StudioGlassHairline)
            )
            Text(
                text = dimsText,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    fontFeatureSettings = "tnum"
                ),
                color = StudioCoolWhite,
                maxLines = 1,
                softWrap = false
            )
            if (appliedUpscaleFactor > 1) {
                Text(
                    text = "${appliedUpscaleFactor}× AI",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    ),
                    color = StudioCinemaAmber,
                    maxLines = 1,
                    softWrap = false
                )
            }
            if (maskCount > 0) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(11.dp)
                        .background(StudioGlassHairline)
                )
                Text(
                    text = stringResource(R.string.info_hud_masks_count, maskCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = StudioMutedSlate,
                    maxLines = 1,
                    softWrap = false
                )
            }
            if (!activeLutTitle.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(11.dp)
                        .background(StudioGlassHairline)
                )
                Text(
                    text = activeLutTitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = StudioMutedSlate,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Unified OLED dark-charcoal (#121214) floating rounded utility pill directly below the photo viewport:
 * Touch Adjust, Histogram, Info HUD, Mask Overlay, Composition Grid toggle, plus Copy/Paste/Reset.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CornerUtilityPill(
    canShowTouchAdjust: Boolean,
    isTouchAdjustEnabled: Boolean,
    canShowHistogram: Boolean,
    isHistogramVisible: Boolean,
    isPhotoInfoVisible: Boolean,
    isMaskOverlayActive: Boolean,
    canToggleMaskOverlay: Boolean,
    isCompositionGridActive: Boolean,
    isGridSelectorOpen: Boolean,
    canPasteEdits: Boolean,
    canReset: Boolean,
    onToggleTouchAdjust: () -> Unit,
    onToggleHistogram: () -> Unit,
    onTogglePhotoInfo: () -> Unit,
    onToggleMaskOverlay: () -> Unit,
    onToggleGridSelector: () -> Unit,
    onLongPressGridClear: () -> Unit,
    onCopyEdits: () -> Unit,
    onPasteEdits: () -> Unit,
    onResetAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = StudioGlassSurface,
        shadowElevation = 8.dp,
        modifier = modifier
            .border(1.dp, StudioGlassHairline, RoundedCornerShape(50))
            .testTag("floating_utility_pill")
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp, vertical = 2.dp),
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
            }

            if (canShowHistogram) {
                CornerPillIconButton(
                    icon = StudioIcons.HistogramBars,
                    contentDescription = stringResource(R.string.cd_toggle_histogram),
                    active = isHistogramVisible,
                    showGoldDot = isHistogramVisible,
                    enabled = true,
                    testTag = "histogram_toggle_button",
                    onClick = onToggleHistogram
                )
            }

            CornerPillIconButton(
                icon = Icons.Outlined.Info,
                contentDescription = stringResource(R.string.cd_toggle_photo_info),
                active = isPhotoInfoVisible,
                showGoldDot = isPhotoInfoVisible,
                enabled = true,
                testTag = "photo_info_toggle_button",
                onClick = onTogglePhotoInfo
            )

            CornerPillIconButton(
                icon = StudioIcons.TabMasks,
                contentDescription = stringResource(R.string.cd_toggle_mask_overlay_quick),
                active = isMaskOverlayActive,
                showGoldDot = isMaskOverlayActive,
                enabled = canToggleMaskOverlay,
                testTag = "mask_overlay_quick_toggle_button",
                onClick = onToggleMaskOverlay
            )

            CornerPillIconButton(
                icon = StudioIcons.CompositionGrid,
                contentDescription = stringResource(R.string.cd_composition_grid_toggle),
                active = isCompositionGridActive || isGridSelectorOpen,
                showGoldDot = isCompositionGridActive,
                enabled = true,
                testTag = "composition_grid_button",
                onClick = onToggleGridSelector,
                onLongClick = onLongPressGridClear
            )

            Box(
                modifier = Modifier
                    .padding(horizontal = 2.dp)
                    .width(1.dp)
                    .height(15.dp)
                    .background(StudioGlassHairline)
            )

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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CornerPillIconButton(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean,
    showGoldDot: Boolean,
    enabled: Boolean,
    testTag: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val tint = when {
        !enabled -> StudioTextMuted.copy(alpha = 0.42f)
        active -> StudioCinemaAmber
        else -> StudioTextSecondary
    }
    val clickModifier = if (onLongClick != null) {
        Modifier.combinedClickable(
            enabled = enabled,
            onClick = onClick,
            onLongClick = onLongClick
        )
    } else {
        Modifier.clickable(enabled = enabled, onClick = onClick)
    }
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(if (active && enabled) StudioAmberPillFill else Color.Transparent)
            .then(clickModifier)
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(18.dp)
        )
        if (showGoldDot && enabled) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 7.dp)
                    .size(3.5.dp)
                    .clip(CircleShape)
                    .background(StudioCinemaAmber)
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

        StudioTab.UPSCALE -> UpscalePanel(
            selectedScale = uiState.upscaleScale,
            currentWidth = uiState.fullWidth,
            currentHeight = uiState.fullHeight,
            isProcessing = uiState.isUpscaling,
            progress = uiState.upscaleProgress,
            isAiModelAvailable = uiState.isUpscaleAiModelAvailable,
            onSelectScale = viewModel::selectUpscaleScale,
            onApplyUpscale = viewModel::applyUpscale
        )

        StudioTab.DENOISE -> DenoisePanel(
            params = uiState.gradingParams,
            isComparingOriginal = uiState.isComparingOriginal,
            isSplitCompareEnabled = uiState.isSplitCompareEnabled,
            isProcessing = uiState.isDenoiseProcessing,
            progress = uiState.denoiseProgress,
            onSelectPreset = viewModel::applyDenoisePreset,
            onParamsChange = viewModel::updateGradingParams,
            onDraggingChanged = viewModel::setSliderDragging,
            onCompareChanged = viewModel::setComparingOriginal,
            onToggleSplitCompare = viewModel::toggleSplitCompare
        )

        StudioTab.LUTS -> LutPanel(
            folders = uiState.lutFolders,
            selectedLutId = uiState.selectedLutId,
            activeLut = uiState.activeLut,
            lutIntensity = uiState.gradingParams.lutIntensity,
            sourcePreviewBitmap = uiState.previewIdleBitmap,
            userPresets = uiState.userPresets,
            userPresetThumbnails = uiState.userPresetThumbnails,
            selectedUserPresetId = uiState.selectedUserPresetId,
            onSelectFolder = viewModel::selectLutFolder,
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
            compositionGridMode = uiState.effectiveCropGridMode,
            spiralOrientation = uiState.spiralOrientation,
            onOverlayModeChange = viewModel::setCropOverlayMode,
            onSelectCompositionGrid = viewModel::setCompositionGridMode,
            onFlipSpiralOrientation = viewModel::flipSpiralOrientation,
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
            activeSubMask = uiState.activeSubMask,
            showMaskOverlay = uiState.showMaskOverlay,
            activeEyedropperMode = uiState.activeEyedropperMode,
            maskBrushRadius = uiState.maskBrushRadius,
            maskBrushHardness = uiState.maskBrushHardness,
            maskBrushFlow = uiState.maskBrushFlow,
            onAddMask = viewModel::addLocalMask,
            onSelectMask = { viewModel.selectMask(it) },
            onSelectSubMask = { viewModel.selectSubMask(it) },
            onAddSubMask = viewModel::addSubMask,
            onDeleteSubMask = viewModel::deleteSubMask,
            onSetSubMaskOp = viewModel::setSubMaskBooleanOp,
            onUpdateSubMask = { maskId, subId, fn -> viewModel.updateSubMask(maskId, subId, false, fn) },
            onClearBrushStrokes = viewModel::clearBrushStrokes,
            onUpdateBrushSettings = viewModel::updateMaskBrushSettings,
            onToggleColorRangeEyedropper = {
                viewModel.toggleEyedropperMode(com.zamalfajji.zamlab.editor.EyedropperMode.MASK_COLOR_RANGE)
            },
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
 * Compact 8-tab floating frosted-glass capsule bottom navigation:
 * Adjust | All | Upscale | Denoise | LUTs | Crop | Heal | Masks.
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
    val hasUpscaleEdits = uiState.hasUpscaleEdits
    val hasDenoiseEdits = gp.hasDenoiseEdits
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
            val useHorizontalScroll = maxWidth < 520.dp
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
                    label = stringResource(R.string.tab_upscale),
                    selectedIcon = StudioIcons.TabUpscale,
                    unselectedIcon = StudioIcons.TabUpscale,
                    selected = uiState.activeTab == StudioTab.UPSCALE,
                    hasEdits = hasUpscaleEdits,
                    testTag = "tab_upscale",
                    onClick = { onSelectTab(StudioTab.UPSCALE) },
                    modifier = itemModifier
                )
                CapsuleNavTabItem(
                    label = stringResource(R.string.tab_denoise),
                    selectedIcon = StudioIcons.TabDenoise,
                    unselectedIcon = StudioIcons.TabDenoise,
                    selected = uiState.activeTab == StudioTab.DENOISE,
                    hasEdits = hasDenoiseEdits,
                    testTag = "tab_denoise",
                    onClick = { onSelectTab(StudioTab.DENOISE) },
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
