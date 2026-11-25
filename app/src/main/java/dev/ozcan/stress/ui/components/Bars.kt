package dev.ozcan.stress.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ozcan.stress.R
import dev.ozcan.stress.ui.theme.StressColors
import dev.ozcan.stress.ui.theme.StressMotion
import dev.ozcan.stress.ui.theme.StressType

/**
 * A title and optional Code [caption]. Supply [brand] for the home wordmark and [actions] for end controls.
 * [supporting] adds a line below the caption block when a screen has a live status or notice.
 */
@Composable
fun TopBar(
    title: String = "",
    modifier: Modifier = Modifier,
    caption: String? = null,
    onBack: (() -> Unit)? = null,
    brand: (@Composable () -> Unit)? = null,
    supporting: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            Arrangement.spacedBy(8.dp),
            Alignment.CenterVertically,
        ) {
            if (onBack != null) TopBarAction(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back), onBack)
            if (brand != null) {
                Box(Modifier.weight(1f)) { brand() }
            } else {
                Column(Modifier.weight(1f)) {
                    Text(title, style = StressType.Title)
                    if (caption != null) BarCaption(caption)
                }
            }
            actions()
        }
        if (brand != null && caption != null) {
            BarCaption(caption, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
        }
        if (supporting != null) Box(Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)) { supporting() }
        Hairline()
    }
}

@Composable
private fun BarCaption(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = StressType.Code, color = StressColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** A 40 dp outlined icon inside a 48 dp touch target. */
@Composable
fun TopBarAction(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.size(48.dp).clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(40.dp).clip(ButtonShape).background(StressColors.SurfaceHigh)
                .border(1.dp, StressColors.OutlineStrong, ButtonShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, label, Modifier.size(20.dp), tint = StressColors.Text)
        }
    }
}

/** A destination in the bottom bar, keyed by the caller's route. */
data class BarDestination<T>(val route: T, val label: String, val icon: ImageVector)

/** A full-width tab bar with its own bottom system inset. */
@Composable
fun <T> BottomBar(destinations: List<BarDestination<T>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().background(StressColors.SurfaceLow)) {
        Hairline()
        Row(Modifier.fillMaxWidth().navigationBarsPadding().selectableGroup()) {
            destinations.forEach { destination ->
                val active = destination.route == selected
                val tint by animateColorAsState(
                    if (active) StressColors.Accent else StressColors.TextDim,
                    StressMotion.state(), label = "destination",
                )
                Column(
                    Modifier.weight(1f).heightIn(min = 56.dp).selectable(active, role = Role.Tab) { onSelect(destination.route) }
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    Arrangement.spacedBy(4.dp, Alignment.CenterVertically), Alignment.CenterHorizontally,
                ) {
                    Icon(destination.icon, null, Modifier.size(20.dp), tint = tint)
                    FitText(destination.label, StressType.Label.copy(letterSpacing = 0.sp),
                        color = tint, textAlign = TextAlign.Center)
                }
            }
        }
    }
}
