package com.echoflow.app.data.prefs

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages persistence of the user's preferred Calendar ID and metadata.
 * Uses SharedPreferences for instant synchronous startup reads and reactive StateFlow for Compose UI.
 */
class CalendarPreferences(context: Context) {

    companion object {
        private const val PREFS_NAME = "echoflow_calendar_prefs"
        private const val KEY_CALENDAR_ID = "selected_calendar_id"
        private const val KEY_ACCOUNT_NAME = "selected_account_name"
        private const val KEY_DISPLAY_NAME = "selected_display_name"
        const val AUTO_SELECT_ID = -1L
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _selectedCalendarId = MutableStateFlow(
        prefs.getLong(KEY_CALENDAR_ID, AUTO_SELECT_ID)
    )
    val selectedCalendarId: StateFlow<Long> = _selectedCalendarId.asStateFlow()

    private val _selectedAccountName = MutableStateFlow<String?>(
        prefs.getString(KEY_ACCOUNT_NAME, null)
    )
    val selectedAccountName: StateFlow<String?> = _selectedAccountName.asStateFlow()

    private val _selectedDisplayName = MutableStateFlow<String?>(
        prefs.getString(KEY_DISPLAY_NAME, null)
    )
    val selectedDisplayName: StateFlow<String?> = _selectedDisplayName.asStateFlow()

    fun getSelectedCalendarId(): Long = prefs.getLong(KEY_CALENDAR_ID, AUTO_SELECT_ID)

    fun getSelectedAccountName(): String? = prefs.getString(KEY_ACCOUNT_NAME, null)

    fun setSelectedCalendar(id: Long, accountName: String?, displayName: String?) {
        prefs.edit()
            .putLong(KEY_CALENDAR_ID, id)
            .putString(KEY_ACCOUNT_NAME, accountName)
            .putString(KEY_DISPLAY_NAME, displayName)
            .apply()

        _selectedCalendarId.value = id
        _selectedAccountName.value = accountName
        _selectedDisplayName.value = displayName
    }

    fun clearSelection() {
        prefs.edit()
            .remove(KEY_CALENDAR_ID)
            .remove(KEY_ACCOUNT_NAME)
            .remove(KEY_DISPLAY_NAME)
            .apply()

        _selectedCalendarId.value = AUTO_SELECT_ID
        _selectedAccountName.value = null
        _selectedDisplayName.value = null
    }
}
