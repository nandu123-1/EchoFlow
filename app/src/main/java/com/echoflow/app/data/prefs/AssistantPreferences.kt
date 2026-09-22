package com.echoflow.app.data.prefs

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists EchoFlow Assistant voice feedback and proactive reminder settings.
 */
class AssistantPreferences(context: Context) {

    companion object {
        private const val PREFS_NAME = "echoflow_assistant_prefs"
        private const val KEY_VOICE_RESPONSES = "voice_responses_enabled"
        private const val KEY_VOICE_REMINDERS = "voice_reminders_enabled"
        private const val KEY_SPEECH_RATE = "speech_rate"
        private const val KEY_SPEAK_LOCKED = "speak_when_locked"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _voiceResponsesEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_VOICE_RESPONSES, true)
    )
    val voiceResponsesEnabled: StateFlow<Boolean> = _voiceResponsesEnabled.asStateFlow()

    private val _voiceRemindersEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_VOICE_REMINDERS, true)
    )
    val voiceRemindersEnabled: StateFlow<Boolean> = _voiceRemindersEnabled.asStateFlow()

    private val _speechRate = MutableStateFlow(
        prefs.getFloat(KEY_SPEECH_RATE, 1.0f)
    )
    val speechRate: StateFlow<Float> = _speechRate.asStateFlow()

    private val _speakWhenLocked = MutableStateFlow(
        prefs.getBoolean(KEY_SPEAK_LOCKED, false)
    )
    val speakWhenLocked: StateFlow<Boolean> = _speakWhenLocked.asStateFlow()

    fun setVoiceResponsesEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_VOICE_RESPONSES, enabled).apply()
        _voiceResponsesEnabled.value = enabled
    }

    fun setVoiceRemindersEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_VOICE_REMINDERS, enabled).apply()
        _voiceRemindersEnabled.value = enabled
    }

    fun setSpeechRate(rate: Float) {
        val clamped = rate.coerceIn(0.5f, 2.0f)
        prefs.edit().putFloat(KEY_SPEECH_RATE, clamped).apply()
        _speechRate.value = clamped
    }

    fun setSpeakWhenLocked(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SPEAK_LOCKED, enabled).apply()
        _speakWhenLocked.value = enabled
    }

    fun isVoiceResponsesEnabled(): Boolean = prefs.getBoolean(KEY_VOICE_RESPONSES, true)
    fun isVoiceRemindersEnabled(): Boolean = prefs.getBoolean(KEY_VOICE_REMINDERS, true)
    fun getSpeechRate(): Float = prefs.getFloat(KEY_SPEECH_RATE, 1.0f)
    fun isSpeakWhenLocked(): Boolean = prefs.getBoolean(KEY_SPEAK_LOCKED, false)
}
