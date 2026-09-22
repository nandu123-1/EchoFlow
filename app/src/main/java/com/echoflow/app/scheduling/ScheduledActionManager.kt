package com.echoflow.app.scheduling

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.echoflow.app.assistant.SpeechOutputManager
import com.echoflow.app.data.db.EchoFlowDatabase
import com.echoflow.app.data.prefs.AssistantPreferences
import com.echoflow.app.domain.model.ActionType
import com.echoflow.app.domain.model.ExecutionMode
import com.echoflow.app.domain.model.ExecutionState
import com.echoflow.app.domain.model.ParsedAction
import com.echoflow.app.execution.CallConfirmationReceiver
import com.echoflow.app.execution.ReminderExecutor
import com.echoflow.app.execution.ReminderReceiver
import com.echoflow.app.execution.ScheduledMessageReceiver
import com.echoflow.app.notification.NotificationPaletteManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * Centralized scheduler and lifecycle manager for all scheduled EchoFlow actions.
 *
 * Responsibilities:
 * - Directs all AlarmManager registrations for Reminders, Calls, and Scheduled Messages.
 * - Enforces distinct single-action cancellation ([cancelAction]) vs workflow-level ([cancelAll]).
 * - Performs in-place modifications for Snooze ([snoozeAction]) preserving action identity.
 * - Tracks actual scheduling drift against target epochs ([recordDrift]).
 * - Keeps the ongoing Notification Action Palette synchronized in real time.
 * - Restores pending actions after application process restart or device reboot.
 */
class ScheduledActionManager(private val context: Context) {

