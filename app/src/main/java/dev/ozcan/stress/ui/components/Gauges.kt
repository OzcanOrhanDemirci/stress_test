package dev.ozcan.stress.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ozcan.stress.ui.Format
import dev.ozcan.stress.ui.theme.NumberStyles
import dev.ozcan.stress.ui.theme.StressColors
import dev.ozcan.stress.ui.theme.StressMotion
import dev.ozcan.stress.ui.theme.StressType
import dev.ozcan.stress.ui.upper

/** A 240° stability arc. [fraction] is 0–1, or null when stability could not be measured. */
@Composable
fun StabilityArc(fraction: Double?, label: String, modifier: Modifier = Modifier) {
    val reading = fraction?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0)
    Column(modifier, Arrangement.spacedBy(4.dp), Alignment.CenterHorizontally) {
        Box(Modifier.size(92.dp), contentAlignment = Alignment.Center) {
            InstrumentArc(reading?.toFloat() ?: 0f, StressColors.Cool, Modifier.fillMaxSize())
            Text(Format.percent(reading, 1), style = NumberStyles.Medium, color = StressColors.Cool)
        }
        Text(label.upper(), style = StressType.Label, color = StressColors.TextDim, textAlign = TextAlign.Center)
    }
}

@Composable
private fun InstrumentArc(fraction: Float, color: Color, modifier: Modifier, strokeWidth: Float = 6f) {
    val fill by animateFloatAsState(fraction.coerceIn(0f, 1f), StressMotion.state(), label = "arc")
    Canvas(modifier) {
        val stroke = strokeWidth.dp.toPx()
        val diameter = (size.minDimension - stroke).coerceAtLeast(0f)
        val start = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
        val bounds = Size(diameter, diameter)
        drawArc(StressColors.SurfaceHighest, 150f, 240f, false, start, bounds, style = Stroke(stroke))
        drawArc(color, 150f, 240f * fill, false, start, bounds, style = Stroke(stroke))
    }
}

/** The flat brand mark for screens whose layout already reserves an icon slot. */
@Composable
fun GaugeMark(modifier: Modifier = Modifier, color: Color? = null) {
    if (color == null) {
        Box(modifier, contentAlignment = Alignment.Center) { WordmarkBar() }
    } else {
        InstrumentArc(0.15f, color, modifier)
    }
}

/** Progress arc for the existing baseline countdown. */
@Composable
fun ProgressRing(progress: Float, modifier: Modifier = Modifier) {
    InstrumentArc(progress, StressColors.Cool, modifier)
}

/** Arc with a live reading for the existing run display. */
@Composable
fun HeroGauge(
    fraction: Float?,
    value: String,
    unit: String,
    caption: String,
    modifier: Modifier = Modifier,
    valueColor: Color = StressColors.Text,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
            InstrumentArc(fraction ?: 0f, StressColors.Cool, Modifier.fillMaxSize())
            MetricNumber(value, unit, NumberStyles.Large, valueColor)
        }
        Text(caption.upper(), style = StressType.Label, color = StressColors.TextDim, textAlign = TextAlign.Center)
    }
}
