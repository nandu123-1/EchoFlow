package com.echoflow.app.ai

import android.util.Log
import com.echoflow.app.domain.model.*
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Deterministic fallback parser that uses regex and keyword matching
 * to decompose natural language into structured actions.
 *
 * This is the hackathon safety net — it produces the SAME ActionGraph format
 * as the Qwen model, ensuring the entire pipeline works even without the LLM.
 *
 * NOT labeled as "AI-powered" — this is explicitly fallback logic.
 */
class FallbackAiEngine : AiEngine {

    override val engineName: String = "Fallback Parser"

    companion object {
        private const val TAG = "EchoFlow/Fallback"

        // --- Calendar patterns ---
        private val CALENDAR_PATTERNS = listOf(
            Regex("""(?:schedule|block|book|set up|create|add)\s+(.+?)(?:\s+(?:for|to|on)\s+|$)""", RegexOption.IGNORE_CASE),
            Regex("""(?:block)\s+(\w+\s+(?:hour|minute|min)s?)\s+(?:for|to)\s+(.+?)(?:\s+(?:and|,)|$)""", RegexOption.IGNORE_CASE),
        )

        // --- Time patterns ---
        private val TIME_PATTERN = Regex("""(?:at\s+)?(\d{1,2})(?::(\d{2}))?\s*(am|pm|AM|PM)?""", RegexOption.IGNORE_CASE)
        private val DURATION_PATTERN = Regex("""(\w+|\d+)\s+(?:hour|hr|minute|min)s?""", RegexOption.IGNORE_CASE)
        private val RELATIVE_DATE_PATTERN = Regex("""(today|tonight|tomorrow|next\s+\w+|this\s+(?:evening|morning|afternoon))""", RegexOption.IGNORE_CASE)

        // --- Keyword matchers ---
        private val REMINDER_KEYWORDS = listOf("remind", "reminder", "don't forget", "remember to")
        private val MESSAGE_KEYWORDS = listOf("tell ", "message ", "send ", "text ", "inform ", "let ")
        private val CALL_KEYWORDS = listOf("call ", "phone ", "dial ", "ring ")
        private val NOTE_KEYWORDS = listOf("note that", "save a note", "remember that", "note:", "jot down")
        private val CALENDAR_KEYWORDS = listOf("schedule", "block", "book", "calendar", "meeting", "appointment")

        private val WORD_TO_NUMBER = mapOf(
            "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
            "ten" to 10, "fifteen" to 15, "twenty" to 20, "thirty" to 30,
            "forty-five" to 45, "forty five" to 45, "half" to 30
        )
    }

    override suspend fun parseIntent(
        input: String,
        context: UserContext
    ): Result<ActionGraph> {
        return try {
            Log.d(TAG, "[FALLBACK] Parsing input: ${input.take(80)}...")
            val startTime = System.currentTimeMillis()

            val clauses = attachLeadingTemporalContext(splitIntoClauses(input))
            Log.d(TAG, "[FALLBACK] Split into ${clauses.size} clauses")

            val actions = mutableListOf<ParsedAction>()

            var lastRecipient: String? = null
            for (clause in clauses) {
                val trimmed = clause.trim()
                if (trimmed.length < 3) continue

                var action = parseClause(trimmed, context)
                if (action?.type == ActionType.MESSAGE && action.recipient == null && lastRecipient != null &&
                    Regex("\\b(him|her|them)\\b", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)
                ) {
                    action = action.copy(
                        recipient = lastRecipient,
                        title = "Message $lastRecipient",
                        confidence = 0.90f,
                        requiresConfirmation = false,
                        ambiguityReason = null
                    )
                }
                if (action != null) {
                    actions.add(action)
                    if (!action.recipient.isNullOrBlank()) lastRecipient = action.recipient
                    Log.d(TAG, "[FALLBACK] Detected ${action.type}: ${action.title}")
                }
            }

            // If no structured actions found, try to parse the entire input
            if (actions.isEmpty()) {
                val wholeAction = parseClause(input, context)
                if (wholeAction != null) {
                    actions.add(wholeAction)
                }
            }

            // If still nothing, create an UNKNOWN action
            if (actions.isEmpty()) {
                actions.add(
                    ParsedAction(
                        type = ActionType.UNKNOWN,
                        title = input.take(100),
                        confidence = 0.3f,
                        requiresConfirmation = true,
                        ambiguityReason = "Could not determine action type"
                    )
                )
            }

            val elapsed = System.currentTimeMillis() - startTime
            val overallConfidence = actions.map { it.confidence }.average().toFloat()

            val graph = ActionGraph(
                originalInput = input,
                actions = actions,
                overallConfidence = overallConfidence,
                processingTimeMs = elapsed,
                sourceEngine = engineName,
                requestCreatedAt = context.requestCreatedAt.toEpochMilli(),
                deviceTimezone = context.timeZoneId
            )

            Log.d(TAG, "[FALLBACK] Created ActionGraph: ${actions.size} actions, " +
                    "confidence=${"%.2f".format(overallConfidence)}, ${elapsed}ms")

            Result.success(graph)
        } catch (e: Exception) {
            Log.e(TAG, "[FALLBACK] Parse error", e)
            Result.failure(e)
        }
    }

