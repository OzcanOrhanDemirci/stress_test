package dev.ozcan.stress.ui.charge

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ozcan.stress.R
import dev.ozcan.stress.analysis.Point
import dev.ozcan.stress.charge.ChargeAnalysis
import dev.ozcan.stress.charge.ChargeSpeed
import dev.ozcan.stress.run.EndReason
import dev.ozcan.stress.run.RunAnalysis
import dev.ozcan.stress.run.RunRecord
import dev.ozcan.stress.ui.Dates
import dev.ozcan.stress.ui.Format
import dev.ozcan.stress.ui.Labels
import dev.ozcan.stress.ui.components.ChartSeries
import dev.ozcan.stress.ui.components.EngineeringRow
import dev.ozcan.stress.ui.components.LogTag
import dev.ozcan.stress.ui.components.MetricNumber
import dev.ozcan.stress.ui.components.MetricTile
import dev.ozcan.stress.ui.components.Panel
import dev.ozcan.stress.ui.components.SectionLabel
import dev.ozcan.stress.ui.components.StatusChip
import dev.ozcan.stress.ui.components.TileRow
import dev.ozcan.stress.ui.components.TimeChart
import dev.ozcan.stress.ui.components.simpleChartHeight
import dev.ozcan.stress.ui.context
import dev.ozcan.stress.ui.result.LogBlock
import dev.ozcan.stress.ui.result.LogEntry
import dev.ozcan.stress.ui.result.VerdictPanel
import dev.ozcan.stress.ui.result.chart
import dev.ozcan.stress.ui.result.deviceBlock
import dev.ozcan.stress.ui.result.endTag
import dev.ozcan.stress.ui.result.recordBlock
import dev.ozcan.stress.ui.theme.NumberStyles
import dev.ozcan.stress.ui.theme.StressColors
import dev.ozcan.stress.ui.theme.StressType

/**
 * The charge test's simple page (DESIGN, Charge result row): peak power into the battery, the three figures that
 * describe the charge, the W-against-% curve and the first finding. Everything else is in [chargeLog].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChargeResult(record: RunRecord, onEngineering: () -> Unit, modifier: Modifier = Modifier) {
    val context = context()
    val summary = record.summary
    val reason = Labels.endReasonOf(record)
    val details = record.charge
    val peak = summary.peakWatts
    val percentCurve = remember(record) { ChargeAnalysis.percentCurve(record.series) }
    val findings = remember(record, context) { chargeFindings(context, record) }
    Column(
        Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        Arrangement.spacedBy(12.dp),
    ) {
        if (reason == EndReason.Safety) {
            Panel(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.charge_hot_stop), style = StressType.Body, color = StressColors.Bad)
            }
        }
        Panel(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // The end-state chip moves under the date when both do not fit on a narrow phone, instead of wrapping the date.
                FlowRow(
                    Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Arrangement.spacedBy(8.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(Dates.short(record.startedAtMillis), style = StressType.Code, color = StressColors.TextDim, maxLines = 1)
                    StatusChip(stringResource(Labels.endReasonShort(reason)), Labels.endReasonColor(reason))
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SectionLabel(stringResource(R.string.charge_peak), caption = Format.clock(record.loadSeconds))
                    MetricNumber(Format.wattNumber(peak), "W", NumberStyles.Hero, StressColors.Accent)
                }
            }
        }
        TileRow(Modifier.height(IntrinsicSize.Min)) {
            val cell = Modifier.weight(1f).fillMaxHeight()
            MetricTile(stringResource(R.string.result_charge_sustained), Format.wattNumber(summary.sustainedWatts), cell, unit = "W", color = StressColors.Accent)
            MetricTile(stringResource(R.string.result_charge_energy), Format.number(summary.energyWattHours, 2), cell, unit = "Wh")
            MetricTile(stringResource(R.string.result_charge_rate), Format.percent(details?.percentPerTenMinutes?.div(100), 1), cell)
        }
        if (peak == null || summary.sustainedWatts == null || summary.energyWattHours == null || details?.percentPerTenMinutes == null) {
            Text(stringResource(R.string.charge_result_missing), style = StressType.BodySmall, color = StressColors.TextDim)
        }
        PercentCurve(percentCurve)
        findings.firstOrNull()?.let { VerdictPanel(it, StressColors.Accent) }
        EngineeringRow(onEngineering)
    }
}

/** The W-against-% curve as the result's one chart, in the same readout panel as the load test's power curve. */
@Composable
private fun PercentCurve(points: List<Point>) {
    if (points.isEmpty()) return
    val name = stringResource(R.string.charge_battery_power)
    Panel(Modifier.fillMaxWidth(), padding = 12.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel(stringResource(R.string.charge_chart_percent))
            BoxWithConstraints {
                TimeChart(
                    points.map(Point::seconds), listOf(ChartSeries(name, StressColors.Accent, points.map(Point::value))), "W",
                    height = simpleChartHeight(maxWidth), yMin = 0.0, decimals = Format.WATT_DECIMALS,
                    timeLabel = { Format.percentValue(it.toInt()) }, xEnd = 100.0, xStep = 25.0, xReadoutPrefix = "",
                )
            }
        }
    }
}

