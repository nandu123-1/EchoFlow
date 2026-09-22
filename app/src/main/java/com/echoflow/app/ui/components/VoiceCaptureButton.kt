package com.echoflow.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.echoflow.app.ui.theme.*

/**
 * Animated microphone button with pulse effect when listening.
 * Central capture interaction for EchoFlow.
 */
@Composable
fun VoiceCaptureButton(
    isListening: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Pulse animation when listening
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )

    val buttonColor = if (isListening) EchoSecondary else EchoPrimary

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        // Outer pulse ring (visible only when listening)
        if (isListening) {
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .scale(pulseScale * 1.3f)
                    .clip(CircleShape)
                    .background(buttonColor.copy(alpha = pulseAlpha * 0.3f))
            )

            Box(
                modifier = Modifier
                    .size(100.dp)
                    .scale(pulseScale)
                    .clip(CircleShape)
                    .background(buttonColor.copy(alpha = pulseAlpha * 0.5f))
            )
        }

        // Glow ring
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            buttonColor.copy(alpha = if (isListening) glowAlpha else 0.15f),
                            Color.Transparent
                        )
                    )
                )
        )

        // Main button
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        colors = if (isListening) {
                            listOf(EchoSecondary, EchoSecondaryLight)
                        } else {
                            listOf(EchoPrimary, EchoPrimaryLight)
                        }
                    )
                )
                .border(
                    width = 1.5.dp,
                    color = Color.White.copy(alpha = 0.2f),
                    shape = CircleShape
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isListening) Icons.Filled.Stop else Icons.Filled.Mic,
                contentDescription = if (isListening) "Stop recording" else "Start recording",
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

/**
 * AI status chip showing model availability.
 */
@Composable
fun AiStatusChip(
    isModelReady: Boolean,
    engineName: String,
    modifier: Modifier = Modifier
) {
    val dotColor = if (isModelReady) EchoSuccess else EchoTextTertiary
    val label = if (isModelReady) "Local AI" else "Fallback"

    Row(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(EchoSurfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = EchoTextSecondary
        )
    }
}
