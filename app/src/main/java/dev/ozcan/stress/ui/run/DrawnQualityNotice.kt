package dev.ozcan.stress.ui.run

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.ozcan.stress.R
import dev.ozcan.stress.engine.SceneKind
import dev.ozcan.stress.engine.SceneQuality
import dev.ozcan.stress.ui.Format
import dev.ozcan.stress.ui.Labels
import dev.ozcan.stress.ui.theme.StressColors
import dev.ozcan.stress.ui.theme.StressType

@Composable
fun DrawnQualityNotice(
    kind: SceneKind?,
    requested: SceneQuality?,
    drawn: SceneQuality?,
    drawnScalePercent: Double?,
) {
    val notice = drawnQualityText(kind, requested, drawn, drawnScalePercent) ?: return
    Text(notice, style = StressType.BodySmall, color = StressColors.Warn)
}

/** The sentence [DrawnQualityNotice] shows, or null when the scene is drawn as requested. */
@Composable
internal fun drawnQualityText(
    kind: SceneKind?,
    requested: SceneQuality?,
    drawn: SceneQuality?,
    drawnScalePercent: Double?,
): String? {
    if (kind == null || requested == null) return null
    val requestedScale = requested.scalePercent(kind)
    return when {
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
        else -> null
    }
}

private fun scaleText(percent: Double): String =
    Format.percent(percent / 100, decimals = if (percent % 1 == 0.0) 0 else 2)