/** The findings in plain words, the charging speed class first; each one names the threshold it was judged by. */
internal fun chargeFindings(context: Context, record: RunRecord): List<String> {
    val peak = record.summary.peakWatts
    val details = record.charge
    return buildList {
        if (peak != null && peak > 0.0) {
            val watts = Format.watts(peak)
            val standardMin = Format.number(ChargeAnalysis.STANDARD_MIN_WATTS, 1)
            val fastMin = Format.number(ChargeAnalysis.FAST_MIN_WATTS, 0)
            val fastMax = Format.number(ChargeAnalysis.FAST_MAX_WATTS, 0)
            add(when (ChargeAnalysis.speed(peak)) {
                ChargeSpeed.UsbPort -> context.getString(R.string.charge_speed_usb, watts, standardMin)
                ChargeSpeed.Standard -> context.getString(R.string.charge_speed_standard, watts, standardMin, fastMin)
                ChargeSpeed.Fast -> context.getString(R.string.charge_speed_fast, watts, fastMin, fastMax)
                ChargeSpeed.HighPower -> context.getString(R.string.charge_speed_high, watts, fastMax)
            })
        }
        if (details?.slowdownWhileHot == true) add(context.getString(R.string.charge_heat_finding))
        if ((record.summary.batteryStartPercent ?: 0) > 80) add(context.getString(R.string.charge_high_start))
        details?.currentChangeSeconds?.let { add(context.getString(R.string.charge_sparse, Format.number(it, 0))) }
    }
}

/** The charge test's engineering page: what went into the battery, how the battery took it, findings, curves. */
internal fun chargeLog(context: Context, record: RunRecord): List<LogBlock> = listOfNotNull(
    chargeSummary(context, record),
    chargePower(context, record),
    chargeBattery(context, record),
    chargeFindingBlock(context, record),
    chargeReading(context, record),
) + chargeCharts(context, record) + listOfNotNull(deviceBlock(context, record), recordBlock(context, record))

private fun chargeSummary(context: Context, record: RunRecord): LogBlock {
    val reason = Labels.endReasonOf(record)
    val s = record.summary
    val start = s.batteryStartPercent
    val end = s.batteryEndPercent
    val lines = buildList {
        add(LogEntry.Line(context.getString(R.string.result_log_date), Dates.long(record.startedAtMillis)))
        add(
            LogEntry.Line(
                context.getString(R.string.result_log_duration), Format.clock(record.loadSeconds),
                source = context.getString(
                    R.string.result_log_planned,
                    record.plannedSeconds?.let { Format.clock(it.toDouble()) } ?: context.getString(R.string.charge_until_full).replaceFirstChar { it.lowercase(java.util.Locale.getDefault()) },
                ),
            ),
        )
        add(
            LogEntry.Line(
                context.getString(R.string.result_log_end), context.getString(Labels.endReason(reason)),
                color = Labels.endReasonColor(reason), tag = endTag(reason),
            ),
        )
        add(LogEntry.Line(context.getString(R.string.charge_source), record.charge?.plugType?.let { context.getString(plugLabel(it)) }))
        add(
            LogEntry.Line(
                context.getString(R.string.charge_battery_level),
                if (start != null && end != null) "${Format.percentValue(start)} → ${Format.percentValue(end)}" else null,
            ),
        )
        if (reason == EndReason.Safety) add(LogEntry.Sentence(context.getString(R.string.charge_hot_stop), StressColors.Bad))
    }
    return LogBlock(
        context.getString(R.string.result_log_summary), lines,
        summary = context.getString(Labels.endReasonShort(reason)),
        note = context.getString(R.string.charge_missing_readings).takeIf { lines.any { it is LogEntry.Line && it.value == null } },
    )
}

