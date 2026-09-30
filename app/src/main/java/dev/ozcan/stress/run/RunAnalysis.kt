package dev.ozcan.stress.run

import dev.ozcan.stress.analysis.CurrentConvention
import dev.ozcan.stress.analysis.Point
import dev.ozcan.stress.analysis.Power
import dev.ozcan.stress.analysis.Stats
import dev.ozcan.stress.telemetry.CpuCluster
import dev.ozcan.stress.telemetry.Sample
import dev.ozcan.stress.telemetry.ThermalGroup
import kotlin.math.floor

/** Turns a run's samples into its verdict and curves. Pure, so it can be tested with made-up samples. */
object RunAnalysis {

    /** Groups drawn on the temperature chart and kept in the summary. */
    val CHART_GROUPS = listOf(ThermalGroup.BigCores, ThermalGroup.LittleCores, ThermalGroup.Gpu, ThermalGroup.Memory)
    const val BATTERY = "Pil"

    private const val PEAK_WINDOW_SECONDS = 5.0
    private const val SUSTAINED_SECONDS = 300.0
    private const val THROTTLE_FRACTION = 0.97
    private const val THROTTLE_HOLD_SECONDS = 3.0

    /** Nominal cell voltage, to turn the gauge's charge into energy when estimating battery life. */
    private const val NOMINAL_VOLTS = 3.87

    fun analyze(
        baseline: List<Sample>,
        load: List<Sample>,
        clusters: List<CpuCluster>,
        clusterName: (CpuCluster) -> String,
    ): Pair<RunSummary, RunSeries> {
        val convention = CurrentConvention.infer(baseline.unplugged().mapNotNull { it.battery.currentRaw })
            ?: CurrentConvention.infer(load.unplugged().mapNotNull { it.battery.currentRaw })
        val origin = load.firstOrNull()?.timeNanos ?: 0L
        val watts = convention?.let { Power.watts(load, it, origin) }.orEmpty()
        val idle = convention?.let { Stats.meanOf(Power.watts(baseline, it, baseline.firstOrNull()?.timeNanos ?: 0L)) }
        val span = load.lastOrNull()?.let { Power.seconds(it.timeNanos, origin) } ?: 0.0
        val mean = Stats.meanOf(watts)
        val sustained = Stats.meanOf(Stats.window(watts, span - SUSTAINED_SECONDS, Double.MAX_VALUE))

        val summary = RunSummary(
            idleWatts = idle,
            peakWatts = (if (watts.isEmpty()) null else Stats.maxWindowMean(watts, PEAK_WINDOW_SECONDS)) ?: mean,
            meanWatts = mean,
            sustainedWatts = sustained,
            energyWattHours = energy(watts),
            batteryStartPercent = load.firstOrNull()?.battery?.levelPercent,
            batteryEndPercent = load.lastOrNull()?.battery?.levelPercent,
            batteryLifeHours = batteryLife(load, sustained),
            maxTemperatures = temperatures(load) { it.maxOrNull() },
            startTemperatures = temperatures(load.take(1)) { it.firstOrNull() },
            firstThrottleSeconds = clusters.mapIndexed { i, c -> clusterName(c) to firstThrottle(load, i, c.maxFreqKhz, origin) }.toMap(),
            cpuStability = stability(load) { s -> s.cpu.workers.sumOf { it.work } },
            gpuStability = if (load.any { it.gpu.isRunning }) stability(load) { it.gpu.work.toDouble() } else null,
            computationErrors = (load.lastOrNull()?.cpu?.errors ?: 0L) + (load.lastOrNull()?.gpu?.errors ?: 0L),
            powerValid = (baseline + load).none { it.battery.plugged },
        )
        return summary to series(load, watts, clusters, clusterName, origin)
    }

    private fun List<Sample>.unplugged() = filter { !it.battery.plugged }

    /** Trapezoidal integral of power over time, in watt-hours. */
    private fun energy(watts: List<Point>): Double? {
        if (watts.size < 2) return null
        val joules = watts.zipWithNext { a, b -> (a.value + b.value) / 2 * (b.seconds - a.seconds) }.sum()
        return joules / 3600.0
    }

    /**
     * The gauge knows the charge left and the level it corresponds to, so the
     * full charge follows; at the nominal voltage that is the energy of a full
     * battery, and dividing by the sustained power gives its life at this load.
     */
    private fun batteryLife(load: List<Sample>, sustainedWatts: Double?): Double? {
        val start = load.firstOrNull()?.battery ?: return null
        val counter = start.chargeCounterMicroAmpHours ?: return null
        val level = start.levelPercent?.takeIf { it > 0 } ?: return null
        val watts = sustainedWatts?.takeIf { it > 0 } ?: return null
        val fullWattHours = counter * 1e-6 * NOMINAL_VOLTS * 100.0 / level
        return fullWattHours / watts
    }