    /**
     * Split natural language into logical clauses at commas, "and", periods,
     * while preserving quoted strings and avoiding splitting inside time expressions.
     */
    private fun splitIntoClauses(input: String): List<String> {
        // First remove contextual/status sentences that aren't actions
        val cleaned = input
            .replace(Regex("""(?:the\s+)?\w+\s+meeting\s+is\s+done\.?""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""(?:the\s+)?\w+\s+is\s+(?:finished|over|complete)\.?""", RegexOption.IGNORE_CASE), "")
            .trim()

        val effectiveInput = if (cleaned.isNotBlank()) cleaned else input

        // Split on: ", and ", " and ", ".", or comma followed by an action verb
        // But not inside quoted text
        val parts = effectiveInput.split(
            Regex("""\s*,\s+and\s+|\s+and\s+|\.\s+|\s*,\s*(?=(?:remind|call|phone|dial|message|send|text|tell|schedule|block|book|note|save\s+a\s+note|don't\s+forget)\b)""")
        ).filter { it.isNotBlank() }

        return if (parts.isEmpty()) listOf(effectiveInput) else parts
    }

    /** Keep a leading time clause attached to the action it qualifies. */
    private fun attachLeadingTemporalContext(clauses: List<String>): List<String> {
        val result = mutableListOf<String>()
        var pendingTemporal: String? = null
        for (clause in clauses) {
            val lower = clause.lowercase()
            val isTemporalOnly = RELATIVE_DATE_PATTERN.containsMatchIn(lower) &&
                TIME_PATTERN.containsMatchIn(clause) &&
                !CALENDAR_KEYWORDS.any { lower.contains(it) } &&
                !REMINDER_KEYWORDS.any { lower.contains(it) } &&
                !MESSAGE_KEYWORDS.any { lower.contains(it) }
            if (isTemporalOnly) {
                pendingTemporal = clause.trim()
            } else {
                result += if (pendingTemporal != null) {
                    "$clause ${pendingTemporal!!}".trim()
                } else clause
                pendingTemporal = null
            }
        }
        if (pendingTemporal != null) result += pendingTemporal!!
        return result
    }

    /**
     * Parse a single clause into a ParsedAction by checking keyword patterns.
     */
    private fun parseClause(clause: String, context: UserContext): ParsedAction? {
        val lower = clause.lowercase().trim()

        return when {
            isNote(lower) -> parseNote(clause, context)
            isReminder(lower) -> parseReminder(clause, context)
            isMessage(lower) && !lower.startsWith("call") -> parseMessage(clause, context)
            isCall(lower) -> parseCall(clause, context)
            isMessage(lower) -> parseMessage(clause, context)
            isCalendar(lower) -> parseCalendar(clause, context)
            // Check if it has time/date info → likely calendar
            hasTimeInfo(lower) -> parseCalendar(clause, context)
            else -> null
        }
    }

    // --- Type detection ---

    private fun isReminder(lower: String): Boolean =
        REMINDER_KEYWORDS.any { lower.contains(it) }

    private fun isCall(lower: String): Boolean =
        CALL_KEYWORDS.any { lower.contains(it) } || lower.startsWith("call")

    private fun isMessage(lower: String): Boolean =
        MESSAGE_KEYWORDS.any { lower.contains(it) }

