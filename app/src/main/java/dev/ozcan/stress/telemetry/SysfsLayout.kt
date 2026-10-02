package dev.ozcan.stress.telemetry

import java.io.File
import java.util.Locale

/**
 * A group of thermal zones reported as one temperature: the hottest zone in
 * it. [key] names the group in stored runs; the screens translate it.
 *
 * Vendors name their zones differently (Qualcomm `cpu-1-0`, `gpuss-0`;
 * MediaTek `mtktscpu`, `cpu-little0`; Samsung and Google `BIG`, `G3D`), so a
 * zone is placed by the words in its name ([of]), not by one phone's list.
 */
enum class ThermalGroup(val key: String) {
    Cpu("cpu"),
    Gpu("gpu"),
    Memory("memory"),
    Npu("npu"),
    Modem("modem"),
    Camera("camera"),
    /** The case: what a hand holding the phone feels. */
    Skin("skin");

    companion object {
        /**
         * Zones that are not temperatures or not the chip's: Qualcomm's
         * battery current and voltage limits (`ibat`, `vbat`, `bcl`) report
         * milliamps and millivolts through the same file, and the battery's
         * own sensor is read from the battery broadcast instead.
         */
        private val NOT_TEMPERATURES = listOf("ibat", "vbat", "bcl", "socd", "lmh", "limit", "freq", "batt", "charger", "usb")

        private val RULES: List<Pair<ThermalGroup, (String) -> Boolean>> = listOf(
            Gpu to { t -> "gpu" in t || "g3d" in t || "mali" in t },
            Npu to { t -> listOf("nsp", "npu", "apu", "tpu", "cdsp", "hvx").any { it in t } },
            Modem to { t -> "mdm" in t || "modem" in t && !t.endsWith("_cfg") },
            // ("isp" alone would take "display" too.)
            Camera to { t -> "camera" in t || t.startsWith("cam") || t.startsWith("isp") || "_isp" in t || "-isp" in t },
            Memory to { t -> "ddr" in t || "dram" in t || t.startsWith("mem") },
            Skin to { t -> listOf("skin", "shell", "quiet", "xo-therm", "xo_therm", "sys-therm", "sys_therm").any { it in t } },
            Cpu to { t ->
                "cpu" in t || t == "big" || t == "mid" || t == "little" || t.startsWith("big") ||
                    t.startsWith("little") || t.startsWith("cluster") || t.startsWith("apc") || "silver" in t || "gold" in t
            },
        )

        fun of(zoneType: String): ThermalGroup? {
            val t = zoneType.trim().lowercase(Locale.ROOT)
            if (t.isEmpty() || NOT_TEMPERATURES.any { it in t }) return null
            return RULES.firstOrNull { (_, matches) -> matches(t) }?.first
        }
    }
}

/** One cpufreq policy: the CPUs that share a clock, and what kind of core they are. */
data class CpuCluster(
    val policy: Int,
    val cpus: List<Int>,
    val maxFreqKhz: Long,
    val currentFreqPath: String,
    /** The core's marketing name ("Cortex-A715"), or null when the CPU does not say. */
    val coreName: String? = null,
)

data class ThermalZone(val type: String, val group: ThermalGroup, val tempPath: String)

/** How the GPU load file reports: kgsl's `busy total` pair, or a percentage. */
enum class GpuBusyFormat { Pair, Percent }

/**
 * Where this phone keeps the numbers the sampler reads. Found once at start-up
 * by [discover]; afterwards only the files listed by [paths] are read.
 */
