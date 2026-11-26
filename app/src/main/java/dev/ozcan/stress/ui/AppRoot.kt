package dev.ozcan.stress.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeveloperBoard
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.ozcan.stress.R
import dev.ozcan.stress.graph
import dev.ozcan.stress.run.StressMode
import dev.ozcan.stress.ui.charge.ChargeRunScreen
import dev.ozcan.stress.ui.compare.CompareScreen
import dev.ozcan.stress.ui.components.BarDestination
import dev.ozcan.stress.ui.components.BottomBar
import dev.ozcan.stress.ui.device.DeviceScreen
import dev.ozcan.stress.ui.history.HistoryScreen
import dev.ozcan.stress.ui.home.HomeScreen
import dev.ozcan.stress.ui.nav.NavEntry
import dev.ozcan.stress.ui.nav.Navigator
import dev.ozcan.stress.ui.nav.Route
import dev.ozcan.stress.ui.nav.pageSpec
import dev.ozcan.stress.ui.nav.pageTransition
import dev.ozcan.stress.ui.result.ResultScreen
import dev.ozcan.stress.ui.run.RunScreen
import dev.ozcan.stress.ui.settings.SettingsScreen
import dev.ozcan.stress.ui.theme.ContentMaxWidth
import kotlinx.coroutines.flow.StateFlow

/** [openRun]: a run to show on top, from a notification; [onOpened] clears it once handled. */
@Composable
fun AppRoot(openRun: StateFlow<String?>, onOpened: () -> Unit) {
    val navigator: Navigator = viewModel()
    val requested by openRun.collectAsStateWithLifecycle()
    LaunchedEffect(requested) {
        val id = requested ?: return@LaunchedEffect
        // A test under way keeps the screen; the run is in History. Already shown, it is not opened twice.
        val shown = navigator.top.route
        if (shown !is Route.Run && shown != Route.Result(id)) navigator.push(Route.Result(id))
        onOpened()
    }
    val holder = rememberSaveableStateHolder()
    val top = navigator.top
    val showTabs = top.route is Route.Tab

    // A run is stopped from its own screen, never by an accidental back gesture.
    BackHandler(enabled = top.route !is Route.Run && (navigator.stack.isNotEmpty() || navigator.tab != Route.Tab.Test)) {
        navigator.back()
    }

    // The bar lies over the screens rather than above them in a column: a screen keeps its height while the bar
    // comes and goes, so nothing on it moves when a page is pushed over the tabs or popped back to them.
    TabsLayout(
        bar = {
            AnimatedVisibility(showTabs, enter = fadeIn(pageSpec()), exit = fadeOut(pageSpec())) {
                BottomBar(
                    destinations = listOf(
                        BarDestination(Route.Tab.Test, stringResource(R.string.tab_test), Icons.Rounded.Speed),
                        BarDestination(Route.Tab.History, stringResource(R.string.tab_history), Icons.Rounded.History),
                        BarDestination(Route.Tab.Device, stringResource(R.string.tab_device), Icons.Rounded.DeveloperBoard),
                        BarDestination(Route.Tab.Settings, stringResource(R.string.tab_settings), Icons.Rounded.Settings),
                    ),
                    selected = navigator.tab,
                    onSelect = navigator::select,
                )
            }
        },
    ) { barHeight ->
        AnimatedContent(
            targetState = top,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = { pageTransition(navigator.forward) },
            contentKey = { it.id },
            label = "screens",
        ) { entry ->
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
                    // A tab ends above the bar, which takes the system's bottom inset; a page pushed over the tabs
                    // takes the whole height. Decided per screen, so the one leaving keeps its own layout.
                    val insets = if (entry.route is Route.Tab) {
                        Modifier.padding(bottom = barHeight).consumeWindowInsets(WindowInsets.navigationBars)
                    } else {
                        Modifier
                    }
                    // On a tablet the screen's content keeps a phone-like width, centred; the ground behind it
                    // and the bottom bar still fill the window. A GPU run's surface fills it too, since its size is
                    // part of the GPU's work; that run caps its own panels. Runs without a surface are narrowed.
                    val fullWidth = (entry.route as? Route.Run)?.mode?.usesGpu == true
                    val width = if (fullWidth) Modifier else Modifier.widthIn(max = ContentMaxWidth)
                    Box(Modifier.fillMaxSize().then(insets), contentAlignment = Alignment.TopCenter) {
                        Box(width.fillMaxSize()) {
                            Screen(entry, navigator)
                        }
                    }
                }
            }
        }
    }

    StartupNotice()
}

/**
 * The safety notice, once each time the app is opened: saved with the
 * activity, so it does not come back when Android recreates the activity,
 * and only if it was on when the app opened.
 */
@Composable
private fun StartupNotice() {
    val store = context().graph.settings
    val settings by store.settings.collectAsStateWithLifecycle()
    var done by rememberSaveable { mutableStateOf(!store.current.startupNotice) }
    if (!done) {
        SafetyNotice(safetyOn = settings.deviceSafety) { dontShowAgain ->
            done = true
            if (dontShowAgain) store.update { it.copy(startupNotice = false) }
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
        Route.Tab.History -> HistoryScreen(
            onOpen = { navigator.push(Route.Result(it)) },
            onCompare = { first, second -> navigator.push(Route.Compare(first, second)) },
        )
        Route.Tab.Settings -> SettingsScreen()
        Route.Tab.Device -> DeviceScreen()
        is Route.Run -> if (route.mode == StressMode.Charge) ChargeRunScreen(
            duration = route.duration,
            onFinished = { navigator.replace(Route.Result(it)) },
            onLeave = { navigator.back() },
        ) else RunScreen(
            mode = route.mode,
            duration = route.duration,
            onFinished = { navigator.replace(Route.Result(it)) },
            onLeave = { navigator.back() },
        )
        is Route.Result -> ResultScreen(
            route.runId,
            onBack = { navigator.back() },
            onCompare = { other -> navigator.push(Route.Compare(route.runId, other)) },
        )
        is Route.Compare -> CompareScreen(route.first, route.second, onBack = { navigator.back() })
    }
}

/**
 * The screens, with [bar] over their bottom edge. The bar is measured first, so a tab gets its height in the same
 * frame and never draws once without it.
 */
@Composable
private fun TabsLayout(bar: @Composable () -> Unit, content: @Composable (barHeight: Dp) -> Unit) {
    SubcomposeLayout(Modifier.fillMaxSize()) { constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val bars = subcompose(TabsSlot.Bar, bar).map { it.measure(loose) }
        val barHeight = bars.maxOfOrNull { it.height } ?: 0
        val screens = subcompose(TabsSlot.Screens) { content(barHeight.toDp()) }.map { it.measure(constraints) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            screens.forEach { it.place(0, 0) }
            bars.forEach { it.place(0, constraints.maxHeight - it.height) }
        }
    }
}

private enum class TabsSlot { Bar, Screens }
