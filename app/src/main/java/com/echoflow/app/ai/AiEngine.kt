package com.echoflow.app.ai

import com.echoflow.app.domain.model.ActionGraph
import com.echoflow.app.domain.model.UserContext

/**
 * Core AI abstraction that isolates the rest of the application from
 * any specific LLM or inference runtime.
 *
 * Implementations:
 *  - [LocalQwenAiEngine]: Qwen2.5-1.5B-Instruct via llama.cpp GGUF
 *  - [FallbackAiEngine]: Deterministic regex/keyword parser
 *
 * The application never calls Qwen or llama.cpp directly.
 */
interface AiEngine {

    /** Human-readable name for logging and UI status display */
    val engineName: String

    /**
     * Parse a natural language productivity input into structured actions.
     *
     * @param input The raw user text (may be from voice transcription)
     * @param context Temporal and contact context for resolving "tomorrow", names, etc.
     * @return Result wrapping an ActionGraph on success, or an error
     */
    suspend fun parseIntent(
        input: String,
        context: UserContext
    ): Result<ActionGraph>
}
