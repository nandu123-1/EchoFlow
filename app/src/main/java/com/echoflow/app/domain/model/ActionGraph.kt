package com.echoflow.app.domain.model

/**
 * Represents the complete decomposed workflow from a single natural language input.
 * The Action Graph is EchoFlow's core innovation — it turns one messy thought
 * into a structured graph of executable actions with dependency relationships.
 */
data class ActionGraph(
    val originalInput: String,
    val actions: List<ParsedAction>,
    val overallConfidence: Float,
    val processingTimeMs: Long = 0L,
    val sourceEngine: String = "unknown",
    val requestCreatedAt: Long = System.currentTimeMillis(),
    val deviceTimezone: String = java.time.ZoneId.systemDefault().id
) {
    /** Actions that have no unmet dependencies and can execute */
    val readyActions: List<ParsedAction>
        get() = actions.filter { action ->
            action.dependencies.isEmpty() ||
            action.dependencies.all { depId ->
                actions.find { it.id == depId }?.executionState == ExecutionState.SUCCESS
            }
        }

    /** Actions flagged for user review */
    val actionsNeedingConfirmation: List<ParsedAction>
        get() = actions.filter { it.requiresConfirmation }

    /** Count of each action type for summary display */
    val actionTypeCounts: Map<ActionType, Int>
        get() = actions.groupBy { it.type }.mapValues { it.value.size }
}
