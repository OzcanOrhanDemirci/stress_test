package dev.ozcan.stress.ui.compare

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.ozcan.stress.AppGraph
import dev.ozcan.stress.R
import dev.ozcan.stress.analysis.Comparison
import dev.ozcan.stress.engine.SceneQuality
import dev.ozcan.stress.graph
import dev.ozcan.stress.run.RunRecord
import dev.ozcan.stress.ui.Dates
import dev.ozcan.stress.ui.Format
import dev.ozcan.stress.ui.Labels
import dev.ozcan.stress.ui.components.CenteredBox
import dev.ozcan.stress.ui.components.ChartSeries
import dev.ozcan.stress.ui.components.CodeTag
import dev.ozcan.stress.ui.components.Hairline
import dev.ozcan.stress.ui.components.Panel
import dev.ozcan.stress.ui.components.SectionLabel
import dev.ozcan.stress.ui.components.SegmentedControl
import dev.ozcan.stress.ui.components.Stack
import dev.ozcan.stress.ui.components.TimeChart
import dev.ozcan.stress.ui.components.TopBar
import dev.ozcan.stress.ui.context
import dev.ozcan.stress.ui.theme.StressColors
import dev.ozcan.stress.ui.theme.StressType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** The same run colours identify the records, table columns and chart lines. */
private val ColorA = StressColors.Accent
private val ColorB = StressColors.Cool

class CompareViewModel(graph: AppGraph, first: String, second: String) : ViewModel() {
    private val _runs = MutableStateFlow<Pair<RunRecord?, RunRecord?>?>(null)
    val runs: StateFlow<Pair<RunRecord?, RunRecord?>?> = _runs.asStateFlow()

    init {
        viewModelScope.launch {
            _runs.value = withContext(Dispatchers.IO) { graph.runs.load(first) to graph.runs.load(second) }
        }
    }
}

@Composable
fun CompareScreen(first: String, second: String, onBack: () -> Unit) {
    val graph = context().graph
    val model: CompareViewModel = viewModel { CompareViewModel(graph, first, second) }
    val runs by model.runs.collectAsStateWithLifecycle()
    val pair = runs
    val a = pair?.first
    val b = pair?.second

    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        TopBar(
            title = stringResource(R.string.compare_title),
            modifier = Modifier.statusBarsPadding(),
            onBack = onBack,
        )
        when {
            first == second -> Refusal(R.string.compare_same_run)
            pair == null -> CenteredBox { CircularProgressIndicator(color = StressColors.Accent) }
            a == null || b == null -> Refusal(R.string.compare_run_missing)
            else -> Body(a, b)
        }
    }
}

@Composable
private fun Refusal(@StringRes reason: Int) {
    CenteredBox(Modifier.padding(16.dp)) {
        Panel(Modifier.fillMaxWidth()) {
            Text(stringResource(reason), style = StressType.Body)
        }
    }
}

@Composable
private fun Body(a: RunRecord, b: RunRecord) {
    val rows = remember(a, b) { Comparison.rows(a, b) }
    Stack(
        Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RunPanel(a, ColorA, "A", Modifier.weight(1f))
            RunPanel(b, ColorB, "B", Modifier.weight(1f))
        }

        SectionLabel(stringResource(R.string.compare_metrics))
        Panel(Modifier.fillMaxWidth(), padding = 12.dp) {
            val peakDifference = rows.firstOrNull { it.metric == Comparison.Metric.PeakPower }?.difference
            val summary = if (peakDifference == null) {
                stringResource(R.string.compare_peak_unavailable)
            } else {
                val signed = (if (peakDifference > 0) "+" else "") + Format.watts(peakDifference)
                stringResource(R.string.compare_peak_difference, signed)
            }
            Text(summary, style = StressType.Body, modifier = Modifier.padding(bottom = 8.dp))
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Spacer(Modifier.weight(1.4f))
                Value("A", Modifier.weight(1f), ColorA)
                Value("B", Modifier.weight(1f), ColorB)
                Value("Δ", Modifier.weight(1f), StressColors.TextDim)
            }
            rows.forEach { row ->
                Hairline()
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(metricName(row.metric)),
                        style = StressType.BodySmall,
                        color = StressColors.TextDim,
                        modifier = Modifier.weight(1.4f),
                    )
                    Value(format(row.metric, row.a), Modifier.weight(1f))
                    Value(format(row.metric, row.b), Modifier.weight(1f))
                    val delta = row.change
                    val color = when {
                        delta == null || abs(delta) < 0.02 -> StressColors.TextDim
                        (delta > 0) == row.metric.higherIsBetter -> StressColors.Good
                        else -> StressColors.Warn
                    }
                    val change = delta?.let { (if (it > 0) "+" else "") + Format.percent(it) } ?: Format.MISSING
                    Value(change, Modifier.weight(1f), color)
                }
            }
            Hairline()
            Text(
                stringResource(R.string.compare_hint),
                style = StressType.BodySmall,
                color = StressColors.TextDim,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        Charts(a, b)
    }
}

