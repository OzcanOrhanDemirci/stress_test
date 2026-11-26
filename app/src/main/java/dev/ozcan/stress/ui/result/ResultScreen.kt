package dev.ozcan.stress.ui.result

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CompareArrows
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.ozcan.stress.AppGraph
import dev.ozcan.stress.R
import dev.ozcan.stress.analysis.Comparison
import dev.ozcan.stress.analysis.Insight
import dev.ozcan.stress.analysis.Insights
import dev.ozcan.stress.charge.isCharge
import dev.ozcan.stress.engine.SceneQuality
import dev.ozcan.stress.graph
import dev.ozcan.stress.report.ReportCsv
import dev.ozcan.stress.report.ReportPdf
import dev.ozcan.stress.run.RunRecord
import dev.ozcan.stress.run.StressMode
import dev.ozcan.stress.ui.Dates
import dev.ozcan.stress.ui.Format
import dev.ozcan.stress.ui.InsightText
import dev.ozcan.stress.ui.Labels
import dev.ozcan.stress.ui.charge.ChargeResult
import dev.ozcan.stress.ui.charge.chargeLog
import dev.ozcan.stress.ui.components.CenteredBox
import dev.ozcan.stress.ui.components.ChartSeries
import dev.ozcan.stress.ui.components.EngineeringPage
import dev.ozcan.stress.ui.components.EngineeringRow
import dev.ozcan.stress.ui.components.Hairline
import dev.ozcan.stress.ui.components.MetricNumber
import dev.ozcan.stress.ui.components.MetricTile
import dev.ozcan.stress.ui.components.Panel
import dev.ozcan.stress.ui.components.PrimaryButton
import dev.ozcan.stress.ui.components.SecondaryButton
import dev.ozcan.stress.ui.components.SectionLabel
import dev.ozcan.stress.ui.components.Stack
import dev.ozcan.stress.ui.components.StatusChip
import dev.ozcan.stress.ui.components.StressDialog
import dev.ozcan.stress.ui.components.Temperatures
import dev.ozcan.stress.ui.components.TileRow
import dev.ozcan.stress.ui.components.TimeChart
import dev.ozcan.stress.ui.components.windowBand
import dev.ozcan.stress.ui.components.simpleChartHeight
import dev.ozcan.stress.ui.components.TopBar
import dev.ozcan.stress.ui.components.TopBarAction
import dev.ozcan.stress.ui.context
import dev.ozcan.stress.ui.theme.NumberStyles
import dev.ozcan.stress.ui.theme.StressColors
import dev.ozcan.stress.ui.theme.StressType
import dev.ozcan.stress.ui.upper
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ResultViewModel(private val graph: AppGraph, private val runId: String) : ViewModel() {
    /** Null while loading; [Loaded.record] null when the run is gone. */
    data class Loaded(val record: RunRecord?, val insights: List<Insight>)

    private val _loaded = MutableStateFlow<Loaded?>(null)
    val loaded: StateFlow<Loaded?> = _loaded.asStateFlow()

    init {
        viewModelScope.launch {
            val record = withContext(Dispatchers.IO) { graph.runs.load(runId) }
            _loaded.value = Loaded(record, record?.takeUnless { it.isCharge }?.let(Insights::of).orEmpty())
        }
    }

    suspend fun delete() = withContext(Dispatchers.IO) { graph.runs.delete(runId) }

    /** Eligible stored runs, newest first. */
    suspend fun others(): List<RunRecord> = withContext(Dispatchers.IO) {
        graph.runs.list().filter { it.id != runId && Comparison.isEligible(it) }
    }
}

