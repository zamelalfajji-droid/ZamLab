package com.example.lut

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.core.content.edit
import com.example.editor.CameraProfiles
import com.example.editor.ColorGradingData
import com.example.editor.ColorWheelValue
import com.example.editor.CopiedEdits
import com.example.editor.CropGeometryParams
import com.example.editor.GradingParams
import com.example.editor.HslBandAdjustment
import com.example.editor.HslMixerData
import com.example.editor.PointColorData
import com.example.editor.RgbCalibrationData
import com.example.editor.ToneCurveData
import com.example.editor.ToneCurvePoint
import com.example.editor.VignetteStyle
import com.example.editor.WhiteBalancePreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "UserPresetStore"
private const val USER_PRESETS_FILE = "user_presets.json"
private const val CLIPBOARD_PREFS = "zamlight_clipboard_prefs"
private const val KEY_COPIED_EDITS_JSON = "copied_edits_json"

@Immutable
data class UserPreset(
    val id: String,
    val name: String,
    val createdAtMs: Long,
    val gradingParams: GradingParams,
    val lutId: String? = null
)

object UserPresetStore {

    private val idCounter = AtomicLong(0L)

    fun newPresetId(): String = "user_preset_${System.currentTimeMillis()}_${idCounter.incrementAndGet()}"

