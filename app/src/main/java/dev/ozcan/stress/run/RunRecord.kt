package dev.ozcan.stress.run

import kotlinx.serialization.Serializable

/** One stress run as the app keeps it: what ran, the verdict, and the curves behind it. */
@Serializable
data class RunRecord(
    val id: String,
    /** Wall-clock start, milliseconds since the epoch. */
    val startedAtMillis: Long,
    val mode: String,
    val workload: String,
    val plannedSeconds: Int?,
    val loadSeconds: Double,
    val stoppedEarly: Boolean,
    val summary: RunSummary,
    val series: RunSeries,
)

@Serializable
data class RunSummary(
    /** Battery output power at rest just before the load, in watts. */
    val idleWatts: Double?,
    /** Highest 5-second mean of battery output power during the load. */
    val peakWatts: Double?,
    val meanWatts: Double?,
    /** Mean over the last five minutes of the load (the whole load if shorter): what the phone can hold. */
    val sustainedWatts: Double?,
    /** Energy drawn from the battery during the load, in watt-hours. */
    val energyWattHours: Double?,
    val batteryStartPercent: Int?,
    val batteryEndPercent: Int?,
    /** How long a full battery would last at the sustained power, in hours. */
    val batteryLifeHours: Double?,
    val maxTemperatures: Map<String, Double>,
    val startTemperatures: Map<String, Double>,
    /**
     * For each cluster the load ran on: seconds into the load when it first
     * dropped below 97% of its top clock for three seconds; null if it never did.
     */
    val firstThrottleSeconds: Map<String, Double?>,
    /** Worst over best work rate across equal slices of the load (3DMark's stability), 0..1. */
    val cpuStability: Double?,
    val gpuStability: Double?,
    val computationErrors: Long,
    /** False when the charger was attached at any point: the power figures are then not valid. */
    val powerValid: Boolean,
)

/** Once-a-second curves. Every list has one entry per second of the load; null where unmeasured. */
@Serializable
data class RunSeries(
    val seconds: List<Double>,
    val watts: List<Double?>,
    val temperatures: Map<String, List<Double?>>,
    val batteryCelsius: List<Double?>,
    /** Clock of each cluster in MHz, keyed by cluster name. */
    val clocksMhz: Map<String, List<Double?>>,
    /** CPU and GPU work rates relative to their best second, 0..1. */
    val cpuRelative: List<Double?>,
    val gpuRelative: List<Double?>,
    val batteryPercent: List<Int?>,
)
