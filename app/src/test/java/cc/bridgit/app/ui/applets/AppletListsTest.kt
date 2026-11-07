package cc.bridgit.app.ui.applets

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
class AppletListsTest {
    @Test fun settingsLinksRetainDeviceAndAppletWithoutGrantingAuthority() {
        val q = "device=bgi_aaaaaaaaaaaaaaaaaaaaaaaa&applet=pkg.kitchen-cleanliness"
        val expected = "https://bridgit.cc/myapps/?device=bgi_aaaaaaaaaaaaaaaaaaaaaaaa&screen=settings#pkg.kitchen-cleanliness"
        assertEquals(expected, AppletLists.settingsTarget(Uri.parse("https://bridgit.cc/applet-settings/?$q")))
        assertEquals(expected, AppletLists.settingsTarget(Uri.parse("bridgit://settings?$q")))
        for (url in listOf("https://evil.example/applet-settings/?$q", "http://bridgit.cc/applet-settings/?$q", "https://bridgit.cc:123/applet-settings/?$q", "bridgit://settings?$q&device=bgi_bbbbbbbbbbbbbbbbbbbbbbbb", "bridgit://settings?device=bgi_aaaaaaaaaaaaaaaaaaaaaaaa&applet=../dog")) {
            org.junit.Assert.assertNull(url, AppletLists.settingsTarget(Uri.parse(url)))
        }
    }

