package dev.ozcan.stress.ui.components

import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp

/**
 * A label that stays on one line: at [style]'s size when it fits, otherwise shrunk in small steps down to
 * [minScale] of that size, and only then cut with an ellipsis. A word is never broken across lines ("CS" / "V").
 * Used for button, chip, segment, tab and status labels; screens can use it for any short label in a tight cell.
 */
@Composable
fun FitText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    textAlign: TextAlign = TextAlign.Unspecified,
    minScale: Float = 0.7f,
) {
    val max = style.fontSize
    val ink = color.takeOrElse { style.color.takeOrElse { LocalContentColor.current } }
    BasicText(
        text,
        modifier,
        style = style.merge(color = ink, textAlign = textAlign),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        autoSize = TextAutoSize.StepBased(minFontSize = max * minScale, maxFontSize = max, stepSize = 0.5.sp),
    )
}
