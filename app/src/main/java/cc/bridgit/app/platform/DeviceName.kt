package cc.bridgit.app.platform

import android.os.Build
import android.provider.Settings
import cc.bridgit.app.BridgeApp

/**
 * The phone's friendly name, the same everywhere (remote view, Bridgit Portal, pairing links): the name the owner
 * gave it in Agent control, else the phone's own name from Settings › About (e.g. "A11 Blue Tab"), else a
 * marketing name for known models (e.g. "Samsung Galaxy Tab A11+"), else maker and model ("Samsung SM-X238U").
 */
object DeviceName {
    const val MAX = 60

    private val SAMSUNG_TABLETS = mapOf("SM-X13" to "Galaxy Tab A11", "SM-X23" to "Galaxy Tab A11+", "SM-X11" to "Galaxy Tab A9", "SM-X21" to "Galaxy Tab A9+")

    fun set(app: BridgeApp, value: String) {
        require(value.length <= MAX && value.none { it.code < 32 || it.code == 127 }) { "Device name must be at most $MAX characters without control characters" }
        app.platform.store.setting("device_name", value.trim(), true)
        app.platform.changed()
    }

    fun of(app: BridgeApp): String = pick(
        custom = runCatching { app.platform.store.setting("device_name", "") }.getOrNull(),
        system = runCatching { Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull(),
        manufacturer = Build.MANUFACTURER.orEmpty(), model = Build.MODEL.orEmpty())

    /**
     * The name this phone gives other Bridgit phones it links with (hub, satellite). A test build adds its suffix,
     * "Pixel 2 XL (test applets)", so a phone with the real app and a test build on it shows up as two different
     * hubs, not one name for two apps (5 Nov 2026: a Pixel 10 linked to the test build looked linked to the real app).
     */
    fun forLinks(app: BridgeApp): String = tagged(of(app))

    /** Pure: [name] with the build's tag for any package but the published one; never longer than [MAX]. */
    fun tagged(name: String, appId: String = cc.bridgit.app.BuildConfig.APPLICATION_ID): String {
        val tag = buildTag(appId) ?: return name
        val end = " ($tag)"
        return if (name.endsWith(end)) name else name.take(MAX - end.length).trim() + end
    }

    /** cc.bridgit.app.test_claude_applets → "test applets", cc.bridgit.app.debug → "debug", cc.bridgit.app → null. */
    fun buildTag(appId: String): String? {
        val suffix = appId.removePrefix("cc.bridgit.app").removePrefix(".")
        if (suffix.isEmpty() || suffix == appId) return if (appId == "cc.bridgit.app") null else appId
        return suffix.removePrefix("test_claude_").let { if (it != suffix) "test " + it.replace('_', ' ') else it.replace('_', ' ') }
    }

    /** Pure: the first usable name. A system name equal to the bare model code adds nothing, so it is skipped. */
    fun pick(custom: String?, system: String?, manufacturer: String, model: String): String {
        fun clean(s: String?) = s?.replace(Regex("[\\u0000-\\u001f\\u007f]"), "")?.trim()?.takeIf { it.isNotEmpty() }
        return (clean(custom) ?: clean(system)?.takeIf { !it.equals(model.trim(), ignoreCase = true) } ?: marketing(manufacturer, model)).take(MAX)
    }

    /** "Samsung Galaxy Tab A11+" for known tablets, else "Samsung SM-X238U". */
    fun marketing(manufacturer: String, model: String): String {
        val upper = model.uppercase()
        SAMSUNG_TABLETS.entries.firstOrNull { upper.startsWith(it.key) }?.let { return "Samsung " + it.value }
        return listOf(manufacturer.replaceFirstChar { it.uppercase() }, model).filter { it.isNotBlank() }.distinct().joinToString(" ").ifBlank { "Android device" }
    }
}
