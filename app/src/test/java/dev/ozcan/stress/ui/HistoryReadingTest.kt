package dev.ozcan.stress.ui

import dev.ozcan.stress.R
import dev.ozcan.stress.run.RunSummary
import dev.ozcan.stress.ui.history.HistoryReading
import dev.ozcan.stress.ui.history.historyPower
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryReadingTest {

    private val summary = RunSummary(
        idleWatts = null, peakWatts = 8.0, meanWatts = 7.0, sustainedWatts = null, energyWattHours = null,
        batteryStartPercent = null, batteryEndPercent = null, batteryLifeHours = null,
        maxTemperatures = emptyMap(), startTemperatures = emptyMap(), firstThrottleSeconds = emptyMap(),
        cpuStability = null, gpuStability = null, computationErrors = 0, powerValid = true,
    )

    @Test
    fun `peak power is the headline when it was measured`() {
        assertEquals(HistoryReading(R.string.result_peak, 8.0, "W"), historyPower(summary))
    }

    @Test
    fun `a short run shows its mean with the correct label`() {
        assertEquals(HistoryReading(R.string.result_mean, 7.0, "W"), historyPower(summary.copy(peakWatts = null)))
    }

    @Test
    fun `invalid and unmeasured power have no headline number`() {
        val missing = HistoryReading(R.string.result_peak, null, "W")
        assertEquals(missing, historyPower(summary.copy(powerValid = false)))
        assertEquals(missing, historyPower(summary.copy(peakWatts = null, meanWatts = null)))
    }
}
