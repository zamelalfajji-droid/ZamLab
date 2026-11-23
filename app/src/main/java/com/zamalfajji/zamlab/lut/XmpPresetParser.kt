package com.zamalfajji.zamlab.lut

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

data class XmpDevelopSettings(
    val name: String,
    val group: String = "",
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val whites: Float = 0f,
    val blacks: Float = 0f,
    val parametricShadows: Float = 0f,
    val parametricDarks: Float = 0f,
    val parametricLights: Float = 0f,
    val parametricHighlights: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val saturation: Float = 0f,
    val vibrance: Float = 0f,
    val texture: Float = 0f,
    val clarity: Float = 0f,
    val dehaze: Float = 0f,
    val vignette: Float = 0f,
    val vignetteStyleIndex: Int = 0,
    val vignetteMidpoint: Float = 50f,
    val vignetteFeather: Float = 50f,
    val vignetteRoundness: Float = 0f,
    val vignetteHighlights: Float = 0f,
    val grain: Float = 0f,
    val grainSize: Float = 25f,
    val grainRoughness: Float = 50f,
    val sharpening: Float = 0f,
    val sharpenRadius: Float = 1.0f,
    val sharpenDetail: Float = 25f,
    val sharpenMasking: Float = 0f,
    val noiseReduction: Float = 0f,
    val noiseReductionDetail: Float = 50f,
    val noiseReductionContrast: Float = 0f,
    val colorNoiseReduction: Float = 0f,
    val colorNoiseReductionDetail: Float = 50f,
    val colorNoiseReductionSmoothness: Float = 50f,
    val lensDistortion: Float = 0f,
    val chromaticAberration: Float = 0f,
    val redHue: Float = 0f,
    val redSaturation: Float = 0f,
    val greenHue: Float = 0f,
    val greenSaturation: Float = 0f,
    val blueHue: Float = 0f,
    val blueSaturation: Float = 0f,
    val shadowTint: Float = 0f,
    val convertToGrayscale: Boolean = false,
    val splitShadowHue: Float = 0f,
    val splitShadowSat: Float = 0f,
    val splitHighlightHue: Float = 0f,
    val splitHighlightSat: Float = 0f,
    val splitBalance: Float = 0f,
    val hueAdjustments: FloatArray = FloatArray(8),
    val satAdjustments: FloatArray = FloatArray(8),
    val lumAdjustments: FloatArray = FloatArray(8),
    val toneCurveMaster: List<Pair<Float, Float>> = emptyList(),
    val toneCurveRed: List<Pair<Float, Float>> = emptyList(),
    val toneCurveGreen: List<Pair<Float, Float>> = emptyList(),
    val toneCurveBlue: List<Pair<Float, Float>> = emptyList(),
    val hasRgbTableOnly: Boolean = false
)

object XmpPresetParser {

    private const val BAKED_LUT_SIZE = 17

    // Lightroom 8-channel HSL hue centers in degrees
    private val HSL_BAND_CENTERS = floatArrayOf(0f, 30f, 60f, 120f, 180f, 240f, 285f, 330f)

    fun parseToCubeLut(
        inputStream: InputStream,
        id: String,
        fallbackTitle: String,
        fallbackCategory: String = "Presets",
        bakeTable: Boolean = true
    ): CubeLut {
        val settings = parseXmp(inputStream, fallbackTitle)
        if (settings.hasRgbTableOnly && !hasAnyDevelopAdjustment(settings)) {
            throw IllegalArgumentException(
                "\"${settings.name}\" uses a proprietary binary Adobe RGBTable. Please import its .cube file instead."
            )
        }
        val resolvedCategory = settings.group.ifBlank { fallbackCategory }.trim().ifBlank { "Presets" }
        return if (bakeTable) {
            bakeSettingsToCubeLut(settings, id, resolvedCategory).copy(xmpSettings = settings)
        } else {
            // Identity 2x2x2 table placeholder; editable XMP parameters are applied directly via xmpSettings
            val identity2 = floatArrayOf(
                0f, 0f, 0f,
                1f, 0f, 0f,
                0f, 1f, 0f,
                1f, 1f, 0f,
                0f, 0f, 1f,
                1f, 0f, 1f,
                0f, 1f, 1f,
                1f, 1f, 1f
            )
            CubeLut(
                id = id,
                title = settings.name,
                category = resolvedCategory,
                type = LutType.LUT_3D,
                size = 2,
                domainMin = floatArrayOf(0f, 0f, 0f),
                domainMax = floatArrayOf(1f, 1f, 1f),
                table = identity2,
                sourceFormat = LutSourceFormat.XMP,
                xmpSettings = settings
            )
        }
    }

    internal fun parseXmp(inputStream: InputStream, fallbackTitle: String): XmpDevelopSettings {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }
        val doc = factory.newDocumentBuilder().parse(inputStream)
        val root = doc.documentElement
            ?: throw IllegalArgumentException("Invalid or empty XMP document.")
        root.normalize()

