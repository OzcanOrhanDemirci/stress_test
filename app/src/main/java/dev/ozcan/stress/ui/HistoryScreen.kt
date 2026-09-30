package dev.ozcan.stress.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ozcan.stress.graph
import dev.ozcan.stress.run.RunRecord
import dev.ozcan.stress.run.StressMode
import dev.ozcan.stress.ui.theme.StressColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(onOpen: (RunRecord) -> Unit, onBack: () -> Unit) {
    val store = LocalContext.current.graph.runs
    val scope = rememberCoroutineScope()
    var runs by remember { mutableStateOf<List<RunRecord>?>(null) }
    var deleting by remember { mutableStateOf<RunRecord?>(null) }
    val format = remember { SimpleDateFormat("d MMM · HH:mm", Locale.forLanguageTag("tr-TR")) }

    // Bumped after a delete to read the list again.
    var version by remember { mutableIntStateOf(0) }
    LaunchedEffect(version) { runs = withContext(Dispatchers.IO) { store.list() } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Geçmiş", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        val list = runs
        when {
            list == null -> Text("Yükleniyor…", color = StressColors.TextDim)
            list.isEmpty() -> Text("Henüz koşu yok.", color = StressColors.TextDim)
            else -> list.forEach { record ->
                val title = runCatching { StressMode.valueOf(record.mode).title }.getOrDefault(record.mode)
                Panel(
                    "${format.format(Date(record.startedAtMillis))} · $title",
                    Modifier.clickable { onOpen(record) },
                ) {
                    Field("Süre", Durations.clock(record.loadSeconds))
                    Field("Tepe · sürekli", "${Format.watts(record.summary.peakWatts)} · ${Format.watts(record.summary.sustainedWatts)}")
                    Field("En sıcak", Format.celsius(record.summary.maxTemperatures.values.maxOrNull()))
                    if (record.summary.computationErrors > 0) {
                        Field("Hesap hatası", record.summary.computationErrors.toString(), StressColors.Bad)
                    }
                    TextButton(onClick = { deleting = record }) { Text("Sil", color = StressColors.TextDim) }
                }
            }
        }
        TextButton(onClick = onBack) { Text("Geri") }
    }

    deleting?.let { record ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Koşu silinsin mi?") },
            text = { Text("${format.format(Date(record.startedAtMillis))} kaydı kalıcı olarak silinecek.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch {
                        withContext(Dispatchers.IO) { store.delete(record.id) }
                        version++
                    }
                }) { Text("Sil") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Vazgeç") } },
        )
    }
}
