package com.echoflow.app.domain.model

import java.time.LocalDateTime
import java.util.UUID

/**
 * Represents a single structured action decomposed from natural language.
 * This is the core data unit flowing through the entire EchoFlow pipeline:
 * LLM/Fallback → ActionParser → ActionGraph → ConfidenceEngine → EchoPalette → Executor
 */
data class ParsedAction(
    val id: String = UUID.randomUUID().toString(),
    val type: ActionType,
    val title: String,
    val description: String? = null,
    val recipient: String? = null,
    val recipientPhone: String? = null,
    val executionMode: ExecutionMode = ExecutionMode.IMMEDIATE,
    val timeExpression: String? = null,
    val requestedTime: String? = null,
    val dateTime: LocalDateTime? = null,
    val resolvedEpochMillis: Long? = null,
    val isPastTime: Boolean = false,
    val durationMinutes: Int? = null,
    val message: String? = null,
    val confidence: Float = 0.9f,
    val requiresConfirmation: Boolean = false,
    val dependencies: List<String> = emptyList(),
    val executionState: ExecutionState = ExecutionState.DETECTED,
    val ambiguityReason: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val executionStartedAt: Long? = null,
    val executionFinishedAt: Long? = null,
    val calendarAccount: String? = null,
    val calendarEventId: Long? = null,
    val executionMessage: String? = null
) {
    val actionType: ActionType get() = type
}
