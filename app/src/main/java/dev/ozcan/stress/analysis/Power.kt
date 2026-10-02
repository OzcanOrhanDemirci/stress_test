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

    companion object {
        /** A phone with its screen on never draws less than this; below it a reading in µA would look like mA. */
        private const val MIN_PLAUSIBLE_MICROAMPS = 20_000L

        /** Needs readings taken while unplugged; returns null when there are too few non-zero ones. */
        fun infer(rawReadings: List<Long>): CurrentConvention? {
            val nonZero = rawReadings.filter { it != 0L }.sorted()
            if (nonZero.size < 3) return null
            val median = nonZero[nonZero.size / 2]
            return CurrentConvention(
                ampsPerUnit = if (abs(median) >= MIN_PLAUSIBLE_MICROAMPS) 1e-6 else 1e-3,
                dischargeSign = if (median < 0) -1 else 1,
            )
        }
    }
}

/** A value at a time, seconds from the start of whatever window it belongs to. */
data class Point(val seconds: Double, val value: Double)

object Power {

    /** No phone battery holds less than this; a counter below it is in mAh, not the µAh Android documents. */
    private const val MIN_PLAUSIBLE_MICROAMP_HOURS = 100_000L

    /**
     * `BATTERY_PROPERTY_CHARGE_COUNTER` in µAh. Android documents µAh; the
     * Honor 400 reports mAh (5679 at 97%), which this detects by size.
     */
    fun normalizeChargeCounter(raw: Long): Long = if (raw in 1 until MIN_PLAUSIBLE_MICROAMP_HOURS) raw * 1000 else raw

    /**
     * `EXTRA_VOLTAGE` in millivolts. Android documents mV; a few devices send
     * volts or microvolts, told apart by size (a cell sits near 3-4.5 V).
     * Null when the value is missing or makes no sense in any unit.
     */
    fun normalizeMillivolts(raw: Int): Int? = when {
        raw <= 0 -> null
        raw < 20 -> raw * 1000
        raw in 1_000..20_000 -> raw
        raw >= 1_000_000 -> raw / 1000
        else -> null
    }

    /**
     * Battery output power of each sample in watts: discharge current times
     * battery voltage. Samples taken while plugged in, or missing either
     * value, are skipped: on a charger the gauge measures the net of charging
     * and load, not the load.
     */
    fun watts(samples: List<Sample>, convention: CurrentConvention, originNanos: Long): List<Point> =
        samples.mapNotNull { s ->
            val raw = s.battery.currentRaw ?: return@mapNotNull null
            val millivolts = s.battery.voltageMillivolts ?: return@mapNotNull null
            if (s.battery.plugged) return@mapNotNull null
            Point(seconds(s.timeNanos, originNanos), convention.dischargeAmps(raw) * millivolts / 1000.0)
        }

    /**
     * Mean discharge current in amperes implied by the charge counter falling
     * over the window: the slope of a least-squares line through it. An
     * independent check on the instantaneous current, which comes from a
     * different register of the gauge.
     */
    fun chargeCounterAmps(samples: List<Sample>, originNanos: Long): Double? {
        val points = samples.mapNotNull { s ->
            s.battery.chargeCounterMicroAmpHours?.let { Point(seconds(s.timeNanos, originNanos), it.toDouble()) }
        }
        val slopeMicroAmpHoursPerSecond = Stats.slope(points) ?: return null
        return -slopeMicroAmpHoursPerSecond * 3600.0 * 1e-6
    }

    fun seconds(timeNanos: Long, originNanos: Long): Double = (timeNanos - originNanos) / 1e9
}
