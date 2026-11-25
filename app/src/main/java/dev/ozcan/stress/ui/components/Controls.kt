package dev.ozcan.stress.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ozcan.stress.ui.theme.StressColors
import dev.ozcan.stress.ui.theme.StressMotion
import dev.ozcan.stress.ui.theme.StressType
import dev.ozcan.stress.ui.upper

/** The orange primary action. [subtitle] adds an optional Code line below the label. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    height: Dp = 52.dp,
) = ActionButton(text, onClick, modifier, icon, enabled, height, StressColors.Accent, StressColors.OnAccent, subtitle = subtitle)

/** A danger action with the square stop icon. */
@Composable
fun StopButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) =
    ActionButton(text, onClick, modifier, Icons.Rounded.Stop, enabled, 52.dp, StressColors.Danger, StressColors.OnDanger)

/** An outlined action for secondary controls and dialog buttons. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    color: Color = StressColors.Text,
    enabled: Boolean = true,
) = ActionButton(text, onClick, modifier, icon, enabled, 48.dp, StressColors.Surface, color, outline = true)

@Composable
private fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    icon: ImageVector?,
    enabled: Boolean,
    height: Dp,
    fill: Color,
    color: Color,
    outline: Boolean = false,
    subtitle: String? = null,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val foreground = if (enabled) color else StressColors.TextDim
    val background by animateColorAsState(
        when {
            !enabled -> StressColors.SurfaceHighest
            pressed && outline -> StressColors.SurfaceHigh
            pressed -> Color.Black.copy(alpha = 0.12f).compositeOver(fill)
            else -> fill
        },
        StressMotion.state(),
        label = "button fill",
    )
    Row(
        modifier.fillMaxWidth().heightIn(min = height.coerceAtLeast(48.dp))
            .clip(ButtonShape).background(background)
            .then(if (outline) Modifier.border(1.dp, StressColors.OutlineStrong, ButtonShape) else Modifier)
            .clickable(source, null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(20.dp), tint = foreground)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text.upper(), style = StressType.Label.copy(fontSize = 14.sp, lineHeight = 20.sp), color = foreground, textAlign = TextAlign.Center)
            if (subtitle != null) {
                Text(subtitle, style = StressType.Code, color = foreground, textAlign = TextAlign.Center)
            }
        }
    }
}

/** Equal settings [options] on a dark track. [enabled] can disable individual options. */
@Composable
fun <T> SegmentedControl(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: @Composable (T) -> String,
    modifier: Modifier = Modifier,
    enabled: (T) -> Boolean = { true },
    height: Dp = 48.dp,
) {
    ChoiceRow(options, selected, onSelect, label, modifier, enabled, height, segmented = true)
}

/** Equal duration cells. [accessibleLabel] can give compact Code labels their expanded spoken name. */
@Composable
fun <T> ChoiceChips(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: @Composable (T) -> String,
    modifier: Modifier = Modifier,
    accessibleLabel: (@Composable (T) -> String)? = null,
) {
    ChoiceRow(options, selected, onSelect, label, modifier, { true }, 48.dp, segmented = false, accessibleLabel)
}

@Composable
private fun <T> ChoiceRow(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: @Composable (T) -> String,
    modifier: Modifier,
    enabled: (T) -> Boolean,
    height: Dp,
    segmented: Boolean,
    accessibleLabel: (@Composable (T) -> String)? = null,
) {
    val selectedColor = if (segmented) StressColors.Cool else StressColors.Accent
    val shape = if (segmented) ButtonShape else ChipShape
    val track = if (segmented) Modifier.background(StressColors.SurfaceLow, shape)
        .border(1.dp, StressColors.Outline, shape).padding(horizontal = 4.dp) else Modifier
    Row(
        modifier.fillMaxWidth().then(track).selectableGroup(),
        Arrangement.spacedBy(if (segmented) 4.dp else 8.dp),
    ) {
        options.forEach { option ->
            val active = option == selected
            val canPick = enabled(option)
            val description = accessibleLabel?.invoke(option)
            val tint by animateColorAsState(
                when {
                    !canPick -> StressColors.TextFaint
                    active -> selectedColor
                    else -> StressColors.TextDim
                },
                StressMotion.state(), label = "choice label",
            )
            val fill by animateColorAsState(
                when {
                    active && segmented -> StressColors.SurfaceHigh
                    active -> StressColors.AccentTint.compositeOver(StressColors.Surface)
                    segmented -> Color.Transparent
                    else -> StressColors.Surface
                },
                StressMotion.state(), label = "choice fill",
            )
            val line by animateColorAsState(
                if (active) selectedColor else if (segmented) Color.Transparent else StressColors.OutlineStrong,
                StressMotion.state(), label = "choice outline",
            )
            Box(
                Modifier.weight(1f).heightIn(min = height.coerceAtLeast(48.dp))
                    .clip(shape).selectable(active, enabled = canPick, role = Role.RadioButton) { onSelect(option) }
                    .then(if (description == null) Modifier else Modifier.semantics { contentDescription = description })
                    .padding(vertical = if (segmented) 4.dp else 0.dp)
                    .background(fill, shape).border(1.dp, line, shape).padding(horizontal = 2.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (segmented) label(option).upper() else label(option),
                    color = tint,
                    style = if (segmented) StressType.Label else StressType.Code,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** A setting: icon, title, description, and a switch on the right. */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = StressColors.TextDim,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(ButtonShape)
            .clickable(role = Role.Switch) { onCheckedChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (icon != null) IconBadge(icon, iconTint, size = 38.dp)
        Column(Modifier.weight(1f)) {
            Text(title, style = StressType.Heading)
            if (description != null) {
                Text(description, style = StressType.BodySmall, color = StressColors.TextDim)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = StressColors.Cool,
                checkedBorderColor = StressColors.Cool,
                uncheckedThumbColor = StressColors.TextDim,
                uncheckedTrackColor = StressColors.SurfaceHighest,
                uncheckedBorderColor = StressColors.OutlineStrong,
            ),
        )
    }
}

/** A tappable row that leads somewhere: icon, title, description, chevron. */
@Composable
fun NavigationRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = StressColors.TextDim,
    trailing: ImageVector? = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
    titleColor: Color = StressColors.Text,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(ButtonShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (icon != null) IconBadge(icon, iconTint, size = 38.dp)
        Column(Modifier.weight(1f)) {
            Text(title, style = StressType.Heading, color = titleColor)
            if (description != null) {
                Text(description, style = StressType.BodySmall, color = StressColors.TextDim)
            }
        }
        if (trailing != null) Icon(trailing, null, tint = StressColors.TextFaint)
    }
}
