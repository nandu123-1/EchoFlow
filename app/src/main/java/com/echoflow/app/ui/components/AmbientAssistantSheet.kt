package com.echoflow.app.ui.components

import androidx.compose.animation.*
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.echoflow.app.assistant.AssistantState
import com.echoflow.app.domain.model.ActionGraph
import com.echoflow.app.domain.model.ExecutionState
import com.echoflow.app.domain.model.ParsedAction
import com.echoflow.app.ui.theme.*

@Composable
fun AmbientAssistantSheet(
    assistantState: AssistantState,
    transcript: String,
    statusText: String,
    spokenResponse: String?,
    actionGraph: ActionGraph?,
    executionStates: Map<String, ExecutionState>,
    onCancel: () -> Unit,
    onExecute: () -> Unit,
    onConfirmAction: (String) -> Unit = {},
    onDeleteAction: (String) -> Unit = {},
    onOpenCalendar: (Long) -> Unit = {},
    onCancelAction: (String) -> Unit = {},
    onCancelAll: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (assistantState == AssistantState.IDLE) return

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.92f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { /* block background clicks */ },
        contentAlignment = Alignment.BottomCenter
    ) {
        // Ambient background glow
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(300.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            when (assistantState) {
                                AssistantState.LISTENING, AssistantState.WAITING_FOLLOWUP -> EchoSecondary.copy(alpha = 0.25f)
                                AssistantState.ANALYZING, AssistantState.PREPARING -> EchoAccent.copy(alpha = 0.25f)
                                AssistantState.EXECUTING -> EchoWarning.copy(alpha = 0.25f)
                                AssistantState.RESPONDING -> EchoSuccess.copy(alpha = 0.25f)
                                AssistantState.ERROR -> EchoError.copy(alpha = 0.25f)
                                else -> EchoPrimary.copy(alpha = 0.15f)
                            },
                            Color.Transparent
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(EchoSecondary, CircleShape)
                    )
                    Text(
                        text = "EchoFlow Ambient",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = EchoTextPrimary
                    )
                }

                IconButton(onClick = onCancel) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "Close Assistant",
                        tint = EchoTextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Animated Visual Aura / Waveform
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(80.dp)
            ) {
                // Outer glowing ring
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .scale(pulseScale)
                        .background(
                            when (assistantState) {
                                AssistantState.LISTENING, AssistantState.WAITING_FOLLOWUP -> EchoSecondary.copy(alpha = pulseAlpha * 0.4f)
                                AssistantState.ANALYZING -> EchoAccent.copy(alpha = pulseAlpha * 0.4f)
                                AssistantState.RESPONDING -> EchoSuccess.copy(alpha = pulseAlpha * 0.4f)
                                else -> EchoPrimary.copy(alpha = 0.2f)
                            },
                            CircleShape
                        )
                )

                // Inner core
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(EchoPrimary, EchoSecondary)
                            ),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when (assistantState) {
                            AssistantState.LISTENING, AssistantState.WAITING_FOLLOWUP -> Icons.Outlined.Mic
                            AssistantState.ANALYZING -> Icons.Outlined.AutoAwesome
                            AssistantState.EXECUTING -> Icons.Outlined.PlayArrow
                            AssistantState.RESPONDING -> Icons.Outlined.Check
                            AssistantState.ERROR -> Icons.Outlined.Warning
                            else -> Icons.Outlined.GraphicEq
                        },
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // State Badge / Status Text
            Text(
                text = when (assistantState) {
                    AssistantState.LISTENING -> "I'm listening..."
                    AssistantState.TRANSCRIBING -> "Transcribing speech..."
                    AssistantState.ANALYZING -> "Analyzing your request..."
                    AssistantState.PREPARING -> "Preparing your actions..."
                    AssistantState.READY_FOR_REVIEW -> "Review before I execute"
                    AssistantState.WAITING_FOLLOWUP -> "Listening: say 'Yes' to confirm or 'Cancel'"
                    AssistantState.EXECUTING -> "Executing actions..."
                    AssistantState.RESPONDING -> "Done"
                    AssistantState.ERROR -> "Something went wrong"
                    else -> statusText
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = when (assistantState) {
                    AssistantState.LISTENING, AssistantState.WAITING_FOLLOWUP -> EchoSecondary
                    AssistantState.ANALYZING -> EchoAccent
                    AssistantState.RESPONDING -> EchoSuccess
                    AssistantState.ERROR -> EchoError
                    else -> EchoTextPrimary
                },
                textAlign = TextAlign.Center
            )

            // Transcript display
            if (transcript.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = EchoDarkSurface,
                    border = BorderStroke(1.dp, EchoGlassStroke)
                ) {
                    Text(
                        text = "\"$transcript\"",
                        style = MaterialTheme.typography.bodyMedium,
                        color = EchoTextSecondary,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        textAlign = TextAlign.Center
                    )
                }
            }

            // Spoken Response Banner
            if (!spokenResponse.isNullOrBlank() && assistantState != AssistantState.LISTENING) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = spokenResponse,
                    style = MaterialTheme.typography.bodySmall,
                    color = EchoTextTertiary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Echo Palette Cards (Reviewable ActionGraph)
            val actions = actionGraph?.actions ?: emptyList()
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
                            executionState = executionStates[action.id] ?: action.executionState,
                            onConfirm = { onConfirmAction(action.id) },
                            onDelete = { onDeleteAction(action.id) },
                            onOpenCalendar = onOpenCalendar,
                            onCancelAction = { onCancelAction(action.id) }
                        )
                    }
                }
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val hasPendingOrScheduled = actions.any { a ->
                    val st = executionStates[a.id] ?: a.executionState
                    st.isPendingOrScheduled
                }

                if (hasPendingOrScheduled && assistantState != AssistantState.EXECUTING) {
                    OutlinedButton(
                        onClick = onCancelAll,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = EchoError)
                    ) {
                        Text("Cancel All", color = EchoError)
                    }
                } else {
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = EchoTextSecondary)
                    ) {
                        Text(if (assistantState == AssistantState.RESPONDING) "Close" else "Dismiss")
                    }
                }

                if (assistantState == AssistantState.READY_FOR_REVIEW ||
                    assistantState == AssistantState.WAITING_FOLLOWUP
                ) {
                    Button(
                        onClick = onExecute,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = EchoAccent)
                    ) {
                        Icon(imageVector = Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Execute All")
                    }
                }
            }
        }
    }
}
