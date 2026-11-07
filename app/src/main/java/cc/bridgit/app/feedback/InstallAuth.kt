package cc.bridgit.app.feedback

import java.math.BigInteger
import java.security.MessageDigest

/**
 * The pure parts of the install identity (docs/spec-client-identity.md), kept free of Android
 * so unit tests and the backend's tests can share vectors (bridgit-cloud/feedback/test/install.test.mjs).
 */
object InstallAuth {
    const val PREFIX = "bgi_"
    const val SIGNING_CONTEXT = "bridgit-install-v1"
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"

    /** CID-02: `bgi_` + lowercase base32 of the first 15 bytes of SHA-256(SubjectPublicKeyInfo DER). */
    fun installId(spki: ByteArray): String = PREFIX + base32(sha256(spki).copyOf(15))

    /** CID-20: the exact text the install key signs for one request. [pathAndQuery] includes `?since=…`. */
    fun signingText(installId: String, method: String, pathAndQuery: String, at: Long, nonce: String, body: ByteArray): String =
        listOf(SIGNING_CONTEXT, installId, method.uppercase(), pathAndQuery, at.toString(), nonce, hex(sha256(body))).joinToString("\n")

    fun signatureHeader(at: Long, nonce: String, sigP1363: ByteArray): String =
        "at=$at,nonce=$nonce,sig=${java.util.Base64.getEncoder().encodeToString(sigP1363)}"

    /** Android Keystore emits DER ECDSA signatures; WebCrypto verifies IEEE P1363 r||s. */
    fun derToP1363(der: ByteArray, size: Int = 32): ByteArray {
        require(der.size >= 8 && der[0] == 0x30.toByte()) { "not a DER signature" }
        var i = if (der[1].toInt() and 0x80 != 0) 2 + (der[1].toInt() and 0x7f) else 2
        val out = ByteArray(size * 2)
        for (part in 0..1) {
            require(der[i] == 0x02.toByte()) { "not a DER integer" }
            val len = der[i + 1].toInt() and 0xff
            val n = BigInteger(1, der.copyOfRange(i + 2, i + 2 + len)).toByteArray().let { if (it.size > size) it.copyOfRange(it.size - size, it.size) else it }
            n.copyInto(out, part * size + size - n.size)
            i += 2 + len
        }
        return out
    }

    fun base32(b: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0; var bits = 0
        for (byte in b) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xff); bits += 8
            while (bits >= 5) { sb.append(ALPHABET[(buffer shr (bits - 5)) and 31]); bits -= 5 }
        }
        if (bits > 0) sb.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return sb.toString()
    }

    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
}
