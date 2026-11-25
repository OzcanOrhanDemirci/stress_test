package dev.ozcan.stress.charge

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.ozcan.stress.MainActivity
import dev.ozcan.stress.R
import dev.ozcan.stress.run.RunRecord
import dev.ozcan.stress.settings.AppLanguages
import dev.ozcan.stress.ui.Format
import dev.ozcan.stress.ui.minutesText
import kotlin.math.roundToLong

/**
 * The app's one notification: a charge test that began below full has filled the battery. While a phone charges
 * nobody watches its screen, so the end is said where it is heard. Without the permission nothing is posted and the
 * test ends as it always did.
 */
class ChargeNotifier(private val app: Context) {

    fun batteryFull(record: RunRecord) {
        if (!allowed()) return
        val context = AppLanguages.localized(app)
        ensureChannel(context)
        val open = Intent(app, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_RUN, record.id)
        val tap = PendingIntent.getActivity(
            app, record.id.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = context.getString(R.string.charge_notify_title) + " " + summary(context, record)
        val notification = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            // One sentence and no title: some launchers join a title and its text with a dash.
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        // The permission can be withdrawn between the check and the post.
        try {
            NotificationManagerCompat.from(app).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w("stress-charge", "Could not post the battery-full notification", e)
        }
    }

    private fun allowed(): Boolean =
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(app).areNotificationsEnabled()

    private fun ensureChannel(context: Context) {
        // Created each time so its name follows the app's language.
        val channel = NotificationChannel(CHANNEL, context.getString(R.string.charge_notify_channel), NotificationManager.IMPORTANCE_DEFAULT)
        app.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** "Took 1 h 12 min. Peak 18.2 W, sustained 15.0 W." with what the record has; it follows "Battery full." */
    private fun summary(context: Context, record: RunRecord): String {
        val took = minutesText(context, (record.loadSeconds / 60).roundToLong().coerceAtLeast(1))
        val peak = record.summary.peakWatts?.let(Format::wattNumber)
        val sustained = record.summary.sustainedWatts?.let(Format::wattNumber)
        return when {
            peak == null -> context.getString(R.string.charge_notify_time, took)
            sustained == null -> context.getString(R.string.charge_notify_peak, took, peak)
            else -> context.getString(R.string.charge_notify_full, took, peak, sustained)
        }
    }

    companion object {
        /** The run a tap on the notification opens. */
        const val EXTRA_RUN = "dev.ozcan.stress.OPEN_RUN"
        private const val CHANNEL = "charge"
        private const val NOTIFICATION_ID = 1
    }
}
