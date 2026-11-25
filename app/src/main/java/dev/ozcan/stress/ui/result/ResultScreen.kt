package dev.ozcan.stress.ui.result

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CompareArrows
import androidx.compose.material.icons.rounded.Delete
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
import dev.ozcan.stress.analysis.ReferenceDevices
import dev.ozcan.stress.charge.isCharge
import dev.ozcan.stress.engine.SceneQuality
import dev.ozcan.stress.graph
import dev.ozcan.stress.report.ReportCsv
import dev.ozcan.stress.report.ReportPdf
import dev.ozcan.stress.run.RunAnalysis
import dev.ozcan.stress.run.RunRecord
import dev.ozcan.stress.run.StressMode
import dev.ozcan.stress.safety.SafetyLimits
import dev.ozcan.stress.ui.Dates
import dev.ozcan.stress.ui.Format
import dev.ozcan.stress.ui.InsightText
import dev.ozcan.stress.ui.Labels
import dev.ozcan.stress.ui.charge.ChargeResult
import dev.ozcan.stress.ui.components.CenteredBox
import dev.ozcan.stress.ui.components.ChartSeries
import dev.ozcan.stress.ui.components.Hairline
import dev.ozcan.stress.ui.components.InfoRow
import dev.ozcan.stress.ui.components.InsightRow
import dev.ozcan.stress.ui.components.MetricNumber
import dev.ozcan.stress.ui.components.MetricTile
import dev.ozcan.stress.ui.components.Panel
import dev.ozcan.stress.ui.components.PrimaryButton
import dev.ozcan.stress.ui.components.SecondaryButton
import dev.ozcan.stress.ui.components.SectionLabel
import dev.ozcan.stress.ui.components.SegmentedControl
import dev.ozcan.stress.ui.components.StabilityArc
import dev.ozcan.stress.ui.components.Stack
import dev.ozcan.stress.ui.components.StatusChip
import dev.ozcan.stress.ui.components.StressDialog
import dev.ozcan.stress.ui.components.TileRow
import dev.ozcan.stress.ui.components.TimeChart
import dev.ozcan.stress.ui.components.TopBar as DesignTopBar
import dev.ozcan.stress.ui.components.TopBarAction
import dev.ozcan.stress.ui.context
import dev.ozcan.stress.ui.hoursText
import dev.ozcan.stress.ui.run.DrawnQualityNotice
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

