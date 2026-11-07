package cc.bridgit.app.update

import cc.bridgit.app.update.UpdateLogic.Compare
import cc.bridgit.app.update.UpdateLogic.Parsed
import cc.bridgit.app.update.UpdateLogic.Problem
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Ways the update logic can fail, and the test that covers each:
 *  1. Manifest is not JSON, or misses a field                       -> parse_rejectsGarbageAndMissingFields
 *  2. Manifest is for another package (debug build vs release)      -> parse_rejectsOtherPackage
 *  3. version_code is a string, a fraction, zero or negative        -> parse_rejectsBadVersionCode
 *  4. version_name is empty or carries odd characters               -> parse_rejectsBadVersionName
 *  5. url is http, another host, a look-alike host, has userinfo,
 *     a port, a query, a fragment, `..`, encoded bytes, outside
 *     /bridget/, or isn't an .apk                                  -> apkUrl_* tests
 *  6. sha256 is short, long, non-hex or missing                     -> sha256_format, parse_rejectsBadSha
 *  7. size_bytes missing, zero, negative or nonnumeric             -> parse_rejectsBadSize
 *  8. Version comparison: equal, older, newer, zero/negative        -> compare_*
 *  9. Retrying deterministic errors (404) or not retrying 5xx       -> transientStatus
 * 10. Following a 3xx                                               -> redirectStatus
 * 11. Auto-check firing too often, never, or stuck after a clock
 *     change                                                        -> autoCheck_*
 * 12. Archive with another package, another versionCode, or not
 *     newer than installed                                          -> archive_*
 * 13. "Up to date" stays forever, or a decision screen closes itself -> autoClose_*, countdown_*
 * 13. Signing certificates: different, subset, superset, empty,
 *     malformed, or same digests in another case or with colons     -> certs_*
 * 14. Log line leaking the path or query                            -> hostOf_onlyHost
 * 15. Huge notes text                                               -> parse_truncatesNotes
 * 16. A 403 from Cloudflare read as "up to date" instead of an error -> statusProblem
 * 17. A default User-Agent (Cloudflare 403s some) or header injection
 *     through an odd version string                                 -> userAgent_*
 */
class UpdateLogicTest {
    private val pkg = "cc.bridgit.app"
    private val sha = "a".repeat(64)
    private val apk = "https://${UpdateLogic.HOST}/bridget/bridget-1.4.0.apk"

    private fun manifest(edit: JSONObject.() -> Unit = {}): String = JSONObject()
        .put("package", pkg).put("version_name", "1.4.0").put("version_code", 5)
        .put("url", apk).put("sha256", sha).put("size_bytes", 12_345_678)
        .put("published_at", "2026-10-27T10:00:00Z").put("notes", "New icon. In-app updates.")
        .apply(edit).toString()

    private fun refused(body: String): Problem = (UpdateLogic.parseManifest(body, pkg) as Parsed.Refused).problem

    @Test fun parse_ok() {
        val m = (UpdateLogic.parseManifest(manifest(), pkg) as Parsed.Ok).manifest
        assertEquals(5L, m.versionCode)
        assertEquals("1.4.0", m.versionName)
        assertEquals(apk, m.url)
        assertEquals(12_345_678L, m.sizeBytes)
        assertEquals("New icon. In-app updates.", m.notes)
    }

    @Test fun parse_upperCaseShaIsNormalised() {
        val m = (UpdateLogic.parseManifest(manifest { put("sha256", "AB".repeat(32)) }, pkg) as Parsed.Ok).manifest
        assertEquals("ab".repeat(32), m.sha256)
    }

    @Test fun parse_rejectsGarbageAndMissingFields() {
        assertEquals(Problem.BAD_MANIFEST, refused("<html>not found</html>"))
        assertEquals(Problem.BAD_MANIFEST, refused(""))
        assertEquals(Problem.BAD_MANIFEST, refused("[]"))
        assertEquals(Problem.BAD_MANIFEST, refused(manifest { remove("package") }))
        assertEquals(Problem.BAD_MANIFEST, refused(manifest { remove("version_code") }))
        assertEquals(Problem.UNSAFE_URL, refused(manifest { remove("url") }))
        assertEquals(Problem.BAD_SHA256, refused(manifest { remove("sha256") }))
    }

    @Test fun parse_rejectsOtherPackage() {
        assertEquals(Problem.WRONG_PACKAGE, refused(manifest { put("package", "cc.bridgit.app.debug") }))
        assertEquals(Problem.WRONG_PACKAGE, UpdateLogic.parseManifest(manifest(), "cc.bridgit.app.debug")
            .let { (it as Parsed.Refused).problem })
    }

