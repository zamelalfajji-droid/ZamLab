package com.zamalfajji.zamlab.ui.components

import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zamalfajji.zamlab.R
import com.zamalfajji.zamlab.ui.theme.StudioAmberPillFill
import com.zamalfajji.zamlab.ui.theme.StudioCinemaAmber
import com.zamalfajji.zamlab.ui.theme.StudioCoolWhite
import com.zamalfajji.zamlab.ui.theme.StudioFrostedBorder
import com.zamalfajji.zamlab.ui.theme.StudioFrostedGlass
import com.zamalfajji.zamlab.ui.theme.StudioGraphite
import com.zamalfajji.zamlab.ui.theme.StudioHairline
import com.zamalfajji.zamlab.ui.theme.StudioMutedSlate
import com.zamalfajji.zamlab.ui.theme.StudioTextMuted
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

data class RulerTickSpec(
    val tickCount: Int,
    val majorEvery: Int,
    val midEvery: Int,
    val zeroFraction: Float?
)

/**
 * Pure tick-mark layout and haptic detent math for Z-Effect's unified OLED precision ruler dials.
 */
object RulerTickMath {
    const val DEFAULT_TICK_COUNT: Int = 41
    const val DEFAULT_MAJOR_EVERY: Int = 5
    const val MIN_HAPTIC_INTERVAL_MS: Long = 28L

    fun tickSpecForRange(
        valueRange: ClosedFloatingPointRange<Float>,
        compact: Boolean = false
    ): RulerTickSpec {
        val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(1e-4f)
        val isBipolar = valueRange.start < 0f && valueRange.endInclusive > 0f
        val zeroFrac = if (isBipolar) (-valueRange.start / span).coerceIn(0f, 1f) else null
        val count = if (compact) 33 else DEFAULT_TICK_COUNT
        val majorEvery = if (compact) 4 else DEFAULT_MAJOR_EVERY
        return RulerTickSpec(
            tickCount = count,
            majorEvery = majorEvery,
            midEvery = (majorEvery / 2).coerceAtLeast(1),
            zeroFraction = zeroFrac
        )
    }

    /**
     * Returns true when scrubbing from [prevValue] to [nextValue] crosses a tactile ruler tick
     * detent or snaps across zero, respecting [minIntervalMs] throttling so haptics feel crisp.
     */
    fun shouldEmitScrubHaptic(
        prevValue: Float,
        nextValue: Float,
        valueRange: ClosedFloatingPointRange<Float>,
        lastHapticTimeMs: Long,
        nowMs: Long,
        detentCount: Int = 40,
        minIntervalMs: Long = MIN_HAPTIC_INTERVAL_MS
    ): Boolean {
        if (prevValue == nextValue) return false
        val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(1e-4f)
        val isBipolar = valueRange.start < 0f && valueRange.endInclusive > 0f
        if (isBipolar) {
            val crossedZero = (prevValue < 0f && nextValue >= 0f) ||
                (prevValue > 0f && nextValue <= 0f) ||
                (abs(prevValue) > 1e-4f && abs(nextValue) <= 1e-4f)
            if (crossedZero) return true
        }
        if (nowMs - lastHapticTimeMs < minIntervalMs) return false
        val prevBucket = (((prevValue - valueRange.start) / span) * detentCount).roundToInt()
        val nextBucket = (((nextValue - valueRange.start) / span) * detentCount).roundToInt()
        return prevBucket != nextBucket
    }
}

