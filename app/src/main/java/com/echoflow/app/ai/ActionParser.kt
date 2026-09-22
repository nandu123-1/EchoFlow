package com.echoflow.app.ai

import android.util.Log
import com.echoflow.app.domain.model.*
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Safely parses and validates LLM JSON output into ActionGraph.
 *
 * Handles malformed JSON, missing fields, invalid values, and type coercion.
 * Never crashes — returns Result.failure on parse errors so the system
 * can fall back to FallbackAiEngine.
 */
object ActionParser {

    private const val TAG = "EchoFlow/Parser"
    private val gson = Gson()

    /**
     * Parse raw JSON string from LLM into an ActionGraph.
     * Applies validation to every parsed action.
     */
    fun parse(
        jsonString: String,
        originalInput: String,
        context: UserContext
    ): Result<ActionGraph> {
        return try {
            // Clean the JSON — LLMs sometimes wrap in markdown code blocks
            val cleaned = cleanJson(jsonString)
            Log.d(TAG, "[PARSER] Cleaned JSON: ${cleaned.take(200)}...")

            val root = JsonParser.parseString(cleaned).asJsonObject

            val actionsArray = when {
                root.has("actions") -> root.getAsJsonArray("actions")
                root.has("action") -> root.getAsJsonArray("action")
                else -> throw IllegalArgumentException("No 'actions' array in JSON")
            }

            val actions = mutableListOf<ParsedAction>()

            for (i in 0 until actionsArray.size()) {
                val actionJson = actionsArray[i].asJsonObject
                val action = parseAction(actionJson, context)
                if (action != null) {
                    val validated = validateAction(action, originalInput)
                    actions.add(validated)
                }
            }

            if (actions.isEmpty()) {
                return Result.failure(IllegalStateException("No valid actions parsed from JSON"))
            }

            val overallConfidence = actions.map { it.confidence }.average().toFloat()

            Result.success(
                ActionGraph(
                    originalInput = originalInput,
                    actions = actions,
                    overallConfidence = overallConfidence,
                    sourceEngine = "Qwen2.5-1.5B",
                    requestCreatedAt = context.requestCreatedAt.toEpochMilli(),
                    deviceTimezone = context.timeZoneId
                )
            )
        } catch (e: JsonSyntaxException) {
            Log.e(TAG, "[PARSER] Invalid JSON syntax", e)
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "[PARSER] Parse error", e)
            Result.failure(e)
        }
    }

    /** Overload for backwards compatibility */
    fun parse(
        jsonString: String,
        originalInput: String,
        currentDateTime: LocalDateTime
    ): Result<ActionGraph> {
        val context = UserContext(
            requestCreatedAt = currentDateTime.atZone(java.time.ZoneId.systemDefault()).toInstant(),
            timeZone = java.time.ZoneId.systemDefault(),
            currentDateTime = currentDateTime
        )
        return parse(jsonString, originalInput, context)
    }

    private fun cleanJson(raw: String): String {
        var json = raw.trim()
        // Remove markdown code fences
        if (json.startsWith("```")) {
            json = json.removePrefix("```json").removePrefix("```")
        }
        if (json.endsWith("```")) {
            json = json.removeSuffix("```")
        }
        json = json.trim()

        // If the response starts with text before JSON, extract JSON
        val braceStart = json.indexOf('{')
        val braceEnd = json.lastIndexOf('}')
        if (braceStart >= 0 && braceEnd > braceStart) {
            json = json.substring(braceStart, braceEnd + 1)
        }

        return json
    }

    private fun parseAction(json: JsonObject, context: UserContext): ParsedAction? {
        return try {
            val typeStr = json.getStringOrNull("type") ?: return null
            val rawType = try {
                ActionType.valueOf(typeStr.uppercase())
            } catch (e: IllegalArgumentException) {
                ActionType.UNKNOWN
            }
            // Map legacy/model NOTE type to REMINDER in V5
            val type = if (rawType == ActionType.NOTE) ActionType.REMINDER else rawType

            val title = json.getStringOrNull("title") ?: "Untitled action"
            val timeExpression = json.getStringOrNull("timeExpression")
                ?: json.getStringOrNull("time_expression")
            val dateStr = json.getStringOrNull("date")
            val timeStr = json.getStringOrNull("time")

            // Deterministic time resolution relative to requestCreatedAt
            val resolved = com.echoflow.app.domain.time.TimeResolver.resolve(
                rawExpression = timeExpression,
                dateStr = dateStr,
                timeStr = timeStr,
                requestCreatedAt = context.requestCreatedAt,
                zoneId = context.timeZone,
                referenceForPastCheck = context.requestCreatedAt
            )

            val executionMode = when (type) {
                ActionType.CALL -> ExecutionMode.PROACTIVE_CONFIRMATION
                ActionType.REMINDER -> ExecutionMode.SCHEDULED
                ActionType.CALENDAR -> ExecutionMode.SCHEDULED
                ActionType.MESSAGE -> if (resolved.resolvedEpochMillis != null) ExecutionMode.SCHEDULED else ExecutionMode.IMMEDIATE
                ActionType.NOTE -> ExecutionMode.SCHEDULED
                ActionType.UNKNOWN -> ExecutionMode.IMMEDIATE
            }

            val requiresConfirmation = (json.getBoolOrNull("requiresConfirmation")
                ?: json.getBoolOrNull("requires_confirmation") ?: false) || resolved.isPastTime

            ParsedAction(
                type = type,
                title = title,
                description = json.getStringOrNull("description"),
                recipient = json.getStringOrNull("recipient"),
                recipientPhone = json.getStringOrNull("recipientPhone") ?: json.getStringOrNull("phone"),
                executionMode = executionMode,
                timeExpression = timeExpression,
                requestedTime = resolved.requestedTimeDisplay,
                dateTime = resolved.resolvedDateTime,
                resolvedEpochMillis = resolved.resolvedEpochMillis,
                isPastTime = resolved.isPastTime,
                durationMinutes = json.getIntOrNull("durationMinutes") ?: json.getIntOrNull("duration_minutes"),
                message = json.getStringOrNull("message"),
                confidence = json.getFloatOrNull("confidence") ?: 0.7f,
                requiresConfirmation = requiresConfirmation,
                ambiguityReason = if (resolved.isPastTime) "Scheduled time has already passed"
                    else (json.getStringOrNull("ambiguityReason") ?: json.getStringOrNull("ambiguity_reason"))
            )
        } catch (e: Exception) {
            Log.w(TAG, "[PARSER] Failed to parse action element", e)
            null
        }
    }

    /**
     * Resolve date and time strings into LocalDateTime.
     * Handles: "tomorrow", "2026-09-22", "today", relative dates.
     */
    private fun resolveDateTime(
        dateStr: String?,
        timeStr: String?,
        now: LocalDateTime
    ): LocalDateTime? {
        if (dateStr == null && timeStr == null) return null

        val date = when {
            dateStr == null -> now.toLocalDate()
            dateStr.equals("today", ignoreCase = true) -> now.toLocalDate()
            dateStr.equals("tomorrow", ignoreCase = true) -> now.toLocalDate().plusDays(1)
            dateStr.contains("next", ignoreCase = true) -> now.toLocalDate().plusWeeks(1)
            else -> try {
                LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE)
            } catch (e: DateTimeParseException) {
                now.toLocalDate()
            }
        }

        val time = when {
            timeStr == null -> null
            else -> try {
                LocalTime.parse(timeStr, DateTimeFormatter.ofPattern("HH:mm"))
            } catch (e: DateTimeParseException) {
                try {
                    LocalTime.parse(timeStr, DateTimeFormatter.ofPattern("H:mm"))
                } catch (e2: DateTimeParseException) {
                    null
                }
            }
        }

        return if (time != null) LocalDateTime.of(date, time) else date?.atTime(9, 0)
    }

    /**
     * Post-parse validation: clamp confidence, verify required fields per type.
     */
    internal fun validateAction(action: ParsedAction, originalInput: String = ""): ParsedAction {
        var updated = action.copy(
            confidence = action.confidence.coerceIn(0f, 1f)
        )

        // Type-specific validation
        when (action.type) {
            ActionType.MESSAGE -> {
                if (action.recipient == null) {
                    updated = updated.copy(
                        requiresConfirmation = true,
                        ambiguityReason = updated.ambiguityReason ?: "Recipient not specified"
                    )
                }

                var cleanMsg = action.message?.trim()?.trimEnd(',', '.', ';')?.trim()
                cleanMsg = cleanMsg?.replace(Regex("""^(?:saying|that)\s*""", RegexOption.IGNORE_CASE), "")?.trim()

                val hasCommandPrefix = cleanMsg != null && Regex(
                    """^(?:message\s+to|send\s+(?:a\s+)?message|text\s+|call\s+|remind\s+)""",
                    RegexOption.IGNORE_CASE
                ).containsMatchIn(cleanMsg)

                val isEqualToOriginal = cleanMsg != null && cleanMsg.equals(originalInput.trim(), ignoreCase = true)

                if (cleanMsg.isNullOrBlank() || isEqualToOriginal || hasCommandPrefix) {
                    updated = updated.copy(
                        message = null,
                        requiresConfirmation = true,
                        ambiguityReason = updated.ambiguityReason ?: "Message content unclear"
                    )
                } else {
                    updated = updated.copy(
                        message = cleanMsg.replaceFirstChar { it.uppercase() }
                    )
                }
            }
            ActionType.CALL -> {
                if (action.recipient == null && action.recipientPhone == null) {
                    updated = updated.copy(
                        requiresConfirmation = true,
                        ambiguityReason = updated.ambiguityReason ?: "Recipient to call not specified"
                    )
                }
            }
            ActionType.CALENDAR -> {
                if (action.dateTime == null) {
                    updated = updated.copy(
                        requiresConfirmation = true,
                        ambiguityReason = updated.ambiguityReason ?: "Date/time not specified"
                    )
                }
                if ((action.durationMinutes ?: 0) <= 0) {
                    updated = updated.copy(durationMinutes = 60) // Default 1 hour
                }
            }
            ActionType.REMINDER -> {
                // Reminders without a time are still valid, just less specific
            }
            else -> { /* No special validation */ }
        }

        return updated
    }

    // --- JSON helper extensions ---

    private fun JsonObject.getStringOrNull(key: String): String? =
        if (has(key) && !get(key).isJsonNull) get(key).asString.takeIf { it.isNotBlank() } else null

    private fun JsonObject.getIntOrNull(key: String): Int? =
        if (has(key) && !get(key).isJsonNull) try { get(key).asInt } catch (e: Exception) { null } else null

    private fun JsonObject.getFloatOrNull(key: String): Float? =
        if (has(key) && !get(key).isJsonNull) try { get(key).asFloat } catch (e: Exception) { null } else null

    private fun JsonObject.getBoolOrNull(key: String): Boolean? =
        if (has(key) && !get(key).isJsonNull) try { get(key).asBoolean } catch (e: Exception) { null } else null
}
