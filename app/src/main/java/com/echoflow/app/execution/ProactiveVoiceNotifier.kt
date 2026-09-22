package com.echoflow.app.execution

import android.content.BroadcastReceiver
import android.content.Context
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
import com.echoflow.app.data.prefs.AssistantPreferences
import java.util.Locale
import java.util.UUID

/**
 * Unified proactive voice response and attention signal helper for EchoFlow background receivers.
 *
 * Ensures:
 * 1. Configured device vibration attention pattern respecting system ringer mode.
 * 2. Asynchronous TTS spoken responses in BroadcastReceivers using goAsync() with watchdog timer.
 * 3. Consistent spoken audio reporting across Reminders, Calls, and Messages.
 */
object ProactiveVoiceNotifier {

    private const val TAG = "EchoFlow-VoiceNotifier"

    /**
     * Produces the configured EchoFlow attention vibration pattern:
     * ~5 seconds total: 5 bursts of 600ms vibration separated by 400ms pauses.
     * Respects device ringer mode (silent mode suppresses vibration).
     */
    fun vibrateAttention(context: Context) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val ringerMode = audioManager?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
        if (ringerMode == AudioManager.RINGER_MODE_SILENT) {
            Log.d(TAG, "[VIBRATE] Suppressed: device in silent mode")
            return
        }

        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            vibrator?.let {
                val timings = longArrayOf(0, 600, 400, 600, 400, 600, 400, 600, 400, 600)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    it.vibrate(VibrationEffect.createWaveform(timings, -1))
                } else {
                    @Suppress("DEPRECATION")
                    it.vibrate(timings, -1)
                }
                Log.d(TAG, "[VIBRATE] Attention pattern triggered")
            }
        } catch (e: Exception) {
            Log.w(TAG, "[VIBRATE] Error executing vibration", e)
        }
    }

    /**
     * Speaks the given text asynchronously inside a BroadcastReceiver lifecycle.
     * Uses pendingResult to keep the receiver process active until TTS finishes.
     */
    fun speakAsync(
        context: Context,
        text: String,
        pendingResult: BroadcastReceiver.PendingResult? = null
    ) {
        val prefs = AssistantPreferences(context)
        if (!prefs.isVoiceResponsesEnabled()) {
            Log.i(TAG, "[TTS] Suppressed: Voice responses disabled in settings")
            pendingResult?.finish()
            return
        }

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audioManager != null && audioManager.ringerMode != AudioManager.RINGER_MODE_NORMAL) {
            Log.i(TAG, "[TTS] Suppressed: Device in silent/vibrate mode (${audioManager.ringerMode})")
            pendingResult?.finish()
            return
        }

        var ttsInstance: TextToSpeech? = null
        val handler = Handler(Looper.getMainLooper())

        val timeoutRunnable = Runnable {
            try {
                ttsInstance?.stop()
                ttsInstance?.shutdown()
            } catch (e: Exception) { /* ignore */ }
            try {
                pendingResult?.finish()
            } catch (e: Exception) { /* ignore */ }
        }
        handler.postDelayed(timeoutRunnable, 8000L)

        ttsInstance = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                try {
                    ttsInstance?.setLanguage(Locale.getDefault())
                    ttsInstance?.setSpeechRate(prefs.getSpeechRate())

                    val utteranceId = UUID.randomUUID().toString()
                    ttsInstance?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) {
                            Log.i(TAG, "[TTS] Speaking: '$text'")
                        }

                        override fun onDone(id: String?) {
                            handler.removeCallbacks(timeoutRunnable)
                            try {
                                ttsInstance?.shutdown()
                            } catch (e: Exception) { /* ignore */ }
                            try {
                                pendingResult?.finish()
                            } catch (e: Exception) { /* ignore */ }
                        }

                        override fun onError(id: String?) {
                            Log.w(TAG, "[TTS] Playback failed for: '$text'")
                            handler.removeCallbacks(timeoutRunnable)
                            try {
                                ttsInstance?.shutdown()
                            } catch (e: Exception) { /* ignore */ }
                            try {
                                pendingResult?.finish()
                            } catch (e: Exception) { /* ignore */ }
                        }
                    })

                    val bundle = android.os.Bundle().apply {
                        putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
                    }
                    ttsInstance?.speak(text, TextToSpeech.QUEUE_FLUSH, bundle, utteranceId)
                } catch (e: Exception) {
                    Log.e(TAG, "[TTS] Error initiating speech", e)
                    handler.removeCallbacks(timeoutRunnable)
                    try {
                        ttsInstance?.shutdown()
                    } catch (ex: Exception) { /* ignore */ }
                    try {
                        pendingResult?.finish()
                    } catch (ex: Exception) { /* ignore */ }
                }
            } else {
                Log.w(TAG, "[TTS] Initialization failed with status: $status")
                handler.removeCallbacks(timeoutRunnable)
                try {
                    pendingResult?.finish()
                } catch (e: Exception) { /* ignore */ }
            }
        }
    }
}
