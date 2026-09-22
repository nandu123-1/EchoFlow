package com.echoflow.app.execution

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
 * BroadcastReceiver that fires when an AlarmManager reminder triggers.
 * - Posts a rich notification with action buttons (Start Now, Snooze 10m, Dismiss).
 * - Best-effort proactive TTS audio reminder adhering to audio safety & user preferences.
 */
class ReminderReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "EchoFlow-Reminder"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra(ReminderExecutor.EXTRA_TITLE) ?: "EchoFlow Reminder"
        val description = intent.getStringExtra(ReminderExecutor.EXTRA_DESCRIPTION) ?: ""
        val id = intent.getIntExtra(ReminderExecutor.EXTRA_ID, System.currentTimeMillis().toInt())
        val actionId = intent.getStringExtra(ScheduledActionManager.EXTRA_ACTION_ID)
        val targetEpoch = intent.getLongExtra(ScheduledActionManager.EXTRA_SCHEDULED_EPOCH, 0L)

        val driftMs = if (actionId != null && targetEpoch > 0) {
            ScheduledActionManager.getInstance(context).recordDrift(actionId, targetEpoch)
        } else 0L

        if (actionId != null) {
            ScheduledActionManager.getInstance(context).onActionTriggered(actionId)
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    EchoFlowDatabase.getInstance(context).historyDao().updateActionExecutionState(
                        actionId = actionId,
                        state = ExecutionState.COMPLETED.name,
                        message = "Reminder fired (drift: ${driftMs}ms)"
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "[REMINDER TRIGGER] Could not update DB state", e)
                }
            }
        }

        Log.i(TAG, "[REMINDER] Triggered for: '$title' (id=$id, drift=${driftMs}ms)")

        // 1. 5-second vibration pattern (respecting device ringer mode)
        vibrateDevice(context)

        // 2. Mandatory Notification with Action Buttons
        postNotification(context, id, title, description)

        // 3. Best-Effort Proactive Spoken Audio
        attemptSpokenReminder(context, title)
    }

    private fun vibrateDevice(context: Context) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val ringerMode = audioManager?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
        if (ringerMode == AudioManager.RINGER_MODE_SILENT) return

        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            vibrator?.let {
                // Waveform for ~5 seconds total: 5 bursts of 600ms vibrate + 400ms pause
                val timings = longArrayOf(0, 600, 400, 600, 400, 600, 400, 600, 400, 600)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    it.vibrate(VibrationEffect.createWaveform(timings, -1))
                } else {
                    @Suppress("DEPRECATION")
                    it.vibrate(timings, -1)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "[REMINDER] Vibration error", e)
        }
    }

    private fun postNotification(context: Context, id: Int, title: String, description: String) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Content intent (tap notification -> open MainActivity)
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context, id, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action: Start Now
        val startNowIntent = Intent(context, ReminderActionReceiver::class.java).apply {
            action = ReminderActionReceiver.ACTION_START_NOW
            putExtra(ReminderActionReceiver.EXTRA_NOTIFICATION_ID, id)
            putExtra(ReminderActionReceiver.EXTRA_REMINDER_TITLE, title)
        }
        val startNowPendingIntent = PendingIntent.getBroadcast(
            context, id * 10 + 1, startNowIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action: Snooze 10m
        val snoozeIntent = Intent(context, ReminderActionReceiver::class.java).apply {
            action = ReminderActionReceiver.ACTION_SNOOZE
            putExtra(ReminderActionReceiver.EXTRA_NOTIFICATION_ID, id)
            putExtra(ReminderActionReceiver.EXTRA_REMINDER_TITLE, title)
            putExtra(ReminderActionReceiver.EXTRA_REMINDER_DESC, description)
        }
        val snoozePendingIntent = PendingIntent.getBroadcast(
            context, id * 10 + 2, snoozeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action: Dismiss
        val dismissIntent = Intent(context, ReminderActionReceiver::class.java).apply {
            action = ReminderActionReceiver.ACTION_DISMISS
            putExtra(ReminderActionReceiver.EXTRA_NOTIFICATION_ID, id)
        }
        val dismissPendingIntent = PendingIntent.getBroadcast(
            context, id * 10 + 3, dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, ReminderExecutor.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setColor(0xFF0B3D91.toInt())
            .setContentTitle(title)
            .setContentText(description.ifBlank { "You asked me to remind you about $title." })
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent)
            .addAction(android.R.drawable.ic_media_play, "Start Now", startNowPendingIntent)
            .addAction(android.R.drawable.ic_lock_idle_alarm, "Snooze 10m", snoozePendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Dismiss", dismissPendingIntent)
            .build()

        try {
            notificationManager.notify(id, notification)
            Log.d(TAG, "[REMINDER] Posted actionable notification (id=$id)")
        } catch (e: SecurityException) {
            Log.e(TAG, "[REMINDER] Cannot post notification — permission denied", e)
        }
    }

    private fun attemptSpokenReminder(context: Context, title: String) {
        val prefs = AssistantPreferences(context)
        if (!prefs.isVoiceRemindersEnabled()) {
            Log.i("EchoFlow-TTS", "[TTS] disabled: Voice reminders disabled in user settings")
            return
        }

        // Safety check: Audio ringer mode
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audioManager != null && audioManager.ringerMode != AudioManager.RINGER_MODE_NORMAL) {
            Log.i("EchoFlow-TTS", "[TTS] blocked: Device is in silent/vibrate mode (ringerMode=${audioManager.ringerMode})")
            return
        }

        val spokenText = "Hey, you asked me to remind you about $title. It's time to start."
        val pendingResult = goAsync()

        var ttsInstance: TextToSpeech? = null
        val handler = Handler(Looper.getMainLooper())

        // Timeout watchdog: ensure pendingResult is released within 8 seconds
        val timeoutRunnable = Runnable {
            try {
                ttsInstance?.stop()
                ttsInstance?.shutdown()
            } catch (e: Exception) {
                // ignore
            }
            try {
                pendingResult.finish()
            } catch (e: Exception) {
                // ignore
            }
        }
        handler.postDelayed(timeoutRunnable, 8000L)

        ttsInstance = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                try {
                    ttsInstance?.setLanguage(Locale.getDefault())
                    val utteranceId = UUID.randomUUID().toString()
                    ttsInstance?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) {
                            Log.i("EchoFlow-TTS", "[TTS] spoken: '$spokenText'")
                        }

                        override fun onDone(id: String?) {
                            handler.removeCallbacks(timeoutRunnable)
                            try {
                                ttsInstance?.shutdown()
                            } catch (e: Exception) { /* ignore */ }
                            try {
                                pendingResult.finish()
                            } catch (e: Exception) { /* ignore */ }
                        }

                        override fun onError(id: String?) {
                            Log.w("EchoFlow-TTS", "[TTS] failed during playback")
                            handler.removeCallbacks(timeoutRunnable)
                            try {
                                ttsInstance?.shutdown()
                            } catch (e: Exception) { /* ignore */ }
                            try {
                                pendingResult.finish()
                            } catch (e: Exception) { /* ignore */ }
                        }
                    })
                    ttsInstance?.speak(spokenText, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                } catch (e: Exception) {
                    Log.e("EchoFlow-TTS", "[TTS] failed: Exception during speak()", e)
                    handler.removeCallbacks(timeoutRunnable)
                    try {
                        pendingResult.finish()
                    } catch (ignored: Exception) {}
                }
            } else {
                Log.w("EchoFlow-TTS", "[TTS] unavailable: Initialization failed with status $status")
                handler.removeCallbacks(timeoutRunnable)
                try {
                    pendingResult.finish()
                } catch (ignored: Exception) {}
            }
        }
    }
}
