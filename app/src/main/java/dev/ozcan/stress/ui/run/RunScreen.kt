package dev.ozcan.stress.ui.run

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Stop
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.ozcan.stress.R
import dev.ozcan.stress.graph
import dev.ozcan.stress.run.EndReason
import dev.ozcan.stress.run.RunController
import dev.ozcan.stress.run.RunState
import dev.ozcan.stress.run.StressDuration
import dev.ozcan.stress.run.StressMode
import dev.ozcan.stress.safety.SafetyReason
import dev.ozcan.stress.ui.Format
import dev.ozcan.stress.ui.GpuSurface
import dev.ozcan.stress.ui.KeepScreenOnEffect
import dev.ozcan.stress.ui.Labels
import dev.ozcan.stress.ui.MaxDisplayEffect
import dev.ozcan.stress.ui.components.MeterBar
import dev.ozcan.stress.ui.components.MetricNumber
import dev.ozcan.stress.ui.components.Panel
import dev.ozcan.stress.ui.components.PrimaryButton
import dev.ozcan.stress.ui.components.SecondaryButton
import dev.ozcan.stress.ui.components.StatusChip
import dev.ozcan.stress.ui.components.StressDialog
import dev.ozcan.stress.ui.context
import dev.ozcan.stress.ui.theme.NumberStyles
import dev.ozcan.stress.ui.theme.StressColors
import dev.ozcan.stress.ui.theme.StressMotion
import dev.ozcan.stress.ui.theme.StressType
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun RunScreen(mode: StressMode, duration: StressDuration, onFinished: (String) -> Unit, onLeave: () -> Unit) {
    val graph = context().graph
    val model: RunViewModel = viewModel { RunViewModel(graph, mode, duration) }
    val state by model.state.collectAsStateWithLifecycle()
    val live by model.live.collectAsStateWithLifecycle()
    val history by model.history.collectAsStateWithLifecycle()
    val safety by model.safety.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    var overlay by remember { mutableStateOf(true) }
    var confirmStop by remember { mutableStateOf(false) }
    var windowCenterY by remember { mutableStateOf<Float?>(null) }

    KeepScreenOnEffect()
    if (model.settings.maxDisplay) MaxDisplayEffect()
    // Off screen the load loses the big cores and the GPU its surface: end the
    // run there and keep what it measured, marked as interrupted.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { model.stop(EndReason.Interrupted) }
    val running = state as? RunState.Running
    val active = state is RunState.Baseline || running != null || state == RunState.Preparing
    LaunchedEffect(active) {
        if (!active) confirmStop = false
    }
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

    val canHide = running != null && mode.scene != null
    val toggle = if (canHide) {
        Modifier.clickable(
            remember { MutableInteractionSource() },
            indication = null,
            role = Role.Button,
            onClickLabel = stringResource(R.string.run_toggle_hud),
        ) { overlay = !overlay }
    } else Modifier

    Box(Modifier.fillMaxSize().background(if (mode.usesGpu) Color.Black else StressColors.Background).then(toggle)) {
        // The surface stays full size even when a scene's viewport or the readings change.
        // A scene's subject sits in the window the panels leave; with the panels hidden, in the centre.
        if (mode.usesGpu) {
            GpuSurface(
                graph.gpu,
                Modifier.fillMaxSize(),
                framedScene = mode.scene != null,
                windowCenterY = windowCenterY.takeIf { canHide && overlay },
            )
        }

        AnimatedVisibility(
            visible = running != null && (!canHide || overlay),
            enter = fadeIn(StressMotion.enter()),
            exit = fadeOut(StressMotion.exit()),
        ) {
            if (running != null) {
                RunReadings(
                    mode, model.settings.quality, running, now, live, history,
                    safety, model.settings.deviceSafety,
                    sceneWindowModifier = Modifier.onGloballyPositioned { windowCenterY = it.boundsInRoot().center.y },
                    onStop = { confirmStop = true },
                )
            }
        }

        Box(Modifier.align(Alignment.Center)) {
            when (val phase = state) {
                RunState.Preparing -> BaselineCard(mode, null, now) { confirmStop = true }
                is RunState.Baseline -> BaselineCard(mode, phase, now) { confirmStop = true }
                RunState.Analysing -> MessageCard(stringResource(R.string.run_analysing))
                is RunState.Finished -> FinishedCard(phase)
                is RunState.Failed -> FailedCard(phase, onLeave)
                is RunState.Running -> Unit
            }
        }
    }

    if (confirmStop) {
        StressDialog(
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
        )
    }
}

