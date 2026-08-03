package ru.franprobe.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF37D5FF),
    secondary = Color(0xFF9CB8FF),
    tertiary = Color(0xFF76E6B0),
    background = Color(0xFF08111F),
    surface = Color(0xFF101C2E),
    surfaceVariant = Color(0xFF1A2940),
    onPrimary = Color(0xFF002633),
    onBackground = Color(0xFFE8F1FF),
    onSurface = Color(0xFFE8F1FF)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF006780),
    secondary = Color(0xFF405E91),
    tertiary = Color(0xFF006C4C),
    background = Color(0xFFF7F9FF),
    surface = Color.White,
    surfaceVariant = Color(0xFFE5EEFA)
)

@Composable
fun FranProbeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content
    )
}
