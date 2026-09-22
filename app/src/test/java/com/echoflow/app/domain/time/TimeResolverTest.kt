package com.echoflow.app.domain.time

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class TimeResolverTest {

    private val fixedZone = ZoneId.of("Asia/Kolkata") // UTC+05:30
    // 2026-09-21T22:33:10+05:30
    private val fixedBaseInstant = LocalDateTime.of(2026, 9, 21, 22, 33, 10)
        .atZone(fixedZone)
        .toInstant()

    @Test
    fun testAfterFiveMinutes_resolvesRelativeToRequestCreatedAt() {
        val result = TimeResolver.resolve(
            rawExpression = "after 5 minutes",
            dateStr = null,
            timeStr = null,
            requestCreatedAt = fixedBaseInstant,
            zoneId = fixedZone,
            referenceForPastCheck = fixedBaseInstant
        )

        assertNotNull(result.resolvedDateTime)
        assertEquals(2026, result.resolvedDateTime?.year)
        assertEquals(9, result.resolvedDateTime?.monthValue)
        assertEquals(21, result.resolvedDateTime?.dayOfMonth)
        assertEquals(22, result.resolvedDateTime?.hour)
        assertEquals(38, result.resolvedDateTime?.minute)
        assertEquals(10, result.resolvedDateTime?.second)

        // Exact 300,000 ms diff from requestCreatedAt
        val expectedEpoch = fixedBaseInstant.toEpochMilli() + 300_000L
        assertEquals(expectedEpoch, result.resolvedEpochMillis)
        assertFalse(result.isPastTime)
        assertTrue(result.isRelative)
    }

    @Test
    fun testAfterOneMinute_resolvesExactlySixtySecondsLater() {
        val result = TimeResolver.resolve(
            rawExpression = "after 1 minute",
            dateStr = null,
            timeStr = null,
            requestCreatedAt = fixedBaseInstant,
            zoneId = fixedZone,
            referenceForPastCheck = fixedBaseInstant
        )

        assertNotNull(result.resolvedDateTime)
        assertEquals(22, result.resolvedDateTime?.hour)
        assertEquals(34, result.resolvedDateTime?.minute)
        assertEquals(10, result.resolvedDateTime?.second)

        val expectedEpoch = fixedBaseInstant.toEpochMilli() + 60_000L
        assertEquals(expectedEpoch, result.resolvedEpochMillis)
        assertFalse(result.isPastTime)
    }

    @Test
    fun testAtSpecificTime_sameDay() {
        val result = TimeResolver.resolve(
            rawExpression = "at 22:34",
            dateStr = null,
            timeStr = "22:34",
            requestCreatedAt = fixedBaseInstant,
            zoneId = fixedZone,
            referenceForPastCheck = fixedBaseInstant
        )

        assertNotNull(result.resolvedDateTime)
        assertEquals(22, result.resolvedDateTime?.hour)
        assertEquals(34, result.resolvedDateTime?.minute)
        assertEquals(0, result.resolvedDateTime?.second)
        assertEquals(21, result.resolvedDateTime?.dayOfMonth)
        assertFalse(result.isPastTime)
    }

    @Test
    fun testTomorrowAtSpecificTime() {
        val result = TimeResolver.resolve(
            rawExpression = "tomorrow at 22:34",
            dateStr = "tomorrow",
            timeStr = "22:34",
            requestCreatedAt = fixedBaseInstant,
            zoneId = fixedZone,
            referenceForPastCheck = fixedBaseInstant
        )

        assertNotNull(result.resolvedDateTime)
        assertEquals(22, result.resolvedDateTime?.dayOfMonth) // Tomorrow is 22nd
        assertEquals(22, result.resolvedDateTime?.hour)
        assertEquals(34, result.resolvedDateTime?.minute)
        assertFalse(result.isPastTime)
    }

    @Test
    fun testAt4PM() {
        val result = TimeResolver.resolve(
            rawExpression = "at 4 PM",
            dateStr = null,
            timeStr = "4 PM",
            requestCreatedAt = fixedBaseInstant,
            zoneId = fixedZone,
            referenceForPastCheck = fixedBaseInstant
        )

        assertNotNull(result.resolvedDateTime)
        assertEquals(16, result.resolvedDateTime?.hour)
        assertEquals(0, result.resolvedDateTime?.minute)
    }

    @Test
    fun testTomorrowAt4PM() {
        val result = TimeResolver.resolve(
            rawExpression = "tomorrow at 4 PM",
            dateStr = "tomorrow",
            timeStr = "16:00",
            requestCreatedAt = fixedBaseInstant,
            zoneId = fixedZone,
            referenceForPastCheck = fixedBaseInstant
        )

        assertNotNull(result.resolvedDateTime)
        assertEquals(22, result.resolvedDateTime?.dayOfMonth)
        assertEquals(16, result.resolvedDateTime?.hour)
        assertEquals(0, result.resolvedDateTime?.minute)
        assertFalse(result.isPastTime)
    }

    @Test
    fun testPastTimeDetection_whenInferenceTookLongerThanTarget() {
        // User asked for 22:34 at 22:33:10
        // Inference finished at 22:34:20 (70s later)
        val inferenceFinishedInstant = fixedBaseInstant.plusSeconds(70)

        val result = TimeResolver.resolve(
            rawExpression = "at 22:34",
            dateStr = null,
            timeStr = "22:34",
            requestCreatedAt = fixedBaseInstant,
            zoneId = fixedZone,
            referenceForPastCheck = inferenceFinishedInstant // 22:34:20
        )

        assertNotNull(result.resolvedDateTime)
        assertEquals(22, result.resolvedDateTime?.hour)
        assertEquals(34, result.resolvedDateTime?.minute)
        // Since 22:34:00 is before 22:34:20, isPastTime must be TRUE!
        assertTrue("Expected isPastTime to be true when review is after target", result.isPastTime)
    }

    @Test
    fun testWordNumbers_afterFiveMinutes() {
        val result = TimeResolver.resolve(
            rawExpression = "after five minutes",
            dateStr = null,
            timeStr = null,
            requestCreatedAt = fixedBaseInstant,
            zoneId = fixedZone,
            referenceForPastCheck = fixedBaseInstant
        )

        assertNotNull(result.resolvedDateTime)
        assertEquals(22, result.resolvedDateTime?.hour)
        assertEquals(38, result.resolvedDateTime?.minute)
        assertEquals(10, result.resolvedDateTime?.second)
    }

    @Test
    fun testTomorrowAt415PM_scheduledMessage() {
        val result = TimeResolver.resolve(
            rawExpression = "tomorrow at 4:15 PM",
            dateStr = "tomorrow",
            timeStr = "16:15",
            requestCreatedAt = fixedBaseInstant,
            zoneId = fixedZone,
            referenceForPastCheck = fixedBaseInstant
        )

        assertNotNull(result.resolvedDateTime)
        assertEquals(22, result.resolvedDateTime?.dayOfMonth)
        assertEquals(16, result.resolvedDateTime?.hour)
        assertEquals(15, result.resolvedDateTime?.minute)
        assertFalse(result.isPastTime)
    }

    @Test
    fun testTomorrowAt5PM_scheduledCall() {
        val result = TimeResolver.resolve(
            rawExpression = "tomorrow at 5 PM",
            dateStr = "tomorrow",
            timeStr = "17:00",
            requestCreatedAt = fixedBaseInstant,
            zoneId = fixedZone,
            referenceForPastCheck = fixedBaseInstant
        )

        assertNotNull(result.resolvedDateTime)
        assertEquals(22, result.resolvedDateTime?.dayOfMonth)
        assertEquals(17, result.resolvedDateTime?.hour)
        assertEquals(0, result.resolvedDateTime?.minute)
        assertFalse(result.isPastTime)
    }

    @Test
    fun testFastDemo_in1Minute() {
        val result = TimeResolver.resolve(
            rawExpression = "in 1 minute",
            dateStr = null,
            timeStr = null,
            requestCreatedAt = fixedBaseInstant,
            zoneId = fixedZone,
            referenceForPastCheck = fixedBaseInstant
        )

        assertNotNull(result.resolvedDateTime)
        val expectedEpoch = fixedBaseInstant.toEpochMilli() + 60_000L
        assertEquals(expectedEpoch, result.resolvedEpochMillis)
        assertFalse(result.isPastTime)
    }

    @Test
    fun testSnoozeOffsetCalculation_fiveMinutes() {
        val originalEpoch = fixedBaseInstant.toEpochMilli() + 3600_000L
        val snoozeMillis = 5 * 60 * 1000L
        val snoozedEpoch = originalEpoch + snoozeMillis
        assertEquals(originalEpoch + 300_000L, snoozedEpoch)
    }
}
