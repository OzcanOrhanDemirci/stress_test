package dev.ozcan.stress.ui.home

import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ozcan.stress.Capabilities
import dev.ozcan.stress.R
import dev.ozcan.stress.charge.ChargeSession
import dev.ozcan.stress.device.DeviceInfo
import dev.ozcan.stress.engine.CpuKernel
import dev.ozcan.stress.engine.GpuBurner
import dev.ozcan.stress.engine.GpuState
import dev.ozcan.stress.engine.SceneQuality
import dev.ozcan.stress.engine.Workload
import dev.ozcan.stress.run.RunController
import dev.ozcan.stress.run.StressDuration
import dev.ozcan.stress.run.StressMode
import dev.ozcan.stress.safety.SafetyCheck
import dev.ozcan.stress.safety.SafetyLimits
import dev.ozcan.stress.settings.Settings
import dev.ozcan.stress.telemetry.SysfsLayout
import dev.ozcan.stress.telemetry.ThermalGroup
import dev.ozcan.stress.ui.Dates
import dev.ozcan.stress.ui.Format
import dev.ozcan.stress.ui.Labels
import dev.ozcan.stress.ui.LiveView
import dev.ozcan.stress.ui.components.EngineeringPage
import dev.ozcan.stress.ui.components.LogLine
import dev.ozcan.stress.ui.components.LogTag
import dev.ozcan.stress.ui.components.NavRow
import dev.ozcan.stress.ui.components.Temperatures
import dev.ozcan.stress.ui.components.logSection
import dev.ozcan.stress.ui.theme.StressColors
import dev.ozcan.stress.ui.theme.StressType
import dev.ozcan.stress.ui.upper
import java.util.Locale

/*
 * The home screen's engineering page (DESIGN "What each simple page keeps", Home row): everything the Test tab
 * knows, as the log. The log is built as plain data first so the simple page's engineering row can count its values.
 */

internal data class HomeLine(
    val key: String,
    val value: String?,
    val unit: String? = null,
    val color: Color = StressColors.Text,
    val tag: LogTag? = null,
    val source: String? = null,
)

/** A full-sentence notice: [tag] in [color], then the sentence. */
internal data class HomeNotice(val tag: String, val color: Color, val text: String)

internal data class HomeSection(
    val title: String,
    val summary: String? = null,
    val note: String? = null,
    val lines: List<HomeLine> = emptyList(),
    val notices: List<HomeNotice> = emptyList(),
    /** A stored run the section can open (the last run). */
    val openRun: String? = null,
)

internal class HomeLog(val sections: List<HomeSection>) {
    val count: Int = sections.sumOf { it.lines.size + it.notices.size }
}

/** What the home screen knows at one moment; [homeLog] turns it into the log. */
internal data class HomeFacts(
    val mode: StressMode,
    val duration: StressDuration,
    val quality: SceneQuality,
    val settings: Settings,
    val live: LiveView?,
    val capabilities: Capabilities?,
    val device: DeviceInfo?,
    val runs: HomeHistory,
    val gate: SafetyCheck?,
    val layout: SysfsLayout,
    val kernels: List<CpuKernel>,
    val burners: List<GpuBurner>,
    val appVersion: String,
)

private val Limits = SafetyLimits()

