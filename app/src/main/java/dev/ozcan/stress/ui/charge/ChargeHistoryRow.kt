package dev.ozcan.stress.ui.charge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ozcan.stress.R
import dev.ozcan.stress.run.RunRecord
import dev.ozcan.stress.ui.Dates
import dev.ozcan.stress.ui.Format
import dev.ozcan.stress.ui.Labels
import dev.ozcan.stress.ui.components.IconBadge
import dev.ozcan.stress.ui.components.Panel
import dev.ozcan.stress.ui.components.StatusChip
import dev.ozcan.stress.ui.components.TopBarAction
import dev.ozcan.stress.ui.theme.NumberStyles
import dev.ozcan.stress.ui.theme.StressColors
import dev.ozcan.stress.ui.theme.StressType

@Composable
fun ChargeHistoryRow(record: RunRecord, onClick: () -> Unit, onDelete: () -> Unit) {
    val end = Labels.endReasonOf(record)
    Panel(Modifier.fillMaxWidth(), onClick = onClick, padding = 12.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {
                IconBadge(Icons.Rounded.BatteryChargingFull, StressColors.Accent)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.mode_charge), style = StressType.Heading)
                    Text(
                        "${Dates.short(record.startedAtMillis)} · ${Format.clock(record.loadSeconds)}",
                        style = StressType.BodySmall, color = StressColors.TextDim,
                    )
                }
                TopBarAction(Icons.Rounded.DeleteOutline, stringResource(R.string.delete), onDelete)
            }
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Text(Format.watts(record.summary.peakWatts), style = NumberStyles.Medium, color = StressColors.Accent)
                StatusChip(stringResource(Labels.endReasonShort(end)), Labels.endReasonColor(end))
            }
        }
    }
}
