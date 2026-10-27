package com.voicebridge.android.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

const val PROTOCOL_VERSION = 1
const val MAX_MESSAGE_BYTES = 256 * 1024
const val MAX_PROTOCOL_INTEGER = 9007199254740991L

enum class SessionState { Idle, Armed, Active, PausedFocus, Stopping, Error }
enum class ConnectionState { Unpaired, Pairing, Connected, Reconnecting, Disconnected }

@Serializable
enum class ErrorCode {
    AUTH_FAILED, PAIRING_EXPIRED, UNSUPPORTED_PROTOCOL, MESSAGE_TOO_LARGE,
    INVALID_MESSAGE, SESSION_NOT_ACTIVE, STALE_SEQUENCE, FOCUS_CHANGED,
    TARGET_UNAVAILABLE, INJECTION_FAILED, CLIPBOARD_DISABLED, RATE_LIMITED,
}

class ProtocolException(val code: ErrorCode) : Exception(code.name)

// Do not generate toString() on messages or payloads: they contain private text and tokens.
class Message(val v: Int, val messageId: String, val sentAt: Long, val body: Body)

@Serializable
sealed class Body {
    @Serializable @SerialName("hello")
    class Hello(val deviceId: String, val clientVersion: String, val auth: Auth) : Body()

    @Serializable @SerialName("hello.ack")
    class HelloAck(
        val serverVersion: String, val connectionId: String,
        val capabilities: List<String>, val authenticated: Boolean,
    ) : Body()

    @Serializable @SerialName("session.start")
    class SessionStart(val sessionId: String, val target: Target) : Body()

    @Serializable @SerialName("text.snapshot")
    class TextSnapshot(
        val sessionId: String, val seq: Long, val text: String,
        val selection: TextRange? = null, val composition: TextRange? = null,
    ) : Body()

    @Serializable @SerialName("text.ack")
    class TextAck(val sessionId: String, val seq: Long, val status: AckStatus, val latencyMs: Long) : Body()

    @Serializable @SerialName("session.stop")
    class SessionStop(val sessionId: String, val reason: String) : Body()

    @Serializable @SerialName("clipboard.text")
    class ClipboardText(
        val eventId: String, val originDeviceId: String, val direction: Direction, val text: String,
    ) : Body()

    @Serializable @SerialName("error")
    class Error(val code: ErrorCode, val message: String) : Body()
}

@Serializable class Auth(val token: String)
@Serializable class Target(val windowLabel: String, val focusPolicy: FocusPolicy)
@Serializable class TextRange(val start: Long, val end: Long)

@Serializable
enum class FocusPolicy { @SerialName("pause-on-focus-change") PauseOnFocusChange }
@Serializable
enum class AckStatus {
    @SerialName("applied") Applied,
    @SerialName("stale") Stale,
    @SerialName("paused-focus") PausedFocus,
    @SerialName("rejected-session") RejectedSession,
    @SerialName("injection-error") InjectionError,
}
@Serializable
enum class Direction {
    @SerialName("android-to-windows") AndroidToWindows,
    @SerialName("windows-to-android") WindowsToAndroid,
}

object Protocol {
    private val json = Json { ignoreUnknownKeys = true }
    private val integer = Regex("0|[1-9][0-9]*")

    private fun requireValid(valid: Boolean) {
        if (!valid) throw ProtocolException(ErrorCode.INVALID_MESSAGE)
    }

    private fun short(value: String) {
        requireValid(value.isNotBlank() && value.toByteArray(Charsets.UTF_8).size <= 256)
    }

    private fun number(value: Long, minimum: Long = 0) {
        requireValid(value in minimum..MAX_PROTOCOL_INTEGER)
    }

    private fun long(element: JsonElement?): Long {
        val value = element as? JsonPrimitive ?: throw ProtocolException(ErrorCode.INVALID_MESSAGE)
        requireValid(!value.isString && integer.matches(value.content))
        return value.longOrNull ?: throw ProtocolException(ErrorCode.INVALID_MESSAGE)
    }

    private fun string(element: JsonElement?): String {
        val value = element as? JsonPrimitive ?: throw ProtocolException(ErrorCode.INVALID_MESSAGE)
        requireValid(value.isString)
        return value.content
    }

