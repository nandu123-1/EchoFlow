package com.echoflow.app.assistant

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [GestureTriggerController] covering:
 * - Ambient triggers (Accessibility Button, Quick Settings Tile, Assistant Intent, 4-Finger Gesture)
 * - Debouncing across triggers
 * - Unrelated gesture rejection
 * - In-process listener invocation
 * - Diagnostic state tracking (service connection, touch exploration mode, trigger counts)
 */
class GestureTriggerControllerTest {

    @Before
    fun setUp() {
        GestureTriggerController.resetTriggerTimeForTesting()
        GestureTriggerController.resetState()
        GestureTriggerController.onTriggerListener = null
        GestureTriggerController.setServiceConnected(false)
        GestureTriggerController.setTouchExplorationActive(false)
    }

    @Test
    fun `correct gesture ID matches four-finger swipe down`() {
        assertEquals(34, GestureTriggerController.GESTURE_4_FINGER_SWIPE_DOWN_CODE)
    }

    @Test
    fun `debounce cooldown is 1500ms`() {
        assertEquals(1500L, GestureTriggerController.DEBOUNCE_COOLDOWN_MS)
    }

    @Test
    fun `unrelated gesture ID is rejected and does not fire trigger`() {
        var listenerFired = false
        GestureTriggerController.onTriggerListener = { listenerFired = true }

        val initialCount = GestureTriggerController.triggerCount.value
        val handled = GestureTriggerController.onGestureReceived(null, 18, nowMs = 1000L)

        assertFalse("Unrelated gesture should return false", handled)
        assertFalse("Listener should not fire for unrelated gesture", listenerFired)
        assertEquals("Trigger count should not increment", initialCount, GestureTriggerController.triggerCount.value)
        assertEquals(18, GestureTriggerController.lastGestureId.value)
        assertEquals("2-Finger Swipe Down", GestureTriggerController.lastGestureName.value)
        assertEquals(1000L, GestureTriggerController.lastGestureTime.value)
    }

    @Test
    fun `target gesture fires listener and increments trigger count`() {
        var listenerFired = false
        GestureTriggerController.onTriggerListener = { listenerFired = true }

        val initialCount = GestureTriggerController.triggerCount.value
        val handled = GestureTriggerController.onGestureReceived(null, 34, nowMs = 1000L)

        assertTrue("Target gesture should return true", handled)
        assertTrue("Listener should be invoked", listenerFired)
        assertEquals(initialCount + 1, GestureTriggerController.triggerCount.value)
        assertEquals(GestureTriggerState.ACTIVE, GestureTriggerController.state.value)
        assertEquals(34, GestureTriggerController.lastGestureId.value)
        assertEquals("4-Finger Swipe Down", GestureTriggerController.lastGestureName.value)
        assertEquals(1000L, GestureTriggerController.lastGestureTime.value)
        assertEquals(GestureTriggerController.TRIGGER_GESTURE_4_FINGER, GestureTriggerController.lastTriggerSource.value)
    }

    @Test
    fun `accessibility button trigger fires listener and records source`() {
        var listenerFired = false
        GestureTriggerController.onTriggerListener = { listenerFired = true }

        val initialCount = GestureTriggerController.triggerCount.value
        val handled = GestureTriggerController.onTriggerFired(
            context = null,
            source = GestureTriggerController.TRIGGER_ACCESSIBILITY_BUTTON,
            nowMs = 1000L
        )

        assertTrue("Accessibility button trigger should return true", handled)
        assertTrue("Listener should be invoked", listenerFired)
        assertEquals(initialCount + 1, GestureTriggerController.triggerCount.value)
        assertEquals(GestureTriggerState.ACTIVE, GestureTriggerController.state.value)
        assertEquals(GestureTriggerController.TRIGGER_ACCESSIBILITY_BUTTON, GestureTriggerController.lastTriggerSource.value)
    }

