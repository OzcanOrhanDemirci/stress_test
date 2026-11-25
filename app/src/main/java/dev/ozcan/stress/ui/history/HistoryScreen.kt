package dev.ozcan.stress.ui.history

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CompareArrows
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.ozcan.stress.AppGraph
import dev.ozcan.stress.R
import dev.ozcan.stress.engine.SceneQuality
import dev.ozcan.stress.graph
import dev.ozcan.stress.run.EndReason
import dev.ozcan.stress.run.RunRecord
import dev.ozcan.stress.run.RunSummary
import dev.ozcan.stress.run.StressMode
import dev.ozcan.stress.ui.Dates
import dev.ozcan.stress.ui.Format
import dev.ozcan.stress.ui.Labels
import dev.ozcan.stress.ui.charge.ChargeHistoryRow
import dev.ozcan.stress.ui.components.CenteredBox
import dev.ozcan.stress.ui.components.Hairline
import dev.ozcan.stress.ui.components.IconBadge
import dev.ozcan.stress.ui.components.MetricNumber
import dev.ozcan.stress.ui.components.Panel
import dev.ozcan.stress.ui.components.PrimaryButton
import dev.ozcan.stress.ui.components.SecondaryButton
import dev.ozcan.stress.ui.components.Sparkline
import dev.ozcan.stress.ui.components.StatusChip
import dev.ozcan.stress.ui.components.StressDialog
import dev.ozcan.stress.ui.components.TopBar
import dev.ozcan.stress.ui.components.TopBarAction
import dev.ozcan.stress.ui.context
import dev.ozcan.stress.ui.theme.NumberStyles
import dev.ozcan.stress.ui.theme.StressColors
import dev.ozcan.stress.ui.theme.StressType
import dev.ozcan.stress.ui.upper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HistoryViewModel(private val graph: AppGraph) : ViewModel() {
    private val _runs = MutableStateFlow<List<RunRecord>?>(null)
    val runs: StateFlow<List<RunRecord>?> = _runs.asStateFlow()

    fun refresh() {
        viewModelScope.launch { _runs.value = withContext(Dispatchers.IO) { graph.runs.list() } }
    }

    fun delete(record: RunRecord) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { graph.runs.delete(record.id) }
            _runs.value = _runs.value?.filterNot { it.id == record.id }
        }
    }
}

@Composable
fun HistoryScreen(onOpen: (String) -> Unit, onCompare: (String, String) -> Unit) {
    val graph = context().graph
    val model: HistoryViewModel = viewModel { HistoryViewModel(graph) }
    val runs by model.runs.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf<RunRecord?>(null) }
    var comparing by rememberSaveable { mutableStateOf(false) }
    var picked by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    // Every visit reads the list again: a run may have finished or been deleted elsewhere.
    LaunchedEffect(Unit) { model.refresh() }
    val list = runs
    val selected = picked.filter { id -> list?.any { it.id == id } == true }
    val cancelSelection = {
        comparing = false
        picked = arrayListOf()
    }
    BackHandler(enabled = comparing, onBack = cancelSelection)

    Column(Modifier.fillMaxSize()) {
        TopBar(
            title = stringResource(R.string.history_title),
            caption = list?.let { stringResource(R.string.history_count, it.size) },
            modifier = Modifier.statusBarsPadding(),
        ) {
            if (comparing) {
                TopBarAction(Icons.Rounded.Close, stringResource(R.string.history_compare_cancel), cancelSelection)
            } else if (!list.isNullOrEmpty()) {
                TopBarAction(Icons.AutoMirrored.Rounded.CompareArrows, stringResource(R.string.compare_button), { comparing = true })
            }
        }
        when {
            list == null -> CenteredBox { CircularProgressIndicator(color = StressColors.Accent) }
            list.isEmpty() -> CenteredBox(Modifier.padding(16.dp)) {
                Panel(Modifier.fillMaxWidth(), title = stringResource(R.string.history_empty_title)) {
                    Text(stringResource(R.string.history_empty_text), style = StressType.Body, color = StressColors.TextDim)
                }
            }
            else -> LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(list, key = { it.id }) { record ->
                    val isSelected = record.id in selected
                    val canSelect = isSelected || selected.size < 2
                    val toggle = {
                        picked = ArrayList(if (isSelected) selected - record.id else selected + record.id)
                    }
                    RunCard(
                        record = record,
                        selected = isSelected.takeIf { comparing },
                        canSelect = canSelect,
                        onClick = if (comparing) toggle else { { onOpen(record.id) } },
                        onDelete = { deleting = record },
                    )
                }
            }
        }
        if (comparing && !list.isNullOrEmpty()) {
            Hairline()
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val instruction = if (list.size >= 2) R.string.history_compare_pick else R.string.history_compare_more
                Text(stringResource(instruction), style = StressType.BodySmall, color = StressColors.TextDim)
                PrimaryButton(
                    text = stringResource(R.string.compare_title),
                    subtitle = stringResource(R.string.history_compare_selected, selected.size),
                    icon = Icons.AutoMirrored.Rounded.CompareArrows,
                    enabled = selected.size == 2,
                    onClick = {
                        cancelSelection()
                        onCompare(selected[0], selected[1])
                    },
                )
            }
        }
    }

    deleting?.let { record ->
        val close = { deleting = null }
        StressDialog(
            onDismissRequest = close,
            icon = { Icon(Icons.Rounded.DeleteOutline, null, tint = StressColors.Bad) },
            title = { Text(stringResource(R.string.history_delete_title)) },
            text = { Text(stringResource(R.string.history_delete_text), color = StressColors.TextDim) },
            confirmButton = {
                SecondaryButton(stringResource(R.string.delete), color = StressColors.Bad, onClick = {
                    close()
                    model.delete(record)
                })
            },
            dismissButton = { SecondaryButton(stringResource(R.string.cancel), onClick = close) },
        )
    }
}

