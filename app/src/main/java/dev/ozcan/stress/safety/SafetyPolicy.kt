package dev.ozcan.stress.safety

import android.os.PowerManager
import dev.ozcan.stress.telemetry.Sample
import dev.ozcan.stress.telemetry.ThermalGroup

/** What device safety watches. */
enum class SafetyReason { BatteryHot, ChipHot, SkinHot, ThermalStatus, BatteryLow }

enum class SafetyLevel { Ok, Warn, Stop }

/**
 * One finding: how serious, why, and the reading behind it (°C for the
 * temperatures, percent for the battery level, the Android thermal status
 * for [SafetyReason.ThermalStatus]).
 */
data class SafetyCheck(val level: SafetyLevel, val reason: SafetyReason? = null, val value: Double? = null) {
    companion object {
        val OK = SafetyCheck(SafetyLevel.Ok)
    }
}

/**
 * The limits device safety keeps. A stress test is meant to reach the
 * phone's own throttling: the Honor 400's kernel starts it at 95 °C on the
 * CPU and GPU zones (its trip points: 95 °C throttling, 110-115 °C harder,
 * 125 °C critical; read 2026-10-02). It does not always hold them there: in a
 * full-load run it let the GPU go from 95 to 107 °C after 37 s, and a 105 °C
 * stop ended that run at 43 s. So the chip stops at 110 °C, where the phone's
 * harder stage begins, and warns from 105 °C (Özcan's decision, 2026-10-02):
 * past that its own protection is no longer keeping up. The battery and case
 * limits are where a cell starts to age fast and a hand gets uncomfortable;
 * Android's own SEVERE thermal status ("UX largely impacted") also ends a test.
 */
data class SafetyLimits(
    val batteryWarn: Double = 44.0,
    val batteryStop: Double = 47.0,
    /** CPU and GPU, the hottest zone of each. */
    val chipWarn: Double = 105.0,
    val chipStop: Double = 110.0,
    val skinWarn: Double = 45.0,
    val skinStop: Double = 48.0,
    val thermalWarn: Int = PowerManager.THERMAL_STATUS_MODERATE,
    val thermalStop: Int = PowerManager.THERMAL_STATUS_SEVERE,
    /** Battery levels, percent; only while the phone runs on battery. */
    val levelWarn: Int = 10,
    val levelStop: Int = 5,
    /** A test does not start above these: the phone first cools down (or charges). */
    val batteryStart: Double = 42.0,
    val chipStart: Double = 80.0,
    val skinStart: Double = 42.0,
    val levelStart: Int = 10,
    /** A temperature stop needs its reading to hold this long: one odd sample does not end a test. */
    val sustainSeconds: Double = 3.0,
)

/** Pure rules over one [Sample]; [SafetyMonitor] adds the time a stop condition must hold. */
object SafetyPolicy {

    /** Every condition of [sample] that is past its warning limit, worst first. */
    fun findings(sample: Sample, limits: SafetyLimits = SafetyLimits()): List<SafetyCheck> {
        val found = mutableListOf<SafetyCheck>()
        fun temperature(reason: SafetyReason, celsius: Double?, warn: Double, stop: Double) {
            if (celsius == null) return
            when {
                celsius >= stop -> found += SafetyCheck(SafetyLevel.Stop, reason, celsius)
                celsius >= warn -> found += SafetyCheck(SafetyLevel.Warn, reason, celsius)
            }
        }
        temperature(SafetyReason.BatteryHot, sample.battery.temperatureCelsius, limits.batteryWarn, limits.batteryStop)
        val chip = listOfNotNull(sample.sysfs.temperatures[ThermalGroup.Cpu], sample.sysfs.temperatures[ThermalGroup.Gpu]).maxOrNull()
        temperature(SafetyReason.ChipHot, chip, limits.chipWarn, limits.chipStop)
        temperature(SafetyReason.SkinHot, sample.sysfs.temperatures[ThermalGroup.Skin], limits.skinWarn, limits.skinStop)
        val status = sample.thermalStatus
        when {
            status >= limits.thermalStop -> found += SafetyCheck(SafetyLevel.Stop, SafetyReason.ThermalStatus, status.toDouble())
            status >= limits.thermalWarn -> found += SafetyCheck(SafetyLevel.Warn, SafetyReason.ThermalStatus, status.toDouble())
        }
        val level = sample.battery.levelPercent
        if (level != null && !sample.battery.plugged) {
            when {
                level <= limits.levelStop -> found += SafetyCheck(SafetyLevel.Stop, SafetyReason.BatteryLow, level.toDouble())
                level <= limits.levelWarn -> found += SafetyCheck(SafetyLevel.Warn, SafetyReason.BatteryLow, level.toDouble())
            }
        }
        return found.sortedByDescending { it.level.ordinal }
    }

    /** Why a test should not start now, or null when it may. */
    fun startBlock(sample: Sample, limits: SafetyLimits = SafetyLimits()): SafetyCheck? {
        val battery = sample.battery.temperatureCelsius
        if (battery != null && battery >= limits.batteryStart) return SafetyCheck(SafetyLevel.Stop, SafetyReason.BatteryHot, battery)
        val chip = listOfNotNull(sample.sysfs.temperatures[ThermalGroup.Cpu], sample.sysfs.temperatures[ThermalGroup.Gpu]).maxOrNull()
        if (chip != null && chip >= limits.chipStart) return SafetyCheck(SafetyLevel.Stop, SafetyReason.ChipHot, chip)
        val skin = sample.sysfs.temperatures[ThermalGroup.Skin]
        if (skin != null && skin >= limits.skinStart) return SafetyCheck(SafetyLevel.Stop, SafetyReason.SkinHot, skin)
        if (sample.thermalStatus >= limits.thermalStop) {
            return SafetyCheck(SafetyLevel.Stop, SafetyReason.ThermalStatus, sample.thermalStatus.toDouble())
        }
        val level = sample.battery.levelPercent
        if (level != null && !sample.battery.plugged && level <= limits.levelStart) {
            return SafetyCheck(SafetyLevel.Stop, SafetyReason.BatteryLow, level.toDouble())
        }
        return null
    }
}

/**
 * Watches samples during a test. A condition past its stop limit must hold
 * for [SafetyLimits.sustainSeconds] before [update] says stop; until then it
 * counts as a warning.
 */
class SafetyMonitor(private val limits: SafetyLimits = SafetyLimits()) {

    private val stopSince = HashMap<SafetyReason, Long>()

    fun update(sample: Sample): SafetyCheck {
        val findings = SafetyPolicy.findings(sample, limits)
        val stopping = findings.filter { it.level == SafetyLevel.Stop }
        stopSince.keys.retainAll(stopping.mapNotNull { it.reason }.toSet())
        for (check in stopping) {
            val reason = check.reason ?: continue
            val since = stopSince.getOrPut(reason) { sample.timeNanos }
            if ((sample.timeNanos - since) / 1e9 >= limits.sustainSeconds) return check
        }
        // Not held long enough yet: shown as the warning it is so far.
        return findings.firstOrNull()?.let { if (it.level == SafetyLevel.Stop) it.copy(level = SafetyLevel.Warn) else it }
            ?: SafetyCheck.OK
    }
}