    @Test
    fun `quick settings tile trigger fires listener and records source`() {
        var listenerFired = false
        GestureTriggerController.onTriggerListener = { listenerFired = true }

        val initialCount = GestureTriggerController.triggerCount.value
        val handled = GestureTriggerController.onTriggerFired(
            context = null,
            source = GestureTriggerController.TRIGGER_QUICK_SETTINGS_TILE,
            nowMs = 1000L
        )

        assertTrue("Tile trigger should return true", handled)
        assertTrue("Listener should be invoked", listenerFired)
        assertEquals(initialCount + 1, GestureTriggerController.triggerCount.value)
        assertEquals(GestureTriggerState.ACTIVE, GestureTriggerController.state.value)
        assertEquals(GestureTriggerController.TRIGGER_QUICK_SETTINGS_TILE, GestureTriggerController.lastTriggerSource.value)
    }

    @Test
    fun `rapid triggers across different sources within debounce cooldown are rejected`() {
        var fireCount = 0
        GestureTriggerController.onTriggerListener = { fireCount++ }

        // First trigger at t=1000ms -> should succeed
        val firstHandled = GestureTriggerController.onTriggerFired(
            null,
            GestureTriggerController.TRIGGER_ACCESSIBILITY_BUTTON,
            nowMs = 1000L
        )
        assertTrue("First trigger should be handled", firstHandled)
        assertEquals(1, fireCount)

        // Second trigger at t=2000ms (elapsed = 1000ms < 1500ms) -> should be debounced
        val secondHandled = GestureTriggerController.onTriggerFired(
            null,
            GestureTriggerController.TRIGGER_QUICK_SETTINGS_TILE,
            nowMs = 2000L
        )
        assertFalse("Second trigger within cooldown should be debounced", secondHandled)
        assertEquals(1, fireCount)

        // Third trigger at t=2600ms (elapsed = 1600ms >= 1500ms) -> should succeed
        val thirdHandled = GestureTriggerController.onTriggerFired(
            null,
            GestureTriggerController.TRIGGER_ASSISTANT_INTENT,
            nowMs = 2600L
        )
        assertTrue("Third trigger after cooldown should be handled", thirdHandled)
        assertEquals(2, fireCount)
    }

    @Test
    fun `service connection and touch exploration states can be updated`() {
        assertFalse(GestureTriggerController.isServiceConnected.value)
        assertFalse(GestureTriggerController.isTouchExplorationActive.value)

        GestureTriggerController.setServiceConnected(true)
        assertTrue(GestureTriggerController.isServiceConnected.value)

        GestureTriggerController.setTouchExplorationActive(true)
        assertTrue(GestureTriggerController.isTouchExplorationActive.value)

        GestureTriggerController.setTouchExplorationActive(false)
        assertFalse(GestureTriggerController.isTouchExplorationActive.value)

        GestureTriggerController.setServiceConnected(false)
        assertFalse(GestureTriggerController.isServiceConnected.value)
    }

    @Test
    fun `human readable gesture display names are resolved`() {
        assertEquals("4-Finger Swipe Down", GestureTriggerController.getGestureDisplayName(34))
        assertEquals("4-Finger Swipe Up", GestureTriggerController.getGestureDisplayName(33))
        assertEquals("Swipe Up", GestureTriggerController.getGestureDisplayName(1))
        assertEquals("2-Finger Swipe Down", GestureTriggerController.getGestureDisplayName(18))
        assertEquals("Gesture #999", GestureTriggerController.getGestureDisplayName(999))
    }

    @Test
    fun `all trigger source constants are distinct`() {
        val sources = setOf(
            GestureTriggerController.TRIGGER_GESTURE_4_FINGER,
            GestureTriggerController.TRIGGER_ACCESSIBILITY_BUTTON,
            GestureTriggerController.TRIGGER_QUICK_SETTINGS_TILE,
            GestureTriggerController.TRIGGER_ASSISTANT_INTENT
        )
        assertEquals(4, sources.size)
        assertEquals("extra_trigger_source", GestureTriggerController.EXTRA_TRIGGER_SOURCE)
    }

    @Test
    fun `resetState restores state to READY`() {
        GestureTriggerController.onTriggerListener = {}
        GestureTriggerController.onTriggerFired(null, GestureTriggerController.TRIGGER_ACCESSIBILITY_BUTTON, nowMs = 1000L)
        assertEquals(GestureTriggerState.ACTIVE, GestureTriggerController.state.value)

        GestureTriggerController.resetState()
        assertEquals(GestureTriggerState.READY, GestureTriggerController.state.value)
    }
}
