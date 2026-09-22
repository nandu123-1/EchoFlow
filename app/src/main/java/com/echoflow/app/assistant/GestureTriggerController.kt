package com.echoflow.app.assistant

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityManager
import com.echoflow.app.MainActivity
import com.echoflow.app.ambient.AmbientController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * State of the global gesture/ambient trigger.
 */
enum class GestureTriggerState {
    DISABLED,
    READY,
    TRIGGERED,
    OPENING,
    ACTIVE
}

/**
 * Coordinates global ambient callbacks (Accessibility Button, Quick Settings Tile,
 * Assistant Intent, and opt-in 4-finger gesture), enforces debouncing,
 * and launches the EchoFlow assistant interface without hijacking system touch.
 */
object GestureTriggerController {

    private const val TAG = "EchoFlow-Trigger"

    const val EXTRA_TRIGGER_SOURCE = "extra_trigger_source"

    // Supported official trigger sources
    const val TRIGGER_GESTURE_4_FINGER = "gesture_4_finger_swipe_down"
    const val TRIGGER_ACCESSIBILITY_BUTTON = "accessibility_button"
    const val TRIGGER_QUICK_SETTINGS_TILE = "quick_settings_tile"
    const val TRIGGER_ASSISTANT_INTENT = "assistant_intent"

    const val GESTURE_4_FINGER_SWIPE_DOWN_CODE = 34 // AccessibilityService.GESTURE_4_FINGER_SWIPE_DOWN (API 30+)
    const val DEBOUNCE_COOLDOWN_MS = 1500L

    private var lastTriggerTimeMs: Long = 0L

    private val _state = MutableStateFlow(GestureTriggerState.READY)
    val state: StateFlow<GestureTriggerState> = _state.asStateFlow()

    private val _isServiceConnected = MutableStateFlow(false)
    val isServiceConnected: StateFlow<Boolean> = _isServiceConnected.asStateFlow()

    private val _isTouchExplorationActive = MutableStateFlow(false)
    val isTouchExplorationActive: StateFlow<Boolean> = _isTouchExplorationActive.asStateFlow()

    private val _lastTriggerSource = MutableStateFlow<String?>(null)
    val lastTriggerSource: StateFlow<String?> = _lastTriggerSource.asStateFlow()

    private val _lastGestureId = MutableStateFlow<Int?>(null)
    val lastGestureId: StateFlow<Int?> = _lastGestureId.asStateFlow()

    private val _lastGestureName = MutableStateFlow<String?>(null)
    val lastGestureName: StateFlow<String?> = _lastGestureName.asStateFlow()

    private val _lastGestureTime = MutableStateFlow<Long?>(null)
    val lastGestureTime: StateFlow<Long?> = _lastGestureTime.asStateFlow()

    private val _triggerCount = MutableStateFlow(0)
    val triggerCount: StateFlow<Int> = _triggerCount.asStateFlow()

    // Optional callback when session manager or activity is directly listening in process
    var onTriggerListener: (() -> Unit)? = null

    fun setServiceConnected(connected: Boolean) {
        _isServiceConnected.value = connected
        Log.d(TAG, "[TRIGGER] Service connected status: $connected")
    }

    fun setTouchExplorationActive(active: Boolean) {
        _isTouchExplorationActive.value = active
        Log.d(TAG, "[TRIGGER] Touch exploration active status: $active")
    }

