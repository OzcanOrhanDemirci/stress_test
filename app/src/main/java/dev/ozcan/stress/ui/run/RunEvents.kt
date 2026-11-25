package dev.ozcan.stress.ui.run

import dev.ozcan.stress.engine.GpuState
import dev.ozcan.stress.engine.SceneDraw
import dev.ozcan.stress.safety.SafetyCheck
import dev.ozcan.stress.ui.LiveView

/** What the run screen watches for its event log, read once per live update. */
internal data class RunObservation(
    val safety: SafetyCheck,
    val thermalStatus: Int,
    val gpuState: GpuState?,
    val sceneDraw: SceneDraw?,
    val errors: Long,
    val plugged: Boolean,
) {
    companion object {
        fun of(view: LiveView, safety: SafetyCheck, usesGpu: Boolean) = RunObservation(
            safety = safety,
            thermalStatus = view.thermalStatus,
            gpuState = view.gpu.state.takeIf { usesGpu },
            sceneDraw = view.gpu.sceneDraw,
            errors = view.errors + view.gpu.errors,
            plugged = view.plugged,
        )
    }
}

/** One change seen while the load runs; [second] counts from the load's start. */
internal sealed interface RunEvent {
    val second: Long

    data class Safety(override val second: Long, val check: SafetyCheck) : RunEvent
    data class Thermal(override val second: Long, val status: Int) : RunEvent
    data class Gpu(override val second: Long, val state: GpuState) : RunEvent
    data class Scene(override val second: Long, val draw: SceneDraw) : RunEvent
    data class Errors(override val second: Long, val total: Long) : RunEvent
    data class Plug(override val second: Long, val plugged: Boolean) : RunEvent
}

/**
 * The run's events so far, for the engineering sheet: only changes, so a
 * reading that holds still adds nothing. It watches what the screen already
 * shows; it decides nothing about the run.
 */
internal data class RunEvents(
    val events: List<RunEvent> = emptyList(),
    private val last: RunObservation? = null,
) {
    fun observe(second: Long, now: RunObservation): RunEvents {
        val before = last
        val added = buildList {
            if (before == null) {
                // The first look reports only what is not the quiet default.
                if (now.safety.level != OK_LEVEL) add(RunEvent.Safety(second, now.safety))
                if (now.thermalStatus > 0) add(RunEvent.Thermal(second, now.thermalStatus))
                now.gpuState?.let { add(RunEvent.Gpu(second, it)) }
                now.sceneDraw?.let { add(RunEvent.Scene(second, it)) }
                if (now.errors > 0) add(RunEvent.Errors(second, now.errors))
                if (now.plugged) add(RunEvent.Plug(second, true))
            } else {
                if (now.safety.level != before.safety.level || now.safety.reason != before.safety.reason) {
                    add(RunEvent.Safety(second, now.safety))
                }
                if (now.thermalStatus != before.thermalStatus) add(RunEvent.Thermal(second, now.thermalStatus))
                if (now.gpuState != null && now.gpuState != before.gpuState) add(RunEvent.Gpu(second, now.gpuState))
                if (now.sceneDraw != null && now.sceneDraw != before.sceneDraw) add(RunEvent.Scene(second, now.sceneDraw))
                if (now.errors > before.errors) add(RunEvent.Errors(second, now.errors))
                if (now.plugged != before.plugged) add(RunEvent.Plug(second, now.plugged))
            }
        }
        if (added.isEmpty() && before == now) return this
        return RunEvents((events + added).takeLast(MAX_EVENTS), now)
    }

    companion object {
        /** A long run with a flickering reading must not grow the sheet without end. */
        const val MAX_EVENTS = 200
        private val OK_LEVEL = SafetyCheck.OK.level
    }
}
