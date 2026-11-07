package cc.bridgit.app.satellite

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * This phone as a hub: the satellites it has added (docs/spec-satellites.md). Each satellite's key (a brg_ key the
 * satellite made for this hub) is encrypted with a non-exportable Keystore AES-GCM key, as PortalLinks does; the
 * rest is plain. [agents] = the hub agents the owner lets use this satellite (SAT-15, none by default).
 */
class SatelliteStore(context: Context) {
    data class Satellite(
        val id: String, val name: String, val host: String, val port: Int, val key: String, val install: String?,
        val addedAt: Long, val lastSeen: Long = 0, val lastError: String? = null,
        val manifest: JSONObject? = null, val agents: Set<String> = emptySet(),
        /** The hub agent the satellite calls back with (its Camera and Portals tabs use this hub's applets), and its key. */
        val returnAgent: String? = null, val returnKey: String? = null,
        /** When this hub last told the satellite its applets ([SatelliteHub.pushHubInfo]), and why the last try failed. */
        val sharedAt: Long = 0, val shareError: String? = null,
    ) {
        /** The satellite's API, through the Tailscale-only name [SatelliteClient] resolves. */
        val base: String get() = SatelliteClient.base(host, port)
        fun online(now: Long = System.currentTimeMillis()) = lastError == null && lastSeen > 0 && now - lastSeen <= ONLINE_MS
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized fun all(): List<Satellite> {
        val arr = runCatching { JSONArray(prefs.getString("satellites", "[]")) }.getOrDefault(JSONArray())
        // A record that can't be read is skipped (not shown), and the next save drops it: say so, so it can be traced.
        return (0 until arr.length()).mapNotNull { i -> runCatching { decode(arr.getJSONObject(i)) }
            .onFailure { android.util.Log.w("Satellites", "Skipped a satellite record that can't be read: ${it.javaClass.simpleName}") }.getOrNull() }
    }

    /** (name, online) per satellite, without decrypting keys: Home draws these every second. */
    fun summaries(now: Long = System.currentTimeMillis()): List<Pair<String, Boolean>> {
        val arr = runCatching { JSONArray(prefs.getString("satellites", "[]")) }.getOrDefault(JSONArray())
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { o ->
            val seen = o.optLong("last_seen"); val err = o.optString("last_error").takeIf { it.isNotEmpty() && !o.isNull("last_error") }
            o.optString("name") to (err == null && seen > 0 && now - seen <= ONLINE_MS)
        } }
    }

    fun get(id: String): Satellite? = all().firstOrNull { it.id == id }
    fun count(): Int = runCatching { JSONArray(prefs.getString("satellites", "[]")).length() }.getOrDefault(0)

    /** Adds a satellite from a claimed link and its [key] (replacing one with the same install id or address). Returns it. */
    @Synchronized fun add(p: SatelliteLink.Parsed, key: String, manifest: JSONObject?, now: Long = System.currentTimeMillis()): Satellite {
        val existing = all()
        val same = existing.firstOrNull { (p.install != null && it.install == p.install) || (it.host == p.host && it.port == p.port) }
        val name = manifest?.optString("name")?.takeIf { it.isNotBlank() }?.take(60) ?: p.name
        val id = same?.id ?: SatelliteLink.newId(name, existing.map { it.id }.toSet())
        val s = Satellite(id, name, p.host, p.port, key, p.install, same?.addedAt ?: now, if (manifest != null) now else 0, null,
            manifest, same?.agents ?: emptySet(), same?.returnAgent, same?.returnKey, same?.sharedAt ?: 0, same?.shareError)
        save(existing.filter { it.id != id } + s)
        return s
    }

    @Synchronized fun update(id: String, f: (Satellite) -> Satellite): Satellite? {
        val list = all(); val old = list.firstOrNull { it.id == id } ?: return null
        val n = f(old)
        save(list.map { if (it.id == id) n else it })
        return n
    }

    @Synchronized fun remove(id: String) = save(all().filter { it.id != id })

    /** Whether hub agent [agentId] may use satellite [id]. */
    fun allows(id: String, agentId: String): Boolean = get(id)?.agents?.contains(agentId) == true

