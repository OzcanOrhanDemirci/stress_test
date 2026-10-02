package dev.ozcan.stress.engine

/** Outcome of [CpuEngine.start]; codes mirror `CpuLoad::StartResult`. */
enum class StartResult(val code: Int) {
    Started(0),
    AlreadyRunning(1),
    InvalidKernel(2),
    NoMemory(3),
    ThreadFailed(4),
    SetupTimeout(5);

    companion object {
        fun fromCode(code: Int): StartResult =
            entries.firstOrNull { it.code == code } ?: error("Unknown start result $code")
    }
}

/** State of the burner thread of one CPU, read from the native snapshot. */
data class WorkerState(
    val cpu: Int,
    val kernel: CpuKernel?,
    val iterationsPerBatch: Long,
    val batches: Long,
    val errors: Long,
    val lastCpu: Int,
    val flags: Int,
    /** Batches that ended on another CPU: time the scheduler took this CPU away (core_ctl pause). */
    val misplacedBatches: Long = 0,
) {
    val isPinned: Boolean get() = flags and FLAG_PINNED != 0
    val isRunning: Boolean get() = flags and FLAG_RUNNING != 0
    val hasFailed: Boolean get() = flags and FLAG_FAILED != 0

    /** Work completed so far, in the kernel's [WorkUnit]. */
    val work: Double get() = kernel?.let { batches.toDouble() * iterationsPerBatch * it.opsPerIteration } ?: 0.0

    companion object {
        const val FLAG_PINNED = 1 shl 0
        const val FLAG_NICE_SET = 1 shl 1
        const val FLAG_RUNNING = 1 shl 2
        const val FLAG_FAILED = 1 shl 3
    }
}

data class CpuSnapshot(val workers: List<WorkerState>) {
    val errors: Long get() = workers.sumOf { it.errors }
    val misplacedBatches: Long get() = workers.sumOf { it.misplacedBatches }
    val isIdle: Boolean get() = workers.all { it.kernel == null }

    companion object {
        fun idle(cpuCount: Int) = CpuSnapshot(List(cpuCount) { WorkerState(it, null, 0, 0, 0, -1, 0) })
    }
}

/**
 * Kotlin face of the native CPU burner, for a device with [requestedCpus]
 * CPUs (capped at the native side's slots). One load runs at a time.
 */
class CpuEngine(requestedCpus: Int) {

    val kernels: List<CpuKernel> = CpuKernel.parseTable(NativeBridge.kernelTable())

    /** CPUs the load can use: one burner thread each. */
    val cpuCount: Int = requestedCpus.coerceIn(1, NativeBridge.cpuMaxCount())

    private val stride = NativeBridge.cpuSnapshotStride().also {
        check(it == SNAPSHOT_STRIDE) { "Native snapshot stride $it, expected $SNAPSHOT_STRIDE" }
    }
    private val buffer = LongArray(cpuCount * stride)

    fun kernel(key: String): CpuKernel = kernels.first { it.key == key }

    /** Calibrates the kernels (a few tens of milliseconds each), then starts every worker at once. */
    fun start(assignment: CoreAssignment, nice: Int = 0, batchMillis: Int = DEFAULT_BATCH_MILLIS): StartResult {
        if (assignment.isIdle || assignment.kernelPerCpu.any { it != null && !it.supported }) return StartResult.InvalidKernel
        return StartResult.fromCode(NativeBridge.cpuStart(assignment.toNative(), nice, batchMillis))
    }

    fun stop() = NativeBridge.cpuStop()

    @Synchronized
    fun snapshot(): CpuSnapshot {
        NativeBridge.cpuSnapshot(buffer)
        return CpuSnapshot(
            List(cpuCount) { cpu ->
                val at = cpu * stride
                val kernelIndex = buffer[at].toInt()
                WorkerState(
                    cpu = cpu,
                    kernel = kernels.getOrNull(kernelIndex),
                    iterationsPerBatch = buffer[at + 1],
                    batches = buffer[at + 2],
                    errors = buffer[at + 3],
                    lastCpu = buffer[at + 4].toInt(),
                    flags = buffer[at + 5].toInt(),
                    misplacedBatches = buffer[at + 6],
                )
            },
        )
    }

    companion object {
        /** Must match `CpuLoad::kSnapshotStride`. */
        const val SNAPSHOT_STRIDE = 7

        /**
         * Short batches keep the work counters smooth at the 10 Hz sample rate
         * and bound how long [stop] waits; the per-batch bookkeeping is a few
         * hundred nanoseconds.
         */
        const val DEFAULT_BATCH_MILLIS = 20
    }
}