    suspend fun loadPresets(context: Context): List<UserPreset> = withContext(Dispatchers.IO) {
        val file = File(context.filesDir, USER_PRESETS_FILE)
        if (!file.exists()) return@withContext emptyList()
        runCatching {
            val raw = file.readText(Charsets.UTF_8)
            val arr = JSONArray(raw)
            buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    deserializeUserPreset(obj)?.let { add(it) }
                }
            }
        }.onFailure { err ->
            Log.e(TAG, "Failed to read saved user presets", err)
        }.getOrDefault(emptyList())
    }

    suspend fun savePresets(context: Context, presets: List<UserPreset>) = withContext(Dispatchers.IO) {
        runCatching {
            val arr = JSONArray()
            presets.forEach { preset ->
                arr.put(serializeUserPreset(preset))
            }
            val target = File(context.filesDir, USER_PRESETS_FILE)
            val tmp = File(context.filesDir, "$USER_PRESETS_FILE.tmp")
            tmp.writeText(arr.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(target)) {
                target.writeText(arr.toString(), Charsets.UTF_8)
                tmp.delete()
            }
        }.onFailure { err ->
            Log.e(TAG, "Failed to persist saved user presets", err)
        }
    }

    fun saveClipboardEdits(context: Context, copied: CopiedEdits) {
        runCatching {
            val obj = JSONObject().apply {
                if (copied.selectedLutId != null) put("selectedLutId", copied.selectedLutId)
                if (copied.selectedUserPresetId != null) put("selectedUserPresetId", copied.selectedUserPresetId)
                put("gradingParams", serializeGradingParams(copied.gradingParams.withoutSpatialEdits()))
            }
            context.getSharedPreferences(CLIPBOARD_PREFS, Context.MODE_PRIVATE).edit {
                putString(KEY_COPIED_EDITS_JSON, obj.toString())
            }
        }.onFailure { err ->
            Log.e(TAG, "Failed to save clipboard edits", err)
        }
    }

    fun loadClipboardEdits(context: Context): CopiedEdits? {
        val raw = context.getSharedPreferences(CLIPBOARD_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_COPIED_EDITS_JSON, null)
            ?: return null
        return runCatching {
            val obj = JSONObject(raw)
            val paramsObj = obj.optJSONObject("gradingParams") ?: return@runCatching null
            val lutId = if (obj.has("selectedLutId") && !obj.isNull("selectedLutId")) {
                obj.getString("selectedLutId").takeIf { it.isNotBlank() }
            } else {
                null
            }
            val userPresetId = if (obj.has("selectedUserPresetId") && !obj.isNull("selectedUserPresetId")) {
                obj.getString("selectedUserPresetId").takeIf { it.isNotBlank() }
            } else {
                null
            }
            CopiedEdits(
                gradingParams = deserializeGradingParams(paramsObj),
                selectedLutId = lutId,
                selectedUserPresetId = userPresetId
            )
        }.onFailure { err ->
            Log.e(TAG, "Failed to restore clipboard edits", err)
        }.getOrNull()
    }

    fun clearClipboardEdits(context: Context) {
        context.getSharedPreferences(CLIPBOARD_PREFS, Context.MODE_PRIVATE).edit {
            remove(KEY_COPIED_EDITS_JSON)
        }
    }

    private fun serializeUserPreset(p: UserPreset): JSONObject {
        return JSONObject().apply {
            put("id", p.id)
            put("name", p.name)
            put("createdAtMs", p.createdAtMs)
            if (p.lutId != null) put("lutId", p.lutId)
            put("gradingParams", serializeGradingParams(p.gradingParams.withoutSpatialEdits()))
        }
    }

    private fun deserializeUserPreset(obj: JSONObject): UserPreset? {
        val id = obj.optString("id", "").trim()
        val name = obj.optString("name", "").trim()
        val paramsObj = obj.optJSONObject("gradingParams") ?: return null
        if (id.isEmpty() || name.isEmpty()) return null
        val lutId = if (obj.has("lutId") && !obj.isNull("lutId")) {
            obj.getString("lutId").takeIf { it.isNotBlank() }
        } else {
            null
        }
        return UserPreset(
            id = id,
            name = name,
            createdAtMs = obj.optLong("createdAtMs", 0L),
            gradingParams = deserializeGradingParams(paramsObj),
            lutId = lutId
        )
    }

    fun serializeGradingParams(p: GradingParams): JSONObject {
        return JSONObject().apply {
            put("exposure", p.exposure.toDouble())
            put("contrast", p.contrast.toDouble())
            put("highlights", p.highlights.toDouble())
            put("shadows", p.shadows.toDouble())
            put("whites", p.whites.toDouble())
            put("blacks", p.blacks.toDouble())
            put("parametricShadows", p.parametricShadows.toDouble())
            put("parametricDarks", p.parametricDarks.toDouble())
            put("parametricLights", p.parametricLights.toDouble())
            put("parametricHighlights", p.parametricHighlights.toDouble())
            put("toneCurve", serializeToneCurve(p.toneCurve))

            put("whiteBalancePreset", p.whiteBalancePreset.name)
            put("temperature", p.temperature.toDouble())
            put("tint", p.tint.toDouble())
            put("vibrance", p.vibrance.toDouble())
            put("saturation", p.saturation.toDouble())
            put("isGrayscale", p.isGrayscale)
            put("hsl", serializeHsl(p.hsl))
            put("pointColor", serializePointColor(p.pointColor))
            put("colorGrading", serializeColorGrading(p.colorGrading))
            put("splitShadowHue", p.splitShadowHue.toDouble())
            put("splitShadowSat", p.splitShadowSat.toDouble())
            put("splitHighlightHue", p.splitHighlightHue.toDouble())
            put("splitHighlightSat", p.splitHighlightSat.toDouble())
            put("splitBalance", p.splitBalance.toDouble())

            put("texture", p.texture.toDouble())
            put("clarity", p.clarity.toDouble())
            put("dehaze", p.dehaze.toDouble())
            put("vignetteStyle", p.vignetteStyle.name)
            put("vignette", p.vignette.toDouble())
            put("vignetteMidpoint", p.vignetteMidpoint.toDouble())
            put("vignetteFeather", p.vignetteFeather.toDouble())
            put("vignetteRoundness", p.vignetteRoundness.toDouble())
            put("vignetteHighlights", p.vignetteHighlights.toDouble())
            put("grain", p.grain.toDouble())
            put("grainSize", p.grainSize.toDouble())
            put("grainRoughness", p.grainRoughness.toDouble())

            put("sharpening", p.sharpening.toDouble())
            put("sharpenRadius", p.sharpenRadius.toDouble())
            put("sharpenDetail", p.sharpenDetail.toDouble())
            put("sharpenMasking", p.sharpenMasking.toDouble())
            put("noiseReduction", p.noiseReduction.toDouble())
            put("noiseReductionDetail", p.noiseReductionDetail.toDouble())
            put("noiseReductionContrast", p.noiseReductionContrast.toDouble())
            put("colorNoiseReduction", p.colorNoiseReduction.toDouble())
            put("colorNoiseReductionDetail", p.colorNoiseReductionDetail.toDouble())
            put("colorNoiseReductionSmoothness", p.colorNoiseReductionSmoothness.toDouble())

            put("lensDistortion", p.lensDistortion.toDouble())
            put("chromaticAberration", p.chromaticAberration.toDouble())
            put("lensBlurAmount", p.lensBlurAmount.toDouble())
            put("bokehFocus", p.bokehFocus.toDouble())

            put("calibration", JSONObject().apply {
                put("redHue", p.calibration.redHue.toDouble())
                put("redSaturation", p.calibration.redSaturation.toDouble())
                put("greenHue", p.calibration.greenHue.toDouble())
                put("greenSaturation", p.calibration.greenSaturation.toDouble())
                put("blueHue", p.calibration.blueHue.toDouble())
                put("blueSaturation", p.calibration.blueSaturation.toDouble())
            })

            put("profileId", p.profileId)
            put("profileAmount", p.profileAmount.toDouble())
            put("lutIntensity", p.lutIntensity.toDouble())
        }
    }

    fun deserializeGradingParams(obj: JSONObject): GradingParams {
        val wbName = obj.optString("whiteBalancePreset", WhiteBalancePreset.AS_SHOT.name)
        val wb = runCatching { WhiteBalancePreset.valueOf(wbName) }.getOrDefault(WhiteBalancePreset.AS_SHOT)
        val vigStyleName = obj.optString("vignetteStyle", VignetteStyle.HIGHLIGHT.name)
        val vigStyle = runCatching { VignetteStyle.valueOf(vigStyleName) }.getOrDefault(VignetteStyle.HIGHLIGHT)
        val calObj = obj.optJSONObject("calibration")
        return GradingParams(
            exposure = obj.optDouble("exposure", 0.0).toFloat(),
            contrast = obj.optDouble("contrast", 0.0).toFloat(),
            highlights = obj.optDouble("highlights", 0.0).toFloat(),
            shadows = obj.optDouble("shadows", 0.0).toFloat(),
            whites = obj.optDouble("whites", 0.0).toFloat(),
            blacks = obj.optDouble("blacks", 0.0).toFloat(),
            toneCurve = deserializeToneCurve(obj.optJSONObject("toneCurve")),
            parametricShadows = obj.optDouble("parametricShadows", 0.0).toFloat(),
            parametricDarks = obj.optDouble("parametricDarks", 0.0).toFloat(),
            parametricLights = obj.optDouble("parametricLights", 0.0).toFloat(),
            parametricHighlights = obj.optDouble("parametricHighlights", 0.0).toFloat(),
            whiteBalancePreset = wb,
            temperature = obj.optDouble("temperature", 0.0).toFloat(),
            tint = obj.optDouble("tint", 0.0).toFloat(),
            vibrance = obj.optDouble("vibrance", 0.0).toFloat(),
            saturation = obj.optDouble("saturation", 0.0).toFloat(),
            isGrayscale = obj.optBoolean("isGrayscale", false),
            hsl = deserializeHsl(obj.optJSONArray("hsl")),
            pointColor = deserializePointColor(obj.optJSONObject("pointColor")),
            colorGrading = deserializeColorGrading(obj.optJSONObject("colorGrading")),
            splitShadowHue = obj.optDouble("splitShadowHue", 0.0).toFloat(),
            splitShadowSat = obj.optDouble("splitShadowSat", 0.0).toFloat(),
            splitHighlightHue = obj.optDouble("splitHighlightHue", 0.0).toFloat(),
            splitHighlightSat = obj.optDouble("splitHighlightSat", 0.0).toFloat(),
            splitBalance = obj.optDouble("splitBalance", 0.0).toFloat(),
            texture = obj.optDouble("texture", 0.0).toFloat(),
            clarity = obj.optDouble("clarity", 0.0).toFloat(),
            dehaze = obj.optDouble("dehaze", 0.0).toFloat(),
            vignetteStyle = vigStyle,
            vignette = obj.optDouble("vignette", 0.0).toFloat(),
            vignetteMidpoint = obj.optDouble("vignetteMidpoint", 50.0).toFloat(),
            vignetteFeather = obj.optDouble("vignetteFeather", 50.0).toFloat(),
            vignetteRoundness = obj.optDouble("vignetteRoundness", 0.0).toFloat(),
            vignetteHighlights = obj.optDouble("vignetteHighlights", 0.0).toFloat(),
            grain = obj.optDouble("grain", 0.0).toFloat(),
            grainSize = obj.optDouble("grainSize", 25.0).toFloat(),
            grainRoughness = obj.optDouble("grainRoughness", 50.0).toFloat(),
            sharpening = obj.optDouble("sharpening", 0.0).toFloat(),
            sharpenRadius = obj.optDouble("sharpenRadius", 1.0).toFloat(),
            sharpenDetail = obj.optDouble("sharpenDetail", 25.0).toFloat(),
            sharpenMasking = obj.optDouble("sharpenMasking", 0.0).toFloat(),
            noiseReduction = obj.optDouble("noiseReduction", 0.0).toFloat(),
            noiseReductionDetail = obj.optDouble("noiseReductionDetail", 50.0).toFloat(),
            noiseReductionContrast = obj.optDouble("noiseReductionContrast", 0.0).toFloat(),
            colorNoiseReduction = obj.optDouble("colorNoiseReduction", 0.0).toFloat(),
            colorNoiseReductionDetail = obj.optDouble("colorNoiseReductionDetail", 50.0).toFloat(),
            colorNoiseReductionSmoothness = obj.optDouble("colorNoiseReductionSmoothness", 50.0).toFloat(),
            lensDistortion = obj.optDouble("lensDistortion", 0.0).toFloat(),
            chromaticAberration = obj.optDouble("chromaticAberration", 0.0).toFloat(),
            lensBlurAmount = obj.optDouble("lensBlurAmount", 0.0).toFloat(),
            bokehFocus = obj.optDouble("bokehFocus", 50.0).toFloat(),
            calibration = if (calObj != null) {
                RgbCalibrationData(
                    redHue = calObj.optDouble("redHue", 0.0).toFloat(),
                    redSaturation = calObj.optDouble("redSaturation", 0.0).toFloat(),
                    greenHue = calObj.optDouble("greenHue", 0.0).toFloat(),
                    greenSaturation = calObj.optDouble("greenSaturation", 0.0).toFloat(),
                    blueHue = calObj.optDouble("blueHue", 0.0).toFloat(),
                    blueSaturation = calObj.optDouble("blueSaturation", 0.0).toFloat()
                )
            } else {
                RgbCalibrationData()
            },
            profileId = obj.optString("profileId", CameraProfiles.DEFAULT_ID),
            profileAmount = obj.optDouble("profileAmount", 1.0).toFloat(),
            crop = CropGeometryParams(),
            masks = emptyList(),
            lutIntensity = obj.optDouble("lutIntensity", 1.0).toFloat()
        )
    }

    private fun serializeToneCurve(tc: ToneCurveData): JSONObject {
        fun encodePts(pts: List<ToneCurvePoint>) = JSONArray().apply {
            pts.forEach { pt ->
                put(JSONObject().apply {
                    put("x", pt.x.toDouble())
                    put("y", pt.y.toDouble())
                })
            }
        }
        return JSONObject().apply {
            put("master", encodePts(tc.master))
            put("red", encodePts(tc.red))
            put("green", encodePts(tc.green))
            put("blue", encodePts(tc.blue))
        }
    }

    private fun deserializeToneCurve(obj: JSONObject?): ToneCurveData {
        if (obj == null) return ToneCurveData()
        fun decodePts(arr: JSONArray?): List<ToneCurvePoint> {
            if (arr == null || arr.length() < 2) return ToneCurveData.DEFAULT_POINTS
            val out = mutableListOf<ToneCurvePoint>()
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                out += ToneCurvePoint(
                    x = p.optDouble("x", 0.0).toFloat().coerceIn(0f, 1f),
                    y = p.optDouble("y", 0.0).toFloat().coerceIn(0f, 1f)
                )
            }
            return ToneCurveData.sanitizeCurve(out)
        }
        return ToneCurveData(
            master = decodePts(obj.optJSONArray("master")),
            red = decodePts(obj.optJSONArray("red")),
            green = decodePts(obj.optJSONArray("green")),
            blue = decodePts(obj.optJSONArray("blue"))
        )
    }

    private fun serializeHsl(hsl: HslMixerData): JSONArray = JSONArray().apply {
        hsl.bands.forEach { b ->
            put(JSONObject().apply {
                put("h", b.hue.toDouble())
                put("s", b.saturation.toDouble())
                put("l", b.luminance.toDouble())
            })
        }
    }

    private fun deserializeHsl(arr: JSONArray?): HslMixerData {
        if (arr == null || arr.length() != 8) return HslMixerData()
        val bands = List(8) { i ->
            val o = arr.optJSONObject(i)
            if (o != null) {
                HslBandAdjustment(
                    hue = o.optDouble("h", 0.0).toFloat(),
                    saturation = o.optDouble("s", 0.0).toFloat(),
                    luminance = o.optDouble("l", 0.0).toFloat()
                )
            } else {
                HslBandAdjustment()
            }
        }
        return HslMixerData(bands)
    }

    private fun serializePointColor(pc: PointColorData): JSONObject = JSONObject().apply {
        put("sourceHueDeg", pc.sourceHueDeg.toDouble())
        put("sourceSat", pc.sourceSat.toDouble())
        put("sourceLuma", pc.sourceLuma.toDouble())
        put("swatchArgb", pc.swatchArgb)
        put("isSampled", pc.isSampled)
        put("hueShift", pc.hueShift.toDouble())
        put("saturationShift", pc.saturationShift.toDouble())
        put("luminanceShift", pc.luminanceShift.toDouble())
        put("range", pc.range.toDouble())
    }

    private fun deserializePointColor(obj: JSONObject?): PointColorData {
        if (obj == null) return PointColorData()
        return PointColorData(
            sourceHueDeg = obj.optDouble("sourceHueDeg", 0.0).toFloat(),
            sourceSat = obj.optDouble("sourceSat", 0.72).toFloat(),
            sourceLuma = obj.optDouble("sourceLuma", 0.50).toFloat(),
            swatchArgb = obj.optLong("swatchArgb", 0xFFEF4444),
            isSampled = obj.optBoolean("isSampled", false),
            hueShift = obj.optDouble("hueShift", 0.0).toFloat(),
            saturationShift = obj.optDouble("saturationShift", 0.0).toFloat(),
            luminanceShift = obj.optDouble("luminanceShift", 0.0).toFloat(),
            range = obj.optDouble("range", 50.0).toFloat()
        )
    }

    private fun serializeColorGrading(cg: ColorGradingData): JSONObject {
        fun serWheel(w: ColorWheelValue) = JSONObject().apply {
            put("h", w.hueDeg.toDouble())
            put("s", w.saturation.toDouble())
            put("l", w.luminance.toDouble())
        }
        return JSONObject().apply {
            put("shadows", serWheel(cg.shadows))
            put("midtones", serWheel(cg.midtones))
            put("highlights", serWheel(cg.highlights))
            put("global", serWheel(cg.global))
            put("blending", cg.blending.toDouble())
            put("balance", cg.balance.toDouble())
        }
    }

    private fun deserializeColorGrading(obj: JSONObject?): ColorGradingData {
        if (obj == null) return ColorGradingData()
        fun deWheel(o: JSONObject?): ColorWheelValue {
            if (o == null) return ColorWheelValue()
            return ColorWheelValue(
                hueDeg = o.optDouble("h", 0.0).toFloat(),
                saturation = o.optDouble("s", 0.0).toFloat(),
                luminance = o.optDouble("l", 0.0).toFloat()
            )
        }
        return ColorGradingData(
            shadows = deWheel(obj.optJSONObject("shadows")),
            midtones = deWheel(obj.optJSONObject("midtones")),
            highlights = deWheel(obj.optJSONObject("highlights")),
            global = deWheel(obj.optJSONObject("global")),
            blending = obj.optDouble("blending", 50.0).toFloat(),
            balance = obj.optDouble("balance", 0.0).toFloat()
        )
    }
}
