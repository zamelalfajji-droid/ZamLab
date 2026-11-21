package com.example.editor

enum class ProfileCategory(val label: String) {
    BASIC("Basic"),
    FILM("Film"),
    CINEMATIC("Cinematic"),
    MUTED("Muted"),
    BW("B&W")
}

data class CameraProfile(
    val id: String,
    val name: String,
    val category: ProfileCategory,
    val isCreative: Boolean = true,
    val swatchArgb: Long,
    // Tone curve shaping: x=contrast, y=shadowLift(black toe), z=highlightCompress, w=midPunch
    val contrast: Float = 0f,
    val shadowLift: Float = 0f,
    val highlightCompress: Float = 0f,
    val midPunch: Float = 0f,
    // White-balance / channel gains (multiplicative offsets around 1.0)
    val rGainDelta: Float = 0f,
    val gGainDelta: Float = 0f,
    val bGainDelta: Float = 0f,
    // Color richness
    val satDelta: Float = 0f,
    val vibDelta: Float = 0f,
    // Split color cast: shadow tint & highlight tint
    val shadowRedBias: Float = 0f,
    val shadowGreenBias: Float = 0f,
    val shadowBlueBias: Float = 0f,
    val highlightRedBias: Float = 0f,
    val highlightGreenBias: Float = 0f,
    val highlightBlueBias: Float = 0f,
    // Monochrome conversion & channel filter weights
    val monoMix: Float = 0f,
    val monoWeightR: Float = 0.2126f,
    val monoWeightG: Float = 0.7152f,
    val monoWeightB: Float = 0.0722f,
    // Selective natural color boost (x=foliage/teal shift, y=warm skin lift, z=blue sky depth)
    val foliageBoost: Float = 0f,
    val skinSoftWarmth: Float = 0f,
    val skyDepth: Float = 0f
)

object CameraProfiles {
    const val DEFAULT_ID = "profile_color"

