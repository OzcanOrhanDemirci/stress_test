package dev.ozcan.stress.run

import dev.ozcan.stress.TestSamples
import dev.ozcan.stress.TestSamples.sample
import dev.ozcan.stress.engine.CpuKernel
import dev.ozcan.stress.engine.GpuBurner
import dev.ozcan.stress.engine.GpuState
import dev.ozcan.stress.engine.LoadControl
import dev.ozcan.stress.engine.LoadSettings
import dev.ozcan.stress.engine.SceneKind
import dev.ozcan.stress.engine.WorkUnit
import dev.ozcan.stress.engine.Workload
import dev.ozcan.stress.safety.SafetyReason
import dev.ozcan.stress.telemetry.Sample
import dev.ozcan.stress.telemetry.SampleLog
import dev.ozcan.stress.telemetry.SampleSource
import dev.ozcan.stress.telemetry.SysfsLayout
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The controller's phases and stops, on virtual time with stand-in telemetry and loads. */
@OptIn(ExperimentalCoroutinesApi::class)
class RunControllerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** A sample source the test feeds by hand. */
    private class StubSource : SampleSource {
        override val latest = MutableStateFlow<Sample?>(null)
        override val log = SampleLog()
    }

    /** A load that starts on demand, with the outcome the test chooses. */
    private class StubDriver(private val outcome: String = LoadControl.STARTED) : LoadControl {
        var started = 0
            private set
        var stopped = 0
            private set
        var workload: Workload? = null
            private set

        override suspend fun start(workload: Workload, settings: LoadSettings): String {
            started++
            this.workload = workload
            return outcome
        }

        override suspend fun stop() {
            stopped++
        }
    }

    private fun store(): RunStore = RunStore(tmp.newFolder("runs"))

    // The kernels the modes under test name: C8 for the CPU, the FP32 burner
    // for the GPU. The analysis itself reads the kernels the samples carry.
    private val kernels = listOf(
        CpuKernel(0, "fp32_l2", "C8", WorkUnit.Flop, 128.0, 262_144),
    )
    private val burners = listOf(
        GpuBurner(0, "gpu_fp32", "G1", WorkUnit.Flop, verified = true),
    )

    private fun TestScope.controller(source: SampleSource, driver: LoadControl, store: RunStore) = RunController(
        source,
        driver,
        kernels,
        burners,
        TestSamples.CPU_COUNT,
        SysfsLayout(TestSamples.clusters, emptyList(), null),
        store,
        device = { null },
        // Virtual time, in the nanoseconds the controller and the samples speak.
        now = { testScheduler.currentTime * 1_000_000 },
        baselineSeconds = 2,
        samplerLostAfterNanos = 1_000_000_000,
    )

    /** Feeds fresh samples every 100 ms until cancelled. */
    private fun CoroutineScope.emit(source: StubSource, at: (Double) -> Sample): Job = launch {
        var t = 0.0
        while (true) {
            val s = at(t)
            source.latest.value = s
            source.log.add(s)
            t += 0.1
            delay(100)
        }
    }

    @Test
    fun `a full run analyses and stores its record`() = runTest {
        val source = StubSource()
        val driver = StubDriver()
        val runs = store()
        val controller = controller(source, driver, runs)
        val samples = emit(source) { sample(it) }

        controller.run(StressMode.Cpu, StressDuration.One, RunOptions())
        samples.cancel()

        val record = (controller.state.value as RunState.Finished).record
        assertEquals(EndReason.Completed.name, record.endReason)
        assertEquals(1, driver.started)
        assertEquals(1, driver.stopped)
        assertTrue(record.summary.powerValid)
        assertEquals(record.id, runs.load(record.id)?.id)
    }

    @Test
    fun `device safety blocks a start that is already too hot`() = runTest {
        val source = StubSource()
        val driver = StubDriver()
        val controller = controller(source, driver, store())
        source.latest.value = sample(0.0, batteryCelsius = 45.0)

        controller.run(StressMode.Cpu, StressDuration.One, RunOptions())

        assertTrue(controller.state.value is RunState.Failed.Blocked)
        assertEquals(0, driver.started)
    }

    @Test
    fun `a stop during the baseline starts no load`() = runTest {
        val source = StubSource()
        val driver = StubDriver()
        val controller = controller(source, driver, store())
        source.latest.value = sample(0.0)
        launch {
            delay(500)
            controller.requestStop(EndReason.User)
        }

        controller.run(StressMode.Cpu, StressDuration.One, RunOptions())

        assertTrue(controller.state.value is RunState.Failed.StoppedBeforeLoad)
        assertEquals(0, driver.started)
        assertEquals(0, driver.stopped)
    }

    @Test
    fun `a load that fails to start fails the run and stops`() = runTest {
        val source = StubSource()
        val driver = StubDriver("cpu=Parked")
        val controller = controller(source, driver, store())
        val samples = emit(source) { sample(it) }

        controller.run(StressMode.Cpu, StressDuration.One, RunOptions())
        samples.cancel()

        assertEquals("cpu=Parked", (controller.state.value as RunState.Failed.LoadFailed).detail)
        assertEquals(1, driver.stopped)
    }

    @Test
    fun `a run that overheats ends with device safety's finding`() = runTest {
        val source = StubSource()
        val controller = controller(source, StubDriver(), store())
        val samples = emit(source) { t -> sample(t, batteryCelsius = if (t >= 3.0) 48.0 else 30.0) }

        controller.run(StressMode.Cpu, StressDuration.One, RunOptions())
        samples.cancel()

        val record = (controller.state.value as RunState.Finished).record
        assertEquals(EndReason.Safety.name, record.endReason)
        assertEquals(SafetyReason.BatteryHot.name, record.safetyReason)
        assertEquals(48.0, record.safetyValue!!, 1e-9)
    }

    @Test
    fun `a gpu that dies mid run ends the run as a gpu failure`() = runTest {
        val source = StubSource()
        val controller = controller(source, StubDriver(), store())
        val samples = emit(source) { t ->
            val gpu = if (t < 3.0) TestSamples.gpu(TestSamples.gpuFp32, frames = (t * 25).toLong())
            else TestSamples.gpu(TestSamples.gpuFp32, frames = 100).copy(state = GpuState.DeviceLost)
            sample(t, gpu = gpu)
        }

        controller.run(StressMode.Gpu, StressDuration.One, RunOptions())
        samples.cancel()

        val record = (controller.state.value as RunState.Finished).record
        assertEquals(EndReason.GpuFailed.name, record.endReason)
    }

    @Test
    fun `a run whose samples stop refreshing ends as sampler lost`() = runTest {
        val source = StubSource()
        val controller = controller(source, StubDriver(), store())
        val samples = emit(source) { sample(it) }
        // Flow through the 2 s baseline and one second of load, then freeze.
        launch {
            delay(3_000)
            samples.cancel()
        }

        controller.run(StressMode.Cpu, StressDuration.One, RunOptions())

        val record = (controller.state.value as RunState.Finished).record
        assertEquals(EndReason.SamplerLost.name, record.endReason)
        assertEquals(null, record.safetyReason)
    }

    @Test
    fun `the scene burner option fills the cinematic recipe`() = runTest {
        val source = StubSource()
        val driver = StubDriver()
        val controller = controller(source, driver, store())
        val samples = emit(source) { sample(it) }

        controller.run(StressMode.Cinematic, StressDuration.One, RunOptions(sceneBurner = true))
        samples.cancel()

        val gpu = driver.workload!!.gpu!!
        assertEquals("gpu_fp32", gpu.burner?.key)
        assertEquals(true, gpu.scene)
        assertEquals(SceneKind.Pool, gpu.kind)
    }

    @Test
    fun `without the option the cinematic scene burns nothing`() = runTest {
        val source = StubSource()
        val driver = StubDriver()
        val controller = controller(source, driver, store())
        val samples = emit(source) { sample(it) }

        controller.run(StressMode.Cinematic, StressDuration.One, RunOptions())
        samples.cancel()

        assertEquals(null, driver.workload!!.gpu!!.burner)
    }

    @Test
    fun `a record that cannot be stored still finishes on the screen`() = runTest {
        val source = StubSource()
        val controller = controller(source, StubDriver(), RunStore(File(tmp.newFile("blocked"), "runs")))
        val samples = emit(source) { sample(it) }

        controller.run(StressMode.Cpu, StressDuration.One, RunOptions())
        samples.cancel()

        assertTrue(controller.state.value is RunState.Finished)
    }
}
