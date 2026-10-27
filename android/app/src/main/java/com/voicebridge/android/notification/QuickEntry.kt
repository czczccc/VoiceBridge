package com.voicebridge.android.notification

import android.Manifest
import android.annotation.SuppressLint
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
import com.voicebridge.android.MainActivity
import com.voicebridge.android.R

/**
 * The notification-shade shortcut to the input field.
 *
 * The notification carries a fixed title and never contains typed text, a computer name, or a
 * credential. Tapping it is the only thing that starts [MainActivity] and asks for the keyboard;
 * nothing here brings the app forward or shows the IME from the background.
 */
object QuickEntry {
    private const val TAG = "QuickEntry"
    private const val CHANNEL_ID = "quick_entry"
    private const val NOTIFICATION_ID = 1001
    private const val REQUEST_CODE = 0

    /** Whether notifications can be posted right now (runtime permission and app settings). */
    fun allowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /** Posts or refreshes the shortcut; does nothing when notifications are not allowed. */
    @SuppressLint("MissingPermission", "NotificationPermission") // allowed() checks the permission.
    fun post(context: Context) {
        if (!allowed(context)) return
        ensureChannel(context)

        val open = Intent(context, MainActivity::class.java)
            .setAction(QuickEntryAction.OPEN_INPUT)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_quick_entry)
            .setContentTitle(context.getString(R.string.quick_entry_notification_title))
            .setContentText(context.getString(R.string.quick_entry_notification_text))
            .setContentIntent(pending)
            .addAction(
                R.drawable.ic_quick_entry,
                context.getString(R.string.quick_entry_notification_action),
                pending,
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            // A shortcut should survive the tap, so the user can use it again; swiping removes it.
            .setAutoCancel(false)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (error: SecurityException) {
            // The user revoked the permission between the check and this call; the shortcut simply
            // stays unavailable, and the input page offers to enable it again.
            Log.w(TAG, "notification permission revoked before posting", error)
        }
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.quick_entry_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.quick_entry_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }
}
