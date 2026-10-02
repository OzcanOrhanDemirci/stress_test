package dev.ozcan.stress.ui.nav

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import dev.ozcan.stress.run.StressDuration
import dev.ozcan.stress.run.StressMode

/** Where the app can be. The three tabs sit under the screens pushed over them. */
sealed interface Route {
    enum class Tab : Route { Test, History, Settings }

    data class Run(val mode: StressMode, val duration: StressDuration) : Route
    data class Result(val runId: String) : Route
    data class Compare(val first: String, val second: String) : Route
    data object Device : Route
}

/**
 * One visit to a [Route]. It owns the view models created while it is shown,
 * which are cleared once it has left the stack and its screen has left the
 * composition (after the exit animation, which still draws it): a run's view
 * model dies with its screen, so the next run of the same test starts fresh.
 */
class NavEntry(val id: Long, val route: Route) : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
}

/**
 * The app's navigation: the selected tab and the stack of screens over it.
 * Kept in a view model, so it survives configuration changes.
 */
class Navigator : ViewModel() {
    private var nextId = 1L

    private val tabEntries = Route.Tab.entries.associateWith { NavEntry(nextId++, it) }

    var tab by mutableStateOf(Route.Tab.Test)
        private set

    /** Screens over the tabs, bottom first. */
    val stack = mutableStateListOf<NavEntry>()

    /** True for the last change that went deeper (push), false for one back (pop): picks the transition. */
    var forward by mutableStateOf(true)
        private set

    val top: NavEntry get() = stack.lastOrNull() ?: tabEntries.getValue(tab)

    fun select(tab: Route.Tab) {
        forward = tab.ordinal >= this.tab.ordinal
        this.tab = tab
    }

    fun push(route: Route) {
        forward = true
        stack += NavEntry(nextId++, route)
    }

    /** Replaces the top screen: a finished run gives way to its result. */
    fun replace(route: Route) {
        forward = true
        stack.removeLastOrNull()
        stack += NavEntry(nextId++, route)
    }

    /** Leaves the top screen; on a tab other than the first, goes back to the first. Returns false when there is nowhere to go. */
    fun back(): Boolean {
        forward = false
        if (stack.removeLastOrNull() != null) return true
        if (tab != Route.Tab.Test) {
            tab = Route.Tab.Test
            return true
        }
        return false
    }

    /** Whether [entry] is still reachable: a tab, or a screen on the stack. */
    fun isLive(entry: NavEntry): Boolean = entry in stack || tabEntries[entry.route] === entry

    override fun onCleared() {
        stack.forEach { it.viewModelStore.clear() }
        tabEntries.values.forEach { it.viewModelStore.clear() }
    }
}
