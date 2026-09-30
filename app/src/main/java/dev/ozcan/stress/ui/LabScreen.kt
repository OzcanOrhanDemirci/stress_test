package dev.ozcan.stress.ui

import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.ozcan.stress.AppGraph
import dev.ozcan.stress.graph
import dev.ozcan.stress.lab.LabRunner
import dev.ozcan.stress.lab.LabSpec
import dev.ozcan.stress.lab.LabState
import dev.ozcan.stress.ui.theme.StressColors
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LabViewModel(graph: AppGraph, spec: LabSpec) : ViewModel() {

    private val runner = graph.labRunner()
    val state: StateFlow<LabState?> = runner.state

    val live: StateFlow<LiveView?> = graph.sampler.latest
        .map { LiveView.from(graph.sampler.log.recent(DiagnosticsViewModel.LIVE_WINDOW_SAMPLES), graph.layout) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(1_000), null)

    init {
        viewModelScope.launch { runner.run(spec) }
    }
}

/** A dark, almost empty screen: a lab run should measure the load, not the display. */
@Composable
fun LabScreen(spec: Result<LabSpec>) {
    val parsed = spec.getOrElse { error ->
        LaunchedEffect(error) { Log.e(LabRunner.LOG_TAG, "error spec=${error.message}") }
        LabText("Geçersiz lab komutu: ${error.message}", StressColors.Bad)
        return
    }
    val context = LocalContext.current
    val model: LabViewModel = viewModel { LabViewModel(context.graph, parsed) }
    val state by model.state.collectAsStateWithLifecycle()
    val live by model.live.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(StressColors.Background)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        LabText("LAB · ${parsed.runCount} koşu · ${parsed.loadSeconds} sn yük", StressColors.TextDim)
        when (val s = state) {
            null -> LabText("Hazırlanıyor", StressColors.TextDim)
            LabState.WaitingForBattery -> LabText("Şarj kablosunu çıkar: ölçüm pilde yapılır.", StressColors.Warn)
            is LabState.Cooling -> {
                LabText("${s.run + 1}/${s.runs} · soğuma bekleniyor", StressColors.Text)
                LabText("CPU ${Format.celsius(s.hottest)} → ${Format.celsius(s.limit)}", StressColors.TextDim)
            }
            is LabState.Measuring -> {
                val remaining = ((s.endsAtNanos - SystemClock.elapsedRealtimeNanos()) / 1e9).coerceAtLeast(0.0)
                val phase = if (s.phase == LabState.Phase.Idle) "boşta ölçüm" else "yük"
                LabText("${s.run + 1}/${s.runs} · ${s.assignment}", StressColors.Text)
                LabText("$phase · ${Format.number(remaining, 0)} sn", StressColors.Text)
                LabText(Format.watts(live?.watts), StressColors.CherenkovDim)
            }
            is LabState.Finished -> {
                LabText("bitti · ${s.results.size} koşu", StressColors.Good)
                s.results.forEach { r ->
                    LabText(
                        "${r.runIndex + 1}. ${r.assignment} · ${Format.watts(r.load.meanWatts)} · hata ${r.computationErrors}",
                        StressColors.TextDim,
                    )
                }
            }
            is LabState.Failed -> LabText(s.message, StressColors.Bad)
        }
    }
}

@Composable
private fun LabText(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(text, color = color, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
}
