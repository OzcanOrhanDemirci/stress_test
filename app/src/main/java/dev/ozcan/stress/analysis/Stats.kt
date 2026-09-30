package dev.ozcan.stress.analysis

object Stats {

    fun mean(values: List<Double>): Double? = if (values.isEmpty()) null else values.sum() / values.size

    fun meanOf(points: List<Point>): Double? = mean(points.map { it.value })

    /** Points with `from <= seconds < until`. */
    fun window(points: List<Point>, from: Double, until: Double): List<Point> =
        points.filter { it.seconds >= from && it.seconds < until }

    /**
     * Highest mean over any window of [windowSeconds]. Windows are anchored
     * on each point and must span at least three quarters of the requested
     * length (a 5 s window of 1 Hz points spans 4 s), so a sparse tail cannot
     * win on a couple of points.
     */
    fun maxWindowMean(points: List<Point>, windowSeconds: Double): Double? {
        require(windowSeconds > 0) { "Window must be positive, was $windowSeconds" }
        var best: Double? = null
        var end = 0
        var sum = 0.0
        for (start in points.indices) {
            while (end < points.size && points[end].seconds < points[start].seconds + windowSeconds) {
                sum += points[end].value
                end++
            }
            val count = end - start
            val span = points[end - 1].seconds - points[start].seconds
            if (count > 0 && span >= windowSeconds * 0.75 - 1e-9) {
                val m = sum / count
                if (best == null || m > best) best = m
            }
            sum -= points[start].value
        }
        return best
    }

    /** Least-squares slope (value per second); null with fewer than two distinct times. */
    fun slope(points: List<Point>): Double? {
        if (points.size < 2) return null
        val meanT = points.sumOf { it.seconds } / points.size
        val meanV = points.sumOf { it.value } / points.size
        var covariance = 0.0
        var variance = 0.0
        for (p in points) {
            val dt = p.seconds - meanT
            covariance += dt * (p.value - meanV)
            variance += dt * dt
        }
        return if (variance == 0.0) null else covariance / variance
    }

    /**
     * How often a reported value actually changes: the median time between
     * consecutive changes in seconds. A gauge polled faster than it updates
     * repeats itself, and this is how that shows.
     */
    fun medianChangeInterval(points: List<Point>): Double? {
        val changes = points.zipWithNext().filter { (a, b) -> a.value != b.value }.map { it.second.seconds }
        val intervals = changes.zipWithNext { a, b -> b - a }.sorted()
        return if (intervals.isEmpty()) null else intervals[intervals.size / 2]
    }
}
