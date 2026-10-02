package dev.ozcan.stress.report

import dev.ozcan.stress.ui.upper
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.res.ResourcesCompat
import dev.ozcan.stress.R
import dev.ozcan.stress.analysis.Insight
import dev.ozcan.stress.engine.SceneQuality
import dev.ozcan.stress.run.RunAnalysis
import dev.ozcan.stress.run.RunRecord
import dev.ozcan.stress.ui.Dates
import dev.ozcan.stress.ui.Format
import dev.ozcan.stress.ui.InsightText
import dev.ozcan.stress.ui.Labels
import dev.ozcan.stress.ui.components.niceStep
import dev.ozcan.stress.ui.components.niceTicks
import dev.ozcan.stress.ui.components.tickLabel
import dev.ozcan.stress.ui.components.timeStep
import dev.ozcan.stress.ui.result.hoursText
import java.io.File

/**
 * A run's report as a two-page A4 PDF: what ran on which phone, the headline
 * numbers, the findings, and the curves. Light, for printing; the same words
 * as the screens ([Labels], [InsightText]).
 */
object ReportPdf {

    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 36f

    private val INK = Color.rgb(0x16, 0x18, 0x1D)
    private val DIM = Color.rgb(0x5F, 0x66, 0x72)
    private val FAINT = Color.rgb(0x9A, 0xA0, 0xAA)
    private val RULE = Color.rgb(0xE3, 0xE6, 0xEB)
    private val PANEL = Color.rgb(0xF5, 0xF6, 0xF8)
    private val ACCENT = Color.rgb(0xFF, 0x6A, 0x21)
    private val HOT = Color.rgb(0xFF, 0x3B, 0x5C)
    private val COOL = Color.rgb(0x1F, 0x9E, 0xC9)
    private val GOOD = Color.rgb(0x16, 0xA3, 0x4A)
    private val WARN = Color.rgb(0xD9, 0x8E, 0x04)
    private val BAD = Color.rgb(0xDC, 0x26, 0x26)
    private val VIOLET = Color.rgb(0x93, 0x4F, 0xE0)
    private val HEADER = Color.rgb(0x0B, 0x0D, 0x12)

    fun fileName(record: RunRecord): String = "stress-test-report-${record.id}.pdf"

    /** Writes the report into [dir] and returns the file. */
    fun write(context: Context, record: RunRecord, insights: List<Insight>, dir: File): File {
        dir.mkdirs()
        val file = File(dir, fileName(record))
        val fonts = Fonts(context)
        val document = PdfDocument()
        try {
            val pages = 2
            page(document, 1) { canvas -> firstPage(canvas, context, fonts, record, insights, pages) }
            page(document, 2) { canvas -> chartPage(canvas, context, fonts, record, pages) }
            file.outputStream().use { document.writeTo(it) }
        } finally {
            document.close()
        }
        return file
    }

