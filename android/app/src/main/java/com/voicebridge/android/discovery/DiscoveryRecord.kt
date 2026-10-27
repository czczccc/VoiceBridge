package com.voicebridge.android.discovery

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** One Windows device as far as its `_voicebridge._tcp` TXT record can describe it. */
data class DiscoveredRecord(
    val deviceId: String,
    val displayName: String,
    val fingerprint: String,
)

private val FINGERPRINT_PATTERN = Regex("SHA256:[0-9a-f]{64}")

private const val SUPPORTED_VERSION = "1"
private const val KEY_VERSION = "v"
private const val KEY_ID = "id"
private const val KEY_NAME = "name"
private const val KEY_FINGERPRINT = "fp"

/** True for a pin this app can connect to: `SHA256:` plus 64 lowercase hex digits. */
fun isCanonicalFingerprint(value: String): Boolean = FINGERPRINT_PATTERN.matches(value)

/**
 * Reads the TXT attributes of a discovered `_voicebridge._tcp` service.
 *
 * Returns null instead of a partly trusted record when the advertisement cannot be used: a missing
 * or non-`1` [KEY_VERSION], a missing or empty `id`, a missing `fp`, a fingerprint that is not
 * canonical (uppercase hex, wrong length, another algorithm) or any value that is not valid UTF-8.
 * Unknown keys are ignored, so a newer Windows build can add TXT keys without breaking this app.
 */
fun parse(attributes: Map<String, ByteArray>): DiscoveredRecord? {
    val version = attributes[KEY_VERSION]?.let { decodeUtf8(it) } ?: return null
    if (version != SUPPORTED_VERSION) return null
    val deviceId = attributes[KEY_ID]?.let { decodeUtf8(it) }?.takeIf { it.isNotEmpty() } ?: return null
    val fingerprint = attributes[KEY_FINGERPRINT]?.let { decodeUtf8(it) } ?: return null
    if (!isCanonicalFingerprint(fingerprint)) return null
    // A missing name is not a reason to drop a device the user can still identify by its id.
    val displayName = attributes[KEY_NAME]?.let { decodeUtf8(it) }?.takeIf { it.isNotEmpty() } ?: deviceId
    return DiscoveredRecord(deviceId, displayName, fingerprint)
}

/**
 * Strict UTF-8 decode: DNS-SD values are bytes, and the platform decoder would silently replace
 * malformed sequences with U+FFFD, which could turn an attacker's bytes into a plausible device id.
 */
private fun decodeUtf8(bytes: ByteArray): String? = try {
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
} catch (error: CharacterCodingException) {
    // Not ours, so the whole record is dropped rather than repaired.
    null
}
