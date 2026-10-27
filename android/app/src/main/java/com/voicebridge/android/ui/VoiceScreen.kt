package com.voicebridge.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voicebridge.android.R
import com.voicebridge.android.pairing.StoredCredential
import kotlinx.coroutines.delay

/**
 * The only page most users see: connection state, the current segment, and the segment button.
 *
 * Speech recognition belongs to the user's IME. This screen keeps the field focused and asks the
 * system for the keyboard, but it never records audio, never starts recognition and never taps the
 * IME's microphone. Pairing, the notification shortcut and updates live behind Settings.
 */
@Composable
fun VoiceScreen(
    input: InputViewModel,
    sync: SyncViewModel,
    stored: StoredCredential?,
    openInputRequests: Int,
    onOpenSettings: () -> Unit,
) {
    val value by input.value.collectAsStateWithLifecycle()
    val syncState by sync.state.collectAsStateWithLifecycle()
    val session by sync.session.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0

    LaunchedEffect(openInputRequests) {
        focusRequester.requestFocus()
        // A notification tap can deliver onNewIntent just before the window regains focus, and an
        // IME request made at that instant is dropped. The delay is still user-initiated.
        delay(150)
        keyboard?.show()
    }
    // Returning from the background should put the microphone back within reach.
    LifecycleResumeEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
        onPauseOrDispose { }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.input_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                SyncStatusRow(stored = stored, state = syncState, session = session)
            }
            TextButton(onClick = onOpenSettings) {
                Text(text = stringResource(R.string.voice_settings))
            }
        }

        if (stored == null) {
            Text(
                text = stringResource(R.string.voice_unpaired),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onOpenSettings) {
                Text(text = stringResource(R.string.voice_pair_action))
            }
        }

        OutlinedTextField(
            value = value,
            onValueChange = input::onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 96.dp, max = 220.dp)
                .focusRequester(focusRequester),
            placeholder = { Text(text = stringResource(R.string.voice_hint)) },
            minLines = 2,
            maxLines = 5,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = sync::finishSegment,
                enabled = value.text.isNotEmpty(),
            ) {
                Text(text = stringResource(R.string.input_new_segment))
            }
            if (!imeVisible) {
                Text(
                    text = stringResource(R.string.voice_continue_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Text(
            text = stringResource(R.string.input_ime_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
