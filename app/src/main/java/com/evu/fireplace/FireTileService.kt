package com.evu.fireplace

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile (swipe down from the top of the screen). Tap to light the fire with your
 * last level and timer, tap again to put it out. Lighting opens the app briefly, which is what
 * lets Android start the heating service reliably on every phone brand.
 */
class FireTileService : TileService() {

    companion object {
        fun requestUpdate(context: Context) {
            try {
                requestListeningState(context, ComponentName(context, FireTileService::class.java))
            } catch (e: Exception) {
                // tile not added by the user, or the system refused: nothing to update
            }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        if (HeatState.ui.value.running) {
            startService(HeatService.stopIntent(this))
            refresh()
        } else if (isLocked) {
            unlockAndRun { openApp() }
        } else {
            openApp()
        }
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_AUTOSTART)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            val pending = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val s = HeatState.ui.value
        tile.state = if (s.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        if (Build.VERSION.SDK_INT >= 29) {
            val temp = s.reading?.batteryC
            tile.subtitle = if (s.running) {
                (if (temp != null) "%.0f°C · ".format(temp) else "") + s.level.label
            } else {
                "Tap to light"
            }
        }
        tile.updateTile()
    }
}