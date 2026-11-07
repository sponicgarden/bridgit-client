package cc.bridgit.app.platform

import cc.bridgit.app.outbound.Categories
import cc.bridgit.app.outbound.Outbound
import kotlinx.coroutines.*
import cc.bridgit.app.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.*

/** Owner-selected destinations only, durable retries and recipient-scoped acknowledgements. */
class DeliveryManager(private val runtime:AgentPlatform) {
    private val sockets=java.util.concurrent.ConcurrentHashMap<String,Socket>()
    fun stopAgent(agent:String) {
        runtime.store.list(agent,"outbox",1000).filter { it.optString("status")=="queued" }.forEach { row ->
            runtime.store.save(agent,"outbox",row.put("status","cancelled")); runCatching { sockets.remove(row.getString("id"))?.close() }
        }
        runtime.store.list(agent,"alert").filter { it.optString("status") in setOf("queued","delivered","escalated") }.forEach { cancel(agent,it.getString("id")) }
    }
    private val sending=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    /**
     * Approves a connection (docs/applet-reach.md A2): a name, a URL and the preset that says how to talk to it
     * ([preset] null: recognised from the URL). Each gets the old hex secret (alerts and callbacks still sign with
     * it) and a Standard Webhooks `whsec_` secret for sends. At most [Connections.MAX].
     */
    fun destination(name:String,url:String,allowPrivate:Boolean,preset:String?=null,headers:JSONObject?=null,extra:JSONObject?=null,id:String?=null):JSONObject {
        val p=preset ?: Connections.recognise(url)
        if(p==Connections.LEGACY || p !in Connections.PRESETS) throw ApiError(422,"invalid_connection","Unknown service type $p")
        if(name.isBlank() || name.length>80) throw ApiError(422,"invalid_connection","Give the connection a name of 1-80 characters")
        Connections.checkUrl(url,allowPrivate,p)
        val checkedHeaders=if(Connections.PRESETS.getValue(p).headers) Connections.checkHeaders(headers) else { if(headers!=null && headers.length()>0) throw ApiError(422,"invalid_connection","${Connections.PRESETS.getValue(p).label} connections take no extra headers"); JSONObject() }
        val checkedExtra=Connections.checkExtra(p,extra)
        synchronized(runtime.store) {
            val previous = id?.let { runtime.store.require(it,"owner","destination") }
            if(runtime.store.list("owner","destination",1000).count { it.optBoolean("enabled") && it.optString("id") != id }>=Connections.MAX) throw ApiError(429,"destination_limit","At most ${Connections.MAX} connections: revoke one first")
            // Adding a connection is the owner's act, so flows may now use the send step (the scope still needs a grant per agent).
            if(!Capabilities.enabled(runtime.app,"connections.send")) runtime.store.setting("enabled:connections.send","true",true)
            val record = previous ?: JSONObject().put("secret",random()).put("whsec",Connections.newSecret())
            return runtime.store.save("owner","destination",record.put("name",name.trim()).put("url",url.trim()).put("allow_private",allowPrivate).put("enabled",true)
                .put("preset",p).put("headers",checkedHeaders).put("extra",checkedExtra))
        }
    }
    /** A new signing secret; the old one keeps signing too for a day, so receivers can switch over. */
    fun rotate(id:String):JSONObject = synchronized(runtime.store) {
        val d=runtime.store.require(id,"owner","destination")
        if(d.optString("whsec").isEmpty()) throw ApiError(409,"no_signing_secret","This older destination signs with its Bridgit secret, which can't be rotated")
        runtime.store.save("owner","destination",d.put("whsec_previous",d.getString("whsec")).put("whsec_previous_until",System.currentTimeMillis()+Connections.ROTATION_MS).put("whsec",Connections.newSecret()))
    }
    /** The connections agents may name, as they see them (no URL or secrets). */
    fun connections():List<JSONObject> = runtime.store.list("owner","destination",1000).filter { it.optBoolean("enabled") }.sortedBy { it.optLong("created_at") }.map(Connections::public)
    fun revoke(id:String) {
        val d=runtime.store.require(id,"owner","destination"); runtime.store.save("owner","destination",d.put("enabled",false))
        runtime.store.list(kind="outbox",limit=1000).filter { it.optString("destination_id")==id }.forEach { runtime.store.save(it.getString("agent_id"),"outbox",it.put("status","cancelled")) }
    }
    fun validateDestinations(ids:List<String>) {
        if(ids.isEmpty() || ids.size>3) throw ApiError(422,"invalid_destination","Choose one to three approved destinations")
        ids.forEach {
            val d=runtime.store.require(it,"owner","destination")
            if(d.optBoolean("enabled")!=true) throw ApiError(403,"destination_disabled","Destination was disabled by the owner")
            // Help alerts and event callbacks keep their own signed https delivery on port 443.
            val u=URI(d.getString("url")); if(u.scheme!="https" || u.port !in listOf(-1,443)) throw ApiError(422,"invalid_destination","${d.optString("name")} isn't an https address on port 443, so it can take sends but not alerts or event callbacks")
        }
    }
    fun trigger(agent:String,args:JSONObject,fromWorkflow:Boolean=false):JSONObject {
        args.only("destinations","text")
        runtime.authorize(agent,"alerts"); val ids=args.strings("destinations",3); validateDestinations(ids)
        val active=runtime.store.list(agent,"alert").firstOrNull { it.optString("status") in listOf("queued","delivered","escalated") && System.currentTimeMillis()-it.optLong("created_at")<600_000 }
        if(active!=null) return public(active) // Repeated help triggers join the active incident.
        val alert=runtime.store.save(agent,"alert",JSONObject().put("status","queued").put("from_workflow",fromWorkflow).put("destinations",JSONArray(ids)).put("text",args.text("text",1024)).put("ack_secret",random()).put("next_contact",0).put("next_at",System.currentTimeMillis()+5000))
        runtime.store.emit(agent,"alert.queued",alert.getString("id")); runtime.changed(); return public(alert)
    }
    fun cancel(agent:String,id:String):JSONObject {
        val r=runtime.store.require(id,agent,"alert"); runtime.store.save(agent,"alert",r.put("status","cancelled"))
        runtime.store.list(agent,"outbox",1000).filter { it.optString("alert_id")==id }.forEach { runtime.store.save(agent,"outbox",it.put("status","cancelled")) }
        runtime.store.emit(agent,"alert.cancelled",id); return public(r)
    }
    private fun public(record:JSONObject)=PlatformStore.copy(record).apply { remove("ack_secret"); remove("text"); remove("from_workflow") }
    fun list(agent:String)=JSONObject().put("alerts",JSONArray(runtime.store.list(agent,"alert").map(::public)))
    fun ack(id:String,destinationId:String,token:String):JSONObject {
        val record=runtime.store.find(id,kind="alert") ?: throw ApiError(404,"alert_not_found","Unknown alert")
        if(destinationId !in record.strings("destinations")) throw ApiError(401,"unauthorized","Invalid recipient")
        val expected=hmac(record.getString("ack_secret"),destinationId)
        if(!MessageDigest.isEqual(expected.toByteArray(),token.toByteArray())) throw ApiError(401,"unauthorized","Invalid recipient credential")
        if(record.optString("status")=="acknowledged") return public(record)
        if(record.optString("status") in setOf("cancelled","expired")) throw ApiError(409,"alert_closed","Alert is no longer active")
        val destination=runtime.store.require(destinationId,"owner","destination")
        if(!destination.optBoolean("enabled")) throw ApiError(403,"destination_disabled","Recipient was revoked")
        val saved=runtime.store.save(record.getString("agent_id"),"alert",record.put("status","acknowledged").put("acknowledged_by",destination.getString("name")).put("acknowledged_at",System.currentTimeMillis()))
        runtime.store.list(record.getString("agent_id"),"outbox",1000).filter { it.optString("alert_id")==id && it.optString("status")=="queued" }.forEach { runtime.store.save(it.getString("agent_id"),"outbox",it.put("status","cancelled")) }
        runtime.store.emit(record.getString("agent_id"),"alert.acknowledged",id); runtime.changed(); return public(saved)
    }
    fun subscribe(agent:String,destinationId:String) { validateDestinations(listOf(destinationId)); runtime.store.setting("callback:$agent",destinationId,true); runtime.store.setting("callback_cursor:$agent",runtime.store.latestCursor().toString(),true) }
    fun tick() {
        val now=System.currentTimeMillis()
        watchNetwork()
        if(now-lastSweep>600_000L) { lastSweep=now; runCatching { sweepPhotos() } }
        runtime.store.list(kind="alert").filter { it.optString("status") in setOf("queued","delivered","escalated") }.forEach { alert ->
            val agent=alert.getString("agent_id"); val id=alert.getString("id")
            if(!runtime.hasGrant(agent,"alerts") || !Capabilities.enabled(runtime.app,"alerts")) { cancel(agent,id); return@forEach }
            if(now-alert.getLong("created_at")>600_000) { runtime.store.save(agent,"alert",alert.put("status","expired")); runtime.store.emit(agent,"alert.expired",id); return@forEach }
            val ids=alert.strings("destinations"); val next=alert.optInt("next_contact")
            if(next<ids.size && now>=alert.optLong("next_at")) {
                val dest=ids[next]
                val payload=JSONObject().put("type","help.requested").put("alert_id",id).put("device_id",runtime.store.deviceId).put("text",alert.getString("text")).put("created_at",alert.getLong("created_at")).put("recipient_id",dest)
                    .put("ack_path","/v2/alerts/$id/ack").put("ack_credential",hmac(alert.getString("ack_secret"),dest))
                if(enqueue(agent,dest,payload,id,alert.optBoolean("from_workflow"))) runtime.store.save(agent,"alert",alert.put("next_contact",next+1).put("status",if(next==0) "queued" else "escalated").put("next_at",alert.getLong("created_at")+if(next==0) 30_000 else 90_000))
            }
        }
        runtime.app.policy.agents().forEach { agent ->
            val destinationId=runtime.store.setting("callback:${agent.id}")
            if(destinationId.isNotEmpty() && runtime.hasGrant(agent.id,"events.read") && Capabilities.enabled(runtime.app,"events.read")) {
                queueCallbacks(agent.id,destinationId)
            }
        }
        runtime.store.list(kind="outbox",limit=1000).filter { it.optString("status")=="queued" && it.optLong("next_at")<=now }.take(4).forEach { row ->
            val id=row.getString("id"); if(sending.add(id)) runtime.scope.launch { try { deliver(row) } finally { sending.remove(id) } }
        }
    }
    internal fun queueCallbacks(agent:String,destination:String) {
        val after=runtime.store.setting("callback_cursor:$agent","0").toLongOrNull() ?: 0
        val events=runtime.applicationEvents.events(agent,after); val rows=events.getJSONArray("events")
        var cursor=after
        for(i in 0 until rows.length()) {
            val payload=rows.getJSONObject(i)
            if(i==0 && events.optBoolean("gap")) payload.put("gap",true)
            if(!enqueue(agent,destination,payload,"")) break
            cursor=payload.getLong("cursor")
        }
        if(rows.length()==0) {
            if(!events.optBoolean("gap") || enqueue(agent,destination,JSONObject().put("type","events.gap").put("gap",true).put("cursor",events.getLong("cursor")),"")) cursor=events.getLong("cursor")
        }
        runtime.store.setting("callback_cursor:$agent",cursor.toString(),true)
    }
    private fun enqueue(agent:String,destination:String,payload:JSONObject,alert:String,fromWorkflow:Boolean=false):Boolean = synchronized(runtime.store) {
        // One delivery per incident and recipient, even if the process stopped before next_contact was saved.
        val id = if (fromWorkflow) workflowAlertDeliveryId(alert, destination) else null
        if (id != null && runtime.store.find(id,agent,"outbox") != null) return true
        if(runtime.store.list(agent,"outbox",1000).count { it.optString("status")=="queued" }>=100) return false
        runtime.store.save(agent,"outbox",JSONObject().put("id",id).put("from_workflow",fromWorkflow).put("destination_id",destination).put("payload",payload).put("alert_id",alert).put("status","queued").put("attempts",0).put("next_at",System.currentTimeMillis()))
        return true
    }
    /** Once the socket may have written bytes, an interrupted workflow delivery must never be replayed.
     * The stable id on signed webhooks helps cooperating receivers; other receivers may ignore it. */
    private fun beforeWorkflowWrite(row:JSONObject) {
        if (!row.optBoolean("from_workflow")) return
        synchronized(runtime.store) {
            val agent=row.getString("agent_id")
            val saved=runtime.store.require(row.getString("id"),agent,"outbox")
            if (saved.optString("status") !in setOf("queued","sending"))
                throw ApiError(409,"delivery_cancelled","Delivery is no longer pending")
            runtime.store.save(agent,"outbox",saved.put("status","unknown_outcome")
                .put("attempts",row.optInt("attempts")+1).put("error","delivery_unconfirmed"))
        }
    }

