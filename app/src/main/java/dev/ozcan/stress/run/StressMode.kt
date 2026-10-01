package dev.ozcan.stress.run

/**
 * What the user can run. Each mode is a lab workload in text form, so the
 * recipes are measured with exactly the code the app runs.
 *
 * The recipes are the winners of the candidate sweeps in docs/OLCUMLER.md
 * (2026-10-01): C8 fp32_l2 on every core, the FP32 burner on the GPU.
 */
enum class StressMode(val title: String, val detail: String, val recipe: String, val usesGpu: Boolean) {
    Full(
        "Tam yük",
        "CPU, GPU ve bellek aynı anda: telefonun çekebildiği en yüksek güç.",
        "fp32_l2+gpu_fp32",
        usesGpu = true,
    ),
    Cpu(
        "CPU",
        "Sekiz çekirdeğin hepsi, en çok güç çeken çekirdek yüküyle.",
        "fp32_l2",
        usesGpu = false,
    ),
    Gpu(
        "GPU",
        "Reaktör sahnesi ve altında GPU yakıcısı.",
        "gpu_fp32",
        usesGpu = true,
    ),
    Dry(
        "Kuru %100",
        "Çekirdekler %100 dolu görünür ama birimleri boştadır. Prime95 ile Cinebench farkını görmek için.",
        "dry",
        usesGpu = false,
    ),
}

/** How long the load runs; null runs until stopped. */
enum class StressDuration(val title: String, val seconds: Int?) {
    Five("5 dk", 5 * 60),
    Fifteen("15 dk", 15 * 60),
    Thirty("30 dk", 30 * 60),
    Endless("Durdurana kadar", null),
}
