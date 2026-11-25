package dev.ozcan.stress.ui.result

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.ozcan.stress.R
import dev.ozcan.stress.analysis.Insight
import dev.ozcan.stress.analysis.ReferenceDevices
import dev.ozcan.stress.analysis.Severity
import dev.ozcan.stress.engine.SceneQuality
import dev.ozcan.stress.run.EndReason
import dev.ozcan.stress.run.RunAnalysis
import dev.ozcan.stress.run.RunRecord
import dev.ozcan.stress.run.StressMode
import dev.ozcan.stress.telemetry.ThermalGroup
import dev.ozcan.stress.ui.Dates
import dev.ozcan.stress.ui.Format
import dev.ozcan.stress.ui.InsightText
import dev.ozcan.stress.ui.Labels
import dev.ozcan.stress.ui.components.ChartSeries
import dev.ozcan.stress.ui.components.LogTag
import dev.ozcan.stress.ui.components.Temperatures
import dev.ozcan.stress.ui.components.TimeChart
import dev.ozcan.stress.ui.hoursText
import dev.ozcan.stress.ui.run.DrawnQualityNotice
import dev.ozcan.stress.ui.theme.StressColors

/*
 * The load test's engineering page (DESIGN, Result row): every insight, metric and chart the record holds, read top
 * to bottom like a report. Summary first, the record's raw identity last.
 */

internal fun resultLog(context: Context, record: RunRecord, insights: List<Insight>): List<LogBlock> = listOfNotNull(
    summaryBlock(context, record),
    insightBlock(context, insights),
    powerBlock(context, record),
    thermalBlock(context, record),
    throttleBlock(context, record),
    workBlock(context, record),
    sceneBlock(context, record),
    eventBlock(context, record),
) + chartBlocks(context, record) + listOfNotNull(deviceBlock(context, record), recordBlock(context, record))

private fun summaryBlock(context: Context, record: RunRecord): LogBlock {
    val reason = Labels.endReasonOf(record)
    val planned = record.plannedSeconds
    val lines = buildList {
        add(LogEntry.Line(context.getString(R.string.result_log_mode), Labels.recordModeName(context, record)))
        add(LogEntry.Line(context.getString(R.string.result_log_date), Dates.long(record.startedAtMillis)))
        add(
            LogEntry.Line(
                context.getString(R.string.result_log_duration), Format.clock(record.loadSeconds),
                source = context.getString(
                    R.string.result_log_planned,
                    planned?.let { Format.clock(it.toDouble()) } ?: context.getString(R.string.result_log_until_stopped),
                ),
            ),
        )
        add(
            LogEntry.Line(
                context.getString(R.string.result_log_end), context.getString(Labels.endReason(reason)),
                color = Labels.endReasonColor(reason), tag = endTag(reason),
            ),
        )
        if (record.safetyReason != null || reason == EndReason.Safety) {
            val finding = record.safetyReason
                ?.let { name -> dev.ozcan.stress.safety.SafetyReason.entries.firstOrNull { it.name == name } }
                ?.let { Labels.safetyFinding(context, it, record.safetyValue) }
            add(LogEntry.Line(context.getString(R.string.result_log_safety_reason), finding, color = StressColors.Warn, tag = LogTag.Warn))
        }
        add(
            LogEntry.Line(
                context.getString(R.string.result_log_safety),
                record.safetyEnabled?.let { context.getString(if (it) R.string.safety_on_short else R.string.safety_off_short) },
                color = if (record.safetyEnabled == false) StressColors.Warn else StressColors.Text,
                tag = record.safetyEnabled?.let { if (it) LogTag.Ok else LogTag.Warn },
            ),
        )
    }
    return LogBlock(
        context.getString(R.string.result_log_summary), lines,
        summary = context.getString(Labels.endReasonShort(reason)),
        note = context.getString(R.string.result_log_older_run).takeIf { lines.any { it.value == null } },
    )
}

internal fun endTag(reason: EndReason): LogTag? = when (reason) {
    EndReason.Completed, EndReason.BatteryFull -> LogTag.Ok
    EndReason.User, EndReason.Unplugged -> null
    EndReason.Safety -> LogTag.Warn
    EndReason.GpuFailed, EndReason.Interrupted, EndReason.SamplerLost, EndReason.ChargeReadingLost -> LogTag.Stop
}

