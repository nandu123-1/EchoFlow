package com.echoflow.app.domain.model

/**
 * Lifecycle state of an individual action as it moves through the
 * EchoFlow pipeline: detection → review → execution → result.
 */
enum class ExecutionState {
    /** Action was detected by the AI engine */
    DETECTED,

    /** Action is pending user review or scheduling */
    PENDING,

    /** Action requires user confirmation before execution */
    NEEDS_CONFIRMATION,

    /** Action is confirmed and ready for execution */
    READY,

    /** Action intent/composer is prepared (e.g. SMS composer opened or prepared) */
    PREPARED,

    /** Action is scheduled for a future time in AlarmManager */
    SCHEDULED,

    /** Action is currently being executed */
    EXECUTING,

    /** Action execution is in progress (e.g. dialer launched, waiting on phone app) */
    IN_PROGRESS,

    /** Proactive notification triggered, waiting for explicit user decision */
    WAITING_FOR_USER,

    /** Action completed successfully */
    COMPLETED,

    /** Action executed successfully (backwards compatibility alias) */
    SUCCESS,

    /** Action was cancelled by the user (distinct from FAILED) */
    CANCELLED,

    /** Action execution failed */
    FAILED,

    /** User chose to skip this action */
    SKIPPED;

    val isTerminal: Boolean
        get() = this in listOf(SUCCESS, COMPLETED, FAILED, CANCELLED, SKIPPED)

    val isPendingOrScheduled: Boolean
        get() = this in listOf(
            DETECTED, PENDING, NEEDS_CONFIRMATION, READY,
            PREPARED, SCHEDULED, EXECUTING, IN_PROGRESS, WAITING_FOR_USER
        )

    val isCancelled: Boolean
        get() = this == CANCELLED
}
