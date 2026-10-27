package com.voicebridge.android.notification

/**
 * The action that marks an intent as "the user tapped the notification shortcut".
 *
 * Kept free of Android types so the matching rule can be unit tested on the JVM.
 */
object QuickEntryAction {
    const val OPEN_INPUT = "com.voicebridge.android.action.OPEN_INPUT"

    fun matches(action: String?): Boolean = action == OPEN_INPUT
}
