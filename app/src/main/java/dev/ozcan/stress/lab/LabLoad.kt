package dev.ozcan.stress.lab

import dev.ozcan.stress.engine.CoreAssignment
import dev.ozcan.stress.engine.CpuKernel
import dev.ozcan.stress.engine.GpuBurner

/**
 * One lab workload: a CPU assignment, a GPU burner, or both at once.
 *
 * Text form: parts joined by '+'. A part naming a GPU burner key is the GPU
 * part; any other part is a [CoreAssignment]. `fp32_gemm`, `gpu_fp32` and
 * `0-3:dry,4-7:bf16_mmla+gpu_texture` are all workloads.
 */
data class LabLoad(val cpu: CoreAssignment?, val gpu: GpuBurner?) {

    init {
        require(cpu != null || gpu != null) { "A workload needs a CPU or a GPU part" }
    }

    /** Inverse of [parse]. */
    fun describe(): String = listOfNotNull(cpu?.describe(), gpu?.key).joinToString("+")

    companion object {
        fun parse(text: String, kernels: List<CpuKernel>, burners: List<GpuBurner>): LabLoad {
            val byKey = burners.associateBy { it.key }
            var cpu: CoreAssignment? = null
            var gpu: GpuBurner? = null
            for (part in text.split('+').map { it.trim() }) {
                require(part.isNotEmpty()) { "Empty part in '$text'" }
                val burner = byKey[part]
                if (burner != null) {
                    require(gpu == null) { "Two GPU parts in '$text'" }
                    gpu = burner
                } else {
                    require(cpu == null) { "Two CPU parts in '$text'" }
                    cpu = CoreAssignment.parse(part, kernels)
                }
            }
            return LabLoad(cpu, gpu)
        }
    }
}
