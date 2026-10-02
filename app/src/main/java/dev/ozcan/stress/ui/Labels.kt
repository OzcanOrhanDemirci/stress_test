package dev.ozcan.stress.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import dev.ozcan.stress.R
import dev.ozcan.stress.engine.SceneKind
import dev.ozcan.stress.engine.SceneQuality
import dev.ozcan.stress.engine.WorkUnit
import dev.ozcan.stress.run.EndReason
import dev.ozcan.stress.run.RunAnalysis
import dev.ozcan.stress.run.RunRecord
import dev.ozcan.stress.run.StressDuration
import dev.ozcan.stress.run.StressMode
import dev.ozcan.stress.safety.SafetyCheck
import dev.ozcan.stress.safety.SafetyReason
import dev.ozcan.stress.telemetry.ClusterRole
import dev.ozcan.stress.telemetry.ThermalGroup
import dev.ozcan.stress.ui.theme.StressColors

/**
 * Names and descriptions of the app's enums, from string resources. Plain
 * functions over a [Context], so the report uses the same words as the screens.
 */
object Labels {

    @StringRes
    fun modeTitle(mode: StressMode): Int = when (mode) {
        StressMode.Full -> R.string.mode_full
        StressMode.Cinematic, StressMode.CinematicForest, StressMode.CinematicWhite -> R.string.mode_cinematic
        StressMode.Cpu -> R.string.mode_cpu
        StressMode.Gpu -> R.string.mode_gpu
        StressMode.Dry -> R.string.mode_dry
    }

    @StringRes
    fun modeDetail(mode: StressMode): Int = when (mode) {
        StressMode.Full -> R.string.mode_full_detail
        StressMode.Cinematic, StressMode.CinematicForest, StressMode.CinematicWhite -> R.string.mode_cinematic_detail
        StressMode.Cpu -> R.string.mode_cpu_detail
        StressMode.Gpu -> R.string.mode_gpu_detail
        StressMode.Dry -> R.string.mode_dry_detail
    }

    fun modeIcon(mode: StressMode): ImageVector = when (mode) {
        StressMode.Full -> Icons.Rounded.Bolt
        StressMode.Cinematic, StressMode.CinematicForest, StressMode.CinematicWhite -> Icons.Rounded.Movie
        StressMode.Cpu -> Icons.Rounded.Memory
        StressMode.Gpu -> Icons.Rounded.Videocam
        StressMode.Dry -> Icons.Rounded.Speed
    }

    @StringRes
    fun scene(kind: SceneKind): Int = when (kind) {
        SceneKind.Pool -> R.string.scene_pool
        SceneKind.Forest -> R.string.scene_forest
        SceneKind.White -> R.string.scene_white
    }

    @StringRes
    fun sceneDetail(kind: SceneKind): Int = when (kind) {
        SceneKind.Pool -> R.string.scene_pool_detail
        SceneKind.Forest -> R.string.scene_forest_detail
        SceneKind.White -> R.string.scene_white_detail
    }

    /** "Max power", or "Cinematic · Forest" for a scene. */
    fun modeName(context: Context, mode: StressMode): String {
        val title = context.getString(modeTitle(mode))
        val scene = mode.scene ?: return title
        return context.getString(R.string.title_with_part, title, context.getString(scene(scene)))
    }

    /** A stored run's mode, also for modes this version no longer has. */
    fun recordModeName(context: Context, record: RunRecord): String =
        StressMode.of(record.mode)?.let { modeName(context, it) } ?: record.mode

    @StringRes
    fun duration(duration: StressDuration): Int = when (duration) {
        StressDuration.One -> R.string.duration_1
        StressDuration.Five -> R.string.duration_5
        StressDuration.Fifteen -> R.string.duration_15
        StressDuration.Thirty -> R.string.duration_30
        StressDuration.Endless -> R.string.duration_endless
    }

    @StringRes
    fun quality(quality: SceneQuality): Int = when (quality) {
        SceneQuality.Low -> R.string.quality_low
        SceneQuality.Medium -> R.string.quality_medium
        SceneQuality.High -> R.string.quality_high
    }

    @StringRes
    fun qualityDetail(quality: SceneQuality): Int = when (quality) {
        SceneQuality.Low -> R.string.quality_low_detail
        SceneQuality.Medium -> R.string.quality_medium_detail
        SceneQuality.High -> R.string.quality_high_detail
    }

    /** A temperature's name by its stored key; old runs used Honor-specific names, shown as they are. */
    fun temperature(context: Context, key: String): String = when (key) {
        ThermalGroup.Cpu.key -> context.getString(R.string.temp_cpu)
        ThermalGroup.Gpu.key -> context.getString(R.string.temp_gpu)
        ThermalGroup.Memory.key, "DDR" -> context.getString(R.string.temp_memory)
        ThermalGroup.Skin.key -> context.getString(R.string.temp_skin)
        ThermalGroup.Npu.key -> context.getString(R.string.temp_npu)
        ThermalGroup.Modem.key -> context.getString(R.string.temp_modem)
        ThermalGroup.Camera.key -> context.getString(R.string.temp_camera)
        RunAnalysis.BATTERY, "Pil" -> context.getString(R.string.temp_battery)
        else -> key
    }

