package com.echoflow.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.echoflow.app.scheduling.ScheduledActionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles notification action buttons from the ongoing EchoFlow Action Palette.
 * Specifically [ACTION_CANCEL_ALL], cancelling the entire pending workflow.
 */
class PaletteActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "EchoFlow-PaletteReceiver"
        const val ACTION_CANCEL_ALL = "com.echoflow.app.notification.ACTION_CANCEL_ALL"
        const val ACTION_CANCEL_SINGLE = "com.echoflow.app.notification.ACTION_CANCEL_SINGLE"
        const val ACTION_SNOOZE_SINGLE = "com.echoflow.app.notification.ACTION_SNOOZE_SINGLE"
        const val EXTRA_ACTION_ID = "extra_action_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i(TAG, "[PALETTE RECEIVER] Received action: $action")

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val manager = ScheduledActionManager.getInstance(context)
                when (action) {
                    ACTION_CANCEL_ALL -> {
                        val count = manager.cancelAll()
                        Log.i(TAG, "[PALETTE RECEIVER] Cancelled all $count pending action(s)")
                    }
                    ACTION_CANCEL_SINGLE -> {
                        val actionId = intent.getStringExtra(EXTRA_ACTION_ID)
                        if (actionId != null) {
                            manager.cancelAction(actionId)
                            Log.i(TAG, "[PALETTE RECEIVER] Cancelled single action $actionId")
                        }
                    }
                    ACTION_SNOOZE_SINGLE -> {
                        val actionId = intent.getStringExtra(EXTRA_ACTION_ID)
                        if (actionId != null) {
                            manager.snoozeAction(actionId, snoozeMinutes = 5)
                            Log.i(TAG, "[PALETTE RECEIVER] Snoozed single action $actionId for 5 min")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "[PALETTE RECEIVER] Error handling $action", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
