package dev.ozcan.stress.lab

import dev.ozcan.stress.engine.CoreAssignment
import dev.ozcan.stress.engine.CpuEngine
import dev.ozcan.stress.engine.CpuKernel
import kotlin.random.Random

/**
 * A measurement session started from a development machine and run by the
 * phone on its own, because this phone drops every adb connection, wired or
 * wireless, the moment the cable comes out:
 *
 *     adb shell am start -S -n dev.ozcan.stress/.MainActivity \
 *         --es lab.load "dry;fp32_gemm;0-3:dry,4-7:bf16_mmla" --es lab.repeat 2
 *
 * The app waits until it runs on battery, then for every run: waits until
 * the CPUs cool below [coolCelsius], idles [idleSeconds] for the baseline,
 * runs the load for [loadSeconds], and writes the result to its external
 * files directory. Loads are separated by ';' (',' belongs to assignments).
 * Every value travels as a string extra (`--es`) and is parsed here.
 */
data class LabSpec(
    val loads: List<CoreAssignment>,
    val repeat: Int,
    val idleSeconds: Int,
    val loadSeconds: Int,
    val nice: Int,
    val batchMillis: Int,
    val brightness: Float,
    val coolCelsius: Double,
    val waitForBattery: Boolean,
) {
    val runCount: Int get() = loads.size * repeat

    /**
     * Every load [repeat] times, shuffled: in a fixed order a candidate would
     * always follow the same neighbour and inherit its heat.
     */
    fun order(random: Random): List<CoreAssignment> = List(repeat) { loads }.flatten().shuffled(random)

    companion object {
        const val PREFIX = "lab."

        /** Dim, so the screen adds little and the same amount to every run. */
        const val DEFAULT_BRIGHTNESS = 0.2f

        /** Returns null when no lab key is present, so a normal launch is not an error. */
        fun parse(extras: Map<String, String?>, kernels: List<CpuKernel>): Result<LabSpec>? {
            if (extras.keys.none { it.startsWith(PREFIX) }) return null
            return runCatching {
                fun text(key: String): String? = extras[PREFIX + key]?.trim()?.takeIf { it.isNotEmpty() }

                fun int(key: String, default: Int, range: IntRange): Int {
                    val value = text(key)?.let {
                        it.toIntOrNull() ?: throw IllegalArgumentException("$PREFIX$key is not a number: '$it'")
                    } ?: default
                    require(value in range) { "$PREFIX$key must be in $range, was $value" }
                    return value
                }

                fun double(key: String, default: Double, range: ClosedFloatingPointRange<Double>): Double {
                    val value = text(key)?.let {
                        it.toDoubleOrNull() ?: throw IllegalArgumentException("$PREFIX$key is not a number: '$it'")
                    } ?: default
                    require(value in range) { "$PREFIX$key must be in $range, was $value" }
                    return value
                }

                val loadText = text("load") ?: throw IllegalArgumentException("${PREFIX}load is required")
                val loads = loadText.split(';').map { it.trim() }.filter { it.isNotEmpty() }
                    .map { CoreAssignment.parse(it, kernels) }
                require(loads.isNotEmpty()) { "${PREFIX}load names no load" }

                LabSpec(
                    loads = loads,
                    repeat = int("repeat", default = 1, range = 1..10),
                    idleSeconds = int("idle", default = 10, range = 3..600),
                    loadSeconds = int("seconds", default = 60, range = 5..3600),
                    nice = int("nice", default = 0, range = -20..19),
                    batchMillis = int("batch", default = CpuEngine.DEFAULT_BATCH_MILLIS, range = 1..1000),
                    brightness = double("brightness", DEFAULT_BRIGHTNESS.toDouble(), 0.0..1.0).toFloat(),
                    coolCelsius = double("cool", default = 40.0, range = 20.0..95.0),
                    waitForBattery = int("battery", default = 1, range = 0..1) == 1,
                )
            }
        }
    }
}
