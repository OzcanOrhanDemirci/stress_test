package dev.ozcan.stress.ui.home

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.GppBad
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shield
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import dev.ozcan.stress.safety.SafetyPolicy
import dev.ozcan.stress.settings.Settings
import dev.ozcan.stress.ui.Format
import dev.ozcan.stress.ui.Labels
import dev.ozcan.stress.ui.LiveView
import dev.ozcan.stress.ui.charge.rememberNotificationAsk
import dev.ozcan.stress.ui.components.ChoiceChips
import dev.ozcan.stress.ui.components.ChipShape
import dev.ozcan.stress.ui.components.EngineeringRow
import dev.ozcan.stress.ui.components.FitText
import dev.ozcan.stress.ui.components.Hairline
import dev.ozcan.stress.ui.components.LogSectionHeader
import dev.ozcan.stress.ui.components.Panel
import dev.ozcan.stress.ui.components.PanelDialog
import dev.ozcan.stress.ui.components.PrimaryButton
import dev.ozcan.stress.ui.components.SceneCard
import dev.ozcan.stress.ui.components.SecondaryButton
import dev.ozcan.stress.ui.components.SectionLabel
import dev.ozcan.stress.ui.components.StatusChip
import dev.ozcan.stress.ui.components.TwoPages
import dev.ozcan.stress.ui.components.StressDialog
import dev.ozcan.stress.ui.components.TopBar
import dev.ozcan.stress.ui.components.Wordmark
import dev.ozcan.stress.ui.context
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

internal val HomeModes = StressMode.entries.map { it.homeGroup }.distinct()

