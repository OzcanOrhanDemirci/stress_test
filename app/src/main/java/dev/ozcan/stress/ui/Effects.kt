package dev.ozcan.stress.ui

import android.os.Build
import android.view.Display
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * While shown, drives the display as hard as it goes: full brightness and the
 * highest refresh rate. Both add to the power the run measures, the same way
 * every time. Restores the previous settings when it leaves.
 */
@Composable
fun MaxDisplayEffect() {
    val activity = LocalActivity.current ?: return
    DisposableEffect(activity) {
        val window = activity.window
        val previous = WindowManager.LayoutParams().apply { copyFrom(window.attributes) }
        val display: Display? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display
        } else {
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay
        }
        val current = display?.mode
        val fastest = display?.supportedModes
            ?.filter { current == null || (it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight) }
            ?.maxByOrNull { it.refreshRate }
        window.attributes = window.attributes.apply {
            screenBrightness = 1f
            if (fastest != null) preferredDisplayModeId = fastest.modeId
        }
        onDispose {
            window.attributes = window.attributes.apply {
                screenBrightness = previous.screenBrightness
                preferredDisplayModeId = previous.preferredDisplayModeId
            }
        }
    }
}

/**
 * Keeps the screen on while shown: a run must stay in front, or the load
 * loses the big cores and the GPU its surface. Elsewhere the screen times out
 * as usual.
 */
@Composable
fun KeepScreenOnEffect() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}
