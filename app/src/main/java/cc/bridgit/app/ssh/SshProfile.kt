package cc.bridgit.app.ssh

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Deliberately not a data class: credentials must never appear in toString. */
class SshProfile(val id: String, val agentId: String, val name: String, val host: String, val port: Int,
    val username: String, val fingerprint: String, val password: String, val privateKey: String,
    val passphrase: String, val permissions: Set<String>, val revision: String = UUID.randomUUID().toString()) {
    fun publicJson() = JSONObject().put("id", id).put("name", name).put("host", host).put("port", port)
        .put("username", username).put("fingerprint", fingerprint).put("authentication", if (privateKey.isEmpty()) "password" else "private_key")
        .put("permissions", JSONArray(permissions.toList())).put("availability", "not_checked")
    fun stored() = publicJson().put("agent_id", agentId).put("password", password).put("private_key", privateKey)
        .put("passphrase", passphrase).put("revision", revision)
    override fun toString() = "SshProfile($id)"
    companion object {
        val PERMISSIONS = setOf("exec", "files_read", "files_write")
        fun read(o: JSONObject) = SshProfile(o.getString("id"), o.getString("agent_id"), o.getString("name"), o.getString("host"),
            o.getInt("port"), o.getString("username"), o.getString("fingerprint"), o.getString("password"), o.getString("private_key"),
            o.getString("passphrase"), o.getJSONArray("permissions").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }, o.getString("revision"))
        fun draft(body: JSONObject, agentId: String): SshProfile {
            val host = SshRules.host(body.getString("host"))
            val port = SshRules.integer(body, "port", 22, 1, 65535)
            val name = body.getString("name"); require(name.isNotBlank() && name.length <= 80)
            val user = body.getString("username"); require(user.isNotBlank() && user.length <= 128 && user.none { it < ' ' })
            val pin = body.getString("fingerprint"); require(pin.matches(Regex("SHA256:[A-Za-z0-9+/]{43}")))
            val pass = body.optString("password"); val key = body.optString("private_key"); val phrase = body.optString("passphrase")
            require(pass.length <= 4096 && key.length <= 32768 && phrase.length <= 4096)
            require(pass.isNotEmpty() xor key.isNotEmpty()) { "Supply exactly one of password or private_key." }
            val a = body.getJSONArray("permissions"); require(a.length() in 1..3)
            val permissions = (0 until a.length()).map { a.getString(it) }.toSet(); require(PERMISSIONS.containsAll(permissions))
            require(body.opt("confirm_host_key") == true) { "Verify the fingerprint independently and confirm it." }
            return SshProfile("ssh:${UUID.randomUUID()}", agentId, name, host, port, user, pin, pass, key, phrase, permissions)
        }
    }
}

object SshRules {
    const val CHUNK = 512 * 1024
    fun host(value: String): String {
        require(value.length in 1..253 && value == value.trim() && value.none { it <= ' ' || it in "/\\@?#%[]" })
        require(value.matches(Regex("[A-Za-z0-9_.:-]+")))
        return value
    }
    fun integer(o: JSONObject, key: String, default: Int, min: Int, max: Int): Int {
        if (!o.has(key)) return default
        val n = o.get(key) as? Number ?: throw IllegalArgumentException("$key must be an integer")
        require(n.toDouble().isFinite() && n.toDouble() == n.toInt().toDouble() && n.toInt() in min..max)
        return n.toInt()
    }
    fun offset(o: JSONObject): Long {
        if (!o.has("offset")) return 0
        val n = o.get("offset") as? Number ?: throw IllegalArgumentException()
        require(n.toDouble().isFinite() && n.toDouble() == n.toLong().toDouble() && n.toLong() in 0..1_000_000_000_000L)
        return n.toLong()
    }
    fun path(value: String): String {
        require(value.isNotBlank() && value.length <= 4096 && value.none { it == '\u0000' || it == '\n' || it == '\r' })
        return value
    }
    // JSch interprets paths as globs. Escape every path, so remove/rename never expand wildcards.
    fun literal(value: String) = path(value).replace("\\", "\\\\").replace("*", "\\*").replace("?", "\\?")
}
