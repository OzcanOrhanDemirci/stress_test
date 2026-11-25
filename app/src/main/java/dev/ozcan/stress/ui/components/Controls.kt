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
import androidx.compose.material.icons.rounded.Terminal
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ozcan.stress.R
import dev.ozcan.stress.ui.theme.StressColors
import dev.ozcan.stress.ui.theme.StressMotion
import dev.ozcan.stress.ui.theme.StressType
import dev.ozcan.stress.ui.upper
import java.util.Locale

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
) = ActionButton(text, onClick, modifier, icon, enabled, 48.dp, StressColors.SurfaceHigh, color, outline = true)

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
            pressed && outline -> StressColors.SurfaceHighest
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
        Column(Modifier.weight(1f, fill = false), horizontalAlignment = Alignment.CenterHorizontally) {
            FitText(text.upper(), StressType.Label.copy(fontSize = 14.sp, lineHeight = 20.sp), color = foreground, textAlign = TextAlign.Center)
            if (subtitle != null) {
                FitText(subtitle, StressType.Code, color = foreground, textAlign = TextAlign.Center)
            }
        }
    }
}

/**
 * Equal settings [options] on a dark track. [enabled] can disable individual options. Labels are upper-cased by the
 * screen's language unless [labelLocale] names the language an option is written in ("English" on a Turkish screen).
 */
@Composable
fun <T> SegmentedControl(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: @Composable (T) -> String,
    modifier: Modifier = Modifier,
    enabled: (T) -> Boolean = { true },
    height: Dp = 48.dp,
    labelLocale: (T) -> Locale? = { null },
) {
    ChoiceRow(options, selected, onSelect, label, modifier, enabled, height, segmented = true, labelLocale = labelLocale)
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
    labelLocale: (T) -> Locale? = { null },
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
                    else -> StressColors.SurfaceHigh
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
                    // 6 dp keeps a long label ("Until full" on a 320 dp phone) off the rule; it shrinks to fit inside.
                    .background(fill, shape).border(1.dp, line, shape).padding(horizontal = 6.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                FitText(
                    if (segmented) label(option).let { text -> labelLocale(option)?.let(text::upper) ?: text.upper() } else label(option),
                    if (segmented) StressType.Label else StressType.Code,
                    color = tint,
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
        StressSwitch(checked, onCheckedChange)
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

/** The app's switch: `Cool` track when on. Pass a null [onCheckedChange] when the whole row toggles it. */
@Composable
fun StressSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
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

/**
 * The control surface shared by the full-width rows: `SurfaceHigh` fill, 1 dp `OutlineStrong` rule, radius 6,
 * `SurfaceHighest` while pressed, at least 56 dp tall. Without [onClick] it keeps the look (the row holds a control).
 */
@Composable
internal fun ControlSurface(
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    role: Role = Role.Button,
    content: @Composable () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val fill by animateColorAsState(
        if (pressed) StressColors.SurfaceHighest else StressColors.SurfaceHigh, StressMotion.state(), label = "row fill",
    )
    Box(
        modifier.fillMaxWidth().heightIn(min = 56.dp).clip(ControlShape).background(fill)
            .border(1.dp, StressColors.OutlineStrong, ControlShape)
            .then(if (onClick == null) Modifier else Modifier.clickable(source, null, role = role, onClick = onClick)),
        contentAlignment = Alignment.CenterStart,
    ) { content() }
}

/**
 * The last item of every simple page: a full-width control to its engineering page. [caption] says what the page
 * holds, in Code style ("42 VALUES"; `pluralStringResource(R.plurals.parts_values, n, n).upper()`).
 */
@Composable
fun EngineeringRow(caption: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ControlSurface(onClick, modifier) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            Arrangement.spacedBy(12.dp),
            Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Terminal, null, Modifier.size(20.dp), tint = StressColors.TextDim)
            Text(
                stringResource(R.string.parts_engineering_view).upper(), Modifier.weight(1f),
                style = StressType.Label, color = StressColors.Text, maxLines = 2,
            )
            Text(caption, style = StressType.Code, color = StressColors.TextDim, maxLines = 1)
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = StressColors.TextDim)
        }
    }
}

/**
 * A setting (DESIGN "Settings"): [title] in Heading, one line of [description] in Body small `TextDim`, the
 * [control] on the right (a [StressSwitch], a value) or, with [controlBelow], under the text at full width (a
 * [SegmentedControl]). [onClick] makes the whole row act, e.g. toggle the switch; the row is drawn as a control
 * either way, since it holds one.
 */
@Composable
fun SettingRow(
    title: String,
    description: String?,
    control: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    controlBelow: Boolean = false,
) {
    ControlSurface(onClick, modifier) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(12.dp), Alignment.CenterVertically) {
                SettingText(title, description, Modifier.weight(1f))
                if (!controlBelow) control()
            }
            if (controlBelow) control()
        }
    }
}

/** A row that opens something: [title], optional [description], the current [value] in `TextDim`, a chevron. */
@Composable
fun NavRow(
    title: String,
    description: String? = null,
    value: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ControlSurface(onClick, modifier) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            Arrangement.spacedBy(8.dp),
            Alignment.CenterVertically,
        ) {
            SettingText(title, description, Modifier.weight(1f))
            if (value != null) Text(value, style = StressType.Body, color = StressColors.TextDim, maxLines = 1)
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = StressColors.TextDim)
        }
    }
}

@Composable
private fun SettingText(title: String, description: String?, modifier: Modifier) {
    Column(modifier, Arrangement.spacedBy(2.dp)) {
        Text(title, style = StressType.Heading, color = StressColors.Text)
        if (description != null) Text(description, style = StressType.BodySmall, color = StressColors.TextDim)
    }
}