data class SysfsLayout(
    val clusters: List<CpuCluster>,
    val zones: List<ThermalZone>,
    /** The GPU load file, the first of [GPU_BUSY_CANDIDATES] that exists; null when there is none. */
    val gpuBusyPath: String?,
    val gpuBusyFormat: GpuBusyFormat = GpuBusyFormat.Pair,
    /** CPUs the kernel can bring online, so the load has a thread for every one. */
    val cpuCount: Int = clusters.flatMap { it.cpus }.maxOrNull()?.plus(1) ?: 1,
) {
    /** Every file to read, in a fixed order: cluster clocks, zone temperatures, the GPU load file. */
    val paths: List<String> get() = clusters.map { it.currentFreqPath } + zones.map { it.tempPath } + listOfNotNull(gpuBusyPath)

    companion object {
        /** More zones than this are not read: each costs a system call at 10 Hz. */
        private const val MAX_ZONES = 64

        /**
         * GPU load files, most informative first: Adreno's busy/total pair,
         * then the percentages of Adreno, Samsung, MediaTek and Mali kernels.
         */
        val GPU_BUSY_CANDIDATES: List<Pair<String, GpuBusyFormat>> = listOf(
            "sys/class/kgsl/kgsl-3d0/gpubusy" to GpuBusyFormat.Pair,
            "sys/class/kgsl/kgsl-3d0/gpu_busy_percentage" to GpuBusyFormat.Percent,
            "sys/kernel/gpu/gpu_busy" to GpuBusyFormat.Percent,
            "sys/kernel/ged/hal/gpu_utilization" to GpuBusyFormat.Percent,
            "sys/class/misc/mali0/device/utilization" to GpuBusyFormat.Percent,
        )

        fun discover(root: File = File("/")): SysfsLayout {
            val cpuDir = File(root, "sys/devices/system/cpu")
            val names = CpuParts.namesByCpu(cpuDir, File(root, "proc/cpuinfo"))
            val clusters = discoverClusters(File(cpuDir, "cpufreq"), names)
            val possible = readText(File(cpuDir, "possible"))?.let(::parseCpuList).orEmpty()
            val highest = (possible + clusters.flatMap { it.cpus }).maxOrNull()
            val gpu = discoverGpuBusy(root)
            return SysfsLayout(
                clusters = clusters,
                zones = discoverZones(File(root, "sys/class/thermal")),
                gpuBusyPath = gpu?.first,
                gpuBusyFormat = gpu?.second ?: GpuBusyFormat.Pair,
                cpuCount = highest?.plus(1) ?: Runtime.getRuntime().availableProcessors(),
            )
        }

        /** "0-3,6,8-9" -> [0, 1, 2, 3, 6, 8, 9]; malformed parts are skipped. */
        fun parseCpuList(text: String): List<Int> =
            text.split(',', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }.flatMap { part ->
                val bounds = part.split('-').mapNotNull { it.trim().toIntOrNull() }
                when (bounds.size) {
                    1 -> listOf(bounds[0])
                    2 -> if (bounds[0] <= bounds[1]) (bounds[0]..bounds[1]).toList() else emptyList()
                    else -> emptyList()
                }
            }.distinct().sorted()

        private fun discoverClusters(dir: File, names: Map<Int, String>): List<CpuCluster> =
            dir.listFiles { f -> f.name.startsWith("policy") }.orEmpty()
                .mapNotNull { policyDir ->
                    val policy = policyDir.name.removePrefix("policy").toIntOrNull() ?: return@mapNotNull null
                    val cpus = (readText(File(policyDir, "related_cpus")) ?: readText(File(policyDir, "affected_cpus")))
                        ?.let(::parseCpuList)
                        .orEmpty()
                        .ifEmpty { return@mapNotNull null }
                    CpuCluster(
                        policy = policy,
                        cpus = cpus,
                        maxFreqKhz = readText(File(policyDir, "cpuinfo_max_freq"))?.toLongOrNull() ?: 0L,
                        currentFreqPath = File(policyDir, "scaling_cur_freq").path,
                        coreName = names[cpus.first()],
                    )
                }
                .sortedBy { it.policy }

        private fun discoverZones(dir: File): List<ThermalZone> =
            dir.listFiles { f -> f.name.startsWith("thermal_zone") }.orEmpty()
                .sortedBy { it.name.removePrefix("thermal_zone").toIntOrNull() ?: Int.MAX_VALUE }
                .mapNotNull { zoneDir ->
                    val type = readText(File(zoneDir, "type")) ?: return@mapNotNull null
                    val group = ThermalGroup.of(type) ?: return@mapNotNull null
                    ThermalZone(type, group, File(zoneDir, "temp").path)
                }
                .take(MAX_ZONES)

        private fun discoverGpuBusy(root: File): Pair<String, GpuBusyFormat>? {
            GPU_BUSY_CANDIDATES.firstOrNull { File(root, it.first).exists() }?.let { return File(root, it.first).path to it.second }
            // Mali kernels keep the file under the GPU's platform device, whose name varies.
            return File(root, "sys/devices/platform").listFiles { f -> "mali" in f.name.lowercase(Locale.ROOT) }.orEmpty()
                .map { File(it, "utilization") }
                .firstOrNull { it.exists() }
                ?.let { it.path to GpuBusyFormat.Percent }
        }

        private fun readText(file: File): String? =
            runCatching { file.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
    }
}
