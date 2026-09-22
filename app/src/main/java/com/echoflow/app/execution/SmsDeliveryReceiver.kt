package com.echoflow.app.execution

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import android.util.Log
import com.echoflow.app.data.db.EchoFlowDatabase
import com.echoflow.app.domain.model.ExecutionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Tracks real delivery status of SMS sent via SmsManager.
 * Ensures the app never claims SMS was delivered if the carrier reports failure.
 */
class SmsDeliveryReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "EchoFlow-SmsDelivery"
        const val ACTION_SMS_SENT = "com.echoflow.app.execution.ACTION_SMS_SENT"
        const val ACTION_SMS_DELIVERED = "com.echoflow.app.execution.ACTION_SMS_DELIVERED"
        const val EXTRA_ACTION_ID = "extra_action_id"
        const val EXTRA_RECIPIENT = "extra_recipient"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val actionId = intent.getStringExtra(EXTRA_ACTION_ID) ?: return
        val recipient = intent.getStringExtra(EXTRA_RECIPIENT) ?: "contact"

        val pendingResult = goAsync()
        when (intent.action) {
            ACTION_SMS_SENT -> {
                val resultCode = resultCode
                if (resultCode == Activity.RESULT_OK) {
                    Log.i(TAG, "[SMS SENT] SMS successfully dispatched by carrier to $recipient (actionId=$actionId)")
                    updateDbState(context, actionId, ExecutionState.SUCCESS, "SMS sent to $recipient") {
                        ProactiveVoiceNotifier.speakAsync(context, "Message sent to $recipient.", pendingResult)
                    }
                } else {
                    val errorDesc = when (resultCode) {
                        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "Generic carrier failure"
                        SmsManager.RESULT_ERROR_NO_SERVICE -> "No cellular service"
                        SmsManager.RESULT_ERROR_NULL_PDU -> "Null PDU"
                        SmsManager.RESULT_ERROR_RADIO_OFF -> "Airplane mode / radio off"
                        else -> "Carrier error (code $resultCode)"
                    }
                    Log.e(TAG, "[SMS SEND FAILED] Could not send SMS to $recipient: $errorDesc (actionId=$actionId)")
                    updateDbState(context, actionId, ExecutionState.FAILED, "SMS send failed: $errorDesc") {
                        ProactiveVoiceNotifier.speakAsync(context, "Your message to $recipient could not be sent.", pendingResult)
                    }
                }
            }
            ACTION_SMS_DELIVERED -> {
                Log.i(TAG, "[SMS DELIVERED] Handset delivery confirmed for $recipient (actionId=$actionId)")
                updateDbState(context, actionId, ExecutionState.SUCCESS, "SMS delivered to $recipient") {
                    pendingResult.finish()
                }
            }
            else -> {
                pendingResult.finish()
            }
        }
    }

    private fun updateDbState(
        context: Context,
        actionId: String,
        state: ExecutionState,
        message: String,
        onComplete: (() -> Unit)? = null
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                EchoFlowDatabase.getInstance(context).historyDao().updateActionExecutionState(
                    actionId = actionId,
                    state = state.name,
                    message = message
                )
            } catch (e: Exception) {
                Log.w(TAG, "[SMS STATUS] Could not update action state in DB", e)
            } finally {
                onComplete?.invoke()
            }
        }
    }
}
