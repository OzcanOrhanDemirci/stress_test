package dev.ozcan.stress

import android.app.Application
import android.content.Context
import dev.ozcan.stress.engine.CpuEngine
import dev.ozcan.stress.lab.LabRunner
import dev.ozcan.stress.telemetry.BatteryReader
import dev.ozcan.stress.telemetry.NativeSensors
import dev.ozcan.stress.telemetry.Sampler
import dev.ozcan.stress.telemetry.SysfsLayout
import dev.ozcan.stress.telemetry.ThermalReader
import java.io.File

/** The app's long-lived objects, created once per process. */
class AppGraph(context: Context) {
    val cpu = CpuEngine()
    val layout = SysfsLayout.discover()
    val sensors = NativeSensors(layout)
    val sampler = Sampler(BatteryReader(context), sensors, ThermalReader(context), cpu)

    /** Lab output lives in the external files directory so `adb pull` can reach it. */
    val labDir: File = File(context.getExternalFilesDir(null), "lab")

    fun labRunner() = LabRunner(sampler, cpu, layout, labDir)
}

class StressApplication : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }
}

val Context.graph: AppGraph get() = (applicationContext as StressApplication).graph
