package com.voicebridge.android.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeSnapshotSeqTest {
    private val sequencer = SnapshotSequencer()

    @Test
    fun thereIsNoSnapshotBeforeASession() {
        assertNull(sequencer.allocate())
        assertEquals(0L, sequencer.sequence)
    }

    @Test
    fun theFirstSnapshotOfASessionIsNumberOne() {
        sequencer.start("session-1", "记事本")

        val allocation = sequencer.allocate()

        assertEquals("session-1", allocation?.session?.sessionId)
        assertEquals(1L, allocation?.sequence)
    }

    @Test
    fun everySnapshotIncrementsTheNumber() {
        sequencer.start("session-1", "记事本")

        sequencer.allocate()
        sequencer.allocate()

        assertEquals(3L, sequencer.allocate()?.sequence)
        assertEquals(3L, sequencer.sequence)
    }

    @Test
    fun aSessionStopResetsTheNumbering() {
        sequencer.start("session-1", "记事本")
        sequencer.allocate()
        sequencer.allocate()

        assertTrue(sequencer.stop("session-1"))

        assertNull(sequencer.session)
        assertEquals(0L, sequencer.sequence)
        assertNull(sequencer.allocate())
    }

    @Test
    fun aStopForAnotherSessionChangesNothing() {
        sequencer.start("session-1", "记事本")
        sequencer.allocate()

        assertFalse(sequencer.stop("session-2"))

        assertEquals("session-1", sequencer.session?.sessionId)
        assertEquals(2L, sequencer.allocate()?.sequence)
    }

    @Test
    fun aDisconnectForgetsTheSessionAndItsNumbering() {
        sequencer.start("session-1", "记事本")
        sequencer.allocate()

        sequencer.clear()
        sequencer.start("session-2", "浏览器")

        assertEquals(1L, sequencer.allocate()?.sequence)
    }

    @Test
    fun aRolledBackNumberIsReusedByTheNextSnapshot() {
        sequencer.start("session-1", "记事本")
        val dropped = sequencer.allocate()

        sequencer.rollback(dropped?.sequence ?: 0)

        assertEquals(1L, sequencer.allocate()?.sequence)
    }

    @Test
    fun rollingBackAnOlderNumberKeepsTheNewest() {
        sequencer.start("session-1", "记事本")
        val first = sequencer.allocate()
        sequencer.allocate()

        sequencer.rollback(first?.sequence ?: 0)

        assertEquals(3L, sequencer.allocate()?.sequence)
    }
}
