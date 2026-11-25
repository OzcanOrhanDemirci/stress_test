package dev.ozcan.stress.charge

import android.os.BatteryManager
import dev.ozcan.stress.analysis.CurrentConvention
import dev.ozcan.stress.analysis.Point
import dev.ozcan.stress.analysis.Power
import dev.ozcan.stress.run.EndReason
import dev.ozcan.stress.telemetry.BatteryReading

internal enum class ChargePhase { Preparing, Baseline, Waiting, Measuring }

internal data class ChargeUpdate(
    val phase: ChargePhase = ChargePhase.Preparing,
    val elapsedSeconds: Double = 0.0,
    val battery: BatteryReading? = null,
    val amps: Double? = null,
    val watts: Double? = null,
    val idleWatts: Double? = null,
    val curve: List<Point> = emptyList(),
    val end: EndReason? = null,
    val cannotMeasure: Boolean = false,
    /** The battery was already full when the measurement would have begun: there is no charging to measure or keep. */
    val alreadyFull: Boolean = false,
)

/** One continuous plug-in session. Times are monotonic and relative to opening the run screen. */
internal class ChargeSession(private val plannedSeconds: Int?) {
    private var phase = ChargePhase.Preparing
    private var baselineStart = 0.0
    private var measurementStart = 0.0
    private val baseline = mutableListOf<ChargeTick>()
    private val probing = mutableListOf<ChargeTick>()
    private var convention: CurrentConvention? = null
    private var idleWatts: Double? = null
    private val recorded = mutableListOf<ChargePoint>()
    private val curveTotals = DoubleArray(101)
    private val curveCounts = IntArray(101)
    private var lastUsableAt: Double? = null
    private var firstUsableAt: Double? = null
    private var lastGaugeAt: Double? = null
    private var sawIncoming = false
    private var inferredWhilePlugged = false

    val points: List<ChargePoint> get() = recorded

    /** Whether the test got a single reading into the battery; one stopped before that has nothing to keep. */
    val measured: Boolean get() = recorded.isNotEmpty() && !update.alreadyFull

    /** Whether the test should tell the person the battery filled: it began below full and was measured up to full. */
    val reachedFull: Boolean get() = update.end == EndReason.BatteryFull && !update.alreadyFull && measured
    var update = ChargeUpdate()
        private set

    fun read(tick: ChargeTick): ChargeUpdate {
        if (update.end != null || update.cannotMeasure) return update
        val (now, battery) = tick
        val wasMeasuring = phase == ChargePhase.Measuring
        if (battery.plugStateKnown) {
            when (phase) {
                ChargePhase.Preparing -> if (battery.plugged) {
                    beginMeasurement(now)
                } else {
                    baselineStart = now
                    phase = ChargePhase.Baseline
                }
                ChargePhase.Baseline, ChargePhase.Waiting -> if (battery.plugged) beginMeasurement(now)
                ChargePhase.Measuring -> Unit
            }
        }

        val ended = endCondition(phase, battery, now - measurementStart, plannedSeconds)
        // Full on the very first reading with the cable in: a 0:00 "battery full" record would say nothing.
        val alreadyFull = ended == EndReason.BatteryFull && !wasMeasuring && recorded.isEmpty()
        if (alreadyFull) {
            update = ChargeUpdate(phase, 0.0, battery, idleWatts = idleWatts, end = ended, alreadyFull = true)
            return update
        }
        val canRead = ChargePower.raw(battery) != null && ChargePower.volts(battery) != null
        val responsive = canRead && ChargePower.raw(battery) != 0L
        var amps: Double? = null
        when (phase) {
            ChargePhase.Baseline -> {
                if (responsive) lastGaugeAt = now
                baseline += tick
                amps = inferBaseline()?.let { c -> battery.currentRaw?.let(c::usableAmps) }
                if (ended == null && now - baselineStart >= BASELINE_SECONDS) {
                    val inferred = inferBaseline()
                    val idle = inferred?.let(::baselineWatts)
                    if (idle != null) {
                        convention = inferred
                        idleWatts = idle
                    }
                    phase = ChargePhase.Waiting
                }
            }
            ChargePhase.Measuring -> if (battery.plugged && battery.plugStateKnown) {
                if (responsive) lastGaugeAt = now
                // Some gauges keep the same sign on both sides of the cable.
                // Never flip a measured baseline from Android status alone.
                val provisional = ChargePower.batteryAmps(battery, convention)
                val directionInDoubt = !inferredWhilePlugged && !sawIncoming &&
                    provisional != null && provisional < 0 && battery.status == BatteryManager.BATTERY_STATUS_CHARGING
                if ((convention == null || directionInDoubt) && ended == null) {
                    probing += tick
                    probing.removeAll { now - it.seconds > CONVENTION_PROBE_SECONDS }
                    ChargePower.inferCharging(probing, requireTrend = convention != null)?.let {
                        convention = it
                        inferredWhilePlugged = true
                        probing.dropLast(1).forEach(::record)
                        probing.clear()
                    }
                } else {
                    probing.clear()
                }
                amps = if (ended == EndReason.BatteryFull || ended == EndReason.Safety ||
                    directionInDoubt && !inferredWhilePlugged) null
                    else ChargePower.batteryAmps(battery, convention)
                record(tick, amps)
                amps = recorded.last().amps
            }
            ChargePhase.Preparing, ChargePhase.Waiting -> Unit
        }

        val elapsed = when (phase) {
            ChargePhase.Baseline -> now - baselineStart
            ChargePhase.Measuring -> now - measurementStart
            else -> 0.0
        }
        val timedOut = ended == null && when (phase) {
            ChargePhase.Preparing -> now >= GAUGE_TIMEOUT_SECONDS
            ChargePhase.Baseline -> elapsed >= GAUGE_TIMEOUT_SECONDS && lastGaugeAt == null
            ChargePhase.Measuring -> when {
                firstUsableAt != null -> now - (lastUsableAt ?: now) >= MEASUREMENT_LOST_SECONDS
                lastGaugeAt == null -> elapsed >= GAUGE_TIMEOUT_SECONDS
                else -> elapsed >= CONVENTION_PROBE_SECONDS
            }
            ChargePhase.Waiting -> false
        }
        update = ChargeUpdate(
            phase, elapsed, battery, amps, watts(battery, amps)?.coerceAtLeast(0.0), idleWatts,
            curveCounts.indices.mapNotNull { level ->
                curveCounts[level].takeIf { it > 0 }?.let { Point(level.toDouble(), curveTotals[level] / it) }
            },
            end = ended ?: EndReason.ChargeReadingLost.takeIf { timedOut && firstUsableAt != null },
            cannotMeasure = timedOut && firstUsableAt == null,
        )
        return update
    }

