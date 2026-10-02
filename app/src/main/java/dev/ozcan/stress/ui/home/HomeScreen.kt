package dev.ozcan.stress.ui.home

import dev.ozcan.stress.ui.upper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BatteryStd
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.GppBad
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.ozcan.stress.AppGraph
import dev.ozcan.stress.Capabilities
import dev.ozcan.stress.R
import dev.ozcan.stress.device.DeviceInfo
import dev.ozcan.stress.engine.SceneKind
import dev.ozcan.stress.engine.SceneQuality
import dev.ozcan.stress.graph
import dev.ozcan.stress.run.RunRecord
import dev.ozcan.stress.run.StressDuration
import dev.ozcan.stress.run.StressMode
import dev.ozcan.stress.safety.SafetyCheck
import dev.ozcan.stress.safety.SafetyPolicy
import dev.ozcan.stress.settings.Settings
import dev.ozcan.stress.ui.BottomBarSpace
import dev.ozcan.stress.ui.Dates
import dev.ozcan.stress.ui.Format
import dev.ozcan.stress.ui.Labels
import dev.ozcan.stress.ui.LiveView
import dev.ozcan.stress.ui.components.GaugeMark
import dev.ozcan.stress.ui.components.GlassCard
import dev.ozcan.stress.ui.components.Wordmark
import dev.ozcan.stress.ui.components.IconBadge
import dev.ozcan.stress.ui.components.Pill
import dev.ozcan.stress.ui.components.PrimaryButton
import dev.ozcan.stress.ui.components.SectionHeader
import dev.ozcan.stress.ui.components.SegmentedControl
import dev.ozcan.stress.ui.context
import dev.ozcan.stress.ui.theme.NumberStyles
import dev.ozcan.stress.ui.theme.StressColors
import kotlinx.coroutines.Dispatchers
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

class HomeViewModel(private val graph: AppGraph) : ViewModel() {

    val settings: StateFlow<Settings> = graph.settings.settings

    val live: StateFlow<LiveView?> = graph.sampler.latest
        .map { LiveView.from(graph.sampler.log.recent(LiveView.WINDOW_SAMPLES), graph.layout) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(1_000), null)

    private val _capabilities = MutableStateFlow<Capabilities?>(null)
    val capabilities: StateFlow<Capabilities?> = _capabilities.asStateFlow()

    private val _device = MutableStateFlow<DeviceInfo?>(null)
    val device: StateFlow<DeviceInfo?> = _device.asStateFlow()

    private val _last = MutableStateFlow<RunRecord?>(null)
    val last: StateFlow<RunRecord?> = _last.asStateFlow()

    init {
        viewModelScope.launch { _capabilities.value = graph.capabilities.await() }
        viewModelScope.launch { _device.value = graph.device.await() }
        refreshLast()
    }

