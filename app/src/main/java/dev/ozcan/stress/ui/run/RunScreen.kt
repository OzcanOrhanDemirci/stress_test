package dev.ozcan.stress.ui.run

import dev.ozcan.stress.ui.upper
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.GppBad
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.ozcan.stress.R
import dev.ozcan.stress.engine.GpuState
import dev.ozcan.stress.graph
import dev.ozcan.stress.run.EndReason
import dev.ozcan.stress.run.RunController
import dev.ozcan.stress.run.RunState
import dev.ozcan.stress.run.StressDuration
import dev.ozcan.stress.run.StressMode
import dev.ozcan.stress.safety.SafetyCheck
import dev.ozcan.stress.safety.SafetyLevel
import dev.ozcan.stress.telemetry.ThermalGroup
import dev.ozcan.stress.ui.Format
import dev.ozcan.stress.ui.GpuSurface
import dev.ozcan.stress.ui.KeepScreenOnEffect
import dev.ozcan.stress.ui.Labels
import dev.ozcan.stress.ui.LiveView
import dev.ozcan.stress.ui.MaxDisplayEffect
import dev.ozcan.stress.ui.components.GaugeMark
import dev.ozcan.stress.ui.components.HeroGauge
import dev.ozcan.stress.ui.components.MeterBar
import dev.ozcan.stress.ui.components.Pill
import dev.ozcan.stress.ui.components.PrimaryButton
import dev.ozcan.stress.ui.components.ProgressRing
import dev.ozcan.stress.ui.components.SecondaryButton
import dev.ozcan.stress.ui.components.Sparkline
import dev.ozcan.stress.ui.context
import dev.ozcan.stress.ui.theme.NumberStyles
import dev.ozcan.stress.ui.theme.StressColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun RunScreen(mode: StressMode, duration: StressDuration, onFinished: (String) -> Unit, onLeave: () -> Unit) {
    val graph = context().graph
    val model: RunViewModel = viewModel { RunViewModel(graph, mode, duration) }
    val state by model.state.collectAsStateWithLifecycle()
    val live by model.live.collectAsStateWithLifecycle()
    val power by model.power.collectAsStateWithLifecycle()
    val idle by model.idleWatts.collectAsStateWithLifecycle()
    val peak by model.peakWatts.collectAsStateWithLifecycle()
    val safety by model.safety.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    var overlay by remember { mutableStateOf(true) }
    var confirmStop by remember { mutableStateOf(false) }

    KeepScreenOnEffect()
    if (model.settings.maxDisplay) MaxDisplayEffect()
    // Off screen the load loses the big cores and the GPU its surface: end the
    // run there and keep what it measured, marked as interrupted.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { model.stop(EndReason.Interrupted) }
    val active = state is RunState.Baseline || state is RunState.Running || state == RunState.Preparing
    BackHandler {
        if (active) confirmStop = true else if (state is RunState.Failed) onLeave()
    }
    LaunchedEffect(state) {
        val finished = state as? RunState.Finished ?: return@LaunchedEffect
        if (model.settings.haptics) {
            haptics.performHapticFeedback(
                if (finished.record.endReason == EndReason.Safety.name) HapticFeedbackType.Reject else HapticFeedbackType.Confirm,
            )
        }
        delay(1_300)
        onFinished(finished.record.id)
    }

    // A clock for the HUD's timers, ticking four times a second.
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtimeNanos()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            now = SystemClock.elapsedRealtimeNanos()
            delay(250)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(remember { MutableInteractionSource() }, indication = null) { overlay = !overlay },
    ) {
        if (mode.usesGpu) {
            GpuSurface(graph.gpu, Modifier.fillMaxSize())
        } else {
            // The gauge belongs to the load: before and after it, the phase card has the middle alone.
            val shown by animateFloatAsState(if (state is RunState.Running) 1f else 0f, tween(450), label = "instrument")
            CpuInstrument(
                live,
                peak,
                model.cpuCount,
                showCores = !overlay,
                Modifier.align(Alignment.Center).graphicsLayer { alpha = shown },
            )
        }

        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            AnimatedVisibility(
                visible = overlay && state is RunState.Running,
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut(),
            ) {
                (state as? RunState.Running)?.let { running ->
                    TopHud(mode, model.settings.quality.takeIf { mode.scene != null }, running, now, live, power, idle, safety, model.settings.deviceSafety)
                }
            }
            Spacer(Modifier.weight(1f))
            AnimatedVisibility(
                visible = overlay && state is RunState.Running,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    BottomHud(live, mode)
                    SecondaryButton(
                        text = stringResource(R.string.run_stop),
                        onClick = { confirmStop = true },
                        icon = Icons.Rounded.Stop,
                        color = StressColors.Bad,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        // Phases other than the load itself take the middle of the screen.
        AnimatedContent(
            targetState = phaseOf(state),
            transitionSpec = { (fadeIn(tween(300)) + scaleIn(initialScale = 0.92f)) togetherWith fadeOut(tween(200)) },
            modifier = Modifier.align(Alignment.Center),
            label = "phase",
        ) { phase ->
            when (phase) {
                Phase.Baseline -> BaselineCard(mode, state as? RunState.Baseline, now, idle) { confirmStop = true }
                Phase.Analysing -> MessageCard(null, stringResource(R.string.run_analysing), StressColors.Text, progress = true)
                Phase.Finished -> FinishedCard(state as? RunState.Finished)
                Phase.Failed -> FailedCard(state as? RunState.Failed, onLeave)
                Phase.Running -> Box(Modifier)
            }
        }
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            icon = { Icon(Icons.Rounded.Stop, null, tint = StressColors.Bad) },
            title = { Text(stringResource(R.string.run_stop_title)) },
            text = { Text(stringResource(R.string.run_stop_text), color = StressColors.TextDim) },
            confirmButton = {
                TextButton(onClick = {
                    confirmStop = false
                    model.stop()
                }) { Text(stringResource(R.string.run_stop_confirm), color = StressColors.Bad) }
            },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text(stringResource(R.string.run_stop_continue)) } },
            containerColor = StressColors.SurfaceHigh,
        )
    }
}

