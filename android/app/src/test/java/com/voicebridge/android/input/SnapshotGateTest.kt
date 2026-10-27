package com.voicebridge.android.input

import com.voicebridge.android.protocol.TextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotGateTest {
    /** Stands in for the coroutine that would run the deadline; tests run it by hand. */
    private class ManualScheduler {
        private val deadlines = mutableListOf<() -> Unit>()
        val windows = mutableListOf<Long>()

        fun schedule(millis: Long, block: () -> Unit) {
            windows += millis
            deadlines += block
        }

        fun runAll() {
            val due = deadlines.toList()
            deadlines.clear()
            due.forEach { it() }
        }
    }

    private val scheduler = ManualScheduler()
    private val sent = mutableListOf<Pair<String, TextRange?>>()

    private fun gate(
        window: Long = 50,
        send: (String, TextRange?) -> Unit = { text, selection -> sent += text to selection },
    ) = SnapshotGate(window, send, scheduler::schedule)

    @Test
    fun editsInsideTheWindowCoalesceIntoOneSendWithTheLatestText() {
        val gate = gate()

        gate.onText("a", null)
        gate.onText("ab", null)
        gate.onText("abc", TextRange(3, 3))
        assertEquals(emptyList<Pair<String, TextRange?>>(), sent)

        scheduler.runAll()

        assertEquals(listOf("abc" to TextRange(3, 3)), sent)
        // One deadline for the whole burst, not one per edit.
        assertEquals(listOf(50L), scheduler.windows)
    }

    @Test
    fun theLastEditEventuallySends() {
        val gate = gate()

        gate.onText("first", null)
        scheduler.runAll()
        gate.onText("second", null)
        scheduler.runAll()

        assertEquals(listOf("first" to null, "second" to null), sent)
    }

    @Test
    fun flushSendsThePendingTextOnce() {
        val gate = gate()
        gate.onText("draft", TextRange(5, 5))

        gate.flush()
        assertEquals(listOf("draft" to TextRange(5, 5)), sent)

        // The deadline of the closed window must not send the same text again.
        scheduler.runAll()
        assertEquals(1, sent.size)
    }

    @Test
    fun flushWithoutAPendingEditSendsNothing() {
        val gate = gate()
        gate.onText("sent", null)
        scheduler.runAll()

        gate.flush()

        assertEquals(1, sent.size)
    }

    @Test
    fun resetDropsThePendingEdit() {
        val gate = gate()
        gate.onText("dropped", null)

        gate.reset()
        scheduler.runAll()

        assertTrue(sent.isEmpty())
    }

    @Test
    fun unchangedTextIsNotSentTwice() {
        val gate = gate()
        gate.onText("same", null)
        scheduler.runAll()

        gate.onText("same", TextRange(0, 0))
        scheduler.runAll()
        gate.flush()

        assertEquals(1, sent.size)
    }

    @Test
    fun textTypedBackToTheLastSentValueIsNotSentAgain() {
        val gate = gate()
        gate.onText("a", null)
        scheduler.runAll()

        gate.onText("ab", null)
        gate.onText("a", null)
        scheduler.runAll()

        assertEquals(1, sent.size)
    }

    @Test
    fun resetForgetsWhatWasSentSoTheSameTextGoesOutAgain() {
        val gate = gate()
        gate.onText("unchanged", null)
        scheduler.runAll()

        gate.reset()
        gate.sendNow("unchanged", TextRange(9, 9))

        assertEquals(listOf("unchanged" to null, "unchanged" to TextRange(9, 9)), sent)
    }

    @Test
    fun anEditAfterTheWindowOpensANewOne() {
        val gate = gate()
        gate.onText("one", null)
        scheduler.runAll()

        gate.onText("two", null)
        assertEquals(listOf(50L, 50L), scheduler.windows)
        scheduler.runAll()

        assertEquals(listOf("one" to null, "two" to null), sent)
    }
}
