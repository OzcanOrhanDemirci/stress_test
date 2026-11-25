package dev.ozcan.stress.ui.compare

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.ozcan.stress.AppGraph
import dev.ozcan.stress.R
import dev.ozcan.stress.charge.isCharge
import dev.ozcan.stress.analysis.Comparison
import dev.ozcan.stress.engine.SceneQuality
import dev.ozcan.stress.graph
import dev.ozcan.stress.run.RunRecord
import dev.ozcan.stress.ui.Dates
import dev.ozcan.stress.ui.Format
import dev.ozcan.stress.ui.Labels
import dev.ozcan.stress.ui.charge.ChargeCompare
import dev.ozcan.stress.ui.charge.ChargeLoadComparisonMessage
import dev.ozcan.stress.ui.charge.chargeCompareLog
import dev.ozcan.stress.ui.components.CenteredBox
import dev.ozcan.stress.ui.components.ChartSeries
import dev.ozcan.stress.ui.components.EngineeringPage
import dev.ozcan.stress.ui.components.EngineeringRow
import dev.ozcan.stress.ui.components.LogNote
import dev.ozcan.stress.ui.components.LogTag
import dev.ozcan.stress.ui.components.Panel
import dev.ozcan.stress.ui.components.TimeChart
import dev.ozcan.stress.ui.components.TopBar
import dev.ozcan.stress.ui.components.logSection
import dev.ozcan.stress.ui.context
import dev.ozcan.stress.ui.theme.StressColors
import dev.ozcan.stress.ui.theme.StressType
import dev.ozcan.stress.ui.upper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

class CompareViewModel(graph: AppGraph, first: String, second: String) : ViewModel() {
    private val _runs = MutableStateFlow<Pair<RunRecord?, RunRecord?>?>(null)
    val runs: StateFlow<Pair<RunRecord?, RunRecord?>?> = _runs.asStateFlow()

    init {
        viewModelScope.launch {
            _runs.value = withContext(Dispatchers.IO) { graph.runs.load(first) to graph.runs.load(second) }
        }
    }
}

/** What the screen can show for the pair it was given. The rules are the comparison's, not the layout's. */
private sealed interface Outcome {
    data class Refused(@param:StringRes val reason: Int) : Outcome
    data object Loading : Outcome
    data object Mixed : Outcome
    data class Charge(val a: RunRecord, val b: RunRecord) : Outcome
    data class Load(val a: RunRecord, val b: RunRecord) : Outcome
}

