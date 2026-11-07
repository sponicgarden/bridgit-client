package cc.bridgit.app.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class AlertLogicTest {
    private val zone = ZoneId.of("Europe/Warsaw")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()
    private val min = 60_000L
    private var nextId = 1L
    private fun ev(ts: Long, agent: String = "Claude", endpoint: String = "/v1/status", http: Int = 200, method: String = "GET") =
        AlertEvent(nextId++, ts, "id-$agent", agent, method, endpoint, http, "$agent: $method $endpoint")

    // ---- window boundaries ----

    @Test fun hourlyWindowsEndOnTheHour() {
        val t = at(2026, 10, 30, 14, 37)
        assertEquals(at(2026, 10, 30, 14), AlertPlanner.boundary(AlertFrequency.HOURLY, t, zone))
        assertEquals(at(2026, 10, 30, 15), AlertPlanner.nextBoundary(AlertFrequency.HOURLY, t, zone))
        assertEquals(at(2026, 10, 30, 14), AlertPlanner.boundary(AlertFrequency.HOURLY, at(2026, 10, 30, 14), zone))
    }

    @Test fun dailyWindowsEndAtEightLocal() {
        assertEquals(at(2026, 10, 30, 8), AlertPlanner.boundary(AlertFrequency.DAILY, at(2026, 10, 30, 9), zone))
        assertEquals(at(2026, 10, 29, 8), AlertPlanner.boundary(AlertFrequency.DAILY, at(2026, 10, 30, 7, 59), zone))
        assertEquals(at(2026, 10, 30, 8), AlertPlanner.nextBoundary(AlertFrequency.DAILY, at(2026, 10, 30, 7, 59), zone))
        assertEquals(at(2026, 10, 31, 8), AlertPlanner.nextBoundary(AlertFrequency.DAILY, at(2026, 10, 30, 8), zone))
    }

    @Test fun weeklyWindowsEndMondayAtEight() {
        // 30 Oct 2026 is a Friday; the previous Monday is 26 Oct, the next 2 Nov.
        assertEquals(at(2026, 10, 26, 8), AlertPlanner.boundary(AlertFrequency.WEEKLY, at(2026, 10, 30, 12), zone))
        assertEquals(at(2026, 11, 2, 8), AlertPlanner.nextBoundary(AlertFrequency.WEEKLY, at(2026, 10, 30, 12), zone))
        // Monday before 8: still last week's window.
        assertEquals(at(2026, 10, 26, 8), AlertPlanner.boundary(AlertFrequency.WEEKLY, at(2026, 11, 2, 7), zone))
        assertEquals(at(2026, 11, 2, 8), AlertPlanner.boundary(AlertFrequency.WEEKLY, at(2026, 11, 2, 8), zone))
    }

    @Test fun dailyBoundaryFollowsLocalTimeAcrossDst() {
        // Poland leaves summer time at 03:00 on 25 Oct 2026: that window is 25 hours, the digest still goes at 8:00 local.
        assertEquals(at(2026, 10, 25, 8), AlertPlanner.nextBoundary(AlertFrequency.DAILY, at(2026, 10, 24, 9), zone))
        assertEquals(25 * 60 * min, at(2026, 10, 25, 8) - at(2026, 10, 24, 8))
    }

    // ---- what is due ----

    @Test fun noneNeverSends() {
        assertNull(AlertPlanner.due(AlertFrequency.NONE, listOf(ev(0)), 0, at(2026, 10, 30, 12), zone))
        assertNull(AlertPlanner.nextCheck(AlertFrequency.NONE, listOf(ev(0)), 0, at(2026, 10, 30, 12), zone))
    }

    @Test fun everyActionSendsAtOnceThenCoalescesForTenMinutes() {
        val t = at(2026, 10, 30, 12)
        val first = ev(t)
        assertEquals(listOf(first), AlertPlanner.due(AlertFrequency.EVERY_ACTION, listOf(first), 0, t, zone)!!.events)
        // Sent at t. Calls at t+1 and t+5 wait until t+10.
        val burst = listOf(ev(t + 1 * min), ev(t + 5 * min))
        assertNull(AlertPlanner.due(AlertFrequency.EVERY_ACTION, burst, t, t + 5 * min, zone))
        assertEquals(t + 10 * min, AlertPlanner.nextCheck(AlertFrequency.EVERY_ACTION, burst, t, t + 5 * min, zone))
        assertEquals(burst, AlertPlanner.due(AlertFrequency.EVERY_ACTION, burst, t, t + 10 * min, zone)!!.events)
    }

    @Test fun digestWaitsForTheBoundaryAndOnlyTakesFinishedWindows() {
        val early = ev(at(2026, 10, 30, 13, 10))
        val late = ev(at(2026, 10, 30, 14, 5))
        val pending = listOf(early, late)
        assertNull(AlertPlanner.due(AlertFrequency.HOURLY, listOf(early), 0, at(2026, 10, 30, 13, 59), zone))
        assertEquals(at(2026, 10, 30, 14), AlertPlanner.nextCheck(AlertFrequency.HOURLY, listOf(early), 0, at(2026, 10, 30, 13, 59), zone))
        val b = AlertPlanner.due(AlertFrequency.HOURLY, pending, 0, at(2026, 10, 30, 14, 6), zone)!!
        assertEquals(listOf(early), b.events)
        assertEquals(at(2026, 10, 30, 14), b.until)
        assertEquals(at(2026, 10, 30, 14, 6), AlertPlanner.nextCheck(AlertFrequency.HOURLY, pending, 0, at(2026, 10, 30, 14, 6), zone))
        assertEquals(at(2026, 10, 30, 15), AlertPlanner.nextCheck(AlertFrequency.HOURLY, listOf(late), 0, at(2026, 10, 30, 14, 6), zone))
    }

    @Test fun missedWindowsCombineIntoOneDigest() {
        val pending = listOf(ev(at(2026, 10, 27, 10)), ev(at(2026, 10, 28, 10)), ev(at(2026, 10, 29, 10)))
        val b = AlertPlanner.due(AlertFrequency.DAILY, pending, 0, at(2026, 10, 30, 9), zone)!!
        assertEquals(3, b.events.size)
        assertEquals(at(2026, 10, 27, 10), b.from)
    }

    @Test fun weeklyDigestHoldsTheWholeWeek() {
        val pending = listOf(ev(at(2026, 10, 26, 9)), ev(at(2026, 11, 1, 23)))
        assertNull(AlertPlanner.due(AlertFrequency.WEEKLY, pending, 0, at(2026, 11, 2, 7, 59), zone))
        assertEquals(2, AlertPlanner.due(AlertFrequency.WEEKLY, pending, 0, at(2026, 11, 2, 8), zone)!!.events.size)
    }

    // ---- composing ----

    @Test fun everyActionEmailListsCallsAndNamesTheAgent() {
        val t = at(2026, 10, 30, 12)
        val m = AlertComposer.compose(AlertFrequency.EVERY_ACTION,
            AlertPlanner.Batch(listOf(ev(t, endpoint = "/v1/camera/snapshot", method = "POST"), ev(t + min, http = 403)), t, t + min), zone, "Pixel 10")
        assertEquals("Bridgit: Claude used Pixel 10 (2 calls)", m.subject)
        assertTrue(m.text, m.text.contains("12:00  Claude: POST /v1/camera/snapshot"))
        assertTrue(m.text, m.text.contains("12:01  Claude: GET /v1/status (failed, 403)"))
        assertTrue(m.text.contains("at most one of these emails every 10 minutes"))
    }

    @Test fun singleCallSubjectHasNoCount() {
        val t = at(2026, 10, 30, 12)
        assertEquals("Bridgit: Claude used your phone",
            AlertComposer.compose(AlertFrequency.EVERY_ACTION, AlertPlanner.Batch(listOf(ev(t)), t, t), zone).subject)
    }

    @Test fun digestGroupsByAgentAndCountsEndpoints() {
        val t = at(2026, 10, 29, 9)
        val events = List(3) { ev(t + it * min, "Claude", "/v1/speak", method = "POST") } +
            ev(t + 10 * min, "Claude", "/v1/status", http = 500) + ev(t + 20 * min, "Codex", "/v1/location")
        val m = AlertComposer.compose(AlertFrequency.DAILY, AlertPlanner.Batch(events, t, at(2026, 10, 30, 8)), zone)
        assertEquals("Bridgit daily summary: 5 calls by 2 agents", m.subject)
        assertTrue(m.text, m.text.contains("Claude — 4 calls, 1 failed, 09:00–09:10"))
        assertTrue(m.text, m.text.contains("  3 × POST /v1/speak"))
        assertTrue(m.text, m.text.contains("Codex — 1 call, 09:20–09:20"))
        assertTrue(m.text, m.text.indexOf("Claude —") < m.text.indexOf("Codex —"))
        assertTrue(m.text, m.text.contains("Thu 29 Oct 09:00 – Fri 30 Oct 08:00"))
    }

    @Test fun idempotencyKeyIsStableForTheSameBatch() {
        val t = at(2026, 10, 30, 12)
        val b = AlertPlanner.Batch(listOf(ev(t), ev(t + min)), t, t + min)
        assertEquals(AlertComposer.compose(AlertFrequency.HOURLY, b, zone).idempotency, AlertComposer.compose(AlertFrequency.HOURLY, b, zone).idempotency)
    }

    @Test fun longEmailsAreCapped() {
        val t = at(2026, 10, 30, 12)
        val many = List(80) { ev(t + it, endpoint = "/v1/" + "x".repeat(300)) }
        val m = AlertComposer.compose(AlertFrequency.EVERY_ACTION, AlertPlanner.Batch(many, t, t), zone)
        assertTrue(m.text.length <= AlertComposer.MAX_TEXT)
        assertTrue(m.text.contains("…and 30 earlier calls") || m.text.endsWith("…"))
    }

    // ---- dispatcher ----

    private class MemoryOutbox : AlertOutbox {
        val rows = mutableListOf<AlertEvent>()
        private var id = 0L
        override fun add(e: AlertEvent) { rows += e.copy(id = ++id) }
        override fun pending() = rows.toList()
        override fun remove(ids: Collection<Long>) { rows.removeAll { it.id in ids } }
        override fun clear() = rows.clear()
        override var lastSentAt = 0L
    }

    private class FakeSender(var result: AlertSender.Result = AlertSender.Result.SENT) : AlertSender {
        val sent = mutableListOf<AlertMessage>()
        override fun send(message: AlertMessage): AlertSender.Result { if (result == AlertSender.Result.SENT) sent += message; return result }
    }

    private class Rig(initial: AlertFrequency, start: Long, val zone: ZoneId) {
        var freq = initial
        var now = start
        var confirmed = true
        var gone = 0
        val outbox = MemoryOutbox()
        val sender = FakeSender()
        val d = AlertDispatcher(outbox, sender, { freq }, { confirmed }, { gone++; confirmed = false }, { now }, { zone })
        fun call(agent: String = "Claude") = d.record(AlertEvent(ts = now, agentId = "id-$agent", agent = agent, method = "GET", endpoint = "/v1/status", http = 200, message = ""))
    }

    @Test fun dispatcherEveryActionBurst() {
        val t = at(2026, 10, 30, 12)
        val r = Rig(AlertFrequency.EVERY_ACTION, t, zone)
        r.call(); r.d.run()
        assertEquals(1, r.sender.sent.size)
        // Burst of five calls over the next four minutes: no mail yet, next look at t+10.
        repeat(5) { r.now = t + (it + 1) * 50_000L; r.call(); assertEquals(AlertDispatcher.Outcome.Idle(t + 10 * min), r.d.run()) }
        assertEquals(1, r.sender.sent.size)
        r.now = t + 10 * min
        r.d.run()
        assertEquals(2, r.sender.sent.size)
        assertTrue(r.sender.sent[1].subject, r.sender.sent[1].subject.contains("(5 calls)"))
        assertTrue(r.outbox.rows.isEmpty())
        assertEquals(AlertDispatcher.Outcome.Idle(null), r.d.run())
    }

    @Test fun dispatcherHourlyDigest() {
        val r = Rig(AlertFrequency.HOURLY, at(2026, 10, 30, 13, 5), zone)
        r.call("Claude"); r.now += 10 * min; r.call("Codex")
        assertEquals(AlertDispatcher.Outcome.Idle(at(2026, 10, 30, 14)), r.d.run())
        assertTrue(r.sender.sent.isEmpty())
        r.now = at(2026, 10, 30, 14, 1)
        r.call("Claude") // belongs to the 14:00–15:00 window
        r.d.run()
        assertEquals(1, r.sender.sent.size)
        assertEquals("Bridgit hourly summary: 2 calls by 2 agents", r.sender.sent[0].subject)
        assertEquals(1, r.outbox.rows.size)
    }

    @Test fun dispatcherDailyAndWeekly() {
        for ((f, sendAt) in listOf(AlertFrequency.DAILY to at(2026, 10, 31, 8), AlertFrequency.WEEKLY to at(2026, 11, 2, 8))) {
            val r = Rig(f, at(2026, 10, 30, 9), zone)
            r.call()
            r.now = sendAt - 1; r.d.run()
            assertTrue("$f sent early", r.sender.sent.isEmpty())
            r.now = sendAt; r.d.run()
            assertEquals("$f", 1, r.sender.sent.size)
        }
    }

    @Test fun dispatcherNoneDropsEverything() {
        val r = Rig(AlertFrequency.HOURLY, at(2026, 10, 30, 13), zone)
        r.call()
        r.freq = AlertFrequency.NONE
        assertFalse(r.call())
        r.d.run()
        assertTrue(r.outbox.rows.isEmpty())
        assertTrue(r.sender.sent.isEmpty())
    }

    @Test fun dispatcherWaitsForConfirmationAndKeepsCalls() {
        val r = Rig(AlertFrequency.EVERY_ACTION, at(2026, 10, 30, 13), zone)
        r.confirmed = false
        r.call()
        r.d.run()
        assertTrue(r.sender.sent.isEmpty())
        r.confirmed = true
        r.d.run()
        assertEquals(1, r.sender.sent.size)
    }

    @Test fun dispatcherRetryKeepsTheBatchAndGoneStops() {
        val r = Rig(AlertFrequency.EVERY_ACTION, at(2026, 10, 30, 13), zone)
        r.call()
        r.sender.result = AlertSender.Result.RETRY
        assertEquals(AlertDispatcher.Outcome.Retry, r.d.run())
        assertEquals(1, r.outbox.rows.size)
        r.sender.result = AlertSender.Result.GONE
        r.d.run()
        assertEquals(1, r.gone)
        assertEquals(1, r.outbox.rows.size)
        r.sender.result = AlertSender.Result.REJECTED
        r.confirmed = true
        r.d.run()
        assertTrue("a rejected message is dropped so it can't block later ones", r.outbox.rows.isEmpty())
    }

    @Test fun relayStatusCodesMapToDispatcherActions() {
        assertEquals(AlertSender.Result.SENT, AlertRelay.resultFor(200))
        assertEquals(AlertSender.Result.GONE, AlertRelay.resultFor(410))
        assertEquals(AlertSender.Result.GONE, AlertRelay.resultFor(409))
        assertEquals(AlertSender.Result.REJECTED, AlertRelay.resultFor(400))
        assertEquals(AlertSender.Result.RETRY, AlertRelay.resultFor(429))
        assertEquals(AlertSender.Result.RETRY, AlertRelay.resultFor(503))
    }

    @Test fun frequencyIdsRoundTrip() {
        for (f in AlertFrequency.values()) assertEquals(f, AlertFrequency.of(f.id))
        assertEquals(AlertFrequency.NONE, AlertFrequency.of(null))
        assertEquals(AlertFrequency.NONE, AlertFrequency.of("bogus"))
    }
}
