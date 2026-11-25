package dev.ozcan.stress.charge

import android.os.BatteryManager
import dev.ozcan.stress.report.ReportCsv
import dev.ozcan.stress.run.EndReason
import dev.ozcan.stress.run.RunStore
import dev.ozcan.stress.telemetry.BatteryReading
import java.io.StringWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChargeTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `plugged starts infer either sign and both current units`() {
        for ((watts, speed) in listOf(4.0 to ChargeSpeed.UsbPort, 5.0 to ChargeSpeed.Standard,
            18.0 to ChargeSpeed.Fast, 26.0 to ChargeSpeed.HighPower)) {
            for (scale in listOf(1_000, 1_000_000)) {
                for (sign in listOf(-1, 1)) {
                    val raw = (watts / 4 * scale * sign).toLong()
                    val session = ChargeSession(300)
                    for (second in 0..5) session.read(tick(second, raw))
                    assertEquals(ChargePhase.Measuring, session.update.phase)
                    assertEquals(watts, requireNotNull(session.update.watts), 0.001)
                    assertEquals(watts, requireNotNull(session.points.first().watts), 0.001)
                    assertEquals(speed, ChargeAnalysis.speed(watts))
                    assertNull(session.update.idleWatts)
                }
            }
        }
        assertEquals(ChargeSpeed.Standard, ChargeAnalysis.speed(4.5))
        assertEquals(ChargeSpeed.Fast, ChargeAnalysis.speed(9.0))
    }

    @Test
    fun `only a complete unplugged baseline supplies the estimate`() {
        val session = ChargeSession(300)
        for (second in 0..19) session.read(tick(second, 250_000L + second, plugged = false))
        assertEquals(ChargePhase.Baseline, session.update.phase)
        session.read(tick(20, 250_000, plugged = false))
        assertEquals(ChargePhase.Waiting, session.update.phase)
        assertEquals(1.0, requireNotNull(session.update.idleWatts), 0.001)
        session.read(tick(61, -4_500_000))
        assertEquals(ChargePhase.Measuring, session.update.phase)
        assertEquals(0.0, session.update.elapsedSeconds, 0.001)
        assertEquals(18.0, requireNotNull(session.update.watts), 0.001)
        session.read(tick(62, -250_000, plugged = false))
        assertEquals(EndReason.Unplugged, session.update.end)

        val early = ChargeSession(null)
        early.read(tick(0, 250_000, plugged = false))
        early.read(tick(5, -4_500_000))
        assertEquals(ChargePhase.Measuring, early.update.phase)
        assertNull(early.update.idleWatts)
    }

    @Test
    fun `a test stopped while waiting for the charger has no reading to keep`() {
        val session = ChargeSession(300)
        for (second in 0..25) session.read(tick(second, 250_000, plugged = false))
        assertEquals(ChargePhase.Waiting, session.update.phase)
        assertFalse(session.measured)
        session.read(tick(26, -4_500_000))
        assertTrue(session.measured)
    }

    @Test
    fun `counter and level direction can identify charging without a charging status`() {
        val ticks = (0..5).map { second ->
            tick(second, -4500, status = BatteryManager.BATTERY_STATUS_UNKNOWN, counter = 1_500_000L + second * 1250)
        }
        assertEquals(-1, ChargePower.inferCharging(ticks)?.let { -it.dischargeSign })
        val opposite = (0..5).map { second ->
            tick(second, -4500, counter = 1_500_000L - second * 1250)
        }
        assertEquals(-4.5, requireNotNull(ChargePower.batteryAmps(opposite.last().battery, ChargePower.inferCharging(opposite))), 0.001)
        val levelOnly = (0..5).map { second ->
            tick(second, -4500, percent = 52 + second / 5, status = BatteryManager.BATTERY_STATUS_UNKNOWN)
        }
        assertEquals(4.5, requireNotNull(ChargePower.batteryAmps(levelOnly.last().battery, ChargePower.inferCharging(levelOnly))), 0.001)
        assertNull(ChargePower.inferCharging((0..5).map { tick(it, -22_000) }))
        val ambiguous = (0..30).map { tick(it, -22_000, counter = 1_500_000L + 6L * it) }
        assertEquals(1e-6, requireNotNull(ChargePower.inferCharging(ambiguous)).ampsPerUnit, 0.0)
    }

    @Test
    fun `a gauge with the same sign plugged and unplugged needs a rising level to reverse the baseline sign`() {
        val session = ChargeSession(null)
        for (second in 0..20) session.read(tick(second, 250_000, plugged = false))
        for (second in 21..23) session.read(tick(second, 1_250_000))
        assertNull(session.update.watts)
        session.read(tick(24, 1_250_000, percent = 26))
        assertEquals(5.0, requireNotNull(session.update.watts), 0.001)
        assertEquals(1.0, requireNotNull(session.update.idleWatts), 0.001)
    }

    @Test
    fun `18 W PD tapers at 80 percent with a complete two minute window`() {
        val session = ChargeSession(300)
        for (second in 0..300) {
            val percent = 76 + second / 50
            val raw = if (percent < 80) 4_500_000L else 2_750_000L
            session.read(tick(second, raw, percent = percent))
        }
        assertEquals(EndReason.Completed, session.update.end)
        val analysis = ChargeAnalysis.measure(session.points, endPercent = 82, seconds = 300.0)
        assertEquals(18.0, requireNotNull(analysis.peak), 0.001)
        assertEquals(18.0, requireNotNull(analysis.sustained), 0.001)
        assertEquals(80, analysis.details.slowdownPercent)
        assertEquals((18 * 199 + 14.5 + 11 * 100) / 3600, requireNotNull(analysis.energy), 0.001)
        assertEquals(12.0, requireNotNull(analysis.details.percentPerTenMinutes), 0.001)
        assertFalse(analysis.details.slowdownWhileHot)
    }

    @Test
    fun `a brief dip recovers but power stays down after the battery heats`() {
        val session = ChargeSession(null)
        for (second in 0..260) {
            val raw = if (second in 20..60 || second >= 170) 3_250_000L else 6_500_000L
            val celsius = if (second < 165) 39.9 else 42.0
            session.read(tick(second, raw, percent = 48 + second / 10, temp = celsius))
        }
        val analysis = ChargeAnalysis.measure(session.points, 74, 260.0)
        assertEquals(65, analysis.details.slowdownPercent)
        assertEquals(42.0, requireNotNull(analysis.maxTemperature), 0.0)
        assertTrue(analysis.details.slowdownWhileHot)
        assertEquals((2 * 19.5 + 108 * 26 + 10 * 13) / 120, requireNotNull(analysis.sustained), 0.001)
        val short = ChargeAnalysis.measure(session.points.take(25), 50, 24.0)
        assertNull(short.sustained)
        assertNull(short.details.slowdownPercent)
    }

    @Test
    fun `a 30 second gauge is sampled each second and flagged as sparse`() {
        val session = ChargeSession(null)
        for (second in 0..180) session.read(tick(second, 1_000_000L + (second / 30) * 10_000, percent = 20 + second / 60))
        val analysis = ChargeAnalysis.measure(session.points, 23, 180.0)
        assertEquals(181, session.points.count { it.watts != null })
        assertEquals(30.0, requireNotNull(analysis.details.currentChangeSeconds), 0.0)
        assertEquals(4.24, requireNotNull(analysis.peak), 0.001)
        assertNull(analysis.sustained)
    }

    @Test
    fun `end conditions keep full unplugged timed and safety distinct`() {
        fun end(battery: BatteryReading, elapsed: Double = 0.0) =
            ChargeSession.endCondition(ChargePhase.Measuring, battery, elapsed, 300)
        assertNull(end(tick(0, 10_000, percent = 2, temp = 45.0).battery))
        assertEquals(EndReason.Safety, end(tick(0, 10_000, temp = 47.0).battery))
        assertEquals(EndReason.BatteryFull, end(tick(0, 10_000, percent = 100).battery))
        assertEquals(EndReason.BatteryFull, end(tick(0, 10_000, status = BatteryManager.BATTERY_STATUS_FULL).battery))
        assertEquals(EndReason.Unplugged, end(tick(0, 10_000, plugged = false).battery))
        assertEquals(EndReason.Completed, end(tick(0, 10_000).battery, 300.0))
        assertEquals(EndReason.Safety, ChargeSession.endCondition(ChargePhase.Waiting, tick(0, 0, temp = 47.0).battery, 0.0, null))
    }

    @Test
    fun `no gauge means no measurement and a lost gauge preserves observed readings`() {
        for (raw in listOf(null, 0L, Long.MIN_VALUE)) {
            val noGauge = ChargeSession(null)
            for (second in 0..7) noGauge.read(tick(second, raw))
            assertTrue(noGauge.update.cannotMeasure)
            assertNull(noGauge.update.watts)
        }
        val session = ChargeSession(null)
        for (second in 0..20) session.read(tick(second, 1_250_000L.takeIf { second < 5 }))
        assertFalse(session.update.cannotMeasure)
        assertEquals(EndReason.ChargeReadingLost, session.update.end)
        assertEquals(5, session.points.count { it.watts != null })
    }

    @Test
    fun `charge records survive history projection and export named battery columns`() {
        val session = ChargeSession(null)
        for (second in 0..5) session.read(tick(second, -4500, percent = 52))
        val original = session.record(1_000L, EndReason.User, null, "test", null)
        val store = RunStore(folder.root)
        store.save(original)
        val row = store.list().single()
        assertTrue(row.isCharge)
        assertNotNull(row.charge)
        assertEquals(18.0, requireNotNull(row.summary.peakWatts), 0.001)
        val saved = requireNotNull(store.load(row.id))
        assertEquals(original, saved)
        val csv = StringWriter().also { ReportCsv.write(it, saved) }.toString()
        assertEquals("t_s,battery_input_w,battery_current_a,battery_v,temp_battery_c,battery_percent", csv.lineSequence().first())
        assertTrue(csv.contains("0.000,18.000,4.500,4.000,25.000,52"))
    }

    private fun tick(
        second: Int,
        raw: Long?,
        plugged: Boolean = true,
        percent: Int = 25,
        temp: Double = 25.0,
        status: Int = if (plugged) BatteryManager.BATTERY_STATUS_CHARGING else BatteryManager.BATTERY_STATUS_DISCHARGING,
        counter: Long? = null,
    ) = ChargeTick(second.toDouble(), BatteryReading(
        currentRaw = raw,
        chargeCounterMicroAmpHours = counter,
        voltageMillivolts = 4_000,
        voltageAgeNanos = 0,
        temperatureCelsius = temp,
        levelPercent = percent,
        plugged = plugged,
        broadcasts = 1,
        plugType = if (plugged) BatteryManager.BATTERY_PLUGGED_AC else 0,
        status = status,
    ))
}
