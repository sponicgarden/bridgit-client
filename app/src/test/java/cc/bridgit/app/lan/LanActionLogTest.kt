package cc.bridgit.app.lan

import cc.bridgit.app.log.LogEntry
import cc.bridgit.app.log.LogQuery
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** A0100: the outlet flow's runtime log. */
class LanActionLogTest {
    private val trigger = LanActionLog.Trigger("agent-a", "Almusca", "mcp", "req_1", "POST", "/v1/lan/devices/action")
    private val device = LanDevice("net:lamp", "Desk lamp", "192.168.1.20", "shelly_gen1", "agent-a", "secret-user", "secret-password", true, 1, "AABBCCDDEEFF")
    private var clock = 1_000L
    private val log = mutableListOf<LogEntry>()
    private fun op(action: String = "relay_on", params: JSONObject? = JSONObject().put("device", "net:lamp").put("action", action), sink: MutableList<LogEntry> = log, t: LanActionLog.Trigger = trigger) =
        LanActionLog.Op({ sink += it.copy(id = (sink.size + 1).toLong()) }, t, "action", action, 0, params, { clock })

    @Test fun anActionLogsStartedThenItsOutcomeInOrderWithTriggerAndDevice() {
        val o = op().device(device)
        o.started()
        clock += 412
        o.ok(JSONObject().put("identity", "AABBCCDDEEFF").put("channel", 0).put("on", true))
        assertEquals(2, log.size)
        val (start, end) = log
        assertEquals(listOf("started", "ok"), log.map { it.data!!.getString("phase") })
        assertEquals(o.id, start.data!!.getString("op_id")); assertEquals(o.id, end.data!!.getString("op_id"))
        assertTrue(log.all { it.kind == "lan" && it.channel == "audit" && it.agentId == "agent-a" && it.requestId == "req_1" })
        assertTrue("lan" in LogEntry.KINDS)
        assertNull(start.result); assertNull(start.durationMs)
        assertEquals("ok", end.result); assertEquals(412L, end.durationMs); assertEquals("ok", end.data!!.getString("outcome"))
        assertEquals("Desk lamp", end.data!!.getJSONObject("device").getString("name"))
        assertEquals("shelly_gen1", end.data!!.getJSONObject("device").getString("adapter"))
        assertEquals("mcp", end.data!!.getJSONObject("trigger").getString("via"))
        assertEquals("Almusca asked Desk lamp: relay_on, channel 0", start.message)
        assertTrue(end.message, end.message.contains("now on"))
        assertTrue(end.data!!.getJSONObject("result").getBoolean("on"))
        assertFalse(log.toString().contains("secret-"))
        // One outcome per operation: a late failure after success is ignored.
        o.failed("upstream_failed", "late", 502)
        assertEquals(2, log.size)
    }

    @Test fun failuresSayWhetherTheDeviceMayHaveChanged() {
        op().device(device).apply { started(); failed("upstream_failed", "The device didn't answer", 502) }
        val unknown = log.last()
        assertEquals("unknown_outcome", unknown.data!!.getString("outcome")); assertEquals("warn", unknown.level)
        assertEquals("upstream_failed", unknown.result); assertTrue(unknown.isError)
        assertEquals("The device didn't answer", unknown.data!!.getString("error_message"))

        op().device(device).apply { started(); failed("conflict", "Another LAN operation is running", 409) }
        assertEquals("failed", log.last().data!!.getString("outcome")); assertEquals("error", log.last().level)

        op("status").device(device).apply { started(); failed("upstream_failed", "no answer", 502) }
        assertEquals("failed", log.last().data!!.getString("outcome"))

        // Unknown device: the id still names it, never anything else.
        op().device("net:gone").apply { started(); failed("not_found", "No device enrolled", 404) }
        assertTrue(log.last().message.startsWith("net:gone: relay_on"))
    }

    @Test fun intermediateStepsAndRelayStatusAreRecorded() {
        val o = op("status").device("net:spk", "Kitchen speaker", "upnp_av", "192.168.1.30")
        o.started(); o.step("retry", "the device refused the connection; finding it again and retrying once")
        o.ok(JSONObject().put("relays", JSONArray().put(JSONObject().put("channel", 0).put("on", false))))
        assertEquals(listOf("started", "retry", "ok"), log.map { it.data!!.getString("phase") })
        assertEquals("0:off", log.last().data!!.getJSONObject("result").getJSONArray("relays").getString(0))
        assertTrue(log.last().message, log.last().message.contains("relay 0 off"))
    }

