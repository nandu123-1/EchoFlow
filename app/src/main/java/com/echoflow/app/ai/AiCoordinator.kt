package com.echoflow.app.ai

import android.util.Log
import com.echoflow.app.domain.model.ActionGraph
import com.echoflow.app.domain.model.ModelState
import com.echoflow.app.domain.model.UserContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Orchestrates AI engine selection between on-device Qwen Local and deterministic Fallback.
 *
 * Honors user preferences, model readiness, and gracefully falls back to deterministic
 * parsing if inference or parsing fails, ensuring 100% truthful reporting of the active engine.
 */
class AiCoordinator(
    private val localEngine: LocalQwenAiEngine,
    private val fallbackEngine: FallbackAiEngine,
    private val modelManager: ModelManager
) {
    companion object {
        private const val TAG = "EchoFlow-AI"
    }

    val modelState: StateFlow<ModelState> = modelManager.modelState

    private val _preferQwen = MutableStateFlow(true)
    val preferQwen: StateFlow<Boolean> = _preferQwen.asStateFlow()

    var lastUsedEngine: String = fallbackEngine.engineName
        private set

    fun setPreferQwen(prefer: Boolean) {
        _preferQwen.value = prefer
        Log.d(TAG, "[AI] preferQwen updated to: $prefer")
    }

    /**
     * Parse intent using the best available engine.
     */
    suspend fun parseIntent(
        input: String,
        context: UserContext
    ): Result<ActionGraph> {
        Log.d(TAG, "[AI] Input received: '${input.take(80)}...'")

        // 1. Try local Qwen if preferred and available
        if (_preferQwen.value && localEngine.isAvailable()) {
            Log.d(TAG, "[AI] Attempting on-device Qwen inference...")
            val qwenResult = localEngine.parseIntent(input, context)

            if (qwenResult.isSuccess) {
                lastUsedEngine = localEngine.engineName
                Log.i(TAG, "[AI] Workflow parsed using on-device Qwen! (${qwenResult.getOrNull()?.actions?.size} actions)")
                return qwenResult
            }

            Log.w(TAG, "[AI] Qwen inference/parsing failed: ${qwenResult.exceptionOrNull()?.message}. Falling back to deterministic parser...")
        } else {
            if (!_preferQwen.value) {
                Log.d(TAG, "[AI] User opted for Fallback Parser")
            } else {
                Log.d(TAG, "[AI] Qwen not loaded in memory (Model state: ${modelManager.modelState.value}). Using Fallback Parser")
            }
        }

        // 2. Deterministic fallback parser
        Log.d(TAG, "[AI] Parsing with FallbackAiEngine...")
        lastUsedEngine = fallbackEngine.engineName
        return fallbackEngine.parseIntent(input, context)
    }

    suspend fun loadLocalModel(): Result<Unit> {
        return localEngine.loadModel()
    }

    fun unloadLocalModel() {
        localEngine.unloadModel()
    }

    fun isLocalModelAvailable(): Boolean = localEngine.isAvailable()

    fun initialize() {
        modelManager.initialize()
    }
}