private fun insightBlock(context: Context, insights: List<Insight>): LogBlock? {
    if (insights.isEmpty()) return null
    val entries = insights.flatMap { insight ->
        val (key, value) = insightKeyValue(context, insight)
        val color = insightColor(insight)
        listOf(
            LogEntry.Line(key, value, color = color, tag = severityTag(context, insight.severity)),
            LogEntry.Sentence(InsightText.detail(context, insight)),
        )
    }
    return LogBlock(context.getString(R.string.result_analysis), entries, summary = insights.size.toString())
}

/** An insight as a short key and its measured value; one without a value keeps an empty one, not "unavailable". */
private fun insightKeyValue(context: Context, insight: Insight): Pair<String, String> = when (insight) {
    is Insight.Throttled -> context.getString(R.string.result_insight_throttled) to Format.clock(insight.seconds)
    is Insight.PerformanceDrop -> context.getString(R.string.result_insight_drop) to Format.percent(insight.drop)
    is Insight.Stability -> context.getString(
        R.string.result_insight_stability, context.getString(InsightText.grade(insight.grade)),
    ) to Format.percent(insight.value, 1)
    is Insight.ChipPeak -> context.getString(R.string.result_insight_chip, Labels.temperature(context, insight.key)) to
        Format.celsius(insight.celsius)
    is Insight.BatteryWarmed -> context.getString(R.string.result_insight_battery) to
        "${Format.number(insight.from, 0)} → ${Format.celsius(insight.to, 0)}"
    is Insight.BatteryDrain -> context.getString(R.string.result_insight_drain) to Format.percent(insight.percentPerHour / 100.0)
    is Insight.ComputationErrors -> context.getString(R.string.result_errors) to insight.count.toString()
    is Insight.FrameRate -> context.getString(R.string.result_insight_fps) to "${Format.number(insight.mean, 1)} FPS"
    else -> InsightText.title(context, insight) to ""
}

internal fun insightColor(insight: Insight): Color = when (insight) {
    is Insight.ChipPeak -> Temperatures.chip(insight.celsius)
    is Insight.BatteryWarmed -> Temperatures.battery(insight.to)
    else -> InsightText.color(insight.severity)
}

private fun severityTag(context: Context, severity: Severity): LogTag = when (severity) {
    Severity.Good -> LogTag.Ok
    Severity.Info -> LogTag.Custom(context.getString(R.string.result_tag_info), StressColors.Cool)
    Severity.Warn -> LogTag.Warn
    Severity.Bad -> LogTag.Custom(context.getString(R.string.result_tag_bad), StressColors.Bad)
}

private fun powerBlock(context: Context, record: RunRecord): LogBlock {
    val s = record.summary
    fun watts(value: Double?) = Format.wattNumber(value).takeIf { s.powerValid && value != null }
    val lines = buildList {
        add(LogEntry.Line(context.getString(R.string.result_peak), watts(s.peakWatts), "W", StressColors.Accent))
        add(LogEntry.Line(context.getString(R.string.result_sustained), watts(s.sustainedWatts), "W", StressColors.Accent))
        add(LogEntry.Line(context.getString(R.string.result_mean), watts(s.meanWatts), "W", StressColors.Accent))
        add(LogEntry.Line(context.getString(R.string.result_idle), watts(s.idleWatts), "W", StressColors.Accent))
        add(
            LogEntry.Line(
                context.getString(R.string.result_energy),
                Format.number(s.energyWattHours, 2).takeIf { s.powerValid && s.energyWattHours != null }, "Wh",
            ),
        )
        add(LogEntry.Line(context.getString(R.string.result_log_battery_start), s.batteryStartPercent?.let(Format::percentValue)))
        add(LogEntry.Line(context.getString(R.string.result_log_battery_end), s.batteryEndPercent?.let(Format::percentValue)))
        add(
            LogEntry.Line(
                context.getString(R.string.result_battery_life),
                s.batteryLifeHours?.takeIf { s.powerValid }?.let { hoursText(context, it) },
            ),
        )
        add(
            LogEntry.Line(
                context.getString(R.string.result_log_power_source),
                context.getString(if (s.powerFromCounter) R.string.result_log_source_counter else R.string.result_log_source_current),
                tag = if (s.powerFromCounter) LogTag.Warn else null,
            ),
        )
        ReferenceDevices.forRun(context, record)?.let { (device, w) ->
            add(LogEntry.Line(context.getString(R.string.result_log_reference, device.name), Format.wattNumber(w), "W", StressColors.Accent, source = device.chipset))
        }
    }
    val note = when {
        !s.powerValid -> context.getString(R.string.insight_charging_detail)
        s.powerFromCounter -> context.getString(R.string.insight_counter_detail)
        lines.any { it.value == null } -> context.getString(R.string.result_unavailable)
        else -> null
    }
    return LogBlock(context.getString(R.string.chart_power), lines, summary = Format.watts(s.peakWatts.takeIf { s.powerValid }), note = note)
}

