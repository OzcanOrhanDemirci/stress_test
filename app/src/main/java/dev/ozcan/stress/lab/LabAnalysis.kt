package dev.ozcan.stress.lab

import dev.ozcan.stress.analysis.CurrentConvention
import dev.ozcan.stress.analysis.Point
import dev.ozcan.stress.analysis.Power
import dev.ozcan.stress.analysis.Stats
import dev.ozcan.stress.analysis.WorkRate
import dev.ozcan.stress.engine.CoreAssignment
import dev.ozcan.stress.engine.StartResult
import dev.ozcan.stress.telemetry.CpuCluster
import dev.ozcan.stress.telemetry.Sample
import dev.ozcan.stress.telemetry.ThermalGroup

/** Where a run sits in its session and how the phone was when it began. */
data class RunContext(
    val assignment: CoreAssignment,
    val index: Int,
    val count: Int,
    val startTemperatures: Map<ThermalGroup, Double>,
    val cooledInTime: Boolean,
)

/** Turns the samples of one lab run into a [LabResult]. Pure, so it can be tested with made-up samples. */
object LabAnalysis {

    fun analyze(
        spec: LabSpec,
        run: RunContext,
        startResult: StartResult,
        idle: List<Sample>,
        load: List<Sample>,
        clusters: List<CpuCluster>,
    ): LabResult {
        val all = idle + load
        val convention = CurrentConvention.infer(idle.unplugged().mapNotNull { it.battery.currentRaw })
            ?: CurrentConvention.infer(load.unplugged().mapNotNull { it.battery.currentRaw })

        val idleStats = phase(idle, convention)
        val loadStats = phase(load, convention)
        val loadOrigin = load.firstOrNull()?.timeNanos ?: 0L
        val loadWatts = convention?.let { Power.watts(load, it, loadOrigin) }.orEmpty()
        val loadSpan = loadWatts.lastOrNull()?.seconds ?: 0.0

        return LabResult(
            assignment = run.assignment.describe(),
            runIndex = run.index,
            runCount = run.count,
            startTemperatures = run.startTemperatures.mapKeys { it.key.label },
            cooledInTime = run.cooledInTime,
            nice = spec.nice,
            batchMillis = spec.batchMillis,
            idleSeconds = spec.idleSeconds,
            loadSeconds = spec.loadSeconds,
            startResult = startResult.name,
            ampsPerUnit = convention?.ampsPerUnit,
            dischargeSign = convention?.dischargeSign,
            pluggedDuringRun = all.any { it.battery.plugged },
            batteryStartPercent = all.firstOrNull()?.battery?.levelPercent,
            batteryEndPercent = all.lastOrNull()?.battery?.levelPercent,
            idle = idleStats,
            load = loadStats,
            loadAboveIdleWatts = difference(loadStats.meanWatts, idleStats.meanWatts),
            loadFirst30sWatts = Stats.meanOf(Stats.window(loadWatts, 0.0, 30.0)),
            loadLast30sWatts = Stats.meanOf(Stats.window(loadWatts, loadSpan - 30.0, Double.MAX_VALUE)),
            loadMax5sWatts = if (loadWatts.isEmpty()) null else Stats.maxWindowMean(loadWatts, 5.0),
            cpus = cpuResults(load),
            clusters = clusterResults(load, clusters),
            maxTemperatures = load.flatMap { it.sysfs.temperatures.entries }
                .groupBy({ it.key.label }, { it.value })
                .mapValues { (_, values) -> values.max() },
            cadence = cadence(all),
            computationErrors = load.lastOrNull()?.cpu?.errors ?: 0L,
        )
    }

    private fun List<Sample>.unplugged() = filter { !it.battery.plugged }

    private fun difference(a: Double?, b: Double?): Double? = if (a != null && b != null) a - b else null

