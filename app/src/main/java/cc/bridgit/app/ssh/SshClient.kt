package cc.bridgit.app.ssh

import com.jcraft.jsch.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.Socket
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Every connection is pinned, bounded and closed. No process, ssh-agent, forwarding or global JSch config. */
class SshClient(private val check: () -> Unit) {
    class Failure(val reason: String, cause: Throwable? = null) : Exception(reason, cause)
    class Pins(private val expected: String?) : HostKeyRepository {
        var observed: String? = null
        var algorithm: String? = null
        override fun check(host: String, key: ByteArray): Int {
            observed = fingerprint(key); algorithm = HostKey(host, key).type
            return if (expected != null && MessageDigest.isEqual(expected.toByteArray(), observed!!.toByteArray())) HostKeyRepository.OK else HostKeyRepository.CHANGED
        }
        override fun add(key: HostKey?, ui: UserInfo?) = Unit
        override fun remove(host: String?, type: String?) = Unit
        override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
        override fun getKnownHostsRepositoryID() = "Bridgit pinned SSH identity"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }
    private fun <T> connect(host: String, port: Int, profile: SshProfile?, timeout: Int, block: (Session, Pins) -> T): T {
        val target = "ssh://${if (':' in host) "[$host]" else host}:$port/"
        val started = System.currentTimeMillis(); var success = false
        cc.bridgit.app.outbound.Outbound.check(target, "SSH", "ssh")
        try { return connectInternal(host, port, profile, timeout, block).also { success = true } }
        finally { cc.bridgit.app.outbound.Outbound.record(target, "SSH", "ssh", status = if (success) 200 else 0,
            error = if (success) null else "ssh_operation_failed", startedAt = started,
            hint = cc.bridgit.app.outbound.Outbound.Hint("SSH", "Owner-authorized remote operation; content omitted")) }
    }
    private fun <T> connectInternal(host: String, port: Int, profile: SshProfile?, timeout: Int, block: (Session, Pins) -> T): T {
        check()
        val pins = Pins(profile?.fingerprint)
        val jsch = JSch().apply { setHostKeyRepository(pins) }
        if (profile != null && profile.privateKey.isNotEmpty()) {
            val key = profile.privateKey.toByteArray(); val phrase = profile.passphrase.toByteArray()
            try { jsch.addIdentity("bridgit", key, null, phrase) } catch (_: Exception) { throw Failure("invalid_private_key") }
            finally { key.fill(0); phrase.fill(0) }
        }
        val session = jsch.getSession(profile?.username ?: "bridgit-host-key-probe", host, port)
        val socket = AtomicReference<Socket?>()
        val stopped = AtomicReference<String?>()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeout.toLong())
        session.setSocketFactory(object : SocketFactory {
            override fun createSocket(h: String, p: Int): Socket {
                check()
                val s = Socket(); socket.set(s)
                if (stopped.get() != null) { s.close(); throw Failure(stopped.get()!!) }
                s.connect(InetSocketAddress(h, p), minOf(10_000, timeout * 1000))
                s.soTimeout = minOf(10_000, timeout * 1000)
                return s
            }
            override fun getInputStream(s: Socket) = s.getInputStream()
            override fun getOutputStream(s: Socket) = s.getOutputStream()
        })
        session.setConfig("StrictHostKeyChecking", "yes")
        session.setConfig("PreferredAuthentications", if (profile?.privateKey?.isNotEmpty() == true) "publickey" else "password")
        session.setConfig("NumberOfPasswordPrompts", "1")
        session.setConfig("MaxAuthTries", "1")
        profile?.password?.takeIf { it.isNotEmpty() }?.let(session::setPassword)
        session.serverAliveInterval = 5000
        session.serverAliveCountMax = 2
        val watcher = WATCH.scheduleAtFixedRate({
            val reason = if (System.nanoTime() >= deadline) "timeout_outcome_unknown" else try { check(); null } catch (_: Exception) { "authorization_revoked_outcome_unknown" }
            if (reason != null && stopped.compareAndSet(null, reason)) {
                runCatching { socket.get()?.close() }
                runCatching { session.disconnect() }
            }
        }, 100, 100, TimeUnit.MILLISECONDS)
        try {
            try { session.connect(minOf(10_000, timeout * 1000)) }
            catch (e: Exception) {
                if (profile == null && pins.observed != null) return block(session, pins)
                if (pins.observed != null && pins.observed != profile?.fingerprint) throw Failure("host_key_mismatch")
                throw e
            }
            check()
            return block(session, pins).also { check(); stopped.get()?.let { throw Failure(it) } }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            stopped.get()?.let { throw Failure(it) }
            if (e is Failure) throw e
            // Never expose library exception text: remote banners, paths and credentials are untrusted.
            throw Failure("connection_or_operation_failed_outcome_unknown", e)
        } finally {
            watcher.cancel(false)
            runCatching { socket.get()?.close() }; session.disconnect(); jsch.removeAllIdentity()
        }
    }
    fun probe(host: String, port: Int): JSONObject = connect(host, port, null, 12) { _, pins ->
        JSONObject().put("host", host).put("port", port).put("fingerprint", pins.observed ?: throw Failure("no_host_key"))
            .put("algorithm", pins.algorithm).put("verified", false).put("authenticated", false)
    }
    fun verify(p: SshProfile) = connect(p.host, p.port, p, 15) { _, _ -> JSONObject().put("authenticated", true).put("fingerprint", p.fingerprint) }

    fun exec(p: SshProfile, o: JSONObject): JSONObject {
        val command = o.getString("command"); require(command.isNotBlank() && command.length <= 32768 && '\u0000' !in command)
        val timeout = SshRules.integer(o, "timeout_seconds", 30, 1, 120)
        val max = SshRules.integer(o, "max_output_bytes", 65536, 1, 1024 * 1024)
        val input = o.optString("stdin"); require(input.toByteArray().size <= SshRules.CHUNK)
        val cwd = o.optString("cwd").takeIf { it.isNotEmpty() }?.let(SshRules::path)
        val env = o.optJSONObject("env") ?: JSONObject(); require(env.length() <= 64)
        val environment = env.keys().asSequence().associateWith { k ->
            require(k.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,127}")))
            env.getString(k).also { require(it.length <= 4096 && '\u0000' !in it) }
        }
        val cols = SshRules.integer(o, "columns", 80, 1, 500); val rows = SshRules.integer(o, "rows", 24, 1, 500)
        val term = o.optString("terminal", "xterm"); require(term.matches(Regex("[A-Za-z0-9_-]{1,40}")))
        return connect(p.host, p.port, p, timeout) { session, _ ->
            val ch = session.openChannel("exec") as ChannelExec
            val out = LimitedOutput(max); val err = LimitedOutput(max)
            try {
                ch.setPty(o.opt("pty") == true); ch.setPtyType(term, cols, rows, 0, 0)
                environment.forEach { (k, v) -> ch.setEnv(k, v) }
                // cwd is opt-in and requires a POSIX shell; the command itself remains exactly caller-supplied.
                ch.setCommand(if (cwd == null) command else "cd -- ${quote(cwd)} && $command")
                ch.setInputStream(input.byteInputStream()); ch.setOutputStream(out); ch.setErrStream(err)
                check(); ch.connect(minOf(10000, timeout * 1000))
                while (!ch.isClosed) { check(); if (!session.isConnected) throw Failure("connection_lost_outcome_unknown"); Thread.sleep(20) }
                JSONObject().put("exit_code", ch.exitStatus.takeIf { it >= 0 } ?: JSONObject.NULL)
                    .put("stdout", out.text()).put("stderr", err.text()).put("stdout_truncated", out.truncated).put("stderr_truncated", err.truncated)
                    .put("timed_out", false).put("pty", o.opt("pty") == true)
            } finally { ch.disconnect() }
        }
    }
    fun files(p: SshProfile, o: JSONObject): JSONObject {
        val action = o.getString("action"); require(action in FILE_ACTIONS)
        val path = SshRules.path(o.getString("path")); val literal = SshRules.literal(path)
        val timeout = SshRules.integer(o, "timeout_seconds", 30, 1, 120)
        val offset = SshRules.offset(o)
        val limit = SshRules.integer(o, "limit", 200, 1, 1000)
        val max = SshRules.integer(o, "max_bytes", SshRules.CHUNK, 1, SshRules.CHUNK)
        val data = if (action == "write") o.getString("data_base64").let { require(it.length <= SshRules.CHUNK * 4 / 3 + 4); Base64.getDecoder().decode(it).also { b -> require(b.size <= SshRules.CHUNK) } } else null
        val mode = if (action == "chmod") o.getString("mode").also { require(it.matches(Regex("[0-7]{3,4}"))) }.toInt(8) else 0
        val destination = if (action == "rename") SshRules.literal(o.getString("destination")) else null
        if (action in setOf("write", "rename", "remove", "rmdir", "chmod")) require(o.opt("confirm") == true)
        if (action == "write") require(offset == 0L) { "Use write_mode replace or append; random positional writes are not supported." }
        return connect(p.host, p.port, p, timeout) { session, _ ->
            val s = session.openChannel("sftp") as ChannelSftp
            try {
                check(); s.connect(10000)
                val result = JSONObject().put("action", action).put("path", path)
                when (action) {
                    "stat" -> result.put("attributes", attrs(if (o.opt("follow_symlinks") == true) s.stat(literal) else s.lstat(literal)))
                    "list" -> {
                        val entries = JSONArray(); var seen = 0L; var more = false
                        s.ls(literal, ChannelSftp.LsEntrySelector { entry ->
                            check()
                            if (entry.filename !in listOf(".", "..")) {
                                if (seen++ >= offset) {
                                    if (entries.length() >= limit) { more = true; return@LsEntrySelector ChannelSftp.LsEntrySelector.BREAK }
                                    entries.put(attrs(entry.attrs).put("name", entry.filename))
                                }
                            }
                            ChannelSftp.LsEntrySelector.CONTINUE
                        })
                        result.put("entries", entries).put("next_offset", if (more) offset + entries.length() else JSONObject.NULL)
                    }
                    "read" -> {
                        val bytes = s.get(literal, null, offset).use { input ->
                            val buffer = ByteArray(max + 1); var used = 0
                            while (used < buffer.size) { check(); val n = input.read(buffer, used, buffer.size - used); if (n < 0) break; used += n }
                            buffer.copyOf(used)
                        }
                        val dataBytes = bytes.take(max).toByteArray()
                        result.put("data_base64", Base64.getEncoder().encodeToString(dataBytes)).put("bytes", dataBytes.size)
                            .put("offset", offset).put("eof", bytes.size <= max).put("next_offset", offset + dataBytes.size)
                    }
                    "write" -> {
                        val writeMode = o.optString("write_mode", "replace")
                        require(writeMode in setOf("replace", "append"))
                        check()
                        s.put(literal, if (writeMode == "replace") ChannelSftp.OVERWRITE else ChannelSftp.APPEND).use { it.write(data!!) }
                        result.put("bytes_written", data!!.size).put("write_mode", writeMode).put("size", s.stat(literal).size)
                    }
                    "mkdir" -> { check(); s.mkdir(path) }
                    "rename" -> { check(); s.rename(literal, destination!!) }
                    "remove" -> { check(); s.rm(literal) }
                    "rmdir" -> { check(); s.rmdir(literal) }
                    "chmod" -> { check(); s.chmod(mode, literal) }
                }
                result
            } finally { s.disconnect() }
        }
    }
    class LimitedOutput(private val max: Int) : OutputStream() {
        private val data = ByteArrayOutputStream()
        var truncated = false; private set
        @Synchronized override fun write(b: Int) { if (data.size() < max) data.write(b) else truncated = true }
        @Synchronized override fun write(b: ByteArray, off: Int, len: Int) {
            val n = minOf(len, max - data.size()); data.write(b, off, n); if (n < len) truncated = true
        }
        @Synchronized fun text() = data.toString("UTF-8")
    }
    companion object {
        private val WATCH = Executors.newScheduledThreadPool(2) { r -> Thread(r, "bridgit-ssh-deadline").apply { isDaemon = true } }
        val FILE_ACTIONS = setOf("stat", "list", "read", "write", "mkdir", "rename", "remove", "rmdir", "chmod")
        fun fingerprint(bytes: ByteArray) = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))
        fun quote(s: String) = "'" + s.replace("'", "'\"'\"'") + "'"
        private fun attrs(a: SftpATTRS) = JSONObject().put("size", a.size).put("directory", a.isDir).put("symlink", a.isLink)
            .put("permissions", Integer.toOctalString(a.permissions and 4095)).put("uid", a.uId).put("gid", a.gId).put("modified_seconds", a.mTime.toLong() and 0xffffffffL)
    }
}