/**
 * The Test tab: a simple page with only what a start needs (DESIGN "What each simple page keeps", Home row), and
 * its engineering page with everything else the tab knows. Scene quality is chosen in Settings; the hero shows it.
 */
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
    val live = calmLive(model.live.collectAsStateWithLifecycle())
    val capabilities by model.capabilities.collectAsStateWithLifecycle()
    val device by model.device.collectAsStateWithLifecycle()
    val runs by model.runs.collectAsStateWithLifecycle()
    val gate by model.block.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    var blocked by remember { mutableStateOf<SafetyCheck?>(null) }
    var engineering by rememberSaveable { mutableStateOf(false) }
    var guide by rememberSaveable { mutableStateOf(false) }
    var cinematic by rememberSaveable { mutableStateOf(settings.lastMode.takeIf { it.scene != null } ?: StressMode.Cinematic) }
    LaunchedEffect(Unit) { model.refreshHistory() }

    val gpuOk = capabilities?.gpuTests == true
    val mode = settings.lastMode.takeIf { capabilities?.gpuTests != false || !it.usesGpu } ?: StressMode.Cpu
    val durations = if (mode == StressMode.Charge) ChargeDurations else StressDuration.entries
    val duration = settings.lastDuration.takeIf { it in durations } ?: StressDuration.Five
    val quality = settings.quality
    val charge = mode == StressMode.Charge
    val safety = charge || settings.deviceSafety
    // A charge test begun at full would have nothing to measure; the run screen still catches a full status.
    val chargeFull = charge && ChargeSession.isFull(live?.levelPercent)
    val askNotification = rememberNotificationAsk()
    val log = homeLog(
        context,
        HomeFacts(
            mode, duration, quality, settings, live, capabilities, device, runs, gate,
            graph.layout, graph.cpu.kernels, graph.gpu.burners, graph.appVersion,
        ),
    )

    val start: @Composable () -> Unit = {
        PrimaryButton(
            text = stringResource(R.string.home_start),
            icon = Icons.Rounded.PlayArrow,
            enabled = (!mode.usesGpu || gpuOk) && !chargeFull,
            onClick = {
                val block = model.startBlock(mode)
                if (block != null) {
                    blocked = block
                    if (settings.haptics) haptics.performHapticFeedback(HapticFeedbackType.Reject)
                } else {
                    if (settings.haptics) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    // The charge test can say when the battery fills; Android 13+ asks first.
                    if (charge) askNotification { onStart(mode, duration) } else onStart(mode, duration)
                }
            },
        )
    }
    // A phone pins the start button above the tab bar; a tablet's tall page would leave it far below the content
    // it starts, so there it follows the content.
    val wide = LocalConfiguration.current.screenWidthDp >= 600

    // Only notices that hold or redirect a start; their full text is on the engineering page. They sit with the start
    // button, below everything a mode changes, so a notice that comes or goes with the mode (charger connected,
    // battery full) never moves the hero, the modes or the durations.
    val notices = listOfNotNull(
        if (live?.plugged == true && !charge) {
            HomeNoticeLine(Icons.Rounded.BatteryChargingFull, stringResource(R.string.home_notice_plugged), StressColors.Warn) {
                model.choose(mode = StressMode.Charge)
            }
        } else {
            null
        },
        if (chargeFull) HomeNoticeLine(Icons.Rounded.BatteryFull, stringResource(R.string.home_notice_charge_full), StressColors.Cool) else null,
        if (capabilities?.gpuTests == false) {
            HomeNoticeLine(Icons.Rounded.Info, stringResource(R.string.home_notice_no_vulkan), StressColors.Cool)
        } else {
            null
        },
        gate?.let { check ->
            HomeNoticeLine(Icons.Rounded.Shield, stringResource(R.string.home_notice_hot, Labels.safetyFinding(context, check)), StressColors.Warn)
        },
    )

    TwoPages(engineering, engineeringPage = { HomeEngineeringPage(log, onOpenRun) { engineering = false } }) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // The phone and its chip are the Device tab's; the version is in Settings, About.
            TopBar(
                brand = { Wordmark() },
                actions = {
                    StatusChip(
                        text = stringResource(if (safety) R.string.protection_chip else R.string.safety_off_short),
                        color = if (safety) StressColors.Good else StressColors.Bad,
                        // The charge test's chip does not open Settings, but keeps the bar's height.
                        modifier = Modifier.heightIn(min = 48.dp),
                        icon = if (safety) Icons.Rounded.Shield else Icons.Rounded.GppBad,
                        onClick = onOpenSettings.takeUnless { charge },
                    )
                },
            )
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SelectedTest(mode, duration, quality)

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeLabel { guide = true }
                    ModeGrid(mode.homeGroup, gpuOk, runs.bestWatts) {
                        model.choose(mode = if (it == StressMode.Cinematic) cinematic else it)
                    }
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
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionLabel(stringResource(R.string.home_section_duration))
                    ChoiceChips(
                        options = durations,
                        selected = duration,
                        onSelect = { model.choose(duration = it) },
                        label = { it.seconds?.let { seconds -> stringResource(R.string.home_duration_short, seconds / 60) }
                            ?: stringResource(Labels.duration(it, mode)) },
                        accessibleLabel = { stringResource(Labels.duration(it, mode)) },
                    )
                }

                EngineeringRow({ engineering = true })
                if (wide) {
                    notices.forEach { Notice(it) }
                    start()
                }
            }

            if (!wide) {
                Hairline()
                Column(
                    Modifier.fillMaxWidth().background(StressColors.Background).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    notices.forEach { Notice(it) }
                    start()
                }
            }
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
    if (guide) ModeGuide { guide = false }
}

/**
 * One height for every mode's hero, so choosing a mode never moves what lies below it; lower on a short screen, where
 * the modes and durations would otherwise fall below the start button.
 */
@Composable
private fun heroHeight() = if (LocalConfiguration.current.screenHeightDp < 700) 120.dp else 144.dp

/**
 * The hero: the selected mode, or the scene's still with its name, with quality and duration in one Code line. It
 * keeps [heroHeight] in every mode and each of its lines stays on one line, so no mode makes it taller.
 */
