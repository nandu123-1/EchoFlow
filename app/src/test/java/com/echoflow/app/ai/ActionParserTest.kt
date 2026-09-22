package com.echoflow.app.ai

import com.echoflow.app.domain.model.ActionType
import com.echoflow.app.domain.model.ExecutionMode
import com.echoflow.app.domain.model.UserContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Unit tests for ActionParser validating compact JSON output without null keys.
 */
class ActionParserTest {

    private val fixedInstant = Instant.parse("2026-09-22T10:00:00Z")
    private val zoneId = ZoneId.of("Asia/Calcutta")
    private val currentDateTime = LocalDateTime.ofInstant(fixedInstant, zoneId)
    private val context = UserContext(
        requestCreatedAt = fixedInstant,
        timeZone = zoneId,
        currentDateTime = currentDateTime
    )

    @Test
    fun testParseCompactReminderOmittedNulls() {
        val json = """
            {
              "actions": [
                {
                  "type": "REMINDER",
                  "title": "Drink water",
                  "timeExpression": "after 5 minutes",
                  "confidence": 0.95
                }
              ]
            }
        """.trimIndent()

        val result = ActionParser.parse(json, "remind me to drink water after 5 minutes", context)
        assertTrue(result.isSuccess)

        val graph = result.getOrThrow()
        assertEquals(1, graph.actions.size)

        val action = graph.actions[0]
        assertEquals(ActionType.REMINDER, action.type)
        assertEquals("Drink water", action.title)
        assertEquals("after 5 minutes", action.timeExpression)
        assertEquals(0.95f, action.confidence, 0.01f)
        assertFalse(action.requiresConfirmation)
        assertEquals(fixedInstant.plusSeconds(300).toEpochMilli(), action.resolvedEpochMillis)
    }

    @Test
    fun testParseCompactMultiAction() {
        val json = """
            {
              "actions": [
                {
                  "type": "CALENDAR",
                  "title": "Robotics meeting",
                  "timeExpression": "tomorrow at 4pm",
                  "durationMinutes": 60,
                  "confidence": 0.9
                },
                {
                  "type": "MESSAGE",
                  "title": "Message Rahul",
                  "recipient": "Rahul",
                  "message": "Send the CAD files",
                  "confidence": 0.85
                },
                {
                  "type": "NOTE",
                  "title": "Motor controller spec",
                  "description": "Reviewed with team",
                  "confidence": 0.95
                }
              ]
            }
        """.trimIndent()

        val result = ActionParser.parse(json, "tomorrow meeting and message rahul and note", context)
        assertTrue(result.isSuccess)

        val graph = result.getOrThrow()
        assertEquals(3, graph.actions.size)

        assertEquals(ActionType.CALENDAR, graph.actions[0].type)
        assertEquals("Robotics meeting", graph.actions[0].title)
        assertEquals(60, graph.actions[0].durationMinutes)

        assertEquals(ActionType.MESSAGE, graph.actions[1].type)
        assertEquals("Rahul", graph.actions[1].recipient)
        assertEquals("Send the CAD files", graph.actions[1].message)

        assertEquals(ActionType.REMINDER, graph.actions[2].type)
        assertEquals("Motor controller spec", graph.actions[2].title)
    }

    @Test
    fun testParseAmbiguousActionRequiresConfirmation() {
        val json = """
            {
              "actions": [
                {
                  "type": "MESSAGE",
                  "title": "Send report",
                  "requiresConfirmation": true,
                  "ambiguityReason": "Recipient not specified"
                }
              ]
            }
        """.trimIndent()

        val result = ActionParser.parse(json, "send report", context)
        assertTrue(result.isSuccess)

        val action = result.getOrThrow().actions[0]
        assertTrue(action.requiresConfirmation)
        assertEquals("Recipient not specified", action.ambiguityReason)
    }

