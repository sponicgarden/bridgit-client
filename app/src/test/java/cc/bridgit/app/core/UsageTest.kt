package cc.bridgit.app.core

import cc.bridgit.app.api.Endpoints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageTest {
    @Test fun payloadCarriesOnlyBatchVersionAndCounts() {
        val p = Usage.payload("3f1c0d2e-0000-4000-8000-000000000000", 42, mapOf("api:GET /v1/status:ok" to 3))
        assertEquals(setOf("batch", "app_version", "counts"), p.keys().asSequence().toSet())
        assertEquals(3, p.getJSONObject("counts").getInt("api:GET /v1/status:ok"))
    }

    /** Every endpoint's tally key must pass the same check the server applies, or its uses would be lost. */
    @Test fun everyEndpointKeyFits() {
        Endpoints.all.forEach { s ->
            listOf("api", "mcp").forEach { kind ->
                val key = "$kind:${s.method} ${s.fullPath}:rate_limited"
                assertTrue(key, Usage.KEY.matches(key))
            }
        }
    }

    @Test fun freeTextIsRejected() {
        listOf("api:GET /v1/files?path=/sdcard/me.jpg", "screen:home\nowner@example.com", "Owner:home", "api:", "x:" + "a".repeat(121),
            "api:GET /v1/ask \"what's my password\"", "api:GET /v1/x@y")
            .forEach { assertFalse(it, Usage.KEY.matches(it)) }
    }
}