    /**
     * Translates Android gesture IDs into human-readable labels for diagnostics and logs.
     */
    fun getGestureDisplayName(gestureId: Int): String {
        return when (gestureId) {
            1 -> "Swipe Up"
            2 -> "Swipe Down"
            3 -> "Swipe Left"
            4 -> "Swipe Right"
            5 -> "Swipe Up and Left"
            6 -> "Swipe Up and Down"
            7 -> "Swipe Up and Right"
            8 -> "Swipe Down and Up"
            9 -> "Swipe Down and Left"
            10 -> "Swipe Down and Right"
            11 -> "Swipe Left and Right"
            12 -> "Swipe Left and Up"
            13 -> "Swipe Left and Down"
            14 -> "Swipe Right and Left"
            15 -> "Swipe Right and Up"
            16 -> "Swipe Right and Down"
            17 -> "2-Finger Swipe Up"
            18 -> "2-Finger Swipe Down"
            19 -> "2-Finger Swipe Left"
            20 -> "2-Finger Swipe Right"
            21 -> "3-Finger Swipe Up"
            22 -> "3-Finger Swipe Down"
            23 -> "3-Finger Swipe Left"
            24 -> "3-Finger Swipe Right"
            25 -> "2-Finger Double Tap"
            26 -> "2-Finger Double Tap and Hold"
            27 -> "2-Finger Triple Tap"
            28 -> "2-Finger Triple Tap and Hold"
            29 -> "3-Finger Single Tap"
            30 -> "3-Finger Double Tap"
            31 -> "3-Finger Double Tap and Hold"
            32 -> "3-Finger Triple Tap"
            33 -> "4-Finger Swipe Up"
            34 -> "4-Finger Swipe Down"
            35 -> "4-Finger Swipe Left"
            36 -> "4-Finger Swipe Right"
            37 -> "4-Finger Single Tap"
            38 -> "4-Finger Double Tap"
            39 -> "4-Finger Double Tap and Hold"
            40 -> "4-Finger Triple Tap"
            41 -> "3-Finger Triple Tap and Hold"
            42 -> "4-Finger Triple Tap and Hold"
            else -> "Gesture #$gestureId"
        }
    }

    /**
     * Generic trigger handler for any ambient trigger (button, tile, intent, gesture).
     * Enforces debouncing, increments count, and starts the assistant session.
     */
    fun onTriggerFired(context: Context?, source: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (lastTriggerTimeMs > 0L) {
            val elapsed = nowMs - lastTriggerTimeMs
            if (elapsed < DEBOUNCE_COOLDOWN_MS) {
                Log.w(TAG, "[TRIGGER] Trigger ($source) debounced (elapsed=${elapsed}ms < ${DEBOUNCE_COOLDOWN_MS}ms)")
                return false
            }
        }

        lastTriggerTimeMs = nowMs
        _triggerCount.value += 1
        _lastTriggerSource.value = source
        _lastGestureTime.value = nowMs
        Log.i(TAG, "[TRIGGER] Assistant trigger fired via '$source' (count=${_triggerCount.value})")
        _state.value = GestureTriggerState.TRIGGERED

        // Route directly to AmbientController for overlay display
        if (context != null) {
            _state.value = GestureTriggerState.ACTIVE
            AmbientController.handleShortcutTrigger(context, source)
            onTriggerListener?.invoke()
            return true
        }

        // Notify in-process listener if active without context
        val listener = onTriggerListener
        if (listener != null) {
            _state.value = GestureTriggerState.ACTIVE
            listener.invoke()
            return true
        }

        Log.w(TAG, "[TRIGGER] Cannot handle trigger because context is null")
        _state.value = GestureTriggerState.READY
        return false
    }

    /**
     * Handles gesture callbacks from AccessibilityService.
     */
    fun onGestureReceived(context: Context?, gestureId: Int, nowMs: Long = System.currentTimeMillis()): Boolean {
        val gestureName = getGestureDisplayName(gestureId)
        _lastGestureId.value = gestureId
        _lastGestureName.value = gestureName
        _lastGestureTime.value = nowMs

        if (gestureId != GESTURE_4_FINGER_SWIPE_DOWN_CODE) {
            Log.d(TAG, "[GESTURE] Ignored gesture ID: $gestureId ($gestureName) - looking for $GESTURE_4_FINGER_SWIPE_DOWN_CODE")
            return false
        }

        return onTriggerFired(context, TRIGGER_GESTURE_4_FINGER, nowMs)
    }

    /**
     * Resets trigger state back to READY.
     */
    fun resetState() {
        _state.value = GestureTriggerState.READY
    }

    /**
     * Reset trigger time for testing.
     */
    internal fun resetTriggerTimeForTesting() {
        lastTriggerTimeMs = 0L
    }

    /**
     * Check if EchoFlowAccessibilityService is currently enabled in Android Accessibility settings.
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        return try {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
            val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            val expectedComponent = ComponentName(context, EchoFlowAccessibilityService::class.java)
            enabledServices.any { service ->
                val comp = ComponentName.unflattenFromString(service.id)
                comp == expectedComponent || service.resolveInfo.serviceInfo.packageName == context.packageName
            }
        } catch (e: Exception) {
            Log.w(TAG, "[TRIGGER] Could not inspect accessibility service status", e)
            false
        }
    }
}
