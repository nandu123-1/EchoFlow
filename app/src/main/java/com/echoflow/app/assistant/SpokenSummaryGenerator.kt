package com.echoflow.app.assistant

import com.echoflow.app.domain.model.ActionGraph
import com.echoflow.app.domain.model.ActionType
import com.echoflow.app.domain.model.ExecutionState
import com.echoflow.app.domain.model.ParsedAction
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Generates deterministic, concise spoken summaries from ActionGraph and execution states.
 * Eliminates redundant LLM generation cycles and guarantees strict adherence to execution truth.
 */
object SpokenSummaryGenerator {

    private val timeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())

    /**
     * Spoken prompt when ActionGraph has been parsed and is presented to user for review.
     */
    fun generateReviewPrompt(graph: ActionGraph): String {
        val actions = graph.actions
        if (actions.isEmpty()) {
            return "I couldn't find any actionable steps in that request."
        }

        if (actions.size == 1) {
            val a = actions.first()
            val timePhrase = formatTimePhrase(a)
            return when (a.type) {
                ActionType.CALENDAR -> {
                    if (timePhrase.isNotBlank()) "I found one calendar event for ${a.title} $timePhrase. Should I schedule it?"
                    else "I found one calendar event for ${a.title}. Should I schedule it?"
                }
                ActionType.REMINDER -> {
                    if (timePhrase.isNotBlank()) "I found one reminder for ${a.title} $timePhrase. Should I schedule it?"
                    else "I found one reminder for ${a.title}. Should I schedule it?"
                }
                ActionType.MESSAGE -> "I prepared a message to ${a.recipient ?: "your contact"}. Please review it."
                ActionType.CALL -> {
                    if (timePhrase.isNotBlank()) "I found one call to ${a.recipient ?: "your contact"} $timePhrase. Should I schedule it?"
                    else "I found one call to ${a.recipient ?: "your contact"}. Should I schedule it?"
                }
                ActionType.NOTE -> "I prepared a note titled ${a.title}. Should I save it?"
                ActionType.UNKNOWN -> "I found one action. Please review it."
            }
        }

        val countWords = when (actions.size) {
            2 -> "two"
            3 -> "three"
            4 -> "four"
            5 -> "five"
            else -> actions.size.toString()
        }
        return "I found $countWords actions. Please review them before I execute."
    }

    /**
     * Spoken summary after actions have executed.
     * Accurately reflects partial execution states (Rule ⑦).
     */
    fun generateExecutionSummary(actions: List<ParsedAction>): String {
        if (actions.isEmpty()) return "No actions were executed."

        val scheduled = actions.filter { it.executionState == ExecutionState.SCHEDULED }
        val completed = actions.filter { (it.executionState == ExecutionState.SUCCESS || it.executionState == ExecutionState.COMPLETED) && it.executionState != ExecutionState.SCHEDULED }
        val prepared = actions.filter { it.executionState == ExecutionState.PREPARED || it.executionState == ExecutionState.IN_PROGRESS }
        val cancelled = actions.filter { it.executionState == ExecutionState.CANCELLED }
        val failed = actions.filter { it.executionState == ExecutionState.FAILED }

        // Single action case
        if (actions.size == 1) {
            val a = actions.first()
            if (a.executionState == ExecutionState.CANCELLED) {
                return "Cancelled ${a.title}."
            }
            if (a.executionState == ExecutionState.FAILED) {
                return when (a.type) {
                    ActionType.CALENDAR -> "I couldn't schedule the calendar event."
                    ActionType.REMINDER -> "I couldn't schedule the reminder."
                    ActionType.MESSAGE -> "I couldn't prepare the message."
                    ActionType.CALL -> "I couldn't prepare the call."
                    ActionType.NOTE -> "I couldn't save the note."
                    ActionType.UNKNOWN -> "I couldn't complete that action."
                }
            }
            val time = formatTimePhrase(a)
            return when (a.type) {
                ActionType.CALENDAR -> {
                    if (time.isNotBlank()) "Your ${a.title} is scheduled $time."
                    else "Your ${a.title} is scheduled."
                }
                ActionType.REMINDER -> {
                    if (time.isNotBlank()) "I'll remind you $time."
                    else "Your reminder is set."
                }
                ActionType.CALL -> {
                    if (a.executionState == ExecutionState.SCHEDULED) {
                        if (time.isNotBlank()) "I've scheduled a call reminder for ${a.recipient ?: "your contact"} $time."
                        else "Call reminder set for ${a.recipient ?: "your contact"}."
                    } else {
                        "Dialer opened for ${a.recipient ?: "your contact"}."
                    }
                }
                ActionType.MESSAGE -> {
                    if (a.executionState == ExecutionState.SCHEDULED) {
                        if (time.isNotBlank()) "Message to ${a.recipient ?: "your contact"} scheduled $time."
                        else "Message scheduled for ${a.recipient ?: "your contact"}."
                    } else {
                        "The message to ${a.recipient ?: "your contact"} is ready for your review."
                    }
                }
                ActionType.NOTE -> "Your note has been saved."
                ActionType.UNKNOWN -> "Action completed."
            }
        }

        val reviewReadyMessages = actions.filter {
            it.type == ActionType.MESSAGE &&
            (it.executionState == ExecutionState.SUCCESS || it.executionState == ExecutionState.PREPARED)
        }
        val dialerCalls = actions.filter {
            it.type == ActionType.CALL &&
            (it.executionState == ExecutionState.SUCCESS || it.executionState == ExecutionState.PREPARED || it.executionState == ExecutionState.IN_PROGRESS)
        }
        val standardCompleted = completed.filter { it.type != ActionType.MESSAGE && it.type != ActionType.CALL }

        // Multi-action case
        val parts = mutableListOf<String>()

        if (standardCompleted.isNotEmpty()) {
            val hasCalendar = standardCompleted.any { it.type == ActionType.CALENDAR }
            val hasReminder = standardCompleted.any { it.type == ActionType.REMINDER }
            val hasNote = standardCompleted.any { it.type == ActionType.NOTE }
            if (hasCalendar && hasReminder) {
                parts.add("The calendar event is scheduled and your reminder is set.")
            } else if (hasCalendar && hasNote) {
                parts.add("Calendar event scheduled and note saved.")
            } else if (hasCalendar) {
                parts.add("The calendar event is scheduled.")
            } else if (hasReminder) {
                parts.add("Your reminder is set.")
            } else if (hasNote) {
                parts.add("The note was saved.")
            } else {
                parts.add("${numberToWord(standardCompleted.size)} action(s) completed.")
            }
        }

        if (scheduled.isNotEmpty()) {
            val hasRem = scheduled.any { it.type == ActionType.REMINDER }
            val hasMsg = scheduled.any { it.type == ActionType.MESSAGE }
            val hasCall = scheduled.any { it.type == ActionType.CALL }
            val hasCal = scheduled.any { it.type == ActionType.CALENDAR }

            val schedItems = mutableListOf<String>()
            if (hasCal) schedItems.add("calendar event")
            if (hasRem) schedItems.add("reminder")
            if (hasMsg) schedItems.add("message")
            if (hasCall) schedItems.add("call confirmation")

            if (schedItems.size == 1) {
                parts.add("The ${schedItems.first()} is scheduled.")
            } else {
                parts.add("Scheduled ${schedItems.joinToString(", ")}.")
            }
        }

        if (reviewReadyMessages.isNotEmpty()) {
            val recipients = reviewReadyMessages.mapNotNull { it.recipient }.distinct()
            if (recipients.size == 1) {
                parts.add("The message to ${recipients.first()} is ready for your review.")
            } else {
                parts.add("${numberToWord(reviewReadyMessages.size)} message(s) are ready for your review.")
            }
        }

        if (dialerCalls.isNotEmpty()) {
            parts.add("Dialer opened.")
        }

        if (cancelled.isNotEmpty()) {
            parts.add("${numberToWord(cancelled.size)} action(s) cancelled.")
        }

        if (failed.isNotEmpty()) {
            parts.add("${numberToWord(failed.size)} action(s) could not be completed.")
        }

        val prefix = if (failed.isEmpty() && cancelled.isEmpty()) "Done. " else ""
        return prefix + parts.joinToString(" ")
    }

    /** Spoken feedback when remaining actions are cancelled mid-workflow */
    fun generateCancellationSummary(): String {
        return "I've cancelled the remaining actions."
    }

    private fun formatTimePhrase(action: ParsedAction): String {
        action.timeExpression?.let { return it }
        action.requestedTime?.let { return it }
        action.dateTime?.let { dt ->
            return "at " + dt.format(timeFormatter)
        }
        return ""
    }

    private fun numberToWord(n: Int): String {
        return when (n) {
            1 -> "One"
            2 -> "Two"
            3 -> "Three"
            4 -> "Four"
            5 -> "Five"
            else -> n.toString()
        }
    }
}