    private fun isNote(lower: String): Boolean =
        NOTE_KEYWORDS.any { lower.contains(it) }

    private fun isCalendar(lower: String): Boolean =
        CALENDAR_KEYWORDS.any { lower.contains(it) }

    private fun hasTimeInfo(lower: String): Boolean =
        RELATIVE_DATE_PATTERN.containsMatchIn(lower) && DURATION_PATTERN.containsMatchIn(lower)

    private fun getEffectiveInstant(context: UserContext): java.time.Instant =
        context.currentDateTime.atZone(context.timeZone).toInstant()

    // --- Parsers ---

    private fun parseCalendar(clause: String, context: UserContext): ParsedAction {
        val effectiveInstant = getEffectiveInstant(context)
        val resolved = com.echoflow.app.domain.time.TimeResolver.resolve(
            rawExpression = clause,
            dateStr = null,
            timeStr = null,
            requestCreatedAt = effectiveInstant,
            zoneId = context.timeZone,
            referenceForPastCheck = effectiveInstant
        )
        val duration = extractDuration(clause)
        val title = extractCalendarTitle(clause)

        val confidence = when {
            resolved.resolvedDateTime != null && duration != null -> 0.95f
            resolved.resolvedDateTime != null -> 0.88f
            duration != null -> 0.82f
            else -> 0.70f
        }

        val needsConfirmation = resolved.resolvedDateTime == null || resolved.isPastTime

        return ParsedAction(
            type = ActionType.CALENDAR,
            title = title,
            timeExpression = resolved.requestedTimeDisplay,
            requestedTime = resolved.requestedTimeDisplay,
            dateTime = resolved.resolvedDateTime,
            resolvedEpochMillis = resolved.resolvedEpochMillis,
            isPastTime = resolved.isPastTime,
            durationMinutes = duration ?: 60,
            confidence = confidence,
            requiresConfirmation = needsConfirmation,
            ambiguityReason = when {
                resolved.isPastTime -> "Scheduled time has already passed"
                needsConfirmation -> "Date/time not specified"
                else -> null
            }
        )
    }

    private fun parseReminder(clause: String, context: UserContext): ParsedAction {
        val recipient = extractRecipient(clause)
        val resolved = com.echoflow.app.domain.time.TimeResolver.resolve(
            rawExpression = clause,
            dateStr = null,
            timeStr = null,
            requestCreatedAt = context.requestCreatedAt,
            zoneId = context.timeZone
        )
        val title = extractReminderTitle(clause, recipient)

        val confidence = when {
            recipient != null && resolved.resolvedDateTime != null -> 0.93f
            recipient != null -> 0.88f
            resolved.resolvedDateTime != null -> 0.85f
            else -> 0.75f
        }

        val needsConfirmation = resolved.resolvedDateTime == null || recipient == null || resolved.isPastTime

        return ParsedAction(
            type = ActionType.REMINDER,
            title = title,
            recipient = recipient,
            timeExpression = resolved.requestedTimeDisplay,
            requestedTime = resolved.requestedTimeDisplay,
            dateTime = resolved.resolvedDateTime,
            resolvedEpochMillis = resolved.resolvedEpochMillis,
            isPastTime = resolved.isPastTime,
            confidence = confidence,
            requiresConfirmation = needsConfirmation,
            ambiguityReason = when {
                resolved.isPastTime -> "Scheduled time has already passed"
                resolved.resolvedDateTime == null && recipient != null -> "When should this reminder trigger?"
                else -> null
            }
        )
    }

    private fun parseCall(clause: String, context: UserContext): ParsedAction {
        val recipient = extractRecipient(clause)
        val effectiveInstant = getEffectiveInstant(context)
        val resolved = com.echoflow.app.domain.time.TimeResolver.resolve(
            rawExpression = clause,
            dateStr = null,
            timeStr = null,
            requestCreatedAt = effectiveInstant,
            zoneId = context.timeZone,
            referenceForPastCheck = effectiveInstant
        )

        val confidence = when {
            recipient != null && resolved.resolvedDateTime != null -> 0.94f
            recipient != null -> 0.88f
            else -> 0.65f
        }

        return ParsedAction(
            type = ActionType.CALL,
            title = if (recipient != null) "Call $recipient" else "Make phone call",
            recipient = recipient,
            executionMode = ExecutionMode.PROACTIVE_CONFIRMATION,
            timeExpression = resolved.requestedTimeDisplay.ifBlank { null },
            requestedTime = resolved.requestedTimeDisplay.ifBlank { null },
            dateTime = resolved.resolvedDateTime,
            resolvedEpochMillis = resolved.resolvedEpochMillis,
            isPastTime = resolved.isPastTime,
            confidence = confidence,
            requiresConfirmation = recipient == null || resolved.isPastTime,
            ambiguityReason = when {
                resolved.isPastTime -> "Scheduled time has already passed"
                recipient == null -> "Recipient to call not specified"
                else -> null
            }
        )
    }

