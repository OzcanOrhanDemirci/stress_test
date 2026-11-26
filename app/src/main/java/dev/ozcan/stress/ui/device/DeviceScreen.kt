package dev.ozcan.stress.ui.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.ozcan.stress.AppGraph
import dev.ozcan.stress.R
import dev.ozcan.stress.device.DeviceInfo
import dev.ozcan.stress.graph
import dev.ozcan.stress.telemetry.SensorAvailability
import dev.ozcan.stress.telemetry.SysfsLayout
import dev.ozcan.stress.ui.Format
import dev.ozcan.stress.ui.LiveView
import dev.ozcan.stress.ui.components.EngineeringPage
import dev.ozcan.stress.ui.components.EngineeringRow
import dev.ozcan.stress.ui.components.Hairline
import dev.ozcan.stress.ui.components.LogLine
import dev.ozcan.stress.ui.components.MetricTile
import dev.ozcan.stress.ui.components.Panel
import dev.ozcan.stress.ui.components.SectionLabel
import dev.ozcan.stress.ui.components.Stack
import dev.ozcan.stress.ui.components.Temperatures
import dev.ozcan.stress.ui.components.TileRow
import dev.ozcan.stress.ui.components.TopBar
import dev.ozcan.stress.ui.components.TwoPages
import dev.ozcan.stress.ui.components.logSection
import dev.ozcan.stress.ui.context
import dev.ozcan.stress.ui.theme.NumberStyles
import dev.ozcan.stress.ui.theme.StressColors
import dev.ozcan.stress.ui.theme.StressType
import dev.ozcan.stress.ui.upper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Live readings at a calm rate (DESIGN "Live values"): every [periodMillis], the mean of the samples taken in that
 * period. Only this collection is slowed; the sampler keeps its own rate for tests and safety.
 */
internal fun AppGraph.liveEvery(periodMillis: Long): Flow<LiveView?> {
    val window = ((LiveView.WINDOW_SAMPLES - 1) * periodMillis / 1_000L).toInt() + 1
    return flow {
        while (true) {
            val view = LiveView.from(sampler.log.recent(window), layout)
            emit(view)
            // Before the sampler's first samples, look again soon instead of leaving the screen empty for a period.
            delay(if (view == null) 200L else periodMillis)
        }
    }.flowOn(Dispatchers.Default)
}

/** The device screen's hardware facts and its live readings, refreshed every [REFRESH_SECONDS]. */
class DeviceViewModel(graph: AppGraph) : ViewModel() {

    val availability: SensorAvailability = graph.sensors.availability
    val layout: SysfsLayout = graph.layout
    val cpuClusters: List<String> = DeviceInfo.cpuLabels(graph.layout, includeClocks = false)

    val live: StateFlow<LiveView?> = graph.liveEvery(REFRESH_SECONDS * 1_000L)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(1_000), null)

    private val _device = MutableStateFlow<DeviceInfo?>(null)
    val device: StateFlow<DeviceInfo?> = _device.asStateFlow()

    init {
        viewModelScope.launch { _device.value = graph.device.await() }
    }

    companion object {
        /** The owner's choice: clocks and temperatures that move ten times a second cannot be read. */
        const val REFRESH_SECONDS = 2
    }
}

@Composable
fun DeviceScreen() {
    val graph = context().graph
    val model: DeviceViewModel = viewModel { DeviceViewModel(graph) }
    val device by model.device.collectAsStateWithLifecycle()
    val live by model.live.collectAsStateWithLifecycle()
    var engineering by rememberSaveable { mutableStateOf(false) }

    val blocks = deviceLog(device, live, model)
    TwoPages(engineering, engineeringPage = {
        EngineeringPage(stringResource(R.string.device_title), onBack = { engineering = false }) {
            // Indexed keys: two clusters can share a role and so a title.
            blocks.forEachIndexed { index, block ->
                logSection(block.title, block.summary, block.note, key = index) {
                    items(block.lines) { line ->
                        LogLine(line.key, line.value, line.unit, line.color, line.tag, line.source)
                    }
                }
            }
        }
    }) {
        SimplePage(device, live, model) { engineering = true }
    }
}

