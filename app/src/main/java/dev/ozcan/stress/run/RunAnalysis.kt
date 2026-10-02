package dev.ozcan.stress.run

import dev.ozcan.stress.analysis.CurrentConvention
import dev.ozcan.stress.analysis.Point
import dev.ozcan.stress.analysis.Power
import dev.ozcan.stress.analysis.Stats
import dev.ozcan.stress.telemetry.CpuCluster
import dev.ozcan.stress.telemetry.Sample
import dev.ozcan.stress.telemetry.ThermalGroup
import kotlin.math.abs
import kotlin.math.floor

/** Turns a run's samples into its verdict and curves. Pure, so it can be tested with made-up samples. */
object RunAnalysis {

    /** Groups drawn on the temperature chart and kept in the summary. */
    val CHART_GROUPS = listOf(ThermalGroup.Cpu, ThermalGroup.Gpu, ThermalGroup.Memory, ThermalGroup.Skin)

    /** The battery's key among the temperatures; the groups use [ThermalGroup.key]. */
    const val BATTERY = "battery"

    private const val PEAK_WINDOW_SECONDS = 5.0
    private const val SUSTAINED_SECONDS = 300.0
    private const val THROTTLE_FRACTION = 0.97
    private const val THROTTLE_HOLD_SECONDS = 3.0
    /** The first seconds of a scene build its pipelines and history: left out of the lowest frame rate. */
    private const val FPS_SETTLE_SECONDS = 10

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
        val span = load.lastOrNull()?.let { Power.seconds(it.timeNanos, origin) } ?: 0.0
        val powerValid = (baseline + load).none { it.battery.plugged }

        // Without an instantaneous current, the charge counter's slope still
        // gives the mean power, though not its peaks.
        val counterWatts = if (watts.isEmpty() && powerValid) counterPower(load, origin) else null
        val idle = convention?.let { Stats.meanOf(Power.watts(baseline, it, baseline.firstOrNull()?.timeNanos ?: 0L)) }
        val mean = Stats.meanOf(watts) ?: counterWatts
        val sustained = Stats.meanOf(Stats.window(watts, span - SUSTAINED_SECONDS, Double.MAX_VALUE)) ?: counterWatts
        val cpuRates = rates(load) { s -> s.cpu.workers.sumOf { it.work } }
        val gpuRunning = load.any { it.gpu.isRunning }
        // A burner paces its frames to a fixed time, so only a scene's frame rate means anything.
        val scene = gpuRunning && load.none { it.gpu.burner != null }