    private fun uncertain(row:JSONObject):Boolean = row.optBoolean("from_workflow") &&
        runtime.store.find(row.getString("id"),row.getString("agent_id"),"outbox")?.optString("status")=="unknown_outcome"

    private fun deliver(row:JSONObject) {
        if(row.has("send")) { deliverSend(row); return }
        val agent=row.getString("agent_id"); val id=row.getString("id"); val attempt=row.optInt("attempts")+1
        try {
            val d=runtime.store.require(row.getString("destination_id"),"owner","destination")
            if(!d.optBoolean("enabled")) throw ApiError(403,"destination_disabled","Destination disabled")
            if(row.optString("alert_id").isNotEmpty() && runtime.store.require(row.getString("alert_id"),agent,"alert").optString("status") in setOf("acknowledged","cancelled","expired")) { runtime.store.save(agent,"outbox",row.put("status","cancelled")); return }
            if(!runtime.applicationEvents.visible(agent,row.getJSONObject("payload"))) { runtime.store.save(agent,"outbox",row.put("status","cancelled")); return }
            val payload=PlatformStore.copy(row.getJSONObject("payload")).put("event_id",id)
            val code=Outbound.call(d.getString("url"),"POST",actor=Outbound.Actor.agent(agent,runtime.app.agents.get(agent)?.name ?: agent),
                hint=Outbound.Hint("Webhook destination ${d.optString("name")}","Deliver an alert or callback to an owner-approved destination",listOf(Categories.ALERT_TEXT))) { meter ->
                meter.sent=payload.toString().toByteArray(); meter.sentType="application/json"
                meter.headers["Content-Type"]="application/json"; meter.headers["Idempotency-Key"]=id; meter.headers["X-Bridgit-Signature"]="hmac"
                postPinned(d,payload.toString(),id,agent).also { meter.status=it }
            }
            if(code !in 200..299) throw ApiError(503,"delivery_failed","Recipient returned $code")
            runtime.store.save(agent,"outbox",row.put("status","accepted").put("attempts",attempt).put("accepted_at",System.currentTimeMillis()))
            if(row.optString("alert_id").isNotEmpty()) {
                val a=runtime.store.require(row.getString("alert_id"),agent,"alert")
                if(a.optString("status")=="queued") runtime.store.save(agent,"alert",a.put("status","delivered"))
            }
        } catch(e:Exception) {
            if(runtime.store.find(id,agent)?.optString("status")=="cancelled" || uncertain(row)) return
            val retry=when(attempt) { 1 -> 2000L; 2 -> 10_000L; 3 -> 30_000L; else -> 300_000L }
            runtime.store.save(agent,"outbox",row.put("attempts",attempt).put("status",if(attempt>=6) "failed" else "queued").put("next_at",System.currentTimeMillis()+retry).put("error",if(e is ApiError) e.code else "transport_failed"))
        } finally { sockets.remove(id)?.let { runCatching { it.close() } } }
    }
    // ---- send (docs/applet-reach.md A3-A6) -------------------------------------------------------------------

