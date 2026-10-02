package dev.ozcan.stress.ui.components

import dev.ozcan.stress.ui.upper
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ozcan.stress.ui.theme.NumberStyles
import dev.ozcan.stress.ui.theme.StressColors
import kotlin.math.cos
import kotlin.math.sin

private const val START = 150f
private const val SWEEP = 240f

/**
 * The app's mark: a gauge, its scale a 240-degree arc from lower left to lower
 * right, filled and pointed at by the needle up to [reading] (0..1).
 */
@Composable
fun GaugeMark(modifier: Modifier = Modifier, reading: Float = 0.86f, color: Color? = null) {
    Canvas(modifier = modifier) {
        val r = size.minDimension / 2
        val c = Offset(size.width / 2, size.height / 2)
        val stroke = r * 0.16f
        val inset = stroke / 2
        val arcTopLeft = Offset(c.x - r + inset, c.y - r + inset)
        val arcSize = Size(2 * r - stroke, 2 * r - stroke)
        val brush = color?.let { Brush.linearGradient(listOf(it, it)) }
            ?: Brush.sweepGradient(listOf(StressColors.Accent, StressColors.AccentHot, StressColors.Accent), c)
        drawArc((color ?: StressColors.Accent).copy(alpha = 0.22f), START, SWEEP, false, arcTopLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        drawArc(brush, START, SWEEP * reading, false, arcTopLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        val angle = Math.toRadians((START + SWEEP * reading).toDouble())
        val tip = Offset(c.x + (cos(angle) * r * 0.6).toFloat(), c.y + (sin(angle) * r * 0.6).toFloat())
        drawLine(color ?: StressColors.Accent, c, tip, strokeWidth = stroke * 0.6f, cap = StrokeCap.Round)
        drawCircle(color ?: StressColors.Accent, radius = stroke * 0.8f, center = c)
    }
}

/**
 * A large instrument gauge: ticks, a cool-to-hot arc filled to [fraction]
 * (0..1, animated with a spring so the needle swings like a real one), a
 * glow at the needle's tip, and the reading in the middle.
 */
@Composable
fun HeroGauge(
    fraction: Float?,
    value: String,
    unit: String,
    caption: String,
    modifier: Modifier = Modifier,
    valueColor: Color = StressColors.Text,
) {
    val needle = remember { Animatable(0f) }
    LaunchedEffect(fraction) {
        needle.animateTo((fraction ?: 0f).coerceIn(0f, 1f), spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow))
    }
    val alpha by animateFloatAsState(if (fraction == null) 0.35f else 1f, tween(500), label = "alpha")
    Box(modifier.fillMaxWidth().aspectRatio(1.18f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(1f).offset(y = 14.dp)) {
            drawGauge(needle.value, alpha)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.offset(y = 18.dp)) {
            Text(value, style = NumberStyles.Hero, color = valueColor, textAlign = TextAlign.Center)
            Text(unit, style = MaterialTheme.typography.titleMedium, color = StressColors.TextDim, letterSpacing = 2.sp)
            Text(caption.upper(), style = MaterialTheme.typography.labelSmall, color = StressColors.TextFaint, letterSpacing = 1.6.sp)
        }
    }
}

private fun DrawScope.drawGauge(reading: Float, alpha: Float) {
    val r = size.minDimension / 2 * 0.92f
    val c = Offset(size.width / 2, size.height / 2)
    val stroke = r * 0.075f
    val arcTopLeft = Offset(c.x - r, c.y - r)
    val arcSize = Size(2 * r, 2 * r)
    // Track and ticks.
    drawArc(StressColors.SurfaceHighest, START, SWEEP, false, arcTopLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
    val ticks = 40
    for (i in 0..ticks) {
        val major = i % 5 == 0
        val a = Math.toRadians((START + SWEEP * i / ticks).toDouble())
        val outer = r - stroke * 1.2f
        val inner = outer - if (major) stroke * 1.6f else stroke * 0.8f
        val lit = i.toFloat() / ticks <= reading
        drawLine(
            if (lit) StressColors.Text.copy(alpha = 0.75f * alpha) else StressColors.TextFaint.copy(alpha = 0.45f),
            Offset(c.x + (cos(a) * inner).toFloat(), c.y + (sin(a) * inner).toFloat()),
            Offset(c.x + (cos(a) * outer).toFloat(), c.y + (sin(a) * outer).toFloat()),
            strokeWidth = if (major) 3f else 1.5f,
            cap = StrokeCap.Round,
        )
    }
    if (reading > 0.001f) {
        // Sweep gradient from cool to hot, rotated so its seam sits in the gap at the bottom.
        rotate(90f, c) {
            // The arc spans 60°..300° of the rotated circle: a sixth to five sixths of the sweep.
            val brush = Brush.sweepGradient(
                0.0f to StressColors.Cool,
                0.17f to StressColors.Cool,
                0.5f to StressColors.Accent,
                0.83f to StressColors.AccentHot,
                1.0f to StressColors.AccentHot,
                center = c,
            )
            drawArc(brush, START - 90f, SWEEP * reading, false, arcTopLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round), alpha = alpha)
        }
        val a = Math.toRadians((START + SWEEP * reading).toDouble())
        val tip = Offset(c.x + (cos(a) * r).toFloat(), c.y + (sin(a) * r).toFloat())
        drawCircle(
            Brush.radialGradient(listOf(StressColors.AccentHot.copy(alpha = 0.55f * alpha), Color.Transparent), tip, stroke * 3.2f),
            radius = stroke * 3.2f,
            center = tip,
        )
        drawCircle(Color.White.copy(alpha = alpha), radius = stroke * 0.42f, center = tip)
    }
}

/**
 * A ring that fills clockwise from the top to [progress] (0..1), for countdowns.
 */
@Composable
fun ProgressRing(progress: Float, modifier: Modifier = Modifier, color: Color = StressColors.Accent, thickness: Float = 0.07f) {
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), tween(400), label = "ring")
    Canvas(modifier) {
        val stroke = size.minDimension * thickness
        val inset = stroke / 2
        val topLeft = Offset(inset, inset)
        val arc = Size(size.width - stroke, size.height - stroke)
        drawArc(StressColors.SurfaceHighest, 0f, 360f, false, topLeft, arc, style = Stroke(stroke))
        drawArc(
            Brush.sweepGradient(listOf(color, StressColors.AccentHot, color)),
            -90f,
            360f * animated,
            false,
            topLeft,
            arc,
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
    }
}
