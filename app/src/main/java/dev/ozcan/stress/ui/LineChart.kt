package dev.ozcan.stress.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ozcan.stress.ui.theme.StressColors
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

data class ChartSeries(val name: String, val color: Color, val values: List<Double?>)

/** Tick positions for an axis: round numbers covering [min, max], about [count] of them. */
fun niceTicks(min: Double, max: Double, count: Int = 4): List<Double> {
    if (!min.isFinite() || !max.isFinite() || max <= min) return listOf(min)
    val raw = (max - min) / count
    val magnitude = 10.0.pow(floor(log10(raw)))
    val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * magnitude }.first { it >= raw }
    val first = ceil(min / step) * step
    return generateSequence(first) { it + step }.takeWhile { it <= max + step * 1e-9 }.toList()
}

/**
 * A time chart: one line per series against [seconds]. Gaps (nulls) break the
 * line. The y range covers the data with a little air; x ticks fall on whole
 * minutes (or ten seconds for short runs).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LineChart(
    seconds: List<Double>,
    series: List<ChartSeries>,
    unit: String,
    modifier: Modifier = Modifier,
    height: Dp = 180.dp,
    yMin: Double? = null,
    yMax: Double? = null,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = StressColors.TextDim, fontSize = 10.sp)
    val values = series.flatMap { it.values.filterNotNull() }
    val low = yMin ?: values.minOrNull() ?: 0.0
    val high = yMax ?: values.maxOrNull() ?: 1.0
    val pad = if (high > low) (high - low) * 0.08 else 1.0
    val bottom = if (yMin != null) low else low - pad
    val top = if (yMax != null) high else high + pad
    val span = seconds.lastOrNull() ?: 0.0
    val xStep = if (span > 180) 60.0 else 10.0

    Column(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxWidth().height(height)) {
            val left = 44.dp.toPx()
            val bottomPad = 18.dp.toPx()
            val w = size.width - left
            val h = size.height - bottomPad
            fun x(t: Double) = left + (if (span > 0) (t / span).toFloat() else 0f) * w
            fun y(v: Double) = h - ((v - bottom) / (top - bottom)).toFloat() * h

            for (tick in niceTicks(bottom, top)) {
                val py = y(tick)
                drawLine(StressColors.Outline, Offset(left, py), Offset(size.width, py), strokeWidth = 1f)
                val label = if (abs(tick) >= 100 || tick == floor(tick)) Format.number(tick, 0) else Format.number(tick, 1)
                drawText(measurer, "$label $unit", Offset(0f, py - 7.sp.toPx()), labelStyle)
            }
            var t = 0.0
            while (t <= span + 1e-9) {
                val px = x(t)
                drawLine(StressColors.Outline, Offset(px, h), Offset(px, h + 4.dp.toPx()), strokeWidth = 1f)
                val label = if (xStep >= 60) "${(t / 60).toInt()} dk" else "${t.toInt()} sn"
                drawText(measurer, label, Offset(px - 8.dp.toPx(), h + 4.dp.toPx()), labelStyle)
                t += xStep
            }

            for (s in series) {
                val path = Path()
                var drawing = false
                s.values.forEachIndexed { i, v ->
                    val time = seconds.getOrNull(i) ?: return@forEachIndexed
                    if (v == null) {
                        drawing = false
                    } else if (!drawing) {
                        path.moveTo(x(time), y(v))
                        drawing = true
                    } else {
                        path.lineTo(x(time), y(v))
                    }
                }
                drawPath(path, s.color, style = Stroke(width = 2.dp.toPx()))
            }
        }
        if (series.size > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                series.forEach { s ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.size(8.dp).background(s.color, CircleShape))
                        Text(s.name, style = MaterialTheme.typography.labelSmall, color = StressColors.TextDim)
                    }
                }
            }
        }
    }
}

/** A bare line of recent values, for the live overlay. */
@Composable
fun Sparkline(values: List<Double>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        if (values.size < 2) return@Canvas
        val low = values.min()
        val high = values.max().coerceAtLeast(low + 0.5)
        val path = Path()
        values.forEachIndexed { i, v ->
            val px = i / (values.size - 1f) * size.width
            val py = size.height - ((v - low) / (high - low)).toFloat() * size.height
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
    }
}