private enum class Phase { Baseline, Running, Analysing, Finished, Failed }

private fun phaseOf(state: RunState): Phase = when (state) {
    RunState.Preparing, is RunState.Baseline -> Phase.Baseline
    is RunState.Running -> Phase.Running
    RunState.Analysing -> Phase.Analysing
    is RunState.Finished -> Phase.Finished
    is RunState.Failed -> Phase.Failed
}

private val HudShape = RoundedCornerShape(22.dp)

@Composable
private fun Modifier.glass(): Modifier = this
    .clip(HudShape)
    .background(Brush.verticalGradient(listOf(Color(0xCC0B0D12), Color(0xB3080A0E))))
    .border(1.dp, Color.White.copy(alpha = 0.08f), HudShape)

@Composable
private fun BaselineCard(mode: StressMode, state: RunState.Baseline?, now: Long, idle: Double?, onCancel: () -> Unit) {
    val context = context()
    val total = RunController.BASELINE_SECONDS.toDouble()
    val remaining = state?.let { ((it.endsAtNanos - now) / 1e9).coerceIn(0.0, total) } ?: total
    Column(
        Modifier.padding(24.dp).widthIn(max = 360.dp).glass().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GaugeMark(Modifier.size(20.dp))
            Text(Labels.modeName(context, mode), style = MaterialTheme.typography.titleMedium)
        }
        Text(
            stringResource(R.string.run_baseline_title).upper(),
            style = MaterialTheme.typography.labelLarge,
            color = StressColors.TextDim,
            letterSpacing = 3.sp,
        )
        Box(contentAlignment = Alignment.Center) {
            ProgressRing(((total - remaining) / total).toFloat(), Modifier.size(150.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(Format.number(kotlin.math.ceil(remaining), 0), style = NumberStyles.Hero, color = StressColors.Text)
                Text(stringResource(R.string.unit_seconds), style = MaterialTheme.typography.labelMedium, color = StressColors.TextDim)
            }
        }
        Text(
            stringResource(R.string.run_baseline_text),
            style = MaterialTheme.typography.bodySmall,
            color = StressColors.TextDim,
            textAlign = TextAlign.Center,
        )
        if (idle != null) {
            Text(stringResource(R.string.run_baseline_idle, Format.watts(idle)), style = NumberStyles.Small, color = StressColors.Cool)
        }
        TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel), color = StressColors.TextDim) }
    }
}