private val Limits = SafetyLimits()

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
    val graph = context().graph
    val model: ResultViewModel = viewModel { ResultViewModel(graph, runId) }
    val loaded by model.loaded.collectAsStateWithLifecycle()
    val record = loaded?.record

    Column(Modifier.fillMaxSize()) {
        ResultTopBar(record, onBack, model, onCompare)
        when {
            loaded == null -> CenteredBox { CircularProgressIndicator(color = StressColors.Accent) }
            record == null -> CenteredBox { Text(stringResource(R.string.result_missing), color = StressColors.TextDim) }
            record.isCharge -> ChargeResult(record) { ExportCard(record, emptyList()) }
            else -> ResultBody(record, loaded!!.insights)
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
    if (record?.isCharge == true) {
        DesignTopBar(stringResource(R.string.charge_result), Modifier.statusBarsPadding(), onBack = onBack) {
            TopBarAction(Icons.AutoMirrored.Rounded.CompareArrows, stringResource(R.string.compare_button), {
                scope.launch { others = model.others() }
            })
            TopBarAction(Icons.Rounded.Delete, stringResource(R.string.delete), { confirmDelete = true })
        }
    } else Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) }
        Text(stringResource(R.string.result_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        if (record != null) {
            IconButton(onClick = { scope.launch { others = model.others() } }) {
                Icon(Icons.AutoMirrored.Rounded.CompareArrows, stringResource(R.string.compare_button), tint = StressColors.TextDim)
            }
            IconButton(onClick = { confirmDelete = true }) {
                Icon(Icons.Rounded.Delete, stringResource(R.string.delete), tint = StressColors.TextDim)
            }
        }
    }
    TopBar(
        title = stringResource(R.string.result_title),
        caption = caption,
        onBack = onBack,
        modifier = Modifier.background(StressColors.SurfaceLow).statusBarsPadding(),
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
                            Text(
                                if (other.summary.powerValid) Format.watts(other.summary.peakWatts ?: other.summary.meanWatts) else Format.MISSING,
                                style = NumberStyles.Small,
                                color = StressColors.Accent,
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

@Composable
private fun ResultBody(record: RunRecord, insights: List<Insight>, modifier: Modifier = Modifier) {
    Stack(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Summary(record)
        PowerTiles(record)
        MainChart(record)
        InsightsCard(insights)
        MoreTiles(record)
        DetailCharts(record)
        DeviceCard(record)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Summary(record: RunRecord) {
    val context = context()
    val s = record.summary
    val reason = Labels.endReasonOf(record)
    val peak = s.peakWatts.takeIf { s.powerValid }
    val sustained = s.sustainedWatts.takeIf { s.powerValid }
    val stability = s.cpuStability ?: s.gpuStability
    Panel(Modifier.fillMaxWidth()) {
        Stack(spacing = 8) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StatusChip(stringResource(Labels.endReason(reason)), Labels.endReasonColor(reason))
                record.quality?.let { name -> SceneQuality.entries.firstOrNull { it.name == name } }?.let {
                    StatusChip(stringResource(Labels.quality(it)), StressColors.Cool)
                }
                record.safetyEnabled?.let { on ->
                    StatusChip(
                        stringResource(if (on) R.string.safety_on_short else R.string.safety_off_short),
                        if (on) StressColors.Good else StressColors.Bad,
                    )
                }
            }
            Text(Dates.long(record.startedAtMillis), style = StressType.Code, color = StressColors.TextDim)
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(12.dp), Alignment.CenterVertically) {
                Stack(Modifier.weight(1f), spacing = 4) {
                    SectionLabel(stringResource(R.string.result_peak))
                    MetricNumber(Format.wattNumber(peak), "W", NumberStyles.Hero, StressColors.Accent)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.result_sustained), style = StressType.BodySmall, color = StressColors.TextDim)
                        MetricNumber(Format.wattNumber(sustained), "W", NumberStyles.Small, StressColors.Accent)
                    }
                }
                StabilityArc(stability, stringResource(R.string.result_stability))
            }
            StressMode.of(record.mode)?.let { mode ->
                ReferenceDevices.forMode(context, mode)?.let { (device, watts) ->
                    Text(
                        stringResource(R.string.result_reference, device.name, device.chipset, Format.watts(watts)),
                        style = StressType.BodySmall, color = StressColors.TextDim,
                    )
                }
            }
            val missing = when {
                !s.powerValid -> R.string.insight_charging_detail
                s.powerFromCounter -> R.string.insight_counter_detail
                peak == null || sustained == null || stability == null || s.meanWatts == null ||
                    s.idleWatts == null || s.energyWattHours == null || s.batteryStartPercent == null ||
                    s.batteryEndPercent == null || s.batteryLifeHours == null || s.hottestChip() == null ||
                    s.maxTemperatures[RunAnalysis.BATTERY] == null && s.maxTemperatures["Pil"] == null ->
                    R.string.result_unavailable
                else -> null
            }
            if (missing != null) Text(stringResource(missing), style = StressType.BodySmall, color = StressColors.TextDim)
        }
    }
}

@Composable
private fun PowerTiles(record: RunRecord) {
    val s = record.summary
    TileRow(Modifier.height(IntrinsicSize.Min)) {
        val cell = Modifier.weight(1f).fillMaxHeight()
        val color = StressColors.Accent
        MetricTile(
            stringResource(R.string.result_mean_short), Format.wattNumber(s.meanWatts.takeIf { s.powerValid }),
            cell, unit = "W", color = color, padding = 8.dp,
        )
        MetricTile(
            stringResource(R.string.result_idle), Format.wattNumber(s.idleWatts.takeIf { s.powerValid }),
            cell, unit = "W", color = color, padding = 8.dp,
        )
        MetricTile(
            stringResource(R.string.result_energy), Format.number(s.energyWattHours.takeIf { s.powerValid }, 2),
            cell, unit = "Wh", color = color, padding = 8.dp,
        )
    }
}

