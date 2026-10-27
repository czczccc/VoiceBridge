package com.voicebridge.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.voicebridge.android.R
import kotlinx.coroutines.delay

/**
 * The text area voice input flows through. The app never records audio and never talks to an ASR
 * engine: the user taps the microphone of whichever IME they already use, and that IME writes into
 * this field, exactly like any other text box.
 *
 * Focus is requested when the screen appears, and again whenever [openInputRequests] changes (the
 * notification shortcut). The system IME is asked to show each time. Android may refuse or delay
 * that request, so the field remains tappable either way.
 */
@Composable
fun InputScreen(
    model: InputViewModel = viewModel(),
    sync: SyncViewModel = viewModel(),
    openInputRequests: Int = 0,
) {
    val value by model.value.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(openInputRequests) {
        focusRequester.requestFocus()
        // A notification tap can deliver onNewIntent just before the window regains focus, and an
        // IME request made at that instant is dropped. The delay does not make this a background
        // request: the activity is already coming to the front because the user tapped.
        delay(150)
        keyboard?.show()
    }
    // The field is the only writer the snapshot layer listens to, so it also owns the attachment.
    DisposableEffect(model, sync) {
        sync.attachInput(model)
        onDispose { sync.detachInput() }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(R.string.input_title),
            style = MaterialTheme.typography.titleMedium,
        )
        OutlinedButton(onClick = sync::finishSegment, enabled = value.text.isNotEmpty()) {
            Text(text = stringResource(R.string.input_new_segment))
        }
    }
    OutlinedTextField(
        value = value,
        onValueChange = model::onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 140.dp)
            .focusRequester(focusRequester)
            .padding(top = 4.dp),
        label = { Text(text = stringResource(R.string.input_hint)) },
        minLines = 4,
    )
    Text(
        text = stringResource(R.string.input_ime_note),
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        text = stringResource(R.string.input_segment_note),
        style = MaterialTheme.typography.bodySmall,
    )
    QuickEntryRow()
}