    private fun range(text: String, range: TextRange, ordered: Boolean) {
        fun boundary(offset: Long): Boolean {
            if (offset !in 0..text.length.toLong()) return false
            val index = offset.toInt()
            return index == 0 || index == text.length ||
                !(text[index - 1].isHighSurrogate() && text[index].isLowSurrogate())
        }
        requireValid(boundary(range.start) && boundary(range.end) && (!ordered || range.start <= range.end))
    }

    fun validate(message: Message) {
        if (message.v != PROTOCOL_VERSION) throw ProtocolException(ErrorCode.UNSUPPORTED_PROTOCOL)
        short(message.messageId)
        number(message.sentAt)
        when (val body = message.body) {
            is Body.Hello -> {
                short(body.deviceId)
                short(body.clientVersion)
                requireValid(body.auth.token.isNotBlank() && body.auth.token.toByteArray(Charsets.UTF_8).size <= 4096)
            }
            is Body.HelloAck -> {
                short(body.serverVersion)
                short(body.connectionId)
                requireValid(body.capabilities.size <= 32)
                body.capabilities.forEach(::short)
            }
            is Body.SessionStart -> { short(body.sessionId); short(body.target.windowLabel) }
            is Body.TextSnapshot -> {
                short(body.sessionId)
                number(body.seq, 1)
                body.selection?.let { range(body.text, it, false) }
                body.composition?.let { range(body.text, it, true) }
            }
            is Body.TextAck -> { short(body.sessionId); number(body.seq, 1); number(body.latencyMs) }
            is Body.SessionStop -> { short(body.sessionId); short(body.reason) }
            is Body.ClipboardText -> { short(body.eventId); short(body.originDeviceId) }
            is Body.Error -> requireValid(body.message.isNotBlank() && body.message.toByteArray(Charsets.UTF_8).size <= 1024)
        }
    }

    fun decode(bytes: ByteArray): Message {
        if (bytes.size > MAX_MESSAGE_BYTES) throw ProtocolException(ErrorCode.MESSAGE_TOO_LARGE)
        try {
            val text = bytes.toString(Charsets.UTF_8)
            requireValid(text.toByteArray(Charsets.UTF_8).contentEquals(bytes))
            val root = json.parseToJsonElement(text).jsonObject
            val version = long(root["v"])
            if (version != 1L) throw ProtocolException(ErrorCode.UNSUPPORTED_PROTOCOL)
            val id = string(root["messageId"])
            val sentAt = long(root["sentAt"])
            // Kotlin JSON can coerce primitives into String properties; reject that explicitly.
            for (key in listOf(
                "type", "deviceId", "clientVersion", "serverVersion", "connectionId", "sessionId",
                "text", "status", "reason", "eventId", "originDeviceId", "direction", "code", "message",
            )) root[key]?.let { string(it) }
            root["auth"]?.let { string(it.jsonObject["token"]) }
            root["target"]?.let {
                string(it.jsonObject["windowLabel"])
                string(it.jsonObject["focusPolicy"])
            }
            root["capabilities"]?.let { it.jsonArray.forEach { capability -> string(capability) } }
            // Reject quoted, fractional and out-of-range integer payloads before decoding.
            for (key in listOf("seq", "latencyMs")) root[key]?.let { long(it) }
            for (key in listOf("selection", "composition")) {
                root[key]?.takeUnless { it is JsonNull }?.let {
                    long(it.jsonObject["start"])
                    long(it.jsonObject["end"])
                }
            }
            val payload = JsonObject(root.filterKeys { it !in setOf("v", "messageId", "sentAt") })
            val body = json.decodeFromJsonElement<Body>(payload)
            return Message(1, id, sentAt, body).also(::validate)
        } catch (error: ProtocolException) {
            throw error
        } catch (error: SerializationException) {
            throw ProtocolException(ErrorCode.INVALID_MESSAGE)
        } catch (error: IllegalArgumentException) {
            throw ProtocolException(ErrorCode.INVALID_MESSAGE)
        }
    }

    fun encode(message: Message): ByteArray {
        validate(message)
        val root = json.encodeToJsonElement<Body>(message.body).jsonObject.toMutableMap()
        root["v"] = JsonPrimitive(message.v)
        root["messageId"] = JsonPrimitive(message.messageId)
        root["sentAt"] = JsonPrimitive(message.sentAt)
        val bytes = json.encodeToString(JsonObject(root)).toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_MESSAGE_BYTES) throw ProtocolException(ErrorCode.MESSAGE_TOO_LARGE)
        return bytes
    }
}