        var name = fallbackTitle
        var group = ""
        var exposure = 0f
        var contrast = 0f
        var highlights = 0f
        var shadows = 0f
        var whites = 0f
        var blacks = 0f
        var parametricShadows = 0f
        var parametricDarks = 0f
        var parametricLights = 0f
        var parametricHighlights = 0f
        var temperature = 0f
        var tint = 0f
        var saturation = 0f
        var vibrance = 0f
        var texture = 0f
        var clarity = 0f
        var dehaze = 0f
        var postCropVignette: Float? = null
        var lensVignette: Float? = null
        var vignetteStyleIndex = 0
        var vignetteMidpoint = 50f
        var vignetteFeather = 50f
        var vignetteRoundness = 0f
        var vignetteHighlights = 0f
        var grain = 0f
        var grainSize = 25f
        var grainRoughness = 50f
        var sharpening = 0f
        var sharpenRadius = 1.0f
        var sharpenDetail = 25f
        var sharpenMasking = 0f
        var noiseReduction = 0f
        var noiseReductionDetail = 50f
        var noiseReductionContrast = 0f
        var colorNoiseReduction = 0f
        var colorNoiseReductionDetail = 50f
        var colorNoiseReductionSmoothness = 50f
        var lensDistortion = 0f
        var defringePurple = 0f
        var autoLateralCa = 0f
        var redHue = 0f
        var redSaturation = 0f
        var greenHue = 0f
        var greenSaturation = 0f
        var blueHue = 0f
        var blueSaturation = 0f
        var shadowTint = 0f
        var convertToGrayscale = false
        var splitShadowHue = 0f
        var splitShadowSat = 0f
        var splitHighlightHue = 0f
        var splitHighlightSat = 0f
        var splitBalance = 0f
        val hueAdj = FloatArray(8)
        val satAdj = FloatArray(8)
        val lumAdj = FloatArray(8)
        var hasRgbTable = false

        val masterCurve = mutableListOf<Pair<Float, Float>>()
        val redCurve = mutableListOf<Pair<Float, Float>>()
        val greenCurve = mutableListOf<Pair<Float, Float>>()
        val blueCurve = mutableListOf<Pair<Float, Float>>()

