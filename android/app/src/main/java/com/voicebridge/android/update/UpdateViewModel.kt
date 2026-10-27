package com.voicebridge.android.update

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface UpdateState {
    data object Checking : UpdateState
    data object Current : UpdateState
    data class Available(val manifest: UpdateManifest) : UpdateState
    data object Downloading : UpdateState
    data class Ready(val file: File) : UpdateState
    data object Failed : UpdateState
}

class UpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = UpdateRepository(application)
    private val mutableState = MutableStateFlow<UpdateState>(UpdateState.Checking)
    val state = mutableState.asStateFlow()
    private var busy = false

    init {
        check()
    }

    fun check() {
        if (busy) return
        busy = true
        mutableState.value = UpdateState.Checking
        viewModelScope.launch {
            try {
                val manifest = repository.check()
                mutableState.value = manifest?.let { UpdateState.Available(it) } ?: UpdateState.Current
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.value = UpdateState.Failed
            } finally {
                busy = false
            }
        }
    }

    fun download(manifest: UpdateManifest) {
        if (busy) return
        busy = true
        mutableState.value = UpdateState.Downloading
        viewModelScope.launch {
            try {
                mutableState.value = UpdateState.Ready(repository.download(manifest))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.value = UpdateState.Failed
            } finally {
                busy = false
            }
        }
    }
}