internal fun homeLog(context: Context, f: HomeFacts): HomeLog {
    fun s(id: Int, vararg args: Any): String = if (args.isEmpty()) context.getString(id) else context.getString(id, *args)
    val yes = s(R.string.home_val_yes)
    val no = s(R.string.home_val_no)
    val live = f.live
    val waiting = s(R.string.home_note_waiting).takeIf { live == null }
    val sections = mutableListOf<HomeSection>()

    // Summary first: what pressing Start would run.
    val scene = f.mode.scene
    val charge = f.mode == StressMode.Charge
    val gpuOk = f.capabilities?.gpuTests != false
    val safety = charge || f.settings.deviceSafety
    sections += HomeSection(
        s(R.string.home_eng_test),
        summary = Labels.modeName(context, f.mode),
        lines = listOfNotNull(
            HomeLine(s(R.string.home_key_mode), Labels.modeName(context, f.mode)),
            scene?.let { HomeLine(s(R.string.home_key_quality), s(Labels.quality(f.quality))) },
            scene?.let { HomeLine(s(R.string.home_key_resolution), Format.percentValue(f.quality.scalePercent(it))) },
            HomeLine(s(R.string.home_key_duration), s(Labels.duration(f.duration, f.mode))),
            HomeLine(
                s(R.string.home_key_safety), s(if (safety) R.string.home_val_on else R.string.home_val_off),
                color = if (safety) StressColors.Good else StressColors.Bad,
                tag = if (safety) LogTag.Ok else LogTag.Warn,
            ),
            if (charge) {
                HomeLine(s(R.string.home_key_stop_battery), Format.number(ChargeSession.STOP_CELSIUS, 0), "°C")
            } else {
                null
            },
            HomeLine(s(R.string.home_key_stop_chip), Format.number(Limits.chipStop, 0), "°C").takeIf { !charge && safety },
            HomeLine(s(R.string.home_key_stop_battery), Format.number(Limits.batteryStop, 0), "°C").takeIf { !charge && safety },
            HomeLine(s(R.string.home_key_stop_skin), Format.number(Limits.skinStop, 0), "°C").takeIf { !charge && safety },
            when {
                f.mode.usesGpu && !gpuOk ->
                    HomeLine(s(R.string.home_key_start), s(R.string.home_val_no_gpu), color = StressColors.Bad, tag = LogTag.Stop)
                f.gate != null ->
                    HomeLine(s(R.string.home_key_start), s(R.string.home_val_held), color = StressColors.Warn, tag = LogTag.Warn)
                else -> HomeLine(s(R.string.home_key_start), s(R.string.home_val_ready), color = StressColors.Good, tag = LogTag.Ok)
            },
        ),
    )

    // Every notice in full, also those the simple page shortens to one line.
    val notices = buildList {
        if (live?.plugged == true && !charge) {
            add(HomeNotice(s(R.string.home_tag_plug), StressColors.Warn, s(R.string.home_plugged)))
        }
        if (f.capabilities?.gpuTests == false) add(HomeNotice(s(R.string.home_tag_gpu), StressColors.Cool, s(R.string.home_no_vulkan)))
        f.gate?.let { add(HomeNotice(s(R.string.home_tag_safety), StressColors.Warn, s(R.string.home_cooling, Labels.safetyFinding(context, it)))) }
        if (live != null && (live.chipCelsius == null || live.batteryCelsius == null || live.levelPercent == null ||
                (!live.plugged && live.watts == null))
        ) {
            add(HomeNotice(s(R.string.home_tag_data), StressColors.TextDim, s(R.string.home_missing_readings)))
        }
    }
    sections += HomeSection(
        s(R.string.home_eng_notices),
        summary = notices.size.toString(),
        note = s(R.string.home_notices_none).takeIf { notices.isEmpty() },
        notices = notices,
    )

    // Live readings, in the order the run screen reads them: power, heat, clocks, GPU.
    val plugged = live?.plugged == true
    val powerLines = listOf(
        HomeLine(s(R.string.home_key_power), Format.number(live?.watts, 2), "W", StressColors.Accent),
        HomeLine(s(R.string.home_key_current), Format.number(live?.dischargeAmps, 3), "A"),
        HomeLine(s(R.string.home_key_raw_current), live?.currentRaw?.toString()),
        HomeLine(s(R.string.home_key_voltage), Format.number(live?.volts, 3), "V"),
        HomeLine(
            s(R.string.home_key_charger),
            live?.let { s(if (it.plugged) R.string.home_val_plugged else R.string.home_val_unplugged) },
            color = if (plugged) StressColors.Warn else StressColors.Text,
        ),
        HomeLine(s(R.string.home_key_level), Format.percentValue(live?.levelPercent)),
        HomeLine(s(R.string.home_key_battery_temp), Format.number(live?.batteryCelsius, 1), "°C", Temperatures.battery(live?.batteryCelsius)),
    )
    sections += HomeSection(
        s(R.string.home_eng_power),
        summary = live?.watts?.let { "${Format.number(it, 1)} W" },
        note = waiting ?: when {
            plugged -> s(R.string.home_note_power_plugged)
            powerLines.any { it.value.isMissing() } -> s(R.string.home_note_power_missing)
            else -> null
        },
        lines = powerLines,
    )

    val zonesByGroup = f.layout.zones.groupBy { it.group }
    val thermalLines = buildList {
        add(HomeLine(s(R.string.home_key_chip), Format.number(live?.chipCelsius, 1), "°C", Temperatures.chip(live?.chipCelsius)))
        for (group in ThermalGroup.entries) {
            val value = live?.temperatures?.get(group)
            val zones = zonesByGroup[group].orEmpty()
            val color = when (group) {
                ThermalGroup.Skin -> Temperatures.skin(value)
                else -> Temperatures.chip(value)
            }
            add(
                HomeLine(
                    Labels.temperature(context, group.key), Format.number(value, 1), "°C", color,
                    source = zones.firstOrNull()?.let { z -> if (zones.size > 1) "${z.type} +${zones.size - 1}" else z.type },
                ),
            )
        }
        add(HomeLine(s(R.string.home_key_thermal_status), live?.let { s(Labels.thermalStatus(it.thermalStatus)) }))
        add(HomeLine(s(R.string.home_key_headroom), live?.headroom?.let { Format.number(it.toDouble(), 2) }))
    }
    sections += HomeSection(
        s(R.string.home_eng_thermal),
        summary = s(R.string.home_eng_zones, f.layout.zones.size).takeIf { f.layout.zones.isNotEmpty() },
        note = waiting ?: s(R.string.home_note_thermal_missing).takeIf { thermalLines.any { it.value.isMissing() } },
        lines = thermalLines,
    )

    val clusters = live?.clusters.orEmpty()
    val clusterLines = clusters.mapIndexed { i, c ->
        HomeLine(
            "${s(Labels.role(c.role))} · ${c.label}",
            c.khz?.let { (it / 1000).toString() }, "MHz",
            source = listOfNotNull(
                s(R.string.home_key_max_clock, (c.maxKhz / 1000).toString()).takeIf { c.maxKhz >= 1000 },
                f.layout.clusters.getOrNull(i)?.currentFreqPath,
            ).joinToString(" // "),
        )
    }
    sections += HomeSection(
        s(R.string.home_eng_clusters),
        summary = f.layout.cpuCount.let { "$it CPU" },
        note = waiting ?: s(R.string.home_note_clocks_missing).takeIf { clusterLines.any { it.value.isMissing() } },
        lines = clusterLines,
    )

    val gpu = live?.gpu
    val estimated = live?.gpuBusy == null && gpu?.busyFromFrames != null
    sections += HomeSection(
        s(R.string.home_eng_gpu),
        note = when {
            f.capabilities?.gpuTests == false -> s(R.string.home_note_no_vulkan)
            waiting != null -> waiting
            estimated -> s(R.string.home_note_gpu_estimated)
            live?.gpuLoad == null -> s(R.string.home_note_gpu_missing)
            else -> null
        },
        lines = listOf(
            HomeLine(s(R.string.home_key_gpu), f.device?.gpu),
            HomeLine(s(R.string.home_key_vulkan), f.device?.vulkan),
            HomeLine(
                s(R.string.home_key_gpu_load), live?.gpuLoad?.let { Format.percent(it) }, color = StressColors.Cool,
                source = f.layout.gpuBusyPath,
            ),
            HomeLine(
                s(R.string.home_key_gpu_state),
                gpu?.state?.let {
                    s(
                        when {
                            it.hasFailed -> R.string.home_val_gpu_failed
                            it == GpuState.Running -> R.string.home_val_gpu_running
                            else -> R.string.home_val_gpu_idle
                        },
                    )
                },
                color = if (gpu?.state?.hasFailed == true) StressColors.Bad else StressColors.Text,
            ),
        ),
    )

    sections += workloadSection(context, f)

    if (scene != null) {
        sections += HomeSection(
            s(R.string.home_eng_scene),
            summary = s(Labels.scene(scene)),
            note = "${s(Labels.sceneDetail(scene))} ${s(Labels.qualityDetail(f.quality))}",
            lines = SceneQuality.entries.map { q ->
                HomeLine(
                    "${s(R.string.home_key_resolution)} · ${s(Labels.quality(q))}", Format.percentValue(q.scalePercent(scene)),
                    color = if (q == f.quality) StressColors.Cool else StressColors.Text,
                    tag = if (q == f.quality) LogTag.Custom(s(R.string.home_tag_selected), StressColors.Cool) else null,
                )
            },
        )
    }

    val bestLines = HomeModes.filter { it != StressMode.Charge }.map { mode ->
        HomeLine(s(Labels.modeTitle(mode)), f.runs.bestWatts[mode]?.let { Format.number(it, 1) }, "W", StressColors.Accent)
    }
    sections += HomeSection(
        s(R.string.home_eng_best),
        note = s(R.string.home_note_best_missing).takeIf { bestLines.any { it.value.isMissing() } },
        lines = bestLines,
    )

    val last = f.runs.last
    sections += if (last == null) {
        HomeSection(s(R.string.home_eng_last), note = s(R.string.home_note_no_runs))
    } else {
        val errors = last.summary.computationErrors
        HomeSection(
            s(R.string.home_eng_last),
            summary = Dates.short(last.startedAtMillis),
            note = s(R.string.home_note_last_power).takeIf { last.summary.peakWatts == null || last.summary.sustainedWatts == null },
            lines = listOf(
                HomeLine(s(R.string.home_key_mode), Labels.recordModeName(context, last)),
                HomeLine(s(R.string.home_key_date), Dates.long(last.startedAtMillis)),
                HomeLine(s(R.string.home_key_load_time), Format.clock(last.loadSeconds)),
                HomeLine(s(R.string.home_key_peak), Format.wattNumber(last.summary.peakWatts), "W", StressColors.Accent),
                HomeLine(s(R.string.home_key_sustained), Format.wattNumber(last.summary.sustainedWatts), "W", StressColors.Accent),
                HomeLine(
                    s(R.string.home_key_errors), errors.toString(),
                    color = if (errors > 0) StressColors.Bad else StressColors.Good,
                    tag = if (errors > 0) LogTag.Stop else LogTag.Ok,
                ),
            ),
            openRun = last.id,
        )
    }

    val device = f.device
    val deviceLines = listOf(
        HomeLine(s(R.string.home_key_model), device?.title),
        HomeLine(s(R.string.home_key_android), device?.let { "${it.androidVersion} (SDK ${it.sdk})" }),
        HomeLine(s(R.string.home_key_soc), device?.soc),
    ) + (device?.cpuClusters?.takeIf { it.isNotEmpty() } ?: listOf(null)).mapIndexed { i, cluster ->
        // One line per cluster: joined, they wrap into an unreadable block.
        HomeLine(if (device?.cpuClusters.orEmpty().size > 1) "${s(R.string.home_key_cpu)} ${i + 1}" else s(R.string.home_key_cpu), cluster)
    } + listOf(
        HomeLine(s(R.string.home_key_cores), device?.cpuCount?.toString()),
        HomeLine(s(R.string.home_key_ram), device?.ramGigabytes?.let { Format.number(it, 1) }, "GB"),
        HomeLine(s(R.string.home_key_display), device?.display),
        HomeLine(s(R.string.home_key_app), f.appVersion),
    )
    sections += HomeSection(
        s(R.string.home_eng_device),
        note = s(R.string.home_note_device_missing).takeIf { deviceLines.any { it.value.isMissing() } },
        lines = deviceLines,
    )
    return HomeLog(sections)
}

