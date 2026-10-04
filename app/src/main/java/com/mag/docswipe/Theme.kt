package com.mag.docswipe

import androidx.compose.material3.MaterialTheme
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

@Composable
fun DocSwipeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DocSwipeColors, content = content)
}
