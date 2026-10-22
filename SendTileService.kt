package com.clipsync.app

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class SendTileService : TileService() {
    override fun onStartListening() {
        val t = qsTile ?: return
        t.state = Tile.STATE_ACTIVE
        t.updateTile()
    }

    override fun onClick() {
        val i = Intent(this, SendActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (isLocked) {
            unlockAndRun { open(i) }
        } else {
            open(i)
        }
    }

    @Suppress("DEPRECATION")
    private fun open(i: Intent) {
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this, 0, i,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        } else {
            startActivityAndCollapse(i)
        }
    }
}
