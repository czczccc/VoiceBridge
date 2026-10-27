package com.voicebridge.android.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.voicebridge.android.R

internal enum class OrbMode { Idle, Connecting, Active, Error }

/**
 * The centerpiece: a soft gradient sphere that encodes connection state.
 *
 * It deliberately does not react to sound. VoiceBridge has no microphone permission, so there is
 * no audio level to draw; a fake waveform would suggest the app is recording when it is not.
 */
@Composable
internal fun VoiceOrb(diameter: Dp, mode: OrbMode, onClick: () -> Unit) {
    val transition = rememberInfiniteTransition()
    val duration = when (mode) {
        OrbMode.Active -> 900
        OrbMode.Connecting -> 1_200
        OrbMode.Idle -> 2_600
        OrbMode.Error -> 1_800
    }
    val targetScale = when (mode) {
        OrbMode.Active -> 1.035f
        OrbMode.Connecting -> 1.06f
        OrbMode.Idle -> 1.012f
        OrbMode.Error -> 1f
    }
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = targetScale,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = duration, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
    )
    val colors = when (mode) {
        OrbMode.Active -> listOf(Color(0xFF5C87FF), Color(0xFFAFC6FF), Color(0xFFF7FAFF))
        OrbMode.Connecting -> listOf(Color(0xFF93B2FF), Color(0xFFD5E2FF), Color(0xFFFFFFFF))
        OrbMode.Idle -> listOf(Color(0xFF7FA5FF), Color(0xFFCEDCFF), Color(0xFFFBFBFA))
        OrbMode.Error -> listOf(Color(0xFFFF8A80), Color(0xFFFFCFCB), Color(0xFFFFF8F7))
    }
    val label = stringResource(
        when (mode) {
            OrbMode.Active -> R.string.voice_orb_active
            OrbMode.Connecting -> R.string.voice_orb_connecting
            OrbMode.Idle -> R.string.voice_orb_idle
            OrbMode.Error -> R.string.voice_orb_error
        },
    )

    Box(
        modifier = Modifier
            .size(diameter)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(24.dp, CircleShape)
            .clip(CircleShape)
            .background(brush = Brush.radialGradient(colors = colors), shape = CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            drawCircle(
                color = Color.White.copy(alpha = 0.22f),
                radius = size.minDimension * 0.16f,
                center = Offset(size.width * 0.32f, size.height * 0.24f),
            )
        }
    }
}
