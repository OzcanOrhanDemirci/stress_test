package dev.ozcan.stress.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.GppBad
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.ozcan.stress.R
import dev.ozcan.stress.ui.components.Hairline
import dev.ozcan.stress.ui.components.IconBadge
import dev.ozcan.stress.ui.components.PanelDialog
import dev.ozcan.stress.ui.components.PrimaryButton
import dev.ozcan.stress.ui.theme.StressColors
import dev.ozcan.stress.ui.theme.StressType

/**
 * The use-with-care notice shown when the app opens: the phone runs at full
 * capacity and gets very hot, device safety cannot guarantee protection, and
 * the responsibility is the user's. It is closed only by accepting it;
 * "don't show again" turns it off (Settings turns it back on).
 */
@Composable
fun SafetyNotice(safetyOn: Boolean, onAccept: (dontShowAgain: Boolean) -> Unit) {
    var dontShow by rememberSaveable { mutableStateOf(false) }

    PanelDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBadge(Icons.Rounded.WarningAmber, StressColors.Warn)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.notice_title), style = StressType.Heading)
                    Text(stringResource(R.string.notice_subtitle), style = StressType.BodySmall, color = StressColors.TextDim)
                }
            }
            Hairline()
            // Only the text scrolls, so acceptance and the checkbox stay available on short screens.
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Line(Icons.Rounded.LocalFireDepartment, StressColors.Warn, stringResource(R.string.notice_load))
                Line(Icons.Rounded.AcUnit, StressColors.TextDim, stringResource(R.string.notice_care))
                Line(Icons.Rounded.Shield, StressColors.TextDim, stringResource(R.string.notice_protection))
                if (!safetyOn) Line(Icons.Rounded.GppBad, StressColors.Bad, stringResource(R.string.notice_safety_off), StressColors.Bad)
                Line(Icons.Rounded.Gavel, StressColors.TextDim, stringResource(R.string.notice_responsibility), StressColors.Text)
            }
            Hairline()
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .toggleable(dontShow, role = Role.Checkbox, onValueChange = { dontShow = it })
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = dontShow,
                    onCheckedChange = null,
                    modifier = Modifier.size(24.dp),
                    colors = CheckboxDefaults.colors(
                        checkedColor = StressColors.Accent,
                        uncheckedColor = StressColors.TextDim,
                        checkmarkColor = StressColors.OnAccent,
                    ),
                )
                Text(stringResource(R.string.notice_dont_show), style = StressType.Body, color = StressColors.TextDim)
            }
            PrimaryButton(text = stringResource(R.string.notice_accept), onClick = { onAccept(dontShow) }, icon = Icons.Rounded.Check)
        }
    }
}

@Composable
private fun Line(icon: ImageVector, tint: Color, text: String, textColor: Color = StressColors.TextDim) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        IconBadge(icon, tint, size = 32.dp)
        Text(text, style = StressType.Body, color = textColor, modifier = Modifier.weight(1f))
    }
}
