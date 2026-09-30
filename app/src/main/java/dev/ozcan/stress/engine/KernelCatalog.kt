package dev.ozcan.stress.engine

/** How the app names and explains each kernel. Keyed by [CpuKernel.key]. */
object KernelCatalog {

    data class Description(val title: String, val detail: String)

    private val descriptions = mapOf(
        "dry" to Description(
            "Kuru %100",
            "Birbirine bağlı tamsayı toplamaları. Çekirdek %100 dolu görünür ama birimlerinin çoğu boştadır. " +
                "Gerçek yükler bununla karşılaştırılır.",
        ),
        "fp32_reg" to Description(
            "FP32 FMA · yazmaç",
            "Yalnız yazmaçlarda vektör FMA; bellek trafiği yok.",
        ),
        "fp32_gemm" to Description(
            "FP32 FMA · L1",
            "SGEMM mikro çekirdeği gibi: L1'den beslenen 8×12 dış çarpım, 24 toplayıcı.",
        ),
        "fp64_gemm" to Description(
            "FP64 FMA · L1",
            "Çift duyarlıklı vektör FMA, L1'den beslenen 8×6 dış çarpım.",
        ),
        "bf16_mmla" to Description(
            "BF16 matris",
            "BFMMLA matris çarpma-toplama komutu, L1'den beslenir.",
        ),
        "i8_mmla" to Description(
            "INT8 matris",
            "SMMLA matris çarpma-toplama komutu, L1'den beslenir.",
        ),
        "i8_dot" to Description(
            "INT8 nokta çarpımı",
            "SDOT nokta çarpımı, L1'den beslenir.",
        ),
        "mixed" to Description(
            "Bütün kapılar",
            "FP32 FMA'nın yanında tamsayı zinciri, çarpıcı ve yazma aynı anda.",
        ),
        "fp32_l2" to Description(
            "FP32 FMA · L2",
            "İşlenenler 256 KiB'lık tampondan akar; önbellekler de çalışır.",
        ),
        "fp32_dram" to Description(
            "FP32 FMA · RAM",
            "İşlenenler 32 MiB'lık tampondan akar; bellek denetleyicisi ve DDR de çalışır.",
        ),
        "memcopy" to Description(
            "RAM kopyalama",
            "32 MiB'lık tamponda okuma ve yazma: saf bellek bant genişliği.",
        ),
        "fp32_s128k" to Description("FP32 FMA · 128 KiB", "C8'in 128 KiB'lık tamponla çalışan hâli."),
        "fp32_s512k" to Description("FP32 FMA · 512 KiB", "C8'in 512 KiB'lık tamponla çalışan hâli."),
        "fp32_s1m" to Description("FP32 FMA · 1 MiB", "C8'in 1 MiB'lık tamponla çalışan hâli."),
        "fp32_s2m" to Description("FP32 FMA · 2 MiB", "C8'in 2 MiB'lık tamponla çalışan hâli."),
    )

    val keys: Set<String> get() = descriptions.keys

    fun describe(kernel: CpuKernel): Description =
        descriptions[kernel.key] ?: Description(kernel.code, kernel.key)
}
