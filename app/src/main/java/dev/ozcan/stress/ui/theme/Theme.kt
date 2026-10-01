package dev.ozcan.stress.ui.theme

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * Benchmark palette: graphite, a hot orange for power and heat (the number
 * the app is about), a cool cyan for a second series.
 */
object StressColors {
    val Background = Color(0xFF0A0B0D)
    val Surface = Color(0xFF131519)
    val SurfaceHigh = Color(0xFF1B1E24)
    val Outline = Color(0xFF2A2E36)
    val Accent = Color(0xFFFF6A21)
    val AccentDim = Color(0xFF6E3015)
    val Cool = Color(0xFF4CC9F0)
    val Text = Color(0xFFECEEF1)
    val TextDim = Color(0xFF8C939E)
    val Good = Color(0xFF7CFFB2)
    val Warn = Color(0xFFFFD84D)
    val Bad = Color(0xFFFF5C5C)
}

private val scheme = darkColorScheme(
    primary = StressColors.Accent,
    onPrimary = StressColors.Background,
    secondary = StressColors.AccentDim,
    background = StressColors.Background,
    onBackground = StressColors.Text,
    surface = StressColors.Surface,
    onSurface = StressColors.Text,
    surfaceVariant = StressColors.SurfaceHigh,
    onSurfaceVariant = StressColors.TextDim,
    outline = StressColors.Outline,
    error = StressColors.Bad,
)

/** The theme plus a full-screen surface, so text defaults to the light content colour. */
@Composable
fun StressTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme) {
        Surface(modifier = Modifier.fillMaxSize(), color = StressColors.Background, contentColor = StressColors.Text) {
            content()
        }
    }
}
