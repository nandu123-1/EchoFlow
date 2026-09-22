package com.echoflow.app.ai

import android.util.Log
import com.echoflow.app.domain.model.ActionGraph
import com.echoflow.app.domain.model.UserContext
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Local AI engine running Qwen2.5-1.5B-Instruct on-device via native llama.cpp / GGUF runtime.
 *
 * Implements full on-device local inference:
 *   GGUF file → Llama.loadModel() → PromptBuilder → Llama.complete() → ActionParser → ActionGraph.
 */
class LocalQwenAiEngine(
    private val modelManager: ModelManager
) : AiEngine {

    override val engineName: String = "Qwen2.5-1.5B Local"

    companion object {
        private const val TAG = "EchoFlow-Qwen"
    }

    private var activeModel: LlamaModel? = null

    /**
     * Check if this engine is ready for inference:
     * Model file exists on disk + runtime model loaded in memory.
     */
    fun isAvailable(): Boolean {
        return activeModel != null
    }

    /**
     * Load the GGUF model into memory using the native llama.cpp runtime.
     */
    suspend fun loadModel(): Result<Unit> = withContext(Dispatchers.IO) {
        if (!modelManager.isModelInstalled()) {
            val msg = "Model file not found at ${modelManager.getModelPath()}"
            Log.w(TAG, "[QWEN] $msg")
            modelManager.markError(msg)
            return@withContext Result.failure(IllegalStateException(msg))
        }

        try {
            modelManager.markLoading()
            val path = modelManager.getModelPath()
            val sizeMb = modelManager.getModelSizeMB()
            Log.d(TAG, "[QWEN] Loading model into native memory: '$path' ($sizeMb MB)...")

            val startTime = System.currentTimeMillis()
            val config = LlamaConfig(
                contextSize = 1024,
                threads = 6,
                temperature = 0.1f,
                topP = 0.9f
            )

            val model = Llama.loadModel(path, config)
            activeModel = model
            val loadTimeMs = System.currentTimeMillis() - startTime
            Log.i(TAG, "[QWEN] Model loaded successfully in ${loadTimeMs}ms. Native runtime active!")
            modelManager.markLoaded()
            Result.success(Unit)
        } catch (e: Throwable) {
            Log.e(TAG, "[QWEN] Native model load failed", e)
            activeModel = null
            modelManager.markError(e.message ?: "Failed to load model")
            Result.failure(e)
        }
    }

    /**
     * Unload model from memory and release native context.
     */
    fun unloadModel() {
        activeModel?.let { model ->
            try {
                Llama.releaseModel(model)
                Log.d(TAG, "[QWEN] Native model memory released")
            } catch (e: Exception) {
                Log.w(TAG, "[QWEN] Error releasing model memory", e)
            }
        }
        activeModel = null
        modelManager.markUnloaded()
    }

    override suspend fun parseIntent(
        input: String,
        context: UserContext
    ): Result<ActionGraph> = withContext(Dispatchers.IO) {
        val model = activeModel
        if (model == null) {
            return@withContext Result.failure(IllegalStateException("Qwen model is not loaded in memory"))
        }

        try {
            Log.d(TAG, "[QWEN] Starting on-device inference for prompt: '${input.take(80)}...'")
            val startTime = System.currentTimeMillis()

            val systemPrompt = PromptBuilder.buildSystemPrompt()
            val userPrompt = PromptBuilder.buildUserPrompt(input, context.currentDateTime)

            val result = Llama.complete(
                model = model,
                prompt = userPrompt,
                systemPrompt = systemPrompt,
                maxTokens = 384
            )

            val elapsedMs = System.currentTimeMillis() - startTime
            val rawOutput = result.text
            Log.d(TAG, "[QWEN] Inference finished in ${elapsedMs}ms (${result.tokensGenerated} tokens, ${result.tokensPerSecond} tok/s)")
            Log.d(TAG, "[QWEN] Raw LLM output:\n$rawOutput")

            val parsedGraph = ActionParser.parse(rawOutput, input, context)
            if (parsedGraph.isSuccess) {
                val graph = parsedGraph.getOrThrow().copy(sourceEngine = engineName)
                Log.i(TAG, "[QWEN] Successfully extracted ${graph.actions.size} action(s) via on-device Qwen!")
                Result.success(graph)
            } else {
                Log.w(TAG, "[QWEN] Output parsing failed: ${parsedGraph.exceptionOrNull()?.message}")
                parsedGraph
            }
        } catch (e: Throwable) {
            Log.e(TAG, "[QWEN] Exception during on-device inference", e)
            Result.failure(e)
        }
    }
}
