package dev.ozcan.stress.ui.nav

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

/*
 * The one page transition of the app: tab to tab, a page pushed or popped, a simple page to its engineering page.
 * A short fade with a slight horizontal slide; going back plays it in reverse. The sizes of the two pages are not
 * animated, so nothing on them moves up or down.
 */

private const val PAGE_MILLIS = 220

/** The slide is this fraction of the width: enough to show the direction, not a full page sweep. */
private const val PAGE_SLIDE = 12

/** The timing of a page change, also for what changes with it (the tab bar). */
fun <T> pageSpec(): FiniteAnimationSpec<T> = tween(PAGE_MILLIS, easing = FastOutSlowInEasing)

/** [forward]: deeper (a push, the next tab to the right, the engineering page); false for back. */
fun pageTransition(forward: Boolean): ContentTransform {
    val direction = if (forward) 1 else -1
    return ContentTransform(
        slideInHorizontally(pageSpec()) { direction * it / PAGE_SLIDE } + fadeIn(pageSpec()),
        slideOutHorizontally(pageSpec()) { -direction * it / PAGE_SLIDE } + fadeOut(pageSpec()),
        sizeTransform = null,
    )
}
