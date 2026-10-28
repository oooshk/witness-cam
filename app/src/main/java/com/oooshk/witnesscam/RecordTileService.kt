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
        // Stopping asks for the PIN; starting must happen from a visible activity, so both open UI.
        val intent = if (RecordingService.status.value.recording) {
            PinActivity.stopIntent(this)
        } else {
            Intent(this, MainActivity::class.java)
                .setAction(RecordingService.ACTION_START)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
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