// Chip zones first, the battery last: the order a reader looks for them.
private val zoneOrder = listOf(ThermalGroup.Cpu.key, "A715", ThermalGroup.Gpu.key, "GPU", "A510")

private fun thermalBlock(context: Context, record: RunRecord): LogBlock {
    val s = record.summary
    val battery = setOf(RunAnalysis.BATTERY, "Pil")
    val keys = s.maxTemperatures.keys.sortedWith(
        compareBy<String>({ it in battery }, { zoneOrder.indexOf(it).let { i -> if (i < 0) zoneOrder.size else i } }, { it }),
    )
    val lines = buildList {
        keys.forEach { key ->
            val max = s.maxTemperatures.getValue(key)
            val color = when (key) {
                in battery -> Temperatures.battery(max)
                ThermalGroup.Skin.key -> Temperatures.skin(max)
                else -> Temperatures.chip(max)
            }
            add(
                LogEntry.Line(
                    Labels.temperature(context, key), Format.number(max, 1), "°C", color,
                    source = s.startTemperatures[key]?.let { context.getString(R.string.result_log_from, Format.celsius(it)) },
                ),
            )
        }
        add(
            LogEntry.Line(
                context.getString(R.string.result_log_thermal_status),
                s.maxThermalStatus?.let { context.getString(Labels.thermalStatus(it)) },
                color = when {
                    (s.maxThermalStatus ?: 0) >= 3 -> StressColors.Bad
                    (s.maxThermalStatus ?: 0) >= 2 -> StressColors.Warn
                    else -> StressColors.Text
                },
            ),
        )
    }
    return LogBlock(
        context.getString(R.string.result_log_thermal), lines,
        summary = s.hottestChip()?.let { Format.celsius(it) },
        note = when {
            keys.isEmpty() -> context.getString(R.string.result_log_no_temperatures)
            s.maxThermalStatus == null -> context.getString(R.string.result_log_no_status)
            else -> null
        },
    )
}

private fun throttleBlock(context: Context, record: RunRecord): LogBlock? {
    val clusters = record.summary.firstThrottleSeconds
    if (clusters.isEmpty()) return null
    val lines = clusters.map { (cluster, seconds) ->
        if (seconds == null) {
            LogEntry.Line(cluster, context.getString(R.string.result_log_never), color = StressColors.Good, tag = LogTag.Ok)
        } else {
            LogEntry.Line(cluster, Format.clock(seconds), color = StressColors.Warn, tag = LogTag.Warn)
        }
    }
    return LogBlock(
        context.getString(R.string.result_log_throttle), lines,
        summary = clusters.values.filterNotNull().minOrNull()?.let(Format::clock),
        note = context.getString(R.string.result_log_throttle_rule),
    )
}

private fun workBlock(context: Context, record: RunRecord): LogBlock {
    val s = record.summary
    val mode = StressMode.of(record.mode)
    val cpu = Labels.rateSymbol(context, s.cpuUnit)
    val gpu = Labels.rateSymbol(context, s.gpuUnit)
    fun rate(value: Double?, symbol: String?): Pair<String?, String?> =
        Format.rateParts(value, symbol).let { (v, u) -> (v.takeIf { it != Format.MISSING }) to u }
    val lines = buildList {
        if (mode?.usesCpu != false) {
            rate(s.cpuMeanRate, cpu).let { (v, u) -> add(LogEntry.Line(context.getString(R.string.result_cpu_perf), v, u)) }
            rate(s.cpuStartRate, cpu).let { (v, u) -> add(LogEntry.Line(context.getString(R.string.result_log_cpu_start), v, u)) }
            rate(s.cpuEndRate, cpu).let { (v, u) -> add(LogEntry.Line(context.getString(R.string.result_log_cpu_end), v, u)) }
            add(LogEntry.Line(context.getString(R.string.result_log_cpu_stability), s.cpuStability?.let { Format.percent(it, 1) }, color = StressColors.Cool))
        }
        if (mode?.usesGpu != false) {
            rate(s.gpuMeanRate, gpu).let { (v, u) -> add(LogEntry.Line(context.getString(R.string.result_gpu_perf), v, u)) }
            add(LogEntry.Line(context.getString(R.string.result_log_gpu_stability), s.gpuStability?.let { Format.percent(it, 1) }, color = StressColors.Cool))
        }
        if (mode?.scene != null || s.meanFps != null) {
            add(LogEntry.Line(context.getString(R.string.result_fps), s.meanFps?.let { Format.number(it, 1) }, "FPS"))
            add(LogEntry.Line(context.getString(R.string.result_log_fps_min), s.minFps?.let { Format.number(it, 1) }, "FPS"))
        }
        add(
            LogEntry.Line(
                context.getString(R.string.result_errors), s.computationErrors.toString(),
                color = if (s.computationErrors > 0) StressColors.Bad else StressColors.Good,
                tag = if (s.computationErrors > 0) LogTag.Custom(context.getString(R.string.result_tag_bad), StressColors.Bad) else LogTag.Ok,
            ),
        )
    }
    return LogBlock(
        context.getString(R.string.result_log_work), lines,
        summary = mode?.recipe ?: record.workload,
        note = context.getString(R.string.result_unavailable).takeIf { lines.any { it.value == null } },
    )
}

