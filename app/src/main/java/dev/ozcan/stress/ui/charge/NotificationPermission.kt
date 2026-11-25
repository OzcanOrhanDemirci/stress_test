package dev.ozcan.stress.ui.charge

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import dev.ozcan.stress.R
import dev.ozcan.stress.ui.components.StressDialog
import dev.ozcan.stress.ui.theme.StressColors

/**
 * Before a charge test: asks for the notification that says the battery filled, then runs the start whatever the
 * answer. Android shows its dialog at most twice; the second time, when it asks for a reason, one line explains it
 * first. Once refused for good the start goes ahead with no question; the test simply ends without a notification.
 * Below Android 13 there is nothing to ask.
 */
@Composable
internal fun rememberNotificationAsk(): (then: () -> Unit) -> Unit {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return remember { { then -> then() } }
    val activity = LocalActivity.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    var explaining by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pending?.invoke()
        pending = null
    }
    if (explaining) {
        val ask = {
            explaining = false
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        StressDialog(
            onDismissRequest = ask,
            icon = { Icon(Icons.Rounded.NotificationsActive, null, tint = StressColors.Cool) },
            title = { Text(stringResource(R.string.charge_notify_rationale_title)) },
            text = { Text(stringResource(R.string.charge_notify_rationale)) },
            confirmButton = { TextButton(onClick = ask) { Text(stringResource(R.string.ok)) } },
        )
    }
    return { then ->
        if (activity == null || ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            then()
        } else {
            pending = then
            if (activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                explaining = true
            } else {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
