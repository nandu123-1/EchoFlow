package com.echoflow.app.execution

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.echoflow.app.domain.model.ExecutionState
import com.echoflow.app.domain.model.ParsedAction
import java.time.ZoneId

/**
 * Schedules reminders using AlarmManager + Notification.
 *
 * This approach works across Android versions without depending on
 * device-specific reminder APIs. When the alarm fires, [ReminderReceiver]
 * posts a notification.
 */
class ReminderExecutor(private val context: Context) {

    companion object {
        private const val TAG = "EchoFlow/Reminder"
        const val CHANNEL_ID = "echoflow_reminders"
        const val CHANNEL_NAME = "EchoFlow Reminders"
        const val EXTRA_TITLE = "reminder_title"
        const val EXTRA_DESCRIPTION = "reminder_description"
        const val EXTRA_ID = "reminder_id"
    }

    init {
        createNotificationChannel()
    }

    suspend fun execute(action: ParsedAction): ExecutionResult {
        Log.d(TAG, "[REMINDER] Scheduling via ScheduledActionManager: ${action.title}")

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.POST_NOTIFICATIONS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                return ExecutionResult(ExecutionState.FAILED, "Notification permission denied")
            }

            // Centralized scheduling via ScheduledActionManager (Rule ③)
            val scheduler = com.echoflow.app.scheduling.ScheduledActionManager.getInstance(context)
            val result = scheduler.scheduleAction(action)

            if (result.success) {
                ExecutionResult(
                    state = ExecutionState.SCHEDULED,
                    message = result.message
                )
            } else {
                ExecutionResult(
                    state = ExecutionState.FAILED,
                    message = result.message
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "[REMINDER] Failed to schedule reminder", e)
            ExecutionResult(
                state = ExecutionState.FAILED,
                message = "Reminder scheduling failed: ${e.message}"
            )
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Reminders from EchoFlow workflows"
            enableVibration(true)
        }

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }
}
