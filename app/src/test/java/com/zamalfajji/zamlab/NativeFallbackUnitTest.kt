package com.zamalfajji.zamlab

import com.zamalfajji.zamlab.editor.ColorGradingEngine
import com.zamalfajji.zamlab.nativebridge.NativeBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NativeFallbackUnitTest {

    @Test
    fun `NativeBridge isAvailable handles JVM without UnsatisfiedLinkError`() {
        // On JVM unit test host there is no Android .so; initialization must not throw
        assertFalse(NativeBridge.isAvailable)
    }

    @Test
    fun `reduceNativeHistogram rejects invalid arrays`() {
        assertNull(ColorGradingEngine.reduceNativeHistogram(IntArray(0)))
        assertNull(ColorGradingEngine.reduceNativeHistogram(IntArray(512)))
    }

    @Test
    fun `reduceNativeHistogram downsamples 256-bin native arrays into 48 normalized bins`() {
        val raw = IntArray(1024)
        // Put peak in Red channel at value 255 (bin 47) and half peak in Luma at value 0 (bin 0)
        raw[255] = 100
        raw[256 + 128] = 50
        raw[512 + 64] = 25
        raw[768 + 0] = 50

        val hist = ColorGradingEngine.reduceNativeHistogram(raw)
        assertNotNull(hist)
        hist!!

        assertEquals(48, hist.red.size)
        assertEquals(48, hist.green.size)
        assertEquals(48, hist.blue.size)
        assertEquals(48, hist.luma.size)

        assertEquals(1.0f, hist.red[47], 1e-5f)
        assertEquals(0.5f, hist.luma[0], 1e-5f)
        val expectedGreenBin = (128 * 47) / 255
        assertEquals(0.5f, hist.green[expectedGreenBin], 1e-5f)
        val expectedBlueBin = (64 * 47) / 255
        assertEquals(0.25f, hist.blue[expectedBlueBin], 1e-5f)
    }
}
