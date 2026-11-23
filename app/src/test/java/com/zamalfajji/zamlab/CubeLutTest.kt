package com.zamalfajji.zamlab

import com.zamalfajji.zamlab.editor.CameraProfiles
import com.zamalfajji.zamlab.editor.ColorGradingData
import com.zamalfajji.zamlab.editor.ColorWheelValue
import com.zamalfajji.zamlab.editor.GradingParams
import com.zamalfajji.zamlab.editor.HslBand
import com.zamalfajji.zamlab.editor.PointColorData
import com.zamalfajji.zamlab.editor.ProfileCategory
import com.zamalfajji.zamlab.editor.ToneCurveData
import com.zamalfajji.zamlab.editor.ToneCurvePoint
import com.zamalfajji.zamlab.editor.WhiteBalancePreset
import com.zamalfajji.zamlab.lut.CubeLutParser
import com.zamalfajji.zamlab.lut.LutSourceFormat
import com.zamalfajji.zamlab.lut.LutType
import com.zamalfajji.zamlab.lut.XmpPresetParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class CubeLutTest {

    @Test
    fun `parses 3D cube LUT and performs trilinear interpolation`() {
        val cubeText = """
            # Test 2x2x2 LUT
            TITLE "Identity 2x2"
            LUT_3D_SIZE 2
            DOMAIN_MIN 0.0 0.0 0.0
            DOMAIN_MAX 1.0 1.0 1.0
            0.0 0.0 0.0
            1.0 0.0 0.0
            0.0 1.0 0.0
            1.0 1.0 0.0
            0.0 0.0 1.0
            1.0 0.0 1.0
            0.0 1.0 1.0
            1.0 1.0 1.0
        """.trimIndent()

        val lut = CubeLutParser.parse(
            ByteArrayInputStream(cubeText.toByteArray()),
            id = "test_id",
            fallbackTitle = "Fallback"
        )

        assertEquals("Identity 2x2", lut.title)
        assertEquals(LutType.LUT_3D, lut.type)
        assertEquals(2, lut.size)

        val out = FloatArray(3)
        lut.sample(0.25f, 0.5f, 0.75f, out)
        assertEquals(0.25f, out[0], 1e-4f)
        assertEquals(0.5f, out[1], 1e-4f)
        assertEquals(0.75f, out[2], 1e-4f)
    }

    @Test
    fun `throws descriptive error on incomplete cube file`() {
        val badCube = """
            LUT_3D_SIZE 4
            0.0 0.0 0.0
        """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            CubeLutParser.parse(
                ByteArrayInputStream(badCube.toByteArray()),
                id = "bad",
                fallbackTitle = "Bad"
            )
        }
    }

    @Test
    fun `parses Lightroom XMP preset and bakes into 3D LUT`() {
        val xmpText = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
             <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
              <rdf:Description rdf:about=""
                xmlns:crs="http://ns.adobe.com/camera-raw-settings/1.0/"
                crs:Exposure2012="+0.50"
                crs:Contrast2012="20"
                crs:Saturation="-15"
                crs:IncrementalTemperature="25">
               <crs:Name>
                <rdf:Alt>
                 <rdf:li xml:lang="x-default">Golden Cinema XMP</rdf:li>
                </rdf:Alt>
               </crs:Name>
               <crs:ToneCurvePV2012>
                <rdf:Seq>
                 <rdf:li>0, 12</rdf:li>
                 <rdf:li>255, 248</rdf:li>
                </rdf:Seq>
               </crs:ToneCurvePV2012>
              </rdf:Description>
             </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()

        val lut = XmpPresetParser.parseToCubeLut(
            ByteArrayInputStream(xmpText.toByteArray()),
            id = "xmp_test",
            fallbackTitle = "Fallback XMP"
        )

        assertEquals("Golden Cinema XMP", lut.title)
        assertEquals(LutSourceFormat.XMP, lut.sourceFormat)
        assertEquals(LutType.LUT_3D, lut.type)

        val out = FloatArray(3)
        lut.sample(0.5f, 0.5f, 0.5f, out)
        // Warm temperature + positive exposure should make mid-gray brighter and warmer (R > B)
        assertTrue(out[0] > 0.5f)
        assertTrue(out[0] > out[2])
    }

    @Test
    fun `ignores Local mask attributes inside MaskGroupBasedCorrections and trims Group`() {
        val xmpWithMasks = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
             <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
              <rdf:Description rdf:about=""
                xmlns:crs="http://ns.adobe.com/camera-raw-settings/1.0/"
                crs:Exposure2012="-0.80"
                crs:Contrast2012="+30"
                crs:IncrementalTemperature="+40"
                crs:Saturation="0">
               <crs:Name><rdf:Alt><rdf:li xml:lang="x-default">Masked Film</rdf:li></rdf:Alt></crs:Name>
               <crs:Group><rdf:Alt><rdf:li xml:lang="x-default">35MM Film </rdf:li></rdf:Alt></crs:Group>
               <crs:MaskGroupBasedCorrections>
                <rdf:Seq>
                 <rdf:li>
                  <rdf:Description
                    crs:LocalExposure2012="0"
                    crs:LocalContrast2012="0"
                    crs:LocalTemperature="0"
                    crs:LocalSaturation="0" />
                 </rdf:li>
                </rdf:Seq>
               </crs:MaskGroupBasedCorrections>
              </rdf:Description>
             </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()

        val lut = XmpPresetParser.parseToCubeLut(
            ByteArrayInputStream(xmpWithMasks.toByteArray()),
            id = "xmp_masked",
            fallbackTitle = "Fallback"
        )

        assertEquals("Masked Film", lut.title)
        assertEquals("35MM Film", lut.category)

        val out = FloatArray(3)
        lut.sample(0.5f, 0.5f, 0.5f, out)
        // Negative global exposure (-0.80 EV) must NOT be zeroed out by LocalExposure2012="0"
        assertTrue("Expected darkened midtone (< 0.45), got ${out[1]}", out[1] < 0.45f)
        // Warm global temperature (+40) must NOT be zeroed out by LocalTemperature="0"
        assertTrue("Expected warm tint (R > B)", out[0] > out[2])
    }

    @Test
    fun `applies ConvertToGrayscale and HSL saturation adjustments`() {
        val bwXmp = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
             <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
              <rdf:Description rdf:about=""
                xmlns:crs="http://ns.adobe.com/camera-raw-settings/1.0/"
                crs:ConvertToGrayscale="True"
                crs:Contrast2012="15">
               <crs:Name><rdf:Alt><rdf:li xml:lang="x-default">Noir Mono</rdf:li></rdf:Alt></crs:Name>
              </rdf:Description>
             </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()

        val lut = XmpPresetParser.parseToCubeLut(
            ByteArrayInputStream(bwXmp.toByteArray()),
            id = "xmp_bw",
            fallbackTitle = "Noir"
        )

        val out = FloatArray(3)
        lut.sample(0.9f, 0.2f, 0.1f, out)
        assertEquals(out[0], out[1], 1e-3f)
        assertEquals(out[1], out[2], 1e-3f)
    }

    @Test
    fun `parses extended Lightroom XMP fields and scales GradingParams by intensity`() {
        val fullXmp = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
             <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
              <rdf:Description rdf:about=""
                xmlns:crs="http://ns.adobe.com/camera-raw-settings/1.0/"
                crs:Exposure2012="+0.60"
                crs:Contrast2012="+20"
                crs:Highlights2012="-40"
                crs:Shadows2012="+30"
                crs:Whites2012="+10"
                crs:Blacks2012="-16"
                crs:Texture="+12"
                crs:Clarity2012="+24"
                crs:Dehaze="+18"
                crs:PostCropVignetteAmount="-30"
                crs:GrainAmount="40"
                crs:Sharpness="50"
                crs:LuminanceSmoothing="20"
                crs:LensManualDistortionAmount="-10"
                crs:DefringePurpleAmount="4"
                crs:RedHue="+14"
                crs:BlueSaturation="-22"
                crs:HueAdjustmentOrange="-10"
                crs:SaturationAdjustmentAqua="+30">
               <crs:Name><rdf:Alt><rdf:li xml:lang="x-default">Editorial</rdf:li></rdf:Alt></crs:Name>
               <crs:ToneCurvePV2012>
                <rdf:Seq>
                 <rdf:li>0, 20</rdf:li>
                 <rdf:li>128, 148</rdf:li>
                 <rdf:li>255, 255</rdf:li>
                </rdf:Seq>
               </crs:ToneCurvePV2012>
              </rdf:Description>
             </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()

        val lut = XmpPresetParser.parseToCubeLut(
            ByteArrayInputStream(fullXmp.toByteArray()),
            id = "xmp_editorial",
            fallbackTitle = "Editorial"
        )

        val settings = lut.xmpSettings
        assertNotNull(settings)
        settings!!
        assertEquals(24f, settings.clarity, 0.01f)
        assertEquals(18f, settings.dehaze, 0.01f)
        assertEquals(-30f, settings.vignette, 0.01f)
        assertEquals(40f, settings.grain, 0.01f)
        assertEquals(50f, settings.sharpening, 0.01f)
        assertEquals(20f, settings.noiseReduction, 0.01f)
        assertEquals(-10f, settings.lensDistortion, 0.01f)
        assertEquals(20f, settings.chromaticAberration, 0.01f)
        assertEquals(14f, settings.redHue, 0.01f)
        assertEquals(-22f, settings.blueSaturation, 0.01f)

        val half = GradingParams.fromXmpSettings(settings, intensity = 0.5f)
        assertEquals(0.30f, half.exposure, 0.01f)
        assertEquals(10f, half.contrast, 0.01f)
        assertEquals(-20f, half.highlights, 0.01f)
        assertEquals(15f, half.shadows, 0.01f)
        assertEquals(12f, half.clarity, 0.01f)
        assertEquals(9f, half.dehaze, 0.01f)
        assertEquals(-15f, half.vignette, 0.01f)
        assertEquals(20f, half.grain, 0.01f)
        assertEquals(25f, half.sharpening, 0.01f)
        assertEquals(10f, half.noiseReduction, 0.01f)
        assertEquals(-5f, half.hsl[HslBand.ORANGE].hue, 0.01f)
        assertEquals(15f, half.hsl[HslBand.AQUA].saturation, 0.01f)

        // Tone curve point 0 -> scaled halfway between y=0 and y=20/255
        assertEquals((10f / 255f), half.toneCurve.master.first().y, 0.005f)

        assertTrue(half.hasLightEdits)
        assertTrue(half.hasColorEdits)
        assertTrue(half.hasEffectsEdits)
        assertTrue(half.hasDetailEdits)
        assertTrue(half.hasOpticsCalibrationEdits)

        val noEffects = half.resetEffects()
        assertFalse(noEffects.hasEffectsEdits)
        assertTrue(noEffects.hasLightEdits)
        assertTrue(noEffects.hasOpticsEdits)
        assertTrue(noEffects.hasCalibrationEdits)
        assertFalse(noEffects.hasLensBlurEdits)

        val withBlur = noEffects.copy(lensBlurAmount = 65f, bokehFocus = 30f)
        assertTrue(withBlur.hasLensBlurEdits)
        val afterBlurReset = withBlur.resetLensBlur()
        assertFalse(afterBlurReset.hasLensBlurEdits)
        assertEquals(0f, afterBlurReset.lensBlurAmount, 0.01f)
        assertEquals(50f, afterBlurReset.bokehFocus, 0.01f)

        val afterOpticsReset = afterBlurReset.resetOptics()
        assertFalse(afterOpticsReset.hasOpticsEdits)
        assertTrue(afterOpticsReset.hasCalibrationEdits)

        val afterCalReset = afterOpticsReset.resetCalibration()
        assertFalse(afterCalReset.hasCalibrationEdits)
    }

    @Test
    fun `ToneCurveData builds smooth monotonic cubic spline LUT without overshoot`() {
        val sCurve = listOf(
            ToneCurvePoint(0f, 0f),
            ToneCurvePoint(0.25f, 0.16f),
            ToneCurvePoint(0.50f, 0.50f),
            ToneCurvePoint(0.75f, 0.84f),
            ToneCurvePoint(1f, 1f)
        )
        val lut = ToneCurveData.buildLut(sCurve)
        assertEquals(256, lut.size)
        assertEquals(0f, lut[0], 0.005f)
        assertEquals(0.16f, lut[64], 0.01f)
        assertEquals(0.50f, lut[128], 0.01f)
        assertEquals(0.84f, lut[191], 0.015f)
        assertEquals(1f, lut[255], 0.005f)

        // Monotonically non-decreasing across entire range
        for (i in 1 until 256) {
            assertTrue("Expected monotonic curve at index $i", lut[i] >= lut[i - 1] - 1e-4f)
            assertTrue(lut[i] in 0f..1f)
        }
    }

    @Test
    fun `CameraProfiles defines all 22 profiles across 5 exact categories`() {
        assertEquals(22, CameraProfiles.ALL.size)
        assertEquals(6, CameraProfiles.byCategory(ProfileCategory.BASIC).size)
        assertEquals(4, CameraProfiles.byCategory(ProfileCategory.FILM).size)
        assertEquals(4, CameraProfiles.byCategory(ProfileCategory.CINEMATIC).size)
        assertEquals(4, CameraProfiles.byCategory(ProfileCategory.MUTED).size)
        assertEquals(4, CameraProfiles.byCategory(ProfileCategory.BW).size)

        val defaultColor = CameraProfiles.byId(CameraProfiles.DEFAULT_ID)
        assertEquals("Color", defaultColor.name)
        assertFalse(defaultColor.isCreative)

        assertTrue(CameraProfiles.byId("profile_teal_amber").isCreative)
        assertTrue(CameraProfiles.byId("profile_sepia_tone").isCreative)
        assertTrue(CameraProfiles.byId("profile_mono_red_filter").monoMix == 1f)
    }

    @Test
    fun `WhiteBalancePreset menu options and PointColor ColorGrading resetColor semantics`() {
        assertEquals(8, WhiteBalancePreset.MENU_OPTIONS.size)
        assertFalse(WhiteBalancePreset.CUSTOM in WhiteBalancePreset.MENU_OPTIONS)

        val edited = GradingParams(
            exposure = 0.5f,
            whiteBalancePreset = WhiteBalancePreset.TUNGSTEN,
            temperature = WhiteBalancePreset.TUNGSTEN.temperature,
            tint = WhiteBalancePreset.TUNGSTEN.tint,
            pointColor = PointColorData(sourceHueDeg = 210f, isSampled = true, hueShift = 35f),
            colorGrading = ColorGradingData(
                shadows = ColorWheelValue(hueDeg = 220f, saturation = 45f),
                highlights = ColorWheelValue(hueDeg = 35f, saturation = 30f),
                blending = 72f,
                balance = -25f
            )
        )

        assertTrue(edited.hasColorEdits)
        assertTrue(edited.hasLightEdits)

        val clearedColor = edited.resetColor()
        assertFalse(clearedColor.hasColorEdits)
        assertTrue(clearedColor.hasLightEdits)
        assertEquals(WhiteBalancePreset.AS_SHOT, clearedColor.whiteBalancePreset)
        assertFalse(clearedColor.pointColor.isModified)
        assertTrue(clearedColor.colorGrading.isDefault)
    }
}
