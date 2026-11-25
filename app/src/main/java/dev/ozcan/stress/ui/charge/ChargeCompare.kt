package dev.ozcan.stress.ui.charge

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ozcan.stress.R
import dev.ozcan.stress.analysis.Comparison
import dev.ozcan.stress.charge.ChargeAnalysis
import dev.ozcan.stress.run.RunAnalysis
import dev.ozcan.stress.run.RunRecord
import dev.ozcan.stress.ui.Dates
import dev.ozcan.stress.ui.Format
import dev.ozcan.stress.ui.compare.ColorA
import dev.ozcan.stress.ui.compare.ColorB
import dev.ozcan.stress.ui.compare.CompareHead
import dev.ozcan.stress.ui.compare.CompareLine
import dev.ozcan.stress.ui.compare.CompareValue
import dev.ozcan.stress.ui.compare.RunHeader
import dev.ozcan.stress.ui.compare.RunHeaders
import dev.ozcan.stress.ui.compare.RunLine
import dev.ozcan.stress.ui.compare.SAME_FRACTION
import dev.ozcan.stress.ui.compare.SideBySide
import dev.ozcan.stress.ui.compare.change
import dev.ozcan.stress.ui.compare.changeColor
import dev.ozcan.stress.ui.compare.commonRunLines
import dev.ozcan.stress.ui.compare.logRunLines
import dev.ozcan.stress.ui.compare.sideRow
import dev.ozcan.stress.ui.compare.signedPercent
import dev.ozcan.stress.ui.components.ChartSeries
import dev.ozcan.stress.ui.components.EngineeringRow
import dev.ozcan.stress.ui.components.LogNote
import dev.ozcan.stress.ui.components.Panel
import dev.ozcan.stress.ui.components.TimeChart
import dev.ozcan.stress.ui.components.logSection
import dev.ozcan.stress.ui.context
import dev.ozcan.stress.ui.theme.StressColors
import dev.ozcan.stress.ui.theme.StressType
import dev.ozcan.stress.ui.upper
import kotlin.math.abs

@Composable
fun ChargeLoadComparisonMessage() {
    Panel(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(stringResource(R.string.charge_mixed_compare), style = StressType.Body)
    }
}

/**
 * Two charge tests (DESIGN "Compare", charge vs charge): both setups, the verdict, sustained and peak power, energy
 * and hottest battery side by side, the power-against-percent chart, and the engineering row.
 */
@Composable
fun ChargeCompare(first: RunRecord, second: RunRecord, onEngineering: () -> Unit) {
    val ctx = context()
    val rows = remember(first, second) {
        fun value(metric: ChargeMetric) = metric.value(first) to metric.value(second)
        fun watts(metric: ChargeMetric) = value(metric).let { (a, b) ->
            sideRow(ctx.getString(metric.label), a, b, "W", metric.higherIsBetter, Format::wattNumber)
        }
        listOf(
            watts(ChargeMetric.Sustained),
            watts(ChargeMetric.Peak),
            value(ChargeMetric.Energy).let { (a, b) ->
                sideRow(ctx.getString(ChargeMetric.Energy.label), a, b, "Wh", true) { Format.number(it, 2) }
            },
            value(ChargeMetric.MaxTemperature).let { (a, b) ->
                sideRow(ctx.getString(ChargeMetric.MaxTemperature.label), a, b, "°C", false) { Format.number(it, 1) }
            },
        )
    }
    val verdict = remember(first, second) { chargeVerdict(ctx, first, second) }
    val values = remember(first, second) { chargeCompareValueCount(ctx, first, second) }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        Arrangement.spacedBy(12.dp),
    ) {
        RunHeaders(setupHeader(ctx, first, R.string.charge_compare_a), setupHeader(ctx, second, R.string.charge_compare_b))
        if (verdict != null) Panel(Modifier.fillMaxWidth()) { Text(verdict, style = StressType.Body) }
        SideBySide(rows)
        Panel(Modifier.fillMaxWidth(), title = stringResource(R.string.charge_chart_percent), padding = 12.dp) {
            PercentChart(first, second)
        }
        EngineeringRow(pluralStringResource(R.plurals.parts_values, values, values).upper(), onEngineering)
    }
}

