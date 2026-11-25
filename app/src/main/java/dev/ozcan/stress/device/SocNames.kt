package dev.ozcan.stress.device

import java.util.Locale

/**
 * Marketing names of common phone chips by the part number Android reports
 * (`Build.SOC_MODEL`). Only names that are certain; any other chip keeps its
 * part number.
 */
object SocNames {

    private val NAMES = mapOf(
        // Qualcomm Snapdragon
        "SM8750" to "Snapdragon 8 Elite",
        "SM8650" to "Snapdragon 8 Gen 3",
        "SM8635" to "Snapdragon 8s Gen 3",
        "SM8550" to "Snapdragon 8 Gen 2",
        "SM8475" to "Snapdragon 8+ Gen 1",
        "SM8450" to "Snapdragon 8 Gen 1",
        "SM8350" to "Snapdragon 888",
        "SM8250" to "Snapdragon 865",
        "SM8150" to "Snapdragon 855",
        "SM7675" to "Snapdragon 7+ Gen 3",
        "SM7635" to "Snapdragon 7s Gen 3",
        "SM7550" to "Snapdragon 7 Gen 3",
        "SM7475" to "Snapdragon 7+ Gen 2",
        "SM7450" to "Snapdragon 7 Gen 1",
        "SM7325" to "Snapdragon 778G",
        "SM7250" to "Snapdragon 765G",
        "SM7225" to "Snapdragon 750G",
        "SM7150" to "Snapdragon 730",
        "SM6450" to "Snapdragon 6 Gen 1",
        "SM6375" to "Snapdragon 695",
        "SM6225" to "Snapdragon 680",
        "SM6115" to "Snapdragon 662",
        "SM4450" to "Snapdragon 4 Gen 2",
        "SM4375" to "Snapdragon 4 Gen 1",
        "SM4350" to "Snapdragon 480",
        // MediaTek Dimensity
        "MT6991" to "Dimensity 9400",
        "MT6989" to "Dimensity 9300",
        "MT6985" to "Dimensity 9200",
        "MT6983" to "Dimensity 9000",
        "MT6897" to "Dimensity 8300",
        "MT6896" to "Dimensity 8200",
        "MT6895" to "Dimensity 8100",
        "MT6893" to "Dimensity 1200",
        // Exynos and Tensor platform identifiers
        "S5E9945" to "Exynos 2400",
        "S5E9925" to "Exynos 2200",
        "S5E9840" to "Exynos 2100",
        "GS101" to "Google Tensor",
        "GS201" to "Google Tensor G2",
        "ZUMA" to "Google Tensor G3",
        "ZUMAPRO" to "Google Tensor G4",
        "HI3660" to "Kirin 960",
        "HI3670" to "Kirin 970",
    )

    private val PLATFORMS = mapOf("SUN" to "SM8750", "PINEAPPLE" to "SM8650", "KALAMA" to "SM8550")
    private val UNISOC = Regex("(SC[0-9]+[A-Z]*|UMS[0-9]+[A-Z]*)")
    private val SNAPDRAGON_BIN = Regex("SM[0-9]{4}-[A-Z]{2}")
    private val GENERIC = setOf(
        "UNKNOWN", "UNDEFINED", "N/A", "NONE", "GENERIC",
        "QCOM", "QTI", "QUALCOMM", "MTK", "MEDIATEK", "SAMSUNG", "EXYNOS", "GOOGLE", "UNISOC", "SPRD",
    )

    /** A model is stronger evidence than a board name. Generic values like `qcom` carry no model. */
    fun resolve(maker: String?, model: String?, hardware: String?, board: String?, platform: () -> String?): String? {
        val soc = identifier(model)
        if (soc != null) return display(maker, soc)
        val candidates = listOfNotNull(identifier(platform()), identifier(hardware), identifier(board))
        val best = candidates.firstOrNull { marketingName(it.uppercase(Locale.ROOT)) != null } ?: candidates.firstOrNull()
        return best?.let { display(maker, it) }
    }

    /** "SM7550" -> "Snapdragon 7 Gen 3 (SM7550)"; unknown parts as given. */
    fun display(maker: String?, model: String): String {
        val part = model.trim()
        val key = part.uppercase(Locale.ROOT)
        val name = marketingName(key)
        val vendor = maker?.trim()?.takeUnless { it.isEmpty() || it.equals("unknown", ignoreCase = true) }
        return when {
            name != null -> "$name ($part)"
            UNISOC.matches(key) -> "Unisoc $part"
            vendor != null && !part.startsWith(vendor, ignoreCase = true) -> "$vendor $part"
            else -> part
        }
    }

    private fun identifier(value: String?): String? =
        value?.trim()?.takeIf { it.isNotEmpty() && it.uppercase(Locale.ROOT) !in GENERIC }

    private fun marketingName(key: String): String? {
        val part = PLATFORMS[key] ?: key
        return NAMES[part] ?: part.takeIf(SNAPDRAGON_BIN::matches)
            ?.substringBefore('-')?.let(NAMES::get)
    }
}
