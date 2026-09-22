package com.echoflow.app.ai

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Builds the system and user prompts for the Qwen2.5-1.5B-Instruct model.
 *
 * The prompt enforces:
 * - JSON-only output
 * - Strict action type vocabulary
 * - No action execution
 * - Explicit ambiguity marking
 */
object PromptBuilder {

    private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd (EEEE)")
    private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")

    /**
     * The system prompt that constrains Qwen's output to structured JSON.
     * Compressed to drastically minimize prompt-prefill latency and token generation.
     */
    fun buildSystemPrompt(): String = """
Extract productivity actions from user input into JSON.
Format:
{"actions":[{"type":"CALENDAR|REMINDER|MESSAGE|CALL|UNKNOWN","title":"...","description":"...","recipient":"...","timeExpression":"...","date":"YYYY-MM-DD","time":"HH:MM","durationMinutes":60,"message":"...","confidence":0.9,"requiresConfirmation":false,"ambiguityReason":"..."}]}

RULES:
1. Return ONLY compact valid JSON with "actions" array. No markdown, no explanations.
2. Omit null or empty fields from action objects to keep output compact.
3. Types: CALENDAR, REMINDER, MESSAGE, CALL.
4. Extract raw time phrases in "timeExpression" (e.g. "after 5 minutes", "tomorrow at 4pm", "at 4:15", "at 5").
5. For CALL: type is CALL, recipient is person/contact to call.
6. If recipient or time is ambiguous, set "requiresConfirmation":true and describe in "ambiguityReason".
7. For MESSAGE: recipient is contact name. "message" must contain ONLY the actual message payload to send (content following "that", "saying", or "tell them"). NEVER include command words, recipient name, or scheduling time in "message".
""".trimIndent()

    /**
     * Format the user message with current context concisely.
     */
    fun buildUserPrompt(input: String, currentDateTime: LocalDateTime): String {
        val dateStr = currentDateTime.format(DATE_FORMAT)
        val timeStr = currentDateTime.format(TIME_FORMAT)

        return "Now: $dateStr $timeStr\nInput: \"$input\"\nJSON:"
    }

    /**
     * Build the full prompt in chat format for Qwen instruct model.
     */
    fun buildFullPrompt(input: String, currentDateTime: LocalDateTime): String {
        return """<|im_start|>system
${buildSystemPrompt()}<|im_end|>
<|im_start|>user
${buildUserPrompt(input, currentDateTime)}<|im_end|>
<|im_start|>assistant
"""
    }
}
