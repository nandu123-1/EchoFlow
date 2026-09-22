package com.echoflow.app.ambient

import com.echoflow.app.domain.model.ActionGraph
import com.echoflow.app.domain.model.ExecutionState

/**
 * High-level phase of the ambient overlay session.
 */
enum class AmbientPhase {
    HIDDEN,
    READY,
    LISTENING,
    TRANSCRIBING,
    ANALYZING,
    READY_FOR_REVIEW,
    WAITING_FOLLOWUP,
    EXECUTING,
    RESPONDING,
    CLOSING,
    ERROR
}

/**
 * Complete snapshot of the ambient overlay state.
 */
data class AmbientSessionState(
    val phase: AmbientPhase = AmbientPhase.HIDDEN,
    val transcript: String = "",
    val statusText: String = "EchoFlow is ready",
    val spokenResponse: String? = null,
    val actionGraph: ActionGraph? = null,
    val executionStates: Map<String, ExecutionState> = emptyMap(),
    val executionMessages: Map<String, String> = emptyMap(),
    val error: String? = null,
    val triggerSource: String? = null
)
