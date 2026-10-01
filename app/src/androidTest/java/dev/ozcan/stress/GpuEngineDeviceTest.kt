package dev.ozcan.stress

import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ozcan.stress.engine.GpuBurner
import dev.ozcan.stress.engine.GpuCatalog
import dev.ozcan.stress.engine.GpuEngine
import dev.ozcan.stress.engine.GpuRequest
import dev.ozcan.stress.engine.GpuSnapshot
import dev.ozcan.stress.engine.GpuStartResult
import dev.ozcan.stress.engine.GpuState
import dev.ozcan.stress.engine.SceneKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The renderer on the real Adreno 720. It draws into an ImageReader instead of
 * the screen: a surface nobody sees, drained as fast as frames arrive.
 */
@RunWith(AndroidJUnit4::class)
class GpuEngineDeviceTest {

    private val engine = GpuEngine()
    private val drain = HandlerThread("image-drain").apply { start() }
    private val reader = ImageReader.newInstance(
        632, 1368, PixelFormat.RGBA_8888, 4,
        HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_CPU_READ_RARELY,
    ).apply { setOnImageAvailableListener({ r -> r.acquireLatestImage()?.close() }, Handler(drain.looper)) }

    @After
    fun tearDown() {
        engine.request(null)
        engine.detach()
        reader.close()
        drain.quitSafely()
    }

    private fun run(request: GpuRequest, millis: Long): GpuSnapshot {
        engine.attach(reader.surface)
        engine.request(request)
        assertEquals("$request did not start", GpuStartResult.Started, engine.lastStart)
        Thread.sleep(millis)
        return engine.snapshot().also { engine.request(null) }
    }

    private fun run(burner: GpuBurner?, millis: Long): GpuSnapshot = run(GpuRequest(burner), millis)

    private companion object {
        const val TAG = "GpuEngineDeviceTest"
    }

    @Test
    fun tableIsDescribed() {
        assertEquals(GpuCatalog.keys, engine.burners.map { it.key }.toSet())
    }

    @Test
    fun everySceneRuns() {
        for (kind in SceneKind.entries) {
            val s = run(GpuRequest(null, sceneKind = kind), 2_000)
            assertEquals("$kind state", GpuState.Running, s.state)
            assertTrue("$kind frames ${s.frames}", s.frames > 5)
        }
    }

    @Test
    fun previewAloneRuns() {
        val s = run(null, 1_000)
        assertEquals(GpuState.Running, s.state)
        assertTrue("no frames", s.frames > 10)
        assertEquals(0L, s.dispatches)
    }

    @Test
    fun everyBurnerRunsWithoutErrorsAndFillsItsFrame() {
        for (burner in engine.burners) {
            val s = run(burner, 3_000)
            val name = burner.code
            assertEquals("$name state", GpuState.Running, s.state)
            assertEquals("$name reported burner", burner, s.burner)
            assertTrue("$name frames ${s.frames}", s.frames > 5)
            assertTrue("$name did no work", s.work > 0)
            assertEquals("$name computed wrong results", 0L, s.errors)
            if (burner.verified) assertTrue("$name was never checked", s.checks > 0)
            // The frame is sized to the 40 ms target; after three seconds it must be close.
            val frameMillis = s.lastFrameNanos / 1e6
            val rate = if (s.gpuNanos > 0) s.work * 1e9 / s.gpuNanos else 0.0
            val frames = s.frames.coerceAtLeast(1)
            Log.i(
                TAG,
                "$name frame %.1f ms (burner %.1f, visible %.1f), %d dispatches, %.3g ${burner.unit.rateSymbol}".format(
                    frameMillis, s.burnerNanos / 1e6 / frames, s.visibleNanos / 1e6 / frames, s.dispatchesPerFrame, rate,
                ),
            )
            assertTrue("$name frame $frameMillis ms, ${s.dispatchesPerFrame} dispatches", frameMillis in 20.0..80.0)
        }
    }
}
