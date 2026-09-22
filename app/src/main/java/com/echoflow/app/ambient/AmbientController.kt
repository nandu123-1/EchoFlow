package com.echoflow.app.ambient

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.echoflow.app.EchoFlowApplication
import com.echoflow.app.MainActivity
import com.echoflow.app.assistant.EchoFlowAccessibilityService
import com.echoflow.app.assistant.GestureTriggerController
import com.echoflow.app.assistant.SpokenSummaryGenerator
import com.echoflow.app.confidence.ConfidenceEngine
import com.echoflow.app.data.db.ActionHistoryEntity
import com.echoflow.app.data.db.WorkflowHistoryEntity
import com.echoflow.app.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.lang.ref.WeakReference
import java.time.Instant
import java.time.ZoneId

/**
 * Central coordinator singleton for the Ambient Overlay engine.
 *
 * Coordinates:
 * - Direct hosting of TYPE_ACCESSIBILITY_OVERLAY via [EchoFlowAccessibilityService]
 * - Overlay lifecycle (Show / Hide / Toggle / Dismiss)
 * - Ambient speech recognition -> AI inference -> Review cards -> Execution -> Spoken summary
 * - 1-turn conversational confirmation ("Yes" / "Cancel")
 * - Shared domain layer integration (Room history, ActionExecutor, ScheduledActionManager)
 */
object AmbientController {

    private const val TAG = "EchoFlow-Ambient"

    private val CONFIRMATION_AFFIRMATIVE = setOf(
        "yes", "yeah", "yep", "sure", "do it", "confirm", "go ahead",
        "execute", "schedule it", "save it", "send it", "ok", "okay", "please"
    )

    private val CONFIRMATION_NEGATIVE = setOf(
        "no", "nope", "cancel", "stop", "don't", "dont", "dismiss", "nevermind", "never mind"
    )

    private var accessibilityServiceRef: WeakReference<EchoFlowAccessibilityService>? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var activeJob: Job? = null

    private val _sessionState = MutableStateFlow(AmbientSessionState())
    val sessionState: StateFlow<AmbientSessionState> = _sessionState.asStateFlow()

    fun registerAccessibilityService(service: EchoFlowAccessibilityService) {
        accessibilityServiceRef = WeakReference(service)
        Log.i(TAG, "[CONTROLLER] EchoFlowAccessibilityService registered with AmbientController")
    }

    fun unregisterAccessibilityService(service: EchoFlowAccessibilityService) {
        if (accessibilityServiceRef?.get() == service) {
            accessibilityServiceRef = null
            Log.i(TAG, "[CONTROLLER] EchoFlowAccessibilityService unregistered")
        }
    }

    fun isServiceConnected(): Boolean = accessibilityServiceRef?.get() != null

    fun isAmbientVisible(): Boolean = _sessionState.value.phase != AmbientPhase.HIDDEN

    /**
     * Entry point for all shortcut triggers (Accessibility Button, QS Tile, Assist Intent, 4-Finger gesture).
     */
    fun handleShortcutTrigger(context: Context, source: String) {
        Log.i(TAG, "[CONTROLLER] handleShortcutTrigger: source='$source', serviceConnected=${isServiceConnected()}")

        val service = accessibilityServiceRef?.get()
        if (service != null) {
            if (isAmbientVisible()) {
                Log.d(TAG, "[CONTROLLER] Overlay already visible -> toggling/dismissing")
                hideAmbient()
            } else {
                showAmbient(context, source)
            }
        } else {
            // Fallback: AccessibilityService not enabled or not connected.
            Log.w(TAG, "[CONTROLLER] AccessibilityService not connected. Launching MainActivity as fallback.")
            try {
                val intent = Intent(context, MainActivity::class.java).apply {
                    action = Intent.ACTION_MAIN
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(GestureTriggerController.EXTRA_TRIGGER_SOURCE, source)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "[CONTROLLER] Failed to launch MainActivity fallback", e)
            }
        }
    }

