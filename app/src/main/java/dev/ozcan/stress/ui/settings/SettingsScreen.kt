package dev.ozcan.stress.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.DeveloperBoard
import androidx.compose.material.icons.rounded.GppBad
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.WarningAmber
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
import dev.ozcan.stress.settings.AppLanguage
import dev.ozcan.stress.settings.AppLanguages
import dev.ozcan.stress.ui.BottomBarSpace
import dev.ozcan.stress.ui.Format
import dev.ozcan.stress.ui.Labels
import dev.ozcan.stress.ui.components.GaugeMark
import dev.ozcan.stress.ui.components.GlassCard
import dev.ozcan.stress.ui.components.Hairline
import dev.ozcan.stress.ui.components.IconBadge
import dev.ozcan.stress.ui.components.NavigationRow
import dev.ozcan.stress.ui.components.SectionHeader
import dev.ozcan.stress.ui.components.SegmentedControl
import dev.ozcan.stress.ui.components.SwitchRow
import dev.ozcan.stress.ui.components.Wordmark
import dev.ozcan.stress.ui.context
import dev.ozcan.stress.ui.theme.StressColors
import dev.ozcan.stress.ui.upper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
            SwitchRow(
                title = stringResource(R.string.settings_notice),
                description = stringResource(R.string.settings_notice_detail),
                checked = settings.startupNotice,
                onCheckedChange = { on -> graph.settings.update { it.copy(startupNotice = on) } },
                icon = Icons.Rounded.WarningAmber,
                iconTint = StressColors.AccentHot,
            )
        }

        SectionHeader(stringResource(R.string.settings_section_language))
        LanguageCard()

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
        AboutCard(graph.appVersion)
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

/**
 * The app's language: the phone's, Turkish or English. Picking one recreates
 * the activity in it (the screen and the open tab stay where they were).
 */
@Composable
private fun LanguageCard() {
    val context = context()
    val scope = rememberCoroutineScope()
    var language by remember { mutableStateOf(AppLanguages.current(context)) }
    var pending by remember { mutableStateOf<Job?>(null) }
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconBadge(Icons.Rounded.Translate, StressColors.Cool, size = 38.dp)
            Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        SegmentedControl(
            options = AppLanguage.entries,
            selected = language,
            onSelect = { picked ->
                if (picked != language) {
                    language = picked
                    pending?.cancel()
                    // Let the indicator slide over before the activity is recreated in the new language.
                    pending = scope.launch {
                        delay(280)
                        context.findActivity()?.let { AppLanguages.set(it, picked) }
                    }
                }
            },
            label = { stringResource(Labels.language(it)) },
        )
        Spacer(Modifier.height(10.dp))
        Text(languageDetail(language), style = MaterialTheme.typography.bodySmall, color = StressColors.TextDim)
    }
}

@Composable
private fun languageDetail(language: AppLanguage): String {
    if (language != AppLanguage.System) return stringResource(R.string.settings_language_fixed)
    val system = AppLanguages.systemLocale()
    val name = system.getDisplayLanguage(system).replaceFirstChar { it.titlecase(system) }
    return if (AppLanguage.of(system.language) != AppLanguage.System) {
        stringResource(R.string.settings_language_system, name)
    } else {
        stringResource(R.string.settings_language_unsupported, name)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private const val DEVELOPER = "Özcan Orhan Demirci"

/** What the app is, what it does, and who made it. */
@Composable
private fun AboutCard(version: String) {
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            GaugeMark(Modifier.size(44.dp))
            Column(Modifier.weight(1f)) {
                Wordmark(fontSize = 22.sp)
                Text(stringResource(R.string.settings_version, version), style = MaterialTheme.typography.bodySmall, color = StressColors.TextDim)
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(stringResource(R.string.settings_about_tagline), style = MaterialTheme.typography.titleMedium, color = StressColors.Accent)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.settings_about_text), style = MaterialTheme.typography.bodySmall, color = StressColors.TextDim)
        Spacer(Modifier.height(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Feature(Icons.Rounded.Memory, StressColors.Accent, stringResource(R.string.settings_about_cpu))
            Feature(Icons.Rounded.ViewInAr, StressColors.Cool, stringResource(R.string.settings_about_gpu))
            Feature(Icons.Rounded.Insights, StressColors.Good, stringResource(R.string.settings_about_analysis))
            Feature(Icons.Rounded.Tune, StressColors.Warn, stringResource(R.string.settings_about_quality))
        }
        Spacer(Modifier.height(14.dp))
        Hairline()
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconBadge(Icons.Rounded.Code, StressColors.AccentHot, size = 38.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.settings_developer).upper(),
                    style = MaterialTheme.typography.labelSmall,
                    color = StressColors.TextFaint,
                    letterSpacing = 1.5.sp,
                )
                Text(DEVELOPER, style = MaterialTheme.typography.titleMedium)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Rounded.Info, null, tint = StressColors.TextFaint, modifier = Modifier.size(14.dp))
            Text(stringResource(R.string.settings_licenses), style = MaterialTheme.typography.labelSmall, color = StressColors.TextFaint)
        }
    }
}

@Composable
private fun Feature(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(28.dp).background(tint.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
        }
        Text(text, style = MaterialTheme.typography.bodySmall, color = StressColors.Text, modifier = Modifier.weight(1f).padding(top = 5.dp))
    }
}

@Composable
private fun Limit(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, tint = StressColors.Good, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = StressColors.Text)
    }
}
