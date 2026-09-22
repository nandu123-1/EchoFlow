package com.echoflow.app.execution

import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Shared helper for querying Android ContactsContract.
 */
object ContactsHelper {

    private const val TAG = "EchoFlow-Contacts"

    /**
     * Look up phone number in Android ContactsContract by name.
     */
    fun lookupPhoneNumberByName(context: Context, name: String): String? {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "[CONTACTS] READ_CONTACTS permission not granted, cannot lookup '$name'")
            return null
        }

        var cursor: Cursor? = null
        return try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
            )
            val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("%$name%")

            cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
            if (cursor != null && cursor.moveToFirst()) {
                val numberIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val matchedNameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val number = if (numberIdx >= 0) cursor.getString(numberIdx) else null
                val matchedName = if (matchedNameIdx >= 0) cursor.getString(matchedNameIdx) else null
                Log.d(TAG, "[CONTACTS] Found matching contact '$matchedName' with phone '$number'")
                number
            } else {
                Log.d(TAG, "[CONTACTS] No contact found matching '$name'")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "[CONTACTS] Exception during contact lookup for '$name'", e)
            null
        } finally {
            cursor?.close()
        }
    }
}