private fun chargePower(context: Context, record: RunRecord): LogBlock {
    val s = record.summary
    val details = record.charge
    // The charger also feeds the phone: what the phone drew at rest plus what reached the battery, at the peak.
    val input = s.idleWatts?.let { idle -> s.peakWatts?.takeIf { it > 0.0 }?.plus(idle) }
    val lines = listOf(
        LogEntry.Line(context.getString(R.string.charge_peak), s.peakWatts?.let(Format::wattNumber), "W", StressColors.Accent),
        LogEntry.Line(
            context.getString(R.string.charge_sustained), s.sustainedWatts?.let(Format::wattNumber), "W", StressColors.Accent,
            source = context.getString(R.string.charge_sustained_note),
        ),
        LogEntry.Line(context.getString(R.string.charge_energy), s.energyWattHours?.let { Format.number(it, 2) }, "Wh"),
        LogEntry.Line(context.getString(R.string.charge_rate), details?.percentPerTenMinutes?.let { Format.percent(it / 100, 1) }),
        LogEntry.Line(context.getString(R.string.charge_idle), s.idleWatts?.let(Format::wattNumber), "W", StressColors.Accent),
        LogEntry.Line(
            context.getString(R.string.charge_estimated_input), input?.let(Format::wattNumber), "W", StressColors.Accent,
            source = context.getString(R.string.result_log_input_source),
        ),
    )
    return LogBlock(
        context.getString(R.string.charge_battery_power), lines,
        summary = Format.watts(s.peakWatts),
        note = listOfNotNull(
            context.getString(R.string.charge_net_explained),
            context.getString(R.string.charge_result_missing).takeIf { lines.any { it.value == null } },
        ).joinToString(" "),
    )
}

private fun chargeBattery(context: Context, record: RunRecord): LogBlock {
    val s = record.summary
    val series = record.series
    val details = record.charge
    val amps = series.chargeCurrentAmps.filterNotNull()
    val volts = series.batteryVolts.filterNotNull()
    val hottest = s.maxTemperatures[RunAnalysis.BATTERY]
    val first = s.startTemperatures[RunAnalysis.BATTERY]
    val lines = listOf(
        LogEntry.Line(context.getString(R.string.result_log_max, context.getString(R.string.charge_current)), amps.maxOrNull()?.let { Format.number(it, 2) }, "A"),
        LogEntry.Line(context.getString(R.string.result_log_mean, context.getString(R.string.charge_current)), amps.takeIf { it.isNotEmpty() }?.average()?.let { Format.number(it, 2) }, "A"),
        LogEntry.Line(context.getString(R.string.result_log_min, context.getString(R.string.charge_voltage)), volts.minOrNull()?.let { Format.number(it, 2) }, "V"),
        LogEntry.Line(context.getString(R.string.result_log_max, context.getString(R.string.charge_voltage)), volts.maxOrNull()?.let { Format.number(it, 2) }, "V"),
        LogEntry.Line(
            context.getString(R.string.charge_max_temp), hottest?.let { Format.number(it, 1) }, "°C", chargeTemperatureColor(hottest),
            source = first?.let { context.getString(R.string.result_log_from, Format.celsius(it)) },
        ),
        LogEntry.Line(context.getString(R.string.charge_slowdown), details?.slowdownPercent?.let(Format::percentValue)),
        LogEntry.Line(
            context.getString(R.string.result_log_slow_hot),
            context.getString(if (details?.slowdownWhileHot == true) R.string.result_log_yes else R.string.result_log_no),
            color = if (details?.slowdownWhileHot == true) StressColors.Warn else StressColors.Text,
            tag = if (details?.slowdownWhileHot == true) LogTag.Warn else null,
        ),
    )
    return LogBlock(
        context.getString(R.string.temp_battery), lines,
        summary = Format.celsius(hottest),
        note = context.getString(R.string.charge_result_missing).takeIf { lines.any { it.value == null } },
    )
}

