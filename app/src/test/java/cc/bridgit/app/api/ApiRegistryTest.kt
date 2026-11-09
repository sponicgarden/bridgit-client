package cc.bridgit.app.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A0120: every API group has a fixed name and number, and the registry lists every call with API access on by default. */
class ApiRegistryTest {
    private val groups = ApiAccess.groups(Endpoints.all, emptyMap(), cc.bridgit.app.platform.Commands.all)

    @Test fun everyApiGroupHasAUniqueNumber() {
        assertEquals("numbers are never reused", ApiAccess.NUMBERS.size, ApiAccess.NUMBERS.toSet().size)
        val missing = Endpoints.all.map { it.capability }.toSet().filter { ApiAccess.number(it) == null }
        assertEquals("append these to ApiAccess.NUMBERS", emptyList<String>(), missing)
        assertEquals(groups.map { it.number }.distinct(), groups.map { it.number })
    }

    @Test fun numbersAreFrozen() {
        // Numbers handed out on 9 Nov 2026 never move: renumbering would break every reference to them.
        assertEquals(1, ApiAccess.number("adb"))
        assertEquals(7, ApiAccess.number("camera"))
        assertEquals(14, ApiAccess.number("feedback"))
        assertEquals(38, ApiAccess.number("platform"))
        assertEquals(45, ApiAccess.number("settings"))
        assertEquals(50, ApiAccess.number("status"))
        assertEquals(56, ApiAccess.number("weather"))
    }

    @Test fun registryListsEveryCallUnderTheOnePermission() {
        val json = ApiAccess.registry(groups, null)
        val apis = json.getJSONArray("apis")
        assertEquals(groups.size, apis.length())
        val calls = (0 until apis.length()).flatMap { i -> apis.getJSONObject(i).getJSONArray("calls").let { a -> (0 until a.length()).map(a::getString) } }
        Endpoints.all.forEach { assertTrue("${it.method} ${it.fullPath}", "${it.method} ${it.fullPath}" in calls) }
        assertTrue("tool phone.status" in calls)
        for (i in 0 until apis.length()) {
            val a = apis.getJSONObject(i)
            assertEquals("api_access", a.getString("permission"))
            assertTrue(a.getBoolean("default"))
        }
        assertEquals(false, json.getJSONObject("permission").getBoolean("granular"))
        assertTrue(json.getJSONObject("permission").getBoolean("you"))
    }
}
