package dev.ozcan.stress.ui.device

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.ozcan.stress.AppGraph
import dev.ozcan.stress.R
import dev.ozcan.stress.device.DeviceInfo
import dev.ozcan.stress.engine.CoreAssignment
import dev.ozcan.stress.engine.CpuKernel
import dev.ozcan.stress.engine.GpuBurner
import dev.ozcan.stress.engine.GpuRequest
import dev.ozcan.stress.engine.GpuState
import dev.ozcan.stress.engine.StartResult
import dev.ozcan.stress.graph
import dev.ozcan.stress.safety.SafetyLimits
import dev.ozcan.stress.telemetry.SensorAvailability
import dev.ozcan.stress.telemetry.ThermalGroup
import dev.ozcan.stress.telemetry.ThermalReader
import dev.ozcan.stress.ui.Format
import dev.ozcan.stress.ui.GpuSurface
import dev.ozcan.stress.ui.Labels
import dev.ozcan.stress.ui.LiveView
import dev.ozcan.stress.ui.components.Hairline
import dev.ozcan.stress.ui.components.InfoRow
import dev.ozcan.stress.ui.components.MeterBar
import dev.ozcan.stress.ui.components.MetricNumber
import dev.ozcan.stress.ui.components.Panel
import dev.ozcan.stress.ui.components.PrimaryButton
import dev.ozcan.stress.ui.components.SectionLabel
import dev.ozcan.stress.ui.components.Stack
import dev.ozcan.stress.ui.components.StatusChip
import dev.ozcan.stress.ui.components.StopButton
import dev.ozcan.stress.ui.components.TopBar
import dev.ozcan.stress.ui.context
import dev.ozcan.stress.ui.theme.NumberStyles
import dev.ozcan.stress.ui.theme.StressColors
import dev.ozcan.stress.ui.theme.StressMotion
import dev.ozcan.stress.ui.theme.StressType
import dev.ozcan.stress.ui.upper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val Limits = SafetyLimits()

/** The device screen's live readings and its manual load tests, which stop when the screen goes. */
class DeviceViewModel(private val graph: AppGraph) : ViewModel() {

    val kernels: List<CpuKernel> = graph.cpu.kernels
    val burners: List<GpuBurner> = graph.gpu.burners
    val availability: SensorAvailability = graph.sensors.availability
    val cpuClusters: List<String> = DeviceInfo.cpuLabels(graph.layout, includeClocks = false)
    val gpuBusyPath: String? = graph.layout.gpuBusyPath

    val live: StateFlow<LiveView?> = graph.sampler.latest
        .map { LiveView.from(graph.sampler.log.recent(LiveView.WINDOW_SAMPLES), graph.layout) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(1_000), null)

    private val _device = MutableStateFlow<DeviceInfo?>(null)
    val device: StateFlow<DeviceInfo?> = _device.asStateFlow()

    private val _gpuTests = MutableStateFlow<Boolean?>(null)
    val gpuTests: StateFlow<Boolean?> = _gpuTests.asStateFlow()

    private val _kernel = MutableStateFlow(kernels.firstOrNull { it.key == "fp32_l2" } ?: kernels.first())
    val kernel: StateFlow<CpuKernel> = _kernel.asStateFlow()

    private val _burner = MutableStateFlow(burners.first())
    val burner: StateFlow<GpuBurner> = _burner.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        viewModelScope.launch { _device.value = graph.device.await() }
        viewModelScope.launch { _gpuTests.value = graph.capabilities.await().gpuTests }
    }

    fun select(kernel: CpuKernel) {
        _kernel.value = kernel
    }

    fun select(burner: GpuBurner) {
        _burner.value = burner
    }

    fun startCpu() {
        val kernel = _kernel.value
        viewModelScope.launch(Dispatchers.Default) {
            graph.cpu.stop()
            val result = graph.cpu.start(CoreAssignment.uniform(kernel, graph.cpu.cpuCount))
            _message.value = if (result == StartResult.Started) null else result.name
        }
    }

    fun stopCpu() {
        viewModelScope.launch(Dispatchers.Default) { graph.cpu.stop() }
    }

    fun startGpu() = graph.gpu.requestAsync(GpuRequest(_burner.value, scene = false))

    fun showPreview() = graph.gpu.requestAsync(GpuRequest(null, scene = false))

    override fun onCleared() {
        graph.cpu.stop()
        graph.gpu.requestAsync(null)
    }
}

