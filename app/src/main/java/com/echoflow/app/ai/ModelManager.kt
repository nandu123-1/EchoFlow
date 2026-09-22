package com.echoflow.app.ai

import android.content.Context
import android.os.Environment
import android.util.Log
import com.echoflow.app.domain.model.ModelState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Manages discovery, location, and lifecycle states of the local GGUF model file.
 *
 * Supported model filenames:
 *   - qwen2.5-1.5b-instruct-q4_k_m-00001-of-00001.gguf
 *   - qwen2.5-1.5b-instruct-q4_k_m.gguf
 *
 * Checks internal storage, external files, and public Download directories.
 */
class ModelManager(private val context: Context) {

    companion object {
        private const val TAG = "EchoFlow/Model"
        const val MODEL_DIR = "models"
        val MODEL_FILENAMES = listOf(
            "qwen2.5-1.5b-instruct-q4_k_m-00001-of-00001.gguf",
            "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            "qwen2.5-1.5b-instruct.gguf"
        )
    }

    private val _modelState = MutableStateFlow(ModelState.NOT_INSTALLED)
    val modelState: StateFlow<ModelState> = _modelState.asStateFlow()

    private var cachedFoundFile: File? = null

    /** Check candidate locations for the GGUF model file */
    private fun findModelFile(): File? {
        // 1. App internal filesDir/models/
        val internalModelsDir = File(context.filesDir, MODEL_DIR)
        for (name in MODEL_FILENAMES) {
            val file = File(internalModelsDir, name)
            if (file.exists() && file.length() > 1024 * 1024) {
                return file
            }
        }

        // 2. App external files directory /models/
        val externalModelsDir = File(context.getExternalFilesDir(null), MODEL_DIR)
        for (name in MODEL_FILENAMES) {
            val file = File(externalModelsDir, name)
            if (file.exists() && file.length() > 1024 * 1024) {
                return file
            }
        }

        // 3. Device Download directory
        try {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (downloadDir != null && downloadDir.exists()) {
                for (name in MODEL_FILENAMES) {
                    val file = File(downloadDir, name)
                    if (file.exists() && file.length() > 1024 * 1024) {
                        return file
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "[MODEL] Could not check Download directory", e)
        }

        // 4. Fallback default file location in internal storage
        return File(internalModelsDir, MODEL_FILENAMES.first())
    }

    /** Check if the GGUF model file exists and is non-empty */
    fun isModelInstalled(): Boolean {
        val file = findModelFile()
        val exists = file != null && file.exists() && file.length() > 0
        if (exists) {
            cachedFoundFile = file
        }
        Log.d(TAG, "[MODEL] isModelInstalled: $exists (path=${file?.absolutePath ?: "none"}, size=${getModelSizeMB()} MB)")
        return exists
    }

    /** Get the absolute path to the active model file */
    fun getModelPath(): String {
        return cachedFoundFile?.absolutePath ?: File(File(context.filesDir, MODEL_DIR), MODEL_FILENAMES.first()).absolutePath
    }

    /** Get model file size in MB, or -1 if not installed */
    fun getModelSizeMB(): Long {
        val file = cachedFoundFile ?: findModelFile()
        return if (file != null && file.exists()) {
            file.length() / (1024 * 1024)
        } else {
            -1
        }
    }

    /** Initialize and check model state */
    fun initialize() {
        ensureModelDirectory()
        _modelState.value = if (isModelInstalled()) {
            Log.d(TAG, "[MODEL] Model file found: ${getModelPath()} (${getModelSizeMB()} MB)")
            ModelState.READY
        } else {
            Log.d(TAG, "[MODEL] Model not installed. To install via adb:\n" +
                    "  adb push D:\\echoflow\\qwen2.5-1.5b-instruct-q4_k_m-00001-of-00001.gguf /sdcard/Download/")
            ModelState.NOT_INSTALLED
        }
    }

    fun markLoading() {
        _modelState.value = ModelState.LOADING
        Log.d(TAG, "[MODEL] State → LOADING")
    }

    fun markLoaded() {
        _modelState.value = ModelState.LOADED
        Log.d(TAG, "[MODEL] State → LOADED")
    }

    fun markError(reason: String? = null) {
        _modelState.value = ModelState.ERROR
        Log.e(TAG, "[MODEL] State → ERROR: $reason")
    }

    fun markUnloaded() {
        _modelState.value = if (isModelInstalled()) ModelState.READY else ModelState.NOT_INSTALLED
        Log.d(TAG, "[MODEL] State → ${_modelState.value}")
    }

    fun ensureModelDirectory() {
        val internalModelsDir = File(context.filesDir, MODEL_DIR)
        if (!internalModelsDir.exists()) {
            internalModelsDir.mkdirs()
        }
        val externalModelsDir = File(context.getExternalFilesDir(null), MODEL_DIR)
        if (!externalModelsDir.exists()) {
            externalModelsDir.mkdirs()
        }
    }
}
