package dev.ozcan.stress.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ChartAndDurationTest {

    @Test
    fun `ticks are round numbers inside the range`() {
        assertEquals(listOf(0.0, 2.5, 5.0, 7.5, 10.0), niceTicks(0.0, 10.0))
        assertEquals(listOf(40.0, 50.0, 60.0, 70.0), niceTicks(35.2, 71.9))
        assertEquals(listOf(1000.0, 1500.0, 2000.0, 2500.0), niceTicks(900.0, 2630.0))
    }

    @Test
    fun `a flat or broken range gives a single tick`() {
        assertEquals(listOf(5.0), niceTicks(5.0, 5.0))
        assertEquals(1, niceTicks(Double.NaN, 1.0).size)
    }

    @Test
    fun `durations read like a clock`() {
        assertEquals("0:00", Durations.clock(0.4))
        assertEquals("2:05", Durations.clock(125.9))
        assertEquals("1:02:05", Durations.clock(3725.0))
        assertEquals("2 sa 31 dk", Durations.hours(2.52))
        assertEquals("45 dk", Durations.hours(0.75))
    }
}
