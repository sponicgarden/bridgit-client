package cc.bridgit.app.ssh

import com.jcraft.jsch.HostKeyRepository
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class SshTest {
    private fun draft() = JSONObject().put("host", "example.test").put("name", "Test").put("username", "tester")
        .put("fingerprint", "SHA256:" + "a".repeat(43)).put("confirm_host_key", true).put("password", "secret-password")
        .put("permissions", JSONArray(listOf("exec", "files_read")))
    @Test fun credentialsNeverAppearInPublicInventoryAndRoundTripPrivately() {
        val p = SshProfile.draft(draft(), "agent-a")
        assertFalse(p.publicJson().toString().contains("secret-password")); assertFalse(p.toString().contains("secret-password"))
        assertEquals(p.password, SshProfile.read(p.stored()).password)
        assertEquals(p.permissions, SshProfile.read(p.stored()).permissions)
    }
    @Test fun hostConfirmationAndExplicitPermissionsAndSingleCredentialAreRequired() {
        for (o in listOf(draft().put("confirm_host_key", false), draft().put("permissions", JSONArray()),
            draft().put("permissions", JSONArray(listOf("admin"))), draft().put("private_key", "also-secret"), draft().put("port", 22.5))) {
            assertThrows(IllegalArgumentException::class.java) { SshProfile.draft(o, "a") }
        }
    }
    @Test fun boundedOutputDrainsButDoesNotGrow() {
        val o = SshClient.LimitedOutput(4); o.write("abcdefgh".toByteArray()); o.write(105)
        assertEquals("abcd", o.text()); assertTrue(o.truncated)
    }
    @Test fun everySshEndpointIsPublishedToHttpAndMcp() {
        val published = cc.bridgit.app.api.Endpoints.all
        for (spec in SshModule.ALL) assertTrue(spec.fullPath, published.contains(spec))
        for (spec in SshModule.ALL) assertTrue(spec.fullPath, cc.bridgit.app.api.McpTools.specs.contains(spec))
    }
    @Test fun pathsAreLiteralAndCwdIsQuoted() {
        assertEquals("a\\*\\?\\\\b", SshRules.literal("a*?\\b"))
        assertEquals("'a'\"'\"'b'", SshClient.quote("a'b"))
        assertThrows(IllegalArgumentException::class.java) { SshRules.path("bad\u0000path") }
        assertThrows(IllegalArgumentException::class.java) { SshRules.host("host;command") }
        assertThrows(IllegalArgumentException::class.java) { SshRules.host("ssh://host") }
        assertEquals("2001:db8::1", SshRules.host("2001:db8::1"))
    }
    @Test fun cwdShellQuotingKeepsMetacharactersLiteral() {
        val parent = java.nio.file.Files.createTempDirectory("ssh-quote-").toFile()
        try {
            val target = java.io.File(parent, "x'; touch marker; #").apply { mkdir() }
            val process = ProcessBuilder("/bin/sh", "-c", "cd -- ${SshClient.quote(target.path)} && printf ok").directory(parent).start()
            assertEquals("ok", process.inputStream.bufferedReader().readText())
            assertEquals(0, process.waitFor())
            assertFalse(java.io.File(parent, "marker").exists())
        } finally { parent.deleteRecursively() }
    }
    @Test fun granularGrantsDoNotGrantShellImplicitly() {
        val p = SshProfile.draft(draft().put("permissions", JSONArray(listOf("files_read"))), "a")
        SshModule.requirePermission(p, "files_read")
        assertThrows(Exception::class.java) { SshModule.requirePermission(p, "exec") }
        assertThrows(Exception::class.java) { SshModule.requirePermission(p, "files_write") }
    }
}

