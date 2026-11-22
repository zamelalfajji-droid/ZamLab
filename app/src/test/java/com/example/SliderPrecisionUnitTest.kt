package com.example

import com.example.editor.GradingParams
import com.example.gl.DirtyRenderGate
import com.example.gl.GlRenderState
import com.example.ui.components.SliderStepMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SliderPrecisionUnitTest {

    @Test
    fun exposureQuantizesToExactPointZeroFiveSteps() {
        val range = -2f..2f
        assertEquals(0.05f, SliderStepMath.defaultStepForRange(range), 0.0001f)
        assertEquals(0.05f, SliderStepMath.quantize(0.04f, range, 0.05f), 0.0001f)
        assertEquals(0.05f, SliderStepMath.quantize(0.07f, range, 0.05f), 0.0001f)
        assertEquals(0.10f, SliderStepMath.quantize(0.08f, range, 0.05f), 0.0001f)
        assertEquals(-0.35f, SliderStepMath.quantize(-0.34f, range, 0.05f), 0.0001f)
        assertEquals(0f, SliderStepMath.quantize(-0.01f, range, 0.05f), 0.0001f)
        assertEquals(2f, SliderStepMath.quantize(2.9f, range, 0.05f), 0.0001f)
        assertEquals(-2f, SliderStepMath.quantize(-3.1f, range, 0.05f), 0.0001f)
    }

    @Test
    fun bipolarHundredSlidersQuantizeToIntegerSteps() {
        val range = -100f..100f
        assertEquals(1f, SliderStepMath.defaultStepForRange(range), 0.0001f)
        assertEquals(50f, SliderStepMath.quantize(49.7f, range, 1f), 0.0001f)
        assertEquals(-50f, SliderStepMath.quantize(-50.2f, range, 1f), 0.0001f)
        assertEquals(0f, SliderStepMath.quantize(-0.2f, range, 1f), 0.0001f)
        assertEquals(100f, SliderStepMath.quantize(140f, range, 1f), 0.0001f)
        assertEquals(-100f, SliderStepMath.quantize(-120f, range, 1f), 0.0001f)
    }

    @Test
    fun dampedDragReducesSensitivityForSmoothControl() {
        val trackWidth = 300f
        // Dragging 10% of track width on -100..+100 at 0.55x sensitivity shifts by +11 (not +20)
        val nextContrast = SliderStepMath.computeDampedDragValue(
            startValue = 0f,
            totalDeltaPx = 30f,
            trackWidthPx = trackWidth,
            valueRange = -100f..100f,
            step = 1f
        )
        assertEquals(11f, nextContrast, 0.001f)

        // Dragging 10% of track width on Exposure -2..+2 at 0.55x sensitivity shifts by +0.20 EV (quantized to 0.05)
        val nextExposure = SliderStepMath.computeDampedDragValue(
            startValue = 0f,
            totalDeltaPx = 30f,
            trackWidthPx = trackWidth,
            valueRange = -2f..2f,
            step = 0.05f
        )
        assertEquals(0.20f, nextExposure, 0.001f)
    }

    @Test
    fun dirtyRenderGateSchedulesEveryLiveSliderTickImmediately() {
        val gate = DirtyRenderGate()
        val s1 = GlRenderState(null, GradingParams(exposure = 0.05f), null, isOriginal = false, splitRatio = -1f, applyCrop = true)
        val s2 = GlRenderState(null, GradingParams(exposure = 0.10f), null, isOriginal = false, splitRatio = -1f, applyCrop = true)
        val s3 = GlRenderState(null, GradingParams(exposure = 0.15f), null, isOriginal = false, splitRatio = -1f, applyCrop = true)

        assertTrue("First drag tick must immediately schedule a GL draw", gate.submit(s1))
        assertFalse("Duplicate state must not trigger redundant draw", gate.submit(s1))

        val consumed1 = gate.consumeForRender()
        assertNotNull(consumed1)
        assertEquals(0.05f, consumed1!!.params.exposure, 0.0001f)

        // New drag ticks arriving mid-render mark dirty and reschedule immediately after swap
        assertTrue(gate.submit(s2))
        assertFalse(gate.submit(s3))
        val consumedLatest = gate.consumeForRender()
        assertNotNull(consumedLatest)
        assertEquals(0.15f, consumedLatest!!.params.exposure, 0.0001f)
        assertFalse(gate.shouldRescheduleAfterDraw())
    }
}
