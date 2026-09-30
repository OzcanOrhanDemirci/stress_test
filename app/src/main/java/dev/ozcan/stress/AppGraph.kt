package dev.ozcan.stress

import android.app.Application
import android.content.Context
import dev.ozcan.stress.engine.CpuEngine
import dev.ozcan.stress.engine.GpuEngine
import dev.ozcan.stress.engine.LoadDriver
import dev.ozcan.stress.lab.LabRunner
import dev.ozcan.stress.run.RunStore
import dev.ozcan.stress.telemetry.BatteryReader
import dev.ozcan.stress.telemetry.NativeSensors
import dev.ozcan.stress.telemetry.Sampler
import dev.ozcan.stress.telemetry.SysfsLayout
import dev.ozcan.stress.telemetry.ThermalReader
import java.io.File

/** The app's long-lived objects, created once per process. */
class AppGraph(context: Context) {
    val cpu = CpuEngine()
    val gpu = GpuEngine()
    val layout = SysfsLayout.discover()
    val sensors = NativeSensors(layout)
    val sampler = Sampler(BatteryReader(context), sensors, ThermalReader(context), cpu, gpu)
    val driver = LoadDriver(cpu, gpu)
    val runs = RunStore(File(context.filesDir, "runs"))

    /** Lab output lives in the external files directory so `adb pull` can reach it. */
    val labDir: File = File(context.getExternalFilesDir(null), "lab")

    fun labRunner() = LabRunner(sampler, driver, layout, labDir)
}

class StressApplication : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }
}

val Context.graph: AppGraph get() = (applicationContext as StressApplication).graph
