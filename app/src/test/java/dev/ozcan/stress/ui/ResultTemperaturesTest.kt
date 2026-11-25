package dev.ozcan.stress.ui

import dev.ozcan.stress.run.RunSeries
import dev.ozcan.stress.run.RunSummary
import dev.ozcan.stress.ui.result.chipTemperatures
import dev.ozcan.stress.ui.result.hottestChip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResultTemperaturesTest {
    @Test
    fun findsThePeakInLegacyAndCurrentRecords() {
        val summary = RunSummary(
            idleWatts = null, peakWatts = null, meanWatts = null, sustainedWatts = null, energyWattHours = null,
            batteryStartPercent = null, batteryEndPercent = null, batteryLifeHours = null,
            maxTemperatures = mapOf("cpu" to 76.0, "A715" to 81.0, "battery" to 85.0),
            startTemperatures = emptyMap(), firstThrottleSeconds = emptyMap(),
            cpuStability = null, gpuStability = null, computationErrors = 0, powerValid = true,
        )

        assertEquals(81.0, summary.hottestChip())
        assertNull(summary.copy(maxTemperatures = mapOf("battery" to 35.0)).hottestChip())
    }

    @Test
    fun usesHottestChipEachSecondAndKeepsGaps() {
        val series = RunSeries(
            seconds = listOf(0.0, 1.0, 2.0, 3.0),
            temperatures = mapOf(
                "cpu" to listOf(40.0, 53.0, null),
                "GPU" to listOf(46.0, null, null, 61.0),
                "skin" to listOf(31.0, 32.0, 33.0, 34.0),
            ),
            batteryCelsius = listOf(26.0, 27.0, 28.0, 29.0),
        )

        assertEquals(listOf(46.0, 53.0, null, 61.0), series.chipTemperatures())
    }
}
