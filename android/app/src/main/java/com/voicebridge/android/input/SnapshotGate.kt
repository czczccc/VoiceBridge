package com.voicebridge.android.input

import com.voicebridge.android.protocol.TextRange

/**
 * Coalesces the edits of one text field into at most one snapshot per [windowMillis].
 *
 * Typing and IME corrections produce far more changes than the PC needs, so the newest text is held
 * until the window ends. Every recorded edit is either sent by that deadline or replaced by a newer
 * one, so the last edit always leaves the phone; [flush] shortens the wait to zero and [reset] drops
 * the pending state (used when a session starts over).
 *
 * All calls must come from one thread, and [schedule] must run its block on that same thread: the
 * class keeps no locks so the order of sends stays deterministic.
 */
class SnapshotGate(
    private val windowMillis: Long,
    private val send: (String, TextRange?) -> Unit,
    private val schedule: (Long, () -> Unit) -> Unit,
) {
    init {
        require(windowMillis > 0) { "The coalescing window must be positive" }
    }

    private var pendingText: String? = null
    private var pendingSelection: TextRange? = null
    private var scheduled = false

    /** A deadline that does not match this value belongs to a window that was already closed. */
    private var generation = 0L

    /** The text of the last snapshot that was handed to [send]; null before the first one. */
    private var lastSentText: String? = null

    /** Records the newest [text] with the caret range that goes with it. */
    fun onText(text: String, selection: TextRange?) {
        // The text is what the PC types; a pure caret move or composition change is not news.
        if (text == lastSentText && pendingText == null) return
        pendingText = text
        pendingSelection = selection
        if (scheduled) return
        scheduled = true
        val deadline = ++generation
        schedule(windowMillis) {
            if (deadline != generation) return@schedule
            scheduled = false
            deliver()
        }
    }

    /** Sends the pending text right away, if there is any. */
    fun flush() {
        generation += 1
        scheduled = false
        deliver()
    }

    /** Sends [text] immediately, bypassing the window; a new session needs the full text at once. */
    fun sendNow(text: String, selection: TextRange?) {
        onText(text, selection)
        flush()
    }

    /** Drops the pending edit and the record of what was sent, so the next text is always sent. */
    fun reset() {
        generation += 1
        scheduled = false
        pendingText = null
        pendingSelection = null
        lastSentText = null
    }

    private fun deliver() {
        val text = pendingText ?: return
        val selection = pendingSelection
        pendingText = null
        pendingSelection = null
        if (text == lastSentText) return
        lastSentText = text
        send(text, selection)
    }
}
