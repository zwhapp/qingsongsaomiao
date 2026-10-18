package com.openscan.docscan.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val BrandGreen = Color(0xFF1B6B4A)
private val BrandGreenDark = Color(0xFF2FA37A)
private val BrandAccent = Color(0xFF2FA37A)
private val AccentAmber = Color(0xFFE0A521)

private val LightColors = lightColorScheme(
    primary = BrandGreen,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB7E4D0),
    onPrimaryContainer = Color(0xFF00210F),
    secondary = BrandAccent,
    onSecondary = Color.White,
    tertiary = AccentAmber,
    onTertiary = Color(0xFF2B1D00),
    background = Color(0xFFFBFAF8),
    onBackground = Color(0xFF1B1C19),
    surface = Color(0xFFFBFAF8),
    onSurface = Color(0xFF1B1C19),
    surfaceVariant = Color(0xFFEDEAE5),
    onSurfaceVariant = Color(0xFF4A4B47),
    outline = Color(0xFF7B7C77)
)

private val DarkColors = darkColorScheme(
    primary = BrandGreenDark,
    onPrimary = Color(0xFF00391F),
    primaryContainer = Color(0xFF00522F),
    onPrimaryContainer = Color(0xFFB7E4D0),
    secondary = Color(0xFF8AD5B4),
    onSecondary = Color(0xFF00382A),
    tertiary = Color(0xFFF5C563),
    onTertiary = Color(0xFF3F2E00),
    background = Color(0xFF121411),
    onBackground = Color(0xFFE3E3DE),
    surface = Color(0xFF121411),
    onSurface = Color(0xFFE3E3DE),
    surfaceVariant = Color(0xFF3F4943),
    onSurfaceVariant = Color(0xFFBFC9C1)
)

val AppCornerRadius = 14.dp
val AppCardElevation = 1.dp

@Composable
fun DocScanTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content
    )
}
