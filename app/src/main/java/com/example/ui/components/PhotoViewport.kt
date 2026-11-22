package com.example.ui.components

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.TextureView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.R
import com.example.editor.CropAspectPreset
import com.example.editor.CropOverlayMode
import com.example.editor.EyedropperMode
import com.example.editor.GeometryMath
import com.example.editor.GradingParams
import com.example.editor.HealMode
import com.example.editor.HealStroke
import com.example.editor.HistogramData
import com.example.editor.HslBand
import com.example.editor.HslBandAdjustment
import com.example.editor.LocalMask
import com.example.editor.MaskType
import com.example.editor.NormalizedCropRect
import com.example.editor.StudioTab
import com.example.gl.GlPhotoRenderController
import com.example.lut.CubeLut
import com.example.ui.theme.StudioAmber
import com.example.ui.theme.StudioBackground
import com.example.ui.theme.StudioBorder
import com.example.ui.theme.StudioCyan
import com.example.ui.theme.StudioEraserCrimson
import com.example.ui.theme.StudioEraserCrimsonSoft
import com.example.ui.theme.StudioGlassHairline
import com.example.ui.theme.StudioGlassSurface
import com.example.ui.theme.StudioGraphite
import com.example.ui.theme.StudioHairline
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioSurfaceElevated
import com.example.ui.theme.StudioTextPrimary
import com.example.ui.theme.StudioTextSecondary
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun PhotoViewport(
    previewBitmap: Bitmap?,
    fullWidth: Int,
    fullHeight: Int,
    gradingParams: GradingParams,
    activeLut: CubeLut?,
    activeTab: StudioTab,
    isComparingOriginal: Boolean,
    isSplitCompareEnabled: Boolean,
    splitCompareRatio: Float,
    showHistogram: Boolean,
    histogram: HistogramData?,
    healMode: HealMode = HealMode.SPOT_HEAL,
    healStrokes: List<HealStroke> = emptyList(),
    activeHealStroke: HealStroke? = null,
    healBrushRadiusNorm: Float = 0.042f,
    healFeather: Float = 0.50f,
    showHealMask: Boolean = false,
    cloneSourcePoint: Pair<Float, Float>? = null,
    isPickingCloneSource: Boolean = false,
    isHealProcessing: Boolean = false,
    activeEyedropperMode: EyedropperMode = EyedropperMode.NONE,
    cropOverlayMode: CropOverlayMode = CropOverlayMode.THIRDS,
    activeMask: LocalMask? = null,
    showMaskOverlay: Boolean = true,
    viewportResetToken: Long = 0L,
    isTouchAdjustEnabled: Boolean = false,
    touchAdjustSelectedBand: HslBand? = null,
    touchAdjustSwatchArgb: Long? = null,
    touchAdjustBandAdjustment: HslBandAdjustment = HslBandAdjustment(),
    touchAdjustHudLabel: String? = null,
    touchAdjustHudValueText: String? = null,
    isTouchAdjustSwiping: Boolean = false,
    onZoomChanged: (Boolean) -> Unit = {},
    onCompareChanged: (Boolean) -> Unit,
    onSplitRatioChange: (Float) -> Unit,
    onToggleHistogram: () -> Unit,
    onStartHealStroke: (Float, Float) -> Unit = { _, _ -> },
    onAppendHealStroke: (Float, Float) -> Unit = { _, _ -> },
    onFinishHealStroke: () -> Unit = {},
    onCancelHealStroke: () -> Unit = {},
    onSetCloneSource: (Float, Float) -> Unit = { _, _ -> },
    onEyedropperPick: (Float, Float) -> Unit = { _, _ -> },
    onCancelEyedropper: () -> Unit = {},
    onCropRectChange: (NormalizedCropRect) -> Unit = {},
    onCropDragStateChanged: (Boolean) -> Unit = {},
    onUpdateMask: (String, (LocalMask) -> LocalMask) -> Unit = { _, _ -> },
    onMaskDragStateChanged: (Boolean) -> Unit = {},
    onToggleTouchAdjust: () -> Unit = {},
    onTouchAdjustTap: (Float, Float) -> Unit = { _, _ -> },
    onTouchAdjustSwipeStart: (Float, Float) -> Unit = { _, _ -> },
    onTouchAdjustSwipeUpdate: (Boolean, Float) -> Unit = { _, _ -> },
    onTouchAdjustSwipeEnd: (Boolean) -> Unit = {},
    onUpdateTouchAdjustBand: ((HslBandAdjustment) -> HslBandAdjustment) -> Unit = {},
    onResetTouchAdjustBand: () -> Unit = {},
    onDismissTouchAdjustPill: () -> Unit = {},
    onTouchAdjustSliderDraggingChanged: (Boolean) -> Unit = {},
    topOverlayInset: Dp = 12.dp,
    bottomOverlayInset: Dp = 12.dp,
    modifier: Modifier = Modifier
) {
    var zoomScale by remember { mutableFloatStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }
    var brushCursorNormalized by remember { mutableStateOf<Offset?>(null) }
    var zoomAnimJob by remember { mutableStateOf<Job?>(null) }
    var pendingSingleTapJob by remember { mutableStateOf<Job?>(null) }
    var lastTapUpTimeMs by remember { mutableLongStateOf(0L) }
    var lastTapUpPos by remember { mutableStateOf(Offset.Zero) }

    val currentOnZoomChanged by rememberUpdatedState(onZoomChanged)
    // Defer zoomScale read inside derivedStateOf so PhotoViewport never recomposes on pinch/pan ticks
    val isZoomedFlag by remember { derivedStateOf { zoomScale > 1.05f } }
    LaunchedEffect(isZoomedFlag) {
        currentOnZoomChanged(isZoomedFlag)
    }

    LaunchedEffect(viewportResetToken) {
        zoomAnimJob?.cancel()
        zoomAnimJob = null
        pendingSingleTapJob?.cancel()
        pendingSingleTapJob = null
        zoomScale = 1f
        panOffset = Offset.Zero
    }

    LaunchedEffect(activeTab) {
        if (activeTab == StudioTab.CROP) {
            zoomAnimJob?.cancel()
            zoomAnimJob = null
            pendingSingleTapJob?.cancel()
            pendingSingleTapJob = null
            zoomScale = 1f
            panOffset = Offset.Zero
        }
    }

    LaunchedEffect(isTouchAdjustEnabled) {
        if (!isTouchAdjustEnabled) {
            pendingSingleTapJob?.cancel()
            pendingSingleTapJob = null
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            pendingSingleTapJob?.cancel()
            pendingSingleTapJob = null
        }
    }

    val density = LocalDensity.current
    val canvasDescription = stringResource(R.string.cd_photo_canvas)
    val gestureScope = rememberCoroutineScope()
    val currentCompareCallback by rememberUpdatedState(onCompareChanged)
    val currentSplitRatio by rememberUpdatedState(splitCompareRatio)
    val currentOnSplitRatioChange by rememberUpdatedState(onSplitRatioChange)
    val currentEyedropperPick by rememberUpdatedState(onEyedropperPick)
    val currentStartHealStroke by rememberUpdatedState(onStartHealStroke)
    val currentAppendHealStroke by rememberUpdatedState(onAppendHealStroke)
    val currentFinishHealStroke by rememberUpdatedState(onFinishHealStroke)
    val currentCancelHealStroke by rememberUpdatedState(onCancelHealStroke)
    val currentSetCloneSource by rememberUpdatedState(onSetCloneSource)
    val currentHealMode by rememberUpdatedState(healMode)
    val currentIsPickingCloneSource by rememberUpdatedState(isPickingCloneSource)
    val currentIsHealProcessing by rememberUpdatedState(isHealProcessing)
    val currentIsTouchAdjustEnabled by rememberUpdatedState(isTouchAdjustEnabled)
    val currentOnTouchAdjustTap by rememberUpdatedState(onTouchAdjustTap)
    val currentOnTouchAdjustSwipeStart by rememberUpdatedState(onTouchAdjustSwipeStart)
    val currentOnTouchAdjustSwipeUpdate by rememberUpdatedState(onTouchAdjustSwipeUpdate)
    val currentOnTouchAdjustSwipeEnd by rememberUpdatedState(onTouchAdjustSwipeEnd)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(StudioBackground)
            .clipToBounds()
    ) {
        val viewportW = with(density) { maxWidth.toPx() }
        val viewportH = with(density) { maxHeight.toPx() }

        if (previewBitmap == null || previewBitmap.isRecycled) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = StudioAmber)
            }
            return@BoxWithConstraints
        }

        // Source pixel dimensions (rock-steady between 640px drag vs 1280px idle textures)
        val srcW = (if (fullWidth > 0) fullWidth else previewBitmap.width).coerceAtLeast(1)
        val srcH = (if (fullHeight > 0) fullHeight else previewBitmap.height).coerceAtLeast(1)
        val isCropMode = activeTab == StudioTab.CROP
        val applyCropInShader = !isCropMode

        val (frameW, frameH) = if (isCropMode) {
            GeometryMath.orientedDimensions(srcW, srcH, gradingParams.crop)
        } else {
            GeometryMath.computeOutputDimensions(srcW, srcH, gradingParams.crop)
        }
        val bmpW = frameW.toFloat()
        val bmpH = frameH.toFloat()
        val fitScale = min(viewportW / bmpW, viewportH / bmpH) * if (isCropMode) 0.92f else 0.96f
        val fittedW = bmpW * fitScale
        val fittedH = bmpH * fitScale
        val imgLeft = (viewportW - fittedW) / 2f
        val imgTop = (viewportH - fittedH) / 2f

        val latestViewportW by rememberUpdatedState(viewportW)
        val latestViewportH by rememberUpdatedState(viewportH)
        val latestFittedW by rememberUpdatedState(fittedW)
        val latestFittedH by rememberUpdatedState(fittedH)
        val latestImgLeft by rememberUpdatedState(imgLeft)
        val latestImgTop by rememberUpdatedState(imgTop)

        LaunchedEffect(viewportW, viewportH, fittedW, fittedH) {
            val clamped = ViewportZoomMath.clampPan(
                pan = panOffset,
                zoom = zoomScale,
                viewportW = viewportW,
                viewportH = viewportH,
                fittedW = fittedW,
                fittedH = fittedH
            )
            if (clamped != panOffset) {
                panOffset = clamped
            }
        }

        val isHealMode = activeTab == StudioTab.HEAL
        val isMaskMode = activeTab == StudioTab.MASK
        val isEyedropperActive = activeEyedropperMode != EyedropperMode.NONE && !isHealMode && !isCropMode
        val effectiveSplitRatio = if (isSplitCompareEnabled && !isComparingOriginal && !isCropMode) splitCompareRatio else -1f

        val gestureModifier = if (isCropMode) {
            Modifier
        } else if (isHealMode) {
            // Supports 1-finger Heal brush painting + long-press Clone Source picking + simultaneous 2-finger pinch/pan
            Modifier.pointerInput(Unit) {
                val touchSlop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val firstDown = awaitFirstDown(requireUnconsumed = false)
                    zoomAnimJob?.cancel()
                    zoomAnimJob = null

                    val downPosition = firstDown.position
                    var hadMultiTouch = false
                    var strokeStarted = false
                    var holdSetSourceTriggered = false
                    var holdJob: Job? = null

                    fun toNorm(pos: Offset): Offset = viewportToNormalized(
                        pos = pos,
                        viewportW = latestViewportW,
                        viewportH = latestViewportH,
                        imgLeft = latestImgLeft,
                        imgTop = latestImgTop,
                        fittedW = latestFittedW,
                        fittedH = latestFittedH,
                        zoom = zoomScale,
                        pan = panOffset
                    )

                    val pickingCloneAtDown = currentIsPickingCloneSource
                    val initialNorm = toNorm(downPosition)
                    if (!pickingCloneAtDown && !currentIsHealProcessing) {
                        brushCursorNormalized = initialNorm
                        if (currentHealMode == HealMode.CLONE_STAMP) {
                            holdJob = gestureScope.launch {
                                delay(420L)
                                if (!hadMultiTouch && !strokeStarted) {
                                    holdSetSourceTriggered = true
                                    brushCursorNormalized = null
                                    currentSetCloneSource(initialNorm.x, initialNorm.y)
                                }
                            }
                        }
                    }

                    try {
                        do {
                            val event = awaitPointerEvent()
                            val pressedChanges = event.changes.filter { it.pressed }
                            val activePointers = pressedChanges.size

                            if (activePointers >= 2) {
                                hadMultiTouch = true
                                holdJob?.cancel()
                                holdJob = null
                                if (strokeStarted) {
                                    strokeStarted = false
                                    currentCancelHealStroke()
                                }
                                brushCursorNormalized = null

                                val zoomChange = event.calculateZoom()
                                val panChange = event.calculatePan()
                                val centroid = event.calculateCentroid(useCurrent = true)
                                val (nextZoom, nextPan) = ViewportZoomMath.applyPinchAndPan(
                                    currentZoom = zoomScale,
                                    currentPan = panOffset,
                                    zoomChange = zoomChange,
                                    panChange = panChange,
                                    focalPoint = centroid,
                                    viewportW = latestViewportW,
                                    viewportH = latestViewportH,
                                    fittedW = latestFittedW,
                                    fittedH = latestFittedH
                                )
                                zoomScale = nextZoom
                                panOffset = nextPan
                                event.changes.forEach { it.consume() }
                            } else if (activePointers == 1 && !hadMultiTouch && !pickingCloneAtDown && !holdSetSourceTriggered && !currentIsHealProcessing) {
                                val change = pressedChanges.first()
                                val norm = toNorm(change.position)
                                brushCursorNormalized = norm
                                val dist = (change.position - downPosition).getDistance()
                                if (!strokeStarted && dist > touchSlop * 0.45f) {
                                    holdJob?.cancel()
                                    holdJob = null
                                    strokeStarted = true
                                    currentStartHealStroke(initialNorm.x, initialNorm.y)
                                    currentAppendHealStroke(norm.x, norm.y)
                                } else if (strokeStarted) {
                                    currentAppendHealStroke(norm.x, norm.y)
                                }
                                change.consume()
                            }
                        } while (event.changes.any { it.pressed })
                    } finally {
                        holdJob?.cancel()
                        brushCursorNormalized = null
                        if (zoomScale <= 1.02f && zoomAnimJob == null) {
                            zoomScale = 1f
                            panOffset = Offset.Zero
                        }
                        if (!hadMultiTouch && !currentIsHealProcessing) {
                            if (pickingCloneAtDown) {
                                currentSetCloneSource(initialNorm.x, initialNorm.y)
                            } else if (holdSetSourceTriggered) {
                                // Already set Clone Source via stationary long-press
                            } else if (strokeStarted) {
                                currentFinishHealStroke()
                            } else {
                                // Single tap-to-heal spot at initialNorm
                                currentStartHealStroke(initialNorm.x, initialNorm.y)
                                currentFinishHealStroke()
                            }
                        } else if (strokeStarted) {
                            currentCancelHealStroke()
                        }
                    }
                }
            }
        } else if (isEyedropperActive) {
            Modifier.pointerInput(activeEyedropperMode) {
                detectTapGestures(
                    onTap = { tapPos ->
                        val norm = viewportToNormalized(
                            pos = tapPos,
                            viewportW = latestViewportW,
                            viewportH = latestViewportH,
                            imgLeft = latestImgLeft,
                            imgTop = latestImgTop,
                            fittedW = latestFittedW,
                            fittedH = latestFittedH,
                            zoom = zoomScale,
                            pan = panOffset
                        )
                        currentEyedropperPick(norm.x, norm.y)
                    }
                )
            }
        } else {
            Modifier.pointerInput(isSplitCompareEnabled, isTouchAdjustEnabled) {
                val touchSlop = viewConfiguration.touchSlop
                val doubleTapTimeout = viewConfiguration.doubleTapTimeoutMillis
                val doubleTapSlop = touchSlop * 6f
                awaitEachGesture {
                    val firstDown = awaitFirstDown(requireUnconsumed = false)
                    zoomAnimJob?.cancel()
                    zoomAnimJob = null
                    pendingSingleTapJob?.cancel()
                    pendingSingleTapJob = null

                    val downPosition = firstDown.position
                    val downTimeMs = SystemClock.uptimeMillis()
                    val isSecondTap = (downTimeMs - lastTapUpTimeMs) <= doubleTapTimeout &&
                        (downPosition - lastTapUpPos).getDistance() <= doubleTapSlop

                    val touchAdjustAtDown = currentIsTouchAdjustEnabled
                    val touchAdjustSwipeAtDown = touchAdjustAtDown && zoomScale <= 1.01f
                    var isHoldComparing = false
                    var holdCancelled = isSecondTap || touchAdjustAtDown
                    var movedPastSlop = false
                    var maxPointers = 1
                    var touchSwipeActive = false
                    var touchSwipeIsVertical = true

                    val holdJob: Job? = if (!isSecondTap && !touchAdjustAtDown) {
                        gestureScope.launch {
                            delay(190L)
                            if (!holdCancelled) {
                                isHoldComparing = true
                                currentCompareCallback(true)
                            }
                        }
                    } else {
                        null
                    }

                    try {
                        do {
                            val event = awaitPointerEvent()
                            if (event.changes.any { it.isConsumed }) {
                                if (!holdCancelled) {
                                    holdCancelled = true
                                    holdJob?.cancel()
                                    if (isHoldComparing) {
                                        isHoldComparing = false
                                        currentCompareCallback(false)
                                    }
                                }
                                continue
                            }

                            val pressedChanges = event.changes.filter { it.pressed }
                            val activePointers = pressedChanges.size
                            if (activePointers > maxPointers) {
                                maxPointers = activePointers
                            }

                            if (activePointers > 1) {
                                if (!holdCancelled) {
                                    holdCancelled = true
                                    holdJob?.cancel()
                                    if (isHoldComparing) {
                                        isHoldComparing = false
                                        currentCompareCallback(false)
                                    }
                                }
                                if (touchSwipeActive) {
                                    touchSwipeActive = false
                                    currentOnTouchAdjustSwipeEnd(false)
                                }
                            } else if (activePointers == 1 && !movedPastSlop) {
                                val dist = (pressedChanges.first().position - downPosition).getDistance()
                                if (dist > touchSlop) {
                                    movedPastSlop = true
                                    if (!holdCancelled) {
                                        holdCancelled = true
                                        holdJob?.cancel()
                                        if (isHoldComparing) {
                                            isHoldComparing = false
                                            currentCompareCallback(false)
                                        }
                                    }
                                }
                            }

                            if (touchAdjustSwipeAtDown && activePointers == 1 && maxPointers == 1 && movedPastSlop) {
                                val curPos = pressedChanges.first().position
                                val delta = curPos - downPosition
                                if (!touchSwipeActive) {
                                    touchSwipeIsVertical = abs(delta.y) >= abs(delta.x)
                                    touchSwipeActive = true
                                    val startNorm = viewportToNormalized(
                                        pos = downPosition,
                                        viewportW = latestViewportW,
                                        viewportH = latestViewportH,
                                        imgLeft = latestImgLeft,
                                        imgTop = latestImgTop,
                                        fittedW = latestFittedW,
                                        fittedH = latestFittedH,
                                        zoom = zoomScale,
                                        pan = panOffset
                                    )
                                    currentOnTouchAdjustSwipeStart(startNorm.x, startNorm.y)
                                }
                                val fraction = if (touchSwipeIsVertical) {
                                    -delta.y / max(latestFittedH * 0.55f, 180f)
                                } else {
                                    delta.x / max(latestFittedW * 0.55f, 180f)
                                }
                                currentOnTouchAdjustSwipeUpdate(touchSwipeIsVertical, fraction)
                                pressedChanges.forEach { it.consume() }
                            } else {
                                val zoomChange = event.calculateZoom()
                                val panChange = event.calculatePan()
                                val centroid = event.calculateCentroid(useCurrent = true)
                                val canTransform = activePointers > 1 ||
                                    (!touchAdjustSwipeAtDown && zoomScale > 1.01f && movedPastSlop)

                                if (canTransform && (zoomChange != 1f || panChange != Offset.Zero)) {
                                    val (nextZoom, nextPan) = ViewportZoomMath.applyPinchAndPan(
                                        currentZoom = zoomScale,
                                        currentPan = panOffset,
                                        zoomChange = if (activePointers > 1) zoomChange else 1f,
                                        panChange = panChange,
                                        focalPoint = centroid,
                                        viewportW = latestViewportW,
                                        viewportH = latestViewportH,
                                        fittedW = latestFittedW,
                                        fittedH = latestFittedH
                                    )
                                    zoomScale = nextZoom
                                    panOffset = nextPan
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        } while (event.changes.any { it.pressed })
                    } finally {
                        val wasCleanTap = !movedPastSlop && maxPointers == 1 && !isHoldComparing && !touchSwipeActive
                        holdCancelled = true
                        holdJob?.cancel()
                        if (isHoldComparing) {
                            currentCompareCallback(false)
                        }
                        if (touchSwipeActive) {
                            currentOnTouchAdjustSwipeEnd(maxPointers == 1)
                        }
                        if (zoomScale <= 1.02f && zoomAnimJob == null) {
                            zoomScale = 1f
                            panOffset = Offset.Zero
                        }
                        if (wasCleanTap) {
                            if (isSecondTap) {
                                lastTapUpTimeMs = 0L
                                val (targetZoom, targetPan) = ViewportZoomMath.computeDoubleTapTarget(
                                    currentZoom = zoomScale,
                                    tapPosition = downPosition,
                                    viewportW = latestViewportW,
                                    viewportH = latestViewportH,
                                    fittedW = latestFittedW,
                                    fittedH = latestFittedH
                                )
                                val startZoom = zoomScale
                                val startPan = panOffset
                                zoomAnimJob = gestureScope.launch {
                                    val progress = Animatable(0f)
                                    progress.animateTo(
                                        targetValue = 1f,
                                        animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing)
                                    ) {
                                        zoomScale = startZoom + (targetZoom - startZoom) * value
                                        panOffset = Offset(
                                            x = startPan.x + (targetPan.x - startPan.x) * value,
                                            y = startPan.y + (targetPan.y - startPan.y) * value
                                        )
                                    }
                                    zoomScale = targetZoom
                                    panOffset = targetPan
                                }
                            } else {
                                lastTapUpTimeMs = SystemClock.uptimeMillis()
                                lastTapUpPos = downPosition
                                if (touchAdjustAtDown) {
                                    val tapSnapshotZoom = zoomScale
                                    val tapSnapshotPan = panOffset
                                    pendingSingleTapJob = gestureScope.launch {
                                        delay(doubleTapTimeout)
                                        if (currentIsTouchAdjustEnabled) {
                                            val norm = viewportToNormalized(
                                                pos = downPosition,
                                                viewportW = latestViewportW,
                                                viewportH = latestViewportH,
                                                imgLeft = latestImgLeft,
                                                imgTop = latestImgTop,
                                                fittedW = latestFittedW,
                                                fittedH = latestFittedH,
                                                zoom = tapSnapshotZoom,
                                                pan = tapSnapshotPan
                                            )
                                            currentOnTouchAdjustTap(norm.x, norm.y)
                                        }
                                    }
                                }
                            }
                        } else {
                            lastTapUpTimeMs = 0L
                        }
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(gestureModifier)
                .graphicsLayer {
                    scaleX = zoomScale
                    scaleY = zoomScale
                    translationX = panOffset.x
                    translationY = panOffset.y
                }
                .semantics { contentDescription = canvasDescription }
                .testTag("photo_canvas")
        ) {
            var glController by remember { mutableStateOf<GlPhotoRenderController?>(null) }

            DisposableEffect(Unit) {
                onDispose {
                    glController?.release()
                }
            }

            SideEffect {
                glController?.updateState(
                    imageBitmap = previewBitmap,
                    params = gradingParams,
                    lut = activeLut,
                    isOriginal = isComparingOriginal,
                    splitRatio = effectiveSplitRatio,
                    applyCrop = applyCropInShader
                )
            }

            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(
                        width = with(density) { fittedW.toDp() },
                        height = with(density) { fittedH.toDp() }
                    )
            ) {
                AndroidView(
                    factory = { ctx ->
                        TextureView(ctx).also { tv ->
                            val controller = GlPhotoRenderController(tv)
                            controller.updateState(
                                imageBitmap = previewBitmap,
                                params = gradingParams,
                                lut = activeLut,
                                isOriginal = isComparingOriginal,
                                splitRatio = effectiveSplitRatio,
                                applyCrop = applyCropInShader
                            )
                            glController = controller
                        }
                    },
                    update = {
                        glController?.updateState(
                            imageBitmap = previewBitmap,
                            params = gradingParams,
                            lut = activeLut,
                            isOriginal = isComparingOriginal,
                            splitRatio = effectiveSplitRatio,
                            applyCrop = applyCropInShader
                        )
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Interactive crop box & composition overlays when Crop & Rotate tab is active
                if (isCropMode && !isComparingOriginal && fittedW > 1f && fittedH > 1f) {
                    val displayedCropRect = GeometryMath.effectiveCropRect(srcW, srcH, gradingParams.crop)
                    val targetPixelAspect = if (
                        gradingParams.crop.aspectLocked &&
                        gradingParams.crop.aspectPreset != CropAspectPreset.FREE
                    ) {
                        GeometryMath.resolveTargetAspectRatio(srcW, srcH, gradingParams.crop)
                    } else {
                        null
                    }
                    val orientAspect = GeometryMath.orientedAspect(srcW, srcH, gradingParams.crop)
                    val targetNormRatio = targetPixelAspect?.let { (it / orientAspect).coerceIn(0.05f, 20f) }

                    InteractiveCropOverlay(
                        cropRect = displayedCropRect,
                        overlayMode = cropOverlayMode,
                        targetNormRatio = targetNormRatio,
                        onCropRectChange = onCropRectChange,
                        onDragStateChanged = onCropDragStateChanged,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Interactive Radial / Linear Local Mask canvas handles & feather overlay
                if (isMaskMode && !isComparingOriginal && activeMask != null && fittedW > 1f && fittedH > 1f) {
                    InteractiveLocalMaskOverlay(
                        mask = activeMask,
                        showOverlayTint = showMaskOverlay,
                        onUpdateMask = onUpdateMask,
                        onDragStateChanged = onMaskDragStateChanged,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Interactive split-screen wipe divider locked directly to TextureView bounds
                if (isSplitCompareEnabled && !isComparingOriginal && !isCropMode && fittedW > 1f) {
                    val splitX = (splitCompareRatio * fittedW).roundToInt()
                    val handleSizePx = with(density) { 48.dp.toPx() }
                    Box(
                        modifier = Modifier
                            .offset { IntOffset((splitX - handleSizePx / 2f).roundToInt(), 0) }
                            .width(48.dp)
                            .fillMaxHeight()
                            .pointerInput(fittedW) {
                                detectHorizontalDragGestures { change, dragAmount ->
                                    change.consume()
                                    val nextRatio = (currentSplitRatio + (dragAmount / fittedW)).coerceIn(0.05f, 0.95f)
                                    currentOnSplitRatioChange(nextRatio)
                                }
                            }
                            .testTag("split_compare_handle"),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .width(2.dp)
                                .fillMaxHeight()
                                .background(Color.White.copy(alpha = 0.9f))
                        )
                        Surface(
                            shape = CircleShape,
                            color = StudioSurfaceElevated.copy(alpha = 0.94f),
                            shadowElevation = 6.dp,
                            modifier = Modifier
                                .size(34.dp)
                                .border(1.5.dp, StudioAmber, CircleShape)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Code,
                                    contentDescription = "Drag to compare before and after",
                                    tint = StudioAmber,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                // Heal mask strokes, Clone Stamp source crosshair, and live circular brush feather reticle
                if (isHealMode && !isComparingOriginal && fittedW > 1f && fittedH > 1f) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val drawW = size.width
                        val drawH = size.height
                        val minSide = min(drawW, drawH)

                        fun toViewportPoint(srcPt: Pair<Float, Float>): Pair<Float, Float>? {
                            return if (gradingParams.crop.isDefault) {
                                srcPt
                            } else {
                                GeometryMath.mapSourceUvToOutputUv(
                                    su = srcPt.first,
                                    sv = srcPt.second,
                                    srcW = srcW,
                                    srcH = srcH,
                                    crop = gradingParams.crop,
                                    applyCrop = applyCropInShader
                                )
                            }
                        }

                        // 1. Committed strokes overlay (when Show Mask is toggled on)
                        if (showHealMask && healStrokes.isNotEmpty()) {
                            val eff = GeometryMath.effectiveCropRect(srcW, srcH, gradingParams.crop)
                            val cropInvScale = 1f / max(eff.width, eff.height).coerceIn(0.15f, 1f)
                            for (stroke in healStrokes) {
                                val mappedPts = stroke.points.mapNotNull { toViewportPoint(it) }
                                if (mappedPts.isEmpty()) continue
                                val rPx = (stroke.radiusNorm * cropInvScale * minSide).coerceAtLeast(3f)
                                val strokeTint = when (stroke.mode) {
                                    HealMode.SPOT_HEAL -> StudioEraserCrimson.copy(alpha = 0.42f * stroke.opacity)
                                    HealMode.CLONE_STAMP -> StudioAmber.copy(alpha = 0.36f * stroke.opacity)
                                    HealMode.PATCH_SMOOTH -> StudioCyan.copy(alpha = 0.36f * stroke.opacity)
                                }
                                drawHealViewportStroke(mappedPts, rPx, strokeTint)
                            }
                        }

                        // 2. Active in-progress stroke
                        activeHealStroke?.let { stroke ->
                            if (stroke.points.isNotEmpty()) {
                                val rPx = (stroke.radiusNorm * minSide).coerceAtLeast(3f)
                                val activeTint = when (stroke.mode) {
                                    HealMode.SPOT_HEAL -> StudioEraserCrimsonSoft
                                    HealMode.CLONE_STAMP -> StudioAmber.copy(alpha = 0.42f)
                                    HealMode.PATCH_SMOOTH -> StudioCyan.copy(alpha = 0.42f)
                                }
                                drawHealViewportStroke(stroke.points, rPx, activeTint)
                            }
                        }

                        // 3. Clone Stamp source crosshair + dynamic sampling offset indicator
                        val mappedCloneSource = cloneSourcePoint?.let { toViewportPoint(it) }
                        if (healMode == HealMode.CLONE_STAMP && mappedCloneSource != null) {
                            val srcCenter = Offset(
                                mappedCloneSource.first * drawW,
                                mappedCloneSource.second * drawH
                            )
                            val activeCursor = brushCursorNormalized
                            val firstStrokePt = activeHealStroke?.points?.firstOrNull()
                            if (activeCursor != null && firstStrokePt != null) {
                                val dstX = activeCursor.x * drawW
                                val dstY = activeCursor.y * drawH
                                val trackingSrc = Offset(
                                    x = (srcCenter.x + (dstX - firstStrokePt.first * drawW)).coerceIn(0f, drawW),
                                    y = (srcCenter.y + (dstY - firstStrokePt.second * drawH)).coerceIn(0f, drawH)
                                )
                                drawLine(
                                    color = StudioAmber.copy(alpha = 0.65f),
                                    start = trackingSrc,
                                    end = Offset(dstX, dstY),
                                    strokeWidth = 1.2.dp.toPx(),
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
                                )
                                drawCloneSourceCrosshair(trackingSrc, 11.dp.toPx(), StudioAmber)
                            } else {
                                drawCloneSourceCrosshair(srcCenter, 12.dp.toPx(), StudioAmber)
                            }
                        }

                        // 4. Live circular brush reticle showing exact radius & feather inner core
                        brushCursorNormalized?.let { cursor ->
                            val cx = cursor.x * drawW
                            val cy = cursor.y * drawH
                            val rPx = (healBrushRadiusNorm * minSide).coerceAtLeast(3f)
                            val featherStop = (1f - healFeather.coerceIn(0f, 1f) * 0.85f).coerceIn(0.12f, 1f)
                            val cursorColor = when (healMode) {
                                HealMode.SPOT_HEAL -> StudioEraserCrimson
                                HealMode.CLONE_STAMP -> StudioAmber
                                HealMode.PATCH_SMOOTH -> StudioCyan
                            }
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colorStops = arrayOf(
                                        0f to cursorColor.copy(alpha = 0.16f),
                                        featherStop to cursorColor.copy(alpha = 0.12f),
                                        1f to Color.Transparent
                                    ),
                                    center = Offset(cx, cy),
                                    radius = rPx
                                ),
                                radius = rPx,
                                center = Offset(cx, cy)
                            )
                            drawCircle(
                                color = Color.White.copy(alpha = 0.92f),
                                radius = rPx,
                                center = Offset(cx, cy),
                                style = Stroke(width = 1.8.dp.toPx())
                            )
                            if (healFeather > 0.03f) {
                                val innerR = rPx * (1f - healFeather * 0.85f)
                                drawCircle(
                                    color = cursorColor.copy(alpha = 0.85f),
                                    radius = innerR,
                                    center = Offset(cx, cy),
                                    style = Stroke(
                                        width = 1.2.dp.toPx(),
                                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f), 0f)
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        // Top-left live RGB/Luma Histogram HUD (shown cleanly when toggled from floating utility pill)
        AnimatedVisibility(
            visible = showHistogram && histogram != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = topOverlayInset, start = 12.dp)
        ) {
            if (histogram != null) {
                HistogramHud(
                    histogram = histogram,
                    onClick = onToggleHistogram
                )
            }
        }

        // Top-right status badges: Original compare, Clone Source hint, Healing progress, or Zoom reset
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = topOverlayInset, end = 12.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AnimatedVisibility(
                visible = isComparingOriginal,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = StudioAmber,
                    shadowElevation = 4.dp
                ) {
                    Text(
                        text = "ORIGINAL",
                        style = MaterialTheme.typography.labelSmall,
                        color = StudioBackground,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }

            if (isHealMode && isPickingCloneSource) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = StudioAmber.copy(alpha = 0.95f),
                    shadowElevation = 4.dp
                ) {
                    Text(
                        text = "TAP TO SET SOURCE",
                        style = MaterialTheme.typography.labelSmall,
                        color = StudioBackground,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }

            if (isHealProcessing) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = StudioSurfaceElevated.copy(alpha = 0.94f),
                    modifier = Modifier.border(1.dp, StudioCyan, RoundedCornerShape(20.dp))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        CircularProgressIndicator(
                            color = StudioCyan,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "Healing…",
                            style = MaterialTheme.typography.labelSmall,
                            color = StudioTextPrimary
                        )
                    }
                }
            }

            ZoomResetBadge(
                visible = isZoomedFlag,
                zoomScaleProvider = { zoomScale },
                onResetZoom = {
                    zoomAnimJob?.cancel()
                    val startZoom = zoomScale
                    val startPan = panOffset
                    zoomAnimJob = gestureScope.launch {
                        val progress = Animatable(0f)
                        progress.animateTo(
                            targetValue = 1f,
                            animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)
                        ) {
                            zoomScale = startZoom + (1f - startZoom) * value
                            panOffset = Offset(
                                x = startPan.x * (1f - value),
                                y = startPan.y * (1f - value)
                            )
                        }
                        zoomScale = 1f
                        panOffset = Offset.Zero
                    }
                }
            )
        }

        // Bottom Before/After labels in split-screen mode
        if (isSplitCompareEnabled && !isComparingOriginal && !isCropMode) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = StudioSurface.copy(alpha = 0.82f),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 12.dp, bottom = bottomOverlayInset)
                    .border(1.dp, StudioBorder, RoundedCornerShape(16.dp))
            ) {
                Text(
                    text = "BEFORE",
                    style = MaterialTheme.typography.labelSmall,
                    color = StudioTextSecondary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = StudioAmber.copy(alpha = 0.9f),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = bottomOverlayInset)
            ) {
                Text(
                    text = "AFTER",
                    style = MaterialTheme.typography.labelSmall,
                    color = StudioBackground,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        // Floating Eyedropper mode instruction + cancel banner
        AnimatedVisibility(
            visible = isEyedropperActive,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = topOverlayInset, start = 16.dp, end = 16.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = StudioSurfaceElevated.copy(alpha = 0.95f),
                shadowElevation = 6.dp,
                modifier = Modifier
                    .border(1.dp, StudioCyan, RoundedCornerShape(22.dp))
                    .testTag("eyedropper_banner")
            ) {
                Row(
                    modifier = Modifier.padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Colorize,
                        contentDescription = null,
                        tint = StudioCyan,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = if (activeEyedropperMode == EyedropperMode.WHITE_BALANCE) {
                            "Tap neutral gray/white on photo"
                        } else {
                            "Tap target color on photo"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = StudioTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onCancelEyedropper)
                            .testTag("cancel_eyedropper_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.cd_cancel_eyedropper),
                            tint = StudioTextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        // End Floating Eyedropper

        // Touch Adjust live swipe value HUD & subtle gesture hint
        TouchAdjustSwipeHud(
            visible = isTouchAdjustSwiping && !isComparingOriginal,
            label = touchAdjustHudLabel,
            valueText = touchAdjustHudValueText,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = topOverlayInset)
        )

        TouchAdjustHintPill(
            visible = isTouchAdjustEnabled &&
                !isTouchAdjustSwiping &&
                touchAdjustSelectedBand == null &&
                !isEyedropperActive &&
                !isComparingOriginal &&
                !isCropMode &&
                !isHealMode,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = topOverlayInset)
        )

        // Floating HSL Target Pill Bar anchored to bottom of image canvas
        TouchAdjustHslPillBar(
            visible = isTouchAdjustEnabled &&
                touchAdjustSelectedBand != null &&
                !isCropMode &&
                !isHealMode &&
                !isComparingOriginal,
            band = touchAdjustSelectedBand,
            swatchArgb = touchAdjustSwatchArgb,
            adjustment = touchAdjustBandAdjustment,
            onUpdateBand = onUpdateTouchAdjustBand,
            onResetBand = onResetTouchAdjustBand,
            onDismiss = onDismissTouchAdjustPill,
            onDraggingChanged = onTouchAdjustSliderDraggingChanged,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

private fun DrawScope.drawHealViewportStroke(
    points: List<Pair<Float, Float>>,
    radiusPx: Float,
    color: Color
) {
    if (points.size == 1) {
        val (nx, ny) = points.first()
        drawCircle(
            color = color,
            radius = radiusPx,
            center = Offset(nx * size.width, ny * size.height)
        )
    } else {
        val path = Path().apply {
            val (sx, sy) = points.first()
            moveTo(sx * size.width, sy * size.height)
            for (i in 1 until points.size) {
                val (px, py) = points[i]
                lineTo(px * size.width, py * size.height)
            }
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(
                width = radiusPx * 2f,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )
    }
}

private fun DrawScope.drawCloneSourceCrosshair(
    center: Offset,
    radiusPx: Float,
    color: Color
) {
    val shadowStroke = 3.2.dp.toPx()
    val mainStroke = 1.6.dp.toPx()
    val arm = radiusPx * 1.4f
    // Subtle dark halo for legibility on bright skies or skin
    drawCircle(
        color = Color.Black.copy(alpha = 0.65f),
        radius = radiusPx,
        center = center,
        style = Stroke(width = shadowStroke)
    )
    drawLine(Color.Black.copy(alpha = 0.65f), Offset(center.x - arm, center.y), Offset(center.x + arm, center.y), shadowStroke)
    drawLine(Color.Black.copy(alpha = 0.65f), Offset(center.x, center.y - arm), Offset(center.x, center.y + arm), shadowStroke)

    drawCircle(
        color = color,
        radius = radiusPx,
        center = center,
        style = Stroke(width = mainStroke)
    )
    drawLine(color, Offset(center.x - arm, center.y), Offset(center.x + arm, center.y), mainStroke)
    drawLine(color, Offset(center.x, center.y - arm), Offset(center.x, center.y + arm), mainStroke)
}

private fun viewportToNormalized(
    pos: Offset,
    viewportW: Float,
    viewportH: Float,
    imgLeft: Float,
    imgTop: Float,
    fittedW: Float,
    fittedH: Float,
    zoom: Float,
    pan: Offset
): Offset {
    val cx = viewportW / 2f
    val cy = viewportH / 2f
    val unscaledX = ((pos.x - pan.x) - cx) / zoom + cx
    val unscaledY = ((pos.y - pan.y) - cy) / zoom + cy
    val nx = ((unscaledX - imgLeft) / fittedW).coerceIn(0f, 1f)
    val ny = ((unscaledY - imgTop) / fittedH).coerceIn(0f, 1f)
    return Offset(nx, ny)
}

@Composable
private fun HistogramHud(
    histogram: HistogramData,
    onClick: () -> Unit
) {
    val hudShape = RoundedCornerShape(12.dp)
    Surface(
        shape = hudShape,
        color = StudioGraphite.copy(alpha = 0.92f),
        shadowElevation = 6.dp,
        modifier = Modifier
            .border(1.dp, StudioHairline, hudShape)
            .clip(hudShape)
            .clickable(onClick = onClick)
            .testTag("histogram_hud")
    ) {
        Box(modifier = Modifier.padding(6.dp)) {
            Canvas(
                modifier = Modifier
                    .width(108.dp)
                    .height(42.dp)
            ) {
                    val bins = histogram.luma.size
                    if (bins < 2) return@Canvas
                    val stepX = size.width / (bins - 1).toFloat()

                    fun drawChannel(data: FloatArray, fill: Color, stroke: Color) {
                        val path = Path().apply {
                            moveTo(0f, size.height)
                            for (i in 0 until bins) {
                                val x = i * stepX
                                val y = size.height - (data[i].coerceIn(0f, 1f) * size.height)
                                lineTo(x, y)
                            }
                            lineTo(size.width, size.height)
                            close()
                        }
                        drawPath(path, color = fill)
                        val linePath = Path().apply {
                            for (i in 0 until bins) {
                                val x = i * stepX
                                val y = size.height - (data[i].coerceIn(0f, 1f) * size.height)
                                if (i == 0) moveTo(x, y) else lineTo(x, y)
                            }
                        }
                        drawPath(linePath, color = stroke, style = Stroke(width = 1.2.dp.toPx()))
                    }

                    drawChannel(histogram.red, Color(0x33EF4444), Color(0xAAEF4444))
                    drawChannel(histogram.green, Color(0x3322C55E), Color(0xAA22C55E))
                    drawChannel(histogram.blue, Color(0x3338BDF8), Color(0xAA38BDF8))
                    drawChannel(histogram.luma, Color(0x30FFFFFF), Color(0xCCFFFFFF))
                }
                Text(
                    text = "RGB",
                    style = MaterialTheme.typography.labelSmall,
                    color = StudioTextSecondary.copy(alpha = 0.85f),
                    modifier = Modifier.align(Alignment.TopStart)
                )
        }
    }
}

private enum class MaskHandle {
    RADIAL_CENTER,
    RADIAL_RIGHT,
    RADIAL_BOTTOM,
    RADIAL_FEATHER,
    LINEAR_START,
    LINEAR_END,
    LINEAR_MID
}

@Composable
private fun InteractiveLocalMaskOverlay(
    mask: LocalMask,
    showOverlayTint: Boolean,
    onUpdateMask: (String, (LocalMask) -> LocalMask) -> Unit,
    onDragStateChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val latestMask by rememberUpdatedState(mask)
    val latestOnUpdate by rememberUpdatedState(onUpdateMask)
    val latestOnDragState by rememberUpdatedState(onDragStateChanged)
    var activeHandle by remember { mutableStateOf<MaskHandle?>(null) }

    Canvas(
        modifier = modifier
            .pointerInput(mask.id) {
                val touchRadiusPx = 34.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    val h = size.height.toFloat().coerceAtLeast(1f)
                    val cur = latestMask
                    val handle = hitTestMaskHandle(down.position, cur, w, h, touchRadiusPx)
                    if (handle == null) {
                        return@awaitEachGesture
                    }
                    down.consume()
                    activeHandle = handle
                    latestOnDragState(true)

                    val startMask = cur
                    var cumDx = 0f
                    var cumDy = 0f
                    try {
                        do {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.size >= 2) {
                                // Yield to 2-finger pinch/pan zoom
                                break
                            }
                            if (pressed.size == 1) {
                                val ch = pressed.first()
                                val delta = ch.position - ch.previousPosition
                                cumDx += delta.x / w
                                cumDy += delta.y / h
                                ch.consume()
                                latestOnUpdate(startMask.id) {
                                    applyMaskHandleDrag(startMask, handle, cumDx, cumDy, w, h)
                                }
                            }
                        } while (event.changes.any { it.pressed })
                    } finally {
                        activeHandle = null
                        latestOnDragState(false)
                    }
                }
            }
            .testTag("local_mask_overlay")
    ) {
        val w = size.width
        val h = size.height
        val shouldShowFill = showOverlayTint || activeHandle != null
        val maskTint = if (mask.type == MaskType.RADIAL) StudioEraserCrimson else StudioCyan

        if (mask.type == MaskType.RADIAL) {
            val cx = mask.centerX * w
            val cy = mask.centerY * h
            val rxPx = (mask.radiusX * w).coerceAtLeast(8f)
            val ryPx = (mask.radiusY * h).coerceAtLeast(8f)
            val innerScale = (1f - mask.feather).coerceIn(0.02f, 0.98f)
            val outerScale = (1f + mask.feather * 0.15f).coerceAtLeast(innerScale + 0.02f)

            if (shouldShowFill) {
                val outerR = rxPx * outerScale
                val stopInner = (innerScale / outerScale).coerceIn(0f, 0.99f)
                val fillColors = if (mask.inverted) {
                    arrayOf(
                        0f to Color.Transparent,
                        stopInner to Color.Transparent,
                        1f to maskTint.copy(alpha = 0.28f)
                    )
                } else {
                    arrayOf(
                        0f to maskTint.copy(alpha = 0.28f),
                        stopInner to maskTint.copy(alpha = 0.22f),
                        1f to Color.Transparent
                    )
                }
                withTransform({
                    translate(cx, cy)
                    scale(1f, ryPx / rxPx.coerceAtLeast(1f), pivot = Offset.Zero)
                }) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colorStops = fillColors,
                            center = Offset.Zero,
                            radius = outerR
                        ),
                        radius = outerR,
                        center = Offset.Zero
                    )
                }
            }

            // Main radial boundary
            drawOval(
                color = Color.White.copy(alpha = 0.9f),
                topLeft = Offset(cx - rxPx, cy - ryPx),
                size = Size(rxPx * 2f, ryPx * 2f),
                style = Stroke(width = 1.6.dp.toPx())
            )
            // Dashed inner feather boundary
            if (mask.feather > 0.04f) {
                val irx = rxPx * innerScale
                val iry = ryPx * innerScale
                drawOval(
                    color = StudioAmber.copy(alpha = 0.85f),
                    topLeft = Offset(cx - irx, cy - iry),
                    size = Size(irx * 2f, iry * 2f),
                    style = Stroke(
                        width = 1.2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
                    )
                )
            }

            // Draggable handles: Center, Right X, Bottom Y, 45deg Feather
            val featherFrac = (1f - mask.feather * 0.85f).coerceIn(0.15f, 1f)
            val featherPos = Offset(cx + rxPx * featherFrac * 0.707f, cy - ryPx * featherFrac * 0.707f)
            drawMaskHandleDot(Offset(cx, cy), StudioAmber, isActive = activeHandle == MaskHandle.RADIAL_CENTER, radiusDp = 7f)
            drawMaskHandleDot(Offset(cx + rxPx, cy), Color.White, isActive = activeHandle == MaskHandle.RADIAL_RIGHT, radiusDp = 5.5f)
            drawMaskHandleDot(Offset(cx, cy + ryPx), Color.White, isActive = activeHandle == MaskHandle.RADIAL_BOTTOM, radiusDp = 5.5f)
            drawMaskHandleDot(featherPos, StudioCyan, isActive = activeHandle == MaskHandle.RADIAL_FEATHER, radiusDp = 5.5f)
        } else {
            val startPt = Offset(mask.startX * w, mask.startY * h)
            val endPt = Offset(mask.endX * w, mask.endY * h)
            val midPt = Offset((startPt.x + endPt.x) * 0.5f, (startPt.y + endPt.y) * 0.5f)

            if (shouldShowFill) {
                val colors = if (mask.inverted) {
                    listOf(Color.Transparent, maskTint.copy(alpha = 0.28f))
                } else {
                    listOf(maskTint.copy(alpha = 0.28f), Color.Transparent)
                }
                drawRect(
                    brush = Brush.linearGradient(
                        colors = colors,
                        start = startPt,
                        end = endPt
                    ),
                    topLeft = Offset.Zero,
                    size = Size(w, h)
                )
            }

            val dx = endPt.x - startPt.x
            val dy = endPt.y - startPt.y
            val len = hypot(dx, dy).coerceAtLeast(1f)
            val perpX = (-dy / len) * max(w, h)
            val perpY = (dx / len) * max(w, h)

            // 100% start guide line
            drawLine(
                color = StudioAmber.copy(alpha = 0.85f),
                start = Offset(startPt.x - perpX, startPt.y - perpY),
                end = Offset(startPt.x + perpX, startPt.y + perpY),
                strokeWidth = 1.4.dp.toPx()
            )
            // 0% end guide line (dashed)
            drawLine(
                color = Color.White.copy(alpha = 0.78f),
                start = Offset(endPt.x - perpX, endPt.y - perpY),
                end = Offset(endPt.x + perpX, endPt.y + perpY),
                strokeWidth = 1.2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 7f), 0f)
            )
            // Axis stem
            drawLine(
                color = Color.White.copy(alpha = 0.75f),
                start = startPt,
                end = endPt,
                strokeWidth = 1.4.dp.toPx()
            )

            drawMaskHandleDot(startPt, StudioAmber, isActive = activeHandle == MaskHandle.LINEAR_START, radiusDp = 6f)
            drawMaskHandleDot(midPt, Color.White, isActive = activeHandle == MaskHandle.LINEAR_MID, radiusDp = 7f)
            drawMaskHandleDot(endPt, StudioCyan, isActive = activeHandle == MaskHandle.LINEAR_END, radiusDp = 6f)
        }
    }
}

private fun DrawScope.drawMaskHandleDot(
    center: Offset,
    color: Color,
    isActive: Boolean,
    radiusDp: Float
) {
    val r = (if (isActive) radiusDp + 2f else radiusDp).dp.toPx()
    drawCircle(color = Color.Black.copy(alpha = 0.65f), radius = r + 2.dp.toPx(), center = center)
    drawCircle(color = color, radius = r, center = center)
    drawCircle(color = Color.White, radius = r, center = center, style = Stroke(width = 1.5.dp.toPx()))
}

private fun hitTestMaskHandle(
    pos: Offset,
    mask: LocalMask,
    w: Float,
    h: Float,
    touchRadiusPx: Float
): MaskHandle? {
    val candidates: List<Pair<MaskHandle, Offset>> = if (mask.type == MaskType.RADIAL) {
        val cx = mask.centerX * w
        val cy = mask.centerY * h
        val rxPx = (mask.radiusX * w).coerceAtLeast(8f)
        val ryPx = (mask.radiusY * h).coerceAtLeast(8f)
        val featherFrac = (1f - mask.feather * 0.85f).coerceIn(0.15f, 1f)
        val featherPos = Offset(cx + rxPx * featherFrac * 0.707f, cy - ryPx * featherFrac * 0.707f)
        listOf(
            MaskHandle.RADIAL_FEATHER to featherPos,
            MaskHandle.RADIAL_RIGHT to Offset(cx + rxPx, cy),
            MaskHandle.RADIAL_BOTTOM to Offset(cx, cy + ryPx),
            MaskHandle.RADIAL_CENTER to Offset(cx, cy)
        )
    } else {
        val s = Offset(mask.startX * w, mask.startY * h)
        val e = Offset(mask.endX * w, mask.endY * h)
        val m = Offset((s.x + e.x) * 0.5f, (s.y + e.y) * 0.5f)
        listOf(
            MaskHandle.LINEAR_START to s,
            MaskHandle.LINEAR_END to e,
            MaskHandle.LINEAR_MID to m
        )
    }
    var best: MaskHandle? = null
    var bestDist = touchRadiusPx
    for ((handle, pt) in candidates) {
        val d = hypot(pos.x - pt.x, pos.y - pt.y)
        if (d <= bestDist) {
            bestDist = d
            best = handle
        }
    }
    return best
}

private fun applyMaskHandleDrag(
    start: LocalMask,
    handle: MaskHandle,
    dxNorm: Float,
    dyNorm: Float,
    w: Float,
    h: Float
): LocalMask {
    return when (handle) {
        MaskHandle.RADIAL_CENTER -> start.copy(
            centerX = (start.centerX + dxNorm).coerceIn(0.05f, 0.95f),
            centerY = (start.centerY + dyNorm).coerceIn(0.05f, 0.95f)
        )
        MaskHandle.RADIAL_RIGHT -> start.copy(
            radiusX = (start.radiusX + dxNorm).coerceIn(0.05f, 0.95f)
        )
        MaskHandle.RADIAL_BOTTOM -> start.copy(
            radiusY = (start.radiusY + dyNorm).coerceIn(0.05f, 0.95f)
        )
        MaskHandle.RADIAL_FEATHER -> {
            // Pulling closer to center increases feather; pushing toward edge sharpens transition
            val rx = start.radiusX.coerceAtLeast(0.05f)
            val ry = start.radiusY.coerceAtLeast(0.05f)
            val baseFrac = (1f - start.feather * 0.85f).coerceIn(0.15f, 1f)
            val fx = rx * baseFrac * 0.707f + dxNorm
            val fy = -ry * baseFrac * 0.707f + dyNorm
            val normDist = hypot(fx / rx, fy / ry).coerceIn(0.15f, 1f)
            val nextFeather = ((1f - normDist) / 0.85f).coerceIn(0f, 1f)
            start.copy(feather = nextFeather)
        }
        MaskHandle.LINEAR_START -> start.copy(
            startX = (start.startX + dxNorm).coerceIn(0.02f, 0.98f),
            startY = (start.startY + dyNorm).coerceIn(0.02f, 0.98f)
        )
        MaskHandle.LINEAR_END -> start.copy(
            endX = (start.endX + dxNorm).coerceIn(0.02f, 0.98f),
            endY = (start.endY + dyNorm).coerceIn(0.02f, 0.98f)
        )
        MaskHandle.LINEAR_MID -> {
            val sx = (start.startX + dxNorm).coerceIn(0.02f, 0.98f)
            val sy = (start.startY + dyNorm).coerceIn(0.02f, 0.98f)
            val actualDx = sx - start.startX
            val actualDy = sy - start.startY
            start.copy(
                startX = sx,
                startY = sy,
                endX = (start.endX + actualDx).coerceIn(0.02f, 0.98f),
                endY = (start.endY + actualDy).coerceIn(0.02f, 0.98f)
            )
        }
    }
}

private enum class CropDragHandle {
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
    LEFT,
    TOP,
    RIGHT,
    BOTTOM,
    MOVE
}

@Composable
private fun InteractiveCropOverlay(
    cropRect: NormalizedCropRect,
    overlayMode: CropOverlayMode,
    targetNormRatio: Float?,
    onCropRectChange: (NormalizedCropRect) -> Unit,
    onDragStateChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val latestCropRect by rememberUpdatedState(cropRect)
    val latestRatio by rememberUpdatedState(targetNormRatio)
    val latestOnChange by rememberUpdatedState(onCropRectChange)
    val latestOnDragState by rememberUpdatedState(onDragStateChanged)

    var activeHandle by remember { mutableStateOf<CropDragHandle?>(null) }
    var dragStartRect by remember { mutableStateOf(cropRect) }
    var cumulativeDelta by remember { mutableStateOf(Offset.Zero) }

    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { start ->
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        val h = size.height.toFloat().coerceAtLeast(1f)
                        val rect = latestCropRect
                        val handle = findCropHandle(
                            pos = start,
                            rect = rect,
                            w = w,
                            h = h,
                            cornerTouchPx = 28.dp.toPx(),
                            edgeTouchPx = 22.dp.toPx()
                        )
                        activeHandle = handle
                        dragStartRect = rect
                        cumulativeDelta = Offset.Zero
                        if (handle != null) {
                            latestOnDragState(true)
                        }
                    },
                    onDrag = { change, dragAmount ->
                        val handle = activeHandle ?: return@detectDragGestures
                        change.consume()
                        cumulativeDelta += dragAmount
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        val h = size.height.toFloat().coerceAtLeast(1f)
                        val next = computeUpdatedCropRect(
                            start = dragStartRect,
                            handle = handle,
                            dxNorm = cumulativeDelta.x / w,
                            dyNorm = cumulativeDelta.y / h,
                            targetNormRatio = latestRatio
                        )
                        latestOnChange(next)
                    },
                    onDragEnd = {
                        if (activeHandle != null) {
                            activeHandle = null
                            latestOnDragState(false)
                        }
                    },
                    onDragCancel = {
                        if (activeHandle != null) {
                            activeHandle = null
                            latestOnDragState(false)
                        }
                    }
                )
            }
            .testTag("interactive_crop_overlay")
    ) {
        val w = size.width
        val h = size.height
        val l = cropRect.left * w
        val t = cropRect.top * h
        val r = cropRect.right * w
        val b = cropRect.bottom * h
        val cropW = (r - l).coerceAtLeast(1f)
        val cropH = (b - t).coerceAtLeast(1f)

        // Dimmed outside scrim around active crop frame
        val scrimColor = Color.Black.copy(alpha = 0.56f)
        if (t > 0f) {
            drawRect(color = scrimColor, topLeft = Offset.Zero, size = Size(w, t))
        }
        if (b < h) {
            drawRect(color = scrimColor, topLeft = Offset(0f, b), size = Size(w, h - b))
        }
        if (l > 0f) {
            drawRect(color = scrimColor, topLeft = Offset(0f, t), size = Size(l, cropH))
        }
        if (r < w) {
            drawRect(color = scrimColor, topLeft = Offset(r, t), size = Size(w - r, cropH))
        }

        // Dynamic composition guide clipped inside crop frame
        if (overlayMode != CropOverlayMode.NONE) {
            clipRect(left = l, top = t, right = r, bottom = b) {
                drawCompositionOverlay(
                    mode = overlayMode,
                    l = l,
                    t = t,
                    r = r,
                    b = b,
                    strokePx = 1.dp.toPx()
                )
            }
        }

        // Outer crisp border
        drawRect(
            color = Color.White.copy(alpha = 0.92f),
            topLeft = Offset(l, t),
            size = Size(cropW, cropH),
            style = Stroke(width = 1.5.dp.toPx())
        )

        // L-shaped corner handles & edge grab bars
        val handleStroke = 3.dp.toPx()
        val cornerLen = min(20.dp.toPx(), min(cropW, cropH) * 0.28f)
        val edgeBarHalf = min(14.dp.toPx(), min(cropW, cropH) * 0.18f)
        val handleColor = Color.White

        // Top-Left corner
        drawLine(handleColor, Offset(l, t), Offset(l + cornerLen, t), handleStroke, StrokeCap.Square)
        drawLine(handleColor, Offset(l, t), Offset(l, t + cornerLen), handleStroke, StrokeCap.Square)
        // Top-Right corner
        drawLine(handleColor, Offset(r - cornerLen, t), Offset(r, t), handleStroke, StrokeCap.Square)
        drawLine(handleColor, Offset(r, t), Offset(r, t + cornerLen), handleStroke, StrokeCap.Square)
        // Bottom-Left corner
        drawLine(handleColor, Offset(l, b - cornerLen), Offset(l, b), handleStroke, StrokeCap.Square)
        drawLine(handleColor, Offset(l, b), Offset(l + cornerLen, b), handleStroke, StrokeCap.Square)
        // Bottom-Right corner
        drawLine(handleColor, Offset(r - cornerLen, b), Offset(r, b), handleStroke, StrokeCap.Square)
        drawLine(handleColor, Offset(r, b - cornerLen), Offset(r, b), handleStroke, StrokeCap.Square)

        // Midpoint edge bars
        val cx = (l + r) * 0.5f
        val cy = (t + b) * 0.5f
        drawLine(handleColor, Offset(cx - edgeBarHalf, t), Offset(cx + edgeBarHalf, t), handleStroke, StrokeCap.Round)
        drawLine(handleColor, Offset(cx - edgeBarHalf, b), Offset(cx + edgeBarHalf, b), handleStroke, StrokeCap.Round)
        drawLine(handleColor, Offset(l, cy - edgeBarHalf), Offset(l, cy + edgeBarHalf), handleStroke, StrokeCap.Round)
        drawLine(handleColor, Offset(r, cy - edgeBarHalf), Offset(r, cy + edgeBarHalf), handleStroke, StrokeCap.Round)
    }
}

private fun DrawScope.drawCompositionOverlay(
    mode: CropOverlayMode,
    l: Float,
    t: Float,
    r: Float,
    b: Float,
    strokePx: Float
) {
    val w = r - l
    val h = b - t
    val guideColor = Color.White.copy(alpha = 0.72f)
    val subGuideColor = Color.White.copy(alpha = 0.34f)

    when (mode) {
        CropOverlayMode.THIRDS -> {
            for (i in 1..2) {
                val x = l + w * (i / 3f)
                val y = t + h * (i / 3f)
                drawLine(guideColor, Offset(x, t), Offset(x, b), strokePx)
                drawLine(guideColor, Offset(l, y), Offset(r, y), strokePx)
            }
        }

        CropOverlayMode.GRID -> {
            val cols = 6
            val rows = 6
            for (i in 1 until cols) {
                val x = l + w * (i.toFloat() / cols)
                drawLine(if (i == cols / 2) guideColor else subGuideColor, Offset(x, t), Offset(x, b), strokePx)
            }
            for (j in 1 until rows) {
                val y = t + h * (j.toFloat() / rows)
                drawLine(if (j == rows / 2) guideColor else subGuideColor, Offset(l, y), Offset(r, y), strokePx)
            }
        }

        CropOverlayMode.GOLDEN -> {
            val phi1 = 0.381966f
            val phi2 = 0.618034f
            drawLine(guideColor, Offset(l + w * phi1, t), Offset(l + w * phi1, b), strokePx)
            drawLine(guideColor, Offset(l + w * phi2, t), Offset(l + w * phi2, b), strokePx)
            drawLine(guideColor, Offset(l, t + h * phi1), Offset(r, t + h * phi1), strokePx)
            drawLine(guideColor, Offset(l, t + h * phi2), Offset(r, t + h * phi2), strokePx)
        }

        CropOverlayMode.DIAGONAL -> {
            drawLine(guideColor, Offset(l, t), Offset(r, b), strokePx)
            drawLine(guideColor, Offset(r, t), Offset(l, b), strokePx)
            val m = min(w, h)
            drawLine(subGuideColor, Offset(l, t), Offset(l + m, t + m), strokePx)
            drawLine(subGuideColor, Offset(r, t), Offset(r - m, t + m), strokePx)
            drawLine(subGuideColor, Offset(l, b), Offset(l + m, b - m), strokePx)
            drawLine(subGuideColor, Offset(r, b), Offset(r - m, b - m), strokePx)
        }

        CropOverlayMode.TRIANGLE -> {
            // Main diagonal from bottom-left to top-right + perpendicular altitudes from other two corners
            drawLine(guideColor, Offset(l, b), Offset(r, t), strokePx)
            val dx = r - l
            val dy = t - b
            val lenSq = (dx * dx + dy * dy).coerceAtLeast(1f)
            // Projection of (l, t) onto (l, b)->(r, t)
            val tTopLeft = ((t - b) * dy) / lenSq
            val p1 = Offset(l + dx * tTopLeft, b + dy * tTopLeft)
            drawLine(guideColor, Offset(l, t), p1, strokePx)
            // Projection of (r, b) onto (l, b)->(r, t)
            val tBotRight = ((r - l) * dx) / lenSq
            val p2 = Offset(l + dx * tBotRight, b + dy * tBotRight)
            drawLine(guideColor, Offset(r, b), p2, strokePx)
        }

        CropOverlayMode.SPIRAL -> {
            // Fibonacci golden spiral anchored cleanly inside crop box
            var x0 = l
            var y0 = t
            var x1 = r
            var y1 = b
            val phiInv = 0.618034f
            // 7 inward golden-rectangle quarter arcs
            for (step in 0 until 7) {
                val rw = (x1 - x0).coerceAtLeast(1f)
                val rh = (y1 - y0).coerceAtLeast(1f)
                when (step % 4) {
                    0 -> {
                        val sqW = rw * phiInv
                        drawLine(subGuideColor, Offset(x0 + sqW, y0), Offset(x0 + sqW, y1), strokePx)
                        drawArc(
                            color = guideColor,
                            startAngle = 180f,
                            sweepAngle = 90f,
                            useCenter = false,
                            topLeft = Offset(x0, y0),
                            size = Size(sqW * 2f, rh * 2f),
                            style = Stroke(width = strokePx)
                        )
                        x0 += sqW
                    }
                    1 -> {
                        val sqH = rh * phiInv
                        drawLine(subGuideColor, Offset(x0, y0 + sqH), Offset(x1, y0 + sqH), strokePx)
                        drawArc(
                            color = guideColor,
                            startAngle = 270f,
                            sweepAngle = 90f,
                            useCenter = false,
                            topLeft = Offset(x0 - rw, y0),
                            size = Size(rw * 2f, sqH * 2f),
                            style = Stroke(width = strokePx)
                        )
                        y0 += sqH
                    }
                    2 -> {
                        val sqW = rw * phiInv
                        val splitX = x1 - sqW
                        drawLine(subGuideColor, Offset(splitX, y0), Offset(splitX, y1), strokePx)
                        drawArc(
                            color = guideColor,
                            startAngle = 0f,
                            sweepAngle = 90f,
                            useCenter = false,
                            topLeft = Offset(splitX - sqW, y0 - rh),
                            size = Size(sqW * 2f, rh * 2f),
                            style = Stroke(width = strokePx)
                        )
                        x1 = splitX
                    }
                    else -> {
                        val sqH = rh * phiInv
                        val splitY = y1 - sqH
                        drawLine(subGuideColor, Offset(x0, splitY), Offset(x1, splitY), strokePx)
                        drawArc(
                            color = guideColor,
                            startAngle = 90f,
                            sweepAngle = 90f,
                            useCenter = false,
                            topLeft = Offset(x0, splitY - sqH),
                            size = Size(rw * 2f, sqH * 2f),
                            style = Stroke(width = strokePx)
                        )
                        y1 = splitY
                    }
                }
            }
        }

        CropOverlayMode.NONE -> Unit
    }
}

private fun findCropHandle(
    pos: Offset,
    rect: NormalizedCropRect,
    w: Float,
    h: Float,
    cornerTouchPx: Float,
    edgeTouchPx: Float
): CropDragHandle? {
    val l = rect.left * w
    val t = rect.top * h
    val r = rect.right * w
    val b = rect.bottom * h

    val corners = listOf(
        CropDragHandle.TOP_LEFT to Offset(l, t),
        CropDragHandle.TOP_RIGHT to Offset(r, t),
        CropDragHandle.BOTTOM_LEFT to Offset(l, b),
        CropDragHandle.BOTTOM_RIGHT to Offset(r, b)
    )
    var bestCorner: CropDragHandle? = null
    var bestCornerDist = cornerTouchPx
    for ((handle, pt) in corners) {
        val d = hypot(pos.x - pt.x, pos.y - pt.y)
        if (d <= bestCornerDist) {
            bestCornerDist = d
            bestCorner = handle
        }
    }
    if (bestCorner != null) return bestCorner

    if (pos.y in (t - edgeTouchPx)..(b + edgeTouchPx)) {
        if (abs(pos.x - l) <= edgeTouchPx) return CropDragHandle.LEFT
        if (abs(pos.x - r) <= edgeTouchPx) return CropDragHandle.RIGHT
    }
    if (pos.x in (l - edgeTouchPx)..(r + edgeTouchPx)) {
        if (abs(pos.y - t) <= edgeTouchPx) return CropDragHandle.TOP
        if (abs(pos.y - b) <= edgeTouchPx) return CropDragHandle.BOTTOM
    }

    if (pos.x in l..r && pos.y in t..b) {
        return CropDragHandle.MOVE
    }
    return null
}

private fun computeUpdatedCropRect(
    start: NormalizedCropRect,
    handle: CropDragHandle,
    dxNorm: Float,
    dyNorm: Float,
    targetNormRatio: Float?
): NormalizedCropRect {
    val minSpan = 0.08f
    if (handle == CropDragHandle.MOVE) {
        val w = start.width
        val h = start.height
        val nl = (start.left + dxNorm).coerceIn(0f, (1f - w).coerceAtLeast(0f))
        val nt = (start.top + dyNorm).coerceIn(0f, (1f - h).coerceAtLeast(0f))
        return NormalizedCropRect(nl, nt, (nl + w).coerceAtMost(1f), (nt + h).coerceAtMost(1f))
    }

    if (targetNormRatio == null) {
        var l = start.left
        var t = start.top
        var r = start.right
        var b = start.bottom
        when (handle) {
            CropDragHandle.LEFT -> l = (l + dxNorm).coerceIn(0f, r - minSpan)
            CropDragHandle.RIGHT -> r = (r + dxNorm).coerceIn(l + minSpan, 1f)
            CropDragHandle.TOP -> t = (t + dyNorm).coerceIn(0f, b - minSpan)
            CropDragHandle.BOTTOM -> b = (b + dyNorm).coerceIn(t + minSpan, 1f)
            CropDragHandle.TOP_LEFT -> {
                l = (l + dxNorm).coerceIn(0f, r - minSpan)
                t = (t + dyNorm).coerceIn(0f, b - minSpan)
            }
            CropDragHandle.TOP_RIGHT -> {
                r = (r + dxNorm).coerceIn(l + minSpan, 1f)
                t = (t + dyNorm).coerceIn(0f, b - minSpan)
            }
            CropDragHandle.BOTTOM_LEFT -> {
                l = (l + dxNorm).coerceIn(0f, r - minSpan)
                b = (b + dyNorm).coerceIn(t + minSpan, 1f)
            }
            CropDragHandle.BOTTOM_RIGHT -> {
                r = (r + dxNorm).coerceIn(l + minSpan, 1f)
                b = (b + dyNorm).coerceIn(t + minSpan, 1f)
            }
            CropDragHandle.MOVE -> Unit
        }
        return NormalizedCropRect(l, t, r, b)
    }

    val ratio = targetNormRatio.coerceIn(0.05f, 20f)
    return when (handle) {
        CropDragHandle.TOP_LEFT,
        CropDragHandle.TOP_RIGHT,
        CropDragHandle.BOTTOM_LEFT,
        CropDragHandle.BOTTOM_RIGHT -> {
            val movingLeft = handle == CropDragHandle.TOP_LEFT || handle == CropDragHandle.BOTTOM_LEFT
            val movingTop = handle == CropDragHandle.TOP_LEFT || handle == CropDragHandle.TOP_RIGHT
            val anchorX = if (movingLeft) start.right else start.left
            val anchorY = if (movingTop) start.bottom else start.top
            val candX = (if (movingLeft) start.left else start.right) + dxNorm
            val candY = (if (movingTop) start.top else start.bottom) + dyNorm

            val availW = if (movingLeft) anchorX else (1f - anchorX)
            val availH = if (movingTop) anchorY else (1f - anchorY)
            val maxH = min(availH, availW / ratio).coerceAtLeast(0.04f)
            val minH = min(maxH, max(minSpan, minSpan / ratio))

            val rawW = if (movingLeft) (anchorX - candX) else (candX - anchorX)
            val rawH = if (movingTop) (anchorY - candY) else (candY - anchorY)
            val desiredH = max(rawH, rawW / ratio).coerceIn(minH, maxH)
            val desiredW = (desiredH * ratio).coerceAtMost(availW)

            val nl = if (movingLeft) anchorX - desiredW else anchorX
            val nr = if (movingLeft) anchorX else anchorX + desiredW
            val nt = if (movingTop) anchorY - desiredH else anchorY
            val nb = if (movingTop) anchorY else anchorY + desiredH
            NormalizedCropRect(nl, nt, nr, nb)
        }

        CropDragHandle.LEFT, CropDragHandle.RIGHT -> {
            val movingLeft = handle == CropDragHandle.LEFT
            val anchorX = if (movingLeft) start.right else start.left
            val availW = if (movingLeft) anchorX else (1f - anchorX)
            val cy = (start.top + start.bottom) * 0.5f
            val maxH = min(2f * cy, 2f * (1f - cy)).coerceAtLeast(minSpan)
            val maxW = min(availW, maxH * ratio).coerceAtLeast(minSpan)
            val minW = min(maxW, max(minSpan, minSpan * ratio))

            val candX = (if (movingLeft) start.left else start.right) + dxNorm
            val rawW = if (movingLeft) (anchorX - candX) else (candX - anchorX)
            val desiredW = rawW.coerceIn(minW, maxW)
            val desiredH = (desiredW / ratio).coerceAtMost(1f)

            val nl = if (movingLeft) anchorX - desiredW else anchorX
            val nr = if (movingLeft) anchorX else anchorX + desiredW
            val nt = (cy - desiredH * 0.5f).coerceIn(0f, (1f - desiredH).coerceAtLeast(0f))
            val nb = (nt + desiredH).coerceAtMost(1f)
            NormalizedCropRect(nl, nt, nr, nb)
        }

        CropDragHandle.TOP, CropDragHandle.BOTTOM -> {
            val movingTop = handle == CropDragHandle.TOP
            val anchorY = if (movingTop) start.bottom else start.top
            val availH = if (movingTop) anchorY else (1f - anchorY)
            val cx = (start.left + start.right) * 0.5f
            val maxW = min(2f * cx, 2f * (1f - cx)).coerceAtLeast(minSpan)
            val maxH = min(availH, maxW / ratio).coerceAtLeast(minSpan)
            val minH = min(maxH, max(minSpan, minSpan / ratio))

            val candY = (if (movingTop) start.top else start.bottom) + dyNorm
            val rawH = if (movingTop) (anchorY - candY) else (candY - anchorY)
            val desiredH = rawH.coerceIn(minH, maxH)
            val desiredW = (desiredH * ratio).coerceAtMost(1f)

            val nt = if (movingTop) anchorY - desiredH else anchorY
            val nb = if (movingTop) anchorY else anchorY + desiredH
            val nl = (cx - desiredW * 0.5f).coerceIn(0f, (1f - desiredW).coerceAtLeast(0f))
            val nr = (nl + desiredW).coerceAtMost(1f)
            NormalizedCropRect(nl, nt, nr, nb)
        }

        CropDragHandle.MOVE -> start
    }
}

object ViewportZoomMath {
    const val MIN_ZOOM = 1.0f
    const val MAX_ZOOM = 8.0f
    const val DOUBLE_TAP_ZOOM = 3.0f

    fun clampPan(
        pan: Offset,
        zoom: Float,
        viewportW: Float,
        viewportH: Float,
        fittedW: Float,
        fittedH: Float
    ): Offset {
        if (zoom <= 1.005f) return Offset.Zero
        val maxPanX = max(0f, (fittedW * zoom - viewportW) * 0.5f)
        val maxPanY = max(0f, (fittedH * zoom - viewportH) * 0.5f)
        return Offset(
            x = pan.x.coerceIn(-maxPanX, maxPanX),
            y = pan.y.coerceIn(-maxPanY, maxPanY)
        )
    }

    fun applyPinchAndPan(
        currentZoom: Float,
        currentPan: Offset,
        zoomChange: Float,
        panChange: Offset,
        focalPoint: Offset,
        viewportW: Float,
        viewportH: Float,
        fittedW: Float,
        fittedH: Float
    ): Pair<Float, Offset> {
        val newZoom = (currentZoom * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (newZoom <= 1.005f) {
            return MIN_ZOOM to Offset.Zero
        }
        val factor = if (currentZoom > 1e-4f) newZoom / currentZoom else 1f
        val viewportCenter = Offset(viewportW * 0.5f, viewportH * 0.5f)
        val focalOffset = if (focalPoint != Offset.Unspecified) focalPoint - viewportCenter else Offset.Zero
        val rawPan = currentPan * factor + focalOffset * (1f - factor) + panChange
        val clamped = clampPan(rawPan, newZoom, viewportW, viewportH, fittedW, fittedH)
        return newZoom to clamped
    }

    fun computeDoubleTapTarget(
        currentZoom: Float,
        tapPosition: Offset,
        viewportW: Float,
        viewportH: Float,
        fittedW: Float,
        fittedH: Float
    ): Pair<Float, Offset> {
        if (currentZoom > 1.05f) {
            return MIN_ZOOM to Offset.Zero
        }
        return applyPinchAndPan(
            currentZoom = MIN_ZOOM,
            currentPan = Offset.Zero,
            zoomChange = DOUBLE_TAP_ZOOM,
            panChange = Offset.Zero,
            focalPoint = tapPosition,
            viewportW = viewportW,
            viewportH = viewportH,
            fittedW = fittedW,
            fittedH = fittedH
        )
    }
}

/**
 * Isolated zoom readout badge so reading [zoomScaleProvider] during 120Hz pinch-to-zoom
 * only recomposes this lightweight pill instead of [PhotoViewport] or its [AndroidView].
 */
@Composable
private fun ZoomResetBadge(
    visible: Boolean,
    zoomScaleProvider: () -> Float,
    onResetZoom: () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        val currentZoom = zoomScaleProvider()
        Surface(
            onClick = onResetZoom,
            shape = RoundedCornerShape(20.dp),
            color = StudioSurfaceElevated.copy(alpha = 0.92f),
            modifier = Modifier
                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                .border(1.dp, StudioCyan.copy(alpha = 0.7f), RoundedCornerShape(20.dp))
                .testTag("zoom_reset_badge")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ZoomOutMap,
                    contentDescription = "Reset zoom to fit screen",
                    tint = StudioCyan,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = String.format(java.util.Locale.US, "%.1f×", currentZoom),
                    style = MaterialTheme.typography.labelSmall,
                    color = StudioTextPrimary
                )
            }
        }
    }
}


