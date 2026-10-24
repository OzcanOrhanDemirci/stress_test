package dev.ozcan.stress.engine

/** The cinematic scenes; [key] names one alone as a workload's GPU part, [code] is the native side's number. */
enum class SceneKind(val key: String, val code: Int) {
    Pool("scene", 0),
    Forest("forest", 1),
    White("white", 2),
}

/**
 * The GPU side of a workload: a burner under the visible pass, or ([burner]
 * null) the visible pass alone. [scene] and [sceneScalePercent] override the
 * run's settings when set; [kind] is the scene drawn. A [burner] beside a
 * scene fills the time the scene's frame leaves (docs/PLAN.md §13/7).
 *
 * Text: a burner key, `scene` (the pool), `forest` or `white`, optionally followed by
 * `@preview` (the cheap preview ring instead of the scene) or `@NN` (the
 * scene at NN% of the screen's resolution): `gpu_fp32@preview`, `scene@35`,
 * `forest@45`. A scene and a burner join into one part, in either order:
 * `scene+gpu_fp32`.
 */
data class GpuPart(
    val burner: GpuBurner?,
    val scene: Boolean? = null,
    val sceneScalePercent: Int? = null,
    val kind: SceneKind = SceneKind.Pool,
) {

    val key: String
        get() {
            // Only an unscaled burner beside a scene prints as one part; a
            // scaled one keeps the burner-first text it has always had, and
            // `scene@35+gpu_fp32` prints as `gpu_fp32@35` — the same part.
            val name = if (burner != null && scene == true && sceneScalePercent == null) {
                "${kind.key}+${burner.key}"
            } else {
                burner?.key ?: kind.key
            }
            return when {
                scene == false -> "$name@$PREVIEW"
                sceneScalePercent != null -> "$name@$sceneScalePercent"
                else -> name
            }
        }

    companion object {
        private const val PREVIEW = "preview"

        /** Null when [text] names no burner and no scene: then it is a CPU part. */
        fun parse(text: String, burners: List<GpuBurner>): GpuPart? {
            val name = text.substringBefore('@')
            val burner = burners.firstOrNull { it.key == name }
            val kind = SceneKind.entries.firstOrNull { it.key == name }
            if (burner == null && kind == null) return null
            val sceneKind = kind ?: SceneKind.Pool
            if ('@' !in text) return GpuPart(burner, scene = if (kind != null) true else null, kind = sceneKind)
            val option = text.substringAfter('@')
            if (option == PREVIEW) {
                require(kind == null) { "A scene cannot be drawn as the preview: '$text'" }
                return GpuPart(burner, scene = false)
            }
            val scale = option.toIntOrNull()
            require(scale != null && scale in 10..100) { "Bad GPU option '$option' in '$text'" }
            return GpuPart(burner, scene = true, sceneScalePercent = scale, kind = sceneKind)
        }
    }
}

/**
 * One workload: a CPU assignment, a GPU part, or both at once.
 *
 * Text form: parts joined by '+'. A part naming a GPU burner key, or `scene`,
 * is the GPU part; any other part is a [CoreAssignment] over the device's
 * CPUs. `fp32_gemm`, `gpu_fp32`, `scene` and `0-3:dry,4-7:bf16_mmla+gpu_texture`
 * are all workloads.
 */
data class Workload(val cpu: CoreAssignment?, val gpu: GpuPart?) {

    init {
        require(cpu != null || gpu != null) { "A workload needs a CPU or a GPU part" }
    }

    /** Inverse of [parse]. */
    fun describe(): String = listOfNotNull(cpu?.describe(), gpu?.key).joinToString("+")

    companion object {
        fun parse(text: String, kernels: List<CpuKernel>, burners: List<GpuBurner>, cpuCount: Int): Workload {
            var cpu: CoreAssignment? = null
            var gpu: GpuPart? = null
            for (part in text.split('+').map { it.trim() }) {
                require(part.isNotEmpty()) { "Empty part in '$text'" }
                val gpuPart = GpuPart.parse(part, burners)
                if (gpuPart != null) {
                    gpu = when {
                        gpu == null -> gpuPart
                        // A scene and a burner, in either order, are one GPU
                        // part: the burner fills the frame the scene leaves.
                        gpu.scene == true && gpu.burner == null && gpuPart.burner != null ->
                            gpu.copy(burner = gpuPart.burner)
                        gpuPart.scene == true && gpuPart.burner == null && gpu.burner != null ->
                            gpuPart.copy(burner = gpu.burner)
                        else -> throw IllegalArgumentException("Two GPU parts in '$text'")
                    }
                } else {
                    require(cpu == null) { "Two CPU parts in '$text'" }
                    cpu = CoreAssignment.parse(part, kernels, cpuCount)
                }
            }
            return Workload(cpu, gpu)
        }
    }
}
