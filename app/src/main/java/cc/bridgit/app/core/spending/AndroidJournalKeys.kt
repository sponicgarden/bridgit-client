package cc.bridgit.app.core.spending

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * [JournalKeys] in the Android Keystore (spending authority G5.3 rule 1): one HMAC-SHA256 key per namespace
 * that cannot be exported and is deleted with the app's data.
 *
 * **The epoch is the key's alias**, not a value kept beside it: `bridgit.spend.journal.<namespace>.<epoch>`.
 * So an epoch exists exactly as long as its key does, and nothing can put an old epoch's name on a new key.
 * [create] removes every earlier key of the namespace before it makes the new one; if the app dies between
 * the two there is no key, which the journal reads as "not whole" and answers with a new epoch.
 *
 * There is no software fallback: where the key store cannot make or use the key, the call throws and the
 * journal does not open.
 */
class AndroidJournalKeys : JournalKeys {
    private val random = SecureRandom()

    private fun store(): KeyStore = KeyStore.getInstance(STORE).apply { load(null) }
    private fun prefix(namespace: Namespace) = "$PREFIX${namespace.id}."

    private fun aliases(store: KeyStore, namespace: Namespace): List<String> = store.aliases().toList().filter { it.startsWith(prefix(namespace)) }

    @Synchronized override fun epoch(namespace: Namespace): String? =
        aliases(store(), namespace).singleOrNull()?.removePrefix(prefix(namespace))?.takeIf { EPOCH.matches(it) }

    @Synchronized override fun create(namespace: Namespace): String {
        val store = store()
        for (alias in aliases(store, namespace)) store.deleteEntry(alias)
        val epoch = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, STORE).run {
            init(KeyGenParameterSpec.Builder(prefix(namespace) + epoch, KeyProperties.PURPOSE_SIGN).build())
            generateKey()
        }
        return epoch
    }

    @Synchronized override fun mac(namespace: Namespace, epoch: String, data: ByteArray): ByteArray? {
        if (!EPOCH.matches(epoch)) return null
        val key = store().getKey(prefix(namespace) + epoch, null) as? SecretKey ?: return null
        return Mac.getInstance("HmacSHA256").run { init(key); doFinal(data) }
    }

    private companion object {
        const val STORE = "AndroidKeyStore"
        const val PREFIX = "bridgit.spend.journal."
        val EPOCH = Regex("^[0-9a-f]{32}$")
    }
}
