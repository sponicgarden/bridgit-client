package cc.bridgit.app.core.spending

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64

/** Contract data of the spending core (`contracts/client-core/limits.json` checks these values). Never a setting. */
object SpendingLimits {
    /**
     * The most a time anchor and the device's wall clock may differ for the anchor to count (G5.2 rule 2).
     * The design names the limit and gives no value; five minutes is this build's proposal to the owner.
     */
    const val ANCHOR_SKEW_MS = 5L * 60 * 1000
}

/** Signs with this install's key (the key the install id is the hash of). */
interface SpendSigner {
    val installId: String
    fun sign(bytes: ByteArray): ByteArray
}

/**
 * Whose signature this device takes on a rule document, a transfer or an increment (G6.1): its own install
 * key, an approver key from a verified enrolment, or the install key of another device of the account it
 * holds an `own`-tier link with whose services include configuration. Anything else is [Standing.UNKNOWN].
 * The cloud's key is not in the book: see [CloudAllowanceKey].
 */
interface SignerBook {
    enum class Standing { OWN, APPROVER, OWN_LINK, UNKNOWN }

    /** [kind] is `install` or `approver`. */
    fun standing(kind: String, id: String): Standing
    fun verify(kind: String, id: String, bytes: ByteArray, signature: ByteArray): Boolean

    /** Whether the account has an approver enrolled, as this device holds it (G6.2). */
    fun approverEnrolled(): Boolean
}

/**
 * The cloud's allowance key (Ed25519; G5.1): shipped in the app, rotated only by an app update. **There is
 * no production key yet, so the constant is empty and everything under the cloud's key is refused**: a cloud
 * slice, a cloud transfer, a cloud-signed rule document, a cloud-signed time (G10.2, the first build).
 */
object CloudAllowanceKey {
    val PUBLIC: ByteArray = ByteArray(0)

    fun verify(key: ByteArray, bytes: ByteArray, signature: ByteArray): Boolean {
        if (key.size != 32 || signature.size != 64) return false
        return runCatching { Ed25519Signer().run { init(false, Ed25519PublicKeyParameters(key, 0)); update(bytes, 0, bytes.size); verifySignature(signature) } }.getOrDefault(false)
    }
}

/** Something the owner or the team should hear about (G4.2). The core only names it; delivery is not built. */
data class SpendEvent(val name: String, val detail: Map<String, String> = emptyMap()) {
    companion object {
        const val RULE_FORK = "spend.rule_fork"
        const val TRANSFER_CONFLICT = "spend.transfer_conflict"
        const val OVERSHOOT = "spend.overshoot"

        /** A document from a signer this device does not take, or a transfer presented by an executor it does not name (§8). */
        const val SECURITY = "spend.security"
    }
}

/**
 * Who made a change (G6.1): who called, through which door, on which device, and the digest of the change.
 * It rides inside the signed document, so it is the signing device's word and no more: what an owner gate
 * needs beyond that is an approver's signature ([Gates]).
 */
data class ActorRecord(val kind: String, val id: String, val door: String, val install: String, val change: String) {
    val isOwner get() = kind == "owner"

    fun toMap(): Map<String, Any?> = mapOf("kind" to kind, "id" to id, "door" to door, "install" to install, "change" to change)

    companion object {
        val KINDS = setOf("owner", "agent", "applet", "portal", "cloud")

        fun of(m: Map<String, Any?>): ActorRecord {
            val a = ActorRecord(m.str("kind")!!, m.str("id")!!, m.str("door")!!, m.str("install")!!, m.str("change")!!)
            require(a.kind in KINDS) { "Unknown actor kind" }
            return a
        }

        /** The actor of [body], whose change digest is over the body without its actor and signature. */
        fun over(label: String, body: Map<String, Any?>, kind: String, id: String, door: String, install: String) =
            ActorRecord(kind, id, door, install, Signed.digest(label, body - "actor"))

        /** Whether [body]'s actor record names this very change. */
        fun matches(label: String, body: Map<String, Any?>): Boolean =
            runCatching { of(body.map("actor")!!).change == Signed.digest(label, body - "actor") }.getOrDefault(false)
    }
}

/** Who is acting, before there is a document to bind it to. */
data class Caller(val kind: String, val id: String, val door: String) {
    init { require(kind in ActorRecord.KINDS) { "Unknown actor kind" } }
}

/** The bytes that are signed and hashed: a label, a line feed, and the document as canonical JSON without its signature. */
internal object Signed {
    const val RULES = "bridgit-spend-rules-v1"
    const val TRANSFER = "bridgit-spend-transfer-v1"
    const val TRANSFER_ID = "bridgit-spend-transfer-id-v1"
    const val RECLAIM_ID = "bridgit-spend-reclaim-id-v1"
    const val INCREMENT = "bridgit-spend-increment-v1"
    const val INCREMENT_ID = "bridgit-spend-increment-id-v1"
    const val ALLOWANCE = "bridgit-spend-allowance-v1"
    const val ALLOWANCE_ID = "bridgit-spend-allowance-id-v1"
    const val HOLDINGS = "bridgit-spend-holdings-v1"

    fun bytes(label: String, body: Map<String, Any?>): ByteArray = "$label\n${Canonical.encode(body - "signature")}".toByteArray(Charsets.UTF_8)
    fun digest(label: String, body: Map<String, Any?>): String = Hex.of(MessageDigest.getInstance("SHA-256").digest(bytes(label, body)))

    /** SHA-256 over the label and each field as UTF-8 behind a 4-byte big-endian length: the framing of [SpendId]. */
    fun id(label: String, vararg fields: String): String {
        val out = ByteArrayOutputStream()
        for (text in listOf(label) + fields) {
            val b = text.toByteArray(Charsets.UTF_8)
            out.write(ByteBuffer.allocate(4).putInt(b.size).array())
            out.write(b)
        }
        return Hex.of(MessageDigest.getInstance("SHA-256").digest(out.toByteArray()))
    }

    fun encode(signature: ByteArray): String = Base64.getEncoder().encodeToString(signature)
    fun decode(signature: String?): ByteArray? = signature?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }

    /** [body] with [signer]'s signature over it. */
    fun sign(label: String, body: Map<String, Any?>, signer: SpendSigner): Map<String, Any?> = body + ("signature" to encode(signer.sign(bytes(label, body))))

    fun verified(label: String, body: Map<String, Any?>, book: SignerBook, kind: String, id: String): Boolean {
        val sig = decode(body.str("signature")) ?: return false
        return book.verify(kind, id, bytes(label, body), sig)
    }
}
