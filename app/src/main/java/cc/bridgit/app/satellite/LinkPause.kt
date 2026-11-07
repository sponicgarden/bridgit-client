package cc.bridgit.app.satellite

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The two pause pills at the top of More › Linked devices (owner, 5 Nov 2026): "Satellites: enabled / paused"
 * and "Hubs: enabled / paused". Each takes that side of the phone-to-phone network down in both directions:
 * this phone stops calling those phones, and refuses what they send it. Nothing is unlinked and nothing is forgotten;
 * switching back on carries on as before. Owner-only: there is no API for it.
 */
object LinkPause {
    private const val PREFS = "link_pause_v1"
    @Volatile private var sats = false
    @Volatile private var hubs = false
    @Volatile private var prefs: android.content.SharedPreferences? = null
    private val bump = MutableStateFlow(0)
    /** Changes whenever a switch is flipped, so the screen can redraw. */
    val changed: StateFlow<Int> get() = bump

    fun attach(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        sats = p.getBoolean("satellites", false); hubs = p.getBoolean("hubs", false)
    }

    /** This phone, as a hub, has paused all its satellites. */
    val satellites: Boolean get() = sats
    /** This phone, as a satellite, has paused all its hubs. */
    val hubsPaused: Boolean get() = hubs

    fun setSatellites(on: Boolean) { sats = on; prefs?.edit()?.putBoolean("satellites", on)?.commit(); bump.value++ }
    fun setHubs(on: Boolean) { hubs = on; prefs?.edit()?.putBoolean("hubs", on)?.commit(); bump.value++ }

    const val SATELLITES_MESSAGE = "The owner paused connections to all satellites on this phone. Wait until they switch them back on; don't retry before then."
    const val HUBS_MESSAGE = "The owner paused connections to all hubs on this phone. Wait until they switch them back on; don't retry before then."
}