    fun refreshLast() {
        viewModelScope.launch { _last.value = withContext(Dispatchers.IO) { graph.runs.list().firstOrNull() } }
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
    fun startBlock(): SafetyCheck? {
        if (!graph.settings.current.deviceSafety) return null
        return graph.sampler.latest.value?.let { SafetyPolicy.startBlock(it) }
    }

    /** [startBlock], kept current, so the home screen can say so before the button is pressed. */
    val block: StateFlow<SafetyCheck?> = combine(graph.sampler.latest, graph.settings.settings) { sample, settings ->
        if (settings.deviceSafety) sample?.let { SafetyPolicy.startBlock(it) } else null
    }
        .distinctUntilChanged { a, b -> a?.reason == b?.reason && a?.value?.toInt() == b?.value?.toInt() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(1_000), null)
}

@Composable
fun HomeScreen(
    onStart: (StressMode, StressDuration) -> Unit,
    onOpenRun: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val graph = context().graph
    val model: HomeViewModel = viewModel { HomeViewModel(graph) }
    val settings by model.settings.collectAsStateWithLifecycle()
    val live by model.live.collectAsStateWithLifecycle()
    val capabilities by model.capabilities.collectAsStateWithLifecycle()
    val device by model.device.collectAsStateWithLifecycle()
    val last by model.last.collectAsStateWithLifecycle()
    val gate by model.block.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    var blocked by remember { mutableStateOf<SafetyCheck?>(null) }
    // Back from a run or the history: the newest run may have changed.
    LaunchedEffect(Unit) { model.refreshLast() }

    val gpuOk = capabilities?.gpuTests != false
    val mode = settings.lastMode.takeIf { gpuOk || !it.usesGpu } ?: StressMode.Cpu
    val duration = settings.lastDuration

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(top = 10.dp, bottom = BottomBarSpace + 96.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Header(device, settings.deviceSafety, onOpenSettings)
            LiveStatus(live)
            if (live?.plugged == true) {
                Notice(Icons.Rounded.BatteryChargingFull, stringResource(R.string.home_plugged), StressColors.Warn)
            }
            if (capabilities?.gpuTests == false) {
                Notice(Icons.Rounded.Info, stringResource(R.string.home_no_vulkan), StressColors.Cool)
            }
            gate?.let { check ->
                val context = context()
                Notice(Icons.Rounded.Shield, stringResource(R.string.home_cooling, Labels.safetyFinding(context, check)), StressColors.Warn)
            }

            SectionHeader(stringResource(R.string.home_section_mode))
            ModeCard(StressMode.Full, mode == StressMode.Full, enabled = gpuOk, badge = stringResource(R.string.home_badge_max)) {
                model.choose(mode = StressMode.Full)
            }
            CinematicCard(
                selected = mode.scene != null,
                current = if (mode.scene != null) mode else StressMode.Cinematic,
                quality = settings.quality,
                enabled = gpuOk,
                onPick = { model.choose(mode = it) },
                onQuality = { model.choose(quality = it) },
            )
            ModeCard(StressMode.Cpu, mode == StressMode.Cpu) { model.choose(mode = StressMode.Cpu) }
            ModeCard(StressMode.Gpu, mode == StressMode.Gpu, enabled = gpuOk) { model.choose(mode = StressMode.Gpu) }
            ModeCard(StressMode.Dry, mode == StressMode.Dry, badge = stringResource(R.string.home_badge_reference)) {
                model.choose(mode = StressMode.Dry)
            }

            SectionHeader(stringResource(R.string.home_section_duration))
            SegmentedControl(
                options = StressDuration.entries,
                selected = duration,
                onSelect = { model.choose(duration = it) },
                label = { stringResource(Labels.duration(it)) },
            )

            last?.let { LastRun(it) { onOpenRun(it.id) } }
        }

        // The call to action stays above the tab bar, over a fade of the content.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, StressColors.Background.copy(alpha = 0.92f), StressColors.Background)))
                .navigationBarsPadding()
                .padding(start = 18.dp, end = 18.dp, top = 24.dp, bottom = BottomBarSpace - 8.dp),
        ) {
            val context = context()
            PrimaryButton(
                text = stringResource(R.string.home_start),
                subtitle = "${Labels.modeName(context, mode)} · ${stringResource(Labels.duration(duration))}" +
                    if (mode.scene != null) " · ${stringResource(Labels.quality(settings.quality))}" else "",
                icon = Icons.Rounded.PlayArrow,
                onClick = {
                    val block = model.startBlock()
                    if (block != null) {
                        blocked = block
                        if (settings.haptics) haptics.performHapticFeedback(HapticFeedbackType.Reject)
                    } else {
                        if (settings.haptics) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        onStart(mode, duration)
                    }
                },
            )
        }
    }

    blocked?.let { check ->
        val context = context()
        AlertDialog(
            onDismissRequest = { blocked = null },
            icon = { Icon(Icons.Rounded.Shield, null, tint = StressColors.Warn) },
            title = { Text(stringResource(R.string.home_blocked_title)) },
            text = {
                Text(
                    stringResource(R.string.home_blocked_text, Labels.safetyFinding(context, check)),
                    color = StressColors.TextDim,
                )
            },
            confirmButton = { TextButton(onClick = { blocked = null }) { Text(stringResource(R.string.ok)) } },
            containerColor = StressColors.SurfaceHigh,
        )
    }
}

