package dev.ozcan.stress.analysis

import dev.ozcan.stress.telemetry.Sample
import kotlin.math.abs

/**
 * How a device reports `BATTERY_PROPERTY_CURRENT_NOW`. Android documents
 * microamperes with positive meaning charge flowing in, but vendors differ in
 * both unit and sign, so the convention is inferred from a window in which the
 * phone is known to run on battery.
 */
data class CurrentConvention(val ampsPerUnit: Double, val dischargeSign: Int) {

    /** Current leaving the battery in amperes; negative while it charges. */
    fun dischargeAmps(raw: Long): Double = raw * ampsPerUnit * dischargeSign

    fun usableAmps(raw: Long): Double? = dischargeAmps(raw).takeIf { it in PHONE_DISCHARGE_AMPS }

    companion object {
        // A deliberately broad envelope for a screen-on phone. If both units
        // could fit (e.g. 22,000), the readings alone do not identify the unit.
        private val PHONE_DISCHARGE_AMPS = 0.02..25.0

        /** Needs a consistent unit and direction in readings taken on battery. */
        fun infer(rawReadings: List<Long>): CurrentConvention? {
            val readings = rawReadings.filter { it != 0L && it != Long.MIN_VALUE && it != Int.MIN_VALUE.toLong() }
            if (readings.size < 3) return null
            return listOf(1e-6, 1e-3).flatMap { unit -> listOf(CurrentConvention(unit, -1), CurrentConvention(unit, 1)) }
                .singleOrNull { c ->
                    val agreeing = readings.count { c.usableAmps(it) != null }
                    agreeing >= 3 && agreeing.toDouble() / readings.size >= 0.8
                }
        }
    }
}

/** A value at a time, seconds from the start of whatever window it belongs to. */
data class Point(val seconds: Double, val value: Double)

object Power {

    /**
     * `BATTERY_PROPERTY_CHARGE_COUNTER` in µAh. Android documents µAh; the
     * Honor 400 reports mAh (5679 at 97%). The battery level helps tell that
     * apart from a legitimate small µAh reading on a nearly empty battery.
     */
    fun normalizeChargeCounter(raw: Long, levelPercent: Int? = null): Long? {
        if (raw !in 1..30_000_000) return null
        if (levelPercent == null) return when (raw) {
            in 500..30_000 -> raw * 1000
            in 100_000..30_000_000 -> raw
            else -> null
        }
        // Near zero the integer percentage cannot distinguish the scales.
        if (levelPercent !in 2..100) return null
        return listOf(raw, raw * 1000).singleOrNull { charge ->
            charge * 100 >= 500_000L * (levelPercent - 1) &&
                charge * 100 <= 30_000_000L * (levelPercent + 1)
        }
    }

    /**
     * `EXTRA_VOLTAGE` in millivolts. Android documents mV; a few devices send
     * volts or microvolts, told apart by size (a cell sits near 3-4.5 V).
     * Null when the value is missing or makes no sense in any unit.
     */
    fun normalizeMillivolts(raw: Int): Int? = when (raw) {
        in 3..10 -> raw * 1000
        in 2_500..10_000 -> raw
        in 2_500_000..10_000_000 -> raw / 1000
        else -> null
    }

    fun currentConvention(samples: List<Sample>): CurrentConvention? =
        CurrentConvention.infer(samples.mapNotNull { it.battery.measurableCurrent })

    /**
     * Battery output power of each sample in watts: discharge current times
     * battery voltage. Samples taken while plugged in, or missing either
     * value, are skipped: on a charger the gauge measures the net of charging
     * and load, not the load.
     */
    fun watts(samples: List<Sample>, convention: CurrentConvention, originNanos: Long): List<Point> =
        samples.mapNotNull { s ->
            val amps = s.battery.measurableCurrent?.let(convention::usableAmps) ?: return@mapNotNull null
            val millivolts = s.battery.voltageMillivolts?.takeIf { it in 2_500..10_000 } ?: return@mapNotNull null
            Point(seconds(s.timeNanos, originNanos), amps * millivolts / 1000.0)
        }

