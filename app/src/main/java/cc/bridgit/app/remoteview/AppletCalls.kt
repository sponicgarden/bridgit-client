package cc.bridgit.app.remoteview

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.judgment.Jev
import okhttp3.Call
import okhttp3.Response

/** Tracks even calls not yet executing, so disabling consent also cancels a pending fallback. */
object AppletCalls {
    private val calls = mutableSetOf<Call>()
    @Synchronized fun cancelDog() { calls.toList().forEach { it.cancel() } }
    fun check(app: BridgeApp, applet: String?, allowPaused: Boolean = false) {
        if (applet == "dog" && !app.remoteView.dogBackend)
            throw Jev.Failure("backend_off", "Device only: remote AI is disabled for this applet on this device.")
        if (applet == "dog" && !allowPaused && app.remoteView.dogPaused)
            throw Jev.Failure("portal_paused", "Pet monitor is paused from your web portal.")
    }
    @Synchronized fun track(app: BridgeApp, applet: String?, call: Call) {
        check(app, applet); if (applet == "dog") calls.add(call)
    }
    @Synchronized fun untrack(call: Call) { calls.remove(call) }
    fun execute(app: BridgeApp, applet: String?, call: Call, authorized: () -> Unit = {}, allowPaused: Boolean = false): Response {
        if (applet != "dog") { authorized(); return call.execute() }
        synchronized(this) { check(app, applet, allowPaused); authorized(); calls.add(call) }
        try { return call.execute() } finally { synchronized(this) { calls.remove(call) } }
    }
}
