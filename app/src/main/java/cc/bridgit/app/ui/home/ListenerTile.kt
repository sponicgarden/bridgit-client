package cc.bridgit.app.ui.home

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BridgeService
import cc.bridgit.app.R
import cc.bridgit.app.ui.MainActivity
import android.annotation.SuppressLint
import android.os.Build

/**
 * UI-23: Quick Settings tile "Bridgit". Active while the listener setting is on (Listening), inactive
 * when paused. Tap toggles like Home's Pause / Resume; pausing always works, even on the lock screen,
 * resuming from the lock screen asks to unlock first. Long-press opens Home (QS_TILE_PREFERENCES on
 * MainActivity). The app asks for an update whenever the setting changes ([requestUpdate]).
 */
class ListenerTile : TileService() {
    private val app get() = application as BridgeApp

    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    override fun onClick() {
        super.onClick()
        if (app.settings.listenerEnabled) pause()
        else if (isLocked) unlockAndRun { resume() }
        else resume()
    }

    private fun pause() {
        app.settings.listenerEnabled = false // first, as on Home, so nothing restarts it
        runCatching { BridgeService.pauseSilently(this) } // not running is fine: the setting is what counts
        app.log.system("audit", "setting", "You paused the listener from the Quick Settings tile")
        render()
    }

    private fun resume() {
        // As on Home: with no agents there's nothing to listen for, so open the app (Home leads to Add agent).
        if (app.agents.count == 0) { openApp(); return }
        app.settings.listenerEnabled = true
        app.log.system("audit", "setting", "You resumed the listener from the Quick Settings tile")
        // If Android refuses a foreground start from the tile, opening the app starts it (MainActivity.onResume).
        if (runCatching { BridgeService.start(this) }.isFailure) openApp()
        render()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated") // the Intent form is the only one before Android 14
    private fun openApp() {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(pi)
            else @Suppress("DEPRECATION") startActivityAndCollapse(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
    }

    private fun render() {
        val t = qsTile ?: return
        val on = app.settings.listenerEnabled
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = getString(R.string.tile_label)
        t.subtitle = getString(if (on) R.string.tile_listening else R.string.tile_paused)
        t.icon = Icon.createWithResource(this, R.drawable.ic_tile_bridgit)
        t.contentDescription = "${t.label}, ${t.subtitle}"
        t.updateTile()
    }

    companion object {
        /** Asks the system to refresh the tile (it's an ACTIVE_TILE, so it only updates when asked). */
        fun requestUpdate(ctx: Context) {
            runCatching { requestListeningState(ctx, ComponentName(ctx, ListenerTile::class.java)) }
        }
    }
}
