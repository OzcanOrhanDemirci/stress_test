package dev.ozcan.stress.ui.device

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
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
import dev.ozcan.stress.telemetry.ClusterRole
import dev.ozcan.stress.telemetry.SensorAvailability
import dev.ozcan.stress.telemetry.ThermalGroup
import dev.ozcan.stress.telemetry.label
import dev.ozcan.stress.ui.Format
import dev.ozcan.stress.ui.GpuSurface
import dev.ozcan.stress.ui.Labels
import dev.ozcan.stress.ui.LiveView
import dev.ozcan.stress.ui.components.GlassCard
import dev.ozcan.stress.ui.components.InfoRow
import dev.ozcan.stress.ui.components.MeterBar
import dev.ozcan.stress.ui.components.PrimaryButton
import dev.ozcan.stress.ui.components.SecondaryButton
import dev.ozcan.stress.ui.components.SectionHeader
import dev.ozcan.stress.ui.context
import dev.ozcan.stress.ui.theme.NumberStyles
import dev.ozcan.stress.ui.theme.StressColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The device screen's live readings and its manual load tests, which stop when the screen goes. */
class DeviceViewModel(private val graph: AppGraph) : ViewModel() {

    val kernels: List<CpuKernel> = graph.cpu.kernels
    val burners: List<GpuBurner> = graph.gpu.burners
    val availability: SensorAvailability = graph.sensors.availability
    val roles: List<ClusterRole> = ClusterRole.of(graph.layout.clusters)
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
fun DeviceScreen(onBack: () -> Unit) {
    val graph = context().graph
    val model: DeviceViewModel = viewModel { DeviceViewModel(graph) }
    val live by model.live.collectAsStateWithLifecycle()
    val device by model.device.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) }
            Text(stringResource(R.string.device_title), style = MaterialTheme.typography.titleLarge)
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            DeviceCard(device)
            PowerCard(live)
            ClockCard(live, model.roles)
            TemperatureCard(live)
            AvailabilityCard(model.availability, model.gpuBusyPath)
            AdvancedCard(model, live)
        }
    }
}

@Composable
private fun DeviceCard(device: DeviceInfo?) {
    SectionHeader(stringResource(R.string.device_section_hardware))
    GlassCard(Modifier.fillMaxWidth()) {
        if (device == null) {
            Text(Format.MISSING, color = StressColors.TextDim)
            return@GlassCard
        }
        Text(device.title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        device.soc?.let { InfoRow(stringResource(R.string.device_soc), it) }
        device.cpuClusters.forEach { InfoRow(stringResource(R.string.device_cpu), it) }
        InfoRow(stringResource(R.string.device_cores), device.cpuCount.toString())
        InfoRow(stringResource(R.string.device_gpu), device.gpu ?: stringResource(R.string.device_no_vulkan))
        device.vulkan?.let { InfoRow("Vulkan", it) }
        InfoRow(stringResource(R.string.device_android), "${device.androidVersion} (API ${device.sdk})")
        device.ramGigabytes?.let { InfoRow(stringResource(R.string.device_ram), "${Format.number(it, 1)} GB") }
        device.display?.let { InfoRow(stringResource(R.string.device_display), it) }
    }
}

@Composable
private fun PowerCard(live: LiveView?) {
    SectionHeader(stringResource(R.string.device_section_power))
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (live?.plugged == true) stringResource(R.string.home_live_charging) else Format.number(live?.watts, 2),
                style = NumberStyles.Large,
                color = StressColors.Accent,
            )
            if (live?.plugged != true) Text("W", style = MaterialTheme.typography.titleMedium, color = StressColors.TextDim, modifier = Modifier.padding(bottom = 4.dp))
        }
        if (live?.plugged == true) {
            Text(stringResource(R.string.home_plugged), style = MaterialTheme.typography.bodySmall, color = StressColors.Warn)
        }
        Spacer(Modifier.height(6.dp))
        InfoRow(stringResource(R.string.device_current), Format.number(live?.dischargeAmps, 3) + " A")
        InfoRow(stringResource(R.string.device_current_raw), live?.currentRaw?.toString() ?: Format.MISSING)
        InfoRow(stringResource(R.string.device_voltage), Format.number(live?.volts, 3) + " V")
        InfoRow(stringResource(R.string.temp_battery), "${Format.percentValue(live?.levelPercent)} · ${Format.celsius(live?.batteryCelsius)}")
        InfoRow(stringResource(R.string.device_thermal_status), live?.let { stringResource(Labels.thermalStatus(it.thermalStatus)) } ?: Format.MISSING)
        InfoRow(stringResource(R.string.device_headroom), Format.number(live?.headroom?.toDouble(), 2))
    }
}