    private fun beginMeasurement(now: Double) {
        // If the cable came early, the discharge convention may already be
        // known, but the interrupted baseline cannot supply an idle estimate.
        if (phase == ChargePhase.Baseline) convention = inferBaseline()
        phase = ChargePhase.Measuring
        measurementStart = now
        lastGaugeAt = null
    }

    private fun inferBaseline(): CurrentConvention? =
        CurrentConvention.infer(baseline.mapNotNull { ChargePower.raw(it.battery) })

    private fun baselineWatts(convention: CurrentConvention): Double? {
        val points = baseline.mapNotNull { (seconds, battery) ->
            watts(battery, battery.currentRaw?.let(convention::usableAmps))?.let { Point(seconds, it) }
        }
        if (points.size < 3 || points.last().seconds - points.first().seconds < BASELINE_SECONDS / 2) return null
        if (points.zipWithNext().any { (a, b) -> b.seconds - a.seconds > 3.0 }) return null
        return Power.meanWatts(points)
    }

    private fun watts(battery: BatteryReading, amps: Double?): Double? =
        ChargePower.volts(battery)?.let { volts -> amps?.times(volts) }

    private fun record(tick: ChargeTick) = record(tick, ChargePower.batteryAmps(tick.battery, convention))

    private fun record(tick: ChargeTick, amps: Double?) {
        val (now, battery) = tick
        val watts = watts(battery, amps)
        if (watts != null && watts > 0.0) sawIncoming = true
        // Some unsupported gauges report 0. Only use zero once the gauge
        // has actually reported a charging current, or a nonzero reverse flow.
        val usable = watts?.takeIf { it != 0.0 || lastUsableAt != null }?.coerceAtLeast(0.0)
        if (usable != null) {
            if (firstUsableAt == null) firstUsableAt = now
            lastUsableAt = now
        }
        val level = battery.levelPercent?.takeIf { it in 0..100 }
        if (level != null && usable != null) {
            curveTotals[level] += usable
            curveCounts[level]++
        }
        val point = ChargePoint(
            now - measurementStart, usable, amps.takeIf { usable != null }, ChargePower.volts(battery),
            level, battery.temperatureCelsius, ChargePower.raw(battery), battery.plugType,
        )
        val previousIndex = recorded.lastIndex
        // Replay only the probe samples for which the inferred convention
        // wasn't available at display time.
        if (previousIndex >= 0 && point.seconds <= recorded[previousIndex].seconds) {
            val index = recorded.binarySearchBy(point.seconds) { it.seconds }
            if (index >= 0) recorded[index] = point
        } else {
            recorded += point
        }
    }

    companion object {
        const val BASELINE_SECONDS = 20
        const val WARN_CELSIUS = 45.0
        const val STOP_CELSIUS = 47.0
        private const val GAUGE_TIMEOUT_SECONDS = 6.0
        private const val CONVENTION_PROBE_SECONDS = 90.0
        private const val MEASUREMENT_LOST_SECONDS = 10.0

        /** Full as Android reports it: 100 % or status FULL. A charge test that would start here has nothing to measure. */
        fun isFull(levelPercent: Int?, status: Int? = null): Boolean =
            levelPercent == 100 || status == BatteryManager.BATTERY_STATUS_FULL

        fun endCondition(phase: ChargePhase, battery: BatteryReading, elapsed: Double, planned: Int?): EndReason? = when {
            (battery.temperatureCelsius ?: Double.NEGATIVE_INFINITY) >= STOP_CELSIUS -> EndReason.Safety
            phase != ChargePhase.Measuring -> null
            battery.onBattery -> EndReason.Unplugged
            isFull(battery.levelPercent, battery.status) -> EndReason.BatteryFull
            planned != null && elapsed >= planned -> EndReason.Completed
            else -> null
        }
    }
}
