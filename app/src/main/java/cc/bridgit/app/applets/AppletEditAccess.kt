package cc.bridgit.app.applets

import android.content.Context
import org.json.JSONObject

/** Owner-selected editors can read the package and propose updates, without table, pause or delete access. */
class AppletEditAccess(context: Context) {
    private val prefs = context.getSharedPreferences("applet_edit_access", Context.MODE_PRIVATE)

    fun grant(instanceId: String, agentId: String) {
        prefs.edit().putString(instanceId, agentId).commit()
    }

    fun canEdit(record: JSONObject, agentId: String): Boolean =
        record.optString("requested_by") == agentId || prefs.getString(record.getString("id"), null) == agentId

    fun clear(instanceId: String) { prefs.edit().remove(instanceId).apply() }
}