    @Test
    fun testParseJsonWrappedInMarkdownBlocks() {
        val json = """
            ```json
            {
              "actions": [
                {
                  "type": "NOTE",
                  "title": "Quick note"
                }
              ]
            }
            ```
        """.trimIndent()

        val result = ActionParser.parse(json, "quick note", context)
        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrThrow().actions.size)
        assertEquals("Quick note", result.getOrThrow().actions[0].title)
    }

    @Test
    fun testParseCallActionWithConfirmation() {
        val json = """
            {
              "actions": [
                {
                  "type": "CALL",
                  "title": "Call Rahul",
                  "recipient": "Rahul",
                  "recipientPhone": "+919876543210",
                  "timeExpression": "tomorrow at 5pm",
                  "confidence": 0.92
                }
              ]
            }
        """.trimIndent()

        val result = ActionParser.parse(json, "call Rahul tomorrow at 5", context)
        assertTrue(result.isSuccess)
        val action = result.getOrThrow().actions[0]
        assertEquals(ActionType.CALL, action.type)
        assertEquals("Rahul", action.recipient)
        assertEquals("+919876543210", action.recipientPhone)
        assertFalse("CALL action with known recipient does not require pre-confirmation in V5", action.requiresConfirmation)
        assertEquals(ExecutionMode.PROACTIVE_CONFIRMATION, action.executionMode)
    }

    @Test
    fun testParseScheduledMessageWithTime() {
        val json = """
            {
              "actions": [
                {
                  "type": "MESSAGE",
                  "title": "Message Rahul",
                  "recipient": "Rahul",
                  "message": "I will start integration tomorrow",
                  "timeExpression": "tomorrow at 4:15pm",
                  "confidence": 0.90
                }
              ]
            }
        """.trimIndent()

        val result = ActionParser.parse(json, "send Rahul a message tomorrow at 4:15", context)
        assertTrue(result.isSuccess)
        val action = result.getOrThrow().actions[0]
        assertEquals(ActionType.MESSAGE, action.type)
        assertEquals(ExecutionMode.SCHEDULED, action.executionMode)
        assertNotNull(action.dateTime)
    }

    @Test
    fun testParseFlagshipFourActionWorkflow() {
        val json = """
            {
              "actions": [
                {
                  "type": "CALENDAR",
                  "title": "Motor controller work",
                  "timeExpression": "tomorrow at 4pm",
                  "durationMinutes": 60,
                  "confidence": 0.92
                },
                {
                  "type": "REMINDER",
                  "title": "Remind Rahul to send CAD files",
                  "recipient": "Rahul",
                  "timeExpression": "tomorrow at 4pm",
                  "confidence": 0.89
                },
                {
                  "type": "MESSAGE",
                  "title": "Message Rahul",
                  "recipient": "Rahul",
                  "message": "I'll start integration tomorrow",
                  "timeExpression": "tomorrow at 4:15pm",
                  "confidence": 0.91
                },
                {
                  "type": "CALL",
                  "title": "Call Rahul",
                  "recipient": "Rahul",
                  "timeExpression": "tomorrow at 5pm",
                  "confidence": 0.94
                }
              ]
            }
        """.trimIndent()

        val result = ActionParser.parse(json, "robotics workflow", context)
        assertTrue(result.isSuccess)
        val graph = result.getOrThrow()
        assertEquals(4, graph.actions.size)

        assertEquals(ActionType.CALENDAR, graph.actions[0].type)
        assertEquals(ActionType.REMINDER, graph.actions[1].type)
        assertEquals(ActionType.MESSAGE, graph.actions[2].type)
        assertEquals(ActionType.CALL, graph.actions[3].type)

        assertEquals(ExecutionMode.SCHEDULED, graph.actions[2].executionMode)
        assertEquals(ExecutionMode.PROACTIVE_CONFIRMATION, graph.actions[3].executionMode)
        assertFalse(graph.actions[3].requiresConfirmation)
    }
}