internal data class HistoryReading(val label: Int, val number: Double?, val unit: String)

internal fun historyPower(summary: RunSummary): HistoryReading =
    if (summary.powerValid && summary.peakWatts == null && summary.meanWatts != null) {
        HistoryReading(R.string.result_mean, summary.meanWatts, "W")
    } else {
        HistoryReading(R.string.result_peak, summary.peakWatts.takeIf { summary.powerValid }, "W")
    }

private data class ModeReading(val icon: ImageVector, val value: HistoryReading)

/** Modes choose their row icon and headline reading together. */
private fun modeReading(mode: StressMode?, record: RunRecord): ModeReading = when (mode) {
    null -> ModeReading(Icons.Rounded.History, historyPower(record.summary))
    else -> ModeReading(Labels.modeIcon(mode), historyPower(record.summary))
}

@Composable
private fun RunCard(
    record: RunRecord,
    selected: Boolean?,
    canSelect: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val summary = record.summary
    val mode = StressMode.of(record.mode)
    val (icon, power) = modeReading(mode, record)
    val reason = Labels.endReasonOf(record)
    val quality = SceneQuality.entries.firstOrNull { it.name == record.quality }.takeIf { mode?.scene != null }
    val selection = if (selected == null) Modifier else Modifier.toggleable(
        value = selected,
        enabled = canSelect,
        role = Role.Checkbox,
        onValueChange = { onClick() },
    )

    Panel(
        Modifier.fillMaxWidth().then(selection),
        padding = 12.dp,
        selected = selected,
        onClick = onClick.takeIf { selected == null },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconBadge(icon, StressColors.TextDim, size = 32.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(Labels.recordModeName(context(), record), style = StressType.Heading)
                    quality?.let { Text(stringResource(Labels.quality(it)), style = StressType.BodySmall, color = StressColors.TextDim) }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(stringResource(power.label).upper(), style = StressType.Label, color = StressColors.TextDim)
                    MetricNumber(Format.wattNumber(power.number), power.unit, NumberStyles.Small, StressColors.Accent)
                }
                if (selected == null) {
                    TopBarAction(Icons.Rounded.DeleteOutline, stringResource(R.string.delete), onDelete)
                } else {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = null,
                        enabled = canSelect,
                        modifier = Modifier.size(40.dp),
                        colors = CheckboxDefaults.colors(checkedColor = StressColors.Cool, uncheckedColor = StressColors.TextDim),
                    )
                }
            }
            Hairline()
            Text(
                "${Dates.long(record.startedAtMillis)} · ${Format.clock(record.loadSeconds)}",
                style = StressType.Code,
                color = StressColors.TextDim,
            )
            val watts = remember(record.series) { record.series.watts.filterNotNull() }
            if (summary.powerValid && watts.size > 2) {
                Sparkline(watts, StressColors.Accent, Modifier.fillMaxWidth().height(24.dp))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val hottest = summary.maxTemperatures.filterKeys { it != "battery" && it != "Pil" }.values.maxOrNull()
                if (hottest != null) {
                    Text(
                        stringResource(R.string.title_with_part, stringResource(R.string.result_hottest), Format.celsius(hottest, 0)),
                        style = StressType.BodySmall,
                        color = StressColors.TextDim,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                }
                if (summary.computationErrors > 0) {
                    StatusChip(stringResource(R.string.run_errors, summary.computationErrors), StressColors.Bad, icon = Icons.Rounded.Warning)
                } else if (reason == EndReason.Completed) {
                    StatusChip(stringResource(R.string.run_errors, 0L), StressColors.Good)
                }
                if (reason != EndReason.Completed) {
                    StatusChip(
                        stringResource(Labels.endReasonShort(reason)),
                        Labels.endReasonColor(reason),
                        icon = Icons.Rounded.Shield.takeIf { reason == EndReason.Safety },
                    )
                }
                if (!summary.powerValid) {
                    StatusChip(stringResource(R.string.history_power_invalid), StressColors.Warn)
                } else if (power.number == null) {
                    StatusChip(stringResource(R.string.history_power_missing), StressColors.TextDim)
                }
            }
        }
    }
}
