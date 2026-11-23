package com.zamalfajji.zamlab

import com.zamalfajji.zamlab.editor.ColorGradingEngine
import com.zamalfajji.zamlab.editor.GradingParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HalationBloomUnitTest {

    @Test
    fun `GradingParams tracks halation and bloom in hasEffectsEdits and resetEffects`() {
        val defaults = GradingParams()
        assertEquals(0f, defaults.halation, 1e-5f)
        assertEquals(0f, defaults.bloom, 1e-5f)
        assertFalse(defaults.hasEffectsEdits)
        assertTrue(defaults.isDefault)

        val withHalation = defaults.copy(halation = 45f)
        assertTrue(withHalation.hasEffectsEdits)
        assertFalse(withHalation.isDefault)

        val withBloom = defaults.copy(bloom = 60f)
        assertTrue(withBloom.hasEffectsEdits)
        assertFalse(withBloom.isDefault)

        val reset = defaults.copy(halation = 55f, bloom = 75f).resetEffects()
        assertEquals(0f, reset.halation, 1e-5f)
        assertEquals(0f, reset.bloom, 1e-5f)
        assertFalse(reset.hasEffectsEdits)
    }

    @Test
    fun `applyHalationBloomPixels is no-op when both amounts are zero`() {
        val w = 16
        val h = 16
        val pixels = IntArray(w * h) { 0xFF102030.toInt() }
        pixels[8 * w + 8] = 0xFFFFFFFF.toInt()
        val expected = pixels.copyOf()

        ColorGradingEngine.applyHalationBloomPixels(pixels, w, h, 0f, 0f)

        assertTrue(expected.contentEquals(pixels))
    }

    @Test
    fun `applyHalationBloomPixels produces warm red-orange halation scatter and preserves alpha`() {
        val w = 24
        val h = 24
        val bg = 0xAA181818.toInt()
        val pixels = IntArray(w * h) { bg }
        // Bright 2x2 highlight core at center
        for (y in 11..12) {
            for (x in 11..12) {
                pixels[y * w + x] = 0xAAFFFFFF.toInt()
            }
        }

        ColorGradingEngine.applyHalationBloomPixels(pixels, w, h, halNorm = 1.0f, bloomNorm = 0.0f)

        // Neighboring pixel (2px away) should receive warm red-orange halation scatter (R > G > B)
        val neighbor = pixels[11 * w + 9]
        val alpha = (neighbor ushr 24) and 0xFF
        val r = (neighbor ushr 16) and 0xFF
        val g = (neighbor ushr 8) and 0xFF
        val b = neighbor and 0xFF

        assertEquals(0xAA, alpha)
        assertTrue("Expected red channel lifted above base 0x18, got $r", r > 0x18)
        assertTrue("Expected halation red-dominant scatter (R=$r, G=$g, B=$b)", r > g && g >= b)
    }

    @Test
    fun `applyHalationBloomPixels produces neutral highlight bloom and leaves dark-only frames unchanged`() {
        val w = 24
        val h = 24
        val bg = 0xFF202020.toInt()
        val darkOnly = IntArray(w * h) { bg }
        val darkExpected = darkOnly.copyOf()

        ColorGradingEngine.applyHalationBloomPixels(darkOnly, w, h, halNorm = 0.8f, bloomNorm = 0.8f)
        assertTrue("Dark-only pixels below threshold should remain unchanged", darkExpected.contentEquals(darkOnly))

        val pixels = IntArray(w * h) { bg }
        for (y in 11..12) {
            for (x in 11..12) {
                pixels[y * w + x] = 0xFFFFFFFF.toInt()
            }
        }

        ColorGradingEngine.applyHalationBloomPixels(pixels, w, h, halNorm = 0.0f, bloomNorm = 1.0f)

        val neighbor = pixels[11 * w + 9]
        val alpha = (neighbor ushr 24) and 0xFF
        val r = (neighbor ushr 16) and 0xFF
        val g = (neighbor ushr 8) and 0xFF
        val b = neighbor and 0xFF

        assertEquals(0xFF, alpha)
        assertTrue("Expected bloom to lift neighboring luminance above 0x20 (R=$r, G=$g, B=$b)", r > 0x20 && g > 0x20 && b > 0x20)
        assertTrue("Expected neutral white bloom (R=$r, G=$g, B=$b)", kotlin.math.abs(r - g) <= 2 && kotlin.math.abs(g - b) <= 2)
    }
}
