package dev.ozcan.stress.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.ozcan.stress.ui.Format
import dev.ozcan.stress.ui.theme.NumberStyles
import dev.ozcan.stress.ui.theme.StressColors
import dev.ozcan.stress.ui.theme.StressType
import dev.ozcan.stress.ui.upper

/** Formats [value] in place after an optional first reveal of at most 600 ms. */
@Composable
fun CountUpNumber(
    value: Double?,
    format: (Double) -> String,
    modifier: Modifier = Modifier,
    style: TextStyle = NumberStyles.Large,
    color: Color = StressColors.Text,
    delayMillis: Int = 0,
) {
    val played = rememberSaveable { mutableStateOf(false) }
    val animating = remember { mutableStateOf(false) }
    val reading = remember { Animatable(0f) }
    LaunchedEffect(value) {
        val target = value ?: return@LaunchedEffect
        if (!played.value) {
            played.value = true
            animating.value = true
            val delay = delayMillis.coerceIn(0, 599)
            try {
                reading.animateTo(target.toFloat(), tween(600 - delay, delay, LinearOutSlowInEasing))
            } finally {
                animating.value = false
            }
        }
    }
    FitText(
        value?.let { format(if (animating.value) reading.value.toDouble() else it) } ?: Format.MISSING,
        style, modifier, color = if (value == null) StressColors.TextDim else color, minScale = 0.6f,
    )
}

/** A reading with a baseline-aligned, dimmed [unit] at 60% of the value size; a missing one is dimmed whole. */
@Composable
fun MetricNumber(
    value: String,
    unit: String? = null,
    style: TextStyle = NumberStyles.Medium,
    color: Color = StressColors.Text,
) {
    val text = buildAnnotatedString {
        append(value)
        if (unit != null && value != Format.MISSING) {
            withStyle(SpanStyle(color = StressColors.TextDim, fontSize = 0.6.em)) { append(" $unit") }
        }
    }
    BasicText(
        text,
        style = style.copy(color = if (value == Format.MISSING) StressColors.TextDim else color),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = style.fontSize, stepSize = 1.sp),
    )
}

/** A readout tile (flat `Surface`, no rule) with a live [statusColor] dot and an optional [meter] or Code [caption]. */
@Composable
fun MetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    color: Color = StressColors.Text,
    statusColor: Color? = null,
    caption: String? = null,
    captionColor: Color = StressColors.TextDim,
    meter: Float? = null,
    style: TextStyle = NumberStyles.Medium,
    padding: Dp = 12.dp,
) {
    Panel(modifier, padding = padding) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(4.dp), Alignment.CenterVertically) {
                // One line, shrunk to fit, so the values of a row of tiles stay on one line too.
                FitText(label.upper(), StressType.Label, Modifier.weight(1f), color = StressColors.TextDim)
                if (statusColor != null) Box(Modifier.size(6.dp).background(statusColor, CircleShape))
            }
            MetricNumber(value, unit, style, color)
            if (meter != null) {
                MeterBar(meter, color = color)
            } else if (caption != null) {
                Text(caption, style = StressType.Code, color = captionColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Compatibility entry point for the existing result tiles. */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    accent: Color = StressColors.Text,
    footnote: String? = null,
    footnoteColor: Color = StressColors.TextFaint,
) {
    MetricTile(label, value, modifier, unit = unit, color = accent, caption = footnote, captionColor = footnoteColor)
}

/** Two or three tiles side by side, with an 8 dp gap. */
@Composable
fun TileRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

/** A column with the vertical rhythm for screen content. */
@Composable
fun Stack(modifier: Modifier = Modifier, spacing: Int = 12, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(spacing.dp), content = content)
}
