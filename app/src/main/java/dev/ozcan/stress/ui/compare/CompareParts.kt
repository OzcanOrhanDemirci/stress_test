package dev.ozcan.stress.ui.compare

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ozcan.stress.R
import dev.ozcan.stress.run.RunRecord
import dev.ozcan.stress.safety.SafetyReason
import dev.ozcan.stress.ui.Dates
import dev.ozcan.stress.ui.Labels
import dev.ozcan.stress.ui.Format
import dev.ozcan.stress.ui.components.CodeTag
import dev.ozcan.stress.ui.components.Hairline
import dev.ozcan.stress.ui.components.LogLine
import dev.ozcan.stress.ui.components.LogTag
import dev.ozcan.stress.ui.components.MetricNumber
import dev.ozcan.stress.ui.components.Panel
import dev.ozcan.stress.ui.theme.NumberStyles
import dev.ozcan.stress.ui.theme.StressColors
import dev.ozcan.stress.ui.theme.StressType
import kotlin.math.abs

/*
 * Parts shared by the load and the charge comparison (simple page and engineering log). The log's own lines hold
 * one value; a comparison needs two and their change, so [CompareLine] is the same grammar with an A and a B column.
 */

/** The same run colours identify the records, table columns and chart lines. */
internal val ColorA = StressColors.Accent
internal val ColorB = StressColors.Cool

/** Changes smaller than this fraction of A read as "the same". */
internal const val SAME_FRACTION = 0.02

/** B against A as a fraction of A, as [dev.ozcan.stress.analysis.Comparison.Row.change] does it. */
internal fun change(a: Double?, b: Double?): Double? = if (a != null && b != null && a != 0.0) (b - a) / a else null

/** A change's colour: `Good` when it went the better way, `Warn` the worse, `TextDim` when too small or unknown. */
internal fun changeColor(change: Double?, higherIsBetter: Boolean): Color = when {
    change == null || abs(change) < SAME_FRACTION -> StressColors.TextDim
    (change > 0) == higherIsBetter -> StressColors.Good
    else -> StressColors.Warn
}

/** "+12%", "-%4" (the sign the way [Format.percent] places it), "—". */
internal fun signedPercent(change: Double?): String =
    change?.let { (if (it > 0 && abs(it) >= 0.005) "+" else "") + Format.percent(it) } ?: Format.MISSING

/** [text] (already formatted from [value]) with a "+" in front when the value is positive. */
internal fun signed(value: Double, text: String): String = if (value > 0 && text.any { it in '1'..'9' }) "+$text" else text

/** A row of the simple page's side-by-side panel: both values as number and unit, and B minus A in its colour. */
internal class SideRow(
    val label: String,
    val a: String,
    val b: String,
    val unit: String?,
    val difference: String,
    val differenceColor: Color,
)

/** Builds a [SideRow] from two readings; [number] formats a value without its unit, also a signed difference. */
internal fun sideRow(
    label: String,
    a: Double?,
    b: Double?,
    unit: String?,
    higherIsBetter: Boolean,
    number: (Double) -> String,
): SideRow {
    val difference = if (a != null && b != null) {
        val d = b - a
        signed(d, number(d)) + (unit?.let { " $it" } ?: "")
    } else {
        Format.MISSING
    }
    return SideRow(
        label,
        a?.let(number) ?: Format.MISSING,
        b?.let(number) ?: Format.MISSING,
        unit,
        difference,
        changeColor(change(a, b), higherIsBetter),
    )
}

/** The two headers of a comparison: a flat panel each, the run's letter in its colour, its name and lines. */
@Composable
internal fun RunHeaders(a: RunHeader, b: RunHeader) {
    Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
        RunHeaderPanel(a, ColorA, "A", Modifier.weight(1f))
        RunHeaderPanel(b, ColorB, "B", Modifier.weight(1f))
    }
}

internal class RunHeader(val title: String, val lines: List<String>)

@Composable
private fun RunHeaderPanel(header: RunHeader, color: Color, letter: String, modifier: Modifier) {
    Panel(modifier, padding = 12.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CodeTag(letter, color)
            Text(header.title, style = StressType.Heading, maxLines = 2, overflow = TextOverflow.Ellipsis)
            header.lines.forEach {
                Text(it, style = StressType.Code, color = StressColors.TextDim)
            }
        }
    }
}

