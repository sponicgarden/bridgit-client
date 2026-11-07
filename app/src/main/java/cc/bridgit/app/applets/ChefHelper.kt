package cc.bridgit.app.applets

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.api.ApiError
import cc.bridgit.app.judgment.Jev
import cc.bridgit.app.outbound.Outbound
import cc.bridgit.app.outbound.Categories
import org.json.JSONArray
import org.json.JSONObject

/** Chef's kitchen-specific workflow step. No microphone, and no cooking photos are kept. */
object ChefHelper {
    const val SESSION_MS = 60 * 60_000L
    fun active(settings: JSONObject?, now: Long, sessionMs: Long = SESSION_MS) = settings?.optBoolean("watching") == true &&
        settings.optLong("started_at") > 0 && now - settings.optLong("started_at") in 0 until sessionMs

    data class Preview(val bytes: ByteArray, val at: Long, val camera: String, val startedAt: Long, val sessionMs: Long)
    private val previews = java.util.concurrent.ConcurrentHashMap<String, Preview>()
    fun camera(settings: JSONObject?): String = if (settings?.optString("camera") == "front") "front" else "rear"
    fun settings(app: BridgeApp, agent: String): JSONObject? = AppletTableStore.of(app)
        .scan(agent,"activity",null,JSONObject().put("column","kind").put("is","settings"),1).rows.firstOrNull()
    fun preview(app: BridgeApp, agent: String): JSONObject? {
        val s = settings(app, agent)
        val p = previews[agent] ?: return null
        if (!watching(app, agent, p.sessionMs) || p.startedAt != s?.optLong("started_at") || p.camera != camera(s) || System.currentTimeMillis()-p.at > 45_000) {
            previews.remove(agent, p); return null
        }
        return JSONObject().put("image", Jev.dataUrl(p.bytes)).put("at",p.at).put("camera",p.camera).put("started_at",p.startedAt)
    }
    fun notePreview(app: BridgeApp, agent: String, bytes: ByteArray, camera: String, startedAt: Long, sessionMs: Long) {
        val s = settings(app,agent)
        if (watching(app,agent,sessionMs) && s?.optLong("started_at") == startedAt && ChefHelper.camera(s) == camera) {
            // A small in-memory thumbnail only; cooking frames never become saved photos.
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size) ?: return
            try {
                val scale = minOf(1.0, 480.0 / maxOf(bitmap.width,bitmap.height))
                val small = android.graphics.Bitmap.createScaledBitmap(bitmap,maxOf(1,(bitmap.width*scale).toInt()),maxOf(1,(bitmap.height*scale).toInt()),true)
                try {
                    val out=java.io.ByteArrayOutputStream()
                    small.compress(android.graphics.Bitmap.CompressFormat.JPEG,70,out)
                    previews[agent]=Preview(out.toByteArray(),System.currentTimeMillis(),camera,startedAt,sessionMs)
                } finally { if (small !== bitmap) small.recycle() }
            } finally { bitmap.recycle() }
        }
    }

    /** Bind the kitchen service to the installed workflow, including privately forked packages. */
    fun usesKitchen(instance: JSONObject): Boolean {
        val processing = instance.optJSONObject("definition")?.optJSONObject("processing") ?: return false
        fun containsKitchen(flow: JSONObject?): Boolean {
            val steps = flow?.optJSONArray("steps") ?: return false
            return (0 until steps.length()).any { steps.optJSONObject(it)?.let(cc.bridgit.app.platform.FlowSteps::hostStep)?.optString("type") == "kitchen" }
        }
        if (containsKitchen(processing.optJSONObject("workflow"))) return true
        val flows = processing.optJSONArray("flows") ?: return false
        return (0 until flows.length()).any { containsKitchen(flows.optJSONObject(it)) }
    }

    private fun permitted(app: BridgeApp, agent: String): Boolean {
        val inst=app.applets.forAgent(agent) ?: return false
        return usesKitchen(inst) && inst.optString("status")=="active" &&
            inst.getString("package_id") in app.remoteView.packages.enabledIds() && runCatching { app.platform.authorize(agent,"decisions"); true }.getOrDefault(false)
    }
    fun watching(app: BridgeApp, agent: String, sessionMs: Long = SESSION_MS): Boolean {
        if(!permitted(app,agent)) return false
        val s=AppletTableStore.of(app).scan(agent,"activity",null,JSONObject().put("column","kind").put("is","settings"),1).rows.firstOrNull()
        return active(s,System.currentTimeMillis(),sessionMs)
    }
    internal fun requestBody(mode: String, image: String, area: String, observationContext: () -> JSONObject): JSONObject {
        val body = when (mode) {
            "scan" -> JSONObject()
            "observe" -> observationContext()
            else -> throw ApiError(422, "invalid_workflow", "Unknown kitchen analysis mode")
        }
        return body.put("mode", mode).put("image", image).put("area", area)
    }

    fun read(app: BridgeApp, agent: String, workflow: JSONObject, bytes: ByteArray, mode: String, area: String, timing: cc.bridgit.app.platform.FlowSteps.CookingTiming, signed: Boolean): JSONObject {
        val sessionMs = timing.sessionS * 1000L
        val inst=app.applets.forAgent(agent) ?: throw ApiError(422,"no_table","Install the kitchen applet first")
        if(!usesKitchen(inst)) throw ApiError(422,"invalid_workflow","The installed package must declare a kitchen step")
        if(!permitted(app,agent)) throw ApiError(403,"backend_off","Enable this applet’s portal to allow kitchen analysis")
        val context = LibraryVision.context(inst,workflow,required=signed)
        val store=AppletTableStore.of(app)
        fun settings()=store.scan(agent,"activity",null,JSONObject().put("column","kind").put("is","settings"),1).rows.firstOrNull()
        val before=if (mode == "observe") settings() else null
        if(mode=="observe" && !active(before,System.currentTimeMillis(),sessionMs)) return JSONObject().put("skipped",true)
        val previous = if (mode == "observe") store.scan(agent,"activity",null,JSONObject().put("column","kind").put("is","tip"),1).rows.firstOrNull() else null
        val body = requestBody(mode, Jev.dataUrl(bytes), area) {
            val pantry = store.rows(agent,"ingredients",200).map { JSONObject().put("label",it.optString("label")).put("note",it.optString("note")) }
            JSONObject().put("goals",before?.optString("goals").orEmpty()).put("pantry",JSONArray(pantry))
                .put("previous_tip",previous?.optString("tip").orEmpty())
        }
        if (context != null) {
            app.budgetSync.confirmEditionBudget(inst,context)
            body.put("library",context)
        }
        val answer=Outbound.on(Outbound.Actor.agent(agent,app.agents.get(agent)?.name ?: inst.getString("name")),
            Outbound.Hint("GLM 5.3 Flash (OpenRouter via Bridgit)","Read kitchen ingredients or give a timely cooking suggestion",listOf(Categories.IMAGE,Categories.AGENT_STATE))) { Jev.kitchen(app,body) {
                LibraryVision.verify(app,agent,inst,workflow,context)
                if(!permitted(app,agent) || (mode=="observe" && (!watching(app,agent,sessionMs) || settings()?.optLong("started_at")!=before?.optLong("started_at") || camera(settings())!=camera(before)))) throw ApiError(403,"backend_off","Kitchen capture was stopped before upload")
            } }
        LibraryVision.verify(app,agent,inst,workflow,context)
        if(!permitted(app,agent)) throw ApiError(403,"backend_off","Kitchen analysis was stopped while the model was working")
        if(mode=="scan") return answer
        val after=settings()
        // Stop, expiry, or a new session while the model was working discards stale advice.
        if(!active(after,System.currentTimeMillis(),sessionMs) || after?.optLong("started_at")!=before?.optLong("started_at") || camera(after)!=camera(before)) return JSONObject().put("skipped",true)
        val table=AppletTables.parse(inst.getJSONObject("definition").getJSONObject("processing")).first { it.id=="activity" }
        var tip=answer.optString("tip").take(400)
        if(previous!=null && System.currentTimeMillis()-previous.optLong("at")<timing.tipGapS * 1000L) tip=""
        if(previous?.optString("tip")==tip) tip=""
        val row=JSONObject().put("kind",if(tip.isBlank()) "observation" else "tip").put("cooking",answer.optBoolean("cooking"))
            .put("action",answer.optString("action").take(160)).put("tip",tip)
        store.add(agent,table,row,JSONObject().put("type","flow"))
        app.remoteView.packages.tableChanged(agent)
        if(tip.isNotBlank() && after?.optBoolean("muted")!=true) {
            app.platform.authorize(agent,"audio.speak")
            app.speaker.admit(agent,"replace")
            app.speaker.speakText(agent,inst.getString("name"),tip,false,allowCloud=true) {
                val latest=settings()
                runCatching { app.platform.authorize(agent,"audio.speak"); true }.getOrDefault(false) && watching(app,agent,sessionMs) && latest?.optBoolean("muted")!=true && latest?.optLong("started_at")==before?.optLong("started_at") && camera(latest)==camera(before)
            }
        }
        return answer.put("tip",tip)
    }
}
