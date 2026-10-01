package dev.ozcan.stress.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ozcan.stress.run.RunAnalysis
import dev.ozcan.stress.run.RunRecord
import dev.ozcan.stress.run.StressMode
import dev.ozcan.stress.ui.theme.StressColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Line colours, the same meaning on every chart. */
object SeriesColors {
    val BigCores = StressColors.Accent
    val LittleCores = Color(0xFF7CFFB2)
    val Gpu = StressColors.Cool
    val Memory = Color(0xFFC792EA)
    val Battery = Color(0xFFFF6B6B)
    val Prime = Color(0xFFE3EEF2)

    fun forTemperature(label: String): Color = when (label) {
        "A715" -> BigCores
        "A510" -> LittleCores
        "GPU" -> Gpu
        "DDR" -> Memory
        else -> Prime
    }

    fun forCluster(index: Int): Color = listOf(LittleCores, BigCores, Prime).getOrElse(index) { Prime }
}

@Composable
fun ResultScreen(record: RunRecord, onBack: () -> Unit) {
    val s = record.summary
    val series = record.series
    val title = runCatching { StressMode.valueOf(record.mode).title }.getOrDefault(record.mode)
    val date = SimpleDateFormat("d MMMM yyyy · HH:mm", Locale.forLanguageTag("tr-TR")).format(Date(record.startedAtMillis))

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "$date · ${Durations.clock(record.loadSeconds)}" + if (record.stoppedEarly) " · erken durduruldu" else "",
            style = MaterialTheme.typography.bodySmall,
            color = StressColors.TextDim,
        )
        if (!s.powerValid) {
            Text("Şarj kablosu takılıydı: güç ve enerji değerleri geçersiz.", color = StressColors.Warn, style = MaterialTheme.typography.bodySmall)
        }

        StatRow(
            "Tepe güç" to Format.watts(s.peakWatts),
            "Sürekli güç" to Format.watts(s.sustainedWatts),
            accent = StressColors.Accent,
        )
        StatRow("Ortalama" to Format.watts(s.meanWatts), "Boşta" to Format.watts(s.idleWatts))
        StatRow(
            "Enerji" to (s.energyWattHours?.let { "${Format.number(it, 2)} Wh" } ?: Format.MISSING),
            "Pil" to "%${s.batteryStartPercent ?: "—"} → %${s.batteryEndPercent ?: "—"}",
        )
        StatRow(
            "Bu yükte tam pil" to (s.batteryLifeHours?.let(Durations::hours) ?: Format.MISSING),
            "Hesap hatası" to s.computationErrors.toString(),
            accent = if (s.computationErrors > 0) StressColors.Bad else StressColors.Text,
        )

        Panel("Sıcaklık") {
            (RunAnalysis.CHART_GROUPS.map { it.label } + RunAnalysis.BATTERY).forEach { label ->
                val max = s.maxTemperatures[label] ?: return@forEach
                val start = s.startTemperatures[label]
                val rise = start?.let { " (+${Format.number(max - it, 1)})" } ?: ""
                Field(label, "${Format.celsius(max)}$rise")
            }
        }
        Panel("Kısılma ve kararlılık (yük altındaki kümeler)") {
            s.firstThrottleSeconds.forEach { (cluster, seconds) ->
                Field("$cluster ilk kısılma", seconds?.let(Durations::clock) ?: "kısılmadı")
            }
            s.cpuStability?.let { Field("CPU kararlılığı", Format.percent(it)) }
            s.gpuStability?.let { Field("GPU kararlılığı", Format.percent(it)) }
        }

        Panel("Güç") {
            LineChart(series.seconds, listOf(ChartSeries("Güç", StressColors.Accent, series.watts)), "W", yMin = 0.0)
        }
        Panel("Sıcaklık") {
            LineChart(
                series.seconds,
                series.temperatures.map { (label, values) -> ChartSeries(label, SeriesColors.forTemperature(label), values) } +
                    ChartSeries(RunAnalysis.BATTERY, SeriesColors.Battery, series.batteryCelsius),
                "°C",
            )
        }
        Panel("Frekans") {
            LineChart(
                series.seconds,
                series.clocksMhz.entries.mapIndexed { i, (name, values) -> ChartSeries(name, SeriesColors.forCluster(i), values) },
                "MHz",
                yMin = 0.0,
            )
        }
        Panel("Performans (en iyi saniyeye göre)") {
            val lines = buildList {
                add(ChartSeries("CPU", StressColors.Accent, series.cpuRelative.map { it?.times(100) }))
                if (series.gpuRelative.any { it != null }) {
                    add(ChartSeries("GPU", SeriesColors.Gpu, series.gpuRelative.map { it?.times(100) }))
                }
            }
            LineChart(series.seconds, lines, "%", yMin = 0.0, yMax = 105.0)
        }

        Row {
            TextButton(onClick = onBack) { Text("Geri") }
        }
    }
}

@Composable
private fun StatRow(first: Pair<String, String>, second: Pair<String, String>, accent: Color = StressColors.Text) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Stat(first.first, first.second, Modifier.weight(1f), accent)
        Stat(second.first, second.second, Modifier.weight(1f), accent)
    }
}