@Composable
fun ResultScreen(runId: String, onBack: () -> Unit, onCompare: (String) -> Unit) {
    val context = context()
    val graph = context.graph
    val model: ResultViewModel = viewModel { ResultViewModel(graph, runId) }
    val loaded by model.loaded.collectAsStateWithLifecycle()
    val record = loaded?.record
    val insights = loaded?.insights.orEmpty()
    // The engineering page is in-screen state, not a route: back returns to the simple page of the same run.
    var engineering by rememberSaveable { mutableStateOf(false) }
    val log = remember(loaded, context) {
        record?.let { if (it.isCharge) chargeLog(context, it) else resultLog(context, it, insights) }.orEmpty()
    }
    if (engineering && record != null) {
        EngineeringPage(stringResource(R.string.result_title), onBack = { engineering = false }) { logBlocks(log) }
        return
    }
    val count = log.valueCount()
    val caption = pluralStringResource(R.plurals.parts_values, count, count).upper()

    Column(Modifier.fillMaxSize()) {
        ResultTopBar(record, onBack, model, onCompare)
        when {
            loaded == null -> CenteredBox { CircularProgressIndicator(color = StressColors.Accent) }
            record == null -> CenteredBox { Text(stringResource(R.string.result_missing), color = StressColors.TextDim) }
            record.isCharge -> {
                ChargeResult(record, caption, { engineering = true }, Modifier.weight(1f))
                ReportActions(record, emptyList())
            }
            else -> {
                ResultBody(record, insights, caption, { engineering = true }, Modifier.weight(1f))
                ReportActions(record, insights)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResultTopBar(record: RunRecord?, onBack: () -> Unit, model: ResultViewModel, onCompare: (String) -> Unit) {
    val context = context()
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    var others by remember { mutableStateOf<List<RunRecord>?>(null) }
    // Mode and length always; a scene's quality only when it fits. The phone model is on the engineering page.
    val scene = record?.let { StressMode.of(it.mode)?.scene }
    val caption = record?.let {
        val mode = if (scene != null) stringResource(Labels.scene(scene)) else Labels.recordModeName(context, it)
        "$mode // ${Format.clock(it.loadSeconds).padStart(5, '0')}".upper()
    }
    val quality = record?.takeIf { scene != null }
        ?.let { r -> SceneQuality.entries.firstOrNull { it.name == r.quality } }
        ?.let { stringResource(Labels.quality(it)).upper() }
    TopBar(
        title = stringResource(R.string.result_title),
        caption = caption,
        captionExtra = quality,
        onBack = onBack,
        modifier = Modifier.windowBand(StressColors.SurfaceLow, ruleAtTop = false).statusBarsPadding(),
        actions = {
            if (record != null) {
                TopBarAction(Icons.AutoMirrored.Rounded.CompareArrows, stringResource(R.string.compare_button), {
                    scope.launch { others = model.others() }
                })
                TopBarAction(Icons.Rounded.Delete, stringResource(R.string.delete), { confirmDelete = true })
            }
        },
    )
    others?.let { list ->
        ModalBottomSheet(onDismissRequest = { others = null }, containerColor = StressColors.Surface, shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.compare_pick_title), style = MaterialTheme.typography.titleMedium)
                if (list.isEmpty()) {
                    Text(stringResource(R.string.compare_none), style = MaterialTheme.typography.bodySmall, color = StressColors.TextDim)
                }
                list.take(30).forEach { other ->
                    Panel(
                        Modifier.fillMaxWidth(),
                        padding = 12.dp,
                        onClick = {
                            others = null
                            onCompare(other.id)
                        },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(Labels.recordModeName(context, other), style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${Dates.short(other.startedAtMillis)} · ${Format.clock(other.loadSeconds)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StressColors.TextDim,
                                )
                            }
                            val power = if (other.summary.powerValid) Format.watts(other.summary.peakWatts ?: other.summary.meanWatts) else Format.MISSING
                            Text(
                                power,
                                style = NumberStyles.Small,
                                color = if (power == Format.MISSING) StressColors.TextDim else StressColors.Accent,
                            )
                        }
                    }
                }
            }
        }
    }
    if (confirmDelete) {
        StressDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.history_delete_title)) },
            text = { Text(stringResource(R.string.history_delete_text), color = StressColors.TextDim) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        model.delete()
                        onBack()
                    }
                }) { Text(stringResource(R.string.delete), color = StressColors.Bad) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/**
 * The simple page (DESIGN, Result row): the two power figures, battery and hottest chip, the power curve and one
 * verdict. Everything else is on the engineering page.
 */
@Composable
private fun ResultBody(
    record: RunRecord,
    insights: List<Insight>,
    engineeringCaption: String,
    onEngineering: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Stack(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp),
        spacing = 12,
    ) {
        PowerPanel(record)
        BatteryAndChip(record)
        PowerCurve(record)
        insights.firstOrNull()?.let { VerdictPanel(InsightText.title(context(), it), insightColor(it)) }
        EngineeringRow(engineeringCaption, onEngineering)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PowerPanel(record: RunRecord) {
    val s = record.summary
    val reason = Labels.endReasonOf(record)
    val peak = s.peakWatts.takeIf { s.powerValid }
    val sustained = s.sustainedWatts.takeIf { s.powerValid }
    Panel(Modifier.fillMaxWidth()) {
        Stack(spacing = 12) {
            // The end-state chip moves under the date when both do not fit on a narrow phone, instead of wrapping the date.
            FlowRow(
                Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text(Dates.long(record.startedAtMillis), style = StressType.Code, color = StressColors.TextDim, maxLines = 1)
                StatusChip(stringResource(Labels.endReasonShort(reason)), Labels.endReasonColor(reason))
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // Labels on one line and numbers on one baseline, so the two figures read as a pair.
                Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(16.dp)) {
                    SectionLabel(stringResource(R.string.result_peak), Modifier.weight(1.15f))
                    SectionLabel(stringResource(R.string.result_sustained), Modifier.weight(1f))
                }
                // Without power (cable plugged in) both places say it is missing: one size, so they sit level and the card
                // keeps no hero height for nothing.
                val measured = peak != null || sustained != null
                Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.weight(1.15f).alignByBaseline()) {
                        MetricNumber(Format.wattNumber(peak), "W", if (measured) NumberStyles.Hero else NumberStyles.Medium, StressColors.Accent)
                    }
                    Box(Modifier.weight(1f).alignByBaseline()) {
                        MetricNumber(Format.wattNumber(sustained), "W", if (measured) NumberStyles.Large else NumberStyles.Medium, StressColors.Accent)
                    }
                }
            }
            val missing = when {
                !s.powerValid -> R.string.insight_charging_detail
                s.powerFromCounter -> R.string.insight_counter_detail
                peak == null || sustained == null || s.batteryStartPercent == null || s.batteryEndPercent == null ||
                    s.hottestChip() == null -> R.string.result_unavailable
                else -> null
            }
            if (missing != null) Text(stringResource(missing), style = StressType.BodySmall, color = StressColors.TextDim)
        }
    }
}

