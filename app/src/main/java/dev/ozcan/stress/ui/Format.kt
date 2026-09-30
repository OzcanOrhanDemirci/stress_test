package dev.ozcan.stress.ui

import dev.ozcan.stress.engine.WorkUnit
import java.util.Locale

/** Number formatting for the screens: Turkish decimal comma, fixed decimals, a dash for missing values. */
object Format {
    private val locale = Locale.forLanguageTag("tr-TR")
    const val MISSING = "—"

    fun number(value: Double?, decimals: Int): String =
        value?.let { String.format(locale, "%.${decimals}f", it) } ?: MISSING

    fun watts(value: Double?): String = value?.let { "${number(it, 2)} W" } ?: MISSING

    fun celsius(value: Double?): String = value?.let { "${number(it, 1)} °C" } ?: MISSING

    fun mhz(khz: Long?): String = khz?.let { "${it / 1000} MHz" } ?: MISSING

    fun percent(fraction: Double?): String = fraction?.let { "%${number(it * 100, 0)}" } ?: MISSING

    /** 1.234e11 FLOPS -> "123,4 GFLOPS". */
    fun rate(value: Double?, unit: WorkUnit?): String {
        if (value == null || unit == null) return MISSING
        val (scaled, prefix) = when {
            value >= 1e12 -> value / 1e12 to "T"
            value >= 1e9 -> value / 1e9 to "G"
            value >= 1e6 -> value / 1e6 to "M"
            value >= 1e3 -> value / 1e3 to "k"
            else -> value to ""
        }
        return "${number(scaled, 1)} $prefix${unit.rateSymbol}"
    }
}
