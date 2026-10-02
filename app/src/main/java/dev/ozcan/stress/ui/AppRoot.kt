package dev.ozcan.stress.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.ozcan.stress.R
import dev.ozcan.stress.ui.components.AppBackground
import dev.ozcan.stress.ui.device.DeviceScreen
import dev.ozcan.stress.ui.history.HistoryScreen
import dev.ozcan.stress.ui.home.HomeScreen
import dev.ozcan.stress.ui.nav.NavEntry
import dev.ozcan.stress.ui.nav.Navigator
import dev.ozcan.stress.ui.nav.Route
import dev.ozcan.stress.ui.result.ResultScreen
import dev.ozcan.stress.ui.run.RunScreen
import dev.ozcan.stress.ui.settings.SettingsScreen
import dev.ozcan.stress.ui.theme.StressColors

/** Bottom bar height plus its margin: what a tab's content leaves free at the bottom. */
val BottomBarSpace = 96.dp

@Composable
fun AppRoot() {
    val navigator: Navigator = viewModel()
    val holder = rememberSaveableStateHolder()
    val top = navigator.top

    // A run is stopped from its own screen, never by an accidental back gesture.
    BackHandler(enabled = top.route !is Route.Run && (navigator.stack.isNotEmpty() || navigator.tab != Route.Tab.Test)) {
        navigator.back()
    }

    AppBackground {
        AnimatedContent(
            targetState = top,
            transitionSpec = { transition(navigator.forward, initialState, targetState) },
            contentKey = { it.id },
            label = "screens",
        ) { entry ->
            // The entry's view models live while it is reachable; once it has left
            // the stack and this screen has left the composition, they are cleared.
            DisposableEffect(entry) {
                onDispose {
                    if (!navigator.isLive(entry)) {
                        entry.viewModelStore.clear()
                        holder.removeState(entry.id)
                    }
                }
            }
            CompositionLocalProvider(LocalViewModelStoreOwner provides entry) {
                holder.SaveableStateProvider(entry.id) {
                    Screen(entry, navigator)
                }
            }
        }

        AnimatedVisibility(
            visible = navigator.stack.isEmpty(),
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            BottomBar(navigator.tab, navigator::select)
        }
    }
}

@Composable
private fun Screen(entry: NavEntry, navigator: Navigator) {
    when (val route = entry.route) {
        Route.Tab.Test -> HomeScreen(
            onStart = { mode, duration -> navigator.push(Route.Run(mode, duration)) },
            onOpenRun = { navigator.push(Route.Result(it)) },
            onOpenSettings = { navigator.select(Route.Tab.Settings) },
        )
        Route.Tab.History -> HistoryScreen(onOpen = { navigator.push(Route.Result(it)) })
        Route.Tab.Settings -> SettingsScreen(onOpenDevice = { navigator.push(Route.Device) })
        is Route.Run -> RunScreen(
            mode = route.mode,
            duration = route.duration,
            onFinished = { navigator.replace(Route.Result(it)) },
            onLeave = { navigator.back() },
        )
        is Route.Result -> ResultScreen(route.runId, onBack = { navigator.back() })
        Route.Device -> DeviceScreen(onBack = { navigator.back() })
    }
}

/** Deeper screens slide in from the right; tabs cross-fade; a run rises from below. */
private fun transition(forward: Boolean, from: NavEntry, to: NavEntry): ContentTransform {
    val spec = tween<Float>(320, easing = FastOutSlowInEasing)
    val slide = tween<androidx.compose.ui.unit.IntOffset>(340, easing = FastOutSlowInEasing)
    return when {
        from.route is Route.Tab && to.route is Route.Tab -> fadeIn(spec) togetherWith fadeOut(spec)
        to.route is Route.Run -> (fadeIn(spec) + scaleIn(tween(380), initialScale = 0.94f)) togetherWith fadeOut(spec)
        forward -> (slideInHorizontally(slide) { it / 3 } + fadeIn(spec)) togetherWith
            (slideOutHorizontally(slide) { -it / 6 } + fadeOut(spec))
        else -> (slideInHorizontally(slide) { -it / 6 } + fadeIn(spec)) togetherWith
            (slideOutHorizontally(slide) { it / 3 } + fadeOut(spec))
    }
}

@Composable
private fun BottomBar(selected: Route.Tab, onSelect: (Route.Tab) -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Row(
        modifier = Modifier
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .fillMaxWidth()
            .height(68.dp)
            .shadow(24.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(StressColors.SurfaceHighest, StressColors.SurfaceHigh)))
            .border(1.dp, Brush.verticalGradient(listOf(StressColors.OutlineBright, StressColors.Outline)), shape)
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TabItem(Icons.Rounded.Speed, stringResource(R.string.tab_test), selected == Route.Tab.Test) { onSelect(Route.Tab.Test) }
        TabItem(Icons.Rounded.History, stringResource(R.string.tab_history), selected == Route.Tab.History) { onSelect(Route.Tab.History) }
        TabItem(Icons.Rounded.Settings, stringResource(R.string.tab_settings), selected == Route.Tab.Settings) { onSelect(Route.Tab.Settings) }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.TabItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val fill by animateFloatAsState(if (selected) 1f else 0f, tween(260), label = "tab")
    val tint by animateColorAsState(if (selected) Color.White else StressColors.TextDim, tween(260), label = "tint")
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxSize()
            .clip(shape)
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = fill
                    scaleX = 0.85f + 0.15f * fill
                    scaleY = 0.85f + 0.15f * fill
                }
                .background(Brush.horizontalGradient(listOf(StressColors.Accent, StressColors.AccentHot)), shape),
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
        }
    }
}
