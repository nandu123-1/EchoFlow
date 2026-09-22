package com.echoflow.app.execution

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.echoflow.app.MainActivity

/**
 * Handles action clicks from reminder notifications:
 * - START NOW: Launches EchoFlow assistant session with ready-to-help prompt.
 * - SNOOZE: Reschedules reminder for +10 minutes.
 * - DISMISS: Cancels notification.
 */
class ReminderActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "EchoFlow-Reminder"

        const val ACTION_START_NOW = "com.echoflow.app.action.REMINDER_START_NOW"
        const val ACTION_SNOOZE = "com.echoflow.app.action.REMINDER_SNOOZE"
        const val ACTION_DISMISS = "com.echoflow.app.action.REMINDER_DISMISS"

        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
        const val EXTRA_REMINDER_TITLE = "extra_reminder_title"
        const val EXTRA_REMINDER_DESC = "extra_reminder_desc"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        val title = intent.getStringExtra(EXTRA_REMINDER_TITLE) ?: "Reminder"
        val desc = intent.getStringExtra(EXTRA_REMINDER_DESC) ?: ""

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(notificationId)

        when (action) {
            ACTION_START_NOW -> {
                Log.i(TAG, "[REMINDER ACTION] User tapped 'Start Now' for: $title")
                try {
                    val launchIntent = Intent(context, MainActivity::class.java).apply {
                        this.action = Intent.ACTION_MAIN
                        addCategory(Intent.CATEGORY_LAUNCHER)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        putExtra("start_reminder_followup", true)
                        putExtra("reminder_title", title)
                    }
                    context.startActivity(launchIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "[REMINDER ACTION] Could not launch MainActivity", e)
                }
            }

            ACTION_SNOOZE -> {
                Log.i(TAG, "[REMINDER ACTION] User snoozed '$title' for 10 minutes")
                snoozeReminder(context, title, desc, notificationId)
            }

            ACTION_DISMISS -> {
                Log.i(TAG, "[REMINDER ACTION] User dismissed notification: $title")
            }
        }
    }

    private fun snoozeReminder(context: Context, title: String, description: String, notificationId: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val snoozeTimeMs = System.currentTimeMillis() + 10 * 60 * 1000L // 10 minutes

        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(ReminderExecutor.EXTRA_TITLE, title)
            putExtra(ReminderExecutor.EXTRA_DESCRIPTION, description)
            putExtra(ReminderExecutor.EXTRA_ID, notificationId)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, snoozeTimeMs, pendingIntent)
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, snoozeTimeMs, pendingIntent)
            }
            Log.d(TAG, "[REMINDER] Successfully snoozed '$title' until $snoozeTimeMs")
        } catch (e: Exception) {
            Log.e(TAG, "[REMINDER] Failed to snooze alarm", e)
        }
    }
}
