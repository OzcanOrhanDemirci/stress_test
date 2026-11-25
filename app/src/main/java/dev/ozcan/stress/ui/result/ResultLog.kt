package dev.ozcan.stress.ui.result

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ozcan.stress.R
import dev.ozcan.stress.device.DeviceInfo
import dev.ozcan.stress.run.RunRecord
import dev.ozcan.stress.ui.Dates
import dev.ozcan.stress.ui.Format
import dev.ozcan.stress.ui.components.LogEvent
import dev.ozcan.stress.ui.components.LogLine
import dev.ozcan.stress.ui.components.LogTag
import dev.ozcan.stress.ui.components.Panel
import dev.ozcan.stress.ui.components.logSection
import dev.ozcan.stress.ui.theme.StressColors
import dev.ozcan.stress.ui.theme.StressType
import dev.ozcan.stress.ui.upper

/*
 * The result's engineering page as data: sections of lines built from a record by plain functions, so the simple
 * page can count the values ("42 VALUES") without composing the log, and the result and the charge result share
 * one renderer.
 */

internal sealed interface LogEntry {
    /** A [dev.ozcan.stress.ui.components.LogLine]; a null [value] is shown as unavailable. */
    data class Line(
        val key: String,
        val value: String?,
        val unit: String? = null,
        val color: Color = StressColors.Text,
        val tag: LogTag? = null,
        val source: String? = null,
    ) : LogEntry

    /** A sentence that belongs to the line above it (an insight's explanation) or stands alone (a finding). */
    data class Sentence(val text: String, val color: Color = StressColors.TextDim) : LogEntry

    data class Event(val time: String, val tag: String, val color: Color, val text: String) : LogEntry

    /** Anything drawn rather than listed: a chart, a notice. */
    class Block(val content: @Composable () -> Unit) : LogEntry
}

internal class LogBlock(
    val title: String,
    val entries: List<LogEntry>,
    val summary: String? = null,
    val note: String? = null,
)

/** How many values the engineering row announces: the log's lines. */
internal fun List<LogBlock>.valueCount(): Int = sumOf { block -> block.entries.count { it is LogEntry.Line } }

internal fun LazyListScope.logBlocks(blocks: List<LogBlock>) {
    blocks.forEachIndexed { index, block ->
        logSection(block.title, block.summary, block.note, key = index) {
            itemsIndexed(block.entries, key = { i, _ -> "log:$index:$i" }) { _, entry -> LogEntryView(entry) }
        }
    }
}

@Composable
private fun LogEntryView(entry: LogEntry) {
    when (entry) {
        is LogEntry.Line -> LogLine(entry.key, entry.value, entry.unit, entry.color, entry.tag, entry.source)
        is LogEntry.Sentence -> Text(
            entry.text, Modifier.fillMaxWidth().padding(bottom = 6.dp), style = StressType.BodySmall, color = entry.color,
        )
        is LogEntry.Event -> LogEvent(entry.time, entry.tag, entry.color, entry.text)
        is LogEntry.Block -> Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) { entry.content() }
    }
}

/** A simple page's one sentence of verdict, under a small square in its [color]. */
@Composable
internal fun VerdictPanel(text: String, color: Color) {
    Panel(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).background(color))
                Text(stringResource(R.string.result_verdict).upper(), style = StressType.Label, color = StressColors.TextDim)
            }
            Text(text, style = StressType.Body, color = StressColors.Text)
        }
    }
}

/** The phone and the app that measured the run, shared by both result pages. */
internal fun deviceBlock(context: Context, record: RunRecord): LogBlock? {
    val device: DeviceInfo = record.device ?: return null
    val lines = buildList {
        add(LogEntry.Line(context.getString(R.string.result_log_phone), device.title))
        add(LogEntry.Line(context.getString(R.string.device_soc), device.soc))
        device.cpuClusters.forEach { add(LogEntry.Line(context.getString(R.string.device_cpu), it)) }
        add(LogEntry.Line(context.getString(R.string.device_cores), device.cpuCount.toString()))
        add(LogEntry.Line(context.getString(R.string.device_gpu), device.gpu))
        add(LogEntry.Line("Vulkan", device.vulkan))
        add(LogEntry.Line(context.getString(R.string.device_android), "${device.androidVersion} (API ${device.sdk})"))
        add(LogEntry.Line(context.getString(R.string.device_ram), Format.number(device.ramGigabytes, 1).takeIf { device.ramGigabytes != null }, "GB"))
        add(LogEntry.Line(context.getString(R.string.device_display), device.display))
        add(LogEntry.Line(context.getString(R.string.device_app), record.appVersion))
    }
    val missing = lines.any { it.value == null }
    return LogBlock(
        context.getString(R.string.result_device), lines, summary = device.model,
        note = context.getString(R.string.result_log_device_missing).takeIf { missing },
    )
}

/** What the record itself holds, last on the page: the raw identity of the run. */
internal fun recordBlock(context: Context, record: RunRecord): LogBlock = LogBlock(
    context.getString(R.string.result_log_record),
    listOf(
        LogEntry.Line(context.getString(R.string.result_log_id), record.id),
        LogEntry.Line(context.getString(R.string.result_log_mode_key), record.mode),
        LogEntry.Line(context.getString(R.string.result_log_workload), record.workload),
        LogEntry.Line(context.getString(R.string.result_log_started), Dates.long(record.startedAtMillis)),
        LogEntry.Line(context.getString(R.string.result_log_samples), record.series.seconds.size.toString()),
    ),
)