@Composable
private fun Header(device: DeviceInfo?, safety: Boolean, onOpenSettings: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        GaugeMark(Modifier.size(42.dp))
        Column(Modifier.weight(1f)) {
            Wordmark(letterSpacing = 5.sp)
            Text(
                device?.let { d -> listOfNotNull(d.title, d.soc).joinToString(" · ") } ?: stringResource(R.string.home_tagline),
                style = MaterialTheme.typography.bodySmall,
                color = StressColors.TextDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Pill(
            text = stringResource(if (safety) R.string.safety_on_short else R.string.safety_off_short),
            color = if (safety) StressColors.Good else StressColors.Bad,
            icon = if (safety) Icons.Rounded.Shield else Icons.Rounded.GppBad,
            modifier = Modifier.clip(CircleShape).clickable(onClick = onOpenSettings),
        )
    }
}

/** The phone right now: battery, chip temperature, power. */
@Composable
private fun LiveStatus(live: LiveView?) {
    GlassCard(padding = 14.dp) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            LiveItem(
                icon = if (live?.plugged == true) Icons.Rounded.BatteryChargingFull else Icons.Rounded.BatteryStd,
                label = stringResource(R.string.home_live_battery),
                value = Format.percentValue(live?.levelPercent),
                sub = Format.celsius(live?.batteryCelsius),
                tint = StressColors.Good,
            )
            LiveItem(
                icon = Icons.Rounded.Thermostat,
                label = stringResource(R.string.home_live_chip),
                value = live?.chipCelsius?.let { Format.number(it, 0) + "°" } ?: Format.MISSING,
                sub = live?.let { stringResource(R.string.home_live_thermal, stringResource(Labels.thermalStatus(it.thermalStatus))) }
                    ?: Format.MISSING,
                tint = StressColors.Accent,
            )
            LiveItem(
                icon = Icons.Rounded.Bolt,
                label = stringResource(R.string.home_live_power),
                value = when {
                    live == null -> Format.MISSING
                    live.plugged -> stringResource(R.string.home_live_charging)
                    else -> Format.number(live.watts, 2)
                },
                sub = if (live != null && !live.plugged && live.watts != null) "W" else " ",
                tint = StressColors.Cool,
            )
        }
    }
}

@Composable
private fun LiveItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, sub: String, tint: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(14.dp))
            Text(label.upper(), style = MaterialTheme.typography.labelSmall, color = StressColors.TextDim, letterSpacing = 1.sp)
        }
        Text(value, style = NumberStyles.Medium, color = StressColors.Text, maxLines = 1)
        Text(sub, style = MaterialTheme.typography.labelSmall, color = StressColors.TextFaint, maxLines = 1)
    }
}

@Composable
private fun Notice(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, color: Color) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = StressColors.Text)
    }
}

/** The radio mark on the right of a mode card: a ring, filled with a check when chosen. */
@Composable
private fun Selector(selected: Boolean) {
    val fill by animateFloatAsState(if (selected) 1f else 0f, tween(220), label = "selector")
    val ring by animateColorAsState(if (selected) StressColors.Accent else StressColors.OutlineBright, label = "ring")
    Box(
        Modifier.size(24.dp).border(2.dp, ring, CircleShape).padding(3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = fill
                    scaleY = fill
                    alpha = fill
                }
                .background(StressColors.Heat, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(12.dp))
        }
    }
}

@Composable
private fun ModeTags(mode: StressMode) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (mode.usesCpu) Pill("CPU", StressColors.Accent)
        if (mode.usesGpu) Pill("GPU", StressColors.Cool)
    }
}

@Composable
private fun ModeCard(mode: StressMode, selected: Boolean, enabled: Boolean = true, badge: String? = null, onClick: () -> Unit) {
    GlassCard(
        modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = if (enabled) 1f else 0.45f },
        highlight = selected,
        onClick = if (enabled) onClick else null,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconBadge(Labels.modeIcon(mode), if (selected) StressColors.Accent else StressColors.TextDim, size = 44.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(Labels.modeTitle(mode)), style = MaterialTheme.typography.titleMedium)
                    if (badge != null) Pill(badge, if (mode == StressMode.Full) StressColors.AccentHot else StressColors.TextDim)
                }
                Text(stringResource(Labels.modeDetail(mode)), style = MaterialTheme.typography.bodySmall, color = StressColors.TextDim)
                ModeTags(mode)
            }
            Selector(selected)
        }
    }
}

