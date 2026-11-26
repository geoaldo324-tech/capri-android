package io.github.emmanuelquintana.capri

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * (1.14.0) «Hablar con Capri»: a Quick Settings tile that brings her up talking — her call
 * screen (CallActivity.kt), where she listens the whole time and no wake word is needed.
 * If she isn't running, her service starts first so her ears are there. The other tile
 * (CapriTile) stays what it was: her on or off.
 */
class CapriTalkTile : TileService() {
    override fun attachBaseContext(newBase: android.content.Context) = super.attachBaseContext(Lang.wrap(newBase))

    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        if (!CapriService.running) CapriService.start(this)
        val intent = Intent(this, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            // (Android 14+ wants a PendingIntent from a tile, not a bare intent)
            val pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
