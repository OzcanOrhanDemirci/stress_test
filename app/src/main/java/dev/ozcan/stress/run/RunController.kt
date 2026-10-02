package dev.ozcan.stress.run

import android.os.SystemClock
import dev.ozcan.stress.device.DeviceInfo
import dev.ozcan.stress.engine.CpuEngine
import dev.ozcan.stress.engine.GpuEngine
import dev.ozcan.stress.engine.GpuState
import dev.ozcan.stress.engine.LoadDriver
import dev.ozcan.stress.engine.LoadSettings
import dev.ozcan.stress.engine.SceneQuality
import dev.ozcan.stress.engine.Workload
import dev.ozcan.stress.safety.SafetyCheck
import dev.ozcan.stress.safety.SafetyLevel
import dev.ozcan.stress.safety.SafetyMonitor
import dev.ozcan.stress.safety.SafetyPolicy
import dev.ozcan.stress.telemetry.Sampler
import dev.ozcan.stress.telemetry.SysfsLayout
import dev.ozcan.stress.telemetry.label
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

sealed interface RunState {
    data object Preparing : RunState
    data class Baseline(val startedAtNanos: Long, val endsAtNanos: Long) : RunState
    data class Running(val startedAtNanos: Long, val endsAtNanos: Long?) : RunState
    data object Analysing : RunState
    data class Finished(val record: RunRecord) : RunState
    sealed interface Failed : RunState {
        /** Device safety would not let the test start. */
        data class Blocked(val check: SafetyCheck) : Failed
        /** Stopped before the load began: by the user, or by device safety ([check]). */
        data class StoppedBeforeLoad(val reason: EndReason, val check: SafetyCheck?) : Failed
        /** A part of the load did not start: "cpu=…", "gpu=…". */
        data class LoadFailed(val detail: String) : Failed
    }
}

/** What a run is set up with, apart from its mode and duration. */
data class RunOptions(
    val deviceSafety: Boolean = true,
    val quality: SceneQuality = SceneQuality.Medium,
    val appVersion: String? = null,
)

/**
 * One stress run: [BASELINE_SECONDS] at rest to measure what the phone draws
 * idle, the load until the duration ends, [requestStop], device safety or a
 * failed GPU ends it, then the analysis, stored in [store]. The screen must
 * stay in front throughout: off screen the CPU load would lose the big cores
 * and the GPU its surface.
 */
