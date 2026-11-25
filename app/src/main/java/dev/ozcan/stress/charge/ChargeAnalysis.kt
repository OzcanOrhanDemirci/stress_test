package dev.ozcan.stress.charge

import dev.ozcan.stress.analysis.Point
import dev.ozcan.stress.analysis.Power
import dev.ozcan.stress.run.RunSeries
import kotlinx.serialization.Serializable

@Serializable
data class ChargeDetails(
    val plugType: Int? = null,
    val slowdownPercent: Int? = null,
    val percentPerTenMinutes: Double? = null,
    val slowdownWhileHot: Boolean = false,
    val currentChangeSeconds: Double? = null,
)

internal data class ChargePoint(
    val seconds: Double,
    val watts: Double?,
    val amps: Double?,
    val volts: Double?,
    val percent: Int?,
    val temperature: Double?,
    val raw: Long?,
    val plugType: Int?,
)

internal enum class ChargeSpeed { UsbPort, Standard, Fast, HighPower }

internal data class ChargeAnalysis(
    val peak: Double?,
    val sustained: Double?,
    val energy: Double?,
    val maxTemperature: Double?,
    val details: ChargeDetails,
) {
    companion object {
        private const val SUSTAINED_SECONDS = 120.0
        private const val SLOWDOWN_FRACTION = 0.70
        private const val SLOWDOWN_SECONDS = 30.0
        private const val MAX_GAP_SECONDS = 3.0

        fun speed(peak: Double): ChargeSpeed = when {
            peak < 4.5 -> ChargeSpeed.UsbPort
            peak < 9.0 -> ChargeSpeed.Standard
            peak <= 18.0 -> ChargeSpeed.Fast
            else -> ChargeSpeed.HighPower
        }

        fun measure(points: List<ChargePoint>, endPercent: Int?, seconds: Double): ChargeAnalysis {
            val valid = points.mapNotNull { p -> p.watts?.let { Point(p.seconds, it) } }
            val peak = valid.maxByOrNull { it.value }
            val spans = spans(valid)
            val peakTime = peak?.seconds ?: 0.0
            val afterPeak = spans.map { span -> span.dropWhile { it.seconds < peakTime } }
            val sustained = afterPeak.mapNotNull(::bestWindow).maxOrNull()
            // A permanent drop belongs to the last continuous segment. A
            // later recovery disqualifies an earlier dip.
            val threshold = (peak?.value ?: 0.0) * SLOWDOWN_FRACTION
            val tail = afterPeak.lastOrNull().orEmpty()
            val lastAbove = tail.indexOfLast { it.value >= threshold }
            val low = tail.getOrNull(lastAbove + 1)?.takeIf {
                lastAbove >= 0 && tail.last().seconds - it.seconds >= SLOWDOWN_SECONDS
            }
            val slowdown = low?.let { first -> points.firstOrNull { it.seconds == first.seconds } }
            val hot = low != null && points.any {
                it.seconds in maxOf(peakTime, low.seconds - SLOWDOWN_SECONDS)..low.seconds && (it.temperature ?: 0.0) > 40.0
            }
            val start = points.firstOrNull()?.percent
            val rate = if (start != null && endPercent != null && seconds > 0 && endPercent >= start) {
                (endPercent - start) * 600.0 / seconds
            } else null
            val energies = spans.mapNotNull(Power::joules)
            return ChargeAnalysis(
                peak = peak?.value,
                sustained = sustained,
                energy = energies.takeIf { it.isNotEmpty() }?.sum()?.div(3600.0),
                maxTemperature = points.mapNotNull { it.temperature }.maxOrNull(),
                details = ChargeDetails(points.firstOrNull()?.plugType, slowdown?.percent, rate, hot, cadence(points)),
            )
        }

        private fun spans(points: List<Point>): List<List<Point>> {
            val spans = mutableListOf<MutableList<Point>>()
            for (point in points) {
                val last = spans.lastOrNull()
                if (last == null || point.seconds - last.last().seconds > MAX_GAP_SECONDS) {
                    spans += mutableListOf(point)
                } else {
                    last += point
                }
            }
            return spans
        }

        /** Compare every complete 120 s window starting at a reading, interpolating its end. */
        private fun bestWindow(points: List<Point>): Double? {
            if (points.size < 2 || points.last().seconds - points.first().seconds < SUSTAINED_SECONDS) return null
            val energy = DoubleArray(points.size)
            for (i in 1..points.lastIndex) {
                val (a, b) = points[i - 1] to points[i]
                energy[i] = energy[i - 1] + (a.value + b.value) * (b.seconds - a.seconds) / 2
            }
            var best = 0.0
            var end = 1
            for ((i, start) in points.withIndex()) {
                val until = start.seconds + SUSTAINED_SECONDS
                if (until > points.last().seconds) break
                while (points[end].seconds < until) end++
                val (a, b) = points[end - 1] to points[end]
                val dt = until - a.seconds
                val final = a.value + (b.value - a.value) * dt / (b.seconds - a.seconds)
                val joules = energy[end - 1] + (a.value + final) * dt / 2 - energy[i]
                best = maxOf(best, joules / SUSTAINED_SECONDS)
            }
            return best
        }

        private fun cadence(points: List<ChargePoint>): Double? {
            var previous: Long? = null
            var changedAt: Double? = null
            val intervals = mutableListOf<Double>()
            for (point in points) {
                val raw = point.raw
                if (raw == null) {
                    previous = null
                    changedAt = null
                } else if (raw != previous) {
                    // The first value may have been present before we started.
                    if (previous != null) {
                        changedAt?.let { intervals += point.seconds - it }
                        changedAt = point.seconds
                    }
                    previous = raw
                }
            }
            // Jitter around one second is expected; a two-second cadence is not.
            return intervals.sorted().takeIf { it.size >= 2 }?.let { it[it.size / 2] }?.takeIf { it >= 1.5 }
        }

        /** A mean at each measured battery percentage, shared by history comparison and results. */
        fun percentCurve(series: RunSeries): List<Point> =
            series.batteryPercent.zip(series.watts)
                .mapNotNull { (level, watts) -> if (level != null && watts != null) Point(level.toDouble(), watts) else null }
                .groupBy(Point::seconds).toSortedMap().map { (level, points) -> Point(level, points.map(Point::value).average()) }
    }
}