    companion object {
        private const val TAG = "EchoFlow-Scheduler"

        const val EXTRA_ACTION_ID = "com.echoflow.app.extra.ACTION_ID"
        const val EXTRA_SCHEDULED_EPOCH = "com.echoflow.app.extra.SCHEDULED_EPOCH"
        const val EXTRA_RECIPIENT = "com.echoflow.app.extra.RECIPIENT"
        const val EXTRA_PHONE = "com.echoflow.app.extra.PHONE"
        const val EXTRA_MESSAGE_BODY = "com.echoflow.app.extra.MESSAGE_BODY"

        @Volatile
        private var INSTANCE: ScheduledActionManager? = null

        fun getInstance(context: Context): ScheduledActionManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ScheduledActionManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val database = EchoFlowDatabase.getInstance(context)
    private val historyDao = database.historyDao()
    private val paletteManager = NotificationPaletteManager.getInstance(context)
    private val speechOutputManager = SpeechOutputManager(context, AssistantPreferences(context))

    // In-memory cache of actively registered scheduled actions
    private val activeActions = ConcurrentHashMap<String, ParsedAction>()
    private val _activeActionsFlow = MutableStateFlow<List<ParsedAction>>(emptyList())
    val activeActionsFlow: StateFlow<List<ParsedAction>> = _activeActionsFlow.asStateFlow()

    private fun syncPendingState() {
        val currentList = activeActions.values.toList().sortedBy { it.resolvedEpochMillis ?: Long.MAX_VALUE }
        _activeActionsFlow.value = currentList
        paletteManager.updatePalette(currentList)
    }

    data class ScheduleResult(
        val success: Boolean,
        val message: String,
        val scheduledEpoch: Long? = null,
        val delayMillis: Long? = null
    )

    /**
     * Schedule a ParsedAction deterministically via AlarmManager.
     */
    fun scheduleAction(action: ParsedAction): ScheduleResult {
        val nowEpoch = System.currentTimeMillis()
        val targetEpoch = action.resolvedEpochMillis
            ?: action.dateTime?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
            ?: (nowEpoch + 3600_000L)

        val delayMillis = targetEpoch - nowEpoch

        Log.i(TAG, """
            [SCHEDULE]
            id=${action.id}
            type=${action.type}
            title=${action.title}
            requested=${action.requestedTime}
            now=${Instant.ofEpochMilli(nowEpoch)}
            targetEpoch=$targetEpoch (${Instant.ofEpochMilli(targetEpoch)})
            delayMillis=${delayMillis}ms
        """.trimIndent())

        if (targetEpoch <= nowEpoch) {
            val pastMsg = "Target time has already passed (${action.requestedTime ?: action.dateTime})"
            Log.w(TAG, "[SCHEDULE REJECTED] $pastMsg")
            return ScheduleResult(false, pastMsg)
        }

        val pendingIntent = createPendingIntent(action, targetEpoch)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, targetEpoch, pendingIntent)
                } else {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, targetEpoch, pendingIntent)
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, targetEpoch, pendingIntent)
            }

            val scheduledAction = action.copy(
                resolvedEpochMillis = targetEpoch,
                executionState = ExecutionState.SCHEDULED
            )
            activeActions[action.id] = scheduledAction

            // Update live notification palette and reactive flow
            syncPendingState()

            return ScheduleResult(
                success = true,
                message = "${action.type} scheduled for ${action.requestedTime ?: formatEpoch(targetEpoch)}",
                scheduledEpoch = targetEpoch,
                delayMillis = delayMillis
            )
        } catch (e: Exception) {
            Log.e(TAG, "[SCHEDULE FAILED] Error registering alarm for ${action.id}", e)
            return ScheduleResult(false, "Failed to schedule alarm: ${e.message}")
        }
    }

    /**
     * Cancel a single scheduled action by ID (distinct from cancelAll).
     */
    suspend fun cancelAction(actionId: String): Boolean = withContext(Dispatchers.IO) {
        val action = activeActions[actionId]
        Log.i(TAG, "[CANCEL SINGLE] Cancelling action $actionId ('${action?.title}')")

        // Cancel AlarmManager PendingIntent
        val dummyAction = action ?: ParsedAction(id = actionId, type = ActionType.REMINDER, title = "", confidence = 1.0f)
        val pendingIntent = createPendingIntent(dummyAction, 0L)
        alarmManager.cancel(pendingIntent)

        // Remove from memory
        activeActions.remove(actionId)

        // Update Room DB
        historyDao.cancelAction(actionId)

        // Update real-time Notification Palette and flow
        syncPendingState()

        action?.let {
            speechOutputManager.speak("Cancelled ${it.title}.")
        }

        true
    }

    /**
     * Cancel all active pending scheduled actions across the workflow.
     */
    suspend fun cancelAll(): Int = withContext(Dispatchers.IO) {
        val count = activeActions.size
        Log.i(TAG, "[CANCEL ALL] Cancelling all $count pending action(s)")

        for ((id, action) in activeActions) {
            try {
                val pendingIntent = createPendingIntent(action, 0L)
                alarmManager.cancel(pendingIntent)
            } catch (e: Exception) {
                Log.w(TAG, "[CANCEL ALL] Error cancelling alarm for $id", e)
            }
        }

        activeActions.clear()

        // Update Room database
        historyDao.cancelAllPendingActions()

        // Dismiss live notification palette and update flow
        syncPendingState()

        // Spoken acknowledgement
        speechOutputManager.speak("I've cancelled the remaining actions.")

        count
    }

    /**
     * Snooze an action in-place by modifying the existing record.
     * Prevents duplicate orphan actions and preserves history integrity.
     */
    suspend fun snoozeAction(actionId: String, snoozeMinutes: Int = 5): ScheduleResult = withContext(Dispatchers.IO) {
        Log.i(TAG, "[SNOOZE] Snoozing action $actionId for $snoozeMinutes minutes")

        val existing = activeActions[actionId] ?: historyDao.getActionById(actionId)?.let { entity ->
            ParsedAction(
                id = entity.id,
                type = try { ActionType.valueOf(entity.actionType) } catch (_: Exception) { ActionType.REMINDER },
                title = entity.title,
                description = entity.description,
                recipient = entity.recipient,
                recipientPhone = entity.recipientPhone,
                executionMode = try { ExecutionMode.valueOf(entity.executionMode ?: "") } catch (_: Exception) { ExecutionMode.PROACTIVE_CONFIRMATION },
                message = entity.message,
                confidence = entity.confidence,
                executionState = ExecutionState.SCHEDULED
            )
        }

        if (existing == null) {
            Log.w(TAG, "[SNOOZE REJECTED] Action $actionId not found")
            return@withContext ScheduleResult(false, "Action not found")
        }

        val newEpoch = System.currentTimeMillis() + (snoozeMinutes * 60_000L)
        val newTimeDisplay = Instant.ofEpochMilli(newEpoch)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("h:mm a"))

        val updatedAction = existing.copy(
            resolvedEpochMillis = newEpoch,
            requestedTime = "in $snoozeMinutes minutes ($newTimeDisplay)",
            executionState = ExecutionState.SCHEDULED
        )

        // Reschedule alarm
        val res = scheduleAction(updatedAction)

        // Update database in-place
        historyDao.updateActionSchedule(
            actionId = actionId,
            newEpochMillis = newEpoch,
            requestedTime = updatedAction.requestedTime,
            resolvedTime = newTimeDisplay
        )

        syncPendingState()

        res
    }

    /**
     * Measure and record actual scheduling drift against target epoch.
     */
    fun recordDrift(actionId: String, targetEpochMillis: Long): Long {
        val actualEpoch = System.currentTimeMillis()
        val driftMs = actualEpoch - targetEpochMillis
        Log.i("EchoFlow-Drift", "[SCHEDULE DRIFT] Action $actionId: target=$targetEpochMillis, actual=$actualEpoch, drift=${driftMs}ms")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                historyDao.updateActionDrift(actionId, driftMs)
            } catch (e: Exception) {
                Log.w(TAG, "[DRIFT] Could not update drift in DB", e)
            }
        }
        return driftMs
    }

    /**
     * Mark an action as completed or in progress when its alarm fires.
     */
    fun onActionTriggered(actionId: String) {
        activeActions.remove(actionId)
        syncPendingState()
    }

    /**
     * Look up a currently active scheduled action.
     */
    fun getAction(actionId: String): ParsedAction? = activeActions[actionId]

    /**
     * Returns currently pending scheduled actions.
     */
    fun getPendingActions(): List<ParsedAction> {
        return activeActions.values.toList().sortedBy { it.resolvedEpochMillis ?: Long.MAX_VALUE }
    }

    /**
     * Restores pending scheduled actions after device reboot or app process restart.
     */
    fun restoreScheduledActionsAfterRestart() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.i(TAG, "[RESTORE] Restoring scheduled actions from Room DB...")
                val pendingEntities = historyDao.getPendingActions()
                val now = System.currentTimeMillis()

                var restoredCount = 0
                for (entity in pendingEntities) {
                    val epoch = entity.resolvedEpochMillis
                    if (epoch != null && epoch > now) {
                        val type = try { ActionType.valueOf(entity.actionType) } catch (_: Exception) { ActionType.REMINDER }
                        val mode = try { ExecutionMode.valueOf(entity.executionMode ?: "") } catch (_: Exception) { ExecutionMode.SCHEDULED }

                        val action = ParsedAction(
                            id = entity.id,
                            type = type,
                            title = entity.title,
                            description = entity.description,
                            recipient = entity.recipient,
                            recipientPhone = entity.recipientPhone,
                            executionMode = mode,
                            timeExpression = entity.timeExpression,
                            requestedTime = entity.requestedTime,
                            resolvedEpochMillis = epoch,
                            confidence = entity.confidence,
                            executionState = ExecutionState.SCHEDULED
                        )

                        scheduleAction(action)
                        restoredCount++
                    } else if (epoch != null && epoch <= now) {
                        // Action expired while device was off
                        historyDao.updateActionExecutionState(entity.id, ExecutionState.FAILED.name, "Missed while device was powered off")
                    }
                }

                Log.i(TAG, "[RESTORE] Restored $restoredCount scheduled action(s)")
                syncPendingState()
            } catch (e: Exception) {
                Log.e(TAG, "[RESTORE FAILED] Could not restore scheduled actions", e)
            }
        }
    }

    private fun createPendingIntent(action: ParsedAction, targetEpoch: Long): PendingIntent {
        val requestCode = action.id.hashCode()

        val intent = when (action.type) {
            ActionType.CALL -> Intent(context, CallConfirmationReceiver::class.java).apply {
                putExtra(EXTRA_ACTION_ID, action.id)
                putExtra(EXTRA_RECIPIENT, action.recipient ?: "Contact")
                putExtra(EXTRA_PHONE, action.recipientPhone ?: "")
                putExtra(EXTRA_SCHEDULED_EPOCH, targetEpoch)
            }
            ActionType.MESSAGE -> Intent(context, ScheduledMessageReceiver::class.java).apply {
                putExtra(EXTRA_ACTION_ID, action.id)
                putExtra(EXTRA_RECIPIENT, action.recipient ?: "Contact")
                putExtra(EXTRA_PHONE, action.recipientPhone ?: "")
                putExtra(EXTRA_MESSAGE_BODY, action.message ?: "")
                putExtra(EXTRA_SCHEDULED_EPOCH, targetEpoch)
            }
            else -> Intent(context, ReminderReceiver::class.java).apply {
                putExtra(ReminderExecutor.EXTRA_TITLE, action.title)
                putExtra(ReminderExecutor.EXTRA_DESCRIPTION, action.description ?: action.title)
                putExtra(ReminderExecutor.EXTRA_ID, requestCode)
                putExtra(EXTRA_ACTION_ID, action.id)
                putExtra(EXTRA_SCHEDULED_EPOCH, targetEpoch)
            }
        }

        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun formatEpoch(epoch: Long): String {
        return Instant.ofEpochMilli(epoch)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("h:mm a"))
    }
}
