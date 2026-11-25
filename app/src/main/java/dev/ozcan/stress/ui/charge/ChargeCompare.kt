package dev.ozcan.stress.ui.charge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import dev.ozcan.stress.run.RunAnalysis
import dev.ozcan.stress.run.RunRecord
import dev.ozcan.stress.ui.Dates
import dev.ozcan.stress.ui.Format
import dev.ozcan.stress.ui.components.ChartSeries
import dev.ozcan.stress.ui.components.Hairline
import dev.ozcan.stress.ui.components.Panel
import dev.ozcan.stress.ui.components.TimeChart
import dev.ozcan.stress.ui.theme.NumberStyles
import dev.ozcan.stress.ui.theme.StressColors
import dev.ozcan.stress.ui.theme.StressType

@Composable
fun ChargeLoadComparisonMessage() {
    Panel(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(stringResource(R.string.charge_mixed_compare), style = StressType.Body)
    }
}

@Composable
fun ChargeCompare(first: RunRecord, second: RunRecord) {
    val a = stringResource(R.string.charge_compare_a)
    val b = stringResource(R.string.charge_compare_b)
    Column(
        Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        Arrangement.spacedBy(12.dp),
    ) {
        Panel(Modifier.fillMaxWidth(), title = stringResource(R.string.compare_metrics)) {
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(a, style = StressType.Heading, color = StressColors.Accent)
                    Text(Dates.short(first.startedAtMillis), style = StressType.BodySmall, color = StressColors.TextDim)
                    first.charge?.plugType?.let { Text(stringResource(plugLabel(it)), style = StressType.BodySmall) }
                }
                Column(Modifier.weight(1f)) {
                    Text(b, style = StressType.Heading, color = StressColors.Cool)
                    Text(Dates.short(second.startedAtMillis), style = StressType.BodySmall, color = StressColors.TextDim)
                    second.charge?.plugType?.let { Text(stringResource(plugLabel(it)), style = StressType.BodySmall) }
                }
            }
            val left = comparisonReadings(first)
            val right = comparisonReadings(second)
            left.zip(right).forEach { (l, r) ->
                Hairline()
                Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(6.dp), Alignment.CenterVertically) {
                    Text(stringResource(l.first), Modifier.weight(1.5f), style = StressType.BodySmall, color = StressColors.TextDim)
                    Text(l.second, Modifier.weight(1f), style = NumberStyles.Tiny)
                    Text(r.second, Modifier.weight(1f), style = NumberStyles.Tiny)
                }
            }
        }
        Panel(Modifier.fillMaxWidth(), title = stringResource(R.string.charge_chart_percent)) {
            val left = remember(first) { ChargeAnalysis.percentCurve(first.series).associate { it.seconds to it.value } }
            val right = remember(second) { ChargeAnalysis.percentCurve(second.series).associate { it.seconds to it.value } }
            if (left.isEmpty() && right.isEmpty()) {
                Text(stringResource(R.string.charge_no_curve), style = StressType.BodySmall, color = StressColors.TextDim)
            } else {
                val levels = (left.keys + right.keys).sorted()
                val lines = listOf(
                    ChartSeries(a, StressColors.Accent, levels.map(left::get)),
                    ChartSeries(b, StressColors.Cool, levels.map(right::get)),
                )
                TimeChart(
                    levels, lines, "W", yMin = 0.0, decimals = Format.WATT_DECIMALS,
                    timeLabel = { Format.percentValue(it.toInt()) }, xEnd = 100.0, xStep = 25.0, xReadoutPrefix = "",
                )
                Text(stringResource(R.string.charge_compare_matching_level), style = StressType.BodySmall, color = StressColors.TextDim)
            }
        }
    }
}

private fun comparisonReadings(record: RunRecord): List<Pair<Int, String>> {
    val summary = record.summary
    return listOf(
        R.string.charge_peak to Format.watts(summary.peakWatts),
        R.string.charge_sustained to Format.watts(summary.sustainedWatts),
        R.string.charge_slowdown to Format.percentValue(record.charge?.slowdownPercent),
        R.string.charge_energy to (summary.energyWattHours?.let { "${Format.number(it, 2)} Wh" } ?: Format.MISSING),
        R.string.charge_rate to Format.percent(record.charge?.percentPerTenMinutes?.div(100), 1),
        R.string.charge_max_temp to Format.celsius(summary.maxTemperatures[RunAnalysis.BATTERY]),
    )
}
