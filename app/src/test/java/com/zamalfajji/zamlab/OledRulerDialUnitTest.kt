package com.zamalfajji.zamlab

import androidx.compose.ui.graphics.Color
import com.zamalfajji.zamlab.ui.components.RulerTickMath
import com.zamalfajji.zamlab.ui.components.SliderStepMath
import com.zamalfajji.zamlab.ui.theme.StudioBackground
import com.zamalfajji.zamlab.ui.theme.StudioCinemaAmber
import com.zamalfajji.zamlab.ui.theme.StudioCoolWhite
import com.zamalfajji.zamlab.ui.theme.StudioGraphite
import com.zamalfajji.zamlab.ui.theme.StudioHairline
import com.zamalfajji.zamlab.ui.theme.StudioOledBlack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OledRulerDialUnitTest {

    @Test
    fun `OLED studio palette matches exact pure black, dark-charcoal, hairline, white, and amber-gold specs`() {
        assertEquals(Color(0xFF000000), StudioOledBlack)
        assertEquals(Color(0xFF000000), StudioBackground)
        assertEquals(Color(0xFF121214), StudioGraphite)
        assertEquals(Color(0xFF242428), StudioHairline)
        assertEquals(Color(0xFFFFFFFF), StudioCoolWhite)
        assertEquals(Color(0xFFF5A623), StudioCinemaAmber)
    }

    @Test
    fun `RulerTickMath computes balanced major and minor ticks for bipolar and unipolar ranges`() {
        val exposureSpec = RulerTickMath.tickSpecForRange(-2f..2f, compact = false)
        assertEquals(41, exposureSpec.tickCount)
        assertEquals(5, exposureSpec.majorEvery)
        assertEquals(0.5f, exposureSpec.zeroFraction!!, 0.0001f)

        val compactContrastSpec = RulerTickMath.tickSpecForRange(-100f..100f, compact = true)
        assertEquals(33, compactContrastSpec.tickCount)
        assertEquals(4, compactContrastSpec.majorEvery)
        assertEquals(0.5f, compactContrastSpec.zeroFraction!!, 0.0001f)

        val denoiseSpec = RulerTickMath.tickSpecForRange(0f..100f, compact = true)
        assertEquals(33, denoiseSpec.tickCount)
        assertNull(denoiseSpec.zeroFraction)

        val lutSpec = RulerTickMath.tickSpecForRange(0f..1f, compact = true)
        assertEquals(33, lutSpec.tickCount)
        assertNull(lutSpec.zeroFraction)
    }

    @Test
    fun `RulerTickMath emits throttled haptic detents and always ticks when crossing zero`() {
        // Crossing a discrete tick bucket after minIntervalMs -> emits haptic
        assertTrue(
            RulerTickMath.shouldEmitScrubHaptic(
                prevValue = 0f,
                nextValue = 5f,
                valueRange = 0f..100f,
                lastHapticTimeMs = 100L,
                nowMs = 140L
            )
        )

        // Rapid scrub within throttle window on non-zero values -> suppressed
        assertFalse(
            RulerTickMath.shouldEmitScrubHaptic(
                prevValue = 10f,
                nextValue = 15f,
                valueRange = 0f..100f,
                lastHapticTimeMs = 100L,
                nowMs = 110L
            )
        )

        // Crossing zero anchor on a bipolar slider -> always emits tactile zero detent
        assertTrue(
            RulerTickMath.shouldEmitScrubHaptic(
                prevValue = -0.05f,
                nextValue = 0f,
                valueRange = -2f..2f,
                lastHapticTimeMs = 100L,
                nowMs = 105L
            )
        )

        // Identical values -> never emits haptic
        assertFalse(
            RulerTickMath.shouldEmitScrubHaptic(
                prevValue = 25f,
                nextValue = 25f,
                valueRange = 0f..100f,
                lastHapticTimeMs = 0L,
                nowMs = 1000L
            )
        )
    }

    @Test
    fun `SliderStepMath quantizes Exposure, Denoise, LUT Strength, and Crop Straighten accurately`() {
        assertEquals(0.05f, SliderStepMath.defaultStepForRange(-2f..2f), 0.0001f)
        assertEquals(0.45f, SliderStepMath.quantize(0.44f, -2f..2f, 0.05f), 0.0001f)
        assertEquals(0f, SliderStepMath.quantize(-0.005f, -2f..2f, 0.05f), 0.0001f)

        assertEquals(1f, SliderStepMath.defaultStepForRange(0f..100f), 0.0001f)
        assertEquals(50f, SliderStepMath.quantize(50.3f, 0f..100f, 1f), 0.0001f)

        assertEquals(0.01f, SliderStepMath.defaultStepForRange(0f..1f), 0.0001f)
        assertEquals(0.85f, SliderStepMath.quantize(0.854f, 0f..1f, 0.01f), 0.0001f)

        assertEquals(1.5f, SliderStepMath.quantize(1.48f, -45f..45f, 0.1f), 0.0001f)
    }
}
