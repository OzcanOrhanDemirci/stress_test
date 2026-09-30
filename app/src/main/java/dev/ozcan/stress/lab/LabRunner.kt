package dev.ozcan.stress.lab

import android.os.SystemClock
import android.util.Log
import dev.ozcan.stress.engine.CpuEngine
import dev.ozcan.stress.engine.StartResult
import dev.ozcan.stress.telemetry.Sample
import dev.ozcan.stress.telemetry.Sampler
import dev.ozcan.stress.telemetry.SysfsLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface LabState {
    enum class Phase { Idle, Load }

    data class Measuring(val phase: Phase, val endsAtNanos: Long) : LabState
    data class Finished(val result: LabResult, val file: File) : LabState
    data class Failed(val message: String) : LabState
}

/**
 * Runs one [LabSpec]: an idle baseline, then the load, then writes the
 * analysis (JSON) and the raw samples (CSV) to [outputDir], which `adb pull`
 * can reach. Ends with one log line tagged `STRESS_LAB` for the scripts.
 */
class LabRunner(
    private val sampler: Sampler,
    private val cpu: CpuEngine,
    private val layout: SysfsLayout,
    private val outputDir: File,
) {
    private val _state = MutableStateFlow<LabState?>(null)
    val state: StateFlow<LabState?> = _state.asStateFlow()

    suspend fun run(spec: LabSpec) {
        val idleStart = now()
        _state.value = LabState.Measuring(LabState.Phase.Idle, idleStart + seconds(spec.idleSeconds))
        delay(spec.idleSeconds * 1000L)
        val idleEnd = now()

        val startResult = withContext(Dispatchers.Default) { cpu.start(spec.assignment, spec.nice, spec.batchMillis) }
        val loadStart = now()
        var loadEnd = loadStart
        if (startResult == StartResult.Started) {
            _state.value = LabState.Measuring(LabState.Phase.Load, loadStart + seconds(spec.loadSeconds))
            try {
                delay(spec.loadSeconds * 1000L)
            } finally {
                loadEnd = now()
                withContext(Dispatchers.Default) { cpu.stop() }
            }
        }

        val idle = sampler.log.between(idleStart, idleEnd)
        val load = sampler.log.between(loadStart, loadEnd)
        val result = LabAnalysis.analyze(spec, startResult, idle, load, layout.clusters)
        val file = withContext(Dispatchers.IO) { write(spec, result, idle, load, idleStart) }

        // Logcat truncates long lines, so the scripts pull the JSON file; this
        // line only says where it is.
        Log.i(LOG_TAG, "done file=${file.absolutePath} load_w=${result.load.meanWatts} idle_w=${result.idle.meanWatts}")
        _state.value = if (startResult == StartResult.Started) {
            LabState.Finished(result, file)
        } else {
            LabState.Failed("Yük başlatılamadı: ${startResult.name}")
        }
    }

    private fun write(spec: LabSpec, result: LabResult, idle: List<Sample>, load: List<Sample>, origin: Long): File {
        outputDir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val name = "${stamp}_${spec.tag.replace(Regex("[^A-Za-z0-9._-]"), "_")}"
        File(outputDir, "$name.csv").bufferedWriter().use { out ->
            SampleCsv.write(out, layout, origin, listOf("idle" to idle, "load" to load))
        }
        return File(outputDir, "$name.json").apply { writeText(prettyJson.encodeToString(LabResult.serializer(), result)) }
    }

    private fun now() = SystemClock.elapsedRealtimeNanos()

    private fun seconds(s: Int) = s * 1_000_000_000L

    companion object {
        const val LOG_TAG = "STRESS_LAB"
        private val prettyJson = Json { encodeDefaults = true; prettyPrint = true }
    }
}
