package cc.bridgit.app.ssh

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.api.*
import cc.bridgit.app.capability.Capability
import cc.bridgit.app.outbound.Outbound
import io.ktor.server.routing.Route
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import org.json.JSONArray
import org.json.JSONObject

class SshModule(app: BridgeApp) : Capability(app) {
    override val id = "ssh"
    override val titleRes = R.string.ssh_title
    override val descriptionRes = R.string.ssh_description
    override val group = "controls"
    override val switchKey = "ssh"
    override val endpoints = ALL
    override fun details() = JSONObject().put("management", "More → SSH connections")
        .put("authentication", JSONArray(listOf("password", "private_key"))).put("host_key_verification", "Required SHA256 pin before authentication")
        .put("scope", "Owner-authorized DNS names, IPv4 and IPv6 hosts reachable from the phone, including LAN and VPN")
        .put("max_concurrent", 2).put("max_seconds", 120).put("file_chunk_bytes", SshRules.CHUNK)
        .put("scheduling", "exec and files use POST /v1/schedule; profile management cannot be scheduled")
    private fun authorize(req: Req) {
        requireUsable()
        val a = req.agent ?: throw ErrorCatalog.error("unauthorized", "Agent required")
        val live = app.agents.get(a.id)
        if (live == null || live.paused || live.keyHash != a.keyHash || !live.allows(id)) throw ErrorCatalog.error("agent_not_allowed", "SSH permission was revoked.")
        if (Outbound.paused()) throw Outbound.Paused()
    }
    private fun owned(req: Req, id: String) = app.sshProfiles.all().firstOrNull { it.id == id && it.agentId == req.agent!!.id }
        ?: throw ErrorCatalog.error("not_found", "No SSH connection saved for this agent.")
    private suspend fun run(req: Req, profile: SshProfile? = null, block: (SshClient) -> JSONObject): Reply {
        authorize(req)
        requireIdempotency(req)
        if (!slots.tryAcquire()) throw ErrorCatalog.error("conflict", "Two SSH operations are running; wait for one to finish.")
        try {
            return withContext(Dispatchers.IO) {
                val scope = currentCoroutineContext()
                val guard = {
                    scope.ensureActive(); authorize(req)
                    if (profile != null && owned(req, profile.id).revision != profile.revision)
                        throw ErrorCatalog.error("conflict", "SSH connection changed; start a new operation.")
                    Unit
                }
                try {
                    guard()
                    val result = block(SshClient(guard)); guard()
                    Reply.Json(result)
                } catch (e: CancellationException) { throw e }
                catch (e: ApiError) { throw e }
                catch (_: org.json.JSONException) { throw ErrorCatalog.error("invalid_parameter", "Missing or invalid SSH parameter; see the endpoint schema.") }
                catch (_: IllegalArgumentException) { throw ErrorCatalog.error("invalid_parameter", "Invalid SSH parameters. See the SSH endpoint schema and guide.") }
                catch (e: SshClient.Failure) {
                    guard() // Preserve current revocation/switch/cancellation errors after the socket watcher closes it.
                    throw ErrorCatalog.error("upstream_failed", "SSH: ${e.reason}. A disconnected or timed-out command may still run remotely; inspect the result before retrying.", mapOf("ssh_error" to e.reason))
                } catch (_: Exception) { throw ErrorCatalog.error("upstream_failed", "SSH operation failed. Check the connection and credentials. Remote outcome may be unknown.") }

            }
        } finally { slots.release() }
    }
    override fun Route.install(api: ApiServer) = with(api) {
        fun Route.sshEndpoint(spec: EndpointSpec, block: suspend Req.() -> Reply) {
            endpoint(spec) {
                authorize(this); requireIdempotency(this)
                try { block() }
                catch (_: SshStore.Unavailable) { throw ErrorCatalog.error("service_start_blocked", "SSH credential storage is unavailable; existing data has been preserved.") }
                catch (_: SshStore.Conflict) { throw ErrorCatalog.error("conflict", "SSH connection changed during verification. Refresh the inventory.") }
                catch (_: org.json.JSONException) { throw ErrorCatalog.error("invalid_parameter", "Missing or invalid SSH parameter; see the endpoint schema.") }
                catch (_: IllegalArgumentException) { throw ErrorCatalog.error("invalid_parameter", "Invalid SSH parameter or a connection changed during verification.") }
            }
        }
        sshEndpoint(LIST) {
            authorize(this)
            Reply.Json(JSONObject().put("connections", JSONArray(app.sshProfiles.all().filter { it.agentId == agent!!.id }.map { it.publicJson() })))
        }
        sshEndpoint(PROBE) {
            val req = this
            val host = SshRules.host(body.getString("host")); val port = SshRules.integer(body, "port", 22, 1, 65535)
            run(req) { it.probe(host, port) }
        }
        sshEndpoint(SAVE) {
            val req = this
            val draft = SshProfile.draft(body, agent!!.id)
            val previous = body.optString("connection").takeIf { it.isNotEmpty() }?.let { owned(req, it) }
            val p = if (previous == null) draft else SshProfile(previous.id, draft.agentId, draft.name, draft.host, draft.port, draft.username,
                draft.fingerprint, draft.password, draft.privateKey, draft.passphrase, draft.permissions)
            run(req, previous) { it.verify(p) }
            authorize(req); currentCoroutineContext().ensureActive()
            // Recheck a replacement under the store lock: a revoked connection cannot be resurrected.
            app.sshProfiles.saveVerified(p, previous?.revision)
            Reply.Json(p.publicJson().put("authenticated", true))
        }
        sshEndpoint(REMOVE) {
            authorize(this); requireIdempotency(this); val p = owned(this, body.getString("connection"))
            app.sshProfiles.remove(p.id)
            Reply.Json(JSONObject().put("removed", p.id))
        }
        sshEndpoint(TEST) {
            val req = this; val p = owned(req, body.getString("connection"))
            run(req, p) { it.verify(p) }
        }
        sshEndpoint(EXEC) {
            val req = this; val p = owned(req, body.getString("connection"))
            requirePermission(p, "exec")
            run(req, p) { it.exec(p, body) }
        }
        sshEndpoint(FILES) {
            val req = this; val p = owned(req, body.getString("connection"))
            requirePermission(p, if (body.optString("action") in listOf("read", "list", "stat")) "files_read" else "files_write")
            run(req, p) { it.files(p, body) }
        }
    }
    companion object {
        fun requireIdempotency(req: Req) {
            if (req.spec.idempotencyRequired && req.header("Idempotency-Key").isNullOrBlank())
                throw ErrorCatalog.error("invalid_parameter", "Idempotency-Key is required for SSH operations that can change state.")
        }
        private val slots = Semaphore(2)
        fun requirePermission(p: SshProfile, permission: String) {
            if (permission !in p.permissions) throw ErrorCatalog.error("agent_not_allowed", "This SSH connection does not grant $permission.")
        }
        private val connection = Param("connection", "string", "This agent's saved SSH connection id", true)
        private val timeout = Param("timeout_seconds", "integer", "Total connect + operation deadline, 1..120 seconds; default 30. Disconnect does not guarantee remote termination.")
        val LIST = EndpointSpec("GET", "/ssh/connections", "ssh", "List this agent's SSH connections without credentials or a network probe",
            example = "GET /v1/ssh/connections", returns = "connections[id,name,host,port,username,fingerprint,authentication,permissions,availability=not_checked]")
        val PROBE = EndpointSpec("POST", "/ssh/probe", "ssh", "Observe an SSH host key without sending credentials; verify it independently before enrollment", capture = true,
            body = listOf(Param("host", "string", "Authorized DNS name, IPv4 or unbracketed IPv6; no URL", true), Param("port", "integer", "1..65535; default 22")),
            example = "POST /v1/ssh/probe {\"host\":\"192.168.1.127\"}", returns = "host,port,fingerprint (SHA256),algorithm,verified=false,authenticated=false")
        val SAVE = EndpointSpec("POST", "/ssh/connections/save", "ssh", "Verify authentication and save or fully replace this agent's SSH connection", capture = true, idempotent = true, idempotencyRequired = true,
            body = listOf(Param("connection", "string", "Existing owned id to replace; omit to create. Replacement requires all fields, including credentials."),
                Param("name", "string", "Label, 1..80 characters", true), Param("host", "string", "DNS name, IPv4 or unbracketed IPv6", true), Param("port", "integer", "1..65535; default 22"),
                Param("username", "string", "Authorized remote account, 1..128 characters", true), Param("fingerprint", "string", "Independently verified SHA256 host-key fingerprint", true),
                Param("confirm_host_key", "boolean", "Must be true after comparing with a trusted host-key source, not merely the probe", true),
                Param("password", "string", "Password authentication; mutually exclusive with private_key"), Param("private_key", "string", "PEM/OpenSSH private key, at most 32768 characters"),
                Param("passphrase", "string", "Optional private-key passphrase"), Param("permissions", "array", "Nonempty selection of exec, files_read, files_write. exec provides the remote account's full shell authority, including file operations.", true)),
            privateFields = setOf("*"), example = "POST /v1/ssh/connections/save (see guide; credentials must not be pasted into chat)", returns = "Safe saved connection and authenticated=true; credentials encrypted on phone, never returned")
        val REMOVE = EndpointSpec("POST", "/ssh/connections/remove", "ssh", "Forget this agent's connection and credentials; active operations disconnect", idempotent = true, idempotencyRequired = true,
            body = listOf(connection), example = "POST /v1/ssh/connections/remove {\"connection\":\"ssh:example\"}", returns = "removed")
        val TEST = EndpointSpec("POST", "/ssh/test", "ssh", "Verify the saved host key and credentials without running a command", capture = true,
            body = listOf(connection), example = "POST /v1/ssh/test {\"connection\":\"ssh:example\"}", returns = "authenticated=true,fingerprint")
        val EXEC = EndpointSpec("POST", "/ssh/exec", "ssh", "Run a remote command with this connection's exec permission; can be scheduled", capture = true, idempotent = true, idempotencyRequired = true,
            body = listOf(connection, Param("command", "string", "Remote shell command, 1..32768 characters; no automatic sudo", true), timeout,
                Param("stdin", "string", "UTF-8 standard input, up to 512 KiB; EOF follows"), Param("cwd", "string", "Optional working directory; requires POSIX remote shell; safely quoted"),
                Param("env", "object", "Up to 64 SSH environment requests. Remote server AcceptEnv policy controls acceptance."), Param("pty", "boolean", "Request a pseudo-terminal; default false. May merge stderr and alter output. Not an interactive session."),
                Param("terminal", "string", "PTY type; default xterm"), Param("columns", "integer", "PTY columns 1..500; default 80"), Param("rows", "integer", "PTY rows 1..500; default 24"),
                Param("max_output_bytes", "integer", "1..1048576 per stream, default 65536. Extra output is drained and discarded.")), privateFields = setOf("*"),
            example = "POST /v1/ssh/exec {\"connection\":\"ssh:example\",\"command\":\"uname -a\",\"timeout_seconds\":10}",
            returns = "exit_code (null if unknown),stdout,stderr,stdout_truncated,stderr_truncated,timed_out=false,pty; transport/deadline/revocation errors include ssh_error; remote effects may continue after disconnect")
        val FILES = EndpointSpec("POST", "/ssh/files", "ssh", "Granular SFTP operations; files_read or files_write permission; can be scheduled", capture = true, idempotent = true, idempotencyRequired = true,
            body = listOf(connection, Param("action", "string", "Operation", true, SshClient.FILE_ACTIONS.toList()), Param("path", "string", "Literal remote path; relative to SFTP home or absolute. Wildcards are escaped.", true), timeout,
                Param("offset", "integer", "Read byte offset, or list entry offset; write requires 0; 0..1000000000000, default 0"), Param("max_bytes", "integer", "Read chunk 1..524288; default 524288"),
                Param("limit", "integer", "List entry count 1..1000; default 200. Pagination is not a snapshot."), Param("data_base64", "string", "write: base64 bytes, at most 512 KiB decoded"),
                Param("write_mode", "string", "write: replace (default, truncates) or append. Upload larger files in sequential chunks: replace first, append subsequent chunks. Do not use concurrent writers.", enum = listOf("replace", "append")), Param("destination", "string", "rename: literal destination path; may replace existing destination"),
                Param("mode", "string", "chmod: octal permissions, e.g. 600 or 0755"), Param("follow_symlinks", "boolean", "stat: follow link; default false"),
                Param("confirm", "boolean", "Must be true for write, rename, remove, rmdir and chmod. Writes may replace data; failed writes may be partial.")), privateFields = setOf("*"),
            example = "POST /v1/ssh/files {\"connection\":\"ssh:example\",\"action\":\"list\",\"path\":\".\",\"limit\":100}",
            returns = "action,path; stat attributes; list entries,next_offset; read data_base64,bytes,offset,eof,next_offset; write bytes_written,write_mode,size. Remote filesystem ACLs apply; directory deletion is nonrecursive.")
        val ALL = listOf(LIST, PROBE, SAVE, REMOVE, TEST, EXEC, FILES)
    }
}
