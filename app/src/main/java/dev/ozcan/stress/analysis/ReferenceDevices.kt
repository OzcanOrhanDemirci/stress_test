package dev.ozcan.stress.analysis

import android.content.Context
import dev.ozcan.stress.engine.SceneQuality
import dev.ozcan.stress.run.RunRecord
import dev.ozcan.stress.run.StressMode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One phone measured with this app whose numbers ship inside it, so a result
 * can say what the same test drew elsewhere. The list starts with the
 * reference phone; measurements that arrive through the issue form join it.
 */
@Serializable
data class ReferenceDevice(
    val name: String,
    val chipset: String,
    /** Where the numbers came from, as the app cites them. */
    val source: String,
    /** Mean watts per [StressMode] name, as this app measured them. */
    val watts: Map<String, Double>,
) {
    fun wattsFor(mode: StressMode): Double? = watts[mode.name]

    fun wattsFor(record: RunRecord): Double? {
        val mode = StressMode.of(record.mode) ?: return null
        if (!Comparison.isEligible(record)) return null
        // The bundled cinematic reading is the original pool scene at 45%.
        if (mode.scene != null && record.drawnQuality != null && record.drawnQuality != SceneQuality.Medium.name) return null
        // It was measured without the optional burner; a run with it is a heavier workload.
        if (mode.scene != null && record.summary.gpuUnit != null) return null
        return wattsFor(mode)
    }
}

/** The bundled reference measurements; the app stays offline, so they travel with it. */
object ReferenceDevices {

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var cached: List<ReferenceDevice>? = null

    fun load(context: Context): List<ReferenceDevice> {
        cached?.let { return it }
        val devices = runCatching {
            json.decodeFromString<List<ReferenceDevice>>(context.assets.open(FILE).bufferedReader().readText())
        }.getOrDefault(emptyList())
        cached = devices
        return devices
    }

    /** The first reference device that measured this workload, with its watts. */
    fun forRun(context: Context, record: RunRecord): Pair<ReferenceDevice, Double>? =
        load(context).firstNotNullOfOrNull { device -> device.wattsFor(record)?.let { device to it } }

    private const val FILE = "reference_devices.json"
}
