package dev.ozcan.stress.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.GppBad
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.ozcan.stress.AppGraph
import dev.ozcan.stress.Capabilities
import dev.ozcan.stress.R
import dev.ozcan.stress.charge.ChargeSession
import dev.ozcan.stress.device.DeviceInfo
import dev.ozcan.stress.engine.SceneKind
import dev.ozcan.stress.engine.SceneQuality
import dev.ozcan.stress.graph
import dev.ozcan.stress.run.RunRecord
import dev.ozcan.stress.run.StressDuration
import dev.ozcan.stress.run.StressMode
import dev.ozcan.stress.safety.SafetyCheck
import dev.ozcan.stress.safety.SafetyLimits
import dev.ozcan.stress.safety.SafetyPolicy
import dev.ozcan.stress.settings.Settings
import dev.ozcan.stress.ui.Dates
import dev.ozcan.stress.ui.Format
import dev.ozcan.stress.ui.Labels
import dev.ozcan.stress.ui.LiveView
import dev.ozcan.stress.ui.components.ChoiceChips
import dev.ozcan.stress.ui.components.CodeTag
import dev.ozcan.stress.ui.components.Hairline
import dev.ozcan.stress.ui.components.IconBadge
import dev.ozcan.stress.ui.components.MetricTile
import dev.ozcan.stress.ui.components.Panel
import dev.ozcan.stress.ui.components.PrimaryButton
import dev.ozcan.stress.ui.components.SceneCard
import dev.ozcan.stress.ui.components.SectionLabel
import dev.ozcan.stress.ui.components.SegmentedControl
import dev.ozcan.stress.ui.components.StatusChip
import dev.ozcan.stress.ui.components.StressDialog
import dev.ozcan.stress.ui.components.TileRow
import dev.ozcan.stress.ui.components.TopBar
import dev.ozcan.stress.ui.components.Wordmark
import dev.ozcan.stress.ui.context
import dev.ozcan.stress.ui.theme.NumberStyles
import dev.ozcan.stress.ui.theme.StressColors
import dev.ozcan.stress.ui.theme.StressMotion
import dev.ozcan.stress.ui.theme.StressType
import dev.ozcan.stress.ui.upper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class HomeHistory(
    val last: RunRecord? = null,
    val bestWatts: Map<StressMode, Double> = emptyMap(),
)

private val StressMode.homeGroup: StressMode get() = if (scene != null) StressMode.Cinematic else this

internal class HomeViewModel(private val graph: AppGraph) : ViewModel() {

    val settings: StateFlow<Settings> = graph.settings.settings

    val live: StateFlow<LiveView?> = graph.sampler.latest
        .map { LiveView.from(graph.sampler.log.recent(LiveView.WINDOW_SAMPLES), graph.layout) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(1_000), null)

    private val _capabilities = MutableStateFlow<Capabilities?>(null)
    val capabilities: StateFlow<Capabilities?> = _capabilities.asStateFlow()

    private val _device = MutableStateFlow<DeviceInfo?>(null)
    val device: StateFlow<DeviceInfo?> = _device.asStateFlow()

    private val history = MutableStateFlow(HomeHistory())
    val runs: StateFlow<HomeHistory> = history.asStateFlow()
    private var historyRefresh: Job? = null

    init {
        viewModelScope.launch { _capabilities.value = graph.capabilities.await() }
        viewModelScope.launch { _device.value = graph.device.await() }
    }

    fun refreshHistory() {
        historyRefresh?.cancel()
        historyRefresh = viewModelScope.launch {
            val device = graph.device.await()
            history.value = withContext(Dispatchers.IO) {
                val records = graph.runs.list()
                val best = mutableMapOf<StressMode, Double>()
                for (record in records) {
                    val mode = StressMode.of(record.mode) ?: continue
                    val peak = record.summary.peakWatts?.takeIf(Double::isFinite) ?: continue
                    if (!record.summary.powerValid) continue
                    // Older files without device metadata still belong to this private, non-backed-up store.
                    val measuredOn = record.device
                    if (measuredOn != null && (measuredOn.manufacturer != device.manufacturer || measuredOn.model != device.model)) continue
                    val card = mode.homeGroup
                    best[card] = maxOf(best[card] ?: peak, peak)
                }
                HomeHistory(records.firstOrNull(), best)
            }
        }
    }

