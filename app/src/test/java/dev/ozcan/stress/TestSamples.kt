package dev.ozcan.stress

import dev.ozcan.stress.engine.CoreAssignment
import dev.ozcan.stress.engine.CpuKernel
import dev.ozcan.stress.engine.CpuSnapshot
import dev.ozcan.stress.engine.WorkUnit
import dev.ozcan.stress.engine.WorkerState
import dev.ozcan.stress.telemetry.BatteryReading
import dev.ozcan.stress.telemetry.CpuCluster
import dev.ozcan.stress.telemetry.GpuBusy
import dev.ozcan.stress.telemetry.Sample
import dev.ozcan.stress.telemetry.SysfsReading
import dev.ozcan.stress.telemetry.ThermalGroup

/** Builders for made-up telemetry. */
object TestSamples {

    val gemm = CpuKernel(index = 2, key = "fp32_gemm", code = "C2", unit = WorkUnit.Flop, opsPerIteration = 1000.0, bufferBytes = 16000)
    val dry = CpuKernel(index = 0, key = "dry", code = "K0", unit = WorkUnit.Op, opsPerIteration = 4.0, bufferBytes = 64)
    val kernels = listOf(dry, gemm)

    val clusters = listOf(
        CpuCluster(policy = 0, cpus = listOf(0, 1, 2, 3), maxFreqKhz = 1_804_800, currentFreqPath = "p0"),
        CpuCluster(policy = 4, cpus = listOf(4, 5, 6), maxFreqKhz = 2_400_000, currentFreqPath = "p4"),
        CpuCluster(policy = 7, cpus = listOf(7), maxFreqKhz = 2_630_400, currentFreqPath = "p7"),
    )

    fun snapshot(kernel: CpuKernel?, batches: Long, iterations: Long = 10, errors: Long = 0): CpuSnapshot =
        CpuSnapshot(
            List(CoreAssignment.CPU_COUNT) { cpu ->
                WorkerState(
                    cpu = cpu,
                    kernel = kernel,
                    iterationsPerBatch = if (kernel == null) 0 else iterations,
                    batches = if (kernel == null) 0 else batches,
                    errors = errors,
                    lastCpu = if (kernel == null) -1 else cpu,
                    flags = if (kernel == null) 0 else WorkerState.FLAG_PINNED or WorkerState.FLAG_RUNNING,
                )
            },
        )

    fun sample(
        seconds: Double,
        currentRaw: Long? = -1_000_000,
        chargeCounter: Long? = 4_000_000,
        millivolts: Int? = 4000,
        plugged: Boolean = false,
        cpu: CpuSnapshot = CpuSnapshot.IDLE,
        freqKhz: List<Long?> = listOf(1_804_800, 2_400_000, 2_630_400),
        temperatures: Map<ThermalGroup, Double> = mapOf(ThermalGroup.BigCores to 40.0),
        level: Int = 80,
        batteryCelsius: Double = 30.0,
        broadcasts: Long = 0,
    ) = Sample(
        timeNanos = (seconds * 1e9).toLong(),
        battery = BatteryReading(
            currentRaw = currentRaw,
            chargeCounterMicroAmpHours = chargeCounter,
            voltageMillivolts = millivolts,
            voltageAgeNanos = 0,
            temperatureCelsius = batteryCelsius,
            levelPercent = level,
            plugged = plugged,
            broadcasts = broadcasts,
        ),
        sysfs = SysfsReading(freqKhz, temperatures, GpuBusy(0, 1_000_000)),
        thermalStatus = 0,
        thermalHeadroom = 0.5f,
        cpu = cpu,
    )
}
