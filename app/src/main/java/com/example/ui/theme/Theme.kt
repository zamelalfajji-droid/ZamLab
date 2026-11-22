package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val StudioDarkColorScheme = darkColorScheme(
    primary = StudioCinemaAmber,
    onPrimary = StudioCharcoal,
    primaryContainer = StudioAmberPillFill,
    onPrimaryContainer = StudioCinemaAmber,
    secondary = StudioCinemaAmber,
    onSecondary = StudioCharcoal,
    secondaryContainer = StudioAmberPillFill,
    onSecondaryContainer = StudioCinemaAmber,
    tertiary = StudioCyan,
    onTertiary = StudioCharcoal,
    background = StudioCharcoal,
    onBackground = StudioCoolWhite,
    surface = StudioGraphite,
    onSurface = StudioCoolWhite,
    surfaceVariant = StudioTileSurface,
    onSurfaceVariant = StudioMutedSlate,
    surfaceContainerLowest = StudioCharcoal,
    surfaceContainerLow = StudioGraphite,
    surfaceContainer = StudioTileSurface,
    surfaceContainerHigh = StudioSurfaceHighest,
    outline = StudioHairline,
    outlineVariant = StudioTileBorder,
    error = StudioEraserCrimson,
    onError = StudioCoolWhite
)

private val StudioShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun ZamLabTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = StudioDarkColorScheme,
        typography = StudioTypography,
        shapes = StudioShapes,
        content = content
    )
}