    @Test fun parse_rejectsBadVersionCode() {
        assertEquals(Problem.BAD_MANIFEST, refused(manifest { put("version_code", "5") }))
        assertEquals(Problem.BAD_MANIFEST, refused(manifest { put("version_code", 5.5) }))
        assertEquals(Problem.BAD_MANIFEST, refused(manifest { put("version_code", 0) }))
        assertEquals(Problem.BAD_MANIFEST, refused(manifest { put("version_code", -3) }))
        assertEquals(Problem.BAD_MANIFEST, refused(manifest { put("version_code", true) }))
        assertTrue(UpdateLogic.parseManifest(manifest { put("version_code", 5_000_000_000L) }, pkg) is Parsed.Ok)
    }

    @Test fun parse_rejectsBadVersionName() {
        assertEquals(Problem.BAD_MANIFEST, refused(manifest { put("version_name", "") }))
        assertEquals(Problem.BAD_MANIFEST, refused(manifest { put("version_name", "1.4.0 <b>now</b>") }))
        assertEquals(Problem.BAD_MANIFEST, refused(manifest { put("version_name", "1".repeat(40)) }))
    }

    @Test fun parse_rejectsBadSha() {
        assertEquals(Problem.BAD_SHA256, refused(manifest { put("sha256", "abc") }))
        assertEquals(Problem.BAD_SHA256, refused(manifest { put("sha256", "g".repeat(64)) }))
    }

    @Test fun parse_rejectsBadSize() {
        assertEquals(Problem.BAD_SIZE, refused(manifest { remove("size_bytes") }))
        assertEquals(Problem.BAD_SIZE, refused(manifest { put("size_bytes", 0) }))
        assertEquals(Problem.BAD_SIZE, refused(manifest { put("size_bytes", -1) }))
        assertEquals(Problem.BAD_SIZE, refused(manifest { put("size_bytes", "123") }))
    }

    @Test fun parse_acceptsSizesAboveFormerLimit() {
        for (size in listOf(150L * 1024 * 1024 + 1, 512L * 1024 * 1024, Long.MAX_VALUE)) {
            val parsed = UpdateLogic.parseManifest(manifest { put("size_bytes", size) }, pkg)
            assertTrue(parsed is Parsed.Ok)
            assertEquals(size, (parsed as Parsed.Ok).manifest.sizeBytes)
        }
    }

    @Test fun parse_truncatesNotes() {
        val m = (UpdateLogic.parseManifest(manifest { put("notes", "x".repeat(10_000)) }, pkg) as Parsed.Ok).manifest
        assertEquals(UpdateLogic.MAX_NOTES_CHARS, m.notes!!.length)
        val none = (UpdateLogic.parseManifest(manifest { remove("notes") }, pkg) as Parsed.Ok).manifest
        assertNull(none.notes)
    }

    @Test fun apkUrl_accepts() {
        assertTrue(UpdateLogic.isSafeApkUrl(apk))
        assertTrue(UpdateLogic.isSafeApkUrl("https://${UpdateLogic.HOST}:443/bridget/bridget-1.4.0.apk"))
        assertTrue(UpdateLogic.isSafeManifestUrl(UpdateLogic.MANIFEST_URL))
    }

    @Test fun apkUrl_rejectsSchemeHostAndPort() {
        val h = UpdateLogic.HOST
        listOf(
            "", "  $apk",
            "http://$h/bridget/a.apk",
            "ftp://$h/bridget/a.apk",
            "https://evil.example/bridget/a.apk",
            "https://$h.evil.example/bridget/a.apk",
            "https://evil.$h/bridget/a.apk",
            "https://user@$h/bridget/a.apk",
            "https://user:pw@$h/bridget/a.apk",
            "https://$h:8443/bridget/a.apk",
            "https://$h:80/bridget/a.apk",
            "//$h/bridget/a.apk",
            "/bridget/a.apk",
        ).forEach { assertFalse(it, UpdateLogic.isSafeApkUrl(it)) }
        assertFalse(UpdateLogic.isSafeApkUrl(null))
    }

    @Test fun apkUrl_rejectsPath() {
        val h = UpdateLogic.HOST
        listOf(
            "https://$h/other/a.apk",
            "https://$h/bridgetx/a.apk",
            "https://$h/bridget/",
            "https://$h/bridget/.apk",
            "https://$h/bridget/a.apk.exe",
            "https://$h/bridget/a.APK",
            "https://$h/bridget/a.apk?x=1",
            "https://$h/bridget/a.apk#frag",
            "https://$h/bridget/../mobile/a.apk",
            "https://$h/bridget/./a.apk",
            "https://$h/bridget//a.apk",
            "https://$h/bridget/%2e%2e/a.apk",
            "https://$h/bridget/a%2Eapk",
            "https://$h/bridget/a b.apk",
            "https://$h/bridget/latest.json",
        ).forEach { assertFalse(it, UpdateLogic.isSafeApkUrl(it)) }
        assertFalse(UpdateLogic.isSafeManifestUrl(apk))
    }

