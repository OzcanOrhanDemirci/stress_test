package dev.ozcan.stress.ui.charge

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
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
import dev.ozcan.stress.ui.components.EngineeringRow
import dev.ozcan.stress.ui.components.EngineeringSheet
import dev.ozcan.stress.ui.components.Hairline
import dev.ozcan.stress.ui.components.MeterBar
import dev.ozcan.stress.ui.components.MetricNumber
import dev.ozcan.stress.ui.components.MetricTile
import dev.ozcan.stress.ui.components.Panel
import dev.ozcan.stress.ui.components.SecondaryButton
import dev.ozcan.stress.ui.components.SectionLabel
import dev.ozcan.stress.ui.components.StatusChip
import dev.ozcan.stress.ui.components.StopButton
import dev.ozcan.stress.ui.components.TileRow
import dev.ozcan.stress.ui.components.TopBar
import dev.ozcan.stress.ui.context
import dev.ozcan.stress.ui.minutesText
import dev.ozcan.stress.ui.run.LogBlock
import dev.ozcan.stress.ui.run.LogItem
import dev.ozcan.stress.ui.run.logBlocks
import dev.ozcan.stress.ui.run.valueCount
import dev.ozcan.stress.ui.theme.NumberStyles
import dev.ozcan.stress.ui.theme.StressColors
import dev.ozcan.stress.ui.theme.StressType
import dev.ozcan.stress.ui.upper

/**
 * The charge test while it runs. Simple page (DESIGN "What each simple page keeps", Charge run): watts into the
 * battery, battery level and temperature, time to full, one sentence, the engineering row, stop. Everything else
 * the test reads is in the engineering sheet.
 */
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
    var engineering by rememberSaveable { mutableStateOf(false) }
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

    val status = when {
        failed -> R.string.charge_unavailable_short
        state.saving -> R.string.charge_saving
        phase == ChargePhase.Baseline -> R.string.charge_baseline
        phase == ChargePhase.Waiting -> R.string.charge_waiting
        phase == ChargePhase.Measuring && update.watts != null -> R.string.charge_measuring
        else -> R.string.charge_checking
    }
    val timer = when (phase) {
        ChargePhase.Baseline -> Format.clock(update.elapsedSeconds) + " / " + Format.clock(ChargeSession.BASELINE_SECONDS.toDouble())
        ChargePhase.Measuring -> Format.clock(update.elapsedSeconds) + " / " + stringResource(Labels.duration(duration, StressMode.Charge))
        else -> null
    }
    val log = chargeLog(state, stringResource(status), timer)

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            TopBar(
                title = stringResource(R.string.mode_charge),
                caption = timer,
                actions = { StatusChip(stringResource(status), if (failed) StressColors.Warn else StressColors.Cool) },
            )
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                Arrangement.spacedBy(12.dp),
            ) {
                if (!failed) ChargeHero(state)
                if (!failed && phase != ChargePhase.Preparing) {
                    TileRow(Modifier.height(IntrinsicSize.Min)) {
                        val tile = Modifier.weight(1f).fillMaxHeight()
                        MetricTile(stringResource(R.string.charge_battery_level), Format.percentValue(battery?.levelPercent), tile)
                        MetricTile(
                            stringResource(R.string.home_live_battery), Format.number(temperature, 1), tile,
                            unit = "°C", color = chargeTemperatureColor(temperature),
                        )
                        if (phase == ChargePhase.Measuring) {
                            MetricTile(
                                stringResource(R.string.charge_until_full),
                                state.remainingMillis?.let { minutesText(context(), it / 60_000) } ?: Format.MISSING, tile,
                            )
                        }
                    }
                }
                ChargeSentence(state, failed)
            }
            Hairline()
            Column(
                Modifier.fillMaxWidth().background(StressColors.Background).padding(horizontal = 16.dp, vertical = 8.dp),
                Arrangement.spacedBy(12.dp),
            ) {
                val values = log.valueCount()
                EngineeringRow(pluralStringResource(R.plurals.parts_values, values, values).upper(), { engineering = true })
                if (failed) {
                    SecondaryButton(stringResource(R.string.back), onLeave, Modifier.fillMaxWidth())
                } else {
                    StopButton(stringResource(R.string.charge_stop), { model.stop() }, enabled = !state.saving)
                }
            }
        }
        if (engineering) {
            EngineeringSheet(onDismiss = { engineering = false }) {
                logBlocks(log)
                if (phase == ChargePhase.Measuring) item(key = "chart") { ChargePercentChart(update.curve, height = 140.dp) }
            }
        }
    }
}