/**
 * Reliable universal double-tap-to-reset modifier for slider labels, numeric value pills, and readouts.
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
 * Wide, tactile OLED scrolling tick-mark ruler dial for Z-Effect's Adjust Focus Dock.
 *
 * Features:
 * - Bold white parameter label on left + tabular monospace value pill centered directly above
 *   the fixed glowing amber-gold (#F5A623) center needle (e.g., `+0.45 EV`, `+15`, `50`).
 * - Major & minor vertical ruler ticks scrolling under the fixed center needle inside an elevated
 *   #121214 dark-charcoal pill tray with subtle 1dp #242428 hairline border.
 * - Tactile haptic tick feedback while scrubbing across detents and zero anchor.
 * - Smooth animated double-tap reset anywhere on the ruler, label, or readout pill.
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

    val isPillHighlighted = isModified || isActivelyDragging
    val pillBgColor by animateColorAsState(
        targetValue = if (isPillHighlighted) StudioAmberPillFill else StudioFrostedGlass,
        animationSpec = tween(durationMillis = 140),
        label = "ruler_pill_bg"
    )
    val pillBorderColor by animateColorAsState(
        targetValue = when {
            isActivelyDragging -> StudioCinemaAmber
            isModified -> StudioCinemaAmber.copy(alpha = 0.78f)
            else -> StudioFrostedBorder
        },
        animationSpec = tween(durationMillis = 140),
        label = "ruler_pill_border"
    )
    val trayBorderColor by animateColorAsState(
        targetValue = if (isActivelyDragging) StudioCinemaAmber.copy(alpha = 0.72f) else StudioFrostedBorder,
        animationSpec = tween(durationMillis = 140),
        label = "ruler_tray_border"
    )

    val padPx = with(density) { 12.dp.toPx() }
    val doubleTapSlopPx = with(density) { 48.dp.toPx() }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        // Header row: Bold white parameter label (left), crisp tabular monospace value pill (center), reset hint (right)
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
                        tint = if (isModified || isActivelyDragging) StudioCinemaAmber else StudioCoolWhite.copy(alpha = 0.80f),
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.2.sp
                        ),
                        color = if (isModified || isActivelyDragging) StudioCoolWhite else StudioCoolWhite.copy(alpha = 0.88f),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Crisp tabular monospace value pill centered directly above the glowing amber needle
            Surface(
                shape = RoundedCornerShape(50),
                color = pillBgColor,
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
                    if (isModified || isActivelyDragging) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(StudioCinemaAmber)
                        )
                    }
                    Text(
                        text = formattedValue,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            fontFeatureSettings = "tnum"
                        ),
                        color = if (isModified || isActivelyDragging) StudioCoolWhite else StudioMutedSlate,
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

        // Wide Translucent Frosted-Glass scrolling tick-mark ruler with glowing Amber-Gold needle
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(StudioFrostedGlass)
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
                    var lastHapticMs = 0L
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
                                        val nowMs = SystemClock.uptimeMillis()
                                        if (RulerTickMath.shouldEmitScrubHaptic(
                                                prevValue = lastEmitted,
                                                nextValue = initialStep,
                                                valueRange = valueRange,
                                                lastHapticTimeMs = lastHapticMs,
                                                nowMs = nowMs
                                            )
                                        ) {
                                            lastHapticMs = nowMs
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
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
                                        val nowMs = SystemClock.uptimeMillis()
                                        if (RulerTickMath.shouldEmitScrubHaptic(
                                                prevValue = lastEmitted,
                                                nextValue = nextVal,
                                                valueRange = valueRange,
                                                lastHapticTimeMs = lastHapticMs,
                                                nowMs = nowMs
                                            )
                                        ) {
                                            lastHapticMs = nowMs
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
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

                val usableW = (w - 24.dp.toPx()).coerceAtLeast(1f)
                val pxPerUnit = usableW / (span * sensitivity).coerceAtLeast(1e-4f)

                val minorTickUnit = when {
                    span <= 4.05f -> 0.05f
                    span <= 105f -> 2f
                    else -> 2.5f
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
                        alpha = 0.68f
                    )
                }

                val zeroX = centerX + (zeroAnchor - value) * pxPerUnit
                if (abs(value - zeroAnchor) > step * 0.25f) {
                    val clampedZeroX = zeroX.coerceIn(0f, w)
                    val barStart = minOf(clampedZeroX, centerX)
                    val barEnd = maxOf(clampedZeroX, centerX)
                    val barH = 2.5.dp.toPx()
                    drawRoundRect(
                        color = StudioCinemaAmber.copy(alpha = 0.34f),
                        topLeft = Offset(barStart, cy - barH * 0.5f),
                        size = Size((barEnd - barStart).coerceAtLeast(1f), barH),
                        cornerRadius = CornerRadius(barH * 0.5f, barH * 0.5f)
                    )
                }

                val minorStroke = 1.15.dp.toPx()
                val majorStroke = 1.65.dp.toPx()
                val activeMin = minOf(zeroAnchor, value)
                val activeMax = maxOf(zeroAnchor, value)

                for (idx in minIdx..maxIdx) {
                    val tickVal = zeroAnchor + idx * minorTickUnit
                    if (tickVal < valueRange.start - 1e-4f || tickVal > valueRange.endInclusive + 1e-4f) continue
                    val x = centerX + (tickVal - value) * pxPerUnit
                    if (x < -4f || x > w + 4f) continue

                    val distRatio = (abs(x - centerX) / (w * 0.5f)).coerceIn(0f, 1f)
                    val edgeAlpha = (1f - distRatio * distRatio * 0.84f).coerceIn(0.10f, 1f)

                    val isZeroTick = idx == 0
                    val isMajor = idx % majorEvery == 0
                    val isMid = !isMajor && idx % midEvery == 0
                    val isInActiveSpan = abs(value - zeroAnchor) > step * 0.25f &&
                        tickVal >= activeMin - 1e-4f &&
                        tickVal <= activeMax + 1e-4f

                    val halfTickH = when {
                        isZeroTick -> 13.5.dp.toPx()
                        isMajor -> 10.5.dp.toPx()
                        isMid -> 7.5.dp.toPx()
                        else -> 5.dp.toPx()
                    }
                    val baseColor = when {
                        isZeroTick && isModified -> StudioCinemaAmber
                        isZeroTick -> StudioCoolWhite.copy(alpha = 0.86f)
                        isInActiveSpan -> StudioCinemaAmber.copy(alpha = if (isMajor) 0.94f else 0.58f)
                        isMajor -> StudioCoolWhite.copy(alpha = 0.36f)
                        isMid -> StudioCoolWhite.copy(alpha = 0.22f)
                        else -> StudioMutedSlate.copy(alpha = 0.22f)
                    }
                    drawLine(
                        color = baseColor.copy(alpha = baseColor.alpha * edgeAlpha),
                        start = Offset(x, cy - halfTickH),
                        end = Offset(x, cy + halfTickH),
                        strokeWidth = if (isMajor || isZeroTick) majorStroke else minorStroke,
                        cap = StrokeCap.Round
                    )
                }

                // Translucent frosted-glass edge vignette fade
                val fadeW = 28.dp.toPx()
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(StudioFrostedGlass, Color.Transparent),
                        startX = 0f,
                        endX = fadeW
                    ),
                    topLeft = Offset.Zero,
                    size = Size(fadeW, h)
                )
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color.Transparent, StudioFrostedGlass),
                        startX = w - fadeW,
                        endX = w
                    ),
                    topLeft = Offset(w - fadeW, 0f),
                    size = Size(fadeW, h)
                )

                // Soft glowing Amber-Gold aura behind center needle
                val glowRadius = if (isActivelyDragging) 20.dp.toPx() else 14.dp.toPx()
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            StudioCinemaAmber.copy(alpha = if (isActivelyDragging) 0.34f else 0.18f),
                            Color.Transparent
                        ),
                        center = Offset(centerX, cy),
                        radius = glowRadius
                    ),
                    radius = glowRadius,
                    center = Offset(centerX, cy)
                )

                // Fixed Glowing Amber-Gold center needle
                val needleHalfH = if (isActivelyDragging) 17.dp.toPx() else 15.dp.toPx()
                val needleW = if (isActivelyDragging) 2.8.dp.toPx() else 2.2.dp.toPx()
                drawRoundRect(
                    color = Color.Black.copy(alpha = 0.65f),
                    topLeft = Offset(centerX - needleW * 0.5f - 1.2.dp.toPx(), cy - needleHalfH - 1.dp.toPx()),
                    size = Size(needleW + 2.4.dp.toPx(), needleHalfH * 2f + 2.dp.toPx()),
                    cornerRadius = CornerRadius(needleW, needleW)
                )
                drawRoundRect(
                    color = StudioCinemaAmber,
                    topLeft = Offset(centerX - needleW * 0.5f, cy - needleHalfH),
                    size = Size(needleW, needleHalfH * 2f),
                    cornerRadius = CornerRadius(needleW, needleW)
                )
                drawCircle(
                    color = StudioCinemaAmber,
                    radius = if (isModified || isActivelyDragging) 2.6.dp.toPx() else 1.9.dp.toPx(),
                    center = Offset(centerX, cy - needleHalfH - 1.5.dp.toPx())
                )
            }
        }
    }
}

/**
 * Unified OLED Precision Ruler-Dial Track shared across ALL adjustment panels
 * (AllParametersPanel, DenoisePanel, MaskPanel, LutPanel, CropPanel, HSL/Color Grading, HealPanel).
 *
 * Renders an elevated dark-charcoal (#121214) horizontal tick-mark ruler with major & minor ticks,
 * glowing amber-gold (#F5A623) value needle, active span illumination, haptic tick scrubbing feedback,
 * damped relative horizontal drag, vertical-scroll yield, and reliable double-tap reset.
 */
