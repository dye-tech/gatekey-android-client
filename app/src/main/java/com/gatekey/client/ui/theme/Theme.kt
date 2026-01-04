package com.gatekey.client.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Gatekey brand colors
val GatekeyBlue = Color(0xFF2563EB)
val GatekeyBlueLight = Color(0xFF3B82F6)
val GatekeyBlueDark = Color(0xFF1D4ED8)
val GatekeyGreen = Color(0xFF10B981)
val GatekeyRed = Color(0xFFEF4444)
val GatekeyYellow = Color(0xFFF59E0B)

private val DarkColorScheme = darkColorScheme(
    primary = GatekeyBlueLight,
    onPrimary = Color.White,
    primaryContainer = GatekeyBlueDark,
    onPrimaryContainer = Color.White,
    secondary = GatekeyGreen,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF1A3D2E),
    onSecondaryContainer = GatekeyGreen,
    tertiary = GatekeyYellow,
    onTertiary = Color.Black,
    tertiaryContainer = Color(0xFF3D3012),
    onTertiaryContainer = GatekeyYellow,
    error = GatekeyRed,
    onError = Color.White,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color(0xFF1A1A1A),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF2A2A2A),
    onSurfaceVariant = Color(0xFFB0B0B0),
    outline = Color(0xFF444444),
    outlineVariant = Color(0xFF333333)
)

private val LightColorScheme = lightColorScheme(
    primary = GatekeyBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBEAFE),
    onPrimaryContainer = GatekeyBlueDark,
    secondary = GatekeyGreen,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD1FAE5),
    onSecondaryContainer = Color(0xFF065F46),
    tertiary = GatekeyYellow,
    onTertiary = Color.Black,
    tertiaryContainer = Color(0xFFFEF3C7),
    onTertiaryContainer = Color(0xFF92400E),
    error = GatekeyRed,
    onError = Color.White,
    background = Color.White,
    onBackground = Color(0xFF1A1A1A),
    surface = Color.White,
    onSurface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFFF5F5F5),
    onSurfaceVariant = Color(0xFF5F5F5F),
    outline = Color(0xFFE0E0E0),
    outlineVariant = Color(0xFFEEEEEE)
)

@Composable
fun GatekeyTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
