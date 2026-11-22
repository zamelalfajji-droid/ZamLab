package com.example.ui.components

import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.StudioActiveOffWhite
import com.example.ui.theme.StudioBackground
import com.example.ui.theme.StudioGlassHairline
import com.example.ui.theme.StudioGlassSurface
import com.example.ui.theme.StudioLuxuryGold
import com.example.ui.theme.StudioSurfaceHighest
import com.example.ui.theme.StudioTextMuted
import com.example.ui.theme.StudioTextPrimary
import com.example.ui.theme.StudioTextSecondary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Pure math utilities for precision darkroom slider dragging and step quantization.
 * Kept Android-free so every step boundary and damped drag ratio is directly unit-testable.
 */
object SliderStepMath {
    /**
     * Damped horizontal drag multiplier: dragging across the full track width adjusts 55% of
     * the slider range, giving nearly 2x finer control so values step smoothly instead of jumping.
     */
    const val DEFAULT_DRAG_SENSITIVITY: Float = 0.55f

    /**
     * Infers a clean Lightroom-style step increment from the slider's range when not explicitly supplied.
     */
    fun defaultStepForRange(valueRange: ClosedFloatingPointRange<Float>): Float {
        val span = valueRange.endInclusive - valueRange.start
        return when {
            valueRange.start < 0f && valueRange.endInclusive > 0f && span <= 4.05f -> 0.05f // Exposure (-2..+2 EV)
            span <= 2.05f -> 0.01f // Normalized 0..1 or 0..2 ratios (1% increments)
            span <= 3.05f -> 0.1f // Radius (0.5..3.0)
            else -> 1f // -100..+100, 0..100, 0..150, 50..200
        }
    }

    /**
     * Quantizes [raw] to exact increments of [step] within [valueRange], anchored at 0 when the
     * range spans zero, and eliminates floating-point drift (such as -0.0 or 0.15000001).
     */
    fun quantize(
        raw: Float,
        valueRange: ClosedFloatingPointRange<Float>,
        step: Float
    ): Float {
        val clamped = raw.coerceIn(valueRange.start, valueRange.endInclusive)
        if (step <= 0f) return clamped
        val spansZero = valueRange.start <= 0f && valueRange.endInclusive >= 0f
        val anchor = if (spansZero) 0f else valueRange.start
        val stepsFromAnchor = ((clamped - anchor) / step).roundToInt()
        val snapped = (anchor + stepsFromAnchor * step).coerceIn(valueRange.start, valueRange.endInclusive)
        if (spansZero && abs(snapped) < step * 0.25f) {
            return 0f
        }
        // Clean decimal precision for common fractional steps (0.01, 0.05, 0.1, 0.5)
        return when {
            step >= 1f -> snapped.roundToInt().toFloat()
            step >= 0.1f -> (snapped * 10f).roundToInt() / 10f
            else -> (snapped * 100f).roundToInt() / 100f
        }.coerceIn(valueRange.start, valueRange.endInclusive)
    }

    /**
     * Computes a damped relative drag value from [startValue] using horizontal pointer displacement
     * [totalDeltaPx] across a track of width [trackWidthPx].
     */
    fun computeDampedDragValue(
        startValue: Float,
        totalDeltaPx: Float,
        trackWidthPx: Float,
        valueRange: ClosedFloatingPointRange<Float>,
        step: Float,
        sensitivity: Float = DEFAULT_DRAG_SENSITIVITY
    ): Float {
        val safeWidth = trackWidthPx.coerceAtLeast(1f)
        val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(1e-4f)
        val raw = startValue + (totalDeltaPx / safeWidth) * span * sensitivity
        return quantize(raw, valueRange, step)
    }

    /**
     * Maps a direct single tap at [tapX] onto [valueRange], quantized to [step].
     */
    fun computeTapValue(
        tapX: Float,
        padPx: Float,
        trackWidthPx: Float,
        valueRange: ClosedFloatingPointRange<Float>,
        step: Float
    ): Float {
        val safeWidth = trackWidthPx.coerceAtLeast(1f)
        val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(1e-4f)
        val fraction = ((tapX - padPx) / safeWidth).coerceIn(0f, 1f)
        return quantize(valueRange.start + fraction * span, valueRange, step)
    }
}

/**
 * Reliable universal double-tap-to-reset modifier for slider labels, numeric value pills, and readouts.
 *
 * Ergonomic guarantees:
 * - Never consumes pointer-down or drag events, so parent verticalScroll / LazyColumn / LazyRow
 *   scrolling works with zero friction.
 * - Uses [rememberUpdatedState] with a stable `pointerInput(Unit)` key so recompositions during
 *   value updates never cancel an in-progress double-tap.
 * - Triggers subtle [HapticFeedbackType.TextHandleMove] feedback when resetting.
 */
