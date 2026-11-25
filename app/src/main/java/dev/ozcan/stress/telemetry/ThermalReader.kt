package dev.ozcan.stress.telemetry

import android.content.Context
import android.os.Build
import android.os.PowerManager

/** Android's own view of the phone's heat: the thermal status and the headroom before throttling. */
class ThermalReader(context: Context) {

    private val power = context.getSystemService(PowerManager::class.java)

    /** One of the `PowerManager.THERMAL_STATUS_*` constants. */
    fun status(): Int = runCatching { power?.currentThermalStatus }.getOrNull()
        ?.takeIf { it in PowerManager.THERMAL_STATUS_NONE..PowerManager.THERMAL_STATUS_SHUTDOWN } ?: UNAVAILABLE

    /**
     * 1.0 is Android's severe throttling threshold (Android 11 and later).
     * The platform can return NaN for an unsupported device or a fast poll.
     */
    fun headroom(): Float? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { power?.getThermalHeadroom(0) }.getOrNull()?.takeIf { it.isFinite() && it >= 0f }
        } else {
            null
        }

    companion object {
        const val UNAVAILABLE = -1
    }
}