    @Test fun portalEntryPointsUseTheEmbeddedSignedInScreen() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        for (id in listOf("dog", "pkg.kitchen-cleanliness", "")) {
            val intent = AppletNavigation.portalIntent(context, id)
            assertEquals(cc.bridgit.app.ui.MainActivity::class.java.name, intent.component?.className)
            assertEquals(id, intent.getStringExtra(cc.bridgit.app.ui.MainActivity.EXTRA_OPEN_PORTAL))
            assertEquals(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP, intent.flags)
            org.junit.Assert.assertNull(intent.data)
            org.junit.Assert.assertNull(intent.action)
        }
    }

    @Test fun noPortalsWhenTheAccountHasNoApplets() {
        assertTrue(AppletLists.portals(dogHere = false, dogOnAccount = false, device = "bgi_x").isEmpty())
    }

    @Test fun petMonitorOnThisDeviceOpensThisDevice() {
        val p = AppletLists.portals(dogHere = true, dogOnAccount = true, device = "bgi_abc")
        assertEquals(listOf("dog"), p.map { it.id })
        assertEquals("https://bridgit.cc/myapps/?device=bgi_abc#dog", p[0].url)
    }

    @Test fun petMonitorOnAnotherDeviceOfTheAccountIsListedToo() {
        val p = AppletLists.portals(dogHere = false, dogOnAccount = true, device = "bgi_phone")
        assertEquals(listOf("Pet monitor"), p.map { it.name })
        assertEquals("https://bridgit.cc/myapps/#dog", p[0].url)
    }

    @Test fun packageAppletPortalOnHereOpensItsTabOnThisDevice() {
        val p = AppletLists.portals(dogHere = true, dogOnAccount = true, device = "bgi_abc",
            packagesHere = mapOf("kitchen-cleanliness" to "Kitchen Cleanliness"))
        assertEquals(listOf("dog", "pkg.kitchen-cleanliness"), p.map { it.id })
        assertEquals("Kitchen Cleanliness", p[1].name)
        assertEquals("https://bridgit.cc/myapps/?device=bgi_abc#pkg.kitchen-cleanliness", p[1].url)
    }

    @Test fun packageAppletOnAnotherDeviceOfTheAccountIsListedWithoutDevice() {
        val p = AppletLists.portals(dogHere = false, dogOnAccount = true, device = "bgi_phone",
            accountApplets = setOf("dog", "pkg.kitchen-cleanliness"))
        assertEquals(listOf("Pet monitor", "Kitchen cleanliness"), p.map { it.name })
        assertEquals("https://bridgit.cc/myapps/#pkg.kitchen-cleanliness", p[1].url)
    }

    @Test fun anInstalledPackageKeepsItsOwnNameEvenWithItsPortalOffHere() {
        val p = AppletLists.portals(dogHere = false, dogOnAccount = false, device = "bgi_x",
            accountApplets = setOf("pkg.kitchen-cleanliness"), names = mapOf("kitchen-cleanliness" to "Kitchen Cleanliness"))
        assertEquals(listOf("Kitchen Cleanliness"), p.map { it.name })
    }

    @Test fun aPackageOnHereAndOnTheAccountIsListedOnceForThisDevice() {
        val p = AppletLists.portals(dogHere = false, dogOnAccount = false, device = "bgi_x",
            packagesHere = mapOf("plants" to "Plants"), accountApplets = setOf("pkg.plants"))
        assertEquals(listOf("https://bridgit.cc/myapps/?device=bgi_x#pkg.plants"), p.map { it.url })
    }

    @Test fun packagesFollowThePetMonitorInNameOrderAndBadIdsAreLeftOut() {
        val p = AppletLists.portals(dogHere = true, dogOnAccount = false, device = "bgi_x",
            packagesHere = mapOf("zebra" to "Zebra", "Bad_Id" to "Bad", "x" to "Too short"),
            accountApplets = setOf("pkg.apples", "pkg.UPPER", "something-else"))
        assertEquals(listOf("dog", "pkg.apples", "pkg.zebra"), p.map { it.id })
    }

    @Test fun portalStaysOnBridgitOnly() {
        assertTrue(AppletLists.staysInPortal(Uri.parse("https://bridgit.cc/login/?next=/myapps/")))
        assertTrue(AppletLists.staysInPortal(Uri.parse("https://www.bridgit.cc/myapps/")))
        assertFalse(AppletLists.staysInPortal(Uri.parse("http://bridgit.cc/myapps/")))
        assertFalse(AppletLists.staysInPortal(Uri.parse("https://evilbridgit.cc/")))
        assertFalse(AppletLists.staysInPortal(Uri.parse("https://accounts.google.com/")))
    }

    @Test fun cardsAreTheHtmlSamples() {
        val cards = AppletLists.cards(File("../site-content/public/samples/samples.json").readText())
        assertTrue(cards.size >= 5)
        assertTrue(cards.all { it.html.contains("<") && it.title.isNotBlank() })
        assertFalse(cards.any { it.title.startsWith("HTML card") })
        assertTrue(cards.any { it.id == "html-checklist" })
    }

    @Test fun loginCodeGoesInTheFragment() {
        val code = "A".repeat(43)
        assertEquals("https://bridgit.cc/myapps/?device=bgi_x#dog&login=$code", AppletLists.withLogin("https://bridgit.cc/myapps/?device=bgi_x#dog", code))
        assertEquals("https://bridgit.cc/myapps/#login=$code", AppletLists.withLogin("https://bridgit.cc/myapps/", code))
        assertThrows(IllegalArgumentException::class.java) { AppletLists.withLogin("https://bridgit.cc/myapps/", "short&x=1") }
    }

    @Test fun portalSessionCookieIsRecognised() {
        assertTrue(AppletLists.hasPortalSession("a=1; __Host-bridgit-session=bes_abc"))
        assertFalse(AppletLists.hasPortalSession("__Host-bridgit-session="))
        assertFalse(AppletLists.hasPortalSession("__Host-bridgit-login=x"))
        assertFalse(AppletLists.hasPortalSession(null))
    }
    @org.junit.Test fun portalRenameUpdatesOnlyTheSelectedTrustedApplet() {
        val url = android.net.Uri.parse("https://bridgit.cc/myapps/?device=phone#pkg.chef-helper")
        org.junit.Assert.assertEquals("New kitchen", AppletLists.portalTitle("New kitchen — Bridgit", url, "pkg.chef-helper"))
        org.junit.Assert.assertNull(AppletLists.portalTitle("New kitchen — Bridgit", url, "pkg.insurance-inventory"))
        org.junit.Assert.assertNull(AppletLists.portalTitle("New kitchen — Bridgit", android.net.Uri.parse("https://example.com/#pkg.chef-helper"), "pkg.chef-helper"))
        org.junit.Assert.assertEquals("Portal", AppletLists.portalIdentity("""{"name":"Portal","applet":"pkg.chef-helper"}""", url, "pkg.chef-helper"))
        org.junit.Assert.assertNull(AppletLists.portalTitle("x".repeat(41) + " — Bridgit", url, "pkg.chef-helper"))
    }
    @Test fun staleTitleCannotRenameAnotherPortal() {
        val chef = Uri.parse("https://bridgit.cc/myapps/#pkg.chef-helper")
        org.junit.Assert.assertNull(AppletLists.portalIdentity("""{"name":"Inventory","applet":"pkg.insurance-inventory"}""", chef, "pkg.chef-helper"))
        org.junit.Assert.assertEquals("My kitchen", AppletLists.portalIdentity("""{"name":"My kitchen","applet":"pkg.chef-helper"}""", chef, "pkg.chef-helper"))
        org.junit.Assert.assertNull(AppletLists.portalIdentity("null", chef, "pkg.chef-helper"))
        org.junit.Assert.assertNull(AppletLists.portalIdentity("bad json", chef, "pkg.chef-helper"))
    }
    @Test fun portalIdentityMessagesAreBoundedDisplayMetadata() {
        fun valid(name: Any, applet: String) = cc.bridgit.app.remoteview.PortalBridge.identityValid(JSONObject().put("name", name).put("applet", applet))
        assertTrue(valid("My kitchen", "pkg.chef-helper"))
        assertTrue(valid("Pet monitor", "dog"))
        assertFalse(valid("x".repeat(41), "pkg.chef-helper"))
        assertFalse(valid("Bad\nname", "pkg.chef-helper"))
        assertFalse(valid(42, "pkg.chef-helper"))
        assertFalse(valid("My kitchen", "https://example.com"))
    }
}
