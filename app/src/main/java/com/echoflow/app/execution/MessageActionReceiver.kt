package com.echoflow.app.execution

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.echoflow.app.data.db.EchoFlowDatabase
import com.echoflow.app.domain.model.ExecutionState
import com.echoflow.app.notification.NotificationPaletteManager
import com.echoflow.app.scheduling.ScheduledActionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver that handles user interactions on scheduled message notifications.
 *
 * Specifically handles [ACTION_OPEN_COMPOSER]:
 * 1. Immediately dismisses the EchoFlow notification so it does not linger.
 * 2. Updates database state truthfully to PREPARED ("Message handed off to SMS app").
 * 3. Updates the live Notification Action Palette.
 * 4. Provides spoken feedback ("Your message to Rahul is ready.").
 * 5. Launches the system SMS composer populated with the actual message body and contact.
 */
class MessageActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "EchoFlow-MsgAction"
        const val ACTION_OPEN_COMPOSER = "com.echoflow.app.execution.ACTION_OPEN_COMPOSER"
        const val EXTRA_ACTION_ID = "extra_action_id"
        const val EXTRA_RECIPIENT = "extra_recipient"
        const val EXTRA_PHONE = "extra_phone"
        const val EXTRA_MESSAGE_BODY = "extra_message_body"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_OPEN_COMPOSER) return

        val actionId = intent.getStringExtra(EXTRA_ACTION_ID) ?: return
        val recipient = intent.getStringExtra(EXTRA_RECIPIENT) ?: "Contact"
        val phone = intent.getStringExtra(EXTRA_PHONE) ?: ""
        val messageBody = intent.getStringExtra(EXTRA_MESSAGE_BODY) ?: ""
        val notifId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, actionId.hashCode())

        Log.i(TAG, "[OPEN & SEND] Handing off message for '$recipient' (actionId=$actionId, notifId=$notifId)")

        // 1. Immediately cancel the notification so it disappears from the shade
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(notifId)
            Log.d(TAG, "[OPEN & SEND] Cancelled notification id=$notifId")
        } catch (e: Exception) {
            Log.w(TAG, "[OPEN & SEND] Error cancelling notification", e)
        }

        // 2. Asynchronously update database & notification palette
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                EchoFlowDatabase.getInstance(context).historyDao().updateActionExecutionState(
                    actionId = actionId,
                    state = ExecutionState.PREPARED.name,
                    message = "Message handed off to SMS app for $recipient"
                )
                // Notify ScheduledActionManager to update ongoing palette
                ScheduledActionManager.getInstance(context).onActionTriggered(actionId)
                Log.d(TAG, "[OPEN & SEND] DB state updated to PREPARED for actionId=$actionId")
            } catch (e: Exception) {
                Log.w(TAG, "[OPEN & SEND] Error updating database state", e)
            } finally {
                // 3. Spoken feedback: "Your message to Rahul is ready."
                ProactiveVoiceNotifier.speakAsync(
                    context = context,
                    text = "Your message to $recipient is ready.",
                    pendingResult = pendingResult
                )
            }
        }

        // 4. Launch SMS Composer with ONLY the extracted message body
        try {
            val smsUri = Uri.parse("smsto:$phone")
            val smsIntent = Intent(Intent.ACTION_SENDTO, smsUri).apply {
                putExtra("sms_body", messageBody)
                putExtra(Intent.EXTRA_TEXT, messageBody)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(smsIntent)
            Log.i(TAG, "[OPEN & SEND] Launched SMS composer for $phone with body: '$messageBody'")
        } catch (e: Exception) {
            Log.e(TAG, "[OPEN & SEND] Failed to launch SMS composer", e)
        }
    }
}
