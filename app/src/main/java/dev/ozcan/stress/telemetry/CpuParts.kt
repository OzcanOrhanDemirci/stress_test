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
        0xd88 to "Cortex-A520AE", 0xd89 to "Cortex-A720AE", 0xd8a to "C1-Nano",
        0xd8b to "C1-Pro", 0xd8c to "C1-Ultra", 0xd90 to "C1-Premium",
    )

    private val QUALCOMM = mapOf(
        0x001 to "Oryon", 0x002 to "Oryon", 0x200 to "Kryo",
        0x800 to "Kryo Gold", 0x801 to "Kryo Silver", 0x802 to "Kryo Gold",
        0x803 to "Kryo Silver", 0x804 to "Kryo Gold", 0x805 to "Kryo Silver",
    )

    private val SAMSUNG = mapOf(0x002 to "Mongoose M3", 0x003 to "Mongoose M4", 0x004 to "Mongoose M5")

    data class CoreId(val implementer: Int, val part: Int, val variant: Int? = null) {
        fun name(): String? = CpuParts.name(implementer, part, variant)
    }

    /** The core's name, or null for an unknown part (the screens then name the cluster by its CPUs). */
    fun name(implementer: Int, part: Int, variant: Int? = null): String? = when (implementer) {
        0x41 -> ARM[part]
        0x51 -> QUALCOMM[part]
        0x53 -> if (part == 0x001) when (variant) {
            1 -> "Mongoose M1"
            4 -> "Mongoose M2"
            else -> "Mongoose"
        } else SAMSUNG[part]
        0x48 -> if (part == 0xd01) "TaiShan v110" else null
        else -> null
    }

    /** "0x00000000411fd462" -> implementer 0x41, part 0xd46, variant 1. */
    fun parseMidr(text: String): CoreId? {
        val value = text.trim().removePrefix("0x").removePrefix("0X").toLongOrNull(16) ?: return null
        val implementer = ((value shr 24) and 0xff).toInt()
        val part = ((value shr 4) and 0xfff).toInt()
        val variant = ((value shr 20) and 0xf).toInt()
        return if (implementer == 0) null else CoreId(implementer, part, variant)
    }

    /** Implementer and part of each processor in `/proc/cpuinfo`'s text, by CPU number. */
    fun parseCpuinfo(text: String): Map<Int, CoreId> {
        val result = HashMap<Int, CoreId>()
        var cpu: Int? = null
        var implementer: Int? = null
        var part: Int? = null
        var variant: Int? = null
        fun flush() {
            val c = cpu
            val i = implementer
            val p = part
            if (c != null && i != null && p != null) result[c] = CoreId(i, p, variant)
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
                    variant = null
                }
                "cpu implementer" -> implementer = value.lowercase(Locale.ROOT).removePrefix("0x").toIntOrNull(16)
                "cpu part" -> part = value.lowercase(Locale.ROOT).removePrefix("0x").toIntOrNull(16)
                "cpu variant" -> variant = value.lowercase(Locale.ROOT).removePrefix("0x").toIntOrNull(16)
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
            parseMidr(midr)?.name()?.let { result[cpu] = it }
        }
        val text = runCatching { cpuinfo.readText() }.getOrNull().orEmpty()
        parseCpuinfo(text).forEach { (cpu, id) -> id.name()?.let { result.putIfAbsent(cpu, it) } }
        return result
    }
}

/** A cluster's place among the phone's clusters, by top clock: the screens name it. */
enum class ClusterRole { All, Little, Mid, Big, Prime, Unknown;

    companion object {
        private val littleCores = setOf(
            "Cortex-A35", "Cortex-A53", "Cortex-A55", "Cortex-A510", "Cortex-A520", "Cortex-A520AE", "Kryo Silver", "C1-Nano",
        )
        /**
         * Rank the frequency tiers while keeping known little cores separate
         * from all-big designs. A lone fastest core (or an Oryon pair) can
         * be a prime tier if another performance cluster sits below it.
         */
        fun of(clusters: List<CpuCluster>, cpuCount: Int = clusters.sumOf { it.cpus.size }): List<ClusterRole> {
            if (clusters.sumOf { it.cpus.size } < cpuCount) return List(clusters.size) { Unknown }
            if (clusters.size <= 1) return List(clusters.size) { All }
            if (clusters.any { it.maxFreqKhz <= 0 }) return List(clusters.size) { Unknown }
            val order = clusters.indices.sortedWith(compareBy({ clusters[it].maxFreqKhz }, { clusters[it].policy }))
            val roles = Array(clusters.size) { Mid }
            for (i in order) if (clusters[i].coreName in littleCores) roles[i] = Little
            if (roles.all { it == Little }) return roles.toList()
            if (roles.none { it == Little } && clusters[order.first()].coreName == null) roles[order.first()] = Little
            val top = order.last()
            val next = order[order.size - 2]
            val distinctTop = clusters[top].maxFreqKhz > clusters[next].maxFreqKhz && roles[next] != Little
            val prime = distinctTop && (clusters[top].cpus.size == 1 ||
                (clusters[top].coreName == "Oryon" && clusters[top].cpus.size == 2))
            roles[top] = if (prime) Prime else Big
            if (prime || (clusters.size >= 4 && roles[next] != Little)) roles[next] = Big
            return roles.toList()
        }
    }
}

/**
 * The language-neutral label a run keeps for a cluster: its core and count
 * ("Cortex-A715 ×3"), or its CPUs when the core is unknown ("CPU 4-6").
 */
fun CpuCluster.label(): String {
    val name = coreName ?: return if (cpus.size > 1 && cpus.last() - cpus.first() + 1 == cpus.size) {
        "CPU ${cpus.first()}-${cpus.last()}"
    } else {
        "CPU ${cpus.joinToString(",")}"
    }
    return "$name ×${cpus.size}"
}
