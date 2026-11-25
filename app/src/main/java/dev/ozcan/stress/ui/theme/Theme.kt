package dev.ozcan.stress.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ozcan.stress.R

/** The instrument palette. Accent is power, Cool is information, and verdicts use their own colours. */
object StressColors {
    val Background = Color(0xFF0B0D11)
    val SurfaceLow = Color(0xFF101319)
    val Surface = Color(0xFF141820)
    val SurfaceHigh = Color(0xFF1B212C)
    val SurfaceHighest = Color(0xFF232A38)
    val Outline = Color(0xFF2C3545)
    val OutlineStrong = Color(0xFF3D4759)
    val Text = Color(0xFFE6E9EF)
    val TextDim = Color(0xFF8A94A6)
    val TextFaint = Color(0xFF5A6375)
    val Accent = Color(0xFFFF6B2C)
    val OnAccent = Color(0xFF1A0B04)
    val AccentTint = Accent.copy(alpha = 0.12f)
    val Cool = Color(0xFF38BDF8)
    val CoolTint = Cool.copy(alpha = 0.12f)
    val Good = Color(0xFF22C55E)
    val Warn = Color(0xFFF59E0B)
    val Bad = Color(0xFFEF4444)
    val Danger = Color(0xFF8E0F14)
    val OnDanger = Color(0xFFFFE4E1)

    /** The reading colour for the supplied safety thresholds. */
    fun temperature(celsius: Double?, warning: Double, stop: Double): Color = when {
        celsius == null -> TextDim
        celsius >= stop -> Bad
        celsius >= warning -> Warn
        else -> Text
    }
}

/** Bundled families for headings, body text and readings. */
object StressFonts {
    private fun variable(id: Int, weight: Int) = Font(
        id,
        weight = FontWeight(weight),
        variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
    )

    val Grotesk = FontFamily(variable(R.font.space_grotesk, 600), variable(R.font.space_grotesk, 700))
    val Sans = FontFamily(variable(R.font.instrument_sans, 400), variable(R.font.instrument_sans, 600))
    val Mono = FontFamily(Font(R.font.space_mono_regular), Font(R.font.space_mono_bold, FontWeight.Bold))
}

private fun type(family: FontFamily, size: Int, line: Int, weight: FontWeight, spacing: Float = 0f) =
    TextStyle(fontFamily = family, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = spacing.sp)

/** Text roles shared by the instrument's parts. */
object StressType {
    val Display = type(StressFonts.Grotesk, 28, 32, FontWeight.Bold, -0.25f)
    val Title = type(StressFonts.Grotesk, 20, 26, FontWeight.SemiBold)
    val Heading = type(StressFonts.Grotesk, 16, 22, FontWeight.SemiBold)
    val Body = type(StressFonts.Sans, 14, 20, FontWeight.Normal)
    val BodySmall = type(StressFonts.Sans, 12, 16, FontWeight.Normal)
    val Label = type(StressFonts.Sans, 12, 16, FontWeight.SemiBold, 0.6f)
    val Code = type(StressFonts.Mono, 12, 16, FontWeight.Normal)
}

/** Monospaced readings. [Tiny] is the Code role used by existing screens. */
object NumberStyles {
    val Hero = type(StressFonts.Mono, 40, 44, FontWeight.Bold, -0.5f)
    val Large = type(StressFonts.Mono, 28, 32, FontWeight.Bold, -0.5f)
    val Medium = type(StressFonts.Mono, 20, 24, FontWeight.Bold)
    val Small = type(StressFonts.Mono, 14, 18, FontWeight.Bold)
    val Tiny = StressType.Code
}

/** Timings for state changes and for entering and leaving a screen or panel. */
object StressMotion {
    fun <T> state() = tween<T>(120, easing = LinearOutSlowInEasing)
    fun <T> enter() = tween<T>(240, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))
    fun <T> exit() = tween<T>(160, easing = LinearOutSlowInEasing)
}

private val typography = Typography(
    displayLarge = StressType.Display,
    displayMedium = StressType.Display,
    displaySmall = StressType.Display,
    headlineLarge = StressType.Display,
    headlineMedium = StressType.Title,
    headlineSmall = StressType.Title,
    titleLarge = StressType.Title,
    titleMedium = StressType.Heading,
    titleSmall = StressType.Heading,
    bodyLarge = StressType.Body,
    bodyMedium = StressType.Body,
    bodySmall = StressType.BodySmall,
    labelLarge = StressType.Label,
    labelMedium = StressType.Label,
    labelSmall = StressType.Label,
)

private val scheme = darkColorScheme(
    primary = StressColors.Accent,
    onPrimary = StressColors.OnAccent,
    primaryContainer = StressColors.AccentTint,
    onPrimaryContainer = StressColors.Text,
    secondary = StressColors.Cool,
    onSecondary = StressColors.Background,
    secondaryContainer = StressColors.SurfaceHigh,
    onSecondaryContainer = StressColors.Cool,
    background = StressColors.Background,
    onBackground = StressColors.Text,
    surface = StressColors.Surface,
    onSurface = StressColors.Text,
    surfaceVariant = StressColors.SurfaceHigh,
    onSurfaceVariant = StressColors.TextDim,
    surfaceContainerLow = StressColors.SurfaceLow,
    surfaceContainer = StressColors.Surface,
    surfaceContainerHigh = StressColors.SurfaceHigh,
    surfaceContainerHighest = StressColors.SurfaceHighest,
    outline = StressColors.OutlineStrong,
    outlineVariant = StressColors.Outline,
    error = StressColors.Bad,
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(8.dp),
)

/** Applies the dark instrument theme and provides the screen ground. */
@Composable
fun StressTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, shapes = shapes) {
        Surface(Modifier.fillMaxSize(), color = StressColors.Background, contentColor = StressColors.Text) {
            content()
        }
    }
}
