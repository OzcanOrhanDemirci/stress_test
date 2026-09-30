package dev.ozcan.stress.lab

import android.os.SystemClock
import android.util.Log
import dev.ozcan.stress.engine.LoadDriver
import dev.ozcan.stress.engine.LoadSettings
import dev.ozcan.stress.engine.Workload
import dev.ozcan.stress.telemetry.Sample
import dev.ozcan.stress.telemetry.Sampler
import dev.ozcan.stress.telemetry.SysfsLayout
import dev.ozcan.stress.telemetry.ThermalGroup
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

sealed interface LabState {
    enum class Phase { Idle, Load }

    data object WaitingForBattery : LabState
    data class Cooling(val run: Int, val runs: Int, val hottest: Double?, val limit: Double) : LabState
    data class Measuring(
        val run: Int,
        val runs: Int,
        val workload: String,
        val phase: Phase,
        val endsAtNanos: Long,
    ) : LabState
    data class Finished(val results: List<LabResult>, val dir: File) : LabState
    data class Failed(val message: String) : LabState
}

/** Written last into the session directory; its presence means the session completed. */
@Serializable
data class LabSession(
    val seed: Long,
    val order: List<String>,
    val files: List<String>,
)

/**
 * Runs a [LabSpec] session without help from the development machine. Every
 * run leaves `NN_<load>.json` (analysis) and `NN_<load>.csv` (raw samples) in
 * a fresh directory under [outputDir]; `session.json` closes the session.
 * Progress goes to logcat under `STRESS_LAB` for when a cable is attached.
 */
class LabRunner(
    private val sampler: Sampler,
    private val driver: LoadDriver,
    private val layout: SysfsLayout,
    private val outputDir: File,
) {
    private val _state = MutableStateFlow<LabState?>(null)
    val state: StateFlow<LabState?> = _state.asStateFlow()

    suspend fun run(spec: LabSpec, seed: Long = System.currentTimeMillis()) {
        val dir = File(outputDir, SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()))
        withContext(Dispatchers.IO) { dir.mkdirs() }
        val order = spec.order(Random(seed))
        Log.i(LOG_TAG, "session dir=${dir.absolutePath} runs=${order.size} seed=$seed")

        val results = mutableListOf<LabResult>()
        val files = mutableListOf<String>()
        for ((index, workload) in order.withIndex()) {
            // A charger voids the power reading, so a session pauses while one is attached.
            if (spec.waitForBattery) waitForBattery()
            val (startTemps, cooled) = coolDown(index, order.size, spec.coolCelsius)
            val run = RunContext(workload, index, order.size, startTemps, cooled)
            val (result, file) = measure(spec, run, dir)
            results += result
            files += file.name
            if (result.startResult != STARTED) {
                _state.value = LabState.Failed("Yük başlatılamadı: ${result.workload} (${result.startResult})")
                return
            }
        }

        val session = LabSession(seed, order.map(Workload::describe), files)
        withContext(Dispatchers.IO) {
            File(dir, SESSION_FILE).writeText(prettyJson.encodeToString(LabSession.serializer(), session))
        }
        Log.i(LOG_TAG, "session-done dir=${dir.absolutePath} runs=${results.size}")
        _state.value = LabState.Finished(results, dir)
    }

    /** Returns once the last three seconds of samples were all on battery. */
    private suspend fun waitForBattery() {
        var since: Long? = null
        while (true) {
            val sample = sampler.latest.value
            if (sample != null && !sample.battery.plugged) {
                val start = since ?: sample.timeNanos.also { since = it }
                if (sample.timeNanos - start >= 3_000_000_000L) return
            } else {
                since = null
                _state.value = LabState.WaitingForBattery
            }
            delay(POLL_MILLIS)
        }
    }

    /**
     * Waits until both CPU clusters are at or below [limit] °C. Gives up after
     * [COOL_TIMEOUT_MILLIS] so a warm room cannot stall a session; the result
     * then records that the run started warm.
     */
    private suspend fun coolDown(index: Int, count: Int, limit: Double): Pair<Map<ThermalGroup, Double>, Boolean> {
        val deadline = SystemClock.elapsedRealtime() + COOL_TIMEOUT_MILLIS
        while (true) {
            val temps = sampler.latest.value?.sysfs?.temperatures.orEmpty()
            val hottest = listOfNotNull(temps[ThermalGroup.BigCores], temps[ThermalGroup.LittleCores]).maxOrNull()
            if (hottest != null && hottest <= limit) return temps to true
            if (SystemClock.elapsedRealtime() > deadline) return temps to false
            _state.value = LabState.Cooling(index, count, hottest, limit)
            delay(POLL_MILLIS)
        }
    }

    private suspend fun measure(spec: LabSpec, run: RunContext, dir: File): Pair<LabResult, File> {
        val label = run.load.describe()
        val idleStart = now()
        _state.value = LabState.Measuring(run.index, run.count, label, LabState.Phase.Idle, idleStart + seconds(spec.idleSeconds))
        delay(spec.idleSeconds * 1000L)
        val idleEnd = now()

        val settings = LoadSettings(spec.nice, spec.batchMillis, spec.scene, spec.sceneScalePercent)
        val outcome = driver.start(run.load, settings)
        val loadStart = now()
        var loadEnd = loadStart
        try {
            if (outcome == STARTED) {
                _state.value = LabState.Measuring(run.index, run.count, label, LabState.Phase.Load, loadStart + seconds(spec.loadSeconds))
                delay(spec.loadSeconds * 1000L)
                loadEnd = now()
            }
        } finally {
            // Also after a failed start, to stop the parts that did start.
            driver.stop()
        }

        val idle = sampler.log.between(idleStart, idleEnd)
        val load = sampler.log.between(loadStart, loadEnd)
        val result = LabAnalysis.analyze(spec, run, outcome, idle, load, layout.clusters)
        val name = "%02d_%s".format(Locale.ROOT, run.index, label.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        val file = withContext(Dispatchers.IO) {
            File(dir, "$name.csv").bufferedWriter().use { out ->
                SampleCsv.write(out, layout, idleStart, listOf("idle" to idle, "load" to load))
            }
            File(dir, "$name.json").apply { writeText(prettyJson.encodeToString(LabResult.serializer(), result)) }
        }
        // Logcat truncates long lines, so the scripts pull the JSON; this line only says where it is.
        Log.i(LOG_TAG, "done file=${file.absolutePath} load_w=${result.load.meanWatts} idle_w=${result.idle.meanWatts}")
        return result to file
    }

    private fun now() = SystemClock.elapsedRealtimeNanos()

    private fun seconds(s: Int) = s * 1_000_000_000L

    companion object {
        const val LOG_TAG = "STRESS_LAB"
        const val SESSION_FILE = "session.json"
        const val STARTED = LoadDriver.STARTED
        private const val POLL_MILLIS = 1_000L
        private const val COOL_TIMEOUT_MILLIS = 15 * 60 * 1000L
        private val prettyJson = Json { encodeDefaults = true; prettyPrint = true }
    }
}
