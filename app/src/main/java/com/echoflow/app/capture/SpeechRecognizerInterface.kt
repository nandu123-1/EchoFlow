package com.echoflow.app.capture

/**
 * Abstraction for speech recognition, isolating the app from any
 * specific STT implementation.
 *
 * Implementations:
 *  - [SystemSpeechRecognizer]: Android's built-in SpeechRecognizer API
 *  - Future: LocalSpeechRecognizer (on-device Whisper, etc.)
 */
interface SpeechRecognizerInterface {
    /** Start listening and return the transcribed text */
    suspend fun transcribe(): Result<String>

    /** Check if speech recognition is available */
    fun isAvailable(): Boolean

    /** Cancel any ongoing recognition */
    fun cancel()
}
