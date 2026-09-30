package dev.ozcan.stress.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.ozcan.stress.run.RunRecord
import dev.ozcan.stress.run.StressDuration
import dev.ozcan.stress.run.StressMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface Screen {
    data object Home : Screen
    data class Run(val mode: StressMode, val duration: StressDuration) : Screen
    data class Result(val record: RunRecord) : Screen
    data object History : Screen
    data object Diagnostics : Screen
}

/** A back stack of screens that survives configuration changes. */
class NavigationViewModel : ViewModel() {
    private val _stack = MutableStateFlow<List<Screen>>(listOf(Screen.Home))
    val stack: StateFlow<List<Screen>> = _stack.asStateFlow()

    fun push(screen: Screen) {
        _stack.value += screen
    }

    /** Replaces the top screen: a finished run gives way to its result. */
    fun replace(screen: Screen) {
        _stack.value = _stack.value.dropLast(1) + screen
    }

    fun pop() {
        if (_stack.value.size > 1) _stack.value = _stack.value.dropLast(1)
    }
}

@Composable
fun AppRoot() {
    val navigation: NavigationViewModel = viewModel()
    val stack by navigation.stack.collectAsStateWithLifecycle()
    val screen = stack.last()

    // A run is stopped from its own screen, never by an accidental back gesture.
    BackHandler(enabled = stack.size > 1 && screen !is Screen.Run) { navigation.pop() }

    when (screen) {
        Screen.Home -> HomeScreen(
            onStart = { mode, duration -> navigation.push(Screen.Run(mode, duration)) },
            onOpen = { navigation.push(Screen.Result(it)) },
            onHistory = { navigation.push(Screen.History) },
            onDiagnostics = { navigation.push(Screen.Diagnostics) },
        )
        is Screen.Run -> RunScreen(
            mode = screen.mode,
            duration = screen.duration,
            onFinished = { navigation.replace(Screen.Result(it)) },
            onLeave = { navigation.pop() },
        )
        is Screen.Result -> ResultScreen(screen.record, onBack = { navigation.pop() })
        Screen.History -> HistoryScreen(onOpen = { navigation.push(Screen.Result(it)) }, onBack = { navigation.pop() })
        Screen.Diagnostics -> DiagnosticsScreen()
    }
}
