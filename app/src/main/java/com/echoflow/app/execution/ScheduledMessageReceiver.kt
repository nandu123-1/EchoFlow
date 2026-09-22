package com.echoflow.app.execution

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.echoflow.app.MainActivity
import com.echoflow.app.data.db.EchoFlowDatabase
import com.echoflow.app.domain.model.ExecutionState
import com.echoflow.app.notification.PaletteActionReceiver
import com.echoflow.app.scheduling.ScheduledActionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * BroadcastReceiver triggered by AlarmManager when a scheduled message time arrives.
 *
 * In V5:
 * If SEND_SMS permission is granted and phone number is available,
 * auto-sends the SMS via SmsManager and tracks real delivery confirmation.
 *
 * If SEND_SMS permission is not granted or phone number is missing,
 * falls back cleanly to the notification with [ Open & Send ] button that opens the SMS composer.
 */
class ScheduledMessageReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "EchoFlow-SchedMsg"
        const val CHANNEL_ID = "echoflow_messages"
        const val CHANNEL_NAME = "EchoFlow Scheduled Messages"
        const val ACTION_OPEN_COMPOSER = "com.echoflow.app.execution.ACTION_OPEN_COMPOSER"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val actionId = intent.getStringExtra(ScheduledActionManager.EXTRA_ACTION_ID) ?: UUID.randomUUID().toString()
        val recipient = intent.getStringExtra(ScheduledActionManager.EXTRA_RECIPIENT) ?: "Contact"
        var phone = intent.getStringExtra(ScheduledActionManager.EXTRA_PHONE)?.trim()
        val messageBody = intent.getStringExtra(ScheduledActionManager.EXTRA_MESSAGE_BODY) ?: ""
        val targetEpoch = intent.getLongExtra(ScheduledActionManager.EXTRA_SCHEDULED_EPOCH, System.currentTimeMillis())

        val driftMs = ScheduledActionManager.getInstance(context).recordDrift(actionId, targetEpoch)
        Log.i(TAG, "[SCHEDULED MESSAGE TRIGGERED] Message for '$recipient'. Drift: ${driftMs}ms")

        // 1. Configured attention vibration signal
        ProactiveVoiceNotifier.vibrateAttention(context)

        // If phone wasn't in intent, attempt contact lookup
        if (phone.isNullOrBlank()) {
            phone = ContactsHelper.lookupPhoneNumberByName(context, recipient)
        }

        val hasSmsPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED

        if (hasSmsPermission && !phone.isNullOrBlank()) {
            // Real background auto-send via SmsManager
            autoSendSms(context, actionId, recipient, phone, messageBody, driftMs)
        } else {
            // Graceful fallback to composer notification
            fallbackToComposerNotification(context, actionId, recipient, phone ?: recipient, messageBody, driftMs)
        }

        ScheduledActionManager.getInstance(context).onActionTriggered(actionId)
    }

    private fun autoSendSms(
        context: Context,
        actionId: String,
        recipient: String,
        phone: String,
        messageBody: String,
        driftMs: Long
    ) {
        try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }

            val sentIntent = PendingIntent.getBroadcast(
                context,
                actionId.hashCode() * 10 + 3,
                Intent(context, SmsDeliveryReceiver::class.java).apply {
                    action = SmsDeliveryReceiver.ACTION_SMS_SENT
                    putExtra(SmsDeliveryReceiver.EXTRA_ACTION_ID, actionId)
                    putExtra(SmsDeliveryReceiver.EXTRA_RECIPIENT, recipient)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val deliveredIntent = PendingIntent.getBroadcast(
                context,
                actionId.hashCode() * 10 + 4,
                Intent(context, SmsDeliveryReceiver::class.java).apply {
                    action = SmsDeliveryReceiver.ACTION_SMS_DELIVERED
                    putExtra(SmsDeliveryReceiver.EXTRA_ACTION_ID, actionId)
                    putExtra(SmsDeliveryReceiver.EXTRA_RECIPIENT, recipient)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            smsManager.sendTextMessage(phone, null, messageBody, sentIntent, deliveredIntent)
            Log.i(TAG, "[SMS AUTO-SENT] Sent SMS to $recipient ($phone): '$messageBody'")

            // Update database to SUCCESS
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    EchoFlowDatabase.getInstance(context).historyDao().updateActionExecutionState(
                        actionId = actionId,
                        state = ExecutionState.SUCCESS.name,
                        message = "SMS sent to $recipient ($phone) (drift: ${driftMs}ms)"
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "[SCHEDULED MESSAGE] Could not update DB state", e)
                }
            }

            postSentConfirmationNotification(context, actionId, recipient, phone, messageBody)
        } catch (e: Exception) {
            Log.e(TAG, "[SMS AUTO-SEND FAILED] Error dispatching SMS to $recipient", e)
            fallbackToComposerNotification(context, actionId, recipient, phone, messageBody, driftMs)
        }
    }

    private fun postSentConfirmationNotification(
        context: Context,
        actionId: String,
        recipient: String,
        phone: String,
        messageBody: String
    ) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createMessageChannel(notificationManager)

        val notifId = actionId.hashCode()
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context, notifId, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setColor(0xFF0B3D91.toInt())
            .setContentTitle("✓ SMS Sent")
            .setContentText("To $recipient ($phone): \"$messageBody\"")
            .setStyle(NotificationCompat.BigTextStyle().bigText("SMS successfully sent to $recipient ($phone):\n\"$messageBody\""))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent)
            .build()

        try {
            notificationManager.notify(notifId, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "[MSG NOTIF] Notification permission denied", e)
        }
    }

    private fun fallbackToComposerNotification(
        context: Context,
        actionId: String,
        recipient: String,
        address: String,
        messageBody: String,
        driftMs: Long
    ) {
        // Update database state to PREPARED
        CoroutineScope(Dispatchers.IO).launch {
            try {
                EchoFlowDatabase.getInstance(context).historyDao().updateActionExecutionState(
                    actionId = actionId,
                    state = ExecutionState.PREPARED.name,
                    message = "Message prepared for $recipient (drift: ${driftMs}ms)"
                )
            } catch (e: Exception) {
                Log.w(TAG, "[SCHEDULED MESSAGE] Could not update DB state", e)
            }
        }

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createMessageChannel(notificationManager)

        val notifId = actionId.hashCode()

        val contentIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context, notifId, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Route [OPEN & SEND] through MessageActionReceiver to dismiss notification and update DB truthfully
        val openComposerIntent = Intent(context, MessageActionReceiver::class.java).apply {
            action = MessageActionReceiver.ACTION_OPEN_COMPOSER
            putExtra(MessageActionReceiver.EXTRA_ACTION_ID, actionId)
            putExtra(MessageActionReceiver.EXTRA_RECIPIENT, recipient)
            putExtra(MessageActionReceiver.EXTRA_PHONE, address)
            putExtra(MessageActionReceiver.EXTRA_MESSAGE_BODY, messageBody)
            putExtra(MessageActionReceiver.EXTRA_NOTIFICATION_ID, notifId)
        }
        val sendPendingIntent = PendingIntent.getBroadcast(
            context, notifId * 10 + 1, openComposerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = Intent(context, PaletteActionReceiver::class.java).apply {
            action = PaletteActionReceiver.ACTION_CANCEL_SINGLE
            putExtra(PaletteActionReceiver.EXTRA_ACTION_ID, actionId)
        }
        val cancelPendingIntent = PendingIntent.getBroadcast(
            context, notifId * 10 + 2, cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setColor(0xFF0B3D91.toInt())
            .setContentTitle("💬 EchoFlow")
            .setContentText("Message prepared for $recipient: \"$messageBody\"")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Message prepared for $recipient:\n\"$messageBody\""))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent)
            .addAction(android.R.drawable.ic_menu_send, "Open & Send", sendPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPendingIntent)
            .build()

        try {
            notificationManager.notify(notifId, notification)
            Log.d(TAG, "[MSG NOTIF] Fallback notification posted for message to $recipient")
        } catch (e: SecurityException) {
            Log.w(TAG, "[MSG NOTIF] Notification permission denied", e)
        }

        // Spoken audio feedback: "Your message to Rahul is ready to send."
        ProactiveVoiceNotifier.speakAsync(context, "Your message to $recipient is ready to send.")
    }

    private fun createMessageChannel(notificationManager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for scheduled messages ready to send"
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }
}

