package dev.ozcan.stress.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ozcan.stress.ui.Format
import dev.ozcan.stress.ui.theme.NumberStyles
import dev.ozcan.stress.ui.theme.StressColors

/**
 * A number that counts to its new value instead of jumping. [format] turns
 * the animated value into text; null shows the missing-value dash.
 */
@Composable
fun AnimatedNumber(
    value: Double?,
    format: (Double) -> String,
    modifier: Modifier = Modifier,
    style: TextStyle = NumberStyles.Medium,
    color: Color = StressColors.Text,
    durationMillis: Int = 600,
) {
    val animatable = remember { Animatable(value?.toFloat() ?: 0f) }
    val first = remember { mutableListOf(true) }
    LaunchedEffect(value) {
        val target = value?.toFloat() ?: return@LaunchedEffect
        if (first[0]) {
            first[0] = false
            animatable.snapTo(target)
        } else {
            animatable.animateTo(target, tween(durationMillis, easing = FastOutSlowInEasing))
        }
    }
    Text(
        if (value == null) Format.MISSING else format(animatable.value.toDouble()),
        modifier = modifier,
        style = style,
        color = color,
        maxLines = 1,
    )
}

/**
 * A number that counts up from zero the first time it is shown: the reveal
 * of a result.
 */
@Composable
fun CountUpNumber(
    value: Double?,
    format: (Double) -> String,
    modifier: Modifier = Modifier,
    style: TextStyle = NumberStyles.Large,
    color: Color = StressColors.Text,
    delayMillis: Int = 0,
) {
    val animatable = remember { Animatable(0f) }
    LaunchedEffect(value) {
        val target = value?.toFloat() ?: return@LaunchedEffect
        animatable.animateTo(target, tween(1100, delayMillis = delayMillis, easing = FastOutSlowInEasing))
    }
    Text(
        if (value == null) Format.MISSING else format(animatable.value.toDouble()),
        modifier = modifier,
        style = style,
        color = color,
        maxLines = 1,
    )
}

/** A metric tile: an icon and label over a value and its unit. */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    icon: ImageVector? = null,
    accent: Color = StressColors.Text,
    footnote: String? = null,
    footnoteColor: Color = StressColors.TextFaint,
) {
    GlassCard(modifier = modifier, padding = 14.dp, shape = TileShape) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, tint = StressColors.TextFaint, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = StressColors.TextDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(value, style = NumberStyles.Medium, color = accent, maxLines = 1)
            if (unit != null) {
                Text(unit, style = MaterialTheme.typography.labelMedium, color = StressColors.TextDim, modifier = Modifier.padding(bottom = 2.dp))
            }
        }
        if (footnote != null) {
            Text(footnote, style = MaterialTheme.typography.labelSmall, color = footnoteColor, maxLines = 2)
        }
    }
}

/** Two tiles side by side. */
@Composable
fun TileRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

/** A column with the app's vertical rhythm. */
@Composable
fun Stack(modifier: Modifier = Modifier, spacing: Int = 12, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(spacing.dp), content = content)
}
