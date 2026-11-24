package com.zamalfajji.zamlab.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Categories shown in Z-Effect's offline STUDIO (Stickers & Creative Layers) tab.
 */
enum class StickerCategory(val label: String) {
    MY_CUTOUTS("My Cutouts"),
    SHAPES("Shapes"),
    ARROWS("Arrows"),
    LIGHT_FLARES("Light Flares"),
    BADGES("Badges"),
    FRAMES("Frames"),
    SYMBOLS("Symbols"),
    NEON_GLOW("Neon Glow"),
    CYBER_HUD("Cyber HUD"),
    SOCIAL_CREATOR("Social & Creator"),
    VINTAGE_STAMPS("Vintage Stamps")
}

/**
 * Descriptor for a built-in, 100% offline vector/canvas-rendered transparent sticker.
 */
data class BuiltInSticker(
    val id: String,
    val name: String,
    val category: StickerCategory,
    val defaultBlendMode: LayerBlendMode = LayerBlendMode.NORMAL,
    val defaultScale: Float = LayerTransformMath.DEFAULT_OVERLAY_SCALE,
    val renderSizePx: Int = 512
)

/**
 * User-created transparent cutout sticker saved locally in `filesDir/cutout_stickers/`.
 */
data class SavedCutoutSticker(
    val id: String,
    val name: String,
    val file: File,
    val createdAtMs: Long
)

/**
 * Outcome returned when importing PNG/WebP sticker packs or ZIP archives into "My Cutouts".
 */
data class StickerPackImportResult(
    val importedStickers: List<SavedCutoutSticker>,
    val skippedCount: Int = 0,
    val message: String,
    val isError: Boolean = false
)

/**
 * Offline sticker & creative-layer asset repository for Z-Effect.
 *
 * Provides:
 * - 154 crisp vector/canvas-rendered transparent ARGB_8888 stickers across 10 offline categories
 *   (Shapes, Arrows, Light Flares, Badges, Frames, Symbols, Neon Glow, Cyber HUD,
 *   Social & Creator, and Vintage Stamps).
 * - Local app-storage persistence for user-generated "My Cutouts" PNG stickers created via
 *   `+ Sticker from Photo`, `+ Import ZIP / Pack`, or bookmarked online stickers.
 */
object StickerLibrary {

    private const val CUTOUT_DIR_NAME = "cutout_stickers"
    const val THUMBNAIL_SIZE_PX: Int = 144
    const val MAX_PACK_IMPORT_STICKERS: Int = 100
    const val MAX_ZIP_ENTRY_BYTES: Long = 20L * 1024L * 1024L
    const val MAX_IMPORT_DIMENSION_PX: Int = 1024
    const val RAR_UNSUPPORTED_MESSAGE: String =
        "RAR archives are not supported directly—extract the RAR in My Files first, then import the PNGs or ZIP."

    val PACK_PICKER_MIME_TYPES: Array<String> = arrayOf(
        "application/zip",
        "application/x-zip-compressed",
        "image/png",
        "image/webp",
        "*/*"
    )

