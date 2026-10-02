package dev.ozcan.stress.ui.components

import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import dev.ozcan.stress.R
import dev.ozcan.stress.ui.theme.StressColors
import dev.ozcan.stress.ui.upper

/**
 * The app's name as a wordmark: upper case and spaced out, the first word in
 * white and the rest in the accent ("STRESS TEST"). It stays on one line,
 * shrinking down to [minFontSize] where the room is short.
 */
@Composable
fun Wordmark(
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 24.sp,
    minFontSize: TextUnit = 16.sp,
    letterSpacing: TextUnit = 4.sp,
) {
    val name = stringResource(R.string.app_name).upper()
    val split = name.indexOf(' ')
    val text = buildAnnotatedString {
        if (split < 0) {
            append(name)
        } else {
            append(name.substring(0, split))
            withStyle(SpanStyle(color = StressColors.Accent)) { append(name.substring(split)) }
        }
    }
    BasicText(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.headlineSmall.copy(
            color = StressColors.Text,
            fontWeight = FontWeight.Bold,
            letterSpacing = letterSpacing,
        ),
        maxLines = 1,
        autoSize = TextAutoSize.StepBased(minFontSize = minFontSize, maxFontSize = fontSize, stepSize = 1.sp),
    )
}
