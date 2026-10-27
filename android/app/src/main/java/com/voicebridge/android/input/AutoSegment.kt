package com.voicebridge.android.input

/**
 * The pauses the user can choose for ending a segment automatically.
 *
 * `0` disables the automatic end: the segment then only finishes when the user taps 「新一段」or the
 * black end button. Values are validated on read so an old or hand-edited preference cannot create
 * an unexpected timer.
 */
object AutoSegment {
    const val DEFAULT = 3_000L
    val OPTIONS = listOf(0L, 3_000L, 5_000L, 8_000L)

    fun coerce(value: Long): Long = if (value in OPTIONS) value else DEFAULT
}
