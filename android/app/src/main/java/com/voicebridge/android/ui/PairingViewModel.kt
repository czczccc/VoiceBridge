package com.voicebridge.android.ui

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voicebridge.android.BuildConfig
import com.voicebridge.android.discovery.DiscoveredComputer
import com.voicebridge.android.discovery.Discovery
import com.voicebridge.android.discovery.DiscoveryException
import com.voicebridge.android.discovery.isCanonicalFingerprint
import com.voicebridge.android.network.PairingClient
import com.voicebridge.android.pairing.AndroidCredentialStore
import com.voicebridge.android.pairing.CredentialStore
import com.voicebridge.android.pairing.PairingState
import com.voicebridge.android.pairing.StoredCredential
import com.voicebridge.android.pairing.randomBase64Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "PairingViewModel"
private const val IDENTITY_PREFERENCES = "voicebridge-identity"
private const val ENTRY_DEVICE_ID = "deviceId"

/** 9 random bytes encode to 12 base64url characters: opaque, and short enough for the ID field. */
private const val DEVICE_ID_BYTES = 9

/**
 * A target typed by hand carries no advertised Windows device id, so the id is learned from the
 * pairing challenge over the already pinned connection.
 */
private const val UNKNOWN_DEVICE_ID = ""

sealed class PairingUiState {
    object Idle : PairingUiState()

    /** A code is on screen and the Windows side is waiting for its user to press Allow. */
    data class Waiting(val code: String) : PairingUiState()

    object Pairing : PairingUiState()

    data class Paired(val name: String) : PairingUiState()

    /** [message] is user facing and never contains an access token. */
    data class Error(val message: String) : PairingUiState()
}

/**
 * Owns discovery, the single pairing attempt and the stored credential. Everything that touches the
 * network runs in [viewModelScope], so leaving the screen cancels an attempt and its socket.
 */
class PairingViewModel(application: Application) : AndroidViewModel(application) {
    private val store: CredentialStore = AndroidCredentialStore(application)
    private val client = PairingClient(
        deviceId = clientDeviceId(application),
        clientVersion = BuildConfig.VERSION_NAME,
    )
    private val discovery = Discovery(application)
    private val deviceName = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android"

    private val _computers = MutableStateFlow<List<DiscoveredComputer>>(emptyList())
    val computers: StateFlow<List<DiscoveredComputer>> = _computers.asStateFlow()

    private val _state = MutableStateFlow<PairingUiState>(PairingUiState.Idle)
    val state: StateFlow<PairingUiState> = _state.asStateFlow()

    private val _stored = MutableStateFlow<StoredCredential?>(null)
    val stored: StateFlow<StoredCredential?> = _stored.asStateFlow()

    private var discoveryJob: Job? = null
    private var pairingJob: Job? = null

    init {
        viewModelScope.launch { loadStored() }
    }

    fun startDiscovery() {
        if (discoveryJob?.isActive == true) return
        discoveryJob = viewModelScope.launch {
            try {
                discovery.start().collect { found -> _computers.value = found }
            } catch (error: CancellationException) {
                throw error
            } catch (error: DiscoveryException) {
                Log.w(TAG, "DNS-SD browse could not start", error)
                _state.value = PairingUiState.Error("无法搜索局域网内的电脑，请改用“手动连接”。")
            } catch (error: Exception) {
                Log.w(TAG, "Discovery failed", error)
                _state.value = PairingUiState.Error("搜索电脑时出错，请重试。")
            }
        }
    }

    fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
        _computers.value = emptyList()
    }

    fun pair(computer: DiscoveredComputer) {
        startPairing(computer)
    }

    fun pairManual(host: String, port: Int, fingerprint: String) {
        val targetHost = host.trim()
        val pin = fingerprint.trim()
        when {
            targetHost.isEmpty() -> {
                _state.value = PairingUiState.Error("请输入电脑的主机地址。")
                return
            }
            port !in 1..65535 -> {
                _state.value = PairingUiState.Error("端口必须在 1 到 65535 之间。")
                return
            }
            !isCanonicalFingerprint(pin) -> {
                _state.value = PairingUiState.Error("证书指纹必须是 SHA256: 加 64 位小写十六进制。")
                return
            }
        }
        startPairing(
            DiscoveredComputer(
                // No DNS-SD instance for a hand-typed target; the name is only used in the list.
                serviceName = "manual",
                deviceId = UNKNOWN_DEVICE_ID,
                displayName = targetHost,
                host = targetHost,
                port = port,
                fingerprint = pin,
            ),
        )
    }

    fun unpair() {
        viewModelScope.launch {
            try {
                store.clear()
                _stored.value = null
                _state.value = PairingUiState.Idle
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Cannot clear the pairing credential", error)
                _state.value = PairingUiState.Error("无法清除已保存的配对信息，请重试。")
                return@launch
            }
            startDiscovery()
        }
    }

    private fun startPairing(computer: DiscoveredComputer) {
        if (pairingJob?.isActive == true) return
        // Keeping the browse running would let a second tap start a competing attempt.
        stopDiscovery()
        _state.value = PairingUiState.Pairing
        pairingJob = viewModelScope.launch {
            val result = try {
                client.pair(computer, deviceName) { progress ->
                    if (progress is PairingState.AwaitingCode) {
                        _state.value = PairingUiState.Waiting(progress.code)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // A malformed pin or host is rejected before any connection is attempted.
                Log.w(TAG, "The pairing attempt failed", error)
                reportFailure("配对失败，请重试。")
                return@launch
            }
            applyResult(computer, result)
        }
    }

    /** Shows [message] and resumes browsing, so the user can retry by tapping a computer again. */
    private fun reportFailure(message: String) {
        _state.value = PairingUiState.Error(message)
        startDiscovery()
    }

    private suspend fun applyResult(computer: DiscoveredComputer, result: PairingState) {
        when (result) {
            is PairingState.Paired -> savePairedCredential(computer, result)
            is PairingState.Rejected -> reportFailure(result.reason)
            is PairingState.Failed -> reportFailure(result.reason)
            PairingState.Idle, is PairingState.AwaitingCode ->
                reportFailure("配对没有完成，请重试。")
        }
    }

    private suspend fun savePairedCredential(computer: DiscoveredComputer, paired: PairingState.Paired) {
        // The device id comes from the result, so a hand-typed pairing stores the real Windows id.
        val credential = StoredCredential(
            serverDeviceId = paired.serverDeviceId,
            serverName = paired.serverName,
            host = computer.host,
            port = computer.port,
            fingerprint = computer.fingerprint,
            token = paired.token,
        )
        try {
            store.save(credential)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Cannot store the pairing credential", error)
            reportFailure("配对成功，但保存配对信息失败，请重新配对。")
            return
        }
        _stored.value = credential
        _state.value = PairingUiState.Paired(paired.serverName)
    }

    private suspend fun loadStored() {
        try {
            _stored.value = store.load()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Cannot read the stored credential", error)
            _state.value = PairingUiState.Error("无法读取已保存的配对信息，请重新配对。")
        }
    }
}

/**
 * The phone's own id as the Windows side sees it. It is stable across attempts and app restarts so
 * the PC keeps showing the same device instead of a new one after every pairing.
 */
internal fun clientDeviceId(application: Application): String {
    val preferences = application.getSharedPreferences(IDENTITY_PREFERENCES, Context.MODE_PRIVATE)
    preferences.getString(ENTRY_DEVICE_ID, null)?.let { return it }
    val generated = "android-${randomBase64Url(DEVICE_ID_BYTES)}"
    // commit() rather than apply(): a dropped write would give the device a new id on the next run.
    if (!preferences.edit().putString(ENTRY_DEVICE_ID, generated).commit()) {
        Log.w(TAG, "Cannot persist the client device id; it changes on the next launch")
    }
    return generated
}
