package com.echoflow.app.domain.model

/**
 * Lifecycle state of the local LLM model.
 * Drives the AI status indicator in the UI.
 */
enum class ModelState {
    /** GGUF file not found in app storage */
    NOT_INSTALLED,

    /** Model is being downloaded/copied */
    DOWNLOADING,

    /** Model file exists, not yet loaded into memory */
    READY,

    /** Model is being loaded into the inference runtime */
    LOADING,

    /** Model is loaded and ready for inference */
    LOADED,

    /** Model encountered an error (load failure, OOM, etc.) */
    ERROR
}