    val allBuiltInStickers: List<BuiltInSticker> = listOf(
        // 1. Shapes (15)
        BuiltInSticker("shape_gold_ring", "Gold Ring", StickerCategory.SHAPES),
        BuiltInSticker("shape_amber_blob", "Amber Blob", StickerCategory.SHAPES),
        BuiltInSticker("shape_hexagon", "Hexagon", StickerCategory.SHAPES),
        BuiltInSticker("shape_diamond_prism", "Prism", StickerCategory.SHAPES),
        BuiltInSticker("shape_neon_capsule", "Capsule", StickerCategory.SHAPES),
        BuiltInSticker("shape_starburst", "Starburst", StickerCategory.SHAPES),
        BuiltInSticker("shape_crescent_gold", "Gold Crescent", StickerCategory.SHAPES),
        BuiltInSticker("shape_hollow_triangle", "Prism Tri", StickerCategory.SHAPES),
        BuiltInSticker("shape_octagon_frame", "Octagon", StickerCategory.SHAPES),
        BuiltInSticker("shape_concentric_rings", "Orbit Rings", StickerCategory.SHAPES),
        BuiltInSticker("shape_split_circle", "Duo Circle", StickerCategory.SHAPES),
        BuiltInSticker("shape_wave_ribbon", "Wave Ribbon", StickerCategory.SHAPES),
        BuiltInSticker("shape_checker_pill", "Check Pill", StickerCategory.SHAPES),
        BuiltInSticker("shape_emerald_orb", "Emerald Orb", StickerCategory.SHAPES),
        BuiltInSticker("shape_ruby_shield", "Ruby Shield", StickerCategory.SHAPES),

        // 2. Arrows (15)
        BuiltInSticker("arrow_bold_right", "Bold Arrow", StickerCategory.ARROWS),
        BuiltInSticker("arrow_curved_swoosh", "Curved Arrow", StickerCategory.ARROWS),
        BuiltInSticker("arrow_double_chevron", "Double Chevron", StickerCategory.ARROWS),
        BuiltInSticker("arrow_studio_pointer", "Studio Pointer", StickerCategory.ARROWS),
        BuiltInSticker("arrow_loop_arc", "Loop Arrow", StickerCategory.ARROWS),
        BuiltInSticker("arrow_drop_pin", "Focus Pin", StickerCategory.ARROWS),
        BuiltInSticker("arrow_up_boost", "Boost Up", StickerCategory.ARROWS),
        BuiltInSticker("arrow_down_drop", "Drop Down", StickerCategory.ARROWS),
        BuiltInSticker("arrow_diag_ne", "Trend Up", StickerCategory.ARROWS),
        BuiltInSticker("arrow_triple_dash", "Fast Forward", StickerCategory.ARROWS),
        BuiltInSticker("arrow_circle_return", "Loop Return", StickerCategory.ARROWS),
        BuiltInSticker("arrow_dashed_curve", "Dotted Arc", StickerCategory.ARROWS),
        BuiltInSticker("arrow_split_fork", "Split Fork", StickerCategory.ARROWS),
        BuiltInSticker("arrow_zigzag_bolt", "Zigzag Surge", StickerCategory.ARROWS),
        BuiltInSticker("arrow_callout_tag", "Callout Tag", StickerCategory.ARROWS),

        // 3. Light Flares (15) - default to SCREEN blend mode for instant optical glow compositing
        BuiltInSticker("flare_anamorphic_cyan", "Anamorphic", StickerCategory.LIGHT_FLARES, LayerBlendMode.SCREEN, 0.88f),
        BuiltInSticker("flare_golden_sunburst", "Sunburst", StickerCategory.LIGHT_FLARES, LayerBlendMode.SCREEN, 0.78f),
        BuiltInSticker("flare_prism_leak", "Prism Leak", StickerCategory.LIGHT_FLARES, LayerBlendMode.SCREEN, 0.92f),
        BuiltInSticker("flare_bokeh_orbs", "Bokeh Orbs", StickerCategory.LIGHT_FLARES, LayerBlendMode.SCREEN, 0.85f),
        BuiltInSticker("flare_star_glint", "Star Glint", StickerCategory.LIGHT_FLARES, LayerBlendMode.SCREEN, 0.62f),
        BuiltInSticker("flare_amber_halo", "Amber Halo", StickerCategory.LIGHT_FLARES, LayerBlendMode.SCREEN, 0.82f),
        BuiltInSticker("flare_magenta_streak", "Magenta Beam", StickerCategory.LIGHT_FLARES, LayerBlendMode.SCREEN, 0.88f),
        BuiltInSticker("flare_emerald_beam", "Emerald Beam", StickerCategory.LIGHT_FLARES, LayerBlendMode.SCREEN, 0.88f),
        BuiltInSticker("flare_violet_nova", "Violet Nova", StickerCategory.LIGHT_FLARES, LayerBlendMode.SCREEN, 0.80f),
        BuiltInSticker("flare_solar_ring", "Solar Ring", StickerCategory.LIGHT_FLARES, LayerBlendMode.SCREEN, 0.82f),
        BuiltInSticker("flare_arctic_glint", "Arctic Glint", StickerCategory.LIGHT_FLARES, LayerBlendMode.SCREEN, 0.64f),
        BuiltInSticker("flare_sunset_wash", "Sunset Wash", StickerCategory.LIGHT_FLARES, LayerBlendMode.SCREEN, 0.92f),
        BuiltInSticker("flare_crimson_leak", "Crimson Leak", StickerCategory.LIGHT_FLARES, LayerBlendMode.SCREEN, 0.90f),
        BuiltInSticker("flare_double_bokeh", "Dream Bokeh", StickerCategory.LIGHT_FLARES, LayerBlendMode.SCREEN, 0.86f),
        BuiltInSticker("flare_diamond_spark", "Gold Glint", StickerCategory.LIGHT_FLARES, LayerBlendMode.SCREEN, 0.62f),

        // 4. Badges (15)
        BuiltInSticker("badge_raw_stamp", "RAW", StickerCategory.BADGES, defaultScale = 0.44f),
        BuiltInSticker("badge_film_35mm", "35MM", StickerCategory.BADGES, defaultScale = 0.44f),
        BuiltInSticker("badge_pro_gold", "PRO", StickerCategory.BADGES, defaultScale = 0.42f),
        BuiltInSticker("badge_new_pill", "NEW", StickerCategory.BADGES, defaultScale = 0.42f),
        BuiltInSticker("badge_rec_live", "REC", StickerCategory.BADGES, defaultScale = 0.46f),
        BuiltInSticker("badge_hdr_plus", "HDR+", StickerCategory.BADGES, defaultScale = 0.44f),
        BuiltInSticker("badge_4k_uhd", "4K UHD", StickerCategory.BADGES, defaultScale = 0.46f),
        BuiltInSticker("badge_cine_log", "C-LOG", StickerCategory.BADGES, defaultScale = 0.45f),
        BuiltInSticker("badge_iso_800", "ISO 800", StickerCategory.BADGES, defaultScale = 0.46f),
        BuiltInSticker("badge_f14_prime", "f/1.4", StickerCategory.BADGES, defaultScale = 0.42f),
        BuiltInSticker("badge_studio_vip", "STUDIO", StickerCategory.BADGES, defaultScale = 0.46f),
        BuiltInSticker("badge_edited_ze", "Z-FX", StickerCategory.BADGES, defaultScale = 0.44f),
        BuiltInSticker("badge_limited", "LIMITED", StickerCategory.BADGES, defaultScale = 0.46f),
        BuiltInSticker("badge_ai_hd", "AI HD", StickerCategory.BADGES, defaultScale = 0.44f),
        BuiltInSticker("badge_mono_bw", "B&W", StickerCategory.BADGES, defaultScale = 0.42f),

        // 5. Frames (15) - default to full fit scale and crisp 1024px resolution
        BuiltInSticker("frame_polaroid", "Instant", StickerCategory.FRAMES, defaultScale = 0.96f, renderSizePx = 1024),
        BuiltInSticker("frame_viewfinder", "Viewfinder", StickerCategory.FRAMES, defaultScale = 0.96f, renderSizePx = 1024),
        BuiltInSticker("frame_gold_hairline", "Gold Trim", StickerCategory.FRAMES, defaultScale = 0.96f, renderSizePx = 1024),
        BuiltInSticker("frame_film_strip", "35mm Strip", StickerCategory.FRAMES, defaultScale = 0.98f, renderSizePx = 1024),
        BuiltInSticker("frame_dashed_trim", "Crop Trim", StickerCategory.FRAMES, defaultScale = 0.96f, renderSizePx = 1024),
        BuiltInSticker("frame_gallery_matte", "Matte", StickerCategory.FRAMES, defaultScale = 0.98f, renderSizePx = 1024),
        BuiltInSticker("frame_cinema_bars", "Letterbox", StickerCategory.FRAMES, defaultScale = 0.98f, renderSizePx = 1024),
        BuiltInSticker("frame_neon_cyan", "Cyan Glow", StickerCategory.FRAMES, defaultScale = 0.96f, renderSizePx = 1024),
        BuiltInSticker("frame_neon_magenta", "Pink Glow", StickerCategory.FRAMES, defaultScale = 0.96f, renderSizePx = 1024),
        BuiltInSticker("frame_double_white", "Fine White", StickerCategory.FRAMES, defaultScale = 0.96f, renderSizePx = 1024),
        BuiltInSticker("frame_rounded_gold", "Round Gold", StickerCategory.FRAMES, defaultScale = 0.96f, renderSizePx = 1024),
        BuiltInSticker("frame_editorial_dark", "Editorial", StickerCategory.FRAMES, defaultScale = 0.98f, renderSizePx = 1024),
        BuiltInSticker("frame_camcorder_rec", "Camcorder", StickerCategory.FRAMES, defaultScale = 0.96f, renderSizePx = 1024),
        BuiltInSticker("frame_corner_ticks", "Corner Gold", StickerCategory.FRAMES, defaultScale = 0.96f, renderSizePx = 1024),
        BuiltInSticker("frame_split_arch", "Arch Portal", StickerCategory.FRAMES, defaultScale = 0.98f, renderSizePx = 1024),

        // 6. Symbols (15)
        BuiltInSticker("symbol_sparkles", "Sparkles", StickerCategory.SYMBOLS),
        BuiltInSticker("symbol_heart", "Heart", StickerCategory.SYMBOLS),
        BuiltInSticker("symbol_lightning", "Lightning", StickerCategory.SYMBOLS),
        BuiltInSticker("symbol_crown", "Crown", StickerCategory.SYMBOLS),
        BuiltInSticker("symbol_aperture", "Aperture", StickerCategory.SYMBOLS),
        BuiltInSticker("symbol_north_star", "North Star", StickerCategory.SYMBOLS),
        BuiltInSticker("symbol_moon_crescent", "Moon", StickerCategory.SYMBOLS),
        BuiltInSticker("symbol_sun_rays", "Sunburst", StickerCategory.SYMBOLS),
        BuiltInSticker("symbol_diamond_gem", "Diamond", StickerCategory.SYMBOLS),
        BuiltInSticker("symbol_music_note", "Music Note", StickerCategory.SYMBOLS),
        BuiltInSticker("symbol_flame_icon", "Flame", StickerCategory.SYMBOLS),
        BuiltInSticker("symbol_wings_pair", "Angel Wings", StickerCategory.SYMBOLS),
        BuiltInSticker("symbol_infinity", "Infinity", StickerCategory.SYMBOLS),
        BuiltInSticker("symbol_verified_check", "Verified", StickerCategory.SYMBOLS),
        BuiltInSticker("symbol_compass_rose", "Compass", StickerCategory.SYMBOLS),

        // 7. Neon Glow (16)
        BuiltInSticker("neon_ring_cyan", "Cyan Ring", StickerCategory.NEON_GLOW),
        BuiltInSticker("neon_ring_magenta", "Pink Ring", StickerCategory.NEON_GLOW),
        BuiltInSticker("neon_heart_pink", "Neon Heart", StickerCategory.NEON_GLOW),
        BuiltInSticker("neon_bolt_yellow", "Neon Bolt", StickerCategory.NEON_GLOW),
        BuiltInSticker("neon_star_purple", "Neon Star", StickerCategory.NEON_GLOW),
        BuiltInSticker("neon_crown_gold", "Neon Crown", StickerCategory.NEON_GLOW),
        BuiltInSticker("neon_triangle_cyan", "Cyber Tri", StickerCategory.NEON_GLOW),
        BuiltInSticker("neon_hex_lime", "Lime Hex", StickerCategory.NEON_GLOW),
        BuiltInSticker("neon_diamond_blue", "Blue Prism", StickerCategory.NEON_GLOW),
        BuiltInSticker("neon_wings_cyan", "Neon Wings", StickerCategory.NEON_GLOW),
        BuiltInSticker("neon_halo_amber", "Gold Halo", StickerCategory.NEON_GLOW),
        BuiltInSticker("neon_infinity_pink", "Pink Loop", StickerCategory.NEON_GLOW),
        BuiltInSticker("neon_wave_teal", "Teal Wave", StickerCategory.NEON_GLOW),
        BuiltInSticker("neon_arrow_lime", "Lime Arrow", StickerCategory.NEON_GLOW),
        BuiltInSticker("neon_moon_violet", "Violet Moon", StickerCategory.NEON_GLOW),
        BuiltInSticker("neon_sign_vibes", "VIBES Sign", StickerCategory.NEON_GLOW, defaultScale = 0.56f),

        // 8. Cyber HUD (16)
        BuiltInSticker("hud_target_lock", "Target Lock", StickerCategory.CYBER_HUD),
        BuiltInSticker("hud_radar_ring", "Radar Scan", StickerCategory.CYBER_HUD),
        BuiltInSticker("hud_bracket_amber", "Lock Box", StickerCategory.CYBER_HUD),
        BuiltInSticker("hud_scan_lines", "Scan Grid", StickerCategory.CYBER_HUD),
        BuiltInSticker("hud_audio_wave", "Audio EQ", StickerCategory.CYBER_HUD),
        BuiltInSticker("hud_hex_matrix", "Hex Matrix", StickerCategory.CYBER_HUD),
        BuiltInSticker("hud_telemetry_bar", "SYS 98%", StickerCategory.CYBER_HUD, defaultScale = 0.52f),
        BuiltInSticker("hud_crosshair_red", "Red Reticle", StickerCategory.CYBER_HUD),
        BuiltInSticker("hud_compass_strip", "Heading", StickerCategory.CYBER_HUD, defaultScale = 0.62f),
        BuiltInSticker("hud_battery_full", "Power Cell", StickerCategory.CYBER_HUD, defaultScale = 0.48f),
        BuiltInSticker("hud_signal_bars", "Signal 5G", StickerCategory.CYBER_HUD, defaultScale = 0.48f),
        BuiltInSticker("hud_data_chip", "AI 4.0", StickerCategory.CYBER_HUD, defaultScale = 0.50f),
        BuiltInSticker("hud_orbital_arc", "Orbit Arc", StickerCategory.CYBER_HUD),
        BuiltInSticker("hud_warning_tri", "Alert Tri", StickerCategory.CYBER_HUD),
        BuiltInSticker("hud_timecode_box", "Timecode", StickerCategory.CYBER_HUD, defaultScale = 0.54f),
        BuiltInSticker("hud_corner_brackets", "Focus HUD", StickerCategory.CYBER_HUD),

        // 9. Social & Creator (16)
        BuiltInSticker("social_live_pill", "LIVE", StickerCategory.SOCIAL_CREATOR, defaultScale = 0.46f),
        BuiltInSticker("social_new_drop", "NEW DROP", StickerCategory.SOCIAL_CREATOR, defaultScale = 0.50f),
        BuiltInSticker("social_subscribe", "FOLLOW +", StickerCategory.SOCIAL_CREATOR, defaultScale = 0.50f),
        BuiltInSticker("social_swipe_up", "SWIPE UP", StickerCategory.SOCIAL_CREATOR, defaultScale = 0.48f),
        BuiltInSticker("social_pov_tag", "POV:", StickerCategory.SOCIAL_CREATOR, defaultScale = 0.46f),
        BuiltInSticker("social_bts_tag", "BTS", StickerCategory.SOCIAL_CREATOR, defaultScale = 0.44f),
        BuiltInSticker("social_vlog_cam", "VLOG", StickerCategory.SOCIAL_CREATOR, defaultScale = 0.44f),
        BuiltInSticker("social_shot_on", "SHOT ON", StickerCategory.SOCIAL_CREATOR, defaultScale = 0.50f),
        BuiltInSticker("social_link_bio", "LINK", StickerCategory.SOCIAL_CREATOR, defaultScale = 0.46f),
        BuiltInSticker("social_like_burst", "10K Likes", StickerCategory.SOCIAL_CREATOR, defaultScale = 0.48f),
        BuiltInSticker("social_fire_hot", "TRENDING", StickerCategory.SOCIAL_CREATOR, defaultScale = 0.52f),
        BuiltInSticker("social_4k_60fps", "4K 60FPS", StickerCategory.SOCIAL_CREATOR, defaultScale = 0.50f),
        BuiltInSticker("social_no_filter", "RAW EDIT", StickerCategory.SOCIAL_CREATOR, defaultScale = 0.50f),
        BuiltInSticker("social_sound_on", "AUDIO ON", StickerCategory.SOCIAL_CREATOR, defaultScale = 0.50f),
        BuiltInSticker("social_daily_fit", "OOTD", StickerCategory.SOCIAL_CREATOR, defaultScale = 0.46f),
        BuiltInSticker("social_creator_check", "CREATOR", StickerCategory.SOCIAL_CREATOR, defaultScale = 0.52f),

        // 10. Vintage Stamps (16)
        BuiltInSticker("stamp_airmail", "Air Mail", StickerCategory.VINTAGE_STAMPS, defaultScale = 0.54f),
        BuiltInSticker("stamp_approved", "Approved", StickerCategory.VINTAGE_STAMPS, defaultScale = 0.54f),
        BuiltInSticker("stamp_kodak_date", "'98 Date", StickerCategory.VINTAGE_STAMPS, defaultScale = 0.56f),
        BuiltInSticker("stamp_film_lab", "Film Lab", StickerCategory.VINTAGE_STAMPS, defaultScale = 0.50f),
        BuiltInSticker("stamp_original", "Original", StickerCategory.VINTAGE_STAMPS, defaultScale = 0.50f),
        BuiltInSticker("stamp_tokyo_pass", "Tokyo Pass", StickerCategory.VINTAGE_STAMPS, defaultScale = 0.52f),
        BuiltInSticker("stamp_paris_pass", "Paris Pass", StickerCategory.VINTAGE_STAMPS, defaultScale = 0.52f),
        BuiltInSticker("stamp_nyc_pass", "NYC Pass", StickerCategory.VINTAGE_STAMPS, defaultScale = 0.52f),
        BuiltInSticker("stamp_vintage_1999", "Est. 1999", StickerCategory.VINTAGE_STAMPS, defaultScale = 0.52f),
        BuiltInSticker("stamp_analog_club", "Analog Club", StickerCategory.VINTAGE_STAMPS, defaultScale = 0.50f),
        BuiltInSticker("stamp_handcrafted", "Crafted", StickerCategory.VINTAGE_STAMPS, defaultScale = 0.50f),
        BuiltInSticker("stamp_wax_seal", "Wax Seal", StickerCategory.VINTAGE_STAMPS, defaultScale = 0.46f),
        BuiltInSticker("stamp_top_secret", "Archive", StickerCategory.VINTAGE_STAMPS, defaultScale = 0.52f),
        BuiltInSticker("stamp_post_express", "Express", StickerCategory.VINTAGE_STAMPS, defaultScale = 0.54f),
        BuiltInSticker("stamp_gold_ribbon", "1st Award", StickerCategory.VINTAGE_STAMPS, defaultScale = 0.48f),
        BuiltInSticker("stamp_retro_rec", "REC 1995", StickerCategory.VINTAGE_STAMPS, defaultScale = 0.54f)
    )

