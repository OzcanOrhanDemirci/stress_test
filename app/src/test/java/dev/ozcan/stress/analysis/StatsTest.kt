package dev.ozcan.stress.analysis

import dev.ozcan.stress.TestSamples
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatsTest {

    private fun series(vararg values: Double, step: Double = 1.0) = values.mapIndexed { i, v -> Point(i * step, v) }

    @Test
    fun `max window mean finds the best stretch`() {
        val points = series(1.0, 1.0, 5.0, 5.0, 5.0, 1.0, 1.0)
        // A 2.1 s window holds three points 2 s apart (95% of it); the three 5s win.
        assertEquals(5.0, Stats.maxWindowMean(points, 2.1)!!, 1e-12)
    }

    @Test
    fun `a window shorter than asked does not count`() {
        val points = series(1.0, 1.0, 9.0)
        // Only the window starting at 0 spans enough time; the tail windows would let the 9 win alone.
        assertEquals(11.0 / 3, Stats.maxWindowMean(points, 2.1)!!, 1e-12)
        assertNull(Stats.maxWindowMean(series(1.0), 2.1))
        // Three points 2 s apart are not three quarters of a 3 s window.
        assertNull(Stats.maxWindowMean(points, 3.0))
    }

    @Test
    fun `slope of a line is exact`() {
        val points = (0..10).map { Point(it.toDouble(), 3.0 - 2.0 * it) }
        assertEquals(-2.0, Stats.slope(points)!!, 1e-12)
        assertNull(Stats.slope(listOf(Point(1.0, 1.0), Point(1.0, 2.0))))
    }

    @Test
    fun `median change interval ignores repeats`() {
        // Value changes at t = 0.3, 0.6, 0.9, 1.2 while sampled every 0.1 s.
        val points = (0..12).map { i -> Point(i / 10.0, (i / 3).toDouble()) }
        assertEquals(0.3, Stats.medianChangeInterval(points)!!, 1e-9)
        assertNull(Stats.medianChangeInterval(series(1.0, 1.0, 1.0)))
    }

    @Test
    fun `work rate is work per second per cpu and null across restarts`() {
        val a = TestSamples.snapshot(TestSamples.gemm, batches = 10)
        val b = TestSamples.snapshot(TestSamples.gemm, batches = 30)
        // 20 batches * 10 iterations * 1000 FLOP in 2 s.
        assertEquals(List(8) { 100_000.0 }, WorkRate.perCpu(a, b, 2.0))
        assertEquals(List(8) { null }, WorkRate.perCpu(b, a, 2.0))
        assertEquals(List(8) { null }, WorkRate.perCpu(a, TestSamples.snapshot(TestSamples.dry, batches = 30), 2.0))
    }
}
