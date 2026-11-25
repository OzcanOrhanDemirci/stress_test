package dev.ozcan.stress.charge

import android.os.BatteryManager
import dev.ozcan.stress.analysis.CurrentConvention
import dev.ozcan.stress.analysis.Point
import dev.ozcan.stress.telemetry.BatteryReading
import kotlin.math.abs

internal data class ChargeTick(val seconds: Double, val battery: BatteryReading)

internal object ChargePower {
    private val possibleAmps = 0.02..25.0
    private val units = listOf(1e-6, 1e-3)

    fun raw(battery: BatteryReading): Long? = battery.currentRaw?.takeIf { it in -25_000_000L..25_000_000L }

    fun volts(battery: BatteryReading): Double? =
        battery.voltageMillivolts?.takeIf { it in 2_500..10_000 }?.div(1000.0)

    fun inferCharging(ticks: List<ChargeTick>): CurrentConvention? {
        val readings = ticks.mapNotNull { tick -> raw(tick.battery)?.takeIf { it != 0L } }
        if (readings.size < 3) return null
        val sign = if (readings.count { it > 0 } > readings.size / 2) 1 else -1
        val agreeing = readings.filter { (it > 0) == (sign > 0) }
        if (agreeing.size < 3 || agreeing.size < 0.8 * readings.size) return null

        // Counter and level trends can expose a stale CHARGING broadcast. Require
        // two consistent counter steps or a level change before overriding it.
        val counter = ticks.mapNotNull { tick ->
            tick.battery.chargeCounterMicroAmpHours?.let { Point(tick.seconds, it.toDouble()) }
        }
        val delta = counter.zipWithNext { a, b -> b.value - a.value }
        val counterDirection = direction(delta, minimum = 2)
        val levels = ticks.mapNotNull { it.battery.levelPercent }
        val levelDirection = direction(levels.zipWithNext { a, b -> (b - a).toDouble() }, minimum = 1)
        if (counterDirection != null && levelDirection != null && counterDirection != levelDirection) return null
        val status = ticks.last().battery.status
        val direction = counterDirection ?: levelDirection ?: when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> 1
            BatteryManager.BATTERY_STATUS_DISCHARGING -> -1
            else -> return null
        }

        val candidates = units.filter { unit ->
            agreeing.count { abs(it) * unit in possibleAmps } >= 0.8 * agreeing.size
        }
        val unit = candidates.singleOrNull() ?: run {
            // A slow trickle and several amperes can share the same raw range.
            // Only use the counter to break that tie over a measured interval.
            if (counterDirection == null || counter.last().seconds - counter.first().seconds < 10.0) return null
            val measuredAmps = abs(counter.last().value - counter.first().value) * 0.0036 /
                (counter.last().seconds - counter.first().seconds)
            val sorted = agreeing.map(::abs).sorted()
            val middle = sorted[sorted.size / 2]
            candidates.singleOrNull { middle * it in (measuredAmps / 3)..(measuredAmps * 3) } ?: return null
        }
        return CurrentConvention(unit, dischargeSign = -direction * sign)
    }

    private fun direction(changes: List<Double>, minimum: Int): Int? = when {
        changes.count { it > 0 } >= minimum && changes.none { it < 0 } -> 1
        changes.count { it < 0 } >= minimum && changes.none { it > 0 } -> -1
        else -> null
    }

    fun batteryAmps(battery: BatteryReading, convention: CurrentConvention?): Double? {
        if (convention == null || !battery.plugged) return null
        val amps = raw(battery)?.let { -convention.dischargeAmps(it) } ?: return null
        return amps.takeIf { abs(it) <= 25.0 }
    }
}