@Composable
internal fun StudioPrecisionTrack(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    step: Float = SliderStepMath.defaultStepForRange(valueRange),
    enabled: Boolean = true,
    accentColor: Color = StudioCinemaAmber,
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
    val tickSpec = remember(valueRange, compact) {
        RulerTickMath.tickSpecForRange(valueRange, compact = compact)
    }

    DisposableEffect(Unit) {
        onDispose {
            pendingSingleTapJob?.cancel()
            pendingSingleTapJob = null
        }
    }

    val trayBorderColor by animateColorAsState(
        targetValue = when {
            !enabled -> StudioHairline.copy(alpha = 0.55f)
            isActivelyDragging -> StudioCinemaAmber.copy(alpha = 0.78f)
            else -> StudioHairline
        },
        animationSpec = tween(durationMillis = 120),
        label = "precision_ruler_border"
    )

    val trackHeightDp = if (compact) 36.dp else 42.dp
    val padPx = with(density) { 12.dp.toPx() }
    val doubleTapSlopPx = with(density) { 48.dp.toPx() }
    val trayShape = RoundedCornerShape(10.dp)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(trackHeightDp)
            .clip(trayShape)
            .background(StudioGraphite.copy(alpha = if (enabled) 0.68f else 0.42f))
            .border(1.dp, trayBorderColor, trayShape)
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
                var lastHapticMs = 0L
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
                                    cancelledByVerticalScroll = true
                                    lastTapUpTimeMs = 0L
                                    break
                                }
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
                                    val nowMs = SystemClock.uptimeMillis()
                                    if (RulerTickMath.shouldEmitScrubHaptic(
                                            prevValue = lastEmitted,
                                            nextValue = initialStep,
                                            valueRange = valueRange,
                                            lastHapticTimeMs = lastHapticMs,
                                            nowMs = nowMs
                                        )
                                    ) {
                                        lastHapticMs = nowMs
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
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
                                    val nowMs = SystemClock.uptimeMillis()
                                    if (RulerTickMath.shouldEmitScrubHaptic(
                                            prevValue = lastEmitted,
                                            nextValue = nextVal,
                                            valueRange = valueRange,
                                            lastHapticTimeMs = lastHapticMs,
                                            nowMs = nowMs
                                        )
                                    ) {
                                        lastHapticMs = nowMs
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
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
            val w = size.width
            val h = size.height
            val pad = 12.dp.toPx()
            val trackW = (w - pad * 2f).coerceAtLeast(1f)
            val cy = h * 0.5f

            val trackAlpha = if (enabled) 1f else 0.40f
            val needleColor = if (enabled) StudioCinemaAmber else StudioTextMuted.copy(alpha = 0.5f)
            val spanAccent = if (enabled) {
                if (accentColor == StudioMutedSlate) StudioCinemaAmber else accentColor
            } else {
                StudioTextMuted.copy(alpha = 0.40f)
            }

            // Optional color spectrum hairline along bottom edge (Temp, Tint, Saturation, HSL)
            if (gradientColors != null && gradientColors.size >= 2) {
                val railH = 2.2.dp.toPx()
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        colors = if (enabled) gradientColors else gradientColors.map { it.copy(alpha = 0.35f) },
                        startX = pad,
                        endX = pad + trackW
                    ),
                    topLeft = Offset(pad, h - railH - 2.dp.toPx()),
                    size = Size(trackW, railH),
                    cornerRadius = CornerRadius(railH * 0.5f, railH * 0.5f),
                    alpha = 0.78f
                )
            }

            // Subtle active span bar connecting anchor (0 for bipolar, start for unipolar) to needle
            val activeMinFrac = if (isBipolar) minOf(zeroFraction, fraction) else 0f
            val activeMaxFrac = if (isBipolar) maxOf(zeroFraction, fraction) else fraction
            val spanStartX = pad + activeMinFrac * trackW
            val spanEndX = pad + activeMaxFrac * trackW
            val spanW = (spanEndX - spanStartX).coerceAtLeast(0f)
            if (spanW > 1f) {
                val barH = 2.dp.toPx()
                drawRoundRect(
                    color = spanAccent.copy(alpha = 0.34f * trackAlpha),
                    topLeft = Offset(spanStartX, cy - barH * 0.5f),
                    size = Size(spanW, barH),
                    cornerRadius = CornerRadius(barH * 0.5f, barH * 0.5f)
                )
            }

            // Draw crisp major & minor horizontal ruler tick marks
            val tickCount = tickSpec.tickCount
            val lastIdx = (tickCount - 1).coerceAtLeast(1)
            val minorStroke = 1.0.dp.toPx()
            val majorStroke = 1.45.dp.toPx()
            val minorHalfH = (if (compact) 4.2.dp else 5.2.dp).toPx()
            val midHalfH = (if (compact) 6.2.dp else 7.4.dp).toPx()
            val majorHalfH = (if (compact) 8.4.dp else 9.8.dp).toPx()
            val zeroHalfH = (if (compact) 10.2.dp else 11.8.dp).toPx()

            for (i in 0 until tickCount) {
                val tFrac = i.toFloat() / lastIdx.toFloat()
                val x = pad + tFrac * trackW
                val isZeroTick = isBipolar && abs(tFrac - zeroFraction) < (0.5f / lastIdx.toFloat())
                val isMajor = i % tickSpec.majorEvery == 0
                val isMid = !isMajor && i % tickSpec.midEvery == 0
                val inActiveSpan = spanW > 1f &&
                    tFrac >= activeMinFrac - 0.004f &&
                    tFrac <= activeMaxFrac + 0.004f

                val halfH = when {
                    isZeroTick -> zeroHalfH
                    isMajor -> majorHalfH
                    isMid -> midHalfH
                    else -> minorHalfH
                }
                val tickColor = when {
                    isZeroTick && spanW > 1f -> spanAccent.copy(alpha = 0.95f * trackAlpha)
                    isZeroTick -> StudioCoolWhite.copy(alpha = 0.80f * trackAlpha)
                    inActiveSpan -> spanAccent.copy(alpha = (if (isMajor) 0.92f else 0.56f) * trackAlpha)
                    isMajor -> StudioCoolWhite.copy(alpha = 0.34f * trackAlpha)
                    isMid -> StudioCoolWhite.copy(alpha = 0.22f * trackAlpha)
                    else -> StudioMutedSlate.copy(alpha = 0.22f * trackAlpha)
                }

                drawLine(
                    color = tickColor,
                    start = Offset(x, cy - halfH),
                    end = Offset(x, cy + halfH),
                    strokeWidth = if (isMajor || isZeroTick) majorStroke else minorStroke,
                    cap = StrokeCap.Round
                )
            }

            // Glowing Amber-Gold value needle positioned at current value
            val needleX = pad + fraction * trackW
            val needleHalfH = (if (compact) {
                if (isActivelyDragging) 12.5.dp else 10.8.dp
            } else {
                if (isActivelyDragging) 14.5.dp else 12.5.dp
            }).toPx()
            val needleW = (if (isActivelyDragging) 2.8.dp else 2.2.dp).toPx()

            if (enabled) {
                val glowRadius = (if (isActivelyDragging) 15.dp else 10.dp).toPx()
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            needleColor.copy(alpha = if (isActivelyDragging) 0.38f else 0.18f),
                            Color.Transparent
                        ),
                        center = Offset(needleX, cy),
                        radius = glowRadius
                    ),
                    radius = glowRadius,
                    center = Offset(needleX, cy)
                )
            }

            // Dark cutout halo so the amber needle stays razor-sharp against ticks
            drawRoundRect(
                color = Color.Black.copy(alpha = 0.72f * trackAlpha),
                topLeft = Offset(needleX - needleW * 0.5f - 1.2.dp.toPx(), cy - needleHalfH - 1.dp.toPx()),
                size = Size(needleW + 2.4.dp.toPx(), needleHalfH * 2f + 2.dp.toPx()),
                cornerRadius = CornerRadius(needleW, needleW)
            )
            // Warm Amber-Gold vertical needle
            drawRoundRect(
                color = needleColor,
                topLeft = Offset(needleX - needleW * 0.5f, cy - needleHalfH),
                size = Size(needleW, needleHalfH * 2f),
                cornerRadius = CornerRadius(needleW, needleW)
            )
            // Luminous top crown dot
            drawCircle(
                color = needleColor,
                radius = (if (isActivelyDragging) 2.4.dp else 1.8.dp).toPx(),
                center = Offset(needleX, cy - needleHalfH - 1.2.dp.toPx())
            )
        }
    }
}
