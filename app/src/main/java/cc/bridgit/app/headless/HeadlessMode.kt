package cc.bridgit.app.headless

import android.content.Context
import android.hardware.display.DisplayManager
import android.graphics.Point
import android.view.Display
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BridgeService
import cc.bridgit.app.core.Net
import org.json.JSONObject

object HeadlessMode {
    const val SETTING = "device.presentation"
    private const val STORE = "presentation_mode"
    fun mode(app: BridgeApp): String = app.platform.store.setting(STORE, "auto")
    fun set(app: BridgeApp, value: String) {
        require(value in HeadlessPolicy.MODES)
        app.platform.store.setting(STORE, value, true)
        BridgeService.running?.poke("presentation changed")
    }
    @Suppress("DEPRECATION")
    private fun size(context: Context): Point = Point().also { p ->
        context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.getRealSize(p)
    }
    fun displayAvailable(app: BridgeApp): Boolean = size(app).let { it.x > 1 && it.y > 1 }
    fun active(app: BridgeApp): Boolean = size(app).let { HeadlessPolicy.active(mode(app), it.x, it.y) }
    fun accepts(app: BridgeApp, source: String): Boolean =
        HeadlessPolicy.sourceAllowed(active(app), source, Net.isTailnet(source))
    fun status(app: BridgeApp): JSONObject = size(app).let { p -> JSONObject()
        .put("mode", mode(app)).put("headless", HeadlessPolicy.active(mode(app), p.x, p.y))
        .put("display_available", p.x > 1 && p.y > 1)
        .put("webview_available", app.packageManager.hasSystemFeature("android.software.webview"))
        .put("usb_setup", "/v1/tools/headless-setup") }
    /** Starting the app does not create an agent, enable switches, or undo an owner's pause. */
    fun launch(app: BridgeApp) {
        if (app.settings.listenerEnabled) runCatching { BridgeService.start(app) }
    }
}