    fun choose(mode: StressMode? = null, duration: StressDuration? = null, quality: SceneQuality? = null) {
        graph.settings.update { s ->
            s.copy(
                lastMode = mode ?: s.lastMode,
                lastDuration = duration ?: s.lastDuration,
                quality = quality ?: s.quality,
            )
        }
    }

    /** Why device safety would not start a test now, or null. */
    fun startBlock(mode: StressMode): SafetyCheck? {
        if (!graph.settings.current.deviceSafety || mode == StressMode.Charge) return null
        return graph.sampler.latest.value?.let { SafetyPolicy.startBlock(it) }
    }

    /** [startBlock], kept current, so the home screen can say so before the button is pressed. */
    val block: StateFlow<SafetyCheck?> = combine(graph.sampler.latest, graph.settings.settings) { sample, settings ->
        if (settings.deviceSafety && settings.lastMode != StressMode.Charge) sample?.let { SafetyPolicy.startBlock(it) } else null
    }
        .distinctUntilChanged { a, b -> a?.reason == b?.reason && a?.value?.toInt() == b?.value?.toInt() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(1_000), null)
}


private val Limits = SafetyLimits()
private val HomeModes = StressMode.entries.map { it.homeGroup }.distinct()

@Composable
fun HomeScreen(
    onStart: (StressMode, StressDuration) -> Unit,
    onOpenRun: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = context()
    val graph = context.graph
    val model: HomeViewModel = viewModel { HomeViewModel(graph) }
    val settings by model.settings.collectAsStateWithLifecycle()
    val live by model.live.collectAsStateWithLifecycle()
    val capabilities by model.capabilities.collectAsStateWithLifecycle()
    val device by model.device.collectAsStateWithLifecycle()
    val runs by model.runs.collectAsStateWithLifecycle()
    val gate by model.block.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    var blocked by remember { mutableStateOf<SafetyCheck?>(null) }
    var cinematic by rememberSaveable { mutableStateOf(settings.lastMode.takeIf { it.scene != null } ?: StressMode.Cinematic) }
    LaunchedEffect(Unit) { model.refreshHistory() }

    val gpuOk = capabilities?.gpuTests == true
    val mode = settings.lastMode.takeIf { capabilities?.gpuTests != false || !it.usesGpu } ?: StressMode.Cpu
    val durations = if (mode == StressMode.Charge) ChargeDurations else StressDuration.entries
    val duration = settings.lastDuration.takeIf { it in durations } ?: StressDuration.Five
    val quality = settings.quality
    val charge = mode == StressMode.Charge
    val safety = charge || settings.deviceSafety
    val summary = listOfNotNull(
        Labels.modeName(context, mode),
        stringResource(Labels.duration(duration, mode)),
        if (mode.scene != null) stringResource(Labels.quality(quality)) else null,
    ).joinToString(" · ")

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        TopBar(
            caption = device?.let(::deviceCaption) ?: stringResource(R.string.home_tagline),
            brand = { Wordmark(version = "v${graph.appVersion}") },
            actions = {
                StatusChip(
                    text = if (safety) {
                        val limit = if (charge) ChargeSession.STOP_CELSIUS else Limits.chipStop
                        stringResource(R.string.home_protection_limit, limit.toInt())
                    } else {
                        stringResource(R.string.safety_off_short)
                    },
                    color = if (safety) StressColors.Good else StressColors.Bad,
                    icon = if (safety) Icons.Rounded.Shield else Icons.Rounded.GppBad,
                    onClick = onOpenSettings.takeUnless { charge },
                )
            },
        )
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SelectedTest(mode, duration, quality, device?.vulkan)
            LiveStatus(live)
            if (live?.plugged == true && mode != StressMode.Charge) {
                Notice(Icons.Rounded.BatteryChargingFull, stringResource(R.string.charge_home_plugged), StressColors.Warn) {
                    model.choose(mode = StressMode.Charge)
                }
            }
            if (capabilities?.gpuTests == false) {
                Notice(Icons.Rounded.Info, stringResource(R.string.home_no_vulkan), StressColors.Cool)
            }
            gate?.let { check ->
                Notice(Icons.Rounded.Shield, stringResource(R.string.home_cooling, Labels.safetyFinding(context, check)), StressColors.Warn)
            }

            SectionLabel(stringResource(R.string.home_section_mode))
            ModeGrid(mode.homeGroup, gpuOk, runs.bestWatts) {
                model.choose(mode = if (it == StressMode.Cinematic) cinematic else it)
            }

            AnimatedVisibility(
                visible = mode.scene != null,
                enter = expandVertically(StressMotion.enter()) + fadeIn(StressMotion.enter()),
                exit = shrinkVertically(StressMotion.exit()) + fadeOut(StressMotion.exit()),
            ) {
                val scene = mode.scene ?: cinematic.scene ?: SceneKind.Pool
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionLabel(stringResource(R.string.home_scenes))
                    Row(Modifier.fillMaxWidth().selectableGroup(), Arrangement.spacedBy(8.dp)) {
                        StressMode.cinematic.forEach { choice ->
                            val kind = requireNotNull(choice.scene)
                            SceneCard(
                                image = kind.still(),
                                name = stringResource(Labels.scene(kind)),
                                selected = scene == kind,
                                modifier = Modifier.weight(1f).heightIn(min = 72.dp),
                                onClick = {
                                    cinematic = choice
                                    model.choose(mode = choice)
                                },
                            )
                        }
                    }
                    Text(stringResource(Labels.sceneDetail(scene)), style = StressType.BodySmall, color = StressColors.TextDim)
                    SectionLabel(stringResource(R.string.home_quality))
                    SegmentedControl(
                        options = SceneQuality.entries,
                        selected = quality,
                        onSelect = { model.choose(quality = it) },
                        label = {
                            "${stringResource(Labels.quality(it))} ${Format.percentValue(it.scalePercent(scene))}"
                        },
                    )
                    Text(stringResource(Labels.qualityDetail(quality)), style = StressType.BodySmall, color = StressColors.TextDim)
                }
            }

            SectionLabel(stringResource(R.string.home_section_duration))
            ChoiceChips(
                options = durations,
                selected = duration,
                onSelect = { model.choose(duration = it) },
                label = { it.seconds?.let { seconds -> stringResource(R.string.home_duration_short, seconds / 60) }
                    ?: stringResource(Labels.duration(it, mode)) },
                accessibleLabel = { stringResource(Labels.duration(it, mode)) },
            )

            runs.last?.let { LastRun(it) { onOpenRun(it.id) } }
        }

        Hairline()
        Column(
            Modifier.fillMaxWidth().background(StressColors.Background).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PrimaryButton(
                text = stringResource(R.string.home_start),
                icon = Icons.Rounded.PlayArrow,
                enabled = !mode.usesGpu || gpuOk,
                onClick = {
                    val block = model.startBlock(mode)
                    if (block != null) {
                        blocked = block
                        if (settings.haptics) haptics.performHapticFeedback(HapticFeedbackType.Reject)
                    } else {
                        if (settings.haptics) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        onStart(mode, duration)
                    }
                },
            )
            Text(summary, style = StressType.Code, color = StressColors.TextDim, textAlign = TextAlign.Center)
        }
    }

    blocked?.let { check ->
        StressDialog(
            onDismissRequest = { blocked = null },
            icon = { Icon(Icons.Rounded.Shield, null, tint = StressColors.Warn) },
            title = { Text(stringResource(R.string.home_blocked_title)) },
            text = { Text(stringResource(R.string.home_blocked_text, Labels.safetyFinding(context, check))) },
            confirmButton = { TextButton(onClick = { blocked = null }) { Text(stringResource(R.string.ok)) } },
        )
    }
}