@Composable
private fun ClockCard(live: LiveView?, roles: List<ClusterRole>) {
    SectionHeader(stringResource(R.string.device_section_clocks))
    GlassCard(Modifier.fillMaxWidth()) {
        val clusters = live?.clusters.orEmpty()
        if (clusters.isEmpty()) Text(stringResource(R.string.device_unreadable), color = StressColors.TextDim, style = MaterialTheme.typography.bodySmall)
        clusters.forEachIndexed { i, c ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${stringResource(Labels.role(roles.getOrElse(i) { c.role }))} · ${c.label}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("${Format.mhz(c.khz)} / ${c.maxKhz / 1000}", style = NumberStyles.Small, color = StressColors.TextDim)
            }
            Spacer(Modifier.height(4.dp))
            MeterBar(c.load ?: 0f, color = Labels.clusterColor(i, clusters.size))
            Spacer(Modifier.height(10.dp))
        }
        InfoRow(stringResource(R.string.run_gpu_load), Format.percent(live?.gpuLoad))
    }
}

@Composable
private fun TemperatureCard(live: LiveView?) {
    val context = context()
    SectionHeader(stringResource(R.string.chart_temperature))
    GlassCard(Modifier.fillMaxWidth()) {
        ThermalGroup.entries.forEach { group ->
            val value = live?.temperatures?.get(group)
            InfoRow(Labels.temperature(context, group.key), Format.celsius(value), valueColor = if (value == null) StressColors.TextFaint else StressColors.Text)
        }
        InfoRow(stringResource(R.string.temp_battery), Format.celsius(live?.batteryCelsius))
    }
}

@Composable
private fun AvailabilityCard(availability: SensorAvailability, gpuBusyPath: String?) {
    val context = context()
    SectionHeader(stringResource(R.string.device_section_sensors))
    GlassCard(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.device_sensors_detail), style = MaterialTheme.typography.bodySmall, color = StressColors.TextDim)
        Spacer(Modifier.height(8.dp))
        availability.clusters.forEach { (cluster, ok) -> Readable(stringResource(R.string.device_clock_of, cluster.label()), ok) }
        ThermalGroup.entries.forEach { group ->
            val total = availability.totalZones(group)
            Readable(
                "${Labels.temperature(context, group.key)} (${availability.readableZones(group)}/$total)",
                total > 0 && availability.readable(group),
            )
        }
        Readable(stringResource(R.string.device_gpu_counter) + (gpuBusyPath?.let { " · ${it.substringAfterLast('/')}" } ?: ""), availability.gpuBusy)
    }
}