@Composable
private fun SelectedTest(mode: StressMode, duration: StressDuration, quality: SceneQuality) {
    val scene = mode.scene
    val time = duration.seconds?.let { Format.clock(it.toDouble()).padStart(5, '0') }
        ?: stringResource(Labels.duration(duration, mode))
    val caption = listOfNotNull(
        if (scene != null) stringResource(R.string.mode_cinematic) else modeParts(mode),
        scene?.let { "${stringResource(Labels.quality(quality))} ${Format.percentValue(quality.scalePercent(it))}" },
        time,
    ).joinToString(" // ")
    val title = stringResource(scene?.let(Labels::scene) ?: Labels.modeTitle(mode)).upper()
    val captionColor = if (scene != null) StressColors.Text else StressColors.TextDim
    Panel(Modifier.fillMaxWidth(), padding = 0.dp) {
        Box(Modifier.fillMaxWidth().height(heroHeight())) {
            if (scene != null) {
                Image(painterResource(scene.still()), null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
                Box(
                    Modifier.matchParentSize().background(
                        Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.92f)),
                    ),
                )
                Column(
                    Modifier.align(Alignment.BottomStart).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    FitText(title, StressType.Display, color = StressColors.Text)
                    FitText(caption.upper(), StressType.Code, color = captionColor)
                }
            } else {
                // The mode and what it does at the top, the test's settings at the foot, as on a scene's card.
                Column(Modifier.matchParentSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        FitText(title, StressType.Display, color = StressColors.Accent)
                        FitText(stringResource(shortDetail(mode)), StressType.Body, color = StressColors.TextDim)
                    }
                    FitText(caption.upper(), StressType.Code, color = captionColor)
                }
            }
        }
    }
}

/** A notice of the simple page: [onClick] makes it a control that leads somewhere. */
private class HomeNoticeLine(val icon: ImageVector, val text: String, val color: Color, val onClick: (() -> Unit)? = null)

/** A one-line notice: a readout, or with an action a control that leads somewhere (chevron). */
@Composable
private fun Notice(notice: HomeNoticeLine) {
    val onClick = notice.onClick
    Panel(Modifier.fillMaxWidth(), padding = 12.dp, onClick = onClick) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(notice.icon, null, tint = notice.color, modifier = Modifier.size(18.dp))
            Text(
                notice.text, Modifier.weight(1f), style = StressType.BodySmall,
                color = if (onClick != null) StressColors.Text else StressColors.TextDim,
            )
            if (onClick != null) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = StressColors.TextDim)
        }
    }
}

/** The "Test mode" label with the question mark beside it that opens [ModeGuide]. */
@Composable
private fun ModeLabel(onHelp: () -> Unit) {
    val description = stringResource(R.string.home_guide_open)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.home_section_mode).upper(), color = StressColors.TextDim, style = StressType.Label)
        // A 40 dp target around a 24 dp outlined square: a control, set apart from the label it explains.
        Box(
            Modifier.size(40.dp).clip(ChipShape).clickable(role = Role.Button, onClick = onHelp)
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(24.dp).background(StressColors.SurfaceHigh, ChipShape).border(1.dp, StressColors.OutlineStrong, ChipShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("?", style = StressType.Label.copy(letterSpacing = 0.sp), color = StressColors.Text)
            }
        }
    }
}

/** What each test mode is for, as a log: the mode's name as the key, one or two sentences as its line. */
@Composable
private fun ModeGuide(onClose: () -> Unit) {
    PanelDialog(onClose) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LogSectionHeader(stringResource(R.string.home_guide_title), HomeModes.size.toString())
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                HomeModes.forEachIndexed { index, mode ->
                    if (index > 0) Hairline()
                    GuideLine(stringResource(Labels.modeTitle(mode)), stringResource(guideText(mode)))
                }
            }
            SecondaryButton(stringResource(R.string.parts_close), onClose)
        }
    }
}

