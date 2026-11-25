package dev.ozcan.stress.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.ozcan.stress.AppGraph
import dev.ozcan.stress.R
import dev.ozcan.stress.engine.CoreAssignment
import dev.ozcan.stress.engine.CpuKernel
import dev.ozcan.stress.engine.GpuBurner
import dev.ozcan.stress.engine.GpuRequest
import dev.ozcan.stress.engine.GpuState
import dev.ozcan.stress.engine.StartResult
import dev.ozcan.stress.graph
import dev.ozcan.stress.ui.Format
import dev.ozcan.stress.ui.GpuSurface
import dev.ozcan.stress.ui.Labels
import dev.ozcan.stress.ui.LiveView
import dev.ozcan.stress.ui.components.Hairline
import dev.ozcan.stress.ui.components.InfoRow
import dev.ozcan.stress.ui.components.Panel
import dev.ozcan.stress.ui.components.PrimaryButton
import dev.ozcan.stress.ui.components.SectionLabel
import dev.ozcan.stress.ui.components.Stack
import dev.ozcan.stress.ui.components.StopButton
import dev.ozcan.stress.ui.context
import dev.ozcan.stress.ui.device.liveEvery
import dev.ozcan.stress.ui.theme.StressColors
import dev.ozcan.stress.ui.theme.StressType
import dev.ozcan.stress.ui.upper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The manual load tests (moved here from the device screen): one CPU kernel or GPU burner, run by hand. */
class LoadTestViewModel(private val graph: AppGraph) : ViewModel() {

    val kernels: List<CpuKernel> = graph.cpu.kernels
    val burners: List<GpuBurner> = graph.gpu.burners

    /** Once a second, like the run screen's live values. */
    val live: StateFlow<LiveView?> = graph.liveEvery(1_000L)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(1_000), null)

    private val _gpuTests = MutableStateFlow<Boolean?>(null)
    val gpuTests: StateFlow<Boolean?> = _gpuTests.asStateFlow()

    private val _kernel = MutableStateFlow(kernels.firstOrNull { it.key == "fp32_l2" } ?: kernels.first())
    val kernel: StateFlow<CpuKernel> = _kernel.asStateFlow()

    private val _burner = MutableStateFlow(burners.first())
    val burner: StateFlow<GpuBurner> = _burner.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
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

    /** Stops both loads; called when the page goes, so nothing keeps running unseen. */
    fun stopAll() {
        graph.cpu.stop()
        graph.gpu.requestAsync(null)
    }

    override fun onCleared() = stopAll()
}

/** The load tests' controls and readings. They stop when this leaves the screen. */
@Composable
internal fun LoadTests() {
    val context = context()
    val graph = context.graph
    val model: LoadTestViewModel = viewModel { LoadTestViewModel(graph) }
    val kernel by model.kernel.collectAsStateWithLifecycle()
    val burner by model.burner.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    val gpuTests by model.gpuTests.collectAsStateWithLifecycle()
    val live by model.live.collectAsStateWithLifecycle()
    DisposableEffect(model) { onDispose { model.stopAll() } }

    Stack(spacing = 8) {
        SectionLabel(stringResource(R.string.device_advanced))
        Text(stringResource(R.string.device_advanced_detail), style = StressType.BodySmall, color = StressColors.TextDim)
    }
    Panel(Modifier.fillMaxWidth()) {
        Stack(spacing = 8) {
            Text(stringResource(R.string.device_cpu_kernels).upper(), style = StressType.Label, color = StressColors.TextDim)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                model.kernels.filter { it.supported }.forEach { k ->
                    LoadChoice("${k.code} · ${k.key}", k == kernel) { model.select(k) }
                }
            }
            LoadActions(start = model::startCpu, stop = model::stopCpu)
            message?.let { Text(stringResource(R.string.device_start_failed, it), color = StressColors.Bad, style = StressType.BodySmall) }
            val view = live
            InfoRow(
                stringResource(R.string.device_running),
                view?.runningKernel?.let { "${it.code} · ${it.key}" } ?: stringResource(R.string.device_none),
                valueWeight = 1.2f,
            )
            view?.clusters?.forEach { c ->
                InfoRow(c.label, Format.rate(c.rate, view.unit?.let { Labels.rateSymbol(context, it) }), valueWeight = 1.2f)
            }
            ErrorRow(view?.errors)
        }
    }

    if (gpuTests == true) {
        Panel(Modifier.fillMaxWidth()) {
            Stack(spacing = 8) {
                Text(stringResource(R.string.device_gpu_burners).upper(), style = StressType.Label, color = StressColors.TextDim)
                GpuSurface(
                    graph.gpu,
                    Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(8.dp)),
                    onAttached = model::showPreview,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    model.burners.forEach { b -> LoadChoice("${b.code} · ${b.key}", b == burner) { model.select(b) } }
                }
                LoadActions(start = model::startGpu, stop = model::showPreview)
                Hairline()
                val gpu = live?.gpu
                val state = gpu?.state?.let {
                    stringResource(
                        when (it) {
                            GpuState.Idle -> R.string.device_gpu_idle
                            GpuState.Running -> R.string.device_gpu_running
                            GpuState.DeviceLost, GpuState.Failed -> R.string.device_gpu_failed
                        },
                    )
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
