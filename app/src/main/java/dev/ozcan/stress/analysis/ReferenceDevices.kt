package dev.ozcan.stress.analysis

import android.content.Context
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

    /** The first reference device that measured [mode], with its watts. */
    fun forMode(context: Context, mode: StressMode): Pair<ReferenceDevice, Double>? =
        load(context).firstNotNullOfOrNull { device -> device.wattsFor(mode)?.let { device to it } }

    private const val FILE = "reference_devices.json"
}
