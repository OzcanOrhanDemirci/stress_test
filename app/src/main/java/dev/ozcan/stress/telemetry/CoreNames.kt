package dev.ozcan.stress.telemetry

/** Names of the Honor 400's three clusters, keyed by cpufreq policy. */
object CoreNames {
    fun of(cluster: CpuCluster): String = when (cluster.policy) {
        0 -> "A510 ×${cluster.cpus.size}"
        4 -> "A715 ×${cluster.cpus.size}"
        7 -> "A715 prime"
        else -> "policy${cluster.policy}"
    }

    /** Four-letter names for tight rows. */
    fun short(cluster: CpuCluster): String = when (cluster.policy) {
        0 -> "A510"
        4 -> "A715"
        7 -> "Prime"
        else -> "p${cluster.policy}"
    }
}
