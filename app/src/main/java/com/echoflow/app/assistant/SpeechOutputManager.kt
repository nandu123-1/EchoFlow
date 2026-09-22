package com.echoflow.app.assistant

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.echoflow.app.data.prefs.AssistantPreferences
import java.util.Locale
import java.util.UUID

/**
 * Manages text-to-speech feedback for EchoFlow Assistant.
 * Maintains a single persistent TextToSpeech instance to avoid latency and resource leaks.
 */
class SpeechOutputManager(
    private val context: Context,
    private val preferences: AssistantPreferences
) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "EchoFlow-TTS"
    }

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private val pendingUtteranceCallbacks = mutableMapOf<String, () -> Unit>()

    init {
        try {
            tts = TextToSpeech(context.applicationContext, this)
        } catch (e: Exception) {
            Log.e(TAG, "[TTS] Error creating TextToSpeech instance", e)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.getDefault())
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "[TTS] Default locale not supported, falling back to US English")
                tts?.setLanguage(Locale.US)
            }
            tts?.setSpeechRate(preferences.getSpeechRate())
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    Log.d(TAG, "[TTS] Started speaking: $utteranceId")
                }

                override fun onDone(utteranceId: String?) {
                    Log.d(TAG, "[TTS] Finished speaking: $utteranceId")
                    utteranceId?.let { id ->
                        val callback = synchronized(pendingUtteranceCallbacks) {
                            pendingUtteranceCallbacks.remove(id)
                        }
                        callback?.invoke()
                    }
                }

                override fun onError(utteranceId: String?) {
                    Log.e(TAG, "[TTS] Error speaking: $utteranceId")
                    utteranceId?.let { id ->
                        val callback = synchronized(pendingUtteranceCallbacks) {
                            pendingUtteranceCallbacks.remove(id)
                        }
                        callback?.invoke()
                    }
                }
            })
            isInitialized = true
            Log.i(TAG, "[TTS] TextToSpeech initialized successfully")
        } else {
            Log.e(TAG, "[TTS] Initialization failed with status code: $status")
            isInitialized = false
        }
    }

    /**
     * Speaks the given text if voice responses are enabled.
     * @param onDone optional callback triggered when utterance completes.
     */
    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (!preferences.isVoiceResponsesEnabled()) {
            Log.d(TAG, "[TTS] Voice responses disabled in settings. Skipping: '$text'")
            onDone?.invoke()
            return
        }

        if (!isInitialized || tts == null) {
            Log.w(TAG, "[TTS] Engine not ready, skipping speech: '$text'")
            onDone?.invoke()
            return
        }

        try {
            tts?.setSpeechRate(preferences.getSpeechRate())
            val utteranceId = UUID.randomUUID().toString()
            if (onDone != null) {
                synchronized(pendingUtteranceCallbacks) {
                    pendingUtteranceCallbacks[utteranceId] = onDone
                }
            }

            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            }

            Log.i(TAG, "[TTS] Speaking: '$text'")
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        } catch (e: Exception) {
            Log.e(TAG, "[TTS] Exception during speak()", e)
            onDone?.invoke()
        }
    }

    fun stop() {
        try {
            if (isSpeaking()) {
                tts?.stop()
                Log.d(TAG, "[TTS] Speech stopped by request")
            }
            synchronized(pendingUtteranceCallbacks) {
                pendingUtteranceCallbacks.clear()
            }
        } catch (e: Exception) {
            Log.w(TAG, "[TTS] Error stopping speech", e)
        }
    }

    fun isSpeaking(): Boolean {
        return try {
            tts?.isSpeaking == true
        } catch (e: Exception) {
            false
        }
    }

    fun release() {
        try {
            stop()
            tts?.shutdown()
            tts = null
            isInitialized = false
            Log.d(TAG, "[TTS] TextToSpeech released")
        } catch (e: Exception) {
            Log.w(TAG, "[TTS] Error releasing TextToSpeech", e)
        }
    }
}