/** The hero: what the phase is about, in one large value where there is one. */
@Composable
private fun ChargeHero(state: ChargeRunState) {
    val update = state.update
    Panel(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (update.phase) {
                ChargePhase.Preparing -> SectionLabel(stringResource(R.string.charge_checking))
                ChargePhase.Baseline -> {
                    // The status chip already names the phase; the hero names the value.
                    SectionLabel(stringResource(R.string.charge_idle))
                    MetricNumber(Format.wattNumber(update.watts), "W", NumberStyles.Hero, StressColors.Accent)
                    MeterBar((update.elapsedSeconds / ChargeSession.BASELINE_SECONDS).toFloat())
                }
                ChargePhase.Waiting -> {
                    SectionLabel(stringResource(R.string.charge_wait_title))
                    Text(stringResource(R.string.charge_wait_text), style = StressType.Body)
                }
                ChargePhase.Measuring -> {
                    SectionLabel(stringResource(R.string.charge_battery_power))
                    MetricNumber(Format.wattNumber(update.watts), "W", NumberStyles.Hero, StressColors.Accent)
                }
            }
        }
    }
}

/** The one sentence: heat first, then what the phase asks of the person or what the reading means. */
@Composable
private fun ChargeSentence(state: ChargeRunState, failed: Boolean) {
    val update = state.update
    val temperature = update.battery?.temperatureCelsius ?: 0.0
    val (text, warning) = when {
        update.end == EndReason.Safety || temperature >= ChargeSession.STOP_CELSIUS -> R.string.charge_hot_stop to true
        temperature >= ChargeSession.WARN_CELSIUS -> R.string.charge_hot_warn to true
        failed -> (if (state.saveFailed) R.string.charge_save_failed else R.string.charge_unavailable) to true
        update.phase == ChargePhase.Preparing -> R.string.charge_checking_text to false
        update.phase == ChargePhase.Baseline -> R.string.charge_baseline_text to false
        update.phase == ChargePhase.Measuring && update.watts == null -> R.string.charge_identifying to false
        update.phase == ChargePhase.Measuring && update.watts == 0.0 -> R.string.charge_no_inflow to false
        else -> R.string.charge_keep_still to false
    }
    Text(stringResource(text), style = StressType.Body, color = if (warning) StressColors.Warn else StressColors.TextDim)
}

/** Everything the charge test reads, for its engineering sheet. */
@Composable
private fun chargeLog(state: ChargeRunState, status: String, timer: String?): List<LogBlock> {
    val update = state.update
    val battery = update.battery
    val missingNote = stringResource(R.string.charge_missing_readings)
    fun noteIf(lines: List<LogItem>) = missingNote.takeIf { lines.any { it.value == null || it.value == Format.MISSING } }

    val run = listOfNotNull(
        LogItem(stringResource(R.string.run_charge_line_status), status),
        timer?.let { LogItem(stringResource(R.string.run_line_elapsed), it) },
    )
    val input = update.idleWatts?.let { idle -> update.watts?.takeIf { it > 0.0 }?.plus(idle) }
    val temperature = battery?.temperatureCelsius
    val power = listOf(
        LogItem(stringResource(R.string.charge_battery_power), Format.wattNumber(update.watts), "W", StressColors.Accent),
        LogItem(stringResource(R.string.charge_idle), Format.wattNumber(update.idleWatts), "W", StressColors.Accent),
        LogItem(stringResource(R.string.charge_estimated_input), Format.wattNumber(input), "W", StressColors.Accent),
        LogItem(stringResource(R.string.charge_current), Format.number(update.amps, 2), "A"),
        LogItem(stringResource(R.string.charge_voltage), Format.number(battery?.let(ChargePower::volts), 2), "V"),
        LogItem(stringResource(R.string.charge_battery_level), Format.percentValue(battery?.levelPercent)),
        LogItem(stringResource(R.string.run_line_battery_temp), Format.number(temperature, 1), "°C", chargeTemperatureColor(temperature)),
        LogItem(stringResource(R.string.charge_source), battery?.plugType?.let { stringResource(plugLabel(it)) }),
        LogItem(
            stringResource(R.string.charge_full_estimate),
            state.remainingMillis?.let { minutesText(context(), it / 60_000) },
        ),
    )
    val readings = listOf(
        LogItem(stringResource(R.string.run_line_current_raw), battery?.currentRaw?.toString()),
        LogItem(
            stringResource(R.string.run_charge_line_fresh),
            battery?.let { stringResource(if (it.currentFresh) R.string.run_charge_fresh else R.string.run_charge_stale) },
        ),
        LogItem(stringResource(R.string.run_charge_line_voltage_age), Format.number(battery?.voltageAgeNanos?.div(1e9), 1), "s"),
        LogItem(stringResource(R.string.run_charge_line_broadcasts), battery?.broadcasts?.toString()),
        LogItem(stringResource(R.string.run_charge_line_counter), Format.number(battery?.chargeCounterMicroAmpHours?.div(1000.0), 0), "mAh"),
        LogItem(
            stringResource(R.string.run_charge_line_energy),
            Format.number(battery?.energyCounterNanoWattHours?.div(1e6), 0), "mWh",
        ),
    )
    return listOf(
        LogBlock(stringResource(R.string.run_charge_log_charge), run),
        LogBlock(
            stringResource(R.string.run_log_power), power,
            note = listOfNotNull(stringResource(R.string.charge_net_explained), noteIf(power)).joinToString(" "),
        ),
        LogBlock(stringResource(R.string.run_charge_log_readings), readings, note = noteIf(readings)),
    )
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
