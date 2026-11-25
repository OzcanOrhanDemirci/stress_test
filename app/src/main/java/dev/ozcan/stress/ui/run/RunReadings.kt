package dev.ozcan.stress.ui.run

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GppBad
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ozcan.stress.R
import dev.ozcan.stress.engine.GpuState
import dev.ozcan.stress.engine.SceneQuality
import dev.ozcan.stress.run.RunState
import dev.ozcan.stress.run.StressMode
import dev.ozcan.stress.safety.SafetyCheck
import dev.ozcan.stress.safety.SafetyLevel
import dev.ozcan.stress.safety.SafetyLimits
import dev.ozcan.stress.telemetry.ThermalGroup
import dev.ozcan.stress.ui.ClusterLive
import dev.ozcan.stress.ui.Format
import dev.ozcan.stress.ui.Labels
import dev.ozcan.stress.ui.LiveView
import dev.ozcan.stress.ui.components.ChartSeries
import dev.ozcan.stress.ui.components.Hairline
import dev.ozcan.stress.ui.components.InfoRow
import dev.ozcan.stress.ui.components.MeterBar
import dev.ozcan.stress.ui.components.MetricNumber
import dev.ozcan.stress.ui.components.MetricTile
import dev.ozcan.stress.ui.components.Panel
import dev.ozcan.stress.ui.components.SectionLabel
import dev.ozcan.stress.ui.components.Sparkline
import dev.ozcan.stress.ui.components.Stack
import dev.ozcan.stress.ui.components.StatusChip
import dev.ozcan.stress.ui.components.StopButton
import dev.ozcan.stress.ui.components.TileRow
import dev.ozcan.stress.ui.components.TimeChart
import dev.ozcan.stress.ui.components.TopBar
import dev.ozcan.stress.ui.context
import dev.ozcan.stress.ui.theme.NumberStyles
import dev.ozcan.stress.ui.theme.StressColors
import dev.ozcan.stress.ui.theme.StressType

private val Limits = SafetyLimits()

@Composable
internal fun RunReadings(
    mode: StressMode,
    quality: SceneQuality,
    state: RunState.Running,
    now: Long,
    live: LiveView?,
    history: LiveHistory,
    safety: SafetyCheck,
    safetyOn: Boolean,
    onStop: () -> Unit,
) {
    val scene = mode.scene != null
    val ground = if (scene) Modifier else Modifier.background(StressColors.Background)
    BoxWithConstraints(Modifier.fillMaxSize().then(ground).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
        val sceneReadingsHeight = maxHeight * 0.40f
        Column(Modifier.fillMaxSize()) {
            RunTopBar(mode, quality, state, now, live, safety, safetyOn)
            if (scene) SceneCorners(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp))
            val body = if (scene) Modifier.heightIn(max = sceneReadingsHeight) else Modifier.weight(1f)
            Stack(
                body.fillMaxWidth().background(StressColors.Background)
                    .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                spacing = if (scene) 8 else 12,
            ) {
                val plugged = live?.plugged == true
                val watts = live?.watts.takeUnless { plugged }
                LivePower(watts, state.idleWatts, history, live, scene)
                MainTiles(mode, live)
                if (scene) ClusterReadings(live?.clusters.orEmpty(), compact = true)
                val missing = watts == null || live?.chipCelsius == null || live.batteryCelsius == null ||
                    live.levelPercent == null || listOf(ThermalGroup.Cpu, ThermalGroup.Gpu, ThermalGroup.Skin).any { it !in live.temperatures } ||
                    (mode.usesGpu && (live.gpuLoad == null || live.gpu.frameMillis == null || live.gpu.framesPerSecond == null ||
                        (mode.scene == null && (live.gpu.burner == null || live.gpu.rate == null)))) ||
                    (mode.usesCpu && (live.clusters.isEmpty() || live.clusters.any { it.khz == null } || live.cpuRate == null))
                if (missing) {
                    Text(
                        stringResource(if (plugged) R.string.run_unavailable_charging else R.string.run_unavailable),
                        style = StressType.BodySmall, color = StressColors.TextDim,
                    )
                }
                if (!scene) {
                    LiveChart(history)
                    ClusterReadings(live?.clusters.orEmpty(), compact = false)
                }
                LoadDetails(mode, live)
            }
            Hairline()
            Box(
                Modifier.fillMaxWidth().background(StressColors.SurfaceLow).navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                StopButton(stringResource(R.string.run_stop), onClick = onStop)
            }
        }
    }
}