    private fun temperatures(samples: List<Sample>, pick: (List<Double>) -> Double?): Map<String, Double> {
        val result = LinkedHashMap<String, Double>()
        for (group in CHART_GROUPS) pick(samples.mapNotNull { it.sysfs.temperatures[group] })?.let { result[group.label] = it }
        pick(samples.mapNotNull { it.battery.temperatureCelsius })?.let { result[BATTERY] = it }
        return result
    }

    /** First moment the cluster's clock fell below THROTTLE_FRACTION of its top and stayed there THROTTLE_HOLD_SECONDS. */
    private fun firstThrottle(load: List<Sample>, cluster: Int, maxKhz: Long, origin: Long): Double? {
        if (maxKhz <= 0) return null
        val limit = maxKhz * THROTTLE_FRACTION
        var since: Double? = null
        for (s in load) {
            val khz = s.sysfs.clusterFreqKhz.getOrNull(cluster) ?: continue
            val t = Power.seconds(s.timeNanos, origin)
            if (khz < limit) {
                val start = since ?: t.also { since = it }
                if (t - start >= THROTTLE_HOLD_SECONDS) return start
            } else {
                since = null
            }
        }
        return null
    }

    /**
     * Splits the load into equal slices (a minute each, ten seconds for loads
     * under two minutes) and compares the slowest slice with the fastest.
     */
    private fun stability(load: List<Sample>, work: (Sample) -> Double): Double? {
        if (load.size < 2) return null
        val origin = load.first().timeNanos
        val span = Power.seconds(load.last().timeNanos, origin)
        val slice = if (span >= 120.0) 60.0 else 10.0
        val count = floor(span / slice).toInt()
        if (count < 2) return null
        val rates = (0 until count).mapNotNull { i ->
            val from = load.minBy { kotlin.math.abs(Power.seconds(it.timeNanos, origin) - i * slice) }
            val to = load.minBy { kotlin.math.abs(Power.seconds(it.timeNanos, origin) - (i + 1) * slice) }
            val seconds = (to.timeNanos - from.timeNanos) / 1e9
            if (seconds > 0) (work(to) - work(from)) / seconds else null
        }
        val best = rates.maxOrNull() ?: return null
        return if (best > 0) rates.min() / best else null
    }

    private fun series(
        load: List<Sample>,
        watts: List<Point>,
        clusters: List<CpuCluster>,
        clusterName: (CpuCluster) -> String,
        origin: Long,
    ): RunSeries {
        val buckets = load.groupBy { floor(Power.seconds(it.timeNanos, origin)).toInt() }.toSortedMap()
        val wattBuckets = watts.groupBy { floor(it.seconds).toInt() }
        val seconds = buckets.keys.map { it.toDouble() }
        val firsts = buckets.values.map { it.first() }

        fun <T> perSecond(value: (List<Sample>) -> T): List<T> = buckets.values.map(value)
        fun relative(work: (Sample) -> Double): List<Double?> {
            val rates = firsts.zipWithNext { a, b ->
                val dt = (b.timeNanos - a.timeNanos) / 1e9
                if (dt > 0) (work(b) - work(a)) / dt else null
            } + listOf(null)
            val best = rates.filterNotNull().maxOrNull()?.takeIf { it > 0 } ?: return rates.map { null }
            return rates.map { it?.div(best) }
        }

        return RunSeries(
            seconds = seconds,
            watts = buckets.keys.map { key -> Stats.meanOf(wattBuckets[key].orEmpty()) },
            temperatures = CHART_GROUPS.associate { group ->
                group.label to perSecond { samples -> Stats.mean(samples.mapNotNull { it.sysfs.temperatures[group] }) }
            },
            batteryCelsius = perSecond { samples -> samples.lastOrNull()?.battery?.temperatureCelsius },
            clocksMhz = clusters.mapIndexed { i, cluster ->
                clusterName(cluster) to perSecond { samples -> Stats.mean(samples.mapNotNull { it.sysfs.clusterFreqKhz.getOrNull(i)?.div(1000.0) }) }
            }.toMap(),
            cpuRelative = relative { s -> s.cpu.workers.sumOf { it.work } },
            gpuRelative = if (load.any { it.gpu.isRunning }) relative { it.gpu.work.toDouble() } else seconds.map { null },
            batteryPercent = perSecond { samples -> samples.lastOrNull()?.battery?.levelPercent },
        )
    }
}