@Composable
private fun Readable(label: String, ok: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.RemoveCircleOutline,
            null,
            tint = if (ok) StressColors.Good else StressColors.TextFaint,
            modifier = Modifier.size(16.dp),
        )
        Text(label, style = MaterialTheme.typography.bodySmall, color = if (ok) StressColors.Text else StressColors.TextDim, modifier = Modifier.weight(1f))
        Text(
            stringResource(if (ok) R.string.device_readable else R.string.device_closed),
            style = MaterialTheme.typography.labelSmall,
            color = if (ok) StressColors.Good else StressColors.TextFaint,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdvancedCard(model: DeviceViewModel, live: LiveView?) {
    val context = context()
    var open by rememberSaveable { mutableStateOf(false) }
    val kernel by model.kernel.collectAsStateWithLifecycle()
    val burner by model.burner.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    val gpuTests by model.gpuTests.collectAsStateWithLifecycle()
    SectionHeader(stringResource(R.string.device_section_advanced))
    GlassCard(Modifier.fillMaxWidth(), onClick = { open = !open }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.device_advanced), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.device_advanced_detail), style = MaterialTheme.typography.bodySmall, color = StressColors.TextDim)
            }
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = StressColors.TextDim)
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.device_cpu_kernels), style = MaterialTheme.typography.labelLarge, color = StressColors.TextDim)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    model.kernels.filter { it.supported }.forEach { k ->
                        Chip("${k.code} · ${k.key}", k == kernel) { model.select(k) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton(stringResource(R.string.device_start), onClick = model::startCpu, icon = Icons.Rounded.PlayArrow, height = 48.dp, modifier = Modifier.weight(1f))
                    SecondaryButton(stringResource(R.string.run_stop), onClick = model::stopCpu, icon = Icons.Rounded.Stop, modifier = Modifier.weight(1f))
                }
                message?.let { Text(stringResource(R.string.device_start_failed, it), color = StressColors.Bad, style = MaterialTheme.typography.bodySmall) }
                InfoRow(
                    stringResource(R.string.device_running),
                    live?.runningKernel?.let { "${it.code} · ${it.key}" } ?: stringResource(R.string.device_none),
                )
                live?.clusters?.forEach { c ->
                    InfoRow(c.label, Format.rate(c.rate, live.unit?.let { Labels.rateSymbol(context, it) }))
                }
                InfoRow(
                    stringResource(R.string.result_errors),
                    (live?.errors ?: 0L).toString(),
                    valueColor = if ((live?.errors ?: 0L) > 0) StressColors.Bad else StressColors.Good,
                )

                if (gpuTests == true) {
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.device_gpu_burners), style = MaterialTheme.typography.labelLarge, color = StressColors.TextDim)
                    GpuSurface(
                        context.graph.gpu,
                        Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(14.dp)),
                        onAttached = model::showPreview,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        model.burners.forEach { b -> Chip("${b.code} · ${b.key}", b == burner) { model.select(b) } }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrimaryButton(stringResource(R.string.device_start), onClick = model::startGpu, icon = Icons.Rounded.PlayArrow, height = 48.dp, modifier = Modifier.weight(1f))
                        SecondaryButton(stringResource(R.string.run_stop), onClick = model::showPreview, icon = Icons.Rounded.Stop, modifier = Modifier.weight(1f))
                    }
                    val gpu = live?.gpu
                    InfoRow(stringResource(R.string.device_gpu_state), gpu?.let { "${it.state.name} · ${it.burner?.code ?: "—"}" } ?: Format.MISSING)
                    InfoRow(stringResource(R.string.device_frame), "${Format.number(gpu?.framesPerSecond, 1)} fps · ${Format.number(gpu?.frameMillis, 1)} ms")
                    InfoRow(stringResource(R.string.device_work), Format.rate(gpu?.rate, gpu?.burner?.unit?.let { Labels.rateSymbol(context, it) }))
                    InfoRow(
                        stringResource(R.string.result_errors),
                        "${gpu?.errors ?: 0} / ${gpu?.checks ?: 0}",
                        valueColor = if ((gpu?.errors ?: 0L) > 0) StressColors.Bad else StressColors.Good,
                    )
                    if (gpu?.state == GpuState.DeviceLost || gpu?.state == GpuState.Failed) {
                        Text(stringResource(R.string.end_gpu_failed), color = StressColors.Bad, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .clip(shape)
            .background(if (selected) StressColors.AccentDim else StressColors.SurfaceHighest, shape)
            .border(1.dp, if (selected) StressColors.Accent else StressColors.Outline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}