    /**
     * Shows the ambient overlay window and begins voice capture.
     */
    fun showAmbient(context: Context, source: String = "shortcut") {
        val service = accessibilityServiceRef?.get()
        if (service == null) {
            Log.w(TAG, "[CONTROLLER] Cannot show overlay: service is null")
            return
        }

        activeJob?.cancel()
        _sessionState.value = AmbientSessionState(
            phase = AmbientPhase.READY,
            triggerSource = source,
            statusText = "EchoFlow is ready"
        )

        service.showAmbientOverlay()

        // Check RECORD_AUDIO permission
        val hasMic = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasMic) {
            _sessionState.update {
                it.copy(
                    phase = AmbientPhase.ERROR,
                    statusText = "Microphone access needed. Open EchoFlow to grant permission.",
                    error = "PERMISSION_DENIED"
                )
            }
            return
        }

        startListening()
    }

    /**
     * Starts listening for user's voice prompt.
     */
    fun startListening() {
        val app = EchoFlowApplication.instance
        if (!app.speechRecognizer.isAvailable()) {
            _sessionState.update {
                it.copy(
                    phase = AmbientPhase.ERROR,
                    statusText = "Speech recognition is unavailable on this device.",
                    error = "SPEECH_UNAVAILABLE"
                )
            }
            return
        }

        _sessionState.update {
            it.copy(
                phase = AmbientPhase.LISTENING,
                transcript = "",
                spokenResponse = null,
                actionGraph = null,
                executionStates = emptyMap(),
                executionMessages = emptyMap(),
                error = null,
                statusText = "I'm listening..."
            )
        }

        activeJob?.cancel()
        activeJob = scope.launch {
            Log.i(TAG, "[CONTROLLER] Ambient speech listening started")
            val result = app.speechRecognizer.transcribe()
            result.fold(
                onSuccess = { recognizedText ->
                    val trimmed = recognizedText.trim()
                    if (trimmed.isBlank()) {
                        _sessionState.update {
                            it.copy(
                                phase = AmbientPhase.ERROR,
                                statusText = "I didn't hear anything.",
                                error = "NO_SPEECH"
                            )
                        }
                    } else {
                        Log.i(TAG, "[CONTROLLER] Transcript received: '$trimmed'")
                        processIntention(trimmed)
                    }
                },
                onFailure = { error ->
                    Log.w(TAG, "[CONTROLLER] Speech recognition error: ${error.message}")
                    _sessionState.update {
                        it.copy(
                            phase = AmbientPhase.ERROR,
                            statusText = "I didn't catch that. Tap the mic to try again.",
                            error = error.message
                        )
                    }
                }
            )
        }
    }

    /**
     * Runs AI parsing on the transcript and prepares the ActionGraph.
     */
    fun processIntention(text: String) {
        val app = EchoFlowApplication.instance
        _sessionState.update {
            it.copy(
                phase = AmbientPhase.ANALYZING,
                transcript = text,
                statusText = "Understanding intention..."
            )
        }

        activeJob?.cancel()
        activeJob = scope.launch {
            val requestCreatedAt = Instant.now()
            val timeZone = ZoneId.systemDefault()
            val userContext = UserContext(requestCreatedAt = requestCreatedAt, timeZone = timeZone)

            val parseResult = app.aiCoordinator.parseIntent(text, userContext)
            parseResult.fold(
                onSuccess = { graph ->
                    val defaultAccount = app.calendarPreferences.getSelectedAccountName()
                    val enrichedActions = graph.actions.map { action ->
                        if (action.type == ActionType.CALENDAR && action.calendarAccount == null) {
                            action.copy(calendarAccount = defaultAccount)
                        } else action
                    }
                    val enrichedGraph = graph.copy(actions = enrichedActions)
                    val evaluated = ConfidenceEngine.evaluate(enrichedGraph)
                    val execStates = evaluated.actions.associate { it.id to it.executionState }

                    _sessionState.update {
                        it.copy(
                            phase = AmbientPhase.READY_FOR_REVIEW,
                            actionGraph = evaluated,
                            executionStates = execStates,
                            statusText = "Review before I execute"
                        )
                    }

                    val reviewPrompt = SpokenSummaryGenerator.generateReviewPrompt(evaluated)
                    _sessionState.update { it.copy(spokenResponse = reviewPrompt) }

                    // Speak review prompt and open 1-turn follow-up listening
                    app.speechOutputManager.speak(reviewPrompt) {
                        scope.launch {
                            if (_sessionState.value.phase == AmbientPhase.READY_FOR_REVIEW) {
                                startFollowupListening()
                            }
                        }
                    }
                },
                onFailure = { error ->
                    Log.e(TAG, "[CONTROLLER] Intention analysis failed", error)
                    _sessionState.update {
                        it.copy(
                            phase = AmbientPhase.ERROR,
                            statusText = error.message ?: "Could not understand request",
                            error = error.message
                        )
                    }
                }
            )
        }
    }

    private suspend fun startFollowupListening() {
        if (_sessionState.value.phase != AmbientPhase.READY_FOR_REVIEW) return
        val app = EchoFlowApplication.instance

        _sessionState.update {
            it.copy(
                phase = AmbientPhase.WAITING_FOLLOWUP,
                statusText = "Listening: say 'Yes' to confirm or 'Cancel'"
            )
        }
        Log.i(TAG, "[CONTROLLER] Follow-up confirmation listening started")

        val result = app.speechRecognizer.transcribe()
        result.fold(
            onSuccess = { text ->
                val lower = text.trim().lowercase()
                Log.i(TAG, "[CONTROLLER] Follow-up voice: '$lower'")

                val isAffirmative = CONFIRMATION_AFFIRMATIVE.any { lower.contains(it) }
                val isNegative = CONFIRMATION_NEGATIVE.any { lower.contains(it) }

                if (isAffirmative) {
                    executeAll()
                } else if (isNegative) {
                    app.speechOutputManager.speak("Cancelled.") {
                        hideAmbient()
                    }
                } else {
                    _sessionState.update { it.copy(phase = AmbientPhase.READY_FOR_REVIEW, statusText = "Review actions") }
                }
            },
            onFailure = {
                _sessionState.update { it.copy(phase = AmbientPhase.READY_FOR_REVIEW, statusText = "Review actions") }
            }
        )
    }

    /**
     * Executes all actions in the current ActionGraph and speaks summary.
     */
    fun executeAll() {
        val graph = _sessionState.value.actionGraph ?: return
        val app = EchoFlowApplication.instance

        _sessionState.update {
            it.copy(
                phase = AmbientPhase.EXECUTING,
                statusText = "Executing actions..."
            )
        }

        activeJob?.cancel()
        activeJob = scope.launch {
            val executedActions = mutableListOf<ParsedAction>()
            val currentStates = _sessionState.value.executionStates.toMutableMap()
            val currentMsgs = _sessionState.value.executionMessages.toMutableMap()

            for (action in graph.actions) {
                currentStates[action.id] = ExecutionState.EXECUTING
                _sessionState.update { it.copy(executionStates = currentStates.toMap()) }

                val startMs = System.currentTimeMillis()
                val result = app.actionExecutor.execute(action)
                val finishMs = System.currentTimeMillis()

                currentStates[action.id] = result.state
                currentMsgs[action.id] = result.message

                val eventId = if (action.type == ActionType.CALENDAR) result.extraData?.toLongOrNull() else null
                val executed = action.copy(
                    executionState = result.state,
                    executionStartedAt = startMs,
                    executionFinishedAt = finishMs,
                    calendarEventId = eventId ?: action.calendarEventId,
                    executionMessage = result.message
                )
                executedActions.add(executed)

                _sessionState.update {
                    it.copy(
                        executionStates = currentStates.toMap(),
                        executionMessages = currentMsgs.toMap()
                    )
                }
            }

            // Persist to Room database
            try {
                val hasFailures = executedActions.any { it.executionState == ExecutionState.FAILED }
                val workflowId = java.util.UUID.randomUUID().toString()
                val now = System.currentTimeMillis()
                val workflowEntity = WorkflowHistoryEntity(
                    id = workflowId,
                    originalInput = graph.originalInput,
                    requestCreatedAt = graph.requestCreatedAt,
                    deviceTimezone = graph.deviceTimezone,
                    createdAt = now,
                    aiEngine = graph.sourceEngine,
                    overallConfidence = graph.overallConfidence,
                    executionStatus = if (hasFailures) "PARTIAL_SUCCESS" else "ALL_SUCCESS"
                )
                val actionEntities = executedActions.map { action ->
                    ActionHistoryEntity(
                        id = action.id,
                        workflowId = workflowId,
                        actionType = action.type.name,
                        title = action.title,
                        description = action.description,
                        recipient = action.recipient,
                        recipientPhone = action.recipientPhone,
                        executionMode = action.executionMode.name,
                        timeExpression = action.timeExpression,
                        requestedTime = action.requestedTime,
                        resolvedTime = action.dateTime?.toString(),
                        resolvedEpochMillis = action.resolvedEpochMillis,
                        dateTime = action.dateTime?.toString(),
                        durationMinutes = action.durationMinutes,
                        message = action.message,
                        confidence = action.confidence,
                        executionState = action.executionState.name,
                        executionMessage = action.executionMessage,
                        executionStartedAt = action.executionStartedAt,
                        executionFinishedAt = action.executionFinishedAt,
                        createdAt = now
                    )
                }
                app.database.historyDao().insertWorkflowWithActions(workflowEntity, actionEntities)
                Log.i(TAG, "[CONTROLLER] Workflow persisted to history: $workflowId")
            } catch (e: Exception) {
                Log.e(TAG, "[CONTROLLER] Failed to persist workflow to Room", e)
            }

            // Speak execution summary
            val summary = SpokenSummaryGenerator.generateExecutionSummary(executedActions)
            Log.i(TAG, "[TTS] Spoken execution summary: '$summary'")
            _sessionState.update {
                it.copy(
                    phase = AmbientPhase.RESPONDING,
                    statusText = summary,
                    spokenResponse = summary
                )
            }

            app.speechOutputManager.speak(summary) {
                scope.launch {
                    delay(1500L)
                    hideAmbient()
                }
            }
        }
    }

    fun confirmAction(actionId: String) {
        _sessionState.update { state ->
            val updated = state.actionGraph?.let { graph ->
                graph.copy(actions = graph.actions.map { if (it.id == actionId) it.copy(executionState = ExecutionState.READY, requiresConfirmation = false) else it })
            }
            val newStates = state.executionStates.toMutableMap().apply { put(actionId, ExecutionState.READY) }
            state.copy(actionGraph = updated, executionStates = newStates)
        }
    }

    fun deleteAction(actionId: String) {
        _sessionState.update { state ->
            val updated = state.actionGraph?.let { graph ->
                graph.copy(actions = graph.actions.filterNot { it.id == actionId })
            }
            val newStates = state.executionStates.toMutableMap().apply { remove(actionId) }
            state.copy(actionGraph = updated, executionStates = newStates)
        }
    }

    fun cancelSingleAction(actionId: String) {
        val app = EchoFlowApplication.instance
        scope.launch {
            app.scheduledActionManager.cancelAction(actionId)
            _sessionState.update { state ->
                val newStates = state.executionStates.toMutableMap().apply { put(actionId, ExecutionState.CANCELLED) }
                val newMsgs = state.executionMessages.toMutableMap().apply { put(actionId, "Cancelled by user") }
                state.copy(executionStates = newStates, executionMessages = newMsgs)
            }
        }
    }

    fun cancelAll() {
        val app = EchoFlowApplication.instance
        scope.launch {
            app.scheduledActionManager.cancelAll()
            hideAmbient()
        }
    }

    /**
     * Hides the ambient overlay and cancels active sessions.
     */
    fun hideAmbient() {
        Log.i(TAG, "[CONTROLLER] Hiding ambient overlay")
        activeJob?.cancel()
        if (EchoFlowApplication.isInitialized) {
            EchoFlowApplication.instance.speechRecognizer.cancel()
            EchoFlowApplication.instance.speechOutputManager.stop()
        }
        _sessionState.value = AmbientSessionState(phase = AmbientPhase.HIDDEN)
        accessibilityServiceRef?.get()?.hideAmbientOverlay()
        GestureTriggerController.resetState()
    }
}
