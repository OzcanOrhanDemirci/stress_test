package dev.ozcan.stress.engine

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** How a workload runs, apart from what it runs. */
data class LoadSettings(
    val nice: Int = 0,
    val batchMillis: Int = CpuEngine.DEFAULT_BATCH_MILLIS,
    val scene: Boolean = true,
    val sceneScalePercent: Int = GpuRequest.DEFAULT_SCENE_SCALE_PERCENT,
)

/** Starts and stops both halves of a [Workload], the same way for lab sessions and for the app's runs. */
class LoadDriver(private val cpu: CpuEngine, private val gpu: GpuEngine) {

    /**
     * Starts every part of [workload]. Returns [STARTED], or what failed; the
     * parts that did start keep running until [stop], which the caller owes
     * either way.
     */
    suspend fun start(workload: Workload, settings: LoadSettings): String {
        val problems = mutableListOf<String>()
        workload.gpu?.let { part ->
            // The renderer draws on the screen's surface, which the screen provides.
            val deadline = SystemClock.elapsedRealtime() + SURFACE_TIMEOUT_MILLIS
            while (!gpu.hasSurface && SystemClock.elapsedRealtime() < deadline) delay(100)
            val request = GpuRequest(
                part.burner,
                scene = part.scene ?: settings.scene,
                sceneScalePercent = part.sceneScalePercent ?: settings.sceneScalePercent,
                sceneKind = part.kind,
            )
            withContext(Dispatchers.Default) { gpu.request(request) }
            val started = gpu.lastStart
            if (!gpu.hasSurface) problems += "gpu=NoSurface" else if (started != GpuStartResult.Started) problems += "gpu=$started"
        }
        workload.cpu?.let { assignment ->
            val started = withContext(Dispatchers.Default) { cpu.start(assignment, settings.nice, settings.batchMillis) }
            if (started != StartResult.Started) problems += "cpu=${started.name}"
        }
        return if (problems.isEmpty()) STARTED else problems.joinToString(" ")
    }

    /**
     * Stops both engines after a short grace. A sample is stamped when it
     * starts but reads the engines a moment later, so a sample stamped just
     * before the caller closed its measuring window must not see the load
     * already stopped. Runs even when the caller is being cancelled.
     */
    suspend fun stop() = withContext(NonCancellable + Dispatchers.Default) {
        delay(STOP_GRACE_MILLIS)
        cpu.stop()
        gpu.request(null)
    }

    companion object {
        const val STARTED = "Started"
        private const val SURFACE_TIMEOUT_MILLIS = 10_000L

        /** Three periods of the 10 Hz sampler. */
        private const val STOP_GRACE_MILLIS = 300L
    }
}
