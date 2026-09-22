package com.echoflow.app.capture

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Uses Android's built-in SpeechRecognizer for voice-to-text.
 *
 * Note: This may use cloud recognition depending on device.
 * The app does NOT claim fully offline STT unless a local engine is integrated.
 */
class SystemSpeechRecognizer(private val context: Context) : SpeechRecognizerInterface {

    companion object {
        private const val TAG = "EchoFlow/Voice"
    }

    private var recognizer: SpeechRecognizer? = null

    override fun isAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(context)
    }

    override suspend fun transcribe(): Result<String> {
        if (!isAvailable()) {
            return Result.failure(IllegalStateException("Speech recognition not available"))
        }

        return suspendCancellableCoroutine { continuation ->
            try {
                recognizer = SpeechRecognizer.createSpeechRecognizer(context)

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                }

                recognizer?.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        Log.d(TAG, "[VOICE] Ready for speech")
                    }

                    override fun onBeginningOfSpeech() {
                        Log.d(TAG, "[VOICE] Speech started")
                    }

                    override fun onRmsChanged(rmsdB: Float) {}

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        Log.d(TAG, "[VOICE] Speech ended")
                    }

                    override fun onError(error: Int) {
                        val errorMsg = when (error) {
                            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                            SpeechRecognizer.ERROR_CLIENT -> "Client side error"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required"
                            SpeechRecognizer.ERROR_NETWORK -> "Network error"
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                            SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized"
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                            SpeechRecognizer.ERROR_SERVER -> "Server error"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
                            else -> "Unknown error ($error)"
                        }
                        Log.e(TAG, "[VOICE] Error: $errorMsg")
                        if (continuation.isActive) {
                            continuation.resume(Result.failure(Exception(errorMsg)))
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull() ?: ""
                        Log.d(TAG, "[VOICE] Result: $text")
                        if (continuation.isActive) {
                            if (text.isNotBlank()) {
                                continuation.resume(Result.success(text))
                            } else {
                                continuation.resume(Result.failure(Exception("No speech recognized")))
                            }
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        Log.d(TAG, "[VOICE] Partial: ${partial?.firstOrNull()}")
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                recognizer?.startListening(intent)

                continuation.invokeOnCancellation {
                    cancel()
                }
            } catch (e: Exception) {
                Log.e(TAG, "[VOICE] Failed to start recognition", e)
                if (continuation.isActive) {
                    continuation.resume(Result.failure(e))
                }
            }
        }
    }

    override fun cancel() {
        try {
            recognizer?.stopListening()
            recognizer?.cancel()
            recognizer?.destroy()
            recognizer = null
        } catch (e: Exception) {
            Log.w(TAG, "[VOICE] Error cancelling recognition", e)
        }
    }
}
