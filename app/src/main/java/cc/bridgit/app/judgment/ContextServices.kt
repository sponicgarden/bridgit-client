package cc.bridgit.app.judgment

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.api.*
import cc.bridgit.app.capability.ImageUtil
import cc.bridgit.app.outbound.Outbound
import io.ktor.server.routing.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** General applet services. Explicit coordinates never cause an implicit device-location read. */
object ContextServices {
    val CONTEXT = EndpointSpec("POST", "/services/context", "judgment",
        "Read one location-context service from GET /v1/services: places, airquality, pollen, timezone, maps, streetview, weathergoogle, osm, wikipedia or openmeteo. No implicit phone-location read; send explicit coordinates. Provider keys stay write-only.",
        privateFields = setOf("*"), maxBody = 4096,
        body = listOf(Param("service","string","Context service id from GET /v1/services",true),Param("latitude","number","-90 to 90",true),Param("longitude","number","-180 to 180",true),Param("radius_m","number","1–1000; default 500"),Param("limit","integer","1–10; default 5"),Param("timestamp","integer","Unix seconds for time-zone lookup"),Param("days","integer","1–5 pollen days; default 1"),Param("heading","number","0–360 for Street View")),
        example = "POST /v1/services/context {\"service\":\"places\",\"latitude\":52.2297,\"longitude\":21.0122,\"radius_m\":500,\"limit\":5}",
        returns = "service, source, fetched_at, data (provider JSON or bounded image), attribution, data_use, cost_usd (conservative budget reserve), cost_basis, key (shared or own); context_key_required means configure its provider. An enabled Google API is not proof of a configured key.")
    val STORY = EndpointSpec("POST", "/story", "judgment", "Applet-specific (A Sorta Fairytale About Me only; not a general platform service). Create a kind fairy tale or wild fictional story from 2–8 kept video frame captures, a portrait and optional context, using GLM 5.3 Flash. Images must be accessible to this caller; no new capture or location read.",
        privateFields = setOf("*"), maxBody = 24 * 1024,
        body = listOf(Param("mode","string","kind or wild",true),Param("frame_capture_ids","array","2–8 kept camera frame capture ids",true),Param("portrait_capture_id","string","Kept portrait capture id",true),Param("context","object","Optional context already read through /services/context; treated as untrusted observations")),
        example = "POST /v1/story {\"mode\":\"kind\",\"frame_capture_ids\":[\"cap_one\",\"cap_two\"],\"portrait_capture_id\":\"cap_portrait\"}",
        returns = "title, narration, inventory, mode, model, cost_usd, elapsed_ms, presentation_token; saves a declared story row for the bundled fairytale applet")
    val GENERATE = STORY.copy(path = "/story/generate",
        example = STORY.example.replace("/v1/story ", "/v1/story/generate "),
        returns = "title, narration, inventory, mode, model, cost_usd, elapsed_ms, presentation_token. The caller chooses which fields to keep in its own declared tables.")
    private val epochs=java.util.concurrent.ConcurrentHashMap<String,Long>()
    private val reveals = mutableMapOf<String,kotlinx.coroutines.CompletableDeferred<Unit>>()
    private fun newEpoch(id:String):Long {
        reveals.remove(id)?.cancel()
        return epochs.merge(id,1L,Long::plus)!!
    }
    suspend fun awaitReveal(id:String, token:String?) {
        if (token == null) throw ApiError(422,"invalid_parameter","A presentation token is required")
        val signal = withPresentation(id, token) { reveals.getOrPut(id) { kotlinx.coroutines.CompletableDeferred() } }
        kotlinx.coroutines.withTimeout(180_000) { signal.await() }
    }
    fun <T> withPresentation(agent: String, token: String?, action: () -> T): T = synchronized(epochs) {
        if(token!=null && epochs[agent]?.toString()!=token) throw ApiError(409,"story_stopped","The story presentation was stopped or replaced")
        action()
    }
    val PRESENTATION=EndpointSpec("POST","/story/presentation","judgment","Applet-specific (A Sorta Fairytale About Me only; not a general platform service). Start a new presentation token for replaying a saved story; invalidates older presentation work",example="POST /v1/story/presentation {}",returns="presentation_token")
    val STOP=EndpointSpec("POST","/story/stop","judgment","Applet-specific (A Sorta Fairytale About Me only; not a general platform service). Stop this caller's story presentation; discard late model results and stop its audio and video",example="POST /v1/story/stop {}",returns="stopped")
    val IMAGE=EndpointSpec("POST","/story/image","judgment","Applet-specific (A Sorta Fairytale About Me only; not a general platform service). Generate a fairytale illustration through the Make Fairytale Image registry task and present it as a full-screen HTML interaction card; existing Azure account quota applies. Requires interactions access too. Stop suppresses late cards.",
        privateFields=setOf("*"),maxBody=16*1024,
        body=listOf(Param("wait_for_reveal","boolean","Prepare now and display after /story/reveal"),Param("presentation_token","string","Token from /story or /story/presentation; Stop invalidates it",true),Param("title","string","Story title, at most 160 characters",true),Param("narration","string","Completed story, at most 8000 characters",true),Param("request_id","string","Stable idempotency key for this story image, 8–128 letters/digits/dash/underscore; reuse on an unknown outcome",true)),
        example="POST /v1/story/image {\"presentation_token\":\"1\",\"title\":\"The tiny kingdom\",\"narration\":\"Once upon a time…\",\"request_id\":\"fairytale-12345678\"}",
        returns="interaction_id, task_key, model, quota, display {shown_as, full_screen, missing?, fix?}; picture bytes stay on the phone and do not cross the card response bridge")
    val REVEAL = PRESENTATION.copy(path="/story/reveal", summary="Applet-specific (A Sorta Fairytale About Me only; not a general platform service). Allow prepared narration and illustration to appear",body=listOf(Param("presentation_token","string","Current story token",true)),example="POST /v1/story/reveal",returns="revealed")
    val ALL = listOf(CONTEXT, STORY, GENERATE, STOP, IMAGE, PRESENTATION, REVEAL)