    fun stickersForCategory(category: StickerCategory): List<BuiltInSticker> =
        allBuiltInStickers.filter { it.category == category }

    // --- Local "My Cutouts" Storage ---

    private fun cutoutsDir(context: Context): File =
        File(context.filesDir, CUTOUT_DIR_NAME).apply { mkdirs() }

    suspend fun loadSavedCutouts(context: Context): List<SavedCutoutSticker> =
        withContext(Dispatchers.IO) {
            val dir = cutoutsDir(context)
            val files = dir.listFiles { file ->
                file.isFile && file.extension.equals("png", ignoreCase = true) && file.length() > 0L
            }.orEmpty()
            files.sortedByDescending { it.lastModified() }.map { file ->
                val raw = file.nameWithoutExtension
                val parts = raw.split("__", limit = 2)
                val id = parts.firstOrNull().orEmpty().ifBlank { raw }
                val prettyName = parts.getOrNull(1)
                    ?.replace('_', ' ')
                    ?.trim()
                    ?.ifBlank { "Cutout" }
                    ?: "Cutout"
                SavedCutoutSticker(
                    id = id,
                    name = prettyName,
                    file = file,
                    createdAtMs = file.lastModified()
                )
            }
        }

    suspend fun saveCutoutBitmap(
        context: Context,
        cutoutBitmap: Bitmap,
        name: String
    ): SavedCutoutSticker = withContext(Dispatchers.IO) {
        val trimmed = trimTransparentPadding(cutoutBitmap, padPx = 8)
        val id = "cut_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
        val safeName = name.trim()
            .replace(Regex("[^A-Za-z0-9 _-]"), "")
            .trim()
            .ifBlank { "Cutout" }
            .take(28)
        val fileSlug = safeName.replace(' ', '_')
        val outFile = File(cutoutsDir(context), "${id}__${fileSlug}.png")
        FileOutputStream(outFile).use { fos ->
            trimmed.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
        if (trimmed !== cutoutBitmap && !trimmed.isRecycled) {
            trimmed.recycle()
        }
        SavedCutoutSticker(
            id = id,
            name = safeName,
            file = outFile,
            createdAtMs = outFile.lastModified()
        )
    }

    suspend fun deleteSavedCutout(context: Context, cutoutId: String): Boolean =
        withContext(Dispatchers.IO) {
            val dir = cutoutsDir(context)
            val match = dir.listFiles()?.firstOrNull { file ->
                file.nameWithoutExtension == cutoutId ||
                    file.nameWithoutExtension.startsWith("${cutoutId}__")
            } ?: return@withContext false
            match.delete()
        }

    suspend fun decodeCutoutBitmap(
        file: File,
        maxDimension: Int = LayerCompositor.OVERLAY_PREVIEW_MAX_DIM
    ): Bitmap? = withContext(Dispatchers.IO) {
        if (!file.exists() || file.length() <= 0L) return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        var sample = 1
        val maxSide = max(bounds.outWidth, bounds.outHeight)
        while (maxSide / (sample * 2) >= maxDimension) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        BitmapFactory.decodeFile(file.absolutePath, opts)
    }

    // --- ZIP / Multi-Image Sticker Pack Import ("+ Import ZIP / Pack") ---

    /**
     * Imports one or more user-selected files (`.zip` sticker packs, `.png`, or `.webp` images)
     * into local "My Cutouts" storage (`filesDir/cutout_stickers/`).
     *
     * - `.zip` archives are unpacked with [ZipInputStream], skipping `__MACOSX` hidden files and
     *   dotfiles, enforcing a safe 20MB per-entry limit and 100-sticker cap, and downscaling
     *   oversized images to max 1024px.
     * - `.rar` files are detected by extension, MIME type, or `"Rar!"` magic bytes and return a
     *   clear, helpful message instructing the user to extract the RAR in My Files first.
     */
    suspend fun importStickerPackFromUris(
        context: Context,
        uris: List<Uri>,
        onProgress: ((String) -> Unit)? = null
    ): StickerPackImportResult = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) {
            return@withContext StickerPackImportResult(
                importedStickers = emptyList(),
                message = "No files selected",
                isError = true
            )
        }

