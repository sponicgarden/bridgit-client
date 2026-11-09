package cc.bridgit.app.portal

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
 * This phone as a Bridgit Portal viewer: pairings with OTHER Bridgit phones (their bridgitportal://pair links), so
 * Home can show one of them read-only through the relay. Each link's rly_ token is encrypted with a non-exportable
 * Android Keystore AES-GCM key; the rest (relay, device, name) is plain. Backup is off for the app.
 */
class PortalLinks(context: Context) {
    data class Link(val device: String, val relay: String, val token: String, val name: String, val install: String?,
                    val portal: Boolean, val addedAt: Long)

    private val appContext = context.applicationContext
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized fun all(): List<Link> {
        val arr = runCatching { JSONArray(prefs.getString("links", "[]")) }.getOrDefault(JSONArray())
        return (0 until arr.length()).mapNotNull { i -> runCatching { decode(arr.getJSONObject(i)) }.getOrNull() }
    }

    fun get(device: String): Link? = all().firstOrNull { it.device == device }

    /** How many phones are paired, without decrypting anything (read every second by Home). */
    fun count(): Int = runCatching { JSONArray(prefs.getString("links", "[]")).length() }.getOrDefault(0)

    /**
     * Adds (or replaces, for the same phone) the pairing in [link]. Returns it. [claimedToken]: for a single-use
     * link (claim=1), the token this phone made and claimed with ([PortalClient.claim]); it is saved instead of the link's.
     */
    @Synchronized fun add(link: String, now: Long = System.currentTimeMillis(), claimedToken: String? = null): Link {
        val f = PortalData.parsePairLink(link) ?: throw IllegalArgumentException("Not a Bridgit pairing link")
        require(f["v"] == "1") { "This link is from a newer Bridgit. Update Bridgit and try again." }
        val relay = f["relay"].orEmpty(); val device = f["device"].orEmpty(); val token = claimedToken ?: f["token"].orEmpty()
        require(Regex("https://[A-Za-z0-9.-]{3,253}(:\\d{1,5})?").matches(relay)) { "The link's relay address isn't valid." }
        require(Regex("[A-Za-z0-9_-]{8,128}").matches(device)) { "The link's phone id isn't valid." }
        require(Regex("rly_[A-Za-z0-9-]{32,120}").matches(token)) { "The link's key isn't valid." }
        val l = Link(device, relay, token, f["name"].orEmpty().take(80).ifBlank { "Bridgit phone" },
            f["install"]?.takeIf { Regex("bgi_[a-z2-7]{24}").matches(it) }, f["portal"] == "1", now)
        val rest = all().filter { it.device != device }
        save(rest + l)
        return l
    }

    @Synchronized fun remove(device: String) {
        save(all().filter { it.device != device })
        if (selectedFlow.value == device) select(null)
    }

    /** The phone Home shows: null = this phone. */
    val selected: StateFlow<String?> get() = selectedFlow

    /**
     * [device] is a paired phone's device id, or "hub:<link id>" for this satellite's hub (owner, 5 Nov 2026: a
     * satellite's Home, Applets and Camera tabs can switch to its hub, as a portal switches phones).
     */
    fun select(device: String?) {
        val d = device?.takeIf { get(it) != null || isHub(it) }
        prefs.edit().putString("selected", d).commit() // survives the app being killed right after
        selectedFlow.value = d
    }

    fun selectedLink(): Link? = selectedFlow.value?.let { get(it) }

    /** The hub this satellite is switched to, or null. */
    fun selectedHub(): cc.bridgit.app.satellite.HubLinks.HubInfo? = selectedFlow.value?.takeIf { it.startsWith(HUB) }?.let { id ->
        hubLinks()?.hubs()?.firstOrNull { HUB + it.linkId == id }
    }

    private fun hubLinks() = (appContext as? cc.bridgit.app.BridgeApp)?.hubLinks
    private fun isHub(id: String) = id.startsWith(HUB) && hubLinks()?.hubs()?.any { HUB + it.linkId == id } == true

    private fun save(links: List<Link>) {
        check(prefs.edit().putString("links", JSONArray(links.map { encode(it) }).toString()).commit())
    }

    private fun encode(l: Link): JSONObject {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key())
        return JSONObject().put("device", l.device).put("relay", l.relay).put("name", l.name).put("install", l.install ?: JSONObject.NULL)
            .put("portal", l.portal).put("added_at", l.addedAt)
            .put("token_iv", b64(c.iv)).put("token_ct", b64(c.doFinal(l.token.toByteArray())))
    }

    private fun decode(o: JSONObject): Link {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, un64(o.getString("token_iv"))))
        val token = String(c.doFinal(un64(o.getString("token_ct"))))
        return Link(o.getString("device"), o.getString("relay"), token, o.getString("name"),
            o.optString("install").takeIf { it.startsWith("bgi_") }, o.optBoolean("portal"), o.optLong("added_at"))
    }

    companion object {
        private const val PREFS = "portal_links_v1"
        private const val ALIAS = "bridgit-portal-links-v1"
        private val selectedFlow = MutableStateFlow<String?>(null)
        const val HUB = "hub:"
        @Volatile private var loaded = false

        /** Test hook: Robolectric has no AndroidKeyStore, so tests use an in-memory key. */
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

        private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
        private fun un64(s: String) = Base64.decode(s, Base64.NO_WRAP)

        /** Loads the saved selection once per process (Home starts on the phone it showed last). */
        fun init(context: Context) {
            if (loaded) return
            loaded = true
            val links = PortalLinks(context)
            selectedFlow.value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("selected", null)
                ?.takeIf { d -> links.all().any { it.device == d } || links.isHub(d) }
        }
    }
}
