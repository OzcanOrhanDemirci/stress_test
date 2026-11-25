package dev.ozcan.stress.ui.history

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CompareArrows
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.History
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.ozcan.stress.AppGraph
import dev.ozcan.stress.R
import dev.ozcan.stress.charge.isCharge
import dev.ozcan.stress.engine.SceneQuality
import dev.ozcan.stress.graph
import dev.ozcan.stress.run.EndReason
import dev.ozcan.stress.run.RunRecord
import dev.ozcan.stress.run.RunSummary
import dev.ozcan.stress.run.StressMode
import dev.ozcan.stress.ui.Dates
import dev.ozcan.stress.ui.Format
import dev.ozcan.stress.ui.Labels
import dev.ozcan.stress.ui.components.CenteredBox
import dev.ozcan.stress.ui.components.Hairline
import dev.ozcan.stress.ui.components.IconBadge
import dev.ozcan.stress.ui.components.MetricNumber
import dev.ozcan.stress.ui.components.Panel
import dev.ozcan.stress.ui.components.PrimaryButton
import dev.ozcan.stress.ui.components.SecondaryButton
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

/** What a tap on a run row does: open it, pick it for a comparison, or ask to delete it. */
private enum class HistoryMode { Browse, Compare, Delete }

/**
 * The history (DESIGN "History"): a calm column of run rows, each a link to its result, whose engineering page is
 * the run's own. Comparing and deleting are modes of the list, entered from the top bar, so a row carries one
 * affordance at a time.
 */
