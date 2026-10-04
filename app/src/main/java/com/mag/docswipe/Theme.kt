package com.mag.docswipe

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DocSwipeColors = lightColorScheme(
    primary = Color(0xFF3F9F78),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7F1E4),
    onPrimaryContainer = Color(0xFF123D2D),
    secondary = Color(0xFF5E6A78),
    tertiary = Color(0xFFEE6F5D),
    error = Color(0xFFD9564B),
    background = Color(0xFFF7F8F4),
    onBackground = Color(0xFF18221E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF18221E),
    surfaceVariant = Color(0xFFEEF1EC),
    onSurfaceVariant = Color(0xFF66716B),
    outline = Color(0xFFD9E0DA)
)

private val DocSwipeDarkColors = darkColorScheme(
    primary = Color(0xFF8DD8B6),
    onPrimary = Color(0xFF003824),
    primaryContainer = Color(0xFF1F6B4B),
    onPrimaryContainer = Color(0xFFB4F1D0),
    secondary = Color(0xFFB8C8C0),
    tertiary = Color(0xFFFFB4A7),
    error = Color(0xFFFFB4AB),
    background = Color(0xFF101512),
    onBackground = Color(0xFFE0E5DF),
    surface = Color(0xFF101512),
    onSurface = Color(0xFFE0E5DF),
    surfaceVariant = Color(0xFF404943),
    onSurfaceVariant = Color(0xFFB9C3BC),
    outline = Color(0xFF89938B)
)

@Composable
fun DocSwipeTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) DocSwipeDarkColors else DocSwipeColors, content = content)
}
