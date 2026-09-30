package dev.ozcan.stress.telemetry

import dev.ozcan.stress.engine.NativeBridge

/** Raw kgsl `gpubusy` counters: busy time and total time of the driver's current window. */
data class GpuBusy(val busy: Long, val total: Long) {
    val fraction: Double? get() = if (total > 0 && busy in 0..total) busy.toDouble() / total else null
}

data class SysfsReading(
    /** Current clock of each cluster in [SysfsLayout.clusters] order; null when unreadable. */
    val clusterFreqKhz: List<Long?>,
    /** Hottest readable zone of each group, in °C. Groups with no readable zone are absent. */
    val temperatures: Map<ThermalGroup, Double>,
    val gpuBusy: GpuBusy?,
)

/** Which of the layout's files this process may read (SELinux decides, not file modes). */
data class SensorAvailability(
    val clusters: List<Pair<CpuCluster, Boolean>>,
    val zones: List<Pair<ThermalZone, Boolean>>,
    val gpuBusy: Boolean,
) {
    fun readableZones(group: ThermalGroup): Int = zones.count { it.first.group == group && it.second }
    fun totalZones(group: ThermalGroup): Int = zones.count { it.first.group == group }
}

/** Reads the layout's files through the native reader (one pread each). */
class NativeSensors(val layout: SysfsLayout) : AutoCloseable {

    private val paths = layout.paths
    private val values = LongArray(paths.size * VALUES_PER_FILE)

    val availability: SensorAvailability

    init {
        val readable = NativeBridge.sensorsOpen(paths.toTypedArray())
        val clusterCount = layout.clusters.size
        val zoneCount = layout.zones.size
        availability = SensorAvailability(
            clusters = layout.clusters.mapIndexed { i, c -> c to readable[i] },
            zones = layout.zones.mapIndexed { i, z -> z to readable[clusterCount + i] },
            gpuBusy = readable[clusterCount + zoneCount],
        )
    }

    @Synchronized
    fun read(): SysfsReading {
        NativeBridge.sensorsRead(values)
        return decode(layout, values)
    }

    override fun close() = NativeBridge.sensorsClose()

    companion object {
        const val VALUES_PER_FILE = 2
        private const val MISSING = Long.MIN_VALUE

        /** Zone readings outside this range are sensor placeholders (e.g. -273000). */
        private val PLAUSIBLE_MILLI_C = -40_000L..150_000L

        /** Turns the flat native value array into a reading. Separate from I/O so it can be tested. */
        fun decode(layout: SysfsLayout, values: LongArray): SysfsReading {
            fun first(index: Int): Long? = values[index * VALUES_PER_FILE].takeIf { it != MISSING }
            fun second(index: Int): Long? = values[index * VALUES_PER_FILE + 1].takeIf { it != MISSING }

            val clusterCount = layout.clusters.size
            val freqs = List(clusterCount) { first(it) }

            val temperatures = HashMap<ThermalGroup, Double>()
            layout.zones.forEachIndexed { i, zone ->
                val milli = first(clusterCount + i)?.takeIf { it in PLAUSIBLE_MILLI_C } ?: return@forEachIndexed
                val celsius = milli / 1000.0
                temperatures.merge(zone.group, celsius, ::maxOf)
            }

            val gpuIndex = clusterCount + layout.zones.size
            val busy = first(gpuIndex)
            val total = second(gpuIndex)
            val gpu = if (busy != null && total != null) GpuBusy(busy, total) else null

            return SysfsReading(freqs, temperatures, gpu)
        }
    }
}
