package dev.ozcan.stress.ui.run

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GppBad
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ozcan.stress.R
import dev.ozcan.stress.engine.SceneQuality
import dev.ozcan.stress.run.RunState
import dev.ozcan.stress.run.StressMode
import dev.ozcan.stress.safety.SafetyCheck
import dev.ozcan.stress.safety.SafetyLevel
import dev.ozcan.stress.ui.Format
import dev.ozcan.stress.ui.Labels
import dev.ozcan.stress.ui.LiveView
import dev.ozcan.stress.ui.components.ChartSeries
import dev.ozcan.stress.ui.components.EngineeringRow
import dev.ozcan.stress.ui.components.Hairline
import dev.ozcan.stress.ui.components.MeterBar
import dev.ozcan.stress.ui.components.MetricNumber
import dev.ozcan.stress.ui.components.SectionLabel
import dev.ozcan.stress.ui.components.StatusChip
import dev.ozcan.stress.ui.components.StopButton
import dev.ozcan.stress.ui.components.Temperatures
import dev.ozcan.stress.ui.components.TimeChart
import dev.ozcan.stress.ui.components.withInfinity
import dev.ozcan.stress.ui.context
import dev.ozcan.stress.ui.theme.ContentMaxWidth
import dev.ozcan.stress.ui.theme.NumberStyles
import dev.ozcan.stress.ui.theme.StressColors
import dev.ozcan.stress.ui.theme.StressType
import dev.ozcan.stress.ui.upper

/** The chart's own width from which the run's live chart fills the middle of the page (tablets). */
private val WIDE_CHART = 560.dp

/**
 * The run's simple page (DESIGN "What each simple page keeps", Run): a slim status line on top, the scene (or the
 * live chart) in the middle, and below it the progress, three large values, at most one notice line, the
 * engineering row and the stop button. [onWindow] reports the part of the screen a scene stays visible in.
 */
@Composable
internal fun RunReadings(
    mode: StressMode,
    quality: SceneQuality,
    state: RunState.Running,
    now: Long,
    live: LiveView?,
    history: LiveHistory,
    safety: SafetyCheck,
    safetyOn: Boolean,
    engineeringCaption: String,
    onWindow: (Rect) -> Unit,
    onEngineering: () -> Unit,
    onStop: () -> Unit,
) {
    val scene = mode.scene != null
    val elapsed = ((now - state.startedAtNanos) / 1e9).coerceAtLeast(0.0)
    val total = state.endsAtNanos?.let { (it - state.startedAtNanos) / 1e9 }
    val ground = if (scene) Modifier else Modifier.background(StressColors.Background)
    Column(Modifier.fillMaxSize().then(ground).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
        RunStatusLine(scene, elapsed, total, safety, safetyOn)
        if (scene) {
            SceneCorners(
                Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { onWindow(it.boundsInRoot()) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        } else {
            BoxWithConstraints(
                Modifier.weight(1f).fillMaxWidth().wrapContentWidth().widthIn(max = ContentMaxWidth).padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                // On a phone the chart keeps a calm height in the middle; on a tablet's tall page it takes the
                // whole middle, so the page does not float a ribbon between two empty bands.
                val most = if (maxWidth >= WIDE_CHART) maxHeight - 40.dp else 240.dp
                LiveChart(history, height = (maxHeight - 40.dp).coerceIn(96.dp, most.coerceAtLeast(96.dp)))
            }
        }
        // The bottom panel's ground spans the window under a scene; its content keeps a phone-like width.
        Column(Modifier.fillMaxWidth().background(StressColors.Background), horizontalAlignment = Alignment.CenterHorizontally) {
            Hairline()
            Column(
                Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth().navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
                Arrangement.spacedBy(12.dp),
            ) {
                if (total != null) MeterBar((elapsed / total).toFloat())
                RunValues(mode, live)
                RunNotice(mode, quality, live, safety, safetyOn)
                EngineeringRow(engineeringCaption, onEngineering)
                StopButton(stringResource(R.string.run_stop), onClick = onStop)
            }
        }
    }
}

/** Recording dot and timer on the left, the protection state on the right; over a scene, on a scrim. */
@Composable
private fun RunStatusLine(scene: Boolean, elapsed: Double, total: Double?, safety: SafetyCheck, safetyOn: Boolean) {
    val scrim = if (scene) {
        Modifier.background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.72f), Color.Transparent)))
    } else Modifier
    Box(Modifier.fillMaxWidth().then(scrim).statusBarsPadding(), contentAlignment = Alignment.TopCenter) {
        Row(
            Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
            Arrangement.spacedBy(8.dp),
            Alignment.CenterVertically,
        ) {
            RecordingDot()
            Text(
                withInfinity("${Format.clock(elapsed)} / ${total?.let(Format::clock) ?: stringResource(R.string.duration_endless)}"),
                style = NumberStyles.Small, color = StressColors.Text, maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            RunProtection(safety, safetyOn)
        }
    }
}

@Composable
private fun RecordingDot() {
    val description = stringResource(R.string.run_recording)
    val pulse = rememberInfiniteTransition(label = "recording")
    val alpha = pulse.animateFloat(1f, 0.4f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "dot")
    Box(
        Modifier.size(6.dp).semantics { contentDescription = description }
            .graphicsLayer { this.alpha = alpha.value }.background(StressColors.Accent, CircleShape),
    )
}

@Composable
private fun RunProtection(check: SafetyCheck, enabled: Boolean) {
    if (!enabled) {
        StatusChip(stringResource(R.string.safety_off_short), StressColors.Bad, icon = Icons.Rounded.GppBad)
    } else {
        // The finding itself is the notice line's; the chip only turns to its colour.
        val ok = check.level == SafetyLevel.Ok
        StatusChip(
            stringResource(R.string.protection_chip),
            safetyColor(check.level), icon = if (ok) Icons.Rounded.Shield else Icons.Rounded.Warning,
        )
    }
}

