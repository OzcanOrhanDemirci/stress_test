package dev.ozcan.stress.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.BrightnessHigh
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.DeveloperBoard
import androidx.compose.material.icons.rounded.GppBad
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ozcan.stress.R
import dev.ozcan.stress.engine.SceneQuality
import dev.ozcan.stress.graph
import dev.ozcan.stress.safety.SafetyLimits
import dev.ozcan.stress.ui.BottomBarSpace
import dev.ozcan.stress.ui.Format
import dev.ozcan.stress.ui.Labels
import dev.ozcan.stress.ui.components.GaugeMark
import dev.ozcan.stress.ui.components.GlassCard
import dev.ozcan.stress.ui.components.NavigationRow
import dev.ozcan.stress.ui.components.SectionHeader
import dev.ozcan.stress.ui.components.SegmentedControl
import dev.ozcan.stress.ui.components.SwitchRow
import dev.ozcan.stress.ui.context
import dev.ozcan.stress.ui.theme.StressColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(onOpenDevice: () -> Unit) {
    val context = context()
    val graph = context.graph
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var confirmOff by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
            .padding(top = 14.dp, bottom = BottomBarSpace + 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)

        SafetyCard(
            enabled = settings.deviceSafety,
            onToggle = { on ->
                if (on) {
                    graph.settings.update { it.copy(deviceSafety = true) }
                    if (settings.haptics) haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                } else {
                    confirmOff = true
                }
            },
        )

        SectionHeader(stringResource(R.string.settings_section_graphics))
        GlassCard(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.settings_quality), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.settings_quality_detail), style = MaterialTheme.typography.bodySmall, color = StressColors.TextDim)
            Spacer(Modifier.height(12.dp))
            SegmentedControl(
                options = SceneQuality.entries,
                selected = settings.quality,
                onSelect = { q -> graph.settings.update { it.copy(quality = q) } },
                label = { stringResource(Labels.quality(it)) },
            )
            Spacer(Modifier.height(10.dp))
            AnimatedContent(
                targetState = settings.quality,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(150)) },
                label = "quality",
            ) { q ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(Labels.qualityDetail(q)), style = MaterialTheme.typography.bodySmall, color = StressColors.Text)
                    Text(
                        stringResource(R.string.settings_quality_scale, q.scalePercent(dev.ozcan.stress.engine.SceneKind.Pool), q.scalePercent(dev.ozcan.stress.engine.SceneKind.Forest)),
                        style = MaterialTheme.typography.labelSmall,
                        color = StressColors.TextFaint,
                    )
                }
            }
        }

        SectionHeader(stringResource(R.string.settings_section_test))
        GlassCard(Modifier.fillMaxWidth(), padding = 12.dp) {
            SwitchRow(
                title = stringResource(R.string.settings_display),
                description = stringResource(R.string.settings_display_detail),
                checked = settings.maxDisplay,
                onCheckedChange = { on -> graph.settings.update { it.copy(maxDisplay = on) } },
                icon = Icons.Rounded.BrightnessHigh,
                iconTint = StressColors.Warn,
            )
            SwitchRow(
                title = stringResource(R.string.settings_haptics),
                description = stringResource(R.string.settings_haptics_detail),
                checked = settings.haptics,
                onCheckedChange = { on -> graph.settings.update { it.copy(haptics = on) } },
                icon = Icons.Rounded.Vibration,
                iconTint = StressColors.Cool,
            )
        }

        SectionHeader(stringResource(R.string.settings_section_device))
        GlassCard(Modifier.fillMaxWidth(), padding = 12.dp) {
            NavigationRow(
                title = stringResource(R.string.settings_device),
                description = stringResource(R.string.settings_device_detail),
                onClick = onOpenDevice,
                icon = Icons.Rounded.DeveloperBoard,
                iconTint = StressColors.Accent,
            )
            NavigationRow(
                title = stringResource(R.string.settings_clear),
                description = stringResource(R.string.settings_clear_detail),
                onClick = { confirmClear = true },
                icon = Icons.Rounded.DeleteSweep,
                iconTint = StressColors.Bad,
                trailing = null,
            )
        }

        SectionHeader(stringResource(R.string.settings_section_about))
        GlassCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GaugeMark(Modifier.size(36.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.settings_version, graph.appVersion), style = MaterialTheme.typography.bodySmall, color = StressColors.TextDim)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.settings_about_text), style = MaterialTheme.typography.bodySmall, color = StressColors.TextDim)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Rounded.Info, null, tint = StressColors.TextFaint, modifier = Modifier.size(14.dp))
                Text(stringResource(R.string.settings_licenses), style = MaterialTheme.typography.labelSmall, color = StressColors.TextFaint)
            }
        }
    }

    if (confirmOff) {
        AlertDialog(
            onDismissRequest = { confirmOff = false },
            icon = { Icon(Icons.Rounded.Warning, null, tint = StressColors.Bad) },
            title = { Text(stringResource(R.string.safety_off_title)) },
            text = { Text(stringResource(R.string.safety_off_text), color = StressColors.TextDim) },
            confirmButton = {
                TextButton(onClick = {
                    confirmOff = false
                    graph.settings.update { it.copy(deviceSafety = false) }
                    if (settings.haptics) haptics.performHapticFeedback(HapticFeedbackType.ToggleOff)
                }) { Text(stringResource(R.string.safety_off_confirm), color = StressColors.Bad, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = { TextButton(onClick = { confirmOff = false }) { Text(stringResource(R.string.safety_off_keep)) } },
            containerColor = StressColors.SurfaceHigh,
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            icon = { Icon(Icons.Rounded.DeleteSweep, null, tint = StressColors.Bad) },
            title = { Text(stringResource(R.string.settings_clear_title)) },
            text = { Text(stringResource(R.string.settings_clear_text), color = StressColors.TextDim) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch { withContext(Dispatchers.IO) { graph.runs.list().forEach { graph.runs.delete(it.id) } } }
                }) { Text(stringResource(R.string.delete), color = StressColors.Bad) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
            containerColor = StressColors.SurfaceHigh,
        )
    }
}

/**
 * Device safety, the setting that matters most: on, a glowing shield and the
 * limits it keeps; off, the card turns red and says so.
 */
@Composable
private fun SafetyCard(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val color by animateColorAsState(if (enabled) StressColors.Good else StressColors.Bad, tween(400), label = "safety")
    val glow by rememberInfiniteTransition(label = "shield").animateFloat(
        initialValue = 0.25f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(tween(1800), RepeatMode.Reverse),
        label = "glow",
    )
    val shape = RoundedCornerShape(24.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(color.copy(alpha = 0.14f), StressColors.Surface)), shape)
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        listOf(color.copy(alpha = 0.22f * glow), Color.Transparent),
                        center = Offset(size.width * 0.15f, size.height * 0.1f),
                        radius = size.maxDimension * 0.9f,
                    ),
                )
            }
            .border(1.5.dp, Brush.linearGradient(listOf(color.copy(alpha = 0.8f), color.copy(alpha = 0.2f))), shape)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier
                    .size(56.dp)
                    .background(Brush.radialGradient(listOf(color.copy(alpha = 0.35f + 0.25f * glow), color.copy(alpha = 0.08f))), CircleShape)
                    .border(1.dp, color.copy(alpha = 0.6f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (enabled) Icons.Rounded.Shield else Icons.Rounded.GppBad, null, tint = color, modifier = Modifier.size(30.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_safety), style = MaterialTheme.typography.titleLarge, fontSize = 20.sp, maxLines = 1)
                Text(
                    stringResource(if (enabled) R.string.safety_status_on else R.string.safety_status_off),
                    style = MaterialTheme.typography.labelLarge,
                    color = color,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = StressColors.Good,
                    checkedBorderColor = StressColors.Good,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = StressColors.Bad.copy(alpha = 0.6f),
                    uncheckedBorderColor = StressColors.Bad,
                ),
            )
        }
        AnimatedContent(
            targetState = enabled,
            transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(160)) },
            label = "safetyBody",
        ) { on ->
            if (on) {
                val limits = SafetyLimits()
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.safety_on_text), style = MaterialTheme.typography.bodySmall, color = StressColors.TextDim)
                    Limit(Icons.Rounded.Thermostat, stringResource(R.string.safety_limit_battery, Format.celsius(limits.batteryStop, 0)))
                    Limit(Icons.Rounded.Thermostat, stringResource(R.string.safety_limit_chip, Format.celsius(limits.chipStop, 0)))
                    Limit(Icons.Rounded.Thermostat, stringResource(R.string.safety_limit_skin, Format.celsius(limits.skinStop, 0)))
                    Limit(Icons.Rounded.Warning, stringResource(R.string.safety_limit_thermal, stringResource(Labels.thermalStatus(limits.thermalStop))))
                    Limit(Icons.Rounded.BatteryAlert, stringResource(R.string.safety_limit_level, Format.percentValue(limits.levelStop)))
                    Limit(
                        Icons.Rounded.Shield,
                        stringResource(
                            R.string.safety_limit_start,
                            Format.celsius(limits.batteryStart, 0),
                            Format.celsius(limits.chipStart, 0),
                            Format.percentValue(limits.levelStart),
                        ),
                    )
                }
            } else {
                Text(stringResource(R.string.safety_off_body), style = MaterialTheme.typography.bodySmall, color = StressColors.Text)
            }
        }
    }
}

@Composable
private fun Limit(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, tint = StressColors.Good, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = StressColors.Text)
    }
}