@Composable
private fun RunTopBar(
    mode: StressMode,
    quality: SceneQuality,
    state: RunState.Running,
    now: Long,
    live: LiveView?,
    safety: SafetyCheck,
    safetyOn: Boolean,
) {
    val elapsed = ((now - state.startedAtNanos) / 1e9).coerceAtLeast(0.0)
    val total = state.endsAtNanos?.let { (it - state.startedAtNanos) / 1e9 }
    val remaining = total?.minus(elapsed)?.coerceAtLeast(0.0)
    val caption = mode.scene?.let { kind ->
        "${stringResource(Labels.quality(quality))} ${Format.percentValue(quality.scalePercent(kind))}"
    } ?: mode.recipe.replace("+", " + ")
    TopBar(
        title = Labels.modeName(context(), mode),
        caption = caption,
        modifier = Modifier.background(StressColors.SurfaceLow).statusBarsPadding(),
        supporting = {
            Stack(Modifier.fillMaxWidth(), spacing = 4) {
                Row(
                    Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp), Alignment.CenterVertically,
                ) {
                    RecordingDot()
                    Text(
                        "${Format.clock(elapsed)} / ${total?.let(Format::clock) ?: stringResource(R.string.duration_endless)}",
                        Modifier.weight(1f), style = StressType.Code,
                    )
                    RunProtection(safety, safetyOn, showFinding = false)
                }
                if (total != null) MeterBar((elapsed / total).toFloat())
                if (remaining != null || mode.usesGpu) {
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Text(
                            remaining?.let { stringResource(R.string.run_remaining, Format.clock(kotlin.math.ceil(it))) }.orEmpty(),
                            style = StressType.Code, color = StressColors.TextDim,
                        )
                        if (mode.usesGpu) {
                            val fps = live?.gpu?.framesPerSecond
                            val frame = live?.gpu?.frameMillis
                            Text(
                                "${reading(fps, 0, "fps")} · ${reading(frame, 1, "ms")}",
                                style = StressType.Code, color = StressColors.Cool,
                            )
                        }
                    }
                }
                RunProtection(safety, safetyOn, showFinding = true)
            }
        },
    )
}

@Composable
private fun RecordingDot() {
    val description = stringResource(R.string.run_recording)
    val pulse = rememberInfiniteTransition(label = "recording")
    val alpha = pulse.animateFloat(1f, 0.4f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "dot")
    Box(
        Modifier.size(6.dp).semantics { contentDescription = description }
            .graphicsLayer { this.alpha = alpha.value }.background(StressColors.Accent, CircleShape),
    )
}

@Composable
private fun RunProtection(check: SafetyCheck, enabled: Boolean, showFinding: Boolean) {
    if (showFinding) {
        if (enabled && check.level != SafetyLevel.Ok) {
            StatusChip(
                Labels.safetyFinding(context(), check),
                if (check.level == SafetyLevel.Stop) StressColors.Bad else StressColors.Warn,
                icon = Icons.Rounded.Warning,
            )
        }
    } else if (!enabled) {
        StatusChip(stringResource(R.string.safety_off_short), StressColors.Bad, icon = Icons.Rounded.GppBad)
    } else if (check.level == SafetyLevel.Ok) {
        StatusChip(
            stringResource(R.string.home_protection_limit, Limits.chipStop.toInt()),
            StressColors.Good, icon = Icons.Rounded.Shield,
        )
    }
}

@Composable
private fun SceneCorners(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val arm = minOf(16.dp.toPx(), size.minDimension / 2)
        val stroke = 1.dp.toPx()
        val inset = stroke / 2
        for (dx in listOf(1, -1)) for (dy in listOf(1, -1)) {
            val x = if (dx == 1) inset else size.width - inset
            val y = if (dy == 1) inset else size.height - inset
            drawLine(StressColors.TextDim, Offset(x, y), Offset(x + dx * arm, y), stroke)
            drawLine(StressColors.TextDim, Offset(x, y), Offset(x, y + dy * arm), stroke)
        }
    }
}