private fun sceneBlock(context: Context, record: RunRecord): LogBlock? {
    val kind = StressMode.of(record.mode)?.scene ?: return null
    val requested = SceneQuality.entries.firstOrNull { it.name == record.quality }
    val drawn = SceneQuality.entries.firstOrNull { it.name == record.drawnQuality }
    val lines = listOf(
        LogEntry.Line(context.getString(R.string.result_log_scene), context.getString(Labels.scene(kind))),
        LogEntry.Line(context.getString(R.string.result_log_quality_requested), requested?.let { context.getString(Labels.quality(it)) }),
        LogEntry.Line(
            context.getString(R.string.result_log_scale_requested),
            requested?.let { Format.percentValue(it.scalePercent(kind)) },
        ),
        LogEntry.Line(
            context.getString(R.string.result_log_quality_drawn), drawn?.let { context.getString(Labels.quality(it)) },
            color = if (drawn != null && drawn != requested) StressColors.Warn else StressColors.Text,
        ),
        LogEntry.Line(
            context.getString(R.string.result_log_scale_drawn),
            record.drawnScalePercent?.let { Format.percent(it / 100, decimals = if (it % 1 == 0.0) 0 else 2) },
        ),
        LogEntry.Block { DrawnQualityNotice(kind, requested, drawn, record.drawnScalePercent) },
    )
    return LogBlock(
        context.getString(R.string.result_log_scene_section), lines,
        summary = requested?.let { context.getString(Labels.quality(it)) },
        note = context.getString(R.string.result_log_scene_missing)
            .takeIf { lines.any { it is LogEntry.Line && it.value == null } && drawn != null },
    )
}

/** A timeline from what the record keeps: the start, each cluster's first slowdown, and how the run ended. */
private fun eventBlock(context: Context, record: RunRecord): LogBlock {
    val reason = Labels.endReasonOf(record)
    val throttles = record.summary.firstThrottleSeconds.mapNotNull { (cluster, seconds) -> seconds?.let { cluster to it } }
        .sortedBy { it.second }
    val events = buildList {
        add(LogEntry.Event("00:00", context.getString(R.string.result_event_start), StressColors.Cool, Labels.recordModeName(context, record)))
        throttles.forEach { (cluster, seconds) ->
            add(
                LogEntry.Event(
                    clock(seconds), context.getString(R.string.result_event_throttle), StressColors.Warn,
                    context.getString(R.string.result_event_throttle_text, cluster),
                ),
            )
        }
        val finding = record.safetyReason
            ?.let { name -> dev.ozcan.stress.safety.SafetyReason.entries.firstOrNull { it.name == name } }
            ?.let { Labels.safetyFinding(context, it, record.safetyValue) }
        val (tag, color) = when (reason) {
            EndReason.Safety -> R.string.result_event_safety to StressColors.Warn
            EndReason.GpuFailed -> R.string.result_event_gpu to StressColors.Bad
            EndReason.Interrupted, EndReason.SamplerLost -> R.string.result_event_error to StressColors.Bad
            else -> R.string.result_event_end to Labels.endReasonColor(reason)
        }
        val text = context.getString(Labels.endReason(reason)).let { if (finding != null) "$it · $finding" else it }
        add(LogEntry.Event(clock(record.loadSeconds), context.getString(tag), color, text))
    }
    return LogBlock(context.getString(R.string.result_log_events), events, summary = events.size.toString())
}