@Composable
private fun SimplePage(
    device: DeviceInfo?,
    live: LiveView?,
    model: DeviceViewModel,
    onEngineering: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        TopBar(stringResource(R.string.device_title), modifier = Modifier.statusBarsPadding())
        Stack(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            PhonePanel(device)
            BatteryTiles(live)
            FitPanel(live, model.availability)
            Spacer(Modifier.height(4.dp))
            EngineeringRow(onEngineering)
        }
    }
}

/** Phone, chip, GPU and Vulkan: what the phone is, as one readout. */
@Composable
private fun PhonePanel(device: DeviceInfo?) {
    Panel(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.device_v2_phone).upper(), style = StressType.Label, color = StressColors.TextDim)
        if (device == null) {
            Text(
                stringResource(R.string.device_waiting_hardware), Modifier.padding(top = 4.dp),
                style = StressType.Body, color = StressColors.TextDim,
            )
            return@Panel
        }
        Text(device.title, Modifier.padding(top = 4.dp), style = StressType.Title)
        Text(
            "ANDROID ${device.androidVersion} // API ${device.sdk} // ${device.cpuCount} CPU",
            Modifier.padding(top = 2.dp), style = StressType.Code, color = StressColors.TextDim,
        )
        Hairline(Modifier.padding(vertical = 12.dp))
        Stack(spacing = 10) {
            SpecLine(stringResource(R.string.device_soc), device.soc)
            SpecLine(stringResource(R.string.device_gpu), device.gpu)
            SpecLine("Vulkan", device.vulkan ?: stringResource(R.string.device_no_vulkan), missing = device.vulkan == null)
        }
    }
}

@Composable
private fun SpecLine(label: String, value: String?, missing: Boolean = value == null) {
    Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(12.dp), Alignment.CenterVertically) {
        Text(label.upper(), style = StressType.Label, color = StressColors.TextDim, maxLines = 1)
        Text(
            value ?: Format.MISSING, Modifier.weight(1f),
            style = StressType.Body, color = if (missing) StressColors.TextFaint else StressColors.Text,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun BatteryTiles(live: LiveView?) {
    Stack(spacing = 8) {
        SectionLabel(stringResource(R.string.device_v2_battery))
        // Equal heights: only the level tile carries a meter.
        TileRow(Modifier.height(IntrinsicSize.Min)) {
            val level = live?.levelPercent
            MetricTile(
                stringResource(R.string.device_v2_level),
                Format.percentValue(level),
                Modifier.weight(1f).fillMaxHeight(),
                style = NumberStyles.Large,
                meter = level?.let { it / 100f },
            )
            val celsius = live?.batteryCelsius
            MetricTile(
                stringResource(R.string.device_v2_temperature),
                Format.number(celsius, 1),
                Modifier.weight(1f).fillMaxHeight(),
                unit = "°C",
                color = Temperatures.battery(celsius),
                style = NumberStyles.Large,
            )
        }
    }
}

/** One sentence of how well the app can read this phone, the missing readings named. */
@Composable
private fun FitPanel(live: LiveView?, availability: SensorAvailability) {
    Panel(Modifier.fillMaxWidth()) {
        if (live == null) {
            Text(stringResource(R.string.device_waiting), style = StressType.Body, color = StressColors.TextDim)
            return@Panel
        }
        val readings = deviceReadings(live, availability)
        val missing = readings.filterNot { it.second }.map { it.first }
        Text(
            stringResource(R.string.device_v2_fit, readings.size - missing.size, readings.size),
            style = StressType.Heading,
        )
        Text(
            if (missing.isEmpty()) stringResource(R.string.device_v2_fit_all)
            else stringResource(R.string.device_v2_fit_missing, missing.joinToString(", ")),
            Modifier.padding(top = 4.dp),
            style = StressType.BodySmall,
            color = if (missing.isEmpty()) StressColors.Good else StressColors.TextDim,
        )
    }
}

/** Keep small reported clocks readable instead of rounding them down to zero MHz. */
internal fun deviceClock(khz: Long?): String = when {
    khz == null || khz <= 0 -> Format.MISSING
    khz < 1000 -> "$khz kHz"
    else -> Format.mhz(khz)
}
