package dev.ozcan.stress.run

import dev.ozcan.stress.device.DeviceInfo
import kotlinx.serialization.Serializable

/** Why a run ended. */
enum class EndReason {
    /** The planned duration ran out. */
    Completed,
    /** The user stopped it. */
    User,
    /** Device safety stopped it ([RunRecord.safetyReason]). */
    Safety,
    /** The GPU failed or was reset by its driver. */
    GpuFailed,
    /** The app left the screen, which takes the load's cores and surface away. */
    Interrupted,
}

/**
 * One stress run as the app keeps it: what ran, the verdict, and the curves
 * behind it. Fields added after the first version have defaults, so older
 * runs still load.
 */
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
    val device: DeviceInfo? = null,
    /** The scene's quality (SceneQuality's name) for cinematic runs. */
    val quality: String? = null,
    /** Whether device safety watched the run. */
    val safetyEnabled: Boolean? = null,
    /** EndReason's name. */
    val endReason: String? = null,
    /** SafetyReason's name and its reading, when device safety ended the run. */
    val safetyReason: String? = null,
    val safetyValue: Double? = null,
    /** The app's version that measured it. */
    val appVersion: String? = null,
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
    /** Mean CPU work rate over the load, in [cpuUnit] per second (all CPUs together). */
    val cpuMeanRate: Double? = null,
    /** WorkUnit's name of the CPU kernel. */
    val cpuUnit: String? = null,
    /** CPU work rate of the first and the last minute (or tenth of the load when shorter). */
    val cpuStartRate: Double? = null,
    val cpuEndRate: Double? = null,
    val gpuMeanRate: Double? = null,
    val gpuUnit: String? = null,
    val meanFps: Double? = null,
    /** Lowest one-second frame rate after the first ten seconds. */
    val minFps: Double? = null,
    /** Highest Android thermal status seen (PowerManager.THERMAL_STATUS_*). */
    val maxThermalStatus: Int? = null,
    /** True when the watts come from the charge counter's slope: the phone has no usable instantaneous current. */
    val powerFromCounter: Boolean = false,
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
    /** Frames drawn each second, when the GPU drew. */
    val fps: List<Double?> = emptyList(),
)
