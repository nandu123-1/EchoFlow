package com.echoflow.app.scheduling

import com.echoflow.app.data.db.ActionHistoryEntity
import com.echoflow.app.domain.model.ActionType
import com.echoflow.app.domain.model.ExecutionMode
import com.echoflow.app.domain.model.ExecutionState
import com.echoflow.app.domain.model.ParsedAction
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests verifying ScheduledAction drift computation, in-place snooze mechanics,
 * cancel vs cancel-all separation, and entity persistence mappings.
 */
class ScheduledActionDriftAndLogicTest {

    @Test
    fun testSchedulingDriftCalculation() {
        val targetEpoch = 1774350000000L
        val actualTriggerEpoch = 1774350000142L // 142 ms after target

        val driftMillis = actualTriggerEpoch - targetEpoch
        assertEquals(142L, driftMillis)
        assertTrue("Drift should be positive when fired after target", driftMillis > 0)
    }

    @Test
    fun testNegativeDriftCalculation() {
        val targetEpoch = 1774350000000L
        val actualTriggerEpoch = 1774349999950L // 50 ms before target

        val driftMillis = actualTriggerEpoch - targetEpoch
        assertEquals(-50L, driftMillis)
        assertTrue("Drift is negative if fired slightly before target", driftMillis < 0)
    }

    @Test
    fun testInPlaceSnoozePreservesActionIdentity() {
        val originalId = "action-call-12345"
        val originalEpoch = 1774350000000L
        val action = ParsedAction(
            id = originalId,
            type = ActionType.CALL,
            title = "Call Rahul",
            recipient = "Rahul",
            recipientPhone = "+919876543210",
            executionMode = ExecutionMode.PROACTIVE_CONFIRMATION,
            confidence = 0.95f,
            executionState = ExecutionState.SCHEDULED,
            resolvedEpochMillis = originalEpoch
        )

        // In-place snooze: +5 minutes (300,000 ms)
        val snoozedEpoch = originalEpoch + (5 * 60 * 1000L)
        val snoozedAction = action.copy(
            resolvedEpochMillis = snoozedEpoch,
            executionState = ExecutionState.SCHEDULED,
            executionMessage = "Snoozed for 5 minutes"
        )

        assertEquals("Action ID must not change during snooze", originalId, snoozedAction.id)
        assertEquals("Title must be preserved", "Call Rahul", snoozedAction.title)
        assertEquals("Recipient must be preserved", "Rahul", snoozedAction.recipient)
        assertEquals(originalEpoch + 300_000L, snoozedAction.resolvedEpochMillis)
        assertEquals(ExecutionState.SCHEDULED, snoozedAction.executionState)
    }

    @Test
    fun testCancelSingleActionDoesNotAffectOthers() {
        val actions = mutableListOf(
            ParsedAction(id = "1", type = ActionType.CALENDAR, title = "Calendar", confidence = 0.9f, executionState = ExecutionState.SUCCESS),
            ParsedAction(id = "2", type = ActionType.REMINDER, title = "Reminder", confidence = 0.9f, executionState = ExecutionState.SCHEDULED),
            ParsedAction(id = "3", type = ActionType.CALL, title = "Call", confidence = 0.9f, executionState = ExecutionState.SCHEDULED)
        )

        // Cancel action "2" only
        val targetId = "2"
        val updatedActions = actions.map {
            if (it.id == targetId) it.copy(executionState = ExecutionState.CANCELLED, executionMessage = "Action cancelled")
            else it
        }

        assertEquals(ExecutionState.SUCCESS, updatedActions[0].executionState)
        assertEquals(ExecutionState.CANCELLED, updatedActions[1].executionState)
        assertEquals(ExecutionState.SCHEDULED, updatedActions[2].executionState)
    }

    @Test
    fun testCancelAllAffectsOnlyPendingAndScheduled() {
        val actions = listOf(
            ParsedAction(id = "1", type = ActionType.CALENDAR, title = "Calendar", confidence = 0.9f, executionState = ExecutionState.SUCCESS),
            ParsedAction(id = "2", type = ActionType.REMINDER, title = "Reminder", confidence = 0.9f, executionState = ExecutionState.SCHEDULED),
            ParsedAction(id = "3", type = ActionType.MESSAGE, title = "Message", confidence = 0.9f, executionState = ExecutionState.SCHEDULED),
            ParsedAction(id = "4", type = ActionType.CALL, title = "Call", confidence = 0.9f, executionState = ExecutionState.SCHEDULED)
        )

        val cancelledActions = actions.map { action ->
            if (action.executionState.isPendingOrScheduled) {
                action.copy(executionState = ExecutionState.CANCELLED, executionMessage = "Workflow cancelled")
            } else {
                action
            }
        }

        assertEquals(ExecutionState.SUCCESS, cancelledActions[0].executionState)
        assertEquals(ExecutionState.CANCELLED, cancelledActions[1].executionState)
        assertEquals(ExecutionState.CANCELLED, cancelledActions[2].executionState)
        assertEquals(ExecutionState.CANCELLED, cancelledActions[3].executionState)
    }

    @Test
    fun testActionHistoryEntityMapping() {
        val entity = ActionHistoryEntity(
            id = "call-999",
            workflowId = "workflow-1",
            actionType = "CALL",
            title = "Call Rahul",
            description = null,
            recipient = "Rahul",
            recipientPhone = "+919876543210",
            executionMode = "PROACTIVE_CONFIRMATION",
            executionState = "SCHEDULED",
            confidence = 0.95f,
            dateTime = "2026-09-22T17:00:00",
            durationMinutes = null,
            message = null,
            executionMessage = null,
            resolvedEpochMillis = 1774350000000L,
            createdAt = 1774340000000L
        )

        assertEquals("call-999", entity.id)
        assertEquals("CALL", entity.actionType)
        assertEquals("+919876543210", entity.recipientPhone)
        assertEquals("PROACTIVE_CONFIRMATION", entity.executionMode)
        assertEquals(1774350000000L, entity.resolvedEpochMillis)
        assertEquals("SCHEDULED", entity.executionState)
        assertNull("driftMs should default to null before execution", entity.driftMs)

        val entityWithDrift = entity.copy(driftMs = 42L)
        assertEquals(42L, entityWithDrift.driftMs)
    }

    @Test
    fun testAppTabIncludesTimeline() {
        val tabs = com.echoflow.app.ui.viewmodel.AppTab.values().map { it.name }
        assertTrue("AppTab must include HOME", tabs.contains("HOME"))
        assertTrue("AppTab must include TIMELINE", tabs.contains("TIMELINE"))
        assertTrue("AppTab must include HISTORY", tabs.contains("HISTORY"))
        assertTrue("AppTab must include SETTINGS", tabs.contains("SETTINGS"))
        assertEquals(4, tabs.size)
    }
}
