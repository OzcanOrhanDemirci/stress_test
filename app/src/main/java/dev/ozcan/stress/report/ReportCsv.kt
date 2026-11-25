package dev.ozcan.stress.report

import android.content.Context
import androidx.annotation.StringRes
import dev.ozcan.stress.R
import dev.ozcan.stress.charge.isCharge
import dev.ozcan.stress.engine.SceneQuality
import dev.ozcan.stress.run.RunAnalysis
import dev.ozcan.stress.run.RunRecord
import dev.ozcan.stress.run.RunSummary
import dev.ozcan.stress.settings.AppLanguages
import dev.ozcan.stress.ui.Labels
import dev.ozcan.stress.ui.charge.plugLabel
import java.io.File
import java.io.Writer
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A test's export for a spreadsheet, in the app's language: a block of "field, value" rows on top (what ran on which
 * phone and the headline results, like the PDF's first page), a blank line, then the once-a-second table with a
 * named column per series, units in the names.
 *
 * Separator and decimal mark follow the language's spreadsheet convention, because that is what a spreadsheet in
 * that language reads on a plain double-click: Turkish (and any decimal-comma language) gets ";" between fields and
 * "8,52"; English gets "," and "8.52". A comma file opened in a Turkish Excel lands in one column and reads "8.500" as
 * 8500. No digit grouping, so every number parses. UTF-8 with a byte order mark, so Excel shows Turkish letters.
 *
 * The lab's own sample CSV ([dev.ozcan.stress.lab.SampleCsv]) is a different, machine-readable format and does not change with this.
 */
object ReportCsv {

    /** The byte order mark's code point, as a number so the source file holds no stray mark. */
    const val BYTE_ORDER_MARK = 0xFEFF

    fun fileName(record: RunRecord): String = "stress-test-data-${record.id}.csv"

    /** The words of the export and the locale its numbers and dates follow. A seam so the format can be tested without Android. */
    class Words(
        val locale: Locale,
        private val text: (Int, Array<out Any>) -> String,
        /** The test's name as the screens show it, scene included. */
        val mode: (RunRecord) -> String,
        val temperature: (String) -> String,
        val endReason: (RunRecord) -> String,
        /** The string resource of a quality's name and of a plug type's name. */
        val quality: (SceneQuality) -> Int,
        val plug: (Int) -> Int,
    ) {
        fun get(@StringRes id: Int, vararg args: Any): String = text(id, args)

        val decimalComma: Boolean get() = DecimalFormatSymbols.getInstance(locale).decimalSeparator == ','
        val separator: Char get() = if (decimalComma) ';' else ','

        companion object {
            /** In the app's language, whatever the phone's. */
            fun of(context: Context): Words {
                val localized = AppLanguages.localized(context)
                return Words(
                    locale = localized.resources.configuration.locales[0],
                    text = { id, args -> if (args.isEmpty()) localized.getString(id) else localized.getString(id, *args) },
                    mode = { Labels.recordModeName(localized, it) },
                    temperature = { Labels.temperature(localized, it) },
                    endReason = { localized.getString(Labels.endReason(Labels.endReasonOf(it))) },
                    quality = Labels::quality,
                    plug = ::plugLabel,
                )
            }
        }
    }

    fun write(context: Context, record: RunRecord, dir: File): File {
        dir.mkdirs()
        val file = File(dir, fileName(record))
        file.bufferedWriter(Charsets.UTF_8).use { write(it, record, Words.of(context)) }
        return file
    }

    /** Writes the export, byte order mark first. Separate from files so it can be tested. */
    fun write(out: Writer, record: RunRecord, words: Words) {
        val csv = Csv(out, words)
        out.write(BYTE_ORDER_MARK)
        csv.row(words.get(R.string.csv_field), words.get(R.string.csv_value))
        for ((field, value) in fields(record, words, csv)) csv.row(field, value)
        out.write("\r\n")
        val columns = columns(record, words, csv)
        csv.row(columns.map { it.first })
        for (i in record.series.seconds.indices) csv.row(columns.map { it.second(i) })
    }

    private fun fields(record: RunRecord, words: Words, csv: Csv): List<Pair<String, String>> = buildList {
        val s = record.summary
        add(words.get(R.string.csv_app) to listOfNotNull(words.get(R.string.app_name), record.appVersion).joinToString(" "))
        record.device?.let { d ->
            add(words.get(R.string.csv_device) to d.title)
            d.soc?.let { add(words.get(R.string.device_soc) to it) }
            add(words.get(R.string.device_android) to "${d.androidVersion} (API ${d.sdk})")
        }
        add(words.get(R.string.csv_test) to words.mode(record))
        record.quality?.let { q -> SceneQuality.entries.firstOrNull { it.name == q } }?.let { requested ->
            add(words.get(R.string.report_requested_quality) to words.get(words.quality(requested)))
            val drawn = SceneQuality.entries.firstOrNull { it.name == record.drawnQuality }
            add(words.get(R.string.report_drawn_quality) to drawn?.let { words.get(words.quality(it)) }.orEmpty())
            add(words.get(R.string.csv_drawn_scale) to csv.number(record.drawnScalePercent, 1))
        }
        add(words.get(R.string.csv_date) to SimpleDateFormat("d MMMM yyyy HH:mm", words.locale).format(Date(record.startedAtMillis)))
        add(words.get(R.string.csv_duration) to csv.number(record.loadSeconds, 0))
        add(words.get(R.string.csv_planned) to (record.plannedSeconds?.toString() ?: words.get(R.string.charge_until_full).takeIf { record.isCharge }.orEmpty()))
        add(words.get(R.string.report_end) to words.endReason(record))
        if (record.isCharge) {
            record.charge?.plugType?.let { add(words.get(R.string.charge_source) to words.get(words.plug(it))) }
            add(words.get(R.string.csv_charge_peak) to csv.number(s.peakWatts, 2))
            add(words.get(R.string.csv_charge_sustained) to csv.number(s.sustainedWatts, 2))
            add(words.get(R.string.csv_charge_energy) to csv.number(s.energyWattHours, 2))
            add(words.get(R.string.csv_charge_rate) to csv.number(record.charge?.percentPerTenMinutes, 1))
            add(words.get(R.string.csv_charge_max_temp) to csv.number(s.maxTemperatures[RunAnalysis.BATTERY], 1))
        } else {
            record.safetyEnabled?.let { on ->
                add(words.get(R.string.settings_safety) to words.get(if (on) R.string.safety_on_short else R.string.safety_off_short))
            }
            // Power read while a charger fed the phone is not the phone's draw: left empty, with the reason as a note.
            val power = s.powerValid
            add(words.get(R.string.csv_peak) to csv.number(s.peakWatts.takeIf { power }, 2))
            add(words.get(R.string.csv_sustained) to csv.number(s.sustainedWatts.takeIf { power }, 2))
            add(words.get(R.string.csv_energy) to csv.number(s.energyWattHours.takeIf { power }, 2))
            add(words.get(R.string.csv_hottest) to csv.number(hottestChip(s), 1))
        }
        add(words.get(R.string.csv_battery_start) to s.batteryStartPercent?.toString().orEmpty())
        add(words.get(R.string.csv_battery_end) to s.batteryEndPercent?.toString().orEmpty())
        if (!record.isCharge) {
            add(words.get(R.string.csv_errors) to s.computationErrors.toString())
            if (!s.powerValid) add(words.get(R.string.csv_note) to words.get(R.string.insight_charging_title))
        }
    }

    private fun columns(record: RunRecord, words: Words, csv: Csv): List<Pair<String, (Int) -> String>> = buildList {
        val s = record.series
        add(words.get(R.string.csv_col_time) to { i -> csv.seconds(s.seconds.getOrNull(i)) })
        val battery = words.get(R.string.csv_col_temperature, words.temperature(RunAnalysis.BATTERY))
        if (record.isCharge) {
            add(words.get(R.string.csv_col_charge_power) to { i -> csv.number(s.watts.getOrNull(i), 2) })
            add(words.get(R.string.csv_col_charge_current) to { i -> csv.number(s.chargeCurrentAmps.getOrNull(i), 2) })
            add(words.get(R.string.csv_col_charge_voltage) to { i -> csv.number(s.batteryVolts.getOrNull(i), 3) })
            add(battery to { i -> csv.number(s.batteryCelsius.getOrNull(i), 1) })
            add(words.get(R.string.csv_col_battery) to { i -> s.batteryPercent.getOrNull(i)?.toString().orEmpty() })
            return@buildList
        }
        add(words.get(R.string.csv_col_power) to { i -> csv.number(s.watts.getOrNull(i), 2) })
        s.temperatures.forEach { (key, values) ->
            add(words.get(R.string.csv_col_temperature, words.temperature(key)) to { i -> csv.number(values.getOrNull(i), 1) })
        }
        add(battery to { i -> csv.number(s.batteryCelsius.getOrNull(i), 1) })
        s.clocksMhz.forEach { (name, values) ->
            add(words.get(R.string.csv_col_clock, name) to { i -> csv.number(values.getOrNull(i), 0) })
        }
        add(words.get(R.string.csv_col_cpu) to { i -> csv.number(s.cpuRelative.getOrNull(i)?.times(100), 1) })
        if (s.gpuRelative.any { it != null }) add(words.get(R.string.csv_col_gpu) to { i -> csv.number(s.gpuRelative.getOrNull(i)?.times(100), 1) })
        if (s.fps.isNotEmpty()) add(words.get(R.string.csv_col_fps) to { i -> csv.number(s.fps.getOrNull(i), 1) })
        add(words.get(R.string.csv_col_battery) to { i -> s.batteryPercent.getOrNull(i)?.toString().orEmpty() })
    }

    /** Rows, quoting, and numbers in the words' convention. */
    private class Csv(private val out: Writer, private val words: Words) {
        private val separator = words.separator

        fun row(vararg cells: String) = row(cells.asList())

        fun row(cells: List<String>) {
            out.write(cells.joinToString(separator.toString(), transform = ::quote))
            out.write("\r\n")
        }

        private fun quote(cell: String): String =
            if (cell.any { it == separator || it == '"' || it == '\n' || it == '\r' }) "\"" + cell.replace("\"", "\"\"") + "\"" else cell

        fun number(value: Double?, decimals: Int): String {
            val text = value?.takeIf { it.isFinite() }?.let { String.format(Locale.ROOT, "%.${decimals}f", it) } ?: return ""
            return if (words.decimalComma) text.replace('.', ',') else text
        }

        /** Whole seconds without a fraction; a sample off the second keeps one decimal. */
        fun seconds(value: Double?): String = if (value != null && value % 1.0 == 0.0) number(value, 0) else number(value, 1)
    }
}

/** The hottest of the chips the headline shows (CPU and GPU, by their current and older keys), in °C. */
internal fun hottestChip(summary: RunSummary): Double? =
    listOfNotNull(
        summary.maxTemperatures["cpu"], summary.maxTemperatures["gpu"],
        summary.maxTemperatures["A715"], summary.maxTemperatures["A510"], summary.maxTemperatures["GPU"],
    ).maxOrNull()
