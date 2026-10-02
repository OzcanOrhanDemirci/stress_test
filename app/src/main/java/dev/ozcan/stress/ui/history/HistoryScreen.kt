package dev.ozcan.stress.ui.history

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.ozcan.stress.AppGraph
import dev.ozcan.stress.R
import dev.ozcan.stress.graph
import dev.ozcan.stress.run.RunRecord
import dev.ozcan.stress.run.StressMode
import dev.ozcan.stress.ui.BottomBarSpace
import dev.ozcan.stress.ui.Dates
import dev.ozcan.stress.ui.Format
import dev.ozcan.stress.ui.Labels
import dev.ozcan.stress.ui.components.CenteredBox
import dev.ozcan.stress.ui.components.GaugeMark
import dev.ozcan.stress.ui.components.GlassCard
import dev.ozcan.stress.ui.components.IconBadge
import dev.ozcan.stress.ui.components.Pill
import dev.ozcan.stress.ui.components.Sparkline
import dev.ozcan.stress.ui.context
import dev.ozcan.stress.ui.theme.NumberStyles
import dev.ozcan.stress.ui.theme.StressColors
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
fun HistoryScreen(onOpen: (String) -> Unit) {
    val graph = context().graph
    val model: HistoryViewModel = viewModel { HistoryViewModel(graph) }
    val runs by model.runs.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf<RunRecord?>(null) }
    // Every visit reads the list again: a run may have finished or been deleted elsewhere.
    LaunchedEffect(Unit) { model.refresh() }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.history_title), style = MaterialTheme.typography.headlineSmall)
                runs?.let {
                    Text(
                        stringResource(R.string.history_count, it.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = StressColors.TextDim,
                    )
                }
            }
        }
        val list = runs
        when {
            list == null -> CenteredBox { CircularProgressIndicator(color = StressColors.Accent) }
            list.isEmpty() -> Empty()
            else -> LazyColumn(
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = BottomBarSpace + 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(list, key = { _, r -> r.id }) { index, record ->
                    var shown by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) { shown = true }
                    AnimatedVisibility(
                        visible = shown,
                        enter = fadeIn(tween(350, delayMillis = 40 * index.coerceAtMost(8))) +
                            slideInVertically(tween(400, delayMillis = 40 * index.coerceAtMost(8))) { it / 4 },
                    ) {
                        RunCard(record, onClick = { onOpen(record.id) }, onDelete = { deleting = record })
                    }
                }
            }
        }
    }

    deleting?.let { record ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            icon = { Icon(Icons.Rounded.DeleteOutline, null, tint = StressColors.Bad) },
            title = { Text(stringResource(R.string.history_delete_title)) },
            text = { Text(stringResource(R.string.history_delete_text), color = StressColors.TextDim) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    model.delete(record)
                }) { Text(stringResource(R.string.delete), color = StressColors.Bad) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
            containerColor = StressColors.SurfaceHigh,
        )
    }
}

@Composable
private fun Empty() {
    CenteredBox(Modifier.padding(bottom = BottomBarSpace)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            GaugeMark(Modifier.size(84.dp), reading = 0.15f, color = StressColors.TextFaint)
            Text(stringResource(R.string.history_empty_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.history_empty_text),
                style = MaterialTheme.typography.bodySmall,
                color = StressColors.TextDim,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 40.dp),
            )
        }
    }
}

@Composable
private fun RunCard(record: RunRecord, onClick: () -> Unit, onDelete: () -> Unit) {
    val context = context()
    val s = record.summary
    val reason = Labels.endReasonOf(record)
    GlassCard(Modifier.fillMaxWidth(), onClick = onClick, padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBadge(StressMode.of(record.mode)?.let(Labels::modeIcon) ?: Icons.Rounded.History, StressColors.Accent, size = 40.dp)
            Column(Modifier.weight(1f)) {
                Text(Labels.recordModeName(context, record), style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(
                    "${Dates.short(record.startedAtMillis)} · ${Format.clock(record.loadSeconds)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = StressColors.TextDim,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Rounded.DeleteOutline, stringResource(R.string.delete), tint = StressColors.TextFaint)
            }
        }
        val watts = record.series.watts.filterNotNull()
        if (watts.size > 2) {
            Spacer(Modifier.height(8.dp))
            Sparkline(watts, StressColors.Accent, Modifier.fillMaxWidth().height(36.dp))
        }
        Spacer(Modifier.height(10.dp))
        // Spaced by hand: a spacedBy arrangement would also put its gap on both sides of the flexible spacer,
        // and the end-reason pill needs that room.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Metric(Icons.Rounded.Bolt, if (s.powerValid) Format.watts(s.peakWatts ?: s.meanWatts) else Format.MISSING, stringResource(R.string.result_peak))
            Spacer(Modifier.width(14.dp))
            Metric(
                Icons.Rounded.Thermostat,
                Format.celsius(s.maxTemperatures.filterKeys { it != "battery" && it != "Pil" }.values.maxOrNull(), 0),
                stringResource(R.string.result_hottest),
            )
            Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
            if (s.computationErrors > 0) {
                Pill(stringResource(R.string.run_errors, s.computationErrors), StressColors.Bad, icon = Icons.Rounded.Warning)
            } else if (reason != dev.ozcan.stress.run.EndReason.Completed) {
                Pill(
                    stringResource(Labels.endReasonShort(reason)),
                    Labels.endReasonColor(reason),
                    icon = Icons.Rounded.Shield.takeIf { reason == dev.ozcan.stress.run.EndReason.Safety },
                )
            }
        }
    }
}

@Composable
private fun Metric(icon: androidx.compose.ui.graphics.vector.ImageVector, value: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, null, tint = StressColors.TextFaint, modifier = Modifier.size(16.dp))
        Column {
            Text(value, style = NumberStyles.Small, color = StressColors.Text)
            Text(label, style = MaterialTheme.typography.labelSmall, color = StressColors.TextFaint)
        }
        Spacer(Modifier.width(2.dp))
    }
}
