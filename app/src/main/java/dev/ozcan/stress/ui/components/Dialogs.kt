package dev.ozcan.stress.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import dev.ozcan.stress.ui.theme.StressType

/** An outlined dialog panel on a 60% scrim. Custom content can bound its own scrolling region. */
@Composable
fun PanelDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(usePlatformDefaultWidth = false),
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismissRequest, properties = properties) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0.60f) }
        Panel(Modifier.padding(16.dp).widthIn(max = 440.dp).fillMaxWidth(), content = content)
    }
}

/** A heading, scrollable body and fixed actions. [dismissButton] and [icon] are optional. */
@Composable
fun StressDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
) {
    PanelDialog(onDismissRequest) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (icon != null) icon()
            ProvideTextStyle(StressType.Heading, title)
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                ProvideTextStyle(StressType.Body, text)
            }
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                dismissButton?.invoke()
                confirmButton()
            }
        }
    }
}
