package dev.ozcan.stress.ui.result

import dev.ozcan.stress.ui.upper
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BatteryStd
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Videocam
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.ozcan.stress.AppGraph
import dev.ozcan.stress.R
import dev.ozcan.stress.analysis.Insight
import dev.ozcan.stress.analysis.Insights
import dev.ozcan.stress.engine.SceneQuality
import dev.ozcan.stress.graph
import dev.ozcan.stress.report.ReportCsv
import dev.ozcan.stress.report.ReportPdf
import dev.ozcan.stress.run.RunAnalysis
import dev.ozcan.stress.run.RunRecord
import dev.ozcan.stress.run.StressMode
import dev.ozcan.stress.ui.Dates
import dev.ozcan.stress.ui.Format
import dev.ozcan.stress.ui.InsightText
import dev.ozcan.stress.ui.Labels
import dev.ozcan.stress.ui.components.CenteredBox
import dev.ozcan.stress.ui.components.ChartSeries
import dev.ozcan.stress.ui.components.CountUpNumber
import dev.ozcan.stress.ui.components.GlassCard
import dev.ozcan.stress.ui.components.IconBadge
import dev.ozcan.stress.ui.components.InfoRow
import dev.ozcan.stress.ui.components.Pill
import dev.ozcan.stress.ui.components.PrimaryButton
import dev.ozcan.stress.ui.components.SecondaryButton
import dev.ozcan.stress.ui.components.SectionHeader
import dev.ozcan.stress.ui.components.StatTile
import dev.ozcan.stress.ui.components.TileRow
import dev.ozcan.stress.ui.components.TimeChart
import dev.ozcan.stress.ui.context
import dev.ozcan.stress.ui.theme.NumberStyles
import dev.ozcan.stress.ui.theme.StressColors
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
            _loaded.value = Loaded(record, record?.let(Insights::of).orEmpty())
        }
    }

    suspend fun delete() = withContext(Dispatchers.IO) { graph.runs.delete(runId) }
}

@Composable
fun ResultScreen(runId: String, onBack: () -> Unit) {
    val graph = context().graph
    val model: ResultViewModel = viewModel { ResultViewModel(graph, runId) }
    val loaded by model.loaded.collectAsStateWithLifecycle()
    val record = loaded?.record

    Column(Modifier.fillMaxSize()) {
        TopBar(record, onBack, onDeleted = onBack, model = model)
        when {
            loaded == null -> CenteredBox { CircularProgressIndicator(color = StressColors.Accent) }
            record == null -> CenteredBox { Text(stringResource(R.string.result_missing), color = StressColors.TextDim) }
            else -> ResultBody(record, loaded!!.insights)
        }
    }
}

@Composable
private fun TopBar(record: RunRecord?, onBack: () -> Unit, onDeleted: () -> Unit, model: ResultViewModel) {
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) }
        Text(stringResource(R.string.result_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        if (record != null) {
            IconButton(onClick = { confirmDelete = true }) {
                Icon(Icons.Rounded.Delete, stringResource(R.string.delete), tint = StressColors.TextDim)
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.history_delete_title)) },
            text = { Text(stringResource(R.string.history_delete_text), color = StressColors.TextDim) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        model.delete()
                        onDeleted()
                    }
                }) { Text(stringResource(R.string.delete), color = StressColors.Bad) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
            containerColor = StressColors.SurfaceHigh,
        )
    }
}

@Composable
private fun ResultBody(record: RunRecord, insights: List<Insight>) {
    val context = context()
    val s = record.summary
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
            .navigationBarsPadding()
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Reveal(shown, 0) { Header(record) }
        Reveal(shown, 1) { Hero(record) }

        Reveal(shown, 2) { Tiles(record) }

        Reveal(shown, 3) { InsightsCard(insights) }

        Reveal(shown, 4) { Charts(record) }

        Reveal(shown, 5) { DeviceCard(record) }

        Reveal(shown, 6) { ExportCard(record, insights) }
    }
}

/** Sections of the result rise in one after another. */
@Composable
private fun Reveal(shown: Boolean, index: Int, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = shown,
        enter = fadeIn(tween(420, delayMillis = 70 * index)) + slideInVertically(tween(480, delayMillis = 70 * index)) { it / 6 },
    ) {
        content()
    }
}

/** One metric of the result grid. */
private data class Tile(
    val label: String,
    val value: String,
    val unit: String? = null,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val accent: androidx.compose.ui.graphics.Color = StressColors.Text,
    val footnote: String? = null,
)

