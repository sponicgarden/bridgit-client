package cc.bridgit.app.platform

import cc.bridgit.app.*
import cc.bridgit.app.core.SafeHttp
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/** Webhooks out (docs/applet-reach.md part A): connections, presets, signing, the send step and its delivery. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class SendTest {
    private val app get() = RuntimeEnvironment.getApplication() as BridgeApp
    @Before fun on() { app.settings.listenerEnabled = true; SafeHttp.allowLoopbackForTests = true }
    @After fun off() { SafeHttp.allowLoopbackForTests = false; servers.forEach { runCatching { it.close() } } }
    private fun o(vararg p: Pair<String, Any?>) = JSONObject().apply { p.forEach { (k, v) -> put(k, v) } }
    private fun a(vararg v: Any?) = JSONArray().apply { v.forEach { put(it) } }
    private fun rejects(code: String, part: String = "", block: () -> Unit) {
        try { block(); fail("Expected $code") } catch (e: ApiError) { assertEquals(e.message, code, e.code); assertTrue("'${e.message}' should mention $part", e.message!!.contains(part)) }
    }

    // ---- a tiny receiver --------------------------------------------------------------------------------------

    class Got(val requestLine: String, val headers: Map<String, String>, val body: ByteArray) { fun h(n: String) = headers[n.lowercase()] }
    private val servers = mutableListOf<ServerSocket>()
    /** A loopback server answering every request with [status] and [reply]; what it got is in the list. */
    private fun receiver(status: () -> Int = { 200 }, reply: String = "{\"state\":\"on\"}", dropReply: Boolean = false): Pair<Int, MutableList<Got>> {
        val got = CopyOnWriteArrayList<Got>()
        val ss = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1")); servers += ss
        Thread {
            while (!ss.isClosed) {
                val s = runCatching { ss.accept() }.getOrNull() ?: break
                runCatching {
                    s.use {
                        val inp = java.io.BufferedInputStream(it.getInputStream())
                        fun line(): String { val b = StringBuilder(); while (true) { val c = inp.read(); if (c < 0 || c == '\n'.code) break; if (c != '\r'.code) b.append(c.toChar()) }; return b.toString() }
                        val first = line(); val h = LinkedHashMap<String, String>()
                        while (true) { val l = line(); if (l.isEmpty()) break; h[l.substringBefore(':').trim().lowercase()] = l.substringAfter(':').trim() }
                        val n = h["content-length"]?.toInt() ?: 0
                        val body = ByteArray(n); var off = 0; while (off < n) { val r = inp.read(body, off, n - off); if (r < 0) break; off += r }
                        got += Got(first, h, body)
                        if (dropReply) return@use
                        val r = reply.toByteArray()
                        it.getOutputStream().write("HTTP/1.1 ${status()} OK\r\nContent-Type: application/json\r\nContent-Length: ${r.size}\r\nConnection: close\r\n\r\n".toByteArray() + r)
                        it.getOutputStream().flush()
                    }
                }
            }
        }.apply { isDaemon = true }.start()
        return ss.localPort to got
    }

    /** A connection to a loopback receiver, saved as the owner would approve it (approval refuses loopback). */
    private fun connection(port: Int, preset: String = "json", path: String = "/hook", extra: JSONObject = JSONObject(), headers: JSONObject = JSONObject()): JSONObject =
        app.platform.store.save("owner", "destination", o("name" to "Test $preset", "url" to "http://127.0.0.1:$port$path", "allow_private" to true, "secret" to "00ff", "enabled" to true,
            "preset" to preset, "whsec" to Connections.newSecret(), "headers" to headers, "extra" to extra))

    // ---- pure rules -------------------------------------------------------------------------------------------

    @Test fun recognisesServicesFromTheirAddress() {
        mapOf("https://discord.com/api/webhooks/1/abc" to "discord", "https://hooks.slack.com/services/T/B/x" to "slack",
            "https://api.telegram.org/bot123:abc" to "telegram", "https://ntfy.sh/my-topic" to "ntfy", "https://api.pushover.net/1/messages.json" to "pushover",
            "http://homeassistant.local:8123/api/webhook/door_x" to "home_assistant", "https://n8n.example.com/webhook/abc" to "n8n",
            "https://n8n.example.com/webhook-test/abc" to "n8n", "https://hooks.zapier.com/hooks/catch/1/2/" to "zapier", "https://hook.eu1.make.com/abc" to "make",
            "https://maker.ifttt.com/trigger/door/with/key/k" to "ifttt", "https://example.com/in" to "json").forEach { (url, p) -> assertEquals(url, p, Connections.recognise(url)) }
    }

    @Test fun plainHttpOnlyOnTheOwnersPrivateNetwork() {
        Connections.checkUrl("https://example.com/hook", false, "json")
        Connections.checkUrl("http://192.168.1.42:8123/api/webhook/x", true, "home_assistant")
        Connections.checkUrl("http://homeassistant.local:8123/api/webhook/x", true, "home_assistant")
        Connections.checkUrl("http://100.94.27.79:8080/x", true, "json")
        Connections.checkUrl("https://example.com:8443/hook", true, "json")
        rejects("invalid_connection", "private network") { Connections.checkUrl("http://192.168.1.42:8123/x", false, "json") }
        rejects("invalid_connection", "private-network address") { Connections.checkUrl("http://example.com/x", true, "json") }
        rejects("invalid_connection", "private-network address") { Connections.checkUrl("http://8.8.8.8/x", true, "json") }
        rejects("invalid_connection", "private-network address") { Connections.checkUrl("http://127.0.0.1/x", true, "json") }
        rejects("invalid_connection", "port 443") { Connections.checkUrl("https://example.com:8443/hook", false, "json") }
        rejects("invalid_connection", "user:password") { Connections.checkUrl("https://u:p@example.com/hook", false, "json") }
        rejects("invalid_connection", "Telegram") { Connections.checkUrl("https://api.telegram.org/x", false, "telegram") }
        // At send time the pinned address decides: plain http to a public address is refused even if the name looked private.
        SafeHttp.allowLoopbackForTests = false
        SafeHttp.resolverForTests = { listOf(InetAddress.getByName("8.8.8.8")) }
        try {
            try { SafeHttp.request("http://homeassistant.local:8123/api/webhook/x", SafeHttp.Policy.OWNER_NETWORK, "POST", emptyMap(), ByteArray(0), 2000, 1000, "http://x/c"); fail() }
            catch (e: SafeHttp.Failure) { assertEquals(SafeHttp.Reason.BLOCKED_ADDRESS, e.reason) }
        } finally { SafeHttp.resolverForTests = null }
    }

    @Test fun ownerHeadersAreBoundedAndCantForgeBridgitsOwn() {
        val ok = Connections.checkHeaders(o("X-Api-Key" to "abc", "Authorization" to "Bearer fixed", "X-Note" to o("\$send" to "text")))
        assertEquals(3, ok.length())
        rejects("invalid_connection", "fill") { Connections.checkHeaders(o("Authorization" to o("\$send" to "text"))) }
        for (h in listOf("Host", "Content-Length", "webhook-signature", "X-Bridgit-Signature")) rejects("invalid_connection", "itself") { Connections.checkHeaders(o(h to "x")) }
        rejects("invalid_connection", "At most") { Connections.checkHeaders(JSONObject().apply { repeat(9) { put("X-$it", "v") } }) }
        rejects("invalid_connection", "one line") { Connections.checkHeaders(o("X-A" to "a\r\nX-B: b")) }
    }

    @Test fun standardWebhooksSignatureMatchesThePublishedExample() {
        // The example from the Standard Webhooks reference libraries.
        val sig = Connections.sign("whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw", "msg_p5jXN8AQM9LWM0D4loKWxJek", 1614265330, "{\"test\": 2432232314}".toByteArray())
        assertEquals("v1,g0hM9SsE+OTPJTGt/tmIKtSyZlE3uFJELVlNIOLJ1OE=", sig)
        val s = Connections.newSecret(); assertTrue(s.startsWith("whsec_")); assertEquals(32, Base64.getDecoder().decode(s.removePrefix("whsec_")).size)
        // During a rotation both secrets sign.
        val c = o("whsec" to Connections.newSecret(), "whsec_previous" to s, "whsec_previous_until" to 2000L)
        assertEquals(2, Connections.signatures(c, "m", 1, "b".toByteArray(), 1000).split(' ').size)
        assertEquals(1, Connections.signatures(c, "m", 1, "b".toByteArray(), 3000).split(' ').size)
    }

    private fun msg(photo: ByteArray? = null) = Connections.Message("d1", o("where" to "front door", "count" to 2.0, "applet" to "Porch", "flow" to "f", "run_id" to "r", "sent_at" to "t", "phone" to "A11"), "A package is at the door", photo, "Porch")

    @Test fun eachPresetGetsTheRequestItsServiceReads() {
        val jpeg = byteArrayOf(-1, -40, -1, 1, 2, 3)
        fun conn(p: String, url: String, extra: JSONObject = JSONObject()) = o("preset" to p, "url" to url, "whsec" to Connections.newSecret(), "secret" to "00ff", "extra" to extra)
        Connections.build(conn("discord", "https://discord.com/api/webhooks/1/x"), msg(), true, 1000).let { r ->
            assertEquals("https://discord.com/api/webhooks/1/x?wait=true", r.url)
            val body = JSONObject(String(r.body)); assertEquals("A package is at the door\nwhere: front door\ncount: 2", body.getString("content"))
            assertEquals(0, body.getJSONObject("allowed_mentions").getJSONArray("parse").length())
            assertNull(r.headers["webhook-signature"])
        }
        Connections.build(conn("discord", "https://discord.com/api/webhooks/1/x"), msg(jpeg), false, 1000).let { r ->
            assertTrue(r.headers["Content-Type"]!!.startsWith("multipart/form-data; boundary="))
            val text = String(r.body, Charsets.ISO_8859_1); assertTrue(text.contains("name=\"payload_json\"")); assertTrue(text.contains("name=\"files[0]\"; filename=\"photo.jpg\""))
        }
        Connections.build(conn("telegram", "https://api.telegram.org/bot1:abc", o("chat_id" to "42")), msg(), false, 1000).let { r ->
            assertEquals("https://api.telegram.org/bot1:abc/sendMessage", r.url); assertEquals("42", JSONObject(String(r.body)).getString("chat_id"))
        }
        assertEquals("https://api.telegram.org/bot1:abc/sendPhoto", Connections.build(conn("telegram", "https://api.telegram.org/bot1:abc/", o("chat_id" to "42")), msg(jpeg), false, 1000).url)
        Connections.build(conn("ntfy", "https://ntfy.sh/t"), msg(), false, 1000).let { r -> assertEquals("POST", r.method); assertEquals("Porch", r.headers["X-Title"]); assertTrue(String(r.body).startsWith("A package")) }
        Connections.build(conn("ntfy", "https://ntfy.sh/t"), Connections.Message("d2", o("title" to "Door", "priority" to "high", "where" to "porch"), "Hi", null, "Porch"), false, 1000).let { r ->
            assertEquals("Door", r.headers["X-Title"]); assertEquals("high", r.headers["X-Priority"]); assertEquals("Hi\nwhere: porch", String(r.body))
        }
        Connections.build(conn("ntfy", "https://ntfy.sh/t"), msg(jpeg), false, 1000).let { r -> assertEquals("PUT", r.method); assertEquals("photo.jpg", r.headers["Filename"]); assertArrayEquals(jpeg, r.body) }
        Connections.build(conn("home_assistant", "http://192.168.1.2:8123/api/webhook/x"), msg(jpeg), false, 1000).let { r ->
            val b = JSONObject(String(r.body)); assertEquals("front door", b.getString("where")); assertEquals("A11", b.getString("phone"))
            assertArrayEquals(jpeg, Base64.getDecoder().decode(b.getJSONObject("photo").getString("data_base64")))
        }
        Connections.build(conn("json", "https://example.com/in"), msg(), false, 5_000).let { r ->
            assertEquals("d1", r.headers["webhook-id"]); assertEquals("5", r.headers["webhook-timestamp"]); assertTrue(r.headers["webhook-signature"]!!.startsWith("v1,"))
        }
        Connections.build(conn("zapier", "https://hooks.zapier.com/hooks/catch/1/2/"), msg(), false, 1000).let { r -> assertEquals("front door", JSONObject(String(r.body)).getString("where")) }
        Connections.build(conn("ifttt", "https://maker.ifttt.com/trigger/e/with/key/k"), msg(), false, 1000).let { r -> assertEquals("A package is at the door", JSONObject(String(r.body)).getString("value1")) }
        // An older destination keeps exactly its X-Bridgit headers.
        Connections.build(conn(Connections.LEGACY, "https://example.com/in"), msg(), false, 1000).let { r ->
            val ts = r.headers.getValue("X-Bridgit-Timestamp"); val nonce = r.headers.getValue("X-Bridgit-Nonce")
            assertEquals(DeliveryManager.hmac("00ff", "$ts.$nonce.d1.${String(r.body)}"), r.headers["X-Bridgit-Signature"]); assertNull(r.headers["webhook-signature"])
        }
        assertEquals(Connections.LEGACY, Connections.preset(o("url" to "https://example.com")).id)
        assertNotNull(Connections.photoProblem(Connections.PRESETS.getValue("slack")))
        // Owner headers, with a fill from the send.
        Connections.build(conn("n8n", "https://n8n.example.com/webhook/a").put("headers", o("X-Api-Key" to "k1", "X-Note" to o("\$send" to "where"))), msg(), false, 1000).let { r ->
            assertEquals("k1", r.headers["X-Api-Key"]); assertEquals("front door", r.headers["X-Note"])
        }
    }

    @Test fun repliesAndPublicViews() {
        assertEquals("on", Connections.reply(200, "{\"state\":\"on\"}".toByteArray(), "application/json").getJSONObject("reply").getString("state"))
        assertEquals("OK", Connections.reply(200, "OK".toByteArray(), "text/plain").getString("reply_text"))
        val c = o("id" to "c1", "name" to "HA", "preset" to "home_assistant", "url" to "http://192.168.1.2:8123/api/webhook/secret_id", "allow_private" to true, "whsec" to "whsec_x", "secret" to "s", "enabled" to true)
        val pub = Connections.public(c).toString()
        assertFalse(pub, pub.contains("secret_id")); assertFalse(pub.contains("whsec")); assertFalse(pub.contains("192.168"))
        assertEquals("HA (your private network)", Connections.words(c))
        assertEquals("http://192.168.1.2:8123/•••", Connections.masked(c.getString("url")))
        assertEquals("https://ntfy.sh/•••", Connections.masked("https://ntfy.sh/my-secret-topic"))
        assertFalse(Connections.logUrl(c).contains("secret_id"))
    }

    // ---- the step -------------------------------------------------------------------------------------------

    private fun flow(vararg steps: JSONObject) = o("name" to "Porch", "trigger" to o("type" to "button"), "steps" to a(*steps))

    @Test fun sendStepChecksAndReadsAsASentence() {
        val (port, _) = receiver()
        val ha = connection(port, "home_assistant", "/api/webhook/x").getString("id")
        val def = flow(o("id" to "tell", "type" to "send", "to" to ha, "data" to o("event" to "package_seen", "n" to 1), "text" to "A package", "wait_reply" to true),
            o("type" to "notice", "title" to "on", "only_if" to o("step" to "tell.reply.state", "is" to "on")))
        val v = app.platform.workflows.validate(def)
        assertTrue(v.getJSONArray("required_scopes").toString().contains("connections.send"))
        assertTrue(v.getString("summary"), v.getString("summary").contains("send \"A package\" to Test home_assistant (your private network) and read the reply"))
        assertEquals(120 + 15, v.getJSONObject("limits").getInt("max_run_seconds"))
        fun bad(step: JSONObject, part: String) = rejects("invalid_workflow", part) { app.platform.workflows.validate(flow(step)) }
        bad(o("type" to "send", "to" to "nope"), "isn't a connection the owner approved")
        bad(o("type" to "send", "to" to o("\$trigger" to "to")), "to must be the id")
        bad(o("type" to "send", "to" to ha, "url" to "https://evil.example"), "isn't a field of send")
        bad(o("type" to "send", "to" to ha, "data" to JSONObject().apply { repeat(33) { put("k$it", it) } }), "32 keys")
        bad(o("type" to "send", "to" to ha, "photo" to o("\$step" to "nobody")), "names no earlier step")
        val discord = connection(port, "discord").getString("id")
        bad(o("type" to "send", "to" to discord), "add a text")
        val slack = connection(port, "slack").getString("id")
        bad(o("type" to "send", "to" to slack, "text" to "x", "photo" to "kept:abc"), "photo links aren't available yet")
        // A single step: do.step send is a call to POST /v1/connections/send, with its references filled.
        assertEquals("POST /v1/connections/send", FlowSteps.inlineEndpoint("send"))
        FlowSteps.checkInline(o("type" to "send", "to" to ha, "text" to a("Seen: ", o("\$trigger" to "message"))))
        val call = FlowSteps.inlineCall(o("type" to "send", "to" to ha, "text" to a("Seen: ", o("\$trigger" to "message")), "data" to o("m" to o("\$trigger" to "message"))), o("message" to "cat"))
        assertEquals("/v1/connections/send", call.getString("path")); assertEquals("Seen: cat", call.getJSONObject("body").getString("text")); assertEquals("cat", call.getJSONObject("body").getJSONObject("data").getString("m"))
    }

    private fun activeWorkflow(def: JSONObject): Pair<String, String> {
        val agent = app.policy.addAgent("SendTester" + (System.nanoTime() % 100000)).first.id
        app.platform.workflows.validate(def).strings("required_scopes").forEach { Capabilities.setEnabled(app, it, true); app.platform.grant(agent, it, System.currentTimeMillis() + 3_600_000) }
        val id = app.platform.workflows.propose(agent, def).getString("id"); app.platform.workflows.activate(id)
        return agent to id
    }

    @Test fun aFlowSendsSignedJsonReadsTheReplyAndActsOnIt() {
        val (port, got) = receiver()
        val conn = connection(port, "json", headers = o("X-Api-Key" to "k1"))
        val def = flow(o("id" to "tell", "type" to "send", "to" to conn.getString("id"), "data" to o("event" to "package_seen"), "text" to "A package", "wait_reply" to true),
            o("id" to "after", "type" to "event", "text" to "it is on", "only_if" to o("step" to "tell.reply.state", "is" to "on")))
        val (agent, id) = activeWorkflow(def)
        val run = runBlocking { withTimeout(30_000) { app.platform.workflows.runOnce(agent, id) } }
        val steps = run.getJSONArray("steps")
        assertEquals(run.toString(), "ok", steps.getJSONObject(0).getString("status"))
        assertEquals("ok", steps.getJSONObject(1).getString("status")) // only_if on the reply held
        assertEquals(1, got.size)
        val g = got[0]
        assertTrue(g.requestLine.startsWith("POST /hook "))
        assertEquals("k1", g.h("X-Api-Key"))
        val body = JSONObject(String(g.body)); assertEquals("package_seen", body.getString("event")); assertEquals("Porch", body.getString("flow")); assertTrue(body.has("sent_at")); assertTrue(body.has("run_id"))
        // The receiver can check it with only the whsec_ secret.
        assertEquals(Connections.sign(conn.getString("whsec"), g.h("webhook-id")!!, g.h("webhook-timestamp")!!.toLong(), g.body), g.h("webhook-signature"))
    }

    @Test fun queuedSendsRetryWithTheSameIdAndKeepSentAt() {
        var fail = true
        val (port, got) = receiver(status = { if (fail) 500 else 200 })
        val conn = connection(port).getString("id")
        val agent = app.policy.addAgent("Queue").first.id
        val r = app.platform.delivery.send(agent, conn, o("k" to 1), "hi", null, waitReply = false)
        assertTrue(r.getBoolean("queued"))
        fun row() = app.platform.store.require(r.getString("delivery_id"), agent)
        fun drain() { val before = row().optInt("attempts"); app.platform.delivery.flush(); val end = System.currentTimeMillis() + 10_000; while (System.currentTimeMillis() < end && row().optInt("attempts") == before) Thread.sleep(20) }
        drain()
        assertEquals("queued", app.platform.store.require(r.getString("delivery_id"), agent).getString("status"))
        fail = false; drain()
        assertEquals("accepted", app.platform.store.require(r.getString("delivery_id"), agent).getString("status"))
        assertEquals(2, got.size)
        assertEquals(got[0].h("webhook-id"), got[1].h("webhook-id"))
        assertEquals(r.getString("delivery_id"), got[1].h("webhook-id"))
        assertEquals(JSONObject(String(got[0].body)).getString("sent_at"), JSONObject(String(got[1].body)).getString("sent_at"))
    }

    @Test fun flowSendLosingItsReplyHasOneExternalEffectAndAnUnknownOutcome() {
        val (port, received) = receiver(dropReply = true)
        val conn = connection(port)
        val flow = flow(o("id" to "message", "type" to "send", "to" to conn.getString("id"),
            "text" to "Dinner reminder", "wait_reply" to true, "on_error" to "retry", "retries" to 2),
            o("id" to "confirmed", "type" to "event", "text" to "Reminder delivered"))
        val (agent, workflow) = activeWorkflow(flow)
        val result = runBlocking { withTimeout(30_000) { app.platform.workflows.runOnce(agent, workflow) } }
        assertEquals(result.toString(), "unknown_outcome", result.getString("status"))
        assertEquals("not_run", result.getJSONArray("steps").getJSONObject(1).getString("status"))
        val row = app.platform.store.list(agent, "outbox").single()
        assertEquals("unknown_outcome", row.getString("status"))
        assertEquals(1, row.getInt("attempts"))
        app.platform.delivery.flush()
        assertEquals(1, received.size)

        // A separate, deliberately started batch exposes the uncertain recipient to the owner.
        assertEquals("started", app.platform.batches.start(agent, "dinner", "Dinner", workflow,
            listOf("family" to null)).getString("status"))
        val deadline = System.currentTimeMillis() + 10_000
        while (app.platform.batches.running(agent) != null && System.currentTimeMillis() < deadline) Thread.sleep(20)
        val summary = app.platform.batches.summary(agent, "dinner")
        assertEquals(1, summary.getInt("unknown"))
        assertEquals(0, summary.getInt("failed"))
        assertEquals("family", summary.getJSONArray("unknown_items").getJSONObject(0).getString("key"))
        assertEquals(2, received.size)
    }

    @Test fun queuedWorkflowSendRemainsUnconfirmedAcrossFlushAfterReceiverClosesConnection() {
        val (port, received) = receiver(dropReply = true)
        val agent = app.policy.addAgent("Dinner monitor").first.id
        val result = app.platform.delivery.send(agent, connection(port).getString("id"), JSONObject(),
            "Dinner reminder", null, waitReply = false, flow = "Dinner", runId = "run-dinner")
        app.platform.delivery.flush()
        val deadline = System.currentTimeMillis() + 10_000
        while (received.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(1, received.size)
        val id = result.getString("delivery_id")
        assertEquals("unknown_outcome", app.platform.store.require(id, agent).getString("status"))
        app.platform.delivery.flush()
        assertEquals(1, app.platform.store.require(id, agent).getInt("attempts"))
    }

    @Test fun failingConnectionsAreMarkedAndRateIsCapped() {
        val (port, _) = receiver(status = { 500 })
        val conn = connection(port).getString("id")
        val agent = app.policy.addAgent("Failing").first.id
        repeat(6) { runCatching { app.platform.delivery.send(agent, conn, JSONObject(), "x", null, waitReply = true) } }
        assertTrue(app.platform.store.require(conn, "owner", "destination").getBoolean("failing"))
        rejects("send_failed") { app.platform.delivery.send(agent, conn, JSONObject(), "x", null, waitReply = true) }
        repeat(Connections.PER_HOUR - 7) { app.platform.store.save(agent, "outbox", o("destination_id" to conn, "send" to JSONObject(), "status" to "accepted")) }
        rejects("rate_limited") { app.platform.delivery.send(agent, conn, JSONObject(), "x", null, waitReply = true) }
    }

    @Test fun approvalLimitsAndAgentsNeverSeeSecrets() {
        val d = app.platform.delivery
        val c = d.destination("Porch HA", "http://192.168.1.42:8123/api/webhook/porch_secret", true)
        assertEquals("home_assistant", c.getString("preset")); assertTrue(c.getString("whsec").startsWith("whsec_"))
        assertTrue(Capabilities.enabled(app, "connections.send"))
        rejects("invalid_connection") { d.destination("Bad", "http://192.168.1.42:8123/api/webhook/x", false) }
        rejects("invalid_connection", "chat id") { d.destination("TG", "https://api.telegram.org/bot1:abc", false) }
        val list = d.connections().toString(); assertFalse(list.contains("porch_secret")); assertFalse(list.contains("whsec"))
        // Alerts and event callbacks still need https on 443.
        rejects("invalid_destination", "can take sends but not alerts") { d.validateDestinations(listOf(c.getString("id"))) }
        val rotated = d.rotate(c.getString("id")); assertEquals(c.getString("whsec"), rotated.getString("whsec_previous"))
    }
}
