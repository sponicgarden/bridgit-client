package cc.bridgit.app.remoteview

import android.content.Context
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.applets.AppletPackage
import cc.bridgit.app.applets.AppletTableStore
import cc.bridgit.app.applets.AppletTables
import cc.bridgit.app.interact.CardPieces
import org.json.JSONArray
import org.json.JSONObject

/**
 * The applet card's buttons, pressed from its web portal ([PortalTables]): the portal page in the app's web view
 * ([PortalBridge]) presses one by id, the same way the card would: the camera button opens the camera screen, a
 * flow button starts its flow (or its batch, one run per row), a share step builds the file and opens Android's
 * share sheet. Only the ids the package lists in `ui.portal.actions` can be pressed.
 */
object PortalActions {
    class Refused(message: String) : Exception(message)

    private fun card(inst: JSONObject): JSONObject? {
        val def = inst.optJSONObject("definition") ?: return null
        val plan = AppletPackage.compile(AppletPackage.check(def), inst.optJSONObject("config") ?: JSONObject())
        return plan.card?.piecesFilled
    }

    private fun button(inst: JSONObject, id: String): Pair<JSONObject, JSONObject> {
        if (id !in PortalTables.actionsOf(inst.optJSONObject("definition"))) throw Refused("The portal can't press $id on this applet.")
        if (cc.bridgit.app.applets.AppletLifecycle.state(inst) in setOf("starting", "pausing")) throw Refused("Wait for the applet transition to finish.")
        val card = card(inst) ?: throw Refused("This applet has no buttons.")
        val b = PortalTables.button(card, id) ?: throw Refused("The button $id is gone.")
        return card to b
    }

    private fun filled(o: JSONObject?): JSONObject {
        val row = JSONObject()
        o?.keys()?.forEach { k -> row.put(k, CardPieces.resolve(o.opt(k), CardPieces.Values(emptyMap())) ?: JSONObject.NULL) }
        return row
    }

    /**
     * Presses [id] on [inst]'s card from the portal page. [context] is the activity showing the page (the camera
     * screen and the share sheet open over it). Returns {ok, message?}. Blocking work runs on the caller's thread.
     */
    suspend fun press(app: BridgeApp, context: Context, inst: JSONObject, id: String): JSONObject {
        val (card, b) = button(inst, id)
        val agentId = inst.optString("agent_id")
        val d = b.optJSONObject("do") ?: throw Refused("The button $id does nothing.")
        d.optJSONObject("camera")?.let { c ->
            val row = filled(c.optJSONObject("row"))
            android.os.Handler(android.os.Looper.getMainLooper()).post { cc.bridgit.app.ui.camera.AppletCameraActivity.start(context, agentId, 0L, c, row) }
            return JSONObject().put("ok", true)
        }
        d.optJSONObject("step")?.takeIf { it.optString("type") == "share" }?.let { st ->
            val table = tables(inst).firstOrNull { it.id == st.optString("table") } ?: throw Refused("The table ${st.optString("table")} is gone.")
            val built = cc.bridgit.app.applets.AppletExport.export(app, agentId, st, table)
            android.os.Handler(android.os.Looper.getMainLooper()).post { cc.bridgit.app.applets.AppletExport.share(context, built) }
            return JSONObject().put("ok", true)
        }
        if (d.has("flow")) return flow(app, inst, card, b, d)
        throw Refused("The portal can't press this kind of button yet.")
    }

    /** A button that needs no screen (a flow): pressed for a portal command from another computer. */
    suspend fun pressHeadless(app: BridgeApp, inst: JSONObject, id: String): JSONObject {
        val (card, b) = button(inst, id)
        val d = b.optJSONObject("do") ?: throw Refused("The button $id does nothing.")
        if (!d.has("flow")) throw Refused("$id needs the phone's screen: press it in Bridgit on the phone.")
        return flow(app, inst, card, b, d)
    }

    private fun tables(inst: JSONObject): List<AppletTables.Table> =
        AppletTables.parse(inst.optJSONObject("definition")?.optJSONObject("processing") ?: JSONObject())

    private suspend fun flow(app: BridgeApp, inst: JSONObject, card: JSONObject, b: JSONObject, d: JSONObject): JSONObject {
        val agentId = inst.optString("agent_id")
        val flow = d.opt("flow").let { (it as? JSONObject)?.optString("\$flow") ?: it.toString() }
        if (!d.has("for_each")) {
            val with = d.optJSONObject("with")?.let { filled(it) }
            val r = app.platform.workflows.start(agentId, flow, JSONObject().put("caller", "portal"), with)
            return JSONObject().put("ok", r.optString("status") in setOf("started", "dropped"))
                .put("message", when (r.optString("status")) { "started" -> "Started."; "dropped" -> "It's already running."; else -> r.optString("message").ifBlank { "That didn't start." } })
        }
        if (app.platform.batches.running(agentId) != null) return JSONObject().put("ok", true).put("message", "It's already running.")
        val path = d.getJSONObject("for_each").optString("\$source").split('.')
        val table = card.optJSONObject("sources")?.optJSONObject(path[0])?.optString("table")?.takeIf { it.isNotEmpty() }
            ?: throw Refused("This button doesn't go through a table.")
        val list = JSONArray(AppletTableStore.of(app).rows(agentId, table, 1000, null, newestFirst = false))
        val key = b.optString("id").ifEmpty { b.optString("label") }
        val r = app.platform.batches.start(agentId, key, b.optString("label"), flow, CardPieces.batchElements(d, list, CardPieces.Values(emptyMap())))
        return when (r.optString("status")) {
            "started" -> JSONObject().put("ok", true).put("total", r.optInt("total"))
            "empty" -> JSONObject().put("ok", true).put("message", "Nothing to do: every photo is read.")
            else -> JSONObject().put("ok", false).put("message", r.optString("message").ifBlank { "That didn't start." })
        }
    }
}
