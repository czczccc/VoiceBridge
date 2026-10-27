package com.voicebridge.android.pairing

import com.voicebridge.android.protocol.Body
import com.voicebridge.android.protocol.Message
import com.voicebridge.android.protocol.PROTOCOL_VERSION
import com.voicebridge.android.protocol.PairStatus
import com.voicebridge.android.protocol.Protocol
import com.voicebridge.android.protocol.ProtocolException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val MESSAGE_ID_BYTES = 12

sealed class PairingState {
    /** `pair.request` has not been sent yet. */
    object Idle : PairingState()

    /** A challenge was accepted; show [code] so the user can compare it with the PC screen. */
    data class AwaitingCode(val code: String) : PairingState()

    /** Pairing succeeded. Hand [token] to [CredentialStore] only; never log or display it. */
    class Paired(val serverDeviceId: String, val serverName: String, val token: String) : PairingState() {
        // Hand-written toString: a data class would print the access token into logs and crash reports.
        override fun toString() =
            "Paired(serverDeviceId=$serverDeviceId, serverName=$serverName, token=<redacted>)"
    }

    /** The peer, the fingerprint check or the expiry check ended the attempt; retrying is safe. */
    data class Rejected(val reason: String) : PairingState()

    /** Local or protocol failure. [reason] is user facing; it never contains a token. */
    data class Failed(val reason: String) : PairingState()
}

/**
 * Client half of the `pair.request` -> `pair.challenge` -> `pair.confirm` -> `pair.result`
 * exchange. It owns no socket: outgoing frames go to [send] and received frames come back through
 * [onMessage], which keeps the state machine testable on the JVM.
 *
 * [send] may throw (for example when the socket is already gone); that exception is not caught
 * here, because the owner decides whether to reconnect or surface the failure.
 *
 * Calls must be serialized by the owner, typically by driving the session from one coroutine.
 * The terminal states are [PairingState.Paired], [PairingState.Rejected] and [PairingState.Failed];
 * once reached, further messages are ignored.
 */
class PairingSession(
    private val windowsDeviceId: String,
    private val observedFingerprint: String,
    private val deviceId: String,
    private val deviceName: String,
    private val clientVersion: String,
    private val send: (ByteArray) -> Unit,
    private val now: () -> Long,
) {
    private val _state = MutableStateFlow<PairingState>(PairingState.Idle)
    val state: StateFlow<PairingState> = _state.asStateFlow()

    private var clientNonce: String? = null
    private var pairingId: String? = null
    private var challengeAccepted = false
    private var confirmSent = false

    /** Sends `pair.request` once. Later calls do nothing. */
    fun start() {
        if (clientNonce != null || _state.value != PairingState.Idle) return
        val nonce = randomBase64Url(PAIRING_NONCE_BYTES)
        clientNonce = nonce
        send(encode(Body.PairRequest(deviceId, deviceName, clientVersion, nonce)))
    }

    /** Sends `pair.confirm(confirmed = true)` once, and only while a code is displayed. */
    fun confirm() {
        val id = pairingId ?: return
        if (confirmSent || _state.value !is PairingState.AwaitingCode) return
        confirmSent = true
        send(encode(Body.PairConfirm(id, true)))
    }

    /**
     * User refused, either because the codes differ or because the attempt was cancelled: send
     * `pair.confirm(confirmed = false)` and close the session. The owner closes the socket.
     */
    fun reject() {
        if (isTerminal()) return
        _state.value = PairingState.Rejected(CANCELLED)
        pairingId?.let { send(encode(Body.PairConfirm(it, false))) }
    }

    /** Handles one received frame. Unknown, unrelated and duplicate messages leave the state alone. */
    fun onMessage(bytes: ByteArray) {
        if (isTerminal()) return
        val message = try {
            Protocol.decode(bytes)
        } catch (error: ProtocolException) {
            fail("收到的配对消息无效（${error.code.name}），已中止配对。")
            return
        }
        when (val body = message.body) {
            is Body.PairChallenge -> onChallenge(body)
            is Body.PairResult -> onResult(body)
            // Before authentication the server answers anything else with AUTH_FAILED; surface that
            // as a failure instead of waiting for a challenge that will never arrive.
            is Body.Error -> fail("电脑端拒绝了配对（${body.code.name}）。")
            else -> fail("配对过程中收到意外的消息，已中止配对。")
        }
    }

    private fun onChallenge(challenge: Body.PairChallenge) {
        if (challengeAccepted) {
            fail("收到重复的配对挑战，已中止配对。")
            return
        }
        challengeAccepted = true
        pairingId = challenge.pairingId
        if (challenge.serverDeviceId != windowsDeviceId) {
            fail("配对挑战来自另一台设备，已中止配对。")
            return
        }
        // The pin came from the TLS handshake, so a different value here means the peer relayed the
        // exchange. Stop before computing a code: a code would make the interception look plausible.
        if (challenge.certFingerprint != observedFingerprint) {
            _state.value = PairingState.Rejected(FINGERPRINT_MISMATCH)
            return
        }
        if (now() >= challenge.expiresAt) {
            _state.value = PairingState.Rejected(EXPIRED)
            return
        }
        val nonce = clientNonce
        if (nonce == null) {
            fail("尚未发送配对请求，已中止配对。")
            return
        }
        _state.value = PairingState.AwaitingCode(
            sasCode(observedFingerprint, nonce, challenge.serverNonce),
        )
    }

    private fun onResult(result: Body.PairResult) {
        // Results for another attempt, and results that arrive without an accepted challenge, are
        // stale by definition; duplicates are dropped by the terminal-state check in [onMessage].
        if (result.pairingId != pairingId) return
        if (_state.value !is PairingState.AwaitingCode) return
        when (result.status) {
            PairStatus.Paired -> {
                val token = result.token?.takeIf { it.isNotEmpty() }
                // Protocol.validate already rejects `paired` without a token; fail closed anyway.
                if (token == null) {
                    fail("电脑端没有返回访问令牌，配对失败。")
                    return
                }
                _state.value = PairingState.Paired(result.serverDeviceId, result.serverName, token)
            }
            PairStatus.Rejected -> _state.value = PairingState.Rejected("电脑端拒绝了本次配对。")
            PairStatus.Expired -> _state.value = PairingState.Rejected(EXPIRED)
            PairStatus.Mismatch -> _state.value = PairingState.Rejected("配对码不一致，配对已被取消。")
            PairStatus.Busy -> _state.value = PairingState.Rejected("电脑正在与其他设备配对，请稍后重试。")
        }
    }

    private fun fail(reason: String) {
        _state.value = PairingState.Failed(reason)
    }

    private fun isTerminal(): Boolean {
        val state = _state.value
        return state is PairingState.Paired ||
            state is PairingState.Rejected ||
            state is PairingState.Failed
    }

    private fun encode(body: Body): ByteArray = Protocol.encode(
        Message(PROTOCOL_VERSION, "android-${randomBase64Url(MESSAGE_ID_BYTES)}", now(), body),
    )
}

// Chinese literals live here because the state machine has no Android dependency; the pairing UI
// maps these reasons onto its own presentation.
private const val CANCELLED = "已取消配对。"
private const val EXPIRED = "配对码已过期，请重新发起配对。"
private const val FINGERPRINT_MISMATCH = "证书指纹与本次连接不一致，可能有人试图中间人转发，已中止配对。"
