package dev.ozcan.stress.ui

import android.os.SystemClock
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.ozcan.stress.AppGraph
import dev.ozcan.stress.engine.GpuState
import dev.ozcan.stress.graph
import dev.ozcan.stress.run.RunController
import dev.ozcan.stress.run.RunRecord
import dev.ozcan.stress.run.RunState
import dev.ozcan.stress.run.StressDuration
import dev.ozcan.stress.run.StressMode
import dev.ozcan.stress.telemetry.ThermalGroup
import dev.ozcan.stress.ui.theme.StressColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class RunViewModel(graph: AppGraph, mode: StressMode, duration: StressDuration) : ViewModel() {

    private val controller = RunController(graph.sampler, graph.driver, graph.cpu, graph.gpu, graph.layout, graph.runs)
    val state: StateFlow<RunState> = controller.state

    val live: StateFlow<LiveView?> = graph.sampler.latest
        .map { LiveView.from(graph.sampler.log.recent(DiagnosticsViewModel.LIVE_WINDOW_SAMPLES), graph.layout) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Battery power once a second over the last two minutes, for the overlay's line. */
    private val _history = MutableStateFlow<List<Double>>(emptyList())
    val history: StateFlow<List<Double>> = _history.asStateFlow()

    /** Mean power while resting before the load. */
    private val _idleWatts = MutableStateFlow<Double?>(null)
    val idleWatts: StateFlow<Double?> = _idleWatts.asStateFlow()

    init {
        viewModelScope.launch { controller.run(mode, duration) }
        viewModelScope.launch {
            var lastSecond = -1L
            val baseline = mutableListOf<Double>()
            live.collect { view ->
                val watts = view?.watts ?: return@collect
                val second = SystemClock.elapsedRealtime() / 1000
                if (second == lastSecond) return@collect
                lastSecond = second
                _history.value = (_history.value + watts).takeLast(HISTORY_SECONDS)
                if (state.value is RunState.Baseline) {
                    baseline += watts
                    _idleWatts.value = baseline.average()
                }
            }
        }
    }

    fun stop() = controller.requestStop()

    override fun onCleared() {
        controller.requestStop()
    }

    private companion object {
        const val HISTORY_SECONDS = 120
    }
}

@Composable
fun RunScreen(mode: StressMode, duration: StressDuration, onFinished: (RunRecord) -> Unit, onLeave: () -> Unit) {
    val graph = LocalContext.current.graph
    val model: RunViewModel = viewModel(key = "run-${mode.name}-${duration.name}") { RunViewModel(graph, mode, duration) }
    val state by model.state.collectAsStateWithLifecycle()
    val live by model.live.collectAsStateWithLifecycle()
    val history by model.history.collectAsStateWithLifecycle()
    val idle by model.idleWatts.collectAsStateWithLifecycle()
    var overlay by remember { mutableStateOf(true) }

    MaxDisplayEffect()
    // Off screen the load loses the big cores and the GPU its surface: end the
    // run there and keep what it measured, marked as stopped early.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { model.stop() }
    LaunchedEffect(state) {
        (state as? RunState.Finished)?.let { onFinished(it.record) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { overlay = !overlay },
    ) {
        if (mode.usesGpu) GpuSurface(graph.gpu, Modifier.fillMaxSize()) else SwingingGauge(Modifier.align(Alignment.Center))

        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            val running = state as? RunState.Running
            when (val s = state) {
                RunState.Preparing, is RunState.Baseline -> Baseline(s as? RunState.Baseline)
                is RunState.Running -> if (overlay) TopHud(mode, duration, s, live, history, idle) else Box(Modifier)
                RunState.Analysing -> Message("Sonuçlar hesaplanıyor…", StressColors.Text)
                is RunState.Finished -> Message("Kaydedildi.", StressColors.Good)
                is RunState.Failed -> Message(s.message, StressColors.Bad)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (running != null && overlay) BottomHud(live)
                val failed = state is RunState.Failed
                if (failed) {
                    OutlinedButton(onClick = onLeave, modifier = Modifier.fillMaxWidth()) { Text("Geri dön") }
                } else if (overlay && state !is RunState.Analysing && state !is RunState.Finished) {
                    Button(
                        onClick = model::stop,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xCC1A1F22), contentColor = StressColors.Text),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("DURDUR", fontWeight = FontWeight.Bold, letterSpacing = 3.sp) }
                }
            }
        }
    }
}

