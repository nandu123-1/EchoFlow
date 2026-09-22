package com.echoflow.app.execution

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import com.echoflow.app.data.db.EchoFlowDatabase
import com.echoflow.app.domain.model.ExecutionState
import com.echoflow.app.scheduling.ScheduledActionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles explicit user taps from the proactive Call Confirmation Notification:
 * - [ACTION_CALL_NOW]: Launches dialer / call flow with honest reporting (state: IN_PROGRESS / PREPARED)
 * - [ACTION_SNOOZE_CALL]: Snoozes the existing action in-place for 5 minutes
 * - [ACTION_CANCEL_CALL]: Marks the single action CANCELLED
 */
class CallActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "EchoFlow-CallAction"
        const val ACTION_CALL_NOW = "com.echoflow.app.execution.ACTION_CALL_NOW"
        const val ACTION_SNOOZE_CALL = "com.echoflow.app.execution.ACTION_SNOOZE_CALL"
        const val ACTION_CANCEL_CALL = "com.echoflow.app.execution.ACTION_CANCEL_CALL"

        const val EXTRA_ACTION_ID = "extra_action_id"
        const val EXTRA_RECIPIENT = "extra_recipient"
        const val EXTRA_PHONE = "extra_phone"
        const val EXTRA_NOTIF_ID = "extra_notif_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val actionId = intent.getStringExtra(EXTRA_ACTION_ID) ?: return
        val recipient = intent.getStringExtra(EXTRA_RECIPIENT) ?: "Contact"
        val phone = intent.getStringExtra(EXTRA_PHONE) ?: ""
        val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, actionId.hashCode())

        // Dismiss notification immediately
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(notifId)

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val scheduler = ScheduledActionManager.getInstance(context)
                val database = EchoFlowDatabase.getInstance(context)

                when (action) {
                    ACTION_CALL_NOW -> {
                        Log.i(TAG, "[CALL ACTION] User tapped CALL for '$recipient'")

                        // Launch Call Flow
                        val target = phone.ifBlank { recipient }
                        val dialUri = Uri.parse("tel:${Uri.encode(target)}")

                        val hasCallPermission = ContextCompat.checkSelfPermission(
                            context, android.Manifest.permission.CALL_PHONE
                        ) == PackageManager.PERMISSION_GRANTED

                        val callIntent = if (hasCallPermission) {
                            Intent(Intent.ACTION_CALL, dialUri).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        } else {
                            Intent(Intent.ACTION_DIAL, dialUri).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        }

                        try {
                            context.startActivity(callIntent)
                            Log.i(TAG, "[CALL ACTION] Call/Dial activity launched successfully")

                            // SAFETY & HONESTY RULE ①: Do NOT mark dialer launch as "call completed"!
                            // Mark as IN_PROGRESS with message "Dialer opened"
                            database.historyDao().updateActionExecutionState(
                                actionId = actionId,
                                state = ExecutionState.IN_PROGRESS.name,
                                message = "Dialer opened for $recipient"
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "[CALL ACTION] Error launching dialer", e)
                            database.historyDao().updateActionExecutionState(
                                actionId = actionId,
                                state = ExecutionState.FAILED.name,
                                message = "Could not open dialer: ${e.message}"
                            )
                        }

                        scheduler.onActionTriggered(actionId)
                    }

                    ACTION_SNOOZE_CALL -> {
                        Log.i(TAG, "[CALL ACTION] User tapped SNOOZE for '$recipient'")
                        // SAFETY & IN-PLACE SNOOZE RULE ⑤: Modify existing action record in-place!
                        scheduler.snoozeAction(actionId, snoozeMinutes = 5)
                    }

                    ACTION_CANCEL_CALL -> {
                        Log.i(TAG, "[CALL ACTION] User tapped CANCEL for '$recipient'")
                        // DISTINCT CANCEL RULE ④: Cancel single action by ID
                        scheduler.cancelAction(actionId)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "[CALL ACTION] Error processing $action", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
