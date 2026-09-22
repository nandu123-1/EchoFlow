package com.echoflow.app.confidence

import com.echoflow.app.domain.model.*

/**
 * Evaluates parsed actions and classifies them by execution readiness.
 *
 * Thresholds (prototype UX values, not scientifically validated):
 *   ≥ 0.90  → READY (auto-executable)
 *   0.70–0.89 → READY but flagged for review
 *   < 0.70  → NEEDS_CONFIRMATION
 *
 * Also performs ambiguity detection:
 *   - Missing recipient for MESSAGE
 *   - Missing time for CALENDAR
 *   - Vague descriptions
 */
object ConfidenceEngine {

    private const val HIGH_CONFIDENCE_THRESHOLD = 0.90f
    private const val MEDIUM_CONFIDENCE_THRESHOLD = 0.70f

    /**
     * Process an ActionGraph and update execution states based on confidence analysis.
     */
    fun evaluate(graph: ActionGraph): ActionGraph {
        val evaluatedActions = graph.actions.map { action ->
            evaluateAction(action)
        }

        return graph.copy(
            actions = evaluatedActions,
            overallConfidence = evaluatedActions.map { it.confidence }.average().toFloat()
        )
    }

    /**
     * Evaluate a single action and set its execution state.
     */
    private fun evaluateAction(action: ParsedAction): ParsedAction {
        // Check for structural ambiguities first
        val ambiguities = detectAmbiguities(action)

        val adjustedConfidence = if (ambiguities.isNotEmpty()) {
            // Reduce confidence for ambiguous actions
            (action.confidence * 0.8f).coerceIn(0f, 1f)
        } else {
            action.confidence
        }

        val executionState = when {
            action.requiresConfirmation -> ExecutionState.NEEDS_CONFIRMATION
            ambiguities.isNotEmpty() -> ExecutionState.NEEDS_CONFIRMATION
            adjustedConfidence >= HIGH_CONFIDENCE_THRESHOLD -> ExecutionState.READY
            adjustedConfidence >= MEDIUM_CONFIDENCE_THRESHOLD -> ExecutionState.READY
            else -> ExecutionState.NEEDS_CONFIRMATION
        }

        val requiresConfirm = executionState == ExecutionState.NEEDS_CONFIRMATION

        // Messages should generally be reviewed
        val finalState = if (action.type == ActionType.MESSAGE && executionState == ExecutionState.READY) {
            ExecutionState.READY // Still READY but user sees it in palette
        } else {
            executionState
        }

        return action.copy(
            confidence = adjustedConfidence,
            executionState = finalState,
            requiresConfirmation = requiresConfirm,
            ambiguityReason = ambiguities.firstOrNull() ?: action.ambiguityReason
        )
    }

    /**
     * Detect structural ambiguities in an action.
     */
    private fun detectAmbiguities(action: ParsedAction): List<String> {
        val issues = mutableListOf<String>()

        when (action.type) {
            ActionType.CALENDAR -> {
                if (action.dateTime == null) {
                    issues.add("Date and time not specified")
                }
                if (action.title.length < 3) {
                    issues.add("Event description is too vague")
                }
            }
            ActionType.REMINDER -> {
                if (action.title.length < 3) {
                    issues.add("Reminder content is too vague")
                }
            }
            ActionType.MESSAGE -> {
                if (action.recipient == null) {
                    issues.add("Recipient not specified")
                }
                if (action.message.isNullOrBlank()) {
                    issues.add("Message content is empty")
                }
            }
            ActionType.CALL -> {
                if (action.recipient == null && action.recipientPhone == null) {
                    issues.add("Recipient to call not specified")
                }
            }
            ActionType.NOTE -> {
                if (action.title.length < 3 && action.description.isNullOrBlank()) {
                    issues.add("Note content is too short")
                }
            }
            ActionType.UNKNOWN -> {
                issues.add("Could not determine action type")
            }
        }

        return issues
    }

    /**
     * Get a human-readable confidence label for UI display.
     */
    fun getConfidenceLabel(confidence: Float): String = when {
        confidence >= HIGH_CONFIDENCE_THRESHOLD -> "High confidence"
        confidence >= MEDIUM_CONFIDENCE_THRESHOLD -> "Review suggested"
        else -> "Needs confirmation"
    }

    /**
     * Get confidence tier for color-coding in UI.
     */
    fun getConfidenceTier(confidence: Float): ConfidenceTier = when {
        confidence >= HIGH_CONFIDENCE_THRESHOLD -> ConfidenceTier.HIGH
        confidence >= MEDIUM_CONFIDENCE_THRESHOLD -> ConfidenceTier.MEDIUM
        else -> ConfidenceTier.LOW
    }
}

enum class ConfidenceTier {
    HIGH,   // Green — auto-executable
    MEDIUM, // Amber — review suggested
    LOW     // Red — confirmation required
}