private fun deviceCaption(device: DeviceInfo): String {
    val soc = device.soc?.let { it.substringAfterLast('(', it).removeSuffix(")") }
    val gpu = device.gpu?.let { name ->
        if (name.contains("SwiftShader", ignoreCase = true)) {
            "SwiftShader"
        } else {
            name.replace(" (TM)", "", ignoreCase = true).replace(" (R)", "", ignoreCase = true)
                .removePrefix("ANGLE ").substringBefore(" /").substringBefore(" (")
        }
    }
    val parts = listOfNotNull(device.title, soc, gpu).map(String::trim).filter(String::isNotEmpty)
    val budget = 44 - 4 * (parts.size - 1)
    val share = budget / parts.size.coerceAtLeast(1)
    var spare = parts.sumOf { (share - it.length).coerceAtLeast(0) }
    return parts.joinToString(" // ") { part ->
        val extra = (part.length - share).coerceIn(0, spare)
        spare -= extra
        val limit = share + extra
        val short = if (part.length <= limit) part else part.take(limit - 1).trimEnd() + "…"
        short.upper()
    }
}

@Composable
private fun SelectedTest(mode: StressMode, duration: StressDuration, quality: SceneQuality, vulkan: String?) {
    val scene = mode.scene
    val selectedDuration = duration.seconds?.let { Format.clock(it.toDouble()).padStart(5, '0') }
        ?: stringResource(Labels.duration(duration, mode))
    val settings = if (scene == null) selectedDuration else
        "${stringResource(Labels.quality(quality))} ${Format.percentValue(quality.scalePercent(scene))} // $selectedDuration"
    Panel(Modifier.fillMaxWidth(), padding = 0.dp) {
        Box(Modifier.fillMaxWidth().heightIn(min = if (scene == null) 112.dp else 168.dp)) {
            if (scene != null) {
                Image(
                    painterResource(scene.still()), null,
                    Modifier.matchParentSize().padding(1.dp), contentScale = ContentScale.Crop,
                )
                Box(
                    Modifier.matchParentSize().padding(1.dp).background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.36f),
                            0.30f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.94f),
                        ),
                    ),
                )
            }
            Column(Modifier.align(if (scene != null) Alignment.TopStart else Alignment.CenterStart).padding(16.dp)) {
                if (scene != null) {
                    val api = vulkan?.split('.')?.take(2)?.joinToString(".") ?: Format.MISSING
                    CodeTag(
                        "VULKAN $api // ${stringResource(R.string.mode_cinematic).upper()}",
                        StressColors.Cool, background = StressColors.Background.copy(alpha = 0.92f),
                    )
                    // Leave room for the still and let the hero grow if a title wraps.
                    Spacer(Modifier.height(32.dp))
                }
                Text(
                    stringResource(scene?.let(Labels::scene) ?: Labels.modeTitle(mode)).upper(),
                    style = StressType.Display,
                )
                Text(
                    if (mode == StressMode.Charge) stringResource(R.string.mode_charge_detail) else mode.recipe.replace("+", " + "),
                    style = StressType.Code,
                    color = if (scene == null) StressColors.TextDim else StressColors.Text,
                )
                Text(settings.upper(), style = StressType.Code, color = StressColors.TextDim)
            }
        }
    }
}

