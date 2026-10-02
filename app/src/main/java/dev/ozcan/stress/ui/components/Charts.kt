package dev.ozcan.stress.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ozcan.stress.ui.Format
import dev.ozcan.stress.ui.theme.StressColors
import dev.ozcan.stress.ui.theme.StressFonts
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

data class ChartSeries(val name: String, val color: Color, val values: List<Double?>)

/** The step [niceTicks] uses: a round number about a [count]th of the range. */
fun niceStep(min: Double, max: Double, count: Int = 4): Double {
    if (!min.isFinite() || !max.isFinite() || max <= min) return 1.0
    val raw = (max - min) / count
    val magnitude = 10.0.pow(floor(log10(raw)))
    return listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * magnitude }.first { it >= raw }
}

/** Tick positions for an axis: round numbers covering [min, max], about [count] of them. */
fun niceTicks(min: Double, max: Double, count: Int = 4): List<Double> {
    if (!min.isFinite() || !max.isFinite() || max <= min) return listOf(min)
    val step = niceStep(min, max, count)
    val first = ceil(min / step) * step
    return generateSequence(first) { it + step }.takeWhile { it <= max + step * 1e-9 }.toList()
}

/** A tick's label with as many decimals as the step needs: steps of 2.5 show one, whole steps none. */
fun tickLabel(tick: Double, step: Double): String {
    val decimals = when {
        step >= 1.0 && step == floor(step) -> 0
        step >= 0.1 -> 1
        else -> 2
    }
    return Format.number(tick, decimals)
}

/** Steps the time axis may take, in seconds. */
private val TIME_STEPS = listOf(10.0, 15.0, 30.0, 60.0, 120.0, 300.0, 600.0, 900.0, 1800.0, 3600.0)

/**
 * The step between x labels: the shortest round step that keeps at most
 * [maxLabels] of them over [spanSeconds] (labels must not run into each other).
 */
fun timeStep(spanSeconds: Double, maxLabels: Int = 7): Double =
    TIME_STEPS.firstOrNull { spanSeconds / it <= maxLabels.coerceAtLeast(1) } ?: TIME_STEPS.last()

