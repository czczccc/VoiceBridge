package com.voicebridge.android.network

/**
 * Backoff for the bridge reconnect loop: 1 s, 2 s, 4 s, … capped at 30 s. Jitter is added on top of
 * the step (up to a quarter of it) so two phones on the same LAN do not retry in lockstep.
 */
object ReconnectBackoff {
    const val INITIAL_MILLIS = 1_000L
    const val MAX_MILLIS = 30_000L

    /** 1000, 2000, 4000, … capped at [MAX_MILLIS], for [attempt] counted from 1. */
    fun stepMillis(attempt: Int): Long {
        var step = INITIAL_MILLIS
        repeat((attempt - 1).coerceIn(0, MAX_DOUBLINGS)) {
            step = if (step >= MAX_MILLIS) MAX_MILLIS else step * 2
        }
        return step.coerceAtMost(MAX_MILLIS)
    }

    /**
     * The wait before [attempt] (from 1). [jitter] is expected between 0 and 1; out of range values
     * are clamped, and the result is never below [INITIAL_MILLIS] and never above [MAX_MILLIS].
     */
    fun delayMillis(attempt: Int, jitter: Double): Long {
        val step = stepMillis(attempt)
        val extra = (step / 4.0 * jitter.coerceIn(0.0, 1.0)).toLong()
        return (step + extra).coerceIn(INITIAL_MILLIS, MAX_MILLIS)
    }

    /** Doubling past 30 s is pointless, so the loop stops early instead of overflowing. */
    private const val MAX_DOUBLINGS = 16
}
