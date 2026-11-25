package dev.ozcan.stress.ui.run

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.ozcan.stress.R
import dev.ozcan.stress.engine.SceneKind
import dev.ozcan.stress.engine.SceneQuality
import dev.ozcan.stress.ui.Format
import dev.ozcan.stress.ui.Labels
import dev.ozcan.stress.ui.theme.StressColors

@Composable
fun DrawnQualityNotice(
    kind: SceneKind?,
    requested: SceneQuality?,
    drawn: SceneQuality?,
    drawnScalePercent: Double?,
) {
    if (kind == null || requested == null) return
    val requestedScale = requested.scalePercent(kind)
    val notice = when {
        drawn == null -> stringResource(R.string.drawn_quality_unsettled)
        drawn != requested && drawnScalePercent != null && drawnScalePercent != drawn.scalePercent(kind).toDouble() ->
            stringResource(
                R.string.drawn_quality_and_scale_reduced,
                stringResource(Labels.quality(drawn)),
                scaleText(drawnScalePercent),
                stringResource(Labels.quality(requested)),
            )
        drawn != requested -> stringResource(
            R.string.drawn_quality_reduced,
            stringResource(Labels.quality(drawn)),
            stringResource(Labels.quality(requested)),
        )
        drawnScalePercent != null && drawnScalePercent != requestedScale.toDouble() -> stringResource(
            R.string.drawn_scale_reduced,
            scaleText(drawnScalePercent),
            Format.percentValue(requestedScale),
        )
        else -> return
    }
    Text(notice, style = MaterialTheme.typography.bodySmall, color = StressColors.Warn)
}

private fun scaleText(percent: Double): String =
    Format.percent(percent / 100, decimals = if (percent % 1 == 0.0) 0 else 2)
