package dev.ozcan.stress

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.StrictMode
import dev.ozcan.stress.device.DeviceInfo
import dev.ozcan.stress.device.GpuInfo
import dev.ozcan.stress.engine.CpuEngine
import dev.ozcan.stress.engine.GpuEngine
import dev.ozcan.stress.engine.LoadDriver
import dev.ozcan.stress.lab.LabRunner
import dev.ozcan.stress.run.RunStore
import dev.ozcan.stress.settings.SettingsStore
import dev.ozcan.stress.telemetry.BatteryReader
import dev.ozcan.stress.telemetry.NativeSensors
import dev.ozcan.stress.telemetry.Sampler
import dev.ozcan.stress.telemetry.SysfsLayout
import dev.ozcan.stress.telemetry.ThermalReader
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/** What the phone can run, found once at start-up. */
data class Capabilities(
    /** The Vulkan GPU; null when the phone has no Vulkan 1.1, and the GPU tests are then unavailable. */
    val gpu: GpuInfo?,
) {
    val gpuTests: Boolean get() = gpu != null
}

/** The app's long-lived objects, created once per process. */
class AppGraph(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val layout = SysfsLayout.discover()
    val cpu = CpuEngine(layout.cpuCount)
    val gpu = GpuEngine()
    val sensors = NativeSensors(layout)
    val sampler = Sampler(BatteryReader(app), sensors, ThermalReader(app), cpu, gpu)
    val driver = LoadDriver(cpu, gpu)
    val runs = RunStore(File(app.filesDir, "runs"))
    val settings = SettingsStore(app)

    /** Asking Vulkan takes a moment: done once, off the main thread. */
    val capabilities: Deferred<Capabilities> = scope.async { Capabilities(DeviceInfo.describeGpu(app)) }

    val device: Deferred<DeviceInfo> = scope.async { DeviceInfo.collect(app, layout, capabilities.await().gpu) }

    val appVersion: String =
        runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "?"

    /** Lab output lives in the external files directory so `adb pull` can reach it. */
    val labDir: File = File(app.getExternalFilesDir(null), "lab")

    /** Reports are written here and shared through the app's file provider. */
    val reportDir: File = File(app.cacheDir, "reports")

    fun labRunner() = LabRunner(sampler, driver, layout, labDir)
}

class StressApplication : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }

    override fun onCreate() {
        super.onCreate()
        // Debug builds only: the app reads the sysfs layout while the first
        // frame is composed and stops the sampler's thread on the way to the
        // background, and a main-thread policy is what makes such work
        // visible while it is written rather than after a slow phone reports
        // jank. Logging only, so nothing development does becomes a crash by
        // itself.
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build(),
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder().detectActivityLeaks().detectLeakedClosableObjects().penaltyLog().build(),
            )
        }
    }
}

val Context.graph: AppGraph get() = (applicationContext as StressApplication).graph
