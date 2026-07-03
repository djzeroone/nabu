package com.mewmix.nabu.assistant

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.mewmix.nabu.MainActivity

class NabuTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile
        tile.state = Tile.STATE_INACTIVE
        tile.label = "Nabu Assistant"
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivityAndCollapse(intent)
    }
}