    private fun phase(samples: List<Sample>, convention: CurrentConvention?): PhaseStats {
        val origin = samples.firstOrNull()?.timeNanos ?: 0L
        val unplugged = samples.unplugged()
        return PhaseStats(
            samples = samples.size,
            meanWatts = convention?.let { Stats.meanOf(Power.watts(samples, it, origin)) },
            meanDischargeAmps = convention?.let { c ->
                Stats.mean(unplugged.mapNotNull { s -> s.battery.currentRaw?.let(c::dischargeAmps) })
            },
            meanVolts = Stats.mean(unplugged.mapNotNull { s -> s.battery.voltageMillivolts?.let { it / 1000.0 } }),
            chargeCounterAmps = if (unplugged.size == samples.size) Power.chargeCounterAmps(samples, origin) else null,
            batteryCelsiusStart = samples.firstOrNull()?.battery?.temperatureCelsius,
            batteryCelsiusEnd = samples.lastOrNull()?.battery?.temperatureCelsius,
        )
    }

    private fun cpuResults(load: List<Sample>): List<CpuResult> {
        val first = load.firstOrNull() ?: return emptyList()
        val last = load.last()
        fun rateBetween(a: Sample, b: Sample): List<Double?>? {
            val seconds = (b.timeNanos - a.timeNanos) / 1e9
            return if (seconds > 0) WorkRate.perCpu(a.cpu, b.cpu, seconds) else null
        }
        fun at(offsetSeconds: Double, fromEnd: Boolean): Sample {
            val target = if (fromEnd) last.timeNanos - (offsetSeconds * 1e9).toLong()
            else first.timeNanos + (offsetSeconds * 1e9).toLong()
            return load.minBy { kotlin.math.abs(it.timeNanos - target) }
        }
        val mean = rateBetween(first, last)
        val early = rateBetween(first, at(10.0, fromEnd = false))
        val late = rateBetween(at(10.0, fromEnd = true), last)
        return last.cpu.workers.map { w ->
            CpuResult(
                cpu = w.cpu,
                kernel = w.kernel?.code,
                unit = w.kernel?.unit?.rateSymbol,
                meanRate = mean?.get(w.cpu),
                first10sRate = early?.get(w.cpu),
                last10sRate = late?.get(w.cpu),
                errors = w.errors,
                pinned = w.isPinned,
                ranOnCpu = w.lastCpu,
                batches = w.batches,
                misplacedBatches = w.misplacedBatches,
            )
        }
    }

    private fun clusterResults(load: List<Sample>, clusters: List<CpuCluster>): List<ClusterResult> {
        val origin = load.firstOrNull()?.timeNanos ?: 0L
        val end = load.lastOrNull()?.let { Power.seconds(it.timeNanos, origin) } ?: 0.0
        return clusters.mapIndexed { i, cluster ->
            val mhz = load.mapNotNull { s ->
                s.sysfs.clusterFreqKhz.getOrNull(i)?.let { Point(Power.seconds(s.timeNanos, origin), it / 1000.0) }
            }
            ClusterResult(
                policy = cluster.policy,
                cpus = cluster.cpus,
                maxMhz = cluster.maxFreqKhz / 1000.0,
                meanMhz = Stats.meanOf(mhz),
                last30sMeanMhz = Stats.meanOf(Stats.window(mhz, end - 30.0, Double.MAX_VALUE)),
            )
        }
    }

    private fun cadence(samples: List<Sample>): Cadence {
        val origin = samples.firstOrNull()?.timeNanos ?: 0L
        fun series(value: (Sample) -> Long?): List<Point> =
            samples.mapNotNull { s -> value(s)?.let { Point(Power.seconds(s.timeNanos, origin), it.toDouble()) } }
        val intervals = samples.zipWithNext { a, b -> (b.timeNanos - a.timeNanos) / 1e9 }.sorted()
        return Cadence(
            sampleIntervalSeconds = intervals.getOrNull(intervals.size / 2),
            currentChangeSeconds = Stats.medianChangeInterval(series { it.battery.currentRaw }),
            chargeCounterChangeSeconds = Stats.medianChangeInterval(series { it.battery.chargeCounterMicroAmpHours }),
            voltageChangeSeconds = Stats.medianChangeInterval(series { it.battery.voltageMillivolts?.toLong() }),
            batteryBroadcasts = (samples.lastOrNull()?.battery?.broadcasts ?: 0L) -
                (samples.firstOrNull()?.battery?.broadcasts ?: 0L),
        )
    }
}