class RunController(
    private val sampler: Sampler,
    private val driver: LoadDriver,
    private val cpu: CpuEngine,
    private val gpu: GpuEngine,
    private val layout: SysfsLayout,
    private val store: RunStore,
    private val device: suspend () -> DeviceInfo?,
) {
    private val _state = MutableStateFlow<RunState>(RunState.Preparing)
    val state: StateFlow<RunState> = _state.asStateFlow()

    /** Device safety's latest finding while the run is watched; OK when it is not. */
    private val _safety = MutableStateFlow(SafetyCheck.OK)
    val safety: StateFlow<SafetyCheck> = _safety.asStateFlow()

    private val stop = CompletableDeferred<EndReason>()
    private var safetyStop: SafetyCheck? = null

    /** Ends the run early: by the user, or [EndReason.Interrupted] when the app leaves the screen. */
    fun requestStop(reason: EndReason = EndReason.User) {
        stop.complete(reason)
    }

    suspend fun run(mode: StressMode, duration: StressDuration, options: RunOptions) = coroutineScope {
        val workload = Workload.parse(mode.recipe, cpu.kernels, gpu.burners, cpu.cpuCount)
        val startedAtMillis = System.currentTimeMillis()

        if (options.deviceSafety) {
            val sample = sampler.latest.value
            val block = sample?.let { SafetyPolicy.startBlock(it) }
            if (block != null) {
                _state.value = RunState.Failed.Blocked(block)
                return@coroutineScope
            }
        }
        val watcher = launch { watch(options.deviceSafety, watchGpu = mode.usesGpu) }

        val baselineStart = now()
        _state.value = RunState.Baseline(baselineStart, baselineStart + BASELINE_SECONDS * NANOS)
        waitOrStop(BASELINE_SECONDS * 1000L)?.let { early ->
            watcher.cancel()
            _state.value = RunState.Failed.StoppedBeforeLoad(early, safetyStop.takeIf { early == EndReason.Safety })
            return@coroutineScope
        }
        val baselineEnd = now()

        val settings = LoadSettings(quality = options.quality)
        val outcome = driver.start(workload, settings)
        val loadStart = now()
        var loadEnd = loadStart
        var reason = EndReason.Completed
        try {
            if (outcome != LoadDriver.STARTED) {
                watcher.cancel()
                _state.value = RunState.Failed.LoadFailed(outcome)
                return@coroutineScope
            }
            _state.value = RunState.Running(loadStart, duration.seconds?.let { loadStart + it * NANOS })
            reason = waitOrStop(duration.seconds?.let { it * 1000L }) ?: EndReason.Completed
            loadEnd = now()
        } finally {
            driver.stop()
        }
        watcher.cancel()

        _state.value = RunState.Analysing
        val info = device()
        val record = withContext(Dispatchers.Default) {
            val (summary, series) = RunAnalysis.analyze(
                baseline = sampler.log.between(baselineStart, baselineEnd),
                load = sampler.log.between(loadStart, loadEnd),
                clusters = layout.clusters,
                clusterName = { it.label() },
            )
            val safetyCheck = safetyStop.takeIf { reason == EndReason.Safety }
            RunRecord(
                id = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date(startedAtMillis)),
                startedAtMillis = startedAtMillis,
                mode = mode.name,
                workload = workload.describe(),
                plannedSeconds = duration.seconds,
                loadSeconds = (loadEnd - loadStart) / 1e9,
                stoppedEarly = reason != EndReason.Completed && duration.seconds != null,
                summary = summary,
                series = series,
                device = info,
                quality = options.quality.name.takeIf { mode.scene != null },
                safetyEnabled = options.deviceSafety,
                endReason = reason.name,
                safetyReason = safetyCheck?.reason?.name,
                safetyValue = safetyCheck?.value,
                appVersion = options.appVersion,
            )
        }
        withContext(Dispatchers.IO) { store.save(record) }
        _state.value = RunState.Finished(record)
    }

    /**
     * Twice a second: device safety (when on) over the newest sample, and,
     * when [watchGpu], the GPU's state once its load has run. Either can end
     * the run. Returns only by cancellation.
     */
    private suspend fun watch(deviceSafety: Boolean, watchGpu: Boolean) {
        val monitor = SafetyMonitor()
        var lastSample = -1L
        var gpuRan = false
        while (true) {
            val sample = sampler.latest.value
            if (sample != null && sample.timeNanos != lastSample) {
                lastSample = sample.timeNanos
                if (deviceSafety) {
                    val check = monitor.update(sample)
                    _safety.value = check
                    if (check.level == SafetyLevel.Stop) {
                        safetyStop = check
                        stop.complete(EndReason.Safety)
                    }
                }
                // A failure is only this run's once its own renderer was seen running.
                if (watchGpu && _state.value is RunState.Running) {
                    if (sample.gpu.state == GpuState.Running) gpuRan = true
                    if (gpuRan && (sample.gpu.state == GpuState.DeviceLost || sample.gpu.state == GpuState.Failed)) {
                        stop.complete(EndReason.GpuFailed)
                    }
                }
            }
            delay(WATCH_MILLIS)
        }
    }

    /** Waits [millis] (forever when null); the reason when a stop came first, null when the time ran out. */
    private suspend fun waitOrStop(millis: Long?): EndReason? {
        if (millis == null) return stop.await()
        return withTimeoutOrNull(millis) { stop.await() }
    }

    private fun now() = SystemClock.elapsedRealtimeNanos()

    companion object {
        const val BASELINE_SECONDS = 10L
        private const val NANOS = 1_000_000_000L
        private const val WATCH_MILLIS = 500L
    }
}