    /** Folder for photos of queued sends: a photo can be 1 MB, too big for a store row. Deleted when the send ends. */
    private val photoDir by lazy { java.io.File(runtime.app.filesDir,"send_photos").apply { mkdirs() } }
    private fun photoFile(id:String)=java.io.File(photoDir,"$id.jpg")
    private fun finishPhoto(id:String) { runCatching { photoFile(id).delete() } }

    /**
     * One `send` from a flow, `do.step` or POST /v1/connections/send. [data] is the step's own data; Bridgit adds
     * applet, flow, run_id, sent_at and phone. With [waitReply] the message goes now and waits up to 15 s for the
     * reply; otherwise it's queued and retried in the background like any delivery, keeping its sent_at.
     */
    fun send(agent:String,to:String,data:JSONObject,text:String,photo:ByteArray?,waitReply:Boolean,flow:String="",runId:String=""):JSONObject {
        val conn=runtime.store.find(to,"owner","destination")?.takeIf { it.optBoolean("enabled") } ?: throw ApiError(404,"connection_not_found","No enabled connection $to: the owner adds connections in Applets › Connections")
        val preset=Connections.preset(conn)
        if(photo!=null) Connections.photoProblem(preset)?.let { throw ApiError(422,"photo_not_supported",it) }
        if(preset.chat && text.isBlank() && data.length()==0) throw ApiError(422,"text_required","${preset.label} shows text: give the send a text")
        if(data.length()>Connections.MAX_DATA_KEYS) throw ApiError(422,"invalid_send","data has more than ${Connections.MAX_DATA_KEYS} keys")
        val now=System.currentTimeMillis()
        val instance=runtime.app.applets.forAgent(agent)
        val full=JSONObject(); for(k in data.keys()) full.put(k,data.opt(k))
        full.put("applet",instance?.optString("name")?.ifEmpty { null } ?: runtime.app.agents.get(agent)?.name ?: if(agent=="owner") "Bridgit" else agent).put("flow",flow).put("run_id",runId)
            .put("sent_at",cc.bridgit.app.capability.isoAt(now)).put("phone",DeviceName.of(runtime.app))
        if(full.toString().toByteArray().size>Connections.MAX_DATA_BYTES+1024) throw ApiError(422,"invalid_send","data is larger than 16 KiB")
        val message=JSONObject().put("data",full).put("text",text.take(Connections.MAX_TEXT)).put("title",full.optString("applet").ifEmpty { "Bridgit" })
        val row=synchronized(runtime.store) {
            val hourAgo=now-3600_000L
            val recent=runtime.store.list(agent,"outbox",1000).count { it.has("send") && it.optString("destination_id")==to && it.optLong("created_at")>=hourAgo }
            if(recent>=Connections.PER_HOUR) throw ApiError(429,"rate_limited","At most ${Connections.PER_HOUR} sends an hour to ${conn.optString("name")} from this applet",mapOf("retry_after_s" to 300))
            if(!waitReply && runtime.store.list(agent,"outbox",1000).count { it.optString("status")=="queued" }>=100) throw ApiError(429,"queue_full","100 sends are already waiting for this applet")
            runtime.store.save(agent,"outbox",JSONObject().put("destination_id",to).put("from_workflow",runId.isNotBlank()).put("send",message).put("has_photo",photo!=null).put("status",if(waitReply) "sending" else "queued").put("attempts",0).put("next_at",now))
        }
        val id=row.getString("id")
        if(photo!=null) photoFile(id).writeBytes(photo)
        if(!waitReply) { runtime.changed(); return JSONObject().put("queued",true).put("delivery_id",id) }
        return try { deliverSend(row,wait=true) ?: throw ApiError(502,"send_failed","${conn.optString("name")} didn't answer") }
        finally { finishPhoto(id) }
    }

