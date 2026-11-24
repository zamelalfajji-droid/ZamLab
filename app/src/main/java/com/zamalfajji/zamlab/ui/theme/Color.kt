package com.zamalfajji.zamlab.ui.theme

import androidx.compose.ui.graphics.Color

// Pure OLED Black & Warm Amber-Gold Studio Darkroom Palette
val StudioOledBlack = Color(0xFF000000)       // True OLED black canvas surface
val StudioCharcoal = StudioOledBlack          // True OLED black root canvas
val StudioGraphite = Color(0xFF121214)        // Base dark-charcoal tone
val StudioFrostedGlass = Color(0xFF121214).copy(alpha = 0.72f)  // Eye-relaxing translucent dark glass HUD
val StudioFrostedBorder = Color(0xFF2E323C)   // Subtle 1dp frosted glass hairline border
val StudioTileSurface = Color(0xFF16161A).copy(alpha = 0.72f)   // Translucent dark-charcoal cards & tool tiles
val StudioTileBorder = StudioFrostedBorder    // Subtle 1dp #2E323C hairline border
val StudioHairline = StudioFrostedBorder      // Subtle 1dp #2E323C hairline border
val StudioCinemaAmber = Color(0xFFF5A623)     // Warm amber-gold active highlight, dot & ruler needle
val StudioAmberPillFill = Color(0xFF261D0E).copy(alpha = 0.80f) // Translucent amber-tinted active pill fill
val StudioDockActivePill = Color(0xFF261D0E).copy(alpha = 0.84f) // Active capsule dock pill fill
val StudioCoolWhite = Color(0xFFFFFFFF)       // Crisp pure white typography
val StudioMutedSlate = Color(0xFF8E9099)      // Neutral muted label ink

val StudioBackground = StudioOledBlack
val StudioSurface = StudioGraphite
val StudioGlassSurface = StudioFrostedGlass   // Translucent 72% dark glass floating pills, drawers & trays
val StudioGlassSurfaceDeep = Color(0xFF121214).copy(alpha = 0.80f)
val StudioGlassHairline = StudioFrostedBorder
val StudioGlassHighlight = Color(0xFF2E323C)  // Subtle frosted glass highlight / drag handle

val StudioSurfaceElevated = StudioTileSurface
val StudioSurfaceHighest = Color(0xFF1E1E22).copy(alpha = 0.78f)
val StudioBorder = StudioFrostedBorder
val StudioBorderSubtle = StudioFrostedBorder

val StudioActiveOffWhite = StudioCoolWhite
val StudioLuxuryGold = StudioCinemaAmber
val StudioAmber = StudioCinemaAmber
val StudioObsidian = StudioOledBlack
val StudioAmberContainer = StudioAmberPillFill
val StudioCyan = Color(0xFF38BDF8)
val StudioCyanContainer = Color(0xFF0D2D3D)
val StudioEraserCrimson = Color(0xFFEF4444)
val StudioEraserCrimsonSoft = Color(0x88EF4444)
val StudioSuccessGreen = Color(0xFF34D399)

val StudioTextPrimary = StudioCoolWhite
val StudioTextSecondary = StudioMutedSlate
val StudioTextMuted = Color(0xFF686A73)
