package dev.ozcan.stress.telemetry

import java.io.File

/** A group of thermal zones reported as one temperature: the hottest zone in it. */
enum class ThermalGroup(val label: String, private val zonePrefixes: List<String>) {
    BigCores("A715", listOf("cpu-1-")),
    LittleCores("A510", listOf("cpu-0-")),
    Gpu("GPU", listOf("gpuss-")),
    Memory("DDR", listOf("ddr")),
    Npu("NPU", listOf("nspss-")),
    Modem("Modem", listOf("mdmss-")),
    Camera("Kamera", listOf("camera-"));

    /** A prefix ending in '-' matches every numbered zone; any other prefix must match exactly. */
    fun matches(zoneType: String): Boolean =
        zonePrefixes.any { if (it.endsWith('-')) zoneType.startsWith(it) else zoneType == it }

    companion object {
        fun of(zoneType: String): ThermalGroup? = entries.firstOrNull { it.matches(zoneType) }
    }
}

/** One cpufreq policy: the CPUs that share a clock. */
data class CpuCluster(
    val policy: Int,
    val cpus: List<Int>,
    val maxFreqKhz: Long,
    val currentFreqPath: String,
)

data class ThermalZone(val type: String, val group: ThermalGroup, val tempPath: String)

/**
 * Where this phone keeps the numbers the sampler reads. Found once at start-up
 * by [discover]; afterwards only the files listed by [paths] are read.
 */
data class SysfsLayout(
    val clusters: List<CpuCluster>,
    val zones: List<ThermalZone>,
    val gpuBusyPath: String,
) {
    /** Every file to read, in a fixed order: cluster clocks, zone temperatures, GPU busy counter. */
    val paths: List<String> get() = clusters.map { it.currentFreqPath } + zones.map { it.tempPath } + gpuBusyPath

    companion object {
        fun discover(root: File = File("/")): SysfsLayout = SysfsLayout(
            clusters = discoverClusters(File(root, "sys/devices/system/cpu/cpufreq")),
            zones = discoverZones(File(root, "sys/class/thermal")),
            gpuBusyPath = File(root, "sys/class/kgsl/kgsl-3d0/gpubusy").path,
        )

        private fun discoverClusters(dir: File): List<CpuCluster> =
            dir.listFiles { f -> f.name.startsWith("policy") }.orEmpty()
                .mapNotNull { policyDir ->
                    val policy = policyDir.name.removePrefix("policy").toIntOrNull() ?: return@mapNotNull null
                    val cpus = readText(File(policyDir, "related_cpus"))
                        ?.split(' ')?.mapNotNull { it.trim().toIntOrNull() }
                        .orEmpty()
                        .ifEmpty { return@mapNotNull null }
                    CpuCluster(
                        policy = policy,
                        cpus = cpus,
                        maxFreqKhz = readText(File(policyDir, "cpuinfo_max_freq"))?.toLongOrNull() ?: 0L,
                        currentFreqPath = File(policyDir, "scaling_cur_freq").path,
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

        private fun readText(file: File): String? =
            runCatching { file.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
    }
}
