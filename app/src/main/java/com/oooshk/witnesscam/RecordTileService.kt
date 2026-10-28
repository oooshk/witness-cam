package com.oooshk.witnesscam

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile: one swipe down and a tap starts recording. */
class RecordTileService : TileService() {

    override fun onStartListening() {
        qsTile?.apply {
            state = if (RecordingService.status.value.recording) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        if (RecordingService.status.value.recording) {
            RecordingService.stop(this)
            qsTile?.apply { state = Tile.STATE_INACTIVE; updateTile() }
            return
        }
        // Camera recording must be started from a visible activity, so open the app to start it.
        val intent = Intent(this, MainActivity::class.java)
            .setAction(RecordingService.ACTION_START)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
