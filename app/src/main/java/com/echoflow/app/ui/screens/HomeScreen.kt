package com.echoflow.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicNone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.echoflow.app.demo.DemoScenarios
import com.echoflow.app.domain.model.ExecutionMode
import com.echoflow.app.domain.model.ExecutionState
import com.echoflow.app.domain.model.ModelState
import com.echoflow.app.domain.model.ParsedAction
import com.echoflow.app.ui.components.ActionCard
import com.echoflow.app.ui.theme.*
import com.echoflow.app.ui.viewmodel.EchoFlowUiState
import com.echoflow.app.ui.viewmodel.VoiceState
import java.time.LocalTime

/**
 * EchoFlow V5.1 Home Screen — Ocean Breeze Ambient Action Engine.
 *
 * Core Concept:
 * "What should I do?"
 * Central voice orb with ambient pulse, followed by clear urgency-driven action streams:
 * - NOW: Ready to run
 * - NEXT: Scheduled in timeline
 * - WAITING: Needs your attention
 * - COMPLETED: Recent outcomes
 */
@Composable
fun HomeScreen(
    uiState: EchoFlowUiState,
    modelState: ModelState,
    onProcessInput: (String) -> Unit,
    onVoiceCapture: () -> Unit,
    onConfirmAction: (String) -> Unit,
    onDeleteAction: (String) -> Unit,
    onEditAction: (ParsedAction) -> Unit,
    onExecuteAll: () -> Unit,
    onLoadDemo: (Int) -> Unit,
    onInputChange: (String) -> Unit,
    onRescheduleTomorrow: (String) -> Unit = {},
    onOpenCalendar: (Long) -> Unit = {},
    onCancelAction: (String) -> Unit = {},
    onCancelAll: () -> Unit = {},
    onRunFlagshipDemo: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var inputText by remember(uiState.input) { mutableStateOf(uiState.input) }
    var showTextInput by remember { mutableStateOf(false) }

    val currentHour = remember { LocalTime.now().hour }
    val greeting = remember(currentHour) {
        when (currentHour) {
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            in 17..21 -> "Good evening"
            else -> "Good night"
        }
    }

    // Breathing animation for ambient orb
    val infiniteTransition = rememberInfiniteTransition(label = "ambient_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = OceanBackground,
        bottomBar = {
            if (uiState.actionGraph != null && uiState.actionGraph.actions.isNotEmpty()) {
                Surface(
                    color = OceanSurface,
                    shadowElevation = 8.dp,
                    border = BorderStroke(1.dp, OceanCardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "${uiState.actionGraph.actions.size} action(s) ready",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = OceanPrimary
                            )
                            Text(
                                text = "Engine: ${uiState.lastEngine}",
                                style = MaterialTheme.typography.bodySmall,
                                color = EchoTextSecondary
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val hasPendingOrScheduled = uiState.actionGraph?.actions?.any { a ->
                                val st = uiState.executionStates[a.id] ?: a.executionState
                                st.isPendingOrScheduled
                            } == true

                            if (hasPendingOrScheduled && !uiState.isExecuting) {
                                OutlinedButton(
                                    onClick = onCancelAll,
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = EchoError),
                                    border = BorderStroke(1.dp, EchoError.copy(alpha = 0.4f)),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                                ) {
                                    Text("Cancel All", fontWeight = FontWeight.SemiBold, color = EchoError)
                                }
                            }

                            Button(
                                onClick = {
                                    if (uiState.workflowComplete) {
                                        val prompt = uiState.actionGraph?.originalInput ?: uiState.input
                                        if (prompt.isNotBlank()) onProcessInput(prompt)
                                    } else {
                                        onExecuteAll()
                                    }
                                },
                                enabled = !uiState.isExecuting && !uiState.isProcessing,
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = OceanPrimary),
                                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
                            ) {
                                if (uiState.isExecuting) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        color = Color.White,
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Acting...", color = Color.White)
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (uiState.workflowComplete) "Run Again" else "Execute All",
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // Header: Title, Ambient Subtitle, Dynamic Greeting & AI Status Pill
            item {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column {
                        Text(
                            text = "EchoFlow",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = OceanPrimary
                        )
                        Text(
                            text = "Ambient Action Engine",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = OceanSecondary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = greeting,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = EchoTextPrimary
                        )
                    }

                    AiStatusPill(
                        modelState = modelState,
                        lastEngine = uiState.lastEngine
                    )
                }
            }

            // Central Voice Interaction Hero Area
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = OceanSurface),
                    border = BorderStroke(1.dp, OceanCardBorder),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // Ambient Breathing Orb
                        val isListening = uiState.voiceState == VoiceState.LISTENING
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(108.dp)
                                .scale(if (isListening) pulseScale else 1.0f)
                                .clip(CircleShape)
                                .background(
                                    Brush.radialGradient(
                                        colors = listOf(
                                            OceanSecondary.copy(alpha = if (isListening) 0.35f else 0.15f),
                                            OceanTertiary.copy(alpha = if (isListening) 0.25f else 0.08f),
                                            Color.Transparent
                                        )
                                    )
                                )
                                .clickable { onVoiceCapture() }
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isListening) OceanSecondary else OceanPrimary
                                    )
                            ) {
                                Icon(
                                    imageVector = if (isListening) Icons.Default.Mic else Icons.Default.MicNone,
                                    contentDescription = "Tap to speak",
                                    tint = Color.White,
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "What should I do?",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = OceanPrimary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (isListening) "Listening to you..." else "Tap or speak",
                                style = MaterialTheme.typography.bodyMedium,
                                color = EchoTextSecondary
                            )
                        }

                        // Text input toggle & input field
                        AnimatedVisibility(visible = showTextInput || inputText.isNotBlank()) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedTextField(
                                    value = inputText,
                                    onValueChange = {
                                        inputText = it
                                        onInputChange(it)
                                    },
                                    placeholder = {
                                        Text(
                                            "e.g. 'Message Rahul after 1 minute that I am coming working'",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = EchoTextTertiary
                                        )
                                    },
                                    minLines = 2,
                                    maxLines = 4,
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = OceanPrimary,
                                        unfocusedBorderColor = OceanCardBorder,
                                        focusedContainerColor = OceanBackground,
                                        unfocusedContainerColor = OceanBackground
                                    )
                                )

                                Button(
                                    onClick = { onProcessInput(inputText) },
                                    enabled = inputText.isNotBlank() && !uiState.isProcessing,
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = OceanPrimary),
                                    modifier = Modifier.align(Alignment.End)
                                ) {
                                    if (uiState.isProcessing) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            color = Color.White,
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Understanding...", color = Color.White)
                                    } else {
                                        Text("Understand & Delegate", fontWeight = FontWeight.SemiBold, color = Color.White)
                                    }
                                }
                            }
                        }

                        if (!showTextInput && inputText.isBlank()) {
                            TextButton(
                                onClick = { showTextInput = true },
                                colors = ButtonDefaults.textButtonColors(contentColor = OceanSecondary)
                            ) {
                                Icon(Icons.Default.Keyboard, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Or type a thought", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }

            // Quick Demo Scenarios
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "EXPLORE FLOWS",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = EchoTextTertiary
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SuggestionChip(
                            onClick = onRunFlagshipDemo,
                            label = { Text("✦ Flagship 4-Action Flow") },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = OceanTertiary.copy(alpha = 0.25f),
                                labelColor = OceanPrimary
                            ),
                            border = BorderStroke(1.dp, OceanTertiary.copy(alpha = 0.5f))
                        )
                        SuggestionChip(
                            onClick = {
                                onLoadDemo(0)
                                inputText = DemoScenarios.scenarios[0].input
                                showTextInput = true
                            },
                            label = { Text("Multi-Action Prompt") },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = OceanSurface,
                                labelColor = EchoTextPrimary
                            ),
                            border = BorderStroke(1.dp, OceanCardBorder)
                        )
                        SuggestionChip(
                            onClick = {
                                onLoadDemo(1)
                                inputText = DemoScenarios.scenarios[1].input
                                showTextInput = true
                            },
                            label = { Text("Scheduled Message") },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = OceanSurface,
                                labelColor = EchoTextPrimary
                            ),
                            border = BorderStroke(1.dp, OceanCardBorder)
                        )
                    }
                }
            }

            // Processing Indicator
            if (uiState.isProcessing && uiState.currentPhase != null) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = OceanSecondary.copy(alpha = 0.12f)),
                        border = BorderStroke(1.dp, OceanSecondary.copy(alpha = 0.3f))
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = OceanSecondary)
                            Text(
                                text = uiState.currentPhase,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = OceanPrimary
                            )
                        }
                    }
                }
            }

            // Action Graph Sections: NOW, NEXT, WAITING, COMPLETED
            if (uiState.actionGraph != null) {
                val allActions = uiState.actionGraph.actions

                val waitingActions = allActions.filter { a ->
                    val st = uiState.executionStates[a.id] ?: a.executionState
                    st == ExecutionState.NEEDS_CONFIRMATION || st == ExecutionState.WAITING_FOR_USER
                }
                val immediateActions = allActions.filter { a ->
                    val st = uiState.executionStates[a.id] ?: a.executionState
                    !waitingActions.contains(a) && !st.isTerminal && a.executionMode == ExecutionMode.IMMEDIATE
                }
                val scheduledActions = allActions.filter { a ->
                    val st = uiState.executionStates[a.id] ?: a.executionState
                    !waitingActions.contains(a) && !st.isTerminal && a.executionMode != ExecutionMode.IMMEDIATE
                }
                val completedActions = allActions.filter { a ->
                    val st = uiState.executionStates[a.id] ?: a.executionState
                    st.isTerminal
                }

                // WAITING SECTION
                if (waitingActions.isNotEmpty()) {
                    item {
                        Text(
                            text = "WAITING — NEEDS ATTENTION",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = EchoWarning,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    items(waitingActions, key = { it.id }) { action ->
                        ActionCard(
                            action = action,
                            executionState = uiState.executionStates[action.id] ?: action.executionState,
                            executionMessage = uiState.executionMessages[action.id],
                            calendarEventId = uiState.calendarEventIds[action.id] ?: action.calendarEventId,
                            onConfirm = { onConfirmAction(action.id) },
                            onEdit = { onEditAction(action) },
                            onDelete = { onDeleteAction(action.id) },
                            onRescheduleTomorrow = { onRescheduleTomorrow(action.id) },
                            onOpenCalendar = onOpenCalendar,
                            onCancelAction = { onCancelAction(action.id) }
                        )
                    }
                }

                // NOW SECTION
                if (immediateActions.isNotEmpty()) {
                    item {
                        Text(
                            text = "NOW — READY TO RUN",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = OceanPrimary,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    items(immediateActions, key = { it.id }) { action ->
                        ActionCard(
                            action = action,
                            executionState = uiState.executionStates[action.id] ?: action.executionState,
                            executionMessage = uiState.executionMessages[action.id],
                            calendarEventId = uiState.calendarEventIds[action.id] ?: action.calendarEventId,
                            onConfirm = { onConfirmAction(action.id) },
                            onEdit = { onEditAction(action) },
                            onDelete = { onDeleteAction(action.id) },
                            onRescheduleTomorrow = { onRescheduleTomorrow(action.id) },
                            onOpenCalendar = onOpenCalendar,
                            onCancelAction = { onCancelAction(action.id) }
                        )
                    }
                }

                // NEXT / SCHEDULED SECTION
                if (scheduledActions.isNotEmpty()) {
                    item {
                        Text(
                            text = "NEXT — SCHEDULED ACTIONS",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = OceanSecondary,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    items(scheduledActions, key = { it.id }) { action ->
                        ActionCard(
                            action = action,
                            executionState = uiState.executionStates[action.id] ?: action.executionState,
                            executionMessage = uiState.executionMessages[action.id],
                            calendarEventId = uiState.calendarEventIds[action.id] ?: action.calendarEventId,
                            onConfirm = { onConfirmAction(action.id) },
                            onEdit = { onEditAction(action) },
                            onDelete = { onDeleteAction(action.id) },
                            onRescheduleTomorrow = { onRescheduleTomorrow(action.id) },
                            onOpenCalendar = onOpenCalendar,
                            onCancelAction = { onCancelAction(action.id) }
                        )
                    }
                }

                // COMPLETED SECTION
                if (completedActions.isNotEmpty()) {
                    item {
                        Text(
                            text = "COMPLETED — RECENT OUTCOMES",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = EchoSuccess,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    items(completedActions, key = { it.id }) { action ->
                        ActionCard(
                            action = action,
                            executionState = uiState.executionStates[action.id] ?: action.executionState,
                            executionMessage = uiState.executionMessages[action.id],
                            calendarEventId = uiState.calendarEventIds[action.id] ?: action.calendarEventId,
                            onConfirm = { onConfirmAction(action.id) },
                            onEdit = { onEditAction(action) },
                            onDelete = { onDeleteAction(action.id) },
                            onRescheduleTomorrow = { onRescheduleTomorrow(action.id) },
                            onOpenCalendar = onOpenCalendar,
                            onCancelAction = { onCancelAction(action.id) }
                        )
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(60.dp))
                }
            }
        }
    }
}

@Composable
fun AiStatusPill(
    modelState: ModelState,
    lastEngine: String
) {
    val dotColor: Color
    val label: String

    if (lastEngine.contains("Qwen", ignoreCase = true) && modelState == ModelState.LOADED) {
        dotColor = EchoSuccess
        label = "Qwen Local"
    } else if (modelState == ModelState.LOADING) {
        dotColor = OceanSecondary
        label = "Loading Qwen..."
    } else if (modelState == ModelState.READY || modelState == ModelState.LOADED) {
        dotColor = EchoSuccess
        label = "Qwen Ready"
    } else {
        dotColor = EchoWarning
        label = "Fallback Parser"
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = dotColor.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, dotColor.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
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
                fontWeight = FontWeight.Bold,
                color = dotColor
            )
        }
    }
}