    private fun parseMessage(clause: String, context: UserContext): ParsedAction {
        val recipient = extractRecipient(clause)
        val effectiveInstant = getEffectiveInstant(context)
        val resolved = com.echoflow.app.domain.time.TimeResolver.resolve(
            rawExpression = clause,
            dateStr = null,
            timeStr = null,
            requestCreatedAt = effectiveInstant,
            zoneId = context.timeZone,
            referenceForPastCheck = effectiveInstant
        )
        val messageContent = extractMessageContent(clause, recipient)

        val isScheduled = resolved.resolvedEpochMillis != null
        val executionMode = if (isScheduled) ExecutionMode.SCHEDULED else ExecutionMode.IMMEDIATE

        val confidence = when {
            recipient != null && messageContent.isNotBlank() -> 0.91f
            recipient != null -> 0.80f
            else -> 0.60f
        }

        val needsConfirmation = recipient == null || messageContent.isBlank() || resolved.isPastTime

        return ParsedAction(
            type = ActionType.MESSAGE,
            title = if (recipient != null) "Message $recipient" else "Send message",
            recipient = recipient,
            executionMode = executionMode,
            timeExpression = if (isScheduled) resolved.requestedTimeDisplay else null,
            requestedTime = if (isScheduled) resolved.requestedTimeDisplay else null,
            dateTime = resolved.resolvedDateTime,
            resolvedEpochMillis = resolved.resolvedEpochMillis,
            isPastTime = resolved.isPastTime,
            message = messageContent.ifBlank { null },
            confidence = confidence,
            requiresConfirmation = needsConfirmation,
            ambiguityReason = when {
                resolved.isPastTime -> "Scheduled time has already passed"
                recipient == null -> "Recipient not specified"
                messageContent.isBlank() -> "Message content unclear"
                else -> null
            }
        )
    }

    private fun parseNote(clause: String, context: UserContext): ParsedAction {
        val content = clause
            .replace(Regex("""^(?:note\s+that|save\s+a\s+note\s+(?:that)?|remember\s+that|jot\s+down)\s*""", RegexOption.IGNORE_CASE), "")
            .trim()

        val effectiveInstant = getEffectiveInstant(context)
        val resolved = com.echoflow.app.domain.time.TimeResolver.resolve(
            rawExpression = clause,
            dateStr = null,
            timeStr = null,
            requestCreatedAt = effectiveInstant,
            zoneId = context.timeZone,
            referenceForPastCheck = effectiveInstant
        )

        return ParsedAction(
            type = ActionType.REMINDER,
            title = content.take(60),
            description = content,
            timeExpression = resolved.requestedTimeDisplay.ifBlank { null },
            requestedTime = resolved.requestedTimeDisplay.ifBlank { null },
            dateTime = resolved.resolvedDateTime,
            resolvedEpochMillis = resolved.resolvedEpochMillis,
            isPastTime = resolved.isPastTime,
            confidence = 0.90f,
            requiresConfirmation = resolved.isPastTime
        )
    }

    // --- Extraction helpers ---