@Composable
private fun RunPanel(record: RunRecord, color: Color, letter: String, modifier: Modifier) {
    Panel(modifier, padding = 12.dp) {
        Stack(spacing = 4) {
            CodeTag(letter, color)
            Text(Labels.recordModeName(context(), record), style = StressType.Heading)
            SceneQuality.entries.firstOrNull { it.name == record.quality }?.let {
                Text(stringResource(Labels.quality(it)), style = StressType.BodySmall, color = StressColors.TextDim)
            }
            Text(Dates.short(record.startedAtMillis), style = StressType.Code, color = StressColors.TextDim)
            Text(Format.clock(record.loadSeconds), style = StressType.Code, color = StressColors.TextDim)
        }
    }
}

@Composable
private fun Value(text: String, modifier: Modifier, color: Color = StressColors.Text) {
    Text(text, style = StressType.Code, color = color, textAlign = TextAlign.End, modifier = modifier.padding(start = 4.dp))
}

private enum class Reading(
    @param:StringRes val label: Int,
    @param:StringRes val title: Int,
    val unit: String,
    val yMin: Double? = null,
    val yMax: Double? = null,
    val values: (RunRecord) -> List<Double?>,
) {
    Power(R.string.chart_power, R.string.chart_power, "W", values = { it.series.watts }),
    Chip(R.string.compare_tab_chip, R.string.compare_chart_chip, "°C", values = Comparison::chipSeries),
    Battery(R.string.temp_battery, R.string.compare_chart_battery, "°C", values = { it.series.batteryCelsius }),
    Cpu(R.string.temp_cpu, R.string.compare_chart_cpu, "%", 0.0, 105.0, { it.series.cpuRelative.map { v -> v?.times(100) } }),
    Fps(R.string.chart_fps, R.string.chart_fps, "fps", 0.0, values = { it.series.fps }),
}

@Composable
private fun Charts(a: RunRecord, b: RunRecord) {
    val readings = remember(a, b) {
        Reading.entries.mapNotNull { reading ->
            val va = reading.values(a)
            val vb = reading.values(b)
            val hasA = va.any { it != null }
            val hasB = vb.any { it != null }
            // Relative CPU curves are only shown when both runs captured CPU work.
            val available = if (reading == Reading.Cpu) hasA && hasB else hasA || hasB
            if (available) reading to Comparison.align(a.series.seconds, va, b.series.seconds, vb) else null
        }.toMap()
    }
    val options = readings.keys.toList()
    if (options.isEmpty()) return
    var selected by rememberSaveable { mutableStateOf(options.first()) }
    val reading = selected.takeIf { it in options } ?: options.first()
    val (seconds, first, second) = readings.getValue(reading)
    SectionLabel(stringResource(R.string.result_charts))
    Panel(Modifier.fillMaxWidth(), padding = 12.dp) {
        Stack(spacing = 8) {
            if (options.size > 1) {
                SegmentedControl(options, reading, { selected = it }, label = { stringResource(it.label) })
            }
            Text(stringResource(reading.title), style = StressType.Heading)
            TimeChart(
                seconds,
                listOf(ChartSeries("A", ColorA, first), ChartSeries("B", ColorB, second)),
                reading.unit,
                yMin = reading.yMin,
                yMax = reading.yMax,
            )
        }
    }
}

private fun metricName(metric: Comparison.Metric): Int = when (metric) {
    Comparison.Metric.PeakPower -> R.string.result_peak
    Comparison.Metric.SustainedPower -> R.string.result_sustained
    Comparison.Metric.MeanPower -> R.string.result_mean
    Comparison.Metric.HottestChip -> R.string.result_hottest
    Comparison.Metric.HottestBattery -> R.string.compare_battery_max
    Comparison.Metric.CpuRate -> R.string.result_cpu_perf
    Comparison.Metric.GpuRate -> R.string.result_gpu_perf
    Comparison.Metric.Fps -> R.string.result_fps
    Comparison.Metric.Stability -> R.string.result_stability
    Comparison.Metric.FirstThrottle -> R.string.compare_first_throttle
    Comparison.Metric.Errors -> R.string.result_errors
}

private fun format(metric: Comparison.Metric, value: Double?): String {
    if (value == null) return Format.MISSING
    return when (metric) {
        Comparison.Metric.PeakPower, Comparison.Metric.SustainedPower, Comparison.Metric.MeanPower -> Format.watts(value)
        Comparison.Metric.HottestChip, Comparison.Metric.HottestBattery -> Format.celsius(value)
        Comparison.Metric.CpuRate, Comparison.Metric.GpuRate -> Format.scaled(value).let { (v, p) -> "${Format.number(v, 1)} $p".trimEnd() }
        Comparison.Metric.Fps -> Format.number(value, 1)
        Comparison.Metric.Stability -> Format.percent(value)
        Comparison.Metric.FirstThrottle -> Format.clock(value)
        Comparison.Metric.Errors -> value.toLong().toString()
    }
}
