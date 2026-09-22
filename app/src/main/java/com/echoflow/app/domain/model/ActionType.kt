package com.echoflow.app.domain.model

/**
 * Types of actions that EchoFlow can decompose from natural language input.
 * Each type maps to a specific Android executor.
 */
enum class ActionType {
    /** Create a calendar event via CalendarContract */
    CALENDAR,

    /** Schedule a reminder via AlarmManager + Notification */
    REMINDER,

    /** Prepare/send a message via messaging intent */
    MESSAGE,

    /** Store a local note (Deprecated in V5 — mapped to REMINDER) */
    @Deprecated("NOTE is deprecated in V5 and mapped to REMINDER. Retained for database backward compatibility.")
    NOTE,

    /** Phone call action (requires explicit user confirmation before placing) */
    CALL,

    /** Action type could not be determined */
    UNKNOWN
}
