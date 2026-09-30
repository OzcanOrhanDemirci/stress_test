package dev.ozcan.stress.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.ozcan.stress.AppGraph
import dev.ozcan.stress.engine.CoreAssignment
import dev.ozcan.stress.engine.CpuKernel
import dev.ozcan.stress.engine.GpuBurner
import dev.ozcan.stress.engine.GpuCatalog
import dev.ozcan.stress.engine.GpuEngine
import dev.ozcan.stress.engine.GpuRequest
import dev.ozcan.stress.engine.KernelCatalog
import dev.ozcan.stress.engine.StartResult
import dev.ozcan.stress.graph
import dev.ozcan.stress.telemetry.SensorAvailability
import dev.ozcan.stress.telemetry.ThermalGroup
import dev.ozcan.stress.ui.theme.StressColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DiagnosticsViewModel(private val graph: AppGraph) : ViewModel() {

    val kernels: List<CpuKernel> = graph.cpu.kernels
    val availability: SensorAvailability = graph.sensors.availability

    private val _selected = MutableStateFlow(kernels.first { it.key == "fp32_gemm" })
    val selected: StateFlow<CpuKernel> = _selected.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val gpu: GpuEngine = graph.gpu
    val burners: List<GpuBurner> = graph.gpu.burners

    private val _selectedBurner = MutableStateFlow(burners.first())
    val selectedBurner: StateFlow<GpuBurner> = _selectedBurner.asStateFlow()

    init {
        // The preview alone, so the surface shows the renderer is alive.
        gpu.requestAsync(GpuRequest(null))
    }

    fun selectBurner(burner: GpuBurner) {
        _selectedBurner.value = burner
    }

    fun startGpu() = gpu.requestAsync(GpuRequest(_selectedBurner.value))

    fun stopGpu() = gpu.requestAsync(GpuRequest(null))

    override fun onCleared() {
        gpu.requestAsync(null)
    }

    val live: StateFlow<LiveView?> = graph.sampler.latest
        .map { LiveView.from(graph.sampler.log.recent(LIVE_WINDOW_SAMPLES), graph.layout) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(1_000), null)

    fun select(kernel: CpuKernel) {
        _selected.value = kernel
    }

    fun start() {
        val kernel = _selected.value
        viewModelScope.launch(Dispatchers.Default) {
            graph.cpu.stop()
            val result = graph.cpu.start(CoreAssignment.uniform(kernel))
            _message.value = if (result == StartResult.Started) null else "Başlatılamadı: ${result.name}"
        }
    }

    fun stop() {
        viewModelScope.launch(Dispatchers.Default) { graph.cpu.stop() }
    }

    companion object {
        /** About one second at the sampler's 10 Hz. */
        const val LIVE_WINDOW_SAMPLES = 11
    }
}

@Composable
fun DiagnosticsScreen() {
    val context = LocalContext.current
    val model: DiagnosticsViewModel = viewModel { DiagnosticsViewModel(context.graph) }
    val live by model.live.collectAsStateWithLifecycle()
    val selected by model.selected.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(StressColors.Background)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Tanılama", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "Honor 400 · Snapdragon 7 Gen 3 · Adreno 720",
            style = MaterialTheme.typography.bodySmall,
            color = StressColors.TextDim,
        )
        PowerPanel(live)
        LoadPanel(model.kernels, selected, live, message, model::select, model::start, model::stop)
        GpuPanel(model, live)
        ClockPanel(live)
        TemperaturePanel(live)
        AvailabilityPanel(model.availability)
    }
}

@Composable
private fun Panel(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(StressColors.Surface, RoundedCornerShape(14.dp))
            .border(1.dp, StressColors.Outline, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = StressColors.TextDim, letterSpacing = 1.5.sp)
        content()
    }
}

