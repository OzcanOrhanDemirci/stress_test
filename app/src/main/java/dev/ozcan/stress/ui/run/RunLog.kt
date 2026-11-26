package dev.ozcan.stress.ui.run

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.ozcan.stress.R
import dev.ozcan.stress.engine.GpuState
import dev.ozcan.stress.engine.SceneQuality
import dev.ozcan.stress.run.RunState
import dev.ozcan.stress.run.StressMode
import dev.ozcan.stress.safety.SafetyCheck
import dev.ozcan.stress.safety.SafetyLevel
import dev.ozcan.stress.safety.SafetyLimits
import dev.ozcan.stress.telemetry.ThermalGroup
import dev.ozcan.stress.ui.Format
import dev.ozcan.stress.ui.Labels
import dev.ozcan.stress.ui.LiveView
import dev.ozcan.stress.ui.components.LogEvent
import dev.ozcan.stress.ui.components.LogLine
import dev.ozcan.stress.ui.components.LogTag
import dev.ozcan.stress.ui.components.Temperatures
import dev.ozcan.stress.ui.components.logSection
import dev.ozcan.stress.ui.context
import dev.ozcan.stress.ui.theme.StressColors

/** One line of an engineering log, as [LogLine] draws it. A null [value] is unavailable. */
internal data class LogItem(
    val key: String,
    val value: String?,
    val unit: String? = null,
    val color: Color = StressColors.Text,
    val tag: LogTag? = null,
    val source: String? = null,
)

/** A log section of plain lines; built as data first so the engineering row can count its values. */
internal data class LogBlock(
    val title: String,
    val lines: List<LogItem>,
    val summary: String? = null,
    val note: String? = null,
)

internal fun LazyListScope.logBlocks(blocks: List<LogBlock>) {
    blocks.forEach { block ->
        logSection(block.title, block.summary, block.note) {
            items(block.lines, key = { "${block.title}:${it.key}" }) {
                LogLine(it.key, it.value, it.unit, it.color, it.tag, it.source)
            }
        }
    }
}

private val Limits = SafetyLimits()

/**
 * Every live reading of a run, by section (DESIGN "What each simple page keeps", Run): the run itself, power and
 * battery, temperatures, clusters and clocks, the GPU, the CPU workload. The live chart and the events follow
 * these in the sheet.
 */
