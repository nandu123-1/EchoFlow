package com.echoflow.app

import android.app.Application
import android.util.Log
import com.echoflow.app.ai.AiCoordinator
import com.echoflow.app.ai.FallbackAiEngine
import com.echoflow.app.ai.LocalQwenAiEngine
import com.echoflow.app.ai.ModelManager
import com.echoflow.app.assistant.SpeechOutputManager
import com.echoflow.app.capture.SystemSpeechRecognizer
import com.echoflow.app.data.db.EchoFlowDatabase
import com.echoflow.app.data.prefs.AssistantPreferences
import com.echoflow.app.data.prefs.CalendarPreferences
import com.echoflow.app.execution.ActionExecutor
import com.echoflow.app.scheduling.ScheduledActionManager

/**
 * EchoFlow Application class.
 * Provides unified, single-instance components for both ViewModel and Ambient Overlay.
 */
class EchoFlowApplication : Application() {

    companion object {
        private const val TAG = "EchoFlow/App"
        lateinit var instance: EchoFlowApplication
            private set

        val isInitialized: Boolean
            get() = ::instance.isInitialized
    }

    val database: EchoFlowDatabase by lazy { EchoFlowDatabase.getInstance(this) }
    val modelManager: ModelManager by lazy { ModelManager(this) }
    val localEngine: LocalQwenAiEngine by lazy { LocalQwenAiEngine(modelManager) }
    val fallbackEngine: FallbackAiEngine by lazy { FallbackAiEngine() }
    val aiCoordinator: AiCoordinator by lazy { AiCoordinator(localEngine, fallbackEngine, modelManager) }
    val calendarPreferences: CalendarPreferences by lazy { CalendarPreferences(this) }
    val assistantPreferences: AssistantPreferences by lazy { AssistantPreferences(this) }
    val actionExecutor: ActionExecutor by lazy { ActionExecutor(this, calendarPreferences) }
    val scheduledActionManager: ScheduledActionManager by lazy { ScheduledActionManager.getInstance(this) }
    val speechOutputManager: SpeechOutputManager by lazy { SpeechOutputManager(this, assistantPreferences) }
    val speechRecognizer: SystemSpeechRecognizer by lazy { SystemSpeechRecognizer(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "[APP] EchoFlow initialized")
    }
}
