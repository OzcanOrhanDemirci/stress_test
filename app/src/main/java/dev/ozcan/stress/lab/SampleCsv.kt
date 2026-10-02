package dev.ozcan.stress.lab

import dev.ozcan.stress.telemetry.Sample
import dev.ozcan.stress.telemetry.SysfsLayout
import dev.ozcan.stress.telemetry.ThermalGroup
import java.io.Writer
import java.util.Locale

/** Raw samples of a lab run, one row each, for analysis off the phone. */
object SampleCsv {

    fun write(out: Writer, layout: SysfsLayout, originNanos: Long, phases: List<Pair<String, List<Sample>>>) {
        val header = buildList {
            addAll(listOf("t_s", "phase", "current_raw", "charge_uah", "voltage_mv", "battery_c", "level", "plugged"))
            addAll(listOf("thermal_status", "headroom"))
            layout.clusters.forEach { add("freq_policy${it.policy}_khz") }
            ThermalGroup.entries.forEach { add("temp_${it.name}_c") }
            addAll(listOf("gpu_busy", "gpu_total"))
            repeat(layout.cpuCount) { add("batches_cpu$it") }
            repeat(layout.cpuCount) { add("misplaced_cpu$it") }
            add("errors")
            addAll(listOf("gpu_state", "gpu_frames", "gpu_dispatches", "gpu_work", "gpu_ns", "gpu_per_frame", "gpu_errors"))
        }
        out.appendLine(header.joinToString(","))
        for ((phase, samples) in phases) {
            for (s in samples) {
                val row = buildList {
                    add(String.format(Locale.ROOT, "%.3f", (s.timeNanos - originNanos) / 1e9))
                    add(phase)
                    add(s.battery.currentRaw.text())
                    add(s.battery.chargeCounterMicroAmpHours.text())
                    add(s.battery.voltageMillivolts.text())
                    add(s.battery.temperatureCelsius.text())
                    add(s.battery.levelPercent.text())
                    add(if (s.battery.plugged) "1" else "0")
                    add(s.thermalStatus.toString())
                    add(s.thermalHeadroom.text())
                    layout.clusters.indices.forEach { add(s.sysfs.clusterFreqKhz.getOrNull(it).text()) }
                    ThermalGroup.entries.forEach { add(s.sysfs.temperatures[it].text()) }
                    add(s.sysfs.gpuBusy?.busy.text())
                    add(s.sysfs.gpuBusy?.total.text())
                    s.cpu.workers.forEach { add(it.batches.toString()) }
                    s.cpu.workers.forEach { add(it.misplacedBatches.toString()) }
                    add(s.cpu.errors.toString())
                    add(s.gpu.state.name)
                    add(s.gpu.frames.toString())
                    add(s.gpu.dispatches.toString())
                    add(s.gpu.work.toString())
                    add(s.gpu.gpuNanos.toString())
                    add(s.gpu.dispatchesPerFrame.toString())
                    add(s.gpu.errors.toString())
                }
                out.appendLine(row.joinToString(","))
            }
        }
    }

    private fun Any?.text(): String = this?.toString() ?: ""
}