@Composable
fun HistoryScreen(onOpen: (String) -> Unit, onCompare: (String, String) -> Unit) {
    val graph = context().graph
    val model: HistoryViewModel = viewModel { HistoryViewModel(graph) }
    val runs by model.runs.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf<RunRecord?>(null) }
    var mode by rememberSaveable { mutableStateOf(HistoryMode.Browse) }
    var picked by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    // Every visit reads the list again: a run may have finished or been deleted elsewhere.
    LaunchedEffect(Unit) { model.refresh() }
    val list = runs
    val selected = picked.filter { id -> list?.any { it.id == id } == true }
    val backToBrowse = {
        mode = HistoryMode.Browse
        picked = arrayListOf()
    }
    BackHandler(enabled = mode != HistoryMode.Browse, onBack = backToBrowse)

    Column(Modifier.fillMaxSize()) {
        TopBar(
            title = stringResource(R.string.history_title),
            caption = list?.let { stringResource(R.string.history_count, it.size) },
            modifier = Modifier.statusBarsPadding(),
        ) {
            when {
                mode != HistoryMode.Browse ->
                    TopBarAction(Icons.Rounded.Close, stringResource(R.string.history_compare_cancel), backToBrowse)
                !list.isNullOrEmpty() -> {
                    TopBarAction(Icons.Rounded.DeleteOutline, stringResource(R.string.history_delete_mode), { mode = HistoryMode.Delete })
                    TopBarAction(Icons.AutoMirrored.Rounded.CompareArrows, stringResource(R.string.compare_button), { mode = HistoryMode.Compare })
                }
            }
        }
        when {
            list == null -> CenteredBox(Modifier.weight(1f)) { CircularProgressIndicator(color = StressColors.Accent) }
            list.isEmpty() -> CenteredBox(Modifier.weight(1f).padding(16.dp)) {
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
                    val onClick: () -> Unit = when (mode) {
                        HistoryMode.Browse -> { { onOpen(record.id) } }
                        HistoryMode.Delete -> { { deleting = record } }
                        HistoryMode.Compare -> { { picked = ArrayList(if (isSelected) selected - record.id else selected + record.id) } }
                    }
                    RunRow(record, mode, isSelected, canSelect = isSelected || selected.size < 2, onClick = onClick)
                }
            }
        }
        if (mode != HistoryMode.Browse && !list.isNullOrEmpty()) {
            Hairline()
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val instruction = when {
                    mode == HistoryMode.Delete -> R.string.history_delete_pick
                    list.size >= 2 -> R.string.history_compare_pick
                    else -> R.string.history_compare_more
                }
                Text(stringResource(instruction), style = StressType.BodySmall, color = StressColors.TextDim)
                if (mode == HistoryMode.Compare) {
                    PrimaryButton(
                        text = stringResource(R.string.compare_title),
                        subtitle = stringResource(R.string.history_compare_selected, selected.size),
                        icon = Icons.AutoMirrored.Rounded.CompareArrows,
                        enabled = selected.size == 2,
                        onClick = {
                            backToBrowse()
                            onCompare(selected[0], selected[1])
                        },
                    )
                }
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
                    // The last run gone leaves nothing to delete: back to the plain list.
                    if (list?.size == 1) backToBrowse()
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

/**
 * One run as a control row: mode icon, mode and scene, date, length and quality, the headline power, and the row's
 * affordance: a chevron to open it, a checkbox while comparing, a red bin while deleting. A run that did not end
 * cleanly says so in one short line; everything else about it is on its result page.
 */
@Composable
private fun RunRow(
    record: RunRecord,
    mode: HistoryMode,
    selected: Boolean,
    canSelect: Boolean,
    onClick: () -> Unit,
) {
    val runMode = StressMode.of(record.mode)
    val (icon, power) = modeReading(runMode, record)
    val quality = SceneQuality.entries.firstOrNull { it.name == record.quality }.takeIf { runMode?.scene != null }
    val qualityName = quality?.let { stringResource(Labels.quality(it)) }
    // The scene's name alone: the clapper icon already says it is a cinematic run, and the row is narrow.
    val title = runMode?.scene?.let { stringResource(Labels.scene(it)) } ?: Labels.recordModeName(context(), record)
    val length = listOfNotNull(Format.clock(record.loadSeconds), qualityName).joinToString(" · ")
    val comparing = mode == HistoryMode.Compare
    val selection = if (!comparing) Modifier else Modifier.toggleable(
        value = selected,
        enabled = canSelect,
        role = Role.Checkbox,
        onValueChange = { onClick() },
    )

    Panel(
        Modifier.fillMaxWidth().then(selection),
        padding = 12.dp,
        selected = selected.takeIf { comparing },
        onClick = onClick.takeIf { !comparing },
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            IconBadge(icon, StressColors.TextDim, size = 36.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = StressType.Heading, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(Dates.short(record.startedAtMillis), style = StressType.Code, color = StressColors.TextDim, maxLines = 1)
                Text(length, style = StressType.Code, color = StressColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                runState(record, power)?.let { Text(it, style = StressType.Code.copy(fontSize = 11.sp), maxLines = 2) }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(power.label).upper(), style = StressType.Label.copy(fontSize = 10.sp, lineHeight = 14.sp),
                    color = StressColors.TextFaint, maxLines = 1,
                )
                MetricNumber(Format.wattNumber(power.number), power.unit, NumberStyles.Small, StressColors.Accent)
            }
            when (mode) {
                HistoryMode.Browse -> Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = StressColors.TextDim)
                HistoryMode.Delete -> Icon(Icons.Rounded.DeleteOutline, stringResource(R.string.delete), tint = StressColors.Bad)
                HistoryMode.Compare -> Checkbox(
                    checked = selected,
                    onCheckedChange = null,
                    enabled = canSelect,
                    modifier = Modifier.size(32.dp),
                    colors = CheckboxDefaults.colors(checkedColor = StressColors.Cool, uncheckedColor = StressColors.TextDim),
                )
            }
        }
    }
}

/**
 * A row's state line, only when the run did not end cleanly: computation errors, an early end, power that was
 * invalid or not measured, each in its colour. Null for a clean run, so a calm list stays calm.
 */
@Composable
private fun runState(record: RunRecord, power: HistoryReading): AnnotatedString? {
    val summary = record.summary
    val reason = Labels.endReasonOf(record)
    val parts = buildList {
        // No computation runs in a charge test, so there is no error count to show.
        if (!record.isCharge && summary.computationErrors > 0) {
            add(stringResource(R.string.run_errors, summary.computationErrors) to StressColors.Bad)
        }
        if (reason != EndReason.Completed) add(stringResource(Labels.endReasonShort(reason)) to Labels.endReasonColor(reason))
        if (!summary.powerValid) {
            add(stringResource(R.string.history_power_invalid) to StressColors.Warn)
        } else if (power.number == null) {
            add(stringResource(R.string.history_power_missing) to StressColors.TextDim)
        }
    }
    if (parts.isEmpty()) return null
    return buildAnnotatedString {
        parts.forEachIndexed { i, (text, color) ->
            if (i > 0) withStyle(SpanStyle(color = StressColors.TextFaint)) { append(" · ") }
            withStyle(SpanStyle(color = color)) { append(text.upper()) }
        }
    }
}
