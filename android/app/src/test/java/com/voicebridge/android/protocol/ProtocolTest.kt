package com.voicebridge.android.protocol

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    private val json = Json

    private fun fixture(name: String): JsonArray {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream(name))
        return stream.bufferedReader().use { json.parseToJsonElement(it.readText()).jsonArray }
    }

    private fun read(value: JsonElement) = Protocol.decode(value.toString().toByteArray(Charsets.UTF_8))

    private fun rejects(code: ErrorCode, block: () -> Unit) {
        val exception = assertThrows(ProtocolException::class.java) { block() }
        assertEquals(code, exception.code)
        assertEquals(code.name, exception.message)
    }

    @Test
    fun sharedMessagesRoundTrip() {
        for (value in fixture("messages.json")) {
            val first = read(value)
            val bytes = Protocol.encode(first)
            val second = Protocol.decode(bytes)
            assertEquals(
                json.parseToJsonElement(bytes.toString(Charsets.UTF_8)),
                json.parseToJsonElement(Protocol.encode(second).toString(Charsets.UTF_8)),
            )
            assertEquals(value.jsonObject["type"], json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject["type"])
        }
    }

    @Test
    fun sharedInvalidMessagesAreRejected() {
        for (case in fixture("invalid.json")) {
            val value = case.jsonObject
            rejects(ErrorCode.valueOf(value.getValue("error").jsonPrimitive.content)) {
                read(value.getValue("message"))
            }
        }
    }

    @Test
    fun everyRequiredFieldIsRequired() {
        val fields = listOf(
            listOf("deviceId", "clientVersion", "auth"),
            listOf("serverVersion", "connectionId", "capabilities", "authenticated"),
            listOf("sessionId", "target"),
            listOf("sessionId", "seq", "text"),
            listOf("sessionId", "seq", "status", "latencyMs"),
            listOf("sessionId", "reason"),
            listOf("eventId", "originDeviceId", "direction", "text"),
            listOf("code", "message"),
        )
        for ((message, required) in fixture("messages.json").zip(fields)) {
            for (field in listOf("v", "type", "messageId", "sentAt") + required) {
                rejects(ErrorCode.INVALID_MESSAGE) { read(JsonObject(message.jsonObject - field)) }
                rejects(ErrorCode.INVALID_MESSAGE) { read(JsonObject(message.jsonObject + (field to JsonNull))) }
            }
        }
    }

    @Test
    fun utf8LimitAppliesToEntireFrameAndEncoding() {
        val message = Message(1, "m", 0, Body.TextSnapshot("s", 1, "中".repeat(MAX_MESSAGE_BYTES / 3 + 1)))
        rejects(ErrorCode.MESSAGE_TOO_LARGE) { Protocol.encode(message) }
        rejects(ErrorCode.MESSAGE_TOO_LARGE) {
            Protocol.decode(ByteArray(MAX_MESSAGE_BYTES + 1) { 32 })
        }
        val bytes = fixture("messages.json")[0].toString().toByteArray(Charsets.UTF_8)
        val padded = ByteArray(MAX_MESSAGE_BYTES - bytes.size) { 32 } + bytes
        Protocol.decode(padded)
        rejects(ErrorCode.MESSAGE_TOO_LARGE) { Protocol.decode(padded + byteArrayOf(32)) }
    }

    @Test
    fun optionalExtensionsAndUtf16Boundaries() {
        val base = fixture("messages.json")[3].jsonObject
        val reversedSelection = buildJsonObject { put("start", 4); put("end", 0) }
        val valid = JsonObject(base + mapOf(
            "seq" to JsonPrimitive(MAX_PROTOCOL_INTEGER),
            "selection" to reversedSelection,
            "futureOptional" to JsonPrimitive(true),
        ) - "composition")
        read(valid)
        rejects(ErrorCode.INVALID_MESSAGE) { read(JsonObject(base + ("text" to JsonPrimitive(123)))) }
        rejects(ErrorCode.INVALID_MESSAGE) { Protocol.decode("not json".toByteArray()) }
        rejects(ErrorCode.INVALID_MESSAGE) { Protocol.decode(byteArrayOf(0xff.toByte())) }
    }

    @Test
    fun encodingAlsoValidatesConstructedModels() {
        rejects(ErrorCode.UNSUPPORTED_PROTOCOL) {
            Protocol.encode(Message(2, "m", 0, Body.SessionStop("s", "user")))
        }
        rejects(ErrorCode.INVALID_MESSAGE) {
            Protocol.encode(Message(1, "m", 0, Body.TextSnapshot("s", 0, "")))
        }
        // Empty snapshot text is valid; clearing text must be expressible.
        Protocol.encode(Message(1, "m", 0, Body.TextSnapshot("s", 1, "")))
    }
}
