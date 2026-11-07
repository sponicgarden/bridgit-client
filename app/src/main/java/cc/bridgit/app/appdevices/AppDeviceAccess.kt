package cc.bridgit.app.appdevices

import android.content.Context
import cc.bridgit.app.R

/** Native app catalog and owner-managed external-device membership. Agent grants stay separate. */
class AppDeviceAccess(context: Context, legacyCameras: List<String>) {
    data class App(val id: String, val title: Int)
    companion object {
        const val DOG = "dog_monitor"
        const val TOUR = "city_tour"
        val apps = listOf(App(TOUR, R.string.tour_title), App(DOG, R.string.dog_title))
    }
    private val prefs = context.getSharedPreferences("app_device_access", Context.MODE_PRIVATE)
    init {
        // Existing cameras were all available to Pet monitor. Preserve that access exactly once.
        if (!prefs.contains("initialized")) prefs.edit().apply {
            legacyCameras.forEach { putStringSet(it, setOf(DOG)) }
            putBoolean("initialized", true)
        }.apply()
    }
    @Synchronized fun appsFor(device: String): Set<String> = prefs.getStringSet(device, emptySet())!!.toSet()
    fun allows(app: String, device: String) = app in appsFor(device)
    var onChanged: ((Set<String>) -> Unit)? = null
    fun setApps(device: String, selected: Set<String>) {
        require(selected.all { id -> apps.any { it.id == id } })
        val revoked = synchronized(this) {
            val old = appsFor(device)
            check(prefs.edit().putStringSet(device, selected.toSet()).commit())
            if (DOG in old && DOG !in selected) setOf(device) else emptySet()
        }
        onChanged?.invoke(revoked)
    }
    fun setDevices(app: String, available: Set<String>, selected: Set<String>) {
        require(apps.any { it.id == app } && selected.all { it in available })
        val revoked = synchronized(this) {
            val removed = available.filter { allows(DOG, it) && app == DOG && it !in selected }.toSet()
            val edit = prefs.edit()
            available.forEach { device ->
                val next = appsFor(device) - app + if (device in selected) setOf(app) else emptySet()
                edit.putStringSet(device, next)
            }
            check(edit.commit())
            removed
        }
        onChanged?.invoke(revoked)
    }
    fun remove(device: String) {
        val revoked = synchronized(this) {
            val old = appsFor(device)
            check(prefs.edit().remove(device).commit())
            if (DOG in old) setOf(device) else emptySet()
        }
        onChanged?.invoke(revoked)
    }
}
