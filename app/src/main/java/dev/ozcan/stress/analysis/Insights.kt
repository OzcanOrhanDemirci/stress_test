package dev.ozcan.stress.analysis

import dev.ozcan.stress.run.EndReason
import dev.ozcan.stress.run.RunAnalysis
import dev.ozcan.stress.run.RunRecord
import dev.ozcan.stress.safety.SafetyLimits
import dev.ozcan.stress.safety.SafetyReason
import dev.ozcan.stress.telemetry.ThermalGroup

/** How a finding reads: good news, plain information, a caution, or a problem. */
enum class Severity { Good, Info, Warn, Bad }

/** Where a run's stability sits, by 3DMark's measure (worst slice over best). */
enum class StabilityGrade { Excellent, Good, Fair, Poor;

    companion object {
        fun of(stability: Double): StabilityGrade = when {
            stability >= 0.95 -> Excellent
            stability >= 0.85 -> Good
            stability >= 0.70 -> Fair
            else -> Poor
        }
    }
}

/** One plain-language finding about a run. The screens and the report word it. */
sealed interface Insight {
    val severity: Severity

    data class EndedBySafety(val reason: SafetyReason?, val value: Double?) : Insight {
        override val severity = Severity.Warn
    }
    data object GpuFailed : Insight {
        override val severity = Severity.Bad
    }
    data object Interrupted : Insight {
        override val severity = Severity.Bad
    }
    data object Charging : Insight {
        override val severity = Severity.Warn
    }
    data object PowerFromCounter : Insight {
        override val severity = Severity.Info
    }
    /** [cluster] first slowed down [seconds] into the load. */
    data class Throttled(val cluster: String, val seconds: Double, override val severity: Severity) : Insight
    data object NeverThrottled : Insight {
        override val severity = Severity.Good
    }
    /** CPU work rate of the last minute against the first: [drop] 0..1. */
    data class PerformanceDrop(val drop: Double, override val severity: Severity) : Insight
    data class Stability(val value: Double, val grade: StabilityGrade, override val severity: Severity) : Insight
    /** The hottest the chip ([key], a temperature key) got. */
    data class ChipPeak(val key: String, val celsius: Double, override val severity: Severity) : Insight
    data class BatteryWarmed(val from: Double, val to: Double, override val severity: Severity) : Insight
    /** Battery level lost per hour at this load. */
    data class BatteryDrain(val percentPerHour: Double) : Insight {
        override val severity = Severity.Info
    }
    data class ComputationErrors(val count: Long) : Insight {
        override val severity = if (count > 0) Severity.Bad else Severity.Good
    }
    data class FrameRate(val mean: Double, val min: Double?) : Insight {
        override val severity = Severity.Info
    }
}

/** Reads a [RunRecord] into its [Insight]s, the most important first. Pure, so it is tested with made-up runs. */
object Insights {

    fun of(record: RunRecord): List<Insight> {
        val s = record.summary
        val found = mutableListOf<Insight>()

        when (record.endReason) {
            EndReason.Safety.name -> found += Insight.EndedBySafety(
                record.safetyReason?.let { name -> SafetyReason.entries.firstOrNull { it.name == name } },
                record.safetyValue,
            )
            EndReason.GpuFailed.name -> found += Insight.GpuFailed
            EndReason.Interrupted.name -> found += Insight.Interrupted
        }
        if (!s.powerValid) found += Insight.Charging else if (s.powerFromCounter) found += Insight.PowerFromCounter

        found += Insight.ComputationErrors(s.computationErrors)

        val throttles = s.firstThrottleSeconds.mapNotNull { (cluster, seconds) -> seconds?.let { cluster to it } }
        if (s.firstThrottleSeconds.isNotEmpty()) {
            val first = throttles.minByOrNull { it.second }
            found += if (first == null) {
                Insight.NeverThrottled
            } else {
                Insight.Throttled(first.first, first.second, if (first.second < 60) Severity.Warn else Severity.Info)
            }
        }

        val start = s.cpuStartRate
        val end = s.cpuEndRate
        if (start != null && end != null && start > 0) {
            val drop = (1 - end / start).coerceAtLeast(0.0)
            found += Insight.PerformanceDrop(
                drop,
                when {
                    drop < 0.05 -> Severity.Good
                    drop < 0.20 -> Severity.Info
                    else -> Severity.Warn
                },
            )
        }

        (s.cpuStability ?: s.gpuStability)?.let { stability ->
            val grade = StabilityGrade.of(stability)
            found += Insight.Stability(
                stability,
                grade,
                when (grade) {
                    StabilityGrade.Excellent, StabilityGrade.Good -> Severity.Good
                    StabilityGrade.Fair -> Severity.Info
                    StabilityGrade.Poor -> Severity.Warn
                },
            )
        }

        s.meanFps?.let { found += Insight.FrameRate(it, s.minFps) }

        val chip = listOf(ThermalGroup.Cpu.key, ThermalGroup.Gpu.key, "A715", "A510", "GPU")
            .mapNotNull { key -> s.maxTemperatures[key]?.let { key to it } }
            .maxByOrNull { it.second }
        if (chip != null) {
            found += Insight.ChipPeak(
                chip.first,
                chip.second,
                when {
                    // Past device safety's warning point the phone is no longer holding the chip at its throttling point.
                    chip.second >= SafetyLimits().chipWarn -> Severity.Bad
                    chip.second >= 95 -> Severity.Warn
                    chip.second >= 80 -> Severity.Info
                    else -> Severity.Good
                },
            )
        }

        val batteryKey = if (RunAnalysis.BATTERY in s.maxTemperatures) RunAnalysis.BATTERY else "Pil"
        val batteryEnd = s.maxTemperatures[batteryKey]
        val batteryStart = s.startTemperatures[batteryKey]
        if (batteryEnd != null && batteryStart != null) {
            found += Insight.BatteryWarmed(
                batteryStart,
                batteryEnd,
                when {
                    batteryEnd >= 44 -> Severity.Warn
                    batteryEnd - batteryStart >= 8 -> Severity.Info
                    else -> Severity.Good
                },
            )
        }

        val from = s.batteryStartPercent
        val to = s.batteryEndPercent
        if (s.powerValid && from != null && to != null && from > to && record.loadSeconds >= 60) {
            found += Insight.BatteryDrain((from - to) / (record.loadSeconds / 3600.0))
        }

        return found.sortedBy { order(it.severity) }
    }

    /** Problems first, then cautions, then the rest in the order found. */
    private fun order(severity: Severity): Int = when (severity) {
        Severity.Bad -> 0
        Severity.Warn -> 1
        Severity.Info, Severity.Good -> 2
    }
}