@Composable
private fun Field(label: String, value: String, valueColor: Color = StressColors.Text) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = StressColors.TextDim, style = MaterialTheme.typography.bodyMedium)
        Text(value, color = valueColor, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun PowerPanel(live: LiveView?) {
    Panel("Güç") {
        Text(
            Format.watts(live?.watts),
            style = MaterialTheme.typography.displaySmall,
            fontFamily = FontFamily.Monospace,
            color = StressColors.Cherenkov,
        )
        if (live?.plugged == true) {
            Text(
                "Şarj kablosu takılı: pilden çekilen güç ölçülemez.",
                color = StressColors.Warn,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Field("Akım", Format.number(live?.dischargeAmps, 3) + " A")
        Field("Ham akım (CURRENT_NOW)", live?.currentRaw?.toString() ?: Format.MISSING)
        Field("Gerilim", Format.number(live?.volts, 3) + " V")
        Field("Pil", "${live?.levelPercent ?: Format.MISSING} % · ${Format.celsius(live?.batteryCelsius)}")
        Field("Android termal durum", live?.thermalStatus?.toString() ?: Format.MISSING)
        Field("Termal pay", Format.number(live?.headroom?.toDouble(), 2))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LoadPanel(
    kernels: List<CpuKernel>,
    selected: CpuKernel,
    live: LiveView?,
    message: String?,
    onSelect: (CpuKernel) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Panel("CPU yükü") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            kernels.forEach { kernel ->
                Chip("${kernel.code} ${KernelCatalog.describe(kernel).title}", kernel == selected) { onSelect(kernel) }
            }
        }
        Text(KernelCatalog.describe(selected).detail, style = MaterialTheme.typography.bodySmall, color = StressColors.TextDim)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onStart) { Text("Başlat") }
            OutlinedButton(onClick = onStop) { Text("Durdur") }
        }
        message?.let { Text(it, color = StressColors.Bad, style = MaterialTheme.typography.bodySmall) }
        val running = live?.runningKernel
        Field("Çalışan", running?.let { "${it.code} ${KernelCatalog.describe(it).title}" } ?: "yok")
        live?.clusters?.forEach { Field(it.name, Format.rate(it.rate, live.unit)) }
        Field(
            "Hesap hatası",
            (live?.errors ?: 0L).toString(),
            if ((live?.errors ?: 0L) > 0) StressColors.Bad else StressColors.Good,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GpuPanel(model: DiagnosticsViewModel, live: LiveView?) {
    val selected by model.selectedBurner.collectAsStateWithLifecycle()
    val gpu = live?.gpu
    Panel("GPU yükü") {
        GpuSurface(model.gpu, Modifier.fillMaxWidth().height(220.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            model.burners.forEach { burner ->
                Chip("${burner.code} ${GpuCatalog.title(burner)}", burner == selected) { model.selectBurner(burner) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = model::startGpu) { Text("Başlat") }
            OutlinedButton(onClick = model::stopGpu) { Text("Durdur") }
        }
        Field("Durum", gpu?.let { "${it.state.name} · ${it.burner?.code ?: "önizleme"}" } ?: Format.MISSING)
        Field("Kare", "${Format.number(gpu?.framesPerSecond, 1)} fps · ${Format.number(gpu?.frameMillis, 1)} ms")
        Field("İş", Format.rate(gpu?.rate, gpu?.burner?.unit))
        Field("Kare başına gönderim", (gpu?.dispatchesPerFrame ?: 0L).toString())
        Field("GPU meşgul (kgsl)", Format.percent(live?.gpuBusy))
        Field(
            "Hesap hatası",
            "${gpu?.errors ?: 0} / ${gpu?.checks ?: 0} kontrol",
            if ((gpu?.errors ?: 0L) > 0) StressColors.Bad else StressColors.Good,
        )
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(if (selected) StressColors.CherenkovDim else StressColors.SurfaceHigh, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ClockPanel(live: LiveView?) {
    Panel("Frekans") {
        live?.clusters?.forEach { cluster ->
            Field(cluster.name, "${Format.mhz(cluster.khz)} / ${cluster.maxKhz / 1000}")
            val fraction = cluster.khz?.let { it.toFloat() / cluster.maxKhz.coerceAtLeast(1) } ?: 0f
            LinearProgressIndicator(
                progress = { fraction.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(4.dp),
                color = StressColors.Cherenkov,
                trackColor = StressColors.SurfaceHigh,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
            Spacer(Modifier.height(2.dp))
        }
        Field("GPU meşgul", Format.percent(live?.gpuBusy))
    }
}

@Composable
private fun TemperaturePanel(live: LiveView?) {
    Panel("Sıcaklık") {
        ThermalGroup.entries.forEach { group -> Field(group.label, Format.celsius(live?.temperatures?.get(group))) }
    }
}

@Composable
private fun AvailabilityPanel(availability: SensorAvailability) {
    Panel("Okunabilirlik (uygulama izniyle)") {
        availability.clusters.forEach { (cluster, ok) -> Availability("Frekans ${CoreNames.of(cluster)}", ok) }
        ThermalGroup.entries.forEach { group ->
            val total = availability.totalZones(group)
            val readable = availability.readableZones(group)
            Availability("Sıcaklık ${group.label} ($readable/$total)", total > 0 && readable == total)
        }
        Availability("GPU meşgul sayacı", availability.gpuBusy)
    }
}

@Composable
private fun Availability(label: String, ok: Boolean) {
    Field(label, if (ok) "okunuyor" else "kapalı", if (ok) StressColors.Good else StressColors.Bad)
}