private fun setupHeader(context: Context, record: RunRecord, @StringRes title: Int): RunHeader = RunHeader(
    context.getString(title),
    listOfNotNull(
        record.charge?.plugType?.let { context.getString(plugLabel(it)) },
        Dates.short(record.startedAtMillis),
        Format.clock(record.loadSeconds),
    ),
)

/** Every number a charge comparison shows, and which way is better for a charger or cable. */
private enum class ChargeMetric(
    @param:StringRes val label: Int,
    val higherIsBetter: Boolean,
    val value: (RunRecord) -> Double?,
    val format: (Double?) -> String,
) {
    Peak(R.string.charge_peak, true, { it.summary.peakWatts }, Format::watts),
    Sustained(R.string.charge_sustained, true, { it.summary.sustainedWatts }, Format::watts),
    // A later slowdown means the setup held its power longer.
    Slowdown(R.string.charge_slowdown, true, { it.charge?.slowdownPercent?.toDouble() }, { Format.percentValue(it?.toInt()) }),
    Energy(R.string.charge_energy, true, { it.summary.energyWattHours }, { v -> v?.let { "${Format.number(it, 2)} Wh" } ?: Format.MISSING }),
    Rate(R.string.charge_rate, true, { it.charge?.percentPerTenMinutes }, { Format.percent(it?.div(100), 1) }),
    MaxTemperature(R.string.charge_max_temp, false, { it.summary.maxTemperatures[RunAnalysis.BATTERY] }, { Format.celsius(it) }),
}

/** How setup B's sustained power into the battery compares with A's; null when either was not measured. */
private fun chargeVerdict(context: Context, a: RunRecord, b: RunRecord): String? {
    val change = change(ChargeMetric.Sustained.value(a), ChargeMetric.Sustained.value(b)) ?: return null
    return when {
        abs(change) < SAME_FRACTION -> context.getString(R.string.charge_compare_verdict_same)
        change > 0 -> context.getString(R.string.charge_compare_verdict_more, Format.percent(change))
        else -> context.getString(R.string.charge_compare_verdict_less, Format.percent(abs(change)))
    }
}

/** Power into the battery against battery percent: the fair axis for two setups that started at different levels. */
@Composable
private fun PercentChart(first: RunRecord, second: RunRecord) {
    val a = stringResource(R.string.charge_compare_a)
    val b = stringResource(R.string.charge_compare_b)
    val left = remember(first) { ChargeAnalysis.percentCurve(first.series).associate { it.seconds to it.value } }
    val right = remember(second) { ChargeAnalysis.percentCurve(second.series).associate { it.seconds to it.value } }
    if (left.isEmpty() && right.isEmpty()) {
        Text(stringResource(R.string.charge_no_curve), style = StressType.BodySmall, color = StressColors.TextDim)
    } else {
        val levels = (left.keys + right.keys).sorted()
        val lines = listOf(
            ChartSeries(a, ColorA, levels.map(left::get)),
            ChartSeries(b, ColorB, levels.map(right::get)),
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TimeChart(
                levels, lines, "W", yMin = 0.0, decimals = Format.WATT_DECIMALS,
                timeLabel = { Format.percentValue(it.toInt()) }, xEnd = 100.0, xStep = 25.0, xReadoutPrefix = "",
            )
            Text(stringResource(R.string.charge_compare_matching_level), style = StressType.BodySmall, color = StressColors.TextDim)
        }
    }
}

/** A curve over time for both setups, on the longer test's axis. */
@Composable
private fun TimeComparison(first: RunRecord, second: RunRecord, unit: String, values: (RunRecord) -> List<Double?>) {
    val (seconds, a, b) = remember(first, second) {
        Comparison.align(first.series.seconds, values(first), second.series.seconds, values(second))
    }
    TimeChart(
        seconds,
        listOf(ChartSeries(stringResource(R.string.charge_compare_a), ColorA, a), ChartSeries(stringResource(R.string.charge_compare_b), ColorB, b)),
        unit,
    )
}

