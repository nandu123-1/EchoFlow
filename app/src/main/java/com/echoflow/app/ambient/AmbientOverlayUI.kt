package com.echoflow.app.ambient

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.echoflow.app.ui.components.ActionCard
import com.echoflow.app.ui.theme.*

/**
 * Compose UI rendered directly within the TYPE_ACCESSIBILITY_OVERLAY window.
 *
 * Implements Ocean Breeze visual language:
 * - Keeps underlying app visible behind translucent scrim
 * - Floating bottom sheet on OceanBackground surface (#E8F6FF)
 * - Soft breathing ambient listening animation (#3BA7F2 / #7FE7D6)
 * - Clear action review cards with [ DELEGATE ] CTA
 */
@Composable
fun AmbientOverlayScreen(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sessionState by AmbientController.sessionState.collectAsState()
    val phase = sessionState.phase

    if (phase == AmbientPhase.HIDDEN) return

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.30f,
        targetValue = 0.80f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    // Full screen overlay with semi-transparent scrim (underlying app remains visible)
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(EchoOverlayScrim)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                // Outside tap dismisses overlay
                onDismiss()
            },
        contentAlignment = Alignment.BottomCenter
    ) {
        // Floating bottom sheet panel on soft surface (#E8F6FF)
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    // Absorb taps inside panel so it doesn't trigger scrim dismiss
                },
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = OceanBackground.copy(alpha = 0.98f),
            border = BorderStroke(1.dp, OceanCardBorder),
            shadowElevation = 16.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header Bar with Ocean Breeze Branding
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "✦",
                            color = OceanSecondary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Column {
                            Text(
                                text = "EchoFlow",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = OceanPrimary
                            )
                            Text(
                                text = "Ambient Action Engine",
                                style = MaterialTheme.typography.labelSmall,
                                color = OceanSecondary
                            )
                        }
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "Close",
                            tint = EchoTextSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Pulsing Ambient Orb with Ocean Breeze Glow (#3BA7F2 -> #7FE7D6)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(84.dp)
                ) {
                    val isListening = phase == AmbientPhase.LISTENING || phase == AmbientPhase.WAITING_FOLLOWUP
                    val isSuccess = phase == AmbientPhase.RESPONDING

                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .scale(if (isListening) pulseScale else 1.0f)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        when {
                                            isSuccess -> OceanTertiary.copy(alpha = pulseAlpha * 0.5f)
                                            isListening -> OceanSecondary.copy(alpha = pulseAlpha * 0.5f)
                                            phase == AmbientPhase.ANALYZING -> OceanSecondary.copy(alpha = pulseAlpha * 0.4f)
                                            phase == AmbientPhase.ERROR -> EchoError.copy(alpha = pulseAlpha * 0.4f)
                                            else -> OceanPrimary.copy(alpha = 0.2f)
                                        },
                                        Color.Transparent
                                    )
                                ),
                                CircleShape
                            )
                    )

                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .background(
                                Brush.linearGradient(
                                    colors = if (isSuccess) {
                                        listOf(OceanTertiary, OceanSecondary)
                                    } else {
                                        listOf(OceanPrimary, OceanSecondary)
                                    }
                                ),
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = when (phase) {
                                AmbientPhase.LISTENING, AmbientPhase.WAITING_FOLLOWUP -> Icons.Outlined.Mic
                                AmbientPhase.ANALYZING -> Icons.Outlined.AutoAwesome
                                AmbientPhase.EXECUTING -> Icons.Outlined.PlayArrow
                                AmbientPhase.RESPONDING -> Icons.Outlined.Check
                                AmbientPhase.ERROR -> Icons.Outlined.Warning
                                else -> Icons.Outlined.GraphicEq
                            },
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // State Badge & Status Text
                val stateBadgeText = when (phase) {
                    AmbientPhase.READY -> "READY"
                    AmbientPhase.LISTENING -> "LISTENING"
                    AmbientPhase.ANALYZING, AmbientPhase.TRANSCRIBING -> "PROCESSING"
                    AmbientPhase.READY_FOR_REVIEW -> "SHOWING ACTIONS"
                    AmbientPhase.EXECUTING -> "EXECUTING"
                    AmbientPhase.RESPONDING -> "RESULT"
                    AmbientPhase.ERROR -> "ERROR"
                    else -> "AMBIENT"
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = OceanSecondary.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = stateBadgeText,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = OceanPrimary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = sessionState.statusText,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = OceanPrimary,
                    textAlign = TextAlign.Center
                )

                // Transcript Banner
                if (sessionState.transcript.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = OceanSurface,
                        border = BorderStroke(1.dp, OceanCardBorder)
                    ) {
                        Text(
                            text = "\"${sessionState.transcript}\"",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = EchoTextPrimary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                }

                // Spoken response prompt
                if (!sessionState.spokenResponse.isNullOrBlank() && phase != AmbientPhase.LISTENING) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = sessionState.spokenResponse ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = EchoTextSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Action cards list
                val actions = sessionState.actionGraph?.actions ?: emptyList()
                if (actions.isNotEmpty()) {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(actions, key = { it.id }) { action ->
                            ActionCard(
                                action = action,
                                executionState = sessionState.executionStates[action.id] ?: action.executionState,
                                executionMessage = sessionState.executionMessages[action.id] ?: action.executionMessage,
                                onConfirm = { AmbientController.confirmAction(action.id) },
                                onDelete = { AmbientController.deleteAction(action.id) },
                                onCancelAction = { AmbientController.cancelSingleAction(action.id) }
                            )
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Bottom Action Buttons: [ DELEGATE ] / [ Execute All ] / [ Cancel All ] / [ Close ]
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val hasPending = actions.any { a ->
                        val st = sessionState.executionStates[a.id] ?: a.executionState
                        st.isPendingOrScheduled
                    }

                    if (hasPending && phase != AmbientPhase.EXECUTING) {
                        OutlinedButton(
                            onClick = { AmbientController.cancelAll() },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = EchoError),
                            border = BorderStroke(1.dp, EchoError.copy(alpha = 0.4f))
                        ) {
                            Text("Cancel All", color = EchoError, fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = EchoTextSecondary),
                            border = BorderStroke(1.dp, OceanCardBorder)
                        ) {
                            Text(if (phase == AmbientPhase.RESPONDING) "Done" else "Dismiss", fontWeight = FontWeight.Medium)
                        }
                    }

                    if (phase == AmbientPhase.READY_FOR_REVIEW || phase == AmbientPhase.WAITING_FOLLOWUP) {
                        Button(
                            onClick = { AmbientController.executeAll() },
                            modifier = Modifier.weight(1.2f),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = OceanPrimary)
                        ) {
                            Icon(imageVector = Icons.Outlined.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("DELEGATE", fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    } else if (phase == AmbientPhase.ERROR) {
                        Button(
                            onClick = { AmbientController.startListening() },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = OceanSecondary)
                        ) {
                            Icon(imageVector = Icons.Outlined.Mic, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Try Again", color = Color.White)
                        }
                    }
                }
            }
        }
    }
}