@Composable
private fun Baseline(state: RunState.Baseline?) {
    val remaining = state?.let { ((it.endsAtNanos - SystemClock.elapsedRealtimeNanos()) / 1e9).coerceAtLeast(0.0) }
    Column(modifier = Modifier.fillMaxWidth().padding(top = 120.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("BOŞTA ÖLÇÜM", style = MaterialTheme.typography.titleMedium, letterSpacing = 4.sp, color = StressColors.TextDim)
        Text(
            remaining?.let { Format.number(kotlin.math.ceil(it), 0) } ?: "…",
            style = MaterialTheme.typography.displayLarge,
            fontFamily = FontFamily.Monospace,
            color = StressColors.Accent,
        )
        Text("Telefona dokunma: önce dinlenirken çektiği güç ölçülüyor.", style = MaterialTheme.typography.bodySmall, color = StressColors.TextDim)
    }
}

@Composable
private fun Message(text: String, color: Color) {
    Text(text, modifier = Modifier.padding(top = 120.dp).fillMaxWidth(), color = color, style = MaterialTheme.typography.titleMedium)
}

private val HudBackground = Color(0x9905080A)

/** Mode, time, and the number that matters: battery power, with its last two minutes. */
@Composable
private fun TopHud(
    mode: StressMode,
    duration: StressDuration,
    state: RunState.Running,
    live: LiveView?,
    history: List<Double>,
    idle: Double?,
) {
    val elapsed = (SystemClock.elapsedRealtimeNanos() - state.startedAtNanos) / 1e9
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(HudBackground, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GaugeMark(StressColors.Accent, Modifier.size(14.dp))
                Text(mode.title.uppercase(), style = MaterialTheme.typography.labelMedium, letterSpacing = 2.sp)
            }
            val planned = duration.seconds?.let { " / ${Durations.clock(it.toDouble())}" } ?: ""
            Text(
                "${Durations.clock(elapsed)}$planned",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelMedium,
                color = StressColors.TextDim,
            )
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                Format.watts(live?.watts),
                style = MaterialTheme.typography.headlineLarge,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = StressColors.Accent,
            )
            val above = if (live?.watts != null && idle != null) live.watts - idle else null
            val plugged = live?.plugged == true
            Text(
                if (plugged) "şarjda: geçersiz" else "boşta ${Format.watts(idle)} · yük +${Format.watts(above)}",
                modifier = Modifier.padding(bottom = 6.dp),
                style = MaterialTheme.typography.labelSmall,
                color = if (plugged) StressColors.Warn else StressColors.TextDim,
                fontFamily = FontFamily.Monospace,
            )
        }
        Sparkline(history, StressColors.Accent, Modifier.fillMaxWidth().height(26.dp))
    }
}

/** The detail, kept small at the bottom so the middle of the screen belongs to the scene. */
@Composable
private fun BottomHud(live: LiveView?) {
    val errors = (live?.errors ?: 0L) + (live?.gpu?.errors ?: 0L)
    val temps = live?.temperatures.orEmpty()
    val small = MaterialTheme.typography.labelSmall
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(HudBackground, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            listOf(ThermalGroup.BigCores, ThermalGroup.LittleCores, ThermalGroup.Gpu, ThermalGroup.Memory)
                .joinToString("  ") { "${it.label} ${Format.number(temps[it], 0)}°" } +
                "  Pil ${Format.number(live?.batteryCelsius, 0)}°",
            fontFamily = FontFamily.Monospace,
            style = small,
        )
        Text(
            live?.clusters.orEmpty().joinToString("  ") { "${it.shortName} ${it.khz?.div(1000) ?: "—"}" } + " MHz",
            fontFamily = FontFamily.Monospace,
            style = small,
            color = StressColors.TextDim,
        )
        val cpuRate = live?.clusters?.mapNotNull { it.rate }?.takeIf { it.isNotEmpty() }?.sum()
        val gpu = live?.gpu
        Text(
            buildList {
                if (cpuRate != null) add("CPU ${Format.rate(cpuRate, live.unit)}")
                if (gpu?.state == GpuState.Running) {
                    add("GPU ${Format.rate(gpu.rate, gpu.burner?.unit)}")
                    add("${Format.number(gpu.framesPerSecond, 0)} fps")
                }
                add("GPU meşgul %${Format.number(live?.gpuBusy?.times(100), 0)}")
            }.joinToString(" · "),
            fontFamily = FontFamily.Monospace,
            style = small,
            color = StressColors.TextDim,
        )
        Text(
            "Hesap hatası $errors",
            fontFamily = FontFamily.Monospace,
            style = small,
            color = if (errors > 0) StressColors.Bad else StressColors.Good,
        )
    }
}

/** What CPU-only runs show instead of a scene: the gauge, its needle swinging near the top. */
@Composable
private fun SwingingGauge(modifier: Modifier) {
    val reading by rememberInfiniteTransition(label = "gauge").animateFloat(
        initialValue = 0.78f,
        targetValue = 0.97f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
        label = "gauge",
    )
    GaugeMark(StressColors.Accent, modifier.size(180.dp), reading)
}