    fun temperatureColor(key: String): Color = when (key) {
        ThermalGroup.Cpu.key, "A715" -> StressColors.Accent
        ThermalGroup.Gpu.key, "GPU" -> StressColors.Cool
        ThermalGroup.Memory.key, "DDR" -> StressColors.Violet
        ThermalGroup.Skin.key, "A510" -> StressColors.Good
        RunAnalysis.BATTERY, "Pil" -> StressColors.AccentHot
        else -> StressColors.TextDim
    }

    @StringRes
    fun role(role: ClusterRole): Int = when (role) {
        ClusterRole.All -> R.string.role_all
        ClusterRole.Little -> R.string.role_little
        ClusterRole.Mid -> R.string.role_mid
        ClusterRole.Big -> R.string.role_big
        ClusterRole.Prime -> R.string.role_prime
    }

    fun clusterColor(index: Int, count: Int): Color {
        val palette = listOf(StressColors.Good, StressColors.Cool, StressColors.Accent, StressColors.AccentHot)
        if (count <= 1) return StressColors.Accent
        return palette.getOrElse(index + (palette.size - count).coerceAtLeast(0)) { StressColors.Violet }
    }

    @StringRes
    fun safetyReason(reason: SafetyReason): Int = when (reason) {
        SafetyReason.BatteryHot -> R.string.safety_reason_battery_hot
        SafetyReason.ChipHot -> R.string.safety_reason_chip_hot
        SafetyReason.SkinHot -> R.string.safety_reason_skin_hot
        SafetyReason.ThermalStatus -> R.string.safety_reason_thermal
        SafetyReason.BatteryLow -> R.string.safety_reason_battery_low
    }

    /** "Battery temperature 47.2 °C", "Battery 4%", "Android thermal status: Severe". */
    fun safetyFinding(context: Context, reason: SafetyReason?, value: Double?): String {
        if (reason == null) return ""
        val name = context.getString(safetyReason(reason))
        val reading = when (reason) {
            SafetyReason.BatteryLow -> Format.percentValue(value?.toInt())
            SafetyReason.ThermalStatus -> value?.let { context.getString(thermalStatus(it.toInt())) } ?: Format.MISSING
            else -> Format.celsius(value)
        }
        return context.getString(R.string.title_with_value, name, reading)
    }

    fun safetyFinding(context: Context, check: SafetyCheck): String = safetyFinding(context, check.reason, check.value)

    @StringRes
    fun thermalStatus(status: Int): Int = when (status) {
        0 -> R.string.thermal_none
        1 -> R.string.thermal_light
        2 -> R.string.thermal_moderate
        3 -> R.string.thermal_severe
        4 -> R.string.thermal_critical
        5 -> R.string.thermal_emergency
        else -> R.string.thermal_shutdown
    }

    @StringRes
    fun endReason(reason: EndReason): Int = when (reason) {
        EndReason.Completed -> R.string.end_completed
        EndReason.User -> R.string.end_user
        EndReason.Safety -> R.string.end_safety
        EndReason.GpuFailed -> R.string.end_gpu_failed
        EndReason.Interrupted -> R.string.end_interrupted
    }

    /** The short form, for list rows. */
    @StringRes
    fun endReasonShort(reason: EndReason): Int = when (reason) {
        EndReason.Completed -> R.string.end_completed_short
        EndReason.User -> R.string.end_user_short
        EndReason.Safety -> R.string.end_safety_short
        EndReason.GpuFailed -> R.string.end_gpu_failed_short
        EndReason.Interrupted -> R.string.end_interrupted_short
    }

    fun endReasonColor(reason: EndReason): Color = when (reason) {
        EndReason.Completed -> StressColors.Good
        EndReason.User -> StressColors.TextDim
        EndReason.Safety -> StressColors.Warn
        EndReason.GpuFailed, EndReason.Interrupted -> StressColors.Bad
    }

    /** A record's end, also for runs stored before the reason was kept. */
    fun endReasonOf(record: RunRecord): EndReason =
        record.endReason?.let { name -> EndReason.entries.firstOrNull { it.name == name } }
            ?: if (record.stoppedEarly) EndReason.User else EndReason.Completed

    /** A work rate's unit symbol in the screen's language: "FLOPS", "pixel/s". */
    fun rateSymbol(context: Context, unit: WorkUnit): String = when (unit) {
        WorkUnit.Pixel -> context.getString(R.string.unit_pixel_rate)
        else -> unit.rateSymbol
    }

    fun rateSymbol(context: Context, unitName: String?): String? =
        unitName?.let { name -> WorkUnit.entries.firstOrNull { it.name == name } }?.let { rateSymbol(context, it) }
}

/** The current context, for [Labels]' plain functions inside composables. */
@Composable
fun context(): Context = LocalContext.current