        fun inspectElement(el: Element) {
            val tagName = el.tagName.orEmpty()
            val localTag = tagName.substringAfter(':')

            // Never let localized mask adjustments (LocalExposure2012, LocalContrast2012, etc.)
            // overwrite top-level global preset develop settings.
            if (localTag == "MaskGroupBasedCorrections" ||
                localTag == "CorrectionMasks" ||
                localTag == "Correction" ||
                localTag == "CorrectionRangeMask"
            ) {
                return
            }

            val attrs = el.attributes
            for (i in 0 until attrs.length) {
                val attr = attrs.item(i)
                val attrName = attr.nodeName.orEmpty()
                val localAttr = attrName.substringAfter(':')
                val attrVal = attr.nodeValue.orEmpty()
                when (localAttr) {
                    "Name" -> if (attrVal.isNotBlank()) name = attrVal.trim()
                    "Group" -> if (attrVal.isNotBlank()) group = attrVal.trim()
                    "Exposure2012", "Exposure" -> {
                        attrVal.toFloatOrNull()?.let { exposure = it.coerceIn(-4f, 4f) }
                    }
                    "Contrast2012", "Contrast" -> {
                        attrVal.toFloatOrNull()?.let { contrast = it.coerceIn(-100f, 100f) }
                    }
                    "Highlights2012" -> {
                        attrVal.toFloatOrNull()?.let { highlights = it.coerceIn(-100f, 100f) }
                    }
                    "Shadows2012" -> {
                        attrVal.toFloatOrNull()?.let { shadows = it.coerceIn(-100f, 100f) }
                    }
                    "Whites2012" -> {
                        attrVal.toFloatOrNull()?.let { whites = it.coerceIn(-100f, 100f) }
                    }
                    "Blacks2012" -> {
                        attrVal.toFloatOrNull()?.let { blacks = it.coerceIn(-100f, 100f) }
                    }
                    "ParametricShadows" -> {
                        attrVal.toFloatOrNull()?.let { parametricShadows = it.coerceIn(-100f, 100f) }
                    }
                    "ParametricDarks" -> {
                        attrVal.toFloatOrNull()?.let { parametricDarks = it.coerceIn(-100f, 100f) }
                    }
                    "ParametricLights" -> {
                        attrVal.toFloatOrNull()?.let { parametricLights = it.coerceIn(-100f, 100f) }
                    }
                    "ParametricHighlights" -> {
                        attrVal.toFloatOrNull()?.let { parametricHighlights = it.coerceIn(-100f, 100f) }
                    }
                    "IncrementalTemperature" -> {
                        attrVal.toFloatOrNull()?.let { temperature = it.coerceIn(-100f, 100f) }
                    }
                    "Temperature" -> {
                        attrVal.toFloatOrNull()?.let { raw ->
                            temperature = if (raw > 500f) {
                                ((raw - 5500f) / 45f).coerceIn(-100f, 100f)
                            } else {
                                raw.coerceIn(-100f, 100f)
                            }
                        }
                    }
                    "IncrementalTint", "Tint" -> {
                        attrVal.toFloatOrNull()?.let { tint = it.coerceIn(-100f, 100f) }
                    }
                    "Saturation" -> {
                        attrVal.toFloatOrNull()?.let { saturation = it.coerceIn(-100f, 100f) }
                    }
                    "Vibrance" -> {
                        attrVal.toFloatOrNull()?.let { vibrance = it.coerceIn(-100f, 100f) }
                    }
                    "Texture" -> {
                        attrVal.toFloatOrNull()?.let { texture = it.coerceIn(-100f, 100f) }
                    }
                    "Clarity2012", "Clarity" -> {
                        attrVal.toFloatOrNull()?.let { clarity = it.coerceIn(-100f, 100f) }
                    }
                    "Dehaze" -> {
                        attrVal.toFloatOrNull()?.let { dehaze = it.coerceIn(-100f, 100f) }
                    }
                    "PostCropVignetteAmount" -> {
                        attrVal.toFloatOrNull()?.let { postCropVignette = it.coerceIn(-100f, 100f) }
                    }
                    "PostCropVignetteStyle" -> {
                        attrVal.toIntOrNull()?.let { rawStyle ->
                            // Lightroom XMP: 1 = Highlight Priority, 2 = Color Priority, 3 = Paint Overlay
                            vignetteStyleIndex = (rawStyle - 1).coerceIn(0, 2)
                        }
                    }
                    "PostCropVignetteMidpoint" -> {
                        attrVal.toFloatOrNull()?.let { vignetteMidpoint = it.coerceIn(0f, 100f) }
                    }
                    "PostCropVignetteFeather" -> {
                        attrVal.toFloatOrNull()?.let { vignetteFeather = it.coerceIn(0f, 100f) }
                    }
                    "PostCropVignetteRoundness" -> {
                        attrVal.toFloatOrNull()?.let { vignetteRoundness = it.coerceIn(-100f, 100f) }
                    }
                    "PostCropVignetteHighlights" -> {
                        attrVal.toFloatOrNull()?.let { vignetteHighlights = it.coerceIn(0f, 100f) }
                    }
                    "VignetteAmount" -> {
                        attrVal.toFloatOrNull()?.let { lensVignette = it.coerceIn(-100f, 100f) }
                    }
                    "GrainAmount" -> {
                        attrVal.toFloatOrNull()?.let { grain = it.coerceIn(0f, 100f) }
                    }
                    "GrainSize" -> {
                        attrVal.toFloatOrNull()?.let { grainSize = it.coerceIn(0f, 100f) }
                    }
                    "GrainRoughness" -> {
                        attrVal.toFloatOrNull()?.let { grainRoughness = it.coerceIn(0f, 100f) }
                    }
                    "Sharpness" -> {
                        attrVal.toFloatOrNull()?.let { sharpening = it.coerceIn(0f, 150f) }
                    }
                    "SharpenRadius" -> {
                        attrVal.toFloatOrNull()?.let { sharpenRadius = it.coerceIn(0.5f, 3.0f) }
                    }
                    "SharpenDetail" -> {
                        attrVal.toFloatOrNull()?.let { sharpenDetail = it.coerceIn(0f, 100f) }
                    }
                    "SharpenEdgeMasking" -> {
                        attrVal.toFloatOrNull()?.let { sharpenMasking = it.coerceIn(0f, 100f) }
                    }
                    "LuminanceSmoothing" -> {
                        attrVal.toFloatOrNull()?.let { noiseReduction = it.coerceIn(0f, 100f) }
                    }
                    "LuminanceNoiseReductionDetail" -> {
                        attrVal.toFloatOrNull()?.let { noiseReductionDetail = it.coerceIn(0f, 100f) }
                    }
                    "LuminanceNoiseReductionContrast" -> {
                        attrVal.toFloatOrNull()?.let { noiseReductionContrast = it.coerceIn(0f, 100f) }
                    }
                    "ColorNoiseReduction" -> {
                        attrVal.toFloatOrNull()?.let { colorNoiseReduction = it.coerceIn(0f, 100f) }
                    }
                    "ColorNoiseReductionDetail" -> {
                        attrVal.toFloatOrNull()?.let { colorNoiseReductionDetail = it.coerceIn(0f, 100f) }
                    }
                    "ColorNoiseReductionSmoothness" -> {
                        attrVal.toFloatOrNull()?.let { colorNoiseReductionSmoothness = it.coerceIn(0f, 100f) }
                    }
                    "LensManualDistortionAmount" -> {
                        attrVal.toFloatOrNull()?.let { lensDistortion = it.coerceIn(-100f, 100f) }
                    }
                    "DefringePurpleAmount" -> {
                        attrVal.toFloatOrNull()?.let { defringePurple = (it * 5f).coerceIn(0f, 100f) }
                    }
                    "AutoLateralCA" -> {
                        attrVal.toFloatOrNull()?.let { autoLateralCa = if (it > 0f) 25f else 0f }
                    }
                    "RedHue" -> attrVal.toFloatOrNull()?.let { redHue = it.coerceIn(-100f, 100f) }
                    "RedSaturation" -> attrVal.toFloatOrNull()?.let { redSaturation = it.coerceIn(-100f, 100f) }
                    "GreenHue" -> attrVal.toFloatOrNull()?.let { greenHue = it.coerceIn(-100f, 100f) }
                    "GreenSaturation" -> attrVal.toFloatOrNull()?.let { greenSaturation = it.coerceIn(-100f, 100f) }
                    "BlueHue" -> attrVal.toFloatOrNull()?.let { blueHue = it.coerceIn(-100f, 100f) }
                    "BlueSaturation" -> attrVal.toFloatOrNull()?.let { blueSaturation = it.coerceIn(-100f, 100f) }
                    "ShadowTint" -> attrVal.toFloatOrNull()?.let { shadowTint = it.coerceIn(-100f, 100f) }
                    "ConvertToGrayscale" -> {
                        convertToGrayscale = attrVal.trim().equals("true", ignoreCase = true)
                    }
                    "SplitToningShadowHue" -> {
                        attrVal.toFloatOrNull()?.let { splitShadowHue = it.coerceIn(0f, 360f) }
                    }
                    "SplitToningShadowSaturation" -> {
                        attrVal.toFloatOrNull()?.let { splitShadowSat = it.coerceIn(0f, 100f) }
                    }
                    "SplitToningHighlightHue" -> {
                        attrVal.toFloatOrNull()?.let { splitHighlightHue = it.coerceIn(0f, 360f) }
                    }
                    "SplitToningHighlightSaturation" -> {
                        attrVal.toFloatOrNull()?.let { splitHighlightSat = it.coerceIn(0f, 100f) }
                    }
                    "SplitToningBalance" -> {
                        attrVal.toFloatOrNull()?.let { splitBalance = it.coerceIn(-100f, 100f) }
                    }
                    "HueAdjustmentRed" -> attrVal.toFloatOrNull()?.let { hueAdj[0] = it.coerceIn(-100f, 100f) }
                    "HueAdjustmentOrange" -> attrVal.toFloatOrNull()?.let { hueAdj[1] = it.coerceIn(-100f, 100f) }
                    "HueAdjustmentYellow" -> attrVal.toFloatOrNull()?.let { hueAdj[2] = it.coerceIn(-100f, 100f) }
                    "HueAdjustmentGreen" -> attrVal.toFloatOrNull()?.let { hueAdj[3] = it.coerceIn(-100f, 100f) }
                    "HueAdjustmentAqua" -> attrVal.toFloatOrNull()?.let { hueAdj[4] = it.coerceIn(-100f, 100f) }
                    "HueAdjustmentBlue" -> attrVal.toFloatOrNull()?.let { hueAdj[5] = it.coerceIn(-100f, 100f) }
                    "HueAdjustmentPurple" -> attrVal.toFloatOrNull()?.let { hueAdj[6] = it.coerceIn(-100f, 100f) }
                    "HueAdjustmentMagenta" -> attrVal.toFloatOrNull()?.let { hueAdj[7] = it.coerceIn(-100f, 100f) }
                    "SaturationAdjustmentRed" -> attrVal.toFloatOrNull()?.let { satAdj[0] = it.coerceIn(-100f, 100f) }
                    "SaturationAdjustmentOrange" -> attrVal.toFloatOrNull()?.let { satAdj[1] = it.coerceIn(-100f, 100f) }
                    "SaturationAdjustmentYellow" -> attrVal.toFloatOrNull()?.let { satAdj[2] = it.coerceIn(-100f, 100f) }
                    "SaturationAdjustmentGreen" -> attrVal.toFloatOrNull()?.let { satAdj[3] = it.coerceIn(-100f, 100f) }
                    "SaturationAdjustmentAqua" -> attrVal.toFloatOrNull()?.let { satAdj[4] = it.coerceIn(-100f, 100f) }
                    "SaturationAdjustmentBlue" -> attrVal.toFloatOrNull()?.let { satAdj[5] = it.coerceIn(-100f, 100f) }
                    "SaturationAdjustmentPurple" -> attrVal.toFloatOrNull()?.let { satAdj[6] = it.coerceIn(-100f, 100f) }
                    "SaturationAdjustmentMagenta" -> attrVal.toFloatOrNull()?.let { satAdj[7] = it.coerceIn(-100f, 100f) }
                    "LuminanceAdjustmentRed" -> attrVal.toFloatOrNull()?.let { lumAdj[0] = it.coerceIn(-100f, 100f) }
                    "LuminanceAdjustmentOrange" -> attrVal.toFloatOrNull()?.let { lumAdj[1] = it.coerceIn(-100f, 100f) }
                    "LuminanceAdjustmentYellow" -> attrVal.toFloatOrNull()?.let { lumAdj[2] = it.coerceIn(-100f, 100f) }
                    "LuminanceAdjustmentGreen" -> attrVal.toFloatOrNull()?.let { lumAdj[3] = it.coerceIn(-100f, 100f) }
                    "LuminanceAdjustmentAqua" -> attrVal.toFloatOrNull()?.let { lumAdj[4] = it.coerceIn(-100f, 100f) }
                    "LuminanceAdjustmentBlue" -> attrVal.toFloatOrNull()?.let { lumAdj[5] = it.coerceIn(-100f, 100f) }
                    "LuminanceAdjustmentPurple" -> attrVal.toFloatOrNull()?.let { lumAdj[6] = it.coerceIn(-100f, 100f) }
                    "LuminanceAdjustmentMagenta" -> attrVal.toFloatOrNull()?.let { lumAdj[7] = it.coerceIn(-100f, 100f) }
                    "RGBTable" -> hasRgbTable = true
                }
            }

            when (localTag) {
                "Name" -> extractFirstLiText(el)?.let { name = it }
                "Group" -> extractFirstLiText(el)?.let { group = it }
                "ToneCurvePV2012", "ToneCurve" -> extractCurvePoints(el, masterCurve)
                "ToneCurvePV2012Red", "ToneCurveRed" -> extractCurvePoints(el, redCurve)
                "ToneCurvePV2012Green", "ToneCurveGreen" -> extractCurvePoints(el, greenCurve)
                "ToneCurvePV2012Blue", "ToneCurveBlue" -> extractCurvePoints(el, blueCurve)
                "RGBTable" -> hasRgbTable = true
            }

            val children = el.childNodes
            for (i in 0 until children.length) {
                val child = children.item(i)
                if (child.nodeType == Node.ELEMENT_NODE) {
                    inspectElement(child as Element)
                }
            }
        }

