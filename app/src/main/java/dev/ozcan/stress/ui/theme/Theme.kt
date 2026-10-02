package dev.ozcan.stress.ui.theme

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.ozcan.stress.R

/**
 * Benchmark palette: graphite, a hot orange-to-red for power and heat (the
 * numbers the app is about), a cool cyan for a second series, and the usual
 * green, amber and red for verdicts.
 */
object StressColors {
    val Background = Color(0xFF07080B)
    val Surface = Color(0xFF111319)
    val SurfaceHigh = Color(0xFF181B23)
    val SurfaceHighest = Color(0xFF222632)
    val Outline = Color(0xFF262A34)
    val OutlineBright = Color(0xFF3B404D)
    val Accent = Color(0xFFFF6A21)
    val AccentHot = Color(0xFFFF3B5C)
    val AccentDim = Color(0xFF6E3015)
    val Cool = Color(0xFF4CC9F0)
    val CoolDeep = Color(0xFF3A86FF)
    val Violet = Color(0xFFC084FC)
    val Text = Color(0xFFEDEFF3)
    val TextDim = Color(0xFF9097A3)
    val TextFaint = Color(0xFF5E6470)
    val Good = Color(0xFF4ADE80)
    val Warn = Color(0xFFFBBF24)
    val Bad = Color(0xFFF87171)
    /** Translucent panels over a scene. */
    val Glass = Color(0xB30A0C10)

    val Heat: Brush get() = Brush.linearGradient(listOf(Accent, AccentHot))
    val HeatHorizontal: Brush get() = Brush.horizontalGradient(listOf(Accent, AccentHot))
}

/** Space Grotesk (variable weight) for text, JetBrains Mono for numbers that change in place. */
@OptIn(ExperimentalTextApi::class)
object StressFonts {
    private fun grotesk(weight: Int) = Font(
        R.font.space_grotesk,
        weight = FontWeight(weight),
        variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
    )

    private fun mono(weight: Int) = Font(
        R.font.jetbrains_mono,
        weight = FontWeight(weight),
        variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
    )

    val Grotesk = FontFamily(grotesk(300), grotesk(400), grotesk(500), grotesk(600), grotesk(700))
    val Mono = FontFamily(mono(400), mono(500), mono(600), mono(700))
}

private val base = Typography()

private fun TextStyle.grotesk(weight: FontWeight, letterSpacing: Float? = null) =
    copy(fontFamily = StressFonts.Grotesk, fontWeight = weight, letterSpacing = letterSpacing?.sp ?: this.letterSpacing)

private val typography = Typography(
    displayLarge = base.displayLarge.grotesk(FontWeight.Bold, -1f),
    displayMedium = base.displayMedium.grotesk(FontWeight.Bold, -0.5f),
    displaySmall = base.displaySmall.grotesk(FontWeight.Bold),
    headlineLarge = base.headlineLarge.grotesk(FontWeight.Bold),
    headlineMedium = base.headlineMedium.grotesk(FontWeight.SemiBold),
    headlineSmall = base.headlineSmall.grotesk(FontWeight.SemiBold),
    titleLarge = base.titleLarge.grotesk(FontWeight.SemiBold),
    titleMedium = base.titleMedium.grotesk(FontWeight.SemiBold),
    titleSmall = base.titleSmall.grotesk(FontWeight.Medium),
    bodyLarge = base.bodyLarge.grotesk(FontWeight.Normal),
    bodyMedium = base.bodyMedium.grotesk(FontWeight.Normal),
    bodySmall = base.bodySmall.grotesk(FontWeight.Normal),
    labelLarge = base.labelLarge.grotesk(FontWeight.SemiBold),
    labelMedium = base.labelMedium.grotesk(FontWeight.Medium),
    labelSmall = base.labelSmall.grotesk(FontWeight.Medium),
)

/** Numbers: monospaced digits, so a value changing in place does not shift its neighbours. */
object NumberStyles {
    val Hero = TextStyle(fontFamily = StressFonts.Mono, fontWeight = FontWeight.Bold, fontSize = 56.sp, letterSpacing = (-2).sp)
    val Large = TextStyle(fontFamily = StressFonts.Mono, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, letterSpacing = (-1).sp)
    val Medium = TextStyle(fontFamily = StressFonts.Mono, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, letterSpacing = (-0.5).sp)
    val Small = TextStyle(fontFamily = StressFonts.Mono, fontWeight = FontWeight.Medium, fontSize = 13.sp)
    val Tiny = TextStyle(fontFamily = StressFonts.Mono, fontWeight = FontWeight.Medium, fontSize = 11.sp)
}

private val scheme = darkColorScheme(
    primary = StressColors.Accent,
    onPrimary = StressColors.Background,
    primaryContainer = StressColors.AccentDim,
    onPrimaryContainer = StressColors.Text,
    secondary = StressColors.Cool,
    onSecondary = StressColors.Background,
    background = StressColors.Background,
    onBackground = StressColors.Text,
    surface = StressColors.Surface,
    onSurface = StressColors.Text,
    surfaceVariant = StressColors.SurfaceHigh,
    onSurfaceVariant = StressColors.TextDim,
    surfaceContainer = StressColors.SurfaceHigh,
    surfaceContainerHigh = StressColors.SurfaceHighest,
    surfaceContainerHighest = StressColors.SurfaceHighest,
    outline = StressColors.Outline,
    outlineVariant = StressColors.Outline,
    error = StressColors.Bad,
)

/** The theme plus a full-screen surface, so text defaults to the light content colour. */
@Composable
fun StressTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography) {
        Surface(modifier = Modifier.fillMaxSize(), color = StressColors.Background, contentColor = StressColors.Text) {
            content()
        }
    }
}
