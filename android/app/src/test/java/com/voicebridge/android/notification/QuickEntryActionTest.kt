package com.voicebridge.android.notification

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickEntryActionTest {
    @Test
    fun acceptsTheShortcutAction() {
        assertTrue(QuickEntryAction.matches(QuickEntryAction.OPEN_INPUT))
    }

    @Test
    fun ignoresMissingOrForeignActions() {
        assertFalse(QuickEntryAction.matches(null))
        assertFalse(QuickEntryAction.matches(""))
        assertFalse(QuickEntryAction.matches("com.voicebridge.android.action.OTHER"))
    }
}
