package cc.bridgit.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TailscaleApkTest {
    @Test fun parsesTheRealIndex() {
        val r = TailscaleApk.parseIndex("""{"Tarballs":{},"APKs":{"universal":"tailscale-android-universal-1.104.5.apk"},"APKsVersion":"1.104.5"}""")!!
        assertEquals("1.104.5", r.version)
        assertEquals("https://pkgs.tailscale.com/stable/tailscale-android-universal-1.104.5.apk", r.apkUrl)
        assertEquals("https://pkgs.tailscale.com/stable/tailscale-android-universal-1.104.5.apk.sha256", r.shaUrl)
        assertTrue(TailscaleApk.isSafeUrl(r.apkUrl))
        assertTrue(TailscaleApk.isSafeUrl(r.shaUrl))
    }

    @Test fun refusesOddFileNames() {
        for (f in listOf("../evil.apk", "tailscale-android-universal-1.2.apk/../x.apk", "other.apk", "tailscale-android-universal-1.2.apk?x=1", ""))
            assertNull(f, TailscaleApk.parseIndex("""{"APKs":{"universal":"$f"}}"""))
        assertNull(TailscaleApk.parseIndex("not json"))
        assertNull(TailscaleApk.parseIndex(null))
    }

    @Test fun urlRules() {
        assertTrue(TailscaleApk.isSafeUrl(TailscaleApk.INDEX_URL))
        assertFalse(TailscaleApk.isSafeUrl("http://pkgs.tailscale.com/stable/tailscale-android-universal-1.104.5.apk"))
        assertFalse(TailscaleApk.isSafeUrl("https://evil.com/stable/tailscale-android-universal-1.104.5.apk"))
        assertFalse(TailscaleApk.isSafeUrl("https://pkgs.tailscale.com.evil.com/stable/tailscale-android-universal-1.104.5.apk"))
        assertFalse(TailscaleApk.isSafeUrl("https://pkgs.tailscale.com/unstable/tailscale-android-universal-1.104.5.apk"))
        assertFalse(TailscaleApk.isSafeUrl("https://pkgs.tailscale.com/stable/tailscale-android-universal-1.104.5.apk?x"))
        assertFalse(TailscaleApk.isSafeUrl("https://u@pkgs.tailscale.com/stable/tailscale-android-universal-1.104.5.apk"))
        assertFalse(TailscaleApk.isSafeUrl("https://pkgs.tailscale.com/stable/../tailscale-android-universal-1.104.5.apk"))
    }

    @Test fun sha256File() {
        val sha = "a517f94006b90a9dbd7383221cbd05ff4acf5ea680335b9b0318e3fbf086d4c9"
        assertEquals(sha, TailscaleApk.parseSha256("$sha\n"))
        assertEquals(sha, TailscaleApk.parseSha256("${sha.uppercase()}  tailscale.apk"))
        assertNull(TailscaleApk.parseSha256("abc"))
        assertNull(TailscaleApk.parseSha256("<html>"))
        assertNull(TailscaleApk.parseSha256(null))
    }

    @Test fun signingKeyPin() {
        assertTrue(TailscaleApk.signedByTailscale(listOf(TailscaleApk.CERT_SHA256)))
        assertTrue(TailscaleApk.signedByTailscale(listOf(TailscaleApk.CERT_SHA256.uppercase())))
        assertFalse(TailscaleApk.signedByTailscale(emptyList()))
        assertFalse(TailscaleApk.signedByTailscale(listOf("00".repeat(32))))
        assertFalse(TailscaleApk.signedByTailscale(listOf(TailscaleApk.CERT_SHA256, "00".repeat(32))))
    }

    @Test fun percent() {
        assertEquals(-1, TailscaleApk.percent(5, -1))
        assertEquals(50, TailscaleApk.percent(50, 100))
        assertEquals(100, TailscaleApk.percent(150, 100))
    }
}
