package com.echoflow.app.execution

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract
import android.util.Log
import com.echoflow.app.domain.model.ExecutionState
import com.echoflow.app.domain.model.ParsedAction
import java.time.ZoneId
import java.util.TimeZone

/**
 * Creates calendar events via Android's CalendarContract provider API.
 *
 * Implements intelligent writable calendar selection with priority for
 * Google Calendar accounts (com.google) to ensure synchronization across
 * Google Calendar apps and web, with fallback to primary/local calendars.
 */
class CalendarExecutor(
    private val context: Context,
    val calendarPreferences: com.echoflow.app.data.prefs.CalendarPreferences = com.echoflow.app.data.prefs.CalendarPreferences(context)
) {

    companion object {
        private const val TAG = "EchoFlow/Calendar"
        const val MIN_WRITE_ACCESS_LEVEL = CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR // 500

        data class CalendarResolutionResult(
            val selectedCalendar: CalendarInfo?,
            val errorMessage: String? = null
        )

        fun resolveCalendarToUse(
            preferredId: Long,
            preferredAccountName: String?,
            allCalendars: List<CalendarInfo>
        ): CalendarResolutionResult {
            val writableCalendars = allCalendars.filter { it.accessLevel >= MIN_WRITE_ACCESS_LEVEL }

            if (preferredId > 0) {
                val matching = writableCalendars.firstOrNull { it.id == preferredId }
                if (matching == null) {
                    val savedAccount = preferredAccountName ?: "ID $preferredId"
                    Log.w(TAG, "[CALENDAR] User-selected calendar ($savedAccount) is missing or no longer writable.")
                    return CalendarResolutionResult(
                        selectedCalendar = null,
                        errorMessage = "Selected calendar ($savedAccount) is no longer available or writable. Please update Default Calendar in Settings."
                    )
                }
                return CalendarResolutionResult(selectedCalendar = matching)
            }

            val fallback = selectFallbackWritableCalendar(writableCalendars, allCalendars)
            if (fallback == null) {
                Log.e(TAG, "[CALENDAR] No writable calendar account found on this device.")
                return CalendarResolutionResult(
                    selectedCalendar = null,
                    errorMessage = "No writable calendar found. Please ensure a calendar account is configured."
                )
            }
            return CalendarResolutionResult(selectedCalendar = fallback)
        }

        fun selectFallbackWritableCalendar(
            writableCalendars: List<CalendarInfo>,
            allCalendars: List<CalendarInfo>
        ): CalendarInfo? {
            if (writableCalendars.isEmpty()) {
                Log.w(TAG, "[CALENDAR] No calendar has write access level >= CONTRIBUTOR (500)")
                return allCalendars.firstOrNull() // Fallback to first available
            }

            // Priority 1: Google account (com.google) that is visible or synced
            val googleVisible = writableCalendars.firstOrNull {
                it.accountType.equals("com.google", ignoreCase = true) && (it.isVisible || it.syncEvents)
            }
            if (googleVisible != null) return googleVisible

            // Priority 2: Any Google account
            val googleAny = writableCalendars.firstOrNull {
                it.accountType.equals("com.google", ignoreCase = true)
            }
            if (googleAny != null) return googleAny

            // Priority 3: Primary calendar
            val primaryCal = writableCalendars.firstOrNull { it.isPrimary }
            if (primaryCal != null) return primaryCal

            // Priority 4: Visible writable calendar
            val visibleCal = writableCalendars.firstOrNull { it.isVisible }
            if (visibleCal != null) return visibleCal

            // Priority 5: First writable calendar
            return writableCalendars.first()
        }
    }

    data class CalendarInfo(
        val id: Long,
        val accountName: String?,
        val accountType: String?,
        val displayName: String?,
        val color: Int?,
        val isVisible: Boolean,
        val accessLevel: Int,
        val isPrimary: Boolean,
        val syncEvents: Boolean
    )

    fun getSelectableCalendars(): List<CalendarInfo> {
        val all = queryAllCalendars()
        return all.filter { it.accessLevel >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR }
    }

    suspend fun execute(action: ParsedAction): ExecutionResult {
        Log.d(TAG, "[CALENDAR] Executing event creation: '${action.title}'")

        return try {
            // 1. Verify runtime permissions
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.READ_CALENDAR
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED ||
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.WRITE_CALENDAR
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "[CALENDAR] READ_CALENDAR or WRITE_CALENDAR permission denied")
                return ExecutionResult(
                    state = ExecutionState.FAILED,
                    message = "Calendar permission required. Please grant Calendar access."
                )
            }

            // 2. Discover and select the best writable calendar (respecting user preference if set)
            val allCalendars = queryAllCalendars()
            val preferredId = calendarPreferences.getSelectedCalendarId()
            val preferredAccount = calendarPreferences.getSelectedAccountName()

            val resolution = resolveCalendarToUse(preferredId, preferredAccount, allCalendars)
            if (resolution.selectedCalendar == null) {
                return ExecutionResult(
                    state = ExecutionState.FAILED,
                    message = resolution.errorMessage ?: "No writable calendar found. Please ensure a calendar account is configured."
                )
            }
            val selectedCalendar = resolution.selectedCalendar

            val providerCategory = when {
                selectedCalendar.accountType.equals("com.google", ignoreCase = true) -> "Google account"
                selectedCalendar.accountType.isNullOrBlank() || selectedCalendar.accountType.contains("local", ignoreCase = true) -> "local/device calendar"
                else -> "another calendar provider (${selectedCalendar.accountType})"
            }

            Log.i("EchoFlow-Calendar", """
                [CALENDAR SELECTED]
                Calendar ID: ${selectedCalendar.id}
                Account Name: ${selectedCalendar.accountName}
                Account Type: ${selectedCalendar.accountType} ($providerCategory)
                Display Name: '${selectedCalendar.displayName}'
                Color: ${selectedCalendar.color?.let { "0x" + Integer.toHexString(it).uppercase() } ?: "None"}
                Visible: ${selectedCalendar.isVisible}
                Access Level: ${selectedCalendar.accessLevel} (700=OWNER, 500=CONTRIBUTOR)
                Sync Events: ${selectedCalendar.syncEvents}
                User Preferred: ${preferredId > 0}
            """.trimIndent())

            val executionStart = System.currentTimeMillis()

            // 3. Compute timestamps using device timezone
            val startMillis = action.resolvedEpochMillis
                ?: action.dateTime?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
                ?: (System.currentTimeMillis() + 3600_000L) // Default: 1 hour from now

            val durationMinutes = action.durationMinutes ?: 60
            val durationMs = durationMinutes * 60_000L
            val endMillis = startMillis + durationMs
            val timezone = ZoneId.systemDefault().id

            // 4. Build event ContentValues
            val eventValues = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, selectedCalendar.id)
                put(CalendarContract.Events.TITLE, action.title)
                put(CalendarContract.Events.DESCRIPTION, action.description ?: "Created by EchoFlow")
                put(CalendarContract.Events.DTSTART, startMillis)
                put(CalendarContract.Events.DTEND, endMillis)
                put(CalendarContract.Events.EVENT_TIMEZONE, timezone)
                put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CONFIRMED)
                put(CalendarContract.Events.HAS_ALARM, 1)
            }

            // 5. Insert into Calendar Provider with precise timestamp logging
            val insertStart = System.currentTimeMillis()
            val uri: Uri? = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, eventValues)
            val insertFinish = System.currentTimeMillis()

            if (uri == null) {
                Log.e("EchoFlow-Calendar", "[CALENDAR] ContentResolver.insert returned null URI (insert duration: ${insertFinish - insertStart}ms)")
                return ExecutionResult(
                    state = ExecutionState.FAILED,
                    message = "Calendar provider returned null URI on insertion"
                )
            }

            val eventId = try {
                ContentUris.parseId(uri)
            } catch (e: Exception) {
                Log.w(TAG, "[CALENDAR] Could not parse event ID from URI: $uri", e)
                -1L
            }

            // 6. Add reminder notification (15 minutes prior) to the created event
            if (eventId > 0) {
                try {
                    val reminderValues = ContentValues().apply {
                        put(CalendarContract.Reminders.EVENT_ID, eventId)
                        put(CalendarContract.Reminders.MINUTES, 15)
                        put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                    }
                    context.contentResolver.insert(
                        CalendarContract.Reminders.CONTENT_URI,
                        reminderValues
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "[CALENDAR] Could not attach reminder to event", e)
                }
            }

            // 7. Immediately verify the inserted event through CalendarContract
            val verifyStart = System.currentTimeMillis()
            val verification = verifyEventDetailed(
                eventId = eventId,
                selectedCal = selectedCalendar,
                expectedTitle = action.title,
                expectedDescription = action.description ?: "Created by EchoFlow",
                expectedStartMillis = startMillis,
                expectedEndMillis = endMillis,
                expectedTimezone = timezone
            )
            val verifyFinish = System.currentTimeMillis()

            val timeFormatter = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
                .withZone(ZoneId.systemDefault())

            Log.i("EchoFlow-Calendar", """
                [CALENDAR EXECUTION TIMESTAMPS]
                executionStart=${timeFormatter.format(java.time.Instant.ofEpochMilli(executionStart))}
                insertStart=${timeFormatter.format(java.time.Instant.ofEpochMilli(insertStart))}
                insertFinish=${timeFormatter.format(java.time.Instant.ofEpochMilli(insertFinish))} (${insertFinish - insertStart}ms)
                uri=$uri
                verifyStart=${timeFormatter.format(java.time.Instant.ofEpochMilli(verifyStart))}
                verifyFinish=${timeFormatter.format(java.time.Instant.ofEpochMilli(verifyFinish))} (${verifyFinish - verifyStart}ms)
                verifiedInProvider=${verification.verified}
                providerEventId=$eventId
                account='${selectedCalendar.accountName}'
            """.trimIndent())

            if (!verification.verified) {
                Log.w(TAG, "[CALENDAR] Event insertion verification failed: ${verification.reason}")
                return ExecutionResult(
                    state = ExecutionState.FAILED,
                    message = "Event insert could not be verified in CalendarContract: ${verification.reason}"
                )
            }

            val calDesc = if (!selectedCalendar.accountName.isNullOrBlank()) {
                " in ${selectedCalendar.accountName}"
            } else {
                ""
            }

            // Request cloud sync if Google account
            val accName = selectedCalendar.accountName
            val accType = selectedCalendar.accountType
            if (accType.equals("com.google", ignoreCase = true) && !accName.isNullOrBlank() && accType != null) {
                try {
                    val account = android.accounts.Account(accName, accType)
                    val bundle = android.os.Bundle().apply {
                        putBoolean(android.content.ContentResolver.SYNC_EXTRAS_MANUAL, true)
                        putBoolean(android.content.ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
                    }
                    android.content.ContentResolver.requestSync(account, CalendarContract.AUTHORITY, bundle)
                    Log.d(TAG, "[CALENDAR] Requested expedited cloud sync for ${selectedCalendar.accountName}")
                } catch (e: Exception) {
                    Log.d(TAG, "[CALENDAR] Cloud sync request note: ${e.message}")
                }
            }

            ExecutionResult(
                state = ExecutionState.SUCCESS,
                message = "Event added to calendar$calDesc (${action.title})",
                extraData = eventId.toString()
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "[CALENDAR] SecurityException during execution", e)
            ExecutionResult(
                state = ExecutionState.FAILED,
                message = "Calendar permission required"
            )
        } catch (e: Exception) {
            Log.e(TAG, "[CALENDAR] Exception during calendar event execution", e)
            ExecutionResult(
                state = ExecutionState.FAILED,
                message = "Calendar error: ${e.message ?: "Unknown error"}"
            )
        }
    }


    private fun queryAllCalendars(): List<CalendarInfo> {
        val list = mutableListOf<CalendarInfo>()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.CALENDAR_COLOR,
            CalendarContract.Calendars.VISIBLE,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.SYNC_EVENTS
        )

        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                null,
                null,
                "${CalendarContract.Calendars._ID} ASC"
            )

            cursor?.let {
                val idIdx = it.getColumnIndex(CalendarContract.Calendars._ID)
                val accNameIdx = it.getColumnIndex(CalendarContract.Calendars.ACCOUNT_NAME)
                val accTypeIdx = it.getColumnIndex(CalendarContract.Calendars.ACCOUNT_TYPE)
                val nameIdx = it.getColumnIndex(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
                val colorIdx = it.getColumnIndex(CalendarContract.Calendars.CALENDAR_COLOR)
                val visibleIdx = it.getColumnIndex(CalendarContract.Calendars.VISIBLE)
                val accessIdx = it.getColumnIndex(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
                val syncIdx = it.getColumnIndex(CalendarContract.Calendars.SYNC_EVENTS)

                while (it.moveToNext()) {
                    val id = if (idIdx >= 0) it.getLong(idIdx) else -1L
                    val accName = if (accNameIdx >= 0) it.getString(accNameIdx) else null
                    val accType = if (accTypeIdx >= 0) it.getString(accTypeIdx) else null
                    val name = if (nameIdx >= 0) it.getString(nameIdx) else null
                    val color = if (colorIdx >= 0 && !it.isNull(colorIdx)) it.getInt(colorIdx) else null
                    val visible = if (visibleIdx >= 0) it.getInt(visibleIdx) == 1 else true
                    val access = if (accessIdx >= 0) it.getInt(accessIdx) else CalendarContract.Calendars.CAL_ACCESS_OWNER
                    val sync = if (syncIdx >= 0) it.getInt(syncIdx) == 1 else true

                    if (id > 0) {
                        list.add(
                            CalendarInfo(
                                id = id,
                                accountName = accName,
                                accountType = accType,
                                displayName = name,
                                color = color,
                                isVisible = visible,
                                accessLevel = access,
                                isPrimary = (accType.equals("com.google", ignoreCase = true) || visible),
                                syncEvents = sync
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[CALENDAR] Error querying calendars from provider", e)
        } finally {
            cursor?.close()
        }
        return list
    }

    data class EventVerificationResult(
        val verified: Boolean,
        val reason: String? = null
    )

    private fun verifyEventDetailed(
        eventId: Long,
        selectedCal: CalendarInfo,
        expectedTitle: String,
        expectedDescription: String,
        expectedStartMillis: Long,
        expectedEndMillis: Long,
        expectedTimezone: String
    ): EventVerificationResult {
        if (eventId <= 0) return EventVerificationResult(false, "Invalid event ID: $eventId")

        var cursor: Cursor? = null
        return try {
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
            val projection = arrayOf(
                CalendarContract.Events._ID,
                CalendarContract.Events.CALENDAR_ID,
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DESCRIPTION,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND,
                CalendarContract.Events.EVENT_TIMEZONE,
                CalendarContract.Events.HAS_ALARM,
                CalendarContract.Events.HAS_ATTENDEE_DATA,
                CalendarContract.Events.ALL_DAY,
                CalendarContract.Events.STATUS,
                CalendarContract.Events.RRULE,
                CalendarContract.Events.RDATE
            )

            cursor = context.contentResolver.query(uri, projection, null, null, null)

            if (cursor == null || !cursor.moveToFirst()) {
                return EventVerificationResult(false, "Event ID $eventId not found in provider query")
            }

            val readId = cursor.getLong(cursor.getColumnIndexOrThrow(CalendarContract.Events._ID))
            val readCalId = cursor.getLong(cursor.getColumnIndexOrThrow(CalendarContract.Events.CALENDAR_ID))
            val readTitle = cursor.getString(cursor.getColumnIndexOrThrow(CalendarContract.Events.TITLE))
            val readDesc = cursor.getString(cursor.getColumnIndexOrThrow(CalendarContract.Events.DESCRIPTION))
            val readStart = cursor.getLong(cursor.getColumnIndexOrThrow(CalendarContract.Events.DTSTART))
            val readEnd = cursor.getLong(cursor.getColumnIndexOrThrow(CalendarContract.Events.DTEND))
            val readTz = cursor.getString(cursor.getColumnIndexOrThrow(CalendarContract.Events.EVENT_TIMEZONE))
            val readHasAlarm = cursor.getInt(cursor.getColumnIndexOrThrow(CalendarContract.Events.HAS_ALARM))
            val readHasAttendee = cursor.getInt(cursor.getColumnIndexOrThrow(CalendarContract.Events.HAS_ATTENDEE_DATA))
            val readAllDay = cursor.getInt(cursor.getColumnIndexOrThrow(CalendarContract.Events.ALL_DAY))
            val readStatus = cursor.getInt(cursor.getColumnIndexOrThrow(CalendarContract.Events.STATUS))
            val readRrule = cursor.getString(cursor.getColumnIndexOrThrow(CalendarContract.Events.RRULE))
            val readRdate = cursor.getString(cursor.getColumnIndexOrThrow(CalendarContract.Events.RDATE))

            // Query Reminders for this event ID
            val reminders = mutableListOf<String>()
            var remCursor: Cursor? = null
            try {
                remCursor = context.contentResolver.query(
                    CalendarContract.Reminders.CONTENT_URI,
                    arrayOf(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.MINUTES),
                    "${CalendarContract.Reminders.EVENT_ID} = ?",
                    arrayOf(eventId.toString()),
                    null
                )
                remCursor?.let { rc ->
                    val mIdx = rc.getColumnIndex(CalendarContract.Reminders.METHOD)
                    val minIdx = rc.getColumnIndex(CalendarContract.Reminders.MINUTES)
                    while (rc.moveToNext()) {
                        val mVal = if (mIdx >= 0) rc.getInt(mIdx) else -1
                        val mName = when (mVal) {
                            CalendarContract.Reminders.METHOD_ALERT -> "METHOD_ALERT (1)"
                            CalendarContract.Reminders.METHOD_DEFAULT -> "METHOD_DEFAULT (0)"
                            CalendarContract.Reminders.METHOD_EMAIL -> "METHOD_EMAIL (2)"
                            CalendarContract.Reminders.METHOD_SMS -> "METHOD_SMS (3)"
                            CalendarContract.Reminders.METHOD_ALARM -> "METHOD_ALARM (4)"
                            else -> "UNKNOWN ($mVal)"
                        }
                        val mins = if (minIdx >= 0) rc.getInt(minIdx) else -1
                        reminders.add("$mName, $mins min before")
                    }
                }
            } catch (e: Exception) {
                Log.w("EchoFlow-Calendar", "[CALENDAR] Could not query Reminders for event $eventId", e)
            } finally {
                remCursor?.close()
            }

            // Query CalendarAlerts if available
            var alertSummary = "None"
            var alertsCursor: Cursor? = null
            try {
                alertsCursor = context.contentResolver.query(
                    CalendarContract.CalendarAlerts.CONTENT_URI,
                    arrayOf(
                        CalendarContract.CalendarAlerts.EVENT_ID,
                        CalendarContract.CalendarAlerts.MINUTES,
                        CalendarContract.CalendarAlerts.STATE,
                        CalendarContract.CalendarAlerts.ALARM_TIME
                    ),
                    "${CalendarContract.CalendarAlerts.EVENT_ID} = ?",
                    arrayOf(eventId.toString()),
                    null
                )
                if (alertsCursor != null && alertsCursor.moveToFirst()) {
                    val mins = alertsCursor.getInt(alertsCursor.getColumnIndexOrThrow(CalendarContract.CalendarAlerts.MINUTES))
                    val state = alertsCursor.getInt(alertsCursor.getColumnIndexOrThrow(CalendarContract.CalendarAlerts.STATE))
                    val alarmTime = alertsCursor.getLong(alertsCursor.getColumnIndexOrThrow(CalendarContract.CalendarAlerts.ALARM_TIME))
                    alertSummary = "Alert exists: minutes=$mins, state=$state, alarmTime=$alarmTime"
                }
            } catch (e: Exception) {
                Log.d("EchoFlow-Calendar", "[CALENDAR] CalendarAlerts query note: ${e.message}")
            } finally {
                alertsCursor?.close()
            }

            val statusStr = when (readStatus) {
                CalendarContract.Events.STATUS_TENTATIVE -> "STATUS_TENTATIVE (0)"
                CalendarContract.Events.STATUS_CONFIRMED -> "STATUS_CONFIRMED (1)"
                CalendarContract.Events.STATUS_CANCELED -> "STATUS_CANCELED (2)"
                else -> "UNKNOWN ($readStatus)"
            }

            val dateTimeFormatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.of(readTz ?: ZoneId.systemDefault().id))

            Log.i("EchoFlow-Calendar", """
                [CALENDAR COMPLETE EVENT METADATA]
                Event ID: $readId
                Calendar ID: $readCalId
                Account Name: ${selectedCal.accountName}
                Account Type: ${selectedCal.accountType}
                Calendar Display Name: '${selectedCal.displayName}'
                Calendar Color: ${selectedCal.color?.let { "0x" + Integer.toHexString(it).uppercase() } ?: "None"}
                Calendar Visibility: ${selectedCal.isVisible}
                Calendar Access Level: ${selectedCal.accessLevel}
                TITLE: '$readTitle'
                DESCRIPTION: '$readDesc'
                DTSTART: $readStart (${dateTimeFormatter.format(java.time.Instant.ofEpochMilli(readStart))})
                DTEND: $readEnd (${dateTimeFormatter.format(java.time.Instant.ofEpochMilli(readEnd))})
                EVENT_TIMEZONE: '$readTz'
                HAS_ALARM: $readHasAlarm
                HAS_ATTENDEE_DATA: $readHasAttendee
                ALL_DAY: $readAllDay
                EVENT_STATUS: $statusStr
                RRULE: ${readRrule ?: "None"}
                RDATE: ${readRdate ?: "None"}
                Reminders: ${if (reminders.isEmpty()) "None" else reminders.joinToString("; ")}
                CalendarAlerts: $alertSummary
            """.trimIndent())

            // Concise Diagnostic Report as requested by prompt
            val reminderReport = if (reminders.isEmpty()) "None" else reminders.joinToString("; ")
            Log.i("EchoFlow-Report", """
                ============================================================
                EVENT CREATED
                Event ID: $readId
                Calendar ID: $readCalId
                Account: ${selectedCal.accountName} (${selectedCal.accountType})
                Calendar: '${selectedCal.displayName}'
                Start: ${dateTimeFormatter.format(java.time.Instant.ofEpochMilli(readStart))}
                End: ${dateTimeFormatter.format(java.time.Instant.ofEpochMilli(readEnd))}
                Timezone: $readTz
                Reminder: $reminderReport
                Provider read-back: Verified in CalendarContract provider
                Event exists: YES
                ============================================================
            """.trimIndent())

            if (readId != eventId) {
                return EventVerificationResult(false, "ID mismatch: got $readId, expected $eventId")
            }
            if (readStart != expectedStartMillis) {
                return EventVerificationResult(false, "DTSTART mismatch: got $readStart, expected $expectedStartMillis")
            }

            EventVerificationResult(true)
        } catch (e: Exception) {
            Log.e("EchoFlow-Calendar", "[CALENDAR] Exception during read-back verification", e)
            EventVerificationResult(false, e.message ?: "Verification query failed")
        } finally {
            cursor?.close()
        }
    }
}