@Composable
private fun BatteryAndChip(record: RunRecord) {
    val s = record.summary
    val chip = s.hottestChip()
    val start = s.batteryStartPercent
    val end = s.batteryEndPercent
    TileRow(Modifier.height(IntrinsicSize.Min)) {
        val cell = Modifier.weight(1f).fillMaxHeight()
        MetricTile(
            stringResource(R.string.result_battery),
            if (start != null && end != null) "${Format.percentValue(start)} → ${Format.percentValue(end)}" else Format.MISSING,
            cell, padding = 12.dp,
        )
        MetricTile(
            stringResource(R.string.result_hottest), Format.number(chip, 1), cell, unit = "°C",
            color = Temperatures.chip(chip), padding = 12.dp,
        )
    }
}

/** The one chart of the simple page: power, or the chip's temperature when power could not be measured. */
@Composable
private fun PowerCurve(record: RunRecord) {
    val series = record.series
    val power = record.summary.powerValid && series.watts.any { it != null }
    val chip = remember(series) { series.chipTemperatures() }
    if (!power && chip.none { it != null }) return
    val title = stringResource(if (power) R.string.result_power_curve else R.string.run_chart_chip)
    Panel(Modifier.fillMaxWidth(), padding = 12.dp) {
        Stack(spacing = 8) {
            SectionLabel(title, caption = Format.clock(record.loadSeconds))
            BoxWithConstraints {
                val height = simpleChartHeight(maxWidth)
                if (power) {
                    TimeChart(
                        series.seconds, listOf(ChartSeries(title, StressColors.Accent, series.watts, "W")), "W",
                        height = height, yMin = 0.0, decimals = Format.WATT_DECIMALS,
                    )
                } else {
                    TimeChart(series.seconds, listOf(ChartSeries(title, StressColors.Cool, chip, "°C")), "°C", height = height)
                }
            }
        }
    }
}

@Composable
private fun ReportActions(record: RunRecord, insights: List<Insight>) {
    val context = context()
    val graph = context.graph
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    // Both formats go to the share sheet, which also offers saving to the phone's files.
    fun export(type: String, write: () -> File) {
        scope.launch {
            busy = true
            val file = withContext(Dispatchers.IO) { runCatching(write).getOrNull() }
            busy = false
            if (file == null) {
                Toast.makeText(context, R.string.export_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.reports", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                this.type = type
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.export_share_title)))
        }
    }

    Column(Modifier.fillMaxWidth().windowBand(StressColors.SurfaceLow, ruleAtTop = true)) {
        Hairline()
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            SecondaryButton(
                text = stringResource(R.string.result_csv),
                icon = Icons.Rounded.TableChart,
                enabled = !busy,
                modifier = Modifier.weight(1f),
                onClick = { export("text/csv") { ReportCsv.write(context, record, graph.reportDir) } },
            )
            // The charge test has no PDF report: its result is the CSV alone.
            if (record.isCharge) return@Row
            PrimaryButton(
                text = stringResource(R.string.result_pdf),
                icon = Icons.Rounded.PictureAsPdf,
                enabled = !busy,
                modifier = Modifier.weight(1f),
                onClick = { export("application/pdf") { ReportPdf.write(context, record, insights, graph.reportDir) } },
            )
        }
    }
}