        inspectElement(root)

        val resolvedVignette = when {
            postCropVignette != null && postCropVignette != 0f -> postCropVignette
            lensVignette != null && lensVignette != 0f -> lensVignette
            else -> 0f
        }
        val resolvedCa = max(defringePurple, autoLateralCa)

        return XmpDevelopSettings(
            name = name,
            group = group,
            exposure = exposure,
            contrast = contrast,
            highlights = highlights,
            shadows = shadows,
            whites = whites,
            blacks = blacks,
            parametricShadows = parametricShadows,
            parametricDarks = parametricDarks,
            parametricLights = parametricLights,
            parametricHighlights = parametricHighlights,
            temperature = temperature,
            tint = tint,
            saturation = saturation,
            vibrance = vibrance,
            texture = texture,
            clarity = clarity,
            dehaze = dehaze,
            vignette = resolvedVignette,
            vignetteStyleIndex = vignetteStyleIndex,
            vignetteMidpoint = vignetteMidpoint,
            vignetteFeather = vignetteFeather,
            vignetteRoundness = vignetteRoundness,
            vignetteHighlights = vignetteHighlights,
            grain = grain,
            grainSize = grainSize,
            grainRoughness = grainRoughness,
            sharpening = sharpening,
            sharpenRadius = sharpenRadius,
            sharpenDetail = sharpenDetail,
            sharpenMasking = sharpenMasking,
            noiseReduction = noiseReduction,
            noiseReductionDetail = noiseReductionDetail,
            noiseReductionContrast = noiseReductionContrast,
            colorNoiseReduction = colorNoiseReduction,
            colorNoiseReductionDetail = colorNoiseReductionDetail,
            colorNoiseReductionSmoothness = colorNoiseReductionSmoothness,
            lensDistortion = lensDistortion,
            chromaticAberration = resolvedCa,
            redHue = redHue,
            redSaturation = redSaturation,
            greenHue = greenHue,
            greenSaturation = greenSaturation,
            blueHue = blueHue,
            blueSaturation = blueSaturation,
            shadowTint = shadowTint,
            convertToGrayscale = convertToGrayscale,
            splitShadowHue = splitShadowHue,
            splitShadowSat = splitShadowSat,
            splitHighlightHue = splitHighlightHue,
            splitHighlightSat = splitHighlightSat,
            splitBalance = splitBalance,
            hueAdjustments = hueAdj,
            satAdjustments = satAdj,
            lumAdjustments = lumAdj,
            toneCurveMaster = masterCurve.sortedBy { it.first },
            toneCurveRed = redCurve.sortedBy { it.first },
            toneCurveGreen = greenCurve.sortedBy { it.first },
            toneCurveBlue = blueCurve.sortedBy { it.first },
            hasRgbTableOnly = hasRgbTable
        )
    }

    private fun extractFirstLiText(el: Element): String? {
        val liNodes = el.getElementsByTagName("*")
        for (j in 0 until liNodes.length) {
            val n = liNodes.item(j)
            if (n.nodeName.substringAfter(':') == "li") {
                val txt = n.textContent?.trim().orEmpty()
                if (txt.isNotEmpty()) return txt
            }
        }
        return null
    }

    private fun extractCurvePoints(curveEl: Element, out: MutableList<Pair<Float, Float>>) {
        val nodes = curveEl.getElementsByTagName("*")
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n.nodeName.substringAfter(':') == "li") {
                parseCurvePoint(n.textContent?.trim().orEmpty())?.let { out.add(it) }
            }
        }
    }

    private fun String.toFiniteFloatOrNull(): Float? =
        toFloatOrNull()?.takeIf { it.isFinite() }

    private fun parseCurvePoint(raw: String): Pair<Float, Float>? {
        val parts = raw.split(',')
        if (parts.size < 2) return null
        val x = parts[0].trim().toFiniteFloatOrNull() ?: return null
        val y = parts[1].trim().toFiniteFloatOrNull() ?: return null
        return (x / 255f).coerceIn(0f, 1f) to (y / 255f).coerceIn(0f, 1f)
    }

    private fun hasAnyDevelopAdjustment(s: XmpDevelopSettings): Boolean {
        return s.exposure != 0f ||
            s.contrast != 0f ||
            s.highlights != 0f ||
            s.shadows != 0f ||
            s.whites != 0f ||
            s.blacks != 0f ||
            s.parametricShadows != 0f ||
            s.parametricDarks != 0f ||
            s.parametricLights != 0f ||
            s.parametricHighlights != 0f ||
            s.temperature != 0f ||
            s.tint != 0f ||
            s.saturation != 0f ||
            s.vibrance != 0f ||
            s.texture != 0f ||
            s.clarity != 0f ||
            s.dehaze != 0f ||
            s.vignette != 0f ||
            s.grain != 0f ||
            s.sharpening != 0f ||
            s.noiseReduction != 0f ||
            s.colorNoiseReduction != 0f ||
            s.lensDistortion != 0f ||
            s.chromaticAberration != 0f ||
            s.redHue != 0f ||
            s.redSaturation != 0f ||
            s.greenHue != 0f ||
            s.greenSaturation != 0f ||
            s.blueHue != 0f ||
            s.blueSaturation != 0f ||
            s.convertToGrayscale ||
            s.splitShadowSat != 0f ||
            s.splitHighlightSat != 0f ||
            s.hueAdjustments.any { it != 0f } ||
            s.satAdjustments.any { it != 0f } ||
            s.lumAdjustments.any { it != 0f } ||
            s.toneCurveMaster.size >= 2 ||
            s.toneCurveRed.size >= 2 ||
            s.toneCurveGreen.size >= 2 ||
            s.toneCurveBlue.size >= 2
    }

    private fun bakeSettingsToCubeLut(
        s: XmpDevelopSettings,
        id: String,
        category: String
    ): CubeLut {
        val n = BAKED_LUT_SIZE
        val table = FloatArray(n * n * n * 3)

        val expMult = 2.0.pow(s.exposure.toDouble()).toFloat()
        val contrastFactor = 1f + (s.contrast / 100f) * 0.75f
        val hiStrength = (s.highlights / 100f) * 0.55f
        val shStrength = (s.shadows / 100f) * 0.55f
        val blackLift = (s.blacks / 100f) * 0.12f
        val whiteGain = 1f + (s.whites / 100f) * 0.15f
        val hasParametric = s.parametricShadows != 0f ||
            s.parametricDarks != 0f ||
            s.parametricLights != 0f ||
            s.parametricHighlights != 0f

        val tempNorm = s.temperature / 100f
        val tintNorm = s.tint / 100f
        val rTemp = 1f + tempNorm * 0.24f + tintNorm * 0.06f
        val gTemp = 1f + tempNorm * 0.03f - tintNorm * 0.14f
        val bTemp = 1f - tempNorm * 0.26f + tintNorm * 0.06f
        val satFactor = (1f + (s.saturation / 100f) + (s.vibrance / 100f) * 0.45f).coerceAtLeast(0f)

        val hasHsl = s.hueAdjustments.any { it != 0f } ||
            s.satAdjustments.any { it != 0f } ||
            s.lumAdjustments.any { it != 0f }

        val masterLut = buildCurveLut(s.toneCurveMaster)
        val redLut = buildCurveLut(s.toneCurveRed)
        val greenLut = buildCurveLut(s.toneCurveGreen)
        val blueLut = buildCurveLut(s.toneCurveBlue)

        val shTint = hueSatToRgbTint(s.splitShadowHue, s.splitShadowSat)
        val hiTint = hueSatToRgbTint(s.splitHighlightHue, s.splitHighlightSat)
        val balanceNorm = s.splitBalance / 100f
        val shadowBalanceMult = (1f - balanceNorm * 0.5f).coerceIn(0.25f, 1.75f)
        val highlightBalanceMult = (1f + balanceNorm * 0.5f).coerceIn(0.25f, 1.75f)

        val hslWork = FloatArray(3)
        val rgbWork = FloatArray(3)

        var idx = 0
        for (bi in 0 until n) {
            val bIn = bi / (n - 1f)
            for (gi in 0 until n) {
                val gIn = gi / (n - 1f)
                for (ri in 0 until n) {
                    val rIn = ri / (n - 1f)

                    var r = if (abs(expMult - 1f) > 0.0005f) linearToSrgbChan(srgbToLinearChan(rIn) * expMult) else rIn
                    var g = if (abs(expMult - 1f) > 0.0005f) linearToSrgbChan(srgbToLinearChan(gIn) * expMult) else gIn
                    var b = if (abs(expMult - 1f) > 0.0005f) linearToSrgbChan(srgbToLinearChan(bIn) * expMult) else bIn
                    r = (r * rTemp).coerceIn(0f, 1f)
                    g = (g * gTemp).coerceIn(0f, 1f)
                    b = (b * bTemp).coerceIn(0f, 1f)

                    if (blackLift != 0f || whiteGain != 1f) {
                        val wbLuma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                        val hiMask = smoothRamp(0.28f, 1.0f, wbLuma)
                        val shMask = 1f - smoothRamp(0.0f, 0.68f, wbLuma)
                        val wr = r + (r * whiteGain - r) * hiMask
                        val wg = g + (g * whiteGain - g) * hiMask
                        val wb = b + (b * whiteGain - b) * hiMask
                        r = (wr + blackLift * shMask).coerceIn(0f, 1f)
                        g = (wg + blackLift * shMask).coerceIn(0f, 1f)
                        b = (wb + blackLift * shMask).coerceIn(0f, 1f)
                    }

                    if (hiStrength != 0f || shStrength != 0f) {
                        val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                        var delta = 0f
                        if (shStrength != 0f) {
                            val shT = ((0.55f - luma) / 0.55f).coerceIn(0f, 1f)
                            delta += shStrength * (shT * shT * (3f - 2f * shT)) * 0.5f
                        }
                        if (hiStrength != 0f) {
                            val hiT = ((luma - 0.45f) / 0.55f).coerceIn(0f, 1f)
                            delta += hiStrength * (hiT * hiT * (3f - 2f * hiT)) * 0.5f
                        }
                        if (delta != 0f) {
                            r = (r + delta).coerceIn(0f, 1f)
                            g = (g + delta).coerceIn(0f, 1f)
                            b = (b + delta).coerceIn(0f, 1f)
                        }
                    }

                    if (hasParametric) {
                        val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                        val wSh = bandWeight(luma, 0.12f, 0.28f)
                        val wDk = bandWeight(luma, 0.35f, 0.26f)
                        val wLt = bandWeight(luma, 0.65f, 0.26f)
                        val wHi = bandWeight(luma, 0.88f, 0.28f)
                        val delta = (
                            (s.parametricShadows / 100f) * wSh +
                                (s.parametricDarks / 100f) * wDk +
                                (s.parametricLights / 100f) * wLt +
                                (s.parametricHighlights / 100f) * wHi
                            ) * 0.18f
                        if (delta != 0f) {
                            r = (r + delta).coerceIn(0f, 1f)
                            g = (g + delta).coerceIn(0f, 1f)
                            b = (b + delta).coerceIn(0f, 1f)
                        }
                    }

                    if (contrastFactor != 1f) {
                        r = ((r - 0.5f) * contrastFactor + 0.5f).coerceIn(0f, 1f)
                        g = ((g - 0.5f) * contrastFactor + 0.5f).coerceIn(0f, 1f)
                        b = ((b - 0.5f) * contrastFactor + 0.5f).coerceIn(0f, 1f)
                    }

                    if (masterLut != null) {
                        r = sampleCurve(masterLut, r)
                        g = sampleCurve(masterLut, g)
                        b = sampleCurve(masterLut, b)
                    }
                    if (redLut != null) r = sampleCurve(redLut, r)
                    if (greenLut != null) g = sampleCurve(greenLut, g)
                    if (blueLut != null) b = sampleCurve(blueLut, b)

                    if (hasHsl && !s.convertToGrayscale) {
                        applyHslBands(
                            r = r,
                            g = g,
                            b = b,
                            hueAdj = s.hueAdjustments,
                            satAdj = s.satAdjustments,
                            lumAdj = s.lumAdjustments,
                            hslWork = hslWork,
                            rgbOut = rgbWork
                        )
                        r = rgbWork[0]
                        g = rgbWork[1]
                        b = rgbWork[2]
                    }

                    if (s.convertToGrayscale) {
                        val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                        r = luma
                        g = luma
                        b = luma
                    } else if (satFactor != 1f) {
                        val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                        r = (luma + (r - luma) * satFactor).coerceIn(0f, 1f)
                        g = (luma + (g - luma) * satFactor).coerceIn(0f, 1f)
                        b = (luma + (b - luma) * satFactor).coerceIn(0f, 1f)
                    }

                    if (s.splitShadowSat > 0f || s.splitHighlightSat > 0f) {
                        val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                        val shW = (1f - luma).pow(1.4f) * (s.splitShadowSat / 100f) * 0.35f * shadowBalanceMult
                        val hiW = luma.pow(1.4f) * (s.splitHighlightSat / 100f) * 0.35f * highlightBalanceMult
                        r = (r + shTint[0] * shW + hiTint[0] * hiW).coerceIn(0f, 1f)
                        g = (g + shTint[1] * shW + hiTint[1] * hiW).coerceIn(0f, 1f)
                        b = (b + shTint[2] * shW + hiTint[2] * hiW).coerceIn(0f, 1f)
                    }

                    table[idx++] = r
                    table[idx++] = g
                    table[idx++] = b
                }
            }
        }

        return CubeLut(
            id = id,
            title = s.name,
            type = LutType.LUT_3D,
            size = n,
            table = table,
            sourceFormat = LutSourceFormat.XMP,
            category = category
        )
    }

    private fun bandWeight(value: Float, center: Float, halfWidth: Float): Float {
        val d = abs(value - center)
        if (d >= halfWidth) return 0f
        val t = 1f - d / halfWidth
        return t * t * (3f - 2f * t)
    }

    private fun applyHslBands(
        r: Float,
        g: Float,
        b: Float,
        hueAdj: FloatArray,
        satAdj: FloatArray,
        lumAdj: FloatArray,
        hslWork: FloatArray,
        rgbOut: FloatArray
    ) {
        rgbToHsl(r, g, b, hslWork)
        val hueDeg = hslWork[0]
        val sat = hslWork[1]
        val light = hslWork[2]
        if (sat <= 0.002f) {
            rgbOut[0] = r
            rgbOut[1] = g
            rgbOut[2] = b
            return
        }

        var dHue = 0f
        var dSat = 0f
        var dLum = 0f
        var totalWeight = 0f

        for (band in 0 until 8) {
            val center = HSL_BAND_CENTERS[band]
            var dist = abs(hueDeg - center)
            if (dist > 180f) dist = 360f - dist
            val span = if (band == 0 || band == 1 || band == 2) 45f else 55f
            if (dist < span) {
                val w = 1f - dist / span
                totalWeight += w
                dHue += hueAdj[band] * w
                dSat += satAdj[band] * w
                dLum += lumAdj[band] * w
            }
        }

        if (totalWeight > 1e-4f) {
            dHue /= totalWeight
            dSat /= totalWeight
            dLum /= totalWeight
        }

        val newHue = ((hueDeg + (dHue / 100f) * 32f) % 360f + 360f) % 360f
        val newSat = (sat * (1f + dSat / 100f)).coerceIn(0f, 1f)
        val newLight = (light + (dLum / 100f) * 0.22f * sat).coerceIn(0f, 1f)
        hslToRgb(newHue, newSat, newLight, rgbOut)
    }

    private fun rgbToHsl(r: Float, g: Float, b: Float, out: FloatArray) {
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val delta = maxC - minC
        val l = (maxC + minC) * 0.5f

        if (delta < 1e-5f) {
            out[0] = 0f
            out[1] = 0f
            out[2] = l
            return
        }

        val s = if (l > 0.5f) delta / (2f - maxC - minC) else delta / (maxC + minC)
        var h = when (maxC) {
            r -> ((g - b) / delta) + (if (g < b) 6f else 0f)
            g -> ((b - r) / delta) + 2f
            else -> ((r - g) / delta) + 4f
        }
        h *= 60f
        out[0] = (h % 360f + 360f) % 360f
        out[1] = s.coerceIn(0f, 1f)
        out[2] = l.coerceIn(0f, 1f)
    }

    private fun hslToRgb(h: Float, s: Float, l: Float, out: FloatArray) {
        if (s <= 1e-5f) {
            out[0] = l
            out[1] = l
            out[2] = l
            return
        }
        val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
        val p = 2f * l - q
        val hk = h / 360f
        out[0] = hue2rgb(p, q, hk + 1f / 3f).coerceIn(0f, 1f)
        out[1] = hue2rgb(p, q, hk).coerceIn(0f, 1f)
        out[2] = hue2rgb(p, q, hk - 1f / 3f).coerceIn(0f, 1f)
    }

    private fun hue2rgb(p: Float, q: Float, tIn: Float): Float {
        var t = tIn
        if (t < 0f) t += 1f
        if (t > 1f) t -= 1f
        return when {
            t < 1f / 6f -> p + (q - p) * 6f * t
            t < 0.5f -> q
            t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
            else -> p
        }
    }

    private fun smoothRamp(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0).coerceAtLeast(1e-4f)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun srgbToLinearChan(c: Float): Float {
        val cc = c.coerceIn(0f, 1f)
        return if (cc <= 0.04045f) cc / 12.92f else ((cc + 0.055f) / 1.055f).pow(2.4f)
    }

    private fun linearToSrgbChan(c: Float): Float {
        val cc = c.coerceAtLeast(0f)
        val s = if (cc <= 0.0031308f) cc * 12.92f else 1.055f * cc.pow(1f / 2.4f) - 0.055f
        return s.coerceIn(0f, 1f)
    }

    private fun buildCurveLut(points: List<Pair<Float, Float>>): FloatArray? {
        if (points.size < 2) return null
        return com.zamalfajji.zamlab.editor.ToneCurveData.buildLut(
            points.map { com.zamalfajji.zamlab.editor.ToneCurvePoint(it.first, it.second) }
        )
    }

    private fun sampleCurve(lut: FloatArray, v: Float): Float {
        val pos = (v.coerceIn(0f, 1f) * 255f)
        val i0 = pos.toInt().coerceIn(0, 255)
        val i1 = (i0 + 1).coerceAtMost(255)
        val frac = pos - i0
        return (lut[i0] + (lut[i1] - lut[i0]) * frac).coerceIn(0f, 1f)
    }

    private fun hueSatToRgbTint(hueDeg: Float, sat: Float): FloatArray {
        if (sat <= 0f) return floatArrayOf(0f, 0f, 0f)
        val h = (hueDeg % 360f) / 60f
        val x = 1f - abs(h % 2f - 1f)
        val (r, g, b) = when {
            h < 1f -> Triple(1f, x, 0f)
            h < 2f -> Triple(x, 1f, 0f)
            h < 3f -> Triple(0f, 1f, x)
            h < 4f -> Triple(0f, x, 1f)
            h < 5f -> Triple(x, 0f, 1f)
            else -> Triple(1f, 0f, x)
        }
        val avg = (r + g + b) / 3f
        return floatArrayOf(r - avg, g - avg, b - avg)
    }
}