/** Power, chip temperature and the mode's own rate: frames for a scene, work for a burner or the CPU. */
@Composable
private fun RunValues(mode: StressMode, live: LiveView?) {
    val context = context()
    val watts = live?.watts.takeUnless { live?.plugged == true }
    val chip = live?.chipCelsius
    Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(12.dp)) {
        LargeValue(stringResource(R.string.home_live_power), Format.number(watts, 2), "W", StressColors.Accent)
        LargeValue(stringResource(R.string.home_live_chip), Format.number(chip, 0), "°C", Temperatures.chip(chip))
        when {
            mode.scene != null ->
                LargeValue(stringResource(R.string.chart_fps), Format.number(live?.gpu?.framesPerSecond, 0), "fps", StressColors.Cool)
            mode.usesGpu -> {
                val burner = live?.gpu?.burner
                val (number, unit) = Format.rateParts(live?.gpu?.rate, burner?.let { Labels.rateSymbol(context, it.unit) })
                LargeValue(stringResource(R.string.run_value_gpu), number, unit, StressColors.Cool)
            }
            else -> {
                val (number, unit) = Format.rateParts(live?.cpuRate, live?.unit?.let { Labels.rateSymbol(context, it) })
                LargeValue(stringResource(R.string.run_value_cpu), number, unit, StressColors.Cool)
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.LargeValue(label: String, value: String, unit: String?, color: Color) {
    Column(Modifier.weight(1f), Arrangement.spacedBy(4.dp)) {
        Text(label.upper(), style = StressType.Label, color = StressColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        MetricNumber(value, unit, NumberStyles.Large, color)
    }
}

/**
 * The one notice the simple page has room for, most urgent first: a safety finding, wrong results, a scene drawn
 * below the requested quality, then readings the phone did not give. The rest is in the engineering sheet.
 */
@Composable
private fun RunNotice(mode: StressMode, quality: SceneQuality, live: LiveView?, safety: SafetyCheck, safetyOn: Boolean) {
    val context = context()
    val errors = live?.let { it.errors + it.gpu.errors } ?: 0L
    val plugged = live?.plugged == true
    val draw = live?.gpu?.sceneDraw
    val drawn = draw?.let { drawnQualityText(mode.scene, quality, it.quality, it.scalePercent) }
    val third = when {
        mode.scene != null -> live?.gpu?.framesPerSecond
        mode.usesGpu -> live?.gpu?.rate
        else -> live?.cpuRate
    }
    val missing = live?.watts == null || plugged || live.chipCelsius == null || third == null
    val (text, color) = when {
        safetyOn && safety.level != SafetyLevel.Ok -> Labels.safetyFinding(context, safety) to safetyColor(safety.level)
        errors > 0 -> pluralStringResource(R.plurals.run_notice_errors, errors.toInt(), errors.toInt()) to StressColors.Bad
        drawn != null -> drawn to StressColors.Warn
        live != null && missing ->
            stringResource(if (plugged) R.string.run_unavailable_charging else R.string.run_unavailable) to StressColors.TextDim
        else -> return
    }
    Text(text, style = StressType.BodySmall, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun SceneCorners(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val arm = minOf(16.dp.toPx(), size.minDimension / 2)
        val stroke = 1.dp.toPx()
        val inset = stroke / 2
        for (dx in listOf(1, -1)) for (dy in listOf(1, -1)) {
            val x = if (dx == 1) inset else size.width - inset
            val y = if (dy == 1) inset else size.height - inset
            drawLine(StressColors.TextDim, Offset(x, y), Offset(x + dx * arm, y), stroke)
            drawLine(StressColors.TextDim, Offset(x, y), Offset(x, y + dy * arm), stroke)
        }
    }
}

/** Power and chip temperature over the last two minutes: the middle of a run without a scene, and the sheet's chart. */
@Composable
internal fun LiveChart(history: LiveHistory, height: Dp, modifier: Modifier = Modifier) {
    val points = history.readings
    val origin = points.firstOrNull()?.second ?: 0
    val seconds = remember(points) { points.map { (it.second - origin).toDouble() } }
    val watts = remember(points) { points.map { it.watts } }
    val hasChip = remember(points) { points.any { it.chipCelsius != null } }
    val heat = remember(points, hasChip) { points.map { if (hasChip) it.chipCelsius else it.batteryCelsius } }
    val temperatures = remember(heat) { heat.filterNotNull() }
    val powerLabel = stringResource(R.string.chart_power)
    val heatLabel = stringResource(if (hasChip) R.string.run_chart_chip else R.string.temp_battery)
    val series = buildList {
        if (history.peakWatts != null && watts.any { it != null }) add(ChartSeries(powerLabel, StressColors.Accent, watts, "W"))
        if (temperatures.isNotEmpty()) {
            val range = if (isNotEmpty()) (temperatures.min() - 5)..(temperatures.max() + 5) else null
            add(ChartSeries(heatLabel, StressColors.Cool, heat, "°C", range))
        }
    }
    Column(modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
        if (series.isEmpty()) {
            SectionLabel(stringResource(R.string.run_live_chart))
            Text(Format.MISSING, style = StressType.Code, color = StressColors.TextDim)
        } else {
            SectionLabel(series.first().name, caption = series.first().unit)
            TimeChart(
                seconds, series, series.first().unit.orEmpty(), height = height,
                yMin = 0.0.takeIf { series.first().unit == "W" },
                timeLabel = { Format.clock(it + origin) },
            )
        }
    }
}