    /** Sends one queued (or waiting) send. Returns the step result for [wait]; queued sends retry on failure. */
    private fun deliverSend(row:JSONObject,wait:Boolean=false):JSONObject? {
        val agent=row.getString("agent_id"); val id=row.getString("id"); val attempt=row.optInt("attempts")+1
        val connId=row.getString("destination_id")
        try {
            val conn=runtime.store.find(connId,"owner","destination")?.takeIf { it.optBoolean("enabled") } ?: throw ApiError(403,"destination_disabled","The owner revoked this connection")
            if(!runtime.app.settings.listenerEnabled) throw ApiError(403,"listener_paused","Bridgit is paused")
            if(!wait && runtime.store.find(id,agent)?.optString("status")!="queued") return null
            val m=row.getJSONObject("send")
            val photo=if(row.optBoolean("has_photo")) photoFile(id).takeIf { it.exists() }?.readBytes() ?: throw ApiError(410,"photo_gone","The photo of this send is gone") else null
            val now=System.currentTimeMillis()
            val req=Connections.build(conn,Connections.Message(id,m.getJSONObject("data"),m.optString("text"),photo,m.optString("title")),wait,now)
            val policy=if(conn.optBoolean("allow_private")) cc.bridgit.app.core.SafeHttp.Policy.OWNER_NETWORK else cc.bridgit.app.core.SafeHttp.Policy.PUBLIC
            val name=runtime.app.agents.get(agent)?.name ?: agent
            val result=Outbound.on(Outbound.Actor.agent(agent,name),Outbound.Hint("Connection ${conn.optString("name")} (${Connections.preset(conn).label})","Send from a flow to an owner-approved connection",if(photo!=null) listOf(Categories.IMAGE) else emptyList())) {
                cc.bridgit.app.core.SafeHttp.request(req.url,policy,req.method,req.headers,req.body,if(wait) Connections.REPLY_WAIT_MS else 30_000L,Connections.MAX_REPLY_BYTES.toLong()*4,Connections.logUrl(conn)) { beforeWorkflowWrite(row) }
            }
            if(result.status !in 200..299) throw ApiError(502,"send_failed","${conn.optString("name")} answered HTTP ${result.status}",mapOf("upstream_status" to result.status))
            runtime.store.save(agent,"outbox",row.put("status","accepted").put("attempts",attempt).put("accepted_at",System.currentTimeMillis()).put("http_status",result.status).put("sent_bytes",req.body.size))
            noteResult(connId,true)
            finishPhoto(id)
            return Connections.reply(result.status,result.bytes,result.header("content-type")).put("delivery_id",id)
        } catch(e:Exception) {
            val code0=(e as? ApiError)?.code
            // A paused Bridgit or a revoked connection says nothing about the service, so it doesn't count toward "failing".
            if(code0 !in setOf("listener_paused","destination_disabled","photo_gone")) noteResult(connId,false)
            val code=when(e) { is ApiError -> e.code; is cc.bridgit.app.core.SafeHttp.Failure -> "transport_failed"; else -> "transport_failed" }
            val message=(e as? cc.bridgit.app.core.SafeHttp.Failure)?.message ?: e.message ?: code
            if(uncertain(row)) {
                finishPhoto(id)
                if(wait) throw ApiError(502,"unknown_outcome","The receiver may have acted. Check the destination before sending again.")
                return null
            }
            if(wait) {
                runtime.store.save(agent,"outbox",row.put("status","failed").put("attempts",attempt).put("error",code))
                throw if(e is ApiError) e else ApiError(502,"send_failed","The send didn't arrive: $message")
            }
            if(runtime.store.find(id,agent)?.optString("status")=="cancelled") { finishPhoto(id); return null }
            val terminal=attempt>=6 || code in setOf("destination_disabled","photo_gone")
            val retry=when(attempt) { 1 -> 2000L; 2 -> 10_000L; 3 -> 30_000L; else -> 300_000L }
            runtime.store.save(agent,"outbox",row.put("attempts",attempt).put("status",if(terminal) "failed" else "queued").put("next_at",System.currentTimeMillis()+retry).put("error",code))
            if(terminal) finishPhoto(id)
            return null
        }
    }

