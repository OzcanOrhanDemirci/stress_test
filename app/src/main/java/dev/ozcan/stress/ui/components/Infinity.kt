package dev.ozcan.stress.ui.components

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em

/** The sign of an endless duration as the strings write it. */
const val INFINITY = "∞"

/**
 * [text] with every [INFINITY] drawn at the height of the digits around it. The fonts draw the sign at about the height
 * of a lowercase letter, which reads as too small beside "15:00" or "5 dk"; it is enlarged and lowered so it sits
 * centred on the digits, still the font's own crisp glyph.
 */
fun withInfinity(text: String): AnnotatedString = buildAnnotatedString {
    var start = 0
    while (true) {
        val at = text.indexOf(INFINITY, start)
        if (at < 0) {
            append(text.substring(start))
            break
        }
        append(text.substring(start, at))
        withStyle(InfinityStyle) { append(INFINITY) }
        start = at + INFINITY.length
    }
}

// Space Mono's sign spans 0.21 to 0.50 em above the baseline; at 1.6 em its centre is lowered to the digits' (0.35 em).
private val InfinityStyle = SpanStyle(fontSize = 1.6.em, baselineShift = BaselineShift(-0.13f))
