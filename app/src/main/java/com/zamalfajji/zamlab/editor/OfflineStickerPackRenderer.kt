package com.zamalfajji.zamlab.editor

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Modular vector/canvas renderer for Z-Effect's expanded 150+ offline sticker catalog across
 * all 10 offline categories (Shapes, Arrows, Light Flares, Badges, Frames, Symbols,
 * Neon Glow, Cyber HUD, Social & Creator, and Vintage Stamps).
 *
 * Every sticker renders onto a transparent ARGB_8888 [Canvas] with crisp anti-aliased paths,
 * optical gradients, and zero external asset dependencies.
 */
internal object OfflineStickerPackRenderer {

    fun renderExtendedSticker(canvas: Canvas, id: String, w: Float, h: Float): Boolean {
        when (id) {
            // --- Expanded Shapes (9 new -> 15 total) ---
            "shape_crescent_gold" -> drawCrescentShape(canvas, w, h, Color.rgb(255, 224, 130), Color.rgb(245, 158, 11))
            "shape_hollow_triangle" -> drawHollowPolygon(canvas, w, h, 3, Color.rgb(56, 189, 248), Color.rgb(168, 85, 247))
            "shape_octagon_frame" -> drawHollowPolygon(canvas, w, h, 8, Color.rgb(245, 166, 35), Color.rgb(239, 68, 68))
            "shape_concentric_rings" -> drawConcentricRings(canvas, w, h, Color.rgb(245, 166, 35), Color.rgb(255, 255, 255))
            "shape_split_circle" -> drawSplitCircle(canvas, w, h)
            "shape_wave_ribbon" -> drawWaveRibbon(canvas, w, h, Color.rgb(56, 189, 248), Color.rgb(245, 166, 35))
            "shape_checker_pill" -> drawCheckerPill(canvas, w, h)
            "shape_emerald_orb" -> drawGradientOrb(canvas, w, h, Color.rgb(110, 231, 183), Color.rgb(16, 185, 129), Color.rgb(6, 95, 70))
            "shape_ruby_shield" -> drawShieldShape(canvas, w, h, Color.rgb(251, 113, 133), Color.rgb(190, 18, 60))

            // --- Expanded Arrows (9 new -> 15 total) ---
            "arrow_up_boost" -> drawRotatedBoldArrow(canvas, w, h, -90f, Color.rgb(34, 197, 94), Color.rgb(16, 185, 129))
            "arrow_down_drop" -> drawRotatedBoldArrow(canvas, w, h, 90f, Color.rgb(245, 166, 35), Color.rgb(234, 88, 12))
            "arrow_diag_ne" -> drawRotatedBoldArrow(canvas, w, h, -45f, Color.rgb(56, 189, 248), Color.rgb(99, 102, 241))
            "arrow_triple_dash" -> drawTripleChevron(canvas, w, h, Color.rgb(56, 189, 248), Color.WHITE)
            "arrow_circle_return" -> drawCircularArrow(canvas, w, h, Color.rgb(245, 166, 35))
            "arrow_dashed_curve" -> drawDashedCurvedArrow(canvas, w, h, Color.WHITE)
            "arrow_split_fork" -> drawSplitForkArrow(canvas, w, h, Color.rgb(245, 166, 35))
            "arrow_zigzag_bolt" -> drawZigZagArrow(canvas, w, h, Color.rgb(250, 204, 21))
            "arrow_callout_tag" -> drawCalloutTagArrow(canvas, w, h)

            // --- Expanded Light Flares (9 new -> 15 total, all SCREEN blend) ---
            "flare_magenta_streak" -> drawHorizontalStreakFlare(canvas, w, h, Color.rgb(236, 72, 153), Color.rgb(255, 228, 242))
            "flare_emerald_beam" -> drawHorizontalStreakFlare(canvas, w, h, Color.rgb(16, 185, 129), Color.rgb(209, 250, 229))
            "flare_violet_nova" -> drawRadialNovaFlare(canvas, w, h, Color.rgb(168, 85, 247), Color.rgb(233, 213, 255))
            "flare_solar_ring" -> drawOpticalRingFlare(canvas, w, h, Color.rgb(251, 146, 60), Color.rgb(254, 240, 138))
            "flare_arctic_glint" -> drawCrossGlintFlare(canvas, w, h, Color.rgb(56, 189, 248), Color.WHITE)
            "flare_sunset_wash" -> drawDiagonalWashFlare(canvas, w, h, Color.rgb(249, 115, 22), Color.rgb(236, 72, 153))
            "flare_crimson_leak" -> drawDiagonalWashFlare(canvas, w, h, Color.rgb(239, 68, 68), Color.rgb(245, 158, 11))
            "flare_double_bokeh" -> drawClusterBokehFlare(canvas, w, h, Color.rgb(56, 189, 248), Color.rgb(192, 132, 252))
            "flare_diamond_spark" -> drawCrossGlintFlare(canvas, w, h, Color.rgb(250, 204, 21), Color.rgb(255, 251, 235))

            // --- Expanded Badges (9 new -> 15 total) ---
            "badge_4k_uhd" -> drawRectStampBadge(canvas, w, h, "4K UHD", Color.rgb(56, 189, 248), Color.argb(235, 12, 16, 24))
            "badge_cine_log" -> drawRectStampBadge(canvas, w, h, "C-LOG", Color.rgb(245, 166, 35), Color.argb(235, 18, 16, 14))
            "badge_iso_800" -> drawRectStampBadge(canvas, w, h, "ISO 800", Color.WHITE, Color.argb(225, 22, 22, 26))
            "badge_f14_prime" -> drawGradientPill(canvas, w, h, "f/1.4", Color.rgb(245, 166, 35), Color.rgb(217, 119, 6), Color.BLACK)
            "badge_studio_vip" -> drawGradientPill(canvas, w, h, "STUDIO", Color.rgb(168, 85, 247), Color.rgb(236, 72, 153), Color.WHITE)
            "badge_edited_ze" -> drawRectStampBadge(canvas, w, h, "Z-FX", Color.rgb(52, 211, 153), Color.argb(235, 10, 22, 18))
            "badge_limited" -> drawGradientPill(canvas, w, h, "LIMITED", Color.rgb(239, 68, 68), Color.rgb(249, 115, 22), Color.WHITE)
            "badge_ai_hd" -> drawGradientPill(canvas, w, h, "AI HD", Color.rgb(14, 165, 233), Color.rgb(34, 211, 238), Color.BLACK)
            "badge_mono_bw" -> drawRectStampBadge(canvas, w, h, "B&W", Color.rgb(230, 230, 235), Color.argb(235, 16, 16, 18))

            // --- Expanded Frames (9 new -> 15 total) ---
            "frame_cinema_bars" -> drawCinemaLetterboxFrame(canvas, w, h)
            "frame_neon_cyan" -> drawNeonRectFrame(canvas, w, h, Color.rgb(34, 211, 238), Color.rgb(56, 189, 248))
            "frame_neon_magenta" -> drawNeonRectFrame(canvas, w, h, Color.rgb(236, 72, 153), Color.rgb(168, 85, 247))
            "frame_double_white" -> drawDoubleLineFrame(canvas, w, h, Color.WHITE)
            "frame_rounded_gold" -> drawRoundedBorderFrame(canvas, w, h, Color.rgb(245, 166, 35))
            "frame_editorial_dark" -> drawEditorialMatteFrame(canvas, w, h)
            "frame_camcorder_rec" -> drawCamcorderHudFrame(canvas, w, h)
            "frame_corner_ticks" -> drawMinimalCornerFrame(canvas, w, h, Color.rgb(245, 166, 35))
            "frame_split_arch" -> drawArchPortalFrame(canvas, w, h, Color.rgb(248, 245, 238))

            // --- Expanded Symbols (9 new -> 15 total) ---
            "symbol_moon_crescent" -> drawCrescentShape(canvas, w, h, Color.rgb(224, 242, 254), Color.rgb(56, 189, 248))
            "symbol_sun_rays" -> drawSunRaysSymbol(canvas, w, h)
            "symbol_diamond_gem" -> drawCutGemSymbol(canvas, w, h, Color.rgb(56, 189, 248), Color.rgb(14, 165, 233))
            "symbol_music_note" -> drawMusicNoteSymbol(canvas, w, h, Color.rgb(236, 72, 153), Color.rgb(168, 85, 247))
            "symbol_flame_icon" -> drawFlameSymbol(canvas, w, h)
            "symbol_wings_pair" -> drawAngelWingsSymbol(canvas, w, h, Color.WHITE, Color.rgb(245, 166, 35))
            "symbol_infinity" -> drawInfinitySymbol(canvas, w, h, Color.rgb(56, 189, 248), Color.rgb(245, 166, 35))
            "symbol_verified_check" -> drawVerifiedSealSymbol(canvas, w, h, Color.rgb(56, 189, 248))
            "symbol_compass_rose" -> drawCompassRoseSymbol(canvas, w, h)

            // --- Category 7: NEON GLOW (16 stickers) ---
            "neon_ring_cyan" -> drawNeonTubeCircle(canvas, w, h, Color.rgb(34, 211, 238))
            "neon_ring_magenta" -> drawNeonTubeCircle(canvas, w, h, Color.rgb(236, 72, 153))
            "neon_heart_pink" -> drawNeonHeart(canvas, w, h, Color.rgb(244, 63, 94))
            "neon_bolt_yellow" -> drawNeonBolt(canvas, w, h, Color.rgb(250, 204, 21))
            "neon_star_purple" -> drawNeonStar(canvas, w, h, Color.rgb(192, 132, 252))
            "neon_crown_gold" -> drawNeonCrown(canvas, w, h, Color.rgb(251, 191, 36))
            "neon_triangle_cyan" -> drawNeonPolygon(canvas, w, h, 3, Color.rgb(34, 211, 238))
            "neon_hex_lime" -> drawNeonPolygon(canvas, w, h, 6, Color.rgb(74, 222, 128))
            "neon_diamond_blue" -> drawNeonPolygon(canvas, w, h, 4, Color.rgb(96, 165, 250))
            "neon_wings_cyan" -> drawNeonWings(canvas, w, h, Color.rgb(34, 211, 238))
            "neon_halo_amber" -> drawNeonHaloOval(canvas, w, h, Color.rgb(251, 191, 36))
            "neon_infinity_pink" -> drawNeonInfinity(canvas, w, h, Color.rgb(236, 72, 153))
            "neon_wave_teal" -> drawNeonWave(canvas, w, h, Color.rgb(45, 212, 191))
            "neon_arrow_lime" -> drawNeonArrow(canvas, w, h, Color.rgb(163, 230, 53))
            "neon_moon_violet" -> drawNeonCrescent(canvas, w, h, Color.rgb(167, 139, 250))
            "neon_sign_vibes" -> drawNeonWordSign(canvas, w, h, "VIBES", Color.rgb(236, 72, 153))

            // --- Category 8: CYBER HUD (16 stickers) ---
            "hud_target_lock" -> drawHudReticle(canvas, w, h, Color.rgb(34, 211, 238), segmented = true)
            "hud_radar_ring" -> drawHudRadarRing(canvas, w, h, Color.rgb(52, 211, 153))
            "hud_bracket_amber" -> drawHudBrackets(canvas, w, h, Color.rgb(245, 166, 35), "LOCK")
            "hud_scan_lines" -> drawHudScanGrid(canvas, w, h, Color.rgb(34, 211, 238))
            "hud_audio_wave" -> drawHudEqualizer(canvas, w, h, Color.rgb(56, 189, 248), Color.rgb(168, 85, 247))
            "hud_hex_matrix" -> drawHudHexCluster(canvas, w, h, Color.rgb(34, 211, 238))
            "hud_telemetry_bar" -> drawHudTelemetryPanel(canvas, w, h, "SYS 98%", Color.rgb(52, 211, 153))
            "hud_crosshair_red" -> drawHudReticle(canvas, w, h, Color.rgb(248, 113, 113), segmented = false)
            "hud_compass_strip" -> drawHudCompassBar(canvas, w, h, Color.rgb(245, 166, 35))
            "hud_battery_full" -> drawHudBatteryModule(canvas, w, h, Color.rgb(74, 222, 128))
            "hud_signal_bars" -> drawHudSignalBars(canvas, w, h, Color.rgb(34, 211, 238))
            "hud_data_chip" -> drawHudTelemetryPanel(canvas, w, h, "AI 4.0", Color.rgb(56, 189, 248))
            "hud_orbital_arc" -> drawHudOrbitalArcs(canvas, w, h, Color.rgb(245, 166, 35))
            "hud_warning_tri" -> drawHudWarningTriangle(canvas, w, h, Color.rgb(250, 204, 21))
            "hud_timecode_box" -> drawHudTelemetryPanel(canvas, w, h, "00:24:08", Color.rgb(248, 113, 113))
            "hud_corner_brackets" -> drawHudBrackets(canvas, w, h, Color.rgb(34, 211, 238), "FOCUS")

            // --- Category 9: SOCIAL & CREATOR (16 stickers) ---
            "social_live_pill" -> drawSocialBadge(canvas, w, h, "● LIVE", Color.rgb(239, 68, 68), Color.rgb(220, 38, 38), Color.WHITE)
            "social_new_drop" -> drawSocialBadge(canvas, w, h, "NEW DROP", Color.rgb(245, 166, 35), Color.rgb(249, 115, 22), Color.BLACK)
            "social_subscribe" -> drawSocialBadge(canvas, w, h, "FOLLOW +", Color.rgb(236, 72, 153), Color.rgb(168, 85, 247), Color.WHITE)
            "social_swipe_up" -> drawSocialSwipeUp(canvas, w, h)
            "social_pov_tag" -> drawSocialGlassTag(canvas, w, h, "POV:", Color.rgb(250, 204, 21))
            "social_bts_tag" -> drawSocialGlassTag(canvas, w, h, "BTS", Color.rgb(56, 189, 248))
            "social_vlog_cam" -> drawSocialBadge(canvas, w, h, "VLOG", Color.rgb(14, 165, 233), Color.rgb(99, 102, 241), Color.WHITE)
            "social_shot_on" -> drawSocialGlassTag(canvas, w, h, "SHOT ON", Color.WHITE)
            "social_link_bio" -> drawSocialBadge(canvas, w, h, "LINK ↗", Color.rgb(16, 185, 129), Color.rgb(5, 150, 105), Color.WHITE)
            "social_like_burst" -> drawSocialReactionPill(canvas, w, h, "♥ 10K", Color.rgb(244, 63, 94))
            "social_fire_hot" -> drawSocialBadge(canvas, w, h, "TRENDING", Color.rgb(249, 115, 22), Color.rgb(239, 68, 68), Color.WHITE)
            "social_4k_60fps" -> drawSocialGlassTag(canvas, w, h, "4K 60FPS", Color.rgb(245, 166, 35))
            "social_no_filter" -> drawSocialGlassTag(canvas, w, h, "RAW EDIT", Color.rgb(52, 211, 153))
            "social_sound_on" -> drawSocialBadge(canvas, w, h, "♫ AUDIO", Color.rgb(139, 92, 246), Color.rgb(59, 130, 246), Color.WHITE)
            "social_daily_fit" -> drawSocialGlassTag(canvas, w, h, "OOTD", Color.rgb(244, 114, 182))
            "social_creator_check" -> drawSocialBadge(canvas, w, h, "✓ CREATOR", Color.rgb(56, 189, 248), Color.rgb(37, 99, 235), Color.WHITE)

            // --- Category 10: VINTAGE STAMPS (16 stickers) ---
            "stamp_airmail" -> drawVintagePostmark(canvas, w, h, "AIR MAIL", "PAR AVION", Color.rgb(220, 38, 38))
            "stamp_approved" -> drawVintageBoxStamp(canvas, w, h, "APPROVED", Color.rgb(34, 197, 94), -10f)
            "stamp_kodak_date" -> drawVintageLedDateStamp(canvas, w, h, "'98 07 24")
            "stamp_film_lab" -> drawVintageCircleSeal(canvas, w, h, "FILM LAB", "35MM", Color.rgb(245, 166, 35))
            "stamp_original" -> drawVintageCircleSeal(canvas, w, h, "ORIGINAL", "PRINT", Color.rgb(239, 68, 68))
            "stamp_tokyo_pass" -> drawVintagePassportStamp(canvas, w, h, "TOKYO", "JP-01", Color.rgb(59, 130, 246))
            "stamp_paris_pass" -> drawVintagePassportStamp(canvas, w, h, "PARIS", "FR-75", Color.rgb(225, 29, 72))
            "stamp_nyc_pass" -> drawVintagePassportStamp(canvas, w, h, "NYC", "US-NY", Color.rgb(245, 158, 11))
            "stamp_vintage_1999" -> drawVintageBoxStamp(canvas, w, h, "EST. 1999", Color.rgb(245, 166, 35), -6f)
            "stamp_analog_club" -> drawVintageCircleSeal(canvas, w, h, "ANALOG", "CLUB", Color.rgb(248, 250, 252))
            "stamp_handcrafted" -> drawVintageCircleSeal(canvas, w, h, "CRAFTED", "STUDIO", Color.rgb(251, 191, 36))
            "stamp_wax_seal" -> drawVintageWaxSeal(canvas, w, h, "Z")
            "stamp_top_secret" -> drawVintageBoxStamp(canvas, w, h, "ARCHIVE", Color.rgb(239, 68, 68), 8f)
            "stamp_post_express" -> drawVintagePostmark(canvas, w, h, "EXPRESS", "PRIORITY", Color.rgb(56, 189, 248))
            "stamp_gold_ribbon" -> drawVintageAwardSeal(canvas, w, h, "1ST")
            "stamp_retro_rec" -> drawVintageLedDateStamp(canvas, w, h, "REC 1995")

            else -> return false
        }
        return true
    }