    @Test fun sha256_format() {
        assertTrue(UpdateLogic.isValidSha256(sha))
        assertTrue(UpdateLogic.isValidSha256("0123456789ABCDEFabcdef".padEnd(64, '0')))
        assertFalse(UpdateLogic.isValidSha256(null))
        assertFalse(UpdateLogic.isValidSha256("a".repeat(63)))
        assertFalse(UpdateLogic.isValidSha256("a".repeat(65)))
        assertFalse(UpdateLogic.isValidSha256(" " + "a".repeat(63)))
        assertFalse(UpdateLogic.isValidSha256("z".repeat(64)))
    }

    @Test fun compare_all() {
        assertEquals(Compare.NEWER, UpdateLogic.compare(4, 5))
        assertEquals(Compare.CURRENT, UpdateLogic.compare(5, 5))
        assertEquals(Compare.LOCAL_AHEAD, UpdateLogic.compare(6, 5))
        assertEquals(Compare.UNCOMPARABLE, UpdateLogic.compare(0, 5))
        assertEquals(Compare.UNCOMPARABLE, UpdateLogic.compare(5, 0))
        assertEquals(Compare.UNCOMPARABLE, UpdateLogic.compare(-1, 5))
        assertEquals(Compare.NEWER, UpdateLogic.compare(Int.MAX_VALUE.toLong(), Int.MAX_VALUE + 1L))
    }

    @Test fun transientStatus() {
        listOf(408, 429, 500, 502, 503, 599).forEach { assertTrue("$it", UpdateLogic.isTransientHttpStatus(it)) }
        listOf(200, 301, 400, 401, 403, 404, 410, 600, -1).forEach { assertFalse("$it", UpdateLogic.isTransientHttpStatus(it)) }
    }

    @Test fun redirectStatus() {
        listOf(300, 301, 302, 303, 307, 308).forEach { assertTrue("$it", UpdateLogic.isRedirect(it)) }
        listOf(200, 204, 400, 404, 500).forEach { assertFalse("$it", UpdateLogic.isRedirect(it)) }
    }

    @Test fun statusProblem() {
        assertEquals(Problem.FORBIDDEN, UpdateLogic.problemForStatus(403))
        assertEquals(Problem.REDIRECT, UpdateLogic.problemForStatus(302))
        assertEquals(Problem.HTTP_STATUS, UpdateLogic.problemForStatus(404))
        assertEquals(Problem.HTTP_STATUS, UpdateLogic.problemForStatus(500))
    }

    @Test fun userAgent_named() {
        assertEquals("Bridgit/1.4.0 (Android 17)", UpdateLogic.userAgent("1.4.0", "17"))
    }

    @Test fun userAgent_sanitised() {
        val ua = UpdateLogic.userAgent("1.4.0\r\nX-Evil: 1", "")
        assertFalse(ua.contains('\r') || ua.contains('\n'))
        assertTrue(ua.startsWith("Bridgit/1.4.0"))
        assertTrue(ua.endsWith("(Android ?)"))
    }

    @Test fun autoCheck_throttle() {
        val now = 1_800_000_000_000L
        assertTrue(UpdateLogic.isAutoCheckDue(now, 0))
        assertTrue(UpdateLogic.isAutoCheckDue(now, -5))
        assertFalse(UpdateLogic.isAutoCheckDue(now, now))
        assertFalse(UpdateLogic.isAutoCheckDue(now, now - UpdateLogic.DAY_MS + 1))
        assertTrue(UpdateLogic.isAutoCheckDue(now, now - UpdateLogic.DAY_MS))
        assertTrue(UpdateLogic.isAutoCheckDue(now, now - 10 * UpdateLogic.DAY_MS))
    }

    @Test fun autoCheck_clockWentBackwards() {
        val now = 1_800_000_000_000L
        assertTrue(UpdateLogic.isAutoCheckDue(now, now + 60_000))
    }

    private val m = (UpdateLogic.parseManifest(manifest(), pkg) as Parsed.Ok).manifest

    @Test fun archive_ok() = assertNull(UpdateLogic.checkArchive(pkg, 5, m, pkg, 4))

