package cc.bridgit.app.appdevices

import android.content.Context
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Both entry points edit the same memberships; selecting an app never changes its active source. */
object DeviceAccessDialogs {
    private fun stopRevokedMonitor(app: BridgeApp, removedFromDog: Boolean) {
        if ((removedFromDog && app.dog.state.value.running) || app.dog.state.value.cameras.any { it.startsWith("lan:") && !app.appDevices.allows(AppDeviceAccess.DOG, it) })
            app.dog.stop(app.getString(R.string.app_device_revoked), "device_access_removed")
    }
    fun forDevice(context: Context, app: BridgeApp, id: String, name: String, saved: () -> Unit) {
        val entries = AppDeviceAccess.apps
        val selected = entries.map { app.appDevices.allows(it.id, id) }.toBooleanArray()
        MaterialAlertDialogBuilder(context).setTitle(context.getString(R.string.device_add_to_apps_title, name))
            .setMultiChoiceItems(entries.map { context.getString(it.title) }.toTypedArray(), selected) { _, which, checked -> selected[which] = checked }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.device_access_save) { _, _ ->
                val next = entries.filterIndexed { index, _ -> selected[index] }.map { it.id }.toSet()
                val removedFromDog = app.appDevices.allows(AppDeviceAccess.DOG, id) && AppDeviceAccess.DOG !in next
                app.appDevices.setApps(id, next)
                stopRevokedMonitor(app, removedFromDog)
                saved()
            }.show()
    }
    fun forApp(context: Context, app: BridgeApp, appId: String, saved: () -> Unit = {}) {
        val cameras = runCatching { app.lanCameras.all().map { it.id to it.name } + app.lanDevices.all().map { it.id to it.name } }.getOrElse {
            MaterialAlertDialogBuilder(context).setTitle(R.string.app_device_access).setMessage(R.string.lan_store_error)
                .setPositiveButton(android.R.string.ok, null).show()
            return
        }
        if (cameras.isEmpty()) {
            MaterialAlertDialogBuilder(context).setTitle(R.string.app_device_access).setMessage(R.string.app_devices_empty)
                .setPositiveButton(android.R.string.ok, null).show()
            return
        }
        val selected = cameras.map { app.appDevices.allows(appId, it.first) }.toBooleanArray()
        MaterialAlertDialogBuilder(context).setTitle(R.string.app_device_access)
            .setMultiChoiceItems(cameras.map { it.second }.toTypedArray(), selected) { _, which, checked -> selected[which] = checked }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.device_access_save) { _, _ ->
                val next = cameras.filterIndexed { index, _ -> selected[index] }.map { it.first }.toSet()
                val removedFromDog = appId == AppDeviceAccess.DOG && cameras.any { app.appDevices.allows(appId, it.first) && it.first !in next }
                app.appDevices.setDevices(appId, cameras.map { it.first }.toSet(), next)
                stopRevokedMonitor(app, removedFromDog)
                saved()
            }.show()
    }
}
