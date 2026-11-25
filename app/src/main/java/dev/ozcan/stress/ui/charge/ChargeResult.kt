package dev.ozcan.stress.ui.charge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import dev.ozcan.stress.charge.ChargeAnalysis
import dev.ozcan.stress.charge.ChargeSpeed
import dev.ozcan.stress.run.EndReason
import dev.ozcan.stress.run.RunAnalysis
import dev.ozcan.stress.run.RunRecord
import dev.ozcan.stress.ui.Dates
import dev.ozcan.stress.ui.Format
import dev.ozcan.stress.ui.Labels
import dev.ozcan.stress.ui.components.ChartSeries
import dev.ozcan.stress.ui.components.Hairline
import dev.ozcan.stress.ui.components.InfoRow
import dev.ozcan.stress.ui.components.MetricNumber
import dev.ozcan.stress.ui.components.MetricTile
import dev.ozcan.stress.ui.components.Panel
import dev.ozcan.stress.ui.components.SectionLabel
import dev.ozcan.stress.ui.components.StatusChip
import dev.ozcan.stress.ui.components.TileRow
import dev.ozcan.stress.ui.components.TimeChart
import dev.ozcan.stress.ui.theme.NumberStyles
import dev.ozcan.stress.ui.theme.StressColors
import dev.ozcan.stress.ui.theme.StressType

@Composable
fun ChargeResult(record: RunRecord, export: @Composable () -> Unit) {
    val summary = record.summary
    val reason = Labels.endReasonOf(record)
    val details = record.charge
    val peak = summary.peakWatts
    val maxTemperature = summary.maxTemperatures[RunAnalysis.BATTERY]
    val percentCurve = remember(record) { ChargeAnalysis.percentCurve(record.series) }
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
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {
                Text(Dates.short(record.startedAtMillis), Modifier.weight(1f), style = StressType.Code, color = StressColors.TextDim)
                StatusChip(stringResource(Labels.endReasonShort(reason)), Labels.endReasonColor(reason))
            }
            SectionLabel(stringResource(R.string.charge_peak), caption = Format.clock(record.loadSeconds))
            MetricNumber(Format.number(peak, 1), "W", NumberStyles.Hero, StressColors.Accent)
            Text(stringResource(R.string.charge_net_explained), style = StressType.BodySmall, color = StressColors.TextDim)
        }
        TileRow(Modifier.height(IntrinsicSize.Min)) {
            MetricTile(
                stringResource(R.string.charge_sustained), Format.number(summary.sustainedWatts, 1), Modifier.weight(1f).fillMaxHeight(),
                unit = "W", color = StressColors.Accent, caption = stringResource(R.string.charge_sustained_note),
            )
            MetricTile(
                stringResource(R.string.charge_slowdown), Format.percentValue(details?.slowdownPercent), Modifier.weight(1f).fillMaxHeight(),
            )
        }
        TileRow(Modifier.height(IntrinsicSize.Min)) {
            MetricTile(
                stringResource(R.string.charge_energy), Format.number(summary.energyWattHours, 2), Modifier.weight(1f).fillMaxHeight(), unit = "Wh",
            )
            MetricTile(
                stringResource(R.string.charge_rate), Format.percent(details?.percentPerTenMinutes?.div(100), 1),
                Modifier.weight(1f).fillMaxHeight(),
            )
        }
        val start = summary.batteryStartPercent
        val end = summary.batteryEndPercent
        Panel(Modifier.fillMaxWidth(), padding = 12.dp) {
            InfoRow(stringResource(R.string.charge_max_temp), Format.celsius(maxTemperature), valueColor = chargeTemperatureColor(maxTemperature))
            if (start != null && end != null) {
                InfoRow(stringResource(R.string.home_live_battery), "${Format.percentValue(start)} → ${Format.percentValue(end)}")
            }
            details?.plugType?.let { InfoRow(stringResource(R.string.charge_source), stringResource(plugLabel(it))) }
        }
        if (peak == null || summary.sustainedWatts == null || details?.slowdownPercent == null || summary.energyWattHours == null ||
            details.percentPerTenMinutes == null || maxTemperature == null
        ) {
            Text(stringResource(R.string.charge_result_missing), style = StressType.BodySmall, color = StressColors.TextDim)
        }
        ChargeFindings(record)
        ChargePercentChart(percentCurve)
        ChargeTimeChart(record)
        export()
    }
}

@Composable
private fun ChargeFindings(record: RunRecord) {
    val peak = record.summary.peakWatts
    val details = record.charge
    val findings = buildList {
        if (peak != null && peak > 0.0) {
            val speed = when (ChargeAnalysis.speed(peak)) {
                ChargeSpeed.UsbPort -> R.string.charge_speed_usb
                ChargeSpeed.Standard -> R.string.charge_speed_standard
                ChargeSpeed.Fast -> R.string.charge_speed_fast
                ChargeSpeed.HighPower -> R.string.charge_speed_high
            }
            add(stringResource(speed, Format.watts(peak)))
        }
        if (details?.slowdownWhileHot == true) add(stringResource(R.string.charge_heat_finding))
        if ((record.summary.batteryStartPercent ?: 0) > 80) add(stringResource(R.string.charge_high_start))
        details?.currentChangeSeconds?.let {
            add(stringResource(R.string.charge_sparse, Format.number(it, 0)))
        }
    }
    if (findings.isEmpty()) return
    Panel(Modifier.fillMaxWidth(), title = stringResource(R.string.charge_findings)) {
        findings.forEachIndexed { index, text ->
            if (index > 0) Hairline()
            Text(text, Modifier.padding(vertical = 4.dp), style = StressType.Body)
        }
    }
}

@Composable
private fun ChargeTimeChart(record: RunRecord) {
    val series = record.series
    val power = stringResource(R.string.charge_battery_power)
    val battery = stringResource(R.string.temp_battery)
    val lines = buildList {
        if (series.watts.any { it != null }) add(ChartSeries(power, StressColors.Accent, series.watts, "W"))
        val min = series.batteryCelsius.filterNotNull().minOrNull()
        val max = series.batteryCelsius.filterNotNull().maxOrNull()
        if (min != null && max != null) {
            val range = if (isEmpty()) null else (min - 2)..(max + 2)
            add(ChartSeries(battery, StressColors.Cool, series.batteryCelsius, "°C", range))
        }
    }
    Panel(Modifier.fillMaxWidth(), title = stringResource(R.string.charge_chart_time)) {
        if (lines.isEmpty()) {
            Text(stringResource(R.string.charge_no_curve), style = StressType.BodySmall, color = StressColors.TextDim)
        } else {
            TimeChart(series.seconds, lines, if (lines.first().color == StressColors.Accent) "W" else "°C",
                yMin = 0.0.takeIf { lines.first().color == StressColors.Accent })
        }
    }
}