    @Test fun archive_refusals() {
        assertEquals(Problem.NOT_AN_APK, UpdateLogic.checkArchive(null, 5, m, pkg, 4))
        assertEquals(Problem.ARCHIVE_PACKAGE, UpdateLogic.checkArchive("com.evil", 5, m, pkg, 4))
        assertEquals(Problem.ARCHIVE_PACKAGE, UpdateLogic.checkArchive("$pkg.debug", 5, m, pkg, 4))
        assertEquals(Problem.ARCHIVE_VERSION, UpdateLogic.checkArchive(pkg, 6, m, pkg, 4))
        assertEquals(Problem.ARCHIVE_VERSION, UpdateLogic.checkArchive(pkg, 4, m, pkg, 4))
        assertEquals(Problem.NOT_NEWER, UpdateLogic.checkArchive(pkg, 5, m, pkg, 5))
        assertEquals(Problem.NOT_NEWER, UpdateLogic.checkArchive(pkg, 5, m, pkg, 9))
    }

    private val c1 = "6bbd482d18efdc58ca28de8358405602ebb0405b479cabd36b37ff8242042f61"
    private val c2 = "1".repeat(64)

    @Test fun certs_match() {
        assertTrue(UpdateLogic.certSetsMatch(listOf(c1), listOf(c1)))
        assertTrue(UpdateLogic.certSetsMatch(listOf(c1.uppercase()), listOf(c1)))
        assertTrue(UpdateLogic.certSetsMatch(listOf(c1.chunked(2).joinToString(":")), listOf(c1)))
        assertTrue(UpdateLogic.certSetsMatch(listOf(c1, c2), listOf(c2, c1)))
        assertTrue(UpdateLogic.certSetsMatch(listOf(c1, c1), listOf(c1)))
    }

    @Test fun certs_mismatch() {
        assertFalse(UpdateLogic.certSetsMatch(listOf(c2), listOf(c1)))
        assertFalse(UpdateLogic.certSetsMatch(listOf(c1), listOf(c1, c2)))
        assertFalse(UpdateLogic.certSetsMatch(listOf(c1, c2), listOf(c1)))
        assertFalse(UpdateLogic.certSetsMatch(emptyList(), listOf(c1)))
        assertFalse(UpdateLogic.certSetsMatch(listOf(c1), emptyList()))
        assertFalse(UpdateLogic.certSetsMatch(emptyList(), emptyList()))
        assertFalse(UpdateLogic.certSetsMatch(listOf(c1, "nothex"), listOf(c1)))
        assertFalse(UpdateLogic.certSetsMatch(listOf("x"), listOf("x")))
    }

    @Test fun hostOf_onlyHost() {
        assertEquals(UpdateLogic.HOST, UpdateLogic.hostOf("$apk?token=secret"))
        assertEquals("?", UpdateLogic.hostOf("not a url"))
        assertEquals("?", UpdateLogic.hostOf(null))
    }

    // 13. "Up to date" must fade away by itself after 10 s; anything that needs a decision must not.
    @Test
    fun autoClose_onlyNothingToDoScreens() {
        assertEquals(10_000L, UpdateLogic.autoCloseAfterMs(UpdateLogic.Screen.UP_TO_DATE))
        assertEquals(10_000L, UpdateLogic.autoCloseAfterMs(UpdateLogic.Screen.LOCAL_AHEAD))
        for (s in listOf(UpdateLogic.Screen.CHECKING, UpdateLogic.Screen.NEWER, UpdateLogic.Screen.DOWNLOADING,
            UpdateLogic.Screen.READY, UpdateLogic.Screen.ERROR, UpdateLogic.Screen.DEBUG)) {
            assertNull("$s must wait for the owner", UpdateLogic.autoCloseAfterMs(s))
        }
        assertTrue("fade is short but visible", UpdateLogic.FADE_MS in 300L..1500L)
    }

    @Test
    fun countdown_fullToEmptyAndClamped() {
        assertEquals(1f, UpdateLogic.countdownFraction(0), 0.0001f)
        assertEquals(0.5f, UpdateLogic.countdownFraction(5_000), 0.0001f)
        assertEquals(0f, UpdateLogic.countdownFraction(10_000), 0.0001f)
        assertEquals(0f, UpdateLogic.countdownFraction(60_000), 0.0001f)
        assertEquals(1f, UpdateLogic.countdownFraction(-50), 0.0001f) // clock oddities never overfill
        assertEquals(0f, UpdateLogic.countdownFraction(10, totalMs = 0), 0.0001f)
        assertEquals(0.75f, UpdateLogic.countdownFraction(5_000, totalMs = 20_000), 0.0001f)
    }
}