@Composable
private fun MoreTiles(record: RunRecord) {
    val context = context()
    val s = record.summary
    val chip = s.hottestChip()
    val battery = s.maxTemperatures[RunAnalysis.BATTERY] ?: s.maxTemperatures["Pil"]
    val mode = StressMode.of(record.mode)
    Stack(spacing = 8) {
        TileRow(Modifier.height(IntrinsicSize.Min)) {
            val cell = Modifier.weight(1f).fillMaxHeight()
            MetricTile(
                stringResource(R.string.result_battery),
                "${Format.percentValue(s.batteryStartPercent)} → ${Format.percentValue(s.batteryEndPercent)}",
                cell,
                caption = stringResource(
                    R.string.result_battery_estimate,
                    s.batteryLifeHours?.takeIf { s.powerValid }?.let { hoursText(context, it) } ?: Format.MISSING,
                ),
            )
            MetricTile(
                stringResource(R.string.result_hottest), Format.number(chip, 1), cell, unit = "°C",
                color = StressColors.temperature(chip, Limits.chipWarn, Limits.chipStop),
                caption = "${stringResource(R.string.temp_battery)} ${Format.celsius(battery)}",
                captionColor = StressColors.temperature(battery, Limits.batteryWarn, Limits.batteryStop),
            )
        }
        TileRow(Modifier.height(IntrinsicSize.Min)) {
            val cell = Modifier.weight(1f).fillMaxHeight()
            if (mode?.usesCpu != false && s.cpuMeanRate != null) {
                val (value, unit) = Format.rateParts(s.cpuMeanRate, Labels.rateSymbol(context, s.cpuUnit))
                MetricTile(stringResource(R.string.result_cpu_perf), value, cell, unit = unit)
            }
            if (s.gpuMeanRate != null) {
                val (value, unit) = Format.rateParts(s.gpuMeanRate, Labels.rateSymbol(context, s.gpuUnit))
                MetricTile(stringResource(R.string.result_gpu_perf), value, cell, unit = unit)
            }
            if (s.meanFps != null) {
                MetricTile(
                    stringResource(R.string.result_fps), Format.number(s.meanFps, 1), cell, unit = "FPS",
                    caption = s.minFps?.let { stringResource(R.string.result_fps_min, Format.number(it, 1)) },
                )
            }
        }
    }
}

@Composable
private fun InsightsCard(insights: List<Insight>) {
    val context = context()
    Panel(Modifier.fillMaxWidth(), title = stringResource(R.string.result_analysis)) {
        insights.forEachIndexed { index, insight ->
            val title = when (insight) {
                is Insight.Throttled -> stringResource(R.string.result_insight_throttled)
                is Insight.PerformanceDrop -> stringResource(R.string.result_insight_drop)
                is Insight.Stability -> stringResource(R.string.result_insight_stability, stringResource(InsightText.grade(insight.grade)))
                is Insight.ChipPeak -> stringResource(R.string.result_insight_chip, Labels.temperature(context, insight.key))
                is Insight.BatteryWarmed -> stringResource(R.string.result_insight_battery)
                is Insight.BatteryDrain -> stringResource(R.string.result_insight_drain)
                is Insight.ComputationErrors -> stringResource(R.string.result_errors)
                is Insight.FrameRate -> stringResource(R.string.result_insight_fps)
                else -> InsightText.title(context, insight)
            }
            val value = when (insight) {
                is Insight.Throttled -> Format.clock(insight.seconds)
                is Insight.PerformanceDrop -> Format.percent(insight.drop)
                is Insight.Stability -> Format.percent(insight.value, 1)
                is Insight.ChipPeak -> Format.celsius(insight.celsius)
                is Insight.BatteryWarmed -> "${Format.number(insight.from, 0)} → ${Format.celsius(insight.to, 0)}"
                is Insight.BatteryDrain -> Format.percent(insight.percentPerHour / 100.0)
                is Insight.ComputationErrors -> insight.count.toString()
                is Insight.FrameRate -> "${Format.number(insight.mean, 1)} FPS"
                else -> null
            }
            val color = when (insight) {
                is Insight.ChipPeak -> StressColors.temperature(insight.celsius, Limits.chipWarn, Limits.chipStop)
                is Insight.BatteryWarmed -> StressColors.temperature(insight.to, Limits.batteryWarn, Limits.batteryStop)
                else -> InsightText.color(insight.severity)
            }
            InsightRow(
                title, value, valueColor = color, divider = index != insights.lastIndex,
                detail = when (insight) {
                    Insight.Charging, Insight.PowerFromCounter -> null // The summary gives the reason beside the missing reading.
                    else -> InsightText.detail(context, insight)
                },
            )
        }
    }
}

private enum class MainSeries(val label: Int) {
    Power(R.string.chart_power),
    Temperature(R.string.chart_temperature),
}

