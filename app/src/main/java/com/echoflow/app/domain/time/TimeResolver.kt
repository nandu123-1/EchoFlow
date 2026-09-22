package com.echoflow.app.domain.time

import android.util.Log
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/**
 * Deterministic time and date resolver for EchoFlow.
 *
 * CRITICAL INVARIANT:
 * User-requested relative times ("after 5 minutes") MUST be resolved relative to
 * [requestCreatedAt], NEVER relative to inference finish time or execution time!
 *
 * This completely prevents ~60s on-device CPU inference delay from drifting scheduled times.
 */
object TimeResolver {

    private const val TAG = "EchoFlow-Time"

    private val WORD_TO_NUMBER = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
        "fifteen" to 15, "twenty" to 20, "thirty" to 30, "forty-five" to 45,
        "forty five" to 45, "half" to 30, "an" to 1, "a" to 1
    )

    private val RELATIVE_TIME_REGEX = Regex(
        """(?:after|in)\s+(\d+)\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?|days?)|(?:after|in)\s+(\w+)\s+(seconds?|secs?|minutes?|mins?|hours?|hrs?|days?)""",
        RegexOption.IGNORE_CASE
    )

    private val TIME_AT_REGEX = Regex(
        """(?:at\s+)?(\d{1,2})(?::(\d{2}))?\s*(am|pm|AM|PM)?""",
        RegexOption.IGNORE_CASE
    )

    data class ResolvedTime(
        val resolvedDateTime: LocalDateTime?,
        val resolvedEpochMillis: Long?,
        val requestedTimeDisplay: String,
        val isPastTime: Boolean = false,
        val isRelative: Boolean = false
    )

    /**
     * Resolve date/time intent deterministically.
     *
     * @param rawExpression User's raw time expression (e.g. "after 5 minutes", "tomorrow at 4 PM", "at 22:34")
     * @param dateStr Optional date component from AI or parser (e.g. "2026-09-22", "tomorrow", "today")
     * @param timeStr Optional time component from AI or parser (e.g. "22:34", "16:00", "4 PM")
     * @param requestCreatedAt The exact Instant the user submitted the thought
     * @param zoneId Device timezone (defaults to systemDefault)
     * @param referenceForPastCheck The Instant to evaluate if the target has already passed (defaults to Instant.now())
     */
    fun resolve(
        rawExpression: String?,
        dateStr: String?,
        timeStr: String?,
        requestCreatedAt: Instant,
        zoneId: ZoneId = ZoneId.systemDefault(),
        referenceForPastCheck: Instant = Instant.now()
    ): ResolvedTime {
        val cleanExpr = rawExpression?.trim() ?: ""
        val cleanDate = dateStr?.trim()
        val cleanTime = timeStr?.trim()

        val requestLocal = LocalDateTime.ofInstant(requestCreatedAt, zoneId)

        // 1. Check for Relative Time expression ("after 5 minutes", "in 1 hour", etc.)
        val relativeMatch = RELATIVE_TIME_REGEX.find(cleanExpr)
            ?: RELATIVE_TIME_REGEX.find(cleanTime ?: "")
            ?: RELATIVE_TIME_REGEX.find(cleanDate ?: "")

        if (relativeMatch != null) {
            val amountStr = if (relativeMatch.groupValues[1].isNotBlank()) {
                relativeMatch.groupValues[1].lowercase()
            } else {
                relativeMatch.groupValues[3].lowercase()
            }
            val unitStr = if (relativeMatch.groupValues[2].isNotBlank()) {
                relativeMatch.groupValues[2].lowercase()
            } else {
                relativeMatch.groupValues[4].lowercase()
            }

            val amount = amountStr.toIntOrNull() ?: WORD_TO_NUMBER[amountStr] ?: 1
            val duration = when {
                unitStr.startsWith("sec") -> Duration.ofSeconds(amount.toLong())
                unitStr.startsWith("min") -> Duration.ofMinutes(amount.toLong())
                unitStr.startsWith("hour") || unitStr.startsWith("hr") -> Duration.ofHours(amount.toLong())
                unitStr.startsWith("day") -> Duration.ofDays(amount.toLong())
                else -> Duration.ofMinutes(amount.toLong())
            }

            // CRITICAL: Base is requestCreatedAt, NOT inference finish time!
            val targetInstant = requestCreatedAt.plus(duration)
            val targetLocal = LocalDateTime.ofInstant(targetInstant, zoneId)
            val targetEpoch = targetInstant.toEpochMilli()

            val isPast = targetInstant.isBefore(referenceForPastCheck)

            Log.d(TAG, "[TIME] Resolved relative '$cleanExpr' from requestCreated=$requestCreatedAt -> target=$targetInstant (isPast=$isPast)")

            return ResolvedTime(
                resolvedDateTime = targetLocal,
                resolvedEpochMillis = targetEpoch,
                requestedTimeDisplay = relativeMatch.value,
                isPastTime = isPast,
                isRelative = true
            )
        }

        // 2. Check for explicit ISO date + time (e.g. "2026-09-22" + "16:00")
        val parsedDate = resolveDate(cleanDate, cleanExpr, requestLocal.toLocalDate())
        val parsedTime = resolveTime(cleanTime, cleanExpr)

        if (parsedDate != null || parsedTime != null) {
            val finalDate = parsedDate ?: if (parsedTime != null) {
                // If only time given: if time today is earlier than request time, could be tomorrow or past
                requestLocal.toLocalDate()
            } else {
                requestLocal.toLocalDate()
            }

            val finalTime = parsedTime ?: LocalTime.of(9, 0)
            val targetLocal = LocalDateTime.of(finalDate, finalTime)
            val targetInstant = targetLocal.atZone(zoneId).toInstant()
            val targetEpoch = targetInstant.toEpochMilli()

            val isPast = targetInstant.isBefore(referenceForPastCheck)

            val display = buildString {
                if (cleanExpr.isNotBlank()) append(cleanExpr)
                else {
                    if (cleanDate != null) append("$cleanDate ")
                    if (cleanTime != null) append("at $cleanTime")
                }
            }.trim()

            Log.d(TAG, "[TIME] Resolved absolute date/time -> target=$targetLocal (epoch=$targetEpoch, isPast=$isPast)")

            return ResolvedTime(
                resolvedDateTime = targetLocal,
                resolvedEpochMillis = targetEpoch,
                requestedTimeDisplay = display.ifBlank { targetLocal.format(DateTimeFormatter.ofPattern("HH:mm")) },
                isPastTime = isPast,
                isRelative = false
            )
        }

        // 3. Fallback: No time information detected
        return ResolvedTime(
            resolvedDateTime = null,
            resolvedEpochMillis = null,
            requestedTimeDisplay = cleanExpr,
            isPastTime = false,
            isRelative = false
        )
    }

    private fun resolveDate(dateStr: String?, expr: String, baseDate: LocalDate): LocalDate? {
        val candidate = (dateStr ?: "").lowercase()
        val exprLower = expr.lowercase()

        return when {
            candidate.contains("tomorrow") || exprLower.contains("tomorrow") -> baseDate.plusDays(1)
            candidate.contains("today") || candidate.contains("tonight") || exprLower.contains("today") || exprLower.contains("tonight") -> baseDate
            candidate.contains("next monday") || exprLower.contains("next monday") -> baseDate.with(DayOfWeek.MONDAY).let {
                if (it.isAfter(baseDate)) it else it.plusWeeks(1)
            }
            candidate.contains("next week") || exprLower.contains("next week") -> baseDate.plusWeeks(1)
            else -> try {
                if (!dateStr.isNullOrBlank()) LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE) else null
            } catch (e: DateTimeParseException) {
                null
            }
        }
    }

    private fun resolveTime(timeStr: String?, expr: String): LocalTime? {
        // Try parsing explicit timeStr first
        if (!timeStr.isNullOrBlank()) {
            val direct = parseTimeDirect(timeStr)
            if (direct != null) return direct
        }

        // Try searching in timeStr or expr with regex
        val searchTarget = when {
            !timeStr.isNullOrBlank() -> timeStr
            expr.isNotBlank() -> expr
            else -> return null
        }

        val match = TIME_AT_REGEX.find(searchTarget)
        if (match != null) {
            val hourRaw = match.groupValues[1].toIntOrNull() ?: return null
            val minuteRaw = match.groupValues[2].toIntOrNull() ?: 0
            val ampm = match.groupValues[3].lowercase()

            var hour = hourRaw
            when {
                ampm == "pm" && hour < 12 -> hour += 12
                ampm == "am" && hour == 12 -> hour = 0
                ampm.isEmpty() && hour in 1..7 -> hour += 12 // Assume PM for small afternoon hours
            }

            return try {
                LocalTime.of(hour.coerceIn(0, 23), minuteRaw.coerceIn(0, 59))
            } catch (e: Exception) {
                null
            }
        }

        val lower = searchTarget.lowercase()
        return when {
            lower.contains("morning") -> LocalTime.of(9, 0)
            lower.contains("afternoon") -> LocalTime.of(14, 0)
            lower.contains("evening") -> LocalTime.of(19, 0)
            lower.contains("tonight") -> LocalTime.of(20, 0)
            else -> null
        }
    }

    private fun parseTimeDirect(str: String): LocalTime? {
        val trimmed = str.trim()
        val patterns = listOf("HH:mm", "H:mm", "hh:mm a", "h:mm a", "h:mma", "hh:mma", "HH:mm:ss")
        for (pattern in patterns) {
            try {
                return LocalTime.parse(trimmed, DateTimeFormatter.ofPattern(pattern))
            } catch (_: Exception) {}
        }
        return null
    }
}
