package dev.ozcan.stress.engine

/**
 * The GPU side of a workload: a burner under the visible pass, or ([burner]
 * null) the visible pass alone. [scene] and [sceneScalePercent] override the
 * run's settings when set.
 *
 * Text: a burner key or `scene`, optionally followed by `@preview` (the cheap
 * preview ring instead of the scene) or `@NN` (the scene at NN% of the
 * screen's resolution): `gpu_fp32@preview`, `scene@35`.
 */
data class GpuPart(val burner: GpuBurner?, val scene: Boolean? = null, val sceneScalePercent: Int? = null) {

    val key: String
        get() {
            val name = burner?.key ?: SCENE_ONLY
            return when {
                scene == false -> "$name@$PREVIEW"
                sceneScalePercent != null -> "$name@$sceneScalePercent"
                else -> name
            }
        }

    companion object {
        /** The text of a GPU part that runs the visible pass without a burner. */
        const val SCENE_ONLY = "scene"
        private const val PREVIEW = "preview"

        /** Null when [text] names no burner and is not `scene`: then it is a CPU part. */
        fun parse(text: String, burners: List<GpuBurner>): GpuPart? {
            val name = text.substringBefore('@')
            val burner = burners.firstOrNull { it.key == name }
            if (burner == null && name != SCENE_ONLY) return null
            if ('@' !in text) return GpuPart(burner)
            val option = text.substringAfter('@')
            if (option == PREVIEW) return GpuPart(burner, scene = false)
            val scale = option.toIntOrNull()
            require(scale != null && scale in 10..100) { "Bad GPU option '$option' in '$text'" }
            return GpuPart(burner, scene = true, sceneScalePercent = scale)
        }
    }
}

/**
 * One workload: a CPU assignment, a GPU part, or both at once.
 *
 * Text form: parts joined by '+'. A part naming a GPU burner key, or `scene`,
 * is the GPU part; any other part is a [CoreAssignment]. `fp32_gemm`,
 * `gpu_fp32`, `scene` and `0-3:dry,4-7:bf16_mmla+gpu_texture` are all workloads.
 */
data class Workload(val cpu: CoreAssignment?, val gpu: GpuPart?) {

    init {
        require(cpu != null || gpu != null) { "A workload needs a CPU or a GPU part" }
    }

    /** Inverse of [parse]. */
    fun describe(): String = listOfNotNull(cpu?.describe(), gpu?.key).joinToString("+")

    companion object {
        fun parse(text: String, kernels: List<CpuKernel>, burners: List<GpuBurner>): Workload {
            var cpu: CoreAssignment? = null
            var gpu: GpuPart? = null
            for (part in text.split('+').map { it.trim() }) {
                require(part.isNotEmpty()) { "Empty part in '$text'" }
                val gpuPart = GpuPart.parse(part, burners)
                if (gpuPart != null) {
                    require(gpu == null) { "Two GPU parts in '$text'" }
                    gpu = gpuPart
                } else {
                    require(cpu == null) { "Two CPU parts in '$text'" }
                    cpu = CoreAssignment.parse(part, kernels)
                }
            }
            return Workload(cpu, gpu)
        }
    }
}
