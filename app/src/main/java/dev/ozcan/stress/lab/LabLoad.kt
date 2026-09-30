package dev.ozcan.stress.lab

import dev.ozcan.stress.engine.CoreAssignment
import dev.ozcan.stress.engine.CpuKernel
import dev.ozcan.stress.engine.GpuBurner

/** The GPU side of a workload: a burner under the visible pass, or ([burner] null) the visible pass alone. */
data class GpuPart(val burner: GpuBurner?) {
    val key: String get() = burner?.key ?: SCENE_ONLY

    companion object {
        /** The text of a GPU part that runs the visible pass without a burner. */
        const val SCENE_ONLY = "scene"
    }
}

/**
 * One lab workload: a CPU assignment, a GPU part, or both at once.
 *
 * Text form: parts joined by '+'. A part naming a GPU burner key, or `scene`,
 * is the GPU part; any other part is a [CoreAssignment]. `fp32_gemm`,
 * `gpu_fp32`, `scene` and `0-3:dry,4-7:bf16_mmla+gpu_texture` are all workloads.
 */
data class LabLoad(val cpu: CoreAssignment?, val gpu: GpuPart?) {

    init {
        require(cpu != null || gpu != null) { "A workload needs a CPU or a GPU part" }
    }

    /** Inverse of [parse]. */
    fun describe(): String = listOfNotNull(cpu?.describe(), gpu?.key).joinToString("+")

    companion object {
        fun parse(text: String, kernels: List<CpuKernel>, burners: List<GpuBurner>): LabLoad {
            val byKey = burners.associateBy { it.key }
            var cpu: CoreAssignment? = null
            var gpu: GpuPart? = null
            for (part in text.split('+').map { it.trim() }) {
                require(part.isNotEmpty()) { "Empty part in '$text'" }
                val gpuPart = when {
                    part == GpuPart.SCENE_ONLY -> GpuPart(null)
                    byKey.containsKey(part) -> GpuPart(byKey.getValue(part))
                    else -> null
                }
                if (gpuPart != null) {
                    require(gpu == null) { "Two GPU parts in '$text'" }
                    gpu = gpuPart
                } else {
                    require(cpu == null) { "Two CPU parts in '$text'" }
                    cpu = CoreAssignment.parse(part, kernels)
                }
            }
            return LabLoad(cpu, gpu)
        }
    }
}
