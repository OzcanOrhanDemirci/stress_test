package dev.ozcan.stress.ui

import java.util.Locale

/**
 * Number formatting for the screens and the report: the device's language
 * decides the decimal separator and where the percent sign goes; fixed
 * decimals; a dash for missing values.
 */
object Format {
    const val MISSING = "—"

    private val locale: Locale get() = Locale.getDefault()

    /** Turkish writes the percent sign first: %45. */
    private val percentFirst: Boolean get() = locale.language == "tr"

    fun number(value: Double?, decimals: Int): String =
        value?.takeIf { it.isFinite() }?.let { String.format(locale, "%.${decimals}f", it) } ?: MISSING

    fun watts(value: Double?): String = value?.let { "${number(it, 2)} W" } ?: MISSING

    fun celsius(value: Double?, decimals: Int = 1): String = value?.let { "${number(it, decimals)} °C" } ?: MISSING

    fun mhz(khz: Long?): String = khz?.let { "${it / 1000} MHz" } ?: MISSING

    /** 0.452 -> "%45" in Turkish, "45%" elsewhere; a minus goes before the sign: "-%6". */
    fun percent(fraction: Double?, decimals: Int = 0): String {
        if (fraction == null || !fraction.isFinite()) return MISSING
        val text = number(kotlin.math.abs(fraction) * 100, decimals)
        val sign = if (fraction < 0 && text.any { it in '1'..'9' }) "-" else ""
        return sign + if (percentFirst) "%$text" else "$text%"
    }

    /** A whole percentage: 45 -> "%45" / "45%". */
    fun percentValue(percent: Int?): String {
        if (percent == null) return MISSING
        return if (percentFirst) "%$percent" else "$percent%"
    }

    /** 1.234e11 with a unit symbol -> "123,4 GFLOPS". */
    fun rate(value: Double?, symbol: String?): String {
        if (value == null || symbol == null) return MISSING
        val (scaled, prefix) = scaled(value)
        return "${number(scaled, 1)} $prefix$symbol"
    }

    /** A rate split for a tile: 1.234e11 FLOPS -> ("123,4", "GFLOPS"). */
    fun rateParts(value: Double?, symbol: String?): Pair<String, String?> {
        if (value == null || symbol == null) return MISSING to null
        val (scaled, prefix) = scaled(value)
        return number(scaled, 1) to "$prefix$symbol"
    }

    /** A value and its SI prefix: 1.234e11 -> (123.4, "G"). */
    fun scaled(value: Double): Pair<Double, String> = when {
        value >= 1e12 -> value / 1e12 to "T"
        value >= 1e9 -> value / 1e9 to "G"
        value >= 1e6 -> value / 1e6 to "M"
        value >= 1e3 -> value / 1e3 to "k"
        else -> value to ""
    }

    /** 125 s -> "2:05"; an hour or more -> "1:02:05". */
    fun clock(seconds: Double): String {
        val total = seconds.toLong().coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(Locale.ROOT, h, m, s) else "%d:%02d".format(Locale.ROOT, m, s)
    }

    /** An axis label: "0:00", "2:30", "1:05:00". */
    fun elapsedShort(seconds: Double): String = clock(seconds)
}

/** Dates in the device's language. */
object Dates {
    /** "2 Eki · 03:15" */
    fun short(millis: Long): String =
        java.text.SimpleDateFormat("d MMM · HH:mm", Locale.getDefault()).format(java.util.Date(millis))

    /** "2 Ekim 2026 · 03:15" */
    fun long(millis: Long): String =
        java.text.SimpleDateFormat("d MMMM yyyy · HH:mm", Locale.getDefault()).format(java.util.Date(millis))
}

/** Upper case by the device's language: Turkish "i" becomes "İ", not "I". */
fun String.upper(): String = uppercase(Locale.getDefault())