private fun clock(seconds: Double) = Format.clock(seconds).padStart(5, '0')

private fun chartBlocks(context: Context, record: RunRecord): List<LogBlock> {
    val series = record.series
    val power = context.getString(R.string.chart_power)
    val chip = series.chipTemperatures()
    val chipValues = chip.filterNotNull()
    val blocks = mutableListOf<LogBlock>()
    val hasPower = record.summary.powerValid && series.watts.any { it != null }
    blocks += if (hasPower) {
        val lines = buildList {
            add(ChartSeries(power, StressColors.Accent, series.watts, "W"))
            if (chipValues.isNotEmpty()) {
                add(ChartSeries(context.getString(R.string.run_chart_chip), StressColors.Cool, chip, "°C", (chipValues.min() - 5)..(chipValues.max() + 5)))
            }
        }
        chart(context.getString(R.string.result_log_chart, power), "W") {
            TimeChart(series.seconds, lines, "W", height = 180.dp, yMin = 0.0, decimals = Format.WATT_DECIMALS, showAxisUnits = lines.size > 1)
        }
    } else {
        LogBlock(
            context.getString(R.string.result_log_chart, power),
            listOf(LogEntry.Sentence(context.getString(if (record.summary.powerValid) R.string.result_log_no_curve else R.string.insight_charging_detail))),
        )
    }
    val temperatures = (series.temperatures.map { (key, values) ->
        ChartSeries(Labels.temperature(context, key), Labels.temperatureColor(key), values)
    } + ChartSeries(context.getString(R.string.temp_battery), Labels.temperatureColor(RunAnalysis.BATTERY), series.batteryCelsius))
        .filter { line -> line.values.any { it != null } }
    if (temperatures.isNotEmpty()) {
        blocks += chart(context.getString(R.string.result_log_chart, context.getString(R.string.chart_temperature)), "°C") {
            TimeChart(series.seconds, temperatures, "°C", height = 180.dp)
        }
    }
    if (series.clocksMhz.values.any { v -> v.any { it != null } }) {
        val entries = series.clocksMhz.entries.toList()
        val lines = entries.mapIndexed { i, (name, values) -> ChartSeries(name, Labels.clusterColor(i, entries.size), values) }
        blocks += chart(context.getString(R.string.result_log_chart, context.getString(R.string.chart_clock)), "MHz") {
            TimeChart(series.seconds, lines, "MHz", yMin = 0.0, decimals = 0)
        }
    }
    val performance = buildList {
        if (series.cpuRelative.any { it != null }) add(ChartSeries("CPU", StressColors.Accent, series.cpuRelative.map { it?.times(100) }))
        if (series.gpuRelative.any { it != null }) add(ChartSeries("GPU", StressColors.Cool, series.gpuRelative.map { it?.times(100) }))
    }
    if (performance.isNotEmpty()) {
        blocks += chart(
            context.getString(R.string.result_log_chart, context.getString(R.string.chart_performance)), "%",
            listOf(LogEntry.Sentence(context.getString(R.string.chart_performance_hint))),
        ) { TimeChart(series.seconds, performance, "%", yMin = 0.0, yMax = 105.0, decimals = 0) }
    }
    if (series.fps.any { it != null }) {
        blocks += chart(context.getString(R.string.result_log_chart, context.getString(R.string.chart_fps)), "FPS") {
            TimeChart(series.seconds, listOf(ChartSeries("FPS", StressColors.Cool, series.fps)), "fps", yMin = 0.0)
        }
    }
    val levels = series.batteryPercent.filterNotNull()
    if (levels.isNotEmpty() && levels.max() - levels.min() >= 1) {
        val battery = listOf(ChartSeries(context.getString(R.string.temp_battery), StressColors.Cool, series.batteryPercent.map { it?.toDouble() }))
        blocks += chart(context.getString(R.string.result_log_chart, context.getString(R.string.chart_battery)), "%") {
            TimeChart(series.seconds, battery, "%", decimals = 0)
        }
    }
    return blocks
}

internal fun chart(title: String, unit: String, before: List<LogEntry> = emptyList(), content: @androidx.compose.runtime.Composable () -> Unit) =
    LogBlock(title, before + LogEntry.Block(content), summary = unit)
