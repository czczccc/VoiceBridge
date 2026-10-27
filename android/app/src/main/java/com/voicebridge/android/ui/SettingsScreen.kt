package com.voicebridge.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.voicebridge.android.R
import com.voicebridge.android.update.UpdatePanel

/**
 * Everything that is not speaking: pairing, the notification shortcut and updates. Discovery only
 * runs while this page is open, so the voice screen does not keep browsing the LAN.
 */
@Composable
fun SettingsScreen(
    pairing: PairingViewModel,
    sync: SyncViewModel,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) {
                Text(text = stringResource(R.string.settings_back))
            }
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.titleLarge,
            )
        }
        PairingScreen(viewModel = pairing, sync = sync)
        QuickEntryRow()
        UpdatePanel()
    }
}
