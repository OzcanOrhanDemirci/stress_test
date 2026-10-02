package dev.ozcan.stress.ui

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.automirrored.rounded.TrendingDown
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import dev.ozcan.stress.R
import dev.ozcan.stress.analysis.Insight
import dev.ozcan.stress.analysis.Severity
import dev.ozcan.stress.analysis.StabilityGrade
import dev.ozcan.stress.ui.theme.StressColors

/** The words, icon and colour of each [Insight], shared by the result screen and the report. */
object InsightText {

    fun title(context: Context, insight: Insight): String = when (insight) {
        is Insight.EndedBySafety -> context.getString(R.string.insight_safety_title)
        Insight.GpuFailed -> context.getString(R.string.insight_gpu_failed_title)
        Insight.Interrupted -> context.getString(R.string.insight_interrupted_title)
        Insight.Charging -> context.getString(R.string.insight_charging_title)
        Insight.PowerFromCounter -> context.getString(R.string.insight_counter_title)
        is Insight.Throttled -> context.getString(R.string.insight_throttled_title, Format.clock(insight.seconds))
        Insight.NeverThrottled -> context.getString(R.string.insight_never_throttled_title)
        is Insight.PerformanceDrop -> context.getString(R.string.insight_drop_title, Format.percent(insight.drop))
        is Insight.Stability -> context.getString(
            R.string.insight_stability_title,
            Format.percent(insight.value),
            context.getString(grade(insight.grade)),
        )
        is Insight.ChipPeak -> context.getString(
            R.string.insight_chip_title,
            Labels.temperature(context, insight.key),
            Format.celsius(insight.celsius),
        )
        is Insight.BatteryWarmed -> context.getString(R.string.insight_battery_title, Format.celsius(insight.from), Format.celsius(insight.to))
        is Insight.BatteryDrain -> context.getString(R.string.insight_drain_title, Format.number(insight.percentPerHour, 0))
        is Insight.ComputationErrors -> if (insight.count == 0L) {
            context.getString(R.string.insight_no_errors_title)
        } else {
            context.getString(R.string.insight_errors_title, insight.count)
        }
        is Insight.FrameRate -> context.getString(R.string.insight_fps_title, Format.number(insight.mean, 1))
    }

    fun detail(context: Context, insight: Insight): String = when (insight) {
        is Insight.EndedBySafety -> context.getString(
            R.string.insight_safety_detail,
            Labels.safetyFinding(context, insight.reason, insight.value),
        )
        Insight.GpuFailed -> context.getString(R.string.insight_gpu_failed_detail)
        Insight.Interrupted -> context.getString(R.string.insight_interrupted_detail)
        Insight.Charging -> context.getString(R.string.insight_charging_detail)
        Insight.PowerFromCounter -> context.getString(R.string.insight_counter_detail)
        is Insight.Throttled -> context.getString(R.string.insight_throttled_detail, insight.cluster)
        Insight.NeverThrottled -> context.getString(R.string.insight_never_throttled_detail)
        is Insight.PerformanceDrop -> context.getString(
            when (insight.severity) {
                Severity.Good -> R.string.insight_drop_detail_good
                Severity.Info -> R.string.insight_drop_detail_info
                else -> R.string.insight_drop_detail_warn
            },
        )
        is Insight.Stability -> context.getString(R.string.insight_stability_detail)
        is Insight.ChipPeak -> context.getString(
            when (insight.severity) {
                Severity.Bad -> R.string.insight_chip_detail_over
                Severity.Warn -> R.string.insight_chip_detail_hot
                Severity.Info -> R.string.insight_chip_detail_warm
                else -> R.string.insight_chip_detail_cool
            },
        )
        is Insight.BatteryWarmed -> context.getString(
            if (insight.severity == Severity.Warn) R.string.insight_battery_detail_hot else R.string.insight_battery_detail,
            Format.number(insight.to - insight.from, 1),
        )
        is Insight.BatteryDrain -> context.getString(R.string.insight_drain_detail)
        is Insight.ComputationErrors -> context.getString(
            if (insight.count == 0L) R.string.insight_no_errors_detail else R.string.insight_errors_detail,
        )
        is Insight.FrameRate -> insight.min?.let { context.getString(R.string.insight_fps_detail, Format.number(it, 1)) }
            ?: context.getString(R.string.insight_fps_detail_plain)
    }

    fun icon(insight: Insight): ImageVector = when (insight) {
        is Insight.EndedBySafety -> Icons.Rounded.Shield
        Insight.GpuFailed, Insight.Interrupted -> Icons.Rounded.ErrorOutline
        Insight.Charging -> Icons.Rounded.BatteryChargingFull
        Insight.PowerFromCounter -> Icons.Rounded.Insights
        is Insight.Throttled, Insight.NeverThrottled -> Icons.Rounded.Speed
        is Insight.PerformanceDrop -> Icons.AutoMirrored.Rounded.TrendingDown
        is Insight.Stability -> Icons.Rounded.Insights
        is Insight.ChipPeak, is Insight.BatteryWarmed -> Icons.Rounded.Thermostat
        is Insight.BatteryDrain -> Icons.Rounded.BatteryAlert
        is Insight.ComputationErrors -> if (insight.count == 0L) Icons.Rounded.CheckCircle else Icons.Rounded.Warning
        is Insight.FrameRate -> Icons.Rounded.Videocam
    }

    fun color(severity: Severity): Color = when (severity) {
        Severity.Good -> StressColors.Good
        Severity.Info -> StressColors.Cool
        Severity.Warn -> StressColors.Warn
        Severity.Bad -> StressColors.Bad
    }

    fun grade(grade: StabilityGrade): Int = when (grade) {
        StabilityGrade.Excellent -> R.string.grade_excellent
        StabilityGrade.Good -> R.string.grade_good
        StabilityGrade.Fair -> R.string.grade_fair
        StabilityGrade.Poor -> R.string.grade_poor
    }
}
