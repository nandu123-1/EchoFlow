package com.echoflow.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionStateAndModeTest {

    @Test
    fun testExecutionStateTerminalStates() {
        assertTrue(ExecutionState.SUCCESS.isTerminal)
        assertTrue(ExecutionState.COMPLETED.isTerminal)
        assertTrue(ExecutionState.FAILED.isTerminal)
        assertTrue(ExecutionState.CANCELLED.isTerminal)

        assertFalse(ExecutionState.PENDING.isTerminal)
        assertFalse(ExecutionState.PREPARED.isTerminal)
        assertFalse(ExecutionState.SCHEDULED.isTerminal)
        assertFalse(ExecutionState.IN_PROGRESS.isTerminal)
        assertFalse(ExecutionState.WAITING_FOR_USER.isTerminal)
    }

    @Test
    fun testExecutionStatePendingOrScheduled() {
        assertTrue(ExecutionState.PENDING.isPendingOrScheduled)
        assertTrue(ExecutionState.PREPARED.isPendingOrScheduled)
        assertTrue(ExecutionState.SCHEDULED.isPendingOrScheduled)
        assertTrue(ExecutionState.WAITING_FOR_USER.isPendingOrScheduled)
        assertTrue(ExecutionState.IN_PROGRESS.isPendingOrScheduled)

        assertFalse(ExecutionState.SUCCESS.isPendingOrScheduled)
        assertFalse(ExecutionState.COMPLETED.isPendingOrScheduled)
        assertFalse(ExecutionState.FAILED.isPendingOrScheduled)
        assertFalse(ExecutionState.CANCELLED.isPendingOrScheduled)
        assertFalse(ExecutionState.SKIPPED.isPendingOrScheduled)
    }

    @Test
    fun testExecutionStateCancelled() {
        assertTrue(ExecutionState.CANCELLED.isCancelled)
        assertFalse(ExecutionState.PENDING.isCancelled)
        assertFalse(ExecutionState.SUCCESS.isCancelled)
        assertFalse(ExecutionState.FAILED.isCancelled)
    }

    @Test
    fun testExecutionModeValues() {
        val modes = ExecutionMode.values().map { it.name }
        assertTrue(modes.contains("IMMEDIATE"))
        assertTrue(modes.contains("SCHEDULED"))
        assertTrue(modes.contains("PROACTIVE_CONFIRMATION"))
    }

    @Test
    fun testActionTypeCallPresence() {
        val types = ActionType.values().map { it.name }
        assertTrue("ActionType must contain CALL", types.contains("CALL"))
        assertTrue(types.contains("CALENDAR"))
        assertTrue(types.contains("REMINDER"))
        assertTrue(types.contains("MESSAGE"))
        assertTrue(types.contains("NOTE"))
    }

    @Test
    fun testParsedActionWithCallAttributes() {
        val callAction = ParsedAction(
            type = ActionType.CALL,
            title = "Call Rahul",
            recipient = "Rahul",
            recipientPhone = "+919876543210",
            executionMode = ExecutionMode.PROACTIVE_CONFIRMATION,
            confidence = 0.95f,
            requiresConfirmation = true,
            executionState = ExecutionState.SCHEDULED,
            resolvedEpochMillis = 1774350000000L
        )

        assertEquals(ActionType.CALL, callAction.actionType)
        assertEquals("Rahul", callAction.recipient)
        assertEquals("+919876543210", callAction.recipientPhone)
        assertEquals(ExecutionMode.PROACTIVE_CONFIRMATION, callAction.executionMode)
        assertTrue(callAction.requiresConfirmation)
        assertEquals(ExecutionState.SCHEDULED, callAction.executionState)
        assertEquals(1774350000000L, callAction.resolvedEpochMillis)
    }

    @Test
    fun testParsedActionDefaultRecipientPhone() {
        val messageAction = ParsedAction(
            type = ActionType.MESSAGE,
            title = "Message Rahul",
            recipient = "Rahul",
            confidence = 0.9f
        )
        assertNull(messageAction.recipientPhone)
        assertEquals(ExecutionMode.IMMEDIATE, messageAction.executionMode)
    }
}
