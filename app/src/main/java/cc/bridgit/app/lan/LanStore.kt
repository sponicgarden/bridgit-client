package cc.bridgit.app.lan

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

/** No secret-bearing toString. AES-GCM Android Keystore, app-private and excluded from backup. */
class LanDevice(val id: String, val name: String, val host: String, val adapter: String, val agentId: String,
    val username: String, val password: String, val http: Boolean, val verifiedAt: Long, val identity: String = "",
    /** Adapter state that isn't a secret, e.g. a device pack's resolved UPnP addresses (lan/packs/PackEngine.Bound). */
    val extra: JSONObject = JSONObject()) {
    fun publicJson(actions: List<String> = LanRules.actions(adapter)) = JSONObject().put("id", id).put("name", name).put("host", host).put("adapter", adapter)
        .put("verified_at", verifiedAt).put("availability", "not_checked").put("enrolled", true)
        .apply { extra.optString("pack_version").takeIf { it.isNotEmpty() }?.let { put("pack_version", it) } }
        .put("actions", JSONArray(actions.map { action -> JSONObject().put("action", action)
            .put("method", "POST").put("path", "/v1/lan/devices/action")
            .put("body", JSONObject().put("device", id).put("action", action)) }))
    /**
     * A device-pack enrollment's public JSON (A0212): each action also documents its parameters (name, type,
     * required, bounds; empty when it takes none), and the advertised body carries example values for the
     * required ones in `params`.
     */
    fun publicJson(pack: cc.bridgit.app.lan.packs.DevicePack.Pack?): JSONObject {
        val actions = pack?.actions.orEmpty()
        val out = publicJson(actions.map { it.name })
        val list = out.getJSONArray("actions")
        actions.forEachIndexed { i, a ->
            val entry = list.getJSONObject(i).put("params", a.paramSchema())
            val example = a.exampleParams()
            if (example.length() > 0) entry.getJSONObject("body").put("params", example)
        }
        return out
    }
    fun stored() = JSONObject().put("id", id).put("name", name).put("host", host).put("adapter", adapter).put("agent_id", agentId)
        .put("username", username).put("password", password).put("allow_http", http).put("verified_at", verifiedAt).put("identity", identity).put("extra", extra)
    fun with(extra: JSONObject) = LanDevice(id, name, host, adapter, agentId, username, password, http, verifiedAt, identity, extra)
    override fun toString() = "LanDevice($id)"
    companion object {
        fun read(o: JSONObject) = LanDevice(o.getString("id"), o.getString("name"), o.getString("host"), o.getString("adapter"),
            o.getString("agent_id"), o.getString("username"), o.getString("password"), o.getBoolean("allow_http"), o.getLong("verified_at"), o.optString("identity"), o.optJSONObject("extra") ?: JSONObject())
    }
}
class LanStore(context: Context) {
    private val prefs = context.getSharedPreferences("lan_devices", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey("bridgit_lan_devices", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("bridgit_lan_devices", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun all(): List<LanDevice> {
        val data = prefs.getString("data", null) ?: return emptyList()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.getDecoder().decode(prefs.getString("iv", ""))))
        val a = JSONArray(String(cipher.doFinal(Base64.getDecoder().decode(data)), Charsets.UTF_8))
        return (0 until a.length()).map { LanDevice.read(a.getJSONObject(it)) }
    }
    @Synchronized fun save(d: LanDevice) { require(all().size < 64 || all().any { it.id == d.id }); write(all().filter { it.id != d.id } + d) }
    @Synchronized fun remove(id: String) { write(all().filter { it.id != id }) }
    private fun write(devices: List<LanDevice>) {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        check(prefs.edit().putString("iv", Base64.getEncoder().encodeToString(c.iv))
            .putString("data", Base64.getEncoder().encodeToString(c.doFinal(JSONArray(devices.map { it.stored() }).toString().toByteArray()))).commit())
    }
}
