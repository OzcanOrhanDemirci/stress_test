package dev.ozcan.stress.ui.components

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ozcan.stress.R
import dev.ozcan.stress.safety.SafetyLevel
import dev.ozcan.stress.safety.SafetyLimits
import dev.ozcan.stress.ui.Format
import dev.ozcan.stress.ui.nav.pageTransition
import dev.ozcan.stress.ui.theme.ContentMaxWidth
import dev.ozcan.stress.ui.theme.NumberStyles
import dev.ozcan.stress.ui.theme.StressColors
import dev.ozcan.stress.ui.theme.StressMotion
import dev.ozcan.stress.ui.theme.StressType
import dev.ozcan.stress.ui.upper
import kotlin.math.max
import kotlin.math.min

/*
 * The engineering page and its log (DESIGN "Two pages per screen", "The log's grammar").
 *
 * A screen keeps the page as in-screen state, no navigation route, and moves between its two pages with [TwoPages]:
 *
 *     var engineering by rememberSaveable { mutableStateOf(false) }
 *     TwoPages(engineering, engineeringPage = {
 *         EngineeringPage(title, onBack = { engineering = false }) {
 *             logSection("Thermal", summary = "14 ZONES", note = reasonForMissing) {
 *                 item { LogLine("CPU", Format.number(cpu, 1), "°C", Temperatures.chip(cpu)) }
 *             }
 *             logSection("Power") { item { TimeChart(...) } }   // a chart is an item of its own
 *         }
 *     }) { SimplePage(..., onEngineering = { engineering = true }) }
 *
 * The run screen uses [EngineeringSheet] instead, so the scene keeps running behind it.
 * Lines carry no horizontal padding: the page and the sheet give the 16 dp margin.
 */

/**
 * A screen's simple page, or its engineering page while [engineering]; the change between them is the app's page
 * transition ([pageTransition]). Each page keeps its saved state (its scroll) while the other is shown.
 */
@Composable
fun TwoPages(engineering: Boolean, engineeringPage: @Composable () -> Unit, simplePage: @Composable () -> Unit) {
    val holder = rememberSaveableStateHolder()
    AnimatedContent(
        engineering,
        Modifier.fillMaxSize(),
        transitionSpec = { pageTransition(forward = targetState) },
        label = "two pages",
    ) { shown ->
        holder.SaveableStateProvider(shown) {
            if (shown) engineeringPage() else simplePage()
        }
    }
}

/**
 * A full page for a screen's engineering view: top bar with [title], the `ENGINEERING` caption and a back arrow,
 * then a [LazyColumn] of log items. System back calls [onBack]. [state] lets section shortcuts scroll the list.
 */
@Composable
fun EngineeringPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    BackHandler(onBack = onBack)
    Column(modifier.fillMaxSize().background(StressColors.Background)) {
        TopBar(
            title,
            modifier = Modifier.statusBarsPadding(),
            caption = stringResource(R.string.parts_engineering).upper(),
            onBack = onBack,
        )
        LogList(state, Modifier.weight(1f), WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(), content)
    }
}

/**
 * The run screen's engineering view: a sheet over the lower [heightFraction] of the screen, so the scene keeps
 * running above it. Place it as the last child of the screen's full-size Box and show it with `if (open)`.
 * A tap above the sheet, the close button and system back all call [onDismiss].
 */
@Composable
fun EngineeringSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    heightFraction: Float = 0.62f,
    state: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    Box(modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss),
        )
        AnimatedVisibility(
            shown,
            Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(StressMotion.enter()) { it },
        ) {
            val shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
            Column(
                Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth().fillMaxHeight(heightFraction).clip(shape).background(StressColors.Surface)
                    // Swallow taps on the sheet so they do not reach the dismiss area behind it.
                    .clickable(remember { MutableInteractionSource() }, null) {},
            ) {
                Hairline()
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 4.dp),
                    Arrangement.spacedBy(8.dp),
                    Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.parts_engineering).upper(), Modifier.weight(1f),
                        style = StressType.Label, color = StressColors.TextDim,
                    )
                    TopBarAction(Icons.Rounded.Close, stringResource(R.string.parts_close), onDismiss)
                }
                LogList(state, Modifier.weight(1f), WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(), content)
            }
        }
    }
}

