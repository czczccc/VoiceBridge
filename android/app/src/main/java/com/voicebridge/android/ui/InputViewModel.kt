package com.voicebridge.android.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds the text the user types with their own IME.
 *
 * This is the single source of truth for the input area: [revision] increments whenever the text or
 * the selection changes, so a later task can turn each revision into one `text.snapshot` without
 * guessing which edits actually happened.
 */
class InputViewModel : ViewModel() {
    private val _value = MutableStateFlow(TextFieldValue(""))
    val value: StateFlow<TextFieldValue> = _value.asStateFlow()

    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    fun onValueChange(next: TextFieldValue) {
        if (next == _value.value) return
        _value.value = next
        _revision.value += 1
    }

    /**
     * Clears the field without announcing a change. A finished segment must not reach the PC as an
     * empty snapshot, because that would delete everything already typed there.
     */
    fun clearWithoutSync() {
        if (_value.value.text.isEmpty() && _value.value.selection == TextRange.Zero) return
        _value.value = TextFieldValue("")
    }
}
