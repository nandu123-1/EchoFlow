package com.echoflow.app.ambient

import com.echoflow.app.domain.model.ActionGraph
import com.echoflow.app.domain.model.ActionType
import com.echoflow.app.domain.model.ExecutionState
import com.echoflow.app.domain.model.ParsedAction
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AmbientControllerTest {

    @Before
    fun setUp() {
        AmbientController.hideAmbient()
    }

    @Test
    fun testInitialState() {
        assertEquals(AmbientPhase.HIDDEN, AmbientController.sessionState.value.phase)
        assertFalse(AmbientController.isAmbientVisible())
        assertFalse(AmbientController.isServiceConnected())
    }

    @Test
    fun testConfirmAction() {
        val action = ParsedAction(
            id = "test-1",
            type = ActionType.REMINDER,
            title = "Test Reminder",
            executionState = ExecutionState.NEEDS_CONFIRMATION,
            requiresConfirmation = true
        )
        val graph = ActionGraph(originalInput = "Test", actions = listOf(action), overallConfidence = 0.95f)

        // Simulate review state
        val stateField = AmbientController.javaClass.getDeclaredField("_sessionState")
        stateField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val mutableFlow = stateField.get(AmbientController) as kotlinx.coroutines.flow.MutableStateFlow<AmbientSessionState>
        mutableFlow.value = AmbientSessionState(
            phase = AmbientPhase.READY_FOR_REVIEW,
            actionGraph = graph,
            executionStates = mapOf("test-1" to ExecutionState.NEEDS_CONFIRMATION)
        )

        AmbientController.confirmAction("test-1")

        assertEquals(ExecutionState.READY, AmbientController.sessionState.value.executionStates["test-1"])
        val updatedAction = AmbientController.sessionState.value.actionGraph?.actions?.find { it.id == "test-1" }
        assertNotNull(updatedAction)
        assertEquals(ExecutionState.READY, updatedAction?.executionState)
        assertFalse(updatedAction?.requiresConfirmation ?: true)
    }

    @Test
    fun testDeleteAction() {
        val action1 = ParsedAction(id = "test-1", type = ActionType.REMINDER, title = "Action 1")
        val action2 = ParsedAction(id = "test-2", type = ActionType.MESSAGE, title = "Action 2")
        val graph = ActionGraph(originalInput = "Test", actions = listOf(action1, action2), overallConfidence = 0.95f)

        val stateField = AmbientController.javaClass.getDeclaredField("_sessionState")
        stateField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val mutableFlow = stateField.get(AmbientController) as kotlinx.coroutines.flow.MutableStateFlow<AmbientSessionState>
        mutableFlow.value = AmbientSessionState(
            phase = AmbientPhase.READY_FOR_REVIEW,
            actionGraph = graph,
            executionStates = mapOf("test-1" to ExecutionState.READY, "test-2" to ExecutionState.READY)
        )

        AmbientController.deleteAction("test-1")

        assertEquals(1, AmbientController.sessionState.value.actionGraph?.actions?.size)
        assertEquals("test-2", AmbientController.sessionState.value.actionGraph?.actions?.first()?.id)
        assertNull(AmbientController.sessionState.value.executionStates["test-1"])
    }

    @Test
    fun testHideAmbient() {
        val stateField = AmbientController.javaClass.getDeclaredField("_sessionState")
        stateField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val mutableFlow = stateField.get(AmbientController) as kotlinx.coroutines.flow.MutableStateFlow<AmbientSessionState>
        mutableFlow.value = AmbientSessionState(phase = AmbientPhase.LISTENING)

        assertTrue(AmbientController.isAmbientVisible())

        AmbientController.hideAmbient()

        assertEquals(AmbientPhase.HIDDEN, AmbientController.sessionState.value.phase)
        assertFalse(AmbientController.isAmbientVisible())
    }
}