/**
 * A time chart: one line per series against [seconds], gaps (nulls) break
 * the line. The first series gets a gradient fill. Touching and dragging
 * shows the values at that moment. The lines draw in from the left the first
 * time the chart is shown.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TimeChart(
    seconds: List<Double>,
    series: List<ChartSeries>,
    unit: String,
    modifier: Modifier = Modifier,
    height: Dp = 190.dp,
    yMin: Double? = null,
    yMax: Double? = null,
    decimals: Int = 1,
    timeLabel: (Double) -> String = { Format.elapsedShort(it) },
) {
    val measurer = rememberTextMeasurer()
    val axisStyle = TextStyle(color = StressColors.TextFaint, fontSize = 10.sp, fontFamily = StressFonts.Mono)
    val tipStyle = TextStyle(color = StressColors.Text, fontSize = 11.sp, fontFamily = StressFonts.Mono)
    val values = series.flatMap { it.values.filterNotNull() }
    val low = yMin ?: values.minOrNull() ?: 0.0
    val high = yMax ?: values.maxOrNull() ?: 1.0
    val pad = if (high > low) (high - low) * 0.1 else 1.0
    val bottom = if (yMin != null) low else low - pad
    val top = if (yMax != null) high else high + pad
    val span = seconds.lastOrNull()?.takeIf { it > 0 } ?: 1.0
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) { reveal.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    var touch by remember { mutableStateOf<Float?>(null) }

    Column(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .pointerInput(seconds) {
                    detectTapGestures(onPress = { offset ->
                        touch = offset.x
                        tryAwaitRelease()
                        touch = null
                    })
                }
                .pointerInput(seconds) {
                    detectHorizontalDragGestures(
                        onDragStart = { touch = it.x },
                        onDragEnd = { touch = null },
                        onDragCancel = { touch = null },
                        onHorizontalDrag = { change, _ -> touch = change.position.x },
                    )
                },
        ) {
            val left = 40.dp.toPx()
            val bottomPad = 20.dp.toPx()
            val topPad = 6.dp.toPx()
            val w = size.width - left
            val h = size.height - bottomPad - topPad
            fun x(t: Double) = left + (t / span).toFloat() * w
            fun y(v: Double) = topPad + h - ((v - bottom) / (top - bottom)).toFloat() * h

            // Grid and y labels.
            val yStep = niceStep(bottom, top)
            for (tick in niceTicks(bottom, top)) {
                val py = y(tick)
                drawLine(
                    StressColors.Outline,
                    Offset(left, py),
                    Offset(size.width, py),
                    strokeWidth = 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                )
                drawText(measurer, tickLabel(tick, yStep), Offset(0f, py - 7.sp.toPx()), axisStyle)
            }
            // x labels, as many as fit with room between them.
            val labelWidth = measurer.measure(timeLabel(span), axisStyle).size.width + 14.dp.toPx()
            val step = timeStep(span, (w / labelWidth).toInt())
            var t = 0.0
            while (t <= span + 1e-9) {
                val px = x(t)
                val text = timeLabel(t)
                val measured = measurer.measure(text, axisStyle)
                val lx = (px - measured.size.width / 2).coerceIn(left - 4f, size.width - measured.size.width)
                drawText(measured, topLeft = Offset(lx, topPad + h + 5.dp.toPx()))
                t += step
            }

            clipRect(left = 0f, top = 0f, right = left + w * reveal.value, bottom = size.height) {
                series.forEachIndexed { index, s ->
                    val line = Path()
                    val fill = Path()
                    var drawing = false
                    var startX = 0f
                    var lastX = 0f
                    // Only a lone line gets a fill: under several it muddies the others.
                    val filled = index == 0 && series.size == 1
                    fun closeFill() {
                        if (filled && drawing) {
                            fill.lineTo(lastX, topPad + h)
                            fill.lineTo(startX, topPad + h)
                            fill.close()
                        }
                    }
                    s.values.forEachIndexed { i, v ->
                        val time = seconds.getOrNull(i) ?: return@forEachIndexed
                        if (v == null) {
                            closeFill()
                            drawing = false
                        } else {
                            val px = x(time)
                            val py = y(v)
                            if (!drawing) {
                                line.moveTo(px, py)
                                if (filled) fill.moveTo(px, py)
                                startX = px
                                drawing = true
                            } else {
                                line.lineTo(px, py)
                                if (filled) fill.lineTo(px, py)
                            }
                            lastX = px
                        }
                    }
                    closeFill()
                    if (filled) {
                        drawPath(fill, Brush.verticalGradient(listOf(s.color.copy(alpha = 0.32f), s.color.copy(alpha = 0.0f)), topPad, topPad + h))
                    }
                    drawPath(line, s.color, style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }

            // The touched moment: a line and the values there.
            val tx = touch
            if (tx != null && seconds.isNotEmpty()) {
                val time = ((tx - left) / w * span).coerceIn(0.0, span)
                val i = seconds.indices.minBy { abs(seconds[it] - time) }
                val px = x(seconds[i])
                drawLine(StressColors.TextDim, Offset(px, topPad), Offset(px, topPad + h), strokeWidth = 1.5f)
                val lines = buildList {
                    add(timeLabel(seconds[i]))
                    series.forEach { s ->
                        val v = s.values.getOrNull(i)
                        add("${s.name}  ${if (v == null) Format.MISSING else Format.number(v, decimals)} $unit")
                    }
                }
                series.forEach { s ->
                    s.values.getOrNull(i)?.let { v -> drawCircle(s.color, 4.dp.toPx(), Offset(px, y(v))) }
                }
                val measured = lines.map { measurer.measure(it, tipStyle) }
                val boxW = measured.maxOf { it.size.width } + 16.dp.toPx()
                val boxH = measured.sumOf { it.size.height } + 12.dp.toPx()
                val bx = if (px + 12.dp.toPx() + boxW < size.width) px + 12.dp.toPx() else px - 12.dp.toPx() - boxW
                val by = topPad
                drawRoundRect(StressColors.SurfaceHighest.copy(alpha = 0.95f), Offset(bx, by), Size(boxW, boxH), CornerRadius(8.dp.toPx()))
                var ly = by + 6.dp.toPx()
                measured.forEachIndexed { k, m ->
                    val color = if (k == 0) StressColors.TextDim else series[k - 1].color
                    drawText(m, color = color, topLeft = Offset(bx + 8.dp.toPx(), ly))
                    ly += m.size.height
                }
            }
        }
        if (series.size > 1) {
            FlowRow(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                series.forEach { s ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(width = 14.dp, height = 3.dp).background(s.color, CircleShape))
                        Text(s.name, style = MaterialTheme.typography.labelSmall, color = StressColors.TextDim)
                    }
                }
            }
        }
    }
}

/** A bare line of recent values with a soft fill, for live displays. */
@Composable
fun Sparkline(values: List<Double>, color: Color, modifier: Modifier = Modifier, minSpan: Double = 0.5) {
    Canvas(modifier = modifier) {
        if (values.size < 2) return@Canvas
        val low = values.min()
        val high = values.max().coerceAtLeast(low + minSpan)
        val line = Path()
        val fill = Path()
        values.forEachIndexed { i, v ->
            val px = i / (values.size - 1f) * size.width
            val py = size.height - ((v - low) / (high - low)).toFloat() * size.height * 0.9f - size.height * 0.05f
            if (i == 0) {
                line.moveTo(px, py)
                fill.moveTo(px, size.height)
                fill.lineTo(px, py)
            } else {
                line.lineTo(px, py)
                fill.lineTo(px, py)
            }
        }
        fill.lineTo(size.width, size.height)
        fill.close()
        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.3f), Color.Transparent)))
        drawPath(line, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