@Composable
internal fun Modifier.doubleTapToReset(
    enabled: Boolean = true,
    performHaptic: Boolean = true,
    onDoubleTapReset: () -> Unit
): Modifier {
    val haptic = LocalHapticFeedback.current
    val viewConfig = LocalViewConfiguration.current
    val density = LocalDensity.current
    val currentEnabled by rememberUpdatedState(enabled)
    val currentPerformHaptic by rememberUpdatedState(performHaptic)
    val currentOnReset by rememberUpdatedState(onDoubleTapReset)
    val doubleTapSlopPx = with(density) { 48.dp.toPx() }

    return this.pointerInput(Unit) {
        var lastTapUpTimeMs = 0L
        var lastTapPos = Offset.Zero
        val touchSlop = viewConfig.touchSlop
        val doubleTapWindowMs = maxOf(viewConfig.doubleTapTimeoutMillis, 360L)

        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (!currentEnabled) return@awaitEachGesture

            val downTimeMs = SystemClock.uptimeMillis()
            val downPos = down.position
            val isDoubleTapCandidate = lastTapUpTimeMs > 0L &&
                (downTimeMs - lastTapUpTimeMs) <= doubleTapWindowMs &&
                (downPos - lastTapPos).getDistance() <= doubleTapSlopPx

            var movedOrConsumed = false
            var releaseChange = down

            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id }
                    ?: event.changes.firstOrNull()
                    ?: break
                releaseChange = change

                if (change.isConsumed) {
                    movedOrConsumed = true
                    break
                }
                if (!change.pressed) {
                    break
                }
                if ((change.position - downPos).getDistance() > touchSlop) {
                    movedOrConsumed = true
                    break
                }
            }

            if (!movedOrConsumed && !releaseChange.isConsumed && currentEnabled) {
                if (isDoubleTapCandidate) {
                    lastTapUpTimeMs = 0L
                    releaseChange.consume()
                    if (currentPerformHaptic) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                    currentOnReset()
                } else {
                    lastTapUpTimeMs = SystemClock.uptimeMillis()
                    lastTapPos = downPos
                }
            } else if (movedOrConsumed) {
                lastTapUpTimeMs = 0L
            }
        }
    }
}

/**
 * Zero-latency single-tap selection paired with instant double-tap-to-reset for tool tiles,
 * HSL color swatches, and curve channel pills inside scrollable strips.
 *
 * - Single tap fires immediately on finger release with zero 300ms delay.
 * - Double-tap keeps the item selected, triggers [HapticFeedbackType.TextHandleMove], and resets
 *   that specific parameter.
 * - Never consumes pointer-down or drag events so horizontal/vertical scrolling stays effortless.
 */
@Composable
internal fun Modifier.tapAndDoubleTapReset(
    enabled: Boolean = true,
    onTap: () -> Unit,
    onDoubleTapReset: (() -> Unit)? = null
): Modifier {
    val haptic = LocalHapticFeedback.current
    val viewConfig = LocalViewConfiguration.current
    val density = LocalDensity.current
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnDoubleTapReset by rememberUpdatedState(onDoubleTapReset)
    val doubleTapSlopPx = with(density) { 48.dp.toPx() }

    return this
        .semantics {
            onClick {
                if (currentEnabled) {
                    currentOnTap()
                    true
                } else {
                    false
                }
            }
        }
        .pointerInput(Unit) {
            var lastTapUpTimeMs = 0L
            var lastTapPos = Offset.Zero
            val touchSlop = viewConfig.touchSlop
            val doubleTapWindowMs = maxOf(viewConfig.doubleTapTimeoutMillis, 360L)

            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (!currentEnabled) return@awaitEachGesture

                val downTimeMs = SystemClock.uptimeMillis()
                val downPos = down.position
                val resetCallback = currentOnDoubleTapReset
                val isDoubleTapCandidate = resetCallback != null &&
                    lastTapUpTimeMs > 0L &&
                    (downTimeMs - lastTapUpTimeMs) <= doubleTapWindowMs &&
                    (downPos - lastTapPos).getDistance() <= doubleTapSlopPx

                var movedOrConsumed = false
                var releaseChange = down

                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id }
                        ?: event.changes.firstOrNull()
                        ?: break
                    releaseChange = change

                    if (change.isConsumed) {
                        movedOrConsumed = true
                        break
                    }
                    if (!change.pressed) {
                        break
                    }
                    if ((change.position - downPos).getDistance() > touchSlop) {
                        movedOrConsumed = true
                        break
                    }
                }

                if (!movedOrConsumed && !releaseChange.isConsumed && currentEnabled) {
                    releaseChange.consume()
                    val activeReset = currentOnDoubleTapReset
                    if (isDoubleTapCandidate && activeReset != null) {
                        lastTapUpTimeMs = 0L
                        currentOnTap()
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        activeReset()
                    } else {
                        lastTapUpTimeMs = SystemClock.uptimeMillis()
                        lastTapPos = downPos
                        currentOnTap()
                    }
                } else if (movedOrConsumed) {
                    lastTapUpTimeMs = 0L
                }
            }
        }
}