/** Optional real protocol suite: launch tools/tests/ssh_fixture.py and set BRIDGIT_SSH_FIXTURE. */
class SshProtocolTest {
    private fun config(): JSONObject {
        val path = System.getenv("BRIDGIT_SSH_FIXTURE")
        org.junit.Assume.assumeTrue("Isolated SSH fixture not running", path != null)
        return JSONObject(java.io.File(path!!).readText())
    }
    private fun profile(c: JSONObject, pin: String = c.getString("fingerprint")) = SshProfile("ssh:test", "test", "Fixture", c.getString("host"), c.getInt("port"),
        "fixture", pin, "fixture-password", "", "", SshProfile.PERMISSIONS)
    @Test fun actualHandshakePinnedAuthenticationExecStdinTruncationAndFailureStatus() {
        val c=config(); val client=SshClient {}; val p=profile(c)
        assertEquals(c.getString("fingerprint"), client.probe(p.host,p.port).getString("fingerprint"))
        assertTrue(client.verify(p).getBoolean("authenticated"))
        val keyProfile = SshProfile("ssh:key", "test", "Key", p.host, p.port, p.username, p.fingerprint, "",
            java.io.File(c.getString("private_key_file")).readText(), "fixture-passphrase", p.permissions)
        assertTrue(client.verify(keyProfile).getBoolean("authenticated"))
        assertEquals("host_key_mismatch", assertThrows(SshClient.Failure::class.java) { client.verify(profile(c,"SHA256:"+"a".repeat(43))) }.reason)
        val out=client.exec(p,JSONObject().put("command","fixture-success"))
        assertEquals(0,out.getInt("exit_code")); assertTrue(out.getString("stdout").contains("hello")); assertTrue(out.getString("stderr").contains("stderr"))
        assertEquals(7,client.exec(p,JSONObject().put("command","fixture-fail")).getInt("exit_code"))
        assertEquals("stdin test",client.exec(p,JSONObject().put("command","fixture-stdin").put("stdin","stdin test")).getString("stdout"))
        val big=client.exec(p,JSONObject().put("command","fixture-output").put("max_output_bytes",100))
        assertEquals(100,big.getString("stdout").length);assertTrue(big.getBoolean("stdout_truncated"))
    }
    @Test fun sftpLiteralNamesChunkUploadDownloadAndMutations() {
        val c=config();val p=profile(c);val client=SshClient {};val dir="roundtrip-${System.nanoTime()}*?\\name[1]"
        fun file(action:String,path:String,extra:JSONObject=JSONObject()):JSONObject = client.files(p,extra.put("action",action).put("path",path).put("confirm",true))
        file("mkdir",dir)
        val path="$dir/file*?\\name[1]"
        file("write",path,JSONObject().put("data_base64",Base64.getEncoder().encodeToString("hello".toByteArray())))
        file("write",path,JSONObject().put("write_mode","append").put("data_base64",Base64.getEncoder().encodeToString(" world".toByteArray())))
        assertEquals(11L,file("stat",path).getJSONObject("attributes").getLong("size"))
        val read=file("read",path,JSONObject().put("offset",6).put("max_bytes",5))
        assertEquals("world",String(Base64.getDecoder().decode(read.getString("data_base64"))))
        assertTrue(read.getBoolean("eof"))
        assertFalse(file("read",path,JSONObject().put("max_bytes",4)).getBoolean("eof"))
        assertEquals(1,file("list",dir).getJSONArray("entries").length())
        file("chmod",path,JSONObject().put("mode","600"));file("rename",path,JSONObject().put("destination","$dir/renamed"))
        file("remove","$dir/renamed");file("rmdir",dir)
    }
    @Test fun deadlineAndRevocationDisconnectInsteadOfReportingSuccess() {
        val p=profile(config())
        assertEquals("timeout_outcome_unknown",assertThrows(SshClient.Failure::class.java) {
            SshClient {}.exec(p,JSONObject().put("command","fixture-sleep").put("timeout_seconds",1))
        }.reason)
        val until=System.nanoTime()+600_000_000
        assertThrows(SshClient.Failure::class.java) {
            SshClient { check(System.nanoTime()<until) }.exec(p,JSONObject().put("command","fixture-sleep"))
        }
    }
}
