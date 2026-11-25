package dev.ozcan.stress.analysis

import dev.ozcan.stress.engine.SceneQuality
import dev.ozcan.stress.run.EndReason
import dev.ozcan.stress.run.RunRecord
import dev.ozcan.stress.run.StressMode
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.floor
import kotlin.math.roundToLong

/** Two runs side by side: their headline numbers and the change between them. Pure, so it is tested. */
object Comparison {

    /** A reduced run can mix different workloads, so its watts cannot rank a single quality. */
    fun isEligible(record: RunRecord): Boolean {
        if (record.endReason == EndReason.GpuFailed.name || record.drawnQuality != record.quality) return false
        val drawnScale = record.drawnScalePercent ?: return true // Older files only recorded the tier.
        val kind = StressMode.of(record.mode)?.scene ?: return false
        val quality = SceneQuality.entries.firstOrNull { it.name == record.quality } ?: return false
        return drawnScale == quality.scalePercent(kind).toDouble()
    }

    /**
     * A compared number, and which way is better: more power and work is what
     * a stress test is after; a cooler phone, a later throttle and fewer errors
     * are better hardware or cooling.
     */
    enum class Metric(val higherIsBetter: Boolean, val delta: Delta = Delta.Percent) {
        PeakPower(true),
        SustainedPower(true),
        MeanPower(true),
        HottestChip(false, Delta.Degrees),
        HottestBattery(false, Delta.Degrees),
        CpuRate(true),
        GpuRate(true),
        Fps(true),
        Stability(true),
        FirstThrottle(true, Delta.Seconds),
        Errors(false, Delta.Count),
    }

    /**
     * How the change from A to B is told: as a percent of A for power, work rates, frame rate and stability; as the
     * plain difference in its own unit for a temperature (a percent of a Celsius reading means nothing), a time and a
     * count.
     */
    enum class Delta { Percent, Degrees, Seconds, Count }

    data class Row(val metric: Metric, val a: Double?, val b: Double?) {
        /** B minus A in the measurement's own units, when both runs measured it. */
        val difference: Double? get() = if (a != null && b != null) b - a else null

        /** B against A, as a fraction of A: +0.12 is 12% more. Null when either is missing or A is zero. */
        val change: Double? get() = if (a != null && b != null && a != 0.0) (b - a) / a else null

        /**
         * B minus A as the screens show the two values, so the difference agrees with them: temperatures to a tenth
         * of a degree, times in the whole seconds the clock shows, counts whole. Null for a [Delta.Percent] metric
         * or when either run did not measure it.
         */
        val shownDifference: Double? get() {
            if (a == null || b == null) return null
            return when (metric.delta) {
                Delta.Percent -> null
                Delta.Degrees -> rounded(rounded(b, 1) - rounded(a, 1), 1)
                Delta.Seconds -> floor(b) - floor(a)
                Delta.Count -> (b.roundToLong() - a.roundToLong()).toDouble()
            }
        }
    }

    /** [value] rounded half up to [decimals] places, the way the screens print it. */
    fun rounded(value: Double, decimals: Int): Double =
        BigDecimal.valueOf(value).setScale(decimals, RoundingMode.HALF_UP).toDouble()

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