    // --- Shared Geometry Helpers ---

    private fun polyPath(cx: Float, cy: Float, r: Float, sides: Int, startRad: Double = -PI / 2.0): Path =
        Path().apply {
            for (i in 0 until sides) {
                val a = startRad + i * (2.0 * PI / sides)
                val x = cx + (r * cos(a)).toFloat()
                val y = cy + (r * sin(a)).toFloat()
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }

    private fun starPath(cx: Float, cy: Float, outerR: Float, innerR: Float, pts: Int): Path =
        Path().apply {
            val total = pts * 2
            for (i in 0 until total) {
                val r = if (i % 2 == 0) outerR else innerR
                val a = -PI / 2.0 + i * (PI / pts)
                val x = cx + (r * cos(a)).toFloat()
                val y = cy + (r * sin(a)).toFloat()
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }

    private fun drawNeonStroke(canvas: Canvas, path: Path, baseColor: Int, widthPx: Float) {
        val outerGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = widthPx * 3.1f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = Color.argb(65, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))
        }
        val midGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = widthPx * 1.85f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = Color.argb(145, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))
        }
        val tube = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = widthPx
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = baseColor
        }
        val core = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = widthPx * 0.36f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = Color.argb(235, 255, 255, 255)
        }
        canvas.drawPath(path, outerGlow)
        canvas.drawPath(path, midGlow)
        canvas.drawPath(path, tube)
        canvas.drawPath(path, core)
    }

    // --- Shapes Renderers ---

    private fun drawCrescentShape(canvas: Canvas, w: Float, h: Float, c1: Int, c2: Int) {
        val cx = w * 0.50f
        val cy = h * 0.50f
        val r = min(w, h) * 0.36f
        val outer = Path().apply { addCircle(cx, cy, r, Path.Direction.CW) }
        val cutout = Path().apply { addCircle(cx + r * 0.42f, cy - r * 0.14f, r * 0.82f, Path.Direction.CW) }
        outer.op(cutout, Path.Op.DIFFERENCE)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = LinearGradient(cx - r, cy - r, cx + r, cy + r, c1, c2, Shader.TileMode.CLAMP)
        }
        canvas.drawPath(outer, p)
    }

    private fun drawHollowPolygon(canvas: Canvas, w: Float, h: Float, sides: Int, c1: Int, c2: Int) {
        val path = polyPath(w * 0.5f, h * 0.5f, min(w, h) * 0.38f, sides)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = w * 0.055f
            strokeJoin = Paint.Join.ROUND
            shader = LinearGradient(0f, 0f, w, h, c1, c2, Shader.TileMode.CLAMP)
        }
        canvas.drawPath(path, stroke)
    }

    private fun drawConcentricRings(canvas: Canvas, w: Float, h: Float, c1: Int, c2: Int) {
        val cx = w * 0.5f
        val cy = h * 0.5f
        val p1 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = w * 0.036f
            color = c1
        }
        val p2 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = w * 0.022f
            color = c2
        }
        canvas.drawCircle(cx, cy, w * 0.40f, p1)
        canvas.drawCircle(cx, cy, w * 0.28f, p2)
        canvas.drawCircle(cx, cy, w * 0.16f, p1)
    }

    private fun drawSplitCircle(canvas: Canvas, w: Float, h: Float) {
        val r = min(w, h) * 0.38f
        val rect = RectF(w * 0.5f - r, h * 0.5f - r, w * 0.5f + r, h * 0.5f + r)
        val p1 = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(245, 166, 35) }
        val p2 = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(56, 189, 248) }
        canvas.drawArc(rect, -90f, 180f, true, p1)
        canvas.drawArc(rect, 90f, 180f, true, p2)
    }

    private fun drawWaveRibbon(canvas: Canvas, w: Float, h: Float, c1: Int, c2: Int) {
        val path = Path().apply {
            moveTo(w * 0.08f, h * 0.56f)
            cubicTo(w * 0.26f, h * 0.18f, w * 0.44f, h * 0.86f, w * 0.64f, h * 0.46f)
            cubicTo(w * 0.76f, h * 0.24f, w * 0.86f, h * 0.42f, w * 0.92f, h * 0.50f)
        }
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = w * 0.085f
            strokeCap = Paint.Cap.ROUND
            shader = LinearGradient(0f, 0f, w, h, c1, c2, Shader.TileMode.CLAMP)
        }
        canvas.drawPath(path, p)
    }

    private fun drawCheckerPill(canvas: Canvas, w: Float, h: Float) {
        val rect = RectF(w * 0.10f, h * 0.28f, w * 0.90f, h * 0.72f)
        val clipPath = Path().apply { addRoundRect(rect, h * 0.22f, h * 0.22f, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(clipPath)
        val cell = w * 0.10f
        val dark = Paint().apply { color = Color.rgb(20, 20, 24) }
        val light = Paint().apply { color = Color.rgb(245, 166, 35) }
        var row = 0
        var y = rect.top
        while (y < rect.bottom) {
            var col = 0
            var x = rect.left
            while (x < rect.right) {
                canvas.drawRect(x, y, x + cell, y + cell, if ((row + col) % 2 == 0) light else dark)
                x += cell
                col++
            }
            y += cell
            row++
        }
        canvas.restore()
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = w * 0.025f
            color = Color.WHITE
        }
        canvas.drawRoundRect(rect, h * 0.22f, h * 0.22f, border)
    }

    private fun drawGradientOrb(canvas: Canvas, w: Float, h: Float, c1: Int, c2: Int, c3: Int) {
        val cx = w * 0.5f
        val cy = h * 0.5f
        val r = min(w, h) * 0.37f
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(cx - r * 0.28f, cy - r * 0.28f, r * 1.35f, intArrayOf(c1, c2, c3), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        }
        canvas.drawCircle(cx, cy, r, p)
    }

    private fun drawShieldShape(canvas: Canvas, w: Float, h: Float, c1: Int, c2: Int) {
        val path = Path().apply {
            moveTo(w * 0.50f, h * 0.10f)
            lineTo(w * 0.84f, h * 0.24f)
            lineTo(w * 0.80f, h * 0.58f)
            quadTo(w * 0.72f, h * 0.80f, w * 0.50f, h * 0.90f)
            quadTo(w * 0.28f, h * 0.80f, w * 0.20f, h * 0.58f)
            lineTo(w * 0.16f, h * 0.24f)
            close()
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, h * 0.1f, 0f, h * 0.9f, c1, c2, Shader.TileMode.CLAMP)
        }
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = w * 0.03f
            color = Color.WHITE
        }
        canvas.drawPath(path, fill)
        canvas.drawPath(path, border)
    }

    // --- Arrows Renderers ---

    private fun drawRotatedBoldArrow(canvas: Canvas, w: Float, h: Float, degrees: Float, c1: Int, c2: Int) {
        canvas.save()
        canvas.rotate(degrees, w * 0.5f, h * 0.5f)
        val path = Path().apply {
            moveTo(w * 0.14f, h * 0.38f)
            lineTo(w * 0.54f, h * 0.38f)
            lineTo(w * 0.54f, h * 0.20f)
            lineTo(w * 0.88f, h * 0.50f)
            lineTo(w * 0.54f, h * 0.80f)
            lineTo(w * 0.54f, h * 0.62f)
            lineTo(w * 0.14f, h * 0.62f)
            close()
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(w * 0.14f, 0f, w * 0.88f, h, c1, c2, Shader.TileMode.CLAMP)
        }
        canvas.drawPath(path, fill)
        canvas.restore()
    }

    private fun drawTripleChevron(canvas: Canvas, w: Float, h: Float, c1: Int, c2: Int) {
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = w * 0.06f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        listOf(0.16f to c2, 0.38f to c1, 0.60f to c2).forEach { (startX, col) ->
            stroke.color = col
            val p = Path().apply {
                moveTo(w * startX, h * 0.24f)
                lineTo(w * (startX + 0.22f), h * 0.50f)
                lineTo(w * startX, h * 0.76f)
            }
            canvas.drawPath(p, stroke)
        }
    }

    private fun drawCircularArrow(canvas: Canvas, w: Float, h: Float, color: Int) {
        val r = min(w, h) * 0.34f
        val rect = RectF(w * 0.5f - r, h * 0.5f - r, w * 0.5f + r, h * 0.5f + r)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = w * 0.07f
            strokeCap = Paint.Cap.ROUND
            this.color = color
        }
        canvas.drawArc(rect, -40f, 285f, false, p)
        val head = Path().apply {
            moveTo(w * 0.74f, h * 0.16f)
            lineTo(w * 0.86f, h * 0.34f)
            lineTo(w * 0.64f, h * 0.34f)
            close()
        }
        p.style = Paint.Style.FILL
        canvas.drawPath(head, p)
    }

    private fun drawDashedCurvedArrow(canvas: Canvas, w: Float, h: Float, color: Int) {
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = w * 0.055f
            strokeCap = Paint.Cap.ROUND
            this.color = color
            pathEffect = DashPathEffect(floatArrayOf(w * 0.08f, w * 0.06f), 0f)
        }
        val curve = Path().apply {
            moveTo(w * 0.14f, h * 0.76f)
            quadTo(w * 0.36f, h * 0.20f, w * 0.80f, h * 0.34f)
        }
        canvas.drawPath(curve, stroke)
        stroke.pathEffect = null
        stroke.color = Color.rgb(245, 166, 35)
        val head = Path().apply {
            moveTo(w * 0.66f, h * 0.19f)
            lineTo(w * 0.84f, h * 0.35f)
            lineTo(w * 0.64f, h * 0.48f)
        }
        canvas.drawPath(head, stroke)
    }

    private fun drawSplitForkArrow(canvas: Canvas, w: Float, h: Float, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = w * 0.065f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            this.color = color
        }
        canvas.drawLine(w * 0.14f, h * 0.50f, w * 0.48f, h * 0.50f, p)
        canvas.drawLine(w * 0.48f, h * 0.50f, w * 0.80f, h * 0.24f, p)
        canvas.drawLine(w * 0.48f, h * 0.50f, w * 0.80f, h * 0.76f, p)
        val topHead = Path().apply {
            moveTo(w * 0.66f, h * 0.22f); lineTo(w * 0.82f, h * 0.22f); lineTo(w * 0.80f, h * 0.38f)
        }
        val botHead = Path().apply {
            moveTo(w * 0.66f, h * 0.78f); lineTo(w * 0.82f, h * 0.78f); lineTo(w * 0.80f, h * 0.62f)
        }
        canvas.drawPath(topHead, p)
        canvas.drawPath(botHead, p)
    }

    private fun drawZigZagArrow(canvas: Canvas, w: Float, h: Float, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = w * 0.068f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            this.color = color
        }
        val path = Path().apply {
            moveTo(w * 0.12f, h * 0.72f)
            lineTo(w * 0.36f, h * 0.40f)
            lineTo(w * 0.54f, h * 0.60f)
            lineTo(w * 0.84f, h * 0.24f)
        }
        canvas.drawPath(path, p)
        val head = Path().apply {
            moveTo(w * 0.66f, h * 0.24f); lineTo(w * 0.85f, h * 0.23f); lineTo(w * 0.83f, h * 0.42f)
        }
        canvas.drawPath(head, p)
    }

    private fun drawCalloutTagArrow(canvas: Canvas, w: Float, h: Float) {
        val path = Path().apply {
            moveTo(w * 0.14f, h * 0.28f)
            lineTo(w * 0.68f, h * 0.28f)
            lineTo(w * 0.88f, h * 0.50f)
            lineTo(w * 0.68f, h * 0.72f)
            lineTo(w * 0.14f, h * 0.72f)
            close()
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(245, 166, 35) }
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(18, 18, 22) }
        canvas.drawPath(path, fill)
        canvas.drawCircle(w * 0.68f, h * 0.50f, w * 0.055f, dot)
    }

    // --- Light Flares Renderers ---

    private fun drawHorizontalStreakFlare(canvas: Canvas, w: Float, h: Float, accent: Int, core: Int) {
        val cy = h * 0.5f
        val streak = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, cy, w, cy,
                intArrayOf(Color.TRANSPARENT, Color.argb(165, Color.red(accent), Color.green(accent), Color.blue(accent)), core, Color.argb(165, Color.red(accent), Color.green(accent), Color.blue(accent)), Color.TRANSPARENT),
                floatArrayOf(0f, 0.28f, 0.5f, 0.72f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawOval(RectF(w * 0.02f, cy - h * 0.06f, w * 0.98f, cy + h * 0.06f), streak)
        val bloom = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(w * 0.5f, cy, w * 0.26f, intArrayOf(Color.WHITE, accent, Color.TRANSPARENT), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        }
        canvas.drawCircle(w * 0.5f, cy, w * 0.26f, bloom)
    }

    private fun drawRadialNovaFlare(canvas: Canvas, w: Float, h: Float, outer: Int, inner: Int) {
        val cx = w * 0.5f
        val cy = h * 0.5f
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(cx, cy, w * 0.46f, intArrayOf(Color.WHITE, inner, outer, Color.TRANSPARENT), floatArrayOf(0f, 0.24f, 0.62f, 1f), Shader.TileMode.CLAMP)
        }
        canvas.drawCircle(cx, cy, w * 0.46f, p)
    }

    private fun drawOpticalRingFlare(canvas: Canvas, w: Float, h: Float, c1: Int, c2: Int) {
        val cx = w * 0.5f
        val cy = h * 0.5f
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx, cy, w * 0.45f,
                intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, c2, c1, Color.TRANSPARENT),
                floatArrayOf(0f, 0.50f, 0.70f, 0.85f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(cx, cy, w * 0.45f, p)
    }

    private fun drawCrossGlintFlare(canvas: Canvas, w: Float, h: Float, glowCol: Int, coreCol: Int) {
        val cx = w * 0.5f
        val cy = h * 0.5f
        val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(cx, cy, w * 0.36f, intArrayOf(coreCol, glowCol, Color.TRANSPARENT), floatArrayOf(0f, 0.40f, 1f), Shader.TileMode.CLAMP)
        }
        canvas.drawCircle(cx, cy, w * 0.36f, halo)
        val star = starPath(cx, cy, w * 0.47f, w * 0.05f, 4)
        canvas.drawPath(star, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = coreCol })
    }

    private fun drawDiagonalWashFlare(canvas: Canvas, w: Float, h: Float, c1: Int, c2: Int) {
        val p1 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(w * 0.34f, h * 0.34f, w * 0.42f, intArrayOf(Color.argb(220, Color.red(c1), Color.green(c1), Color.blue(c1)), Color.TRANSPARENT), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        }
        val p2 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(w * 0.66f, h * 0.66f, w * 0.42f, intArrayOf(Color.argb(210, Color.red(c2), Color.green(c2), Color.blue(c2)), Color.TRANSPARENT), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        }
        canvas.drawCircle(w * 0.34f, h * 0.34f, w * 0.42f, p1)
        canvas.drawCircle(w * 0.66f, h * 0.66f, w * 0.42f, p2)
    }

    private fun drawClusterBokehFlare(canvas: Canvas, w: Float, h: Float, c1: Int, c2: Int) {
        listOf(
            Triple(0.32f, 0.36f, 0.20f to c1),
            Triple(0.66f, 0.32f, 0.16f to c2),
            Triple(0.54f, 0.64f, 0.22f to c1),
            Triple(0.28f, 0.68f, 0.14f to c2)
        ).forEach { (fx, fy, pair) ->
            val r = w * pair.first
            val col = pair.second
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    w * fx, h * fy, r,
                    intArrayOf(Color.argb(110, Color.red(col), Color.green(col), Color.blue(col)), Color.argb(220, Color.red(col), Color.green(col), Color.blue(col)), Color.TRANSPARENT),
                    floatArrayOf(0f, 0.80f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawCircle(w * fx, h * fy, r, p)
        }
    }

    // --- Badges & Social Pills ---

    private fun drawRectStampBadge(canvas: Canvas, w: Float, h: Float, text: String, accent: Int, bg: Int) {
        val rect = RectF(w * 0.07f, h * 0.26f, w * 0.93f, h * 0.74f)
        val r = h * 0.12f
        canvas.drawRoundRect(rect, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg })
        canvas.drawRoundRect(rect, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = w * 0.032f
            color = accent
        })
        drawCenteredText(canvas, rect, text, accent, Typeface.MONOSPACE, if (text.length > 5) h * 0.19f else h * 0.25f)
    }

    private fun drawGradientPill(canvas: Canvas, w: Float, h: Float, text: String, c1: Int, c2: Int, textColor: Int) {
        val rect = RectF(w * 0.08f, h * 0.27f, w * 0.92f, h * 0.73f)
        val r = h * 0.23f
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom, c1, c2, Shader.TileMode.CLAMP)
        }
        canvas.drawRoundRect(rect, r, r, fill)
        drawCenteredText(canvas, rect, text, textColor, Typeface.SANS_SERIF, if (text.length > 6) h * 0.17f else h * 0.22f)
    }

    private fun drawCenteredText(canvas: Canvas, rect: RectF, text: String, color: Int, family: Typeface, sizePx: Float) {
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(family, Typeface.BOLD)
            textSize = sizePx
        }
        val baseline = rect.centerY() - (tp.descent() + tp.ascent()) * 0.5f
        canvas.drawText(text, rect.centerX(), baseline, tp)
    }

    // --- Frames Renderers ---

    private fun drawCinemaLetterboxFrame(canvas: Canvas, w: Float, h: Float) {
        val barH = h * 0.13f
        val p = Paint().apply { color = Color.rgb(10, 10, 12) }
        canvas.drawRect(0f, 0f, w, barH, p)
        canvas.drawRect(0f, h - barH, w, h, p)
        val line = Paint().apply { color = Color.argb(140, 245, 166, 35); strokeWidth = max(2f, h * 0.005f) }
        canvas.drawLine(0f, barH, w, barH, line)
        canvas.drawLine(0f, h - barH, w, h - barH, line)
    }

    private fun drawNeonRectFrame(canvas: Canvas, w: Float, h: Float, c1: Int, c2: Int) {
        val m = w * 0.06f
        val path = Path().apply { addRoundRect(RectF(m, m, w - m, h - m), w * 0.05f, w * 0.05f, Path.Direction.CW) }
        drawNeonStroke(canvas, path, c1, max(4f, w * 0.018f))
    }

    private fun drawDoubleLineFrame(canvas: Canvas, w: Float, h: Float, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = max(2.5f, w * 0.012f); this.color = color }
        canvas.drawRect(w * 0.04f, h * 0.04f, w * 0.96f, h * 0.96f, p)
        p.strokeWidth = max(1.5f, w * 0.006f)
        canvas.drawRect(w * 0.08f, h * 0.08f, w * 0.92f, h * 0.92f, p)
    }

    private fun drawRoundedBorderFrame(canvas: Canvas, w: Float, h: Float, color: Int) {
        val m = w * 0.055f
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = max(4f, w * 0.024f); this.color = color }
        canvas.drawRoundRect(RectF(m, m, w - m, h - m), w * 0.10f, w * 0.10f, p)
    }

    private fun drawEditorialMatteFrame(canvas: Canvas, w: Float, h: Float) {
        val b = w * 0.07f
        val p = Paint().apply { color = Color.rgb(18, 18, 22) }
        canvas.drawRect(0f, 0f, w, b, p)
        canvas.drawRect(0f, h - b * 1.6f, w, h, p)
        canvas.drawRect(0f, b, b, h - b * 1.6f, p)
        canvas.drawRect(w - b, b, w, h - b * 1.6f, p)
        drawCenteredText(canvas, RectF(0f, h - b * 1.6f, w, h), "Z-EFFECT EDITION", Color.rgb(245, 166, 35), Typeface.MONOSPACE, b * 0.52f)
    }

    private fun drawCamcorderHudFrame(canvas: Canvas, w: Float, h: Float) {
        drawMinimalCornerFrame(canvas, w, h, Color.WHITE)
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(239, 68, 68) }
        canvas.drawCircle(w * 0.15f, h * 0.14f, w * 0.028f, dot)
        drawCenteredText(canvas, RectF(w * 0.18f, h * 0.09f, w * 0.36f, h * 0.19f), "REC", Color.WHITE, Typeface.MONOSPACE, w * 0.055f)
        drawCenteredText(canvas, RectF(w * 0.62f, h * 0.82f, w * 0.90f, h * 0.92f), "00:01:24", Color.WHITE, Typeface.MONOSPACE, w * 0.048f)
    }

    private fun drawMinimalCornerFrame(canvas: Canvas, w: Float, h: Float, color: Int) {
        val m = w * 0.06f
        val arm = w * 0.13f
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = max(3f, w * 0.016f); this.color = color }
        canvas.drawLine(m, m, m + arm, m, p); canvas.drawLine(m, m, m, m + arm, p)
        canvas.drawLine(w - m, m, w - m - arm, m, p); canvas.drawLine(w - m, m, w - m, m + arm, p)
        canvas.drawLine(m, h - m, m + arm, h - m, p); canvas.drawLine(m, h - m, m, h - m - arm, p)
        canvas.drawLine(w - m, h - m, w - m - arm, h - m, p); canvas.drawLine(w - m, h - m, w - m, h - m - arm, p)
    }

    private fun drawArchPortalFrame(canvas: Canvas, w: Float, h: Float, color: Int) {
        val outer = Path().apply { addRect(0f, 0f, w, h, Path.Direction.CW) }
        val inner = Path().apply {
            moveTo(w * 0.12f, h * 0.92f)
            lineTo(w * 0.12f, h * 0.44f)
            arcTo(RectF(w * 0.12f, h * 0.08f, w * 0.88f, h * 0.80f), 180f, 180f, false)
            lineTo(w * 0.88f, h * 0.92f)
            close()
        }
        outer.op(inner, Path.Op.DIFFERENCE)
        canvas.drawPath(outer, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
    }

    // --- Symbols Renderers ---

    private fun drawSunRaysSymbol(canvas: Canvas, w: Float, h: Float) {
        val cx = w * 0.5f; val cy = h * 0.5f
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(251, 191, 36) }
        canvas.drawCircle(cx, cy, w * 0.21f, fill)
        val ray = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = w * 0.045f; strokeCap = Paint.Cap.ROUND; color = Color.rgb(245, 158, 11) }
        for (i in 0 until 12) {
            val a = i * (PI / 6.0)
            canvas.drawLine(cx + (w * 0.29f * cos(a)).toFloat(), cy + (w * 0.29f * sin(a)).toFloat(), cx + (w * 0.43f * cos(a)).toFloat(), cy + (w * 0.43f * sin(a)).toFloat(), ray)
        }
    }

    private fun drawCutGemSymbol(canvas: Canvas, w: Float, h: Float, c1: Int, c2: Int) {
        val path = Path().apply {
            moveTo(w * 0.26f, h * 0.20f); lineTo(w * 0.74f, h * 0.20f)
            lineTo(w * 0.90f, h * 0.42f); lineTo(w * 0.50f, h * 0.86f)
            lineTo(w * 0.10f, h * 0.42f); close()
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = LinearGradient(0f, h * 0.2f, 0f, h * 0.86f, c1, c2, Shader.TileMode.CLAMP) }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = w * 0.024f; color = Color.WHITE }
        canvas.drawPath(path, fill)
        canvas.drawPath(path, line)
        canvas.drawLine(w * 0.10f, h * 0.42f, w * 0.90f, h * 0.42f, line)
        canvas.drawLine(w * 0.34f, h * 0.42f, w * 0.50f, h * 0.86f, line)
        canvas.drawLine(w * 0.66f, h * 0.42f, w * 0.50f, h * 0.86f, line)
    }

    private fun drawMusicNoteSymbol(canvas: Canvas, w: Float, h: Float, c1: Int, c2: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, w, h, c1, c2, Shader.TileMode.CLAMP)
            strokeWidth = w * 0.065f
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawCircle(w * 0.30f, h * 0.74f, w * 0.11f, p)
        canvas.drawCircle(w * 0.70f, h * 0.66f, w * 0.11f, p)
        canvas.drawLine(w * 0.39f, h * 0.74f, w * 0.39f, h * 0.24f, p)
        canvas.drawLine(w * 0.79f, h * 0.66f, w * 0.79f, h * 0.18f, p)
        canvas.drawLine(w * 0.39f, h * 0.24f, w * 0.79f, h * 0.18f, p)
    }

    private fun drawFlameSymbol(canvas: Canvas, w: Float, h: Float) {
        val outer = Path().apply {
            moveTo(w * 0.50f, h * 0.10f)
            cubicTo(w * 0.82f, h * 0.38f, w * 0.88f, h * 0.82f, w * 0.50f, h * 0.90f)
            cubicTo(w * 0.12f, h * 0.82f, w * 0.18f, h * 0.40f, w * 0.50f, h * 0.10f)
            close()
        }
        val inner = Path().apply {
            moveTo(w * 0.50f, h * 0.42f)
            cubicTo(w * 0.68f, h * 0.58f, w * 0.68f, h * 0.82f, w * 0.50f, h * 0.86f)
            cubicTo(w * 0.32f, h * 0.82f, w * 0.32f, h * 0.58f, w * 0.50f, h * 0.42f)
            close()
        }
        canvas.drawPath(outer, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, h * 0.1f, 0f, h * 0.9f, Color.rgb(251, 146, 60), Color.rgb(220, 38, 38), Shader.TileMode.CLAMP)
        })
        canvas.drawPath(inner, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(254, 240, 138) })
    }

    private fun drawAngelWingsSymbol(canvas: Canvas, w: Float, h: Float, c1: Int, c2: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, w, h, c1, c2, Shader.TileMode.CLAMP)
        }
        val leftWing = Path().apply {
            moveTo(w * 0.46f, h * 0.58f)
            cubicTo(w * 0.32f, h * 0.18f, w * 0.08f, h * 0.18f, w * 0.10f, h * 0.42f)
            cubicTo(w * 0.12f, h * 0.62f, w * 0.28f, h * 0.78f, w * 0.46f, h * 0.68f)
            close()
        }
        val rightWing = Path().apply {
            moveTo(w * 0.54f, h * 0.58f)
            cubicTo(w * 0.68f, h * 0.18f, w * 0.92f, h * 0.18f, w * 0.90f, h * 0.42f)
            cubicTo(w * 0.88f, h * 0.62f, w * 0.72f, h * 0.78f, w * 0.54f, h * 0.68f)
            close()
        }
        canvas.drawPath(leftWing, p)
        canvas.drawPath(rightWing, p)
    }

    private fun drawInfinitySymbol(canvas: Canvas, w: Float, h: Float, c1: Int, c2: Int) {
        val path = infinityPath(w, h)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = w * 0.075f
            strokeCap = Paint.Cap.ROUND
            shader = LinearGradient(0f, 0f, w, h, c1, c2, Shader.TileMode.CLAMP)
        }
        canvas.drawPath(path, p)
    }

    private fun infinityPath(w: Float, h: Float): Path = Path().apply {
        moveTo(w * 0.50f, h * 0.50f)
        cubicTo(w * 0.64f, h * 0.22f, w * 0.90f, h * 0.26f, w * 0.88f, h * 0.50f)
        cubicTo(w * 0.90f, h * 0.74f, w * 0.64f, h * 0.78f, w * 0.50f, h * 0.50f)
        cubicTo(w * 0.36f, h * 0.22f, w * 0.10f, h * 0.26f, w * 0.12f, h * 0.50f)
        cubicTo(w * 0.10f, h * 0.74f, w * 0.36f, h * 0.78f, w * 0.50f, h * 0.50f)
        close()
    }

    private fun drawVerifiedSealSymbol(canvas: Canvas, w: Float, h: Float, color: Int) {
        val seal = starPath(w * 0.5f, h * 0.5f, w * 0.42f, w * 0.34f, 10)
        canvas.drawPath(seal, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        val check = Path().apply {
            moveTo(w * 0.32f, h * 0.52f); lineTo(w * 0.45f, h * 0.65f); lineTo(w * 0.69f, h * 0.38f)
        }
        canvas.drawPath(check, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = w * 0.07f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; this.color = Color.WHITE
        })
    }

    private fun drawCompassRoseSymbol(canvas: Canvas, w: Float, h: Float) {
        val cx = w * 0.5f; val cy = h * 0.5f
        canvas.drawCircle(cx, cy, w * 0.38f, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = w * 0.03f; color = Color.WHITE })
        canvas.drawPath(starPath(cx, cy, w * 0.42f, w * 0.10f, 4), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(245, 166, 35) })
        canvas.drawCircle(cx, cy, w * 0.07f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
    }

    // --- Category 7: Neon Glow Renderers ---

    private fun drawNeonTubeCircle(canvas: Canvas, w: Float, h: Float, color: Int) {
        val path = Path().apply { addCircle(w * 0.5f, h * 0.5f, min(w, h) * 0.35f, Path.Direction.CW) }
        drawNeonStroke(canvas, path, color, w * 0.045f)
    }

    private fun drawNeonHeart(canvas: Canvas, w: Float, h: Float, color: Int) {
        val cx = w * 0.5f
        val path = Path().apply {
            moveTo(cx, h * 0.80f)
            cubicTo(w * 0.14f, h * 0.54f, w * 0.14f, h * 0.24f, w * 0.34f, h * 0.22f)
            cubicTo(w * 0.44f, h * 0.20f, cx, h * 0.32f, cx, h * 0.36f)
            cubicTo(cx, h * 0.32f, w * 0.56f, h * 0.20f, w * 0.66f, h * 0.22f)
            cubicTo(w * 0.86f, h * 0.24f, w * 0.86f, h * 0.54f, cx, h * 0.80f)
            close()
        }
        drawNeonStroke(canvas, path, color, w * 0.042f)
    }

    private fun drawNeonBolt(canvas: Canvas, w: Float, h: Float, color: Int) {
        val path = Path().apply {
            moveTo(w * 0.58f, h * 0.12f); lineTo(w * 0.26f, h * 0.52f); lineTo(w * 0.48f, h * 0.52f)
            lineTo(w * 0.42f, h * 0.88f); lineTo(w * 0.74f, h * 0.46f); lineTo(w * 0.52f, h * 0.46f); close()
        }
        drawNeonStroke(canvas, path, color, w * 0.038f)
    }

    private fun drawNeonStar(canvas: Canvas, w: Float, h: Float, color: Int) {
        drawNeonStroke(canvas, starPath(w * 0.5f, h * 0.5f, w * 0.38f, w * 0.16f, 5), color, w * 0.036f)
    }

    private fun drawNeonCrown(canvas: Canvas, w: Float, h: Float, color: Int) {
        val path = Path().apply {
            moveTo(w * 0.18f, h * 0.72f); lineTo(w * 0.22f, h * 0.32f); lineTo(w * 0.38f, h * 0.52f)
            lineTo(w * 0.50f, h * 0.24f); lineTo(w * 0.62f, h * 0.52f); lineTo(w * 0.78f, h * 0.32f)
            lineTo(w * 0.82f, h * 0.72f); close()
        }
        drawNeonStroke(canvas, path, color, w * 0.038f)
    }

    private fun drawNeonPolygon(canvas: Canvas, w: Float, h: Float, sides: Int, color: Int) {
        drawNeonStroke(canvas, polyPath(w * 0.5f, h * 0.5f, min(w, h) * 0.36f, sides), color, w * 0.042f)
    }

    private fun drawNeonWings(canvas: Canvas, w: Float, h: Float, color: Int) {
        val path = Path().apply {
            moveTo(w * 0.44f, h * 0.60f)
            cubicTo(w * 0.30f, h * 0.22f, w * 0.10f, h * 0.24f, w * 0.14f, h * 0.46f)
            cubicTo(w * 0.18f, h * 0.64f, w * 0.32f, h * 0.74f, w * 0.44f, h * 0.60f)
            moveTo(w * 0.56f, h * 0.60f)
            cubicTo(w * 0.70f, h * 0.22f, w * 0.90f, h * 0.24f, w * 0.86f, h * 0.46f)
            cubicTo(w * 0.82f, h * 0.64f, w * 0.68f, h * 0.74f, w * 0.56f, h * 0.60f)
        }
        drawNeonStroke(canvas, path, color, w * 0.034f)
    }

    private fun drawNeonHaloOval(canvas: Canvas, w: Float, h: Float, color: Int) {
        val path = Path().apply { addOval(RectF(w * 0.12f, h * 0.34f, w * 0.88f, h * 0.66f), Path.Direction.CW) }
        drawNeonStroke(canvas, path, color, w * 0.042f)
    }

    private fun drawNeonInfinity(canvas: Canvas, w: Float, h: Float, color: Int) {
        drawNeonStroke(canvas, infinityPath(w, h), color, w * 0.040f)
    }

    private fun drawNeonWave(canvas: Canvas, w: Float, h: Float, color: Int) {
        val path = Path().apply {
            moveTo(w * 0.10f, h * 0.55f)
            quadTo(w * 0.30f, h * 0.20f, w * 0.50f, h * 0.50f)
            quadTo(w * 0.70f, h * 0.80f, w * 0.90f, h * 0.45f)
        }
        drawNeonStroke(canvas, path, color, w * 0.044f)
    }

    private fun drawNeonArrow(canvas: Canvas, w: Float, h: Float, color: Int) {
        val path = Path().apply {
            moveTo(w * 0.16f, h * 0.50f); lineTo(w * 0.82f, h * 0.50f)
            moveTo(w * 0.60f, h * 0.28f); lineTo(w * 0.82f, h * 0.50f); lineTo(w * 0.60f, h * 0.72f)
        }
        drawNeonStroke(canvas, path, color, w * 0.044f)
    }

    private fun drawNeonCrescent(canvas: Canvas, w: Float, h: Float, color: Int) {
        val r = min(w, h) * 0.34f
        val outer = Path().apply { addCircle(w * 0.5f, h * 0.5f, r, Path.Direction.CW) }
        val cut = Path().apply { addCircle(w * 0.5f + r * 0.45f, h * 0.5f - r * 0.12f, r * 0.80f, Path.Direction.CW) }
        outer.op(cut, Path.Op.DIFFERENCE)
        drawNeonStroke(canvas, outer, color, w * 0.036f)
    }

    private fun drawNeonWordSign(canvas: Canvas, w: Float, h: Float, text: String, color: Int) {
        val rect = RectF(w * 0.08f, h * 0.26f, w * 0.92f, h * 0.74f)
        val border = Path().apply { addRoundRect(rect, h * 0.16f, h * 0.16f, Path.Direction.CW) }
        drawNeonStroke(canvas, border, color, w * 0.026f)
        drawCenteredText(canvas, rect, text, Color.WHITE, Typeface.SANS_SERIF, h * 0.23f)
    }

    // --- Category 8: Cyber HUD Renderers ---

    private fun drawHudReticle(canvas: Canvas, w: Float, h: Float, color: Int, segmented: Boolean) {
        val cx = w * 0.5f; val cy = h * 0.5f; val r = w * 0.36f
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = w * 0.026f; this.color = color }
        if (segmented) {
            val rect = RectF(cx - r, cy - r, cx + r, cy + r)
            for (i in 0 until 4) canvas.drawArc(rect, i * 90f + 14f, 62f, false, p)
        } else {
            canvas.drawCircle(cx, cy, r, p)
        }
        canvas.drawCircle(cx, cy, r * 0.45f, p)
        canvas.drawLine(cx - w * 0.45f, cy, cx - r * 0.58f, cy, p)
        canvas.drawLine(cx + r * 0.58f, cy, cx + w * 0.45f, cy, p)
        canvas.drawLine(cx, cy - h * 0.45f, cx, cy - r * 0.58f, p)
        canvas.drawLine(cx, cy + r * 0.58f, cx, cy + h * 0.45f, p)
    }

    private fun drawHudRadarRing(canvas: Canvas, w: Float, h: Float, color: Int) {
        val cx = w * 0.5f; val cy = h * 0.5f
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = w * 0.022f; this.color = color }
        canvas.drawCircle(cx, cy, w * 0.40f, p)
        canvas.drawCircle(cx, cy, w * 0.26f, p)
        canvas.drawCircle(cx, cy, w * 0.12f, p)
        val sweep = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.color = Color.argb(110, Color.red(color), Color.green(color), Color.blue(color)) }
        canvas.drawArc(RectF(w * 0.10f, h * 0.10f, w * 0.90f, h * 0.90f), -60f, 55f, true, sweep)
    }

    private fun drawHudBrackets(canvas: Canvas, w: Float, h: Float, color: Int, label: String) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = w * 0.032f; this.color = color }
        val left = Path().apply { moveTo(w * 0.28f, h * 0.24f); lineTo(w * 0.12f, h * 0.24f); lineTo(w * 0.12f, h * 0.76f); lineTo(w * 0.28f, h * 0.76f) }
        val right = Path().apply { moveTo(w * 0.72f, h * 0.24f); lineTo(w * 0.88f, h * 0.24f); lineTo(w * 0.88f, h * 0.76f); lineTo(w * 0.72f, h * 0.76f) }
        canvas.drawPath(left, p); canvas.drawPath(right, p)
        drawCenteredText(canvas, RectF(w * 0.14f, h * 0.26f, w * 0.86f, h * 0.74f), label, color, Typeface.MONOSPACE, h * 0.18f)
    }

    private fun drawHudScanGrid(canvas: Canvas, w: Float, h: Float, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = w * 0.018f; this.color = color }
        val m = w * 0.14f
        canvas.drawRect(m, m, w - m, h - m, p)
        for (i in 1..4) {
            val t = m + (w - 2 * m) * (i / 5f)
            canvas.drawLine(t, m, t, h - m, p)
            canvas.drawLine(m, t, w - m, t, p)
        }
    }

    private fun drawHudEqualizer(canvas: Canvas, w: Float, h: Float, c1: Int, c2: Int) {
        val heights = floatArrayOf(0.28f, 0.56f, 0.76f, 0.44f, 0.82f, 0.62f, 0.34f)
        val barW = w * 0.085f
        val gap = w * 0.032f
        val startX = (w - (heights.size * barW + (heights.size - 1) * gap)) * 0.5f
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = LinearGradient(0f, h * 0.15f, 0f, h * 0.85f, c1, c2, Shader.TileMode.CLAMP) }
        heights.forEachIndexed { idx, frac ->
            val bh = h * 0.74f * frac
            val x = startX + idx * (barW + gap)
            canvas.drawRoundRect(RectF(x, (h - bh) * 0.5f, x + barW, (h + bh) * 0.5f), barW * 0.4f, barW * 0.4f, p)
        }
    }

    private fun drawHudHexCluster(canvas: Canvas, w: Float, h: Float, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = w * 0.028f; this.color = color }
        canvas.drawPath(polyPath(w * 0.36f, h * 0.40f, w * 0.20f, 6), p)
        canvas.drawPath(polyPath(w * 0.66f, h * 0.40f, w * 0.20f, 6), p)
        canvas.drawPath(polyPath(w * 0.51f, h * 0.66f, w * 0.20f, 6), p)
    }

    private fun drawHudTelemetryPanel(canvas: Canvas, w: Float, h: Float, text: String, accent: Int) {
        val rect = RectF(w * 0.08f, h * 0.28f, w * 0.92f, h * 0.72f)
        canvas.drawRoundRect(rect, h * 0.08f, h * 0.08f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(215, 10, 14, 20) })
        canvas.drawRoundRect(rect, h * 0.08f, h * 0.08f, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = w * 0.024f; color = accent })
        drawCenteredText(canvas, rect, text, accent, Typeface.MONOSPACE, if (text.length > 6) h * 0.16f else h * 0.21f)
    }

    private fun drawHudCompassBar(canvas: Canvas, w: Float, h: Float, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = w * 0.024f; this.color = color }
        canvas.drawLine(w * 0.10f, h * 0.58f, w * 0.90f, h * 0.58f, p)
        for (i in 0..8) {
            val x = w * (0.12f + i * 0.095f)
            val top = if (i % 2 == 0) h * 0.36f else h * 0.46f
            canvas.drawLine(x, top, x, h * 0.58f, p)
        }
        drawCenteredText(canvas, RectF(0f, h * 0.62f, w, h * 0.84f), "N  045°  E", color, Typeface.MONOSPACE, h * 0.14f)
    }

    private fun drawHudBatteryModule(canvas: Canvas, w: Float, h: Float, color: Int) {
        val body = RectF(w * 0.12f, h * 0.30f, w * 0.82f, h * 0.70f)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = w * 0.032f; this.color = color }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        canvas.drawRoundRect(body, h * 0.06f, h * 0.06f, stroke)
        canvas.drawRect(w * 0.84f, h * 0.42f, w * 0.90f, h * 0.58f, fill)
        for (i in 0 until 4) {
            val x = w * (0.18f + i * 0.15f)
            canvas.drawRoundRect(RectF(x, h * 0.37f, x + w * 0.11f, h * 0.63f), w * 0.02f, w * 0.02f, fill)
        }
    }

    private fun drawHudSignalBars(canvas: Canvas, w: Float, h: Float, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        for (i in 0 until 5) {
            val x = w * (0.14f + i * 0.155f)
            val top = h * (0.74f - i * 0.13f)
            canvas.drawRoundRect(RectF(x, top, x + w * 0.11f, h * 0.82f), w * 0.025f, w * 0.025f, p)
        }
    }

    private fun drawHudOrbitalArcs(canvas: Canvas, w: Float, h: Float, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = w * 0.032f; strokeCap = Paint.Cap.ROUND; this.color = color }
        canvas.drawArc(RectF(w * 0.10f, h * 0.10f, w * 0.90f, h * 0.90f), -30f, 120f, false, p)
        canvas.drawArc(RectF(w * 0.10f, h * 0.10f, w * 0.90f, h * 0.90f), 150f, 120f, false, p)
        canvas.drawArc(RectF(w * 0.24f, h * 0.24f, w * 0.76f, h * 0.76f), 40f, 220f, false, p)
    }

    private fun drawHudWarningTriangle(canvas: Canvas, w: Float, h: Float, color: Int) {
        val tri = polyPath(w * 0.5f, h * 0.54f, w * 0.40f, 3)
        canvas.drawPath(tri, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = w * 0.044f; strokeJoin = Paint.Join.ROUND; this.color = color })
        drawCenteredText(canvas, RectF(w * 0.30f, h * 0.36f, w * 0.70f, h * 0.76f), "!", color, Typeface.SANS_SERIF, h * 0.32f)
    }

    // --- Category 9: Social & Creator Renderers ---

    private fun drawSocialBadge(canvas: Canvas, w: Float, h: Float, text: String, c1: Int, c2: Int, textColor: Int) {
        drawGradientPill(canvas, w, h, text, c1, c2, textColor)
    }

    private fun drawSocialGlassTag(canvas: Canvas, w: Float, h: Float, text: String, accent: Int) {
        val rect = RectF(w * 0.08f, h * 0.27f, w * 0.92f, h * 0.73f)
        val r = h * 0.18f
        canvas.drawRoundRect(rect, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(220, 16, 16, 20) })
        canvas.drawRoundRect(rect, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = w * 0.028f; color = accent })
        drawCenteredText(canvas, rect, text, accent, Typeface.SANS_SERIF, if (text.length > 6) h * 0.165f else h * 0.215f)
    }

    private fun drawSocialSwipeUp(canvas: Canvas, w: Float, h: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = w * 0.052f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = Color.WHITE }
        val ch1 = Path().apply { moveTo(w * 0.30f, h * 0.42f); lineTo(w * 0.50f, h * 0.22f); lineTo(w * 0.70f, h * 0.42f) }
        val ch2 = Path().apply { moveTo(w * 0.30f, h * 0.58f); lineTo(w * 0.50f, h * 0.38f); lineTo(w * 0.70f, h * 0.58f) }
        canvas.drawPath(ch1, p)
        p.color = Color.rgb(245, 166, 35)
        canvas.drawPath(ch2, p)
        drawCenteredText(canvas, RectF(w * 0.10f, h * 0.64f, w * 0.90f, h * 0.88f), "SWIPE UP", Color.WHITE, Typeface.SANS_SERIF, h * 0.15f)
    }

    private fun drawSocialReactionPill(canvas: Canvas, w: Float, h: Float, text: String, accent: Int) {
        val rect = RectF(w * 0.10f, h * 0.27f, w * 0.90f, h * 0.73f)
        val r = h * 0.23f
        canvas.drawRoundRect(rect, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        drawCenteredText(canvas, rect, text, accent, Typeface.SANS_SERIF, h * 0.21f)
    }

    // --- Category 10: Vintage Stamps Renderers ---

    private fun drawVintagePostmark(canvas: Canvas, w: Float, h: Float, line1: String, line2: String, color: Int) {
        canvas.save()
        canvas.rotate(-8f, w * 0.5f, h * 0.5f)
        val rect = RectF(w * 0.08f, h * 0.24f, w * 0.92f, h * 0.76f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = w * 0.032f; this.color = color }
        canvas.drawRect(rect, p)
        p.strokeWidth = w * 0.014f
        canvas.drawRect(RectF(w * 0.12f, h * 0.28f, w * 0.88f, h * 0.72f), p)
        drawCenteredText(canvas, RectF(w * 0.12f, h * 0.29f, w * 0.88f, h * 0.55f), line1, color, Typeface.MONOSPACE, h * 0.16f)
        drawCenteredText(canvas, RectF(w * 0.12f, h * 0.51f, w * 0.88f, h * 0.71f), line2, color, Typeface.MONOSPACE, h * 0.11f)
        canvas.restore()
    }

    private fun drawVintageBoxStamp(canvas: Canvas, w: Float, h: Float, text: String, color: Int, angle: Float) {
        canvas.save()
        canvas.rotate(angle, w * 0.5f, h * 0.5f)
        val rect = RectF(w * 0.09f, h * 0.30f, w * 0.91f, h * 0.70f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = w * 0.038f; this.color = color }
        canvas.drawRoundRect(rect, h * 0.06f, h * 0.06f, p)
        drawCenteredText(canvas, rect, text, color, Typeface.MONOSPACE, h * 0.17f)
        canvas.restore()
    }

    private fun drawVintageLedDateStamp(canvas: Canvas, w: Float, h: Float, dateText: String) {
        val rect = RectF(w * 0.06f, h * 0.32f, w * 0.94f, h * 0.68f)
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(120, 255, 115, 20)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = h * 0.20f
        }
        val baseline = rect.centerY() - (glow.descent() + glow.ascent()) * 0.5f
        canvas.drawText(dateText, rect.centerX() + 2f, baseline + 2f, glow)
        drawCenteredText(canvas, rect, dateText, Color.rgb(255, 148, 42), Typeface.MONOSPACE, h * 0.19f)
    }

    private fun drawVintageCircleSeal(canvas: Canvas, w: Float, h: Float, topText: String, bottomText: String, color: Int) {
        val cx = w * 0.5f; val cy = h * 0.5f
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = w * 0.030f; this.color = color }
        canvas.drawCircle(cx, cy, w * 0.40f, p)
        p.strokeWidth = w * 0.015f
        canvas.drawCircle(cx, cy, w * 0.33f, p)
        canvas.drawLine(w * 0.18f, cy, w * 0.82f, cy, p)
        drawCenteredText(canvas, RectF(w * 0.18f, h * 0.22f, w * 0.82f, cy), topText, color, Typeface.MONOSPACE, h * 0.115f)
        drawCenteredText(canvas, RectF(w * 0.18f, cy, w * 0.82f, h * 0.78f), bottomText, color, Typeface.MONOSPACE, h * 0.135f)
    }

    private fun drawVintagePassportStamp(canvas: Canvas, w: Float, h: Float, city: String, code: String, color: Int) {
        canvas.save()
        canvas.rotate(6f, w * 0.5f, h * 0.5f)
        val rect = RectF(w * 0.10f, h * 0.24f, w * 0.90f, h * 0.76f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = w * 0.032f; this.color = color }
        canvas.drawRoundRect(rect, h * 0.14f, h * 0.14f, p)
        drawCenteredText(canvas, RectF(w * 0.14f, h * 0.28f, w * 0.86f, h * 0.56f), city, color, Typeface.MONOSPACE, h * 0.18f)
        drawCenteredText(canvas, RectF(w * 0.14f, h * 0.54f, w * 0.86f, h * 0.73f), code, color, Typeface.MONOSPACE, h * 0.115f)
        canvas.restore()
    }

    private fun drawVintageWaxSeal(canvas: Canvas, w: Float, h: Float, initial: String) {
        val seal = starPath(w * 0.5f, h * 0.5f, w * 0.41f, w * 0.35f, 16)
        canvas.drawPath(seal, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(185, 28, 28) })
        canvas.drawCircle(w * 0.5f, h * 0.5f, w * 0.28f, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = w * 0.025f; color = Color.rgb(252, 165, 165) })
        drawCenteredText(canvas, RectF(w * 0.22f, h * 0.22f, w * 0.78f, h * 0.78f), initial, Color.rgb(254, 226, 226), Typeface.SERIF, h * 0.30f)
    }

    private fun drawVintageAwardSeal(canvas: Canvas, w: Float, h: Float, label: String) {
        val ribbon = Path().apply {
            moveTo(w * 0.34f, h * 0.58f); lineTo(w * 0.26f, h * 0.92f); lineTo(w * 0.42f, h * 0.82f); lineTo(w * 0.50f, h * 0.92f)
            lineTo(w * 0.58f, h * 0.82f); lineTo(w * 0.74f, h * 0.92f); lineTo(w * 0.66f, h * 0.58f); close()
        }
        canvas.drawPath(ribbon, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(180, 83, 9) })
        canvas.drawPath(starPath(w * 0.5f, h * 0.44f, w * 0.34f, w * 0.27f, 14), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(245, 166, 35) })
        drawCenteredText(canvas, RectF(w * 0.24f, h * 0.24f, w * 0.76f, h * 0.64f), label, Color.rgb(18, 18, 22), Typeface.SANS_SERIF, h * 0.20f)
    }
}