    private fun extractRecipient(clause: String): String? {
        // "message to Rahul", "send a message to Rahul", "remind Rahul", "tell Rahul", "message Rahul", "call Rahul", "let Rahul know"
        val nonNameWords = setOf("me", "myself", "him", "her", "them", "that", "the", "a", "an", "to", "after", "in", "at", "on", "saying", "for")
        val patterns = listOf(
            Regex("""(?:remind|tell|message|send|text|inform|call|phone|dial)\s+(?:a\s+message\s+)?to\s+([A-Za-z]+)""", RegexOption.IGNORE_CASE),
            Regex("""(?:send)\s+([A-Za-z]+)\s+(?:a\s+)?message""", RegexOption.IGNORE_CASE),
            Regex("""(?:remind|tell|message|send|text|inform|call|phone|dial)\s+([A-Z][a-z]+)"""),
            Regex("""(?:let)\s+([A-Za-z]+)\s+know""", RegexOption.IGNORE_CASE),
            Regex("""(?:remind|tell|message|send|text|inform|call|phone|dial)\s+(\w+)""", RegexOption.IGNORE_CASE)
        )

        for (pattern in patterns) {
            val match = pattern.find(clause)
            if (match != null) {
                val name = match.groupValues[1]
                if (name.lowercase() !in nonNameWords) {
                    return name.replaceFirstChar { it.uppercase() }
                }
            }
        }
        return null
    }

