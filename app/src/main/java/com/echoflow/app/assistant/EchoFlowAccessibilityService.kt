package com.echoflow.app.assistant

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityGestureEvent
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.echoflow.app.ambient.AmbientController
import com.echoflow.app.ambient.AmbientOverlayScreen
import com.echoflow.app.ambient.ServiceLifecycleOwner
import com.echoflow.app.ui.theme.EchoFlowTheme
import java.lang.ref.WeakReference

/**
 * Isolated AccessibilityService providing ambient accessibility triggers
 * (Floating Accessibility Button / nav bar shortcut, and optional opt-in gesture detection)
 * WITHOUT hijacking normal user touch interactions.
 *
 * Directly hosts the TYPE_ACCESSIBILITY_OVERLAY Compose window for the EchoFlow Ambient assistant.
 *
 * PRIVACY & SAFETY:
 * - canRetrieveWindowContent is strictly set to FALSE in XML.
 * - This service does NOT read, inspect, intercept, or record screen content, text, or keystrokes.
 * - Default flags use flagRequestAccessibilityButton with standard touch untouched.
 */
class EchoFlowAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "EchoFlow-Trigger"
        private var activeInstance: WeakReference<EchoFlowAccessibilityService>? = null

        val instance: EchoFlowAccessibilityService?
            get() = activeInstance?.get()

        /**
         * Dynamically toggles Touch Exploration Mode on the active service if connected.
         * Used ONLY when the user explicitly opts in to 4-finger gestures in Settings.
         */
        fun setTouchExplorationEnabled(enabled: Boolean): Boolean {
            val service = activeInstance?.get() ?: return false
            return try {
                val info = service.serviceInfo ?: return false
                if (enabled) {
                    info.flags = info.flags or
                        AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE or
                        AccessibilityServiceInfo.FLAG_REQUEST_MULTI_FINGER_GESTURES
                } else {
                    info.flags = info.flags and
                        (AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE or
                         AccessibilityServiceInfo.FLAG_REQUEST_MULTI_FINGER_GESTURES).inv()
                }
                service.serviceInfo = info
                val actualActive = (info.flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE) != 0
                GestureTriggerController.setTouchExplorationActive(actualActive)
                Log.i(TAG, "[SERVICE] Dynamically updated service flags: touchExploration=$enabled, flags=0x${Integer.toHexString(info.flags)}")
                true
            } catch (e: Exception) {
                Log.e(TAG, "[SERVICE] Failed to dynamically update service flags", e)
                false
            }
        }
    }

    private var buttonCallback: Any? = null
    private var windowManager: WindowManager? = null
    private var overlayComposeView: ComposeView? = null
    private var serviceLifecycleOwner: ServiceLifecycleOwner? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeInstance = WeakReference(this)
        AmbientController.registerAccessibilityService(this)
        GestureTriggerController.setServiceConnected(true)

        val info = serviceInfo
        val flagsHex = if (info != null) "0x${Integer.toHexString(info.flags)}" else "null"
        val touchExploration = (info?.flags ?: 0) and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE != 0
        val multiFinger = (info?.flags ?: 0) and AccessibilityServiceInfo.FLAG_REQUEST_MULTI_FINGER_GESTURES != 0
        GestureTriggerController.setTouchExplorationActive(touchExploration)

        // Register official Android Accessibility Button callback (API 26+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val callback = object : AccessibilityButtonController.AccessibilityButtonCallback() {
                    override fun onClicked(controller: AccessibilityButtonController) {
                        Log.i(TAG, "[BUTTON] Accessibility button clicked! Summoning EchoFlow assistant")
                        AmbientController.handleShortcutTrigger(
                            this@EchoFlowAccessibilityService,
                            GestureTriggerController.TRIGGER_ACCESSIBILITY_BUTTON
                        )
                    }

                    override fun onAvailabilityChanged(controller: AccessibilityButtonController, available: Boolean) {
                        Log.d(TAG, "[BUTTON] Accessibility button availability changed: $available")
                    }
                }
                accessibilityButtonController.registerAccessibilityButtonCallback(callback)
                buttonCallback = callback
                Log.i(TAG, "[BUTTON] Registered AccessibilityButtonController callback successfully")
            } catch (e: Exception) {
                Log.w(TAG, "[BUTTON] Could not register AccessibilityButtonController callback", e)
            }
        }

        Log.i(TAG, "[SERVICE] ========================================")
        Log.i(TAG, "[SERVICE] EchoFlowAccessibilityService connected and active")
        Log.i(TAG, "[SERVICE] Service flags: $flagsHex")
        Log.i(TAG, "[SERVICE] Touch exploration enabled: $touchExploration (Normal Touch: ${if (touchExploration) "ALTERED" else "NORMAL/PRESERVED"})")
        Log.i(TAG, "[SERVICE] Multi-finger gestures enabled: $multiFinger")
        Log.i(TAG, "[SERVICE] Accessibility button available: ${Build.VERSION.SDK_INT >= Build.VERSION_CODES.O}")
        Log.i(TAG, "[SERVICE] ========================================")
    }

    /**
     * Directly creates and attaches a TYPE_ACCESSIBILITY_OVERLAY window hosting Compose.
     */
    fun showAmbientOverlay() {
        if (overlayComposeView != null) {
            Log.d(TAG, "[OVERLAY] Overlay already visible")
            return
        }

        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            windowManager = wm

            val lifecycleOwner = ServiceLifecycleOwner()
            serviceLifecycleOwner = lifecycleOwner

            val composeView = ComposeView(this).apply {
                lifecycleOwner.attachToView(this)
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent {
                    EchoFlowTheme {
                        AmbientOverlayScreen(
                            onDismiss = {
                                AmbientController.hideAmbient()
                            }
                        )
                    }
                }
            }

            val layoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM
            }

            lifecycleOwner.start()
            wm.addView(composeView, layoutParams)
            overlayComposeView = composeView
            Log.i(TAG, "[OVERLAY] TYPE_ACCESSIBILITY_OVERLAY window attached successfully")
        } catch (e: Exception) {
            Log.e(TAG, "[OVERLAY] Failed to display accessibility overlay window", e)
            hideAmbientOverlay()
        }
    }

    /**
     * Removes the TYPE_ACCESSIBILITY_OVERLAY window and tears down its lifecycle.
     */
    fun hideAmbientOverlay() {
        val view = overlayComposeView ?: return
        try {
            windowManager?.removeView(view)
            serviceLifecycleOwner?.destroy()
            Log.i(TAG, "[OVERLAY] TYPE_ACCESSIBILITY_OVERLAY window removed")
        } catch (e: Exception) {
            Log.w(TAG, "[OVERLAY] Error removing overlay view", e)
        } finally {
            overlayComposeView = null
            serviceLifecycleOwner = null
        }
    }

    fun isOverlayVisible(): Boolean = overlayComposeView != null

    /**
     * Modern gesture callback (API 30+).
     */
    override fun onGesture(gestureEvent: AccessibilityGestureEvent): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val gestureId = gestureEvent.gestureId
            val gestureName = GestureTriggerController.getGestureDisplayName(gestureId)
            Log.i(TAG, "[GESTURE] onGesture(event) received: ID=$gestureId ($gestureName)")
            if (gestureId == GestureTriggerController.GESTURE_4_FINGER_SWIPE_DOWN_CODE) {
                AmbientController.handleShortcutTrigger(this, GestureTriggerController.TRIGGER_GESTURE_4_FINGER)
                return true
            }
        }
        return super.onGesture(gestureEvent)
    }

    /**
     * Legacy gesture callback fallback.
     */
    @Suppress("DEPRECATION")
    override fun onGesture(gestureId: Int): Boolean {
        val gestureName = GestureTriggerController.getGestureDisplayName(gestureId)
        Log.i(TAG, "[GESTURE] onGesture(legacy) received: ID=$gestureId ($gestureName)")
        if (gestureId == GestureTriggerController.GESTURE_4_FINGER_SWIPE_DOWN_CODE) {
            AmbientController.handleShortcutTrigger(this, GestureTriggerController.TRIGGER_GESTURE_4_FINGER)
            return true
        }
        return super.onGesture(gestureId)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Deliberately no-op: We do not process screen/window content
    }

    override fun onInterrupt() {
        Log.w(TAG, "[SERVICE] AccessibilityService interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "[SERVICE] EchoFlowAccessibilityService unbinding")
        hideAmbientOverlay()
        AmbientController.unregisterAccessibilityService(this)
        unregisterButtonCallback()
        GestureTriggerController.setServiceConnected(false)
        GestureTriggerController.setTouchExplorationActive(false)
        activeInstance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "[SERVICE] EchoFlowAccessibilityService destroyed")
        hideAmbientOverlay()
        AmbientController.unregisterAccessibilityService(this)
        unregisterButtonCallback()
        GestureTriggerController.setServiceConnected(false)
        GestureTriggerController.setTouchExplorationActive(false)
        activeInstance = null
    }

    private fun unregisterButtonCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val cb = buttonCallback as? AccessibilityButtonController.AccessibilityButtonCallback
            if (cb != null) {
                try {
                    accessibilityButtonController.unregisterAccessibilityButtonCallback(cb)
                    Log.d(TAG, "[BUTTON] Unregistered AccessibilityButtonController callback")
                } catch (e: Exception) {
                    Log.w(TAG, "[BUTTON] Error unregistering button callback", e)
                }
            }
            buttonCallback = null
        }
    }
}

