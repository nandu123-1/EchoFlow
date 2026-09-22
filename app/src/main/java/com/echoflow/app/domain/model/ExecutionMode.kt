package com.echoflow.app.domain.model

/**
 * Scheduling semantics for parsed actions:
 * - IMMEDIATE: Executed right after user review (e.g. quick message composer, note creation)
 * - SCHEDULED: Scheduled via AlarmManager for a future time (e.g. calendar event, reminder, delayed message)
 * - PROACTIVE_CONFIRMATION: Scheduled for a future time, but triggers a proactive notification
 *   prompting user confirmation before any actual action is taken (mandatory for CALL).
 */
enum class ExecutionMode {
    IMMEDIATE,
    SCHEDULED,
    PROACTIVE_CONFIRMATION
}
