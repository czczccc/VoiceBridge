package com.voicebridge.android.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiscoveryRecordTest {
    private fun txt(vararg pairs: Pair<String, ByteArray>): Map<String, ByteArray> = mapOf(*pairs)

    private fun value(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    private val validFingerprint = "SHA256:" + "0123456789abcdef".repeat(4)

    private fun valid() = txt(
        "v" to value("1"),
        "id" to value("windows-device"),
        "name" to value("书房电脑"),
        "fp" to value(validFingerprint),
    )

    @Test
    fun parsesValidRecord() {
        assertEquals(
            DiscoveredRecord("windows-device", "书房电脑", validFingerprint),
            parse(valid()),
        )
    }

    @Test
    fun rejectsMissingFingerprint() {
        val attributes = valid().toMutableMap().apply { remove("fp") }
        assertNull(parse(attributes))
    }

    @Test
    fun rejectsUppercaseFingerprint() {
        val attributes = valid().toMutableMap().apply { put("fp", value(validFingerprint.uppercase())) }
        assertNull(parse(attributes))
    }

    @Test
    fun rejectsUnsupportedVersion() {
        val attributes = valid().toMutableMap().apply { put("v", value("2")) }
        assertNull(parse(attributes))
    }

    @Test
    fun rejectsMissingId() {
        val attributes = valid().toMutableMap().apply { remove("id") }
        assertNull(parse(attributes))
    }

    @Test
    fun rejectsInvalidUtf8() {
        // 0xFF never appears in UTF-8, so the id cannot be a value this app wrote.
        val attributes = valid().toMutableMap().apply { put("id", byteArrayOf(0xFF.toByte(), 0xFE.toByte())) }
        assertNull(parse(attributes))
    }

    @Test
    fun acceptsUnknownKeys() {
        val attributes = valid().toMutableMap().apply {
            put("extra", value("ignored"))
            put("proto", value("future"))
        }
        assertEquals(
            DiscoveredRecord("windows-device", "书房电脑", validFingerprint),
            parse(attributes),
        )
    }
}
