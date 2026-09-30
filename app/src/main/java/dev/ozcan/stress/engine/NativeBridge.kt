package dev.ozcan.stress.engine

/**
 * JNI surface of `libstress.so`, one function per entry point in `jni_bridge.cpp`.
 * Everything is cheap except [cpuStart], which calibrates each kernel it is
 * given before the load begins.
 */
internal object NativeBridge {

    init {
        System.loadLibrary("stress")
    }

    /** One line per kernel: `key|code|unit|opsPerIteration|bufferBytes`. */
    @JvmStatic external fun kernelTable(): Array<String>

    /** Returns a native `StartResult` code; `0` means the load is running. */
    @JvmStatic external fun cpuStart(kernelPerCpu: IntArray, nice: Int, batchMillis: Int): Int

    @JvmStatic external fun cpuStop()

    @JvmStatic external fun cpuSnapshotStride(): Int

    @JvmStatic external fun cpuSnapshot(out: LongArray)

    /** `{digest low, digest high, pinned}` of one run on a thread pinned to [cpu] (-1: unpinned), or null. */
    @JvmStatic external fun kernelDigest(kernel: Int, iterations: Long, cpu: Int): LongArray?

    @JvmStatic external fun sensorsOpen(paths: Array<String>): BooleanArray

    /** Two values per opened path: the first two integers in the file, [Long.MIN_VALUE] when missing. */
    @JvmStatic external fun sensorsRead(out: LongArray)

    @JvmStatic external fun sensorsClose()
}
