package com.voicebridge.android.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.voicebridge.android.R
import com.voicebridge.android.notification.QuickEntry

/**
 * Offers the notification-shade shortcut and explains why it is missing.
 *
 * The runtime permission is requested only after the user taps the button, so someone who never
 * wants the shortcut never sees a permission dialog. A denial is reported here and changes nothing
 * else: typing, pairing and syncing keep working.
 */
@Composable
fun QuickEntryRow() {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(QuickEntry.allowed(context)) }
    var requested by rememberSaveable { mutableStateOf(false) }
    var notice by remember { mutableStateOf<Int?>(null) }

    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        requested = true
        allowed = QuickEntry.allowed(context)
        if (allowed) QuickEntry.post(context)
    }

    // Coming back from system settings should show the new state without a restart.
    LifecycleResumeEffect(Unit) {
        allowed = QuickEntry.allowed(context)
        onPauseOrDispose { }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.quick_entry_title),
            style = MaterialTheme.typography.titleSmall,
        )
        OutlinedButton(
            onClick = {
                notice = null
                when {
                    allowed -> QuickEntry.post(context)
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !requested ->
                        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else -> try {
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                        )
                    } catch (error: ActivityNotFoundException) {
                        notice = R.string.quick_entry_settings_error
                    }
                }
            },
        ) {
            val label = when {
                allowed -> R.string.quick_entry_show
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !requested ->
                    R.string.quick_entry_enable
                else -> R.string.quick_entry_settings
            }
            Text(text = stringResource(label))
        }
    }
    if (!allowed) {
        Text(
            text = stringResource(R.string.quick_entry_denied),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    notice?.let { message ->
        Text(
            text = stringResource(message),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
