package dev.ozcan.stress.lab

import kotlinx.serialization.Serializable

/** What one lab run measured. Written as JSON next to the raw samples (CSV) of the run. */
@Serializable
data class LabResult(
    /** The workload in LabLoad's text form. */
    val workload: String,
    /** Position of this run in the session (0-based) and the session's length. */
    val runIndex: Int,
    val runCount: Int,
    /** Hottest zone of each group when the run began, in °C. */
    val startTemperatures: Map<String, Double>,
    /** False when the CPUs had not cooled to the session's limit before the run had to start. */
    val cooledInTime: Boolean,
    val nice: Int,
    val batchMillis: Int,
    val idleSeconds: Int,
    val loadSeconds: Int,
    val startResult: String,
    /** Unit and sign of CURRENT_NOW inferred from the idle phase. */
    val ampsPerUnit: Double?,
    val dischargeSign: Int?,
    val pluggedDuringRun: Boolean,
    val batteryStartPercent: Int?,
    val batteryEndPercent: Int?,
    val idle: PhaseStats,
    val load: PhaseStats,
    /** Load power above the idle baseline, in watts. */
    val loadAboveIdleWatts: Double?,
    val loadFirst30sWatts: Double?,
    val loadLast30sWatts: Double?,
    val loadMax5sWatts: Double?,
    val cpus: List<CpuResult>,
    val clusters: List<ClusterResult>,
    val gpu: GpuResult?,
    val maxTemperatures: Map<String, Double>,
    val cadence: Cadence,
    /** CPU and GPU computation errors together. */
    val computationErrors: Long,
)

@Serializable
data class PhaseStats(
    val samples: Int,
    val meanWatts: Double?,
    val meanDischargeAmps: Double?,
    val meanVolts: Double?,
    /** Mean current implied by the falling charge counter; checks meanDischargeAmps from another register. */
    val chargeCounterAmps: Double?,
    val batteryCelsiusStart: Double?,
    val batteryCelsiusEnd: Double?,
)

@Serializable
data class CpuResult(
    val cpu: Int,
    val kernel: String?,
    val unit: String?,
    val meanRate: Double?,
    val first10sRate: Double?,
    val last10sRate: Double?,
    val errors: Long,
    val pinned: Boolean,
    val ranOnCpu: Int,
    val batches: Long,
    /** Batches that ended on another CPU; see WorkerState.misplacedBatches. */
    val misplacedBatches: Long,
)

@Serializable
data class ClusterResult(
    val policy: Int,
    val cpus: List<Int>,
    val maxMhz: Double,
    val meanMhz: Double?,
    val last30sMeanMhz: Double?,
)

/** How often each gauge value really changed, in seconds (median interval between changes). */
@Serializable
data class Cadence(
    val sampleIntervalSeconds: Double?,
    val currentChangeSeconds: Double?,
    val chargeCounterChangeSeconds: Double?,
    val voltageChangeSeconds: Double?,
    val batteryBroadcasts: Long,
)

@Serializable
data class GpuResult(
    val burner: String?,
    val unit: String?,
    val meanRate: Double?,
    val first10sRate: Double?,
    val last10sRate: Double?,
    val framesPerSecond: Double?,
    /** Mean GPU time per frame from timestamp queries, in milliseconds. */
    val meanFrameMillis: Double?,
    val dispatchesPerFrame: Long,
    /** Mean of the kgsl busy counter over the load phase, 0..1. */
    val busyFraction: Double?,
    val errors: Long,
    val checks: Long,
    val finalState: String,
)
