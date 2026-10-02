package dev.ozcan.stress.run

import dev.ozcan.stress.engine.SceneKind

/**
 * What the user can run. Each mode is a lab workload in text form, so the
 * recipes are measured with exactly the code the app runs. The screens name
 * and describe the modes; their names here are kept in stored runs.
 *
 * The recipes are the winners of the candidate sweeps in docs/OLCUMLER.md
 * (2026-10-01): C8 fp32_l2 on every core, the FP32 burner on the GPU. The
 * cinematic scenes keep the GPU just as busy but draw about half the
 * burner's power, so the power modes show a light gauge and the scenes have
 * modes of their own: one for each, grouped on the home screen by [scene].
 * The recipes were found on one phone and hold on others: a load that keeps
 * every unit busy on one core design keeps it busy on the next.
 */
enum class StressMode(
    val recipe: String,
    val usesCpu: Boolean,
    val usesGpu: Boolean,
    val scene: SceneKind? = null,
) {
    Full("fp32_l2+gpu_fp32@preview", usesCpu = true, usesGpu = true),
    Cinematic("fp32_l2+scene", usesCpu = true, usesGpu = true, scene = SceneKind.Pool),
    CinematicForest("fp32_l2+forest", usesCpu = true, usesGpu = true, scene = SceneKind.Forest),
    CinematicWhite("fp32_l2+white", usesCpu = true, usesGpu = true, scene = SceneKind.White),
    Cpu("fp32_l2", usesCpu = true, usesGpu = false),
    Gpu("gpu_fp32@preview", usesCpu = false, usesGpu = true),
    Dry("dry", usesCpu = true, usesGpu = false);

    companion object {
        val cinematic: List<StressMode> get() = entries.filter { it.scene != null }

        fun of(name: String): StressMode? = entries.firstOrNull { it.name == name }
    }
}

/** How long the load runs; null runs until stopped. */
enum class StressDuration(val seconds: Int?) {
    One(60),
    Five(5 * 60),
    Fifteen(15 * 60),
    Thirty(30 * 60),
    Endless(null),
}
