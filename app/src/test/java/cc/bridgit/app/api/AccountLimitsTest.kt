package cc.bridgit.app.api

import cc.bridgit.app.interact.CardRules
import cc.bridgit.app.watch.WatchRules
import org.junit.Assert.*
import org.junit.Test

/** Limits are per-account variables: the phone reports them at GET /v1/limits and the docs point there. */
class AccountLimitsTest {
    @Test fun phonePartReportsWhatThisBuildEnforces() {
        val p = AccountLimits.phone("attested")
        assertEquals(CardRules.HTML_MAX_BYTES, p.getJSONObject("cards").getInt("html_max_bytes"))
        assertEquals(RateLimiter.CAPTURES_PER_AGENT, p.getInt("captures_per_minute_per_agent"))
        assertEquals(RateLimiter.DEVICE_ACTIONS_PER_AGENT, p.getInt("lan_device_actions_per_minute_per_agent"))
        // A0212: device actions have their own bucket and wording; LAN discovery and enrollment stay with captures.
        assertEquals("LAN device actions", RateLimiter.bucket("lan_control").noun)
        assertNotEquals(RateLimiter.CAPTURES.key, RateLimiter.bucket("lan_control").key)
        assertEquals(RateLimiter.CAPTURES, RateLimiter.bucket("lan_enrollment")); assertEquals(RateLimiter.CAPTURES, RateLimiter.bucket("camera"))
        assertEquals("cap", RateLimiter.CAPTURES.key) // FramingSessions charges the same cap-agent / cap-ip keys
        assertEquals(WatchRules.MAX_CHECKS_PER_HOUR, p.getJSONObject("watches").getInt("default_paid_checks_per_hour"))
        assertEquals(WatchRules.MAX_CHECKS_PER_HOUR_UNVERIFIED, AccountLimits.phone(null).getJSONObject("watches").getInt("default_paid_checks_per_hour"))
        // Without an answer from the team backend, watches fall back to this build's default.
        assertEquals(WatchRules.maxChecksPerHour("attested"), AccountLimits.watchPaidChecksPerHour("attested"))
    }

    @Test fun endpointIsPublishedAndDocsSendAgentsToIt() {
        assertTrue(Endpoints.all.any { it.method == "GET" && it.fullPath == "/v1/limits" })
        assertTrue(ApiChanges.revisions.any { r -> r.items.any { it.endpoint == "/v1/limits" } })
        for ((name, text) in listOf("start" to CapabilityCatalog.start(), "decisions" to Learn.decisions(), "playbook" to Playbook.build()))
            assertTrue("$name must send agents to GET /v1/limits", text.contains("GET /v1/limits"))
        // Limits are never stated as fixed facts in the entry page.
        assertFalse(CapabilityCatalog.start().contains("$1.50 a month per phone for"))
    }
}
