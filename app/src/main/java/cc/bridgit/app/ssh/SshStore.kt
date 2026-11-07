package cc.bridgit.app.ssh

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

class SshStore(context: Context) {
    class Conflict : Exception("SSH connection changed during verification")
    class Unavailable : Exception("SSH credential store unavailable; existing data preserved")
    private val prefs = context.getSharedPreferences("ssh_profiles", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey("bridgit_ssh_profiles", null) as? SecretKey)?.let { return it }
        if (prefs.contains("data")) throw Unavailable()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("bridgit_ssh_profiles", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun all(): List<SshProfile> {
        val data = prefs.getString("data", null) ?: return emptyList()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.getDecoder().decode(prefs.getString("iv", ""))))
        val a = JSONArray(String(cipher.doFinal(Base64.getDecoder().decode(data)), Charsets.UTF_8))
        return (0 until a.length()).map { SshProfile.read(a.getJSONObject(it)) }
    }
    @Synchronized fun saveVerified(d: SshProfile, previousRevision: String?) {
        val existing = all().firstOrNull { it.id == d.id }
        if (!(if (previousRevision == null) existing == null else existing?.revision == previousRevision)) throw Conflict()
        save(d)
    }
    @Synchronized fun save(d: SshProfile) {
        val current = all()
        require(current.size < 64 || current.any { it.id == d.id }) { "At most 64 SSH connections" }
        write(current.filter { it.id != d.id } + d)
    }
    @Synchronized fun remove(id: String) { write(all().filter { it.id != id }) }
    private fun write(devices: List<SshProfile>) {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        check(prefs.edit().putString("iv", Base64.getEncoder().encodeToString(c.iv))
            .putString("data", Base64.getEncoder().encodeToString(c.doFinal(JSONArray(devices.map { it.stored() }).toString().toByteArray()))).commit())
    }
}