/** The headline metrics, two to a row; a run shows the ones it measured. */
@Composable
private fun Tiles(record: RunRecord) {
    val context = context()
    val s = record.summary
    val mode = StressMode.of(record.mode)
    val chip = listOfNotNull(s.maxTemperatures["cpu"], s.maxTemperatures["gpu"], s.maxTemperatures["A715"], s.maxTemperatures["A510"], s.maxTemperatures["GPU"]).maxOrNull()
    val tiles = buildList {
        add(Tile(stringResource(R.string.result_mean), Format.number(s.meanWatts, 2), "W", Icons.Rounded.Bolt))
        add(Tile(stringResource(R.string.result_idle), Format.number(s.idleWatts, 2), "W", Icons.Rounded.Bolt))
        add(Tile(stringResource(R.string.result_energy), Format.number(s.energyWattHours, 2), "Wh", Icons.Rounded.BatteryStd))
        add(
            Tile(
                stringResource(R.string.result_battery),
                "${Format.percentValue(s.batteryStartPercent)} → ${Format.percentValue(s.batteryEndPercent)}",
                icon = Icons.Rounded.BatteryStd,
            ),
        )
        add(Tile(stringResource(R.string.result_battery_life), s.batteryLifeHours?.let { hoursText(context, it) } ?: Format.MISSING, icon = Icons.Rounded.Schedule))
        add(
            Tile(
                stringResource(R.string.result_hottest),
                Format.number(chip, 1),
                "°C",
                Icons.Rounded.Thermostat,
                accent = if ((chip ?: 0.0) >= 95) StressColors.Warn else StressColors.Text,
            ),
        )
        if (mode?.usesCpu != false && s.cpuMeanRate != null) {
            val (value, unit) = Format.rateParts(s.cpuMeanRate, Labels.rateSymbol(context, s.cpuUnit))
            add(Tile(stringResource(R.string.result_cpu_perf), value, unit, Icons.Rounded.Memory))
        }
        if (s.gpuMeanRate != null) {
            val (value, unit) = Format.rateParts(s.gpuMeanRate, Labels.rateSymbol(context, s.gpuUnit))
            add(Tile(stringResource(R.string.result_gpu_perf), value, unit, Icons.Rounded.Videocam))
        }
        if (s.meanFps != null) {
            add(
                Tile(
                    stringResource(R.string.result_fps),
                    Format.number(s.meanFps, 1),
                    "FPS",
                    Icons.Rounded.Videocam,
                    footnote = s.minFps?.let { stringResource(R.string.result_fps_min, Format.number(it, 1)) },
                ),
            )
        }
        (s.cpuStability ?: s.gpuStability)?.let {
            add(Tile(stringResource(R.string.result_stability), Format.percent(it), icon = Icons.Rounded.Insights))
        }
        add(
            Tile(
                stringResource(R.string.result_errors),
                s.computationErrors.toString(),
                icon = Icons.Rounded.ErrorOutline,
                accent = if (s.computationErrors > 0) StressColors.Bad else StressColors.Good,
            ),
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.chunked(2).forEach { row ->
            TileRow {
                row.forEach { t ->
                    StatTile(t.label, t.value, Modifier.weight(1f), unit = t.unit, icon = t.icon, accent = t.accent, footnote = t.footnote)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Header(record: RunRecord) {
    val context = context()
    val mode = StressMode.of(record.mode)
    val reason = Labels.endReasonOf(record)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IconBadge(mode?.let(Labels::modeIcon) ?: Icons.Rounded.Bolt, StressColors.Accent, size = 48.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(Labels.recordModeName(context, record), style = MaterialTheme.typography.titleLarge)
            Text(
                "${Dates.long(record.startedAtMillis)} · ${Format.clock(record.loadSeconds)}",
                style = MaterialTheme.typography.bodySmall,
                color = StressColors.TextDim,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Pill(stringResource(Labels.endReason(reason)), Labels.endReasonColor(reason))
                record.quality?.let { q -> SceneQuality.entries.firstOrNull { it.name == q } }?.let {
                    Pill(stringResource(Labels.quality(it)), StressColors.Cool)
                }
                record.safetyEnabled?.let { on ->
                    Pill(
                        stringResource(if (on) R.string.safety_on_short else R.string.safety_off_short),
                        if (on) StressColors.Good else StressColors.Bad,
                    )
                }
            }
        }
    }
}

@Composable
private fun Hero(record: RunRecord) {
    val s = record.summary
    GlassCard(Modifier.fillMaxWidth(), highlight = true, padding = 20.dp) {
        if (!s.powerValid) {
            Text(stringResource(R.string.result_power_invalid), style = MaterialTheme.typography.titleMedium, color = StressColors.Warn)
            Text(stringResource(R.string.insight_charging_detail), style = MaterialTheme.typography.bodySmall, color = StressColors.TextDim)
            return@GlassCard
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.result_peak).upper(), style = MaterialTheme.typography.labelMedium, color = StressColors.TextDim, letterSpacing = 1.6.sp)
                Row(verticalAlignment = Alignment.Bottom) {
                    CountUpNumber(s.peakWatts ?: s.meanWatts, { Format.number(it, 2) }, style = NumberStyles.Hero, color = StressColors.Accent)
                    Text(" W", style = MaterialTheme.typography.headlineSmall, color = StressColors.TextDim, modifier = Modifier.padding(bottom = 10.dp))
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(stringResource(R.string.result_sustained).upper(), style = MaterialTheme.typography.labelMedium, color = StressColors.TextDim, letterSpacing = 1.6.sp)
                Row(verticalAlignment = Alignment.Bottom) {
                    CountUpNumber(s.sustainedWatts, { Format.number(it, 2) }, style = NumberStyles.Large, color = StressColors.Text, delayMillis = 150)
                    Text(" W", style = MaterialTheme.typography.titleMedium, color = StressColors.TextDim, modifier = Modifier.padding(bottom = 4.dp))
                }
            }
        }
        if (s.powerFromCounter) {
            Text(stringResource(R.string.insight_counter_detail), style = MaterialTheme.typography.labelSmall, color = StressColors.TextFaint)
        }
    }
}

@Composable
private fun InsightsCard(insights: List<Insight>) {
    val context = context()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(stringResource(R.string.result_analysis))
        GlassCard(Modifier.fillMaxWidth()) {
            insights.forEachIndexed { i, insight ->
                if (i > 0) Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val color = InsightText.color(insight.severity)
                    Box(
                        Modifier.size(34.dp).background(color.copy(alpha = 0.14f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(InsightText.icon(insight), null, tint = color, modifier = Modifier.size(18.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(InsightText.title(context, insight), style = MaterialTheme.typography.titleSmall)
                        Text(InsightText.detail(context, insight), style = MaterialTheme.typography.bodySmall, color = StressColors.TextDim)
                    }
                }
            }
        }
    }
}

@Composable
private fun Charts(record: RunRecord) {
    val context = context()
    val series = record.series
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(stringResource(R.string.result_charts))
        if (series.watts.any { it != null }) {
            ChartCard(stringResource(R.string.chart_power)) {
                TimeChart(series.seconds, listOf(ChartSeries(stringResource(R.string.chart_power), StressColors.Accent, series.watts)), "W", yMin = 0.0, decimals = 2)
            }
        }
        val temperatures = series.temperatures.map { (key, values) ->
            ChartSeries(Labels.temperature(context, key), Labels.temperatureColor(key), values)
        } + ChartSeries(Labels.temperature(context, RunAnalysis.BATTERY), Labels.temperatureColor(RunAnalysis.BATTERY), series.batteryCelsius)
        if (temperatures.any { s -> s.values.any { it != null } }) {
            ChartCard(stringResource(R.string.chart_temperature)) {
                TimeChart(series.seconds, temperatures.filter { s -> s.values.any { it != null } }, "°C")
            }
        }
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
                TimeChart(series.seconds, listOf(ChartSeries("FPS", StressColors.Violet, series.fps)), "fps", yMin = 0.0)
            }
        }
        val levels = series.batteryPercent.filterNotNull()
        if (levels.isNotEmpty() && levels.max() - levels.min() >= 1) {
            ChartCard(stringResource(R.string.chart_battery)) {
                TimeChart(
                    series.seconds,
                    listOf(ChartSeries(stringResource(R.string.temp_battery), StressColors.Good, series.batteryPercent.map { it?.toDouble() })),
                    "%",
                    decimals = 0,
                )
            }
        }
    }
}

@Composable
private fun ChartCard(title: String, hint: String? = null, content: @Composable () -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), padding = 14.dp) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        if (hint != null) Text(hint, style = MaterialTheme.typography.labelSmall, color = StressColors.TextFaint)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun DeviceCard(record: RunRecord) {
    val device = record.device ?: return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(stringResource(R.string.result_device))
        GlassCard(Modifier.fillMaxWidth()) {
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
private fun ExportCard(record: RunRecord, insights: List<Insight>) {
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

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(stringResource(R.string.result_export))
        GlassCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBadge(Icons.Rounded.PictureAsPdf, StressColors.AccentHot)
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
                    onClick = {
                        scope.launch {
                            val file = withContext(Dispatchers.IO) {
                                runCatching { ReportCsv.write(record, graph.reportDir) }.getOrNull()
                            }
                            if (file != null) share(file, "text/csv") else Toast.makeText(context, R.string.export_failed, Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            }
        }
    }
}

/** 2.52 h -> "2 h 31 min" in the screen's language. */
fun hoursText(context: android.content.Context, hours: Double): String {
    val minutes = (hours * 60).toLong()
    return if (minutes >= 60) context.getString(R.string.hours_minutes, minutes / 60, minutes % 60)
    else context.getString(R.string.minutes_only, minutes)
}
