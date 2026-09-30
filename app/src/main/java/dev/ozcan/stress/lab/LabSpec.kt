package dev.ozcan.stress.lab

import dev.ozcan.stress.engine.CoreAssignment
import dev.ozcan.stress.engine.CpuEngine
import dev.ozcan.stress.engine.CpuKernel

/**
 * A measurement run started from a development machine:
 *
 *     adb shell am start -S -n dev.ozcan.stress/.MainActivity \
 *         --es lab.load 0-3:dry,4-7:fp32_gemm --es lab.seconds 60
 *
 * The phone idles for [idleSeconds] to measure the baseline, runs the load for
 * [loadSeconds], then writes the result to its external files directory. Every
 * value travels as a string extra (`--es`) and is parsed here.
 */
data class LabSpec(
    val assignment: CoreAssignment,
    val idleSeconds: Int,
    val loadSeconds: Int,
    val nice: Int,
    val batchMillis: Int,
    val brightness: Float,
    val tag: String,
) {
    companion object {
        const val PREFIX = "lab."

        /** Returns null when no lab key is present, so a normal launch is not an error. */
        fun parse(extras: Map<String, String?>, kernels: List<CpuKernel>): Result<LabSpec>? {
            if (extras.keys.none { it.startsWith(PREFIX) }) return null
            return runCatching {
                fun int(key: String, default: Int, range: IntRange): Int {
                    val value = extras[PREFIX + key]?.let {
                        it.trim().toIntOrNull() ?: throw IllegalArgumentException("$PREFIX$key is not a number: '$it'")
                    } ?: default
                    require(value in range) { "$PREFIX$key must be in $range, was $value" }
                    return value
                }

                val load = extras[PREFIX + "load"] ?: throw IllegalArgumentException("${PREFIX}load is required")
                val assignment = CoreAssignment.parse(load, kernels)
                val brightness = extras[PREFIX + "brightness"]?.let {
                    it.trim().toFloatOrNull() ?: throw IllegalArgumentException("${PREFIX}brightness is not a number: '$it'")
                } ?: DEFAULT_BRIGHTNESS
                require(brightness in 0f..1f) { "${PREFIX}brightness must be in 0..1, was $brightness" }

                LabSpec(
                    assignment = assignment,
                    idleSeconds = int("idle", default = 10, range = 3..600),
                    loadSeconds = int("seconds", default = 60, range = 5..3600),
                    nice = int("nice", default = 0, range = -20..19),
                    batchMillis = int("batch", default = CpuEngine.DEFAULT_BATCH_MILLIS, range = 1..1000),
                    brightness = brightness,
                    tag = extras[PREFIX + "tag"]?.trim()?.takeIf { it.isNotEmpty() } ?: assignment.describe(),
                )
            }
        }

        /** Dim, so the screen adds little and the same amount to every run. */
        const val DEFAULT_BRIGHTNESS = 0.2f
    }
}
