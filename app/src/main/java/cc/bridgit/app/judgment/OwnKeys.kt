package cc.bridgit.app.judgment

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The owner's own AI keys ("bring your own key"), for the phone's own Jev and Clef calls. When the team
 * backend says Bridgit's shared allowance is used up ([FALLBACK_REASONS]), [Jev.ask] retries the call
 * once with one of these keys in the X-Bridgit-Own-Key header; the backend uses it for that call only and
 * never stores it (bridgit-cloud/feedback/src/ownkeys.mjs).
 *
 * Keys are typed in by the owner in Settings and kept encrypted with a non-exportable Android Keystore
 * AES-GCM key. Agents with App settings and Super Powers can replace or clear them through a write-only API. The log never shows them.
 *
 * To add a provider: add it to contracts/client-core/vocab/own-key-providers.json (then run
 * tools/client-core/gen.mjs), its strings to [TEXT] here, and the same id to PROVIDERS in
 * bridgit-cloud/feedback/src/ownkeys.mjs. Settings builds its rows from this list.
 */
class OwnKeys(context: Context) {
    /** One field the owner types; [pattern] checks it, [hint] is a string resource; [secret] fields are masked while typing. */
    data class Field(val hint: Int, val pattern: Regex, val secret: Boolean = true)

    /**
     * A provider: its wire [id], the [routes] it can answer ("jev", "clef"), and the [fields] that are
     * joined with ":" into the header's secret. [native] = the route's own model (tried before a stand-in).
     */
    data class Provider(val id: String, val label: Int, val summary: Int, val routes: Set<String>, val native: Set<String>, val fields: List<Field>)

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Whether a key is saved for [provider]. */
    fun has(provider: String): Boolean = prefs.contains("$provider.data")

    /** The saved secret for [provider] (fields joined with ":"), or null if none or unreadable. */
    @Synchronized
    fun get(provider: String): String? {
        val data = prefs.getString("$provider.data", null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(prefs.getString("$provider.iv", ""), Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)))
        }.getOrNull()
    }

    @Synchronized
    fun set(provider: String, secret: String, authorize: () -> Unit = {}) {
        authorize()
        require(PROVIDERS.any { it.id == provider }) { "unknown provider $provider" }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val sealed = cipher.doFinal(secret.toByteArray())
        authorize()
        check(prefs.edit()
            .putString("$provider.iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("$provider.data", Base64.encodeToString(sealed, Base64.NO_WRAP))
            .putString("$provider.hint", masked(secret))
            .commit()) { "Model credential storage failed" }
    }

    @Synchronized
    fun clear(provider: String) {
        check(prefs.edit().remove("$provider.iv").remove("$provider.data").remove("$provider.hint").commit()) { "Model credential removal failed" }
    }

    /** What Settings shows for a saved key: its last 4 characters, never the whole key. */
    fun hint(provider: String): String? = prefs.getString("$provider.hint", null)

    /** The header value for [route] ("jev" or "clef"), or null when no saved key can answer it. */
    fun headerFor(route: String): String? {
        val p = choose(route, PROVIDERS.filter { has(it.id) }.map { it.id }.toSet()) ?: return null
        val secret = get(p) ?: return null
        return "$p=$secret"
    }

    private fun secretKey(): SecretKey = synchronized(KEY_LOCK) {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return@synchronized it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        gen.generateKey()
    }

    companion object {
        private val KEY_LOCK = Any()
        private const val PREFS = "own_keys"
        private const val ALIAS = "bridgit_own_keys"
        private const val TRANSFORM = "AES/GCM/NoPadding"

        /** The backend's answers that mean Bridgit's shared allowance is used up, so an own key may help. */
        val FALLBACK_REASONS = setOf("spend_cap", "rate_limited", "clef_quota")

        /** Name and summary strings per provider id; the ids, routes and fields come from the contract pack. */
        private val TEXT: Map<String, Pair<Int, Int>> = mapOf(
            "googlemaps" to (cc.bridgit.app.R.string.own_key_googlemaps to cc.bridgit.app.R.string.own_key_googlemaps_desc),
            "openmeteo" to (cc.bridgit.app.R.string.own_key_openmeteo to cc.bridgit.app.R.string.own_key_openmeteo_desc),
            "typesafe" to (cc.bridgit.app.R.string.own_key_typesafe to cc.bridgit.app.R.string.own_key_typesafe_desc),
            "cloudflare" to (cc.bridgit.app.R.string.own_key_cloudflare to cc.bridgit.app.R.string.own_key_cloudflare_desc),
            "openrouter" to (cc.bridgit.app.R.string.own_key_openrouter to cc.bridgit.app.R.string.own_key_openrouter_desc),
        )
        private val HINTS: Map<String, Int> = mapOf(
            "api_key" to cc.bridgit.app.R.string.own_key_api_key, "account_id" to cc.bridgit.app.R.string.own_key_cf_account, "api_token" to cc.bridgit.app.R.string.own_key_cf_token,
        )

        /** Generated from contracts/client-core/vocab/own-key-providers.json; add a provider there (and in bridgit-cloud ownkeys.mjs). */
        val PROVIDERS: List<Provider> = cc.bridgit.app.core.contract.ContractVocab.OWN_KEY_PROVIDERS.map { p ->
            val (label, summary) = TEXT[p.id] ?: error("no strings for own-key provider ${p.id}")
            Provider(p.id, label, summary, p.routes, p.ownRoutes,
                p.fields.map { f -> Field(HINTS[f.id] ?: error("no hint for own-key field ${f.id}"), Regex(f.pattern), f.secret) })
        }

        /** "jev" or "clef" for a backend route like "/v1/jev". Pure. */
        fun routeName(route: String): String = route.substringAfterLast('/')

        /**
         * Which saved provider answers [route]: the route's own model first (TypeSafe for Jev, Cloudflare
         * for Clef), then a stand-in like OpenRouter, in [PROVIDERS] order. Pure, unit-tested.
         */
        fun choose(route: String, saved: Set<String>): String? {
            val able = PROVIDERS.filter { route in it.routes && it.id in saved }
            return (able.firstOrNull { route in it.native } ?: able.firstOrNull())?.id
        }

        /** The fields the owner typed, checked and joined; null if any doesn't fit. Pure, unit-tested. */
        fun secretOf(provider: Provider, values: List<String>): String? {
            if (values.size != provider.fields.size) return null
            val trimmed = values.map { it.trim() }
            if (provider.fields.zip(trimmed).any { (f, v) -> !f.pattern.matches(v) }) return null
            return trimmed.joinToString(":")
        }

        /** "••••abcd" for a secret. Pure. */
        fun masked(secret: String): String = "••••" + secret.takeLast(4)
    }
}
