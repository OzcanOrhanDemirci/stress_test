package dev.ozcan.stress.run

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RunStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    /** A run as version 0.1.0 stored it (2026-10-01): Honor-specific temperature keys, no device, no reason. */
    private val firstVersion = """
        {"id":"20261001-052012","startedAtMillis":1790821212000,"mode":"CinematicWhite","workload":"fp32_l2+white",
         "plannedSeconds":300,"loadSeconds":63.2,"stoppedEarly":true,
         "summary":{"idleWatts":null,"peakWatts":null,"meanWatts":null,"sustainedWatts":null,"energyWattHours":null,
          "batteryStartPercent":76,"batteryEndPercent":76,"batteryLifeHours":null,
          "maxTemperatures":{"A715":85.6,"A510":85.6,"GPU":81.6,"DDR":72.8,"Pil":33.0},
          "startTemperatures":{"A715":47.7,"A510":48.1,"GPU":37.2,"DDR":35.8,"Pil":31.0},
          "firstThrottleSeconds":{"A510 ×4":12.3,"A715 ×3":20.1,"A715 prime":null},
          "cpuStability":0.81,"gpuStability":null,"computationErrors":0,"powerValid":false},
         "series":{"seconds":[0.0,1.0],"watts":[null,null],
          "temperatures":{"A715":[47.7,60.2],"A510":[48.1,59.0],"GPU":[37.2,45.0],"DDR":[35.8,40.1]},
          "batteryCelsius":[31.0,31.0],"clocksMhz":{"A510 ×4":[1804.0,1804.0],"A715 ×3":[2400.0,2400.0],"A715 prime":[2630.0,2630.0]},
          "cpuRelative":[1.0,null],"gpuRelative":[null,null],"batteryPercent":[76,76]}}
    """.trimIndent()

    @Test
    fun `runs stored by the first version still load`() {
        val dir = tmp.newFolder("runs")
        File(dir, "20261001-052012.json").writeText(firstVersion)
        val store = RunStore(dir)
        val record = store.list().single()
        assertEquals("CinematicWhite", record.mode)
        assertEquals(StressMode.CinematicWhite, StressMode.of(record.mode))
        assertEquals(85.6, record.summary.maxTemperatures.getValue("A715"), 0.0)
        assertNull(record.device)
        assertNull(record.endReason)
        assertTrue(record.series.fps.isEmpty())
        assertEquals(record, store.load("20261001-052012"))
    }

    @Test
    fun `a run round-trips and is deleted`() {
        val dir = tmp.newFolder("runs")
        val store = RunStore(dir)
        val record = store.run {
            File(dir, "a.json").writeText(firstVersion)
            list().single()
        }.copy(id = "b", endReason = EndReason.Safety.name, safetyReason = "BatteryHot", safetyValue = 47.1)
        store.save(record)
        assertEquals(record, store.load("b"))
        store.delete("b")
        assertNull(store.load("b"))
    }
}
