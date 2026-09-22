package com.echoflow.app.assistant

import com.echoflow.app.domain.model.ActionType
import com.echoflow.app.domain.model.ExecutionMode
import com.echoflow.app.domain.model.ExecutionState
import com.echoflow.app.domain.model.ParsedAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class V4SpokenSummaryGeneratorTest {

    @Test
    fun testFlagshipFourActionWorkflowSummary() {
        val actions = listOf(
            ParsedAction(
                type = ActionType.CALENDAR,
                title = "Work on motor controller",
                dateTime = LocalDateTime.of(2026, 9, 22, 16, 0),
                durationMinutes = 60,
                executionState = ExecutionState.SUCCESS
            ),
            ParsedAction(
                type = ActionType.REMINDER,
                title = "Remind Rahul to send CAD files",
                recipient = "Rahul",
                dateTime = LocalDateTime.of(2026, 9, 22, 16, 0),
                executionState = ExecutionState.SUCCESS
            ),
            ParsedAction(
                type = ActionType.MESSAGE,
                title = "Message Rahul",
                recipient = "Rahul",
                message = "I'll start integration tomorrow",
                dateTime = LocalDateTime.of(2026, 9, 22, 16, 15),
                executionMode = ExecutionMode.SCHEDULED,
                executionState = ExecutionState.SCHEDULED
            ),
            ParsedAction(
                type = ActionType.CALL,
                title = "Call Rahul",
                recipient = "Rahul",
                dateTime = LocalDateTime.of(2026, 9, 22, 17, 0),
                executionMode = ExecutionMode.PROACTIVE_CONFIRMATION,
                requiresConfirmation = true,
                executionState = ExecutionState.SCHEDULED
            )
        )

        val summary = SpokenSummaryGenerator.generateExecutionSummary(actions)
        assertTrue("Must start with Done", summary.startsWith("Done"))
        assertTrue("Must mention calendar and reminder", summary.contains("calendar event is scheduled and your reminder is set"))
        assertTrue("Must mention scheduled message", summary.contains("message"))
        assertTrue("Must mention call confirmation", summary.contains("call confirmation"))
    }

    @Test
    fun testSingleCallActionImmediateSummary() {
        val action = ParsedAction(
            type = ActionType.CALL,
            title = "Call Rahul",
            recipient = "Rahul",
            executionState = ExecutionState.IN_PROGRESS
        )
        val summary = SpokenSummaryGenerator.generateExecutionSummary(listOf(action))
        assertEquals("Dialer opened for Rahul.", summary)
    }

    @Test
    fun testSingleCallActionScheduledSummary() {
        val action = ParsedAction(
            type = ActionType.CALL,
            title = "Call Rahul",
            recipient = "Rahul",
            dateTime = LocalDateTime.of(2026, 9, 22, 17, 0),
            executionState = ExecutionState.SCHEDULED
        )
        val summary = SpokenSummaryGenerator.generateExecutionSummary(listOf(action))
        assertTrue("Should mention scheduled call reminder", summary.contains("scheduled a call reminder for Rahul"))
    }

    @Test
    fun testSingleCancelledActionSummary() {
        val action = ParsedAction(
            type = ActionType.CALL,
            title = "Call Rahul",
            executionState = ExecutionState.CANCELLED
        )
        val summary = SpokenSummaryGenerator.generateExecutionSummary(listOf(action))
        assertEquals("Cancelled Call Rahul.", summary)
    }

    @Test
    fun testCancellationSummaryForWorkflow() {
        val summary = SpokenSummaryGenerator.generateCancellationSummary()
        assertEquals("I've cancelled the remaining actions.", summary)
    }

    @Test
    fun testPartialWorkflowWithCancellationsAndPrepared() {
        val actions = listOf(
            ParsedAction(
                type = ActionType.CALENDAR,
                title = "Meeting",
                executionState = ExecutionState.SUCCESS
            ),
            ParsedAction(
                type = ActionType.MESSAGE,
                title = "Msg to Rahul",
                recipient = "Rahul",
                executionState = ExecutionState.PREPARED
            ),
            ParsedAction(
                type = ActionType.CALL,
                title = "Call Mom",
                executionState = ExecutionState.CANCELLED
            )
        )
        val summary = SpokenSummaryGenerator.generateExecutionSummary(actions)
        assertTrue(summary.contains("calendar event is scheduled"))
        assertTrue(summary.contains("ready for your review"))
        assertTrue(summary.contains("cancelled"))
    }
}