@Composable
fun CompareScreen(first: String, second: String, onBack: () -> Unit) {
    val graph = context().graph
    val model: CompareViewModel = viewModel { CompareViewModel(graph, first, second) }
    val runs by model.runs.collectAsStateWithLifecycle()
    val pair = runs
    val a = pair?.first
    val b = pair?.second
    val outcome = when {
        first == second -> Outcome.Refused(R.string.compare_same_run)
        pair == null -> Outcome.Loading
        a == null || b == null -> Outcome.Refused(R.string.compare_run_missing)
        a.isCharge && b.isCharge -> Outcome.Charge(a, b)
        a.isCharge || b.isCharge -> Outcome.Mixed
        // A reduced or GPU-failed run has no fair counterpart.
        !Comparison.isEligible(a) || !Comparison.isEligible(b) -> Outcome.Refused(R.string.compare_not_eligible)
        else -> Outcome.Load(a, b)
    }
    var engineering by rememberSaveable { mutableStateOf(false) }
    val title = stringResource(R.string.compare_title)
    val ctx = context()

    if (engineering && (outcome is Outcome.Load || outcome is Outcome.Charge)) {
        EngineeringPage(title, onBack = { engineering = false }) {
            if (outcome is Outcome.Load) compareLog(ctx, outcome.a, outcome.b)
            if (outcome is Outcome.Charge) chargeCompareLog(ctx, outcome.a, outcome.b)
        }
        return
    }

    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        TopBar(title = title, modifier = Modifier.statusBarsPadding(), onBack = onBack)
        when (outcome) {
            is Outcome.Refused -> Refusal(outcome.reason)
            Outcome.Loading -> CenteredBox { CircularProgressIndicator(color = StressColors.Accent) }
            Outcome.Mixed -> ChargeLoadComparisonMessage()
            is Outcome.Charge -> ChargeCompare(outcome.a, outcome.b, onEngineering = { engineering = true })
            is Outcome.Load -> SimplePage(outcome.a, outcome.b, onEngineering = { engineering = true })
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

/**
 * The simple page (DESIGN "Compare"): both runs' headers, the verdict, peak and sustained power, hottest chip and
 * battery drain side by side with their difference, the overlaid power chart, and the engineering row.
 */
@Composable
private fun SimplePage(a: RunRecord, b: RunRecord, onEngineering: () -> Unit) {
    val ctx = context()
    val rows = remember(a, b) { sideRows(ctx, a, b) }
    val verdict = remember(a, b) { verdict(ctx, a, b) }
    val chart = remember(a, b) { chartReadings(a, b).let { it[Reading.Power]?.let { line -> Reading.Power to line } ?: it.entries.firstOrNull()?.toPair() } }
    val values = remember(a, b) { compareValueCount(ctx, a, b) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        Arrangement.spacedBy(12.dp),
    ) {
        RunHeaders(runHeader(ctx, a), runHeader(ctx, b))
        Panel(Modifier.fillMaxWidth()) { Text(verdict, style = StressType.Body) }
        SideBySide(rows)
        if (chart != null) {
            val (reading, line) = chart
            Panel(Modifier.fillMaxWidth(), title = stringResource(reading.title), padding = 12.dp) {
                ComparisonChart(reading, line)
            }
        }
        EngineeringRow(pluralStringResource(R.plurals.parts_values, values, values).upper(), onEngineering)
    }
}

private fun runHeader(context: Context, record: RunRecord): RunHeader {
    val quality = SceneQuality.entries.firstOrNull { it.name == record.quality }?.let { context.getString(Labels.quality(it)) }
    return RunHeader(
        Labels.recordModeName(context, record),
        listOfNotNull(Dates.short(record.startedAtMillis), listOfNotNull(Format.clock(record.loadSeconds), quality).joinToString(" · ")),
    )
}

/** Battery percent used during the run, in points; null unless both ends were read. */
private fun batteryDrain(record: RunRecord): Double? {
    val start = record.summary.batteryStartPercent ?: return null
    val end = record.summary.batteryEndPercent ?: return null
    return (start - end).toDouble()
}

/** "%3" / "3%", also signed: a percent-point count in the language's order. */
private fun points(value: Double): String = (if (value < 0) "-" else "") + Format.percentValue(abs(value).roundToInt())

private fun sideRows(context: Context, a: RunRecord, b: RunRecord): List<SideRow> {
    fun metric(metric: Comparison.Metric) = Comparison.value(a, metric) to Comparison.value(b, metric)
    val (peakA, peakB) = metric(Comparison.Metric.PeakPower)
    val (sustainedA, sustainedB) = metric(Comparison.Metric.SustainedPower)
    val (chipA, chipB) = metric(Comparison.Metric.HottestChip)
    // Each value is rounded as it is printed, so the difference is the one between the numbers on the screen.
    fun watts(value: Double?) = value?.let { Comparison.rounded(it, Format.WATT_DECIMALS) }
    fun degrees(value: Double?) = value?.let { Comparison.rounded(it, 1) }
    return listOf(
        sideRow(context.getString(R.string.result_peak), watts(peakA), watts(peakB), "W", true, Format::wattNumber),
        sideRow(context.getString(R.string.result_sustained), watts(sustainedA), watts(sustainedB), "W", true, Format::wattNumber),
        sideRow(context.getString(R.string.result_hottest), degrees(chipA), degrees(chipB), "°C", false) { Format.number(it, 1) },
        // Less battery for the same work is the better phone or setting.
        sideRow(context.getString(R.string.compare_battery_drain), batteryDrain(a), batteryDrain(b), null, false, ::points),
    )
}

/**
 * One or two sentences: how B's power compares with A's (sustained, else peak), then its hottest chip when that
 * moved by a degree or more.
 */
private fun verdict(context: Context, a: RunRecord, b: RunRecord): String {
    val sustained = change(Comparison.value(a, Comparison.Metric.SustainedPower), Comparison.value(b, Comparison.Metric.SustainedPower))
    val peak = change(Comparison.value(a, Comparison.Metric.PeakPower), Comparison.value(b, Comparison.Metric.PeakPower))
    val power = when {
        sustained != null -> powerSentence(context, sustained, R.string.compare_verdict_sustained_more, R.string.compare_verdict_sustained_less)
        peak != null -> powerSentence(context, peak, R.string.compare_verdict_peak_more, R.string.compare_verdict_peak_less)
        else -> context.getString(R.string.compare_peak_unavailable)
    }
    val chipA = Comparison.value(a, Comparison.Metric.HottestChip)?.let { Comparison.rounded(it, 1) }
    val chipB = Comparison.value(b, Comparison.Metric.HottestChip)?.let { Comparison.rounded(it, 1) }
    val heat = if (chipA != null && chipB != null && abs(chipB - chipA) >= 1.0) {
        val text = Format.celsius(abs(chipB - chipA), 0)
        context.getString(if (chipB > chipA) R.string.compare_verdict_hotter else R.string.compare_verdict_cooler, text)
    } else {
        null
    }
    return listOfNotNull(power, heat).joinToString(" ")
}

private fun powerSentence(context: Context, change: Double, @StringRes more: Int, @StringRes less: Int): String = when {
    abs(change) < SAME_FRACTION -> context.getString(R.string.compare_verdict_same)
    change > 0 -> context.getString(more, Format.percent(change))
    else -> context.getString(less, Format.percent(abs(change)))
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

private typealias AlignedLine = Triple<List<Double>, List<Double?>, List<Double?>>

/** Every reading either run recorded, on the longer run's time axis. */
private fun chartReadings(a: RunRecord, b: RunRecord): Map<Reading, AlignedLine> =
    Reading.entries.mapNotNull { reading ->
        val va = reading.values(a)
        val vb = reading.values(b)
        val hasA = va.any { it != null }
        val hasB = vb.any { it != null }
        // Relative CPU curves are only shown when both runs captured CPU work.
        val available = if (reading == Reading.Cpu) hasA && hasB else hasA || hasB
        if (available) reading to Comparison.align(a.series.seconds, va, b.series.seconds, vb) else null
    }.toMap()

@Composable
private fun ComparisonChart(reading: Reading, line: AlignedLine) {
    val (seconds, first, second) = line
    TimeChart(
        seconds,
        listOf(ChartSeries("A", ColorA, first), ChartSeries("B", ColorB, second)),
        reading.unit,
        yMin = reading.yMin,
        yMax = reading.yMax,
    )
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

private fun format(metric: Comparison.Metric, value: Double?, scale: Double = 1.0): String {
    if (value == null) return Format.MISSING
    return when (metric) {
        Comparison.Metric.PeakPower, Comparison.Metric.SustainedPower, Comparison.Metric.MeanPower -> Format.watts(value)
        Comparison.Metric.HottestChip, Comparison.Metric.HottestBattery -> Format.celsius(value)
        Comparison.Metric.CpuRate, Comparison.Metric.GpuRate -> Format.number(value / scale, 1)
        Comparison.Metric.Fps -> Format.number(value, 1)
        Comparison.Metric.Stability -> Format.percent(value)
        Comparison.Metric.FirstThrottle -> Format.clock(value)
        Comparison.Metric.Errors -> value.toLong().toString()
    }
}

/** Every compared number: the comparison's rows, then the run-level ones it does not hold (battery, energy). */
private fun compareValues(context: Context, a: RunRecord, b: RunRecord): List<CompareValue> {
    val metrics = Comparison.rows(a, b).map { row ->
        val name = context.getString(metricName(row.metric))
        // A work rate's unit goes with its name ("CPU performance (GFLOPS)"), both runs on one prefix, so the
        // numbers stay short enough for their column.
        val symbol = when (row.metric) {
            Comparison.Metric.CpuRate -> rateSymbol(context, a.summary.cpuUnit, b.summary.cpuUnit)
            Comparison.Metric.GpuRate -> rateSymbol(context, a.summary.gpuUnit, b.summary.gpuUnit)
            else -> null
        }
        val top = maxOf(row.a ?: 0.0, row.b ?: 0.0)
        val (scaled, prefix) = Format.scaled(top)
        val scale = if (symbol != null && scaled != 0.0) top / scaled else 1.0
        CompareValue(
            if (symbol != null) "$name ($prefix$symbol)" else name,
            format(row.metric, row.a, scale), format(row.metric, row.b, scale),
            // A difference that shows as zero ("0 sn") reads as no change, not as better or worse.
            delta(context, row), changeColor(row.change.takeUnless { row.shownDifference == 0.0 }, row.metric.higherIsBetter),
        )
    }
    val drainA = batteryDrain(a)
    val drainB = batteryDrain(b)
    val energyA = a.summary.energyWattHours.takeIf { a.summary.powerValid }
    val energyB = b.summary.energyWattHours.takeIf { b.summary.powerValid }
    fun wh(value: Double?) = value?.let { "${Format.number(it, 2)} Wh" } ?: Format.MISSING
    return metrics + listOfNotNull(
        CompareValue(
            context.getString(R.string.compare_battery_drain), drainA?.let(::points) ?: Format.MISSING,
            drainB?.let(::points) ?: Format.MISSING,
            // Battery use is already in percent: its change is told in points, as on the simple page.
            if (drainA != null && drainB != null) (drainB - drainA).let { signed(it, points(it)) } else Format.MISSING,
            changeColor(change(drainA, drainB), false),
        ).takeIf { drainA != null || drainB != null },
        CompareValue(
            context.getString(R.string.result_energy), wh(energyA), wh(energyB),
            signedPercent(change(energyA, energyB)), changeColor(change(energyA, energyB), true),
        ).takeIf { energyA != null || energyB != null },
    )
}

/** The unit of a work rate both runs share; null when they measured different work, as nothing then shares a unit. */
private fun rateSymbol(context: Context, a: String?, b: String?): String? {
    if (a != null && b != null && a != b) return null
    return Labels.rateSymbol(context, a ?: b)
}

/** The Δ of a row: a percent of A, or the difference in the reading's own unit (DESIGN: no percent of a °C). */
private fun delta(context: Context, row: Comparison.Row): String {
    if (row.metric.delta == Comparison.Delta.Percent) return signedPercent(row.change)
    val d = row.shownDifference ?: return Format.MISSING
    return when (row.metric.delta) {
        Comparison.Delta.Degrees -> signed(d, Format.celsius(d))
        Comparison.Delta.Seconds -> signed(d, context.getString(R.string.compare_seconds_difference, d.toLong().toString()))
        else -> signed(d, d.toLong().toString())
    }
}

/** What one run was: its mode, workload, quality, safety and power state, then the lines every run has. */
private fun runLines(context: Context, record: RunRecord): List<RunLine> {
    val summary = record.summary
    fun qualityName(name: String?) = SceneQuality.entries.firstOrNull { it.name == name }?.let { context.getString(Labels.quality(it)) }
    val scene = record.quality != null
    val power = when {
        !summary.powerValid -> RunLine(context.getString(R.string.compare_power), context.getString(R.string.history_power_invalid), StressColors.Warn, LogTag.Warn)
        summary.powerFromCounter -> RunLine(context.getString(R.string.compare_power), context.getString(R.string.compare_power_counter))
        else -> RunLine(context.getString(R.string.compare_power), context.getString(R.string.compare_power_valid), tag = LogTag.Ok)
    }
    return listOfNotNull(
        RunLine(context.getString(R.string.report_mode), Labels.recordModeName(context, record)),
        RunLine(context.getString(R.string.report_workload), record.workload),
        RunLine(context.getString(R.string.report_requested_quality), qualityName(record.quality)).takeIf { scene },
        RunLine(
            context.getString(R.string.report_drawn_quality), qualityName(record.drawnQuality),
            source = record.drawnScalePercent?.let { context.getString(R.string.compare_scale, Format.number(it, 0)) },
        ).takeIf { scene },
        RunLine(
            context.getString(R.string.settings_safety),
            record.safetyEnabled?.let { context.getString(if (it) R.string.safety_on_short else R.string.safety_off_short) },
            if (record.safetyEnabled == false) StressColors.Warn else StressColors.Text,
        ),
        power,
        RunLine(
            context.getString(R.string.result_idle), summary.idleWatts.takeIf { summary.powerValid }?.let(Format::wattNumber),
            StressColors.Accent, unit = "W",
        ),
    ) + commonRunLines(context, record)
}

private fun compareValueCount(context: Context, a: RunRecord, b: RunRecord): Int =
    compareValues(context, a, b).size + chartReadings(a, b).size + runLines(context, a).size + runLines(context, b).size

/**
 * The engineering page (DESIGN "Compare"): the verdict, every compared number with its change, every overlaid
 * chart, then each run's settings, read top to bottom like a report.
 */
private fun LazyListScope.compareLog(context: Context, a: RunRecord, b: RunRecord) {
    val values = compareValues(context, a, b)
    val charts = chartReadings(a, b)
    val peakDifference = Comparison.rows(a, b).firstOrNull { it.metric == Comparison.Metric.PeakPower }?.difference
    val peakLine = if (peakDifference == null) {
        context.getString(R.string.compare_peak_unavailable)
    } else {
        context.getString(R.string.compare_peak_difference, signed(peakDifference, Format.watts(peakDifference)))
    }
    val missing = values.any { it.a == Format.MISSING || it.b == Format.MISSING }

    val verdict = verdict(context, a, b)
    logSection(context.getString(R.string.compare_summary)) {
        item(key = "verdict") { Text(verdict, Modifier.padding(vertical = 6.dp), style = StressType.Body) }
        // Without power in both runs the verdict already says peak power needs both; it is said once.
        if (peakLine !in verdict) item(key = "peak") { LogNote(peakLine) }
    }
    logSection(
        context.getString(R.string.compare_metrics),
        summary = context.resources.getQuantityString(R.plurals.parts_values, values.size, values.size),
        note = listOfNotNull(context.getString(R.string.compare_hint), context.getString(R.string.result_unavailable).takeIf { missing })
            .joinToString(" "),
    ) {
        item(key = "metrics-head") { CompareHead() }
        values.forEachIndexed { i, value -> item(key = "metric-$i") { CompareLine(value) } }
    }
    if (charts.isNotEmpty()) {
        logSection(context.getString(R.string.result_charts), summary = charts.size.toString()) {
            charts.forEach { (reading, line) ->
                item(key = "chart-${reading.name}") {
                    Column(Modifier.padding(vertical = 8.dp), Arrangement.spacedBy(8.dp)) {
                        Text(context.getString(reading.title).upper(), style = StressType.Code, color = StressColors.Text)
                        ComparisonChart(reading, line)
                    }
                }
            }
        }
    }
    listOf(Triple("a", R.string.compare_run_a, a), Triple("b", R.string.compare_run_b, b)).forEach { (key, title, record) ->
        val lines = runLines(context, record)
        logSection(
            context.getString(title), summary = Dates.short(record.startedAtMillis), key = "run-$key",
            note = context.getString(R.string.result_unavailable).takeIf { lines.any { it.value == null } },
        ) { logRunLines("run-$key", lines) }
    }
}
