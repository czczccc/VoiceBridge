package com.voicebridge.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.voicebridge.android.R
import com.voicebridge.android.discovery.DiscoveredComputer

/**
 * Discovery list, the progress of the single pairing attempt and the manual fallback for a network
 * where DNS-SD does not work.
 */
@Composable
fun PairingScreen(viewModel: PairingViewModel = viewModel()) {
    val computers by viewModel.computers.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val stored by viewModel.stored.collectAsStateWithLifecycle()
    var manualEntryVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.startDiscovery() }
    DisposableEffect(Unit) {
        onDispose { viewModel.stopDiscovery() }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.pairing_title),
            style = MaterialTheme.typography.titleMedium,
        )
        stored?.let { credential ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = stringResource(R.string.pairing_paired, credential.serverName))
                OutlinedButton(onClick = viewModel::unpair) {
                    Text(text = stringResource(R.string.pairing_repair))
                }
            }
        }
        StatusBlock(state)
        if (computers.isEmpty()) {
            Text(text = stringResource(R.string.pairing_empty), style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(text = stringResource(R.string.pairing_tap_to_pair), style = MaterialTheme.typography.bodySmall)
            computers.forEach { computer ->
                ComputerRow(computer = computer, onPair = { viewModel.pair(computer) })
            }
        }
        OutlinedButton(onClick = { manualEntryVisible = true }) {
            Text(text = stringResource(R.string.pairing_manual))
        }
    }

    if (manualEntryVisible) {
        ManualEntryDialog(
            onDismiss = { manualEntryVisible = false },
            onConnect = { host, port, fingerprint ->
                manualEntryVisible = false
                viewModel.pairManual(host, port, fingerprint)
            },
        )
    }
}

@Composable
private fun StatusBlock(state: PairingUiState) {
    when (state) {
        PairingUiState.Idle -> Text(
            text = stringResource(R.string.pairing_searching),
            style = MaterialTheme.typography.bodySmall,
        )
        PairingUiState.Pairing -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator()
            Text(text = stringResource(R.string.pairing_connecting))
        }
        is PairingUiState.Waiting -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.pairing_waiting_allow),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.pairing_code, state.code),
                style = MaterialTheme.typography.headlineSmall,
            )
        }
        is PairingUiState.Paired -> Text(
            text = stringResource(R.string.pairing_paired, state.name),
            color = MaterialTheme.colorScheme.primary,
        )
        is PairingUiState.Error -> Text(
            text = state.message,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun ComputerRow(computer: DiscoveredComputer, onPair: () -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onPair)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = computer.displayName, style = MaterialTheme.typography.titleSmall)
            Text(
                text = stringResource(R.string.pairing_host_port, computer.host, computer.port),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ManualEntryDialog(
    onDismiss: () -> Unit,
    onConnect: (host: String, port: Int, fingerprint: String) -> Unit,
) {
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var fingerprint by remember { mutableStateOf("") }
    val parsedPort = port.toIntOrNull()
    val connectable = host.isNotBlank() &&
        parsedPort != null &&
        parsedPort in 1..65535 &&
        fingerprint.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.pairing_manual_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text(text = stringResource(R.string.pairing_manual_host)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { input -> port = input.filter { it.isDigit() } },
                    label = { Text(text = stringResource(R.string.pairing_manual_port)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = fingerprint,
                    onValueChange = { fingerprint = it },
                    label = { Text(text = stringResource(R.string.pairing_manual_fingerprint)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { parsedPort?.let { onConnect(host, it, fingerprint) } },
                enabled = connectable,
            ) {
                Text(text = stringResource(R.string.pairing_manual_connect))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.pairing_cancel))
            }
        },
    )
}
