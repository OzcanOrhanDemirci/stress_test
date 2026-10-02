package dev.ozcan.stress.device

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import dev.ozcan.stress.engine.NativeBridge
import dev.ozcan.stress.telemetry.SysfsLayout
import dev.ozcan.stress.telemetry.label
import kotlinx.serialization.Serializable
import java.util.Locale
import kotlin.math.roundToInt

/** The GPU as Vulkan describes it ([NativeBridge.gpuDescribe]). */
@Serializable
data class GpuInfo(val name: String, val vulkanVersion: String, val vendorId: Int, val deviceId: Int) {
    companion object {
        /** Parses `name|api|driver|vendor|device`; null for an empty or malformed line. */
        fun parse(line: String): GpuInfo? {
            val parts = line.split('|')
            if (parts.size != 5 || parts[0].isBlank()) return null
            return GpuInfo(parts[0].trim(), parts[1], parts[3].toIntOrNull() ?: 0, parts[4].toIntOrNull() ?: 0)
        }
    }
}

/**
 * The phone a run was measured on, kept with the run and printed in its
 * report: results only compare between runs on the same hardware.
 */
@Serializable
data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val sdk: Int,
    /** The SoC's name where Android tells it (Android 12+), else the board's. */
    val soc: String?,
    /** Clusters as "Cortex-A510 ×4 @ 1.80 GHz", fastest last. */
    val cpuClusters: List<String>,
    val cpuCount: Int,
    val gpu: String?,
    val vulkan: String?,
    val ramGigabytes: Double?,
    /** "1264 × 2736 · 120 Hz" */
    val display: String?,
) {
    val title: String
        get() {
            val brand = manufacturer.replaceFirstChar { it.titlecase(Locale.ROOT) }
            return if (model.startsWith(manufacturer, ignoreCase = true)) model else "$brand $model"
        }

    companion object {
        /** Reads everything; asks Vulkan once ([gpu] from [describeGpu]), so call it off the main thread. */
        fun collect(context: Context, layout: SysfsLayout, gpu: GpuInfo?): DeviceInfo {
            val memory = ActivityManager.MemoryInfo()
            context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(memory)
            val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
            val mode = display?.mode
            val fastest = display?.supportedModes
                ?.filter { mode == null || (it.physicalWidth == mode.physicalWidth && it.physicalHeight == mode.physicalHeight) }
                ?.maxOfOrNull { it.refreshRate }
            return DeviceInfo(
                manufacturer = Build.MANUFACTURER.orEmpty(),
                model = Build.MODEL.orEmpty(),
                androidVersion = Build.VERSION.RELEASE.orEmpty(),
                sdk = Build.VERSION.SDK_INT,
                soc = soc(),
                cpuClusters = layout.clusters.sortedBy { it.maxFreqKhz }.map { c ->
                    val ghz = String.format(Locale.ROOT, "%.2f", c.maxFreqKhz / 1e6)
                    "${c.label()} @ $ghz GHz"
                },
                cpuCount = layout.cpuCount,
                gpu = gpu?.name,
                vulkan = gpu?.vulkanVersion,
                ramGigabytes = memory.totalMem.takeIf { it > 0 }?.let { (it / 1e9 * 10).roundToInt() / 10.0 },
                display = mode?.let { m ->
                    val hz = (fastest ?: m.refreshRate).roundToInt()
                    "${m.physicalWidth} × ${m.physicalHeight} · $hz Hz"
                },
            )
        }

        private fun soc(): String? {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val maker = Build.SOC_MANUFACTURER.takeUnless { it.isNullOrBlank() || it == Build.UNKNOWN }
                val model = Build.SOC_MODEL.takeUnless { it.isNullOrBlank() || it == Build.UNKNOWN }
                if (model != null) return SocNames.display(maker, model)
            }
            return Build.HARDWARE.takeUnless { it.isNullOrBlank() || it == Build.UNKNOWN }
        }

        /**
         * The Vulkan GPU, or null when the phone has no Vulkan 1.1: then the
         * GPU tests cannot run (the CPU tests still can).
         */
        fun describeGpu(context: Context): GpuInfo? {
            val pm = context.packageManager
            if (!pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, VULKAN_1_1)) return null
            return runCatching { GpuInfo.parse(NativeBridge.gpuDescribe()) }.getOrNull()
        }

        /** VK_API_VERSION_1_1 as PackageManager encodes it. */
        private const val VULKAN_1_1 = 0x401000
    }
}
