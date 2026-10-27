package com.voicebridge.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.voicebridge.android.notification.QuickEntry
import com.voicebridge.android.notification.QuickEntryAction
import com.voicebridge.android.ui.VoiceBridgeApp

class MainActivity : ComponentActivity() {
    /**
     * Counts notification taps so the input field can grab focus again even when the activity was
     * already running in the background. A plain counter keeps repeated taps distinct.
     */
    private var openInputRequests by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        QuickEntry.post(this)
        if (QuickEntryAction.matches(intent?.action)) openInputRequests++
        setContent {
            MaterialTheme {
                VoiceBridgeApp(openInputRequests = openInputRequests)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (QuickEntryAction.matches(intent.action)) openInputRequests++
    }
}
