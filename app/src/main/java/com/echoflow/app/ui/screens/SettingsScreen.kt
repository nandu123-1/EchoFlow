package com.echoflow.app.ui.screens

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.echoflow.app.ai.ModelManager
import com.echoflow.app.domain.model.ModelState
import androidx.compose.foundation.BorderStroke
import com.echoflow.app.execution.CalendarExecutor
import com.echoflow.app.assistant.GestureTriggerController
import com.echoflow.app.assistant.EchoFlowAccessibilityService
import android.content.Intent
import android.provider.Settings
import com.echoflow.app.ui.theme.*

@Composable
fun SettingsScreen(
    modelManager: ModelManager,
    modelState: ModelState,
    preferQwen: Boolean,
    onSetPreferQwen: (Boolean) -> Unit,
    onLoadModel: () -> Unit,
    onUnloadModel: () -> Unit,
    onImportModelUri: (Uri) -> Unit,
    onCopyFromDownloads: () -> Unit,
    onCreateTestCalendar: () -> Unit,
    onScheduleTestReminder: () -> Unit,
    onTestAmbient: () -> Unit,
    isImporting: Boolean,
    importProgress: String?,
    availableCalendars: List<CalendarExecutor.CalendarInfo> = emptyList(),
    selectedCalendarId: Long = -1L,
    onSelectCalendar: (Long, String?, String?) -> Unit = { _, _, _ -> },
    onRefreshCalendars: () -> Unit = {},
    voiceResponsesEnabled: Boolean = true,
    onSetVoiceResponses: (Boolean) -> Unit = {},
    voiceRemindersEnabled: Boolean = true,
    onSetVoiceReminders: (Boolean) -> Unit = {},
    speechRate: Float = 1.0f,
    onSetSpeechRate: (Float) -> Unit = {},
    onTestVoice: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { onImportModelUri(it) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = EchoDarkBg
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = EchoTextPrimary
            )

            // --- AI MODEL SECTION ---
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = EchoDarkSurface)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "AI MODEL",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = EchoAccent
                        )

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = when (modelState) {
                                ModelState.LOADED -> EchoSuccess.copy(alpha = 0.15f)
                                ModelState.READY -> EchoAccent.copy(alpha = 0.15f)
                                ModelState.LOADING -> EchoAccent.copy(alpha = 0.15f)
                                ModelState.DOWNLOADING -> EchoAccent.copy(alpha = 0.15f)
                                ModelState.ERROR -> EchoError.copy(alpha = 0.15f)
                                ModelState.NOT_INSTALLED -> EchoWarning.copy(alpha = 0.15f)
                            }
                        ) {
                            Text(
                                text = modelState.name,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = when (modelState) {
                                    ModelState.LOADED -> EchoSuccess
                                    ModelState.READY -> EchoAccent
                                    ModelState.LOADING -> EchoAccent
                                    ModelState.DOWNLOADING -> EchoAccent
                                    ModelState.ERROR -> EchoError
                                    ModelState.NOT_INSTALLED -> EchoWarning
                                },
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Qwen2.5-1.5B-Instruct (GGUF Q4_K_M)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = EchoTextPrimary
                        )

                        val modelSize = modelManager.getModelSizeMB()
                        val modelPath = modelManager.getModelPath()

                        Text(
                            text = if (modelSize > 0) "Size: $modelSize MB on disk" else "Model file not found",
                            style = MaterialTheme.typography.bodySmall,
                            color = EchoTextSecondary
                        )

                        Text(
                            text = "Path: $modelPath",
                            style = MaterialTheme.typography.bodySmall,
                            color = EchoTextTertiary
                        )
                    }

                    if (isImporting) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = EchoAccent)
                            Text(
                                text = importProgress ?: "Importing model...",
                                style = MaterialTheme.typography.bodySmall,
                                color = EchoAccent
                            )
                        }
                    }

                    // Model Actions
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Pick File", maxLines = 1)
                        }

                        OutlinedButton(
                            onClick = onCopyFromDownloads,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Import /sdcard", maxLines = 1)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (modelState == ModelState.LOADED) {
                            Button(
                                onClick = onUnloadModel,
                                colors = ButtonDefaults.buttonColors(containerColor = EchoError.copy(alpha = 0.8f)),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Unload Model")
                            }
                        } else {
                            Button(
                                onClick = onLoadModel,
                                enabled = modelManager.isModelInstalled() && !isImporting,
                                colors = ButtonDefaults.buttonColors(containerColor = EchoAccent),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Load Model (RAM)")
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    // Prefer Qwen Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Prefer Qwen Local",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = EchoTextPrimary
                            )
                            Text(
                                text = "When loaded, use on-device LLM for intent extraction",
                                style = MaterialTheme.typography.bodySmall,
                                color = EchoTextSecondary
                            )
                        }
                        Switch(
                            checked = preferQwen,
                            onCheckedChange = onSetPreferQwen
                        )
                    }
                }
            }

            // --- SYSTEM PERMISSIONS ---
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = EchoDarkSurface)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "ANDROID INTEGRATIONS",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = EchoAccent
                    )

                    PermissionRow(
                        name = "Calendar Access",
                        granted = isPermissionGranted(context, android.Manifest.permission.WRITE_CALENDAR),
                        onRequest = {
                            permissionLauncher.launch(
                                arrayOf(
                                    android.Manifest.permission.READ_CALENDAR,
                                    android.Manifest.permission.WRITE_CALENDAR
                                )
                            )
                        }
                    )

                    PermissionRow(
                        name = "Notifications",
                        granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            isPermissionGranted(context, android.Manifest.permission.POST_NOTIFICATIONS)
                        } else true,
                        onRequest = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                permissionLauncher.launch(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS))
                            }
                        }
                    )

                    PermissionRow(
                        name = "Microphone",
                        granted = isPermissionGranted(context, android.Manifest.permission.RECORD_AUDIO),
                        onRequest = {
                            permissionLauncher.launch(arrayOf(android.Manifest.permission.RECORD_AUDIO))
                        }
                    )

                    PermissionRow(
                        name = "Contacts Lookup",
                        granted = isPermissionGranted(context, android.Manifest.permission.READ_CONTACTS),
                        onRequest = {
                            permissionLauncher.launch(arrayOf(android.Manifest.permission.READ_CONTACTS))
                        }
                    )
                }
            }

            // --- AMBIENT ASSISTANT ACCESS ---
            var gestureRefreshTrigger by remember { mutableStateOf(0) }
            val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                    if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                        gestureRefreshTrigger++
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                }
            }

            val isGestureEnabled = remember(context, gestureRefreshTrigger) {
                GestureTriggerController.isAccessibilityServiceEnabled(context)
            }
            val isServiceConnected by GestureTriggerController.isServiceConnected.collectAsState()
            val isTouchExplorationActive by GestureTriggerController.isTouchExplorationActive.collectAsState()
            val lastTriggerSource by GestureTriggerController.lastTriggerSource.collectAsState()
            val lastGestureName by GestureTriggerController.lastGestureName.collectAsState()
            val lastGestureTime by GestureTriggerController.lastGestureTime.collectAsState()
            val triggerCount by GestureTriggerController.triggerCount.collectAsState()
            val timeFormatter = remember { java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()) }
            val formattedLastTime = lastGestureTime?.let { timeFormatter.format(java.util.Date(it)) } ?: "None"

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = EchoDarkSurface)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "AMBIENT ASSISTANT ACCESS",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = EchoAccent
                        )

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isGestureEnabled && isServiceConnected) {
                                if (isTouchExplorationActive) EchoWarning.copy(alpha = 0.15f) else EchoSuccess.copy(alpha = 0.15f)
                            } else if (isGestureEnabled) {
                                EchoAccent.copy(alpha = 0.15f)
                            } else {
                                EchoWarning.copy(alpha = 0.15f)
                            }
                        ) {
                            Text(
                                text = if (isGestureEnabled && isServiceConnected) {
                                    if (isTouchExplorationActive) "⚠️ Touch Exploration Active" else "✓ Normal Touch Intact"
                                } else if (isGestureEnabled) {
                                    "Service Enabled"
                                } else {
                                    "Service Disabled"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (isGestureEnabled && isServiceConnected) {
                                    if (isTouchExplorationActive) EchoWarning else EchoSuccess
                                } else if (isGestureEnabled) {
                                    EchoAccent
                                } else {
                                    EchoWarning
                                },
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }

                    // --- RECOMMENDED: SAFE AMBIENT TRIGGERS ---
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Safe Ambient Triggers (Zero Touch Impact)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = EchoTextPrimary
                        )

                        Text(
                            text = "These official Android triggers summon EchoFlow from any app without interfering with standard phone touch, scrolling, or typing.",
                            style = MaterialTheme.typography.bodySmall,
                            color = EchoTextSecondary
                        )

                        // 1. Accessibility Shortcut Button
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = EchoDarkBg,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.TouchApp,
                                    contentDescription = null,
                                    tint = EchoAccent,
                                    modifier = Modifier.size(24.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Floating Accessibility Button",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = EchoTextPrimary
                                    )
                                    Text(
                                        text = "Tap the floating accessibility button on your screen to summon the ambient assistant instantly.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = EchoTextTertiary
                                    )
                                }
                            }
                        }

                        // 2. Quick Settings Tile
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = EchoDarkBg,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Widgets,
                                    contentDescription = null,
                                    tint = EchoAccent,
                                    modifier = Modifier.size(24.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Quick Settings Tile",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = EchoTextPrimary
                                    )
                                    Text(
                                        text = "Pull down the notification shade from any screen or game and tap the 'EchoFlow' tile.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = EchoTextTertiary
                                    )
                                }
                            }
                        }

                        // 3. Digital Assistant
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = EchoDarkBg,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Assistant,
                                    contentDescription = null,
                                    tint = EchoAccent,
                                    modifier = Modifier.size(24.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Digital Assistant Gesture",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = EchoTextPrimary
                                    )
                                    Text(
                                        text = "Set EchoFlow as default Digital Assistant in Android Settings to summon via power button hold or corner swipe.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = EchoTextTertiary
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f))

                    // --- OPT-IN EXPERIMENTAL: 4-FINGER GESTURE ---
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "4-Finger Swipe Gesture (Opt-In)",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = EchoTextPrimary
                                )
                                Text(
                                    text = "Experimental Android Touch Exploration mode",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = EchoTextSecondary
                                )
                            }

                            Switch(
                                checked = isTouchExplorationActive,
                                onCheckedChange = { enabled ->
                                    EchoFlowAccessibilityService.setTouchExplorationEnabled(enabled)
                                },
                                enabled = isServiceConnected
                            )
                        }

                        if (isTouchExplorationActive) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = EchoWarning.copy(alpha = 0.12f),
                                border = BorderStroke(1.dp, EchoWarning.copy(alpha = 0.4f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "⚠️ Touch Exploration Active: Android requires TalkBack touch exploration to deliver 4-finger gestures. In this mode, taps select/hover, double-tap activates buttons, and two fingers scroll. Turn this OFF to restore normal phone touch.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = EchoWarning,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        } else {
                            Text(
                                text = "Keep this OFF for daily use. Android requires Touch Exploration to detect multi-finger gestures, which alters single-finger taps and scrolling.",
                                style = MaterialTheme.typography.bodySmall,
                                color = EchoTextTertiary
                            )
                        }
                    }

                    // --- LIVE DIAGNOSTIC STATS ---
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = EchoDarkBg,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "SYSTEM DIAGNOSTICS",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = EchoAccent
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Accessibility Service:", style = MaterialTheme.typography.bodySmall, color = EchoTextSecondary)
                                Text(
                                    text = if (isGestureEnabled) "ENABLED" else "NOT ENABLED",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isGestureEnabled) EchoSuccess else EchoWarning
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Service Connection:", style = MaterialTheme.typography.bodySmall, color = EchoTextSecondary)
                                Text(
                                    text = if (isServiceConnected) "CONNECTED" else "DISCONNECTED",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isServiceConnected) EchoSuccess else EchoTextTertiary
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Normal Touch State:", style = MaterialTheme.typography.bodySmall, color = EchoTextSecondary)
                                Text(
                                    text = if (isTouchExplorationActive) "ALTERED (Touch Exploration)" else "100% INTACT (Safe)",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isTouchExplorationActive) EchoWarning else EchoSuccess
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Last Trigger Source:", style = MaterialTheme.typography.bodySmall, color = EchoTextSecondary)
                                Text(
                                    text = when (lastTriggerSource) {
                                        GestureTriggerController.TRIGGER_ACCESSIBILITY_BUTTON -> "Accessibility Button"
                                        GestureTriggerController.TRIGGER_QUICK_SETTINGS_TILE -> "Quick Settings Tile"
                                        GestureTriggerController.TRIGGER_ASSISTANT_INTENT -> "Digital Assistant"
                                        GestureTriggerController.TRIGGER_GESTURE_4_FINGER -> "4-Finger Gesture"
                                        else -> lastTriggerSource ?: "None yet"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (lastTriggerSource != null) EchoAccent else EchoTextTertiary
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Last Trigger Time:", style = MaterialTheme.typography.bodySmall, color = EchoTextSecondary)
                                Text(
                                    text = formattedLastTime,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = EchoTextSecondary
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Triggers Fired:", style = MaterialTheme.typography.bodySmall, color = EchoTextSecondary)
                                Text(
                                    text = "$triggerCount",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = EchoTextPrimary
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                try {
                                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    // fallback
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = EchoAccent),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(imageVector = Icons.Outlined.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Accessibility")
                        }

                        OutlinedButton(
                            onClick = { gestureRefreshTrigger++ },
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(imageVector = Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Refresh")
                        }
                    }
                }
            }

            // --- ASSISTANT & VOICE ---
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = EchoDarkSurface)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "ASSISTANT & VOICE",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = EchoAccent
                    )

                    // Voice responses toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Voice responses",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = EchoTextPrimary
                            )
                            Text(
                                text = "Speak action summaries aloud upon completion",
                                style = MaterialTheme.typography.bodySmall,
                                color = EchoTextSecondary
                            )
                        }
                        Switch(
                            checked = voiceResponsesEnabled,
                            onCheckedChange = onSetVoiceResponses
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    // Voice reminders toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Voice reminders",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = EchoTextPrimary
                            )
                            Text(
                                text = "Speak reminders aloud when they trigger",
                                style = MaterialTheme.typography.bodySmall,
                                color = EchoTextSecondary
                            )
                        }
                        Switch(
                            checked = voiceRemindersEnabled,
                            onCheckedChange = onSetVoiceReminders
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    // Speech rate
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Speech rate",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = EchoTextPrimary
                            )
                            Text(
                                text = "%.2fx".format(speechRate),
                                style = MaterialTheme.typography.bodySmall,
                                color = EchoAccent,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Slider(
                            value = speechRate,
                            onValueChange = onSetSpeechRate,
                            valueRange = 0.6f..1.6f,
                            steps = 9,
                            colors = SliderDefaults.colors(
                                thumbColor = EchoAccent,
                                activeTrackColor = EchoAccent
                            )
                        )
                    }

                    OutlinedButton(
                        onClick = onTestVoice,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(imageVector = Icons.Outlined.VolumeUp, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Test Voice")
                    }
                }
            }

            // --- CALENDAR SELECTION ---
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = EchoDarkSurface)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "CALENDAR",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = EchoAccent
                        )
                        IconButton(
                            onClick = onRefreshCalendars,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = "Refresh Calendars",
                                tint = EchoAccent,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Text(
                        text = "Default calendar",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = EchoTextPrimary
                    )

                    if (availableCalendars.isEmpty()) {
                        Text(
                            text = "No writable calendars discovered. Ensure Calendar permission is granted.",
                            style = MaterialTheme.typography.bodySmall,
                            color = EchoTextTertiary
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            availableCalendars.forEach { cal ->
                                val isSelected = selectedCalendarId == cal.id
                                val providerName = when {
                                    cal.accountType.equals("com.google", ignoreCase = true) -> "Google Calendar"
                                    cal.accountType.isNullOrBlank() || cal.accountType.contains("local", ignoreCase = true) -> "Device Calendar"
                                    else -> cal.accountType ?: "Calendar"
                                }

                                Surface(
                                    onClick = { onSelectCalendar(cal.id, cal.accountName, cal.displayName) },
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isSelected) EchoAccent.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                                    border = if (isSelected) BorderStroke(1.dp, EchoAccent) else null,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = isSelected,
                                            onClick = null,
                                            colors = RadioButtonDefaults.colors(selectedColor = EchoAccent)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = cal.accountName ?: cal.displayName ?: "Calendar ID ${cal.id}",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                color = if (isSelected) EchoAccent else EchoTextPrimary
                                            )
                                            Text(
                                                text = "$providerName • ${cal.displayName ?: ""}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = EchoTextSecondary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // --- DEVELOPER VERIFICATION SUITE ---
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = EchoDarkSurface)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "DEVELOPER VERIFICATION",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = EchoAccent
                    )

                    Text(
                        text = "Execute direct tests bypassing AI parsing to physically verify device subsystems:",
                        style = MaterialTheme.typography.bodySmall,
                        color = EchoTextSecondary
                    )

                    Button(
                        onClick = onCreateTestCalendar,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = EchoCalendar.copy(alpha = 0.85f))
                    ) {
                        Icon(imageVector = Icons.Outlined.CalendarToday, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Create Calendar Test Event (5 min from now)")
                    }

                    Button(
                        onClick = onScheduleTestReminder,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = EchoReminder.copy(alpha = 0.85f))
                    ) {
                        Icon(imageVector = Icons.Outlined.Notifications, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Test Reminder in 60 seconds")
                    }

                    Button(
                        onClick = onTestAmbient,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = EchoSecondary.copy(alpha = 0.85f))
                    ) {
                        Icon(imageVector = Icons.Outlined.GraphicEq, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Test Ambient Overlay")
                    }
                }
            }

            Spacer(modifier = Modifier.height(30.dp))
        }
    }
}

@Composable
fun PermissionRow(
    name: String,
    granted: Boolean,
    onRequest: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = EchoTextPrimary
            )
            Text(
                text = if (granted) "Granted" else "Not granted",
                style = MaterialTheme.typography.bodySmall,
                color = if (granted) EchoSuccess else EchoWarning
            )
        }

        if (!granted) {
            TextButton(onClick = onRequest) {
                Text("Grant", color = EchoAccent, fontWeight = FontWeight.Bold)
            }
        } else {
            Icon(
                imageVector = Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = EchoSuccess,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

private fun isPermissionGranted(context: Context, permission: String): Boolean {
    return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
