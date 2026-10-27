package com.voicebridge.android.network

import android.util.Log
import com.voicebridge.android.discovery.DiscoveredComputer
import com.voicebridge.android.pairing.PairingSession
import com.voicebridge.android.pairing.PairingState
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

private const val TAG = "PairingClient"

/**
 * The phone waits for `pair.result`, so the budget has to cover the human on the Windows side
 * pressing Allow, which the Windows app allows up to 120 s for.
 */
private const val PAIRING_TIMEOUT_MILLIS = 130_000L

private const val NORMAL_CLOSURE = 1000

/**
 * Runs one pairing attempt over `wss://{host}:{port}/`, pinned to [DiscoveredComputer.fingerprint].
 *
 * The transport is a plain text/binary WebSocket: frames go into [PairingSession.onMessage] and the
 * session's output goes back out through the socket, so this class only owns the connection. Tokens
 * and nonces are never logged; the only messages written here are transport level.
 */
class PairingClient(
    private val deviceId: String,
    private val clientVersion: String,
) {
    /**
     * Runs the exchange against [computer] and returns its terminal state. [onProgress] receives
     * every intermediate state, including [PairingState.AwaitingCode], which the UI shows so the
     * user can compare it with the code on the PC screen.
     *
     * Cancelling the calling coroutine aborts the attempt and closes the socket.
     */
    suspend fun pair(
        computer: DiscoveredComputer,
        deviceName: String,
        onProgress: (PairingState) -> Unit = {},
    ): PairingState {
        val client: OkHttpClient = clientFor(computer.fingerprint)
        val terminal = CompletableDeferred<PairingState>()
        var socket: WebSocket? = null
        // The socket exists only after newWebSocket returns, but the session needs a send function
        // at construction time; every attempt therefore goes through this single holder.
        val socketRef = AtomicReference<WebSocket?>(null)

        val session = PairingSession(
            windowsDeviceId = computer.deviceId.takeIf { it.isNotEmpty() },
            observedFingerprint = computer.fingerprint,
            deviceId = deviceId,
            deviceName = deviceName,
            clientVersion = clientVersion,
            send = { bytes ->
                // Protocol frames are JSON, so they travel as text frames.
                if (socketRef.get()?.send(String(bytes, Charsets.UTF_8)) != true) {
                    Log.w(TAG, "A pairing frame could not be sent; the socket is gone")
                }
            },
            now = { System.currentTimeMillis() },
        )

        fun publish(state: PairingState) {
            onProgress(state)
            if (state is PairingState.AwaitingCode) {
                // The PC shows the same code and needs a human to press Allow; the confirmation has
                // to be sent for that prompt to resolve. The user verifies the code on the PC, and
                // the PC side is also what refuses when the codes differ.
                session.confirm()
            }
            if (isTerminal(state)) terminal.complete(state)
        }

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // The connect completes asynchronously, so onOpen can race the assignment below.
                socketRef.set(webSocket)
                session.start()
                publish(session.state.value)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                session.onMessage(text.toByteArray(Charsets.UTF_8))
                publish(session.state.value)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                session.onMessage(bytes.toByteArray())
                publish(session.state.value)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(NORMAL_CLOSURE, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                terminal.complete(PairingState.Failed("与电脑的连接已断开（$code），请重试。"))
            }

            override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
                // Only the transport message is surfaced; it carries no pairing secret.
                val detail = error.message ?: error::class.java.simpleName
                terminal.complete(PairingState.Failed("无法连接到电脑：$detail"))
            }
        }

        return try {
            // Building the request and the socket inside the try keeps the per-attempt client's
            // threads and pool from leaking when the URL is rejected.
            val request = Request.Builder().url(wssUrl(computer.host, computer.port)).build()
            socket = client.newWebSocket(request, listener)
            socketRef.set(socket)
            withTimeout(PAIRING_TIMEOUT_MILLIS) { terminal.await() }
        } catch (error: TimeoutCancellationException) {
            PairingState.Failed("等待电脑端确认超时，请重试。")
        } finally {
            socket?.cancel()
            // Each attempt pins its own TLS configuration, so its client is not reused: release its
            // threads and pooled connections instead of leaking one client per pairing attempt.
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}

/**
 * `wss://{host}:{port}/`, which is the path the Windows side serves. An IPv6 literal needs brackets
 * to stay a valid authority.
 */
internal fun wssUrl(host: String, port: Int): String {
    val authority = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
    return "wss://$authority:$port/"
}

private fun isTerminal(state: PairingState): Boolean =
    state is PairingState.Paired || state is PairingState.Rejected || state is PairingState.Failed
