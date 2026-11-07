package cc.bridgit.app.remoteview

import cc.bridgit.app.applets.ChefHelper
import android.app.Activity
import android.net.Uri
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.applets.AppletTableStore
import cc.bridgit.app.applets.AppletTables
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * `window.BridgitApp` in Applets › Portals (owner, 5 Nov 2026): lets an applet's portal page, when the portal is the
 * interface of an applet installed on this device ([PortalTables]), press the applet's buttons (camera, Create
 * inventory, share) and edit or delete its rows right here, instead of queueing them for the next check-in.
 *
 * An origin-checked web message channel (WebViewCompat.addWebMessageListener), not a JavaScript interface: only
 * the main frame of an https://bridgit.cc page gets it, so no iframe (Google sign-in or anything embedded later)
 * can call it, and the page never blocks while the phone works. Protocol (version 2): the page posts
 * `{id, method, args}` as a JSON string; the reply, one per request, is `{id, ok, message?, ...}`. Methods: hello,
 * press {pkg, button}, updateRow {pkg, table, row, set}, deleteRow {pkg, table, row}, state {pkg}. Each acts only
 * on applets whose package lists the table or button.
 */
class PortalBridge(private val app: BridgeApp, private val activity: Activity, private val scope: CoroutineScope,
                   private val onIdentity: (JSONObject) -> Unit = {}) {
    companion object {
        const val NAME = "BridgitApp"
        const val VERSION = 5
        const val ORIGIN = "https://bridgit.cc"

        /** Display metadata only: never changes an applet, its grants or its saved name. */
        fun identityValid(args: JSONObject): Boolean {
            val name = args.opt("name") as? String ?: return false
            val applet = args.opt("applet") as? String ?: return false
            return name.length in 1..40 && name.none { it.code < 32 || it.code == 127 } &&
                (applet == "dog" || Regex("^pkg\\.[a-z][a-z0-9-]{1,63}$").matches(applet))
        }

        /** Adds the bridge to [web] when this WebView supports it (it does on any updated Android System WebView). */
        fun attach(web: WebView, bridge: PortalBridge): Boolean {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return false
            WebViewCompat.addWebMessageListener(web, NAME, setOf(ORIGIN)) { _, message, origin, isMainFrame, reply ->
                bridge.receive(message, origin, isMainFrame, reply)
            }
            return true
        }
    }

    private class Refused(message: String) : Exception(message)

    private fun receive(message: WebMessageCompat, origin: Uri, isMainFrame: Boolean, reply: JavaScriptReplyProxy) {
        // The listener's origin rule already holds; this is the second lock on the door.
        if (!isMainFrame || origin.scheme != "https" || origin.host != "bridgit.cc") return
        val req = runCatching { JSONObject(message.data ?: return) }.getOrNull() ?: return
        val id = req.opt("id")
        if (req.optString("method") == "portalIdentity") {
            val args = req.optJSONObject("args") ?: JSONObject()
            val valid = identityValid(args)
            if (valid) activity.runOnUiThread { onIdentity(args) }
            runCatching { reply.postMessage(JSONObject().put("id", id).put("ok", valid).toString()) }
            return
        }
        scope.launch {
            val out = try { handle(req.optString("method"), req.optJSONObject("args") ?: JSONObject()) }
                catch (e: PortalActions.Refused) { no(e.message) } catch (e: Refused) { no(e.message) }
                catch (e: AppletTables.Refused) { no(e.message) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { no(e.message?.ifBlank { null }) }
            out.put("id", id)
            runCatching { reply.postMessage(out.toString()) } // the page may have gone
        }
    }

    private fun no(message: String?) = JSONObject().put("ok", false).put("message", message ?: "That didn't work.")

    private fun interfaceApplets(): List<JSONObject> =
        app.applets.all().filter { it.optString("native") != "dog" && PortalTables.tablesOf(it.optJSONObject("definition")).isNotEmpty() }

    private fun applet(pkg: String): JSONObject = interfaceApplets().firstOrNull { it.optString("package_id") == pkg }
        ?: throw Refused("That applet isn't installed on this device.")

    private fun table(inst: JSONObject, id: String): AppletTables.Table {
        if (id !in PortalTables.tablesOf(inst.optJSONObject("definition"))) throw Refused("The portal can't change the table $id.")
        return AppletTables.parse(inst.optJSONObject("definition")?.optJSONObject("processing") ?: JSONObject()).firstOrNull { it.id == id }
            ?: throw Refused("The table $id is gone.")
    }

    private suspend fun handle(method: String, a: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        when (method) {
            "hello" -> JSONObject().put("ok", true).put("version", VERSION)
                .put("installId", cc.bridgit.app.feedback.InstallIdentity.status(app)?.installId.orEmpty())
                .put("applets", JSONArray(interfaceApplets().map { it.optString("package_id") }))
                .put("exportApplets", JSONArray(app.applets.all().map { it.optString("package_id") }.filter { it.isNotBlank() }))
            "shareData" -> {
                val data = a.optJSONObject("data") ?: throw Refused("No export data was supplied.")
                val local = a.optString("phone") == cc.bridgit.app.feedback.InstallIdentity.status(app)?.installId
                val inst = if (!local) null else app.applets.all().firstOrNull { it.optString("package_id") == a.optString("pkg") }
                val built = cc.bridgit.app.applets.AppletJsonExport.export(app, inst, data)
                withContext(Dispatchers.Main) { cc.bridgit.app.applets.AppletExport.share(activity, built) }
                JSONObject().put("ok", true)
            }
            "press" -> {
                val inst = applet(a.optString("pkg"))
                PortalActions.press(app, activity, inst, a.optString("button")).also { app.remoteView.packages.tableChanged(inst.optString("agent_id")) }
            }
            "rows" -> {
                val inst=applet(a.optString("pkg")); val t=table(inst,a.optString("table"))
                JSONObject().put("ok",true).put("rows",JSONArray(AppletTableStore.of(app).rows(inst.optString("agent_id"),t.id,1000).map(PortalTables::wire)))
            }
            "addRow" -> {
                val inst=applet(a.optString("pkg")); val t=table(inst,a.optString("table"))
                val values=PortalTables.cleanSet(a.optJSONObject("values") ?: JSONObject(),t.columns.keys,t.columns.values.filter { it.type=="photo" }.map { it.id })
                val row=AppletTableStore.of(app).add(inst.optString("agent_id"),t,values,JSONObject().put("type","owner").put("owner",true).put("via","portal"))
                app.remoteView.packages.tableChanged(inst.optString("agent_id"))
                JSONObject().put("ok",true).put("row",PortalTables.wire(row))
            }
            "updateRow" -> {
                val inst = applet(a.optString("pkg"))
                val t = table(inst, a.optString("table"))
                val set = PortalTables.cleanSet(a.optJSONObject("set") ?: JSONObject(), t.columns.keys, t.columns.values.filter { it.type == "photo" }.map { it.id })
                if (set.length() == 0) throw Refused("Nothing to change.")
                AppletTableStore.of(app).update(inst.optString("agent_id"), t, a.optString("row"), set, JSONObject().put("type", "owner").put("owner", true).put("via", "portal"))
                if(ChefHelper.usesKitchen(inst) && t.id=="activity" && (set.opt("watching")==false || set.opt("muted")==true)) app.speaker.stop(inst.optString("agent_id"))
                app.remoteView.packages.tableChanged(inst.optString("agent_id"))
                JSONObject().put("ok", true)
            }
            "deleteRow" -> {
                val inst = applet(a.optString("pkg"))
                val gone = AppletTableStore.of(app).delete(inst.optString("agent_id"), table(inst, a.optString("table")).id, a.optString("row"))
                app.remoteView.packages.tableChanged(inst.optString("agent_id"))
                if (gone) JSONObject().put("ok", true) else no("That row is already gone.")
            }
            "state" -> {
                val inst = applet(a.optString("pkg"))
                JSONObject().put("ok", true).put("batch", app.remoteView.packages.batchOf(inst.optString("agent_id")) ?: JSONObject.NULL)
                    .put("paused", inst.optString("status") == "paused")
                    .apply { if(ChefHelper.usesKitchen(inst)) put("preview", cc.bridgit.app.applets.ChefHelper.preview(app,inst.optString("agent_id")) ?: JSONObject.NULL) }
            }
            else -> no("Unknown request.")
        }
    }
}
