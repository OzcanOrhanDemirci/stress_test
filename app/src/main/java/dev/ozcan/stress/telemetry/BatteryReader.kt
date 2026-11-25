package dev.ozcan.stress.telemetry

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import dev.ozcan.stress.analysis.Power
import java.util.concurrent.atomic.AtomicLong

/**
 * One reading of the battery. The instantaneous values come from
 * [BatteryManager] properties (the fuel gauge, via the health HAL); voltage,
 * temperature and plug state only arrive with the battery broadcast, so they
 * carry their age.
 */
data class BatteryReading(
    /** `BATTERY_PROPERTY_CURRENT_NOW` as reported: unit and sign are device specific, see CurrentConvention. */
    val currentRaw: Long?,
    val chargeCounterMicroAmpHours: Long?,
    val voltageMillivolts: Int?,
    val voltageAgeNanos: Long?,
    val temperatureCelsius: Double?,
    val levelPercent: Int?,
    val plugged: Boolean,
    /** Battery broadcasts received since the reader started; shows how often voltage refreshes. */
    val broadcasts: Long,
    val energyCounterNanoWattHours: Long? = null,
    val plugStateKnown: Boolean = true,
    val currentFresh: Boolean = true,
) {
    val onBattery: Boolean get() = plugStateKnown && !plugged
    val measurableCurrent: Long? get() = currentRaw.takeIf { onBattery && currentFresh }
}

/** CURRENT_NOW has no driver timestamp. Use changes in the raw value to establish cadence. */
internal class CurrentFreshness {
    private var previous: Long? = null
    private var lastChange = 0L
    private var fastChanges = 0

    fun update(raw: Long?, onBattery: Boolean, now: Long): Boolean {
        if (!onBattery || raw == null || raw == 0L) {
            previous = null
            fastChanges = 0
            return false
        }
        if (raw != previous) {
            fastChanges = if (previous != null && now - lastChange <= FAST_CHANGE_NANOS) {
                (fastChanges + 1).coerceAtMost(2)
            } else {
                0
            }
            previous = raw
            lastChange = now
        }
        // Allow repeated reads of a fast gauge, but require two changes to
        // qualify or recover. A gauge refreshing only every 10–60 s cannot
        // measure peaks or reproduce the shape of a short load.
        return fastChanges == 2 && now - lastChange < MAX_HOLD_NANOS
    }

    private companion object {
        const val FAST_CHANGE_NANOS = 5_000_000_000L
        const val MAX_HOLD_NANOS = 10_000_000_000L
    }
}

class BatteryReader(private val context: Context) {

    private data class Broadcast(
        val voltageMillivolts: Int,
        val temperatureTenths: Int,
        val level: Int,
        val scale: Int,
        val plugged: Int,
        val receivedNanos: Long,
    )

    private val manager = context.getSystemService(BatteryManager::class.java)
    private val currentFreshness = CurrentFreshness()

    @Volatile private var last: Broadcast? = null
    private val broadcastCount = AtomicLong()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = accept(intent)
    }

    private var registered = false

    @Synchronized
    fun start() {
        if (registered) return
        last = null
        currentFreshness.update(null, false, 0)
        // The battery broadcast is sticky: registering returns the latest one.
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )?.let(::accept)
        registered = true
    }

    @Synchronized
    fun stop() {
        if (!registered) return
        context.unregisterReceiver(receiver)
        registered = false
    }

    private fun accept(intent: Intent) {
        last = Broadcast(
            voltageMillivolts = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1),
            temperatureTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE),
            level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
            scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
            plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1),
            receivedNanos = SystemClock.elapsedRealtimeNanos(),
        )
        broadcastCount.incrementAndGet()
    }

    fun read(nowNanos: Long = SystemClock.elapsedRealtimeNanos()): BatteryReading {
        val b = last
        val level = b?.takeIf { it.scale > 0 && it.level in 0..it.scale }
            ?.let { (it.level * 100L / it.scale).toInt() }
        val plugState = b?.plugged?.takeIf { it >= 0 }
        val current = property(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        return BatteryReading(
            currentRaw = current,
            chargeCounterMicroAmpHours = property(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                ?.let { Power.normalizeChargeCounter(it, level) },
            voltageMillivolts = b?.voltageMillivolts?.let(Power::normalizeMillivolts),
            voltageAgeNanos = b?.let { nowNanos - it.receivedNanos },
            temperatureCelsius = b?.temperatureTenths?.takeIf { it in -399..1000 }?.let { it / 10.0 },
            levelPercent = level,
            plugged = plugState != null && plugState != 0,
            broadcasts = broadcastCount.get(),
            energyCounterNanoWattHours = property(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)
                ?.takeIf { it in 1..300_000_000_000L },
            plugStateKnown = plugState != null,
            currentFresh = currentFreshness.update(current, plugState == 0, nowNanos),
        )
    }

    /** Unsupported properties read as [Long.MIN_VALUE]. */
    private fun property(id: Int): Long? = runCatching { manager?.getLongProperty(id) }.getOrNull()
        ?.takeUnless { it == Long.MIN_VALUE || it == Int.MIN_VALUE.toLong() }
}
