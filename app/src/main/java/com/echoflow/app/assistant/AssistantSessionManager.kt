package com.echoflow.app.assistant

import android.content.Context
import android.util.Log
import com.echoflow.app.capture.SystemSpeechRecognizer
import com.echoflow.app.domain.model.ActionGraph
import com.echoflow.app.domain.model.ParsedAction
import com.echoflow.app.ui.viewmodel.EchoFlowViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Lifecycle states of the EchoFlow ambient voice assistant session.
 */
enum class AssistantState {
    IDLE,
    OPENING,
    LISTENING,
    TRANSCRIBING,
    ANALYZING,
    PREPARING,
    READY_FOR_REVIEW,
    WAITING_FOLLOWUP,
    EXECUTING,
    RESPONDING,
    CLOSING,
    ERROR
}

/**
 * Coordinates ambient assistant voice interactions:
 * - Speech recognition lifecycle
 * - Model inference coordination via [EchoFlowViewModel]
 * - Deterministic review prompts & execution summaries
 * - 1-turn conversational voice confirmation ("Yes" / "Cancel")
 * - Resource cleanup and error resilience
 */
class AssistantSessionManager(
    private val context: Context,
    private val speechRecognizer: SystemSpeechRecognizer,
    private val speechOutputManager: SpeechOutputManager,
    private val viewModel: EchoFlowViewModel
) {

    companion object {
        private const val TAG = "EchoFlow-Assistant"

        private val CONFIRMATION_AFFIRMATIVE = setOf(
            "yes", "yeah", "yep", "sure", "do it", "confirm", "go ahead",
            "execute", "schedule it", "save it", "send it", "ok", "okay", "please"
        )

        private val CONFIRMATION_NEGATIVE = setOf(
            "no", "nope", "cancel", "stop", "don't", "dont", "dismiss", "nevermind", "never mind"
        )
    }

    private val sessionScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var currentJob: Job? = null

    private val _state = MutableStateFlow(AssistantState.IDLE)
    val state: StateFlow<AssistantState> = _state.asStateFlow()

    private val _transcript = MutableStateFlow("")
    val transcript: StateFlow<String> = _transcript.asStateFlow()

    private val _statusText = MutableStateFlow("I'm listening...")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    private val _spokenResponse = MutableStateFlow<String?>(null)
    val spokenResponse: StateFlow<String?> = _spokenResponse.asStateFlow()

    /**
     * Starts a new ambient assistant session (e.g. from 4-finger swipe gesture or mic button).
     */
    fun startSession(triggerSource: String = "gesture") {
        if (_state.value != AssistantState.IDLE) {
            Log.d(TAG, "[ASSISTANT] Session already active in state ${_state.value}; resetting")
            cancelSession()
        }

        Log.i(TAG, "[ASSISTANT] Starting ambient assistant session (trigger=$triggerSource)")
        _transcript.value = ""
        _spokenResponse.value = null
        _statusText.value = "I'm listening..."
        _state.value = AssistantState.LISTENING

        currentJob?.cancel()
        currentJob = sessionScope.launch {
            // Optional intro speech if desired: "I'm listening."
            // But we keep it fast and directly start listening
            listenForVoiceInput()
        }
    }

    private suspend fun listenForVoiceInput() {
        if (!speechRecognizer.isAvailable()) {
            _statusText.value = "Speech recognition is unavailable"
            _state.value = AssistantState.ERROR
            Log.e(TAG, "[SPEECH] Speech recognition unavailable on device")
            return
        }

        _state.value = AssistantState.LISTENING
        Log.i(TAG, "[SPEECH] Listening started")

        val result = speechRecognizer.transcribe()
        result.fold(
            onSuccess = { recognizedText ->
                val trimmed = recognizedText.trim()
                if (trimmed.isBlank()) {
                    _statusText.value = "I didn't hear anything."
                    _state.value = AssistantState.ERROR
                    Log.w(TAG, "[SPEECH] Final transcript was blank")
                    return@fold
                }

                _transcript.value = trimmed
                _state.value = AssistantState.ANALYZING
                _statusText.value = "Analyzing your request..."
                Log.i(TAG, "[SPEECH] Final transcript: '$trimmed'")

                // Pass into existing EchoFlowViewModel pipeline
                processWorkflowInput(trimmed)
            },
            onFailure = { error ->
                Log.w(TAG, "[SPEECH] Recognition failed: ${error.message}")
                _statusText.value = "I didn't catch that. Please try again."
                _state.value = AssistantState.ERROR
            }
        )
    }

    private fun processWorkflowInput(input: String) {
        viewModel.setInput(input)
        viewModel.processInput(input)

        // Observe ViewModel's action graph parsing
        currentJob?.cancel()
        currentJob = sessionScope.launch {
            // Wait for processing to complete in ViewModel
            viewModel.uiState.collect { uiState ->
                if (!uiState.isProcessing && uiState.actionGraph != null) {
                    val graph = uiState.actionGraph
                    onWorkflowParsed(graph)
                    cancel() // stop collecting once parsed
                } else if (!uiState.isProcessing && uiState.error != null) {
                    _statusText.value = uiState.error
                    _state.value = AssistantState.ERROR
                    cancel()
                }
            }
        }
    }

    private fun onWorkflowParsed(graph: ActionGraph) {
        Log.i(TAG, "[PARSER] ActionGraph created: ${graph.actions.size} action(s)")
        _state.value = AssistantState.READY_FOR_REVIEW
        _statusText.value = "Review before I execute"

        val reviewPrompt = SpokenSummaryGenerator.generateReviewPrompt(graph)
        _spokenResponse.value = reviewPrompt

        // Speak review prompt, then listen for 1-turn conversational confirmation
        speechOutputManager.speak(reviewPrompt) {
            sessionScope.launch {
                // Once review prompt finishes speaking, open 1-turn follow-up listening
                if (_state.value == AssistantState.READY_FOR_REVIEW) {
                    startFollowupListening()
                }
            }
        }
    }

    private suspend fun startFollowupListening() {
        if (_state.value != AssistantState.READY_FOR_REVIEW) return
        _state.value = AssistantState.WAITING_FOLLOWUP
        Log.i(TAG, "[ASSISTANT] Listening for conversational follow-up confirmation...")

        val result = speechRecognizer.transcribe()
        result.fold(
            onSuccess = { text ->
                val lower = text.trim().lowercase()
                Log.i(TAG, "[ASSISTANT] Follow-up voice response: '$lower'")

                val isAffirmative = CONFIRMATION_AFFIRMATIVE.any { lower.contains(it) }
                val isNegative = CONFIRMATION_NEGATIVE.any { lower.contains(it) }

                if (isAffirmative) {
                    Log.i(TAG, "[ASSISTANT] Voice confirmation matched: Executing actions")
                    executeActions()
                } else if (isNegative) {
                    Log.i(TAG, "[ASSISTANT] Voice cancellation matched: Cancelling session")
                    speechOutputManager.speak("Cancelled.") {
                        cancelSession()
                    }
                } else {
                    // Ambiguous or unrecognized speech: remain in review mode for manual touch
                    Log.d(TAG, "[ASSISTANT] Follow-up not an explicit command, remaining in review")
                    _state.value = AssistantState.READY_FOR_REVIEW
                }
            },
            onFailure = {
                // Timeout or no speech: seamlessly stay in READY_FOR_REVIEW for manual touch
                Log.d(TAG, "[ASSISTANT] Follow-up listening ended without speech")
                _state.value = AssistantState.READY_FOR_REVIEW
            }
        )
    }

    /**
     * Executes parsed actions and speaks the deterministic summary.
     */
    fun executeActions() {
        _state.value = AssistantState.EXECUTING
        _statusText.value = "Executing actions..."
        Log.i(TAG, "[ACTION] Executing actions...")

        viewModel.executeAll()

        currentJob?.cancel()
        currentJob = sessionScope.launch {
            viewModel.uiState.collect { uiState ->
                if (uiState.workflowComplete && !uiState.isExecuting) {
                    val actions = uiState.actionGraph?.actions ?: emptyList()
                    onExecutionCompleted(actions)
                    cancel()
                }
            }
        }
    }

    private fun onExecutionCompleted(actions: List<ParsedAction>) {
        val summary = SpokenSummaryGenerator.generateExecutionSummary(actions)
        Log.i(TAG, "[TTS] Response: '$summary'")
        _statusText.value = summary
        _spokenResponse.value = summary
        _state.value = AssistantState.RESPONDING

        speechOutputManager.speak(summary) {
            sessionScope.launch {
                delay(1200L)
                if (_state.value == AssistantState.RESPONDING) {
                    _state.value = AssistantState.CLOSING
                    delay(400L)
                    _state.value = AssistantState.IDLE
                }
            }
        }
    }

    /**
     * Cancels the active assistant session and releases transient resources.
     */
    fun cancelSession() {
        Log.i(TAG, "[ASSISTANT] Session cancelled")
        currentJob?.cancel()
        speechRecognizer.cancel()
        speechOutputManager.stop()
        _state.value = AssistantState.IDLE
        _transcript.value = ""
        _spokenResponse.value = null
        GestureTriggerController.resetState()
    }

    fun release() {
        cancelSession()
        sessionScope.cancel()
    }
}
