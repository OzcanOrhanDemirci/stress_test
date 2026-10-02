package dev.ozcan.stress.analysis

import dev.ozcan.stress.run.RunRecord

/** Two runs side by side: their headline numbers and the change between them. Pure, so it is tested. */
object Comparison {

    /**
     * A compared number, and which way is better: more power and work is what
     * a stress test is after; a cooler phone, a later throttle and fewer errors
     * are better hardware or cooling.
     */
    enum class Metric(val higherIsBetter: Boolean) {
        PeakPower(true),
        SustainedPower(true),
        MeanPower(true),
        HottestChip(false),
        HottestBattery(false),
        CpuRate(true),
        GpuRate(true),
        Fps(true),
        Stability(true),
        FirstThrottle(true),
        Errors(false),
    }

    data class Row(val metric: Metric, val a: Double?, val b: Double?) {
        /** B against A, as a fraction of A: +0.12 is 12% more. Null when either is missing or A is zero. */
        val change: Double? get() = if (a != null && b != null && a != 0.0) (b - a) / a else null
    }

    /** The rows worth showing: those where at least one run has a value. */
    fun rows(a: RunRecord, b: RunRecord): List<Row> =
        Metric.entries.map { Row(it, value(a, it), value(b, it)) }.filter { it.a != null || it.b != null }

    fun value(record: RunRecord, metric: Metric): Double? {
        val s = record.summary
        val power = s.powerValid
        return when (metric) {
            Metric.PeakPower -> s.peakWatts.takeIf { power }
            Metric.SustainedPower -> s.sustainedWatts.takeIf { power }
            Metric.MeanPower -> s.meanWatts.takeIf { power }
            Metric.HottestChip -> CHIP_KEYS.mapNotNull { s.maxTemperatures[it] }.maxOrNull()
            Metric.HottestBattery -> BATTERY_KEYS.firstNotNullOfOrNull { s.maxTemperatures[it] }
            Metric.CpuRate -> s.cpuMeanRate
            Metric.GpuRate -> s.gpuMeanRate
            Metric.Fps -> s.meanFps
            Metric.Stability -> s.cpuStability ?: s.gpuStability
            Metric.FirstThrottle -> s.firstThrottleSeconds.values.filterNotNull().minOrNull()
            Metric.Errors -> s.computationErrors.toDouble()
        }
    }

    /** The hotter of the CPU and the GPU each second; runs of the first version keep other names. */
    fun chipSeries(record: RunRecord): List<Double?> {
        val curves = CHIP_KEYS.mapNotNull { record.series.temperatures[it] }
        return record.series.seconds.indices.map { i -> curves.mapNotNull { it.getOrNull(i) }.maxOrNull() }
    }

    /**
     * Puts two once-a-second curves on one time axis, the longer run's: the
     * shorter curve ends in nulls.
     */
    fun align(
        secondsA: List<Double>,
        a: List<Double?>,
        secondsB: List<Double>,
        b: List<Double?>,
    ): Triple<List<Double>, List<Double?>, List<Double?>> {
        val seconds = if (secondsA.size >= secondsB.size) secondsA else secondsB
        fun pad(values: List<Double?>) = List(seconds.size) { values.getOrNull(it) }
        return Triple(seconds, pad(a), pad(b))
    }

    private val CHIP_KEYS = listOf("cpu", "gpu", "A715", "A510", "GPU")
    private val BATTERY_KEYS = listOf("battery", "Pil")
}
