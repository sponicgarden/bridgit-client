package cc.bridgit.app.update

import cc.bridgit.app.update.AdbInstallLogic.JobState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Ways the no-confirm ADB install can go wrong, and the test that covers each:
 *  1. Used without Bridgit's own ADB link, or after Revoke Super Powers   -> available_*
 *  2. A package, class or path with shell characters reaches the command  -> unsafe_*
 *  3. Installing a file that isn't byte-for-byte the verified one         -> verify_*
 *  4. The install job dies with Bridgit, or blocks the ADB stream         -> install_detachedAndQuiet
 *  5. A failed install read as success (or still running read as failed)  -> log_*
 */
class AdbInstallLogicTest {
    private val pkg = "cc.bridgit.app.test_claude_silent_update"
    private val comp = "$pkg/cc.bridgit.app.ui.MainActivity"
    private val sha = "0123456789abcdef".repeat(4)

    @Test fun available_onlyWithOwnLinkNotRevoked() {
        assertTrue(AdbInstallLogic.available(privateControl = true, paired = true, revoked = false))
        assertFalse(AdbInstallLogic.available(privateControl = false, paired = true, revoked = false))
        assertFalse(AdbInstallLogic.available(privateControl = true, paired = false, revoked = false))
        assertFalse(AdbInstallLogic.available(privateControl = true, paired = true, revoked = true))
    }

    @Test fun paths_inShellTmp() {
        val p = AdbInstallLogic.paths(pkg, 700)!!
        assertEquals("/data/local/tmp/bridgit-update-$pkg-700.apk", p.apk)
        assertEquals("/data/local/tmp/bridgit-update-$pkg-700.log", p.log)
        assertNull(AdbInstallLogic.paths(pkg, 0))
    }

    @Test fun unsafe_packageAndComponent() {
        for (bad in listOf("", "cc", "cc.bridgit.app;rm -rf /", "cc.bridgit.'app'", "cc..app", "cc.bridgit.app ", "1cc.app", "cc.bridgit.app\$x"))
            assertNull(bad, AdbInstallLogic.paths(bad, 5))
        val paths = AdbInstallLogic.paths(pkg, 5)!!
        for (bad in listOf("$pkg/a b", "$pkg/a'b", "$pkg/a;b", "other.pkg/cc.X", "$pkg/cc.X/y", "$pkg/", "$pkg/cc.X\$Inner", "$pkg/.Main"))
            assertNull(bad, AdbInstallLogic.installCommand(pkg, paths, bad))
        assertNotNull(AdbInstallLogic.installCommand(pkg, paths, comp))
    }

    @Test fun unsafe_pathsRefused() {
        assertNull(AdbInstallLogic.writeCommand("/sdcard/x.apk", 10))
        assertNull(AdbInstallLogic.writeCommand("/data/local/tmp/bridgit-update-../../x.apk", 10))
        assertNull(AdbInstallLogic.writeCommand("/data/local/tmp/bridgit-update-a'b.apk", 10))
        assertNull(AdbInstallLogic.writeCommand("/data/local/tmp/bridgit-update-a.apk", 0))
        assertNull(AdbInstallLogic.verifyCommand("/data/local/tmp/other.apk"))
        assertNull(AdbInstallLogic.installCommand(pkg, AdbInstallLogic.Paths("/tmp/x.apk", "/tmp/x.log"), comp))
    }

    @Test fun write_readsExactSizeThenMarks() {
        val p = AdbInstallLogic.paths(pkg, 5)!!
        val cmd = AdbInstallLogic.writeCommand(p.apk, 1234)!!
        assertTrue(cmd.contains("head -c 1234 > '${p.apk}'"))
        assertTrue(cmd.endsWith("echo ${AdbInstallLogic.WROTE_MARKER}"))
    }

    @Test fun verify_sizeAndShaMustBothMatch() {
        val out = "1234\n$sha  /data/local/tmp/bridgit-update-x.apk\n"
        assertTrue(AdbInstallLogic.verifyMatches(out, 1234, sha))
        assertTrue(AdbInstallLogic.verifyMatches(out, 1234, sha.uppercase()))
        assertFalse(AdbInstallLogic.verifyMatches(out, 1235, sha))
        assertFalse(AdbInstallLogic.verifyMatches(out, 1234, "f" + sha.drop(1)))
        assertFalse(AdbInstallLogic.verifyMatches("1234\n", 1234, sha))
        assertFalse(AdbInstallLogic.verifyMatches("", 1234, sha))
        assertFalse(AdbInstallLogic.verifyMatches("stat: No such file\n$sha  x", 1234, sha))
    }

    @Test fun install_detachedAndQuiet() {
        val p = AdbInstallLogic.paths(pkg, 5)!!
        val cmd = AdbInstallLogic.installCommand(pkg, p, comp)!!
        assertTrue(cmd.contains("setsid nohup sh -c '"))
        assertTrue(cmd.endsWith("> /dev/null 2>&1 < /dev/null &"))
        assertTrue(cmd.contains("pm install -r -i $pkg ${p.apk} > ${p.log} 2>&1"))
        assertTrue(cmd.contains("[ \$rc -eq 0 ] && am start -n $comp"))
        assertTrue(cmd.contains("rm -f ${p.apk}"))
        // The inner script is one single-quoted string: no other single quotes inside it.
        val inner = cmd.substringAfter("sh -c '").substringBeforeLast("' > /dev/null")
        assertFalse(inner.contains('\''))
    }

    @Test fun log_states() {
        val m = AdbInstallLogic.RC_MARKER
        assertEquals(JobState.Running, AdbInstallLogic.parseLog(null))
        assertEquals(JobState.Running, AdbInstallLogic.parseLog(""))
        assertEquals(JobState.Running, AdbInstallLogic.parseLog("Performing Streamed Install\n"))
        assertEquals(JobState.Installed, AdbInstallLogic.parseLog("Performing Streamed Install\nSuccess\n${m}0\n"))
        assertEquals(JobState.Failed("INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match"),
            AdbInstallLogic.parseLog("Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match]\n${m}1\n"))
        assertEquals(JobState.Failed("exit 1"), AdbInstallLogic.parseLog("Error: something\n${m}1\n"))
        // Exit 0 without "Success" is not trusted.
        assertEquals(JobState.Failed("exit 0"), AdbInstallLogic.parseLog("${m}0\n"))
    }
}
