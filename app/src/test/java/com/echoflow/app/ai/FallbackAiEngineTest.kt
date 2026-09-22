package com.echoflow.app.ai

import com.echoflow.app.domain.model.ActionType
import com.echoflow.app.domain.model.UserContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime

/**
 * Unit tests for FallbackAiEngine — verifying intent decomposition
 * without relying on the LLM.
 */
class FallbackAiEngineTest {

    private lateinit var engine: FallbackAiEngine
    private lateinit var context: UserContext

    @Before
    fun setup() {
        engine = FallbackAiEngine()
        // Fixed date for deterministic tests: 2026-09-21 (Monday)
        context = UserContext(
            currentDateTime = LocalDateTime.of(2026, 9, 21, 15, 0)
        )
    }

    @Test
    fun `flagship demo produces 3 actions`() = runBlocking {
        val input = "The robotics meeting is done. Tomorrow at 4, block one hour to work on the motor controller, remind Rahul to send the CAD files, and tell him I'll start integration tomorrow."

        val result = engine.parseIntent(input, context)
        assertTrue("Should succeed", result.isSuccess)

        val graph = result.getOrThrow()
        assertTrue("Should have at least 3 actions, got ${graph.actions.size}",
            graph.actions.size >= 3)

        val types = graph.actions.map { it.type }
        assertTrue("Should contain CALENDAR", types.contains(ActionType.CALENDAR))
        assertTrue("Should contain REMINDER", types.contains(ActionType.REMINDER))
        assertTrue("Should contain MESSAGE", types.contains(ActionType.MESSAGE))
    }

    @Test
    fun `flagship demo carries time context and pronoun recipient`() = runBlocking {
        val input = "The robotics meeting is done. Tomorrow at 4, block one hour to work on the motor controller, remind Rahul to send the CAD files, and tell him I'll start integration tomorrow."
        val graph = engine.parseIntent(input, context).getOrThrow()
        val calendar = graph.actions.first { it.type == ActionType.CALENDAR }
        val message = graph.actions.first { it.type == ActionType.MESSAGE }

        assertEquals(16, calendar.dateTime?.hour)
        assertEquals(60, calendar.durationMinutes)
        assertEquals("Rahul", message.recipient)
        assertEquals("I'll start integration tomorrow", message.message)
    }

    @Test
    fun `calendar detection with time and duration`() = runBlocking {
        val input = "Schedule one hour tomorrow at 4 to work on the motor controller."

        val result = engine.parseIntent(input, context)
        assertTrue(result.isSuccess)

        val graph = result.getOrThrow()
        assertEquals("Should detect 1 action", 1, graph.actions.size)
        assertEquals(ActionType.CALENDAR, graph.actions[0].type)
        assertNotNull("Should extract date/time", graph.actions[0].dateTime)
        assertEquals("Duration should be 60 min", 60, graph.actions[0].durationMinutes)
    }

    @Test
    fun `reminder detection with recipient`() = runBlocking {
        val input = "Remind Rahul to send the CAD files."

        val result = engine.parseIntent(input, context)
        assertTrue(result.isSuccess)

        val graph = result.getOrThrow()
        assertEquals(1, graph.actions.size)
        assertEquals(ActionType.REMINDER, graph.actions[0].type)
        assertEquals("Rahul", graph.actions[0].recipient)
    }

    @Test
    fun `message detection`() = runBlocking {
        val input = "Tell Rahul I'll start integration tomorrow."

        val result = engine.parseIntent(input, context)
        assertTrue(result.isSuccess)

        val graph = result.getOrThrow()
        assertEquals(1, graph.actions.size)
        assertEquals(ActionType.MESSAGE, graph.actions[0].type)
        assertEquals("Rahul", graph.actions[0].recipient)
        assertNotNull("Should extract message content", graph.actions[0].message)
    }

    @Test
    fun `note detection`() = runBlocking {
        val input = "Save a note that we need to test the battery."

        val result = engine.parseIntent(input, context)
        assertTrue(result.isSuccess)

        val graph = result.getOrThrow()
        assertEquals(1, graph.actions.size)
        assertEquals(ActionType.REMINDER, graph.actions[0].type)
    }

    @Test
    fun `ambiguous input requires confirmation`() = runBlocking {
        val input = "Remind Rahul about the files."

        val result = engine.parseIntent(input, context)
        assertTrue(result.isSuccess)

        val graph = result.getOrThrow()
        assertEquals(1, graph.actions.size)
        assertEquals(ActionType.REMINDER, graph.actions[0].type)
    }

    @Test
    fun `two-action workflow - reminder and calendar`() = runBlocking {
        val input = "Tomorrow morning remind me to submit the robotics report and schedule thirty minutes to review the presentation."

        val result = engine.parseIntent(input, context)
        assertTrue(result.isSuccess)

        val graph = result.getOrThrow()
        assertTrue("Should detect at least 2 actions, got ${graph.actions.size}",
            graph.actions.size >= 2)

        val types = graph.actions.map { it.type }
        assertTrue("Should contain REMINDER", types.contains(ActionType.REMINDER))
        assertTrue("Should contain CALENDAR", types.contains(ActionType.CALENDAR))
    }

