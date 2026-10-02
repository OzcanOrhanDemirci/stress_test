package dev.ozcan.stress.ui.components

import dev.ozcan.stress.ui.upper
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ozcan.stress.ui.theme.StressColors

val CardShape = RoundedCornerShape(20.dp)
val TileShape = RoundedCornerShape(16.dp)
val PillShape = RoundedCornerShape(50)

/** The app's backdrop: near black, warmed at the top left and cooled at the bottom right. */
@Composable
fun AppBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(StressColors.Background)
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        listOf(StressColors.Accent.copy(alpha = 0.10f), Color.Transparent),
                        center = Offset(size.width * 0.05f, size.height * 0.02f),
                        radius = size.maxDimension * 0.6f,
                    ),
                )
                drawRect(
                    Brush.radialGradient(
                        listOf(StressColors.CoolDeep.copy(alpha = 0.07f), Color.Transparent),
                        center = Offset(size.width * 0.95f, size.height * 0.85f),
                        radius = size.maxDimension * 0.55f,
                    ),
                )
            },
        content = content,
    )
}

/**
 * A raised card: a dark surface with a hairline border lit from the top left.
 * [highlight] lights the border in the accent (animated), for a selected card.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
    accent: Color = StressColors.Accent,
    shape: Shape = CardShape,
    padding: Dp = 16.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.98f else 1f, spring(stiffness = 600f), label = "press")
    val border by animateColorAsState(if (highlight) accent else StressColors.Outline, tween(250), label = "border")
    val glow by animateFloatAsState(if (highlight) 1f else 0f, tween(300), label = "glow")
    Column(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .drawBehind {
                if (glow > 0f) {
                    drawRoundRect(
                        Brush.radialGradient(
                            listOf(accent.copy(alpha = 0.16f * glow), Color.Transparent),
                            center = Offset(size.width * 0.2f, 0f),
                            radius = size.maxDimension,
                        ),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(20.dp.toPx()),
                    )
                }
            }
            .clip(shape)
            .background(
                Brush.verticalGradient(listOf(StressColors.SurfaceHigh, StressColors.Surface)),
                shape,
            )
            .border(
                width = if (highlight) 1.5.dp else 1.dp,
                brush = Brush.linearGradient(listOf(border.copy(alpha = if (highlight) 1f else 0.9f), border.copy(alpha = 0.35f))),
                shape = shape,
            )
            .then(if (onClick != null) Modifier.clickable(interaction, indication = null, onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

/** An uppercase, spaced-out caption over a section. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).background(StressColors.Heat, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(
            text.upper(),
            style = MaterialTheme.typography.labelMedium,
            color = StressColors.TextDim,
            letterSpacing = 1.8.sp,
            modifier = Modifier.weight(1f),
        )
        trailing()
    }
}

/** A small rounded label: a status, a tag. */
@Composable
fun Pill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    filled: Boolean = false,
) {
    Row(
        modifier = modifier
            .clip(PillShape)
            .background(if (filled) color else color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = if (filled) 0f else 0.35f), PillShape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = if (filled) StressColors.Background else color, modifier = Modifier.size(13.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (filled) StressColors.Background else color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** An icon in a tinted circle, the lead of a row or a card. */
@Composable
fun IconBadge(icon: ImageVector, tint: Color, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    Box(
        modifier = modifier
            .size(size)
            .background(Brush.linearGradient(listOf(tint.copy(alpha = 0.22f), tint.copy(alpha = 0.08f))), CircleShape)
            .border(1.dp, tint.copy(alpha = 0.3f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

/** A thin horizontal rule. */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = 2.dp).height(1.dp).background(StressColors.Outline))
}

/** A horizontal bar filled to [fraction], animated, in [color] (or the heat gradient). */
@Composable
fun MeterBar(fraction: Float, modifier: Modifier = Modifier, color: Color? = null, height: Dp = 6.dp) {
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(450), label = "meter")
    Canvas(modifier = modifier.fillMaxWidth().height(height)) {
        val r = size.height / 2
        drawRoundRect(StressColors.SurfaceHighest, cornerRadius = androidx.compose.ui.geometry.CornerRadius(r))
        if (animated > 0f) {
            val w = (size.width * animated).coerceAtLeast(size.height)
            if (color != null) {
                drawRoundRect(color, size = size.copy(width = w), cornerRadius = androidx.compose.ui.geometry.CornerRadius(r))
            } else {
                drawRoundRect(
                    Brush.horizontalGradient(listOf(StressColors.Accent, StressColors.AccentHot), endX = size.width),
                    size = size.copy(width = w),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(r),
                )
            }
        }
    }
}

/** A labelled value on one line: label left, value right. */
@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = StressColors.Text) {
    Row(modifier = modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = StressColors.TextDim, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            value,
            color = valueColor,
            style = dev.ozcan.stress.ui.theme.NumberStyles.Small,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

/** Centres [content] in a full-size box: for empty and loading states. */
@Composable
fun CenteredBox(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center, content = content)
}
