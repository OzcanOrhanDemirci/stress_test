package dev.ozcan.stress.engine

import android.view.Surface
import java.util.concurrent.Executors

/** One GPU burner as the native table describes it. */
data class GpuBurner(val index: Int, val key: String, val code: String, val unit: WorkUnit, val verified: Boolean) {
    companion object {
        /** Parses one line of [NativeBridge.gpuBurnerTable]: `key|code|unit|verified`. */
        fun parse(index: Int, line: String): GpuBurner {
            val parts = line.split('|')
            require(parts.size == 4) { "Malformed GPU burner line '$line'" }
            return GpuBurner(index, parts[0], parts[1], WorkUnit.fromNative(parts[2]), parts[3] == "1")
        }
    }
}

enum class GpuState(val code: Int) {
    Idle(0),
    Running(1),
    DeviceLost(2),
    Failed(3);

    companion object {
        fun fromCode(code: Int): GpuState = entries.firstOrNull { it.code == code } ?: Failed
    }
}

/** Outcome of starting the renderer; codes mirror `GpuLoad::StartResult`. */
enum class GpuStartResult(val code: Int) {
    Started(0),
    AlreadyRunning(1),
    InvalidBurner(2),
    SetupFailed(3);

    companion object {
        fun fromCode(code: Int): GpuStartResult = entries.firstOrNull { it.code == code } ?: SetupFailed
    }
}

/**
 * How hard a cinematic scene works: its resolution, as a share of the
 * screen's in each direction ([scalePercent]), and the native side's
 * sampling (SceneRenderer::Quality). [Medium] is the scene as it was tuned
 * on the Honor 400 (Snapdragon 7 Gen 3, a mid-range phone): 38-40 ms a frame
 * at 45 % (docs/OLCUMLER.md, 2026-10-02). [Low] draws fewer pixels and
 * samples for entry-level GPUs (20-25 ms there); [High] draws more of both
 * for the fastest phones and runs at 3-7 frames a second on a mid-range one
 * (150-300 ms). The forest is drawn from triangles, so its High is the
 * screen's own resolution; the ray-marched scenes trace two rays a pixel.
 */
enum class SceneQuality(val code: Int, private val scale: Int, private val forestScale: Int) {
    Low(0, 30, 30),
    Medium(1, 45, 45),
    High(2, 65, 100);

    /** The share of the screen's resolution, in percent, [kind] is drawn at. */
    fun scalePercent(kind: SceneKind): Int = if (kind == SceneKind.Forest) forestScale else scale

    companion object {
        fun fromCode(code: Int): SceneQuality = entries.firstOrNull { it.code == code } ?: Medium
    }
}

/**
 * What the GPU should do: a burner (null: the visible pass alone), the GPU time
 * to fill per frame, and the visible pass: a [scene] ([sceneKind]) rendered at
 * [sceneScalePercent] of the screen's resolution and [quality], or a cheap preview ring.
 */
data class GpuRequest(
    val burner: GpuBurner?,
    val targetFrameMillis: Int = DEFAULT_TARGET_FRAME_MILLIS,
    val scene: Boolean = true,
    val sceneScalePercent: Int = DEFAULT_SCENE_SCALE_PERCENT,
    val sceneKind: SceneKind = SceneKind.Pool,
    val quality: SceneQuality = SceneQuality.Medium,
) {
    companion object {
        /**
         * The cinematic scene's resolution at Medium quality, as a share of the
         * screen's in each direction; temporal accumulation and a Catmull-Rom
         * upscale make up the rest. 45 % runs at ~49 ms a frame, ~20 fps on the
         * Honor 400 (docs/OLCUMLER.md).
         */
        val DEFAULT_SCENE_SCALE_PERCENT = SceneQuality.Medium.scalePercent(SceneKind.Pool)

        /**
         * Long enough that the frame's fixed costs are small next to the burner,
         * short enough (with three frames in flight) that the screen still moves.
         */
        const val DEFAULT_TARGET_FRAME_MILLIS = 40
    }
}

