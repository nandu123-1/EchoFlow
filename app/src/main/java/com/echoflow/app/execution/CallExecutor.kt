package com.echoflow.app.execution

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.echoflow.app.domain.model.ExecutionMode
import com.echoflow.app.domain.model.ExecutionState
import com.echoflow.app.domain.model.ParsedAction
import com.echoflow.app.scheduling.ScheduledActionManager

/**
 * Executor for CALL actions.
 *
 * SAFETY INVARIANT:
 * EchoFlow must NEVER silently initiate a phone call automatically.
 *
 * For scheduled calls or proactive confirmation, it registers the action with
 * [ScheduledActionManager]. At the designated time, the user is proactively prompted.
 * Only after explicit user confirmation is the dial/call flow launched.
 */
class CallExecutor(private val context: Context) {

    companion object {
        private const val TAG = "EchoFlow-CallExecutor"
    }

    private val scheduledActionManager = ScheduledActionManager.getInstance(context)

    suspend fun execute(action: ParsedAction): ExecutionResult {
        val rawRecipient = action.recipient?.trim()
        Log.d(TAG, "[CALL EXECUTOR] Processing CALL action for: '$rawRecipient' (mode: ${action.executionMode})")

        // 1. Resolve contact phone number if name was provided
        val resolvedPhone = action.recipientPhone ?: if (!rawRecipient.isNullOrBlank() && !rawRecipient.matches(Regex("""^[+0-9\- ()]+$"""))) {
            lookupPhoneNumberByName(rawRecipient)
        } else {
            rawRecipient
        }

        val enrichedAction = action.copy(recipientPhone = resolvedPhone)

        // 2. Scheduled or Proactive Confirmation Mode
        val hasFutureTime = (enrichedAction.resolvedEpochMillis ?: 0L) > System.currentTimeMillis()
        if (enrichedAction.executionMode == ExecutionMode.PROACTIVE_CONFIRMATION ||
            enrichedAction.executionMode == ExecutionMode.SCHEDULED ||
            hasFutureTime
        ) {
            val result = scheduledActionManager.scheduleAction(enrichedAction)
            return if (result.success) {
                ExecutionResult(
                    state = ExecutionState.SCHEDULED,
                    message = "Call confirmation scheduled for ${enrichedAction.requestedTime ?: "requested time"}"
                )
            } else {
                ExecutionResult(
                    state = ExecutionState.FAILED,
                    message = result.message
                )
            }
        }

        // 3. Immediate Call Flow (Requires explicit user interaction to complete)
        return try {
            val target = resolvedPhone ?: rawRecipient ?: ""
            val dialUri = Uri.parse("tel:${Uri.encode(target)}")

            val hasCallPermission = ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.CALL_PHONE
            ) == PackageManager.PERMISSION_GRANTED

            val intent = if (hasCallPermission) {
                Intent(Intent.ACTION_CALL, dialUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } else {
                Intent(Intent.ACTION_DIAL, dialUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }

            context.startActivity(intent)

            // SAFETY RULE ①: Never claim "call completed" on dialer launch!
            ExecutionResult(
                state = ExecutionState.PREPARED,
                message = "Dialer opened for ${rawRecipient ?: target}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "[CALL EXECUTOR] Error opening dialer", e)
            ExecutionResult(
                state = ExecutionState.FAILED,
                message = "Could not open dialer: ${e.message}"
            )
        }
    }

    private fun lookupPhoneNumberByName(name: String): String? {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "[CALL EXECUTOR] READ_CONTACTS permission not granted, using raw name '$name'")
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
                val numberCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                if (numberCol >= 0) {
                    val number = cursor.getString(numberCol)
                    Log.d(TAG, "[CALL EXECUTOR] Found phone number for '$name': '$number'")
                    number
                } else null
            } else {
                Log.d(TAG, "[CALL EXECUTOR] No contact found matching '$name'")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "[CALL EXECUTOR] Error querying contacts", e)
            null
        } finally {
            cursor?.close()
        }
    }
}
