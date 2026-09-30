package dev.ozcan.stress.telemetry

import android.content.Context
import android.os.PowerManager

/** Android's own view of the phone's heat: the thermal status and the headroom before throttling. */
class ThermalReader(context: Context) {

    private val power = context.getSystemService(PowerManager::class.java)

    /** One of the `PowerManager.THERMAL_STATUS_*` constants. */
    fun status(): Int = power.currentThermalStatus

    /**
     * 1.0 means the device is at its throttling threshold. The platform
     * returns NaN when asked more often than about once a second, so the
     * sampler asks at 1 Hz.
     */
    fun headroom(): Float? = power.getThermalHeadroom(0).takeUnless { it.isNaN() }
}
