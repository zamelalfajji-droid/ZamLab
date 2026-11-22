package com.example

import com.example.editor.ColorGradingEngine
import com.example.editor.GradingParams
import com.example.editor.StudioTab
import com.example.lut.LutImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XmpNativePresetUnitTest {

    @Test
    fun studioTabEnumHasExactlySixNonAiTabs() {
        val names = StudioTab.entries.map { it.name }
        assertEquals(
            listOf("ADJUST", "ALL", "LUTS", "CROP", "HEAL", "MASK"),
            names
        )
        assertFalse(names.contains("AI"))
    }

    @Test
    fun decodeNativeXmpToGradingParamsMapsAllThirteenFieldsAndToneCurve() {
        val packed = floatArrayOf(
            0.75f,   // Exposure2012
            24f,     // Contrast2012
            -35f,    // Highlights2012
            42f,     // Shadows2012
            18f,     // Whites2012
            -14f,    // Blacks2012
            12f,     // Temperature
            -6f,     // Tint
            28f,     // Vibrance
            -10f,    // Saturation
            15f,     // Clarity2012
            22f,     // Dehaze
            3f,      // ToneCurvePV2012 count
            0.0f, 0.05f,
            0.5f, 0.56f,
            1.0f, 0.96f
        )

        val decoded = ColorGradingEngine.decodeNativeXmpToGradingParams(packed)

        assertEquals(0.75f, decoded.exposure, 0.001f)
        assertEquals(24f, decoded.contrast, 0.001f)
        assertEquals(-35f, decoded.highlights, 0.001f)
        assertEquals(42f, decoded.shadows, 0.001f)
        assertEquals(18f, decoded.whites, 0.001f)
        assertEquals(-14f, decoded.blacks, 0.001f)
        assertEquals(12f, decoded.temperature, 0.001f)
        assertEquals(-6f, decoded.tint, 0.001f)
        assertEquals(28f, decoded.vibrance, 0.001f)
        assertEquals(-10f, decoded.saturation, 0.001f)
        assertEquals(15f, decoded.clarity, 0.001f)
        assertEquals(22f, decoded.dehaze, 0.001f)
        assertEquals(3, decoded.toneCurve.master.size)
        assertEquals(0.05f, decoded.toneCurve.master[0].y, 0.001f)
        assertEquals(0.56f, decoded.toneCurve.master[1].y, 0.001f)
        assertEquals(0.96f, decoded.toneCurve.master[2].y, 0.001f)
    }

    @Test
    fun parseXmpPresetToGradingParamsKotlinFallbackReadsLightroomXmlTags() {
        val sampleXmp = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description
                    xmlns:crs="http://ns.adobe.com/camera-raw-settings/1.0/"
                    crs:Exposure2012="+0.60"
                    crs:Contrast2012="18"
                    crs:Highlights2012="-28"
                    crs:Shadows2012="34"
                    crs:Whites2012="11"
                    crs:Blacks2012="-16"
                    crs:Temperature="6400"
                    crs:Tint="8"
                    crs:Vibrance="20"
                    crs:Saturation="-5"
                    crs:Clarity2012="14"
                    crs:Dehaze="12">
                  <crs:ToneCurvePV2012>
                    <rdf:Seq>
                      <rdf:li>0, 12</rdf:li>
                      <rdf:li>128, 140</rdf:li>
                      <rdf:li>255, 245</rdf:li>
                    </rdf:Seq>
                  </crs:ToneCurvePV2012>
                </rdf:Description>
              </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()

        val params = ColorGradingEngine.parseXmpPresetToGradingParams(
            fd = null,
            fallbackXmpContent = sampleXmp,
            baseParams = GradingParams()
        )

        assertNotNull(params)
        val p = params!!
        assertEquals(0.60f, p.exposure, 0.001f)
        assertEquals(18f, p.contrast, 0.001f)
        assertEquals(-28f, p.highlights, 0.001f)
        assertEquals(34f, p.shadows, 0.001f)
        assertEquals(11f, p.whites, 0.001f)
        assertEquals(-16f, p.blacks, 0.001f)
        assertEquals(20f, p.temperature, 0.01f) // (6400 - 5500) / 45 = 20
        assertEquals(8f, p.tint, 0.001f)
        assertEquals(20f, p.vibrance, 0.001f)
        assertEquals(-5f, p.saturation, 0.001f)
        assertEquals(14f, p.clarity, 0.001f)
        assertEquals(12f, p.dehaze, 0.001f)
        assertEquals(3, p.toneCurve.master.size)
        assertEquals(12f / 255f, p.toneCurve.master[0].y, 0.002f)
        assertEquals(140f / 255f, p.toneCurve.master[1].y, 0.002f)
        assertEquals(245f / 255f, p.toneCurve.master[2].y, 0.002f)
    }

    @Test
    fun validatePresetExtensionAllowsCubeXmpZipAndRejectsOtherTypes() {
        assertEquals("cube", LutImporter.validatePresetExtension("Kodak_2383.cube"))
        assertEquals("xmp", LutImporter.validatePresetExtension("Portra_400.XMP"))
        assertEquals("zip", LutImporter.validatePresetExtension("CinemaPack.zip"))
        assertNull(LutImporter.validatePresetExtension("extensionless_document"))

        var rejectedJpg = false
        try {
            LutImporter.validatePresetExtension("holiday_photo.jpg")
        } catch (_: IllegalArgumentException) {
            rejectedJpg = true
        }
        assertTrue(rejectedJpg)

        var rejectedTxt = false
        try {
            LutImporter.validatePresetExtension("notes.txt")
        } catch (_: IllegalArgumentException) {
            rejectedTxt = true
        }
        assertTrue(rejectedTxt)
    }
}
