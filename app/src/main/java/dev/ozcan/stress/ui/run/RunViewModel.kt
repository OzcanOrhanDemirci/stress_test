package dev.ozcan.stress.ui.run

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.ozcan.stress.AppGraph
import dev.ozcan.stress.run.EndReason
import dev.ozcan.stress.run.RunController
import dev.ozcan.stress.run.RunOptions
import dev.ozcan.stress.run.RunState
import dev.ozcan.stress.run.StressDuration
import dev.ozcan.stress.run.StressMode
import dev.ozcan.stress.safety.SafetyCheck
import dev.ozcan.stress.ui.LiveView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One run and what its screen shows while it goes. Created once per visit to the run screen. */
class RunViewModel(graph: AppGraph, mode: StressMode, duration: StressDuration) : ViewModel() {

    val settings = graph.settings.current
    private val controller = RunController(
        graph.sampler,
        graph.driver,
        graph.cpu.kernels,
        graph.gpu.burners,
        graph.cpu.cpuCount,
        graph.layout,
        graph.runs,
        device = { graph.device.await() },
    )
    val state: StateFlow<RunState> = controller.state
    val safety: StateFlow<SafetyCheck> = controller.safety
    private val _history = MutableStateFlow(LiveHistory())
    internal val history: StateFlow<LiveHistory> = _history.asStateFlow()
    private val _events = MutableStateFlow(RunEvents())
    internal val events: StateFlow<RunEvents> = _events.asStateFlow()

    val live: StateFlow<LiveView?> = combine(graph.sampler.latest, state) { _, phase ->
        val recent = graph.sampler.log.recent(LiveView.WINDOW_SAMPLES)
        val running = phase as? RunState.Running
        val samples = if (running == null) recent else recent.takeLastWhile { it.timeNanos >= running.startedAtNanos }
        val view = LiveView.from(samples, graph.layout, running?.currentConvention)
        if (running != null && view != null) {
            val second = (samples.last().timeNanos - running.startedAtNanos) / 1_000_000_000L
            _history.value = _history.value.append(
                LiveReading(second, view.watts.takeUnless { view.plugged }, view.chipCelsius, view.batteryCelsius),
            )
            _events.value = _events.value.observe(second, RunObservation.of(view, controller.safety.value, mode.usesGpu))
        }
        view
    }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch {
            controller.run(
                mode,
                duration,
                RunOptions(
                    deviceSafety = settings.deviceSafety,
                    quality = settings.quality,
                    sceneBurner = settings.sceneBurner,
                    appVersion = graph.appVersion,
                ),
            )
        }
    }

    fun stop(reason: EndReason = EndReason.User) = controller.requestStop(reason)

    override fun onCleared() {
        controller.requestStop(EndReason.Interrupted)
    }
}
