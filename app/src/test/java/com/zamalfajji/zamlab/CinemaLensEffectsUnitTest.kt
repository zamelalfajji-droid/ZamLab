package com.zamalfajji.zamlab

import com.zamalfajji.zamlab.editor.ColorGradingEngine
import com.zamalfajji.zamlab.editor.GradingParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CinemaLensEffectsUnitTest {

    @Test
    fun `GradingParams tracks chromaticAberration and anamorphicStreak under Effects and resets cleanly`() {
        val defaults = GradingParams()
        assertEquals(0f, defaults.chromaticAberration, 1e-5f)
        assertEquals(0f, defaults.anamorphicStreak, 1e-5f)
        assertFalse(defaults.hasEffectsEdits)
        assertFalse(defaults.hasOpticsEdits)
        assertTrue(defaults.isDefault)

        val negativeCa = defaults.copy(chromaticAberration = -35f)
        assertTrue(negativeCa.hasEffectsEdits)
        assertFalse(negativeCa.hasOpticsEdits)
        assertFalse(negativeCa.isDefault)

        val positiveCa = defaults.copy(chromaticAberration = 42f)
        assertTrue(positiveCa.hasEffectsEdits)

        val withStreak = defaults.copy(anamorphicStreak = 75f)
        assertTrue(withStreak.hasEffectsEdits)
        assertFalse(withStreak.isDefault)

        val reset = defaults.copy(
            chromaticAberration = -40f,
            anamorphicStreak = 85f,
            lensDistortion = 25f
        ).resetEffects()
        assertEquals(0f, reset.chromaticAberration, 1e-5f)
        assertEquals(0f, reset.anamorphicStreak, 1e-5f)
        assertFalse(reset.hasEffectsEdits)
        assertTrue(reset.hasOpticsEdits)
        assertEquals(25f, reset.lensDistortion, 1e-5f)
    }

    @Test
    fun `applyChromaticAberrationPixels is no-op at zero`() {
        val w = 32
        val h = 32
        val pixels = IntArray(w * h) { idx ->
            val x = idx % w
            if (x >= w / 2) 0xFFEEEEEE.toInt() else 0xFF202020.toInt()
        }
        val expected = pixels.copyOf()

        ColorGradingEngine.applyChromaticAberrationPixels(pixels, w, h, 0f)

        assertTrue(expected.contentEquals(pixels))
    }

    @Test
    fun `applyChromaticAberrationPixels shifts R and B oppositely from center and preserves green and alpha`() {
        // Use a sufficiently large image (200x200) so 1.2% radial shift exceeds 1px near edges
        val w = 200
        val h = 200
        val base = IntArray(w * h) { idx ->
            val x = idx % w
            // High-contrast gray step at x = 160 (far from center x = 100) and gray background
            if (x >= 160) 0xAAE0E0E0.toInt() else 0xAA202020.toInt()
        }

        val posPixels = base.copyOf()
        ColorGradingEngine.applyChromaticAberrationPixels(posPixels, w, h, 1.0f)

        // At edge boundary x = 160, y = 100:
        // Positive shift moves R sampling outward (into 0xE0) and B sampling inward (into 0x20)
        val posEdgePx = posPixels[100 * w + 160]
        val posA = (posEdgePx ushr 24) and 0xFF
        val posR = (posEdgePx ushr 16) and 0xFF
        val posG = (posEdgePx ushr 8) and 0xFF
        val posB = posEdgePx and 0xFF

        assertEquals(0xAA, posA)
        assertEquals(0xE0, posG)
        assertTrue("Expected radial R/B separation at edge (R=$posR, B=$posB)", posR > posB)

        // Exact center neighborhood stays untouched
        val centerPx = posPixels[100 * w + 100]
        assertEquals(base[100 * w + 100], centerPx)

        // Negative amount (-50 -> -1.0f) reverses R and B shift directions
        val negPixels = base.copyOf()
        ColorGradingEngine.applyChromaticAberrationPixels(negPixels, w, h, -1.0f)
        val negEdgePx = negPixels[100 * w + 160]
        val negR = (negEdgePx ushr 16) and 0xFF
        val negB = negEdgePx and 0xFF
        assertTrue("Expected reversed R/B separation for negative CA (R=$negR, B=$negB)", negB > negR)
        assertNotEquals(posEdgePx, negEdgePx)
    }

    @Test
    fun `applyAnamorphicStreakPixels streaks cool cyan horizontally from bright highlights and leaves dark frames untouched`() {
        val w = 48
        val h = 48
        val bg = 0xAA141414.toInt()
        val darkOnly = IntArray(w * h) { bg }
        val darkExpected = darkOnly.copyOf()

        ColorGradingEngine.applyAnamorphicStreakPixels(darkOnly, w, h, 0f)
        assertTrue(darkExpected.contentEquals(darkOnly))

        ColorGradingEngine.applyAnamorphicStreakPixels(darkOnly, w, h, 1.0f)
        assertTrue("Dark pixels below highlight threshold must remain untouched", darkExpected.contentEquals(darkOnly))

        val pixels = IntArray(w * h) { bg }
        // Bright highlight core along center rows y = 23..24, x = 23..24
        for (y in 23..24) {
            for (x in 23..24) {
                pixels[y * w + x] = 0xAAFFFFFF.toInt()
            }
        }

        ColorGradingEngine.applyAnamorphicStreakPixels(pixels, w, h, 1.0f)

        // Same horizontal scanline y = 23, 5 pixels to the right (x = 29): should receive cyan/blue streak
        val streakPx = pixels[23 * w + 29]
        val alpha = (streakPx ushr 24) and 0xFF
        val r = (streakPx ushr 16) and 0xFF
        val g = (streakPx ushr 8) and 0xFF
        val b = streakPx and 0xFF

        assertEquals(0xAA, alpha)
        assertTrue("Expected additive lift along highlight row (R=$r, G=$g, B=$b)", b > 0x14 && g > 0x14)
        assertTrue("Expected cool cyan/blue streak tint (B >= G > R, got R=$r G=$g B=$b)", b >= g && g > r)

        // Vertically distant row (y = 2, x = 29) has no horizontal streak bleed
        assertEquals(bg, pixels[2 * w + 29])
    }
}
