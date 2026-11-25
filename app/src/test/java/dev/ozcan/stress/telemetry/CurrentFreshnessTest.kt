package dev.ozcan.stress.telemetry

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentFreshnessTest {
    @Test
    fun `Honor 400 one hertz mA qualifies, goes stale, and needs two changes to recover`() {
        val monitor = CurrentFreshness()
        fun at(second: Long, raw: Long?, onBattery: Boolean = true) = monitor.update(raw, onBattery, second * 1_000_000_000L)
        assertFalse(at(0, -500))
        assertFalse(at(1, -501))
        assertTrue(at(2, -502))
        assertTrue(at(11, -502))
        assertFalse(at(12, -502))
        assertFalse(at(13, -2000))
        assertFalse(at(14, -2005))
        assertTrue(at(15, -2002))
        assertFalse(at(16, -2002, onBattery = false))
        assertFalse(at(17, -2003))
        assertFalse(at(18, null))
    }

    @Test
    fun `a counter refreshing at ten thirty or sixty seconds never looks instantaneous`() {
        for (interval in listOf(10, 30, 60)) {
            val monitor = CurrentFreshness()
            for (second in 0..180) {
                assertFalse(monitor.update(-400_000L - second / interval * 1000L, true, second * 1_000_000_000L))
            }
        }
    }
}
