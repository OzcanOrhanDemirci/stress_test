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
        private val EXCLUDED = listOf("ibat", "vbat", "bcl", "socd", "lmh", "limit", "freq", "batt", "charger", "charging", "usb", "ambient", "rfpa")
        private val excludedToken = Regex("(^|[-_.])(pa[0-9]*|usr|cfg)([-_.]|$)")
        private val cpuName = Regex("(^|[-_.])(cpu(ss|top)?[0-9]*|big[0-9]*|mid[0-9]*|little[0-9]*|apc[0-9]*|cluster[0-9]*|silver|gold)([-_.]|$)")
        private val gpuName = Regex("(^|[-_.])(gpu(ss|top)?[0-9]*|g3d|mali|sgpu|xclipse)([-_.]|$)")

        private val RULES: List<Pair<ThermalGroup, (String) -> Boolean>> = listOf(
            Skin to { t -> t == "virtual-skin" || !t.startsWith("virtual-skin-") &&
                listOf("skin", "shell", "quiet", "xo-therm", "xo_therm", "sys-therm", "sys_therm").any { it in t } },
            Gpu to { t -> gpuName.containsMatchIn(t) || t == "mtktsgpu" },
            Npu to { t -> listOf("nsp", "npu", "apu", "tpu", "cdsp", "hvx").any { it in t } },
            Modem to { t -> "mdm" in t || "modem" in t && !t.endsWith("_cfg") },
            // ("isp" alone would take "display" too.)
            Camera to { t -> "camera" in t || t.startsWith("cam") || t.startsWith("isp") || "_isp" in t || "-isp" in t },
            Memory to { t -> "ddr" in t || "dram" in t || t.startsWith("mem") },
            Cpu to { t ->
                cpuName.containsMatchIn(t) || t in setOf("mtktscpu", "soc-max", "soc_max", "mtk-soc-max")
            },
        )

        fun of(zoneType: String): ThermalGroup? {
            val t = zoneType.trim().lowercase(Locale.ROOT)
            if (t.isEmpty() || EXCLUDED.any { it in t } || excludedToken.containsMatchIn(t)) return null
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
    val fallbackFreqPath: String? = null,
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
    /** The first recognized GPU load file the app can read. */
    val gpuBusyPath: String?,
    val gpuBusyFormat: GpuBusyFormat = GpuBusyFormat.Pair,
    /** CPUs the kernel can bring online, so the load has a thread for every one. */
    val cpuCount: Int = clusters.flatMap { it.cpus }.maxOrNull()?.plus(1) ?: 1,
    /** Also keeps names of CPUs whose clock policy was hidden. */
    val cpuNames: Map<Int, String> = emptyMap(),
) {
    /** Cluster clocks, zone temperatures, GPU load, then any alternative clocks. */
    val paths: List<String> get() = clusters.map { it.currentFreqPath } + zones.map { it.tempPath } +
        listOfNotNull(gpuBusyPath) + clusters.mapNotNull { it.fallbackFreqPath }
    internal val fallbackClockStart: Int get() = clusters.size + zones.size + if (gpuBusyPath == null) 0 else 1

    companion object {
        /** More zones than this are not read: each costs a system call at 10 Hz. */
        private const val MAX_ZONES = 64
        private const val MAX_CPU_ID = 4095
        private val cpuDirName = Regex("cpu([0-9]+)")
        private val cpuRange = Regex("([0-9]+)(?:-([0-9]+))?")
        private val gpuDevice = Regex("(^|[-_.])(gpu|mali[0-9]*|pvr|powervr|pvrsrvkm|sgpu|xclipse)([-_.]|$)")

        /**
         * GPU load files, most informative first: Adreno's busy/total pair,
         * then the percentages of Adreno, Samsung, MediaTek and Mali kernels.
         */
        private val GPU_BUSY_CANDIDATES: List<Pair<String, GpuBusyFormat>> = listOf(
            "sys/class/kgsl/kgsl-3d0/gpubusy" to GpuBusyFormat.Pair,
            "sys/class/kgsl/kgsl-3d0/gpu_busy_percentage" to GpuBusyFormat.Percent,
            "sys/kernel/gpu/gpu_busy" to GpuBusyFormat.Percent,
            "sys/kernel/ged/hal/gpu_utilization" to GpuBusyFormat.Percent,
            "sys/module/ged/parameters/gpu_loading" to GpuBusyFormat.Percent,
            "sys/devices/platform/gpusysfs/gpu_busy" to GpuBusyFormat.Percent,
            "sys/class/misc/mali0/device/utilization" to GpuBusyFormat.Percent,
            "sys/class/misc/mali0/device/utilisation" to GpuBusyFormat.Percent,
            "sys/class/misc/pvrsrvkm/device/utilisation" to GpuBusyFormat.Percent,
            "sys/class/pvr/gpu_utilisation" to GpuBusyFormat.Percent,
            "sys/class/pvr/utilisation" to GpuBusyFormat.Percent,
        )

        fun discover(root: File = File("/")): SysfsLayout {
            val cpuDir = File(root, "sys/devices/system/cpu")
            val names = CpuParts.namesByCpu(cpuDir, File(root, "proc/cpuinfo"))
            val cpuDirs = cpuDir.listFiles().orEmpty().mapNotNull { dir ->
                val id = cpuDirName.matchEntire(dir.name)?.groupValues?.get(1)?.toIntOrNull()
                    ?.takeIf { it in 0..MAX_CPU_ID } ?: return@mapNotNull null
                id to dir
            }.toMap()
            val clusters = discoverClusters(cpuDir, cpuDirs, names)
            val possible = readText(File(cpuDir, "possible"))?.let(::parseCpuList).orEmpty()
                .ifEmpty { readText(File(cpuDir, "present"))?.let(::parseCpuList).orEmpty() }
            val highest = (possible + cpuDirs.keys + names.keys + clusters.flatMap { it.cpus }).maxOrNull()
            val gpu = discoverGpuBusy(root)
            return SysfsLayout(
                clusters = clusters,
                zones = discoverZones(File(root, "sys/class/thermal")),
                gpuBusyPath = gpu?.first,
                gpuBusyFormat = gpu?.second ?: GpuBusyFormat.Pair,
                cpuCount = highest?.plus(1) ?: Runtime.getRuntime().availableProcessors(),
                cpuNames = names,
            )
        }

        /** "0-3,6,8-9" -> [0, 1, 2, 3, 6, 8, 9]; malformed parts are skipped. */
        fun parseCpuList(text: String): List<Int> =
            text.split(Regex("[,\\s]+")).flatMap { part ->
                val match = cpuRange.matchEntire(part) ?: return@flatMap emptyList()
                val first = match.groupValues[1].toIntOrNull() ?: return@flatMap emptyList()
                val last = (match.groups[2]?.value ?: match.groupValues[1]).toIntOrNull() ?: return@flatMap emptyList()
                if (first > last || last > MAX_CPU_ID) emptyList() else (first..last).toList()
            }.distinct().sorted()

        private fun discoverClusters(cpuDir: File, cpuDirs: Map<Int, File>, names: Map<Int, String>): List<CpuCluster> {
            val found = mutableListOf<CpuCluster>()
            val claimed = mutableSetOf<Int>()
            fun add(policy: Int, dir: File) {
                // related_cpus includes offline CPUs; affected_cpus lists only online ones.
                val cpus = readText(File(dir, "related_cpus"))?.let(::parseCpuList).orEmpty()
                    .ifEmpty { readText(File(dir, "affected_cpus"))?.let(::parseCpuList).orEmpty() }
                if (cpus.isEmpty() || cpus.any { it in claimed } || found.any { it.policy == policy }) return
                claimed += cpus
                val core = cpus.mapNotNull(names::get).distinct().singleOrNull()
                val max = readText(File(dir, "cpuinfo_max_freq"))?.toLongOrNull()?.takeIf { it > 0 }
                found += CpuCluster(
                    policy = policy,
                    cpus = cpus,
                    maxFreqKhz = max ?: 0L,
                    currentFreqPath = File(dir, "scaling_cur_freq").path,
                    coreName = core,
                    fallbackFreqPath = File(dir, "cpuinfo_cur_freq").takeIf { it.exists() }?.path,
                )
            }
            val policies = File(cpuDir, "cpufreq").listFiles().orEmpty().mapNotNull { dir ->
                if (!dir.name.startsWith("policy")) return@mapNotNull null
                dir.name.removePrefix("policy").toIntOrNull()?.takeIf { it in 0..MAX_CPU_ID }?.let { it to dir }
            }
            for ((policy, dir) in policies.sortedBy { it.first }) add(policy, dir)
            // Older or restricted kernels may expose only the per-CPU cpufreq symlink.
            for ((cpu, dir) in cpuDirs.toSortedMap()) if (cpu !in claimed) add(cpu, File(dir, "cpufreq"))
            return found.sortedBy { it.policy }
        }

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
            fun usable(file: File, format: GpuBusyFormat): Boolean {
                val fields = readText(file)?.split(Regex("\\s+")) ?: return false
                val busy = fields.first().removeSuffix("%").toLongOrNull() ?: return false
                if (format == GpuBusyFormat.Percent) return busy in 0..100
                val total = fields.getOrNull(1)?.toLongOrNull() ?: return false
                // KGSL returns an empty interval when the GPU is idle.
                return total >= 0 && busy in 0..total
            }
            for ((path, format) in GPU_BUSY_CANDIDATES) {
                val file = File(root, path)
                if (usable(file, format)) return file.path to format
            }
            val drm = File(root, "sys/class/drm").listFiles { f -> f.name.matches(Regex("card[0-9]+")) }.orEmpty()
            for (card in drm.sortedBy { it.name }) {
                val file = File(card, "device/gpu_busy_percent")
                if (usable(file, GpuBusyFormat.Percent)) return file.path to GpuBusyFormat.Percent
            }
            // Mali and similar drivers hang these files off their platform or devfreq device.
            for (path in listOf("sys/devices/platform", "sys/class/devfreq")) {
                val nodes = File(root, path).listFiles { f -> gpuDevice.containsMatchIn(f.name.lowercase(Locale.ROOT)) }.orEmpty()
                for (node in nodes.sortedBy { it.name }) for (name in listOf("utilization", "utilisation", "gpu_busy")) {
                    val file = File(node, name)
                    if (usable(file, GpuBusyFormat.Percent)) return file.path to GpuBusyFormat.Percent
                }
            }
            return null
        }

        private fun readText(file: File): String? =
            runCatching { file.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
    }
}