    private fun page(document: PdfDocument, number: Int, draw: (Canvas) -> Unit) {
        val page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, number).create())
        draw(page.canvas)
        document.finishPage(page)
    }

    private class Fonts(context: Context) {
        private val grotesk: Typeface = ResourcesCompat.getFont(context, R.font.space_grotesk) ?: Typeface.DEFAULT
        private val mono: Typeface = ResourcesCompat.getFont(context, R.font.jetbrains_mono) ?: Typeface.MONOSPACE

        fun text(weight: Int): Typeface = weighted(grotesk, weight)
        fun number(weight: Int): Typeface = weighted(mono, weight)

        private fun weighted(base: Typeface, weight: Int): Typeface =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Typeface.create(base, weight, false)
            else Typeface.create(base, if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun paint(typeface: Typeface, size: Float, color: Int) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        this.typeface = typeface
        textSize = size
        this.color = color
    }

    // ---- page 1 ---------------------------------------------------------------------------------

    private fun firstPage(canvas: Canvas, context: Context, fonts: Fonts, record: RunRecord, insights: List<Insight>, pages: Int) {
        var y = header(canvas, context, fonts, record)
        y = testAndDevice(canvas, context, fonts, record, y + 18f)
        y = metrics(canvas, context, fonts, record, y + 16f)
        findings(canvas, context, fonts, insights, y + 16f)
        footer(canvas, context, fonts, record, 1, pages)
    }

    /** The dark band across the top: the mark, the title, the mode and date. */
    private fun header(canvas: Canvas, context: Context, fonts: Fonts, record: RunRecord): Float {
        val height = 112f
        canvas.drawRect(0f, 0f, PAGE_W.toFloat(), height, Paint().apply { color = HEADER })
        canvas.drawRect(
            0f, height - 4f, PAGE_W.toFloat(), height,
            Paint().apply { shader = LinearGradient(0f, 0f, PAGE_W.toFloat(), 0f, ACCENT, HOT, Shader.TileMode.CLAMP) },
        )
        gauge(canvas, MARGIN + 22f, 50f, 22f)
        // The wordmark as on screen: the first word white, the rest in the accent.
        val name = context.getString(R.string.app_name).upper()
        val split = name.indexOf(' ').takeIf { it > 0 } ?: name.length
        val title = paint(fonts.text(700), 22f, Color.WHITE).apply { letterSpacing = 0.25f }
        canvas.drawText(name, 0, split, MARGIN + 56f, 44f, title)
        if (split < name.length) {
            val x = MARGIN + 56f + title.measureText(name, 0, split)
            canvas.drawText(name, split, name.length, x, 44f, title.apply { color = ACCENT })
        }
        canvas.drawText(context.getString(R.string.report_title), MARGIN + 56f, 62f, paint(fonts.text(400), 11f, Color.rgb(0xB8, 0xBE, 0xC8)))
        val right = PAGE_W - MARGIN
        val mode = paint(fonts.text(600), 15f, Color.WHITE).apply { textAlign = Paint.Align.RIGHT }
        canvas.drawText(Labels.recordModeName(context, record), right, 44f, mode)
        val date = paint(fonts.text(400), 10f, Color.rgb(0xB8, 0xBE, 0xC8)).apply { textAlign = Paint.Align.RIGHT }
        canvas.drawText(Dates.long(record.startedAtMillis), right, 62f, date)
        canvas.drawText(context.getString(R.string.report_run_id, record.id), right, 78f, date)
        return height
    }

    /** The app's mark, drawn: a 240-degree gauge with its needle near the top. */
    private fun gauge(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val stroke = r * 0.18f
        val arc = RectF(cx - r + stroke / 2, cy - r + stroke / 2, cx + r - stroke / 2, cy + r - stroke / 2)
        val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
            color = Color.argb(70, 0xFF, 0x6A, 0x21)
        }
        canvas.drawArc(arc, 150f, 240f, false, track)
        canvas.drawArc(arc, 150f, 240f * 0.86f, false, track.apply { color = ACCENT })
        val angle = Math.toRadians(150.0 + 240.0 * 0.86)
        val needle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ACCENT
            strokeWidth = stroke * 0.6f
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawLine(cx, cy, cx + (Math.cos(angle) * r * 0.6).toFloat(), cy + (Math.sin(angle) * r * 0.6).toFloat(), needle)
        canvas.drawCircle(cx, cy, stroke * 0.8f, needle)
    }

    private fun testAndDevice(canvas: Canvas, context: Context, fonts: Fonts, record: RunRecord, top: Float): Float {
        val half = (PAGE_W - 2 * MARGIN - 16f) / 2
        val test = buildList {
            add(context.getString(R.string.report_mode) to Labels.recordModeName(context, record))
            record.quality?.let { q -> SceneQuality.entries.firstOrNull { it.name == q } }?.let {
                add(context.getString(R.string.home_quality) to context.getString(Labels.quality(it)))
            }
            add(context.getString(R.string.report_duration) to buildString {
                append(Format.clock(record.loadSeconds))
                record.plannedSeconds?.let { append(" / ").append(Format.clock(it.toDouble())) }
            })
            add(context.getString(R.string.report_end) to context.getString(Labels.endReason(Labels.endReasonOf(record))))
            record.safetyEnabled?.let { on ->
                add(context.getString(R.string.settings_safety) to context.getString(if (on) R.string.safety_on_short else R.string.safety_off_short))
            }
            add(context.getString(R.string.report_workload) to record.workload)
        }
        val device = record.device?.let { d ->
            buildList {
                add(context.getString(R.string.report_device) to d.title)
                d.soc?.let { add(context.getString(R.string.device_soc) to it) }
                d.cpuClusters.forEach { add(context.getString(R.string.device_cpu) to it) }
                d.gpu?.let { gpu -> add(context.getString(R.string.device_gpu) to gpu + (d.vulkan?.let { v -> " · Vulkan $v" } ?: "")) }
                add(context.getString(R.string.device_android) to "${d.androidVersion} (API ${d.sdk})")
                d.ramGigabytes?.let { add(context.getString(R.string.device_ram) to "${Format.number(it, 1)} GB") }
            }
        } ?: listOf(context.getString(R.string.report_device) to Format.MISSING)
        val h1 = block(canvas, fonts, context.getString(R.string.report_section_test), test, MARGIN, top, half)
        val h2 = block(canvas, fonts, context.getString(R.string.report_section_device), device, MARGIN + half + 16f, top, half)
        return top + maxOf(h1, h2)
    }

    /** A titled panel of label/value lines; returns its height. */
    private fun block(canvas: Canvas, fonts: Fonts, title: String, rows: List<Pair<String, String>>, left: Float, top: Float, width: Float): Float {
        val label = paint(fonts.text(400), 8.5f, DIM)
        val value = paint(fonts.text(600), 8.5f, INK)
        val lineH = 14f
        var y = top + 30f
        val lines = rows.map { (l, v) ->
            val layout = StaticLayout.Builder.obtain(v, 0, v.length, value, (width - 108f).toInt())
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setMaxLines(2).build()
            Triple(l, layout, maxOf(lineH, layout.height + 3f))
        }
        val height = 30f + lines.sumOf { it.third.toDouble() }.toFloat() + 8f
        canvas.drawRoundRect(RectF(left, top, left + width, top + height), 8f, 8f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PANEL })
        sectionTitle(canvas, fonts, title, left + 12f, top + 18f)
        for ((l, layout, h) in lines) {
            canvas.drawText(l, left + 12f, y + 8.5f, label)
            canvas.save()
            canvas.translate(left + 96f, y)
            layout.draw(canvas)
            canvas.restore()
            y += h
        }
        return height
    }

    private fun sectionTitle(canvas: Canvas, fonts: Fonts, text: String, x: Float, y: Float) {
        canvas.drawCircle(x + 3f, y - 3.5f, 3f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT })
        canvas.drawText(text.upper(), x + 11f, y, paint(fonts.text(700), 8.5f, DIM).apply { letterSpacing = 0.12f })
    }

    private fun metrics(canvas: Canvas, context: Context, fonts: Fonts, record: RunRecord, top: Float): Float {
        val s = record.summary
        val chip = listOfNotNull(s.maxTemperatures["cpu"], s.maxTemperatures["gpu"], s.maxTemperatures["A715"], s.maxTemperatures["A510"], s.maxTemperatures["GPU"]).maxOrNull()
        val valid = s.powerValid
        val tiles = listOf(
            Triple(context.getString(R.string.result_peak), if (valid) Format.watts(s.peakWatts) else Format.MISSING, ACCENT),
            Triple(context.getString(R.string.result_sustained), if (valid) Format.watts(s.sustainedWatts) else Format.MISSING, ACCENT),
            Triple(context.getString(R.string.result_mean), if (valid) Format.watts(s.meanWatts) else Format.MISSING, INK),
            Triple(context.getString(R.string.result_idle), if (valid) Format.watts(s.idleWatts) else Format.MISSING, INK),
            Triple(context.getString(R.string.result_energy), if (valid) s.energyWattHours?.let { "${Format.number(it, 2)} Wh" } ?: Format.MISSING else Format.MISSING, INK),
            Triple(context.getString(R.string.result_battery), "${Format.percentValue(s.batteryStartPercent)} → ${Format.percentValue(s.batteryEndPercent)}", INK),
            Triple(context.getString(R.string.result_battery_life), s.batteryLifeHours?.let { hoursText(context, it) } ?: Format.MISSING, INK),
            Triple(context.getString(R.string.result_hottest), Format.celsius(chip), if ((chip ?: 0.0) >= 95) WARN else INK),
            Triple(context.getString(R.string.result_cpu_perf), s.cpuMeanRate?.let { Format.rate(it, Labels.rateSymbol(context, s.cpuUnit)) } ?: Format.MISSING, INK),
            if (s.meanFps != null) {
                Triple(context.getString(R.string.result_fps), "${Format.number(s.meanFps, 1)} FPS", INK)
            } else {
                Triple(context.getString(R.string.result_gpu_perf), s.gpuMeanRate?.let { Format.rate(it, Labels.rateSymbol(context, s.gpuUnit)) } ?: Format.MISSING, INK)
            },
            Triple(context.getString(R.string.result_stability), Format.percent(s.cpuStability ?: s.gpuStability), INK),
            Triple(context.getString(R.string.result_errors), s.computationErrors.toString(), if (s.computationErrors > 0) BAD else GOOD),
        )
        sectionTitle(canvas, fonts, context.getString(R.string.report_section_results), MARGIN, top + 8f)
        val columns = 4
        val gap = 8f
        val w = (PAGE_W - 2 * MARGIN - gap * (columns - 1)) / columns
        val h = 46f
        val label = paint(fonts.text(400), 7.5f, DIM)
        tiles.forEachIndexed { i, (l, v, color) ->
            val x = MARGIN + (i % columns) * (w + gap)
            val y = top + 18f + (i / columns) * (h + gap)
            canvas.drawRoundRect(RectF(x, y, x + w, y + h), 7f, 7f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = PANEL })
            canvas.drawText(ellipsize(l, label, w - 16f), x + 8f, y + 15f, label)
            val valuePaint = paint(fonts.number(700), 12.5f, color)
            canvas.drawText(ellipsize(v, valuePaint, w - 16f), x + 8f, y + 35f, valuePaint)
        }
        val rows = (tiles.size + columns - 1) / columns
        var bottom = top + 18f + rows * (h + gap)
        if (!valid) {
            val note = paint(fonts.text(500), 8.5f, WARN)
            canvas.drawText(context.getString(R.string.insight_charging_title), MARGIN, bottom + 8f, note)
            bottom += 14f
        }
        return bottom
    }

    private fun findings(canvas: Canvas, context: Context, fonts: Fonts, insights: List<Insight>, top: Float) {
        sectionTitle(canvas, fonts, context.getString(R.string.result_analysis), MARGIN, top + 8f)
        var y = top + 20f
        val width = (PAGE_W - 2 * MARGIN - 20f).toInt()
        val title = paint(fonts.text(600), 9.5f, INK)
        val detail = paint(fonts.text(400), 8.5f, DIM)
        val limit = PAGE_H - 60f
        for (insight in insights) {
            val heading = InsightText.title(context, insight)
            val text = InsightText.detail(context, insight)
            val t = StaticLayout.Builder.obtain(heading, 0, heading.length, title, width).build()
            val d = StaticLayout.Builder.obtain(text, 0, text.length, detail, width).build()
            val h = t.height + d.height + 8f
            if (y + h > limit) break
            canvas.drawCircle(MARGIN + 5f, y + 7f, 4f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = severityColor(insight) })
            canvas.save()
            canvas.translate(MARGIN + 18f, y)
            t.draw(canvas)
            canvas.translate(0f, t.height.toFloat() + 1f)
            d.draw(canvas)
            canvas.restore()
            y += h
        }
    }

    private fun severityColor(insight: Insight): Int = when (insight.severity) {
        dev.ozcan.stress.analysis.Severity.Good -> GOOD
        dev.ozcan.stress.analysis.Severity.Info -> COOL
        dev.ozcan.stress.analysis.Severity.Warn -> WARN
        dev.ozcan.stress.analysis.Severity.Bad -> BAD
    }

    private fun footer(canvas: Canvas, context: Context, fonts: Fonts, record: RunRecord, page: Int, pages: Int) {
        val y = PAGE_H - 24f
        canvas.drawLine(MARGIN, y - 12f, PAGE_W - MARGIN, y - 12f, Paint().apply { color = RULE; strokeWidth = 0.8f })
        val p = paint(fonts.text(400), 7.5f, FAINT)
        canvas.drawText(context.getString(R.string.report_footer, record.appVersion ?: "?"), MARGIN, y, p)
        canvas.drawText(context.getString(R.string.report_page, page, pages), PAGE_W - MARGIN, y, p.apply { textAlign = Paint.Align.RIGHT })
    }

    // ---- page 2: the curves -------------------------------------------------------------------------

    private class Line(val name: String, val color: Int, val values: List<Double?>)

    private fun chartPage(canvas: Canvas, context: Context, fonts: Fonts, record: RunRecord, pages: Int) {
        val series = record.series
        val charts = buildList {
            if (series.watts.any { it != null }) {
                add(Triple(context.getString(R.string.chart_power), "W", listOf(Line(context.getString(R.string.chart_power), ACCENT, series.watts))))
            }
            val temps = series.temperatures.map { (key, values) -> Line(Labels.temperature(context, key), temperatureColor(key), values) } +
                Line(Labels.temperature(context, RunAnalysis.BATTERY), HOT, series.batteryCelsius)
            if (temps.any { l -> l.values.any { it != null } }) {
                add(Triple(context.getString(R.string.chart_temperature), "°C", temps.filter { l -> l.values.any { it != null } }))
            }
            if (series.clocksMhz.values.any { v -> v.any { it != null } }) {
                val palette = listOf(GOOD, COOL, ACCENT, HOT, VIOLET)
                add(Triple(context.getString(R.string.chart_clock), "MHz", series.clocksMhz.entries.mapIndexed { i, (n, v) -> Line(n, palette[i % palette.size], v) }))
            }
            val performance = buildList {
                if (series.cpuRelative.any { it != null }) add(Line("CPU", ACCENT, series.cpuRelative.map { it?.times(100) }))
                if (series.gpuRelative.any { it != null }) add(Line("GPU", COOL, series.gpuRelative.map { it?.times(100) }))
            }
            if (performance.isNotEmpty()) add(Triple(context.getString(R.string.chart_performance), "%", performance))
            if (series.fps.any { it != null }) add(Triple(context.getString(R.string.chart_fps), "fps", listOf(Line("FPS", VIOLET, series.fps))))
        }
        sectionTitle(canvas, fonts, context.getString(R.string.result_charts), MARGIN, MARGIN + 6f)
        val available = PAGE_H - MARGIN - 60f - (MARGIN + 18f)
        val each = if (charts.isEmpty()) 0f else minOf(160f, available / charts.size)
        var y = MARGIN + 18f
        for ((title, unit, lines) in charts) {
            chart(canvas, fonts, title, unit, series.seconds, lines, MARGIN, y, PAGE_W - 2 * MARGIN, each - 10f)
            y += each
        }
        footer(canvas, context, fonts, record, 2, pages)
    }

    private fun temperatureColor(key: String): Int = when (key) {
        "cpu", "A715" -> ACCENT
        "gpu", "GPU" -> COOL
        "memory", "DDR" -> VIOLET
        "skin", "A510" -> GOOD
        else -> DIM
    }

    private fun chart(
        canvas: Canvas,
        fonts: Fonts,
        title: String,
        unit: String,
        seconds: List<Double>,
        lines: List<Line>,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
    ) {
        canvas.drawRoundRect(RectF(left, top, left + width, top + height), 8f, 8f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PANEL })
        canvas.drawText(title, left + 12f, top + 16f, paint(fonts.text(600), 9.5f, INK))
        // Legend, right aligned on the title line.
        var lx = left + width - 12f
        val legend = paint(fonts.text(400), 7.5f, DIM).apply { textAlign = Paint.Align.RIGHT }
        for (line in lines.reversed()) {
            canvas.drawText(line.name, lx, top + 15f, legend)
            lx -= legend.measureText(line.name) + 4f
            canvas.drawRect(lx - 10f, top + 11f, lx, top + 13f, Paint().apply { color = line.color })
            lx -= 18f
        }
        val plotLeft = left + 44f
        val plotRight = left + width - 12f
        val plotTop = top + 26f
        val plotBottom = top + height - 18f
        val values = lines.flatMap { it.values.filterNotNull() }
        if (values.isEmpty() || seconds.isEmpty()) return
        val minimum = if (unit == "%" || unit == "W" || unit == "MHz" || unit == "fps") 0.0 else values.min()
        val low = minimum
        val high = if (unit == "%") 105.0 else values.max()
        val pad = if (high > low) (high - low) * 0.08 else 1.0
        val bottom = if (low == 0.0) 0.0 else low - pad
        val topValue = if (unit == "%") high else high + pad
        val span = seconds.last().takeIf { it > 0 } ?: 1.0
        fun x(t: Double) = plotLeft + (t / span).toFloat() * (plotRight - plotLeft)
        fun y(v: Double) = plotBottom - ((v - bottom) / (topValue - bottom)).toFloat() * (plotBottom - plotTop)

        val grid = Paint().apply {
            color = RULE
            strokeWidth = 0.7f
            pathEffect = DashPathEffect(floatArrayOf(3f, 3f), 0f)
        }
        val axis = paint(fonts.number(400), 7f, FAINT)
        val yStep = niceStep(bottom, topValue)
        for (tick in niceTicks(bottom, topValue)) {
            val py = y(tick)
            canvas.drawLine(plotLeft, py, plotRight, py, grid)
            val text = tickLabel(tick, yStep) + " $unit"
            canvas.drawText(text, left + 8f, py + 2.5f, axis)
        }
        var t = 0.0
        val step = timeStep(span, ((plotRight - plotLeft) / 46f).toInt())
        val timeAxis = paint(fonts.number(400), 7f, FAINT).apply { textAlign = Paint.Align.CENTER }
        while (t <= span + 1e-9) {
            canvas.drawText(Format.clock(t), x(t), plotBottom + 11f, timeAxis)
            t += step
        }
        lines.forEachIndexed { index, line ->
            val path = Path()
            var drawing = false
            var gaps = 0
            var firstX: Float? = null
            var lastX = 0f
            line.values.forEachIndexed { i, v ->
                val time = seconds.getOrNull(i) ?: return@forEachIndexed
                if (v == null) {
                    if (drawing) gaps++
                    drawing = false
                } else {
                    if (drawing) path.lineTo(x(time), y(v)) else path.moveTo(x(time), y(v))
                    drawing = true
                    if (firstX == null) firstX = x(time)
                    lastX = x(time)
                }
            }
            val start = firstX
            // A soft fill under a lone line, when it is one piece (a trailing gap does not break it).
            if (index == 0 && lines.size == 1 && start != null && (gaps == 0 || (gaps == 1 && !drawing))) {
                val fill = Path(path)
                fill.lineTo(lastX, plotBottom)
                fill.lineTo(start, plotBottom)
                fill.close()
                canvas.drawPath(
                    fill,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        shader = LinearGradient(0f, plotTop, 0f, plotBottom, Color.argb(70, Color.red(line.color), Color.green(line.color), Color.blue(line.color)), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
                    },
                )
            }
            canvas.drawPath(
                path,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 1.3f
                    strokeJoin = Paint.Join.ROUND
                    color = line.color
                },
            )
        }
    }

    private fun ellipsize(text: String, paint: Paint, width: Float): String {
        if (paint.measureText(text) <= width) return text
        var end = text.length
        while (end > 1 && paint.measureText(text, 0, end) + paint.measureText("…") > width) end--
        return text.substring(0, end) + "…"
    }
}
