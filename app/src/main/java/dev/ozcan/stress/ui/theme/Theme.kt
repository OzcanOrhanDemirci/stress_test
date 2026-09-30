package dev.ozcan.stress.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Reactor palette: near-black steel, Cherenkov blue, a warning amber. */
object StressColors {
    val Background = Color(0xFF05080A)
    val Surface = Color(0xFF0D1417)
    val SurfaceHigh = Color(0xFF142026)
    val Outline = Color(0xFF22323A)
    val Cherenkov = Color(0xFF3FD0FF)
    val CherenkovDim = Color(0xFF1C6F8C)
    val Text = Color(0xFFE3EEF2)
    val TextDim = Color(0xFF8AA0A9)
    val Good = Color(0xFF7CFFB2)
    val Warn = Color(0xFFFFB547)
    val Bad = Color(0xFFFF5C5C)
}

private val scheme = darkColorScheme(
    primary = StressColors.Cherenkov,
    onPrimary = StressColors.Background,
    secondary = StressColors.CherenkovDim,
    background = StressColors.Background,
    onBackground = StressColors.Text,
    surface = StressColors.Surface,
    onSurface = StressColors.Text,
    surfaceVariant = StressColors.SurfaceHigh,
    onSurfaceVariant = StressColors.TextDim,
    outline = StressColors.Outline,
    error = StressColors.Bad,
)

@Composable
fun StressTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
