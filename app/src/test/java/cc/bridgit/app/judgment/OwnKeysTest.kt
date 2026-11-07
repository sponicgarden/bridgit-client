package cc.bridgit.app.judgment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OwnKeysTest {
    private fun provider(id: String) = OwnKeys.PROVIDERS.first { it.id == id }

    @Test fun `the route's own model comes before OpenRouter`() {
        assertEquals("typesafe", OwnKeys.choose("jev", setOf("openrouter", "typesafe")))
        assertEquals("cloudflare", OwnKeys.choose("clef", setOf("openrouter", "cloudflare", "typesafe")))
        assertEquals("openrouter", OwnKeys.choose("jev", setOf("openrouter", "cloudflare")))
        assertEquals("openrouter", OwnKeys.choose("clef", setOf("openrouter")))
        assertNull(OwnKeys.choose("clef", setOf("typesafe")))
        assertNull(OwnKeys.choose("jev", emptySet()))
    }

    @Test fun `route names come from backend routes`() {
        assertEquals("jev", OwnKeys.routeName(Jev.JEV_ROUTE))
        assertEquals("clef", OwnKeys.routeName(Jev.CLEF_ROUTE))
    }

    @Test fun `typed fields are checked and joined`() {
        assertEquals("sk-or-v1-abcdef", OwnKeys.secretOf(provider("openrouter"), listOf("  sk-or-v1-abcdef ")))
        assertNull(OwnKeys.secretOf(provider("openrouter"), listOf("short")))
        assertNull(OwnKeys.secretOf(provider("openrouter"), listOf("has a space in it")))
        val acct = "0123456789abcdef0123456789abcdef"
        assertEquals("$acct:token-123456", OwnKeys.secretOf(provider("cloudflare"), listOf(acct, "token-123456")))
        assertNull(OwnKeys.secretOf(provider("cloudflare"), listOf("not-an-account", "token-123456")))
        assertNull(OwnKeys.secretOf(provider("cloudflare"), listOf(acct)))
    }

    @Test fun `only the last four characters are shown`() {
        assertEquals("••••wxyz", OwnKeys.masked("sk-or-v1-stuvwxyz"))
    }

    @Test fun `the backend's out-of-allowance answers trigger the fallback`() {
        for (r in listOf("spend_cap", "rate_limited", "clef_quota"))
            assertEquals(r, runCatching { Jev.parse(429, """{"error":"$r","message":"m"}""") }.exceptionOrNull().let { (it as Jev.Failure).reason }.also { assert(it in OwnKeys.FALLBACK_REASONS) })
    }

    @Test fun `the answer says which key paid`() {
        val a = Jev.parse(200, """{"model":"openrouter:z-ai/glm-5.3-flash","answers":{"x":{"type":"noul","noul":0.4}},"key":"openrouter"}""")
        assertEquals("openrouter", a.key)
        assertEquals("team", Jev.parse(200, """{"model":"jev","answers":{}}""").key)
    }
}
