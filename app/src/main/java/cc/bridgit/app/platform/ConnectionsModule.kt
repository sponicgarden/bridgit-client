package cc.bridgit.app.platform

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.api.ApiServer
import cc.bridgit.app.api.EndpointSpec
import cc.bridgit.app.api.ErrorCatalog
import cc.bridgit.app.api.Param
import cc.bridgit.app.api.Reply
import cc.bridgit.app.capability.Capability
import io.ktor.server.routing.Route
import org.json.JSONArray
import org.json.JSONObject

/**
 * Webhooks out (docs/applet-reach.md A2-A3) for agents and single steps: the owner's connections by name, and one
 * send to one of them. A flow's `send` step and `do.step` send use the same path (`DeliveryManager.send`). Agents see
 * a connection's id, name, service and whether it takes photos, never its address or secrets.
 */
object ConnectionsApi {
    val LIST = EndpointSpec("GET", "/connections", "connections",
        "The owner's connections (webhooks out to Home Assistant, Discord, ntfy, Telegram, n8n…) you can send to: id, name and service, never the address or secrets",
        example = "GET /v1/connections",
        returns = "connections [{id, name, preset, service, photos (takes a photo file), needs_text, private_network, enabled, failing}]")
    val SEND = EndpointSpec("POST", "/connections/send", "connections",
        "Send a message, data and optionally a kept photo to one of the owner's connections. Queued and retried by default; wait_reply waits up to 15 s and returns the reply",
        body = listOf(
            Param("to", "string", "A connection id from GET /v1/connections", required = true),
            Param("data", "object", "Up to 32 keys of text, numbers or true/false (16 KiB). Bridgit adds applet, flow, run_id, sent_at and phone"),
            Param("text", "string", "A short line, up to 1000 characters. Needed for Discord, Slack, Telegram, ntfy and Pushover"),
            Param("photo", "string", "A kept photo of your applet (kept:…), for connections that take photos"),
            Param("wait_reply", "boolean", "true sends now and waits up to 15 s for the reply")),
        example = "POST /v1/connections/send {\"to\":\"<connection id>\",\"text\":\"A package is at the front door\",\"data\":{\"where\":\"front door\"}}",
        returns = "queued true and delivery_id; or with wait_reply: status (HTTP), reply (JSON) or reply_text, queued false",
        errors = listOf("invalid_parameter", "not_found", "rate_limited", "upstream_failed"))
    val CONFIGURE = EndpointSpec("POST", "/connections/configure", "connections", "Create or replace a connection under App settings, Super Powers and owner.configuration authority; URL, headers and credentials are write-only",
        idempotent=true, privateFields=setOf("*"), body=listOf(Param("id","string","Existing connection id to replace; omit to create"),Param("name","string","Display name",true),
            Param("url","string","Complete replacement destination URL",true),Param("allow_private","boolean","Allow the exact private-network destination; default false"),Param("preset","string","Service id; omitted recognizes URL"),
            Param("headers","object","Complete replacement HTTP headers"),Param("extra","object","Complete replacement provider fields, e.g. chat_id/user/token")),
        example="POST /v1/connections/configure {\"name\":\"Automation\",\"url\":\"https://example.com/webhook\"}",returns="connection metadata; no URL, headers or signing secrets")
    val REMOVE = EndpointSpec("DELETE", "/connections/{id}", "connections", "Revoke a connection and cancel queued sends; requires configuration authority",
        example="DELETE /v1/connections/connection-id",returns="revoked:true")
    val ROTATE = EndpointSpec("POST", "/connections/{id}/rotate", "connections", "Rotate a connection signing key; configuration authority required; the generated key remains owner-only",
        idempotent=true,example="POST /v1/connections/connection-id/rotate",returns="connection metadata only; owner copies the signing key from Connections")
    val ALL = listOf(LIST, SEND, CONFIGURE, REMOVE, ROTATE)

    /** Platform codes as the agent API's catalogued ones. */
    fun mapped(e: cc.bridgit.app.api.ApiError): cc.bridgit.app.api.ApiError = when (e.code) {
        "connection_not_found", "photo_not_found" -> ErrorCatalog.error("not_found", e.message ?: "Not found.")
        "photo_not_supported", "text_required", "invalid_send", "invalid_photo", "photo_too_large" -> ErrorCatalog.error("invalid_parameter", e.message ?: "Not a valid send.")
        "queue_full" -> ErrorCatalog.error("rate_limited", e.message ?: "Too many sends waiting.", mapOf("retry_after_s" to 60))
        "send_failed", "destination_disabled" -> ErrorCatalog.error("upstream_failed", e.message ?: "The connection didn't take the send.", e.extra + mapOf("service" to "connection"))
        else -> e
    }
}