        val imported = ArrayList<SavedCutoutSticker>()
        var skipped = 0
        var sawRar = false
        var hitCap = false

        for (uri in uris) {
            if (imported.size >= MAX_PACK_IMPORT_STICKERS) {
                hitCap = true
                break
            }
            val displayName = resolveDocumentDisplayName(context, uri)
            val mimeType = runCatching { context.contentResolver.getType(uri) }.getOrNull().orEmpty().lowercase()
            val nameLower = displayName.lowercase()

            val headerBytes = readHeaderSignature(context, uri)
            val isRar = nameLower.endsWith(".rar") ||
                mimeType.contains("rar") ||
                hasRarMagicHeader(headerBytes)
            if (isRar) {
                sawRar = true
                skipped++
                continue
            }

            val isZip = nameLower.endsWith(".zip") ||
                mimeType.contains("zip") ||
                hasZipMagicHeader(headerBytes)

            if (isZip) {
                onProgress?.invoke("Extracting sticker ZIP…")
                val remaining = MAX_PACK_IMPORT_STICKERS - imported.size
                val zipOutcome = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        importZipStream(context, stream, remaining, onProgress)
                    }
                }.getOrNull()
                if (zipOutcome != null) {
                    imported.addAll(zipOutcome.importedStickers)
                    skipped += zipOutcome.skippedCount
                    if (zipOutcome.hitStickerCap) hitCap = true
                } else {
                    skipped++
                }
            } else {
                onProgress?.invoke("Importing sticker…")
                val rawBytes = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        readBoundedBytes(stream, MAX_ZIP_ENTRY_BYTES)
                    }
                }.getOrNull()
                if (rawBytes == null) {
                    skipped++
                    continue
                }
                val bmp = decodeAndDownscaleStickerBytes(rawBytes, MAX_IMPORT_DIMENSION_PX)
                if (bmp == null) {
                    skipped++
                    continue
                }
                try {
                    val saved = saveCutoutBitmap(context, bmp, cleanImportedStickerName(displayName))
                    imported.add(saved)
                } finally {
                    if (!bmp.isRecycled) bmp.recycle()
                }
            }
        }

        val count = imported.size
        val plural = if (count == 1) "sticker" else "stickers"
        val message = when {
            count == 0 && sawRar -> RAR_UNSUPPORTED_MESSAGE
            count > 0 && sawRar -> "Imported $count $plural into My Cutouts. $RAR_UNSUPPORTED_MESSAGE"
            count > 0 && hitCap -> "Imported $count stickers into My Cutouts ($MAX_PACK_IMPORT_STICKERS-sticker pack limit reached)"
            count > 0 -> "Imported $count $plural into My Cutouts"
            else -> "No valid PNG or WebP stickers found in the selected file"
        }

        StickerPackImportResult(
            importedStickers = imported,
            skippedCount = skipped,
            message = message,
            isError = count == 0
        )
    }

    internal data class ZipExtractionOutcome(
        val importedStickers: List<SavedCutoutSticker>,
        val skippedCount: Int,
        val hitStickerCap: Boolean
    )

    internal suspend fun importZipStream(
        context: Context,
        inputStream: InputStream,
        maxStickers: Int = MAX_PACK_IMPORT_STICKERS,
        onProgress: ((String) -> Unit)? = null
    ): ZipExtractionOutcome = withContext(Dispatchers.IO) {
        val imported = ArrayList<SavedCutoutSticker>()
        var skipped = 0
        var hitCap = false

        ZipInputStream(BufferedInputStream(inputStream)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                try {
                    if (entry.isDirectory) continue
                    val entryName = entry.name.orEmpty().replace('\\', '/')
                    if (isIgnoredZipEntry(entryName)) {
                        continue
                    }
                    val baseName = entryName.substringAfterLast('/')
                    val ext = baseName.substringAfterLast('.', "").lowercase()
                    if (ext != "png" && ext != "webp") {
                        skipped++
                        continue
                    }
                    if (imported.size >= maxStickers) {
                        hitCap = true
                        break
                    }
                    if (entry.size > MAX_ZIP_ENTRY_BYTES) {
                        skipped++
                        continue
                    }
                    val entryBytes = readBoundedBytes(zis, MAX_ZIP_ENTRY_BYTES)
                    if (entryBytes == null) {
                        skipped++
                        continue
                    }
                    val bmp = decodeAndDownscaleStickerBytes(entryBytes, MAX_IMPORT_DIMENSION_PX)
                    if (bmp == null) {
                        skipped++
                        continue
                    }
                    try {
                        val saved = saveCutoutBitmap(context, bmp, cleanImportedStickerName(baseName))
                        imported.add(saved)
                        onProgress?.invoke("Imported ${imported.size} sticker${if (imported.size == 1) "" else "s"}…")
                    } finally {
                        if (!bmp.isRecycled) bmp.recycle()
                    }
                } finally {
                    runCatching { zis.closeEntry() }
                }
            }
        }
        ZipExtractionOutcome(imported, skipped, hitCap)
    }

    internal fun isIgnoredZipEntry(entryPath: String): Boolean {
        val normalized = entryPath.replace('\\', '/').trim()
        if (normalized.isEmpty()) return true
        if (normalized.split('/').any { it.equals("__MACOSX", ignoreCase = true) }) return true
        val base = normalized.substringAfterLast('/')
        return base.isEmpty() || base.startsWith("._") || base.startsWith(".")
    }

    internal fun decodeAndDownscaleStickerBytes(
        bytes: ByteArray,
        maxDimensionPx: Int = MAX_IMPORT_DIMENSION_PX
    ): Bitmap? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        val maxSide = max(bounds.outWidth, bounds.outHeight)
        while (maxSide / (sample * 2) >= maxDimensionPx) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        val curMax = max(decoded.width, decoded.height)
        if (curMax <= maxDimensionPx) return decoded

        val scale = maxDimensionPx.toFloat() / curMax.toFloat()
        val targetW = (decoded.width * scale).roundToInt().coerceAtLeast(1)
        val targetH = (decoded.height * scale).roundToInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(decoded, targetW, targetH, true)
        if (scaled !== decoded && !decoded.isRecycled) {
            decoded.recycle()
        }
        return scaled
    }

    private fun readBoundedBytes(stream: InputStream, maxBytes: Long): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val read = stream.read(buf)
            if (read <= 0) break
            total += read
            if (total > maxBytes) {
                return null
            }
            out.write(buf, 0, read)
        }
        return out.toByteArray()
    }

    private fun readHeaderSignature(context: Context, uri: Uri): ByteArray {
        val header = ByteArray(8)
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val n = stream.read(header)
                if (n > 0) header.copyOf(n) else ByteArray(0)
            } ?: ByteArray(0)
        }.getOrDefault(ByteArray(0))
    }

    internal fun hasRarMagicHeader(header: ByteArray): Boolean =
        header.size >= 4 &&
            header[0] == 0x52.toByte() && // 'R'
            header[1] == 0x61.toByte() && // 'a'
            header[2] == 0x72.toByte() && // 'r'
            header[3] == 0x21.toByte()    // '!'

    internal fun hasZipMagicHeader(header: ByteArray): Boolean =
        header.size >= 4 &&
            header[0] == 0x50.toByte() && // 'P'
            header[1] == 0x4B.toByte() && // 'K'
            (header[2] == 0x03.toByte() || header[2] == 0x05.toByte() || header[2] == 0x07.toByte())

    private fun resolveDocumentDisplayName(context: Context, uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        val name = cursor.getString(idx)
                        if (!name.isNullOrBlank()) return name
                    }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { "Sticker" } ?: "Sticker"
    }

    private fun cleanImportedStickerName(rawFileName: String): String {
        val withoutExt = rawFileName.substringAfterLast('/').substringBeforeLast('.')
        return withoutExt
            .replace('_', ' ')
            .replace('-', ' ')
            .replace(Regex("[^A-Za-z0-9 ]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            .ifBlank { "Sticker" }
            .take(28)
    }

    /**
     * Crops away excess transparent border around a Smart Cut foreground cutout so the saved
     * sticker has snug, ergonomic transform handles when dropped onto the canvas.
     */
    internal fun trimTransparentPadding(source: Bitmap, padPx: Int = 6): Bitmap {
        val w = source.width
        val h = source.height
        if (w <= 2 || h <= 2) return source
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)

        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        for (y in 0 until h) {
            val rowOff = y * w
            for (x in 0 until w) {
                val alpha = (pixels[rowOff + x] ushr 24) and 0xFF
                if (alpha > 8) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < minX || maxY < minY) return source

        val left = (minX - padPx).coerceAtLeast(0)
        val top = (minY - padPx).coerceAtLeast(0)
        val right = (maxX + padPx + 1).coerceAtMost(w)
        val bottom = (maxY + padPx + 1).coerceAtMost(h)
        val cropW = (right - left).coerceAtLeast(1)
        val cropH = (bottom - top).coerceAtLeast(1)
        if (cropW == w && cropH == h) return source
        return Bitmap.createBitmap(source, left, top, cropW, cropH)
    }

    // --- Vector / Canvas Sticker Renderer (ARGB_8888 Transparent) ---

    fun renderBuiltInSticker(
        sticker: BuiltInSticker,
        sizePx: Int = sticker.renderSizePx
    ): Bitmap {
        val s = sizePx.coerceIn(64, 1024)
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(bmp)
        val w = s.toFloat()
        val h = s.toFloat()

        when (sticker.id) {
            // SHAPES
            "shape_gold_ring" -> drawGoldRing(canvas, w, h)
            "shape_amber_blob" -> drawAmberBlob(canvas, w, h)
            "shape_hexagon" -> drawHexagonBadge(canvas, w, h)
            "shape_diamond_prism" -> drawDiamondPrism(canvas, w, h)
            "shape_neon_capsule" -> drawNeonCapsule(canvas, w, h)
            "shape_starburst" -> drawStarburstShape(canvas, w, h)

            // ARROWS
            "arrow_bold_right" -> drawBoldArrow(canvas, w, h)
            "arrow_curved_swoosh" -> drawCurvedArrow(canvas, w, h)
            "arrow_double_chevron" -> drawDoubleChevron(canvas, w, h)
            "arrow_studio_pointer" -> drawStudioPointer(canvas, w, h)
            "arrow_loop_arc" -> drawLoopArrow(canvas, w, h)
            "arrow_drop_pin" -> drawDropPin(canvas, w, h)

            // LIGHT FLARES
            "flare_anamorphic_cyan" -> drawAnamorphicCyanFlare(canvas, w, h)
            "flare_golden_sunburst" -> drawGoldenSunburstFlare(canvas, w, h)
            "flare_prism_leak" -> drawPrismLeakFlare(canvas, w, h)
            "flare_bokeh_orbs" -> drawBokehOrbsFlare(canvas, w, h)
            "flare_star_glint" -> drawStarGlintFlare(canvas, w, h)
            "flare_amber_halo" -> drawAmberHaloFlare(canvas, w, h)

            // BADGES
            "badge_raw_stamp" -> drawEditorialStamp(canvas, w, h, "RAW", Color.rgb(245, 166, 35), Color.argb(230, 18, 18, 22))
            "badge_film_35mm" -> drawEditorialStamp(canvas, w, h, "35MM", Color.WHITE, Color.argb(225, 24, 24, 28))
            "badge_pro_gold" -> drawSolidPillBadge(canvas, w, h, "PRO", Color.rgb(245, 166, 35), Color.rgb(255, 214, 112), Color.BLACK)
            "badge_new_pill" -> drawSolidPillBadge(canvas, w, h, "NEW", Color.rgb(56, 189, 248), Color.rgb(99, 102, 241), Color.WHITE)
            "badge_rec_live" -> drawRecBadge(canvas, w, h)
            "badge_hdr_plus" -> drawEditorialStamp(canvas, w, h, "HDR+", Color.rgb(255, 220, 125), Color.argb(235, 14, 14, 16))

            // FRAMES
            "frame_polaroid" -> drawPolaroidFrame(canvas, w, h)
            "frame_viewfinder" -> drawViewfinderFrame(canvas, w, h)
            "frame_gold_hairline" -> drawGoldHairlineFrame(canvas, w, h)
            "frame_film_strip" -> drawFilmStripFrame(canvas, w, h)
            "frame_dashed_trim" -> drawDashedTrimFrame(canvas, w, h)
            "frame_gallery_matte" -> drawGalleryMatteFrame(canvas, w, h)

            // SYMBOLS
            "symbol_sparkles" -> drawSparklesSymbol(canvas, w, h)
            "symbol_heart" -> drawHeartSymbol(canvas, w, h)
            "symbol_lightning" -> drawLightningSymbol(canvas, w, h)
            "symbol_crown" -> drawCrownSymbol(canvas, w, h)
            "symbol_aperture" -> drawApertureSymbol(canvas, w, h)
            "symbol_north_star" -> drawNorthStarSymbol(canvas, w, h)
            else -> {
                if (!OfflineStickerPackRenderer.renderExtendedSticker(canvas, sticker.id, w, h)) {
                    drawGoldRing(canvas, w, h)
                }
            }
        }
        return bmp
    }

    // --- Shapes ---

    private fun drawGoldRing(canvas: Canvas, w: Float, h: Float) {
        val cx = w * 0.5f
        val cy = h * 0.5f
        val r = min(w, h) * 0.36f
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = w * 0.055f
            shader = LinearGradient(
                cx - r, cy - r, cx + r, cy + r,
                intArrayOf(Color.rgb(255, 232, 163), Color.rgb(245, 166, 35), Color.rgb(184, 110, 15)),
                floatArrayOf(0f, 0.52f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(cx, cy, r, stroke)
        val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = w * 0.014f
            color = Color.argb(180, 255, 240, 195)
        }
        canvas.drawCircle(cx, cy, r * 0.78f, inner)
    }

    private fun drawAmberBlob(canvas: Canvas, w: Float, h: Float) {
        val cx = w * 0.5f
        val cy = h * 0.5f
        val r = min(w, h) * 0.37f
        val path = Path()
        val pts = 72
        for (i in 0..pts) {
            val a = (i.toDouble() / pts) * 2.0 * PI
            val wave = 1.0 + 0.14 * sin(3.0 * a) + 0.08 * cos(5.0 * a + 0.6)
            val px = cx + (r * wave * cos(a)).toFloat()
            val py = cy + (r * wave * sin(a)).toFloat()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = LinearGradient(
                0f, 0f, w, h,
                intArrayOf(Color.rgb(255, 196, 77), Color.rgb(245, 130, 35), Color.rgb(225, 68, 92)),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawPath(path, fill)
    }

    private fun drawHexagonBadge(canvas: Canvas, w: Float, h: Float) {
        val path = regularPolygonPath(w * 0.5f, h * 0.5f, min(w, h) * 0.40f, 6, -PI / 2.0)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(200, 18, 18, 22)
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = w * 0.042f
            strokeJoin = Paint.Join.ROUND
            color = Color.rgb(245, 166, 35)
        }
        canvas.drawPath(path, fill)
        canvas.drawPath(path, stroke)
    }

    private fun drawDiamondPrism(canvas: Canvas, w: Float, h: Float) {
        val cx = w * 0.5f
        val cy = h * 0.5f
        val rx = w * 0.34f
        val ry = h * 0.42f
        val outer = Path().apply {
            moveTo(cx, cy - ry)
            lineTo(cx + rx, cy)
            lineTo(cx, cy + ry)
            lineTo(cx - rx, cy)
            close()
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = LinearGradient(
                cx - rx, cy - ry, cx + rx, cy + ry,
                intArrayOf(Color.argb(220, 96, 225, 255), Color.argb(210, 168, 85, 247), Color.argb(220, 245, 166, 35)),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawPath(outer, fill)
        val facet = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = w * 0.024f
            color = Color.argb(230, 255, 255, 255)
            strokeJoin = Paint.Join.ROUND
        }
        canvas.drawPath(outer, facet)
        canvas.drawLine(cx - rx, cy, cx + rx, cy, facet)
        canvas.drawLine(cx, cy - ry, cx, cy + ry, facet)
    }

    private fun drawNeonCapsule(canvas: Canvas, w: Float, h: Float) {
        val rect = RectF(w * 0.10f, h * 0.28f, w * 0.90f, h * 0.72f)
        val radius = h * 0.22f
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(185, 14, 14, 18)
        }
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = w * 0.036f
            shader = LinearGradient(
                rect.left, rect.top, rect.right, rect.bottom,
                Color.rgb(245, 166, 35),
                Color.rgb(56, 189, 248),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(rect, radius, radius, fill)
        canvas.drawRoundRect(rect, radius, radius, border)
    }

    private fun drawStarburstShape(canvas: Canvas, w: Float, h: Float) {
        val path = starPath(w * 0.5f, h * 0.5f, w * 0.43f, w * 0.28f, 12)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = RadialGradient(
                w * 0.5f, h * 0.5f, w * 0.43f,
                intArrayOf(Color.rgb(255, 238, 160), Color.rgb(245, 166, 35), Color.rgb(217, 119, 6)),
                floatArrayOf(0f, 0.65f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawPath(path, fill)
    }

    // --- Arrows ---

    private fun drawBoldArrow(canvas: Canvas, w: Float, h: Float) {
        val path = Path().apply {
            moveTo(w * 0.12f, h * 0.38f)
            lineTo(w * 0.54f, h * 0.38f)
            lineTo(w * 0.54f, h * 0.20f)
            lineTo(w * 0.89f, h * 0.50f)
            lineTo(w * 0.54f, h * 0.80f)
            lineTo(w * 0.54f, h * 0.62f)
            lineTo(w * 0.12f, h * 0.62f)
            close()
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = w * 0.025f
            strokeJoin = Paint.Join.ROUND
            color = Color.rgb(245, 166, 35)
        }
        canvas.drawPath(path, fill)
        canvas.drawPath(path, border)
    }

    private fun drawCurvedArrow(canvas: Canvas, w: Float, h: Float) {
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = w * 0.072f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = Color.rgb(245, 166, 35)
        }
        val shaft = Path().apply {
            moveTo(w * 0.15f, h * 0.78f)
            quadTo(w * 0.24f, h * 0.25f, w * 0.82f, h * 0.32f)
        }
        canvas.drawPath(shaft, stroke)
        val head = Path().apply {
            moveTo(w * 0.64f, h * 0.17f)
            lineTo(w * 0.85f, h * 0.32f)
            lineTo(w * 0.64f, h * 0.49f)
        }
        canvas.drawPath(head, stroke)
    }

    private fun drawDoubleChevron(canvas: Canvas, w: Float, h: Float) {
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = w * 0.075f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = Color.WHITE
        }
        val first = Path().apply {
            moveTo(w * 0.22f, h * 0.22f)
            lineTo(w * 0.50f, h * 0.50f)
            lineTo(w * 0.22f, h * 0.78f)
        }
        val second = Path().apply {
            moveTo(w * 0.52f, h * 0.22f)
            lineTo(w * 0.80f, h * 0.50f)
            lineTo(w * 0.52f, h * 0.78f)
        }
        canvas.drawPath(first, stroke)
        stroke.color = Color.rgb(245, 166, 35)
        canvas.drawPath(second, stroke)
    }

    private fun drawStudioPointer(canvas: Canvas, w: Float, h: Float) {
        val path = Path().apply {
            moveTo(w * 0.14f, h * 0.82f)
            lineTo(w * 0.86f, h * 0.18f)
            lineTo(w * 0.62f, h * 0.84f)
            lineTo(w * 0.49f, h * 0.56f)
            close()
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = LinearGradient(
                w * 0.15f, h * 0.82f, w * 0.86f, h * 0.18f,
                Color.rgb(245, 166, 35),
                Color.WHITE,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawPath(path, fill)
    }

    private fun drawLoopArrow(canvas: Canvas, w: Float, h: Float) {
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = w * 0.065f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = Color.rgb(240, 244, 248)
        }
        val path = Path().apply {
            moveTo(w * 0.16f, h * 0.74f)
            cubicTo(w * 0.55f, h * 0.78f, w * 0.68f, h * 0.30f, w * 0.44f, h * 0.34f)
            cubicTo(w * 0.24f, h * 0.38f, w * 0.44f, h * 0.64f, w * 0.80f, h * 0.28f)
        }
        canvas.drawPath(path, stroke)
        val head = Path().apply {
            moveTo(w * 0.63f, h * 0.25f)
            lineTo(w * 0.82f, h * 0.26f)
            lineTo(w * 0.79f, h * 0.45f)
        }
        stroke.color = Color.rgb(245, 166, 35)
        canvas.drawPath(head, stroke)
    }

    private fun drawDropPin(canvas: Canvas, w: Float, h: Float) {
        val cx = w * 0.5f
        val topY = h * 0.12f
        val r = w * 0.26f
        val circleCy = topY + r
        val pinPath = Path().apply {
            moveTo(cx, h * 0.88f)
            cubicTo(cx - r * 1.15f, circleCy + r * 0.9f, cx - r * 1.05f, topY, cx, topY)
            cubicTo(cx + r * 1.05f, topY, cx + r * 1.15f, circleCy + r * 0.9f, cx, h * 0.88f)
            close()
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = LinearGradient(
                cx, topY, cx, h * 0.88f,
                Color.rgb(245, 166, 35),
                Color.rgb(234, 88, 12),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawPath(pinPath, fill)
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.rgb(18, 18, 20)
        }
        canvas.drawCircle(cx, circleCy, r * 0.42f, dot)
    }

    // --- Light Flares ---

    private fun drawAnamorphicCyanFlare(canvas: Canvas, w: Float, h: Float) {
        val cx = w * 0.5f
        val cy = h * 0.5f
        // Horizontal wide cinema streak
        val streakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, cy, w, cy,
                intArrayOf(
                    Color.TRANSPARENT,
                    Color.argb(140, 14, 165, 233),
                    Color.argb(255, 224, 248, 255),
                    Color.argb(140, 14, 165, 233),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.26f, 0.5f, 0.74f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawOval(RectF(w * 0.02f, cy - h * 0.055f, w * 0.98f, cy + h * 0.055f), streakPaint)
        // Bright central optical bloom
        val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx, cy, w * 0.24f,
                intArrayOf(
                    Color.argb(255, 255, 255, 255),
                    Color.argb(190, 125, 211, 252),
                    Color.argb(60, 14, 165, 233),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.28f, 0.65f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(cx, cy, w * 0.24f, corePaint)
    }

    private fun drawGoldenSunburstFlare(canvas: Canvas, w: Float, h: Float) {
        val cx = w * 0.5f
        val cy = h * 0.5f
        // Soft outer radial glow
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx, cy, w * 0.48f,
                intArrayOf(
                    Color.argb(255, 255, 250, 220),
                    Color.argb(210, 255, 198, 82),
                    Color.argb(90, 245, 130, 32),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.25f, 0.62f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(cx, cy, w * 0.48f, glow)
        // 8 optical diffraction rays
        val rayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = w * 0.014f
            strokeCap = Paint.Cap.ROUND
            color = Color.argb(175, 255, 244, 196)
        }
        for (i in 0 until 8) {
            val angle = i * (PI / 4.0)
            val len = if (i % 2 == 0) w * 0.44f else w * 0.30f
            canvas.drawLine(
                cx - (len * cos(angle)).toFloat(),
                cy - (len * sin(angle)).toFloat(),
                cx + (len * cos(angle)).toFloat(),
                cy + (len * sin(angle)).toFloat(),
                rayPaint
            )
        }
    }

    private fun drawPrismLeakFlare(canvas: Canvas, w: Float, h: Float) {
        val bands = listOf(
            Triple(0.28f, 0.34f, Color.argb(200, 255, 92, 92)),
            Triple(0.46f, 0.48f, Color.argb(210, 245, 166, 35)),
            Triple(0.62f, 0.60f, Color.argb(190, 56, 189, 248)),
            Triple(0.74f, 0.70f, Color.argb(170, 192, 132, 252))
        )
        bands.forEach { (fx, fy, color) ->
            val r = w * 0.34f
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    w * fx, h * fy, r,
                    intArrayOf(color, Color.TRANSPARENT),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawCircle(w * fx, h * fy, r, p)
        }
    }

    private fun drawBokehOrbsFlare(canvas: Canvas, w: Float, h: Float) {
        val orbs = listOf(
            floatArrayOf(0.28f, 0.32f, 0.17f, 255f, 196f, 92f),
            floatArrayOf(0.68f, 0.28f, 0.13f, 255f, 145f, 105f),
            floatArrayOf(0.52f, 0.56f, 0.22f, 255f, 224f, 150f),
            floatArrayOf(0.24f, 0.70f, 0.12f, 125f, 211f, 252f),
            floatArrayOf(0.76f, 0.68f, 0.16f, 245f, 166f, 35f)
        )
        orbs.forEach { o ->
            val cx = w * o[0]
            val cy = h * o[1]
            val r = w * o[2]
            val cCenter = Color.argb(120, o[3].toInt(), o[4].toInt(), o[5].toInt())
            val cEdge = Color.argb(210, o[3].toInt(), o[4].toInt(), o[5].toInt())
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    cx, cy, r,
                    intArrayOf(cCenter, cEdge, Color.TRANSPARENT),
                    floatArrayOf(0f, 0.82f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawCircle(cx, cy, r, p)
        }
    }

    private fun drawStarGlintFlare(canvas: Canvas, w: Float, h: Float) {
        val cx = w * 0.5f
        val cy = h * 0.5f
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx, cy, w * 0.34f,
                intArrayOf(Color.argb(245, 255, 255, 255), Color.argb(110, 245, 196, 95), Color.TRANSPARENT),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(cx, cy, w * 0.34f, glow)
        val star = starPath(cx, cy, w * 0.46f, w * 0.065f, 4)
        val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }
        canvas.drawPath(star, starPaint)
    }

    private fun drawAmberHaloFlare(canvas: Canvas, w: Float, h: Float) {
        val cx = w * 0.5f
        val cy = h * 0.5f
        val r = w * 0.42f
        val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx, cy, r,
                intArrayOf(
                    Color.TRANSPARENT,
                    Color.argb(40, 245, 166, 35),
                    Color.argb(225, 255, 196, 82),
                    Color.argb(120, 234, 88, 12),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.46f, 0.72f, 0.88f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(cx, cy, r, halo)
    }

    // --- Badges ---

    private fun drawEditorialStamp(
        canvas: Canvas,
        w: Float,
        h: Float,
        text: String,
        accentColor: Int,
        bgColor: Int
    ) {
        val rect = RectF(w * 0.08f, h * 0.24f, w * 0.92f, h * 0.76f)
        val corner = h * 0.12f
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = bgColor
        }
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = w * 0.036f
            color = accentColor
        }
        canvas.drawRoundRect(rect, corner, corner, fill)
        canvas.drawRoundRect(rect, corner, corner, border)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentColor
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = h * 0.28f
        }
        val baseline = rect.centerY() - (textPaint.descent() + textPaint.ascent()) * 0.5f
        canvas.drawText(text, rect.centerX(), baseline, textPaint)
    }

    private fun drawSolidPillBadge(
        canvas: Canvas,
        w: Float,
        h: Float,
        text: String,
        startColor: Int,
        endColor: Int,
        textColor: Int
    ) {
        val rect = RectF(w * 0.10f, h * 0.25f, w * 0.90f, h * 0.75f)
        val radius = h * 0.25f
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom, startColor, endColor, Shader.TileMode.CLAMP)
        }
        canvas.drawRoundRect(rect, radius, radius, fill)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = h * 0.27f
        }
        val baseline = rect.centerY() - (textPaint.descent() + textPaint.ascent()) * 0.5f
        canvas.drawText(text, rect.centerX(), baseline, textPaint)
    }

    private fun drawRecBadge(canvas: Canvas, w: Float, h: Float) {
        val rect = RectF(w * 0.08f, h * 0.25f, w * 0.92f, h * 0.75f)
        val radius = h * 0.25f
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(230, 18, 18, 22)
        }
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = w * 0.028f
            color = Color.argb(220, 239, 68, 68)
        }
        canvas.drawRoundRect(rect, radius, radius, bg)
        canvas.drawRoundRect(rect, radius, radius, border)

        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.rgb(239, 68, 68)
        }
        canvas.drawCircle(w * 0.26f, rect.centerY(), h * 0.095f, dot)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.LEFT
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = h * 0.25f
        }
        val baseline = rect.centerY() - (textPaint.descent() + textPaint.ascent()) * 0.5f
        canvas.drawText("REC", w * 0.40f, baseline, textPaint)
    }

    // --- Frames ---

    private fun drawPolaroidFrame(canvas: Canvas, w: Float, h: Float) {
        val side = w * 0.065f
        val top = h * 0.065f
        val bottom = h * 0.20f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.rgb(246, 245, 240)
        }
        // Top, bottom, left, right border bars leaving center crystal transparent
        canvas.drawRect(0f, 0f, w, top, paint)
        canvas.drawRect(0f, h - bottom, w, h, paint)
        canvas.drawRect(0f, top, side, h - bottom, paint)
        canvas.drawRect(w - side, top, w, h - bottom, paint)

        val innerStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(2f, w * 0.005f)
            color = Color.argb(55, 0, 0, 0)
        }
        canvas.drawRect(side, top, w - side, h - bottom, innerStroke)
    }

    private fun drawViewfinderFrame(canvas: Canvas, w: Float, h: Float) {
        val m = w * 0.06f
        val arm = w * 0.14f
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(3f, w * 0.016f)
            strokeCap = Paint.Cap.SQUARE
            color = Color.WHITE
        }
        // Four corner brackets
        canvas.drawLine(m, m, m + arm, m, stroke)
        canvas.drawLine(m, m, m, m + arm, stroke)

        canvas.drawLine(w - m, m, w - m - arm, m, stroke)
        canvas.drawLine(w - m, m, w - m, m + arm, stroke)

        canvas.drawLine(m, h - m, m + arm, h - m, stroke)
        canvas.drawLine(m, h - m, m, h - m - arm, stroke)

        canvas.drawLine(w - m, h - m, w - m - arm, h - m, stroke)
        canvas.drawLine(w - m, h - m, w - m, h - m - arm, stroke)

        // Subtle center amber reticle crosshair
        val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(2f, w * 0.009f)
            color = Color.argb(210, 245, 166, 35)
        }
        val cx = w * 0.5f
        val cy = h * 0.5f
        val cArm = w * 0.035f
        canvas.drawLine(cx - cArm, cy, cx + cArm, cy, cross)
        canvas.drawLine(cx, cy - cArm, cx, cy + cArm, cross)
    }

    private fun drawGoldHairlineFrame(canvas: Canvas, w: Float, h: Float) {
        val m = w * 0.045f
        val outer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(3f, w * 0.014f)
            color = Color.rgb(245, 166, 35)
        }
        val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(1.5f, w * 0.006f)
            color = Color.argb(190, 255, 232, 163)
        }
        canvas.drawRect(m, m, w - m, h - m, outer)
        canvas.drawRect(m * 1.7f, m * 1.7f, w - m * 1.7f, h - m * 1.7f, inner)
    }

    private fun drawFilmStripFrame(canvas: Canvas, w: Float, h: Float) {
        val railH = h * 0.11f
        val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.rgb(16, 16, 18)
        }
        canvas.drawRect(0f, 0f, w, railH, bar)
        canvas.drawRect(0f, h - railH, w, h, bar)

        val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.rgb(245, 166, 35)
        }
        val count = 8
        val step = w / count
        val holeW = step * 0.44f
        val holeH = railH * 0.48f
        val corner = holeH * 0.24f
        for (i in 0 until count) {
            val left = i * step + (step - holeW) * 0.5f
            canvas.drawRoundRect(
                RectF(left, (railH - holeH) * 0.5f, left + holeW, (railH + holeH) * 0.5f),
                corner,
                corner,
                hole
            )
            canvas.drawRoundRect(
                RectF(left, h - railH + (railH - holeH) * 0.5f, left + holeW, h - railH + (railH + holeH) * 0.5f),
                corner,
                corner,
                hole
            )
        }
    }

    private fun drawDashedTrimFrame(canvas: Canvas, w: Float, h: Float) {
        val m = w * 0.055f
        val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(2.5f, w * 0.012f)
            color = Color.WHITE
            pathEffect = DashPathEffect(floatArrayOf(w * 0.045f, w * 0.03f), 0f)
        }
        canvas.drawRoundRect(RectF(m, m, w - m, h - m), w * 0.04f, w * 0.04f, dash)
    }

    private fun drawGalleryMatteFrame(canvas: Canvas, w: Float, h: Float) {
        val border = w * 0.075f
        val matte = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.rgb(248, 248, 246)
        }
        canvas.drawRect(0f, 0f, w, border, matte)
        canvas.drawRect(0f, h - border, w, h, matte)
        canvas.drawRect(0f, border, border, h - border, matte)
        canvas.drawRect(w - border, border, w, h - border, matte)

        val bevel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(2f, w * 0.008f)
            color = Color.rgb(212, 175, 55)
        }
        canvas.drawRect(border, border, w - border, h - border, bevel)
    }

    // --- Symbols ---

    private fun drawSparklesSymbol(canvas: Canvas, w: Float, h: Float) {
        val goldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = LinearGradient(
                0f, 0f, w, h,
                Color.rgb(255, 240, 178),
                Color.rgb(245, 166, 35),
                Shader.TileMode.CLAMP
            )
        }
        val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }
        canvas.drawPath(fourPointSparklePath(w * 0.44f, h * 0.48f, w * 0.34f), goldPaint)
        canvas.drawPath(fourPointSparklePath(w * 0.78f, h * 0.25f, w * 0.14f), whitePaint)
        canvas.drawPath(fourPointSparklePath(w * 0.74f, h * 0.76f, w * 0.11f), goldPaint)
    }

    private fun drawHeartSymbol(canvas: Canvas, w: Float, h: Float) {
        val cx = w * 0.5f
        val path = Path().apply {
            moveTo(cx, h * 0.84f)
            cubicTo(w * 0.10f, h * 0.56f, w * 0.08f, h * 0.22f, w * 0.31f, h * 0.18f)
            cubicTo(w * 0.42f, h * 0.16f, cx, h * 0.28f, cx, h * 0.33f)
            cubicTo(cx, h * 0.28f, w * 0.58f, h * 0.16f, w * 0.69f, h * 0.18f)
            cubicTo(w * 0.92f, h * 0.22f, w * 0.90f, h * 0.56f, cx, h * 0.84f)
            close()
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = LinearGradient(
                cx, h * 0.15f, cx, h * 0.85f,
                Color.rgb(251, 113, 133),
                Color.rgb(225, 29, 72),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawPath(path, fill)
    }

    private fun drawLightningSymbol(canvas: Canvas, w: Float, h: Float) {
        val path = Path().apply {
            moveTo(w * 0.58f, h * 0.08f)
            lineTo(w * 0.22f, h * 0.54f)
            lineTo(w * 0.48f, h * 0.54f)
            lineTo(w * 0.40f, h * 0.92f)
            lineTo(w * 0.78f, h * 0.44f)
            lineTo(w * 0.52f, h * 0.44f)
            close()
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = LinearGradient(
                w * 0.3f, h * 0.1f, w * 0.7f, h * 0.9f,
                Color.rgb(254, 240, 138),
                Color.rgb(245, 158, 11),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawPath(path, fill)
    }

    private fun drawCrownSymbol(canvas: Canvas, w: Float, h: Float) {
        val path = Path().apply {
            moveTo(w * 0.14f, h * 0.74f)
            lineTo(w * 0.18f, h * 0.30f)
            lineTo(w * 0.37f, h * 0.52f)
            lineTo(w * 0.50f, h * 0.22f)
            lineTo(w * 0.63f, h * 0.52f)
            lineTo(w * 0.82f, h * 0.30f)
            lineTo(w * 0.86f, h * 0.74f)
            close()
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = LinearGradient(
                w * 0.5f, h * 0.20f, w * 0.5f, h * 0.78f,
                Color.rgb(255, 232, 155),
                Color.rgb(217, 119, 6),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawPath(path, fill)
        // Crown base band
        val band = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.rgb(245, 166, 35)
        }
        canvas.drawRoundRect(
            RectF(w * 0.14f, h * 0.77f, w * 0.86f, h * 0.85f),
            h * 0.04f,
            h * 0.04f,
            band
        )
    }

    private fun drawApertureSymbol(canvas: Canvas, w: Float, h: Float) {
        val cx = w * 0.5f
        val cy = h * 0.5f
        val outerR = min(w, h) * 0.40f
        val innerR = outerR * 0.42f
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = w * 0.045f
            shader = SweepGradient(
                cx, cy,
                intArrayOf(
                    Color.rgb(245, 166, 35),
                    Color.WHITE,
                    Color.rgb(245, 166, 35)
                ),
                null
            )
        }
        canvas.drawCircle(cx, cy, outerR, ring)

        val blade = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = w * 0.032f
            strokeCap = Paint.Cap.ROUND
            color = Color.WHITE
        }
        for (i in 0 until 6) {
            val a1 = i * (PI / 3.0)
            val a2 = a1 + PI / 2.2
            val sx = cx + (innerR * cos(a1)).toFloat()
            val sy = cy + (innerR * sin(a1)).toFloat()
            val ex = cx + (outerR * 0.94f * cos(a2)).toFloat()
            val ey = cy + (outerR * 0.94f * sin(a2)).toFloat()
            canvas.drawLine(sx, sy, ex, ey, blade)
        }
    }

    private fun drawNorthStarSymbol(canvas: Canvas, w: Float, h: Float) {
        val cx = w * 0.5f
        val cy = h * 0.5f
        val secondary = starPath(cx, cy, w * 0.28f, w * 0.08f, 4, rotationRad = PI / 4.0)
        val primary = starPath(cx, cy, w * 0.44f, w * 0.11f, 4, rotationRad = 0.0)
        val secPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.rgb(245, 166, 35)
        }
        val priPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }
        canvas.drawPath(secondary, secPaint)
        canvas.drawPath(primary, priPaint)
    }

    // --- Geometry Helpers ---

    private fun regularPolygonPath(
        cx: Float,
        cy: Float,
        radius: Float,
        sides: Int,
        startAngleRad: Double
    ): Path = Path().apply {
        for (i in 0 until sides) {
            val angle = startAngleRad + i * (2.0 * PI / sides)
            val px = cx + (radius * cos(angle)).toFloat()
            val py = cy + (radius * sin(angle)).toFloat()
            if (i == 0) moveTo(px, py) else lineTo(px, py)
        }
        close()
    }

    private fun starPath(
        cx: Float,
        cy: Float,
        outerR: Float,
        innerR: Float,
        points: Int,
        rotationRad: Double = -PI / 2.0
    ): Path = Path().apply {
        val total = points * 2
        for (i in 0 until total) {
            val r = if (i % 2 == 0) outerR else innerR
            val angle = rotationRad + i * (PI / points)
            val px = cx + (r * cos(angle)).toFloat()
            val py = cy + (r * sin(angle)).toFloat()
            if (i == 0) moveTo(px, py) else lineTo(px, py)
        }
        close()
    }

    private fun fourPointSparklePath(cx: Float, cy: Float, radius: Float): Path = Path().apply {
        val ctrl = radius * 0.18f
        moveTo(cx, cy - radius)
        quadTo(cx + ctrl, cy - ctrl, cx + radius, cy)
        quadTo(cx + ctrl, cy + ctrl, cx, cy + radius)
        quadTo(cx - ctrl, cy + ctrl, cx - radius, cy)
        quadTo(cx - ctrl, cy - ctrl, cx, cy - radius)
        close()
    }
}