@Composable
private fun MessageCard(icon: androidx.compose.ui.graphics.vector.ImageVector?, text: String, color: Color, progress: Boolean = false) {
    Column(
        Modifier.padding(24.dp).glass().padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (progress) CircularProgressIndicator(color = StressColors.Accent, strokeWidth = 3.dp, modifier = Modifier.size(42.dp))
        if (icon != null) Icon(icon, null, tint = color, modifier = Modifier.size(48.dp))
        Text(text, style = MaterialTheme.typography.titleMedium, color = color, textAlign = TextAlign.Center)
    }
}

@Composable
private fun FinishedCard(state: RunState.Finished?) {
    val context = context()
    val record = state?.record
    val reason = record?.let(Labels::endReasonOf) ?: EndReason.Completed
    val safetyStop = reason == EndReason.Safety
    Column(
        Modifier.padding(24.dp).widthIn(max = 360.dp).glass().padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            if (safetyStop) Icons.Rounded.Shield else Icons.Rounded.CheckCircle,
            null,
            tint = if (safetyStop) StressColors.Warn else StressColors.Good,
            modifier = Modifier.size(56.dp),
        )
        Text(stringResource(Labels.endReason(reason)), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        if (safetyStop && record != null) {
            Text(
                Labels.safetyFinding(context, record.safetyReason?.let { n -> dev.ozcan.stress.safety.SafetyReason.entries.firstOrNull { it.name == n } }, record.safetyValue),
                style = MaterialTheme.typography.bodyMedium,
                color = StressColors.Warn,
                textAlign = TextAlign.Center,
            )
        }
        Text(stringResource(R.string.run_saved), style = MaterialTheme.typography.bodySmall, color = StressColors.TextDim)
    }
}

@Composable
private fun FailedCard(state: RunState.Failed?, onLeave: () -> Unit) {
    val context = context()
    val (title, detail) = when (state) {
        is RunState.Failed.Blocked -> stringResource(R.string.home_blocked_title) to
            stringResource(R.string.home_blocked_text, Labels.safetyFinding(context, state.check))
        is RunState.Failed.StoppedBeforeLoad -> if (state.reason == EndReason.Safety && state.check != null) {
            stringResource(R.string.end_safety) to Labels.safetyFinding(context, state.check)
        } else {
            stringResource(R.string.run_cancelled) to stringResource(R.string.run_cancelled_text)
        }
        is RunState.Failed.LoadFailed -> stringResource(R.string.run_failed) to stringResource(R.string.run_failed_text, state.detail)
        null -> "" to ""
    }
    Column(
        Modifier.padding(24.dp).widthIn(max = 380.dp).glass().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            if (state is RunState.Failed.LoadFailed) Icons.Rounded.ErrorOutline else Icons.Rounded.Shield,
            null,
            tint = if (state is RunState.Failed.LoadFailed) StressColors.Bad else StressColors.Warn,
            modifier = Modifier.size(52.dp),
        )
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = StressColors.TextDim, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        PrimaryButton(stringResource(R.string.back), onClick = onLeave, height = 52.dp)
    }
}