    private fun authorize(app: BridgeApp, agent: String) {
        val live=app.agents.get(agent)
        if(live == null || live.suspended || !live.allows("judgment")) throw ApiError(403,"agent_not_allowed","Decision access was revoked")
        val inst=app.applets.forAgent(agent)
        if(inst != null && inst.optString("status")!="active") throw ApiError(403,"agent_not_allowed","The applet is paused or removed")
    }
    fun stopPresentation(app:BridgeApp, id:String) { synchronized(epochs) { newEpoch(id); Jev.stopStory(id); cc.bridgit.app.capture.VisibleCapture.cancel(id); app.speaker.stop(id); cc.bridgit.app.planned.AppletVideoSessions.stop(id) } }
    fun Route.install(api: ApiServer, app: BridgeApp) = with(api) {
        endpoint(STOP) { stopPresentation(app, agent!!.id); Reply.Json(JSONObject().put("stopped",true)) }
        endpoint(PRESENTATION) { val token=synchronized(epochs) { app.speaker.stop(agent!!.id); newEpoch(agent!!.id) }; Reply.Json(JSONObject().put("presentation_token",token.toString())) }
        endpoint(REVEAL) {
            val token = body.optString("presentation_token")
            withPresentation(agent!!.id,token) { reveals.getOrPut(agent!!.id) { kotlinx.coroutines.CompletableDeferred() }.complete(Unit) }
            Reply.Json(JSONObject().put("revealed",true))
        }
        endpoint(IMAGE) {
            val id=agent!!.id
            val epoch=body.optString("presentation_token").toLongOrNull() ?: throw ApiError(422,"invalid_parameter","presentation_token is required")
            fun allowed() {
                authorize(app,id)
                if(epochs[id]!=epoch) throw ApiError(409,"story_stopped","The story was stopped or replaced")
                if(app.agents.get(id)?.allows("interactions")!=true || !app.settings.isOn("interactions")) throw ApiError(403,"agent_not_allowed","Interaction access is required")
                if(!app.captureNotificationsOn()) throw ErrorCatalog.error("notifications_required","Notifications must be enabled to show the image card")
            }
            val title=body.opt("title") as? String ?: throw ApiError(422,"invalid_parameter","title is required")
            val text=body.opt("narration") as? String ?: throw ApiError(422,"invalid_parameter","narration is required")
            val requestId=body.optString("request_id")
            if(title.isBlank() || title.length>160 || text.isBlank() || text.length>8000) throw ApiError(422,"invalid_parameter","Invalid story title or narration")
            allowed()
            val result=ImageGeneration.call(app,JSONObject().put("prompt",title+"\n"+text).put("task_key","make_fairytale_image").put("size","1536x1024").put("output_format","jpeg"),requestId,id)
            allowed()
            val jpeg=withContext(Dispatchers.IO) {
                val raw=android.util.Base64.decode(result.getJSONArray("data").getJSONObject(0).getString("b64_json"),android.util.Base64.DEFAULT)
                var size=960
                var image=ImageUtil.prepare(raw,"image/jpeg",Sizing.Spec(size,null,75),forceJpeg=true).bytes
                while(image.size>150_000 && size>240) { size/=2; image=ImageUtil.prepare(raw,"image/jpeg",Sizing.Spec(size,null,65),forceJpeg=true).bytes }
                if(image.size>150_000) throw ApiError(502,"upstream_failed","Image exceeds the card limit")
                image
            }
            if(body.optBoolean("wait_for_reveal")) awaitReveal(id,epoch.toString())
            val html=FairytaleImageCard.html(title,java.util.Base64.getEncoder().encodeToString(jpeg))
            val draft=cc.bridgit.app.interact.InteractionRules.parse(JSONObject().put("kind","html").put("title",title.take(80)).put("html",html))
            val answer=withContext(Dispatchers.Main.immediate) {
                allowed()
                val display=cc.bridgit.app.interact.InteractionNotifier.display(app,false)
                val card=withPresentation(id,epoch.toString()) { app.interactions.create(id,agent!!.name,draft,null) }
                JSONObject().put("interaction_id",card.id).put("task_key",result.getString("task_key")).put("model",result.getString("model")).put("quota",result.getJSONObject("quota"))
                    .put("display",JSONObject().put("shown_as",display.shownAs).put("full_screen",display.shownAs=="full_screen").apply {
                        if(display.missing.isNotEmpty()) put("missing",JSONArray(display.missing)).put("fix",cc.bridgit.app.interact.NotifRules.fix(display.missing))
                    })
            }
            Reply.Json(answer)
        }
        endpoint(CONTEXT) {
            val id=agent!!.id
            val result=withContext(Dispatchers.IO) {
                val service=body.optString("service")
                val descriptor=(0 until ContextCatalog.services().length()).map { ContextCatalog.services().getJSONObject(it) }.firstOrNull { it.optString("id")==service }
                    ?: throw ApiError(422,"invalid_parameter","Unknown context service; read GET /v1/services")
                val provider=descriptor.optJSONObject("configuration")?.optString("credential_provider")
                val own=provider?.let { OwnKeys(app).get(it)?.let { key -> "$it=$key" } }
                val answer=Outbound.hinted(Outbound.Hint("Bridgit place context", "Read $service for the calling applet", noBodyPreview=true)) {
                    Jev.external(app,"/v1/services/context",body,own) { authorize(app,id) }
                }
                // Cards receive at most 64 KiB. Retain attribution and return a bounded preview, never an oversize success.
                if(service in setOf("maps","streetview")) {
                    val image=answer.getJSONObject("data")
                    val raw=android.util.Base64.decode(image.getString("data_base64"),android.util.Base64.DEFAULT)
                    val thumb=ImageUtil.prepare(raw,image.getString("content_type"),Sizing.Spec(256,null,55),forceJpeg=true)
                    if(thumb.bytes.size>36_000) throw ApiError(502,"upstream_failed","Map preview exceeded its bounded size")
                    image.put("data_base64",java.util.Base64.getEncoder().encodeToString(thumb.bytes)).put("content_type","image/jpeg")
                        .put("width_px",thumb.width).put("height_px",thumb.height).put("preview",true)
                }
                answer
            }
            Reply.Json(result)
        }
        val generateStory: suspend Req.() -> Reply = {
            val id=agent!!.id
            val epoch=synchronized(epochs) { newEpoch(id) }
            fun stillCurrent() { authorize(app,id); if(epochs[id]!=epoch) throw ApiError(409,"story_stopped","The story was stopped or replaced") }
            val result=withContext(Dispatchers.IO) {
                val frames=body.optJSONArray("frame_capture_ids") ?: throw ApiError(422,"invalid_parameter","frame_capture_ids is required")
                if(frames.length() !in 2..8) throw ApiError(422,"invalid_parameter","Send 2–8 frame ids")
                fun image(value:Any?):String {
                    val captureId=value as? String ?: throw ApiError(422,"invalid_parameter","Capture ids must be text")
                    val found=app.captures.get(captureId,id,"camera") ?: throw ApiError(404,"photo_not_found","Capture unavailable to this caller")
                    val jpeg=ImageUtil.prepare(found.data,found.entry.mime,Sizing.Spec(960,null,75),forceJpeg=true).bytes
                    return Jev.dataUrl(jpeg)
                }
                val request=JSONObject().put("mode",body.optString("mode")).put("frames",JSONArray((0 until frames.length()).map { image(frames.opt(it)) }))
                    .put("portrait",image(body.opt("portrait_capture_id"))).put("context",body.optJSONObject("context") ?: JSONObject())
                val own=OwnKeys(app).get("openrouter")?.let { "openrouter=$it" }
                val reply=Outbound.hinted(Outbound.Hint("GLM fairytale", "Write a story from the supplied captures and context", noBodyPreview=true)) {
                    Jev.external(app,"/v1/story",request,own) { stillCurrent() }
                }
                stillCurrent()
                reply.put("presentation_token",epoch.toString())
                val inst = if (spec == STORY) app.applets.forAgent(id) else null
                if(inst?.optString("package_id")=="a-sorta-fairytale-about-me") {
                    val table=cc.bridgit.app.applets.AppletTables.parse(inst.getJSONObject("definition").getJSONObject("processing")).first { it.id=="stories" }
                    val saved=JSONObject().put("title",reply.getString("title")).put("narration",reply.getString("narration")).put("mode",reply.getString("mode"))
                    cc.bridgit.app.applets.AppletTableStore.of(app).add(id,table,JSONObject().put("result",saved).put("status","done"),JSONObject().put("type","story"))
                }
                reply
            }
            Reply.Json(result)
        }
        endpoint(STORY, block = generateStory)
        endpoint(GENERATE, block = generateStory)
    }
}
