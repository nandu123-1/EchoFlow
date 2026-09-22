package com.echoflow.app.execution

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import com.echoflow.app.MainActivity
import com.echoflow.app.data.db.EchoFlowDatabase
import com.echoflow.app.data.prefs.AssistantPreferences
import com.echoflow.app.domain.model.ExecutionState
import com.echoflow.app.scheduling.ScheduledActionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

/**
 * Proactive Call Confirmation Receiver.
 *
 * CRITICAL SAFETY PRINCIPLE:
 * EchoFlow must NEVER silently initiate or place a phone call automatically.
 *
 * At the scheduled time:
 * 1. Measures and logs exact scheduling drift.
 * 2. Prompts user proactively via a high-priority heads-up notification with
 *    [ Call ], [ Snooze 5 Min ], and [ Cancel ] action buttons.
 * 3. Best-effort audio TTS ("It's time to call Rahul. Do you want to call now?").
 * 4. Only when user explicitly taps [ Call ] is the permitted Android dial/call flow launched.
 */
class CallConfirmationReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "EchoFlow-CallConfirm"
        const val CHANNEL_ID = "echoflow_calls"
        const val CHANNEL_NAME = "EchoFlow Calls"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val actionId = intent.getStringExtra(ScheduledActionManager.EXTRA_ACTION_ID) ?: UUID.randomUUID().toString()
        val recipient = intent.getStringExtra(ScheduledActionManager.EXTRA_RECIPIENT) ?: "Contact"
        val phone = intent.getStringExtra(ScheduledActionManager.EXTRA_PHONE) ?: ""
        val targetEpoch = intent.getLongExtra(ScheduledActionManager.EXTRA_SCHEDULED_EPOCH, System.currentTimeMillis())

        val driftMs = ScheduledActionManager.getInstance(context).recordDrift(actionId, targetEpoch)
        Log.i(TAG, "[PROACTIVE CALL TRIGGERED] Calling '$recipient' ($phone). Drift: ${driftMs}ms")

        // Update database state to WAITING_FOR_USER
        CoroutineScope(Dispatchers.IO).launch {
            try {
                EchoFlowDatabase.getInstance(context).historyDao().updateActionExecutionState(
                    actionId = actionId,
                    state = ExecutionState.WAITING_FOR_USER.name,
                    message = "Waiting for user confirmation to call $recipient (drift: ${driftMs}ms)"
                )
            } catch (e: Exception) {
                Log.w(TAG, "[CALL TRIGGER] Could not update DB state", e)
            }
        }

        // 1. Mandatory Proactive Heads-Up Notification with Action Buttons
        postCallNotification(context, actionId, recipient, phone)

        // 2. Best-Effort Proactive Spoken Audio Prompt
        attemptSpokenPrompt(context, recipient)
    }

    private fun postCallNotification(context: Context, actionId: String, recipient: String, phone: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createCallChannel(notificationManager)

        val notifId = actionId.hashCode()

        // Content intent (tap notification body -> opens app)
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context, notifId, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action 1: [ Call ]
        val callIntent = Intent(context, CallActionReceiver::class.java).apply {
            action = CallActionReceiver.ACTION_CALL_NOW
            putExtra(CallActionReceiver.EXTRA_ACTION_ID, actionId)
            putExtra(CallActionReceiver.EXTRA_RECIPIENT, recipient)
            putExtra(CallActionReceiver.EXTRA_PHONE, phone)
            putExtra(CallActionReceiver.EXTRA_NOTIF_ID, notifId)
        }
        val callPendingIntent = PendingIntent.getBroadcast(
            context, notifId * 10 + 1, callIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action 2: [ Snooze 5 Min ]
        val snoozeIntent = Intent(context, CallActionReceiver::class.java).apply {
            action = CallActionReceiver.ACTION_SNOOZE_CALL
            putExtra(CallActionReceiver.EXTRA_ACTION_ID, actionId)
            putExtra(CallActionReceiver.EXTRA_RECIPIENT, recipient)
            putExtra(CallActionReceiver.EXTRA_PHONE, phone)
            putExtra(CallActionReceiver.EXTRA_NOTIF_ID, notifId)
        }
        val snoozePendingIntent = PendingIntent.getBroadcast(
            context, notifId * 10 + 2, snoozeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action 3: [ Cancel ]
        val cancelIntent = Intent(context, CallActionReceiver::class.java).apply {
            action = CallActionReceiver.ACTION_CANCEL_CALL
            putExtra(CallActionReceiver.EXTRA_ACTION_ID, actionId)
            putExtra(CallActionReceiver.EXTRA_NOTIF_ID, notifId)
        }
        val cancelPendingIntent = PendingIntent.getBroadcast(
            context, notifId * 10 + 3, cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setColor(0xFF0B3D91.toInt())
            .setContentTitle("📞 EchoFlow")
            .setContentText("It's time to call $recipient. Do you want to call now?")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent)
            .addAction(android.R.drawable.sym_action_call, "Call", callPendingIntent)
            .addAction(android.R.drawable.ic_popup_reminder, "Snooze 5m", snoozePendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPendingIntent)
            .build()

        try {
            notificationManager.notify(notifId, notification)
            Log.d(TAG, "[CALL NOTIF] Posted proactive notification for $recipient")
        } catch (e: SecurityException) {
            Log.w(TAG, "[CALL NOTIF] Notification permission denied", e)
        }
    }

    private fun attemptSpokenPrompt(context: Context, recipient: String) {
        val prefs = AssistantPreferences(context)
        if (!prefs.isVoiceRemindersEnabled()) {
            Log.d(TAG, "[CALL TTS] Voice reminders disabled in preferences, skipping TTS")
            return
        }

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (audioManager.ringerMode != AudioManager.RINGER_MODE_NORMAL) {
            Log.d(TAG, "[CALL TTS] Device muted/vibrate, skipping audio prompt")
            return
        }

        var tts: TextToSpeech? = null
        val promptText = "It's time to call $recipient. Do you want to call now?"

        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS && tts != null) {
                tts?.language = Locale.US
                tts?.setSpeechRate(prefs.getSpeechRate())

                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        Handler(Looper.getMainLooper()).post {
                            tts?.stop()
                            tts?.shutdown()
                        }
                    }
                    override fun onError(utteranceId: String?) {
                        Handler(Looper.getMainLooper()).post {
                            tts?.stop()
                            tts?.shutdown()
                        }
                    }
                })

                val params = android.os.Bundle().apply {
                    putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_NOTIFICATION)
                }
                tts?.speak(promptText, TextToSpeech.QUEUE_FLUSH, params, "call_prompt_${System.currentTimeMillis()}")
            } else {
                tts?.shutdown()
            }
        }
    }

    private fun createCallChannel(notificationManager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Proactive call confirmation notifications"
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }
}