/** The kernels and burners pressing Start would run, parsed the way the run controller parses them. */
private fun workloadSection(context: Context, f: HomeFacts): HomeSection {
    fun s(id: Int, vararg args: Any): String = if (args.isEmpty()) context.getString(id) else context.getString(id, *args)
    val title = s(R.string.home_eng_workload)
    if (f.mode == StressMode.Charge) return HomeSection(title, note = s(R.string.home_note_charge_workload))
    val recipe = if (f.settings.sceneBurner && f.mode.scene != null && f.burners.any { it.key == RunController.FILL_BURNER }) {
        "${f.mode.recipe}+${RunController.FILL_BURNER}"
    } else {
        f.mode.recipe
    }
    val workload = runCatching { Workload.parse(recipe, f.kernels, f.burners, f.layout.cpuCount) }.getOrNull()
    val lines = mutableListOf(HomeLine(s(R.string.home_key_recipe), recipe))
    workload?.cpu?.let { cpu ->
        val kernels = cpu.kernelPerCpu.filterNotNull().distinct()
        for (kernel in kernels) {
            lines += HomeLine(
                s(R.string.home_key_cpu_kernel), kernel.key, color = StressColors.Accent,
                tag = if (kernel.supported) LogTag.Ok else LogTag.Stop, source = kernel.code,
            )
            lines += HomeLine(s(R.string.home_key_unit), kernel.unit.rateSymbol)
        }
        lines += HomeLine(s(R.string.home_key_threads), cpu.kernelPerCpu.count { it != null }.toString())
        lines += HomeLine(
            s(R.string.home_key_supported),
            s(if (kernels.all { it.supported }) R.string.home_val_yes else R.string.home_val_no),
            color = if (kernels.all { it.supported }) StressColors.Good else StressColors.Bad,
        )
    }
    workload?.gpu?.let { gpu ->
        gpu.burner?.let { burner ->
            lines += HomeLine(s(R.string.home_key_gpu_burner), burner.key, color = StressColors.Cool, source = burner.code)
            lines += HomeLine(s(R.string.home_key_unit), burner.unit.rateSymbol)
            lines += HomeLine(
                s(R.string.home_key_verified), s(if (burner.verified) R.string.home_val_yes else R.string.home_val_no),
                tag = if (burner.verified) LogTag.Ok else LogTag.Warn,
            )
        }
        lines += HomeLine(
            s(R.string.home_key_visible_pass),
            if (gpu.scene == false) s(R.string.home_val_preview) else s(Labels.scene(gpu.kind)),
        )
    }
    if (f.mode.scene != null) {
        lines += HomeLine(
            s(R.string.home_key_scene_burner), s(if (f.settings.sceneBurner) R.string.home_val_on else R.string.home_val_off),
        )
    }
    return HomeSection(title, summary = recipe.takeIf { workload == null }?.upper(Locale.ROOT), lines = lines)
}

