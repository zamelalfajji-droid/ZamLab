package com.example.editor

import android.graphics.Bitmap
import androidx.compose.runtime.Immutable
import com.example.lut.XmpDevelopSettings
import kotlin.math.abs

enum class ToneCurveChannel(val label: String) {
    MASTER("RGB"),
    RED("Red"),
    GREEN("Green"),
    BLUE("Blue")
}

@Immutable
data class ToneCurvePoint(
    val x: Float,
    val y: Float
)

@Immutable
data class ToneCurveData(
    val master: List<ToneCurvePoint> = DEFAULT_POINTS,
    val red: List<ToneCurvePoint> = DEFAULT_POINTS,
    val green: List<ToneCurvePoint> = DEFAULT_POINTS,
    val blue: List<ToneCurvePoint> = DEFAULT_POINTS
) {
    val isDefault: Boolean
        get() = isLinear(master) && isLinear(red) && isLinear(green) && isLinear(blue)

    fun pointsFor(channel: ToneCurveChannel): List<ToneCurvePoint> = when (channel) {
        ToneCurveChannel.MASTER -> master
        ToneCurveChannel.RED -> red
        ToneCurveChannel.GREEN -> green
        ToneCurveChannel.BLUE -> blue
    }

    fun isChannelModified(channel: ToneCurveChannel): Boolean = !isLinear(pointsFor(channel))

    fun withChannel(channel: ToneCurveChannel, points: List<ToneCurvePoint>): ToneCurveData {
        val sorted = sanitizeCurve(points)
        return when (channel) {
            ToneCurveChannel.MASTER -> copy(master = sorted)
            ToneCurveChannel.RED -> copy(red = sorted)
            ToneCurveChannel.GREEN -> copy(green = sorted)
            ToneCurveChannel.BLUE -> copy(blue = sorted)
        }
    }

    fun resetChannel(channel: ToneCurveChannel): ToneCurveData = withChannel(channel, DEFAULT_POINTS)

    fun buildMasterLut(): FloatArray = buildLut(master)
    fun buildRedLut(): FloatArray = buildLut(red)
    fun buildGreenLut(): FloatArray = buildLut(green)
    fun buildBlueLut(): FloatArray = buildLut(blue)

    /**
     * Packs all 4 channels (R, G, B, Master->A) into a 256-entry ARGB_8888 LUT array
     * ready for upload to a 256x1 OpenGL texture.
     */
    fun buildPackedRgba256(): IntArray {
        val out = IntArray(256)
        buildPackedRgba256Into(out)
        return out
    }

    /**
     * Packs all 4 channels (R, G, B, Master->A) into caller-owned [out] (length >= 256)
     * for zero-allocation OpenGL texSubImage2D updates.
     */
    fun buildPackedRgba256Into(out: IntArray) {
        val mLut = buildMasterLut()
        val rLut = buildRedLut()
        val gLut = buildGreenLut()
        val bLut = buildBlueLut()
        for (i in 0 until 256) {
            val r = (rLut[i] * 255f + 0.5f).toInt().coerceIn(0, 255)
            val g = (gLut[i] * 255f + 0.5f).toInt().coerceIn(0, 255)
            val b = (bLut[i] * 255f + 0.5f).toInt().coerceIn(0, 255)
            val a = (mLut[i] * 255f + 0.5f).toInt().coerceIn(0, 255)
            out[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    /**
     * Packs R, G, B (row 0) and Master (row 1) into a 512-entry fully-opaque (alpha = 0xFF)
     * ARGB_8888 array for a 256x2 OpenGL texture. Keeps alpha at 0xFF so Android Bitmap
     * premultiplication never crushes RGB curve values.
     */
    fun buildPackedRgba512(): IntArray {
        val out = IntArray(512)
        buildPackedRgba512Into(out)
        return out
    }

    fun buildPackedRgba512Into(out: IntArray) {
        val mLut = buildMasterLut()
        val rLut = buildRedLut()
        val gLut = buildGreenLut()
        val bLut = buildBlueLut()
        for (i in 0 until 256) {
            val r = (rLut[i] * 255f + 0.5f).toInt().coerceIn(0, 255)
            val g = (gLut[i] * 255f + 0.5f).toInt().coerceIn(0, 255)
            val b = (bLut[i] * 255f + 0.5f).toInt().coerceIn(0, 255)
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

            val m = (mLut[i] * 255f + 0.5f).toInt().coerceIn(0, 255)
            out[256 + i] = (0xFF shl 24) or (m shl 16) or (m shl 8) or m
        }
    }

    companion object {
        val DEFAULT_POINTS: List<ToneCurvePoint> = listOf(
            ToneCurvePoint(0f, 0f),
            ToneCurvePoint(1f, 1f)
        )

        fun isLinear(points: List<ToneCurvePoint>): Boolean {
            if (points.size < 2) return true
            return points.all { abs(it.y - it.x) < 0.008f }
        }

        fun sanitizeCurve(points: List<ToneCurvePoint>): List<ToneCurvePoint> {
            if (points.size < 2) return DEFAULT_POINTS
            val clamped = points
                .map { ToneCurvePoint(it.x.coerceIn(0f, 1f), it.y.coerceIn(0f, 1f)) }
                .sortedBy { it.x }
            return clamped
        }

        fun fromXmp(s: XmpDevelopSettings, intensity: Float): ToneCurveData {
            val t = intensity.coerceIn(0f, 1f)
            return ToneCurveData(
                master = scaleXmpPoints(s.toneCurveMaster, t),
                red = scaleXmpPoints(s.toneCurveRed, t),
                green = scaleXmpPoints(s.toneCurveGreen, t),
                blue = scaleXmpPoints(s.toneCurveBlue, t)
            )
        }

        private fun scaleXmpPoints(
            raw: List<Pair<Float, Float>>,
            intensity: Float
        ): List<ToneCurvePoint> {
            if (raw.size < 2 || intensity <= 0.001f) return DEFAULT_POINTS
            val pts = raw.sortedBy { it.first }.map { (x, y) ->
                val cx = x.coerceIn(0f, 1f)
                val cy = (cx + (y.coerceIn(0f, 1f) - cx) * intensity).coerceIn(0f, 1f)
                ToneCurvePoint(cx, cy)
            }
            return if (isLinear(pts)) DEFAULT_POINTS else pts
        }

        fun buildLut(points: List<ToneCurvePoint>): FloatArray {
            if (points.size < 2) {
                return FloatArray(256) { it / 255f }
            }
            val sorted = points.sortedBy { it.x }
            val n = sorted.size
            if (n == 2) {
                val p0 = sorted[0]
                val p1 = sorted[1]
                return FloatArray(256) { i ->
                    val x = i / 255f
                    if (x <= p0.x) p0.y
                    else if (x >= p1.x) p1.y
                    else {
                        val span = (p1.x - p0.x).coerceAtLeast(1e-5f)
                        (p0.y + (p1.y - p0.y) * ((x - p0.x) / span)).coerceIn(0f, 1f)
                    }
                }
            }

            // Fritsch-Carlson monotone cubic Hermite spline (matches desktop tone curve response)
            val dx = FloatArray(n - 1)
            val dy = FloatArray(n - 1)
            val slope = FloatArray(n - 1)
            for (i in 0 until n - 1) {
                dx[i] = (sorted[i + 1].x - sorted[i].x).coerceAtLeast(1e-4f)
                dy[i] = sorted[i + 1].y - sorted[i].y
                slope[i] = dy[i] / dx[i]
            }

            val tangents = FloatArray(n)
            tangents[0] = slope[0]
            tangents[n - 1] = slope[n - 2]
            for (i in 1 until n - 1) {
                val mPrev = slope[i - 1]
                val mNext = slope[i]
                if (mPrev * mNext <= 0f) {
                    tangents[i] = 0f
                } else {
                    val w1 = 2f * dx[i] + dx[i - 1]
                    val w2 = dx[i] + 2f * dx[i - 1]
                    tangents[i] = (w1 + w2) / (w1 / mPrev + w2 / mNext)
                }
            }

            return FloatArray(256) { idx ->
                val x = idx / 255f
                if (x <= sorted.first().x) {
                    sorted.first().y.coerceIn(0f, 1f)
                } else if (x >= sorted.last().x) {
                    sorted.last().y.coerceIn(0f, 1f)
                } else {
                    var seg = 0
                    for (i in 0 until n - 1) {
                        if (x >= sorted[i].x && x <= sorted[i + 1].x) {
                            seg = i
                            break
                        }
                    }
                    val h = dx[seg]
                    val t = ((x - sorted[seg].x) / h).coerceIn(0f, 1f)
                    val t2 = t * t
                    val t3 = t2 * t
                    val h00 = 2f * t3 - 3f * t2 + 1f
                    val h10 = t3 - 2f * t2 + t
                    val h01 = -2f * t3 + 3f * t2
                    val h11 = t3 - t2
                    val y = h00 * sorted[seg].y +
                        h10 * h * tangents[seg] +
                        h01 * sorted[seg + 1].y +
                        h11 * h * tangents[seg + 1]
                    y.coerceIn(0f, 1f)
                }
            }
        }
    }
}

enum class HslBand(
    val label: String,
    val centerHueDeg: Float,
    val spanDeg: Float,
    val swatchArgb: Long
) {
    RED("Red", 0f, 45f, 0xFFEF4444),
    ORANGE("Orange", 30f, 45f, 0xFFF97316),
    YELLOW("Yellow", 60f, 45f, 0xFFEAB308),
    GREEN("Green", 120f, 55f, 0xFF22C55E),
    AQUA("Aqua", 180f, 55f, 0xFF06B6D4),
    BLUE("Blue", 240f, 55f, 0xFF3B82F6),
    PURPLE("Purple", 285f, 55f, 0xFF8B5CF6),
    MAGENTA("Magenta", 330f, 55f, 0xFFEC4899);

    companion object {
        fun closestToHue(hueDeg: Float): HslBand {
            val normHue = ((hueDeg % 360f) + 360f) % 360f
            return entries.minByOrNull { band ->
                var d = abs(normHue - band.centerHueDeg)
                if (d > 180f) d = 360f - d
                d
            } ?: RED
        }
    }
}

enum class TouchToneZone(val label: String) {
    SHADOWS("Shadows"),
    MIDTONES("Midtones"),
    HIGHLIGHTS("Highlights")
}

@Immutable
data class HslBandAdjustment(
    val hue: Float = 0f,        // -100 .. +100
    val saturation: Float = 0f, // -100 .. +100
    val luminance: Float = 0f   // -100 .. +100
) {
    val isDefault: Boolean
        get() = abs(hue) < 0.01f && abs(saturation) < 0.01f && abs(luminance) < 0.01f
}

@Immutable
data class HslMixerData(
    val bands: List<HslBandAdjustment> = DEFAULT_BANDS
) {
    val isDefault: Boolean
        get() = bands.all { it.isDefault }

    operator fun get(band: HslBand): HslBandAdjustment =
        bands.getOrElse(band.ordinal) { HslBandAdjustment() }

    fun withBand(
        band: HslBand,
        transform: (HslBandAdjustment) -> HslBandAdjustment
    ): HslMixerData {
        val next = bands.toMutableList()
        while (next.size < 8) next.add(HslBandAdjustment())
        next[band.ordinal] = transform(next[band.ordinal])
        return copy(bands = next)
    }

    companion object {
        val DEFAULT_BANDS: List<HslBandAdjustment> = List(8) { HslBandAdjustment() }

        fun fromXmp(s: XmpDevelopSettings, intensity: Float): HslMixerData {
            val t = intensity.coerceIn(0f, 1f)
            val bands = List(8) { i ->
                HslBandAdjustment(
                    hue = (s.hueAdjustments[i] * t).coerceIn(-100f, 100f),
                    saturation = (s.satAdjustments[i] * t).coerceIn(-100f, 100f),
                    luminance = (s.lumAdjustments[i] * t).coerceIn(-100f, 100f)
                )
            }
            return HslMixerData(bands)
        }
    }
}

@Immutable
data class RgbCalibrationData(
    val redHue: Float = 0f,         // -100 .. +100
    val redSaturation: Float = 0f,  // -100 .. +100
    val greenHue: Float = 0f,       // -100 .. +100
    val greenSaturation: Float = 0f,// -100 .. +100
    val blueHue: Float = 0f,        // -100 .. +100
    val blueSaturation: Float = 0f  // -100 .. +100
) {
    val isDefault: Boolean
        get() = redHue == 0f && redSaturation == 0f &&
            greenHue == 0f && greenSaturation == 0f &&
            blueHue == 0f && blueSaturation == 0f
}

enum class WhiteBalancePreset(
    val label: String,
    val temperature: Float,
    val tint: Float
) {
    AS_SHOT("As Shot", 0f, 0f),
    AUTO("Auto", 0f, 0f),
    DAYLIGHT("Daylight", 8f, 2f),
    CLOUDY("Cloudy", 20f, 4f),
    SHADE("Shade", 32f, 6f),
    TUNGSTEN("Tungsten", -38f, -6f),
    FLUORESCENT("Fluorescent", -14f, 22f),
    FLASH("Flash", 12f, 3f),
    CUSTOM("Custom", 0f, 0f);

    companion object {
        val MENU_OPTIONS: List<WhiteBalancePreset> = listOf(
            AS_SHOT,
            AUTO,
            DAYLIGHT,
            CLOUDY,
            SHADE,
            TUNGSTEN,
            FLUORESCENT,
            FLASH
        )
    }
}

@Immutable
data class PointColorData(
    val sourceHueDeg: Float = 0f,     // 0 .. 360
    val sourceSat: Float = 0.72f,     // 0 .. 1
    val sourceLuma: Float = 0.50f,    // 0 .. 1
    val swatchArgb: Long = 0xFFEF4444,
    val isSampled: Boolean = false,
    val hueShift: Float = 0f,         // -100 .. +100
    val saturationShift: Float = 0f,  // -100 .. +100
    val luminanceShift: Float = 0f,   // -100 .. +100
    val range: Float = 50f            // 0 .. 100
) {
    val isModified: Boolean
        get() = abs(hueShift) > 0.01f ||
            abs(saturationShift) > 0.01f ||
            abs(luminanceShift) > 0.01f
}

@Immutable
data class ColorWheelValue(
    val hueDeg: Float = 0f,       // 0 .. 360
    val saturation: Float = 0f,   // 0 .. 100
    val luminance: Float = 0f     // -100 .. +100
) {
    val isDefault: Boolean
        get() = saturation < 0.01f && abs(luminance) < 0.01f
}

@Immutable
data class ColorGradingData(
    val shadows: ColorWheelValue = ColorWheelValue(),
    val midtones: ColorWheelValue = ColorWheelValue(),
    val highlights: ColorWheelValue = ColorWheelValue(),
    val global: ColorWheelValue = ColorWheelValue(),
    val blending: Float = 50f,    // 0 .. 100
    val balance: Float = 0f       // -100 .. +100
) {
    val isDefault: Boolean
        get() = shadows.isDefault &&
            midtones.isDefault &&
            highlights.isDefault &&
            global.isDefault &&
            abs(blending - 50f) < 0.01f &&
            abs(balance) < 0.01f
}

enum class VignetteStyle(val label: String, val shaderIndex: Int) {
    HIGHLIGHT("Highlight", 0),
    COLOR("Color", 1),
    PAINT("Paint", 2)
}

enum class MaskType(val label: String, val shaderType: Int) {
    RADIAL("Radial", 0),
    LINEAR("Linear", 1)
}

const val MAX_LOCAL_MASKS = 4

@Immutable
data class LocalMask(
    val id: String,
    val name: String,
    val type: MaskType,
    val enabled: Boolean = true,
    val inverted: Boolean = false,
    // Radial gradient geometry in normalized output view UV [0..1]
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    val radiusX: Float = 0.28f,
    val radiusY: Float = 0.28f,
    val feather: Float = 0.50f,     // 0.0 (hard) .. 1.0 (soft)
    // Linear gradient geometry in normalized output view UV [0..1]
    val startX: Float = 0.5f,
    val startY: Float = 0.16f,      // 100% mask side
    val endX: Float = 0.5f,
    val endY: Float = 0.62f,        // 0% fade side
    // Independent local adjustments
    val exposure: Float = 0f,       // -2.0 .. +2.0 EV
    val contrast: Float = 0f,       // -100 .. +100
    val warmth: Float = 0f,         // -100 .. +100
    val saturation: Float = 0f,     // -100 .. +100
    val sharpness: Float = 0f       // 0 .. +100
) {
    val hasAdjustments: Boolean
        get() = abs(exposure) > 0.001f ||
            abs(contrast) > 0.01f ||
            abs(warmth) > 0.01f ||
            abs(saturation) > 0.01f ||
            sharpness > 0.01f
}

/**
 * Evaluates spatial weight [0..1] for [mask] at output UV ([u], [v]) with output aspect ratio
 * ([aspect] = width / height). Shared cleanly by CPU ColorGradingEngine and mirrors GLSL math 1:1.
 */
fun evaluateLocalMaskWeight(mask: LocalMask, u: Float, v: Float, aspect: Float): Float {
    if (!mask.enabled) return 0f
    var w = if (mask.type == MaskType.RADIAL) {
        val rx = mask.radiusX.coerceAtLeast(0.02f)
        val ry = mask.radiusY.coerceAtLeast(0.02f)
        val dx = (u - mask.centerX) / rx
        val dy = (v - mask.centerY) / ry
        val dist = kotlin.math.sqrt(dx * dx + dy * dy)
        val f = mask.feather.coerceIn(0f, 1f)
        val inner = (1f - f).coerceIn(0f, 0.98f)
        val outer = (1f + f * 0.15f).coerceAtLeast(inner + 0.02f)
        val t = ((dist - inner) / (outer - inner)).coerceIn(0f, 1f)
        val smooth = t * t * (3f - 2f * t)
        1f - smooth
    } else {
        val safeAspect = aspect.coerceAtLeast(0.1f)
        val px = (u - mask.startX) * safeAspect
        val py = v - mask.startY
        val dx = (mask.endX - mask.startX) * safeAspect
        val dy = mask.endY - mask.startY
        val len2 = (dx * dx + dy * dy).coerceAtLeast(1e-4f)
        val proj = ((px * dx + py * dy) / len2).coerceIn(0f, 1f)
        val smooth = proj * proj * (3f - 2f * proj)
        1f - smooth
    }
    if (mask.inverted) {
        w = 1f - w
    }
    return w.coerceIn(0f, 1f)
}

@Immutable
data class GradingParams(
    // Light
    val exposure: Float = 0f,       // -2.0 .. +2.0 EV
    val contrast: Float = 0f,       // -100 .. +100
    val highlights: Float = 0f,     // -100 .. +100
    val shadows: Float = 0f,        // -100 .. +100
    val whites: Float = 0f,         // -100 .. +100
    val blacks: Float = 0f,         // -100 .. +100
    val toneCurve: ToneCurveData = ToneCurveData(),
    val parametricShadows: Float = 0f,    // Preserved from XMP (-100 .. +100)
    val parametricDarks: Float = 0f,
    val parametricLights: Float = 0f,
    val parametricHighlights: Float = 0f,

    // Color
    val whiteBalancePreset: WhiteBalancePreset = WhiteBalancePreset.AS_SHOT,
    val temperature: Float = 0f,    // -100 .. +100
    val tint: Float = 0f,           // -100 .. +100
    val vibrance: Float = 0f,       // -100 .. +100
    val saturation: Float = 0f,     // -100 .. +100
    val isGrayscale: Boolean = false,
    val hsl: HslMixerData = HslMixerData(),
    val pointColor: PointColorData = PointColorData(),
    val colorGrading: ColorGradingData = ColorGradingData(),
    val splitShadowHue: Float = 0f,       // 0 .. 360
    val splitShadowSat: Float = 0f,       // 0 .. 100
    val splitHighlightHue: Float = 0f,    // 0 .. 360
    val splitHighlightSat: Float = 0f,    // 0 .. 100
    val splitBalance: Float = 0f,         // -100 .. +100

    // Effects
    val texture: Float = 0f,        // -100 .. +100
    val clarity: Float = 0f,        // -100 .. +100
    val dehaze: Float = 0f,         // -100 .. +100
    val vignetteStyle: VignetteStyle = VignetteStyle.HIGHLIGHT,
    val vignette: Float = 0f,       // -100 .. +100
    val vignetteMidpoint: Float = 50f,   // 0 .. 100
    val vignetteFeather: Float = 50f,    // 0 .. 100
    val vignetteRoundness: Float = 0f,   // -100 .. +100
    val vignetteHighlights: Float = 0f,  // 0 .. 100
    val grain: Float = 0f,          // 0 .. 100
    val grainSize: Float = 25f,     // 0 .. 100
    val grainRoughness: Float = 50f,// 0 .. 100

    // Detail
    val sharpening: Float = 0f,                    // 0 .. 150
    val sharpenRadius: Float = 1.0f,               // 0.5 .. 3.0
    val sharpenDetail: Float = 25f,                // 0 .. 100
    val sharpenMasking: Float = 0f,                // 0 .. 100
    val noiseReduction: Float = 0f,                // 0 .. 100
    val noiseReductionDetail: Float = 50f,         // 0 .. 100
    val noiseReductionContrast: Float = 0f,        // 0 .. 100
    val colorNoiseReduction: Float = 0f,           // 0 .. 100
    val colorNoiseReductionDetail: Float = 50f,    // 0 .. 100
    val colorNoiseReductionSmoothness: Float = 50f,// 0 .. 100

    // Optics
    val lensDistortion: Float = 0f,       // -100 .. +100
    val chromaticAberration: Float = 0f,  // 0 .. 100

    // Lens Blur
    val lensBlurAmount: Float = 0f,       // 0 .. 100
    val bokehFocus: Float = 50f,          // 0 .. 100 (default 50 balanced center focal plane)

    // Calibration
    val calibration: RgbCalibrationData = RgbCalibrationData(),

    // Foundational Camera Profile (non-destructive base layer)
    val profileId: String = CameraProfiles.DEFAULT_ID,
    val profileAmount: Float = 1f,  // 0.0 .. 2.0 (1.0 = 100%)

    // Crop, Orientation & Geometry / Perspective
    val crop: CropGeometryParams = CropGeometryParams(),

    // Selective Local Masks (Radial & Linear gradients evaluated directly on GPU)
    val masks: List<LocalMask> = emptyList(),

    // LUT / Preset Intensity
    val lutIntensity: Float = 1f    // 0.0 .. 1.0
) {
    val hasCropGeometryEdits: Boolean
        get() = !crop.isDefault

    val hasMaskEdits: Boolean
        get() = masks.any { it.enabled && it.hasAdjustments } || masks.isNotEmpty()

    val hasProfileEdits: Boolean
        get() = profileId != CameraProfiles.DEFAULT_ID && profileAmount > 0.001f
    val hasLightEdits: Boolean
        get() = exposure != 0f ||
            contrast != 0f ||
            highlights != 0f ||
            shadows != 0f ||
            whites != 0f ||
            blacks != 0f ||
            parametricShadows != 0f ||
            parametricDarks != 0f ||
            parametricLights != 0f ||
            parametricHighlights != 0f ||
            !toneCurve.isDefault

    val hasColorEdits: Boolean
        get() = whiteBalancePreset != WhiteBalancePreset.AS_SHOT ||
            temperature != 0f ||
            tint != 0f ||
            vibrance != 0f ||
            saturation != 0f ||
            isGrayscale ||
            !hsl.isDefault ||
            pointColor.isModified ||
            !colorGrading.isDefault ||
            splitShadowSat > 0f ||
            splitHighlightSat > 0f

    val hasEffectsEdits: Boolean
        get() = texture != 0f ||
            clarity != 0f ||
            dehaze != 0f ||
            vignette != 0f ||
            grain > 0f

    val hasDetailEdits: Boolean
        get() = sharpening > 0f || noiseReduction > 0f || colorNoiseReduction > 0f

    val hasOpticsEdits: Boolean
        get() = lensDistortion != 0f || chromaticAberration > 0f

    val hasLensBlurEdits: Boolean
        get() = lensBlurAmount > 0f || bokehFocus != 50f

    val hasCalibrationEdits: Boolean
        get() = !calibration.isDefault

    val hasOpticsCalibrationEdits: Boolean
        get() = hasOpticsEdits || hasCalibrationEdits

    val isDefault: Boolean
        get() = !hasProfileEdits &&
            !hasLightEdits &&
            !hasColorEdits &&
            !hasEffectsEdits &&
            !hasDetailEdits &&
            !hasOpticsEdits &&
            !hasLensBlurEdits &&
            !hasCalibrationEdits &&
            !hasCropGeometryEdits &&
            masks.isEmpty()

    fun resetLight(): GradingParams = copy(
        exposure = 0f,
        contrast = 0f,
        highlights = 0f,
        shadows = 0f,
        whites = 0f,
        blacks = 0f,
        parametricShadows = 0f,
        parametricDarks = 0f,
        parametricLights = 0f,
        parametricHighlights = 0f,
        toneCurve = ToneCurveData()
    )

    fun resetColor(): GradingParams = copy(
        whiteBalancePreset = WhiteBalancePreset.AS_SHOT,
        temperature = 0f,
        tint = 0f,
        vibrance = 0f,
        saturation = 0f,
        isGrayscale = false,
        hsl = HslMixerData(),
        pointColor = PointColorData(),
        colorGrading = ColorGradingData(),
        splitShadowHue = 0f,
        splitShadowSat = 0f,
        splitHighlightHue = 0f,
        splitHighlightSat = 0f,
        splitBalance = 0f
    )

    fun resetEffects(): GradingParams = copy(
        texture = 0f,
        clarity = 0f,
        dehaze = 0f,
        vignetteStyle = VignetteStyle.HIGHLIGHT,
        vignette = 0f,
        vignetteMidpoint = 50f,
        vignetteFeather = 50f,
        vignetteRoundness = 0f,
        vignetteHighlights = 0f,
        grain = 0f,
        grainSize = 25f,
        grainRoughness = 50f
    )

    fun resetDetail(): GradingParams = copy(
        sharpening = 0f,
        sharpenRadius = 1.0f,
        sharpenDetail = 25f,
        sharpenMasking = 0f,
        noiseReduction = 0f,
        noiseReductionDetail = 50f,
        noiseReductionContrast = 0f,
        colorNoiseReduction = 0f,
        colorNoiseReductionDetail = 50f,
        colorNoiseReductionSmoothness = 50f
    )

    fun resetOptics(): GradingParams = copy(
        lensDistortion = 0f,
        chromaticAberration = 0f
    )

    fun resetLensBlur(): GradingParams = copy(
        lensBlurAmount = 0f,
        bokehFocus = 50f
    )

    fun resetCalibration(): GradingParams = copy(
        calibration = RgbCalibrationData()
    )

    fun resetCropAndOrientation(): GradingParams = copy(
        crop = crop.resetCropAndOrientation()
    )

    fun resetCropGeometry(): GradingParams = copy(
        crop = crop.resetGeometry()
    )

    fun resetAllCropAndGeometry(): GradingParams = copy(
        crop = CropGeometryParams()
    )

    fun resetMasks(): GradingParams = copy(
        masks = emptyList()
    )

    fun withoutSpatialEdits(): GradingParams = copy(
        crop = CropGeometryParams(),
        masks = emptyList()
    )

    companion object {
        fun fromXmpSettings(
            s: XmpDevelopSettings,
            intensity: Float = 1f
        ): GradingParams {
            val t = intensity.coerceIn(0f, 1f)
            return GradingParams(
                exposure = (s.exposure * t).coerceIn(-2f, 2f),
                contrast = (s.contrast * t).coerceIn(-100f, 100f),
                highlights = (s.highlights * t).coerceIn(-100f, 100f),
                shadows = (s.shadows * t).coerceIn(-100f, 100f),
                whites = (s.whites * t).coerceIn(-100f, 100f),
                blacks = (s.blacks * t).coerceIn(-100f, 100f),
                toneCurve = ToneCurveData.fromXmp(s, t),
                parametricShadows = (s.parametricShadows * t).coerceIn(-100f, 100f),
                parametricDarks = (s.parametricDarks * t).coerceIn(-100f, 100f),
                parametricLights = (s.parametricLights * t).coerceIn(-100f, 100f),
                parametricHighlights = (s.parametricHighlights * t).coerceIn(-100f, 100f),
                temperature = (s.temperature * t).coerceIn(-100f, 100f),
                tint = (s.tint * t).coerceIn(-100f, 100f),
                vibrance = (s.vibrance * t).coerceIn(-100f, 100f),
                saturation = (s.saturation * t).coerceIn(-100f, 100f),
                isGrayscale = s.convertToGrayscale && t > 0.01f,
                hsl = HslMixerData.fromXmp(s, t),
                splitShadowHue = s.splitShadowHue,
                splitShadowSat = (s.splitShadowSat * t).coerceIn(0f, 100f),
                splitHighlightHue = s.splitHighlightHue,
                splitHighlightSat = (s.splitHighlightSat * t).coerceIn(0f, 100f),
                splitBalance = s.splitBalance,
                texture = (s.texture * t).coerceIn(-100f, 100f),
                clarity = (s.clarity * t).coerceIn(-100f, 100f),
                dehaze = (s.dehaze * t).coerceIn(-100f, 100f),
                vignetteStyle = VignetteStyle.entries.getOrElse(s.vignetteStyleIndex) { VignetteStyle.HIGHLIGHT },
                vignette = (s.vignette * t).coerceIn(-100f, 100f),
                vignetteMidpoint = s.vignetteMidpoint.coerceIn(0f, 100f),
                vignetteFeather = s.vignetteFeather.coerceIn(0f, 100f),
                vignetteRoundness = s.vignetteRoundness.coerceIn(-100f, 100f),
                vignetteHighlights = s.vignetteHighlights.coerceIn(0f, 100f),
                grain = (s.grain * t).coerceIn(0f, 100f),
                grainSize = s.grainSize.coerceIn(0f, 100f),
                grainRoughness = s.grainRoughness.coerceIn(0f, 100f),
                sharpening = (s.sharpening * t).coerceIn(0f, 150f),
                sharpenRadius = s.sharpenRadius.coerceIn(0.5f, 3.0f),
                sharpenDetail = s.sharpenDetail.coerceIn(0f, 100f),
                sharpenMasking = s.sharpenMasking.coerceIn(0f, 100f),
                noiseReduction = (s.noiseReduction * t).coerceIn(0f, 100f),
                noiseReductionDetail = s.noiseReductionDetail.coerceIn(0f, 100f),
                noiseReductionContrast = (s.noiseReductionContrast * t).coerceIn(0f, 100f),
                colorNoiseReduction = (s.colorNoiseReduction * t).coerceIn(0f, 100f),
                colorNoiseReductionDetail = s.colorNoiseReductionDetail.coerceIn(0f, 100f),
                colorNoiseReductionSmoothness = s.colorNoiseReductionSmoothness.coerceIn(0f, 100f),
                lensDistortion = (s.lensDistortion * t).coerceIn(-100f, 100f),
                chromaticAberration = (s.chromaticAberration * t).coerceIn(0f, 100f),
                calibration = RgbCalibrationData(
                    redHue = (s.redHue * t).coerceIn(-100f, 100f),
                    redSaturation = (s.redSaturation * t).coerceIn(-100f, 100f),
                    greenHue = (s.greenHue * t).coerceIn(-100f, 100f),
                    greenSaturation = (s.greenSaturation * t).coerceIn(-100f, 100f),
                    blueHue = (s.blueHue * t).coerceIn(-100f, 100f),
                    blueSaturation = (s.blueSaturation * t).coerceIn(-100f, 100f)
                ),
                lutIntensity = t
            )
        }
    }
}

@Immutable
data class HistogramData(
    val luma: FloatArray,
    val red: FloatArray,
    val green: FloatArray,
    val blue: FloatArray
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = luma.contentHashCode()
}

data class GradedResult(
    val bitmap: Bitmap,
    val histogram: HistogramData
)