@Composable
private fun LivePower(watts: Double?, idle: Double?, history: LiveHistory, live: LiveView?, compact: Boolean) {
    val aboveIdle = if (idle != null && watts != null) (watts - idle).takeIf { it >= 0 } else null
    val errors = live?.let { it.errors + it.gpu.errors }
    Panel(Modifier.fillMaxWidth(), padding = if (compact) 12.dp else 16.dp) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            SectionLabel(stringResource(R.string.home_live_power), Modifier.weight(1f))
            StatusChip(
                errors?.let { stringResource(R.string.run_errors, it) } ?: Format.MISSING,
                when {
                    errors == null -> StressColors.TextDim
                    errors > 0 -> StressColors.Bad
                    else -> StressColors.Good
                },
            )
        }
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(16.dp), Alignment.CenterVertically) {
            MetricNumber(Format.number(watts, 2), "W", if (compact) NumberStyles.Large else NumberStyles.Hero, StressColors.Accent)
            if (compact && history.peakWatts != null) {
                val values = remember(history) { history.readings.map { it.watts } }
                Stack(Modifier.weight(1f), spacing = 4) {
                    Text(
                        stringResource(R.string.run_peak, Format.watts(history.peakWatts)),
                        style = StressType.Code, color = StressColors.TextDim,
                    )
                    Sparkline(values, StressColors.Accent, Modifier.fillMaxWidth().height(20.dp))
                }
            }
        }
        Text(
            stringResource(R.string.run_power_caption, Format.watts(idle), aboveIdle?.let { "+${Format.watts(it)}" } ?: Format.MISSING),
            style = StressType.Code, color = StressColors.TextDim,
        )
    }
}

@Composable
private fun MainTiles(mode: StressMode, live: LiveView?) {
    val chip = live?.chipCelsius
    val battery = live?.batteryCelsius
    val chipColor = StressColors.temperature(chip, Limits.chipWarn, Limits.chipStop)
    val batteryColor = StressColors.temperature(battery, Limits.batteryWarn, Limits.batteryStop)
    val context = context()
    val gpu = live?.gpu
    val gpuLoad = live?.gpuLoad
    val tile = Modifier.fillMaxHeight()
    TileRow(Modifier.height(IntrinsicSize.Min)) {
        MetricTile(
            stringResource(R.string.home_live_chip), Format.number(chip, 0), tile.weight(1f),
            unit = "°C", color = chipColor, statusColor = chipColor.takeIf { chip != null },
            caption = live?.let { stringResource(Labels.thermalStatus(it.thermalStatus)) } ?: Format.MISSING,
            padding = 8.dp,
        )
        MetricTile(
            stringResource(R.string.home_live_battery), Format.number(battery, 0), tile.weight(1f),
            unit = "°C", color = batteryColor, statusColor = batteryColor.takeIf { battery != null },
            caption = Format.percentValue(live?.levelPercent), padding = 8.dp,
        )
        if (mode.usesGpu) {
            MetricTile(
                stringResource(R.string.run_gpu_load), Format.percent(gpuLoad), tile.weight(1f),
                statusColor = gpu?.let { gpuColor(it.state) },
                caption = stringResource(gpu?.state?.let(::gpuLabel) ?: R.string.run_gpu_unknown),
                padding = 8.dp,
            )
        } else {
            val (number, unit) = Format.rateParts(live?.cpuRate, live?.unit?.let { Labels.rateSymbol(context, it) })
            MetricTile(
                stringResource(R.string.temp_cpu), number, tile.weight(1f), unit = unit,
                statusColor = StressColors.Text.takeIf { live?.cpuRate != null },
                caption = live?.runningKernel?.key ?: Format.MISSING, padding = 8.dp,
            )
        }
    }
}