/**
 * Wide, tactile Frosted Glass scrolling tick-mark ruler slider for ZamLab's Adjust Focus Dock.
 *
 * Features:
 * - Crisp monospace value pill centered directly above the fixed center needle (e.g., `+0.35 EV` or `+18`).
 * - Vertical ruler tick marks that smoothly scroll/scrub under a fixed off-white center needle,
 *   with a subtle warm-gold accent dot & active track glow when modified.
 * - Calibrated damped horizontal drag (`0.55x`, `0.05` step for Exposure `-2..+2`, `1` step for `-100..+100`)
 *   emitting live updates on every move.
 * - Smooth animated double-tap anywhere on the ruler, label, or live numeric value pill to reset
 *   the parameter to its default with light haptic feedback and a single clean undo state.
 */
@Composable
internal fun RulerPrecisionSlider(
    label: String,
    icon: ImageVector,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    step: Float = SliderStepMath.defaultStepForRange(valueRange),
    defaultValue: Float = if (valueRange.start < 0f && valueRange.endInclusive > 0f) 0f else valueRange.start,
    formattedValue: String,
    isModified: Boolean,
    gradientColors: List<Color>? = null,
    sensitivity: Float = SliderStepMath.DEFAULT_DRAG_SENSITIVITY,
    testTag: String,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit = {},
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currentValue by rememberUpdatedState(value)
    val currentDefaultValue by rememberUpdatedState(defaultValue)
    val currentValueRange by rememberUpdatedState(valueRange)
    val currentStep by rememberUpdatedState(step)
    val currentOnChange by rememberUpdatedState(onValueChange)
    val currentOnFinish by rememberUpdatedState(onValueChangeFinished)
    val currentOnReset by rememberUpdatedState(onReset)

    var isActivelyDragging by remember { mutableStateOf(false) }
    var isAnimatingReset by remember { mutableStateOf(false) }
    var resetAnimJob by remember { mutableStateOf<Job?>(null) }
    var pendingSingleTapJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val viewConfig = LocalViewConfiguration.current
    val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(1e-4f)
    val isBipolar = valueRange.start < 0f && valueRange.endInclusive > 0f
    val zeroAnchor = if (isBipolar) 0f else valueRange.start

    // Cancel any in-flight reset animation or deferred single tap if the active tool changes
    LaunchedEffect(testTag) {
        pendingSingleTapJob?.cancel()
        pendingSingleTapJob = null
        if (resetAnimJob != null) {
            resetAnimJob?.cancel()
            resetAnimJob = null
            if (isAnimatingReset) {
                isAnimatingReset = false
                currentOnFinish()
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            pendingSingleTapJob?.cancel()
            resetAnimJob?.cancel()
            if (isAnimatingReset) {
                isAnimatingReset = false
                currentOnFinish()
            }
        }
    }

    fun animateSmoothReset() {
        pendingSingleTapJob?.cancel()
        pendingSingleTapJob = null
        resetAnimJob?.cancel()
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        val start = currentValue
        val target = currentDefaultValue
        if (abs(start - target) <= 0.0001f) {
            if (isModified) {
                currentOnReset()
            }
            return
        }
        val range = currentValueRange
        val activeStep = currentStep
        resetAnimJob = scope.launch {
            isAnimatingReset = true
            try {
                // Capture ONE clean pre-reset undo snapshot before animating smoothly to default
                currentOnChange(start)
                val anim = Animatable(start)
                var lastEmitted = start
                anim.animateTo(
                    targetValue = target,
                    animationSpec = tween(durationMillis = 190, easing = FastOutSlowInEasing)
                ) {
                    val q = SliderStepMath.quantize(this.value, range, activeStep)
                    if (q != lastEmitted) {
                        lastEmitted = q
                        currentOnChange(q)
                    }
                }
                currentOnChange(target)
                currentOnFinish()
            } catch (ce: CancellationException) {
                throw ce
            } finally {
                isAnimatingReset = false
                resetAnimJob = null
            }
        }
    }

    val pillBorderColor by animateColorAsState(
        targetValue = when {
            isActivelyDragging -> StudioActiveOffWhite.copy(alpha = 0.34f)
            isModified -> StudioLuxuryGold.copy(alpha = 0.36f)
            else -> StudioGlassHairline
        },
        animationSpec = tween(durationMillis = 140),
        label = "ruler_pill_border"
    )
    val trayBorderColor by animateColorAsState(
        targetValue = if (isActivelyDragging) StudioActiveOffWhite.copy(alpha = 0.24f) else StudioGlassHairline,
        animationSpec = tween(durationMillis = 140),
        label = "ruler_tray_border"
    )

    val padPx = with(density) { 12.dp.toPx() }
    val doubleTapSlopPx = with(density) { 48.dp.toPx() }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        // Header row: Tool label (left), crisp centered monospace value pill above needle, reset hint (right)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .doubleTapToReset(
                            performHaptic = false,
                            onDoubleTapReset = { animateSmoothReset() }
                        )
                        .padding(horizontal = 4.dp, vertical = 4.dp)
                        .testTag("${testTag}_label"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isModified || isActivelyDragging) StudioActiveOffWhite else StudioTextSecondary,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 0.2.sp
                        ),
                        color = if (isModified || isActivelyDragging) StudioActiveOffWhite else StudioTextSecondary,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Crisp monospace value pill centered directly above the fixed needle — double-tap smoothly resets
            Surface(
                shape = RoundedCornerShape(50),
                color = StudioGlassSurface,
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .border(1.dp, pillBorderColor, RoundedCornerShape(50))
                    .clip(RoundedCornerShape(50))
                    .doubleTapToReset(
                        performHaptic = false,
                        onDoubleTapReset = { animateSmoothReset() }
                    )
                    .testTag("${testTag}_readout")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (isModified) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(StudioLuxuryGold)
                        )
                    }
                    Text(
                        text = formattedValue,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            fontFeatureSettings = "tnum"
                        ),
                        color = if (isModified || isActivelyDragging) StudioActiveOffWhite else StudioTextSecondary,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterEnd
            ) {
                if (isModified && !isActivelyDragging) {
                    Text(
                        text = stringResource(R.string.hint_double_tap_reset),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = StudioTextMuted,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // Wide frosted-glass scrolling tick-mark ruler under a fixed center needle
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(StudioGlassSurface)
                .border(1.dp, trayBorderColor, RoundedCornerShape(14.dp))
                .semantics {
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        current = value.coerceIn(valueRange.start, valueRange.endInclusive),
                        range = valueRange
                    )
                    setProgress { target ->
                        val quantized = SliderStepMath.quantize(target, valueRange, step)
                        currentOnChange(quantized)
                        currentOnFinish()
                        true
                    }
                }
                .pointerInput(valueRange, step, sensitivity) {
                    var lastTapUpTimeMs = 0L
                    var lastTapPos = Offset.Zero
                    val touchSlop = viewConfig.touchSlop
                    val dragStartSlop = (touchSlop * 0.32f).coerceAtLeast(3.5f)
                    val doubleTapWindowMs = maxOf(viewConfig.doubleTapTimeoutMillis, 360L)
                    val singleTapDelayMs = minOf(viewConfig.doubleTapTimeoutMillis, 230L)

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val downTimeMs = SystemClock.uptimeMillis()
                        val downPos = down.position

                        val isDoubleTapCandidate = lastTapUpTimeMs > 0L &&
                            (downTimeMs - lastTapUpTimeMs) <= doubleTapWindowMs &&
                            (downPos - lastTapPos).getDistance() <= doubleTapSlopPx

                        pendingSingleTapJob?.cancel()
                        pendingSingleTapJob = null
                        if (resetAnimJob != null) {
                            resetAnimJob?.cancel()
                            resetAnimJob = null
                            isAnimatingReset = false
                        }

                        val startVal = currentValue
                        val usableTrackW = (size.width.toFloat() - padPx * 2f).coerceAtLeast(1f)

                        var dragging = false
                        var dragAnchorX = downPos.x
                        var lastEmitted = startVal

                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                    ?: event.changes.firstOrNull()
                                    ?: break

                                if (!change.pressed) {
                                    if (dragging) change.consume()
                                    break
                                }

                                if (!dragging) {
                                    val dx = change.position.x - downPos.x
                                    if (abs(dx) >= dragStartSlop) {
                                        dragging = true
                                        isActivelyDragging = true
                                        lastTapUpTimeMs = 0L
                                        dragAnchorX = change.position.x
                                        change.consume()
                                        val initialStep = SliderStepMath.computeDampedDragValue(
                                            startValue = startVal,
                                            totalDeltaPx = dx * 0.35f,
                                            trackWidthPx = usableTrackW,
                                            valueRange = valueRange,
                                            step = step,
                                            sensitivity = sensitivity
                                        )
                                        lastEmitted = initialStep
                                        currentOnChange(initialStep)
                                    }
                                } else {
                                    change.consume()
                                    val deltaX = change.position.x - dragAnchorX
                                    val nextVal = SliderStepMath.computeDampedDragValue(
                                        startValue = startVal,
                                        totalDeltaPx = deltaX,
                                        trackWidthPx = usableTrackW,
                                        valueRange = valueRange,
                                        step = step,
                                        sensitivity = sensitivity
                                    )
                                    if (nextVal != lastEmitted) {
                                        lastEmitted = nextVal
                                        currentOnChange(nextVal)
                                    }
                                }
                            }
                        } finally {
                            if (dragging) {
                                isActivelyDragging = false
                                currentOnFinish()
                            } else if (isDoubleTapCandidate) {
                                lastTapUpTimeMs = 0L
                                animateSmoothReset()
                            } else {
                                lastTapUpTimeMs = SystemClock.uptimeMillis()
                                lastTapPos = downPos
                                // Defer single-tap nudge briefly so a double-tap never jumps value or pollutes undo
                                val centerOffsetPx = downPos.x - (size.width * 0.5f)
                                val nudged = SliderStepMath.computeDampedDragValue(
                                    startValue = startVal,
                                    totalDeltaPx = centerOffsetPx * 0.5f,
                                    trackWidthPx = usableTrackW,
                                    valueRange = valueRange,
                                    step = step,
                                    sensitivity = sensitivity
                                )
                                pendingSingleTapJob = scope.launch {
                                    delay(singleTapDelayMs)
                                    currentOnChange(nudged)
                                    currentOnFinish()
                                    pendingSingleTapJob = null
                                }
                            }
                        }
                    }
                }
                .testTag(testTag),
            contentAlignment = Alignment.Center
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                val w = size.width
                val h = size.height
                val centerX = w * 0.5f
                val cy = h * 0.5f

                // Scale ticks so scrubbing across the ruler moves ticks 1:1 with the damped drag ratio
                val usableW = (w - 24.dp.toPx()).coerceAtLeast(1f)
                val pxPerUnit = usableW / (span * sensitivity).coerceAtLeast(1e-4f)

                // Choose crisp visual tick spacing in domain units (so ticks stay clean & uncluttered)
                val minorTickUnit = when {
                    span <= 4.05f -> 0.05f // Exposure: every 0.05 EV; major every 0.50 EV (10 steps)
                    span <= 105f -> 2f
                    else -> 2.5f // -100..+100: 80 crisp ticks across full scale; major every 25 units
                }
                val majorEvery = when {
                    span <= 4.05f -> 10
                    span <= 105f -> 5
                    else -> 10
                }
                val midEvery = majorEvery / 2

                val halfVisibleUnits = (w * 0.5f) / pxPerUnit
                val minVisibleVal = (value - halfVisibleUnits).coerceAtLeast(valueRange.start)
                val maxVisibleVal = (value + halfVisibleUnits).coerceAtMost(valueRange.endInclusive)

                val minIdx = ceil((minVisibleVal - zeroAnchor) / minorTickUnit).toInt()
                val maxIdx = floor((maxVisibleVal - zeroAnchor) / minorTickUnit).toInt()

                // Optional subtle color spectrum hairline along bottom edge for Temp/Tint/Vibrance/Saturation
                if (gradientColors != null && gradientColors.size >= 2) {
                    val lineH = 2.dp.toPx()
                    drawRoundRect(
                        brush = Brush.horizontalGradient(
                            colors = gradientColors,
                            startX = 16.dp.toPx(),
                            endX = w - 16.dp.toPx()
                        ),
                        topLeft = Offset(16.dp.toPx(), h - 5.dp.toPx()),
                        size = Size((w - 32.dp.toPx()).coerceAtLeast(1f), lineH),
                        cornerRadius = CornerRadius(lineH * 0.5f, lineH * 0.5f),
                        alpha = 0.65f
                    )
                }

                // Active span bar connecting scrolled 0-anchor to the fixed center needle
                val zeroX = centerX + (zeroAnchor - value) * pxPerUnit
                if (abs(value - zeroAnchor) > step * 0.25f) {
                    val clampedZeroX = zeroX.coerceIn(0f, w)
                    val barStart = minOf(clampedZeroX, centerX)
                    val barEnd = maxOf(clampedZeroX, centerX)
                    val barH = 2.5.dp.toPx()
                    drawRoundRect(
                        color = StudioActiveOffWhite.copy(alpha = 0.24f),
                        topLeft = Offset(barStart, cy - barH * 0.5f),
                        size = Size((barEnd - barStart).coerceAtLeast(1f), barH),
                        cornerRadius = CornerRadius(barH * 0.5f, barH * 0.5f)
                    )
                }

                // Draw scrolling vertical ruler ticks
                val minorStroke = 1.15.dp.toPx()
                val majorStroke = 1.65.dp.toPx()
                val activeMin = minOf(zeroAnchor, value)
                val activeMax = maxOf(zeroAnchor, value)

                for (idx in minIdx..maxIdx) {
                    val tickVal = zeroAnchor + idx * minorTickUnit
                    if (tickVal < valueRange.start - 1e-4f || tickVal > valueRange.endInclusive + 1e-4f) continue
                    val x = centerX + (tickVal - value) * pxPerUnit
                    if (x < -4f || x > w + 4f) continue

                    // Edge attenuation so ticks fade cleanly into the frosted glass corners
                    val distRatio = (abs(x - centerX) / (w * 0.5f)).coerceIn(0f, 1f)
                    val edgeAlpha = (1f - distRatio * distRatio * 0.82f).coerceIn(0.12f, 1f)

                    val isZeroTick = idx == 0
                    val isMajor = idx % majorEvery == 0
                    val isMid = !isMajor && idx % midEvery == 0
                    val isInActiveSpan = abs(value - zeroAnchor) > step * 0.25f &&
                        tickVal >= activeMin - 1e-4f &&
                        tickVal <= activeMax + 1e-4f

                    val halfTickH = when {
                        isZeroTick -> 13.dp.toPx()
                        isMajor -> 10.dp.toPx()
                        isMid -> 7.5.dp.toPx()
                        else -> 5.dp.toPx()
                    }
                    val baseColor = when {
                        isZeroTick && isModified -> StudioLuxuryGold
                        isZeroTick -> StudioActiveOffWhite.copy(alpha = 0.78f)
                        isInActiveSpan -> StudioActiveOffWhite.copy(alpha = if (isMajor) 0.90f else 0.55f)
                        isMajor -> Color.White.copy(alpha = 0.36f)
                        isMid -> Color.White.copy(alpha = 0.24f)
                        else -> Color.White.copy(alpha = 0.14f)
                    }
                    drawLine(
                        color = baseColor.copy(alpha = baseColor.alpha * edgeAlpha),
                        start = Offset(x, cy - halfTickH),
                        end = Offset(x, cy + halfTickH),
                        strokeWidth = if (isMajor || isZeroTick) majorStroke else minorStroke,
                        cap = StrokeCap.Round
                    )
                }

                // Subtle frosted edge vignette fade left & right
                val fadeW = 28.dp.toPx()
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(StudioGlassSurface, Color.Transparent),
                        startX = 0f,
                        endX = fadeW
                    ),
                    topLeft = Offset.Zero,
                    size = Size(fadeW, h)
                )
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color.Transparent, StudioGlassSurface),
                        startX = w - fadeW,
                        endX = w
                    ),
                    topLeft = Offset(w - fadeW, 0f),
                    size = Size(fadeW, h)
                )

                // Fixed crisp off-white center needle (with subtle warm gold tip when modified)
                val needleHalfH = if (isActivelyDragging) 17.dp.toPx() else 15.dp.toPx()
                val needleW = if (isActivelyDragging) 2.8.dp.toPx() else 2.2.dp.toPx()
                drawRoundRect(
                    color = Color.Black.copy(alpha = 0.48f),
                    topLeft = Offset(centerX - needleW * 0.5f - 1.dp.toPx(), cy - needleHalfH - 1.dp.toPx()),
                    size = Size(needleW + 2.dp.toPx(), needleHalfH * 2f + 2.dp.toPx()),
                    cornerRadius = CornerRadius(needleW, needleW)
                )
                drawRoundRect(
                    color = StudioActiveOffWhite,
                    topLeft = Offset(centerX - needleW * 0.5f, cy - needleHalfH),
                    size = Size(needleW, needleHalfH * 2f),
                    cornerRadius = CornerRadius(needleW, needleW)
                )
                if (isModified || isActivelyDragging) {
                    drawCircle(
                        color = StudioLuxuryGold,
                        radius = 2.4.dp.toPx(),
                        center = Offset(centerX, cy - needleHalfH - 1.5.dp.toPx())
                    )
                }
            }
        }
    }
}

