package dev.ozcan.stress.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ozcan.stress.ui.theme.NumberStyles
import dev.ozcan.stress.ui.theme.StressColors
import dev.ozcan.stress.ui.theme.StressMotion
import dev.ozcan.stress.ui.theme.StressType
import dev.ozcan.stress.ui.upper

internal val PanelShape = RoundedCornerShape(8.dp)
internal val ButtonShape = RoundedCornerShape(6.dp)
internal val ChipShape = RoundedCornerShape(4.dp)

/** Radius of every control that is not a chip: panels, rows and cards that act or navigate. */
internal val ControlShape = ButtonShape

/**
 * A full-width column in one of two kinds (DESIGN "Pressable and readout").
 *
 * - **Readout**: no [onClick] and [selected] null or false. Flat `Surface` fill, no rule, radius 8, no pressed state.
 * - **Control**: [onClick] set, or [selected] not null. `SurfaceHigh` fill, 1 dp `OutlineStrong` rule, radius 6,
 *   a lighter fill while pressed. [selected] true keeps the [selectionColor] rule and its 12 % tint, and with
 *   [onClick] turns the panel into a radio choice.
 *
 * [title] adds a heading above [content].
 */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    title: String? = null,
    padding: Dp = 16.dp,
    selected: Boolean? = null,
    selectionColor: Color = StressColors.Cool,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val control = onClick != null || selected != null
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val ground = when {
        !control -> StressColors.Surface
        pressed && enabled -> StressColors.SurfaceHighest
        else -> StressColors.SurfaceHigh
    }
    val line by animateColorAsState(
        when {
            selected == true -> selectionColor
            control -> StressColors.OutlineStrong
            else -> Color.Transparent
        },
        StressMotion.state(),
        label = "panel outline",
    )
    val fill by animateColorAsState(
        if (selected == true) selectionColor.copy(alpha = 0.12f).compositeOver(ground) else ground,
        StressMotion.state(),
        label = "panel fill",
    )
    val shape = if (control) ControlShape else PanelShape
    val action = when {
        onClick == null -> Modifier
        selected == null -> Modifier.clickable(interaction, null, enabled = enabled, role = Role.Button, onClick = onClick)
        else -> Modifier.selectable(selected, interaction, null, enabled = enabled, role = Role.RadioButton, onClick = onClick)
    }
    Column(
        modifier.clip(shape).background(fill)
            .then(if (control) Modifier.border(1.dp, line, shape) else Modifier)
            .then(if (onClick != null) Modifier.heightIn(min = 48.dp) else Modifier)
            .then(action).padding(padding),
        verticalArrangement = Arrangement.spacedBy(if (title == null) 0.dp else 8.dp),
    ) {
        if (title != null) Text(title, style = StressType.Heading)
        content()
    }
}

/** Compatibility entry point for panels already in existing screens. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
    accent: Color = StressColors.Accent,
    padding: Dp = 16.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) = Panel(modifier, padding = padding, selected = true.takeIf { highlight }, selectionColor = accent, onClick = onClick, content = content)

/** Uppercase section [text] with an optional Code [caption] aligned to the end. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, caption: String? = null) {
    SectionHeader(text, modifier) {
        if (caption != null) Text(caption, style = StressType.Code, color = StressColors.TextDim)
    }
}

/** Section label with a trailing slot for existing callers that supply a control. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {
        Text(text.upper(), Modifier.weight(1f), color = StressColors.TextDim, style = StressType.Label)
        trailing()
    }
}

/** An outlined state label. [color] supplies the border, label and light tint. */
@Composable
fun StatusChip(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
) {
    val hitTarget = if (onClick == null) Modifier else Modifier.heightIn(min = 48.dp)
        .clickable(role = Role.Button, onClick = onClick)
    Box(modifier.then(hitTarget), contentAlignment = Alignment.Center) {
        OutlinedTag(text.upper(), color, icon = icon, tint = 0.10f, style = StressType.Label)
    }
}

/** A compact Code label, optionally backed by a scrim or solid colour behind the tag. */
@Composable
fun CodeTag(text: String, color: Color, modifier: Modifier = Modifier, background: Color = Color.Transparent) {
    Box(modifier) {
        OutlinedTag(text, color, background = background, style = StressType.Code)
    }
}

