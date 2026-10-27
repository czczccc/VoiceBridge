package com.voicebridge.android.network

import android.util.Log
import com.voicebridge.android.pairing.StoredCredential
import com.voicebridge.android.pairing.randomBase64Url
import com.voicebridge.android.protocol.AckStatus
import com.voicebridge.android.protocol.Auth
import com.voicebridge.android.protocol.Body
import com.voicebridge.android.protocol.ErrorCode
import com.voicebridge.android.protocol.FocusPolicy
import com.voicebridge.android.protocol.Message
import com.voicebridge.android.protocol.PROTOCOL_VERSION
import com.voicebridge.android.protocol.Protocol
import com.voicebridge.android.protocol.ProtocolException
import com.voicebridge.android.protocol.Target
import com.voicebridge.android.protocol.TextRange
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

private const val TAG = "BridgeClient"
private const val MESSAGE_ID_BYTES = 12

/** The PC answers `hello` immediately; a silent peer means the link cannot be used. */
private const val HELLO_ACK_TIMEOUT_MILLIS = 10_000L

private const val NORMAL_CLOSURE = 1000

/** Codes after which a retry cannot succeed, so the user has to pair again or update the app. */
private val FATAL_CODES = setOf(
    ErrorCode.AUTH_FAILED,
    ErrorCode.PAIRING_EXPIRED,
    ErrorCode.UNSUPPORTED_PROTOCOL,
)

sealed class BridgeConnectionState {
    /** Nothing is running: no credential, or the owner stopped the client. */
    object Disconnected : BridgeConnectionState()

    /** The first attempt of a run; an established link reports [Connected] instead. */
    object Connecting : BridgeConnectionState()

    object Connected : BridgeConnectionState()

    /** The link dropped and is being retried; [attempt] counts from 1. */
    data class Reconnecting(val attempt: Int) : BridgeConnectionState()

    /** [code] comes from an `error` frame or from a frame that failed validation. */
    data class Error(val code: ErrorCode) : BridgeConnectionState()
}

/**
 * Keeps one authenticated `wss://{host}:{port}/` link to the paired PC open.
 *
 * The socket is re-established with exponential backoff until [stop] is called. A reconnect always
 * starts a fresh session: the PC has to send `session.start` again, and the snapshot numbering is
 * dropped on every disconnect, because the PC refuses to inject into a session it did not start.
 *
 * Tokens, nonces and text are never logged; the only lines written here are transport-level and name
 * exception types or protocol codes.
 */