class ConnectionsModule(app: BridgeApp) : Capability(app) {
    override val id = "connections"
    override val titleRes = R.string.cap_connections
    override val descriptionRes = R.string.cap_connections_desc
    override val group = "apps"
    override val endpoints = ConnectionsApi.ALL

    override fun Route.install(api: ApiServer) = with(api) {
        endpoint(ConnectionsApi.LIST) {
            Reply.Json(JSONObject().put("connections", JSONArray(app.platform.delivery.connections())))
        }
        endpoint(ConnectionsApi.CONFIGURE) {
            cc.bridgit.app.capability.SettingsConfigurationApi.requireWriter(app,agent?.id,agent?.keyHash)
            app.platform.authorize(agent!!.id,"owner.configuration")
            body.only("id","name","url","allow_private","preset","headers","extra")
            val name=body.text("name",80)
            val url=body.text("url",2048)
            if(body.has("allow_private") && body.opt("allow_private") !is Boolean) throw ErrorCatalog.error("invalid_parameter","allow_private must be boolean")
            fun optionalText(key:String):String? = if(body.has(key)) body.opt(key) as? String ?: throw ErrorCatalog.error("invalid_parameter","$key must be text") else null
            fun optionalObject(key:String):JSONObject? = if(body.has(key)) body.optJSONObject(key) ?: throw ErrorCatalog.error("invalid_parameter","$key must be an object") else null
            val result=app.platform.delivery.destination(name,url,body.optBoolean("allow_private"),optionalText("preset"),optionalObject("headers"),optionalObject("extra"),optionalText("id"))
            app.log.system("audit","setting","Connection configuration changed")
            Reply.Json(Connections.public(result))
        }
        endpoint(ConnectionsApi.REMOVE) {
            cc.bridgit.app.capability.SettingsConfigurationApi.requireWriter(app,agent?.id,agent?.keyHash)
            app.platform.authorize(agent!!.id,"owner.configuration")
            app.platform.delivery.revoke(path("id") ?: "")
            app.log.system("audit","setting","Connection revoked")
            Reply.Json(JSONObject().put("revoked",true))
        }
        endpoint(ConnectionsApi.ROTATE) {
            cc.bridgit.app.capability.SettingsConfigurationApi.requireWriter(app,agent?.id,agent?.keyHash)
            app.platform.authorize(agent!!.id,"owner.configuration")
            val result=app.platform.delivery.rotate(path("id") ?: "")
            app.log.system("audit","setting","Connection signing key rotated")
            Reply.Json(Connections.public(result))
        }
        endpoint(ConnectionsApi.SEND) {
            val a = agent!!.id
            val to = body.opt("to") as? String ?: throw ErrorCatalog.error("invalid_parameter", "to must be a connection id from GET /v1/connections.")
            val data = when (val d = body.opt("data")) { null, JSONObject.NULL -> JSONObject(); is JSONObject -> d; else -> throw ErrorCatalog.error("invalid_parameter", "data must be an object.") }
            for (k in data.keys()) when (val v = data.opt(k)) {
                JSONObject.NULL, is Boolean, is Number -> Unit
                is String -> if (v.length > 4096) throw ErrorCatalog.error("invalid_parameter", "data.$k is longer than 4096 characters.")
                else -> throw ErrorCatalog.error("invalid_parameter", "data.$k must be text, a number, true/false or null.")
            }
            val text = (body.opt("text") as? String).orEmpty()
            if (text.length > Connections.MAX_TEXT) throw ErrorCatalog.error("invalid_parameter", "text is longer than ${Connections.MAX_TEXT} characters.")
            val photo = (body.opt("photo") as? String)?.takeIf { it.isNotEmpty() }?.let { ref ->
                if (!Regex("^kept:[a-z0-9]{1,40}$").matches(ref)) throw ErrorCatalog.error("invalid_parameter", "photo must be a kept photo of your applet (kept:…).")
                val file = cc.bridgit.app.applets.AppletTableStore.of(app).photoFile(a, ref) ?: throw ErrorCatalog.error("not_found", "No kept photo $ref.")
                WorkflowEngine.sendJpeg(file.readBytes())
            }
            val result = try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.platform.delivery.send(a, to, data, text, photo, body.optBoolean("wait_reply")) }
            } catch (e: cc.bridgit.app.api.ApiError) { throw ConnectionsApi.mapped(e) }
            this.data = JSONObject().put("to", to).put("queued", result.optBoolean("queued")).put("status", result.optInt("status"))
            Reply.Json(result)
        }
    }
}
