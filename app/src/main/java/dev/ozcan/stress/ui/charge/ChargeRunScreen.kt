package dev.ozcan.stress.ui.charge

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.ozcan.stress.R
import dev.ozcan.stress.charge.ChargePhase
import dev.ozcan.stress.charge.ChargePower
import dev.ozcan.stress.charge.ChargeSession
import dev.ozcan.stress.graph
import dev.ozcan.stress.run.EndReason
import dev.ozcan.stress.run.StressDuration
import dev.ozcan.stress.run.StressMode
import dev.ozcan.stress.ui.Format
import dev.ozcan.stress.ui.KeepScreenOnEffect
import dev.ozcan.stress.ui.Labels
import dev.ozcan.stress.ui.components.Hairline
import dev.ozcan.stress.ui.components.InfoRow
import dev.ozcan.stress.ui.components.MeterBar
import dev.ozcan.stress.ui.components.MetricNumber
import dev.ozcan.stress.ui.components.MetricTile
import dev.ozcan.stress.ui.components.Panel
import dev.ozcan.stress.ui.components.SecondaryButton
import dev.ozcan.stress.ui.components.StatusChip
import dev.ozcan.stress.ui.components.StopButton
import dev.ozcan.stress.ui.components.TileRow
import dev.ozcan.stress.ui.components.TopBar
import dev.ozcan.stress.ui.context
import dev.ozcan.stress.ui.minutesText
import dev.ozcan.stress.ui.theme.NumberStyles
import dev.ozcan.stress.ui.theme.StressColors
import dev.ozcan.stress.ui.theme.StressType

