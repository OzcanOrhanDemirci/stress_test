package dev.ozcan.stress.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ozcan.stress.BuildConfig
import dev.ozcan.stress.R
import dev.ozcan.stress.engine.SceneKind
import dev.ozcan.stress.engine.SceneQuality
import dev.ozcan.stress.graph
import dev.ozcan.stress.safety.SafetyLimits
import dev.ozcan.stress.settings.AppLanguage
import dev.ozcan.stress.settings.AppLanguages
import dev.ozcan.stress.ui.Format
import dev.ozcan.stress.ui.Labels
import dev.ozcan.stress.ui.components.Hairline
import dev.ozcan.stress.ui.components.IconBadge
import dev.ozcan.stress.ui.components.NavRow
import dev.ozcan.stress.ui.components.Panel
import dev.ozcan.stress.ui.components.SecondaryButton
import dev.ozcan.stress.ui.components.SectionLabel
import dev.ozcan.stress.ui.components.SegmentedControl
import dev.ozcan.stress.ui.components.SettingRow
import dev.ozcan.stress.ui.components.Stack
import dev.ozcan.stress.ui.components.StatusChip
import dev.ozcan.stress.ui.components.StressDialog
import dev.ozcan.stress.ui.components.StressSwitch
import dev.ozcan.stress.ui.components.TopBar
import dev.ozcan.stress.ui.components.Wordmark
import dev.ozcan.stress.ui.context
import dev.ozcan.stress.ui.theme.StressColors
import dev.ozcan.stress.ui.theme.StressType
import dev.ozcan.stress.ui.upper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Settings is one page of setting rows; About and the advanced items are pages behind two of them. */
private enum class SettingsPage { Main, About, Advanced }

@Composable
fun SettingsScreen() {
    val graph = context().graph
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var page by rememberSaveable { mutableStateOf(SettingsPage.Main) }
    var confirmOff by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }

    when (page) {
        SettingsPage.About -> SubPage(stringResource(R.string.settings_section_about), { page = SettingsPage.Main }) {
            About(settings.deviceSafety)
        }
        SettingsPage.Advanced -> SubPage(stringResource(R.string.device_section_advanced), { page = SettingsPage.Main }) {
            SwitchSetting(
                R.string.settings_scene_burner, R.string.settings_scene_burner_detail, settings.sceneBurner,
            ) { on -> graph.settings.update { it.copy(sceneBurner = on) } }
            LoadTests()
        }
        SettingsPage.Main -> Column(Modifier.fillMaxSize()) {
            TopBar(stringResource(R.string.settings_title), modifier = Modifier.statusBarsPadding())
            Stack(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), spacing = 8) {
                SafetyRow(settings.deviceSafety) { on ->
                    if (on) {
                        graph.settings.update { it.copy(deviceSafety = true) }
                        if (settings.haptics) haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                    } else {
                        confirmOff = true
                    }
                }

                Group(R.string.settings_section_test)
                QualityRow(settings.quality) { q -> graph.settings.update { it.copy(quality = q) } }
                SwitchSetting(R.string.settings_display, R.string.settings_display_detail, settings.maxDisplay) { on ->
                    graph.settings.update { it.copy(maxDisplay = on) }
                }
                SwitchSetting(R.string.settings_haptics, R.string.settings_haptics_detail, settings.haptics) { on ->
                    graph.settings.update { it.copy(haptics = on) }
                }
                SwitchSetting(R.string.settings_notice, R.string.settings_notice_detail, settings.startupNotice) { on ->
                    graph.settings.update { it.copy(startupNotice = on) }
                }

                Group(R.string.settings_section_language)
                LanguageRow()

                Group(R.string.settings_section_device)
                SettingRow(
                    stringResource(R.string.settings_clear),
                    stringResource(R.string.settings_clear_detail),
                    control = { ActionLabel(Icons.Rounded.DeleteSweep, stringResource(R.string.settings_v2_clear_action), StressColors.Bad) },
                    onClick = { confirmClear = true },
                )

                Group(null)
                NavRow(
                    stringResource(R.string.device_section_advanced),
                    stringResource(R.string.settings_v2_advanced_detail),
                    onClick = { page = SettingsPage.Advanced },
                )
                NavRow(
                    stringResource(R.string.settings_section_about),
                    stringResource(R.string.settings_v2_about_detail),
                    value = BuildConfig.VERSION_NAME,
                    onClick = { page = SettingsPage.About },
                )
            }
        }
    }

    if (confirmOff) {
        val dismiss = { confirmOff = false }
        StressDialog(
            onDismissRequest = dismiss,
            icon = { IconBadge(Icons.Rounded.Warning, StressColors.Bad) },
            title = { Text(stringResource(R.string.safety_off_title)) },
            text = { Text(stringResource(R.string.safety_off_text), color = StressColors.TextDim) },
            confirmButton = {
                SecondaryButton(stringResource(R.string.safety_off_confirm), color = StressColors.Bad, onClick = {
                    confirmOff = false
                    graph.settings.update { it.copy(deviceSafety = false) }
                    if (settings.haptics) haptics.performHapticFeedback(HapticFeedbackType.ToggleOff)
                })
            },
            dismissButton = { SecondaryButton(stringResource(R.string.safety_off_keep), onClick = dismiss) },
        )
    }
    if (confirmClear) {
        val dismiss = { confirmClear = false }
        StressDialog(
            onDismissRequest = dismiss,
            icon = { IconBadge(Icons.Rounded.DeleteSweep, StressColors.Bad) },
            title = { Text(stringResource(R.string.settings_clear_title)) },
            text = { Text(stringResource(R.string.settings_clear_text), color = StressColors.TextDim) },
            confirmButton = {
                SecondaryButton(stringResource(R.string.delete), color = StressColors.Bad, onClick = {
                    confirmClear = false
                    scope.launch { withContext(Dispatchers.IO) { graph.runs.list().forEach { graph.runs.delete(it.id) } } }
                })
            },
            dismissButton = { SecondaryButton(stringResource(R.string.cancel), onClick = dismiss) },
        )
    }
}