/** Mode, time, safety, and the number that matters: battery power, with its last two minutes. */
@Composable
private fun TopHud(
    mode: StressMode,
    quality: dev.ozcan.stress.engine.SceneQuality?,
    state: RunState.Running,
    now: Long,
    live: LiveView?,
    power: List<Double>,
    idle: Double?,
    safety: SafetyCheck,
    safetyOn: Boolean,
) {
    val context = context()
    val elapsed = ((now - state.startedAtNanos) / 1e9).coerceAtLeast(0.0)
    val total = state.endsAtNanos?.let { (it - state.startedAtNanos) / 1e9 }
    Column(Modifier.fillMaxWidth().glass().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GaugeMark(Modifier.size(18.dp))
            Text(
                Labels.modeName(context, mode).upper(),
                style = MaterialTheme.typography.labelLarge,
                letterSpacing = 1.2.sp,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (quality != null) Pill(stringResource(Labels.quality(quality)), StressColors.Cool)
            if (!safetyOn || safety.level == SafetyLevel.Ok) SafetyChip(safety, safetyOn)
        }
        AnimatedVisibility(safetyOn && safety.level != SafetyLevel.Ok) { SafetyChip(safety, safetyOn) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(Format.clock(elapsed), style = NumberStyles.Small, color = StressColors.Text)
            if (total != null) {
                MeterBar((elapsed / total).toFloat(), Modifier.weight(1f), height = 5.dp)
                Text(Format.clock(total), style = NumberStyles.Small, color = StressColors.TextDim)
            } else {
                Text("∞", style = NumberStyles.Small, color = StressColors.TextDim)
                Spacer(Modifier.weight(1f))
            }
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column {
                Text(
                    if (live?.plugged == true) stringResource(R.string.home_live_charging) else Format.number(live?.watts, 2),
                    style = NumberStyles.Large,
                    color = StressColors.Accent,
                )
                Text(
                    when {
                        live?.plugged == true -> stringResource(R.string.run_power_invalid)
                        else -> stringResource(R.string.run_power_caption, Format.watts(idle), Format.watts(live?.watts?.let { w -> idle?.let { w - it } }))
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (live?.plugged == true) StressColors.Warn else StressColors.TextDim,
                )
            }
            if (live?.plugged != true) {
                Text("W", style = MaterialTheme.typography.titleMedium, color = StressColors.TextDim, modifier = Modifier.padding(bottom = 18.dp))
            }
            Sparkline(power, StressColors.Accent, Modifier.weight(1f).height(44.dp).padding(bottom = 4.dp))
        }
    }
}

@Composable
private fun SafetyChip(safety: SafetyCheck, enabled: Boolean) {
    val context = context()
    val (color, icon, text) = when {
        !enabled -> Triple(StressColors.Bad, Icons.Rounded.GppBad, stringResource(R.string.safety_off_short))
        safety.level == SafetyLevel.Ok -> Triple(StressColors.Good, Icons.Rounded.Shield, stringResource(R.string.run_safety_ok))
        else -> Triple(StressColors.Warn, Icons.Rounded.Warning, Labels.safetyFinding(context, safety))
    }
    val animated by animateColorAsState(color, label = "safetyColor")
    if (enabled && safety.level == SafetyLevel.Warn) {
        // A warning pulses; a quiet chip stays still, so the overlay is not redrawn every frame.
        val pulse by rememberInfiniteTransition(label = "safety").animateFloat(
            initialValue = 0.6f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
            label = "pulse",
        )
        Pill(text, animated.copy(alpha = pulse), icon = icon)
    } else {
        Pill(text, animated, icon = icon)
    }
}

/** Temperatures, clocks, rates: small at the bottom, so the middle of the screen belongs to the scene. */
@Composable
private fun BottomHud(live: LiveView?, mode: StressMode) {
    val context = context()
    val temps = live?.temperatures.orEmpty()
    Column(Modifier.fillMaxWidth().glass().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TempCell(stringResource(R.string.temp_cpu), temps[ThermalGroup.Cpu], StressColors.Accent)
            TempCell(stringResource(R.string.temp_gpu), temps[ThermalGroup.Gpu], StressColors.Cool)
            TempCell(stringResource(R.string.temp_battery), live?.batteryCelsius, StressColors.AccentHot)
            TempCell(stringResource(R.string.temp_skin), temps[ThermalGroup.Skin], StressColors.Good)
        }
        live?.clusters?.takeIf { it.isNotEmpty() }?.let { clusters ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                clusters.forEachIndexed { i, c ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(Labels.role(c.role)),
                            style = MaterialTheme.typography.labelSmall,
                            color = StressColors.TextDim,
                            modifier = Modifier.width(54.dp),
                            maxLines = 1,
                        )
                        MeterBar(c.load ?: 0f, Modifier.weight(1f), color = Labels.clusterColor(i, clusters.size), height = 5.dp)
                        Text(
                            c.khz?.let { "${it / 1000}" } ?: Format.MISSING,
                            style = NumberStyles.Tiny,
                            color = StressColors.Text,
                            modifier = Modifier.width(40.dp),
                            textAlign = TextAlign.End,
                        )
                    }
                }
            }
        }
        val gpu = live?.gpu
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            live?.cpuRate?.let { rate ->
                Metric("CPU", Format.rate(rate, live.unit?.let { Labels.rateSymbol(context, it) }))
            }
            if (mode.usesGpu && gpu?.state == GpuState.Running) {
                gpu.burner?.let { Metric("GPU", Format.rate(gpu.rate, Labels.rateSymbol(context, it.unit))) }
                if (mode.scene != null) Metric("FPS", Format.number(gpu.framesPerSecond, 0))
            }
            if (mode.usesGpu) Metric(stringResource(R.string.run_gpu_load), Format.percent(live?.gpuLoad))
            val errors = (live?.errors ?: 0L) + (gpu?.errors ?: 0L)
            Pill(
                stringResource(R.string.run_errors, errors),
                if (errors > 0) StressColors.Bad else StressColors.Good,
            )
        }
    }
}

