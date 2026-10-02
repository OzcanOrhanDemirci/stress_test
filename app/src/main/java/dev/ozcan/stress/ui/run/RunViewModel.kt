package dev.ozcan.stress.ui.run

import android.os.SystemClock
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One run and what its screen shows while it goes. Created once per visit to the run screen. */
class RunViewModel(graph: AppGraph, mode: StressMode, duration: StressDuration) : ViewModel() {

    val settings = graph.settings.current
    private val controller = RunController(
        graph.sampler,
        graph.driver,
        graph.cpu,
        graph.gpu,
        graph.layout,
        graph.runs,
        device = { graph.device.await() },
    )
    val state: StateFlow<RunState> = controller.state
    val safety: StateFlow<SafetyCheck> = controller.safety
    val cpuCount: Int = graph.cpu.cpuCount

    val live: StateFlow<LiveView?> = graph.sampler.latest
        .map { LiveView.from(graph.sampler.log.recent(LiveView.WINDOW_SAMPLES), graph.layout) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Battery power once a second over the last two minutes, for the overlay's line. */
    private val _power = MutableStateFlow<List<Double>>(emptyList())
    val power: StateFlow<List<Double>> = _power.asStateFlow()

    /** Mean power while resting before the load. */
    private val _idleWatts = MutableStateFlow<Double?>(null)
    val idleWatts: StateFlow<Double?> = _idleWatts.asStateFlow()

    /** The highest one-second power so far: the gauge's scale grows to it. */
    private val _peakWatts = MutableStateFlow<Double?>(null)
    val peakWatts: StateFlow<Double?> = _peakWatts.asStateFlow()

    init {
        viewModelScope.launch {
            controller.run(
                mode,
                duration,
                RunOptions(deviceSafety = settings.deviceSafety, quality = settings.quality, appVersion = graph.appVersion),
            )
        }
        viewModelScope.launch {
            var lastSecond = -1L
            val baseline = mutableListOf<Double>()
            live.collect { view ->
                val watts = view?.watts ?: return@collect
                val second = SystemClock.elapsedRealtime() / 1000
                if (second == lastSecond) return@collect
                lastSecond = second
                _power.value = (_power.value + watts).takeLast(HISTORY_SECONDS)
                when (state.value) {
                    is RunState.Baseline -> {
                        baseline += watts
                        _idleWatts.value = baseline.average()
                    }
                    is RunState.Running -> _peakWatts.value = maxOf(_peakWatts.value ?: 0.0, watts)
                    else -> Unit
                }
            }
        }
    }

    fun stop(reason: EndReason = EndReason.User) = controller.requestStop(reason)

    override fun onCleared() {
        controller.requestStop(EndReason.Interrupted)
    }

    private companion object {
        const val HISTORY_SECONDS = 120
    }
}
