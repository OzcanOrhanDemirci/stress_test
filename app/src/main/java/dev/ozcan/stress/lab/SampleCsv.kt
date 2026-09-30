package dev.ozcan.stress.lab

import dev.ozcan.stress.engine.CoreAssignment
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
            repeat(CoreAssignment.CPU_COUNT) { add("batches_cpu$it") }
            repeat(CoreAssignment.CPU_COUNT) { add("misplaced_cpu$it") }
            add("errors")
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
                }
                out.appendLine(row.joinToString(","))
            }
        }
    }

    private fun Any?.text(): String = this?.toString() ?: ""
}