@Composable
private fun MainChart(record: RunRecord) {
    val context = context()
    val series = record.series
    val chip = remember(series) { series.chipTemperatures() }
    val chipValues = remember(chip) { chip.filterNotNull() }
    val batteryLabel = stringResource(R.string.temp_battery)
    val temperatures = remember(series, context, batteryLabel) {
        (series.temperatures.map { (key, values) ->
            ChartSeries(Labels.temperature(context, key), Labels.temperatureColor(key), values)
        } + ChartSeries(batteryLabel, Labels.temperatureColor(RunAnalysis.BATTERY), series.batteryCelsius))
            .filter { line -> line.values.any { it != null } }
    }
    val hasPower = record.summary.powerValid && series.watts.any { it != null }
    val hasHeat = temperatures.isNotEmpty()
    var selected by rememberSaveable(record.id) {
        mutableStateOf(if (hasPower || !hasHeat) MainSeries.Power else MainSeries.Temperature)
    }
    Panel(Modifier.fillMaxWidth(), padding = 12.dp) {
        Stack(spacing = 8) {
            SegmentedControl(
                options = MainSeries.entries, selected = selected,
                onSelect = { selected = it }, label = { stringResource(it.label) },
            )
            when (selected) {
                MainSeries.Power -> if (hasPower) {
                    val powerLabel = stringResource(R.string.chart_power)
                    val chipLabel = stringResource(R.string.run_chart_chip)
                    val lines = buildList {
                        add(ChartSeries(powerLabel, StressColors.Accent, series.watts, "W"))
                        if (chipValues.isNotEmpty()) {
                            val range = (chipValues.min() - 5)..(chipValues.max() + 5)
                            add(ChartSeries(chipLabel, StressColors.Cool, chip, "°C", range))
                        }
                    }
                    TimeChart(series.seconds, lines, "W", height = 160.dp, yMin = 0.0, decimals = Format.WATT_DECIMALS)
                } else {
                    Text(Format.MISSING, style = StressType.Code, color = StressColors.TextDim)
                }
                MainSeries.Temperature -> if (hasHeat) {
                    if (temperatures.size == 1) SectionLabel(temperatures.single().name, caption = "°C")
                    TimeChart(series.seconds, temperatures, "°C", height = 160.dp)
                } else {
                    Text(Format.MISSING, style = StressType.Code, color = StressColors.TextDim)
                }
            }
        }
    }
}

@Composable
private fun DetailCharts(record: RunRecord) {
    val series = record.series
    Stack {
        if (series.clocksMhz.values.any { v -> v.any { it != null } }) {
            ChartCard(stringResource(R.string.chart_clock)) {
                val entries = series.clocksMhz.entries.toList()
                TimeChart(
                    series.seconds,
                    entries.mapIndexed { i, (name, values) -> ChartSeries(name, Labels.clusterColor(i, entries.size), values) },
                    "MHz",
                    yMin = 0.0,
                    decimals = 0,
                )
            }
        }
        val performance = buildList {
            if (series.cpuRelative.any { it != null }) add(ChartSeries("CPU", StressColors.Accent, series.cpuRelative.map { it?.times(100) }))
            if (series.gpuRelative.any { it != null }) add(ChartSeries("GPU", StressColors.Cool, series.gpuRelative.map { it?.times(100) }))
        }
        if (performance.isNotEmpty()) {
            ChartCard(stringResource(R.string.chart_performance), stringResource(R.string.chart_performance_hint)) {
                TimeChart(series.seconds, performance, "%", yMin = 0.0, yMax = 105.0, decimals = 0)
            }
        }
        if (series.fps.any { it != null }) {
            ChartCard(stringResource(R.string.chart_fps)) {
                TimeChart(series.seconds, listOf(ChartSeries("FPS", StressColors.Cool, series.fps)), "fps", yMin = 0.0)
            }
        }
        val levels = series.batteryPercent.filterNotNull()
        if (levels.isNotEmpty() && levels.max() - levels.min() >= 1) {
            ChartCard(stringResource(R.string.chart_battery)) {
                TimeChart(
                    series.seconds,
                    listOf(ChartSeries(stringResource(R.string.temp_battery), StressColors.Cool, series.batteryPercent.map { it?.toDouble() })),
                    "%",
                    decimals = 0,
                )
            }
        }
    }
}

