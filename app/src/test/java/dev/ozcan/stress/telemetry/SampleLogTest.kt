package dev.ozcan.stress.telemetry

import dev.ozcan.stress.TestSamples.sample
import org.junit.Assert.assertEquals
import org.junit.Test

class SampleLogTest {

    /** Sample times in tenths of a second, rounded: the builder turns 4.1 s into 4_099_999_999 ns. */
    private fun times(samples: List<Sample>) = samples.map { Math.round(it.timeNanos / 1e8) }

    @Test
    fun `recent samples stay at full rate, older ones thin to one a second`() {
        val log = SampleLog(recentCount = 10, archiveCount = 100)
        for (i in 0 until 50) log.add(sample(i / 10.0))
        val all = log.between(0)
        // The last ten at 0.1 s steps (4.0 s .. 4.9 s); before that one per second.
        assertEquals(listOf(0L, 10L, 20L, 30L) + (40L..49L).toList(), times(all))
        assertEquals((45L..49L).toList(), times(log.recent(5)))
    }

    @Test
    fun `the archive keeps only its newest samples`() {
        val log = SampleLog(recentCount = 10, archiveCount = 2)
        for (i in 0 until 50) log.add(sample(i / 10.0))
        assertEquals(listOf(20L, 30L), times(log.between(0)).take(2))
    }

    @Test
    fun `between selects a half-open window in time order`() {
        val log = SampleLog(recentCount = 10, archiveCount = 100)
        for (i in 0 until 50) log.add(sample(i / 10.0))
        assertEquals(listOf(10L, 20L, 30L, 40L, 41L), times(log.between(1_000_000_000L, 4_200_000_000L)))
    }
}
