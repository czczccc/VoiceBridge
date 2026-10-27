package com.voicebridge.android.ui

import android.app.Application
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voicebridge.android.BuildConfig
import com.voicebridge.android.input.SnapshotGate
import com.voicebridge.android.network.BridgeClient
import com.voicebridge.android.network.BridgeConnectionState
import com.voicebridge.android.network.BridgeSession
import com.voicebridge.android.pairing.StoredCredential
import com.voicebridge.android.protocol.TextRange as ProtocolTextRange
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 30-80 ms is the documented range; the exact window is a build-time setting. */
private val SNAPSHOT_WINDOW_MILLIS = BuildConfig.SNAPSHOT_WINDOW_MS

/**
 * Connects the paired PC to the text field.
 *
 * The text field stays owned by [InputViewModel]; this view model only observes it and forwards the
 * coalesced text through a [SnapshotGate] to the [BridgeClient]. The credential arrives through
 * [useCredential] from whoever loaded it, so pairing and unpairing take effect without a restart.
 *
 * Everything runs in [viewModelScope]: leaving the app cancels the gate, the collectors and the
 * socket.
 */
class SyncViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow<BridgeConnectionState>(BridgeConnectionState.Disconnected)
    val state: StateFlow<BridgeConnectionState> = _state.asStateFlow()

    private val _session = MutableStateFlow<BridgeSession?>(null)
    val session: StateFlow<BridgeSession?> = _session.asStateFlow()

    private val gate = SnapshotGate(
        windowMillis = SNAPSHOT_WINDOW_MILLIS,
        send = { text, selection -> bridge?.sendSnapshot(text, selection) },
        // The gate stays a plain single-threaded object; only the deadline is dispatched here, and
        // it lands back on the main thread, which is the thread every other gate call uses.
        schedule = { millis, block ->
            viewModelScope.launch {
                delay(millis)
                block()
            }
        },
    )

    private var bridge: BridgeClient? = null
    private var bridgeScope: Job? = null
    private var runningFor: StoredCredential? = null

    private var text: InputViewModel? = null
    private var textJob: Job? = null

    /** Starts (or stops, for null) the bridge for [credential]; an unchanged credential is a no-op. */
    fun useCredential(credential: StoredCredential?) {
        if (credential == runningFor) return
        stopBridge()
        if (credential == null) return
        runningFor = credential
        val client = BridgeClient(
            credential = credential,
            deviceId = clientDeviceId(getApplication()),
            clientVersion = BuildConfig.VERSION_NAME,
        )
        bridge = client
        bridgeScope = viewModelScope.launch {
            launch { client.state.collect { _state.value = it } }
            launch { client.session.collect { onSession(it) } }
        }
        client.start()
    }

    /** Feeds every accepted edit of [model] through the gate; [detachInput] or [onCleared] ends it. */
    fun attachInput(model: InputViewModel) {
        if (text === model && textJob?.isActive == true) return
        detachInput()
        text = model
        textJob = viewModelScope.launch {
            // revision is the single "something changed" signal; value carries the payload it names.
            model.revision.collect {
                val current = model.value.value
                gate.onText(current.text, current.protocolSelection())
            }
        }
    }

    fun detachInput() {
        textJob?.cancel()
        textJob = null
        text = null
    }

    override fun onCleared() {
        detachInput()
        stopBridge()
    }

    private fun onSession(session: BridgeSession?) {
        _session.value = session
        // A session never resumes: the old numbering and the dedupe memory go with it, and the PC
        // needs the full text before the user's next keystroke.
        gate.reset()
        val current = text?.value?.value
        if (session != null && current != null) {
            gate.sendNow(current.text, current.protocolSelection())
        }
    }

    private fun stopBridge() {
        bridgeScope?.cancel()
        bridgeScope = null
        bridge?.stop()
        bridge = null
        runningFor = null
        gate.reset()
        _session.value = null
        _state.value = BridgeConnectionState.Disconnected
    }
}

/**
 * The caret as the protocol wants it: clamped into the text and never inside a surrogate pair, which
 * the encoder rejects. A back-to-front selection is flattened because only the covered range matters.
 */
private fun TextFieldValue.protocolSelection(): ProtocolTextRange {
    fun boundary(offset: Int): Long {
        var index = offset.coerceIn(0, text.length)
        if (index in 1 until text.length &&
            text[index - 1].isHighSurrogate() && text[index].isLowSurrogate()
        ) {
            index -= 1
        }
        return index.toLong()
    }
    return ProtocolTextRange(boundary(selection.min), boundary(selection.max))
}
