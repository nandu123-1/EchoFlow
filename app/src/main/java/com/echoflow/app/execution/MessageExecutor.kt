package com.echoflow.app.execution

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.echoflow.app.domain.model.ExecutionState
import com.echoflow.app.domain.model.ParsedAction

/**
 * Prepares a messaging intent with prefilled recipient and message body.
 *
 * Checks ContactsContract for matching contact phone numbers if recipient is a name
 * (e.g., "Rahul"). Honest reporting: always reports "Message prepared", never "Message sent",
 * because it launches the system messaging composer.
 */
class MessageExecutor(private val context: Context) {

    companion object {
        private const val TAG = "EchoFlow-Message"
    }

    suspend fun execute(action: ParsedAction): ExecutionResult {
        val rawRecipient = action.recipient?.trim()
        val messageBody = action.message ?: ""
        Log.d(TAG, "[MESSAGE] Preparing message. Recipient='$rawRecipient', Body='$messageBody'")

        return try {
            // Resolve recipient phone number if it's a name
            val resolvedPhoneNumber = if (!rawRecipient.isNullOrBlank() && !rawRecipient.matches(Regex("""^[+0-9\- ()]+$"""))) {
                lookupPhoneNumberByName(rawRecipient)
            } else {
                rawRecipient
            }

            val target = resolvedPhoneNumber ?: rawRecipient ?: ""
            Log.d(TAG, "[MESSAGE] Resolved target for intent: '$target'")

            // Build SMS Intent using standard smsto: URI
            val smsUri = Uri.parse("smsto:$target")
            val intent = Intent(Intent.ACTION_SENDTO, smsUri).apply {
                putExtra("sms_body", messageBody)
                putExtra(Intent.EXTRA_TEXT, messageBody)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                val targetDesc = if (target.isNotBlank()) " to $target" else ""
                Log.d(TAG, "[MESSAGE] System messaging composer opened successfully$targetDesc")
                ExecutionResult(
                    state = ExecutionState.PREPARED,
                    message = "Message prepared$targetDesc"
                )
            } else {
                // Fallback to text share chooser
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, messageBody)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val chooser = Intent.createChooser(shareIntent, "Send message to ${action.recipient ?: "contact"}")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)

                Log.d(TAG, "[MESSAGE] Share chooser opened as fallback")
                ExecutionResult(
                    state = ExecutionState.PREPARED,
                    message = "Message prepared (share sheet)"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "[MESSAGE] Error launching messaging app", e)
            ExecutionResult(
                state = ExecutionState.FAILED,
                message = "Could not open messaging app: ${e.message ?: "Unknown error"}"
            )
        }
    }

    /**
     * Look up phone number in Android ContactsContract by name.
     */
    private fun lookupPhoneNumberByName(name: String): String? {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "[MESSAGE] READ_CONTACTS permission not granted, using raw name '$name'")
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
                Log.d(TAG, "[MESSAGE] Found matching contact '$matchedName' with phone number '$number'")
                number
            } else {
                Log.d(TAG, "[MESSAGE] No contacts found matching '$name'")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "[MESSAGE] Exception during contact lookup for '$name'", e)
            null
        } finally {
            cursor?.close()
        }
    }
}
