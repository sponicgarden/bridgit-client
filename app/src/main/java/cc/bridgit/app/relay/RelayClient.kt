package cc.bridgit.app.relay

import cc.bridgit.app.outbound.Categories
import cc.bridgit.app.outbound.Outbound
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Semaphore
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BridgeService
import cc.bridgit.app.api.ApiError
import cc.bridgit.app.api.ApiServer
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** One outbound TLS socket; independent of the Tailscale listener. No wake locks or cloud speech. */
class RelayClient(private val service:BridgeService) {
    private val app=service.application as BridgeApp
    val store=RelayStore(app)
    private val http=OkHttpClient.Builder().addInterceptor(cc.bridgit.app.outbound.OutboundInterceptor).followRedirects(false).followSslRedirects(false).connectTimeout(15,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS).callTimeout(20,TimeUnit.SECONDS).build()
    private val api=ApiServer(service,emptyList(),app.settings.port).apply { prepareRelay() }
    private val dispatcher=RelayDispatcher(app,api)
    private val active=ConcurrentHashMap<String,Job>()
    private val operations=ConcurrentHashMap<String,Pair<String,String>>()
    @Volatile var status="Off";private set
    @Volatile private var socket:WebSocket?=null
    private val wakeups=Channel<Unit>(Channel.CONFLATED)
    @Volatile private var wokenAt=0L
    @Volatile private var pushSent:String?=null
    /** FCM wake-up or new push token: reconnect now, or prove the current socket is alive and resend the token. */
    fun wake(){wokenAt=System.currentTimeMillis();pushSent=null;socket?.send("{\"type\":\"ping\"}");wakeups.trySend(Unit)}
    fun disable(){store.enabled=false;socket?.send("{\"type\":\"off\"}");socket?.close(1000,"owner_disabled");active.values.forEach { it.cancel() };store.agents().forEach { app.platform.invalidate(it.getString("id")) };status="Off"}
    suspend fun enroll(token:String)=withContext(Dispatchers.IO){
        require(store.base.endsWith("/d/${store.device}")) { "Relay URL must match this device id" }
        val data=store.enrollment().put("token",token)
        if(token.isEmpty())post("/challenge",store.challengeProof()) else post("/enroll",data);store.enabled=true
    }
    private fun post(path:String,data:JSONObject):JSONObject {
        http.newCall(Request.Builder().url(store.base+path).post(data.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { r ->
            if(!r.isSuccessful)error("Relay refused request (${r.code})")
            val source=r.body?.source() ?: error("Empty relay response");if(source.request(65537))error("Relay response exceeds limit")
            return JSONObject(source.readUtf8())
        }
    }
    suspend fun run() {
        var backoff=1000L
        try { while(currentCoroutineContext().isActive){
            if(!store.enabled||store.base.isEmpty()){status="Off";delay(1000);continue}
            try { connected();backoff=1000 }catch(e:CancellationException){throw e}catch(_:Exception){status="Disconnected; retrying"}
            backoff=if(withTimeoutOrNull(backoff+kotlin.random.Random.nextLong(500)){wakeups.receive()}!=null)1000 else (backoff*2).coerceAtMost(60000)
        } } finally {socket?.close(1000,"listener_stopped");http.dispatcher.executorService.shutdown();http.connectionPool.evictAll()}
    }
    private suspend fun connected()=coroutineScope {
        status="Connecting"
        val c=withContext(Dispatchers.IO){post("/challenge",store.challengeProof())}
        val messages=Channel<String>(16);val ended=CompletableDeferred<Unit>();val opened=CompletableDeferred<Unit>()
        val request=Request.Builder().url(store.base.replaceFirst("https://","wss://")+"/socket").header("X-Relay-Nonce",c.getString("nonce")).header("X-Relay-Signature",store.sign(c.getString("nonce"),c.getLong("until"))).build()
        val ws=http.newWebSocket(request,object:WebSocketListener(){
            override fun onOpen(webSocket:WebSocket,response:Response){opened.complete(Unit)}
            override fun onMessage(webSocket:WebSocket,text:String){if(text.length>3*1024*1024||!messages.trySend(text).isSuccess){webSocket.close(4002,"backpressure");ended.complete(Unit)}}
            override fun onClosed(webSocket:WebSocket,code:Int,reason:String){ended.complete(Unit)}
            override fun onClosing(webSocket:WebSocket,code:Int,reason:String){webSocket.close(code,reason);ended.complete(Unit)}
            override fun onFailure(webSocket:WebSocket,t:Throwable,response:Response?){opened.completeExceptionally(t);ended.complete(Unit)}
        });socket=ws
        val acknowledgments=ConcurrentHashMap<String,CompletableDeferred<Unit>>()
        val slots=Semaphore(2)
        // Two jobs run at once, but their sequence numbers are checked in arrival order: the phone only accepts
        // a sequence higher than the last one, so checking a later job first would wrongly refuse the earlier one.
        // Trade-off kept on purpose: a slow decrypt holds the next job (and its slot) until it has claimed.
        var claimGate:Deferred<Unit> = CompletableDeferred(Unit);var lastPong=System.currentTimeMillis();var fingerprint="";var sweepTick=0;pushSent=null
        try {
            withTimeout(20000){opened.await()};status="Connected";service.refreshNotification()
            val heartbeat=launch {
                while(isActive){
                    if(!store.enabled) {disable();break}
                    operations.entries.removeIf { (_,value)->app.platform.store.find(value.second,value.first)?.let { it.optString("status") in setOf("completed","failed","cancelled","expired","unknown_outcome")||it.optLong("expires_at")<=System.currentTimeMillis() } ?: true }
                    val configs=store.agents();val current=JSONArray(configs).toString()
                    if(fingerprint!=current){
                        val live=configs.associateBy { it.getString("id") };operations.values.filter { (a,_) -> a !in live }.forEach { (a,_) -> app.platform.invalidate(a) }
                        active.keys.filter { key -> live.values.none { key.startsWith(it.getString("id")+":"+it.getString("generation")+":") } }.forEach { active[it]?.cancel() }
                        val msg=JSONObject().put("type","agents").put("agents",JSONArray(configs.map { o -> JSONObject(o.toString()).apply { remove("peer");remove("routes");remove("tools");put("portal",cc.bridgit.app.portal.PortalData.isPortalConfig(RelayDispatcher.strings(o,"routes"),RelayDispatcher.strings(o,"tools"))) } })).toString()
                        val okSend=ws.send(msg);frame("agents",msg,"Tell the relay which agents may reach this phone",listOf(Categories.AGENT_INFO),okSend)
                        if(!okSend)error("Socket queue full")
                        fingerprint=current
                    }
                    if(pushSent==null){val token=RelayPush.token();if(token!=null){val msg=JSONObject().put("type","push").put("token",token).toString();val okSend=ws.send(msg);frame("push",msg,"Give the relay this phone's push token so it can wake the phone",listOf(Categories.PUSH_TOKEN),okSend);if(!okSend)error("Socket queue full")};pushSent=token ?: ""}
                    // Single-use portal links nobody claimed in 24 h: the pairing goes (checked about once a minute).
                    if(++sweepTick%60==0)runCatching { store.sweepExpiredClaims() }
                    if(System.currentTimeMillis()-lastPong>75000)error("Heartbeat expired")
                    // After a wake-up the relay already thinks this socket is gone; don't wait 75 s to find out.
                    if(wokenAt>lastPong&&System.currentTimeMillis()-wokenAt>5000)error("Heartbeat expired after wake-up")
                    if(System.currentTimeMillis()-lastPong>25000)ws.send("{\"type\":\"ping\"}")
                    delay(1000)
                }
            }
            val reader=launch {
                for(text in messages){val b=JSONObject(text);when(b.optString("type")){
                    "pong" -> lastPong=System.currentTimeMillis()
                    "ack" -> acknowledgments.remove(b.getString("id")+":"+b.getInt("seq"))?.let { if(b.has("error"))it.completeExceptionally(CancellationException("Relay result refused")) else it.complete(Unit) }
                    "cancel" -> {val id=b.getString("id");active.entries.filter { it.key.endsWith(":$id") }.forEach { it.value.cancel() };operations.remove(id)?.let { (a,op)->app.platform.cancelRelayOperation(a,op) };app.platform.store.list(kind="operation",limit=1000).filter { it.optString("relay_job")==id }.forEach { app.platform.cancelRelayOperation(it.getString("agent_id"),it.getString("id")) }}
                    "job" -> {
                        // The live window is timed on this phone's clock from receipt, never against the relay's wall clock.
                        b.put("received_at",System.currentTimeMillis())
                        if(!slots.tryAcquire()){
                            ws.send(JSONObject().put("type","failure").put("id",b.getString("id")).put("error","backpressure").toString());continue
                        }
                        val key=b.getString("agent_id")+":"+b.getString("generation")+":"+b.getString("id")
                        val before=claimGate;val claimed=CompletableDeferred<Unit>();claimGate=claimed
                        val job=launch(start=CoroutineStart.LAZY){try{
                            val response=try { execute(b,before,claimed) }catch(e:CancellationException){throw e}catch(_:Exception){
                                ws.send(JSONObject().put("type","failure").put("id",b.getString("id")).put("error","invalid_job").toString());return@launch
                            }
                            val raw=response.toString();if(raw.toByteArray().size>8*1024*1024){ws.send(JSONObject().put("type","failure").put("id",b.getString("id")).put("error","result_too_large").toString());return@launch}
                            val chunks=raw.chunked(32768)
                            val sentAt=System.currentTimeMillis();var failure:String?="not sent"
                            try {
                                for((seq,chunk) in chunks.withIndex()){
                                    val ack=CompletableDeferred<Unit>();acknowledgments[b.getString("id")+":"+seq]=ack
                                    check(ws.send(JSONObject().put("type","result").put("id",b.getString("id")).put("seq",seq).put("data",chunk).put("final",seq==chunks.lastIndex).toString()))
                                    withTimeout(10000){ack.await()}
                                }
                                failure=null
                            } catch(e:Exception){failure=e.javaClass.simpleName;throw e} finally { recordResult(b,raw,response,sentAt,failure) }
                        }finally{claimed.complete(Unit);active.remove(key);slots.release()}}
                        active[key]=job;job.start()
                    }
                    else -> error("Unknown relay frame")
                }}
            }
            ended.await();heartbeat.cancelAndJoin();reader.cancelAndJoin()
        } finally {
            active.values.forEach { it.cancel() };active.clear();acknowledgments.values.forEach { it.cancel() };messages.close();ws.cancel();socket=null
            // A detached remote approval/control must never survive transport loss.
            store.agents().forEach { app.platform.invalidate(it.getString("id")) };operations.clear();status="Disconnected";service.refreshNotification()
        }
    }
    /** Outbound log: one frame the phone sent to the relay. */
    private fun frame(type:String,text:String,purpose:String,categories:List<String>,sent:Boolean)=Outbound.record(store.base.replaceFirst("https://","wss://")+"/socket","SEND","wss",
        sent=text.toByteArray(),sentType="application/json",status=if(sent)101 else 0,error=if(sent)null else "socket queue full",startedAt=System.currentTimeMillis(),durationMs=0,
        actor=Outbound.Actor.PHONE,hint=Outbound.Hint("Bridgit relay","Relay frame \"$type\": $purpose",categories))
    /** Outbound log: the reply to one relayed request, as it left for the agent through the relay. */
    private fun recordResult(job:JSONObject,raw:String,response:JSONObject,sentAt:Long,failure:String?){
        val config=store.agents().firstOrNull { it.optString("id")==job.optString("agent_id") }
        val agentId=job.optString("agent_id");val name=config?.optString("name")?.takeIf { it.isNotEmpty() } ?: app.agents.get(agentId)?.name ?: agentId
        val sealed=response.has("ciphertext")||response.has("enc")||config?.optString("mode")=="sealed"
        Outbound.record(store.base.replaceFirst("https://","wss://")+"/socket","SEND","wss",sent=if(sealed)null else raw.toByteArray(),sentBytes=raw.toByteArray().size.toLong(),
            sentType="application/json",status=if(failure==null)101 else 0,error=failure,startedAt=sentAt,
            actor=Outbound.Actor.agent(agentId,name),hint=Outbound.Hint("Bridgit relay",if(sealed)"Relayed reply to the agent (end-to-end encrypted; the relay can't read it)" else "Relayed reply to the agent (the relay can read it)",
                listOf(Categories.API_RESULT)+(if(sealed)listOf(Categories.ENCRYPTED) else emptyList())))
    }
    private suspend fun execute(job:JSONObject,before:Deferred<Unit>?=null,claimed:CompletableDeferred<Unit>?=null):JSONObject {
        before?.await()
        val config=store.agents().firstOrNull { it.getString("id")==job.getString("agent_id")&&it.getString("generation")==job.getString("generation")&&it.getString("mode")==job.getString("mode") } ?: throw ApiError(403,"agent_revoked","No such relay grant")
        require(job.getString("device_id")==store.device)
        var aad:ByteArray?=null;var requestId="";var seq=0L;val sealed=config.getString("mode")=="sealed"
        var deadline=job.getLong("deadline");val receivedAt=job.optLong("received_at",System.currentTimeMillis())
        val ageMs=if(job.has("age_ms"))job.getLong("age_ms") else null
        var created=RelayDispatcher.liveStart(receivedAt,job.getLong("created_at"),ageMs)
        val request=if(sealed){
            val envelope=job.getJSONObject("payload");requestId=envelope.getString("request_id");require(Regex("[A-Za-z0-9_-]{8,128}").matches(requestId));seq=envelope.getLong("seq");deadline=envelope.getLong("deadline")
            require(deadline==job.getLong("deadline"))
            aad=Sealed.aad(store.device,config.getString("id"),config.getString("generation"),requestId,deadline,seq,"request")
            val plaintext=JSONObject(String(Sealed.open(store.hpke(),config.getString("peer"),envelope,aad),Charsets.UTF_8));created=RelayDispatcher.liveStart(receivedAt,job.getLong("created_at"),ageMs,plaintext.getLong("created_at"))
            if(!store.claim(config.getString("id"),config.getString("generation"),seq,requestId))throw ApiError(409,"replay_rejected","Sequence already consumed")
            plaintext.getJSONObject("request")
        }else{
            if(!store.claim(config.getString("id"),config.getString("generation"),job.getLong("seq")))throw ApiError(409,"replay_rejected","Job already consumed")
            job.getJSONObject("payload")
        }
        claimed?.complete(Unit)
        val result=try {
            if(deadline-System.currentTimeMillis() !in 1..1800000)throw ApiError(409,"deadline_expired","Deadline invalid or expired")
            val result=withTimeout((deadline-System.currentTimeMillis()).coerceAtLeast(1)){dispatcher.dispatch(config,request,deadline,created,job.getString("id"),job.optJSONObject("caller"))}
            val operation=result.optJSONObject("body") ?: result.optJSONObject("mcp")?.optJSONObject("result")?.optJSONArray("content")?.optJSONObject(0)?.optString("text")?.let { runCatching { JSONObject(it) }.getOrNull() }
            operation?.takeIf { it.has("command")&&it.has("id") }?.let { operations[job.getString("id")]=config.getString("id") to it.getString("id") }
            result
        }catch(e:CancellationException){throw e}catch(e:Exception){JSONObject().put("status",if(e is ApiError)e.http else 500).put("body",JSONObject().put("error",if(e is ApiError)e.code else "request_failed"))}
        app.audit.append(JSONObject().put("agent_id",config.getString("id")).put("endpoint","relay:${config.getString("mode")}").put("result",result.optInt("status")).put("request_id",job.getString("id")))
        if(!sealed)return result
        return Sealed.seal(store.hpke(),config.getString("peer"),result.toString().toByteArray(),Sealed.aad(store.device,config.getString("id"),config.getString("generation"),requestId,deadline,seq,"response"))
            .put("request_id",requestId).put("deadline",deadline).put("seq",seq)
    }
}
