package dev.ozcan.stress.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ozcan.stress.ui.Format
import dev.ozcan.stress.ui.theme.StressColors
import dev.ozcan.stress.ui.theme.StressType
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** A sampled line. Set [unit] and [range] to plot a secondary reading on its own scale. */
data class ChartSeries(
    val name: String,
    val color: Color,
    val values: List<Double?>,
    val unit: String? = null,
    val range: ClosedFloatingPointRange<Double>? = null,
)

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

private val AxisWidth = 44.dp
private val AxisEnd = 12.dp

/**
 * A time plot over [seconds], with the axis for the primary series and null readings drawn as gaps.
 * Touch or drag to inspect a sample in the strip above the plot. Use [SegmentedControl] to
 * switch series above the chart, and a [ChartSeries.range] for an independent secondary scale.
 */
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
    timeLabel: (Double) -> String = { Format.elapsedShort(it).padStart(5, '0') },
    xEnd: Double? = null,
    xStep: Double? = null,
    xReadoutPrefix: String = "t = ",
) {
    val measurer = rememberTextMeasurer()
    val axisStyle = StressType.Code.copy(color = StressColors.TextFaint)
    val primaryRange = remember(series, yMin, yMax) {
        series.firstOrNull()?.range ?: chartRange(series.filter { it.range == null }, yMin, yMax)
    }
    val span = xEnd ?: seconds.lastOrNull()?.takeIf { it > 0 } ?: 1.0
    var cursor by remember(seconds, series) { mutableIntStateOf(-1) }
    val readout = cursor.takeIf { it in seconds.indices } ?: seconds.lastIndex

    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (readout >= 0) {
            Row(
                Modifier.fillMaxWidth().background(StressColors.SurfaceLow).padding(horizontal = 8.dp, vertical = 4.dp),
                Arrangement.spacedBy(8.dp), Alignment.CenterVertically,
            ) {
                Text("$xReadoutPrefix${timeLabel(seconds[readout])}", style = StressType.Code, color = StressColors.TextDim)
                FlowRow(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                ) {
                    series.forEachIndexed { index, line ->
                        if (index > 0) Text("//", style = StressType.Code, color = StressColors.TextDim)
                        val value = Format.number(line.values.getOrNull(readout), decimals)
                        val reading = if (value == Format.MISSING) value else "$value ${line.unit ?: unit}".trimEnd()
                        Text(reading, style = StressType.Code, color = line.color)
                    }
                }
            }
        }
        Canvas(
            Modifier.fillMaxWidth().height(height).background(StressColors.SurfaceLow)
                .pointerInput(seconds, series) {
                    if (seconds.isEmpty()) return@pointerInput
                    fun select(x: Float) {
                        val width = (size.width - AxisWidth.toPx() - AxisEnd.toPx()).coerceAtLeast(1f)
                        val time = ((x - AxisWidth.toPx()) / width).coerceIn(0f, 1f) * span
                        cursor = seconds.indices.minBy { abs(seconds[it] - time) }
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        select(down.position.x)
                        try {
                            val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                            if (drag != null) {
                                select(drag.position.x)
                                horizontalDrag(drag.id) { change ->
                                    select(change.position.x)
                                    change.consume()
                                }
                            }
                        } finally {
                            cursor = -1
                        }
                    }
                },
        ) {
            val left = AxisWidth.toPx()
            val right = size.width - AxisEnd.toPx()
            val top = 12.dp.toPx()
            val bottom = size.height - 24.dp.toPx()
            val width = right - left
            val plotHeight = bottom - top
            if (width <= 0 || plotHeight <= 0) return@Canvas

            fun x(time: Double) = left + (time / span).toFloat() * width
            fun y(value: Double, range: ClosedFloatingPointRange<Double> = primaryRange): Float {
                val spanY = (range.endInclusive - range.start).takeIf { it > 0 } ?: 1.0
                return bottom - ((value - range.start) / spanY).toFloat() * plotHeight
            }
            val yStep = niceStep(primaryRange.start, primaryRange.endInclusive)
            val dashes = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
            for (tick in niceTicks(primaryRange.start, primaryRange.endInclusive)) {
                val py = y(tick)
                drawLine(StressColors.Outline, Offset(left, py), Offset(right, py), 1.dp.toPx(), pathEffect = dashes)
                val label = measurer.measure(tickLabel(tick, yStep), axisStyle)
                drawText(label, topLeft = Offset((left - label.size.width - 4.dp.toPx()).coerceAtLeast(0f), py - label.size.height / 2f))
            }

            val labelWidth = measurer.measure(timeLabel(span), axisStyle).size.width + 12.dp.toPx()
            val step = xStep ?: timeStep(span, (width / labelWidth).toInt())
            val lastRegular = floor(span / step) * step
            val ticks = generateSequence(0.0) { it + step }
                .takeWhile { it < span && (it < lastRegular || x(span) - x(it) >= labelWidth) }
                .toList() + span
            for (time in ticks) {
                val label = measurer.measure(timeLabel(time), axisStyle)
                val lx = (x(time) - label.size.width / 2f).coerceIn(0f, (size.width - label.size.width).coerceAtLeast(0f))
                drawText(label, topLeft = Offset(lx, bottom + 4.dp.toPx()))
            }

            clipRect(left - 2.dp.toPx(), top - 2.dp.toPx(), right + 2.dp.toPx(), bottom + 2.dp.toPx()) {
                series.forEachIndexed { index, line ->
                    val path = Path()
                    val fill = Path()
                    val range = line.range ?: primaryRange
                    val filled = index == 0 && line.color == StressColors.Accent
                    var startX: Float? = null
                    var lastX = 0f
                    var firstY = 0f
                    fun finishSegment() {
                        val start = startX ?: return
                        if (start == lastX && xEnd != null) drawCircle(line.color, 2.dp.toPx(), Offset(start, firstY))
                        if (filled) {
                            fill.lineTo(lastX, bottom)
                            fill.lineTo(start, bottom)
                            fill.close()
                        }
                        startX = null
                    }
                    for (i in 0 until minOf(seconds.size, line.values.size)) {
                        val value = line.values[i]?.takeIf { it.isFinite() }
                        if (value == null) {
                            finishSegment()
                            continue
                        }
                        val px = x(seconds[i])
                        val py = y(value, range)
                        if (startX == null) {
                            path.moveTo(px, py)
                            if (filled) fill.moveTo(px, py)
                            startX = px
                            firstY = py
                        } else {
                            path.lineTo(px, py)
                            if (filled) fill.lineTo(px, py)
                        }
                        lastX = px
                    }
                    finishSegment()
                    if (filled) {
                        drawPath(fill, Brush.verticalGradient(listOf(line.color.copy(alpha = 0.22f), Color.Transparent), top, bottom))
                    }
                    drawPath(path, line.color, style = Stroke(
                        width = if (index == 0) 2.dp.toPx() else 1.5.dp.toPx(),
                        join = StrokeJoin.Round, pathEffect = dashes.takeIf { index > 0 },
                    ))
                }
            }
            if (cursor in seconds.indices) {
                val px = x(seconds[cursor])
                drawLine(StressColors.TextDim, Offset(px, top), Offset(px, bottom), 1.dp.toPx())
                series.forEach { line ->
                    line.values.getOrNull(cursor)?.takeIf { it.isFinite() }?.let { value ->
                        val py = y(value, line.range ?: primaryRange)
                        if (py in top..bottom) drawCircle(line.color, 3.dp.toPx(), Offset(px, py))
                    }
                }
            }
        }
        if (series.size > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                series.forEach { line ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.size(width = 12.dp, height = 2.dp).background(line.color))
                        Text(line.name, style = StressType.Label, color = StressColors.TextDim)
                    }
                }
            }
        }
    }
}

private fun chartRange(series: List<ChartSeries>, yMin: Double?, yMax: Double?): ClosedFloatingPointRange<Double> {
    val values = series.flatMap { line -> line.values.filterNotNull().filter { it.isFinite() } }
    val low = yMin ?: values.minOrNull() ?: 0.0
    val high = yMax ?: values.maxOrNull() ?: 1.0
    val padding = if (high > low) (high - low) * 0.1 else 1.0
    val bottom = yMin ?: low - padding
    val top = yMax ?: high + padding
    return bottom..top.coerceAtLeast(bottom + 1e-9)
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
        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.22f), Color.Transparent)))
        drawPath(line, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
