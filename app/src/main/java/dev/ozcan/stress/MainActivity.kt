package dev.ozcan.stress

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.ozcan.stress.lab.LabSpec
import dev.ozcan.stress.ui.DiagnosticsScreen
import dev.ozcan.stress.ui.LabScreen
import dev.ozcan.stress.ui.theme.StressTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A load only reaches every core while the app is on screen (the
        // top-app cpuset); the screen must stay on and show over the lock
        // screen so lab runs started over adb find it there.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        val lab = LabSpec.parse(labExtras(), graph.cpu.kernels, graph.gpu.burners)
        lab?.getOrNull()?.let { spec ->
            window.attributes = window.attributes.apply { screenBrightness = spec.brightness }
        }

        setContent {
            StressTheme {
                if (lab == null) {
                    DiagnosticsScreen()
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

    private fun labExtras(): Map<String, String?> {
        val extras = intent.extras ?: return emptyMap()
        return extras.keySet().filter { it.startsWith(LabSpec.PREFIX) }.associateWith { extras.getString(it) }
    }
}