@Composable
private fun CinematicCard(
    selected: Boolean,
    current: StressMode,
    quality: SceneQuality,
    enabled: Boolean,
    onPick: (StressMode) -> Unit,
    onQuality: (SceneQuality) -> Unit,
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = if (enabled) 1f else 0.45f },
        highlight = selected,
        onClick = if (enabled) ({ onPick(current) }) else null,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconBadge(Labels.modeIcon(current), if (selected) StressColors.Accent else StressColors.TextDim, size = 44.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(stringResource(R.string.mode_cinematic), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.mode_cinematic_detail), style = MaterialTheme.typography.bodySmall, color = StressColors.TextDim)
                ModeTags(current)
            }
            Selector(selected)
        }
        AnimatedVisibility(
            visible = selected && enabled,
            enter = expandVertically(tween(320)) + fadeIn(tween(320)),
            exit = shrinkVertically(tween(260)) + fadeOut(tween(200)),
        ) {
            Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StressMode.cinematic.forEach { m ->
                        SceneThumb(m.scene!!, m == current, Modifier.weight(1f)) { onPick(m) }
                    }
                }
                Text(
                    stringResource(Labels.sceneDetail(current.scene ?: SceneKind.Pool)),
                    style = MaterialTheme.typography.bodySmall,
                    color = StressColors.TextDim,
                )
                Text(
                    stringResource(R.string.home_quality).upper(),
                    style = MaterialTheme.typography.labelSmall,
                    color = StressColors.TextDim,
                    letterSpacing = 1.4.sp,
                )
                SegmentedControl(
                    options = SceneQuality.entries,
                    selected = quality,
                    onSelect = onQuality,
                    label = { stringResource(Labels.quality(it)) },
                    height = 40.dp,
                )
                Text(stringResource(Labels.qualityDetail(quality)), style = MaterialTheme.typography.bodySmall, color = StressColors.TextFaint)
            }
        }
    }
}

@Composable
private fun SceneThumb(kind: SceneKind, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val border by animateColorAsState(if (selected) StressColors.Accent else StressColors.Outline, label = "thumb")
    val scale by animateFloatAsState(if (selected) 1f else 0.95f, label = "thumbScale")
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .aspectRatio(0.72f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .border(if (selected) 2.dp else 1.dp, border, shape)
            .clickable(onClick = onClick),
    ) {
        Image(
            painter = painterResource(
                when (kind) {
                    SceneKind.Pool -> R.drawable.scene_pool
                    SceneKind.Forest -> R.drawable.scene_forest
                    SceneKind.White -> R.drawable.scene_white
                },
            ),
            contentDescription = stringResource(Labels.scene(kind)),
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (selected) 1f else 0.7f },
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f))),
        )
        Text(
            stringResource(Labels.scene(kind)),
            modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
        )
        if (selected) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(8.dp).size(22.dp).background(StressColors.Heat, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
private fun LastRun(record: RunRecord, onClick: () -> Unit) {
    val context = context()
    SectionHeader(stringResource(R.string.home_section_last))
    GlassCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBadge(
                StressMode.of(record.mode)?.let(Labels::modeIcon) ?: Icons.Rounded.Bolt,
                StressColors.Accent,
                size = 38.dp,
            )
            Column(Modifier.weight(1f)) {
                Text(Labels.recordModeName(context, record), style = MaterialTheme.typography.titleSmall)
                Text(
                    "${Dates.short(record.startedAtMillis)} · ${Format.clock(record.loadSeconds)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = StressColors.TextDim,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(Format.watts(record.summary.peakWatts ?: record.summary.meanWatts), style = NumberStyles.Medium, color = StressColors.Accent)
                Text(stringResource(R.string.home_last_peak), style = MaterialTheme.typography.labelSmall, color = StressColors.TextFaint)
            }
        }
        if (record.summary.computationErrors > 0) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Rounded.Warning, null, tint = StressColors.Bad, modifier = Modifier.size(14.dp))
                Text(
                    stringResource(R.string.insight_errors_title, record.summary.computationErrors),
                    style = MaterialTheme.typography.labelSmall,
                    color = StressColors.Bad,
                )
            }
        }
    }
}