private fun chargeValues(context: Context, a: RunRecord, b: RunRecord): List<CompareValue> = ChargeMetric.entries.map { metric ->
    val va = metric.value(a)
    val vb = metric.value(b)
    CompareValue(
        context.getString(metric.label), metric.format(va), metric.format(vb),
        signedPercent(change(va, vb)), changeColor(change(va, vb), metric.higherIsBetter),
    )
}

/** What a setup was: its plug, the phone's draw at rest, the reading cadence, then the lines every run has. */
private fun setupLines(context: Context, record: RunRecord): List<RunLine> {
    val details = record.charge
    return listOf(
        RunLine(context.getString(R.string.charge_source), details?.plugType?.let { context.getString(plugLabel(it)) }),
        RunLine(context.getString(R.string.charge_idle), record.summary.idleWatts?.let(Format::wattNumber), StressColors.Accent, unit = "W"),
        RunLine(context.getString(R.string.compare_cadence), details?.currentChangeSeconds?.let { Format.number(it, 1) }, unit = "s"),
        RunLine(
            context.getString(R.string.compare_hot_slowdown),
            details?.let { context.getString(if (it.slowdownWhileHot) R.string.compare_yes else R.string.compare_no) },
            if (details?.slowdownWhileHot == true) StressColors.Warn else StressColors.Text,
        ),
    ) + commonRunLines(context, record)
}

/** The number of values the engineering page holds: metrics, three charts, both setups' lines. */
internal fun chargeCompareValueCount(context: Context, a: RunRecord, b: RunRecord): Int =
    ChargeMetric.entries.size + 3 + setupLines(context, a).size + setupLines(context, b).size

/**
 * The charge comparison's engineering page: verdict, every finding side by side, the three curves (power against
 * percent, power and battery temperature over time), then each setup's details.
 */
internal fun LazyListScope.chargeCompareLog(context: Context, a: RunRecord, b: RunRecord) {
    val values = chargeValues(context, a, b)
    val missing = values.any { it.a == Format.MISSING || it.b == Format.MISSING }
    logSection(context.getString(R.string.compare_summary)) {
        chargeVerdict(context, a, b)?.let { verdict ->
            item(key = "verdict") { Text(verdict, Modifier.padding(vertical = 6.dp), style = StressType.Body) }
        }
        item(key = "matching") { LogNote(context.getString(R.string.charge_compare_matching_level)) }
    }
    logSection(
        context.getString(R.string.charge_findings),
        summary = context.resources.getQuantityString(R.plurals.parts_values, values.size, values.size),
        note = listOfNotNull(context.getString(R.string.compare_hint), context.getString(R.string.charge_result_missing).takeIf { missing })
            .joinToString(" "),
    ) {
        item(key = "metrics-head") { CompareHead() }
        values.forEachIndexed { i, value -> item(key = "metric-$i") { CompareLine(value) } }
    }
    logSection(context.getString(R.string.result_charts), summary = "3") {
        item(key = "chart-percent") {
            ChartItem(context.getString(R.string.charge_chart_percent)) { PercentChart(a, b) }
        }
        item(key = "chart-power") {
            ChartItem(context.getString(R.string.charge_battery_power)) { TimeComparison(a, b, "W") { it.series.watts } }
        }
        item(key = "chart-battery") {
            ChartItem(context.getString(R.string.compare_chart_battery)) { TimeComparison(a, b, "°C") { it.series.batteryCelsius } }
        }
    }
    listOf(Triple("a", R.string.charge_compare_a, a), Triple("b", R.string.charge_compare_b, b)).forEach { (key, title, record) ->
        val lines = setupLines(context, record)
        logSection(
            context.getString(title), summary = Dates.short(record.startedAtMillis), key = "setup-$key",
            note = context.getString(R.string.charge_missing_readings).takeIf { lines.any { it.value == null } },
        ) { logRunLines("setup-$key", lines) }
    }
}

@Composable
private fun ChartItem(title: String, chart: @Composable () -> Unit) {
    Column(Modifier.padding(vertical = 8.dp), Arrangement.spacedBy(8.dp)) {
        Text(title.upper(), style = StressType.Code, color = StressColors.Text)
        chart()
    }
}
