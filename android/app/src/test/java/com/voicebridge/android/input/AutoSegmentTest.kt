package com.voicebridge.android.input

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoSegmentTest {
    @Test
    fun keepsEveryOfferedPause() {
        AutoSegment.OPTIONS.forEach { option ->
            assertEquals(option, AutoSegment.coerce(option))
        }
    }

    @Test
    fun fallsBackToTheDefaultForUnknownValues() {
        assertEquals(AutoSegment.DEFAULT, AutoSegment.coerce(-1L))
        assertEquals(AutoSegment.DEFAULT, AutoSegment.coerce(1_234L))
    }
}