@Composable
private fun LiveStatus(live: LiveView?) {
    val plugged = live?.plugged == true
    val chip = live?.chipCelsius
    val battery = live?.batteryCelsius
    val chipColor = StressColors.temperature(chip, Limits.chipWarn, Limits.chipStop)
    val batteryColor = StressColors.temperature(battery, Limits.batteryWarn, Limits.batteryStop)
    TileRow(Modifier.height(IntrinsicSize.Min)) {
        MetricTile(
            stringResource(R.string.home_live_power),
            if (plugged) stringResource(R.string.home_live_charging) else Format.number(live?.watts, 1),
            Modifier.weight(1f).fillMaxHeight(), unit = if (plugged) null else "W",
            color = if (plugged) StressColors.Warn else StressColors.Accent,
            statusColor = when {
                plugged -> StressColors.Warn
                live?.watts != null -> StressColors.Accent
                else -> null
            },
            caption = live?.volts?.let { "${Format.number(it, 2)} V" },
            padding = 8.dp,
        )
        MetricTile(
            stringResource(R.string.home_live_chip), Format.number(chip, 0), Modifier.weight(1f).fillMaxHeight(),
            unit = "°C", color = chipColor, statusColor = chipColor.takeIf { chip != null },
            caption = live?.let { stringResource(Labels.thermalStatus(it.thermalStatus)) } ?: Format.MISSING,
            padding = 8.dp,
        )
        MetricTile(
            stringResource(R.string.home_live_battery), Format.percentValue(live?.levelPercent), Modifier.weight(1f).fillMaxHeight(),
            statusColor = StressColors.Text.takeIf { live?.levelPercent != null },
            caption = Format.celsius(battery, 0), captionColor = batteryColor, padding = 8.dp,
        )
    }
    if (live != null && (chip == null || battery == null || live.levelPercent == null || (!plugged && live.watts == null))) {
        Text(stringResource(R.string.home_missing_readings), style = StressType.BodySmall, color = StressColors.TextDim)
    }
}

@Composable
private fun Notice(icon: ImageVector, text: String, color: Color, onClick: (() -> Unit)? = null) {
    Panel(Modifier.fillMaxWidth(), padding = 12.dp, onClick = onClick) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
            Text(text, style = StressType.BodySmall, color = StressColors.TextDim)
        }
    }
}