/** A guide line: the key in Code `Accent` beside its sentences, or above them where the dialog is narrow. */
@Composable
private fun GuideLine(key: String, text: String) = BoxWithConstraints(Modifier.fillMaxWidth()) {
    val side = maxWidth >= 360.dp
    val keyText: @Composable (Modifier) -> Unit = { modifier ->
        Text(key.upper(), modifier, style = StressType.Code, color = StressColors.Accent)
    }
    val body: @Composable (Modifier) -> Unit = { modifier ->
        Text(text, modifier, style = StressType.BodySmall, color = StressColors.Text)
    }
    if (side) {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), Arrangement.spacedBy(12.dp)) {
            keyText(Modifier.width(96.dp).padding(top = 1.dp))
            body(Modifier.weight(1f))
        }
    } else {
        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            keyText(Modifier)
            body(Modifier)
        }
    }
}

private fun guideText(mode: StressMode): Int = when (mode) {
    StressMode.Full -> R.string.home_guide_full
    StressMode.Cpu -> R.string.home_guide_cpu
    StressMode.Gpu -> R.string.home_guide_gpu
    StressMode.Dry -> R.string.home_guide_dry
    StressMode.Charge -> R.string.home_guide_charge
    else -> R.string.home_guide_cinematic
}

private val ChargeDurations = listOf(StressDuration.Five, StressDuration.Fifteen, StressDuration.Thirty, StressDuration.Endless)

/**
 * Every test mode, the charge test among them, as compact radio controls in rows of three; rows of two on a narrow
 * screen, where a third column would break a mode's name inside a word.
 */
@Composable
private fun ModeGrid(selected: StressMode, gpuOk: Boolean, bestWatts: Map<StressMode, Double>, onSelect: (StressMode) -> Unit) = BoxWithConstraints {
    val columns = if (maxWidth < 300.dp) 2 else 3
    Column(Modifier.fillMaxWidth().selectableGroup(), Arrangement.spacedBy(8.dp)) {
        for (row in HomeModes.chunked(columns)) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), Arrangement.spacedBy(8.dp)) {
                row.forEach { mode ->
                    ModeCell(mode, mode == selected, gpuOk || !mode.usesGpu, bestWatts[mode], Modifier.weight(1f).fillMaxHeight()) {
                        onSelect(mode)
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ModeCell(mode: StressMode, selected: Boolean, enabled: Boolean, best: Double?, modifier: Modifier, onClick: () -> Unit) {
    val titleColor = when {
        !enabled -> StressColors.TextFaint
        selected -> StressColors.Accent
        else -> StressColors.Text
    }
    Panel(
        modifier, padding = 10.dp, selected = selected, selectionColor = StressColors.Accent,
        enabled = enabled, onClick = onClick,
    ) {
        Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(Labels.modeTitle(mode)), style = StressType.Heading, color = titleColor)
            // The phone's best on this mode when it has one, else what the mode loads.
            Text(
                best?.let { "${Format.number(it, 1)} W" } ?: modeParts(mode).upper(),
                Modifier.padding(top = 4.dp),
                style = StressType.Code,
                color = if (best != null && enabled) StressColors.Accent else StressColors.TextDim,
                maxLines = 1,
            )
        }
    }
}

private fun shortDetail(mode: StressMode): Int = when (mode) {
    StressMode.Full -> R.string.home_mode_full_detail
    StressMode.Cinematic -> R.string.home_mode_cinematic_detail
    StressMode.Cpu -> R.string.home_mode_cpu_detail
    StressMode.Gpu -> R.string.home_mode_gpu_detail
    StressMode.Dry -> R.string.home_mode_dry_detail
    StressMode.Charge -> R.string.home_mode_charge_detail
    else -> Labels.modeDetail(mode)
}

/** What a mode loads ("CPU + GPU"); the charge test loads nothing and measures the battery's input. */
@Composable
private fun modeParts(mode: StressMode): String =
    listOfNotNull("CPU".takeIf { mode.usesCpu }, "GPU".takeIf { mode.usesGpu }).joinToString(" + ")
        .ifEmpty { stringResource(R.string.home_mode_charge_part) }

private fun SceneKind.still(): Int = when (this) {
    SceneKind.Pool -> R.drawable.scene_pool
    SceneKind.Forest -> R.drawable.scene_forest
    SceneKind.White -> R.drawable.scene_white
    SceneKind.Hole -> R.drawable.scene_hole
}