@Composable
private fun ChartCard(title: String, hint: String? = null, content: @Composable () -> Unit) {
    Panel(Modifier.fillMaxWidth(), title = title, padding = 12.dp) {
        if (hint != null) Text(hint, style = StressType.BodySmall, color = StressColors.TextDim)
        content()
    }
}

@Composable
private fun DeviceCard(record: RunRecord) {
    val device = record.device ?: return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel(stringResource(R.string.result_device))
        Panel(Modifier.fillMaxWidth()) {
            Text(device.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            device.soc?.let { InfoRow(stringResource(R.string.device_soc), it) }
            device.cpuClusters.forEach { InfoRow(stringResource(R.string.device_cpu), it) }
            device.gpu?.let { InfoRow(stringResource(R.string.device_gpu), it) }
            device.vulkan?.let { InfoRow("Vulkan", it) }
            InfoRow(stringResource(R.string.device_android), "${device.androidVersion} (API ${device.sdk})")
            device.ramGigabytes?.let { InfoRow(stringResource(R.string.device_ram), "${Format.number(it, 1)} GB") }
            device.display?.let { InfoRow(stringResource(R.string.device_display), it) }
            record.appVersion?.let { InfoRow(stringResource(R.string.device_app), it) }
        }
    }
}

@Composable
private fun ReportActions(record: RunRecord, insights: List<Insight>) {
    val context = context()
    val graph = context.graph
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val file = ReportPdf.write(context, record, insights, graph.reportDir)
                    context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } != null
                }.getOrDefault(false)
            }
            busy = false
            Toast.makeText(context, if (ok) R.string.export_saved else R.string.export_failed, Toast.LENGTH_SHORT).show()
        }
    }

    fun share(file: File, type: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.reports", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            this.type = type
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.export_share_title)))
    }

    val shareCsv: () -> Unit = {
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching { ReportCsv.write(record, graph.reportDir) }.getOrNull()
            }
            if (file != null) share(file, "text/csv") else Toast.makeText(context, R.string.export_failed, Toast.LENGTH_SHORT).show()
        }
    }
    if (record.isCharge) {
        SecondaryButton(stringResource(R.string.export_csv), shareCsv, Modifier.fillMaxWidth(), icon = Icons.Rounded.TableChart)
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(stringResource(R.string.result_export))
        GlassCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBadge(Icons.Rounded.PictureAsPdf, StressColors.Accent)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.export_pdf_title), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.export_pdf_detail), style = MaterialTheme.typography.bodySmall, color = StressColors.TextDim)
                }
                if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = StressColors.Accent, strokeWidth = 2.dp)
            }
            Spacer(Modifier.height(12.dp))
            PrimaryButton(
                text = stringResource(R.string.export_share_pdf),
                icon = Icons.Rounded.Share,
                height = 52.dp,
                enabled = !busy,
                modifier = if (record.isCharge) Modifier.weight(1f) else Modifier.width(80.dp),
                onClick = {
                    scope.launch {
                        busy = true
                        val file = withContext(Dispatchers.IO) {
                            runCatching { ReportCsv.write(record, graph.reportDir) }.getOrNull()
                        }
                        busy = false
                        if (file != null) share(file, "text/csv") else Toast.makeText(context, R.string.export_failed, Toast.LENGTH_SHORT).show()
                    }
                },
            )
            SecondaryButton(
                text = stringResource(R.string.export_save_pdf),
                enabled = !busy,
                modifier = Modifier.weight(1f),
                onClick = { savePdf.launch(ReportPdf.fileName(record)) },
            )
            PrimaryButton(
                text = stringResource(R.string.result_share_pdf),
                enabled = !busy,
                modifier = Modifier.weight(1f),
                onClick = {
                    scope.launch {
                        busy = true
                        val file = withContext(Dispatchers.IO) {
                            runCatching { ReportPdf.write(context, record, insights, graph.reportDir) }.getOrNull()
                        }
                        busy = false
                        if (file != null) share(file, "application/pdf") else Toast.makeText(context, R.string.export_failed, Toast.LENGTH_SHORT).show()
                    }
                },
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(
                    text = stringResource(R.string.export_save_pdf),
                    icon = Icons.Rounded.Download,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    onClick = { savePdf.launch(ReportPdf.fileName(record)) },
                )
                SecondaryButton(
                    text = stringResource(R.string.export_csv),
                    icon = Icons.Rounded.TableChart,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    onClick = shareCsv,
                )
            }
        }
    }
}
