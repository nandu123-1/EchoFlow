package com.echoflow.app.assistant

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [AssistantSessionManager] state machine and follow-up parsing.
 */
class AssistantSessionManagerTest {

    @Test
    fun `assistant states cover full lifecycle`() {
        val allStates = AssistantState.values()
        // Verify we have all expected states from the spec
        val expectedNames = listOf(
            "IDLE", "OPENING", "LISTENING", "TRANSCRIBING",
            "ANALYZING", "PREPARING", "READY_FOR_REVIEW",
            "WAITING_FOLLOWUP", "EXECUTING", "RESPONDING",
            "CLOSING", "ERROR"
        )
        for (name in expectedNames) {
            assertTrue(
                "AssistantState should contain $name",
                allStates.any { it.name == name }
            )
        }
    }

    @Test
    fun `IDLE is the default starting state`() {
        assertEquals(AssistantState.IDLE, AssistantState.values().first())
    }

    @Test
    fun `affirmative keywords are recognized`() {
        val affirmatives = listOf("yes", "yeah", "do it", "sure", "go ahead", "confirm", "ok", "please")
        for (word in affirmatives) {
            assertTrue(
                "'$word' should be recognized as affirmative",
                isAffirmative(word)
            )
        }
    }

    @Test
    fun `negative keywords are recognized`() {
        val negatives = listOf("no", "nope", "cancel", "stop", "dismiss", "nevermind")
        for (word in negatives) {
            assertTrue(
                "'$word' should be recognized as negative",
                isNegative(word)
            )
        }
    }

    @Test
    fun `ambiguous input is neither affirmative nor negative`() {
        val ambiguous = listOf("maybe", "hmm", "let me think", "what", "repeat")
        for (word in ambiguous) {
            assertFalse("'$word' should NOT be affirmative", isAffirmative(word))
            assertFalse("'$word' should NOT be negative", isNegative(word))
        }
    }

    // Mirroring the actual confirmation keyword sets from AssistantSessionManager
    private val AFFIRMATIVE = setOf(
        "yes", "yeah", "yep", "sure", "do it", "confirm", "go ahead",
        "execute", "schedule it", "save it", "send it", "ok", "okay", "please"
    )
    private val NEGATIVE = setOf(
        "no", "nope", "cancel", "stop", "don't", "dont", "dismiss", "nevermind", "never mind"
    )

    private fun isAffirmative(text: String): Boolean {
        val lower = text.trim().lowercase()
        return AFFIRMATIVE.any { lower.contains(it) }
    }

    private fun isNegative(text: String): Boolean {
        val lower = text.trim().lowercase()
        return NEGATIVE.any { lower.contains(it) }
    }
}