/** The simple page's readout of the headline numbers: label, A, B, and the difference, rows split by hairlines. */
@Composable
internal fun SideBySide(rows: List<SideRow>) {
    Panel(Modifier.fillMaxWidth(), padding = 12.dp) {
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), Arrangement.spacedBy(6.dp)) {
            Box(Modifier.weight(1.2f))
            HeaderCell("A", ColorA, Modifier.weight(1f))
            HeaderCell("B", ColorB, Modifier.weight(1f))
            HeaderCell(stringResource(R.string.compare_difference), StressColors.TextDim, Modifier.weight(1f))
        }
        rows.forEach { row ->
            Hairline()
            Row(
                Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(vertical = 6.dp),
                Arrangement.spacedBy(6.dp),
                Alignment.CenterVertically,
            ) {
                Text(row.label, Modifier.weight(1.2f), style = StressType.BodySmall, color = StressColors.TextDim)
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    MetricNumber(row.a, row.unit, NumberStyles.Small, ColorA)
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    MetricNumber(row.b, row.unit, NumberStyles.Small, ColorB)
                }
                Text(
                    row.difference, Modifier.weight(1f), style = StressType.Code, color = row.differenceColor,
                    textAlign = TextAlign.End, maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun HeaderCell(text: String, color: Color, modifier: Modifier) {
    Text(text.uppercase(), modifier, style = StressType.Code, color = color, textAlign = TextAlign.End, maxLines = 1)
}

/** A compared value of the log: [key], A and B in their colours, the change from A to B. */
internal class CompareValue(val key: String, val a: String, val b: String, val change: String, val changeColor: Color)

private val ValueColumn = 80.dp
private val ChangeColumn = 48.dp

/**
 * One [CompareValue] in the log's grammar: Code key, Number S values right-aligned, the change as a tag column.
 * On a narrow screen (under 320 dp of content) the key takes its own line above the values, so it never breaks
 * mid-word or loses its end beside three columns.
 */
@Composable
internal fun CompareLine(value: CompareValue) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val narrow = maxWidth < 320.dp
        Column(Modifier.fillMaxWidth().heightIn(min = 32.dp).padding(vertical = 4.dp)) {
            if (narrow) Text(value.key, style = StressType.Code, color = StressColors.TextDim)
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {
                if (narrow) {
                    Box(Modifier.weight(1f))
                } else {
                    Text(value.key, Modifier.weight(1f), style = StressType.Code, color = StressColors.TextDim, maxLines = 3)
                }
                CompareCell(value.a, ColorA)
                CompareCell(value.b, ColorB)
                Text(value.change, Modifier.width(ChangeColumn), style = StressType.Code, color = value.changeColor, textAlign = TextAlign.End, maxLines = 1)
            }
        }
    }
}

@Composable
private fun CompareCell(text: String, color: Color) {
    val missing = text == Format.MISSING
    Text(
        text, Modifier.width(ValueColumn), style = NumberStyles.Small,
        color = if (missing) StressColors.TextFaint else color, textAlign = TextAlign.End, maxLines = 2,
    )
}

/** The column heads above a block of [CompareLine]s. */
@Composable
internal fun CompareHead() {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f))
        Text("A", Modifier.width(ValueColumn), style = StressType.Code, color = ColorA, textAlign = TextAlign.End)
        Text("B", Modifier.width(ValueColumn), style = StressType.Code, color = ColorB, textAlign = TextAlign.End)
        Text("Δ", Modifier.width(ChangeColumn), style = StressType.Code, color = StressColors.TextDim, textAlign = TextAlign.End)
    }
}

/** A setting or fact of one run in the log: a plain [LogLine] once rendered. */
internal class RunLine(
    val key: String,
    val value: String?,
    val color: Color = StressColors.Text,
    val tag: LogTag? = null,
    val source: String? = null,
    val unit: String? = null,
)

/** Lines every run shares, whatever it ran: when, how long, how it ended, the battery, device and app. */
internal fun commonRunLines(context: Context, record: RunRecord): List<RunLine> {
    val summary = record.summary
    val reason = Labels.endReasonOf(record)
    val safetyFinding = SafetyReason.entries.firstOrNull { it.name == record.safetyReason }
        ?.let { Labels.safetyFinding(context, it, record.safetyValue) }
    val battery = if (summary.batteryStartPercent == null && summary.batteryEndPercent == null) {
        null
    } else {
        "${Format.percentValue(summary.batteryStartPercent)} → ${Format.percentValue(summary.batteryEndPercent)}"
    }
    val device = record.device
    return listOf(
        RunLine(context.getString(R.string.compare_date), Dates.long(record.startedAtMillis)),
        RunLine(
            context.getString(R.string.compare_planned),
            record.plannedSeconds?.let { Format.clock(it.toDouble()) } ?: context.getString(R.string.duration_endless),
        ),
        RunLine(context.getString(R.string.report_duration), Format.clock(record.loadSeconds)),
        RunLine(
            context.getString(R.string.report_end), context.getString(Labels.endReason(reason)),
            Labels.endReasonColor(reason), source = safetyFinding,
        ),
        RunLine(context.getString(R.string.result_battery), battery),
        RunLine(
            context.getString(R.string.report_device),
            device?.let { "${it.manufacturer} ${it.model}" },
            source = device?.soc,
        ),
        RunLine(context.getString(R.string.device_app), record.appVersion),
    )
}

/** Renders [lines] as log items; [key] keeps item keys apart between the two runs. */
internal fun LazyListScope.logRunLines(key: String, lines: List<RunLine>) {
    lines.forEachIndexed { i, line ->
        item(key = "$key-$i") { LogLine(line.key, line.value, line.unit, line.color, tag = line.tag, source = line.source) }
    }
}
