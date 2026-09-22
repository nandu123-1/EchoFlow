package com.echoflow.app.execution

import android.content.Context
import android.util.Log
import com.echoflow.app.domain.model.ActionType
import com.echoflow.app.domain.model.ExecutionState
import com.echoflow.app.domain.model.ParsedAction

/**
 * Dispatches ParsedActions to the correct type-specific executor.
 *
 * Pipeline position:
 *   ActionGraph → ConfidenceEngine → User Confirmation → ActionExecutor → Android API
 *
 * The LLM NEVER reaches this layer directly.
 */
class ActionExecutor(
    private val context: Context,
    val calendarPreferences: com.echoflow.app.data.prefs.CalendarPreferences = com.echoflow.app.data.prefs.CalendarPreferences(context)
) {

    companion object {
        private const val TAG = "EchoFlow/Executor"
    }

    val calendarExecutor = CalendarExecutor(context, calendarPreferences)
    private val reminderExecutor = ReminderExecutor(context)
    private val messageExecutor = MessageExecutor(context)
    private val callExecutor = CallExecutor(context)
    private val noteExecutor = NoteExecutor(context)
    private val scheduledActionManager = com.echoflow.app.scheduling.ScheduledActionManager.getInstance(context)

    /**
     * Execute a single action. Returns the updated execution state.
     */
    suspend fun execute(action: ParsedAction): ExecutionResult {
        Log.d(TAG, "[EXECUTOR] Executing ${action.type}: ${action.title} (mode: ${action.executionMode})")

        return try {
            when (action.type) {
                ActionType.CALENDAR -> calendarExecutor.execute(action)
                ActionType.REMINDER -> reminderExecutor.execute(action)
                ActionType.CALL -> callExecutor.execute(action)
                ActionType.MESSAGE -> {
                    val hasFutureTime = (action.resolvedEpochMillis ?: 0L) > System.currentTimeMillis()
                    if (action.executionMode == com.echoflow.app.domain.model.ExecutionMode.SCHEDULED || hasFutureTime) {
                        val scheduleResult = scheduledActionManager.scheduleAction(action)
                        if (scheduleResult.success) {
                            ExecutionResult(
                                state = ExecutionState.SCHEDULED,
                                message = "Message scheduled for ${action.requestedTime ?: "requested time"}"
                            )
                        } else {
                            ExecutionResult(
                                state = ExecutionState.FAILED,
                                message = scheduleResult.message
                            )
                        }
                    } else {
                        messageExecutor.execute(action)
                    }
                }
                ActionType.NOTE -> noteExecutor.execute(action)
                ActionType.UNKNOWN -> ExecutionResult(
                    state = ExecutionState.FAILED,
                    message = "Cannot execute unknown action type"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "[EXECUTOR] Execution failed for ${action.type}", e)
            ExecutionResult(
                state = ExecutionState.FAILED,
                message = e.message ?: "Execution failed"
            )
        }
    }

    /**
     * Execute all ready actions in an ActionGraph.
     */
    suspend fun executeAll(actions: List<ParsedAction>): Map<String, ExecutionResult> {
        val results = mutableMapOf<String, ExecutionResult>()

        for (action in actions) {
            if (action.executionState == ExecutionState.READY ||
                action.executionState == ExecutionState.DETECTED) {
                results[action.id] = execute(action)
            } else {
                results[action.id] = ExecutionResult(
                    state = action.executionState,
                    message = "Skipped: ${action.executionState}"
                )
            }
        }

        return results
    }
}

/**
 * Result of executing a single action.
 */
data class ExecutionResult(
    val state: ExecutionState,
    val message: String,
    val extraData: String? = null
)
