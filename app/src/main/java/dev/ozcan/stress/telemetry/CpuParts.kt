package dev.ozcan.stress.telemetry

import java.io.File
import java.util.Locale

/**
 * Names of CPU cores from their identification register (MIDR_EL1): the
 * implementer (ARM, Qualcomm, Samsung) and the part number. The kernel shows
 * the register under `cpuN/regs/identification/midr_el1`, and `/proc/cpuinfo`
 * repeats both fields for kernels that hide the register.
 */
object CpuParts {

    private val ARM = mapOf(
        0xd01 to "Cortex-A32", 0xd02 to "Cortex-A34", 0xd03 to "Cortex-A53", 0xd04 to "Cortex-A35",
        0xd05 to "Cortex-A55", 0xd06 to "Cortex-A65", 0xd07 to "Cortex-A57", 0xd08 to "Cortex-A72",
        0xd09 to "Cortex-A73", 0xd0a to "Cortex-A75", 0xd0b to "Cortex-A76", 0xd0d to "Cortex-A77",
        0xd0e to "Cortex-A76AE", 0xd41 to "Cortex-A78", 0xd42 to "Cortex-A78AE", 0xd44 to "Cortex-X1",
        0xd46 to "Cortex-A510", 0xd47 to "Cortex-A710", 0xd48 to "Cortex-X2", 0xd4b to "Cortex-A78C",
        0xd4c to "Cortex-X1C", 0xd4d to "Cortex-A715", 0xd4e to "Cortex-X3", 0xd80 to "Cortex-A520",
        0xd81 to "Cortex-A720", 0xd82 to "Cortex-X4", 0xd85 to "Cortex-X925", 0xd87 to "Cortex-A725",
    )

    private val QUALCOMM = mapOf(
        0x001 to "Oryon", 0x800 to "Kryo Gold", 0x801 to "Kryo Silver", 0x802 to "Kryo Gold",
        0x803 to "Kryo Silver", 0x804 to "Kryo Gold", 0x805 to "Kryo Silver",
    )

    private val SAMSUNG = mapOf(0x001 to "Mongoose M1", 0x002 to "Mongoose M3", 0x003 to "Mongoose M4", 0x004 to "Mongoose M5")

    /** The core's name, or null for an unknown part (the screens then name the cluster by its CPUs). */
    fun name(implementer: Int, part: Int): String? = when (implementer) {
        0x41 -> ARM[part]
        0x51 -> QUALCOMM[part]
        0x53 -> SAMSUNG[part]
        else -> null
    }

    /** "0x00000000411fd462" -> (0x41, 0xd46). */
    fun parseMidr(text: String): Pair<Int, Int>? {
        val value = text.trim().removePrefix("0x").removePrefix("0X").toLongOrNull(16) ?: return null
        val implementer = ((value shr 24) and 0xff).toInt()
        val part = ((value shr 4) and 0xfff).toInt()
        return if (implementer == 0) null else implementer to part
    }

    /** Implementer and part of each processor in `/proc/cpuinfo`'s text, by CPU number. */
    fun parseCpuinfo(text: String): Map<Int, Pair<Int, Int>> {
        val result = HashMap<Int, Pair<Int, Int>>()
        var cpu: Int? = null
        var implementer: Int? = null
        var part: Int? = null
        fun flush() {
            val c = cpu
            val i = implementer
            val p = part
            if (c != null && i != null && p != null) result[c] = i to p
        }
        for (line in text.lineSequence()) {
            val key = line.substringBefore(':').trim().lowercase(Locale.ROOT)
            val value = line.substringAfter(':', "").trim()
            when (key) {
                "processor" -> {
                    flush()
                    cpu = value.toIntOrNull()
                    implementer = null
                    part = null
                }
                "cpu implementer" -> implementer = value.removePrefix("0x").toIntOrNull(16)
                "cpu part" -> part = value.removePrefix("0x").toIntOrNull(16)
            }
        }
        flush()
        return result
    }

    /** The name of every CPU this process can identify. */
    fun namesByCpu(cpuDir: File, cpuinfo: File): Map<Int, String> {
        val result = HashMap<Int, String>()
        cpuDir.listFiles { f -> f.name.matches(Regex("cpu[0-9]+")) }.orEmpty().forEach { dir ->
            val cpu = dir.name.removePrefix("cpu").toIntOrNull() ?: return@forEach
            val midr = runCatching { File(dir, "regs/identification/midr_el1").readText() }.getOrNull() ?: return@forEach
            parseMidr(midr)?.let { (i, p) -> name(i, p) }?.let { result[cpu] = it }
        }
        if (result.isEmpty()) {
            val text = runCatching { cpuinfo.readText() }.getOrNull().orEmpty()
            parseCpuinfo(text).forEach { (cpu, id) -> name(id.first, id.second)?.let { result[cpu] = it } }
        }
        return result
    }
}

/** A cluster's place among the phone's clusters, by top clock: the screens name it. */
enum class ClusterRole { All, Little, Mid, Big, Prime;

    companion object {
        /**
         * Roles of [clusters] in their order: one cluster is the whole CPU;
         * otherwise the slowest is little and the fastest big, or prime when
         * it is a single core above a big cluster; any between are mid.
         */
        fun of(clusters: List<CpuCluster>): List<ClusterRole> {
            if (clusters.size <= 1) return List(clusters.size) { All }
            val order = clusters.indices.sortedWith(compareBy({ clusters[it].maxFreqKhz }, { clusters[it].policy }))
            val roles = Array(clusters.size) { Mid }
            roles[order.first()] = Little
            val top = order.last()
            roles[top] = if (clusters.size >= 3 && clusters[top].cpus.size == 1) Prime else Big
            if (roles[top] == Prime && clusters.size >= 3) roles[order[order.size - 2]] = Big
            return roles.toList()
        }
    }
}

/**
 * The language-neutral label a run keeps for a cluster: its core and count
 * ("Cortex-A715 ×3"), or its CPUs when the core is unknown ("CPU 4-6").
 */
fun CpuCluster.label(): String {
    val name = coreName ?: return if (cpus.size == 1) "CPU ${cpus.first()}" else "CPU ${cpus.first()}-${cpus.last()}"
    return "$name ×${cpus.size}"
}
