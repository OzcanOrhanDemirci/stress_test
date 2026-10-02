package dev.ozcan.stress.report

import dev.ozcan.stress.run.RunAnalysis
import dev.ozcan.stress.run.RunRecord
import java.io.File
import java.io.Writer
import java.util.Locale

/**
 * A run's once-a-second curves as CSV, for a spreadsheet: one row a second,
 * a column per series. Decimal points and comma separators whatever the
 * phone's language, so every spreadsheet reads it the same.
 */
object ReportCsv {

    fun fileName(record: RunRecord): String = "stress-test-data-${record.id}.csv"

    fun write(record: RunRecord, dir: File): File {
        dir.mkdirs()
        val file = File(dir, fileName(record))
        file.bufferedWriter().use { write(it, record) }
        return file
    }

    /** Writes the table, its columns named in English whatever the language. Separate from files so it can be tested. */
    fun write(out: Writer, record: RunRecord) {
        val s = record.series
        val columns = buildList<Pair<String, (Int) -> String>> {
            add("t_s" to { i -> number(s.seconds.getOrNull(i)) })
            add("power_w" to { i -> number(s.watts.getOrNull(i)) })
            s.temperatures.forEach { (key, values) -> add("temp_${slug(key)}_c" to { i -> number(values.getOrNull(i)) }) }
            add("temp_${RunAnalysis.BATTERY}_c" to { i -> number(s.batteryCelsius.getOrNull(i)) })
            s.clocksMhz.forEach { (name, values) -> add("clock_${slug(name)}_mhz" to { i -> number(values.getOrNull(i)) }) }
            add("cpu_relative" to { i -> number(s.cpuRelative.getOrNull(i)) })
            if (s.gpuRelative.any { it != null }) add("gpu_relative" to { i -> number(s.gpuRelative.getOrNull(i)) })
            if (s.fps.isNotEmpty()) add("fps" to { i -> number(s.fps.getOrNull(i)) })
            add("battery_percent" to { i -> s.batteryPercent.getOrNull(i)?.toString().orEmpty() })
        }
        out.appendLine(columns.joinToString(",") { it.first })
        for (i in s.seconds.indices) {
            out.appendLine(columns.joinToString(",") { it.second(i) })
        }
    }

    private fun number(value: Double?): String = value?.takeIf { it.isFinite() }?.let { String.format(Locale.ROOT, "%.3f", it) }.orEmpty()

    /** "Cortex-A715 ×3" -> "cortex_a715_x3". */
    fun slug(text: String): String =
        text.lowercase(Locale.ROOT).replace("×", "x").replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "x" }
}
