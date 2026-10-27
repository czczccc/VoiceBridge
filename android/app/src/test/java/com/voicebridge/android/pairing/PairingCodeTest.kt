package com.voicebridge.android.pairing

import com.voicebridge.android.protocol.Body
import com.voicebridge.android.protocol.Message
import com.voicebridge.android.protocol.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

// Fingerprint from the PROTOCOL.md 3.7 vector.
private const val VECTOR_FINGERPRINT =
    "SHA256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

private val VECTOR_CLIENT_NONCE = "A".repeat(43)
private val VECTOR_SERVER_NONCE = "B".repeat(43)
private val SIX_DIGITS = Regex("[0-9]{6}")

class PairingCodeTest {
    @Test
    fun sasMatchesProtocolVector() {
        assertEquals("873723", sasCode(VECTOR_FINGERPRINT, VECTOR_CLIENT_NONCE, VECTOR_SERVER_NONCE))
    }

    @Test
    fun sasChangesWhenAnyInputChanges() {
        val base = sasCode(VECTOR_FINGERPRINT, VECTOR_CLIENT_NONCE, VECTOR_SERVER_NONCE)
        assertEquals(base, sasCode(VECTOR_FINGERPRINT, VECTOR_CLIENT_NONCE, VECTOR_SERVER_NONCE))
        assertNotEquals(
            base,
            sasCode(VECTOR_FINGERPRINT.dropLast(1) + "e", VECTOR_CLIENT_NONCE, VECTOR_SERVER_NONCE),
        )
        assertNotEquals(
            base,
            sasCode(VECTOR_FINGERPRINT, VECTOR_CLIENT_NONCE.dropLast(1) + "C", VECTOR_SERVER_NONCE),
        )
        assertNotEquals(
            base,
            sasCode(VECTOR_FINGERPRINT, VECTOR_CLIENT_NONCE, VECTOR_SERVER_NONCE.dropLast(1) + "C"),
        )
    }

    @Test
    fun sasIsAlwaysSixAsciiDigits() {
        val inputs = listOf(
            Triple(VECTOR_FINGERPRINT, VECTOR_CLIENT_NONCE, VECTOR_SERVER_NONCE),
            Triple("", "", ""),
            Triple("SHA256:" + "0".repeat(64), "A", "B"),
            Triple(VECTOR_FINGERPRINT.uppercase(), VECTOR_CLIENT_NONCE.reversed(), VECTOR_SERVER_NONCE.reversed()),
            Triple("指纹", "客户端 nonce", "服务端 nonce"),
        )
        for ((fingerprint, clientNonce, serverNonce) in inputs) {
            val code = sasCode(fingerprint, clientNonce, serverNonce)
            assertTrue(code, SIX_DIGITS.matches(code))
        }
    }

    @Test
    fun nonceIsUnpaddedBase64UrlTheProtocolAccepts() {
        val nonce = randomBase64Url(PAIRING_NONCE_BYTES)
        assertEquals(43, nonce.length)
        assertTrue(
            nonce,
            nonce.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' },
        )
        assertNotEquals(nonce, randomBase64Url(PAIRING_NONCE_BYTES))
        assertThrows(IllegalArgumentException::class.java) { randomBase64Url(0) }

        // Protocol.decode enforces the documented nonce alphabet and size, so this pins the encoding.
        val request = Body.PairRequest("android-device", "Pixel", "0.1.0-test.1", nonce)
        Protocol.decode(Protocol.encode(Message(1, "message-1", 0, request)))
    }

    @Test
    fun fingerprintIsLowercaseSha256OfTheEncodedCertificate() {
        // SHA-256 of the empty byte string, a fixed vector for "SHA256:" plus lowercase hex.
        assertEquals(
            "SHA256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            fingerprintOf(ByteArray(0)),
        )
        val certificateDer = byteArrayOf(0x30, 0x82.toByte(), 0x01, 0x00, 0x7f, 0xff.toByte())
        val fingerprint = fingerprintOf(certificateDer)
        assertEquals(71, fingerprint.length)
        assertTrue(fingerprint, Regex("SHA256:[0-9a-f]{64}").matches(fingerprint))
        assertEquals(fingerprint, fingerprintOf(certificateDer.copyOf()))
        assertNotEquals(fingerprint, fingerprintOf(certificateDer + byteArrayOf(0)))
    }
}
