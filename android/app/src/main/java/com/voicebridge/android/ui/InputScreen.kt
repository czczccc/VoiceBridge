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

/**
 * The text area voice input flows through. The app never records audio and never talks to an ASR
 * engine: the user taps the microphone of whichever IME they already use, and that IME writes into
 * this field, exactly like any other text box.
 *
 * Focus is requested when the screen appears, and the system IME is asked to show. Android may
 * refuse or delay that request, so the field remains tappable either way.
 */
@Composable
fun InputScreen(model: InputViewModel = viewModel()) {
    val value by model.value.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(R.string.input_title),
            style = MaterialTheme.typography.titleMedium,
        )
        OutlinedButton(onClick = model::clear, enabled = value.text.isNotEmpty()) {
            Text(text = stringResource(R.string.input_clear))
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
}
