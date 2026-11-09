package cc.bridgit.app.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A0028: a refused confirmation email must pass bridgit.cc's real limit and wait to agents, not a fixed 300 s. */
class EmailRateLimitTest {
    @Test fun usesTheServersLimitWaitAndMessage() {
        val l = EmailVerificationRules.rateLimit("""{"error":"This address already got 7 confirmation emails in the last 24 hours. No email was sent. Try again in 22 hours.","code":"rate_limited","limit":"address_daily","retry_after_s":78123}""", "78123")
        assertEquals("address_daily", l.limit)
        assertEquals(78123, l.retryAfterS)
        assertEquals("This address already got 7 confirmation emails in the last 24 hours. No email was sent. Try again in 22 hours.", l.message)
    }

    @Test fun olderServersFallBackToRetryAfterThenFiveMinutes() {
        assertEquals(42, EmailVerificationRules.rateLimit("""{"error":"Please wait","code":"rate_limited"}""", "42").retryAfterS)
        val none = EmailVerificationRules.rateLimit("not json", null)
        assertEquals(EmailVerificationRules.DEFAULT_RETRY_AFTER_S, none.retryAfterS)
        assertNull(none.limit); assertNull(none.message)
    }

    @Test fun oddValuesAreBounded() {
        val l = EmailVerificationRules.rateLimit("""{"limit":"<script>","retry_after_s":99999999,"error":"${"x".repeat(1000)}"}""", null)
        assertNull(l.limit)
        assertEquals(2 * 86_400, l.retryAfterS)
        assertEquals(400, l.message!!.length)
    }
}
