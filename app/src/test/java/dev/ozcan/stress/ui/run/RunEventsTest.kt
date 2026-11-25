package dev.ozcan.stress.ui.run

import dev.ozcan.stress.engine.GpuState
import dev.ozcan.stress.engine.SceneDraw
import dev.ozcan.stress.engine.SceneQuality
import dev.ozcan.stress.safety.SafetyCheck
import dev.ozcan.stress.safety.SafetyLevel
import dev.ozcan.stress.safety.SafetyReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class RunEventsTest {
    private val quiet = RunObservation(SafetyCheck.OK, 0, GpuState.Running, null, 0, plugged = false)

    @Test
    fun firstLookReportsOnlyWhatIsNotQuiet() {
        val events = RunEvents().observe(0, quiet).events
        assertEquals(listOf<RunEvent>(RunEvent.Gpu(0, GpuState.Running)), events)
    }

    @Test
    fun aReadingThatHoldsStillAddsNothing() {
        val once = RunEvents().observe(0, quiet)
        assertSame(once, once.observe(1, quiet))
    }

    @Test
    fun changesBecomeEventsInOrder() {
        val warm = SafetyCheck(SafetyLevel.Warn, SafetyReason.ChipHot, 106.0)
        val draw = SceneDraw(SceneQuality.Medium, 7_500)
        val events = RunEvents()
            .observe(0, quiet)
            .observe(4, quiet.copy(sceneDraw = draw))
            .observe(30, quiet.copy(sceneDraw = draw, safety = warm, thermalStatus = 2))
            .observe(31, quiet.copy(sceneDraw = draw, safety = warm, thermalStatus = 2, errors = 3))
            .observe(40, quiet.copy(sceneDraw = draw, errors = 3, plugged = true, gpuState = GpuState.Failed))
            .events
        assertEquals(
            listOf(
                RunEvent.Gpu(0, GpuState.Running),
                RunEvent.Scene(4, draw),
                RunEvent.Safety(30, warm),
                RunEvent.Thermal(30, 2),
                RunEvent.Errors(31, 3),
                RunEvent.Safety(40, SafetyCheck.OK),
                RunEvent.Thermal(40, 0),
                RunEvent.Gpu(40, GpuState.Failed),
                RunEvent.Plug(40, true),
            ),
            events,
        )
    }

    @Test
    fun keepsOnlyTheLatestEvents() {
        var events = RunEvents().observe(0, quiet)
        for (second in 1..(RunEvents.MAX_EVENTS + 10)) {
            events = events.observe(second.toLong(), quiet.copy(plugged = second % 2 == 1))
        }
        assertEquals(RunEvents.MAX_EVENTS, events.events.size)
        assertEquals((RunEvents.MAX_EVENTS + 10).toLong(), events.events.last().second)
    }
}
