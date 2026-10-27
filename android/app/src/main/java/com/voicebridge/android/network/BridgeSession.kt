package com.voicebridge.android.network

/** A session the PC started; the phone sends `text.snapshot` only while one is active. */
data class BridgeSession(val sessionId: String, val targetLabel: String)

/**
 * The active session and the `text.snapshot` sequence number that belongs to it.
 *
 * A reconnect never resumes an old session: the PC has to send `session.start` again, and the
 * numbering restarts at 1 for the new one. Not thread safe; [BridgeClient] guards it with its own
 * lock because OkHttp callbacks and the sender run on different threads.
 */
class SnapshotSequencer {
    var session: BridgeSession? = null
        private set

    /** The last number that was handed out; 0 means none was. */
    var sequence: Long = 0
        private set

    fun start(sessionId: String, targetLabel: String) {
        session = BridgeSession(sessionId, targetLabel)
        sequence = 0
    }

    /** Ends [sessionId] and drops its numbering. A stop for another session changes nothing. */
    fun stop(sessionId: String): Boolean {
        if (session?.sessionId != sessionId) return false
        clear()
        return true
    }

    /** Forgets the session, used on disconnect. */
    fun clear() {
        session = null
        sequence = 0
    }

    /** The session plus the number the next snapshot must carry, or null while idle. */
    fun allocate(): Allocation? {
        val active = session ?: return null
        sequence += 1
        return Allocation(active, sequence)
    }

    /**
     * Gives back [sequence] when the frame it was allocated for was never sent, so the PC sees a
     * gap-free series.
     */
    fun rollback(sequence: Long) {
        if (this.sequence == sequence) this.sequence = sequence - 1
    }

    data class Allocation(val session: BridgeSession, val sequence: Long)
}
