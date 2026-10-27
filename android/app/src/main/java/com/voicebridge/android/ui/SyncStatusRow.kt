package com.voicebridge.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.voicebridge.android.R
import com.voicebridge.android.network.BridgeConnectionState
import com.voicebridge.android.network.BridgeSession
import com.voicebridge.android.pairing.StoredCredential

/**
 * One line that says whether the paired PC is reachable and whether it is taking input right now.
 *
 * [stored] comes from the pairing screen, which already owns the credential question, so this row
 * never guesses why the link is idle.
 */
@Composable
fun SyncStatusRow(
    stored: StoredCredential?,
    state: BridgeConnectionState,
    session: BridgeSession?,
    modifier: Modifier = Modifier,
) {
    val message: String
    val color: Color
    when {
        stored == null -> {
            message = stringResource(R.string.sync_unpaired)
            color = MaterialTheme.colorScheme.onSurfaceVariant
        }
        state is BridgeConnectionState.Error -> {
            message = stringResource(R.string.sync_error, state.code.name)
            color = MaterialTheme.colorScheme.error
        }
        state is BridgeConnectionState.Connected && session != null -> {
            message = stringResource(R.string.sync_syncing, session.targetLabel)
            color = MaterialTheme.colorScheme.primary
        }
        state is BridgeConnectionState.Connected -> {
            message = stringResource(R.string.sync_waiting)
            color = MaterialTheme.colorScheme.onSurfaceVariant
        }
        state is BridgeConnectionState.Reconnecting -> {
            message = stringResource(R.string.sync_reconnecting, state.attempt)
            color = MaterialTheme.colorScheme.onSurfaceVariant
        }
        else -> {
            message = stringResource(R.string.sync_connecting)
            color = MaterialTheme.colorScheme.onSurfaceVariant
        }
    }
    Text(text = message, style = MaterialTheme.typography.bodySmall, color = color, modifier = modifier)
}
