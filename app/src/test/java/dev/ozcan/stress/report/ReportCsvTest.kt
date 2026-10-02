package dev.ozcan.stress.report

import dev.ozcan.stress.run.RunRecord
import dev.ozcan.stress.run.RunSeries
import dev.ozcan.stress.run.RunSummary
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.StringWriter
import java.util.Locale

class ReportCsvTest {

    private val saved = Locale.getDefault()

    @Before
    fun turkish() = Locale.setDefault(Locale.forLanguageTag("tr-TR"))

    @After
    fun restore() = Locale.setDefault(saved)

    private val record = RunRecord(
        id = "20261002-031500", startedAtMillis = 0, mode = "Cinematic", workload = "fp32_l2+scene", plannedSeconds = 60,
        loadSeconds = 2.0, stoppedEarly = false,
        summary = RunSummary(
            null, null, null, null, null, null, null, null, emptyMap(), emptyMap(), emptyMap(), null, null, 0, true,
        ),
        series = RunSeries(
            seconds = listOf(0.0, 1.0),
            watts = listOf(8.5, null),
            temperatures = mapOf("cpu" to listOf(80.25, 81.0)),
            batteryCelsius = listOf(33.0, 33.1),
            clocksMhz = mapOf("Cortex-A715 ×3" to listOf(2400.0, 2208.0)),
            cpuRelative = listOf(1.0, null),
            gpuRelative = listOf(null, null),
            batteryPercent = listOf(80, 80),
            fps = listOf(24.5, null),
        ),
    )

    @Test
    fun `one row a second, english column names, decimal points whatever the language`() {
        val out = StringWriter()
        ReportCsv.write(out, record)
        val lines = out.toString().trim().lines()
        assertEquals(
            "t_s,power_w,temp_cpu_c,temp_battery_c,clock_cortex_a715_x3_mhz,cpu_relative,fps,battery_percent",
            lines[0],
        )
        assertEquals("0.000,8.500,80.250,33.000,2400.000,1.000,24.500,80", lines[1])
        assertEquals("1.000,,81.000,33.100,2208.000,,,80", lines[2])
        assertEquals(3, lines.size)
    }

    @Test
    fun `slugs are plain ascii`() {
        assertEquals("cortex_a510_x4", ReportCsv.slug("Cortex-A510 ×4"))
        assertEquals("cpu_4_6", ReportCsv.slug("CPU 4-6"))
        assertEquals("x", ReportCsv.slug("—"))
    }
}
