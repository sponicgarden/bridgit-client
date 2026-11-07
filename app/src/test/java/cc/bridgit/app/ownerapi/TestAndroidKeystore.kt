package cc.bridgit.app.ownerapi

import android.security.keystore.KeyGenParameterSpec
import java.io.InputStream
import java.io.OutputStream
import java.security.*
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyGenerator
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey

/** JVM substitute for Android's non-exportable key registry; ciphertext still uses real AES-GCM. */
object TestAndroidKeystore {
    private val keys = ConcurrentHashMap<String, SecretKey>()
    fun install() {
        keys.clear()
        if (Security.getProvider("AndroidKeyStore") == null) Security.addProvider(object : Provider("AndroidKeyStore", 1.0, "Unit-test key registry") {
            init {
                put("KeyStore.AndroidKeyStore", Store::class.java.name)
                put("KeyGenerator.AES", Generator::class.java.name)
            }
        })
    }
    fun remove() { Security.removeProvider("AndroidKeyStore"); keys.clear() }
    class Generator : KeyGeneratorSpi() {
        private var alias = ""
        override fun engineInit(random: SecureRandom?) = Unit
        override fun engineInit(size: Int, random: SecureRandom?) = Unit
        override fun engineInit(params: AlgorithmParameterSpec, random: SecureRandom?) { alias = (params as KeyGenParameterSpec).keystoreAlias }
        override fun engineGenerateKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { keys[alias] = it }
    }
    class Store : KeyStoreSpi() {
        override fun engineGetKey(alias: String, password: CharArray?): Key? = keys[alias]
        override fun engineContainsAlias(alias: String) = keys.containsKey(alias)
        override fun engineAliases() = Collections.enumeration(keys.keys)
        override fun engineSize() = keys.size
        override fun engineDeleteEntry(alias: String) { keys.remove(alias) }
        override fun engineIsKeyEntry(alias: String) = keys.containsKey(alias)
        override fun engineIsCertificateEntry(alias: String) = false
        override fun engineGetCertificate(alias: String): Certificate? = null
        override fun engineGetCertificateChain(alias: String): Array<Certificate>? = null
        override fun engineGetCertificateAlias(cert: Certificate): String? = null
        override fun engineGetCreationDate(alias: String) = Date(0)
        override fun engineSetCertificateEntry(alias: String, cert: Certificate) = Unit
        override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<Certificate>?) { keys[alias] = key as SecretKey }
        override fun engineSetKeyEntry(alias: String, key: ByteArray, chain: Array<Certificate>?) = Unit
        override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit
        override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
    }
}
