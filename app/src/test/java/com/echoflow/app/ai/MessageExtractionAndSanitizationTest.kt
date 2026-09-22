package com.echoflow.app.ai

import com.echoflow.app.domain.model.ActionType
import com.echoflow.app.domain.model.ParsedAction
import com.echoflow.app.domain.model.UserContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime

/**
 * Unit tests verifying Bug #1 fix:
 * Precise extraction and sanitization of SMS message bodies.
 * Ensures command words, recipient name, temporal clauses, and markers
 * are completely stripped, and message body never equals original input.
 */
class MessageExtractionAndSanitizationTest {

    private lateinit var engine: FallbackAiEngine
    private lateinit var context: UserContext

    @Before
    fun setup() {
        engine = FallbackAiEngine()
        // Deterministic context: Monday 2026-09-21 at 15:00
        val fixedInstant = java.time.LocalDateTime.of(2026, 9, 21, 15, 0)
            .atZone(java.time.ZoneId.systemDefault())
            .toInstant()
        context = UserContext(
            requestCreatedAt = fixedInstant,
            currentDateTime = LocalDateTime.of(2026, 9, 21, 15, 0)
        )
    }

    @Test
    fun `test exact bug 1 query extracts clean message and recipient`() = runBlocking {
        val input = "message to rahul after 1minute that i am coming working"

        val result = engine.parseIntent(input, context)
        assertTrue("Parsing should succeed", result.isSuccess)

        val graph = result.getOrThrow()
        val messageAction = graph.actions.firstOrNull { it.type == ActionType.MESSAGE }
        assertNotNull("Should contain MESSAGE action", messageAction)

        assertEquals("Recipient should be Rahul", "Rahul", messageAction?.recipient)
        assertEquals("Message must be exact payload capitalized", "I am coming working", messageAction?.message)
        assertNotEquals("Message must NOT equal original input", input, messageAction?.message)
        assertFalse("Message must NOT contain 'message to'", messageAction?.message?.lowercase()?.contains("message to") == true)
        assertFalse("Message must NOT contain 'after 1minute'", messageAction?.message?.lowercase()?.contains("after 1minute") == true)
        assertFalse("Message must NOT contain 'after 1 minute'", messageAction?.message?.lowercase()?.contains("after 1 minute") == true)

        // Verify time resolution: ~1 minute from 15:00 -> 15:01
        assertNotNull("DateTime should be scheduled", messageAction?.dateTime)
        assertEquals("Should be scheduled for minute 1", 1, messageAction?.dateTime?.minute)
    }

    @Test
    fun `test message with saying marker extracts clean payload`() = runBlocking {
        val input = "send a message to Rahul tomorrow at 5 saying I will call you"

        val result = engine.parseIntent(input, context)
        assertTrue(result.isSuccess)

        val graph = result.getOrThrow()
        val action = graph.actions.first { it.type == ActionType.MESSAGE }

        assertEquals("Rahul", action.recipient)
        assertEquals("I will call you", action.message)
    }

    @Test
    fun `test text with after minutes and that marker`() = runBlocking {
        val input = "text Rahul after 2 minutes that I am outside"

        val result = engine.parseIntent(input, context)
        assertTrue(result.isSuccess)

        val graph = result.getOrThrow()
        val action = graph.actions.first { it.type == ActionType.MESSAGE }

        assertEquals("Rahul", action.recipient)
        assertEquals("I am outside", action.message)
        assertEquals(2, action.dateTime?.minute)
    }

    @Test
    fun `test tell them marker extracts payload`() = runBlocking {
        val input = "message to John after 5 minutes tell them we have arrived"

        val result = engine.parseIntent(input, context)
        assertTrue(result.isSuccess)

        val graph = result.getOrThrow()
        val action = graph.actions.first { it.type == ActionType.MESSAGE }

        assertEquals("John", action.recipient)
        assertEquals("We have arrived", action.message)
    }

    @Test
    fun `test nested that preserved in message payload`() = runBlocking {
        val input = "message to Rahul that I told John that we are ready"

        val result = engine.parseIntent(input, context)
        assertTrue(result.isSuccess)

        val graph = result.getOrThrow()
        val action = graph.actions.first { it.type == ActionType.MESSAGE }

        assertEquals("Rahul", action.recipient)
        assertEquals("I told John that we are ready", action.message)
    }

    @Test
    fun `test punctuation and numbers preserved in message`() = runBlocking {
        val input = "message to Priya saying meeting is at room 402, don't be late!"

        val result = engine.parseIntent(input, context)
        assertTrue(result.isSuccess)

        val graph = result.getOrThrow()
        val action = graph.actions.first { it.type == ActionType.MESSAGE }

        assertEquals("Priya", action.recipient)
        assertEquals("Meeting is at room 402, don't be late!", action.message)
    }

    @Test
    fun `test ActionParser validateAction strips that marker from message`() {
        val action = ParsedAction(
            id = "test-1",
            type = ActionType.MESSAGE,
            title = "Send message to Rahul",
            recipient = "Rahul",
            message = "that i am coming working",
            confidence = 0.9f
        )

        val originalInput = "message to rahul after 1minute that i am coming working"
        val validated = ActionParser.validateAction(action, originalInput)

        assertEquals("I am coming working", validated.message)
        assertFalse(validated.requiresConfirmation)
    }

    @Test
    fun `test ActionParser rejects and flags confirmation if command prefix leaked into message`() {
        val dirtyAction = ParsedAction(
            id = "test-2",
            type = ActionType.MESSAGE,
            title = "Send message to Rahul",
            recipient = "Rahul",
            message = "message to rahul that i am coming working",
            confidence = 0.9f
        )

        val originalInput = "message to rahul after 1minute that i am coming working"
        val validated = ActionParser.validateAction(dirtyAction, originalInput)

        assertNull("Message should be null when dirty prefix leaked", validated.message)
        assertTrue("Should require confirmation", validated.requiresConfirmation)
    }

    @Test
    fun `test ActionParser flags confirmation if raw message equals original input`() {
        val input = "message to rahul after 1minute that i am coming working"
        val rawAction = ParsedAction(
            id = "test-3",
            type = ActionType.MESSAGE,
            title = "Send message to Rahul",
            recipient = "Rahul",
            message = input, // raw input leaked as message
            confidence = 0.9f
        )

        val validated = ActionParser.validateAction(rawAction, input)

        assertNull("Message must be null if equal to original input", validated.message)
        assertTrue("Must require confirmation", validated.requiresConfirmation)
    }

    @Test
    fun `test ActionParser flags confirmation when message is completely empty or ambiguous`() {
        val input = "send a message to Rahul"
        val emptyMessageAction = ParsedAction(
            id = "test-4",
            type = ActionType.MESSAGE,
            title = "Send message to Rahul",
            recipient = "Rahul",
            message = null,
            confidence = 0.9f
        )

        val validated = ActionParser.validateAction(emptyMessageAction, input)
        assertTrue("Action with missing message should require confirmation", validated.requiresConfirmation)
        assertNull(validated.message)
    }
}
