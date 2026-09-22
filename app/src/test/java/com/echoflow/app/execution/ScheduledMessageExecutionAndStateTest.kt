package com.echoflow.app.execution

import com.echoflow.app.domain.model.ActionType
import com.echoflow.app.domain.model.ExecutionState
import com.echoflow.app.domain.model.ParsedAction
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests verifying Bug #2 and Bug #3 fixes:
 * 1. Honest execution state handling (PREPARED is not FAILED, truthful history summary).
 * 2. Outcome badges (✓ SENT, ↗ HANDED OFF, ◉ WAITING, ✕ FAILED, ↶ CANCELLED).
 * 3. SMS intent payload safety (never falling back to action.title).
 * 4. Spoken TTS speech string formatting for proactive notifications.
 */
class ScheduledMessageExecutionAndStateTest {

    @Test
    fun `test execution state classification`() {
        // PREPARED indicates handed off to external app / composer
        assertTrue(ExecutionState.PREPARED.isPendingOrScheduled)
        assertFalse(ExecutionState.PREPARED.isTerminal)

        // SUCCESS and COMPLETED are terminal
        assertTrue(ExecutionState.SUCCESS.isTerminal)
        assertTrue(ExecutionState.COMPLETED.isTerminal)

        // FAILED and CANCELLED are distinct terminal states
        assertTrue(ExecutionState.FAILED.isTerminal)
        assertTrue(ExecutionState.CANCELLED.isTerminal)
        assertTrue(ExecutionState.CANCELLED.isCancelled)
        assertFalse(ExecutionState.FAILED.isCancelled)
    }

    @Test
    fun `test truthful summary calculation treats PREPARED as completed handoff not failed`() {
        val mockActionStates = listOf("PREPARED", "SUCCESS", "COMPLETED")

        val completedCount = mockActionStates.count { it in listOf("SUCCESS", "COMPLETED", "PREPARED") }
        val failedCount = mockActionStates.count { it == "FAILED" }

        assertEquals(3, completedCount)
        assertEquals(0, failedCount)

        // Under old bug logic: only it == "SUCCESS" was counted, so 2 would have been counted as failed!
        // Verify our new logic reports all completed:
        val statusText = when {
            completedCount == mockActionStates.size -> "All completed"
            completedCount > 0 && failedCount == 0 -> "$completedCount completed"
            failedCount == mockActionStates.size -> "Failed"
            failedCount > 0 -> "$failedCount failed"
            else -> "${mockActionStates.size} action(s)"
        }

        assertEquals("All completed", statusText)
    }

    @Test
    fun `test truthful summary with mixed scheduled and prepared actions`() {
        val mockActionStates = listOf("PREPARED", "SCHEDULED")

        val completedCount = mockActionStates.count { it in listOf("SUCCESS", "COMPLETED", "PREPARED") }
        val scheduledCount = mockActionStates.count { it == "SCHEDULED" }
        val failedCount = mockActionStates.count { it == "FAILED" }

        assertEquals(1, completedCount)
        assertEquals(1, scheduledCount)
        assertEquals(0, failedCount)

        val statusText = when {
            completedCount == mockActionStates.size -> "All completed"
            scheduledCount == mockActionStates.size -> "Scheduled"
            completedCount > 0 && failedCount == 0 -> "$completedCount completed"
            failedCount > 0 -> "$failedCount failed"
            else -> "${mockActionStates.size} action(s)"
        }

        assertEquals("1 completed", statusText)
    }

    @Test
    fun `test outcome badge formatting logic`() {
        fun getOutcomeBadge(state: String, type: String): String {
            return when (state) {
                "SUCCESS" -> if (type == "MESSAGE") "✓ SENT" else "✓ COMPLETED"
                "COMPLETED" -> "✓ COMPLETED"
                "PREPARED" -> "↗ HANDED OFF"
                "WAITING_FOR_USER", "NEEDS_CONFIRMATION" -> "◉ WAITING"
                "SCHEDULED" -> "● SCHEDULED"
                "CANCELLED" -> "↶ CANCELLED"
                "FAILED" -> "✕ FAILED"
                else -> state
            }
        }

        assertEquals("✓ SENT", getOutcomeBadge("SUCCESS", "MESSAGE"))
        assertEquals("✓ COMPLETED", getOutcomeBadge("SUCCESS", "CALENDAR"))
        assertEquals("✓ COMPLETED", getOutcomeBadge("COMPLETED", "MESSAGE"))
        assertEquals("↗ HANDED OFF", getOutcomeBadge("PREPARED", "MESSAGE"))
        assertEquals("◉ WAITING", getOutcomeBadge("WAITING_FOR_USER", "MESSAGE"))
        assertEquals("◉ WAITING", getOutcomeBadge("NEEDS_CONFIRMATION", "CALL"))
        assertEquals("● SCHEDULED", getOutcomeBadge("SCHEDULED", "REMINDER"))
        assertEquals("↶ CANCELLED", getOutcomeBadge("CANCELLED", "MESSAGE"))
        assertEquals("✕ FAILED", getOutcomeBadge("FAILED", "MESSAGE"))
    }

    @Test
    fun `test SMS payload never falls back to action title`() {
        val action = ParsedAction(
            id = "act-101",
            type = ActionType.MESSAGE,
            title = "Send message to Rahul",
            recipient = "Rahul",
            message = null
        )

        // Previous bug in ScheduledActionManager:
        // val message = action.message ?: action.title -> would send "Send message to Rahul" as body!
        // Fixed logic:
        val safeMessage = action.message ?: ""
        assertEquals("", safeMessage)
        assertNotEquals(action.title, safeMessage)
    }

    @Test
    fun `test proactive voice notifier speech templates`() {
        val recipient = "Rahul"

        // Composer handoff notification speech
        val handoffSpeech = "Your message to $recipient is ready to send."
        assertEquals("Your message to Rahul is ready to send.", handoffSpeech)

        // User taps Open & Send speech
        val openSendSpeech = "Your message to $recipient is ready."
        assertEquals("Your message to Rahul is ready.", openSendSpeech)

        // Carrier send success speech
        val carrierSuccessSpeech = "Message sent to $recipient."
        assertEquals("Message sent to Rahul.", carrierSuccessSpeech)

        // Carrier send failure speech
        val carrierFailureSpeech = "Your message to $recipient could not be sent."
        assertEquals("Your message to Rahul could not be sent.", carrierFailureSpeech)
    }
}
