package com.echoflow.app.assistant

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.echoflow.app.MainActivity

/**
 * Quick Settings Tile allowing instant summoning of the EchoFlow ambient assistant
 * from any screen, game, or application via the Android notification pull-down shade.
 *
 * PRIVACY & SAFETY:
 * - Completely transparent to normal touch.
 * - Standard official Android TileService API.
 */
class EchoFlowTileService : TileService() {

    companion object {
        private const val TAG = "EchoFlow-Tile"
    }

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        tile.state = Tile.STATE_INACTIVE
        tile.label = "EchoFlow"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = "Tap to summon"
        }
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        Log.i(TAG, "[TILE] Quick Settings tile clicked -> summoning EchoFlow assistant")

        if (com.echoflow.app.ambient.AmbientController.isServiceConnected()) {
            unlockAndRun {
                com.echoflow.app.ambient.AmbientController.handleShortcutTrigger(
                    this,
                    GestureTriggerController.TRIGGER_QUICK_SETTINGS_TILE
                )
            }
        } else {
            // Fallback: Launch MainActivity if accessibility service is not active
            GestureTriggerController.onTriggerFired(this, GestureTriggerController.TRIGGER_QUICK_SETTINGS_TILE)
            val intent = Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(GestureTriggerController.EXTRA_TRIGGER_SOURCE, GestureTriggerController.TRIGGER_QUICK_SETTINGS_TILE)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
    }
}
