package dev.ozcan.stress.ui.charge

import android.os.BatteryManager
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ozcan.stress.R
import dev.ozcan.stress.analysis.Point
import dev.ozcan.stress.charge.ChargeSession
import dev.ozcan.stress.ui.Format
import dev.ozcan.stress.ui.components.ChartSeries
import dev.ozcan.stress.ui.components.Panel
import dev.ozcan.stress.ui.components.TimeChart
import dev.ozcan.stress.ui.theme.StressColors
import dev.ozcan.stress.ui.theme.StressType

@StringRes
internal fun plugLabel(plug: Int): Int = when {
    plug and BatteryManager.BATTERY_PLUGGED_AC != 0 -> R.string.charge_plug_ac
    plug and BatteryManager.BATTERY_PLUGGED_USB != 0 -> R.string.charge_plug_usb
    plug and BatteryManager.BATTERY_PLUGGED_WIRELESS != 0 -> R.string.charge_plug_wireless
    plug and BatteryManager.BATTERY_PLUGGED_DOCK != 0 -> R.string.charge_plug_dock
    plug == 0 -> R.string.charge_unplugged
    else -> R.string.reading_unavailable
}

internal fun chargeTemperatureColor(temperature: Double?) =
    StressColors.temperature(temperature, ChargeSession.WARN_CELSIUS, ChargeSession.STOP_CELSIUS)

@Composable
internal fun ChargePercentChart(points: List<Point>, height: Dp = 160.dp) {
    val name = stringResource(R.string.charge_battery_power)
    Panel(Modifier.fillMaxWidth(), title = stringResource(R.string.charge_chart_percent)) {
        if (points.isEmpty()) {
            Text(stringResource(R.string.charge_no_curve), style = StressType.BodySmall, color = StressColors.TextDim)
        } else {
            TimeChart(
                points.map(Point::seconds), listOf(ChartSeries(name, StressColors.Accent, points.map(Point::value))), "W",
                height = height, yMin = 0.0, decimals = Format.WATT_DECIMALS,
                timeLabel = { Format.percentValue(it.toInt()) }, xEnd = 100.0, xStep = 25.0, xReadoutPrefix = "",
            )
        }
    }
}
