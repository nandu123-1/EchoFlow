package com.echoflow.app.assistant

import com.echoflow.app.domain.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

/**
 * Unit tests for [SpokenSummaryGenerator] — deterministic spoken phrase generation.
 */
class SpokenSummaryGeneratorTest {

    private fun makeGraph(actions: List<ParsedAction>): ActionGraph {
        return ActionGraph(
            originalInput = "test input",
            actions = actions,
            overallConfidence = 0.9f,
            sourceEngine = "Test"
        )
    }

    // --- Review Prompts ---

    @Test
    fun `review prompt for single calendar action`() {
        val action = ParsedAction(
            type = ActionType.CALENDAR,
            title = "Team Sync",
            confidence = 0.95f,
            timeExpression = "tomorrow at 4 PM"
        )
        val result = SpokenSummaryGenerator.generateReviewPrompt(makeGraph(listOf(action)))
        assertTrue("Should mention calendar event", result.contains("calendar event"))
        assertTrue("Should mention title", result.contains("Team Sync"))
        assertTrue("Should ask for confirmation", result.contains("schedule"))
    }

    @Test
    fun `review prompt for single reminder action`() {
        val action = ParsedAction(
            type = ActionType.REMINDER,
            title = "Water Plants",
            confidence = 0.9f,
            timeExpression = "in 10 minutes"
        )
        val result = SpokenSummaryGenerator.generateReviewPrompt(makeGraph(listOf(action)))
        assertTrue("Should mention reminder", result.contains("reminder"))
        assertTrue("Should mention title", result.contains("Water Plants"))
    }

    @Test
    fun `review prompt for single message action`() {
        val action = ParsedAction(
            type = ActionType.MESSAGE,
            title = "Message Rahul",
            recipient = "Rahul",
            confidence = 0.9f
        )
        val result = SpokenSummaryGenerator.generateReviewPrompt(makeGraph(listOf(action)))
        assertTrue("Should mention message", result.contains("message"))
        assertTrue("Should mention recipient", result.contains("Rahul"))
    }

    @Test
    fun `review prompt for multi-action graph`() {
        val actions = listOf(
            ParsedAction(type = ActionType.CALENDAR, title = "Meeting", confidence = 0.9f),
            ParsedAction(type = ActionType.REMINDER, title = "Follow up", confidence = 0.9f),
            ParsedAction(type = ActionType.MESSAGE, title = "Message", recipient = "Bob", confidence = 0.9f)
        )
        val result = SpokenSummaryGenerator.generateReviewPrompt(makeGraph(actions))
        assertTrue("Should mention count", result.contains("three"))
        assertTrue("Should ask for review", result.contains("review") || result.contains("execute"))
    }

    @Test
    fun `review prompt for empty graph`() {
        val result = SpokenSummaryGenerator.generateReviewPrompt(makeGraph(emptyList()))
        assertTrue("Should indicate no actions", result.contains("couldn't find"))
    }

    // --- Execution Summaries ---

    @Test
    fun `execution summary for single successful calendar`() {
        val action = ParsedAction(
            type = ActionType.CALENDAR,
            title = "Sprint Planning",
            confidence = 0.95f,
            executionState = ExecutionState.SUCCESS,
            timeExpression = "at 3 PM"
        )
        val result = SpokenSummaryGenerator.generateExecutionSummary(listOf(action))
        assertTrue("Should mention scheduled", result.contains("scheduled"))
        assertTrue("Should mention title", result.contains("Sprint Planning"))
    }

    @Test
    fun `execution summary for successful reminder`() {
        val action = ParsedAction(
            type = ActionType.REMINDER,
            title = "Check oven",
            confidence = 0.9f,
            executionState = ExecutionState.SUCCESS,
            timeExpression = "in 5 minutes"
        )
        val result = SpokenSummaryGenerator.generateExecutionSummary(listOf(action))
        assertTrue("Should mention remind", result.contains("remind"))
    }

    @Test
    fun `execution summary for prepared message`() {
        val action = ParsedAction(
            type = ActionType.MESSAGE,
            title = "Message to Rahul",
            recipient = "Rahul",
            confidence = 0.9f,
            executionState = ExecutionState.SUCCESS
        )
        val result = SpokenSummaryGenerator.generateExecutionSummary(listOf(action))
        assertTrue("Should mention ready for review", result.contains("ready for your review"))
        assertTrue("Should mention recipient", result.contains("Rahul"))
    }

    @Test
    fun `execution summary for failed action`() {
        val action = ParsedAction(
            type = ActionType.CALENDAR,
            title = "Meeting",
            confidence = 0.9f,
            executionState = ExecutionState.FAILED
        )
        val result = SpokenSummaryGenerator.generateExecutionSummary(listOf(action))
        assertTrue("Should mention failure", result.contains("couldn't"))
    }

    @Test
    fun `multi-action execution summary with mixed states`() {
        val actions = listOf(
            ParsedAction(type = ActionType.CALENDAR, title = "Meeting", confidence = 0.9f, executionState = ExecutionState.SUCCESS),
            ParsedAction(type = ActionType.REMINDER, title = "Follow up", confidence = 0.9f, executionState = ExecutionState.SUCCESS),
            ParsedAction(type = ActionType.MESSAGE, title = "Msg", recipient = "Rahul", confidence = 0.9f, executionState = ExecutionState.SUCCESS)
        )
        val result = SpokenSummaryGenerator.generateExecutionSummary(actions)
        assertTrue("Should start with Done", result.startsWith("Done"))
        assertTrue("Should mention message ready", result.contains("ready for your review"))
    }

    @Test
    fun `execution summary for empty actions`() {
        val result = SpokenSummaryGenerator.generateExecutionSummary(emptyList())
        assertTrue("Should say no actions", result.contains("No actions"))
    }
}