/** A page behind a settings row: back arrow, its own scroll; system back returns to the rows. */
@Composable
private fun SubPage(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize()) {
        TopBar(title, modifier = Modifier.statusBarsPadding(), onBack = onBack)
        Stack(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), content = content)
    }
}

/** A group's label, with the extra air that separates groups of rows; null leaves only the air. */
@Composable
private fun Group(@StringRes label: Int?) {
    if (label == null) {
        Spacer(Modifier.height(12.dp))
    } else {
        SectionLabel(stringResource(label), Modifier.padding(top = 12.dp, bottom = 0.dp))
    }
}

@Composable
private fun SwitchSetting(@StringRes title: Int, @StringRes description: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    SettingRow(
        stringResource(title),
        stringResource(description),
        control = { StressSwitch(checked, null) },
        onClick = { onChange(!checked) },
    )
}

@Composable
private fun SafetyRow(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    SettingRow(
        stringResource(R.string.settings_safety),
        stringResource(if (enabled) R.string.safety_status_on else R.string.safety_status_off),
        control = { StressSwitch(enabled, null) },
        onClick = { onToggle(!enabled) },
    )
    // Off is the one state worth a sentence on the main page: what the user gave up.
    if (!enabled) {
        Text(
            stringResource(R.string.safety_off_body), Modifier.padding(horizontal = 4.dp),
            style = StressType.BodySmall, color = StressColors.Bad,
        )
    }
}

/** An action inside a row: icon plus verb, in the action's colour. */
@Composable
private fun ActionLabel(icon: ImageVector, text: String, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(20.dp), tint = color)
        Text(text.upper(), style = StressType.Label, color = color, maxLines = 1)
    }
}

