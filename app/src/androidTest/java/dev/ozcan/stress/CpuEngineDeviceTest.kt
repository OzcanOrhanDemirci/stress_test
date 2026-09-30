package dev.ozcan.stress

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ozcan.stress.engine.CoreAssignment
import dev.ozcan.stress.engine.CpuEngine
import dev.ozcan.stress.engine.StartResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CpuEngineDeviceTest {

    private val engine = CpuEngine()

    @After
    fun stop() = engine.stop()

    @Test
    fun everyCoreBurnsPinnedAndWithoutErrors() {
        val kernel = engine.kernel("fp32_gemm")
        assertEquals(StartResult.Started, engine.start(CoreAssignment.uniform(kernel)))
        // At rest core_ctl keeps CPUs 5 and 7 paused; the load must win them back.
        val deadline = System.nanoTime() + 3_000_000_000L
        while (!engine.snapshot().workers.all { it.isPinned } && System.nanoTime() < deadline) Thread.sleep(50)
        Thread.sleep(500)
        val snapshot = engine.snapshot()
        snapshot.workers.forEach { w ->
            assertEquals(kernel, w.kernel)
            assertTrue("cpu ${w.cpu} not pinned", w.isPinned)
            assertTrue("cpu ${w.cpu} not running", w.isRunning)
            assertEquals("cpu ${w.cpu} ran elsewhere", w.cpu, w.lastCpu)
            assertTrue("cpu ${w.cpu} did no batches", w.batches > 0)
            assertEquals("cpu ${w.cpu} computed wrong results", 0L, w.errors)
        }
        engine.stop()
        assertTrue(engine.snapshot().isIdle)
    }

    @Test
    fun mixedAssignmentsRunTheirOwnKernels() {
        val assignment = CoreAssignment.parse("0-3:memcopy,4-6:bf16_mmla,7:mixed", engine.kernels)
        assertEquals(StartResult.Started, engine.start(assignment))
        Thread.sleep(1_000)
        val snapshot = engine.snapshot()
        assertEquals(assignment.kernelPerCpu, snapshot.workers.map { it.kernel })
        assertEquals(0L, snapshot.errors)
        assertTrue(snapshot.workers.all { it.batches > 0 })
    }

    @Test
    fun aSecondStartIsRefusedUntilStopped() {
        val assignment = CoreAssignment.uniform(engine.kernel("dry"))
        assertEquals(StartResult.Started, engine.start(assignment))
        assertEquals(StartResult.AlreadyRunning, engine.start(assignment))
        engine.stop()
        assertEquals(StartResult.Started, engine.start(assignment))
    }

    @Test
    fun anIdleAssignmentIsRejected() {
        assertEquals(StartResult.InvalidKernel, engine.start(CoreAssignment.uniform(null)))
    }
}
