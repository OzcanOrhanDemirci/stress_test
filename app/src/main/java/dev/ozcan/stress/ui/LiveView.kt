package dev.ozcan.stress.ui

import dev.ozcan.stress.analysis.CurrentConvention
import dev.ozcan.stress.analysis.Power
import dev.ozcan.stress.analysis.Stats
import dev.ozcan.stress.analysis.WorkRate
import dev.ozcan.stress.engine.CpuKernel
import dev.ozcan.stress.engine.WorkUnit
import dev.ozcan.stress.telemetry.CpuCluster
import dev.ozcan.stress.telemetry.Sample
import dev.ozcan.stress.telemetry.SysfsLayout
import dev.ozcan.stress.telemetry.ThermalGroup

/** Names of the Honor 400's three clusters, keyed by cpufreq policy. */
object CoreNames {
    fun of(cluster: CpuCluster): String = when (cluster.policy) {
        0 -> "A510 ×${cluster.cpus.size}"
        4 -> "A715 ×${cluster.cpus.size}"
        7 -> "A715 prime"
        else -> "policy${cluster.policy}"
    }
}

data class ClusterLive(
    val name: String,
    val khz: Long?,
    val maxKhz: Long,
    /** Work per second of the cluster's burner threads, in [LiveView.unit]. */
    val rate: Double?,
)

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
    val gpuBusy: Double?,
    val thermalStatus: Int,
    val headroom: Float?,
    val runningKernel: CpuKernel?,
    val unit: WorkUnit?,
    val errors: Long,
) {
    companion object {
        /** [recent] must be in time order; returns null when it is empty. */
        fun from(recent: List<Sample>, layout: SysfsLayout): LiveView? {
            val last = recent.lastOrNull() ?: return null
            val first = recent.first()
            val unplugged = recent.filter { !it.battery.plugged }
            val convention = CurrentConvention.infer(unplugged.mapNotNull { it.battery.currentRaw })
            val seconds = (last.timeNanos - first.timeNanos) / 1e9
            val rates = if (seconds > 0) WorkRate.perCpu(first.cpu, last.cpu, seconds) else null
            val running = last.cpu.workers.firstNotNullOfOrNull { it.kernel }

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
                        name = CoreNames.of(cluster),
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
            )
        }
    }
}