    @Test fun paramsAreRedactedAndUrlsLoseCredentialsAndQuery() {
        val params = JSONObject().put("device", "net:spk").put("action", "play")
            .put("params", JSONObject().put("url", "https://user:pw@cdn.example.com/chime.mp3?token=abc123#t=1").put("level", 30))
            .put("password", "hunter2")
        op("play", params).apply { started(); ok(null) }
        val logged = log.first().params!!
        assertEquals("https://cdn.example.com/chime.mp3?…", logged.getJSONObject("params").getString("url"))
        assertEquals(30, logged.getJSONObject("params").getInt("level"))
        assertEquals("[redacted]", logged.getString("password"))
        assertFalse(log.toString().contains("abc123")); assertFalse(log.toString().contains("hunter2")); assertFalse(log.toString().contains("pw@"))
        assertEquals("not a url", LanActionLog.stripUrl("not a url"))
        assertEquals("http://192.168.1.30:49152/desc.xml", LanActionLog.stripUrl("http://192.168.1.30:49152/desc.xml"))
    }

    /** A store stand-in that applies the parts of LogQuery the reader uses. */
    private fun store(entries: List<LogEntry>, asked: MutableList<LogQuery> = mutableListOf()): (LogQuery) -> List<LogEntry> = { q ->
        asked += q
        entries.filter { e -> (q.agentIds == null || e.agentId in q.agentIds!!) && (q.kinds.isEmpty() || e.kind in q.kinds) &&
            (q.channels.isEmpty() || e.channel in q.channels) && (q.sinceId == null || e.id > q.sinceId!!) &&
            (q.cursor == null || if (q.ascending) e.id > q.cursor!! else e.id < q.cursor!!) }
            .let { if (q.ascending) it.sortedBy { e -> e.id } else it.sortedByDescending { e -> e.id } }.take(q.limit)
    }

    @Test fun readReturnsOnlyTheCallersEntriesFilteredByDeviceAndOperation() {
        val a = op().device(device).apply { started(); ok(JSONObject().put("on", true)) }
        op().device("net:other-device").apply { started(); failed("not_found", "nope", 404) }
        LanActionLog.Op({ log += it.copy(id = (log.size + 1).toLong()) }, trigger.copy(agentId = "agent-b", agentName = "Other"), "action", "relay_off", 0, null, { clock })
            .device("net:lamp").apply { started() }
        log += LogEntry(id = (log.size + 1).toLong(), ts = 1, channel = "audit", kind = "request", agentId = "agent-a", message = "not lan")
        val asked = mutableListOf<LogQuery>()
        val iso = { ms: Long -> "t$ms" }
        val mine = LanActionLog.read(store(log, asked), "agent-a", null, null, null, 100, true, null, iso)
        assertEquals(4, mine.getInt("count"))
        assertEquals(listOf("agent-a"), asked.first().agentIds); assertEquals(listOf("lan"), asked.first().kinds)
        val lamp = LanActionLog.read(store(log), "agent-a", "net:lamp", null, null, 100, true, null, iso).getJSONArray("entries")
        assertEquals(2, lamp.length())
        assertEquals("started", lamp.getJSONObject(0).getString("phase")); assertEquals("ok", lamp.getJSONObject(1).getString("outcome"))
        assertEquals("Desk lamp", lamp.getJSONObject(1).getJSONObject("device").getString("name"))
        assertEquals("POST", lamp.getJSONObject(0).getJSONObject("trigger").getString("method"))
        val byOp = LanActionLog.read(store(log), "agent-a", null, a.id, null, 100, false, null, iso).getJSONArray("entries")
        assertEquals(2, byOp.length()); assertEquals("ok", byOp.getJSONObject(0).getString("phase"))
        // Paging: limit 1 with a cursor walks the same entries one by one.
        val first = LanActionLog.read(store(log), "agent-a", "net:lamp", null, null, 1, true, null, iso)
        val cursor = LogQuery.decodeCursor(first.getString("next_cursor"))
        val second = LanActionLog.read(store(log), "agent-a", "net:lamp", null, null, 1, true, cursor, iso)
        assertEquals("ok", second.getJSONArray("entries").getJSONObject(0).getString("phase"))
        // Another agent never sees agent-a's entries.
        assertEquals(1, LanActionLog.read(store(log), "agent-b", null, null, null, 100, true, null, iso).getInt("count"))
    }
}