    /** A connection that fails 6 times in a row is marked failing; one success clears it. */
    private fun noteResult(id:String,ok:Boolean) = synchronized(runtime.store) {
        val d=runtime.store.find(id,"owner","destination") ?: return@synchronized
        val streak=if(ok) 0 else d.optInt("fail_streak")+1
        if(d.optInt("fail_streak")==streak && d.optBoolean("failing")==(streak>=6)) return@synchronized
        runtime.store.save("owner","destination",d.put("fail_streak",streak).put("failing",streak>=6).apply { if(ok) put("last_ok_at",System.currentTimeMillis()) else put("last_fail_at",System.currentTimeMillis()) })
    }

    /** The network came back (A6): every queued delivery is due now instead of at its next retry time. */
    fun flush() {
        val now=System.currentTimeMillis()
        synchronized(runtime.store) { runtime.store.list(kind="outbox",limit=1000).filter { it.optString("status")=="queued" && it.optLong("next_at")>now }.forEach { runtime.store.save(it.getString("agent_id"),"outbox",it.put("next_at",now)) } }
        tick()
    }
    @Volatile private var networkWatch=false
    @Volatile private var lastFlush=0L
    /**
     * Listens for a real network (Wi-Fi, mobile data) coming up or passing its internet check, once per process (A6).
     * Not the default network: under Tailscale that is the VPN, which stays up while the Wi-Fi under it comes and goes.
     * The flush waits a moment for DNS and the VPN to settle, then tries again once.
     */
    private fun watchNetwork() {
        if(networkWatch) return; networkWatch=true
        runCatching {
            val cm=runtime.app.getSystemService(android.net.ConnectivityManager::class.java)
            val request=android.net.NetworkRequest.Builder().addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
            fun back() {
                val now=System.currentTimeMillis(); if(now-lastFlush<5000) return; lastFlush=now
                runtime.scope.launch { delay(3000); runCatching { flush() }; delay(12_000); runCatching { flush() } }
            }
            // Only changes count: a network that just came up, or just passed its internet check. Signal-strength updates don't.
            val validated=java.util.concurrent.ConcurrentHashMap.newKeySet<android.net.Network>()
            cm.registerNetworkCallback(request,object:android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network:android.net.Network) = back()
                override fun onCapabilitiesChanged(network:android.net.Network,caps:android.net.NetworkCapabilities) {
                    if(caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)) { if(validated.add(network)) back() } else validated.remove(network)
                }
                override fun onLost(network:android.net.Network) { validated.remove(network) }
            })
        }
    }
    /** Photos of sends that ended (cancelled, trimmed) are removed. */
    private fun sweepPhotos() {
        val files=photoDir.listFiles() ?: return
        if(files.isEmpty()) return
        val live=runtime.store.list(kind="outbox",limit=1000).filter { it.optString("status") in setOf("queued","sending") }.map { it.getString("id") }.toSet()
        files.filter { it.nameWithoutExtension !in live && System.currentTimeMillis()-it.lastModified()>60_000 }.forEach { it.delete() }
    }
    private var lastSweep=0L

    /** DNS is validated once and the TLS socket is pinned to that exact address. No redirects. */
    private fun postPinned(destination:JSONObject,body:String,id:String,agent:String):Int {
        val uri=URI(destination.getString("url")); val host=uri.host
        val addresses=InetAddress.getAllByName(host)
        val address=addresses.firstOrNull { publicAddress(it) || (destination.optBoolean("allow_private") && privateAddress(it)) } ?: throw ApiError(403,"destination_address_blocked","Destination is not a permitted public address")
        if(!destination.optBoolean("allow_private") && addresses.any { !publicAddress(it) }) throw ApiError(403,"destination_address_blocked","Mixed public/private destination addresses")
        val raw=Socket(); sockets[id]=raw; if(!runtime.applicationEvents.visible(agent,runtime.store.require(id,agent).getJSONObject("payload")) || !runtime.app.settings.listenerEnabled || runtime.store.find(id,agent)?.optString("status")!="queued") { raw.close(); throw ApiError(409,"delivery_cancelled","Delivery was cancelled") }; raw.connect(InetSocketAddress(address,443),5000); raw.soTimeout=5000
        val tls=try { (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw,host,443,true) as SSLSocket } catch(e:Exception) { raw.close(); throw e }
        tls.use { socket ->
            socket.sslParameters=socket.sslParameters.apply { endpointIdentificationAlgorithm="HTTPS"; if(!host.matches(Regex("[0-9.]+"))) serverNames=listOf(SNIHostName(host)) }
            socket.startHandshake()
            val bytes=body.toByteArray(Charsets.UTF_8); val timestamp=System.currentTimeMillis().toString(); val nonce=UUID.randomUUID().toString(); val signature=hmac(destination.getString("secret"),"$timestamp.$nonce.$id.$body")
            val path=(uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/")+(uri.rawQuery?.let { "?$it" } ?: "")
            val headers="POST $path HTTP/1.1\r\nHost: $host\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\nIdempotency-Key: $id\r\nX-Bridgit-Timestamp: $timestamp\r\nX-Bridgit-Nonce: $nonce\r\nX-Bridgit-Event: $id\r\nX-Bridgit-Signature: $signature\r\n\r\n"
            if(!runtime.applicationEvents.visible(agent,runtime.store.require(id,agent).getJSONObject("payload")) || !runtime.app.settings.listenerEnabled || !runtime.store.require(destination.getString("id"),"owner","destination").optBoolean("enabled") || runtime.store.find(id,agent)?.optString("status")!="queued") throw ApiError(409,"delivery_cancelled","Delivery was cancelled")
            beforeWorkflowWrite(runtime.store.require(id,agent,"outbox"))
            socket.outputStream.write(headers.toByteArray(Charsets.US_ASCII)); socket.outputStream.write(bytes); socket.outputStream.flush()
            val input=socket.inputStream; val status=StringBuilder()
            while(status.length<1024) { val ch=input.read(); if(ch<0 || ch==10) break; status.append(ch.toChar()) }
            return status.toString().split(' ').getOrNull(1)?.toIntOrNull() ?: throw ApiError(503,"delivery_failed","Invalid recipient response")
        }
    }
    companion object {
        fun workflowAlertDeliveryId(alert: String, destination: String): String = "flow-alert-$alert-$destination"

        private fun random()=ByteArray(32).also { SecureRandom().nextBytes(it) }.toHex()
        fun hmac(secret:String,text:String):String=Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(),"HmacSHA256")) }.doFinal(text.toByteArray()).toHex()
        fun privateAddress(address:InetAddress):Boolean {
            if(address.isLoopbackAddress || address.isLinkLocalAddress || address.isAnyLocalAddress || address.isMulticastAddress) return false
            val b=address.address
            return address.isSiteLocalAddress || (b.size==4 && (b[0].toInt() and 255)==100 && (b[1].toInt() and 255) in 64..127) || (b.size==16 && (b[0].toInt() and 0xfe)==0xfc)
        }
        fun publicAddress(address:InetAddress):Boolean {
            if(address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) return false
            val b=address.address
            if(b.size==4) {
                val first=b[0].toInt() and 255; val second=b[1].toInt() and 255; val third=b[2].toInt() and 255
                return first !in setOf(0,10,127) && first<224 && !(first==100 && second in 64..127) && !(first==172 && second in 16..31) && !(first==169 && second==254) && !(first==192 && (second==168 || (second==0 && third in setOf(0,2)))) && !(first==198 && (second in 18..19 || (second==51 && third==100))) && !(first==203 && second==0 && third==113)
            }
            return (b[0].toInt() and 0xfe)!=0xfc && !(b.take(12).all { it==0.toByte() })
        }
    }
}
