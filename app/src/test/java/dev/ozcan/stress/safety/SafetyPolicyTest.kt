package dev.ozcan.stress.safety

import dev.ozcan.stress.TestSamples.sample
import dev.ozcan.stress.telemetry.Sample
import dev.ozcan.stress.telemetry.ThermalGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SafetyPolicyTest {

    private val limits = SafetyLimits()

    private fun at(
        seconds: Double = 0.0,
        battery: Double = 35.0,
        cpu: Double? = 70.0,
        gpu: Double? = 65.0,
        skin: Double? = 38.0,
        status: Int = 0,
        level: Int = 60,
        plugged: Boolean = false,
    ): Sample {
        val temps = buildMap {
            cpu?.let { put(ThermalGroup.Cpu, it) }
            gpu?.let { put(ThermalGroup.Gpu, it) }
            skin?.let { put(ThermalGroup.Skin, it) }
        }
        return sample(seconds, batteryCelsius = battery, temperatures = temps, level = level, plugged = plugged)
            .copy(thermalStatus = status)
    }

    @Test
    fun `a phone under its limits has no findings`() {
        // The Honor 400 under full load: cores held near 86 °C, the GPU at its 95 °C throttling point.
        assertEquals(emptyList<SafetyCheck>(), SafetyPolicy.findings(at(cpu = 86.0, gpu = 95.6, battery = 41.0, skin = 40.0)))
    }

    @Test
    fun `each reading warns before it stops`() {
        fun level(sample: Sample, reason: SafetyReason) = SafetyPolicy.findings(sample).first { it.reason == reason }.level
        assertEquals(SafetyLevel.Warn, level(at(battery = 44.0), SafetyReason.BatteryHot))
        assertEquals(SafetyLevel.Stop, level(at(battery = 47.0), SafetyReason.BatteryHot))
        assertEquals(SafetyLevel.Warn, level(at(gpu = 100.0), SafetyReason.ChipHot))
        assertEquals(SafetyLevel.Stop, level(at(cpu = 105.5), SafetyReason.ChipHot))
        assertEquals(SafetyLevel.Warn, level(at(skin = 45.0), SafetyReason.SkinHot))
        assertEquals(SafetyLevel.Stop, level(at(skin = 48.0), SafetyReason.SkinHot))
        assertEquals(SafetyLevel.Warn, level(at(status = 2), SafetyReason.ThermalStatus))
        assertEquals(SafetyLevel.Stop, level(at(status = 3), SafetyReason.ThermalStatus))
        assertEquals(SafetyLevel.Warn, level(at(level = 10), SafetyReason.BatteryLow))
        assertEquals(SafetyLevel.Stop, level(at(level = 5), SafetyReason.BatteryLow))
    }

    @Test
    fun `stops come before warnings and a charging phone is never low`() {
        val findings = SafetyPolicy.findings(at(battery = 45.0, cpu = 106.0))
        assertEquals(listOf(SafetyLevel.Stop, SafetyLevel.Warn), findings.map { it.level })
        assertEquals(SafetyReason.ChipHot, findings.first().reason)
        assertEquals(106.0, findings.first().value!!, 0.0)
        assertEquals(emptyList<SafetyCheck>(), SafetyPolicy.findings(at(level = 3, plugged = true)))
    }

    @Test
    fun `missing sensors are not findings`() {
        assertEquals(emptyList<SafetyCheck>(), SafetyPolicy.findings(at(cpu = null, gpu = null, skin = null)))
    }

    @Test
    fun `a stop must hold for its sustain time`() {
        val monitor = SafetyMonitor(limits)
        assertEquals(SafetyLevel.Ok, monitor.update(at(0.0)).level)
        // Hot for 2.9 s: still only a warning.
        assertEquals(SafetyLevel.Warn, monitor.update(at(1.0, battery = 48.0)).level)
        assertEquals(SafetyLevel.Warn, monitor.update(at(3.9, battery = 48.0)).level)
        // Holds for 3 s: stop, with the reading.
        val stop = monitor.update(at(4.0, battery = 48.5))
        assertEquals(SafetyLevel.Stop, stop.level)
        assertEquals(SafetyReason.BatteryHot, stop.reason)
        assertEquals(48.5, stop.value!!, 0.0)
    }

    @Test
    fun `a spike that falls back restarts the clock`() {
        val monitor = SafetyMonitor(limits)
        monitor.update(at(0.0, cpu = 106.0))
        monitor.update(at(2.0, cpu = 90.0))
        assertEquals(SafetyLevel.Warn, monitor.update(at(4.0, cpu = 106.0)).level)
        assertEquals(SafetyLevel.Warn, monitor.update(at(6.5, cpu = 106.0)).level)
        assertEquals(SafetyLevel.Stop, monitor.update(at(7.0, cpu = 106.0)).level)
    }

    @Test
    fun `a hot or flat phone does not start`() {
        assertNull(SafetyPolicy.startBlock(at()))
        assertEquals(SafetyReason.BatteryHot, SafetyPolicy.startBlock(at(battery = 42.0))!!.reason)
        assertEquals(SafetyReason.ChipHot, SafetyPolicy.startBlock(at(cpu = 81.0))!!.reason)
        assertEquals(SafetyReason.SkinHot, SafetyPolicy.startBlock(at(skin = 42.5))!!.reason)
        assertEquals(SafetyReason.ThermalStatus, SafetyPolicy.startBlock(at(status = 3))!!.reason)
        assertEquals(SafetyReason.BatteryLow, SafetyPolicy.startBlock(at(level = 10))!!.reason)
        assertNull(SafetyPolicy.startBlock(at(level = 10, plugged = true)))
    }
}