@Composable
private fun QualityRow(quality: SceneQuality, onSelect: (SceneQuality) -> Unit) {
    SettingRow(
        stringResource(R.string.settings_quality),
        stringResource(R.string.settings_quality_detail),
        controlBelow = true,
        control = {
            Stack(spacing = 8) {
                SegmentedControl(SceneQuality.entries, quality, onSelect, label = { stringResource(Labels.quality(it)) })
                Text(stringResource(Labels.qualityDetail(quality)), style = StressType.BodySmall)
                Text(
                    stringResource(
                        R.string.settings_quality_scale,
                        quality.scalePercent(SceneKind.Pool),
                        quality.scalePercent(SceneKind.Forest),
                    ),
                    style = StressType.Code,
                    color = StressColors.TextDim,
                )
            }
        },
    )
}

/** Picking a language recreates the activity and keeps the open tab. */
@Composable
private fun LanguageRow() {
    val context = context()
    val scope = rememberCoroutineScope()
    var language by remember { mutableStateOf(AppLanguages.current(context)) }
    var pending by remember { mutableStateOf<Job?>(null) }
    SettingRow(
        stringResource(R.string.settings_language),
        languageDetail(language),
        controlBelow = true,
        control = {
            SegmentedControl(
                options = AppLanguage.entries,
                selected = language,
                onSelect = { picked ->
                    if (picked != language) {
                        language = picked
                        pending?.cancel()
                        // Allow the selected state to appear before recreating the activity.
                        pending = scope.launch {
                            delay(150)
                            context.findActivity()?.let { AppLanguages.set(it, picked) }
                        }
                    }
                },
                label = { stringResource(Labels.language(it)) },
                // Each language's name is in that language, so it is upper-cased by its own rules: "ENGLISH", "TÜRKÇE".
                labelLocale = { it.tag?.let(Locale::forLanguageTag) },
            )
        },
    )
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

/** About is information, so it is drawn as readout panels: no rules, nothing to press. */
@Composable
private fun About(safetyOn: Boolean) {
    Panel(Modifier.fillMaxWidth()) {
        Stack(spacing = 12) {
            Wordmark(version = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME))
            Text(stringResource(R.string.settings_about_text), style = StressType.Body)
            Hairline()
            AboutFeature(Icons.Rounded.Memory, R.string.settings_about_cpu)
            AboutFeature(Icons.Rounded.ViewInAr, R.string.settings_about_gpu)
            AboutFeature(Icons.Rounded.Insights, R.string.settings_about_analysis)
            AboutFeature(Icons.Rounded.Tune, R.string.settings_about_quality)
        }
    }
    SafetyLimitsPanel(safetyOn)
    Panel(Modifier.fillMaxWidth()) {
        Stack(spacing = 12) {
            Stack(spacing = 4) {
                SectionLabel(stringResource(R.string.settings_developer))
                Text(DEVELOPER, style = StressType.Heading)
            }
            Text(stringResource(R.string.settings_licenses), style = StressType.BodySmall, color = StressColors.TextDim)
        }
    }
}

@Composable
private fun SafetyLimitsPanel(enabled: Boolean) {
    val color = if (enabled) StressColors.Good else StressColors.Bad
    Panel(Modifier.fillMaxWidth()) {
        Stack(spacing = 8) {
            SectionLabel(stringResource(R.string.settings_v2_safety_limits))
            StatusChip(stringResource(if (enabled) R.string.safety_status_on else R.string.safety_status_off), color)
            if (enabled) {
                val limits = SafetyLimits()
                Text(stringResource(R.string.safety_on_text), style = StressType.BodySmall, color = StressColors.TextDim)
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
            } else {
                Text(stringResource(R.string.safety_off_body), style = StressType.Body)
            }
        }
    }
}

@Composable
private fun AboutFeature(icon: ImageVector, @StringRes text: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(icon, StressColors.TextDim, size = 28.dp)
        Text(stringResource(text), style = StressType.BodySmall)
    }
}

@Composable
private fun Limit(icon: ImageVector, text: String, tint: Color = StressColors.TextDim) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
        Text(text, style = StressType.BodySmall)
    }
}
