package com.voicebridge.android.pairing

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** 32 random bytes encode to 43 unpadded base64url characters, inside the protocol's 16..86 range. */
const val PAIRING_NONCE_BYTES = 32

private const val SAS_LABEL = "voicebridge-sas-v1"
private const val SAS_MODULUS = 1_000_000
private const val SAS_DIGITS = 6
private const val FINGERPRINT_PREFIX = "SHA256:"
private const val HEX_DIGITS = "0123456789abcdef"

private val secureRandom = SecureRandom()

/**
 * Bluetooth-style 6 digit comparison code. Both ends compute it from data they already hold, so
 * the code itself never travels over the network (PROTOCOL.md 3.7).
 */
fun sasCode(fingerprint: String, clientNonce: String, serverNonce: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest((SAS_LABEL + fingerprint + clientNonce + serverNonce).toByteArray(Charsets.UTF_8))
    var value = 0L
    for (index in 0 until 4) value = (value shl 8) or (digest[index].toLong() and 0xFF)
    // Manual zero padding keeps the result ASCII digits whatever the JVM default locale is.
    return (value % SAS_MODULUS).toString().padStart(SAS_DIGITS, '0')
}

/** Unpadded base64url of [bytes] cryptographically random bytes; used for nonces and message ids. */
fun randomBase64Url(bytes: Int): String {
    require(bytes > 0) { "Byte count must be positive" }
    val buffer = ByteArray(bytes)
    secureRandom.nextBytes(buffer)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer)
}

/** Canonical certificate pin: `SHA256:` followed by 64 lowercase hex digits, as the protocol expects. */
fun fingerprintOf(certificateDer: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(certificateDer)
    val builder = StringBuilder(FINGERPRINT_PREFIX.length + digest.size * 2)
    builder.append(FINGERPRINT_PREFIX)
    for (byte in digest) {
        val value = byte.toInt() and 0xFF
        builder.append(HEX_DIGITS[value ushr 4]).append(HEX_DIGITS[value and 0x0F])
    }
    return builder.toString()
}
