package dev.ozcan.stress.run

import android.os.SystemClock
import dev.ozcan.stress.engine.CpuEngine
import dev.ozcan.stress.engine.GpuEngine
import dev.ozcan.stress.engine.LoadDriver
import dev.ozcan.stress.engine.LoadSettings
import dev.ozcan.stress.engine.Workload
import dev.ozcan.stress.telemetry.CoreNames
import dev.ozcan.stress.telemetry.Sampler
import dev.ozcan.stress.telemetry.SysfsLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

sealed interface RunState {
    data object Preparing : RunState
    data class Baseline(val endsAtNanos: Long) : RunState
    data class Running(val startedAtNanos: Long, val endsAtNanos: Long?) : RunState
    data object Analysing : RunState
    data class Finished(val record: RunRecord) : RunState
    data class Failed(val message: String) : RunState
}

/**
 * One stress run: [BASELINE_SECONDS] at rest to measure what the phone draws
 * idle, the load until the duration ends or [requestStop], then the analysis,
 * stored in [store]. The screen must stay in front throughout: off screen the
 * CPU load would lose the big cores and the GPU its surface.
 */
class RunController(
    private val sampler: Sampler,
    private val driver: LoadDriver,
    private val cpu: CpuEngine,
    private val gpu: GpuEngine,
    private val layout: SysfsLayout,
    private val store: RunStore,
) {
    private val _state = MutableStateFlow<RunState>(RunState.Preparing)
    val state: StateFlow<RunState> = _state.asStateFlow()

    private val stop = CompletableDeferred<Unit>()

    fun requestStop() {
        stop.complete(Unit)
    }

    suspend fun run(mode: StressMode, duration: StressDuration) {
        val workload = Workload.parse(mode.recipe, cpu.kernels, gpu.burners)
        val startedAtMillis = System.currentTimeMillis()

        val baselineStart = now()
        _state.value = RunState.Baseline(baselineStart + BASELINE_SECONDS * NANOS)
        if (waitOrStop(BASELINE_SECONDS * 1000L)) {
            _state.value = RunState.Failed("Durduruldu: yük başlamadan önce.")
            return
        }
        val baselineEnd = now()

        val outcome = driver.start(workload, LoadSettings())
        val loadStart = now()
        var loadEnd = loadStart
        var stoppedEarly = false
        try {
            if (outcome != LoadDriver.STARTED) {
                _state.value = RunState.Failed("Yük başlatılamadı ($outcome).")
                return
            }
            _state.value = RunState.Running(loadStart, duration.seconds?.let { loadStart + it * NANOS })
            stoppedEarly = waitOrStop(duration.seconds?.let { it * 1000L }) && duration.seconds != null
            loadEnd = now()
        } finally {
            driver.stop()
        }

        _state.value = RunState.Analysing
        val record = withContext(Dispatchers.Default) {
            val (summary, series) = RunAnalysis.analyze(
                baseline = sampler.log.between(baselineStart, baselineEnd),
                load = sampler.log.between(loadStart, loadEnd),
                clusters = layout.clusters,
                clusterName = CoreNames::of,
            )
            RunRecord(
                id = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date(startedAtMillis)),
                startedAtMillis = startedAtMillis,
                mode = mode.name,
                workload = workload.describe(),
                plannedSeconds = duration.seconds,
                loadSeconds = (loadEnd - loadStart) / 1e9,
                stoppedEarly = stoppedEarly,
                summary = summary,
                series = series,
            )
        }
        withContext(Dispatchers.IO) { store.save(record) }
        _state.value = RunState.Finished(record)
    }

    /** Waits [millis] (forever when null); true when a stop came first. */
    private suspend fun waitOrStop(millis: Long?): Boolean {
        if (millis == null) {
            stop.await()
            return true
        }
        return withTimeoutOrNull(millis) { stop.await() } != null
    }

    private fun now() = SystemClock.elapsedRealtimeNanos()

    companion object {
        const val BASELINE_SECONDS = 10L
        private const val NANOS = 1_000_000_000L
    }
}
