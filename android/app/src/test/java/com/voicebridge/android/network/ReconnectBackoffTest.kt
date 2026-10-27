package com.voicebridge.android.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectBackoffTest {
    @Test
    fun theStepDoublesFromOneSecond() {
        assertEquals(1_000L, ReconnectBackoff.stepMillis(1))
        assertEquals(2_000L, ReconnectBackoff.stepMillis(2))
        assertEquals(4_000L, ReconnectBackoff.stepMillis(3))
        assertEquals(8_000L, ReconnectBackoff.stepMillis(4))
        assertEquals(16_000L, ReconnectBackoff.stepMillis(5))
    }

    @Test
    fun theStepStopsAtThirtySeconds() {
        assertEquals(30_000L, ReconnectBackoff.stepMillis(6))
        assertEquals(30_000L, ReconnectBackoff.stepMillis(7))
        assertEquals(30_000L, ReconnectBackoff.stepMillis(1_000))
    }

    @Test
    fun anAttemptBelowOneStillWaits() {
        assertEquals(1_000L, ReconnectBackoff.stepMillis(0))
        assertEquals(1_000L, ReconnectBackoff.stepMillis(-5))
        assertEquals(1_000L, ReconnectBackoff.delayMillis(0, 0.0))
    }

    @Test
    fun jitterNeverShortensTheStepAndNeverExceedsTheCap() {
        for (attempt in 1..40) {
            val step = ReconnectBackoff.stepMillis(attempt)
            for (jitter in listOf(0.0, 0.25, 0.9, 0.999)) {
                val delay = ReconnectBackoff.delayMillis(attempt, jitter)
                assertTrue("delay $delay for attempt $attempt", delay >= step)
                assertTrue("delay $delay for attempt $attempt", delay <= ReconnectBackoff.MAX_MILLIS)
                assertTrue("delay $delay for attempt $attempt", delay > 0)
            }
        }
    }

    @Test
    fun noJitterGivesTheBareStep() {
        assertEquals(1_000L, ReconnectBackoff.delayMillis(1, 0.0))
        assertEquals(4_000L, ReconnectBackoff.delayMillis(3, 0.0))
        assertEquals(30_000L, ReconnectBackoff.delayMillis(9, 0.0))
    }

    @Test
    fun outOfRangeJitterIsClamped() {
        assertEquals(1_000L, ReconnectBackoff.delayMillis(1, -3.0))
        assertEquals(30_000L, ReconnectBackoff.delayMillis(9, 40.0))
    }
}