@Composable
private fun ClusterReadings(clusters: List<ClusterLive>, compact: Boolean) {
    Panel(Modifier.fillMaxWidth(), padding = if (compact) 8.dp else 12.dp) {
        Stack(spacing = 8) {
            if (!compact || clusters.isEmpty()) SectionLabel(stringResource(R.string.chart_clock))
            if (clusters.isEmpty()) Text(Format.MISSING, style = StressType.Code, color = StressColors.TextDim)
            clusters.withIndex().chunked(3).forEach { row ->
                TileRow {
                    row.forEach { (index, cluster) ->
                        val color = Labels.clusterColor(index, clusters.size)
                        Stack(Modifier.weight(1f), spacing = 4) {
                            Text(cluster.label, style = StressType.BodySmall, color = StressColors.TextDim)
                            MetricNumber(
                                Format.number(cluster.khz?.div(1000.0), 0), "MHz",
                                style = NumberStyles.Small, color = color,
                            )
                            cluster.load?.let { MeterBar(it, color = color) }
                            if (!compact) {
                                Text(
                                    pluralStringResource(R.plurals.run_threads, cluster.threads, cluster.threads),
                                    style = StressType.Code, color = StressColors.TextDim,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadDetails(mode: StressMode, live: LiveView?) {
    val context = context()
    val gpu = live?.gpu
    val cpuRate = Format.rate(live?.cpuRate, live?.unit?.let { Labels.rateSymbol(context, it) })
    val temperatures = live?.temperatures.orEmpty()
    Panel(Modifier.fillMaxWidth(), title = stringResource(R.string.run_workload)) {
        if (mode.usesCpu) {
            InfoRow(stringResource(R.string.run_cpu_kernel), live?.runningKernel?.key ?: Format.MISSING)
            InfoRow(stringResource(R.string.result_cpu_perf), cpuRate)
        }
        if (mode.usesGpu && (mode.scene == null || gpu?.burner != null)) {
            InfoRow(stringResource(R.string.run_gpu_burner), gpu?.burner?.key ?: Format.MISSING)
            InfoRow(stringResource(R.string.result_gpu_perf), Format.rate(gpu?.rate, gpu?.burner?.let { Labels.rateSymbol(context, it.unit) }))
        }
        TemperatureRow(R.string.temp_cpu, temperatures[ThermalGroup.Cpu], Limits.chipWarn, Limits.chipStop)
        TemperatureRow(R.string.temp_gpu, temperatures[ThermalGroup.Gpu], Limits.chipWarn, Limits.chipStop)
        TemperatureRow(R.string.temp_skin, temperatures[ThermalGroup.Skin], Limits.skinWarn, Limits.skinStop)
    }
}

@Composable
private fun LiveChart(history: LiveHistory) {
    val points = history.readings
    val origin = points.firstOrNull()?.second ?: 0
    val seconds = remember(points) { points.map { (it.second - origin).toDouble() } }
    val watts = remember(points) { points.map { it.watts } }
    val hasChip = remember(points) { points.any { it.chipCelsius != null } }
    val heat = remember(points, hasChip) { points.map { if (hasChip) it.chipCelsius else it.batteryCelsius } }
    val temperatures = remember(heat) { heat.filterNotNull() }
    val powerLabel = stringResource(R.string.chart_power)
    val heatLabel = stringResource(if (hasChip) R.string.run_chart_chip else R.string.temp_battery)
    val series = buildList {
        if (history.peakWatts != null && watts.any { it != null }) add(ChartSeries(powerLabel, StressColors.Accent, watts, "W"))
        if (temperatures.isNotEmpty()) {
            val range = if (isNotEmpty()) (temperatures.min() - 5)..(temperatures.max() + 5) else null
            add(ChartSeries(heatLabel, StressColors.Cool, heat, "°C", range))
        }
    }
    Panel(Modifier.fillMaxWidth(), title = stringResource(R.string.run_live_chart), padding = 12.dp) {
        if (series.isEmpty()) {
            Text(Format.MISSING, style = StressType.Code, color = StressColors.TextDim)
        } else {
            SectionLabel(series.first().name, caption = series.first().unit)
            TimeChart(
                seconds, series, series.first().unit.orEmpty(), height = 124.dp,
                yMin = 0.0.takeIf { series.first().unit == "W" },
                timeLabel = { Format.clock(it + origin) },
            )
        }
    }
}

@Composable
private fun TemperatureRow(label: Int, value: Double?, warn: Double, stop: Double) {
    InfoRow(stringResource(label), Format.celsius(value, 0), valueColor = StressColors.temperature(value, warn, stop))
}

private fun reading(value: Double?, decimals: Int, unit: String): String =
    value?.let { "${Format.number(it, decimals)} $unit" } ?: Format.MISSING

private fun gpuLabel(state: GpuState): Int = when (state) {
    GpuState.Idle -> R.string.run_gpu_idle
    GpuState.Running -> R.string.run_gpu_running
    GpuState.DeviceLost, GpuState.Failed -> R.string.run_gpu_failed
}

private fun gpuColor(state: GpuState): Color = when (state) {
    GpuState.Idle -> StressColors.TextDim
    GpuState.Running -> StressColors.Cool
    GpuState.DeviceLost, GpuState.Failed -> StressColors.Bad
}
