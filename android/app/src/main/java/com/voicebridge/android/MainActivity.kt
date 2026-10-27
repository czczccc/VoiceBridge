package com.voicebridge.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.voicebridge.android.notification.QuickEntry
import com.voicebridge.android.notification.QuickEntryAction
import com.voicebridge.android.ui.InputScreen
import com.voicebridge.android.ui.PairingScreen
import com.voicebridge.android.update.UpdatePanel

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
                WelcomeScreen(openInputRequests = openInputRequests)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (QuickEntryAction.matches(intent.action)) openInputRequests++
    }
}

@Composable
private fun WelcomeScreen(openInputRequests: Int) {
    Scaffold { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .padding(contentPadding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineLarge,
            )
            Text(text = stringResource(R.string.tagline))
            Text(
                text = stringResource(R.string.welcome),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(text = stringResource(R.string.baseline_message))
            InputScreen(openInputRequests = openInputRequests)
            PairingScreen()
            UpdatePanel()
        }
    }
}
