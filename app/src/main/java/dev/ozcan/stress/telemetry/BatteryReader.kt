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
)

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

    @Volatile private var last: Broadcast? = null
    private val broadcastCount = AtomicLong()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = accept(intent)
    }

    private var registered = false

    @Synchronized
    fun start() {
        if (registered) return
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
            plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0),
            receivedNanos = SystemClock.elapsedRealtimeNanos(),
        )
        broadcastCount.incrementAndGet()
    }

    fun read(nowNanos: Long = SystemClock.elapsedRealtimeNanos()): BatteryReading {
        val b = last
        return BatteryReading(
            currentRaw = property(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
            chargeCounterMicroAmpHours = property(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)?.let(Power::normalizeChargeCounter),
            voltageMillivolts = b?.voltageMillivolts?.takeIf { it > 0 },
            voltageAgeNanos = b?.let { nowNanos - it.receivedNanos },
            temperatureCelsius = b?.temperatureTenths?.takeIf { it != Int.MIN_VALUE }?.let { it / 10.0 },
            levelPercent = b?.takeIf { it.level >= 0 && it.scale > 0 }?.let { it.level * 100 / it.scale },
            plugged = (b?.plugged ?: 0) != 0,
            broadcasts = broadcastCount.get(),
        )
    }

    /** Unsupported properties read as [Long.MIN_VALUE]. */
    private fun property(id: Int): Long? = manager.getLongProperty(id).takeIf { it != Long.MIN_VALUE }
}
