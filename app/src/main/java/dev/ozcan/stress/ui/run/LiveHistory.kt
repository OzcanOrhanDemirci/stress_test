package dev.ozcan.stress.ui.run

internal data class LiveReading(
    val second: Long,
    val watts: Double?,
    val chipCelsius: Double?,
    val batteryCelsius: Double? = null,
)

/** The last two minutes of load readings. Keep nulls so a missing reading breaks the line. */
internal data class LiveHistory(
    val readings: List<LiveReading> = emptyList(),
    val peakWatts: Double? = null,
) {
    fun append(reading: LiveReading): LiveHistory {
        if (readings.lastOrNull()?.second?.let { reading.second <= it } == true) return this
        return LiveHistory(
            readings.dropWhile { it.second <= reading.second - 120 } + reading,
            reading.watts?.let { maxOf(peakWatts ?: it, it) } ?: peakWatts,
        )
    }
}