@Composable
fun DeviceScreen() {
    val graph = context().graph
    val model: DeviceViewModel = viewModel { DeviceViewModel(graph) }
    val device by model.device.collectAsStateWithLifecycle()
    val live by model.live.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        TopBar(stringResource(R.string.device_title), modifier = Modifier.statusBarsPadding())
        Stack(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            HardwareCard(device, model.cpuClusters)
            val readings = live
            if (readings == null) {
                Text(stringResource(R.string.device_waiting), style = StressType.Body, color = StressColors.TextDim)
            } else {
                PowerCard(readings)
                ClockCard(readings, model.availability)
                TemperatureCard(readings, model.availability)
            }
            SensorCard(model.availability, model.gpuBusyPath)
            AdvancedCard(model, readings)
        }
    }
}

@Composable
private fun DeviceSection(@StringRes label: Int, content: @Composable ColumnScope.() -> Unit) {
    Stack(spacing = 8) {
        SectionLabel(stringResource(label))
        Panel(Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
private fun HardwareCard(device: DeviceInfo?, cpuClusters: List<String>) {
    DeviceSection(R.string.device_section_hardware) {
        if (device == null) {
            Text(stringResource(R.string.device_waiting_hardware), style = StressType.Body, color = StressColors.TextDim)
            return@DeviceSection
        }
        Text(device.title, style = StressType.Heading, modifier = Modifier.padding(bottom = 8.dp))
        Reading(stringResource(R.string.device_soc), device.soc, R.string.device_soc_missing)
        Reading(stringResource(R.string.device_cores), device.cpuCount.toString(), R.string.device_cpu_missing)
        Reading(
            stringResource(R.string.device_cpu_clusters),
            cpuClusters.takeIf { it.isNotEmpty() }?.joinToString("\n"),
            R.string.device_cpu_missing,
        )
        Hairline(Modifier.padding(vertical = 4.dp))
        Reading(stringResource(R.string.device_gpu), device.gpu, R.string.device_gpu_missing)
        Reading("Vulkan", device.vulkan, R.string.device_vulkan_missing)
        Hairline(Modifier.padding(vertical = 4.dp))
        InfoRow(stringResource(R.string.device_android), "${device.androidVersion} (API ${device.sdk})")
        Reading(stringResource(R.string.device_ram), device.ramGigabytes?.let { "${Format.number(it, 1)} GB" }, R.string.device_ram_missing)
        Reading(stringResource(R.string.device_display), device.display, R.string.device_display_missing, valueWeight = 2f)
    }
}

@Composable
private fun PowerCard(live: LiveView) {
    DeviceSection(R.string.device_section_power) {
        val powerUnavailable = if (live.plugged) R.string.device_power_plugged else R.string.device_power_missing
        Text(stringResource(R.string.device_battery_output).upper(), style = StressType.Label, color = StressColors.TextDim)
        MetricNumber(Format.number(live.watts, 2), "W", NumberStyles.Large, StressColors.Accent)
        if (live.watts == null) {
            Text(stringResource(powerUnavailable), style = StressType.BodySmall, color = StressColors.TextDim)
        }
        Hairline(Modifier.padding(vertical = 8.dp))
        Reading(stringResource(R.string.device_battery_level), Format.percentValue(live.levelPercent), R.string.device_level_missing)
        val battery = live.batteryCelsius
        Reading(
            stringResource(R.string.device_battery_temperature),
            Format.celsius(battery),
            R.string.device_battery_temp_missing,
            StressColors.temperature(battery, Limits.batteryWarn, Limits.batteryStop),
        )
        Reading(
            stringResource(R.string.device_current),
            live.dischargeAmps?.let { "${Format.number(it, 3)} A" },
            if (live.plugged) R.string.device_current_plugged else R.string.device_current_missing,
        )
        Reading(stringResource(R.string.device_current_raw), live.currentRaw?.toString(), R.string.device_raw_missing)
        Reading(stringResource(R.string.device_voltage), live.volts?.let { "${Format.number(it, 3)} V" }, R.string.device_voltage_missing)
    }
}

@Composable
private fun ClockCard(live: LiveView, availability: SensorAvailability) {
    DeviceSection(R.string.device_section_clocks) {
        val clusters = live.clusters
        if (clusters.isEmpty()) {
            Reading(stringResource(R.string.device_cpu), null, R.string.device_clocks_missing)
        }
        clusters.forEachIndexed { index, cluster ->
            val readable = availability.clusters.getOrNull(index)?.second == true
            Text(
                stringResource(R.string.title_with_part, stringResource(Labels.role(cluster.role)), cluster.label),
                style = StressType.Heading,
            )
            Reading(
                stringResource(R.string.device_clock_now),
                deviceClock(cluster.khz),
                if (readable) R.string.device_clock_no_sample else R.string.device_clock_not_readable,
            )
            Reading(
                stringResource(R.string.device_clock_max),
                deviceClock(cluster.maxKhz),
                R.string.device_clock_max_missing,
            )
            cluster.load?.let {
                MeterBar(it, color = Labels.clusterColor(index, clusters.size), modifier = Modifier.padding(top = 4.dp))
            }
            Hairline(Modifier.padding(vertical = 8.dp))
        }
        val estimated = live.gpuBusy == null && live.gpuLoad != null
        Reading(
            stringResource(R.string.run_gpu_load),
            Format.percent(live.gpuLoad),
            if (availability.gpuBusy) R.string.device_gpu_busy_no_sample else R.string.device_gpu_busy_missing,
            detail = if (estimated) stringResource(R.string.device_gpu_busy_estimated) else null,
        )
    }
}

/** Keep small reported clocks readable instead of rounding them down to zero MHz. */
internal fun deviceClock(khz: Long?): String = when {
    khz == null || khz <= 0 -> Format.MISSING
    khz < 1000 -> "$khz kHz"
    else -> Format.mhz(khz)
}

@Composable
private fun TemperatureCard(live: LiveView, availability: SensorAvailability) {
    val context = context()
    DeviceSection(R.string.chart_temperature) {
        Reading(
            stringResource(R.string.device_thermal_status),
            live.thermalStatus.takeUnless { it == ThermalReader.UNAVAILABLE }?.let { stringResource(Labels.thermalStatus(it)) },
            R.string.device_status_missing,
        )
        Reading(
            stringResource(R.string.device_headroom),
            Format.number(live.headroom?.toDouble(), 2),
            R.string.device_headroom_missing,
        )
        Hairline(Modifier.padding(vertical = 8.dp))
        ThermalGroup.entries.forEach { group ->
            val value = live.temperatures[group]
            val color = when (group) {
                ThermalGroup.Cpu, ThermalGroup.Gpu -> StressColors.temperature(value, Limits.chipWarn, Limits.chipStop)
                ThermalGroup.Skin -> StressColors.temperature(value, Limits.skinWarn, Limits.skinStop)
                else -> StressColors.Text
            }
            val reason = when {
                availability.totalZones(group) == 0 -> R.string.device_zone_missing
                !availability.readable(group) -> R.string.device_zone_not_readable
                else -> R.string.device_zone_no_sample
            }
            Reading(Labels.temperature(context, group.key), Format.celsius(value), reason, color)
        }
    }
}

@Composable
private fun SensorCard(availability: SensorAvailability, gpuBusyPath: String?) {
    val context = context()
    DeviceSection(R.string.device_section_sensors) {
        Text(stringResource(R.string.device_sensors_detail), style = StressType.BodySmall, color = StressColors.TextDim)
        Hairline(Modifier.padding(vertical = 8.dp))
        val zones = availability.zones.groupBy { it.first.group }
        if (zones.isEmpty()) {
            Reading(stringResource(R.string.device_thermal_zones), null, R.string.device_zone_missing)
        }
        zones.forEach { (group, entries) ->
            Text(
                stringResource(R.string.device_zones_count, Labels.temperature(context, group.key), availability.readableZones(group), entries.size),
                style = StressType.Heading,
                modifier = Modifier.padding(top = 4.dp),
            )
            entries.forEach { (zone, readable) ->
                SensorRow(zone.type, readable, R.string.device_zone_not_readable)
            }
            Hairline(Modifier.padding(vertical = 8.dp))
        }
        val counter = stringResource(R.string.device_gpu_counter)
        val fileName = gpuBusyPath?.substringAfterLast('/')
        val label = if (fileName == null) counter else stringResource(R.string.title_with_part, counter, fileName)
        SensorRow(label, availability.gpuBusy, R.string.device_gpu_busy_missing)
    }
}

@Composable
private fun SensorRow(label: String, readable: Boolean, @StringRes missingReason: Int) {
    if (readable) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, Modifier.weight(1f), style = StressType.Code, color = StressColors.TextDim)
            StatusChip(stringResource(R.string.device_readable), StressColors.Good)
        }
    } else {
        Reading(label, null, missingReason)
    }
}

