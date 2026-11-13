package cc.bridgit.app.core.spending

import cc.bridgit.app.core.contract.ContractVocab
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * The accounting namespace (spending authority G10.3 X10). The instrument row fixes it: provider `sandbox`
 * is [SANDBOX], everything else is [LIVE]. No request chooses it, so there is no way to build one from
 * request text except [claimed], which exists only to compare a spend block's word with the source's.
 */
enum class Namespace(val id: String) {
    LIVE("live"), SANDBOX("sandbox");

    companion object {
        /** The namespace of an instrument: its row's, or live when it has none. */
        fun of(instrument: String): Namespace =
            byId(ContractVocab.INSTRUMENTS.firstOrNull { it.id == instrument }?.namespace ?: ContractVocab.SPEND_DEFAULT_NAMESPACE)!!

        /** What a spend block says, or null when it is not a namespace at all. */
        fun claimed(text: String): Namespace? = byId(text)

        private fun byId(id: String) = values().firstOrNull { it.id == id }
    }
}

/**
 * One spend id per request (spending authority G2.2): SHA-256 of the label, the namespace (X10), the install
 * id of the device that took the request, and the request id. Each is UTF-8 behind a 4-byte big-endian
 * length, the framing `bridgit-owner-auth-v1` uses, so no two different inputs give the same bytes. It goes
 * to the provider as its idempotency key and is the journal key.
 */
object SpendId {
    const val LABEL = "bridgit-spend-v1"

    fun of(namespace: Namespace, installId: String, requestId: String): String {
        require(installId.isNotEmpty() && requestId.isNotEmpty()) { "Empty field" }
        val out = ByteArrayOutputStream()
        for (text in listOf(LABEL, namespace.id, installId, requestId)) {
            val b = text.toByteArray(Charsets.UTF_8)
            out.write(ByteBuffer.allocate(4).putInt(b.size).array())
            out.write(b)
        }
        return Hex.of(MessageDigest.getInstance("SHA-256").digest(out.toByteArray()))
    }

    /** A spend id as this file makes them: 64 lower-case hex digits. */
    fun isValid(id: String) = id.length == 64 && id.all { it in '0'..'9' || it in 'a'..'f' }
}

internal object Hex {
    fun of(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    fun decode(hex: String): ByteArray {
        require(hex.length % 2 == 0 && hex.all { it in '0'..'9' || it in 'a'..'f' }) { "Not hex" }
        return ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
