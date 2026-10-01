package dev.ozcan.stress.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ozcan.stress.graph
import dev.ozcan.stress.run.RunRecord
import dev.ozcan.stress.run.StressDuration
import dev.ozcan.stress.run.StressMode
import dev.ozcan.stress.ui.theme.StressColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    onStart: (StressMode, StressDuration) -> Unit,
    onOpen: (RunRecord) -> Unit,
    onHistory: () -> Unit,
    onDiagnostics: () -> Unit,
) {
    val graph = LocalContext.current.graph
    var mode by rememberSaveable { mutableStateOf(StressMode.Full) }
    var duration by rememberSaveable { mutableStateOf(StressDuration.Fifteen) }
    val latest by graph.sampler.latest.collectAsStateWithLifecycle()
    val last by produceState<RunRecord?>(null) { value = withContext(Dispatchers.IO) { graph.runs.list().firstOrNull() } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GaugeMark(StressColors.Accent, Modifier.size(44.dp))
            Column {
                Text("STRES", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, letterSpacing = 6.sp)
                Text(
                    "Stres testi · benchmark · Honor 400 · Snapdragon 7 Gen 3",
                    style = MaterialTheme.typography.bodySmall,
                    color = StressColors.TextDim,
                )
            }
        }

        latest?.let { sample ->
            val battery = sample.battery
            val hottest = sample.sysfs.temperatures.values.maxOrNull()
            Text(
                "Pil %${battery.levelPercent ?: "—"} · ${Format.celsius(battery.temperatureCelsius)} · çip ${Format.celsius(hottest)}",
                style = MaterialTheme.typography.bodyMedium,
                color = StressColors.TextDim,
                fontFamily = FontFamily.Monospace,
            )
            if (battery.plugged) {
                Text(
                    "Şarj kablosu takılı: test çalışır ama pilden çekilen güç ölçülemez.",
                    color = StressColors.Warn,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // The cinematic modes share one card, a chip for each scene, in the
        // place of the first of them.
        var scene by rememberSaveable { mutableStateOf(StressMode.Cinematic) }
        val cinematic = StressMode.entries.filter { it.scene != null }
        StressMode.entries.forEach { m ->
            when {
                m.scene == null -> ModeCard(m, selected = m == mode) { mode = m }
                m == cinematic.first() -> CinematicCard(cinematic, selected = mode.scene != null, current = scene) { picked ->
                    scene = picked
                    mode = picked
                }
            }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StressDuration.entries.forEach { d ->
                val selected = d == duration
                Text(
                    d.title,
                    modifier = Modifier
                        .background(if (selected) StressColors.AccentDim else StressColors.SurfaceHigh, RoundedCornerShape(20.dp))
                        .clickable { duration = d }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }

        Button(
            onClick = { onStart(mode, duration) },
            modifier = Modifier.fillMaxWidth().height(58.dp),
            colors = ButtonDefaults.buttonColors(containerColor = StressColors.Accent, contentColor = StressColors.Background),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text("BAŞLAT", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
        }

        last?.let { record ->
            Panel("Son koşu", Modifier.clickable { onOpen(record) }) {
                val title = runCatching { StressMode.valueOf(record.mode).title }.getOrDefault(record.mode)
                Field(title, Durations.clock(record.loadSeconds))
                Field("Tepe · sürekli", "${Format.watts(record.summary.peakWatts)} · ${Format.watts(record.summary.sustainedWatts)}")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onHistory) { Text("Geçmiş") }
            TextButton(onClick = onDiagnostics) { Text("Tanılama") }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** The cinematic modes as one card: what they share, and a chip for each scene. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CinematicCard(modes: List<StressMode>, selected: Boolean, current: StressMode, onPick: (StressMode) -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) StressColors.SurfaceHigh else StressColors.Surface, shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) StressColors.Accent else StressColors.Outline, shape)
            .clickable { onPick(current) }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Sinematik", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "Ekranda bir sahne, CPU tam yükte. Göz için: en yüksek güçten biraz düşük.",
            style = MaterialTheme.typography.bodySmall,
            color = StressColors.TextDim,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            modes.forEach { m ->
                val on = selected && m == current
                Text(
                    m.scene.orEmpty(),
                    modifier = Modifier
                        .background(if (on) StressColors.Accent else StressColors.Surface, RoundedCornerShape(20.dp))
                        .border(1.dp, if (on) StressColors.Accent else StressColors.Outline, RoundedCornerShape(20.dp))
                        .clickable { onPick(m) }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (on) StressColors.Background else StressColors.Text,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
        Text(current.detail, style = MaterialTheme.typography.bodySmall, color = StressColors.TextDim)
    }
}

@Composable
private fun ModeCard(mode: StressMode, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) StressColors.SurfaceHigh else StressColors.Surface, shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) StressColors.Accent else StressColors.Outline, shape)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(mode.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(mode.detail, style = MaterialTheme.typography.bodySmall, color = StressColors.TextDim)
    }
}
