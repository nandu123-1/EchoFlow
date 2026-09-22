package com.echoflow.app.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.echoflow.app.ai.ModelManager
import com.echoflow.app.assistant.AssistantState
import com.echoflow.app.data.db.WorkflowWithActions
import com.echoflow.app.domain.model.ModelState
import com.echoflow.app.domain.model.ParsedAction
import com.echoflow.app.ui.components.AmbientAssistantSheet
import com.echoflow.app.ui.components.EditActionBottomSheet
import com.echoflow.app.ui.theme.EchoAccent
import com.echoflow.app.ui.theme.EchoDarkBg
import com.echoflow.app.ui.theme.EchoDarkSurface
import com.echoflow.app.ui.viewmodel.AppTab
import com.echoflow.app.ui.viewmodel.EchoFlowUiState

@Composable
fun MainScreen(
    uiState: EchoFlowUiState,
    modelState: ModelState,
    modelManager: ModelManager,
    preferQwen: Boolean,
    historyWorkflows: List<WorkflowWithActions>,
    pendingScheduledActions: List<ParsedAction> = emptyList(),
    onSelectTab: (AppTab) -> Unit,
    onProcessInput: (String) -> Unit,
    onVoiceCapture: () -> Unit,
    onConfirmAction: (String) -> Unit,
    onDeleteAction: (String) -> Unit,
    onEditAction: (ParsedAction) -> Unit,
    onSaveEditedAction: (ParsedAction) -> Unit,
    onCloseEditAction: () -> Unit,
    onExecuteAll: () -> Unit,
    onLoadDemo: (Int) -> Unit,
    onInputChange: (String) -> Unit,
    onDeleteWorkflow: (String) -> Unit,
    onRunAgainWorkflow: (WorkflowWithActions) -> Unit,
    onSetPreferQwen: (Boolean) -> Unit,
    onLoadModel: () -> Unit,
    onUnloadModel: () -> Unit,
    onImportModelUri: (Uri) -> Unit,
    onCopyFromDownloads: () -> Unit,
    onCreateTestCalendar: () -> Unit,
    onScheduleTestReminder: () -> Unit,
    onTestAmbient: () -> Unit,
    onClearToast: () -> Unit,
    onRescheduleTomorrow: (String) -> Unit = {},
    availableCalendars: List<com.echoflow.app.execution.CalendarExecutor.CalendarInfo> = emptyList(),
    selectedCalendarId: Long = -1L,
    onSelectCalendar: (Long, String?, String?) -> Unit = { _, _, _ -> },
    onRefreshCalendars: () -> Unit = {},
    onOpenCalendar: (Long) -> Unit = {},
    // V3 Voice Settings
    voiceResponsesEnabled: Boolean = true,
    onSetVoiceResponses: (Boolean) -> Unit = {},
    voiceRemindersEnabled: Boolean = true,
    onSetVoiceReminders: (Boolean) -> Unit = {},
    speechRate: Float = 1.0f,
    onSetSpeechRate: (Float) -> Unit = {},
    onTestVoice: () -> Unit = {},
    // V3 Assistant Session
    assistantState: AssistantState = AssistantState.IDLE,
    assistantTranscript: String = "",
    assistantStatusText: String = "",
    assistantSpokenResponse: String? = null,
    onCancelAssistant: () -> Unit = {},
    onExecuteAssistant: () -> Unit = {},
    onCancelAction: (String) -> Unit = {},
    onCancelAll: () -> Unit = {},
    onRunFlagshipDemo: () -> Unit = {},
    onSnoozeAction: (String, Int) -> Unit = { _, _ -> },
    onExecuteActionNow: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Show status toast when available
    LaunchedEffect(uiState.statusToast) {
        uiState.statusToast?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            onClearToast()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = EchoDarkBg,
            bottomBar = {
                NavigationBar(
                    containerColor = EchoDarkSurface,
                    tonalElevation = 8.dp
                ) {
                    NavigationBarItem(
                        selected = uiState.selectedTab == AppTab.HOME,
                        onClick = { onSelectTab(AppTab.HOME) },
                        icon = {
                            Icon(
                                imageVector = if (uiState.selectedTab == AppTab.HOME) Icons.Filled.Home else Icons.Outlined.Home,
                                contentDescription = "Home"
                            )
                        },
                        label = { Text("Home") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = EchoAccent,
                            selectedTextColor = EchoAccent,
                            indicatorColor = EchoAccent.copy(alpha = 0.15f)
                        )
                    )

                    NavigationBarItem(
                        selected = uiState.selectedTab == AppTab.TIMELINE,
                        onClick = { onSelectTab(AppTab.TIMELINE) },
                        icon = {
                            Icon(
                                imageVector = if (uiState.selectedTab == AppTab.TIMELINE) Icons.Filled.CalendarMonth else Icons.Outlined.CalendarMonth,
                                contentDescription = "Timeline"
                            )
                        },
                        label = { Text("Timeline") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = EchoAccent,
                            selectedTextColor = EchoAccent,
                            indicatorColor = EchoAccent.copy(alpha = 0.15f)
                        )
                    )

                    NavigationBarItem(
                        selected = uiState.selectedTab == AppTab.HISTORY,
                        onClick = { onSelectTab(AppTab.HISTORY) },
                        icon = {
                            Icon(
                                imageVector = if (uiState.selectedTab == AppTab.HISTORY) Icons.Filled.History else Icons.Outlined.History,
                                contentDescription = "History"
                            )
                        },
                        label = { Text("History") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = EchoAccent,
                            selectedTextColor = EchoAccent,
                            indicatorColor = EchoAccent.copy(alpha = 0.15f)
                        )
                    )

                    NavigationBarItem(
                        selected = uiState.selectedTab == AppTab.SETTINGS,
                        onClick = { onSelectTab(AppTab.SETTINGS) },
                        icon = {
                            Icon(
                                imageVector = if (uiState.selectedTab == AppTab.SETTINGS) Icons.Filled.Settings else Icons.Outlined.Settings,
                                contentDescription = "Settings"
                            )
                        },
                        label = { Text("Settings") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = EchoAccent,
                            selectedTextColor = EchoAccent,
                            indicatorColor = EchoAccent.copy(alpha = 0.15f)
                        )
                    )
                }
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                when (uiState.selectedTab) {
                    AppTab.HOME -> {
                        HomeScreen(
                            uiState = uiState,
                            modelState = modelState,
                            onProcessInput = onProcessInput,
                            onVoiceCapture = onVoiceCapture,
                            onConfirmAction = onConfirmAction,
                            onDeleteAction = onDeleteAction,
                            onEditAction = onEditAction,
                            onExecuteAll = onExecuteAll,
                            onLoadDemo = onLoadDemo,
                            onInputChange = onInputChange,
                            onRescheduleTomorrow = onRescheduleTomorrow,
                            onOpenCalendar = onOpenCalendar,
                            onCancelAction = onCancelAction,
                            onCancelAll = onCancelAll,
                            onRunFlagshipDemo = onRunFlagshipDemo
                        )
                    }
                    AppTab.TIMELINE -> {
                        TimelineScreen(
                            pendingActions = pendingScheduledActions,
                            historyWorkflows = historyWorkflows,
                            onSnoozeAction = onSnoozeAction,
                            onCancelAction = onCancelAction,
                            onExecuteActionNow = onExecuteActionNow,
                            onOpenCalendar = onOpenCalendar
                        )
                    }
                    AppTab.HISTORY -> {
                        HistoryScreen(
                            workflows = historyWorkflows,
                            onDeleteWorkflow = onDeleteWorkflow,
                            onRunAgain = onRunAgainWorkflow
                        )
                    }
                    AppTab.SETTINGS -> {
                        SettingsScreen(
                            modelManager = modelManager,
                            modelState = modelState,
                            preferQwen = preferQwen,
                            onSetPreferQwen = onSetPreferQwen,
                            onLoadModel = onLoadModel,
                            onUnloadModel = onUnloadModel,
                            onImportModelUri = onImportModelUri,
                            onCopyFromDownloads = onCopyFromDownloads,
                            onCreateTestCalendar = onCreateTestCalendar,
                            onScheduleTestReminder = onScheduleTestReminder,
                            onTestAmbient = onTestAmbient,
                            isImporting = uiState.isImportingModel,
                            importProgress = uiState.modelImportProgress,
                            availableCalendars = availableCalendars,
                            selectedCalendarId = selectedCalendarId,
                            onSelectCalendar = onSelectCalendar,
                            onRefreshCalendars = onRefreshCalendars,
                            voiceResponsesEnabled = voiceResponsesEnabled,
                            onSetVoiceResponses = onSetVoiceResponses,
                            voiceRemindersEnabled = voiceRemindersEnabled,
                            onSetVoiceReminders = onSetVoiceReminders,
                            speechRate = speechRate,
                            onSetSpeechRate = onSetSpeechRate,
                            onTestVoice = onTestVoice
                        )
                    }
                }

                // Edit Action Modal Bottom Sheet
                uiState.editingAction?.let { action ->
                    EditActionBottomSheet(
                        action = action,
                        onDismiss = onCloseEditAction,
                        onSave = onSaveEditedAction
                    )
                }
            }
        }

        // V3 Ambient Assistant Overlay (renders on top of everything when active)
        AmbientAssistantSheet(
            assistantState = assistantState,
            transcript = assistantTranscript,
            statusText = assistantStatusText,
            spokenResponse = assistantSpokenResponse,
            actionGraph = uiState.actionGraph,
            executionStates = uiState.executionStates,
            onCancel = onCancelAssistant,
            onExecute = onExecuteAssistant,
            onConfirmAction = onConfirmAction,
            onDeleteAction = onDeleteAction,
            onOpenCalendar = onOpenCalendar,
            onCancelAction = onCancelAction,
            onCancelAll = onCancelAll
        )
    }
}
