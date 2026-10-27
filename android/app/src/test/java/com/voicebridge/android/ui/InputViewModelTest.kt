package com.voicebridge.android.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Test

class InputViewModelTest {
    @Test
    fun everyAcceptedEditBumpsTheRevision() {
        val model = InputViewModel()
        assertEquals(0L, model.revision.value)

        model.onValueChange(TextFieldValue("你", selection = TextRange(1)))
        model.onValueChange(TextFieldValue("你好", selection = TextRange(2)))

        assertEquals("你好", model.value.value.text)
        assertEquals(2L, model.revision.value)
    }

    @Test
    fun anIdenticalValueIsNotAChange() {
        val model = InputViewModel()
        val value = TextFieldValue("你好", selection = TextRange(2))
        model.onValueChange(value)
        val revision = model.revision.value

        model.onValueChange(TextFieldValue("你好", selection = TextRange(2)))

        assertEquals(revision, model.revision.value)
    }

    @Test
    fun selectionAndCompositionChangesAreObserved() {
        val model = InputViewModel()
        model.onValueChange(TextFieldValue("ni hao", selection = TextRange(6), composition = TextRange(0, 6)))
        val revision = model.revision.value

        // Moving the caret only is still a state change the snapshot layer must see.
        model.onValueChange(TextFieldValue("ni hao", selection = TextRange(2), composition = TextRange(0, 6)))

        assertEquals(revision + 1, model.revision.value)
        assertEquals(TextRange(2), model.value.value.selection)
    }

    @Test
    fun clearEmptiesTheTextAndCountsAsAChange() {
        val model = InputViewModel()
        model.onValueChange(TextFieldValue("要清空的文字", selection = TextRange(5)))
        val revision = model.revision.value

        model.clear()

        assertEquals("", model.value.value.text)
        assertEquals(revision + 1, model.revision.value)
    }

    @Test
    fun clearingAnEmptyFieldIsANoOp() {
        val model = InputViewModel()
        model.clear()

        assertEquals(0L, model.revision.value)
    }
}