class BridgeClient(
    private val credential: StoredCredential,
    private val deviceId: String,
    private val clientVersion: String,
    private val now: () -> Long = System::currentTimeMillis,
    private val jitter: () -> Double = { kotlin.random.Random.nextDouble() },
) {
    private val _state = MutableStateFlow<BridgeConnectionState>(BridgeConnectionState.Disconnected)
    val state: StateFlow<BridgeConnectionState> = _state.asStateFlow()

    private val _session = MutableStateFlow<BridgeSession?>(null)
    val session: StateFlow<BridgeSession?> = _session.asStateFlow()

    /** Guards [sequencer] and [socket]; OkHttp callbacks and the sender arrive on other threads. */
    private val lock = Any()
    private val sequencer = SnapshotSequencer()
    private var scope: CoroutineScope? = null
    private var socket: WebSocket? = null
    /** Kept until the PC has armed a session, so the first characters are not lost. */
    private var pendingSnapshot: Pair<String, TextRange?>? = null
    private var sessionRequested = false
    private var nextRequestAt = 0L

    /** Starts the connect loop; a second call while it runs does nothing. */
    fun start() {
        synchronized(lock) {
            if (scope != null) return
            val started = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scope = started
            started.launch { connectUntilStopped() }
        }
    }

    /** Stops the loop, closes the socket and forgets the session. Safe to call more than once. */
    fun stop() {
        val (running, open) = synchronized(lock) {
            val current = scope
            val currentSocket = socket
            scope = null
            socket = null
            current to currentSocket
        }
        running?.cancel()
        open?.close(NORMAL_CLOSURE, null)
        forgetSession()
        _state.value = BridgeConnectionState.Disconnected
    }

    /**
     * Sends the full [text] with the current session's next sequence number.
     *
     * Ignored unless a session is active and the socket is up. The frame is encoded on the calling
     * thread, which is what keeps the numbering in send order; `WebSocket.send` only queues the
     * frame, so no network I/O blocks the UI.
     */
    fun sendSnapshot(text: String, selection: TextRange?) {
        val failure = synchronized(lock) {
            if (sequencer.session == null) {
                // No session yet: buffer this text and ask the PC to arm one at its current
                // focus. Typing is what starts a session; no click is needed on the PC.
                pendingSnapshot = text to selection
                requestSessionLocked()
                null
            } else {
                sendLocked(text, selection)
            }
        }
        if (failure != null) publish(BridgeConnectionState.Error(failure))
    }

    /** Runs under [lock]. Asks once, then waits a moment before retrying a refused request. */
    private fun requestSessionLocked() {
        if (sessionRequested || now() < nextRequestAt) return
        val target = socket ?: return
        sessionRequested = true
        // Two seconds is enough for a refusal to be read and shown, and keeps a burst of
        // keystrokes from flooding the PC with requests.
        nextRequestAt = now() + 2_000
        val frame = try {
            String(
                Protocol.encode(
                    message(
                        Body.SessionStart(
                            sessionId = "request-${randomBase64Url(6)}",
                            target = Target("auto", FocusPolicy.PauseOnFocusChange),
                        ),
                    ),
                ),
                Charsets.UTF_8,
            )
        } catch (error: ProtocolException) {
            Log.w(TAG, "A session request could not be encoded (${error.code})")
            sessionRequested = false
            return
        }
        if (!target.send(frame)) {
            sessionRequested = false
            Log.w(TAG, "A session request was dropped because the socket is closed")
        }
    }

    /** Runs under [lock]. Returns the error to report, or null when nothing needs saying. */
    private fun sendLocked(text: String, selection: TextRange?): ErrorCode? {
        val target = socket ?: return null
        // Allocated only once the frame can go out, so a missing socket leaves no sequence gap.
        val allocation = sequencer.allocate() ?: return null
        val frame = try {
            String(
                Protocol.encode(
                    message(
                        Body.TextSnapshot(
                            sessionId = allocation.session.sessionId,
                            seq = allocation.sequence,
                            text = text,
                            selection = selection,
                        ),
                    ),
                ),
                Charsets.UTF_8,
            )
        } catch (error: ProtocolException) {
            // Never send a truncated frame: the PC has to reject it and the text would be wrong.
            sequencer.rollback(allocation.sequence)
            Log.w(TAG, "A snapshot could not be encoded (${error.code})")
            return error.code
        }
        if (!target.send(frame)) {
            // The socket is already gone; its listener reports the disconnect and drives the retry.
            Log.w(TAG, "A snapshot frame was dropped because the socket is closed")
        }
        return null
    }

    private suspend fun connectUntilStopped() {
        var failures = 0
        while (true) {
            publish(
                if (failures == 0) BridgeConnectionState.Connecting
                else BridgeConnectionState.Reconnecting(failures),
            )
            val outcome = try {
                runAttempt()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "A bridge attempt failed: ${error::class.java.simpleName}")
                AttemptOutcome(authenticated = false)
            }
            val fatal = outcome.fatal
            if (fatal != null) {
                publish(BridgeConnectionState.Error(fatal))
                return
            }
            // A link that was up for a while gets a fresh 1 s step instead of the stale long one.
            failures = if (outcome.authenticated) 1 else failures + 1
            publish(BridgeConnectionState.Reconnecting(failures))
            delay(ReconnectBackoff.delayMillis(failures, jitter()))
        }
    }

    /** One socket from `hello` to its close. Returns when the link is gone or the caller cancels. */
    private suspend fun runAttempt(): AttemptOutcome {
        val client = try {
            clientFor(credential.fingerprint)
        } catch (error: IllegalArgumentException) {
            // No pairing ever produces an invalid pin, so retrying cannot fix it.
            Log.w(TAG, "The stored certificate pin is unusable")
            return AttemptOutcome(authenticated = false, fatal = ErrorCode.AUTH_FAILED)
        }
        val attempt = Attempt()
        val socketRef = AtomicReference<WebSocket?>()
        try {
            val request = try {
                Request.Builder().url(wssUrl(credential.host, credential.port)).build()
            } catch (error: IllegalArgumentException) {
                Log.w(TAG, "The stored host and port do not form a URL")
                return AttemptOutcome(authenticated = false)
            }
            socketRef.set(client.newWebSocket(request, AttemptListener(attempt)))
            synchronized(lock) { socket = socketRef.get() }
            val authenticated =
                withTimeoutOrNull(HELLO_ACK_TIMEOUT_MILLIS) { attempt.helloAck.await() } == true
            val fatal = attempt.fatal
            if (fatal != null) return AttemptOutcome(authenticated, fatal)
            if (!authenticated) {
                Log.w(TAG, "The handshake did not complete")
                return AttemptOutcome(authenticated = false)
            }
            attempt.terminated.await()
            return AttemptOutcome(authenticated = true, fatal = attempt.fatal)
        } finally {
            synchronized(lock) { if (socket === socketRef.get()) socket = null }
            socketRef.get()?.cancel()
            // Each attempt pins its own TLS configuration, so its client is not reused.
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            // A reconnect never resumes this session; the PC has to start a new one.
            forgetSession()
        }
    }

    /** Publishes [next] unless [stop] already ran, so a finishing coroutine cannot resurrect it. */
    private fun publish(next: BridgeConnectionState) {
        synchronized(lock) {
            if (scope == null) return
            _state.value = next
        }
    }

    private fun forgetSession() {
        synchronized(lock) {
            sequencer.clear()
            pendingSnapshot = null
            sessionRequested = false
        }
        _session.value = null
    }

    private fun startSession(body: Body.SessionStart) {
        val started = synchronized(lock) {
            sequencer.start(body.sessionId, body.target.windowLabel)
            sequencer.session
        }
        publishSession(started)
    }

    private fun endSession(sessionId: String) {
        val cleared = synchronized(lock) { sequencer.stop(sessionId) }
        if (cleared) publishSession(null)
    }

    /** Same "stopped wins" rule as [publish], so a late frame cannot revive a stopped link. */
    private fun publishSession(next: BridgeSession?) {
        synchronized(lock) {
            if (scope == null) return
            _session.value = next
        }
    }

    private fun handleFrame(attempt: Attempt, bytes: ByteArray) {
        val message = try {
            Protocol.decode(bytes)
        } catch (error: ProtocolException) {
            // A frame the protocol rejects means the peer cannot be talked to; retrying hides that.
            Log.w(TAG, "A server frame was rejected (${error.code})")
            publish(BridgeConnectionState.Error(error.code))
            attempt.fatal = error.code
            attempt.helloAck.complete(false)
            attempt.socket?.close(NORMAL_CLOSURE, null)
            return
        }
        when (val body = message.body) {
            is Body.HelloAck -> {
                if (!body.authenticated) {
                    Log.w(TAG, "The server did not accept the stored credential")
                    attempt.fatal = ErrorCode.AUTH_FAILED
                    attempt.helloAck.complete(false)
                    attempt.socket?.close(NORMAL_CLOSURE, null)
                    return
                }
                attempt.helloAck.complete(true)
                publish(BridgeConnectionState.Connected)
            }
            is Body.SessionStart -> {
                startSession(body)
                // The buffered text was waiting for exactly this session.
                val buffered = synchronized(lock) {
                    sessionRequested = false
                    pendingSnapshot.also { pendingSnapshot = null }
                }
                if (buffered != null) sendSnapshot(buffered.first, buffered.second)
            }
            is Body.SessionStop -> endSession(body.sessionId)
            is Body.TextAck -> onAck(body)
            is Body.Error -> {
                // Any refusal allows a later retry, but the buffered text is dropped: injecting
                // it into whatever is focused minutes later would be worse than losing it.
                synchronized(lock) {
                    sessionRequested = false
                    pendingSnapshot = null
                }
                Log.w(TAG, "The server reported ${body.code}")
                publish(BridgeConnectionState.Error(body.code))
                if (body.code in FATAL_CODES) {
                    attempt.fatal = body.code
                    attempt.helloAck.complete(false)
                    attempt.socket?.close(NORMAL_CLOSURE, null)
                }
            }
            // Clipboard and pairing frames belong to other features; they are not this link's job.
            else -> Unit
        }
    }

    private fun onAck(ack: Body.TextAck) {
        when (ack.status) {
            // The PC dropped the session; snapshots for it would be rejected from now on.
            AckStatus.RejectedSession -> endSession(ack.sessionId)
            // An applied snapshot proves the link works, so a local encoding error is over.
            AckStatus.Applied -> publish(BridgeConnectionState.Connected)
            // These describe the PC target (caret, focus, injection), not the link itself.
            AckStatus.Stale, AckStatus.PausedFocus, AckStatus.InjectionError -> Unit
        }
    }

    private fun message(body: Body): Message =
        Message(PROTOCOL_VERSION, "android-${randomBase64Url(MESSAGE_ID_BYTES)}", now(), body)

    private data class AttemptOutcome(val authenticated: Boolean, val fatal: ErrorCode? = null)

    /** One socket's handshake and life, filled in by [AttemptListener]. */
    private class Attempt {
        @Volatile var socket: WebSocket? = null
        @Volatile var fatal: ErrorCode? = null
        val helloAck = CompletableDeferred<Boolean>()
        val terminated = CompletableDeferred<Unit>()
    }

    private inner class AttemptListener(private val attempt: Attempt) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            attempt.socket = webSocket
            val frame = try {
                String(
                    Protocol.encode(
                        message(
                            Body.Hello(
                                deviceId = deviceId,
                                clientVersion = clientVersion,
                                auth = Auth(credential.token),
                            ),
                        ),
                    ),
                    Charsets.UTF_8,
                )
            } catch (error: ProtocolException) {
                Log.w(TAG, "The hello frame could not be encoded (${error.code})")
                attempt.fatal = error.code
                attempt.helloAck.complete(false)
                webSocket.close(NORMAL_CLOSURE, null)
                return
            }
            if (!webSocket.send(frame)) {
                Log.w(TAG, "The hello frame could not be queued")
                attempt.helloAck.complete(false)
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleFrame(attempt, text.toByteArray(Charsets.UTF_8))
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            handleFrame(attempt, bytes.toByteArray())
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(NORMAL_CLOSURE, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            attempt.helloAck.complete(false)
            attempt.terminated.complete(Unit)
        }

        override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
            // Only the type is logged: the throwable can carry the peer URL.
            Log.w(TAG, "The bridge socket failed: ${error::class.java.simpleName}")
            attempt.helloAck.complete(false)
            attempt.terminated.complete(Unit)
        }
    }
}
