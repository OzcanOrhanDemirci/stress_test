package dev.ozcan.stress.analysis

import dev.ozcan.stress.engine.CpuSnapshot

object WorkRate {

    /**
     * Work per second done by each CPU between two snapshots, in its kernel's
     * unit. Null for a CPU that was idle, changed kernel, or restarted (its
     * batch counter went backwards).
     */
    fun perCpu(from: CpuSnapshot, to: CpuSnapshot, seconds: Double): List<Double?> {
        require(seconds > 0) { "Window must be positive, was $seconds" }
        return from.workers.zip(to.workers) { a, b ->
            when {
                a.kernel == null || b.kernel != a.kernel -> null
                b.iterationsPerBatch != a.iterationsPerBatch -> null
                b.batches < a.batches -> null
                else -> (b.work - a.work) / seconds
            }
        }
    }
}
