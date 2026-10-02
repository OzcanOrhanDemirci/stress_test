package dev.ozcan.stress.engine

/**
 * Which kernel runs on which CPU; `null` leaves the CPU idle. There is one
 * entry for every CPU of the device ([cpuCount]).
 *
 * Text form, used by lab commands: either one kernel key for every CPU
 * (`fp32_gemm`) or comma separated `cpus:key` groups where `cpus` is a single
 * CPU or an inclusive range (`0-3:dry,4-6:fp32_gemm,7:bf16_mmla`). CPUs not
 * named stay idle.
 */
data class CoreAssignment(val kernelPerCpu: List<CpuKernel?>) {

    init {
        require(kernelPerCpu.isNotEmpty()) { "An assignment needs at least one CPU" }
    }

    val cpuCount: Int get() = kernelPerCpu.size

    val isIdle: Boolean get() = kernelPerCpu.all { it == null }

    fun toNative(): IntArray = IntArray(cpuCount) { kernelPerCpu[it]?.index ?: -1 }

    /** Inverse of [parse]; groups neighbouring CPUs that run the same kernel. */
    fun describe(): String {
        if (kernelPerCpu.all { it != null && it == kernelPerCpu[0] }) return kernelPerCpu[0]!!.key
        val groups = mutableListOf<String>()
        var start = 0
        while (start < cpuCount) {
            val kernel = kernelPerCpu[start]
            var end = start
            while (end + 1 < cpuCount && kernelPerCpu[end + 1] == kernel) end++
            if (kernel != null) {
                val cpus = if (start == end) "$start" else "$start-$end"
                groups += "$cpus:${kernel.key}"
            }
            start = end + 1
        }
        return groups.joinToString(",")
    }

    companion object {
        fun uniform(kernel: CpuKernel?, cpuCount: Int): CoreAssignment = CoreAssignment(List(cpuCount) { kernel })

        fun parse(text: String, kernels: List<CpuKernel>, cpuCount: Int): CoreAssignment {
            require(cpuCount > 0) { "No CPUs" }
            val byKey = kernels.associateBy { it.key }
            fun kernel(key: String) = byKey[key.trim()] ?: throw IllegalArgumentException("Unknown kernel '$key'")

            val trimmed = text.trim()
            require(trimmed.isNotEmpty()) { "Empty assignment" }
            if (':' !in trimmed) return uniform(kernel(trimmed), cpuCount)

            val slots = arrayOfNulls<CpuKernel>(cpuCount)
            for (group in trimmed.split(',')) {
                val (cpus, key) = group.split(':').let {
                    require(it.size == 2) { "Malformed group '$group'" }
                    it[0].trim() to it[1]
                }
                val range = parseRange(cpus, cpuCount)
                for (cpu in range) {
                    require(slots[cpu] == null) { "CPU $cpu assigned twice" }
                    slots[cpu] = kernel(key)
                }
            }
            return CoreAssignment(slots.toList())
        }

        private fun parseRange(text: String, cpuCount: Int): IntRange {
            val bounds = text.split('-').map {
                it.trim().toIntOrNull() ?: throw IllegalArgumentException("Bad CPU '$text'")
            }
            val range = when (bounds.size) {
                1 -> bounds[0]..bounds[0]
                2 -> bounds[0]..bounds[1]
                else -> throw IllegalArgumentException("Bad CPU range '$text'")
            }
            require(!range.isEmpty() && range.first >= 0 && range.last < cpuCount) { "CPU range '$text' out of 0-${cpuCount - 1}" }
            return range
        }
    }
}
