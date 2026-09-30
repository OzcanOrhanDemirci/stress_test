package dev.ozcan.stress.engine

/**
 * Which kernel runs on which CPU; `null` leaves the CPU idle.
 *
 * Text form, used by lab commands: either one kernel key for every CPU
 * (`fp32_gemm`) or comma separated `cpus:key` groups where `cpus` is a single
 * CPU or an inclusive range (`0-3:dry,4-6:fp32_gemm,7:bf16_mmla`). CPUs not
 * named stay idle.
 */
data class CoreAssignment(val kernelPerCpu: List<CpuKernel?>) {

    init {
        require(kernelPerCpu.size == CPU_COUNT) { "Expected $CPU_COUNT CPUs, got ${kernelPerCpu.size}" }
    }

    val isIdle: Boolean get() = kernelPerCpu.all { it == null }

    fun toNative(): IntArray = IntArray(CPU_COUNT) { kernelPerCpu[it]?.index ?: -1 }

    /** Inverse of [parse]; groups neighbouring CPUs that run the same kernel. */
    fun describe(): String {
        if (kernelPerCpu.all { it != null && it == kernelPerCpu[0] }) return kernelPerCpu[0]!!.key
        val groups = mutableListOf<String>()
        var start = 0
        while (start < CPU_COUNT) {
            val kernel = kernelPerCpu[start]
            var end = start
            while (end + 1 < CPU_COUNT && kernelPerCpu[end + 1] == kernel) end++
            if (kernel != null) {
                val cpus = if (start == end) "$start" else "$start-$end"
                groups += "$cpus:${kernel.key}"
            }
            start = end + 1
        }
        return groups.joinToString(",")
    }

    companion object {
        /** The Honor 400 has eight CPUs: 0-3 Cortex-A510, 4-6 Cortex-A715, 7 Cortex-A715 prime. */
        const val CPU_COUNT = 8

        fun uniform(kernel: CpuKernel?): CoreAssignment = CoreAssignment(List(CPU_COUNT) { kernel })

        fun parse(text: String, kernels: List<CpuKernel>): CoreAssignment {
            val byKey = kernels.associateBy { it.key }
            fun kernel(key: String) = byKey[key.trim()] ?: throw IllegalArgumentException("Unknown kernel '$key'")

            val trimmed = text.trim()
            require(trimmed.isNotEmpty()) { "Empty assignment" }
            if (':' !in trimmed) return uniform(kernel(trimmed))

            val slots = arrayOfNulls<CpuKernel>(CPU_COUNT)
            for (group in trimmed.split(',')) {
                val (cpus, key) = group.split(':').let {
                    require(it.size == 2) { "Malformed group '$group'" }
                    it[0].trim() to it[1]
                }
                val range = parseRange(cpus)
                for (cpu in range) {
                    require(slots[cpu] == null) { "CPU $cpu assigned twice" }
                    slots[cpu] = kernel(key)
                }
            }
            return CoreAssignment(slots.toList())
        }

        private fun parseRange(text: String): IntRange {
            val bounds = text.split('-').map {
                it.trim().toIntOrNull() ?: throw IllegalArgumentException("Bad CPU '$text'")
            }
            val range = when (bounds.size) {
                1 -> bounds[0]..bounds[0]
                2 -> bounds[0]..bounds[1]
                else -> throw IllegalArgumentException("Bad CPU range '$text'")
            }
            require(!range.isEmpty() && range.first >= 0 && range.last < CPU_COUNT) { "CPU range '$text' out of 0-${CPU_COUNT - 1}" }
            return range
        }
    }
}
