package com.echoflow.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.echoflow.app.MainActivity
import com.echoflow.app.domain.model.ActionType
import com.echoflow.app.domain.model.ParsedAction
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Manages the real-time ongoing EchoFlow Notification Action Palette.
 *
 * This notification is the ambient mobile equivalent of the Echo Palette.
 * It is displayed whenever one or more actions are scheduled or pending.
 *
 * When actions complete, cancel, snooze, or are added, this notification
 * dynamically updates in real time. It is automatically dismissed when 0
 * actions are pending.
 */
class NotificationPaletteManager(private val context: Context) {

    companion object {
        private const val TAG = "EchoFlow-PaletteNotif"
        const val CHANNEL_ID = "echoflow_palette"
        const val CHANNEL_NAME = "EchoFlow Action Palette"
        const val NOTIFICATION_ID = 1001

        private val TIME_FORMATTER = DateTimeFormatter.ofPattern("h:mm a")

        @Volatile
        private var INSTANCE: NotificationPaletteManager? = null

        fun getInstance(context: Context): NotificationPaletteManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: NotificationPaletteManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createChannel()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW // Silent ongoing palette
            ).apply {
                description = "Ongoing view of scheduled EchoFlow actions"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Update the live Notification Palette with the list of pending actions.
     * If list is empty, cancels the notification.
     */
    fun updatePalette(pendingActions: List<ParsedAction>) {
        if (pendingActions.isEmpty()) {
            dismiss()
            return
        }

        val count = pendingActions.size
        val title = "✦ EchoFlow"
        val subtitle = "$count action${if (count > 1) "s" else ""} pending"

        // Open App Intent
        val openIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Cancel All Broadcast Intent
        val cancelAllIntent = Intent(context, PaletteActionReceiver::class.java).apply {
            action = PaletteActionReceiver.ACTION_CANCEL_ALL
        }
        val cancelAllPendingIntent = PendingIntent.getBroadcast(
            context,
            NOTIFICATION_ID + 1,
            cancelAllIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Single action intents for when exactly 1 action is pending
        val singleAction = pendingActions.firstOrNull()
        val snoozePendingIntent = singleAction?.let { item ->
            val snoozeIntent = Intent(context, PaletteActionReceiver::class.java).apply {
                action = PaletteActionReceiver.ACTION_SNOOZE_SINGLE
                putExtra(PaletteActionReceiver.EXTRA_ACTION_ID, item.id)
            }
            PendingIntent.getBroadcast(
                context,
                NOTIFICATION_ID + 2,
                snoozeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val cancelSinglePendingIntent = singleAction?.let { item ->
            val cancelIntent = Intent(context, PaletteActionReceiver::class.java).apply {
                action = PaletteActionReceiver.ACTION_CANCEL_SINGLE
                putExtra(PaletteActionReceiver.EXTRA_ACTION_ID, item.id)
            }
            PendingIntent.getBroadcast(
                context,
                NOTIFICATION_ID + 3,
                cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        // Build InboxStyle list of actions
        val inboxStyle = NotificationCompat.InboxStyle()
            .setBigContentTitle(title)
            .setSummaryText(subtitle)

        for (action in pendingActions.take(5)) {
            val iconPrefix = when (action.type) {
                ActionType.CALENDAR -> "📅"
                ActionType.REMINDER -> "🔔"
                ActionType.MESSAGE -> "💬"
                ActionType.CALL -> "📞"
                @Suppress("DEPRECATION")
                ActionType.NOTE -> "📝"
                ActionType.UNKNOWN -> "✦"
            }

            val timeDisplay = formatTime(action)
            val line = "$iconPrefix ${action.title} — $timeDisplay"
            inboxStyle.addLine(line)
        }

        if (pendingActions.size > 5) {
            inboxStyle.addLine("+ ${pendingActions.size - 5} more")
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setColor(0xFF0B3D91.toInt())
            .setContentTitle(title)
            .setContentText(subtitle)
            .setStyle(inboxStyle)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_menu_view, "Open", openPendingIntent)

        if (pendingActions.size == 1 && snoozePendingIntent != null && cancelSinglePendingIntent != null) {
            builder.addAction(android.R.drawable.ic_popup_reminder, "Snooze 5m", snoozePendingIntent)
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelSinglePendingIntent)
        } else {
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel All", cancelAllPendingIntent)
        }

        try {
            notificationManager.notify(NOTIFICATION_ID, builder.build())
            Log.d(TAG, "[PALETTE NOTIF] Updated with $count pending action(s)")
        } catch (e: SecurityException) {
            Log.w(TAG, "[PALETTE NOTIF] POST_NOTIFICATIONS permission not granted", e)
        }
    }

    /** Dismiss the ongoing notification when no actions remain. */
    fun dismiss() {
        notificationManager.cancel(NOTIFICATION_ID)
        Log.d(TAG, "[PALETTE NOTIF] Dismissed (0 actions pending)")
    }

    private fun formatTime(action: ParsedAction): String {
        action.requestedTime?.let { if (it.isNotBlank()) return it }
        action.timeExpression?.let { if (it.isNotBlank()) return it }
        action.resolvedEpochMillis?.let { epoch ->
            return Instant.ofEpochMilli(epoch)
                .atZone(ZoneId.systemDefault())
                .format(TIME_FORMATTER)
        }
        action.dateTime?.let { return it.format(TIME_FORMATTER) }
        return "Pending"
    }
}