    private fun extractDateTime(clause: String, now: LocalDateTime): LocalDateTime? {
        val lower = clause.lowercase()

        // Resolve relative date
        val date = when {
            lower.contains("today") || lower.contains("tonight") -> now.toLocalDate()
            lower.contains("tomorrow") -> now.toLocalDate().plusDays(1)
            lower.contains("next monday") -> now.toLocalDate().with(java.time.DayOfWeek.MONDAY).let {
                if (it.isAfter(now.toLocalDate())) it else it.plusWeeks(1)
            }
            lower.contains("next week") -> now.toLocalDate().plusWeeks(1)
            lower.contains("this evening") -> now.toLocalDate()
            lower.contains("this morning") -> now.toLocalDate()
            lower.contains("this afternoon") -> now.toLocalDate()
            else -> null
        }

        // Extract time
        val timeMatch = TIME_PATTERN.find(clause)
        val time = if (timeMatch != null) {
            var hour = timeMatch.groupValues[1].toIntOrNull() ?: return if (date != null) date.atTime(9, 0) else null
            val minute = timeMatch.groupValues[2].toIntOrNull() ?: 0
            val ampm = timeMatch.groupValues[3].lowercase()

            when {
                ampm == "pm" && hour < 12 -> hour += 12
                ampm == "am" && hour == 12 -> hour = 0
                ampm.isEmpty() && hour in 1..7 -> hour += 12 // Assume PM for small hours
            }

            LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59))
        } else if (lower.contains("morning")) {
            LocalTime.of(9, 0)
        } else if (lower.contains("afternoon")) {
            LocalTime.of(14, 0)
        } else if (lower.contains("evening") || lower.contains("tonight")) {
            LocalTime.of(19, 0)
        } else {
            null
        }

        return when {
            date != null && time != null -> LocalDateTime.of(date, time)
            date != null -> date.atTime(9, 0) // Default morning
            time != null -> LocalDateTime.of(now.toLocalDate().plusDays(1), time) // Assume tomorrow
            else -> null
        }
    }

    private fun extractDuration(clause: String): Int? {
        val match = DURATION_PATTERN.find(clause.lowercase()) ?: return null
        val numberStr = match.groupValues[1]
        val number = numberStr.toIntOrNull() ?: WORD_TO_NUMBER[numberStr] ?: return null
        val unit = match.value.lowercase()

        return when {
            unit.contains("hour") || unit.contains("hr") -> number * 60
            unit.contains("minute") || unit.contains("min") -> number
            else -> number * 60
        }
    }

    private fun extractCalendarTitle(clause: String): String {
        // Remove time/date/duration fragments to get the core activity
        var title = clause
            .replace(Regex("""(?:tomorrow|today|tonight|next\s+\w+|this\s+\w+)""", RegexOption.IGNORE_CASE), "")
            .replace(TIME_PATTERN, "")
            .replace(DURATION_PATTERN, "")
            .replace(Regex("""(?:schedule|block|book|set up|create|add|at|for|to|on)\s*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trimEnd(',', '.', ';')
            .trim()

        if (title.isBlank()) {
            title = clause.take(50)
        }

        return title.replaceFirstChar { it.uppercase() }
    }

    private fun extractReminderTitle(clause: String, recipient: String?): String {
        var title = clause
            .replace(Regex("""^(?:remind\s+\w+\s+(?:to\s+)?|remind\s+me\s+(?:to\s+)?)""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""(?:tomorrow|today|tonight)\s*""", RegexOption.IGNORE_CASE), "")
            .trim()
            .trimEnd(',', '.', ';')
            .trim()

        if (title.isBlank()) {
            title = clause.take(50)
        }

        return if (recipient != null) {
            "Ask $recipient to ${title.replaceFirstChar { it.lowercase() }}"
        } else {
            title.replaceFirstChar { it.uppercase() }
        }
    }

    private fun extractMessageContent(clause: String, recipient: String?): String {
        // "message to Rahul after 1 minute that I am coming for working" → "I am coming for working"
        // "send a message to Rahul tomorrow at 5 saying I will call you" → "I will call you"
        // "message Rahul at 7 pm that I reached home" → "I reached home"
        // "text Rahul after 2 minutes saying I am outside" → "I am outside"
        // "tell Rahul that I told John that we are ready" → "I told John that we are ready"

        // 1. Primary: Match content following explicit message markers ("that", "saying", "tell them that")
        val markerPattern = Regex(
            """\b(?:saying|that|tell\s+(?:them|him|her)(?:\s+that)?)\s+(.+)""",
            RegexOption.IGNORE_CASE
        )
        val markerMatch = markerPattern.find(clause)
        if (markerMatch != null) {
            val extracted = sanitizeExtractedMessage(markerMatch.groupValues[1], clause)
            if (extracted.isNotBlank()) return extracted
        }

        // 2. Secondary: If no explicit marker, match verb + recipient + optional time + content
        val directPattern = Regex(
            """^(?:send\s+(?:a\s+)?message(?:\s+to)?|message(?:\s+to)?|text|tell|inform)\s+(?:to\s+)?(?:[A-Za-z]+)\s*(?:(?:after|in)\s+\d+\s*(?:seconds?|minutes?|mins?|hours?|hrs?|days?)|tomorrow|today|at\s+[\d:]+\s*(?:am|pm)?)?\s+(.+)""",
            RegexOption.IGNORE_CASE
        )
        val directMatch = directPattern.find(clause)
        if (directMatch != null) {
            val extracted = sanitizeExtractedMessage(directMatch.groupValues[1], clause)
            if (extracted.isNotBlank()) return extracted
        }

        // 3. Fallback: Strip leading command, recipient, and time info
        val fallback = clause
            .replace(Regex("""^(?:send\s+(?:(?:\w+)\s+)?(?:a\s+)?message(?:\s+to)?|message(?:\s+to)?|text|tell|inform)\s+(?:to\s+)?(?:\w+\s+)?""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^(?:(?:after|in)\s+\w+\s*(?:seconds?|secs?|minutes?|mins?|hours?|hrs?|days?)|tomorrow|today|tonight|at\s+[\d:]+\s*(?:am|pm)?)\s*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^(?:saying|that)\s*""", RegexOption.IGNORE_CASE), "")
            .trim()
            .trimEnd(',', '.', ';')
            .trim()

        return sanitizeExtractedMessage(fallback, clause)
    }

    private fun sanitizeExtractedMessage(candidate: String, originalClause: String): String {
        var content = candidate.trim().trimEnd(',', '.', ';').trim()

        // Strip redundant leading "saying" or "that"
        content = content.replace(Regex("""^(?:saying|that)\s*""", RegexOption.IGNORE_CASE), "").trim()

        // Safety checks:
        // 1. Message must not be blank
        if (content.isBlank()) return ""

        // 2. Message must not equal the original clause
        if (content.equals(originalClause.trim(), ignoreCase = true)) return ""

        // 3. Message must not begin with command prefixes
        val commandPrefixRegex = Regex(
            """^(?:message\s+to|send\s+(?:a\s+)?message|text\s+|call\s+|remind\s+)""",
            RegexOption.IGNORE_CASE
        )
        if (commandPrefixRegex.containsMatchIn(content)) return ""

        // 4. Message must not just be recipient or time fragment
        if (content.matches(Regex("""^(?:after|in)\s+\d+\s*(?:minute|min|hour|sec)s?$""", RegexOption.IGNORE_CASE))) return ""

        // Capitalize first character for clean presentation
        return content.replaceFirstChar { it.uppercase() }
    }
}