private val ChargeDurations = listOf(StressDuration.Five, StressDuration.Fifteen, StressDuration.Thirty, StressDuration.Endless)

@Composable
private fun ModeGrid(selected: StressMode, gpuOk: Boolean, bestWatts: Map<StressMode, Double>, onSelect: (StressMode) -> Unit) {
    val (large, compact) = HomeModes.partition { it == StressMode.Full || it.scene != null }
    val (charge, load) = compact.partition { it == StressMode.Charge }
    Column(Modifier.fillMaxWidth().selectableGroup(), Arrangement.spacedBy(12.dp)) {
        for ((width, modes) in listOf(2 to large, 3 to load, 1 to charge)) {
            for (row in modes.chunked(width)) {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), Arrangement.spacedBy(12.dp)) {
                    row.forEach { mode ->
                        ModeCard(mode, mode == selected, gpuOk || !mode.usesGpu, bestWatts[mode], Modifier.weight(1f).fillMaxHeight()) {
                            onSelect(mode)
                        }
                    }
                    repeat(width - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun ModeCard(mode: StressMode, selected: Boolean, enabled: Boolean, best: Double?, modifier: Modifier, onClick: () -> Unit) {
    val titleColor = when {
        !enabled -> StressColors.TextDim
        selected -> StressColors.Accent
        else -> StressColors.Text
    }
    Panel(
        modifier, padding = 12.dp, selected = selected, selectionColor = StressColors.Accent,
        enabled = enabled, onClick = onClick,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(Labels.modeTitle(mode)), style = StressType.Heading, color = titleColor)
            Text(stringResource(shortDetail(mode)), style = StressType.BodySmall, color = StressColors.TextDim)
            val hardware = parts(mode)
            if (hardware.isNotEmpty()) Text(hardware, style = StressType.Code, color = StressColors.TextDim)
            if (best != null) {
                Text(
                    stringResource(R.string.home_best_power, Format.number(best, 1)).upper(),
                    style = StressType.Code,
                    color = StressColors.Accent,
                )
            }
        }
    }
}

private fun shortDetail(mode: StressMode): Int = when (mode) {
    StressMode.Full -> R.string.home_mode_full_detail
    StressMode.Cinematic -> R.string.home_mode_cinematic_detail
    StressMode.Cpu -> R.string.home_mode_cpu_detail
    StressMode.Gpu -> R.string.home_mode_gpu_detail
    StressMode.Dry -> R.string.home_mode_dry_detail
    else -> Labels.modeDetail(mode)
}

private fun parts(mode: StressMode): String =
    listOfNotNull("CPU".takeIf { mode.usesCpu }, "GPU".takeIf { mode.usesGpu }).joinToString(" + ")

private fun SceneKind.still(): Int = when (this) {
    SceneKind.Pool -> R.drawable.scene_pool
    SceneKind.Forest -> R.drawable.scene_forest
    SceneKind.White -> R.drawable.scene_white
    SceneKind.Hole -> R.drawable.scene_hole
}

@Composable
private fun LastRun(record: RunRecord, onClick: () -> Unit) {
    val context = context()
    SectionLabel(stringResource(R.string.home_section_last))
    Panel(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBadge(
                StressMode.of(record.mode)?.let(Labels::modeIcon) ?: Icons.Rounded.Bolt,
                StressColors.Accent,
                size = 38.dp,
            )
            Column(Modifier.weight(1f)) {
                Text(Labels.recordModeName(context, record), style = StressType.Heading)
                Text(
                    "${Dates.short(record.startedAtMillis)} · ${Format.clock(record.loadSeconds)}",
                    style = StressType.BodySmall,
                    color = StressColors.TextDim,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(Format.watts(record.summary.peakWatts ?: record.summary.meanWatts), style = NumberStyles.Medium, color = StressColors.Accent)
                Text(stringResource(R.string.home_last_peak), style = StressType.Label, color = StressColors.TextDim)
            }
        }
        if (record.summary.computationErrors > 0) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Rounded.Warning, null, tint = StressColors.Bad, modifier = Modifier.size(14.dp))
                Text(
                    pluralStringResource(
                        R.plurals.insight_errors,
                        record.summary.computationErrors.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        record.summary.computationErrors,
                    ),
                    style = StressType.Label,
                    color = StressColors.Bad,
                )
            }
        }
    }
}
