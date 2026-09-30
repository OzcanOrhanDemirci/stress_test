package dev.ozcan.stress.engine

/** How the app names each GPU burner. Keyed by [GpuBurner.key]. */
object GpuCatalog {

    private val titles = mapOf(
        "gpu_fp32" to "FP32 ALU",
        "gpu_fp16" to "FP16 ALU",
        "gpu_texture" to "Doku örnekleme",
        "gpu_bandwidth" to "Bellek bant genişliği",
        "gpu_blend" to "Harmanlama (ROP)",
    )

    val keys: Set<String> get() = titles.keys

    fun title(burner: GpuBurner): String = titles[burner.key] ?: burner.key
}