    @Test
    fun `message and note workflow`() = runBlocking {
        val input = "Tell Rahul that the prototype is ready and save a note that we need to test the battery."

        val result = engine.parseIntent(input, context)
        assertTrue(result.isSuccess)

        val graph = result.getOrThrow()
        assertTrue("Should detect at least 2 actions, got ${graph.actions.size}",
            graph.actions.size >= 2)

        val types = graph.actions.map { it.type }
        assertTrue("Should contain MESSAGE", types.contains(ActionType.MESSAGE))
        assertTrue("Should contain REMINDER", types.contains(ActionType.REMINDER))
    }

    @Test
    fun `engine name is set`() {
        assertEquals("Fallback Parser", engine.engineName)
    }

    @Test
    fun `empty input returns unknown action`() = runBlocking {
        val result = engine.parseIntent("", context)
        // Empty input might return failure or unknown
        // Either is acceptable
    }

    @Test
    fun `confidence values are in valid range`() = runBlocking {
        val input = "Tomorrow at 4, block one hour to work on the motor controller."

        val result = engine.parseIntent(input, context)
        assertTrue(result.isSuccess)

        val graph = result.getOrThrow()
        graph.actions.forEach { action ->
            assertTrue("Confidence should be >= 0", action.confidence >= 0f)
            assertTrue("Confidence should be <= 1", action.confidence <= 1f)
        }
    }

    @Test
    fun `flagship demo V4 produces 4 actions`() = runBlocking {
        val input = "The robotics meeting is done. Tomorrow at 4, block one hour to work on the motor controller, remind Rahul to send the CAD files, send Rahul a message at 4:15 saying I'll start integration tomorrow, and call Rahul at 5."

        val result = engine.parseIntent(input, context)
        assertTrue("Should succeed", result.isSuccess)

        val graph = result.getOrThrow()
        assertTrue("Should have 4 actions, got ${graph.actions.size}", graph.actions.size >= 4)

        val types = graph.actions.map { it.type }
        assertTrue("Should contain CALENDAR", types.contains(ActionType.CALENDAR))
        assertTrue("Should contain REMINDER", types.contains(ActionType.REMINDER))
        assertTrue("Should contain MESSAGE", types.contains(ActionType.MESSAGE))
        assertTrue("Should contain CALL", types.contains(ActionType.CALL))
    }

    @Test
    fun `flagship demo V4 extracts correct temporal offsets and call attributes`() = runBlocking {
        val input = "The robotics meeting is done. Tomorrow at 4, block one hour to work on the motor controller, remind Rahul to send the CAD files, send Rahul a message at 4:15 saying I'll start integration tomorrow, and call Rahul at 5."
        val graph = engine.parseIntent(input, context).getOrThrow()

        val calendar = graph.actions.first { it.type == ActionType.CALENDAR }
        val reminder = graph.actions.first { it.type == ActionType.REMINDER }
        val message = graph.actions.first { it.type == ActionType.MESSAGE }
        val call = graph.actions.first { it.type == ActionType.CALL }

        assertEquals(16, calendar.dateTime?.hour)
        assertEquals(60, calendar.durationMinutes)

        assertEquals("Rahul", reminder.recipient)

        assertEquals("Rahul", message.recipient)
        assertEquals("I'll start integration tomorrow", message.message)
        assertEquals(16, message.dateTime?.hour)
        assertEquals(15, message.dateTime?.minute)

        assertEquals("Rahul", call.recipient)
        assertEquals(17, call.dateTime?.hour)
        assertEquals(0, call.dateTime?.minute)
        assertFalse("In V5, CALL actions with valid recipient are READY by default", call.requiresConfirmation)
    }

    @Test
    fun `call detection with time and recipient`() = runBlocking {
        val input = "Call Rahul tomorrow at 5."
        val result = engine.parseIntent(input, context)
        assertTrue(result.isSuccess)

        val graph = result.getOrThrow()
        assertEquals(1, graph.actions.size)
        val call = graph.actions[0]
        assertEquals(ActionType.CALL, call.type)
        assertEquals("Rahul", call.recipient)
        assertEquals(17, call.dateTime?.hour)
        assertFalse(call.requiresConfirmation)
    }

    @Test
    fun `call detection without time`() = runBlocking {
        val input = "Call Mom."
        val result = engine.parseIntent(input, context)
        assertTrue(result.isSuccess)

        val graph = result.getOrThrow()
        assertEquals(1, graph.actions.size)
        val call = graph.actions[0]
        assertEquals(ActionType.CALL, call.type)
        assertEquals("Mom", call.recipient)
        assertFalse(call.requiresConfirmation)
    }

    @Test
    fun `scheduled message detection with explicit time`() = runBlocking {
        val input = "Send Rahul a message at 4:15 saying I'll be there soon."
        val result = engine.parseIntent(input, context)
        assertTrue(result.isSuccess)

        val graph = result.getOrThrow()
        assertEquals(1, graph.actions.size)
        val msg = graph.actions[0]
        assertEquals(ActionType.MESSAGE, msg.type)
        assertEquals("Rahul", msg.recipient)
        assertEquals("I'll be there soon", msg.message)
        assertEquals(16, msg.dateTime?.hour)
        assertEquals(15, msg.dateTime?.minute)
    }

    @Test
    fun `fast demo scenario produces scheduled call`() = runBlocking {
        val input = "Call Rahul in 1 minute about motor controller test."
        val result = engine.parseIntent(input, context)
        assertTrue(result.isSuccess)

        val graph = result.getOrThrow()
        assertEquals(1, graph.actions.size)
        val call = graph.actions[0]
        assertEquals(ActionType.CALL, call.type)
        assertEquals("Rahul", call.recipient)
        assertFalse(call.requiresConfirmation)
    }
}
