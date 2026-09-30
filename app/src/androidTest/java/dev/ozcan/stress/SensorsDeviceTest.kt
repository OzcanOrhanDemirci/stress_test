package dev.ozcan.stress

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ozcan.stress.engine.NativeBridge
import dev.ozcan.stress.telemetry.SysfsLayout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SensorsDeviceTest {

    private val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir

    @After
    fun close() = NativeBridge.sensorsClose()

    @Test
    fun nativeReaderParsesAndRereadsFiles() {
        val busy = File(dir, "busy").apply { writeText("302334 1000061\n") }
        val temp = File(dir, "temp").apply { writeText("-273000\n") }
        val text = File(dir, "text").apply { writeText("abc\n") }
        val missing = File(dir, "missing")

        val readable = NativeBridge.sensorsOpen(arrayOf(busy.path, temp.path, text.path, missing.path))
        assertEquals(listOf(true, true, false, false), readable.toList())

        val values = LongArray(8)
        NativeBridge.sensorsRead(values)
        val gone = Long.MIN_VALUE
        assertEquals(listOf(302334L, 1000061L, -273000L, gone, gone, gone, gone, gone), values.toList())

        // The same descriptor must see new contents: pread from offset 0.
        busy.writeText("5 10\n")
        NativeBridge.sensorsRead(values)
        assertEquals(listOf(5L, 10L), values.take(2))
    }

    @Test
    fun honor400LayoutIsDiscoverable() {
        val layout = SysfsLayout.discover()
        assertEquals(listOf(0, 4, 7), layout.clusters.map { it.policy })
        assertEquals(listOf(listOf(0, 1, 2, 3), listOf(4, 5, 6), listOf(7)), layout.clusters.map { it.cpus })
    }
}