@Composable
internal fun runLog(
    mode: StressMode,
    quality: SceneQuality,
    state: RunState.Running,
    now: Long,
    live: LiveView?,
    history: LiveHistory,
    safety: SafetyCheck,
    safetyOn: Boolean,
): List<LogBlock> {
    val context = context()
    val missingNote = stringResource(R.string.run_unavailable)
    fun noteIf(lines: List<LogItem>) = missingNote.takeIf { lines.any { it.value == null || it.value == Format.MISSING } }

    val elapsed = ((now - state.startedAtNanos) / 1e9).coerceAtLeast(0.0)
    val total = state.endsAtNanos?.let { (it - state.startedAtNanos) / 1e9 }
    val run = buildList {
        add(LogItem(stringResource(R.string.run_line_mode), Labels.modeName(context, mode)))
        add(
            LogItem(
                stringResource(R.string.run_line_elapsed),
                "${Format.clock(elapsed)} / ${total?.let(Format::clock) ?: stringResource(R.string.duration_endless)}",
            ),
        )
        if (total != null) {
            add(LogItem(stringResource(R.string.run_line_remaining), Format.clock(kotlin.math.ceil((total - elapsed).coerceAtLeast(0.0)))))
        }
        add(LogItem(stringResource(R.string.run_line_recipe), mode.recipe.replace("+", " + ")))
        mode.scene?.let { kind ->
            add(
                LogItem(
                    stringResource(R.string.run_line_requested),
                    "${stringResource(Labels.quality(quality))} ${Format.percentValue(quality.scalePercent(kind))}",
                ),
            )
            // Shown once the startup probe has settled; before that there is nothing drawn to report.
            live?.gpu?.sceneDraw?.let { draw ->
                val reduced = draw.quality != quality || draw.scalePercent != quality.scalePercent(kind).toDouble()
                add(
                    LogItem(
                        stringResource(R.string.run_line_drawn),
                        "${stringResource(Labels.quality(draw.quality))} ${Format.percent(draw.scalePercent / 100, if (draw.scalePercent % 1 == 0.0) 0 else 2)}",
                        color = if (reduced) StressColors.Warn else StressColors.Text,
                        tag = if (reduced) LogTag.Warn else LogTag.Ok,
                    ),
                )
            }
        }
        add(
            if (safetyOn) {
                LogItem(
                    stringResource(R.string.run_line_protection),
                    stringResource(R.string.home_protection_limit, Limits.chipStop.toInt()),
                    tag = LogTag.Ok,
                )
            } else {
                LogItem(
                    stringResource(R.string.run_line_protection), stringResource(R.string.safety_off_short),
                    color = StressColors.Bad, tag = LogTag.Warn,
                )
            },
        )
        if (safetyOn) {
            add(
                LogItem(
                    stringResource(R.string.run_line_safety),
                    if (safety.level == SafetyLevel.Ok) stringResource(R.string.run_safety_ok) else Labels.safetyFinding(context, safety),
                    color = safetyColor(safety.level),
                    tag = LogTag.of(safety.level),
                ),
            )
        }
    }

    val plugged = live?.plugged == true
    val watts = live?.watts.takeUnless { plugged }
    val idle = state.idleWatts
    val power = buildList {
        add(LogItem(stringResource(R.string.run_line_power), Format.number(watts, 2), "W", StressColors.Accent))
        add(LogItem(stringResource(R.string.run_line_idle), Format.number(idle, 2), "W", StressColors.Accent))
        val above = if (idle != null && watts != null) (watts - idle).takeIf { it >= 0 } else null
        add(LogItem(stringResource(R.string.run_line_above_idle), Format.number(above, 2), "W", StressColors.Accent))
        add(LogItem(stringResource(R.string.run_line_peak), Format.number(history.peakWatts, 2), "W", StressColors.Accent))
        add(LogItem(stringResource(R.string.charge_current), Format.number(live?.dischargeAmps, 2), "A"))
        add(LogItem(stringResource(R.string.charge_voltage), Format.number(live?.volts, 2), "V"))
        add(LogItem(stringResource(R.string.run_line_current_raw), live?.currentRaw?.toString()))
        add(LogItem(stringResource(R.string.charge_battery_level), Format.percentValue(live?.levelPercent)))
        val battery = live?.batteryCelsius
        add(LogItem(stringResource(R.string.run_line_battery_temp), Format.number(battery, 1), "°C", Temperatures.battery(battery)))
        add(
            LogItem(
                stringResource(R.string.charger),
                live?.let { stringResource(if (it.plugged) R.string.charger_connected else R.string.charger_not_connected) },
                tag = if (plugged) LogTag.Warn else null,
            ),
        )
    }

    val temperatures = live?.temperatures.orEmpty()
    val thermal = buildList {
        val chip = live?.chipCelsius
        add(LogItem(stringResource(R.string.run_line_chip), Format.number(chip, 1), "°C", Temperatures.chip(chip)))
        ThermalGroup.entries.forEach { group ->
            val value = temperatures[group]
            val color = if (group == ThermalGroup.Skin) Temperatures.skin(value) else Temperatures.chip(value)
            add(LogItem(Labels.temperature(context, group.key), Format.number(value, 1), "°C", color))
        }
        add(
            LogItem(
                stringResource(R.string.run_line_thermal_status),
                live?.let { stringResource(Labels.thermalStatus(it.thermalStatus)) },
                color = if ((live?.thermalStatus ?: 0) >= 3) StressColors.Warn else StressColors.Text,
            ),
        )
        add(LogItem(stringResource(R.string.run_line_headroom), Format.number(live?.headroom?.toDouble(), 2)))
    }

    val clusters = live?.clusters.orEmpty()
    val clusterLines = clusters.mapIndexed { index, cluster ->
        val role = stringResource(Labels.role(cluster.role))
        val details = listOfNotNull(
            // Some kernels do not give the top clock; then the role stands alone.
            if (cluster.maxKhz > 0) stringResource(R.string.run_cluster_source, role, Format.mhz(cluster.maxKhz)) else role,
            pluralStringResource(R.plurals.run_threads, cluster.threads, cluster.threads),
            cluster.rate?.let { Format.rate(it, live?.unit?.let { u -> Labels.rateSymbol(context, u) }) },
        )
        LogItem(
            cluster.label, Format.number(cluster.khz?.div(1000.0), 0), "MHz",
            Labels.clusterColor(index, clusters.size).takeIf { it != StressColors.TextFaint } ?: StressColors.TextDim,
            source = details.joinToString(" · "),
        )
    }

    val gpu = live?.gpu
    val gpuLines = if (!mode.usesGpu) emptyList() else buildList {
        add(
            LogItem(
                stringResource(R.string.run_line_gpu_state),
                gpu?.let { stringResource(gpuLabel(it.state)) },
                color = gpu?.let { gpuColor(it.state) } ?: StressColors.Text,
                tag = gpu?.state?.takeIf { it.hasFailed }?.let { LogTag.Stop },
            ),
        )
        add(LogItem(stringResource(R.string.run_gpu_load), Format.number(live?.gpuLoad?.times(100), 0), "%", StressColors.Cool))
        add(
            LogItem(
                stringResource(R.string.run_line_load_source),
                when {
                    live?.gpuBusy != null -> stringResource(R.string.run_load_driver)
                    gpu?.busyFromFrames != null -> stringResource(R.string.run_load_frames)
                    else -> null
                },
            ),
        )
        add(LogItem(stringResource(R.string.chart_fps), Format.number(gpu?.framesPerSecond, 1), "fps", StressColors.Cool))
        add(LogItem(stringResource(R.string.run_line_frame_time), Format.number(gpu?.frameMillis, 1), "ms"))
        if (mode.scene == null || gpu?.burner != null) {
            add(LogItem(stringResource(R.string.run_gpu_burner), gpu?.burner?.key))
            val (rate, unit) = Format.rateParts(gpu?.rate, gpu?.burner?.let { Labels.rateSymbol(context, it.unit) })
            add(LogItem(stringResource(R.string.result_gpu_perf), rate, unit))
            add(LogItem(stringResource(R.string.run_line_dispatches), gpu?.dispatchesPerFrame?.toString()))
        }
        add(LogItem(stringResource(R.string.run_line_checks), gpu?.checks?.toString()))
        add(errorLine(gpu?.errors))
    }

    val workload = if (!mode.usesCpu) emptyList() else buildList {
        add(LogItem(stringResource(R.string.run_cpu_kernel), live?.runningKernel?.key))
        val (rate, unit) = Format.rateParts(live?.cpuRate, live?.unit?.let { Labels.rateSymbol(context, it) })
        add(LogItem(stringResource(R.string.result_cpu_perf), rate, unit))
        add(errorLine(live?.errors))
    }

    val read = ThermalGroup.entries.count { it in temperatures }
    return listOfNotNull(
        LogBlock(stringResource(R.string.run_log_run), run),
        LogBlock(
            stringResource(R.string.run_log_power), power,
            note = if (plugged) stringResource(R.string.run_unavailable_charging) else noteIf(power),
        ),
        LogBlock(
            stringResource(R.string.run_log_thermal), thermal,
            summary = "$read / ${ThermalGroup.entries.size}",
            note = noteIf(thermal),
        ),
        LogBlock(
            stringResource(R.string.run_log_clusters), clusterLines,
            summary = pluralStringResource(R.plurals.run_log_cluster_count, clusters.size, clusters.size),
            note = noteIf(clusterLines).takeIf { clusters.isNotEmpty() } ?: missingNote.takeIf { clusters.isEmpty() },
        ),
        gpuLines.takeIf { it.isNotEmpty() }?.let { LogBlock(stringResource(R.string.run_log_gpu), it, note = noteIf(it)) },
        workload.takeIf { it.isNotEmpty() }?.let { LogBlock(stringResource(R.string.run_workload), it, note = noteIf(it)) },
    )
}