    val ALL: List<CameraProfile> = listOf(
        // --- BASIC (6) ---
        CameraProfile(
            id = "profile_color",
            name = "Color",
            category = ProfileCategory.BASIC,
            isCreative = false,
            swatchArgb = 0xFF94A3B8
        ),
        CameraProfile(
            id = "profile_neutral",
            name = "Neutral",
            category = ProfileCategory.BASIC,
            swatchArgb = 0xFFCBD5E1,
            contrast = -0.10f,
            shadowLift = 0.02f,
            highlightCompress = 0.14f,
            satDelta = -0.14f
        ),
        CameraProfile(
            id = "profile_vivid",
            name = "Vivid",
            category = ProfileCategory.BASIC,
            swatchArgb = 0xFFF59E0B,
            contrast = 0.16f,
            midPunch = 0.08f,
            satDelta = 0.14f,
            vibDelta = 0.28f
        ),
        CameraProfile(
            id = "profile_landscape",
            name = "Landscape",
            category = ProfileCategory.BASIC,
            swatchArgb = 0xFF10B981,
            contrast = 0.14f,
            highlightCompress = 0.12f,
            vibDelta = 0.22f,
            foliageBoost = 0.26f,
            skyDepth = 0.24f
        ),
        CameraProfile(
            id = "profile_portrait",
            name = "Portrait",
            category = ProfileCategory.BASIC,
            swatchArgb = 0xFFF472B6,
            contrast = -0.04f,
            highlightCompress = 0.15f,
            rGainDelta = 0.03f,
            satDelta = -0.05f,
            vibDelta = 0.08f,
            skinSoftWarmth = 0.24f
        ),
        CameraProfile(
            id = "profile_monochrome",
            name = "Monochrome",
            category = ProfileCategory.BASIC,
            swatchArgb = 0xFF64748B,
            contrast = 0.08f,
            monoMix = 1f,
            monoWeightR = 0.25f,
            monoWeightG = 0.65f,
            monoWeightB = 0.10f
        ),

        // --- FILM (4) ---
        CameraProfile(
            id = "profile_warm_print",
            name = "Warm Print",
            category = ProfileCategory.FILM,
            swatchArgb = 0xFFD97706,
            contrast = 0.10f,
            shadowLift = 0.03f,
            highlightCompress = 0.18f,
            rGainDelta = 0.06f,
            gGainDelta = 0.02f,
            bGainDelta = -0.07f,
            vibDelta = 0.12f,
            shadowRedBias = 0.04f,
            highlightRedBias = 0.08f,
            highlightGreenBias = 0.03f
        ),
        CameraProfile(
            id = "profile_cool_fade",
            name = "Cool Fade",
            category = ProfileCategory.FILM,
            swatchArgb = 0xFF38BDF8,
            contrast = -0.06f,
            shadowLift = 0.06f,
            highlightCompress = 0.12f,
            rGainDelta = -0.04f,
            bGainDelta = 0.07f,
            satDelta = -0.16f,
            shadowBlueBias = 0.10f,
            shadowGreenBias = 0.03f
        ),
        CameraProfile(
            id = "profile_golden_hour",
            name = "Golden Hour",
            category = ProfileCategory.FILM,
            swatchArgb = 0xFFFBBF24,
            contrast = 0.08f,
            shadowLift = 0.02f,
            highlightCompress = 0.20f,
            rGainDelta = 0.10f,
            gGainDelta = 0.04f,
            bGainDelta = -0.10f,
            vibDelta = 0.18f,
            highlightRedBias = 0.10f,
            highlightGreenBias = 0.05f,
            skinSoftWarmth = 0.15f
        ),
        CameraProfile(
            id = "profile_faded_slide",
            name = "Faded Slide",
            category = ProfileCategory.FILM,
            swatchArgb = 0xFFA78BFA,
            contrast = -0.08f,
            shadowLift = 0.07f,
            highlightCompress = 0.16f,
            satDelta = -0.18f,
            shadowGreenBias = 0.04f,
            shadowBlueBias = 0.05f,
            highlightRedBias = 0.05f,
            highlightGreenBias = 0.02f
        ),

        // --- CINEMATIC (4) ---
        CameraProfile(
            id = "profile_teal_amber",
            name = "Teal & Amber",
            category = ProfileCategory.CINEMATIC,
            swatchArgb = 0xFF06B6D4,
            contrast = 0.20f,
            shadowLift = 0.02f,
            highlightCompress = 0.16f,
            satDelta = -0.04f,
            vibDelta = 0.20f,
            shadowRedBias = -0.06f,
            shadowGreenBias = 0.05f,
            shadowBlueBias = 0.11f,
            highlightRedBias = 0.12f,
            highlightGreenBias = 0.05f,
            highlightBlueBias = -0.06f
        ),
        CameraProfile(
            id = "profile_night_blue",
            name = "Night Blue",
            category = ProfileCategory.CINEMATIC,
            swatchArgb = 0xFF3B82F6,
            contrast = 0.18f,
            midPunch = -0.06f,
            rGainDelta = -0.08f,
            gGainDelta = -0.01f,
            bGainDelta = 0.12f,
            satDelta = -0.08f,
            shadowBlueBias = 0.12f,
            shadowGreenBias = 0.03f,
            highlightBlueBias = 0.04f
        ),
        CameraProfile(
            id = "profile_desert_heat",
            name = "Desert Heat",
            category = ProfileCategory.CINEMATIC,
            swatchArgb = 0xFFEA580C,
            contrast = 0.16f,
            highlightCompress = 0.14f,
            rGainDelta = 0.12f,
            gGainDelta = 0.02f,
            bGainDelta = -0.11f,
            vibDelta = 0.16f,
            shadowRedBias = 0.06f,
            highlightRedBias = 0.11f,
            highlightGreenBias = 0.04f
        ),
        CameraProfile(
            id = "profile_neon_dusk",
            name = "Neon Dusk",
            category = ProfileCategory.CINEMATIC,
            swatchArgb = 0xFFEC4899,
            contrast = 0.22f,
            shadowLift = 0.02f,
            vibDelta = 0.26f,
            shadowRedBias = 0.06f,
            shadowBlueBias = 0.12f,
            highlightRedBias = 0.10f,
            highlightBlueBias = 0.06f
        ),

        // --- MUTED (4) ---
        CameraProfile(
            id = "profile_matte_soft",
            name = "Matte Soft",
            category = ProfileCategory.MUTED,
            swatchArgb = 0xFF9CA3AF,
            contrast = -0.12f,
            shadowLift = 0.09f,
            highlightCompress = 0.18f,
            satDelta = -0.22f
        ),
        CameraProfile(
            id = "profile_bleached",
            name = "Bleached",
            category = ProfileCategory.MUTED,
            swatchArgb = 0xFF71717A,
            contrast = 0.24f,
            shadowLift = 0.03f,
            highlightCompress = 0.10f,
            satDelta = -0.58f,
            shadowBlueBias = 0.02f
        ),
        CameraProfile(
            id = "profile_pastel_haze",
            name = "Pastel Haze",
            category = ProfileCategory.MUTED,
            swatchArgb = 0xFFF9A8D4,
            contrast = -0.16f,
            shadowLift = 0.08f,
            highlightCompress = 0.22f,
            rGainDelta = 0.04f,
            bGainDelta = 0.03f,
            satDelta = -0.20f,
            shadowBlueBias = 0.05f,
            highlightRedBias = 0.06f
        ),
        CameraProfile(
            id = "profile_quiet_green",
            name = "Quiet Green",
            category = ProfileCategory.MUTED,
            swatchArgb = 0xFF4ADE80,
            contrast = 0.04f,
            shadowLift = 0.05f,
            highlightCompress = 0.14f,
            gGainDelta = 0.04f,
            satDelta = -0.26f,
            shadowGreenBias = 0.07f,
            foliageBoost = 0.18f
        ),

        // --- B&W (4) ---
        CameraProfile(
            id = "profile_mono_rich",
            name = "Mono Rich",
            category = ProfileCategory.BW,
            swatchArgb = 0xFF334155,
            contrast = 0.30f,
            midPunch = 0.04f,
            monoMix = 1f,
            monoWeightR = 0.30f,
            monoWeightG = 0.58f,
            monoWeightB = 0.12f
        ),
        CameraProfile(
            id = "profile_mono_red_filter",
            name = "Mono Red Filter",
            category = ProfileCategory.BW,
            swatchArgb = 0xFFDC2626,
            contrast = 0.24f,
            monoMix = 1f,
            monoWeightR = 0.64f,
            monoWeightG = 0.30f,
            monoWeightB = 0.06f
        ),
        CameraProfile(
            id = "profile_mono_soft",
            name = "Mono Soft",
            category = ProfileCategory.BW,
            swatchArgb = 0xFF94A3B8,
            contrast = -0.08f,
            shadowLift = 0.08f,
            highlightCompress = 0.18f,
            monoMix = 1f,
            monoWeightR = 0.22f,
            monoWeightG = 0.68f,
            monoWeightB = 0.10f
        ),
        CameraProfile(
            id = "profile_sepia_tone",
            name = "Sepia Tone",
            category = ProfileCategory.BW,
            swatchArgb = 0xFFB45309,
            contrast = 0.12f,
            shadowLift = 0.04f,
            highlightCompress = 0.12f,
            shadowRedBias = 0.08f,
            shadowGreenBias = 0.03f,
            shadowBlueBias = -0.05f,
            highlightRedBias = 0.14f,
            highlightGreenBias = 0.07f,
            highlightBlueBias = -0.10f,
            monoMix = 1f,
            monoWeightR = 0.32f,
            monoWeightG = 0.56f,
            monoWeightB = 0.12f
        )
    )

    private val byIdMap: Map<String, CameraProfile> = ALL.associateBy { it.id }

    fun byId(id: String?): CameraProfile = byIdMap[id] ?: ALL.first()

    fun byCategory(category: ProfileCategory): List<CameraProfile> =
        ALL.filter { it.category == category }
}
