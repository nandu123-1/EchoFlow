package com.echoflow.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.activity.viewModels
import com.echoflow.app.assistant.AssistantSessionManager
import com.echoflow.app.assistant.AssistantState
import com.echoflow.app.assistant.GestureTriggerController
import com.echoflow.app.capture.SystemSpeechRecognizer
import com.echoflow.app.ui.screens.MainScreen
import com.echoflow.app.ui.theme.EchoFlowTheme
import com.echoflow.app.ui.viewmodel.EchoFlowViewModel
import com.echoflow.app.ui.viewmodel.VoiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Single Activity for EchoFlow.
 * Handles system permissions, speech-to-text, ambient assistant sessions,
 * and hosts the Compose UI.
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "EchoFlow-Main"
    }

    private val viewModel: EchoFlowViewModel by viewModels()
    private lateinit var speechRecognizer: SystemSpeechRecognizer
    private var assistantSessionManager: AssistantSessionManager? = null

    // Permission launchers
    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startVoiceCapture()
        } else {
            Toast.makeText(this, "Microphone permission is needed for voice input", Toast.LENGTH_SHORT).show()
        }
    }

    private val assistantMicPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startAssistantSession("gesture")
        } else {
            Toast.makeText(this, "Microphone access is required for voice input.", Toast.LENGTH_SHORT).show()
        }
    }

    private val calendarPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (!allGranted) {
            Toast.makeText(this, "Calendar permissions needed for event creation", Toast.LENGTH_SHORT).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "Notifications are required for reminders", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        speechRecognizer = SystemSpeechRecognizer(this)

        // Initialize AssistantSessionManager
        assistantSessionManager = AssistantSessionManager(
            context = this,
            speechRecognizer = speechRecognizer,
            speechOutputManager = viewModel.speechOutputManager,
            viewModel = viewModel
        )

        // Register in-process gesture trigger listener so if activity is alive,
        // gesture callback can directly activate the session without re-launching
        GestureTriggerController.onTriggerListener = {
            CoroutineScope(Dispatchers.Main).launch {
                Log.i(TAG, "[GESTURE] In-process trigger: starting assistant session")
                requestMicAndStartAssistant()
            }
        }

        requestCalendarPermissions()
        requestNotificationPermission()

        setContent {
            EchoFlowTheme {
                val uiState by viewModel.uiState.collectAsState()
                val modelState by viewModel.modelState.collectAsState()
                val preferQwen by viewModel.preferQwen.collectAsState()
                val historyWorkflows by viewModel.historyWorkflows.collectAsState()
                val availableCalendars by viewModel.availableCalendars.collectAsState()
                val selectedCalendarId by viewModel.selectedCalendarId.collectAsState()
                val voiceResponsesEnabled by viewModel.voiceResponsesEnabled.collectAsState()
                val voiceRemindersEnabled by viewModel.voiceRemindersEnabled.collectAsState()
                val speechRate by viewModel.speechRate.collectAsState()

                // Assistant session state
                val assistantState by (assistantSessionManager?.state
                    ?: kotlinx.coroutines.flow.MutableStateFlow(AssistantState.IDLE)).collectAsState()
                val assistantTranscript by (assistantSessionManager?.transcript
                    ?: kotlinx.coroutines.flow.MutableStateFlow("")).collectAsState()
                val assistantStatusText by (assistantSessionManager?.statusText
                    ?: kotlinx.coroutines.flow.MutableStateFlow("")).collectAsState()
                val assistantSpokenResponse by (assistantSessionManager?.spokenResponse
                    ?: kotlinx.coroutines.flow.MutableStateFlow(null)).collectAsState()
                val pendingScheduledActions by viewModel.pendingScheduledActions.collectAsState()

                MainScreen(
                    uiState = uiState,
                    modelState = modelState,
                    modelManager = viewModel.modelManager,
                    preferQwen = preferQwen,
                    historyWorkflows = historyWorkflows,
                    pendingScheduledActions = pendingScheduledActions,
                    onSelectTab = { tab -> viewModel.selectTab(tab) },
                    onProcessInput = { input -> viewModel.processInput(input) },
                    onVoiceCapture = { requestMicAndCapture() },
                    onConfirmAction = { id -> viewModel.confirmAction(id) },
                    onDeleteAction = { id -> viewModel.deleteAction(id) },
                    onEditAction = { action -> viewModel.openEditAction(action) },
                    onSaveEditedAction = { edited -> viewModel.saveEditedAction(edited) },
                    onCloseEditAction = { viewModel.closeEditAction() },
                    onExecuteAll = { viewModel.executeAll() },
                    onLoadDemo = { index ->
                        val scenario = com.echoflow.app.demo.DemoScenarios.scenarios.getOrNull(index)
                        scenario?.let { viewModel.processInput(it.input) }
                    },
                    onInputChange = { text -> viewModel.setInput(text) },
                    onDeleteWorkflow = { id -> viewModel.deleteWorkflowHistory(id) },
                    onRunAgainWorkflow = { wf -> viewModel.runWorkflowAgain(wf) },
                    onSetPreferQwen = { prefer -> viewModel.setPreferQwen(prefer) },
                    onLoadModel = { viewModel.loadQwenModel() },
                    onUnloadModel = { viewModel.unloadQwenModel() },
                    onImportModelUri = { uri -> viewModel.importModelFromUri(uri) },
                    onCopyFromDownloads = { viewModel.copyModelFromDownloads() },
                    onCreateTestCalendar = { viewModel.createTestCalendarEvent() },
                    onScheduleTestReminder = { viewModel.scheduleTestReminder() },
                    onTestAmbient = { com.echoflow.app.ambient.AmbientController.handleShortcutTrigger(this, "diagnostic") },
                    onClearToast = { viewModel.clearToast() },
                    onRescheduleTomorrow = { id -> viewModel.rescheduleActionForTomorrow(id) },
                    availableCalendars = availableCalendars,
                    selectedCalendarId = selectedCalendarId,
                    onSelectCalendar = { id, acc, name -> viewModel.selectDefaultCalendar(id, acc, name) },
                    onRefreshCalendars = { viewModel.loadAvailableCalendars() },
                    onOpenCalendar = { eventId -> openCalendarEvent(eventId) },
                    // V3 Voice Settings
                    voiceResponsesEnabled = voiceResponsesEnabled,
                    onSetVoiceResponses = { viewModel.setVoiceResponsesEnabled(it) },
                    voiceRemindersEnabled = voiceRemindersEnabled,
                    onSetVoiceReminders = { viewModel.setVoiceRemindersEnabled(it) },
                    speechRate = speechRate,
                    onSetSpeechRate = { viewModel.setSpeechRate(it) },
                    onTestVoice = { viewModel.testVoiceFeedback() },
                    // V3 Assistant Session
                    assistantState = assistantState,
                    assistantTranscript = assistantTranscript,
                    assistantStatusText = assistantStatusText,
                    assistantSpokenResponse = assistantSpokenResponse,
                    onCancelAssistant = { assistantSessionManager?.cancelSession() },
                    onExecuteAssistant = { assistantSessionManager?.executeActions() },
                    onCancelAction = { id -> viewModel.cancelAction(id) },
                    onCancelAll = { viewModel.cancelAllPendingActions() },
                    onRunFlagshipDemo = { viewModel.runFlagshipDemo() },
                    onSnoozeAction = { id, mins -> viewModel.snoozeAction(id, mins) },
                    onExecuteActionNow = { id -> viewModel.executeActionNow(id) }
                )
            }
        }

        handleIntent(intent)
    }

    private fun openCalendarEvent(eventId: Long) {
        try {
            Log.i(TAG, "[CALENDAR OPEN] openCalendarEvent with ID: $eventId")
            val uri = if (eventId > 0) {
                android.content.ContentUris.withAppendedId(
                    android.provider.CalendarContract.Events.CONTENT_URI, eventId
                )
            } else {
                android.provider.CalendarContract.CONTENT_URI.buildUpon()
                    .appendPath("time")
                    .appendPath(System.currentTimeMillis().toString())
                    .build()
            }

            var launched = false
            try {
                // Try Google Calendar explicit package first
                val googleCalIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri).apply {
                    setPackage("com.google.android.calendar")
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(googleCalIntent)
                launched = true
            } catch (e: Exception) {
                Log.w(TAG, "[CALENDAR] Google Calendar direct launch failed, trying generic intent", e)
            }

            if (!launched) {
                val genericIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri).apply {
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(genericIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "[CALENDAR] Could not open event $eventId", e)
            Toast.makeText(this, "Could not open calendar: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: android.content.Intent?) {
        // V3: Handle ambient triggers (Accessibility Button, Quick Settings Tile, 4-Finger Gesture)
        val triggerSource = intent?.getStringExtra(GestureTriggerController.EXTRA_TRIGGER_SOURCE)
        if (!triggerSource.isNullOrBlank()) {
            Log.i(TAG, "[INTENT] Received ambient trigger from source: $triggerSource")
            intent.removeExtra(GestureTriggerController.EXTRA_TRIGGER_SOURCE)
            requestMicAndStartAssistant()
            return
        }

        // V3: Handle system ACTION_ASSIST
        if (intent?.action == android.content.Intent.ACTION_ASSIST) {
            Log.i(TAG, "[INTENT] Received system ACTION_ASSIST")
            requestMicAndStartAssistant()
            return
        }

        // V3: Handle reminder "Start Now" follow-up
        if (intent?.getBooleanExtra("start_reminder_followup", false) == true) {
            Log.i(TAG, "[INTENT] Received reminder follow-up 'Start Now'")
            intent.removeExtra("start_reminder_followup")
            requestMicAndStartAssistant()
            return
        }

        val input = intent?.getStringExtra("process_input")
        if (!input.isNullOrBlank()) {
            Log.d(TAG, "[INTENT] Processing input: $input")
            viewModel.setInput(input)
            viewModel.processInput(input)
            intent.removeExtra("process_input")
        }
        if (intent?.getBooleanExtra("execute_all", false) == true) {
            Log.d(TAG, "[INTENT] Executing all actions")
            viewModel.executeAll()
            intent.removeExtra("execute_all")
        }
        if (intent?.getBooleanExtra("test_calendar", false) == true ||
            intent?.getBooleanExtra("run_calendar_diagnostic", false) == true) {
            Log.d(TAG, "[INTENT] Triggering test calendar event")
            viewModel.createTestCalendarEvent()
            intent.removeExtra("test_calendar")
            intent.removeExtra("run_calendar_diagnostic")
        }
        if (intent?.getBooleanExtra("test_reminder", false) == true) {
            Log.d(TAG, "[INTENT] Triggering test 60s reminder")
            viewModel.scheduleTestReminder()
            intent.removeExtra("test_reminder")
        }
        if (intent?.getBooleanExtra("test_note", false) == true) {
            Log.d(TAG, "[INTENT] Triggering test ambient session")
            com.echoflow.app.ambient.AmbientController.handleShortcutTrigger(this, "test_intent")
            intent.removeExtra("test_note")
        }
        if (intent?.getBooleanExtra("run_flagship_demo", false) == true) {
            Log.d(TAG, "[INTENT] Triggering flagship demo")
            viewModel.runFlagshipDemo()
            intent.removeExtra("run_flagship_demo")
        }
        if (intent?.getBooleanExtra("cancel_all", false) == true) {
            Log.d(TAG, "[INTENT] Cancelling all pending actions")
            viewModel.cancelAllPendingActions()
            intent.removeExtra("cancel_all")
        }
    }

    // --- V3 Assistant Session Trigger ---

    private fun requestMicAndStartAssistant() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED -> {
                startAssistantSession("gesture")
            }
            else -> {
                assistantMicPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun startAssistantSession(triggerSource: String) {
        Log.i(TAG, "[ASSISTANT] Starting assistant session from $triggerSource")
        assistantSessionManager?.startSession(triggerSource)
    }

    // --- Existing V2 Voice Capture ---

    private fun requestMicAndCapture() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED -> {
                startVoiceCapture()
            }
            else -> {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun startVoiceCapture() {
        if (!speechRecognizer.isAvailable()) {
            Toast.makeText(this, "Speech recognition not available on this device. Type naturally instead.", Toast.LENGTH_SHORT).show()
            return
        }

        viewModel.setVoiceState(VoiceState.LISTENING)

        CoroutineScope(Dispatchers.Main).launch {
            val result = speechRecognizer.transcribe()
            result.fold(
                onSuccess = { text ->
                    viewModel.setVoiceState(VoiceState.IDLE)
                    if (text.isNotBlank()) {
                        viewModel.setInput(text)
                        viewModel.processInput(text)
                    }
                },
                onFailure = { error ->
                    viewModel.setVoiceState(VoiceState.IDLE)
                    Toast.makeText(
                        this@MainActivity,
                        "Voice input: ${error.message ?: "Could not recognize speech"}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            )
        }
    }

    private fun requestCalendarPermissions() {
        val needed = listOf(
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR
        ).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            calendarPermissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer.cancel()
        assistantSessionManager?.release()
        GestureTriggerController.onTriggerListener = null
    }
}
