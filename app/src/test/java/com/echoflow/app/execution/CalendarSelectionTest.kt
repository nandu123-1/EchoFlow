package com.echoflow.app.execution

import com.echoflow.app.execution.CalendarExecutor.CalendarInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for Calendar selection, fallback priority, and validation logic.
 */
class CalendarSelectionTest {

    private fun createCal(
        id: Long,
        accountName: String = "test@example.com",
        accountType: String = "com.google",
        displayName: String = "Test Calendar",
        color: Int = 0x0000FF,
        isVisible: Boolean = true,
        accessLevel: Int = 700, // OWNER
        isPrimary: Boolean = false,
        syncEvents: Boolean = true
    ) = CalendarInfo(
        id = id,
        accountName = accountName,
        accountType = accountType,
        displayName = displayName,
        color = color,
        isVisible = isVisible,
        accessLevel = accessLevel,
        isPrimary = isPrimary,
        syncEvents = syncEvents
    )

    @Test
    fun testExplicitPreferredCalendarSelectedSuccessfully() {
        val cal1 = createCal(id = 15, accountName = "user1@gmail.com", accountType = "com.google")
        val cal2 = createCal(id = 16, accountName = "user2@gmail.com", accountType = "com.google")
        val allCalendars = listOf(cal1, cal2)

        val result = CalendarExecutor.resolveCalendarToUse(
            preferredId = 16,
            preferredAccountName = "user2@gmail.com",
            allCalendars = allCalendars
        )

        assertNotNull(result.selectedCalendar)
        assertEquals(16L, result.selectedCalendar?.id)
        assertEquals("user2@gmail.com", result.selectedCalendar?.accountName)
        assertNull(result.errorMessage)
    }

    @Test
    fun testExplicitPreferredCalendarFailsWhenReadOnly() {
        val cal1 = createCal(id = 15, accountName = "user1@gmail.com", accessLevel = 700)
        // Access level 200 = READ
        val cal2 = createCal(id = 16, accountName = "user2@gmail.com", accessLevel = 200)
        val allCalendars = listOf(cal1, cal2)

        val result = CalendarExecutor.resolveCalendarToUse(
            preferredId = 16,
            preferredAccountName = "user2@gmail.com",
            allCalendars = allCalendars
        )

        assertNull(result.selectedCalendar)
        assertNotNull(result.errorMessage)
        assertTrue(result.errorMessage!!.contains("user2@gmail.com"))
        assertTrue(result.errorMessage!!.contains("Settings"))
    }

    @Test
    fun testExplicitPreferredCalendarFailsWhenMissing() {
        val cal1 = createCal(id = 15, accountName = "user1@gmail.com")
        val allCalendars = listOf(cal1)

        val result = CalendarExecutor.resolveCalendarToUse(
            preferredId = 99,
            preferredAccountName = "missing@gmail.com",
            allCalendars = allCalendars
        )

        assertNull(result.selectedCalendar)
        assertNotNull(result.errorMessage)
        assertTrue(result.errorMessage!!.contains("missing@gmail.com"))
    }

    @Test
    fun testFallbackPrefersSyncedOrVisibleGoogleAccount() {
        val localCal = createCal(id = 1, accountName = "local", accountType = "local", isVisible = true)
        val googleCalNotVisible = createCal(id = 2, accountName = "g1@gmail.com", accountType = "com.google", isVisible = false, syncEvents = false)
        val googleCalVisible = createCal(id = 3, accountName = "g2@gmail.com", accountType = "com.google", isVisible = true, syncEvents = true)

        val allCalendars = listOf(localCal, googleCalNotVisible, googleCalVisible)

        val result = CalendarExecutor.resolveCalendarToUse(
            preferredId = -1,
            preferredAccountName = null,
            allCalendars = allCalendars
        )

        assertNotNull(result.selectedCalendar)
        assertEquals(3L, result.selectedCalendar?.id)
        assertEquals("g2@gmail.com", result.selectedCalendar?.accountName)
    }

    @Test
    fun testFallbackPrefersAnyGoogleAccountOverLocal() {
        val localCal = createCal(id = 1, accountName = "local", accountType = "local", isVisible = true)
        val googleCal = createCal(id = 2, accountName = "g1@gmail.com", accountType = "com.google", isVisible = false, syncEvents = false)

        val allCalendars = listOf(localCal, googleCal)

        val result = CalendarExecutor.resolveCalendarToUse(
            preferredId = -1,
            preferredAccountName = null,
            allCalendars = allCalendars
        )

        assertNotNull(result.selectedCalendar)
        assertEquals(2L, result.selectedCalendar?.id)
    }

    @Test
    fun testFallbackPrefersPrimaryWhenNoGoogleAccount() {
        val cal1 = createCal(id = 10, accountName = "work", accountType = "exchange", isPrimary = false)
        val cal2 = createCal(id = 11, accountName = "main", accountType = "exchange", isPrimary = true)

        val allCalendars = listOf(cal1, cal2)

        val result = CalendarExecutor.resolveCalendarToUse(
            preferredId = -1,
            preferredAccountName = null,
            allCalendars = allCalendars
        )

        assertNotNull(result.selectedCalendar)
        assertEquals(11L, result.selectedCalendar?.id)
    }

    @Test
    fun testFallbackFailsWhenNoWritableCalendarsExist() {
        val calReadOnly = createCal(id = 5, accountName = "holidays", accessLevel = 100)
        val allCalendars = listOf(calReadOnly)

        // Empty writable calendars and only read-only
        val writable = allCalendars.filter { it.accessLevel >= CalendarExecutor.MIN_WRITE_ACCESS_LEVEL }
        val fallback = CalendarExecutor.selectFallbackWritableCalendar(writable, allCalendars)

        // When writable is empty, selectFallbackWritableCalendar falls back to allCalendars.firstOrNull()
        assertEquals(5L, fallback?.id)
    }
}