@Composable
private fun TempCell(label: String, celsius: Double?, color: Color) {
    val hot = (celsius ?: 0.0) >= 90.0
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label.upper(), style = MaterialTheme.typography.labelSmall, color = StressColors.TextDim, letterSpacing = 1.sp, maxLines = 1)
        Text(
            celsius?.let { Format.number(it, 0) + "°" } ?: Format.MISSING,
            style = NumberStyles.Medium,
            color = if (hot) StressColors.Bad else if (celsius == null) StressColors.TextFaint else color,
        )
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = StressColors.TextFaint)
        Text(value, style = NumberStyles.Tiny, color = StressColors.Text, maxLines = 1)
    }
}

/**
 * What CPU-only runs show instead of a scene: the power gauge (its scale
 * grows with the highest reading), and, with the HUD hidden (its clock bars
 * say the same), a cell for every core glowing with its cluster's clock.
 */
@Composable
private fun CpuInstrument(live: LiveView?, peak: Double?, cpuCount: Int, showCores: Boolean, modifier: Modifier) {
    val context = context()
    val scale = maxOf(8.0, (peak ?: 0.0) * 1.25)
    val watts = live?.watts?.takeIf { live.plugged.not() }
    val chip = live?.chipCelsius
    Box(modifier.fillMaxWidth().padding(horizontal = 28.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (watts != null) {
                HeroGauge(
                    fraction = (watts / scale).toFloat(),
                    value = Format.number(watts, 2),
                    unit = "W",
                    caption = stringResource(R.string.run_gauge_power),
                )
            } else {
                HeroGauge(
                    fraction = chip?.let { ((it - 25) / 80).toFloat() },
                    value = chip?.let { Format.number(it, 0) } ?: Format.MISSING,
                    unit = "°C",
                    caption = stringResource(R.string.run_gauge_chip),
                    valueColor = StressColors.Accent,
                )
            }
            AnimatedVisibility(showCores, enter = fadeIn(), exit = fadeOut()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CoreGrid(live, cpuCount)
                    Text(
                        context.getString(R.string.run_cores, cpuCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = StressColors.TextFaint,
                        letterSpacing = 1.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun CoreGrid(live: LiveView?, cpuCount: Int) {
    val clusters = live?.clusters.orEmpty()
    val shimmer by rememberInfiniteTransition(label = "cores").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Restart),
        label = "shimmer",
    )
    val columns = when {
        cpuCount <= 4 -> cpuCount
        cpuCount <= 8 -> 4
        else -> 6
    }.coerceAtLeast(1)
    val rows = (cpuCount + columns - 1) / columns
    Canvas(Modifier.fillMaxWidth().height((rows * 34).dp).padding(vertical = 6.dp)) {
        val gap = 8.dp.toPx()
        val cellW = (size.width - gap * (columns - 1)) / columns
        val cellH = (size.height - gap * (rows - 1)) / rows
        for (cpu in 0 until cpuCount) {
            val index = clusters.indexOfFirst { cpu in it.cpus }
            val cluster = clusters.getOrNull(index)
            val load = cluster?.load ?: 0f
            val color = if (index >= 0) Labels.clusterColor(index, clusters.size) else StressColors.TextFaint
            val col = cpu % columns
            val row = cpu / columns
            val x = col * (cellW + gap)
            val y = row * (cellH + gap)
            val wave = 0.5f + 0.5f * kotlin.math.sin((shimmer + cpu * 0.13f) * 2f * Math.PI.toFloat())
            drawRoundRect(StressColors.SurfaceHigh, Offset(x, y), Size(cellW, cellH), CornerRadius(8.dp.toPx()))
            drawRoundRect(
                color.copy(alpha = (0.25f + 0.55f * load) * (0.75f + 0.25f * wave)),
                Offset(x, y + cellH * (1 - load)),
                Size(cellW, cellH * load),
                CornerRadius(8.dp.toPx()),
            )
        }
    }
}