        val summary = RunSummary(
            idleWatts = idle,
            peakWatts = if (watts.isEmpty()) null else Stats.maxWindowMean(watts, PEAK_WINDOW_SECONDS) ?: mean,
            meanWatts = mean,
            sustainedWatts = sustained,
            energyWattHours = energy(watts) ?: counterWatts?.let { it * span / 3600.0 },
            batteryStartPercent = load.firstOrNull()?.battery?.levelPercent,
            batteryEndPercent = load.lastOrNull()?.battery?.levelPercent,
            batteryLifeHours = batteryLife(load, sustained),
            maxTemperatures = temperatures(load) { it.maxOrNull() },
            startTemperatures = temperatures(load.take(1)) { it.firstOrNull() },
            // Only loaded clusters: an idle cluster slows down because it has nothing to do, not because it is hot.
            firstThrottleSeconds = clusters.withIndex()
                .filter { (_, c) -> load.any { s -> c.cpus.any { cpu -> s.cpu.workers.getOrNull(cpu)?.kernel != null } } }
                .associate { (i, c) -> clusterName(c) to firstThrottle(load, i, c.maxFreqKhz, origin) },
            cpuStability = stability(load) { s -> s.cpu.workers.sumOf { it.work } },
            gpuStability = if (gpuRunning) stability(load) { it.gpu.work.toDouble() } else null,
            computationErrors = (load.lastOrNull()?.cpu?.errors ?: 0L) + (load.lastOrNull()?.gpu?.errors ?: 0L),
            powerValid = powerValid,
            cpuMeanRate = cpuRates?.mean,
            cpuUnit = load.firstNotNullOfOrNull { s -> s.cpu.workers.firstNotNullOfOrNull { it.kernel } }?.unit?.name,
            cpuStartRate = cpuRates?.start,
            cpuEndRate = cpuRates?.end,
            gpuMeanRate = if (gpuRunning && load.any { it.gpu.burner != null }) rates(load) { it.gpu.work.toDouble() }?.mean else null,
            gpuUnit = load.firstNotNullOfOrNull { it.gpu.burner }?.unit?.name,
            meanFps = if (scene) rates(load) { it.gpu.frames.toDouble() }?.mean else null,
            maxThermalStatus = load.maxOfOrNull { it.thermalStatus },
            powerFromCounter = counterWatts != null,
        )
        val series = series(load, watts, clusters, clusterName, origin)
        val settledFps = series.fps.drop(FPS_SETTLE_SECONDS).filterNotNull()
        return summary.copy(minFps = settledFps.minOrNull()) to series
    }

    private fun List<Sample>.unplugged() = filter { !it.battery.plugged }

    /** Mean battery output power from the charge counter's slope and the mean voltage, in watts. */
    private fun counterPower(load: List<Sample>, origin: Long): Double? {
        val amps = Power.chargeCounterAmps(load, origin)?.takeIf { it > 0 } ?: return null
        val volts = Stats.mean(load.mapNotNull { s -> s.battery.voltageMillivolts?.let { it / 1000.0 } }) ?: return null
        return amps * volts
    }

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
        for (group in CHART_GROUPS) pick(samples.mapNotNull { it.sysfs.temperatures[group] })?.let { result[group.key] = it }
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

    private data class Rates(val mean: Double, val start: Double?, val end: Double?)

    /**
     * A counter's rate over the whole load, and over its first and last
     * minute (a tenth of the load each, at least ten seconds, when the load
     * is under ten minutes): how much the phone slowed as it heated.
     */
    private fun rates(load: List<Sample>, counter: (Sample) -> Double): Rates? {
        if (load.size < 2) return null
        val first = load.first()
        val last = load.last()
        val span = (last.timeNanos - first.timeNanos) / 1e9
        if (span <= 0) return null
        fun at(seconds: Double): Sample =
            load.minBy { abs((it.timeNanos - first.timeNanos) / 1e9 - seconds) }
        fun rate(a: Sample, b: Sample): Double? {
            val dt = (b.timeNanos - a.timeNanos) / 1e9
            val done = counter(b) - counter(a)
            return if (dt > 0 && done >= 0) done / dt else null
        }
        val mean = rate(first, last) ?: return null
        val window = (span / 10).coerceIn(10.0, 60.0)
        val edges = if (span >= 2 * window) rate(first, at(window)) to rate(at(span - window), last) else null to null
        return Rates(mean, edges.first, edges.second)
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
            val from = load.minBy { abs(Power.seconds(it.timeNanos, origin) - i * slice) }
            val to = load.minBy { abs(Power.seconds(it.timeNanos, origin) - (i + 1) * slice) }
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
        fun perSecondRate(counter: (Sample) -> Double): List<Double?> = firsts.zipWithNext { a, b ->
            val dt = (b.timeNanos - a.timeNanos) / 1e9
            val done = counter(b) - counter(a)
            if (dt > 0 && done >= 0) done / dt else null
        } + listOf(null)
        fun relative(work: (Sample) -> Double): List<Double?> {
            val rates = perSecondRate(work)
            val best = rates.filterNotNull().maxOrNull()?.takeIf { it > 0 } ?: return rates.map { null }
            return rates.map { it?.div(best) }
        }
        val gpuRunning = load.any { it.gpu.isRunning }
        val scene = gpuRunning && load.none { it.gpu.burner != null }

        return RunSeries(
            seconds = seconds,
            watts = buckets.keys.map { key -> Stats.meanOf(wattBuckets[key].orEmpty()) },
            temperatures = CHART_GROUPS
                .filter { group -> load.any { it.sysfs.temperatures[group] != null } }
                .associate { group ->
                    group.key to perSecond { samples -> Stats.mean(samples.mapNotNull { it.sysfs.temperatures[group] }) }
                },
            batteryCelsius = perSecond { samples -> samples.lastOrNull()?.battery?.temperatureCelsius },
            clocksMhz = clusters.mapIndexed { i, cluster ->
                clusterName(cluster) to perSecond { samples -> Stats.mean(samples.mapNotNull { it.sysfs.clusterFreqKhz.getOrNull(i)?.div(1000.0) }) }
            }.toMap(),
            cpuRelative = relative { s -> s.cpu.workers.sumOf { it.work } },
            gpuRelative = if (gpuRunning && load.any { it.gpu.burner != null }) relative { it.gpu.work.toDouble() } else seconds.map { null },
            batteryPercent = perSecond { samples -> samples.lastOrNull()?.battery?.levelPercent },
            fps = if (scene) perSecondRate { it.gpu.frames.toDouble() } else emptyList(),
        )
    }
}
