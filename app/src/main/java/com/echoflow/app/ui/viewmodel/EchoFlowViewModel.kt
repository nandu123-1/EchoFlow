package com.echoflow.app.ui.viewmodel

import android.app.Application
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.echoflow.app.ai.AiCoordinator
import com.echoflow.app.ai.FallbackAiEngine
import com.echoflow.app.ai.LocalQwenAiEngine
import com.echoflow.app.ai.ModelManager
import com.echoflow.app.confidence.ConfidenceEngine
import com.echoflow.app.data.db.ActionHistoryEntity
import com.echoflow.app.data.db.EchoFlowDatabase
import com.echoflow.app.data.db.WorkflowHistoryEntity
import com.echoflow.app.data.db.WorkflowWithActions
import com.echoflow.app.domain.model.*
import com.echoflow.app.execution.ActionExecutor
import com.echoflow.app.execution.ExecutionResult
import com.echoflow.app.assistant.GestureTriggerController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

enum class AppTab {
    HOME, TIMELINE, HISTORY, SETTINGS
}

enum class VoiceState {
    IDLE, LISTENING, TRANSCRIBING, READY, ERROR
}

data class EchoFlowUiState(
    val selectedTab: AppTab = AppTab.HOME,
    val input: String = "",
    val voiceState: VoiceState = VoiceState.IDLE,
    val isProcessing: Boolean = false,
    val isExecuting: Boolean = false,
    val currentPhase: String? = null,
    val actionGraph: ActionGraph? = null,
    val executionStates: Map<String, ExecutionState> = emptyMap(),
    val executionMessages: Map<String, String> = emptyMap(),
    val calendarEventIds: Map<String, Long> = emptyMap(),
    val error: String? = null,
    val lastEngine: String = "Fallback Parser",
    val workflowComplete: Boolean = false,
    val editingAction: ParsedAction? = null,
    val statusToast: String? = null,
    val isImportingModel: Boolean = false,
    val modelImportProgress: String? = null
)

class EchoFlowViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "EchoFlow-VM"
    }

    private val db = EchoFlowDatabase.getInstance(application)
    private val historyDao = db.historyDao()

    // --- AI Layer ---
    val modelManager = ModelManager(application)
    private val localEngine = LocalQwenAiEngine(modelManager)
    private val fallbackEngine = FallbackAiEngine()
    val aiCoordinator = AiCoordinator(localEngine, fallbackEngine, modelManager)

    // --- Execution Layer ---
    val calendarPreferences = com.echoflow.app.data.prefs.CalendarPreferences(application)
    private val actionExecutor = ActionExecutor(application, calendarPreferences)
    val scheduledActionManager = com.echoflow.app.scheduling.ScheduledActionManager.getInstance(application)
    val pendingScheduledActions: StateFlow<List<ParsedAction>> = scheduledActionManager.activeActionsFlow

    // --- UI State ---
    private val _uiState = MutableStateFlow(EchoFlowUiState())
    val uiState: StateFlow<EchoFlowUiState> = _uiState.asStateFlow()

    // --- Model state ---
    val modelState: StateFlow<ModelState> = modelManager.modelState
    val preferQwen: StateFlow<Boolean> = aiCoordinator.preferQwen

    // --- Calendar State ---
    val selectedCalendarId: StateFlow<Long> = calendarPreferences.selectedCalendarId
    val selectedCalendarAccount: StateFlow<String?> = calendarPreferences.selectedAccountName
    private val _availableCalendars = MutableStateFlow<List<com.echoflow.app.execution.CalendarExecutor.CalendarInfo>>(emptyList())
    val availableCalendars: StateFlow<List<com.echoflow.app.execution.CalendarExecutor.CalendarInfo>> = _availableCalendars.asStateFlow()

    // --- Assistant & Voice Feedback ---
    val assistantPreferences = com.echoflow.app.data.prefs.AssistantPreferences(application)
    val speechOutputManager = com.echoflow.app.assistant.SpeechOutputManager(application, assistantPreferences)
    val voiceResponsesEnabled: StateFlow<Boolean> = assistantPreferences.voiceResponsesEnabled
    val voiceRemindersEnabled: StateFlow<Boolean> = assistantPreferences.voiceRemindersEnabled
    val speechRate: StateFlow<Float> = assistantPreferences.speechRate

    // --- Gesture & Ambient Diagnostics ---
    val isGestureServiceConnected: StateFlow<Boolean> = GestureTriggerController.isServiceConnected
    val isTouchExplorationActive: StateFlow<Boolean> = GestureTriggerController.isTouchExplorationActive
    val lastTriggerSource: StateFlow<String?> = GestureTriggerController.lastTriggerSource
    val lastDetectedGesture: StateFlow<String?> = GestureTriggerController.lastGestureName
    val lastGestureTime: StateFlow<Long?> = GestureTriggerController.lastGestureTime
    val gestureTriggerCount: StateFlow<Int> = GestureTriggerController.triggerCount


    // --- History Flow from Room ---
    val historyWorkflows: StateFlow<List<WorkflowWithActions>> = historyDao
        .getAllWorkflowsWithActions()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
        aiCoordinator.initialize()
        loadAvailableCalendars()
        // Try auto-loading Qwen in background if model file is installed
        viewModelScope.launch(Dispatchers.IO) {
            if (modelManager.isModelInstalled() && preferQwen.value) {
                Log.d(TAG, "[VM] Model detected on startup, initiating background load...")
                val result = aiCoordinator.loadLocalModel()
                if (result.isSuccess) {
                    Log.i(TAG, "[VM] Qwen Local initialized and ready on startup!")
                    _uiState.update { it.copy(lastEngine = "Qwen2.5-1.5B Local") }
                } else {
                    Log.w(TAG, "[VM] Background model load did not succeed: ${result.exceptionOrNull()?.message}")
                }
            }
        }
    }

    fun loadAvailableCalendars() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val list = actionExecutor.calendarExecutor.getSelectableCalendars()
                _availableCalendars.value = list
                Log.d(TAG, "[VM] Loaded ${list.size} selectable calendars for settings")
            } catch (e: Exception) {
                Log.w(TAG, "[VM] Could not load available calendars", e)
            }
        }
    }

    fun selectDefaultCalendar(calendarId: Long, accountName: String?, displayName: String?) {
        calendarPreferences.setSelectedCalendar(calendarId, accountName, displayName)
        _uiState.update { it.copy(statusToast = "Default calendar set to ${accountName ?: displayName ?: "ID $calendarId"}") }
    }

    fun selectTab(tab: AppTab) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    fun setInput(text: String) {
        _uiState.update { it.copy(input = text) }
    }

    fun setVoiceState(state: VoiceState) {
        _uiState.update { it.copy(voiceState = state) }
    }

    fun clearToast() {
        _uiState.update { it.copy(statusToast = null) }
    }

    fun setPreferQwen(prefer: Boolean) {
        aiCoordinator.setPreferQwen(prefer)
    }

    // --- Workflow Processing Pipeline ---

    fun processInput(input: String) {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return

        // 1. Capture requestCreatedAt immediately at user submission time!
        val requestCreatedAt = java.time.Instant.now()
        val deviceZone = java.time.ZoneId.systemDefault()
        val context = UserContext(
            requestCreatedAt = requestCreatedAt,
            timeZone = deviceZone
        )

        viewModelScope.launch {
            Log.d(TAG, "[FLOW] Starting workflow analysis for: '${trimmed.take(80)}...' (requestCreatedAt=$requestCreatedAt)")
            _uiState.update { it.copy(
                input = trimmed,
                isProcessing = true,
                actionGraph = null,
                executionStates = emptyMap(),
                executionMessages = emptyMap(),
                error = null,
                workflowComplete = false,
                currentPhase = "Analyzing workflow with ${if (aiCoordinator.isLocalModelAvailable() && preferQwen.value) "Qwen Local (usually takes ~60s on CPU)..." else "Fallback Parser"}..."
            )}

            val result = aiCoordinator.parseIntent(trimmed, context)

            result.fold(
                onSuccess = { graph ->
                    val defaultAccount = calendarPreferences.getSelectedAccountName()
                    val enrichedActions = graph.actions.map { action ->
                        if (action.type == ActionType.CALENDAR && action.calendarAccount == null) {
                            action.copy(calendarAccount = defaultAccount)
                        } else action
                    }
                    val enrichedGraph = graph.copy(actions = enrichedActions)

                    // Directly evaluate confidence without artificial sleep
                    val evaluated = ConfidenceEngine.evaluate(enrichedGraph)
                    val execStates = evaluated.actions.associate { it.id to it.executionState }

                    _uiState.update { it.copy(
                        isProcessing = false,
                        actionGraph = evaluated,
                        executionStates = execStates,
                        currentPhase = null,
                        lastEngine = evaluated.sourceEngine
                    )}
                    Log.i(TAG, "[FLOW] Workflow parsed: ${evaluated.actions.size} action(s) generated via ${evaluated.sourceEngine}")
                },
                onFailure = { error ->
                    Log.e(TAG, "[FLOW] Failed to parse workflow", error)
                    _uiState.update { it.copy(
                        isProcessing = false,
                        error = error.message ?: "Could not understand workflow",
                        currentPhase = null
                    )}
                }
            )
        }
    }

    // --- Action Editing and Removal ---

    fun openEditAction(action: ParsedAction) {
        _uiState.update { it.copy(editingAction = action) }
    }

    fun closeEditAction() {
        _uiState.update { it.copy(editingAction = null) }
    }

    fun saveEditedAction(edited: ParsedAction) {
        _uiState.update { state ->
            val updatedGraph = state.actionGraph?.let { graph ->
                graph.copy(actions = graph.actions.map { if (it.id == edited.id) edited else it })
            }
            state.copy(actionGraph = updatedGraph, editingAction = null, statusToast = "Action updated")
        }
    }

    /** Reschedule an action that has passed for tomorrow at the same time */
    fun rescheduleActionForTomorrow(actionId: String) {
        _uiState.update { state ->
            val updatedGraph = state.actionGraph?.let { graph ->
                val updatedActions = graph.actions.map { action ->
                    if (action.id == actionId) {
                        val currentDt = action.dateTime ?: LocalDateTime.now()
                        val tomorrowDt = currentDt.plusDays(1)
                        val tomorrowEpoch = tomorrowDt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        action.copy(
                            dateTime = tomorrowDt,
                            resolvedEpochMillis = tomorrowEpoch,
                            isPastTime = false,
                            requiresConfirmation = false,
                            ambiguityReason = null,
                            executionState = ExecutionState.READY
                        )
                    } else action
                }
                graph.copy(actions = updatedActions)
            }
            val newStates = state.executionStates.toMutableMap()
            newStates[actionId] = ExecutionState.READY
            state.copy(
                actionGraph = updatedGraph,
                executionStates = newStates,
                statusToast = "Rescheduled for tomorrow"
            )
        }
    }

    fun deleteAction(actionId: String) {
        _uiState.update { state ->
            val updatedGraph = state.actionGraph?.let { graph ->
                graph.copy(actions = graph.actions.filterNot { it.id == actionId })
            }
            val newStates = state.executionStates.toMutableMap().apply { remove(actionId) }
            state.copy(actionGraph = updatedGraph, executionStates = newStates, statusToast = "Action removed")
        }
    }

    fun confirmAction(actionId: String) {
        _uiState.update { state ->
            val newGraph = state.actionGraph?.let { graph ->
                graph.copy(
                    actions = graph.actions.map { action ->
                        if (action.id == actionId) {
                            action.copy(
                                executionState = ExecutionState.READY,
                                requiresConfirmation = false
                            )
                        } else action
                    }
                )
            }
            val newStates = state.executionStates.toMutableMap()
            newStates[actionId] = ExecutionState.READY
            state.copy(actionGraph = newGraph, executionStates = newStates)
        }
    }

    // --- Real Execution Layer ---

    fun executeAll() {
        val graph = _uiState.value.actionGraph ?: return

        viewModelScope.launch {
            Log.d(TAG, "[EXEC] Executing all actions...")
            val actionsToExecute = graph.actions.filter { action ->
                val state = _uiState.value.executionStates[action.id] ?: action.executionState
                state == ExecutionState.READY ||
                state == ExecutionState.DETECTED ||
                state == ExecutionState.NEEDS_CONFIRMATION ||
                state == ExecutionState.PENDING
            }

            if (actionsToExecute.isEmpty()) {
                _uiState.update { it.copy(statusToast = "No actions ready to execute") }
                return@launch
            }

            _uiState.update { it.copy(isExecuting = true) }

            val resultMap = mutableMapOf<String, ExecutionResult>()
            val executedActionList = mutableListOf<ParsedAction>()

            for (action in actionsToExecute) {
                val execStart = System.currentTimeMillis()
                // Update state to executing
                _uiState.update { state ->
                    val newStates = state.executionStates.toMutableMap()
                    newStates[action.id] = ExecutionState.EXECUTING
                    state.copy(executionStates = newStates)
                }

                // Actually perform action via Android APIs
                val result = actionExecutor.execute(action)
                val execFinish = System.currentTimeMillis()
                resultMap[action.id] = result
                Log.i(TAG, "[EXEC] ${action.type} '${action.title}' → ${result.state}: ${result.message}")

                val eventId = if (action.type == ActionType.CALENDAR) result.extraData?.toLongOrNull() else null
                val executedAction = action.copy(
                    executionState = result.state,
                    executionStartedAt = execStart,
                    executionFinishedAt = execFinish,
                    calendarEventId = eventId ?: action.calendarEventId,
                    executionMessage = result.message
                )
                executedActionList.add(executedAction)

                _uiState.update { state ->
                    val newStates = state.executionStates.toMutableMap()
                    newStates[action.id] = result.state

                    val newMsgs = state.executionMessages.toMutableMap()
                    newMsgs[action.id] = result.message

                    val newEventIds = state.calendarEventIds.toMutableMap()
                    if (eventId != null) {
                        newEventIds[action.id] = eventId
                    }

                    state.copy(
                        executionStates = newStates,
                        executionMessages = newMsgs,
                        calendarEventIds = newEventIds
                    )
                }
                // NO ARTIFICIAL DELAYS!
            }

            val updatedGraph = graph.copy(
                actions = graph.actions.map { orig ->
                    executedActionList.find { it.id == orig.id } ?: orig
                }
            )

            val completed = executedActionList.count { (it.executionState == ExecutionState.SUCCESS || it.executionState == ExecutionState.COMPLETED) && it.executionState != ExecutionState.SCHEDULED }
            val scheduled = executedActionList.count { it.executionState == ExecutionState.SCHEDULED }
            val prepared = executedActionList.count { it.executionState == ExecutionState.PREPARED || it.executionState == ExecutionState.IN_PROGRESS }
            val failed = executedActionList.count { it.executionState == ExecutionState.FAILED }

            val summaryParts = mutableListOf<String>()
            if (completed > 0) summaryParts.add("$completed completed")
            if (scheduled > 0) summaryParts.add("$scheduled scheduled")
            if (prepared > 0) summaryParts.add("$prepared prepared")
            if (failed > 0) summaryParts.add("$failed failed")
            val summaryMsg = if (summaryParts.isNotEmpty()) summaryParts.joinToString(", ") else "All actions processed"

            _uiState.update { it.copy(
                isExecuting = false,
                workflowComplete = true,
                actionGraph = updatedGraph,
                statusToast = summaryMsg
            ) }

            // Spoken feedback for executed workflow (Rule ⑦)
            speechOutputManager.speak(com.echoflow.app.assistant.SpokenSummaryGenerator.generateExecutionSummary(executedActionList))

            // Persist entire executed workflow into Room database
            saveWorkflowToHistory(updatedGraph, resultMap)
        }
    }

    /**
     * Cancel a single action by ID (Rule ④).
     */
    fun cancelAction(actionId: String) {
        viewModelScope.launch {
            Log.i(TAG, "[CANCEL] User cancelled action: $actionId")
            scheduledActionManager.cancelAction(actionId)

            _uiState.update { state ->
                val newStates = state.executionStates.toMutableMap()
                newStates[actionId] = ExecutionState.CANCELLED

                val newMsgs = state.executionMessages.toMutableMap()
                newMsgs[actionId] = "Cancelled by user"

                val updatedGraph = state.actionGraph?.let { g ->
                    g.copy(actions = g.actions.map { a ->
                        if (a.id == actionId) a.copy(executionState = ExecutionState.CANCELLED, executionMessage = "Cancelled by user")
                        else a
                    })
                }

                state.copy(
                    executionStates = newStates,
                    executionMessages = newMsgs,
                    actionGraph = updatedGraph,
                    statusToast = "Action cancelled"
                )
            }
        }
    }

    /**
     * Cancel all pending scheduled actions in the workflow (Rule ④).
     */
    fun cancelAllPendingActions() {
        viewModelScope.launch {
            Log.i(TAG, "[CANCEL ALL] User cancelled all pending actions")
            val count = scheduledActionManager.cancelAll()

            _uiState.update { state ->
                val newStates = state.executionStates.toMutableMap()
                val newMsgs = state.executionMessages.toMutableMap()

                state.actionGraph?.actions?.forEach { a ->
                    val curState = newStates[a.id] ?: a.executionState
                    if (curState.isPendingOrScheduled) {
                        newStates[a.id] = ExecutionState.CANCELLED
                        newMsgs[a.id] = "Cancelled in workflow"
                    }
                }

                val updatedGraph = state.actionGraph?.let { g ->
                    g.copy(actions = g.actions.map { a ->
                        if (a.executionState.isPendingOrScheduled) {
                            a.copy(executionState = ExecutionState.CANCELLED, executionMessage = "Cancelled in workflow")
                        } else a
                    })
                }

                state.copy(
                    executionStates = newStates,
                    executionMessages = newMsgs,
                    actionGraph = updatedGraph,
                    statusToast = "Cancelled all pending actions ($count)"
                )
            }
        }
    }

    /**
     * Snooze an action in-place by modifying the existing record (Rule ⑤).
     */
    fun snoozeAction(actionId: String, minutes: Int = 5) {
        viewModelScope.launch {
            Log.i(TAG, "[SNOOZE] User snoozed action $actionId for $minutes minutes")
            val res = scheduledActionManager.snoozeAction(actionId, minutes)
            _uiState.update { it.copy(statusToast = if (res.success) "Snoozed for $minutes min" else res.message) }
        }
    }

    /**
     * Trigger a scheduled action immediately.
     */
    fun executeActionNow(actionId: String) {
        viewModelScope.launch {
            Log.i(TAG, "[EXECUTE NOW] User triggered action $actionId immediately")
            val action = scheduledActionManager.getAction(actionId) ?: historyDao.getActionById(actionId)?.let { entity ->
                ParsedAction(
                    id = entity.id,
                    type = try { ActionType.valueOf(entity.actionType) } catch (_: Exception) { ActionType.REMINDER },
                    title = entity.title,
                    description = entity.description,
                    recipient = entity.recipient,
                    recipientPhone = entity.recipientPhone,
                    executionMode = ExecutionMode.IMMEDIATE,
                    message = entity.message,
                    confidence = entity.confidence,
                    dateTime = entity.dateTime?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() },
                    durationMinutes = entity.durationMinutes,
                    requiresConfirmation = false,
                    executionState = ExecutionState.READY
                )
            }

            if (action == null) {
                _uiState.update { it.copy(statusToast = "Action not found") }
                return@launch
            }

            // Cancel scheduled alarm
            scheduledActionManager.cancelAction(actionId)

            // Execute action immediately via ActionExecutor
            val result = actionExecutor.execute(action)
            _uiState.update { state ->
                val newStates = state.executionStates.toMutableMap()
                newStates[actionId] = result.state
                val newMsgs = state.executionMessages.toMutableMap()
                newMsgs[actionId] = result.message
                state.copy(
                    executionStates = newStates,
                    executionMessages = newMsgs,
                    statusToast = "${action.title}: ${result.message}"
                )
            }
        }
    }

    /**
     * Trigger Flagship Demo scenario (Robotics Meeting with 4 actions).
     */
    fun runFlagshipDemo() {
        val flagship = com.echoflow.app.demo.DemoScenarios.getFlagshipDemo()
        _uiState.update { it.copy(input = flagship) }
        processInput(flagship)
    }

    private suspend fun saveWorkflowToHistory(graph: ActionGraph, results: Map<String, ExecutionResult>) {
        withContext(Dispatchers.IO) {
            try {
                val now = System.currentTimeMillis()
                val workflowId = UUID.randomUUID().toString()

                val successCount = results.values.filter {
                    it.state == ExecutionState.SUCCESS || it.state == ExecutionState.COMPLETED || it.state == ExecutionState.SCHEDULED || it.state == ExecutionState.PREPARED
                }.size
                val totalCount = results.size
                val overallStatus = when {
                    totalCount > 0 && successCount == totalCount -> "ALL_SUCCESS"
                    successCount > 0 -> "PARTIAL_SUCCESS"
                    else -> "FAILED"
                }

                val workflowEntity = WorkflowHistoryEntity(
                    id = workflowId,
                    originalInput = graph.originalInput,
                    requestCreatedAt = graph.requestCreatedAt,
                    deviceTimezone = graph.deviceTimezone,
                    createdAt = now,
                    aiEngine = graph.sourceEngine,
                    overallConfidence = graph.overallConfidence,
                    executionStatus = overallStatus
                )

                val actionEntities = graph.actions.map { action ->
                    val res = results[action.id]
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
                        executionState = res?.state?.name ?: action.executionState.name,
                        executionMessage = res?.message ?: action.executionMessage,
                        executionStartedAt = action.executionStartedAt,
                        executionFinishedAt = action.executionFinishedAt,
                        createdAt = now
                    )
                }

                historyDao.insertWorkflowWithActions(workflowEntity, actionEntities)
                Log.i(TAG, "[HISTORY] Workflow $workflowId saved to Room with ${actionEntities.size} action(s)")
            } catch (e: Exception) {
                Log.e(TAG, "[HISTORY] Error saving workflow to Room", e)
            }
        }
    }

    // --- History Actions ---

    fun deleteWorkflowHistory(workflowId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            historyDao.deleteWorkflow(workflowId)
            _uiState.update { it.copy(statusToast = "Workflow removed from history") }
        }
    }

    fun runWorkflowAgain(workflow: WorkflowWithActions) {
        processInput(workflow.workflow.originalInput)
        selectTab(AppTab.HOME)
    }

    // --- Model Management & Import ---

    fun loadQwenModel() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(statusToast = "Loading Qwen model into native runtime...") }
            val res = aiCoordinator.loadLocalModel()
            if (res.isSuccess) {
                _uiState.update { it.copy(statusToast = "Qwen Local loaded and ready!", lastEngine = "Qwen2.5-1.5B Local") }
            } else {
                _uiState.update { it.copy(statusToast = "Failed to load Qwen: ${res.exceptionOrNull()?.message}") }
            }
        }
    }

    fun unloadQwenModel() {
        aiCoordinator.unloadLocalModel()
        _uiState.update { it.copy(statusToast = "Qwen model unloaded from memory", lastEngine = "Fallback Parser") }
    }

    fun importModelFromUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isImportingModel = true, modelImportProgress = "Copying model to app storage...") }
            try {
                val context = getApplication<Application>()
                val targetDir = File(context.getExternalFilesDir(null), ModelManager.MODEL_DIR)
                if (!targetDir.exists()) targetDir.mkdirs()

                val targetFile = File(targetDir, ModelManager.MODEL_FILENAMES.first())

                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(targetFile).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var bytesRead: Int
                        var totalBytes: Long = 0
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalBytes += bytesRead
                        }
                    }
                }

                modelManager.initialize()
                _uiState.update { it.copy(
                    isImportingModel = false,
                    modelImportProgress = null,
                    statusToast = "Model imported successfully (${targetFile.length() / (1024 * 1024)} MB)"
                )}
                loadQwenModel()
            } catch (e: Exception) {
                Log.e(TAG, "[MODEL] Failed to import model from URI", e)
                _uiState.update { it.copy(
                    isImportingModel = false,
                    modelImportProgress = null,
                    statusToast = "Model import failed: ${e.message}"
                )}
            }
        }
    }

    fun copyModelFromDownloads() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isImportingModel = true, modelImportProgress = "Scanning storage for Qwen GGUF...") }
            val context = getApplication<Application>()
            try {
                val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                var sourceFile: File? = null
                for (name in ModelManager.MODEL_FILENAMES) {
                    val f = File(downloadDir, name)
                    if (f.exists() && f.length() > 0) {
                        sourceFile = f
                        break
                    }
                }

                if (sourceFile == null) {
                    _uiState.update { it.copy(
                        isImportingModel = false,
                        modelImportProgress = null,
                        statusToast = "No GGUF file found in /sdcard/Download/"
                    )}
                    return@launch
                }

                val targetDir = File(context.getExternalFilesDir(null), ModelManager.MODEL_DIR)
                if (!targetDir.exists()) targetDir.mkdirs()
                val targetFile = File(targetDir, ModelManager.MODEL_FILENAMES.first())

                sourceFile.inputStream().use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                }

                modelManager.initialize()
                _uiState.update { it.copy(
                    isImportingModel = false,
                    modelImportProgress = null,
                    statusToast = "Model copied to app storage (${targetFile.length() / (1024 * 1024)} MB)"
                )}
                loadQwenModel()
            } catch (e: Exception) {
                _uiState.update { it.copy(
                    isImportingModel = false,
                    modelImportProgress = null,
                    statusToast = "Copy failed: ${e.message}"
                )}
            }
        }
    }

    // --- Developer Verification Tests ---

    fun createTestCalendarEvent() {
        val requestCreated = Instant.now()
        val zoneId = ZoneId.systemDefault()
        val startInstant = requestCreated.plus(java.time.Duration.ofMinutes(5))
        val startEpoch = startInstant.toEpochMilli()
        val defaultAccount = calendarPreferences.getSelectedAccountName()
        val action = ParsedAction(
            type = ActionType.CALENDAR,
            title = "EchoFlow UI Verification",
            description = "CalendarContract diagnostic verification event",
            timeExpression = "in 5 minutes",
            requestedTime = "in 5 minutes",
            resolvedEpochMillis = startEpoch,
            durationMinutes = 30,
            calendarAccount = defaultAccount,
            confidence = 1f,
            executionState = ExecutionState.READY
        )
        executeSingleDirectAction(action, "EchoFlow UI Verification")
    }


    fun scheduleTestReminder() {
        val triggerAt = LocalDateTime.now().plusSeconds(60)
        val action = ParsedAction(
            type = ActionType.REMINDER,
            title = "EchoFlow 60s Test Reminder",
            description = "This notification verifies the AlarmManager → ReminderReceiver pipeline.",
            dateTime = triggerAt,
            confidence = 1f,
            executionState = ExecutionState.READY
        )
        executeSingleDirectAction(action, "Reminder Direct Test")
    }


    private fun executeSingleDirectAction(action: ParsedAction, testName: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(
                actionGraph = ActionGraph(
                    originalInput = testName,
                    actions = listOf(action),
                    overallConfidence = 1f,
                    sourceEngine = "System Direct Test"
                ),
                executionStates = mapOf(action.id to ExecutionState.READY),
                lastEngine = "System Direct Test"
            )}
            executeAll()
        }
    }

    fun setVoiceResponsesEnabled(enabled: Boolean) = assistantPreferences.setVoiceResponsesEnabled(enabled)
    fun setVoiceRemindersEnabled(enabled: Boolean) = assistantPreferences.setVoiceRemindersEnabled(enabled)
    fun setSpeechRate(rate: Float) = assistantPreferences.setSpeechRate(rate)

    fun testVoiceFeedback() {
        speechOutputManager.speak("Hello! EchoFlow voice responses are working.")
    }


    override fun onCleared() {
        super.onCleared()
        speechOutputManager.release()
    }
}

