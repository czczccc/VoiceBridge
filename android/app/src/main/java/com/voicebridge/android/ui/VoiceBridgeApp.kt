package com.voicebridge.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * The activity-level shell. The credential and the bridge live here rather than on the pairing
 * screen, so the PC stays connected while the voice screen is the only visible page.
 *
 * A notification tap switches back to the voice screen even when Settings was open.
 */
@Composable
fun VoiceBridgeApp(openInputRequests: Int) {
    val pairing: PairingViewModel = viewModel()
    val sync: SyncViewModel = viewModel()
    val input: InputViewModel = viewModel()
    val stored by pairing.stored.collectAsStateWithLifecycle()
    var settingsVisible by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(stored) { sync.useCredential(stored) }
    // The field is observed for the whole activity lifetime, including while Settings is open, so
    // the three-second segment rule keeps working when the user steps away to change a setting.
    DisposableEffect(input, sync) {
        sync.attachInput(input)
        onDispose { sync.detachInput() }
    }
    LaunchedEffect(openInputRequests) {
        if (openInputRequests > 0) settingsVisible = false
    }

    Scaffold { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            if (settingsVisible) {
                SettingsScreen(
                    pairing = pairing,
                    sync = sync,
                    onBack = { settingsVisible = false },
                )
            } else {
                VoiceScreen(
                    input = input,
                    sync = sync,
                    stored = stored,
                    openInputRequests = openInputRequests,
                    onOpenSettings = { settingsVisible = true },
                )
            }
        }
    }
}