@Composable
private fun BaselineCard(mode: StressMode, state: RunState.Baseline?, now: Long, onCancel: () -> Unit) {
    val context = context()
    val total = RunController.BASELINE_SECONDS.toDouble()
    val remaining = state?.let { ((it.endsAtNanos - now) / 1e9).coerceIn(0.0, total) } ?: total
    Panel(Modifier.padding(16.dp).widthIn(max = 360.dp), padding = 16.dp) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(Labels.modeName(context, mode), style = StressType.Title, textAlign = TextAlign.Center)
            Text(stringResource(R.string.run_baseline_title), style = StressType.Heading)
            MetricNumber(Format.number(kotlin.math.ceil(remaining), 0), stringResource(R.string.unit_seconds), NumberStyles.Large)
            MeterBar(((total - remaining) / total).toFloat())
            Text(
                stringResource(R.string.run_baseline_text),
                style = MaterialTheme.typography.bodySmall,
                color = StressColors.TextDim,
                textAlign = TextAlign.Center,
            )
            SecondaryButton(stringResource(R.string.cancel), onClick = onCancel)
        }
    }
}
@Composable
private fun MessageCard(text: String) {
    Panel(Modifier.padding(16.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(color = StressColors.Accent, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
            Text(text, style = StressType.Heading, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun FinishedCard(state: RunState.Finished) {
    val context = context()
    val record = state.record
    val reason = Labels.endReasonOf(record)
    val safetyStop = reason == EndReason.Safety
    Panel(Modifier.padding(16.dp).widthIn(max = 360.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StatusChip(
                stringResource(Labels.endReason(reason)),
                Labels.endReasonColor(reason),
                icon = if (safetyStop) Icons.Rounded.Shield else if (reason == EndReason.Completed) Icons.Rounded.CheckCircle else null,
            )
            if (safetyStop) {
                Text(
                    Labels.safetyFinding(context, record.safetyReason?.let { n -> SafetyReason.entries.firstOrNull { it.name == n } }, record.safetyValue),
                    style = MaterialTheme.typography.bodyMedium,
                    color = StressColors.Warn,
                    textAlign = TextAlign.Center,
                )
            }
            Text(stringResource(R.string.run_saved), style = StressType.BodySmall, color = StressColors.TextDim)
        }
    }
}

@Composable
private fun FailedCard(state: RunState.Failed, onLeave: () -> Unit) {
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
        RunState.Failed.GpuFailed -> stringResource(R.string.end_gpu_failed) to stringResource(R.string.insight_gpu_failed_detail)
        is RunState.Failed.AnalysisFailed -> stringResource(R.string.run_analysis_failed) to
            stringResource(R.string.run_analysis_failed_text, state.detail)
    }
    Panel(Modifier.padding(16.dp).widthIn(max = 380.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                if (state is RunState.Failed.LoadFailed) Icons.Rounded.ErrorOutline else Icons.Rounded.Shield,
                null,
                tint = if (state is RunState.Failed.LoadFailed) StressColors.Bad else StressColors.Warn,
                modifier = Modifier.size(52.dp),
            )
            Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = StressColors.TextDim, textAlign = TextAlign.Center)
            PrimaryButton(stringResource(R.string.back), onClick = onLeave)
        }
    }
}
