package dev.ozcan.stress.run

import dev.ozcan.stress.analysis.Point
import dev.ozcan.stress.analysis.Power
import dev.ozcan.stress.analysis.Stats
import dev.ozcan.stress.telemetry.CpuCluster
import dev.ozcan.stress.telemetry.Sample
import dev.ozcan.stress.telemetry.ThermalGroup
import dev.ozcan.stress.telemetry.label
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
    ): Pair<RunSummary, RunSeries> = analyze(RunPowerBaseline(baseline), load, clusters, clusterName)

    internal fun analyze(
        baseline: RunPowerBaseline,
        load: List<Sample>,
        clusters: List<CpuCluster>,
        clusterName: (CpuCluster) -> String,
    ): Pair<RunSummary, RunSeries> {
        val origin = load.firstOrNull()?.timeNanos ?: 0L
        val span = load.lastOrNull()?.let { Power.seconds(it.timeNanos, origin) } ?: 0.0
        // This stored flag means a charger was attached, including in older runs.
        val powerValid = !baseline.plugged && load.none { it.battery.plugged }
        val canMeasure = baseline.canMeasure(load)
        val idleRaw = baseline.lastCurrent
        // A gauge can freeze just before the load starts, while its last idle
        // value still looks fresh. The load has to show some response.
        val responds = idleRaw == null || load.any { it.battery.measurableCurrent?.let { raw -> raw != idleRaw } == true }
        val direct = if (canMeasure && responds) {
            baseline.convention?.let { Power.windowWatts(load, it, origin) }.orEmpty()
        } else emptyList()
        val directMean = Power.meanWatts(direct)
        val energyWatts = if (canMeasure) Power.energyCounterWatts(load, origin) else null
        val chargeWatts = if (canMeasure) Power.chargeCounterWatts(load, origin) else null
        val counter = energyWatts ?: chargeWatts
        val mismatch = directMean != null && counter != null && !Power.agrees(directMean, counter)
        val watts = if (mismatch) emptyList() else direct
        // With no usable current the counters can give a mean. When two
        // sources disagree, only report a mean if the two counters corroborate it.
        val counterWatts = counter?.takeIf {
            watts.isEmpty() && (!mismatch ||
                (energyWatts != null && chargeWatts != null && Power.agrees(energyWatts, chargeWatts)))
        }
        val idle = baseline.watts.takeIf { canMeasure && responds && !mismatch }
        val mean = Power.meanWatts(watts) ?: counterWatts
        val sustained = Power.meanWatts(Stats.window(watts, span - SUSTAINED_SECONDS, Double.MAX_VALUE)) ?: counterWatts?.let {
            if (span <= SUSTAINED_SECONDS) it else {
                val tail = load.filter { Power.seconds(it.timeNanos, origin) >= span - SUSTAINED_SECONDS }
                if (energyWatts != null) Power.energyCounterWatts(tail, origin) else Power.chargeCounterWatts(tail, origin)
            }
        }
        val cpuRates = rates(load) { s -> s.cpu.workers.sumOf { it.work } }
        val gpuRunning = load.any { it.gpu.isRunning }
        // A burner paces its frames to a fixed time, so only a scene's frame rate means anything.
        val scene = gpuRunning && load.none { it.gpu.burner != null }
        val names = clusters.map(clusterName)
        val counts = names.groupingBy { it }.eachCount()
        val labels = names.mapIndexed { i, name ->
            if (counts.getValue(name) == 1) name else "$name (${clusters[i].copy(coreName = null).label()})"
        }

        val summary = RunSummary(
            idleWatts = idle,
            peakWatts = Stats.maxWindowMean(watts, PEAK_WINDOW_SECONDS),
            meanWatts = mean,
            sustainedWatts = sustained,
            energyWattHours = Power.joules(watts)?.div(3600.0) ?: counterWatts?.let { it * span / 3600.0 },
            batteryStartPercent = load.firstOrNull()?.battery?.levelPercent,
            batteryEndPercent = load.lastOrNull()?.battery?.levelPercent,
            batteryLifeHours = batteryLife(load, sustained),
            maxTemperatures = temperatures(load) { it.maxOrNull() },
            startTemperatures = temperatures(load.take(1)) { it.firstOrNull() },
            firstThrottleSeconds = throttles(load, clusters, labels, origin),
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
            maxThermalStatus = load.map { it.thermalStatus }.filter { it >= 0 }.maxOrNull(),
            powerFromCounter = counterWatts != null,
        )
        val series = series(load, watts, labels, origin)
        val settledFps = series.fps.drop(FPS_SETTLE_SECONDS).filterNotNull()
        return summary.copy(minFps = settledFps.minOrNull()) to series
    }

    /**
     * The gauge knows the charge left and the level it corresponds to, so the
     * full charge follows; at the nominal voltage that is the energy of a full
     * battery, and dividing by the sustained power gives its life at this load.
     */
    private fun batteryLife(load: List<Sample>, sustainedWatts: Double?): Double? {
        val start = load.firstOrNull()?.battery ?: return null
        val level = start.levelPercent?.takeIf { it >= 10 } ?: return null
        val watts = sustainedWatts?.takeIf { it > 0 } ?: return null
        val remainingWattHours = start.energyCounterNanoWattHours?.times(1e-9) ?: run {
            val counter = start.chargeCounterMicroAmpHours ?: return null
            val mv = start.voltageMillivolts ?: return null
            val nominal = if (mv > 5_000) 2 * NOMINAL_VOLTS else NOMINAL_VOLTS
            counter * 1e-6 * nominal
        }
        val fullWattHours = remainingWattHours * 100.0 / level
        return fullWattHours / watts
    }

    private fun temperatures(samples: List<Sample>, pick: (List<Double>) -> Double?): Map<String, Double> {
        val result = LinkedHashMap<String, Double>()
        for (group in CHART_GROUPS) pick(samples.mapNotNull { it.sysfs.temperatures[group] })?.let { result[group.key] = it }
        pick(samples.mapNotNull { it.battery.temperatureCelsius })?.let { result[BATTERY] = it }
        return result
    }

    /** A clock drop needs continuous evidence while the cluster actually has pinned work. */
    private fun throttles(load: List<Sample>, clusters: List<CpuCluster>, labels: List<String>, origin: Long): Map<String, Double?> {
        val findings = linkedMapOf<String, Double?>()
        val knownCpus = clusters.flatMap { it.cpus }.toSet()
        var complete = load.none { s -> s.cpu.workers.any { it.kernel != null && it.cpu !in knownCpus } }
        for ((i, c) in clusters.withIndex()) {
            if (load.none { s -> c.cpus.any { s.cpu.workers.getOrNull(it)?.kernel != null } }) continue
            val limit = c.maxFreqKhz * THROTTLE_FRACTION
            var since: Double? = null
            var previous = 0.0
            var firstThrottle: Double? = null
            for (s in load) {
                val khz = s.sysfs.clusterFreqKhz.getOrNull(i)
                val pinned = c.cpus.any { s.cpu.workers.getOrNull(it)?.let { w -> w.isPinned && w.isRunning } == true }
                val t = Power.seconds(s.timeNanos, origin)
                if (t - previous > 1.5) {
                    complete = false
                    since = null
                }
                previous = t
                if (c.maxFreqKhz <= 0 || khz == null || !pinned) {
                    complete = false
                    since = null
                } else if (khz < limit) {
                    val start = since ?: t.also { since = it }
                    if (t - start >= THROTTLE_HOLD_SECONDS) {
                        firstThrottle = start
                        break
                    }
                } else {
                    since = null
                }
            }
            findings[labels[i]] = firstThrottle
        }
        // An empty map means there is no basis for saying none of the loaded clusters throttled.
        return if (complete || findings.values.any { it != null }) findings else emptyMap()
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
        labels: List<String>,
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
            clocksMhz = labels.mapIndexedNotNull { i, name ->
                val clock = perSecond { samples -> Stats.mean(samples.mapNotNull { it.sysfs.clusterFreqKhz.getOrNull(i)?.div(1000.0) }) }
                name.takeIf { clock.any { it != null } }?.let { it to clock }
            }.toMap(),
            cpuRelative = relative { s -> s.cpu.workers.sumOf { it.work } },
            gpuRelative = if (gpuRunning && load.any { it.gpu.burner != null }) relative { it.gpu.work.toDouble() } else seconds.map { null },
            batteryPercent = perSecond { samples -> samples.lastOrNull()?.battery?.levelPercent },
            fps = if (scene) perSecondRate { it.gpu.frames.toDouble() } else emptyList(),
        )
    }
}

/** The completed idle phase fixes the current convention for both views of a run. */
internal class RunPowerBaseline(samples: List<Sample>) {
    val plugged = samples.any { it.battery.plugged }
    val lastCurrent = samples.lastOrNull()?.battery?.measurableCurrent
    private val onBattery = samples.all { it.battery.onBattery }
    private val candidate = if (onBattery) Power.currentConvention(samples) else null
    private val direction = Power.currentDirection(samples, candidate)
    private val showsCharging = Power.countersShowCharging(samples) ||
        direction == 0 || (candidate != null && direction != candidate.dischargeSign)

    val convention = candidate.takeUnless { showsCharging }
    val watts = convention?.let { Power.meanWatts(Power.windowWatts(samples, it, samples.first().timeNanos)) }

    fun canMeasure(load: List<Sample>): Boolean {
        if (!onBattery || showsCharging || load.any { !it.battery.onBattery } || Power.countersShowCharging(load)) {
            return false
        }
        // A false unplug broadcast can leave the idle phase charging while
        // the load actually drains the battery. Never reinterpret that flip.
        val loadDirection = Power.currentDirection(load, convention) ?: return true
        return loadDirection != 0 && (direction == null || loadDirection == direction)
    }
}