private fun String?.isMissing(): Boolean = this == null || this == Format.MISSING

/**
 * [live] at a calm rate: live values move ten times a second, the Test tab reads them once a second (DESIGN
 * "Live values"). A plugged or unplugged cable passes at once, since it changes what the page offers.
 */
@Composable
internal fun calmLive(live: State<LiveView?>): LiveView? {
    val calm by produceState(live.value, live) {
        var shownAt = 0L
        snapshotFlow { live.value }.collect { next ->
            val now = SystemClock.uptimeMillis()
            if (value == null || next == null || next.plugged != value?.plugged || now - shownAt >= 1_000) {
                value = next
                shownAt = now
            }
        }
    }
    return calm
}

/** The page itself: [log] section by section; the last run's section can open it. */
@Composable
internal fun HomeEngineeringPage(log: HomeLog, onOpenRun: (String) -> Unit, onBack: () -> Unit) {
    EngineeringPage(stringResource(R.string.tab_test), onBack) {
        log.sections.forEach { section ->
            logSection(section.title, section.summary, section.note) {
                section.notices.forEachIndexed { i, notice ->
                    item(key = "notice:${section.title}:$i") { NoticeLine(notice) }
                }
                section.lines.forEachIndexed { i, line ->
                    item(key = "line:${section.title}:$i") {
                        LogLine(line.key, line.value, line.unit, line.color, line.tag, line.source)
                    }
                }
                section.openRun?.let { id ->
                    item(key = "open:${section.title}") {
                        NavRow(stringResource(R.string.home_open_last), onClick = { onOpenRun(id) }, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun NoticeLine(notice: HomeNotice) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 32.dp).padding(vertical = 6.dp),
        Arrangement.spacedBy(10.dp),
    ) {
        Text(notice.tag.upper(), Modifier.widthIn(min = 64.dp), style = StressType.Code, color = notice.color, maxLines = 1)
        Text(notice.text, Modifier.weight(1f), style = StressType.BodySmall, color = StressColors.Text)
    }
}