@Composable
private fun Reading(
    label: String,
    value: String?,
    @StringRes missingReason: Int,
    color: Color = StressColors.Text,
    detail: String? = null,
    valueWeight: Float = 1.2f,
) {
    val missing = value == null || value == Format.MISSING
    InfoRow(
        label,
        value ?: Format.MISSING,
        valueColor = if (missing) StressColors.TextDim else color,
        detail = if (missing) stringResource(missingReason) else detail,
        valueWeight = valueWeight,
    )
}

@Composable
private fun AdvancedCard(model: DeviceViewModel, live: LiveView?) {
    val context = context()
    var open by rememberSaveable { mutableStateOf(false) }
    val kernel by model.kernel.collectAsStateWithLifecycle()
    val burner by model.burner.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    val gpuTests by model.gpuTests.collectAsStateWithLifecycle()
    DeviceSection(R.string.device_section_advanced) {
        Panel(Modifier.fillMaxWidth(), padding = 12.dp, onClick = { open = !open }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stack(Modifier.weight(1f), spacing = 4) {
                    Text(stringResource(R.string.device_advanced), style = StressType.Heading)
                    Text(stringResource(R.string.device_advanced_detail), style = StressType.BodySmall, color = StressColors.TextDim)
                }
                Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = StressColors.TextDim)
            }
        }
        AnimatedVisibility(open, enter = fadeIn(StressMotion.enter()), exit = fadeOut(StressMotion.exit())) {
            Stack(Modifier.padding(top = 12.dp), spacing = 8) {
                SectionLabel(stringResource(R.string.device_cpu_kernels))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    model.kernels.filter { it.supported }.forEach { k ->
                        LoadChoice("${k.code} · ${k.key}", k == kernel) { model.select(k) }
                    }
                }
                LoadActions(start = model::startCpu, stop = model::stopCpu)
                message?.let { Text(stringResource(R.string.device_start_failed, it), color = StressColors.Bad, style = StressType.BodySmall) }
                InfoRow(
                    stringResource(R.string.device_running),
                    live?.runningKernel?.let { "${it.code} · ${it.key}" } ?: stringResource(R.string.device_none),
                    valueWeight = 1.2f,
                )
                live?.clusters?.forEach { c ->
                    InfoRow(c.label, Format.rate(c.rate, live.unit?.let { Labels.rateSymbol(context, it) }), valueWeight = 1.2f)
                }
                ErrorRow(live?.errors)

                if (gpuTests == true) {
                    Hairline()
                    SectionLabel(stringResource(R.string.device_gpu_burners))
                    GpuSurface(
                        context.graph.gpu,
                        Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(8.dp)),
                        onAttached = model::showPreview,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        model.burners.forEach { b -> LoadChoice("${b.code} · ${b.key}", b == burner) { model.select(b) } }
                    }
                    LoadActions(start = model::startGpu, stop = model::showPreview)
                    val gpu = live?.gpu
                    val state = gpu?.state?.let {
                        stringResource(when (it) {
                            GpuState.Idle -> R.string.device_gpu_idle
                            GpuState.Running -> R.string.device_gpu_running
                            GpuState.DeviceLost, GpuState.Failed -> R.string.device_gpu_failed
                        })
                    }
                    InfoRow(
                        stringResource(R.string.device_gpu_state),
                        listOfNotNull(state, gpu?.burner?.code).joinToString(" · ").ifEmpty { Format.MISSING },
                    )
                    val fps = gpu?.framesPerSecond?.let { "${Format.number(it, 1)} fps" } ?: Format.MISSING
                    val frame = gpu?.frameMillis?.let { "${Format.number(it, 1)} ms" } ?: Format.MISSING
                    InfoRow(stringResource(R.string.device_frame), "$fps · $frame")
                    InfoRow(stringResource(R.string.device_work), Format.rate(gpu?.rate, gpu?.burner?.unit?.let { Labels.rateSymbol(context, it) }))
                    ErrorRow(gpu?.errors, gpu?.checks)
                    if (gpu?.state == GpuState.DeviceLost || gpu?.state == GpuState.Failed) {
                        Text(stringResource(R.string.end_gpu_failed), color = StressColors.Bad, style = StressType.BodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Panel(padding = 12.dp, selected = selected, onClick = onClick) {
        Text(label, style = StressType.Code, color = if (selected) StressColors.Cool else StressColors.Text)
    }
}

@Composable
private fun LoadActions(start: () -> Unit, stop: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PrimaryButton(stringResource(R.string.device_start), onClick = start, icon = Icons.Rounded.PlayArrow, modifier = Modifier.weight(1f))
        StopButton(stringResource(R.string.run_stop), onClick = stop, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ErrorRow(errors: Long?, checks: Long? = null) {
    val value = errors?.let { if (checks == null) it.toString() else "$it / $checks" }
    val color = when {
        errors == null -> StressColors.TextDim
        errors > 0 -> StressColors.Bad
        else -> StressColors.Good
    }
    InfoRow(stringResource(R.string.result_errors), value ?: Format.MISSING, valueColor = color)
}
