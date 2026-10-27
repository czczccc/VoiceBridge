package com.voicebridge.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voicebridge.android.R
import com.voicebridge.android.network.BridgeConnectionState
import com.voicebridge.android.network.BridgeSession
import com.voicebridge.android.pairing.StoredCredential
import kotlinx.coroutines.delay

private val Ink = Color(0xFF1C1C1E)
private val Paper = Color(0xFFFFFFFF)
private val Muted = Ink.copy(alpha = 0.38f)
private val Backdrop = listOf(Color(0xFFFFFFFF), Color(0xFFF2F2F0))

/**
 * The voice-mode screen: a quiet full screen with one sphere, the current sentence, and controls.
 *
 * Speech recognition belongs to the user's IME. The screen keeps the field focused and asks for the
 * keyboard, but it never records audio, never starts recognition and never taps the IME microphone.
 * Settings and connection details live behind the two round buttons at the top.
 */
@Composable
fun VoiceScreen(
    input: InputViewModel,
    sync: SyncViewModel,
    stored: StoredCredential?,
    openInputRequests: Int,
    onOpenSettings: () -> Unit,
) {
    val value by input.value.collectAsStateWithLifecycle()
    val syncState by sync.state.collectAsStateWithLifecycle()
    val session by sync.session.collectAsStateWithLifecycle()
    val paused by sync.pausedByUser.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    var connectionDialogVisible by rememberSaveable { mutableStateOf(false) }

    val continueSpeaking: () -> Unit = {
        focusRequester.requestFocus()
        keyboard?.show()
    }
    val mode = when {
        paused -> OrbMode.Paused
        syncState is BridgeConnectionState.Error -> OrbMode.Error
        session != null -> OrbMode.Active
        syncState is BridgeConnectionState.Connecting ||
            syncState is BridgeConnectionState.Reconnecting -> OrbMode.Connecting
        else -> OrbMode.Idle
    }

    LaunchedEffect(openInputRequests) {
        focusRequester.requestFocus()
        // A notification tap can deliver onNewIntent just before the window regains focus, and an
        // IME request made at that instant is dropped. The delay is still user-initiated.
        delay(150)
        keyboard?.show()
    }
    // Returning from the background should put the microphone back within reach.
    LifecycleResumeEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
        onPauseOrDispose { }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(colors = Backdrop))
            .imePadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundIconButton(
                    label = stringResource(R.string.voice_connections),
                    onClick = { connectionDialogVisible = true },
                ) { MenuGlyph() }
                RoundIconButton(
                    label = stringResource(R.string.voice_settings),
                    onClick = onOpenSettings,
                ) { SlidersGlyph() }
            }

            Box(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = if (stored != null) 64.dp else 0.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    VoiceStatus(
                        stored = stored,
                        state = syncState,
                        session = session,
                        paused = paused,
                    )
                }
                if (stored != null) {
                    TextButton(
                        onClick = { sync.setPausedByUser(!paused) },
                        modifier = Modifier.align(Alignment.CenterEnd),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Text(
                            text = stringResource(
                                if (paused) R.string.voice_resume else R.string.voice_pause,
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            if (stored == null) {
                Text(
                    text = stringResource(R.string.voice_unpaired),
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                val orbSize = minOf(maxWidth * 0.72f, maxHeight * 0.95f, 340.dp)
                VoiceOrb(diameter = orbSize, mode = mode, onClick = continueSpeaking)
            }

            CurrentText(
                input = input,
                value = value,
                focusRequester = focusRequester,
                modifier = Modifier.fillMaxWidth(),
            )

            if (!imeVisible) {
                Text(
                    text = stringResource(R.string.voice_continue_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val paired = stored != null
                val canFinish = !paused && value.text.isNotEmpty()
                val leading: (@Composable () -> Unit)? = if (paired) {
                    { KeyboardGlyph() }
                } else {
                    null
                }
                Pill(
                    label = stringResource(
                        if (paired) R.string.voice_continue else R.string.voice_pair_action,
                    ),
                    onClick = if (paired) continueSpeaking else onOpenSettings,
                    modifier = Modifier.weight(1f),
                    leading = leading,
                )
                Pill(
                    label = stringResource(R.string.input_new_segment),
                    enabled = canFinish,
                    onClick = sync::finishSegment,
                )
                EndButton(
                    enabled = canFinish,
                    onClick = sync::finishSegment,
                    label = stringResource(R.string.voice_end_segment),
                )
            }

            // The privacy note is only shown when the keyboard is hidden, where there is room.
            if (!imeVisible) {
                Text(
                    text = stringResource(R.string.input_ime_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    if (connectionDialogVisible) {
        AlertDialog(
            onDismissRequest = { connectionDialogVisible = false },
            title = { Text(text = stringResource(R.string.voice_connections)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SyncStatusRow(stored = stored, state = syncState, session = session)
                    stored?.let { credential ->
                        Text(
                            text = stringResource(R.string.voice_paired_to, credential.serverName),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        connectionDialogVisible = false
                        onOpenSettings()
                    },
                ) {
                    Text(text = stringResource(R.string.voice_settings))
                }
            },
            dismissButton = {
                TextButton(onClick = { connectionDialogVisible = false }) {
                    Text(text = stringResource(R.string.voice_close))
                }
            },
        )
    }
}

@Composable
private fun VoiceStatus(
    stored: StoredCredential?,
    state: BridgeConnectionState,
    session: BridgeSession?,
    paused: Boolean,
) {
    if (paused) {
        Text(
            text = stringResource(R.string.voice_status_paused),
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }
    if (state is BridgeConnectionState.Error) {
        // Errors keep the detailed, actionable explanation from the shared status row.
        SyncStatusRow(stored = stored, state = state, session = session)
        return
    }
    val text = when {
        session != null ->
            stringResource(R.string.voice_status_syncing_to, session.targetLabel)
        state is BridgeConnectionState.Reconnecting ->
            stringResource(R.string.voice_status_reconnecting, state.attempt)
        state is BridgeConnectionState.Connected && stored != null ->
            stringResource(R.string.voice_status_connected, stored.serverName)
        state is BridgeConnectionState.Connecting -> stringResource(R.string.voice_status_connecting)
        stored == null -> stringResource(R.string.voice_status_unpaired)
        else -> stringResource(R.string.voice_status_disconnected)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = Muted,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun CurrentText(
    input: InputViewModel,
    value: TextFieldValue,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = value,
        onValueChange = input::onValueChange,
        modifier = modifier
            .heightIn(min = 44.dp, max = 120.dp)
            .focusRequester(focusRequester),
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            color = Ink,
            textAlign = TextAlign.Center,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                if (value.text.isEmpty()) {
                    Text(
                        text = stringResource(R.string.voice_hint),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Muted,
                        textAlign = TextAlign.Center,
                    )
                }
                innerTextField()
            }
        },
    )
}

@Composable
private fun RoundIconButton(
    label: String,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(44.dp)
            .shadow(6.dp, CircleShape)
            .clip(CircleShape)
            .background(Paper)
            .semantics { contentDescription = label },
    ) {
        icon()
    }
}

@Composable
private fun MenuGlyph() {
    Canvas(modifier = Modifier.size(20.dp)) {
        val stroke = 2.dp.toPx()
        drawLine(
            color = Ink,
            start = Offset(size.width * 0.12f, size.height * 0.38f),
            end = Offset(size.width * 0.88f, size.height * 0.38f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = Ink,
            start = Offset(size.width * 0.12f, size.height * 0.62f),
            end = Offset(size.width * 0.88f, size.height * 0.62f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun SlidersGlyph() {
    Canvas(modifier = Modifier.size(20.dp)) {
        val stroke = 2.dp.toPx()
        val knob = 2.6.dp.toPx()
        drawLine(
            color = Ink,
            start = Offset(size.width * 0.08f, size.height * 0.35f),
            end = Offset(size.width * 0.92f, size.height * 0.35f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawCircle(color = Ink, radius = knob, center = Offset(size.width * 0.68f, size.height * 0.35f))
        drawLine(
            color = Ink,
            start = Offset(size.width * 0.08f, size.height * 0.65f),
            end = Offset(size.width * 0.92f, size.height * 0.65f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawCircle(color = Ink, radius = knob, center = Offset(size.width * 0.32f, size.height * 0.65f))
    }
}

@Composable
private fun Pill(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .shadow(4.dp, RoundedCornerShape(50))
            .clip(RoundedCornerShape(50))
            .background(Paper)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (leading != null) {
                leading()
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) Ink else Muted,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun KeyboardGlyph() {
    Canvas(modifier = Modifier.size(18.dp)) {
        val stroke = 1.6.dp.toPx()
        drawRoundRect(
            color = Ink,
            topLeft = Offset(size.width * 0.04f, size.height * 0.2f),
            size = Size(size.width * 0.92f, size.height * 0.58f),
            cornerRadius = CornerRadius(3.dp.toPx()),
            style = Stroke(width = stroke),
        )
        val dot = 1.1.dp.toPx()
        listOf(0.25f, 0.5f, 0.75f).forEach { x ->
            drawCircle(
                color = Ink,
                radius = dot,
                center = Offset(size.width * x, size.height * 0.45f),
            )
        }
        drawLine(
            color = Ink,
            start = Offset(size.width * 0.32f, size.height * 0.63f),
            end = Offset(size.width * 0.68f, size.height * 0.63f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun EndButton(enabled: Boolean, onClick: () -> Unit, label: String) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(if (enabled) Ink else Ink.copy(alpha = 0.25f))
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(20.dp)) {
            val stroke = 2.4.dp.toPx()
            drawLine(
                color = Color.White,
                start = Offset(size.width * 0.05f, size.height * 0.05f),
                end = Offset(size.width * 0.95f, size.height * 0.95f),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = Color.White,
                start = Offset(size.width * 0.95f, size.height * 0.05f),
                end = Offset(size.width * 0.05f, size.height * 0.95f),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}
