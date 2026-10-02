package dev.ozcan.stress

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.ozcan.stress.lab.LabSpec
import dev.ozcan.stress.settings.AppLanguages
import dev.ozcan.stress.ui.AppRoot
import dev.ozcan.stress.ui.LabScreen
import dev.ozcan.stress.ui.theme.StressTheme

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        // Before Android 13 the app's own language is laid over the activity here.
        AppLanguages.attach(newBase)?.let(::applyOverrideConfiguration)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        AppLanguages.reapply(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val lab = LabSpec.parse(labExtras(), graph.cpu.kernels, graph.gpu.burners, graph.cpu.cpuCount)
        if (lab != null) {
            // A load only reaches every core while the app is on screen (the
            // top-app cpuset); a lab session started over adb must find the
            // screen on and the app over the lock screen. The app's own runs
            // keep the screen on only while they run (RunScreen).
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            lab.getOrNull()?.let { spec ->
                window.attributes = window.attributes.apply { screenBrightness = spec.brightness }
            }
        }

        setContent {
            StressTheme {
                if (lab == null) {
                    AppRoot()
                } else {
                    LabScreen(lab)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        graph.sampler.start()
    }

    override fun onStop() {
        // Off screen the CPU load would be confined to the little cores and the
        // GPU would lose its surface: stop both rather than measure something else.
        graph.cpu.stop()
        graph.gpu.requestAsync(null)
        graph.sampler.stop()
        super.onStop()
    }

    /** Lab keys only through the lab entry, which only the adb shell may start (see the manifest). */
    private fun labExtras(): Map<String, String?> {
        if (intent.component?.className != LAB_ENTRY) return emptyMap()
        val extras = intent.extras ?: return emptyMap()
        return extras.keySet().filter { it.startsWith(LabSpec.PREFIX) }.associateWith { extras.getString(it) }
    }

    private companion object {
        const val LAB_ENTRY = "dev.ozcan.stress.LabActivity"
    }
}