@Composable
private fun LogList(state: LazyListState, modifier: Modifier, bottomInset: androidx.compose.ui.unit.Dp, content: LazyListScope.() -> Unit) {
    LazyColumn(
        modifier.fillMaxWidth(),
        state = state,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp + bottomInset),
        content = content,
    )
}

/**
 * A log section: header ([title] in Code `TextDim` uppercase, a 1 dp `Outline` rule to the end, [summary] last),
 * then [content]'s items, then [note], the once-per-section reason for values shown as unavailable, and a gap.
 * [key] keeps item keys unique when the same section can appear twice.
 */
fun LazyListScope.logSection(
    title: String,
    summary: String? = null,
    note: String? = null,
    key: Any = title,
    content: LazyListScope.() -> Unit,
) {
    item(key = "log-section:$key") { LogSectionHeader(title, summary) }
    content()
    if (note != null) item(key = "log-note:$key") { LogNote(note) }
    item(key = "log-gap:$key") { Spacer(Modifier.height(16.dp)) }
}

/** The header row of [logSection], for use outside a lazy list. */
@Composable
fun LogSectionHeader(title: String, summary: String? = null, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 32.dp).padding(top = 8.dp),
        Arrangement.spacedBy(8.dp),
        Alignment.CenterVertically,
    ) {
        Text(title.upper(), style = StressType.Code, color = StressColors.TextDim, maxLines = 1)
        Box(Modifier.weight(1f).height(1.dp).background(StressColors.Outline))
        if (summary != null) Text(summary.upper(), style = StressType.Code, color = StressColors.TextDim, maxLines = 1)
    }
}

/** A section's note: Body small `TextFaint`, under its lines. */
@Composable
fun LogNote(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.fillMaxWidth().padding(vertical = 6.dp), style = StressType.BodySmall, color = StressColors.TextFaint)
}

/** The status tag at the far right of a [LogLine]. */
sealed class LogTag(val color: Color) {
    data object Ok : LogTag(StressColors.Good)
    data object Na : LogTag(StressColors.TextFaint)
    data object Warn : LogTag(StressColors.Warn)
    data object Stop : LogTag(StressColors.Bad)

    /** Any other tag: [text] as given (already uppercase) in [tint]. */
    class Custom(val text: String, tint: Color) : LogTag(tint)

    companion object {
        /** The tag for a safety finding's level. */
        fun of(level: SafetyLevel): LogTag = when (level) {
            SafetyLevel.Ok -> Ok
            SafetyLevel.Warn -> Warn
            SafetyLevel.Stop -> Stop
        }
    }
}

@StringRes
private fun LogTag.label(): Int? = when (this) {
    LogTag.Ok -> R.string.parts_tag_ok
    LogTag.Na -> R.string.parts_tag_na
    LogTag.Warn -> R.string.parts_tag_warn
    LogTag.Stop -> R.string.parts_tag_stop
    is LogTag.Custom -> null
}

/**
 * One value of the log, 32 dp, no box: [key] in Code `TextDim` on the left, [value] right-aligned in Number S and
 * [color] with [unit] in `TextDim`, an optional [tag] in a 40 dp column at the far right (kept empty without one). A null [value] shows [Format.MISSING]
 * faint, with no tag of its own; give the reason once with the section's note. [source] is a faint second line under the key (a sysfs path).
 * A long value wraps rather than squeezing the key: the key never breaks inside a word.
 */
@Composable
fun LogLine(
    key: String,
    value: String?,
    unit: String? = null,
    color: Color = StressColors.Text,
    tag: LogTag? = null,
    source: String? = null,
    modifier: Modifier = Modifier,
) {
    val missing = value == null || value == Format.MISSING
    // The value already says it is missing; an N/A tag beside it would say it twice.
    val shownTag = tag
    Row(
        modifier.fillMaxWidth().heightIn(min = 32.dp).padding(vertical = 4.dp),
        Arrangement.spacedBy(12.dp),
        Alignment.CenterVertically,
    ) {
        val text = buildAnnotatedString {
            if (missing) {
                withStyle(SpanStyle(color = StressColors.TextFaint)) { append(Format.MISSING) }
            } else {
                append(withInfinity(value.orEmpty()))
                if (unit != null) {
                    withStyle(SpanStyle(color = StressColors.TextDim, fontSize = 12.sp)) { append(" $unit") }
                }
            }
        }
        KeyAndValue(key, source, text, color, Modifier.weight(1f))
        // The tag column is always kept so values line up whether or not a line carries a tag.
        Box(Modifier.widthIn(min = 40.dp), contentAlignment = Alignment.CenterEnd) {
            if (shownTag != null) LogTagText(shownTag)
        }
    }
}

