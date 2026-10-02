package dev.ozcan.stress.ui.compare

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.ozcan.stress.AppGraph
import dev.ozcan.stress.R
import dev.ozcan.stress.analysis.Comparison
import dev.ozcan.stress.graph
import dev.ozcan.stress.run.RunRecord
import dev.ozcan.stress.ui.Dates
import dev.ozcan.stress.ui.Format
import dev.ozcan.stress.ui.Labels
import dev.ozcan.stress.ui.components.CenteredBox
import dev.ozcan.stress.ui.components.ChartSeries
import dev.ozcan.stress.ui.components.GlassCard
import dev.ozcan.stress.ui.components.SectionHeader
import dev.ozcan.stress.ui.components.TimeChart
import dev.ozcan.stress.ui.context
import dev.ozcan.stress.ui.theme.NumberStyles
import dev.ozcan.stress.ui.theme.StressColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The two runs' colours, the same on every row and chart. */
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

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) }
            Text(stringResource(R.string.compare_title), style = MaterialTheme.typography.titleLarge)
        }
        val pair = runs
        val a = pair?.first
        val b = pair?.second
        when {
            pair == null -> CenteredBox { CircularProgressIndicator(color = StressColors.Accent) }
            a == null || b == null -> CenteredBox { Text(stringResource(R.string.result_missing), color = StressColors.TextDim) }
            else -> Body(a, b)
        }
    }
}

@Composable
private fun Body(a: RunRecord, b: RunRecord) {
    val rows = Comparison.rows(a, b)
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
            .navigationBarsPadding()
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RunChip(a, ColorA, "A", Modifier.weight(1f))
            RunChip(b, ColorB, "B", Modifier.weight(1f))
        }

        SectionHeader(stringResource(R.string.compare_metrics))
        GlassCard(Modifier.fillMaxWidth(), padding = 14.dp) {
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                Spacer(Modifier.weight(1.4f))
                Header("A", ColorA, Modifier.weight(1f))
                Header("B", ColorB, Modifier.weight(1f))
                Header("Δ", StressColors.TextDim, Modifier.weight(0.8f))
            }
            rows.forEach { row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(metricName(row.metric)),
                        style = MaterialTheme.typography.bodySmall,
                        color = StressColors.TextDim,
                        modifier = Modifier.weight(1.4f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Value(format(row.metric, row.a), Modifier.weight(1f))
                    Value(format(row.metric, row.b), Modifier.weight(1f))
                    val delta = row.change
                    Text(
                        delta?.let { (if (it > 0) "+" else "") + Format.percent(it) } ?: Format.MISSING,
                        style = NumberStyles.Tiny,
                        color = when {
                            delta == null || kotlin.math.abs(delta) < 0.02 -> StressColors.TextDim
                            (delta > 0) == row.metric.higherIsBetter -> StressColors.Good
                            else -> StressColors.Warn
                        },
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(0.8f),
                    )
                }
            }
            Text(stringResource(R.string.compare_hint), style = MaterialTheme.typography.labelSmall, color = StressColors.TextFaint)
        }

        SectionHeader(stringResource(R.string.result_charts))
        if (a.series.watts.any { it != null } || b.series.watts.any { it != null }) {
            Overlay(stringResource(R.string.chart_power), "W", a, b) { it.series.watts }
        }
        Overlay(stringResource(R.string.compare_chart_chip), "°C", a, b) { r -> Comparison.chipSeries(r) }
        Overlay(stringResource(R.string.compare_chart_battery), "°C", a, b) { it.series.batteryCelsius }
        if (a.series.cpuRelative.any { it != null } && b.series.cpuRelative.any { it != null }) {
            Overlay(stringResource(R.string.compare_chart_cpu), "%", a, b, yMin = 0.0, yMax = 105.0) { r -> r.series.cpuRelative.map { it?.times(100) } }
        }
        if (a.series.fps.any { it != null } || b.series.fps.any { it != null }) {
            Overlay(stringResource(R.string.chart_fps), "fps", a, b, yMin = 0.0) { it.series.fps }
        }
    }
}

@Composable
private fun RunChip(record: RunRecord, color: Color, letter: String, modifier: Modifier) {
    val context = context()
    GlassCard(modifier, padding = 12.dp, highlight = true, accent = color) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(22.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
                Text(letter, style = MaterialTheme.typography.labelMedium, color = StressColors.Background)
            }
            Column {
                Text(Labels.recordModeName(context, record), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${Dates.short(record.startedAtMillis)} · ${Format.clock(record.loadSeconds)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = StressColors.TextDim,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun Header(text: String, color: Color, modifier: Modifier) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = color, textAlign = TextAlign.End, modifier = modifier)
}

@Composable
private fun Value(text: String, modifier: Modifier) {
    Text(text, style = NumberStyles.Tiny, color = StressColors.Text, textAlign = TextAlign.End, modifier = modifier, maxLines = 1)
}

/**
 * Two runs on one chart. Each keeps its own time axis from its start; the
 * chart spans the longer one, the shorter ends early.
 */
@Composable
private fun Overlay(
    title: String,
    unit: String,
    a: RunRecord,
    b: RunRecord,
    yMin: Double? = null,
    yMax: Double? = null,
    values: (RunRecord) -> List<Double?>,
) {
    val va = values(a)
    val vb = values(b)
    if (va.none { it != null } && vb.none { it != null }) return
    val (seconds, first, second) = Comparison.align(a.series.seconds, va, b.series.seconds, vb)
    GlassCard(Modifier.fillMaxWidth(), padding = 14.dp) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(10.dp))
        TimeChart(seconds, listOf(ChartSeries("A", ColorA, first), ChartSeries("B", ColorB, second)), unit, yMin = yMin, yMax = yMax)
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
        Comparison.Metric.CpuRate, Comparison.Metric.GpuRate -> Format.scaled(value).let { (v, p) -> "${Format.number(v, 1)} $p" }
        Comparison.Metric.Fps -> Format.number(value, 1)
        Comparison.Metric.Stability -> Format.percent(value)
        Comparison.Metric.FirstThrottle -> Format.clock(value)
        Comparison.Metric.Errors -> value.toLong().toString()
    }
}