data class GpuSnapshot(
    val state: GpuState,
    /** The burner of the request the renderer is running, null for the visible pass alone or when stopped. */
    val burner: GpuBurner?,
    val frames: Long,
    val dispatches: Long,
    /** Work completed in [GpuBurner.unit]. */
    val work: Long,
    val gpuNanos: Long,
    val lastFrameNanos: Long,
    val dispatchesPerFrame: Long,
    val errors: Long,
    val checks: Long,
    val width: Int,
    val height: Int,
    /** Of [gpuNanos]: time in burner work, and time drawing the visible pass (the scene). */
    val burnerNanos: Long = 0,
    val visibleNanos: Long = 0,
    /** True when the times come from GPU timestamps; false when the CPU timed whole frames (no timestamps). */
    val timed: Boolean = true,
) {
    val isRunning: Boolean get() = state == GpuState.Running

    companion object {
        val IDLE = GpuSnapshot(GpuState.Idle, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
    }
}

/**
 * Kotlin face of the native renderer. The renderer needs a surface to draw on,
 * which the screen provides and takes away ([attach], [detach]), and a
 * [GpuRequest], which the load logic sets. It runs exactly when both exist.
 * Starting blocks while Vulkan is set up, so calls belong off the main thread
 * except [detach], which must finish before the surface goes away.
 */
class GpuEngine {

    val burners: List<GpuBurner> = NativeBridge.gpuBurnerTable().mapIndexed(GpuBurner::parse)

    private val stride = NativeBridge.gpuSnapshotStride().also {
        check(it == SNAPSHOT_STRIDE) { "Native GPU snapshot stride $it, expected $SNAPSHOT_STRIDE" }
    }
    private val buffer = LongArray(stride)

    private var surface: Surface? = null
    private var request: GpuRequest? = null

    // Written under the engine lock, read by snapshot() without it: a snapshot
    // must never wait for a start in progress.
    @Volatile private var running: GpuRequest? = null

    /** The result of the last attempt to start the renderer. */
    @Volatile var lastStart: GpuStartResult? = null
        private set

    val hasSurface: Boolean @Synchronized get() = surface != null

    /** Serialises the calls made on behalf of the main thread, which must not block on Vulkan setup. */
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "stress-gpu-control") }

    fun attachAsync(target: Surface) = worker.execute { attach(target) }

    fun requestAsync(wanted: GpuRequest?) = worker.execute { request(wanted) }

    fun burner(key: String): GpuBurner = burners.first { it.key == key }

    @Synchronized
    fun attach(target: Surface) {
        // Attach requests are queued; the surface may be gone by the time one runs.
        if (target == surface || !target.isValid) return
        stopRenderer()
        surface = target
        reconcile()
    }

    @Synchronized
    fun detach() {
        surface = null
        reconcile()
    }

    /** Sets what should run; null stops the renderer. */
    @Synchronized
    fun request(wanted: GpuRequest?) {
        request = wanted
        reconcile()
    }

    private fun reconcile() {
        val target = surface
        val wanted = if (target != null && target.isValid) request else null
        if (wanted == running) return
        stopRenderer()
        if (wanted != null && target != null) {
            val code = NativeBridge.gpuStart(
                target,
                wanted.burner?.index ?: -1,
                wanted.targetFrameMillis,
                wanted.scene,
                wanted.sceneScalePercent,
                wanted.sceneKind.code,
                wanted.quality.code,
            )
            val result = GpuStartResult.fromCode(code)
            lastStart = result
            if (result == GpuStartResult.Started) running = wanted
        }
    }

    private fun stopRenderer() {
        if (running != null) NativeBridge.gpuStop()
        running = null
    }

    fun snapshot(): GpuSnapshot = synchronized(buffer) {
        NativeBridge.gpuSnapshot(buffer)
        GpuSnapshot(
            state = GpuState.fromCode(buffer[0].toInt()),
            burner = running?.burner,
            frames = buffer[1],
            dispatches = buffer[2],
            work = buffer[3],
            gpuNanos = buffer[4],
            lastFrameNanos = buffer[5],
            dispatchesPerFrame = buffer[6],
            errors = buffer[7],
            checks = buffer[8],
            width = buffer[9].toInt(),
            height = buffer[10].toInt(),
            burnerNanos = buffer[11],
            visibleNanos = buffer[12],
            timed = buffer[13] != 0L,
        )
    }

    companion object {
        /** Must match `GpuLoad::kSnapshotStride`. */
        const val SNAPSHOT_STRIDE = 14
    }
}
