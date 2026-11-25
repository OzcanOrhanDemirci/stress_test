package dev.ozcan.stress.ui

import dev.ozcan.stress.ui.run.LiveHistory
import dev.ozcan.stress.ui.run.LiveReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LiveHistoryTest {
    @Test
    fun keepsOneReadingPerSecondAndPreservesMissingPower() {
        val first = LiveHistory().append(LiveReading(0, null, 31.0))
        assertSame(first, first.append(LiveReading(0, 9.0, 32.0)))
        val history = first.append(LiveReading(1, 4.0, 33.0)).append(LiveReading(2, null, 34.0))
        assertEquals(listOf(null, 4.0, null), history.readings.map { it.watts })
        assertEquals(4.0, history.peakWatts)
    }

    @Test
    fun trimsByElapsedTimeAndKeepsThePeakFromEarlierInTheRun() {
        val history = LiveHistory()
            .append(LiveReading(0, 9.0, null))
            .append(LiveReading(1, 3.0, null))
            .append(LiveReading(120, 4.0, null))

        assertEquals(listOf(1L, 120L), history.readings.map { it.second })
        assertEquals(9.0, history.peakWatts)
        assertSame(history, history.append(LiveReading(119, 12.0, null)))
    }
}
