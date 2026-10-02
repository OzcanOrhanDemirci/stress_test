package dev.ozcan.stress.telemetry

import dev.ozcan.stress.engine.NativeBridge

/** The GPU's load as its driver counts it: busy time out of total (a percentage reads as out of 100). */
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
    fun readable(group: ThermalGroup): Boolean = readableZones(group) > 0
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
            gpuBusy = layout.gpuBusyPath != null && readable.getOrElse(clusterCount + zoneCount) { false },
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

        /** Readings outside this range, in °C, are sensor placeholders (e.g. -273000) or not temperatures. */
        private val PLAUSIBLE_CELSIUS = -40.0..150.0

        /**
         * A zone's reading in °C. Kernels report millidegrees, but some
         * drivers give degrees or tenths: a value is read in the unit that
         * puts it in a phone's range. Zero is a sensor that is not running.
         */
        fun celsius(raw: Long): Double? {
            if (raw == 0L) return null
            val value = when {
                raw in -40L..150L -> raw.toDouble()
                raw in 151L..1500L -> raw / 10.0
                else -> raw / 1000.0
            }
            return value.takeIf { it in PLAUSIBLE_CELSIUS }
        }

        /** Turns the flat native value array into a reading. Separate from I/O so it can be tested. */
        fun decode(layout: SysfsLayout, values: LongArray): SysfsReading {
            fun first(index: Int): Long? = values.getOrNull(index * VALUES_PER_FILE)?.takeIf { it != MISSING }
            fun second(index: Int): Long? = values.getOrNull(index * VALUES_PER_FILE + 1)?.takeIf { it != MISSING }

            val clusterCount = layout.clusters.size
            val freqs = List(clusterCount) { first(it)?.takeIf { khz -> khz > 0 } }

            val temperatures = HashMap<ThermalGroup, Double>()
            layout.zones.forEachIndexed { i, zone ->
                val celsius = first(clusterCount + i)?.let(::celsius) ?: return@forEachIndexed
                temperatures.merge(zone.group, celsius, ::maxOf)
            }

            val gpuIndex = clusterCount + layout.zones.size
            val busy = first(gpuIndex)
            val gpu = when {
                layout.gpuBusyPath == null || busy == null -> null
                layout.gpuBusyFormat == GpuBusyFormat.Percent -> GpuBusy(busy, 100)
                else -> second(gpuIndex)?.let { GpuBusy(busy, it) }
            }
            return SysfsReading(freqs, temperatures, gpu)
        }
    }
}
