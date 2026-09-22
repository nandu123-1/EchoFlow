package com.echoflow.app.scheduling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Restores pending scheduled EchoFlow actions and the Notification Action Palette
 * upon device restart (BOOT_COMPLETED).
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "EchoFlow-Boot"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            Log.i(TAG, "[BOOT] Device reboot completed. Restoring scheduled actions...")
            ScheduledActionManager.getInstance(context).restoreScheduledActionsAfterRestart()
        }
    }
}