private fun chargeFindingBlock(context: Context, record: RunRecord): LogBlock? {
    val findings = chargeFindings(context, record)
    if (findings.isEmpty()) return null
    return LogBlock(
        context.getString(R.string.charge_findings), findings.map { LogEntry.Sentence(it, StressColors.Text) },
        summary = findings.size.toString(),
    )
}

private fun chargeReading(context: Context, record: RunRecord): LogBlock {
    val cadence = record.charge?.currentChangeSeconds
    return LogBlock(
        context.getString(R.string.result_log_readings),
        listOf(
            LogEntry.Line(context.getString(R.string.result_log_samples), record.series.seconds.size.toString()),
            // Null means the reading changed about every second, or too few changes to tell: not a missing value.
            LogEntry.Line(
                context.getString(R.string.result_log_cadence),
                cadence?.let { Format.number(it, 0) } ?: context.getString(R.string.result_log_cadence_normal),
                unit = if (cadence != null) "s" else null,
                color = if (cadence != null) StressColors.Warn else StressColors.Text,
                tag = if (cadence != null) LogTag.Warn else LogTag.Ok,
            ),
        ),
    )
}

private fun chargeCharts(context: Context, record: RunRecord): List<LogBlock> {
    val series = record.series
    val power = context.getString(R.string.charge_battery_power)
    val curve = ChargeAnalysis.percentCurve(series)
    val byPercent = if (curve.isEmpty()) {
        LogBlock(context.getString(R.string.charge_chart_percent), listOf(LogEntry.Sentence(context.getString(R.string.charge_no_curve))))
    } else {
        chart(context.getString(R.string.charge_chart_percent), "W") {
            TimeChart(
                curve.map(Point::seconds), listOf(ChartSeries(power, StressColors.Accent, curve.map(Point::value))), "W",
                height = 180.dp, yMin = 0.0, decimals = Format.WATT_DECIMALS,
                timeLabel = { Format.percentValue(it.toInt()) }, xEnd = 100.0, xStep = 25.0, xReadoutPrefix = "",
            )
        }
    }
    val lines = buildList {
        if (series.watts.any { it != null }) {
            add(ChartSeries(power, StressColors.Accent, series.watts, "W", decimals = Format.WATT_DECIMALS))
        }
        val temperatures = series.batteryCelsius.filterNotNull()
        if (temperatures.isNotEmpty()) {
            val range = if (isEmpty()) null else (temperatures.min() - 2)..(temperatures.max() + 2)
            add(ChartSeries(context.getString(R.string.temp_battery), StressColors.Cool, series.batteryCelsius, "°C", range))
        }
    }
    val overTime = if (lines.isEmpty()) {
        LogBlock(context.getString(R.string.charge_chart_time), listOf(LogEntry.Sentence(context.getString(R.string.charge_no_curve))))
    } else {
        val primaryPower = lines.first().color == StressColors.Accent
        chart(context.getString(R.string.charge_chart_time), if (primaryPower) "W" else "°C") {
            TimeChart(
                series.seconds, lines, if (primaryPower) "W" else "°C", height = 180.dp,
                yMin = 0.0.takeIf { primaryPower }, showAxisUnits = true,
            )
        }
    }
    return listOf(byPercent, overTime)
}