/**
 * The key and value of a [LogLine] side by side, 12 dp apart. Both take their full width when they fit. Otherwise the
 * key keeps its own width up to 45 % of the line, and never less than its longest word; the value gets the rest and
 * wraps inside it, right-aligned.
 */
@Composable
private fun KeyAndValue(key: String, source: String?, value: AnnotatedString, color: Color, modifier: Modifier) {
    Layout(
        content = {
            Text(key, style = StressType.Code, color = StressColors.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(value, style = NumberStyles.Small, color = color, textAlign = TextAlign.End)
            if (source != null) {
                Text(source, style = StressType.Code.copy(fontSize = 10.sp, lineHeight = 14.sp), color = StressColors.TextFaint)
            }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val gap = 12.dp.roundToPx()
        val (keyText, valueText) = measurables
        val keyFull = keyText.maxIntrinsicWidth(Constraints.Infinity)
        val keyWord = keyText.minIntrinsicWidth(Constraints.Infinity)
        val valueFull = valueText.maxIntrinsicWidth(Constraints.Infinity)
        val keyKept = max(keyWord, min(keyFull, (width * 0.45f).toInt()))
        val valueWidth = min(valueFull, width - gap - keyKept).coerceAtLeast(min(valueFull, width / 3))
        val keyWidth = (width - gap - valueWidth).coerceAtLeast(0)
        val keyPlaced = keyText.measure(Constraints(maxWidth = keyWidth))
        val valuePlaced = valueText.measure(Constraints(maxWidth = valueWidth))
        val sourcePlaced = measurables.getOrNull(2)?.measure(Constraints(maxWidth = keyWidth))
        val left = keyPlaced.height + (sourcePlaced?.height ?: 0)
        val height = max(max(left, valuePlaced.height), constraints.minHeight)
        layout(width, height) {
            val top = (height - left) / 2
            keyPlaced.place(0, top)
            sourcePlaced?.place(0, top + keyPlaced.height)
            valuePlaced.place(width - valuePlaced.width, (height - valuePlaced.height) / 2)
        }
    }
}

@Composable
private fun LogTagText(tag: LogTag) {
    val text = tag.label()?.let { stringResource(it) } ?: (tag as LogTag.Custom).text
    Text(
        text,
        style = StressType.Code.copy(fontSize = 10.sp, lineHeight = 14.sp), color = tag.color,
        textAlign = TextAlign.End, maxLines = 1,
    )
}

/** An event of a run: [time] (`01:18`), the [tag] (`THROTTLE`) in [color], then the sentence in Body small. */
@Composable
fun LogEvent(time: String, tag: String, color: Color, text: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 32.dp).padding(vertical = 6.dp),
        Arrangement.spacedBy(10.dp),
    ) {
        Text(time, style = StressType.Code, color = StressColors.TextDim, maxLines = 1)
        Text(tag.upper(), style = StressType.Code, color = color, maxLines = 1)
        Text(text, Modifier.weight(1f), style = StressType.BodySmall, color = StressColors.Text)
    }
}

/**
 * Temperature colours by band with the thresholds of [SafetyLimits] (`Text`, `Warn` from the warning limit, `Bad`
 * from the stop limit, `TextDim` when unread). Wraps [StressColors.temperature] so screens need not hold limits.
 */
object Temperatures {
    private val limits = SafetyLimits()

    /** CPU and GPU zones (105 / 110 °C). Other SoC zones are coloured the same way. */
    fun chip(celsius: Double?): Color = StressColors.temperature(celsius, limits.chipWarn, limits.chipStop)

    fun battery(celsius: Double?): Color = StressColors.temperature(celsius, limits.batteryWarn, limits.batteryStop)

    /** The phone's case (skin) sensor. */
    fun skin(celsius: Double?): Color = StressColors.temperature(celsius, limits.skinWarn, limits.skinStop)
}