    fun setAgent(id: String, agentId: String, allowed: Boolean) =
        update(id) { it.copy(agents = if (allowed) it.agents + agentId else it.agents - agentId) }

    /** Called when a hub agent is removed, so a re-used id can't inherit access. */
    @Synchronized fun forgetAgent(agentId: String) {
        val list = all()
        if (list.any { agentId in it.agents }) save(list.map { it.copy(agents = it.agents - agentId) })
    }

    /** Whether [agentId] is a satellite's return link on this hub. Read on every request, so no decrypting. */
    fun isReturnAgent(agentId: String?): Boolean = agentId != null && agentId in (prefs.getStringSet("return_ids", emptySet()) ?: emptySet())

    /** The satellite whose return link is [agentId]. */
    fun byReturnAgent(agentId: String): Satellite? = all().firstOrNull { it.returnAgent == agentId }

    private fun save(list: List<Satellite>) {
        check(prefs.edit().putString("satellites", JSONArray(list.map { encode(it) }).toString())
            .putStringSet("return_ids", list.mapNotNull { it.returnAgent }.toSet()).commit())
        changes.value = changes.value + 1
    }

    private fun encode(s: Satellite): JSONObject {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key())
        return JSONObject().put("id", s.id).put("name", s.name).put("host", s.host).put("port", s.port)
            .put("install", s.install ?: JSONObject.NULL).put("added_at", s.addedAt).put("last_seen", s.lastSeen)
            .put("last_error", s.lastError ?: JSONObject.NULL).put("manifest", s.manifest ?: JSONObject.NULL)
            .put("agents", JSONArray(s.agents.sorted()))
            .put("key_iv", b64(c.iv)).put("key_ct", b64(c.doFinal(s.key.toByteArray())))
            .put("shared_at", s.sharedAt).put("share_error", s.shareError ?: JSONObject.NULL)
            .put("return_agent", s.returnAgent ?: JSONObject.NULL).put("return_key", s.returnKey?.let { seal(it) } ?: JSONObject.NULL)
    }

    private fun decode(o: JSONObject): Satellite {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, un64(o.getString("key_iv"))))
        val agents = o.optJSONArray("agents")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
        return Satellite(o.getString("id"), o.getString("name"), o.getString("host"), o.getInt("port"),
            String(c.doFinal(un64(o.getString("key_ct")))), o.optString("install").takeIf { it.startsWith("bgi_") },
            o.optLong("added_at"), o.optLong("last_seen"), o.optString("last_error").takeIf { it.isNotEmpty() && !o.isNull("last_error") },
            o.optJSONObject("manifest"), agents, o.optString("return_agent").takeIf { it.isNotEmpty() && !o.isNull("return_agent") },
            o.optJSONObject("return_key")?.let { open(it) }, o.optLong("shared_at"),
            o.optString("share_error").takeIf { it.isNotEmpty() && !o.isNull("share_error") })
    }

    companion object {
        private const val PREFS = "satellites_v1"
        private const val ALIAS = "bridgit-satellites-v1"
        /** Seen within this long = online (the hub refreshes every 5 min while it is listening). */
        const val ONLINE_MS = 11 * 60_000L
        /** Bumped on every change, so the Devices screen can redraw. */
        val changes = MutableStateFlow(0)
        val changed: StateFlow<Int> get() = changes

        /** Test hook: Robolectric has no AndroidKeyStore. */
        @Volatile internal var keyOverride: SecretKey? = null

        private fun key(): SecretKey {
            keyOverride?.let { return it }
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!ks.containsAlias(ALIAS)) KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
            return ks.getKey(ALIAS, null) as SecretKey
        }

        /** A secret encrypted with the satellites' Keystore key: {iv, ct}. Also used for the hub's return key on a satellite. */
        fun seal(secret: String): JSONObject {
            val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key())
            return JSONObject().put("iv", b64(c.iv)).put("ct", b64(c.doFinal(secret.toByteArray())))
        }

        /** The reverse of [seal]; null if it can't be read (another install's key, damaged). */
        fun open(o: JSONObject): String? = runCatching {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, un64(o.getString("iv"))))
            String(c.doFinal(un64(o.getString("ct"))))
        }.getOrNull()

        private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
        private fun un64(s: String) = Base64.decode(s, Base64.NO_WRAP)
    }
}
