package com.echoflow.app.demo

import com.echoflow.app.domain.model.*
import java.time.LocalDateTime

/**
 * Predefined demo scenarios for hackathon reliability.
 *
 * These scenarios flow through the REAL pipeline:
 *   FallbackAiEngine → ActionGraph → ConfidenceEngine → EchoPalette → Execution
 *
 * They do NOT bypass the architecture with fake UI.
 */
object DemoScenarios {

    data class DemoScenario(
        val title: String,
        val description: String,
        val input: String,
        val expectedActionCount: Int,
        val expectedTypes: List<ActionType>
    )

    val scenarios = listOf(
        // Scenario 1: The flagship demo (4 actions: Calendar, Reminder, Message, Call)
        DemoScenario(
            title = "Robotics Meeting Workflow",
            description = "Flagship 4-action workflow: Calendar, Reminder, Scheduled Message, Proactive Call",
            input = "The robotics meeting is done. Tomorrow at 4, block one hour to work on the motor controller, remind Rahul to send the CAD files, send Rahul a message at 4:15 saying I'll start integration tomorrow, and call Rahul at 5.",
            expectedActionCount = 4,
            expectedTypes = listOf(ActionType.CALENDAR, ActionType.REMINDER, ActionType.MESSAGE, ActionType.CALL)
        ),

        // Scenario 2: Two-action workflow
        DemoScenario(
            title = "Morning Productivity",
            description = "Reminder + calendar scheduling",
            input = "Tomorrow morning remind me to submit the robotics report and schedule thirty minutes to review the presentation.",
            expectedActionCount = 2,
            expectedTypes = listOf(ActionType.REMINDER, ActionType.CALENDAR)
        ),

        // Scenario 3: Message + Reminder
        DemoScenario(
            title = "Prototype Update",
            description = "Message to team + schedule a reminder",
            input = "Tell Rahul that the prototype is ready and remind me to test the battery tomorrow at 10am.",
            expectedActionCount = 2,
            expectedTypes = listOf(ActionType.MESSAGE, ActionType.REMINDER)
        ),

        // Scenario 4: Single complex calendar
        DemoScenario(
            title = "Quick Schedule",
            description = "Single calendar event with full details",
            input = "Schedule one hour tomorrow at 4 to work on the motor controller.",
            expectedActionCount = 1,
            expectedTypes = listOf(ActionType.CALENDAR)
        ),

        // Scenario 5: Ambiguous input
        DemoScenario(
            title = "Ambiguous Request",
            description = "Tests ambiguity detection",
            input = "Remind Rahul about the files.",
            expectedActionCount = 1,
            expectedTypes = listOf(ActionType.REMINDER)
        ),

        // Scenario 6: Live 1-Minute Fast Test
        DemoScenario(
            title = "Live Fast Test (1-min)",
            description = "Quick 1-minute reminder and proactive call for live demonstration",
            input = "In 1 minute remind me to review the circuit, and in 2 minutes call Rahul.",
            expectedActionCount = 2,
            expectedTypes = listOf(ActionType.REMINDER, ActionType.CALL)
        )
    )

    /**
     * Get the flagship demo input (Scenario 1).
     */
    fun getFlagshipDemo(): String = scenarios[0].input
}