@Composable
private fun OutlinedTag(
    text: String,
    color: Color,
    icon: ImageVector? = null,
    tint: Float = 0f,
    background: Color = Color.Transparent,
    style: TextStyle,
) {
    Row(
        Modifier.background(color.copy(alpha = tint).compositeOver(background), ChipShape)
            .border(1.dp, color, ChipShape).padding(horizontal = 8.dp, vertical = 4.dp),
        Arrangement.spacedBy(4.dp),
        Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, null, tint = color, modifier = Modifier.size(14.dp))
        FitText(text, style, Modifier.weight(1f, fill = false), color = color)
    }
}

/** Compatibility entry point for existing status labels. */
@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier, icon: ImageVector? = null) =
    StatusChip(text, color, modifier, icon)

/** An icon on a compact filled square; no rule, since it is not itself a control. */
@Composable
fun IconBadge(icon: ImageVector, tint: Color, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    Box(
        modifier.size(size).background(StressColors.SurfaceHighest, ButtonShape),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.5f)) }
}

/**
 * Paints a bar's [color] behind it across the whole window, beyond the centred content width a tablet keeps, with
 * its 1 dp `Outline` rule along the top ([ruleAtTop]) or bottom edge, so a bar does not end at the content's edge.
 * On a phone the content is the window and this is the same as a background.
 */
fun Modifier.windowBand(color: Color, ruleAtTop: Boolean): Modifier = drawBehind {
    val bleed = BandBleed.toPx()
    val width = size.width + 2 * bleed
    drawRect(color, Offset(-bleed, 0f), Size(width, size.height))
    val rule = 1.dp.toPx()
    drawRect(StressColors.Outline, Offset(-bleed, if (ruleAtTop) 0f else size.height - rule), Size(width, rule))
}

/** Further than any window reaches past the centred content on either side. */
private val BandBleed = 2000.dp

/** A 1 dp horizontal divider. */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(StressColors.Outline))
}

/** A square-ended meter. [fraction] is clamped to 0–1 and [color] supplies the flat fill. */
@Composable
fun MeterBar(fraction: Float, modifier: Modifier = Modifier, color: Color = StressColors.Accent, height: Dp = 3.dp) {
    val fill by animateFloatAsState(fraction.coerceIn(0f, 1f), StressMotion.state(), label = "meter")
    Canvas(modifier.fillMaxWidth().height(height)) {
        drawRect(StressColors.SurfaceHighest)
        drawRect(color, size = size.copy(width = size.width * fill))
    }
}

/** A sentence, with optional [detail] below it and a right-aligned verdict [value] when one is measured. */
@Composable
fun InsightRow(
    text: String,
    value: String?,
    modifier: Modifier = Modifier,
    valueColor: Color = StressColors.Text,
    divider: Boolean = true,
    detail: String? = null,
) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.padding(vertical = 8.dp), Arrangement.spacedBy(12.dp), Alignment.CenterVertically) {
            Column(Modifier.weight(1f), Arrangement.spacedBy(4.dp)) {
                Text(text, style = StressType.Body, color = if (value == null) valueColor else StressColors.Text)
                if (detail != null) Text(detail, style = StressType.BodySmall, color = StressColors.TextDim)
            }
            if (value != null) Text(value, style = NumberStyles.Small, color = valueColor)
        }
        if (divider) Hairline()
    }
}

/**
 * A label and reading in a technical inventory. [detail] describes its source or why it is absent.
 * Set [valueWeight] to reserve proportional space for a long value that needs to wrap beside its label.
 */
@Composable
fun InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = StressColors.Text,
    detail: String? = null,
    valueWeight: Float? = null,
) {
    Column(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(12.dp), Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), color = StressColors.TextDim, style = StressType.Body)
            Text(
                value,
                modifier = if (valueWeight == null) Modifier else Modifier.weight(valueWeight),
                textAlign = if (valueWeight == null) TextAlign.Start else TextAlign.End,
                color = valueColor,
                style = NumberStyles.Small,
            )
        }
        if (detail != null) {
            Text(detail, Modifier.padding(top = 4.dp), color = StressColors.TextDim, style = StressType.BodySmall)
        }
    }
}

/** Provides a flat, full-screen ground to layer screen content over. */
@Composable
fun AppBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxSize().background(StressColors.Background), content = content)
}

/** Centres [content] in a full-size box for loading and empty states. */
@Composable
fun CenteredBox(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center, content = content)
}
