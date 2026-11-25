package dev.ozcan.stress.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ozcan.stress.R
import dev.ozcan.stress.ui.theme.StressColors
import dev.ozcan.stress.ui.theme.StressType
import dev.ozcan.stress.ui.upper

/** The two-tone Stress Test name and its orange mark, with an optional [version] beside it. */
@Composable
fun Wordmark(modifier: Modifier = Modifier, fontSize: TextUnit = 20.sp, version: String? = null) {
    val name = stringResource(R.string.app_name).upper()
    val text = buildAnnotatedString {
        append(name.substringBefore(' '))
        if (' ' in name) {
            append(" ")
            withStyle(SpanStyle(color = StressColors.Accent)) { append(name.substringAfter(' ')) }
        }
    }
    Row(modifier, Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {
        WordmarkBar()
        BasicText(
            text,
            modifier = Modifier.weight(1f, fill = false),
            style = StressType.Title.copy(
                color = StressColors.Text,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.25).sp,
            ),
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = fontSize, stepSize = 1.sp),
        )
        if (version != null) Text(version, style = StressType.Code, color = StressColors.TextDim, maxLines = 1)
    }
}

@Composable
internal fun WordmarkBar(modifier: Modifier = Modifier) {
    Box(modifier.size(width = 4.dp, height = 16.dp).background(StressColors.Accent))
}