@Composable
fun ChargeRunScreen(duration: StressDuration, onFinished: (String) -> Unit, onLeave: () -> Unit) {
    val graph = context().graph
    val model: ChargeViewModel = viewModel { ChargeViewModel(graph, duration) }
    val state by model.state.collectAsStateWithLifecycle()
    val update = state.update
    val failed = update.cannotMeasure || state.saveFailed
    val phase = update.phase
    val battery = update.battery
    val temperature = battery?.temperatureCelsius
    val color = chargeTemperatureColor(temperature)
    KeepScreenOnEffect()
    DimChargeDisplay()
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { model.stop(EndReason.Interrupted) }
    LaunchedEffect(model) {
        // Let the dimmed run screen replace Home before the first battery sample.
        withFrameNanos { }
        model.start()
    }
    LaunchedEffect(state.recordId) { state.recordId?.let(onFinished) }
    BackHandler { if (failed) onLeave() }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        val timer = when (phase) {
            ChargePhase.Baseline -> Format.clock(update.elapsedSeconds) + " / " + Format.clock(ChargeSession.BASELINE_SECONDS.toDouble())
            ChargePhase.Measuring -> Format.clock(update.elapsedSeconds) + " / " + stringResource(Labels.duration(duration, StressMode.Charge))
            else -> null
        }
        TopBar(
            title = stringResource(R.string.mode_charge),
            caption = timer,
            actions = {
                val status = when {
                    failed -> R.string.charge_unavailable_short
                    state.saving -> R.string.charge_saving
                    phase == ChargePhase.Baseline -> R.string.charge_baseline
                    phase == ChargePhase.Waiting -> R.string.charge_waiting
                    phase == ChargePhase.Measuring && update.watts != null -> R.string.charge_measuring
                    else -> R.string.charge_checking
                }
                StatusChip(stringResource(status), if (failed) StressColors.Warn else StressColors.Cool)
            },
        )
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            Arrangement.spacedBy(12.dp),
        ) {
            if (update.end == EndReason.Safety || (temperature ?: 0.0) >= ChargeSession.STOP_CELSIUS) {
                ChargeMessage(R.string.charge_hot_stop, warning = true)
            } else if ((temperature ?: 0.0) >= ChargeSession.WARN_CELSIUS) {
                ChargeMessage(R.string.charge_hot_warn, warning = true)
            }
            if (failed) {
                ChargeMessage(if (state.saveFailed) R.string.charge_save_failed else R.string.charge_unavailable, warning = true)
            } else when (phase) {
                ChargePhase.Preparing -> ChargeMessage(R.string.charge_checking_text)
                ChargePhase.Baseline -> Panel(Modifier.fillMaxWidth(), title = stringResource(R.string.charge_baseline)) {
                    MetricNumber(Format.wattNumber(update.watts), "W", NumberStyles.Hero, StressColors.Accent)
                    Text(stringResource(R.string.charge_baseline_text), style = StressType.BodySmall, color = StressColors.TextDim)
                    MeterBar((update.elapsedSeconds / ChargeSession.BASELINE_SECONDS).toFloat())
                }
                ChargePhase.Waiting -> Panel(Modifier.fillMaxWidth(), title = stringResource(R.string.charge_wait_title)) {
                    Text(stringResource(R.string.charge_wait_text), style = StressType.Body)
                    InfoRow(stringResource(R.string.charge_idle), Format.watts(update.idleWatts))
                }
                ChargePhase.Measuring -> Panel(Modifier.fillMaxWidth(), title = stringResource(R.string.charge_battery_power)) {
                    MetricNumber(Format.wattNumber(update.watts), "W", NumberStyles.Hero, StressColors.Accent)
                    Text(
                        stringResource(when (update.watts) {
                            null -> R.string.charge_identifying
                            0.0 -> R.string.charge_no_inflow
                            else -> R.string.charge_net_explained
                        }),
                        style = StressType.BodySmall, color = StressColors.TextDim,
                    )
                    val input = update.idleWatts?.let { idle -> update.watts?.takeIf { it > 0.0 }?.plus(idle) }
                    if (input != null) InfoRow(stringResource(R.string.charge_estimated_input), Format.watts(input))
                }
            }
            if (phase != ChargePhase.Preparing) {
                TileRow(Modifier.height(IntrinsicSize.Min)) {
                    MetricTile(stringResource(R.string.charge_current), Format.number(update.amps, 2), Modifier.weight(1f), unit = "A")
                    MetricTile(stringResource(R.string.charge_voltage), Format.number(battery?.let(ChargePower::volts), 2), Modifier.weight(1f), unit = "V")
                }
                TileRow(Modifier.height(IntrinsicSize.Min)) {
                    MetricTile(stringResource(R.string.charge_battery_level), Format.percentValue(battery?.levelPercent), Modifier.weight(1f).fillMaxHeight())
                    MetricTile(
                        stringResource(R.string.safety_reason_battery_hot), Format.number(temperature, 1), Modifier.weight(1f).fillMaxHeight(),
                        unit = "°C", color = color,
                    )
                }
            }
            if (phase == ChargePhase.Measuring) {
                Panel(Modifier.fillMaxWidth(), padding = 12.dp) {
                    InfoRow(
                        stringResource(R.string.charge_source),
                        battery?.plugType?.let { stringResource(plugLabel(it)) } ?: Format.MISSING,
                    )
                    state.remainingMillis?.let {
                        InfoRow(stringResource(R.string.charge_full_estimate), minutesText(context(), it / 60_000))
                    }
                }
            }
            if (!failed) Text(stringResource(R.string.charge_keep_still), style = StressType.BodySmall, color = StressColors.TextDim)
            if (!failed && battery != null && (battery.levelPercent == null || temperature == null || ChargePower.volts(battery) == null)) {
                Text(stringResource(R.string.charge_missing_readings), style = StressType.BodySmall, color = StressColors.TextDim)
            }
            if (phase == ChargePhase.Measuring && !failed) ChargePercentChart(update.curve, height = 124.dp)
        }
        Hairline()
        Column(Modifier.fillMaxWidth().background(StressColors.Background).padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (failed) {
                SecondaryButton(stringResource(R.string.back), onLeave, Modifier.fillMaxWidth())
            } else {
                StopButton(stringResource(R.string.charge_stop), { model.stop() }, enabled = !state.saving)
            }
        }
    }
}

@Composable
private fun ChargeMessage(text: Int, warning: Boolean = false) {
    Panel(Modifier.fillMaxWidth()) {
        Text(stringResource(text), style = StressType.Body, color = if (warning) StressColors.Warn else StressColors.Text)
    }
}

@Composable
private fun DimChargeDisplay() {
    val activity = LocalActivity.current ?: return
    DisposableEffect(activity) {
        val window = activity.window
        val previous = window.attributes.screenBrightness
        window.attributes = window.attributes.apply { screenBrightness = 0.10f }
        onDispose { window.attributes = window.attributes.apply { screenBrightness = previous } }
    }
}
