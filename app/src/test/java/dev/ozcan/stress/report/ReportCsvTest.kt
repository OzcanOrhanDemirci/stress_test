package dev.ozcan.stress.report

import dev.ozcan.stress.R
import dev.ozcan.stress.engine.SceneQuality
import dev.ozcan.stress.run.RunRecord
import dev.ozcan.stress.run.RunSeries
import dev.ozcan.stress.run.RunSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.StringWriter
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

class ReportCsvTest {

    private val record = RunRecord(
        id = "20261002-031500", startedAtMillis = 0, mode = "Cpu", workload = "fp32_l2", plannedSeconds = 60,
        loadSeconds = 60.4, stoppedEarly = false,
        summary = RunSummary(
            null, 8.5, null, 6.125, 0.1, 80, 79, null, mapOf("cpu" to 81.0), emptyMap(), emptyMap(), null, null, 0, true,
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
        appVersion = "0.3.0",
    )

    private fun export(record: RunRecord, language: String): String =
        StringWriter().also { ReportCsv.write(it, record, words(language)) }.toString()

    /** The header block, the blank line, and the table, without the byte order mark. */
    private fun parts(csv: String): Pair<List<String>, List<String>> {
        assertEquals(ReportCsv.BYTE_ORDER_MARK, csv.first().code)
        val lines = csv.drop(1).removeSuffix("\r\n").split("\r\n")
        val blank = lines.indexOf("")
        return lines.take(blank) to lines.drop(blank + 1)
    }

    @Test
    fun `turkish export uses semicolons, decimal commas and named columns`() {
        val (fields, table) = parts(export(record, "tr"))
        assertEquals("Alan;Değer", fields[0])
        assertTrue(fields.contains("Uygulama;Stress Test 0.3.0"))
        assertTrue(fields.contains("Test süresi (sn);60"))
        assertTrue(fields.contains("Planlanan süre (sn);60"))
        assertTrue(fields.contains("Tepe güç (W);8,50"))
        assertTrue(fields.contains("Sürekli güç (W);6,13"))
        assertTrue(fields.contains("En sıcak çip (°C);81,0"))
        assertTrue(fields.contains("Başlangıçta pil (%);80"))
        assertEquals(
            "Zaman (sn);Güç (W);CPU sıcaklığı (°C);Pil sıcaklığı (°C);Cortex-A715 ×3 saat hızı (MHz);" +
                "CPU performansı (%);Kare hızı (FPS);Pil seviyesi (%)",
            table[0],
        )
        assertEquals("0;8,50;80,3;33,0;2400;100,0;24,5;80", table[1])
        assertEquals("1;;81,0;33,1;2208;;;80", table[2])
        assertEquals(3, table.size)
    }

    @Test
    fun `english export uses commas and decimal points`() {
        val (fields, table) = parts(export(record, "en"))
        assertEquals("Field,Value", fields[0])
        assertTrue(fields.contains("Peak power (W),8.50"))
        assertEquals("0,8.50,80.3,33.0,2400,100.0,24.5,80", table[1])
        assertTrue(table[0].startsWith("Time (s),Power (W),CPU temperature (°C)"))
    }

    @Test
    fun `a scene export names requested and drawn quality and scale in the header`() {
        val (fields, _) = parts(export(record.copy(quality = "High", drawnQuality = "Medium", drawnScalePercent = 43.25), "en"))
        val quality = fields.filter { it.startsWith("Requested quality,") || it.startsWith("Drawn ") }
        assertEquals(3, quality.size)
        assertTrue(quality.last(), quality.last() == "Drawn scale (%),43.3")
    }

    @Test
    fun `a field holding the separator is quoted`() {
        val csv = export(record.copy(mode = "A, B \"x\""), "en")
        assertTrue(csv.contains("Test,\"A, B \"\"x\"\"\""))
    }

    @Test
    fun `power read on a charger is left empty with a note`() {
        val (fields, _) = parts(export(record.copy(summary = record.summary.copy(powerValid = false)), "tr"))
        assertTrue(fields.contains("Tepe güç (W);"))
        assertTrue(fields.any { it.startsWith("Not;") })
    }

    companion object {
        /** The export's words from the app's own string files, so the test reads what the user reads. */
        fun words(language: String): ReportCsv.Words {
            val strings = strings(if (language == "tr") "values-tr" else "values")
            val names = R.string::class.java.fields.associate { it.getInt(null) to it.name }
            val locale = Locale.forLanguageTag(language)
            val text = { id: Int, args: Array<out Any> ->
                val raw = strings[names[id]] ?: strings(language = "values")[names[id]] ?: error("no string ${names[id]}")
                if (args.isEmpty()) raw else String.format(locale, raw, *args)
            }
            return ReportCsv.Words(
                locale = locale,
                text = text,
                mode = { it.mode },
                temperature = { key ->
                    when (key) {
                        "cpu" -> text(R.string.temp_cpu, emptyArray())
                        "battery" -> text(R.string.temp_battery, emptyArray())
                        else -> key
                    }
                },
                endReason = { text(R.string.end_completed, emptyArray()) },
                quality = {
                    when (it) {
                        SceneQuality.Low -> R.string.quality_low
                        SceneQuality.Medium -> R.string.quality_medium
                        SceneQuality.High -> R.string.quality_high
                    }
                },
                plug = { R.string.charge_plug_ac },
            )
        }

        private fun strings(language: String): Map<String, String> {
            val dir = listOf(File("src/main/res/$language"), File("app/src/main/res/$language")).first { it.isDirectory }
            val factory = DocumentBuilderFactory.newInstance()
            return dir.listFiles { f -> f.name.startsWith("strings") }.orEmpty().flatMap { file ->
                val nodes = factory.newDocumentBuilder().parse(file).getElementsByTagName("string")
                (0 until nodes.length).map { i ->
                    val node = nodes.item(i)
                    node.attributes.getNamedItem("name").nodeValue to node.textContent.replace("\\'", "'").replace("\\n", "\n")
                }
            }.toMap()
        }
    }
}