/**
 * Smooth, precision-damped darkroom slider track shared across all adjustment panels.
 *
 * Key ergonomic guarantees:
 * - Damped relative horizontal drag (no sudden thumb jump on touch-down, smooth fine stepping).
 * - Immediate real-time [onValueChange] emission on every drag delta (never deferred to release).
 * - Vertical gesture yield so scrolling inside accordion panels never accidentally moves a slider.
 * - Direct single-tap positioning (deferred cleanly when [onReset] is set so double-tap never jumps)
 *   and reliable double-tap reset with light haptic feedback ([HapticFeedbackType.TextHandleMove]).
 */
@Composable
internal fun StudioPrecisionTrack(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    step: Float = SliderStepMath.defaultStepForRange(valueRange),
    enabled: Boolean = true,
    accentColor: Color,
    gradientColors: List<Color>? = null,
    compact: Boolean = false,
    sensitivity: Float = SliderStepMath.DEFAULT_DRAG_SENSITIVITY,
    testTag: String,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit = {},
    onReset: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val currentValue by rememberUpdatedState(value)
    val currentOnChange by rememberUpdatedState(onValueChange)
    val currentOnFinish by rememberUpdatedState(onValueChangeFinished)
    val currentOnReset by rememberUpdatedState(onReset)

    var isActivelyDragging by remember { mutableStateOf(false) }
    var pendingSingleTapJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val viewConfig = LocalViewConfiguration.current
    val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(1e-4f)
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    val isBipolar = valueRange.start < 0f && valueRange.endInclusive > 0f
    val zeroFraction = if (isBipolar) (-valueRange.start / span).coerceIn(0f, 1f) else 0f

    DisposableEffect(Unit) {
        onDispose {
            pendingSingleTapJob?.cancel()
            pendingSingleTapJob = null
        }
    }

    val targetThumbRadius: Dp = when {
        compact && isActivelyDragging -> 8.5.dp
        compact -> 7.dp
        isActivelyDragging -> 10.dp
        else -> 8.dp
    }
    val thumbRadiusDp by animateDpAsState(
        targetValue = targetThumbRadius,
        animationSpec = tween(durationMillis = 100),
        label = "slider_thumb_radius"
    )

    val trackHeightDp = if (compact) 34.dp else 40.dp
    val padPx = with(density) { 11.dp.toPx() }
    val doubleTapSlopPx = with(density) { 48.dp.toPx() }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(trackHeightDp)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = value.coerceIn(valueRange.start, valueRange.endInclusive),
                    range = valueRange
                )
                if (enabled) {
                    setProgress { target ->
                        val quantized = SliderStepMath.quantize(target, valueRange, step)
                        currentOnChange(quantized)
                        currentOnFinish()
                        true
                    }
                }
            }
            .pointerInput(enabled, valueRange, step, sensitivity) {
                if (!enabled) return@pointerInput
                var lastTapUpTimeMs = 0L
                var lastTapPos = Offset.Zero
                val touchSlop = viewConfig.touchSlop
                val dragStartSlop = (touchSlop * 0.42f).coerceAtLeast(4f)
                val doubleTapWindowMs = maxOf(viewConfig.doubleTapTimeoutMillis, 360L)
                val singleTapDelayMs = minOf(viewConfig.doubleTapTimeoutMillis, 230L)

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downTimeMs = SystemClock.uptimeMillis()
                    val downPos = down.position
                    val resetCallback = currentOnReset
                    val isDoubleTapCandidate = resetCallback != null &&
                        lastTapUpTimeMs > 0L &&
                        (downTimeMs - lastTapUpTimeMs) <= doubleTapWindowMs &&
                        (downPos - lastTapPos).getDistance() <= doubleTapSlopPx

                    pendingSingleTapJob?.cancel()
                    pendingSingleTapJob = null

                    val startVal = currentValue
                    val usableTrackW = (size.width.toFloat() - padPx * 2f).coerceAtLeast(1f)

                    var dragging = false
                    var cancelledByVerticalScroll = false
                    var dragAnchorX = downPos.x
                    var lastEmitted = startVal

                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                                ?: event.changes.firstOrNull()
                                ?: break

                            if (!dragging && change.isConsumed) {
                                cancelledByVerticalScroll = true
                                lastTapUpTimeMs = 0L
                                break
                            }

                            if (!change.pressed) {
                                if (dragging) {
                                    change.consume()
                                }
                                break
                            }

                            if (!dragging) {
                                val dx = change.position.x - downPos.x
                                val dy = change.position.y - downPos.y
                                if (abs(dy) > touchSlop && abs(dy) > abs(dx) * 1.15f) {
                                    // Yield cleanly to parent verticalScroll
                                    cancelledByVerticalScroll = true
                                    lastTapUpTimeMs = 0L
                                    break
                                }
                                if (abs(dx) >= dragStartSlop) {
                                    dragging = true
                                    isActivelyDragging = true
                                    lastTapUpTimeMs = 0L
                                    // Anchor at the drag start threshold so there is zero jump
                                    dragAnchorX = change.position.x
                                    change.consume()
                                    // Signal drag start immediately so undo snapshot & live mode engage
                                    val initialStep = SliderStepMath.computeDampedDragValue(
                                        startValue = startVal,
                                        totalDeltaPx = dx * 0.35f,
                                        trackWidthPx = usableTrackW,
                                        valueRange = valueRange,
                                        step = step,
                                        sensitivity = sensitivity
                                    )
                                    lastEmitted = initialStep
                                    currentOnChange(initialStep)
                                }
                            } else {
                                change.consume()
                                val deltaX = change.position.x - dragAnchorX
                                val nextVal = SliderStepMath.computeDampedDragValue(
                                    startValue = startVal,
                                    totalDeltaPx = deltaX,
                                    trackWidthPx = usableTrackW,
                                    valueRange = valueRange,
                                    step = step,
                                    sensitivity = sensitivity
                                )
                                if (nextVal != lastEmitted) {
                                    lastEmitted = nextVal
                                    currentOnChange(nextVal)
                                }
                            }
                        }
                    } finally {
                        if (dragging) {
                            isActivelyDragging = false
                            currentOnFinish()
                        } else if (!cancelledByVerticalScroll) {
                            val activeReset = currentOnReset
                            if (isDoubleTapCandidate && activeReset != null) {
                                lastTapUpTimeMs = 0L
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                activeReset()
                            } else {
                                val tappedVal = SliderStepMath.computeTapValue(
                                    tapX = downPos.x,
                                    padPx = padPx,
                                    trackWidthPx = usableTrackW,
                                    valueRange = valueRange,
                                    step = step
                                )
                                if (activeReset != null) {
                                    lastTapUpTimeMs = SystemClock.uptimeMillis()
                                    lastTapPos = downPos
                                    // Defer single-tap jump briefly so double-tap resets cleanly with 1 undo entry
                                    pendingSingleTapJob = scope.launch {
                                        delay(singleTapDelayMs)
                                        currentOnChange(tappedVal)
                                        currentOnFinish()
                                        pendingSingleTapJob = null
                                    }
                                } else {
                                    currentOnChange(tappedVal)
                                    currentOnFinish()
                                }
                            }
                        }
                    }
                }
            }
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(trackHeightDp)
        ) {
            val pad = 11.dp.toPx()
            val trackW = (size.width - pad * 2f).coerceAtLeast(1f)
            val trackH = (if (compact) 4.5.dp else 5.5.dp).toPx()
            val cy = size.height * 0.5f
            val corner = CornerRadius(trackH * 0.5f, trackH * 0.5f)

            val trackAlpha = if (enabled) 1f else 0.42f
            val activeTint = if (enabled) accentColor else StudioTextMuted.copy(alpha = 0.45f)

            if (gradientColors != null && gradientColors.size >= 2) {
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        colors = if (enabled) gradientColors else gradientColors.map { it.copy(alpha = 0.4f) },
                        startX = pad,
                        endX = pad + trackW
                    ),
                    topLeft = Offset(pad, cy - trackH * 0.5f),
                    size = Size(trackW, trackH),
                    cornerRadius = corner
                )
            } else {
                // Base inactive track
                drawRoundRect(
                    color = StudioSurfaceHighest.copy(alpha = if (enabled) 1f else 0.55f),
                    topLeft = Offset(pad, cy - trackH * 0.5f),
                    size = Size(trackW, trackH),
                    cornerRadius = corner
                )

                // Active filled portion (from center zero for bipolar sliders, from left for unipolar)
                val startX = if (isBipolar) {
                    pad + minOf(zeroFraction, fraction) * trackW
                } else {
                    pad
                }
                val endX = if (isBipolar) {
                    pad + maxOf(zeroFraction, fraction) * trackW
                } else {
                    pad + fraction * trackW
                }
                val fillW = (endX - startX).coerceAtLeast(0f)
                if (fillW > 0.5f) {
                    drawRoundRect(
                        color = activeTint,
                        topLeft = Offset(startX, cy - trackH * 0.5f),
                        size = Size(fillW, trackH),
                        cornerRadius = corner
                    )
                }
            }

            // Center zero tick mark for bipolar sliders
            if (isBipolar) {
                val zx = pad + zeroFraction * trackW
                drawLine(
                    color = if (gradientColors != null) {
                        StudioBackground.copy(alpha = 0.85f * trackAlpha)
                    } else {
                        Color.White.copy(alpha = 0.35f * trackAlpha)
                    },
                    start = Offset(zx, cy - trackH * 0.95f),
                    end = Offset(zx, cy + trackH * 0.95f),
                    strokeWidth = 1.5.dp.toPx()
                )
            }

            // Precision thumb indicator
            val thumbX = pad + fraction * trackW
            val thumbR = thumbRadiusDp.toPx()
            if (isActivelyDragging && enabled) {
                drawCircle(
                    color = accentColor.copy(alpha = 0.24f),
                    radius = thumbR + 5.dp.toPx(),
                    center = Offset(thumbX, cy)
                )
            }
            drawCircle(
                color = Color.Black.copy(alpha = 0.42f * trackAlpha),
                radius = thumbR + 1.5.dp.toPx(),
                center = Offset(thumbX, cy)
            )
            drawCircle(
                color = Color.White.copy(alpha = if (enabled) 0.96f else 0.55f),
                radius = thumbR,
                center = Offset(thumbX, cy)
            )
            drawCircle(
                color = activeTint,
                radius = (thumbR - 2.3.dp.toPx()).coerceAtLeast(2f),
                center = Offset(thumbX, cy)
            )
        }
    }
}