    /**
     * A few missing ticks are normal. With a longer hole a mean can describe
     * the wrong part of the run, and integrating across it invents energy.
     */
    fun windowWatts(samples: List<Sample>, convention: CurrentConvention, origin: Long): List<Point> {
        val first = samples.firstOrNull() ?: return emptyList()
        val points = watts(samples, convention, origin)
        if (points.size < 3 || points.first().seconds - seconds(first.timeNanos, origin) > 3.0 ||
            seconds(samples.last().timeNanos, origin) - points.last().seconds > 2.0 ||
            points.zipWithNext().any { (a, b) -> b.seconds - a.seconds > 2.0 }
        ) return emptyList()
        return points
    }

    /** Integration and its mean use elapsed time, including across the log's 1 Hz/10 Hz boundary. */
    fun joules(watts: List<Point>): Double? = watts.takeIf { it.size >= 2 }
        ?.zipWithNext { a, b -> (a.value + b.value) / 2 * (b.seconds - a.seconds) }?.sum()

    fun meanWatts(watts: List<Point>): Double? {
        val energy = joules(watts) ?: return null
        return (watts.last().seconds - watts.first().seconds).takeIf { it > 0 }?.let { energy / it }
    }

    /**
     * Mean discharge current in amperes implied by the charge counter falling
     * over the window: the slope of a least-squares line through it. An
     * independent check on the instantaneous current, which comes from a
     * different register of the gauge.
     */
    fun chargeCounterAmps(samples: List<Sample>, originNanos: Long): Double? {
        val points = counterPoints(samples, originNanos) { it.battery.chargeCounterMicroAmpHours }
        val slopeMicroAmpHoursPerSecond = Stats.slope(points) ?: return null
        return -slopeMicroAmpHoursPerSecond * 3600.0 * 1e-6
    }

    /** Mean power from a gauge counter with enough observed changes to measure a slope. */
    fun energyCounterWatts(samples: List<Sample>, origin: Long): Double? =
        dischargeRate(counterPoints(samples, origin) { it.battery.energyCounterNanoWattHours })
            ?.times(3600e-9)?.takeIf { it in 0.05..250.0 }

    fun chargeCounterWatts(samples: List<Sample>, origin: Long): Double? {
        val amps = dischargeRate(counterPoints(samples, origin) { it.battery.chargeCounterMicroAmpHours })
            ?.times(3600e-6)?.takeIf { it in 0.02..25.0 } ?: return null
        val volts = meanWatts(samples.mapNotNull {
            it.battery.voltageMillivolts?.takeIf { mv -> mv in 2_500..10_000 }
                ?.let { mv -> Point(seconds(it.timeNanos, origin), mv / 1000.0) }
        }) ?: return null
        return amps * volts
    }

    /** The plan calls for approximately 10% agreement over the same discharge window. */
    fun agrees(watts: Double, counterWatts: Double): Boolean = abs(watts - counterWatts) / counterWatts <= 0.10

    private fun counterPoints(samples: List<Sample>, origin: Long, counter: (Sample) -> Long?): List<Point> =
        samples.filter { it.battery.onBattery }.mapNotNull { s ->
            counter(s)?.takeIf { it > 0 }?.let { Point(seconds(s.timeNanos, origin), it.toDouble()) }
        }

    private fun dischargeRate(points: List<Point>): Double? {
        val first = points.firstOrNull() ?: return null
        val last = points.last()
        // The sampler excludes the end instant of a nominal one-minute run.
        if (last.seconds - first.seconds < 59.0 || last.value >= first.value) return null
        val changes = points.zipWithNext { a, b -> a.value - b.value }
        if (changes.count { it > 0 } < 3 || changes.any { abs(it) > (first.value - last.value) * 0.6 }) return null
        // The log stores recent samples more densely than old ones. Give
        // roughly every second the same weight in the counter regression.
        val seconds = points.distinctBy { it.seconds.toLong() }
        val slope = Stats.slope(seconds) ?: return null
        val meanTime = seconds.map { it.seconds }.average()
        val meanCounter = seconds.map { it.value }.average()
        val variance = seconds.sumOf { (it.value - meanCounter).let { delta -> delta * delta } }
        val residual = seconds.sumOf { (it.value - meanCounter - slope * (it.seconds - meanTime)).let { error -> error * error } }
        return (-slope).takeIf { it > 0 && residual <= variance * 0.15 }
    }

    fun seconds(timeNanos: Long, originNanos: Long): Double = (timeNanos - originNanos) / 1e9
}
