package dev.ozcan.stress.ui

import dev.ozcan.stress.analysis.CurrentConvention
import dev.ozcan.stress.analysis.Power
import dev.ozcan.stress.analysis.Stats
import dev.ozcan.stress.analysis.WorkRate
import dev.ozcan.stress.engine.CpuKernel
import dev.ozcan.stress.engine.GpuBurner
import dev.ozcan.stress.engine.GpuSnapshot
import dev.ozcan.stress.engine.GpuState
import dev.ozcan.stress.engine.WorkUnit
import dev.ozcan.stress.telemetry.ClusterRole
import dev.ozcan.stress.telemetry.Sample
import dev.ozcan.stress.telemetry.SysfsLayout
import dev.ozcan.stress.telemetry.ThermalGroup
import dev.ozcan.stress.telemetry.label

data class ClusterLive(
    val role: ClusterRole,
    /** "Cortex-A715 ×3", or the CPUs when the core is unknown. */
    val label: String,
    val cpus: List<Int>,
    val khz: Long?,
    val maxKhz: Long,
    /** Work per second of the cluster's burner threads, in [LiveView.unit]. */
    val rate: Double?,
) {
    /** Clock as a share of the top clock, 0..1. */
    val load: Float? get() = khz?.let { (it.toFloat() / maxKhz.coerceAtLeast(1)).coerceIn(0f, 1f) }
}

/** The GPU over the last second: what runs, how fast, and whether it computes right. */
data class GpuLive(
    val state: GpuState,
    val burner: GpuBurner?,
    val framesPerSecond: Double?,
    val rate: Double?,
    val frameMillis: Double?,
    val dispatchesPerFrame: Long,
    val errors: Long,
    val checks: Long,
    /**
     * Share of the last second the GPU spent on this app's frames, from its
     * own frame times: what the screens show when the driver's load file is
     * closed to apps.
     */
    val busyFromFrames: Double?,
) {
    companion object {
        fun from(first: GpuSnapshot, last: GpuSnapshot, seconds: Double): GpuLive {
            val continuous = last.frames >= first.frames && seconds > 0
            return GpuLive(
                state = last.state,
                burner = last.burner,
                framesPerSecond = if (continuous) (last.frames - first.frames) / seconds else null,
                rate = if (continuous && last.work >= first.work) (last.work - first.work) / seconds else null,
                frameMillis = last.lastFrameNanos.takeIf { it > 0 }?.let { it / 1e6 },
                dispatchesPerFrame = last.dispatchesPerFrame,
                errors = last.errors,
                checks = last.checks,
                busyFromFrames = if (continuous && last.isRunning && last.gpuNanos >= first.gpuNanos) {
                    ((last.gpuNanos - first.gpuNanos) / 1e9 / seconds).coerceIn(0.0, 1.0)
                } else {
                    null
                },
            )
        }
    }
}

/** What the live panels show: the last second of samples, averaged. */
data class LiveView(
    val watts: Double?,
    val dischargeAmps: Double?,
    /** CURRENT_NOW exactly as the gauge reports it, before unit and sign are known. */
    val currentRaw: Long?,
    val volts: Double?,
    val plugged: Boolean,
    val batteryCelsius: Double?,
    val levelPercent: Int?,
    val clusters: List<ClusterLive>,
    val temperatures: Map<ThermalGroup, Double>,
    /** The driver's own GPU load, where its file is readable. */
    val gpuBusy: Double?,
    val thermalStatus: Int,
    val headroom: Float?,
    val runningKernel: CpuKernel?,
    val unit: WorkUnit?,
    val errors: Long,
    val gpu: GpuLive,
) {
    /** The GPU's load: the driver's figure where it gives one, else this app's frame times. */
    val gpuLoad: Double? get() = gpuBusy ?: gpu.busyFromFrames

    /** CPU work per second over every cluster, in [unit]. */
    val cpuRate: Double? get() = clusters.mapNotNull { it.rate }.takeIf { it.isNotEmpty() }?.sum()

    /** The hottest of the CPU and GPU. */
    val chipCelsius: Double? get() = listOfNotNull(temperatures[ThermalGroup.Cpu], temperatures[ThermalGroup.Gpu]).maxOrNull()

    companion object {
        /** About one second at the sampler's 10 Hz. */
        const val WINDOW_SAMPLES = 11

        /** [recent] must be in time order; returns null when it is empty. */
        fun from(recent: List<Sample>, layout: SysfsLayout): LiveView? {
            val last = recent.lastOrNull() ?: return null
            val first = recent.first()
            val unplugged = recent.filter { !it.battery.plugged }
            val convention = CurrentConvention.infer(unplugged.mapNotNull { it.battery.currentRaw })
            val seconds = (last.timeNanos - first.timeNanos) / 1e9
            val rates = if (seconds > 0) WorkRate.perCpu(first.cpu, last.cpu, seconds) else null
            val running = last.cpu.workers.firstNotNullOfOrNull { it.kernel }
            val roles = ClusterRole.of(layout.clusters)

            return LiveView(
                watts = convention?.let { Stats.meanOf(Power.watts(recent, it, first.timeNanos)) },
                dischargeAmps = convention?.let { c ->
                    Stats.mean(unplugged.mapNotNull { s -> s.battery.currentRaw?.let(c::dischargeAmps) })
                },
                currentRaw = last.battery.currentRaw,
                volts = last.battery.voltageMillivolts?.let { it / 1000.0 },
                plugged = last.battery.plugged,
                batteryCelsius = last.battery.temperatureCelsius,
                levelPercent = last.battery.levelPercent,
                clusters = layout.clusters.mapIndexed { i, cluster ->
                    ClusterLive(
                        role = roles[i],
                        label = cluster.label(),
                        cpus = cluster.cpus,
                        khz = last.sysfs.clusterFreqKhz.getOrNull(i),
                        maxKhz = cluster.maxFreqKhz,
                        rate = rates?.let { r ->
                            cluster.cpus.mapNotNull { r.getOrNull(it) }.takeIf { it.isNotEmpty() }?.sum()
                        },
                    )
                },
                temperatures = last.sysfs.temperatures,
                gpuBusy = last.sysfs.gpuBusy?.fraction,
                thermalStatus = last.thermalStatus,
                headroom = last.thermalHeadroom,
                runningKernel = running,
                unit = running?.unit,
                errors = last.cpu.errors,
                gpu = GpuLive.from(first.gpu, last.gpu, seconds),
            )
        }
    }
}