@Composable
private fun errorLine(errors: Long?): LogItem = LogItem(
    stringResource(R.string.run_line_errors), errors?.toString(),
    color = when {
        errors == null -> StressColors.Text
        errors > 0 -> StressColors.Bad
        else -> StressColors.Good
    },
    tag = errors?.let { if (it > 0) LogTag.Stop else LogTag.Ok },
)

/** The events so far, newest last, as the log reads top to bottom. */
internal fun LazyListScope.runEvents(events: RunEvents, title: String, summary: String, empty: String) {
    logSection(title, summary, note = empty.takeIf { events.events.isEmpty() }) {
        items(events.events.size, key = { "event:$it" }) { index -> RunEventLine(events.events[index]) }
    }
}

@Composable
private fun RunEventLine(event: RunEvent) {
    val context = context()
    val (tag, color, text) = when (event) {
        is RunEvent.Safety -> Triple(
            R.string.run_tag_safety,
            safetyColor(event.check.level).takeIf { event.check.level != SafetyLevel.Ok } ?: StressColors.Good,
            if (event.check.level == SafetyLevel.Ok) stringResource(R.string.run_event_safety_ok) else Labels.safetyFinding(context, event.check),
        )
        is RunEvent.Thermal -> Triple(
            R.string.run_tag_thermal,
            if (event.status >= 3) StressColors.Warn else StressColors.Cool,
            stringResource(R.string.home_live_thermal, stringResource(Labels.thermalStatus(event.status))),
        )
        is RunEvent.Gpu -> Triple(R.string.run_tag_gpu, gpuColor(event.state), stringResource(gpuLabel(event.state)))
        is RunEvent.Scene -> Triple(
            R.string.run_tag_scene, StressColors.Cool,
            stringResource(
                R.string.run_event_scene,
                stringResource(Labels.quality(event.draw.quality)),
                Format.percent(event.draw.scalePercent / 100, if (event.draw.scalePercent % 1 == 0.0) 0 else 2),
            ),
        )
        is RunEvent.Errors -> Triple(
            R.string.run_tag_error, StressColors.Bad,
            pluralStringResource(R.plurals.run_event_errors, event.total.toInt(), event.total.toInt()),
        )
        is RunEvent.Plug -> Triple(
            R.string.run_tag_plug, StressColors.Warn,
            stringResource(if (event.plugged) R.string.run_event_plugged else R.string.run_event_unplugged),
        )
    }
    LogEvent(Format.clock(event.second.toDouble()), stringResource(tag), color, text)
}

internal fun safetyColor(level: SafetyLevel): Color = when (level) {
    SafetyLevel.Ok -> StressColors.Good
    SafetyLevel.Warn -> StressColors.Warn
    SafetyLevel.Stop -> StressColors.Bad
}

internal fun gpuLabel(state: GpuState): Int = when (state) {
    GpuState.Idle -> R.string.run_gpu_idle
    GpuState.Running -> R.string.run_gpu_running
    GpuState.DeviceLost, GpuState.Failed -> R.string.run_gpu_failed
}

internal fun gpuColor(state: GpuState): Color = when (state) {
    GpuState.Idle -> StressColors.TextDim
    GpuState.Running -> StressColors.Cool
    GpuState.DeviceLost, GpuState.Failed -> StressColors.Bad
}
