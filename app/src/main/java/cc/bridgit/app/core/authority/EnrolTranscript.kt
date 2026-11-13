package cc.bridgit.app.core.authority

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * The transcript of the approver enrolment handshake, `bridgit-approver-enrol-v1`
 * (docs/plans/client-core/architecture.md §4.7 step 2; spending authority G10.3 X1, X3).
 *
 * Each device builds its own from what it holds: its own keys, role, random value and authority generation,
 * the link id, and the other side's keys and random value as they arrived. Nothing here is trusted because
 * it arrived; the two transcripts are equal only if nothing was changed on the way, and [EnrolProofs] checks
 * that they are. The key exchange is named by [kexHash], a labelled hash of its public parts taken from the
 * finished [Spake2.Keys], never Hash(TT), which is the key itself.
 *
 * Bytes: the sixteen fields in the order below, each behind a 4-byte big-endian length. Text is UTF-8, the
 * generation is 8 bytes big-endian, an absent attestation is zero bytes long. Install and approver keys are
 * SPKI; message and sealing keys are whatever their owner publishes (a 65-byte point for HPKE on P-256).
 * The contract pack's crypto/spake2.json holds the vectors.
 */
class EnrolTranscript(
    val linkId: String,
    val makerInstallId: String,
    makerInstallKey: ByteArray,
    val claimerInstallId: String,
    claimerInstallKey: ByteArray,
    /** Which side becomes the approver. The other is the operating device. */
    val approver: Side,
    approverKey: ByteArray,
    approverSealingKey: ByteArray,
    approverAttestation: ByteArray,
    makerMessageKey: ByteArray,
    claimerMessageKey: ByteArray,
    /** The finished key exchange. Its identities must be the two install ids: the maker is side A. */
    kex: Spake2.Keys,
    /** The operating device's authority generation. */
    val generation: Long,
    makerNonce: ByteArray,
    claimerNonce: ByteArray,
) {
    enum class Side(val id: String) {
        MAKER("maker"), CLAIMER("claimer");

        val other get() = if (this == MAKER) CLAIMER else MAKER
        val spake get() = if (this == MAKER) Spake2.Side.A else Spake2.Side.B
    }

    private val makerInstallKey = makerInstallKey.copyOf()
    private val claimerInstallKey = claimerInstallKey.copyOf()
    private val approverKey = approverKey.copyOf()
    private val approverSealingKey = approverSealingKey.copyOf()
    private val approverAttestation = approverAttestation.copyOf()
    private val makerMessageKey = makerMessageKey.copyOf()
    private val claimerMessageKey = claimerMessageKey.copyOf()
    private val makerNonce = makerNonce.copyOf()
    private val claimerNonce = claimerNonce.copyOf()
    private val kexHash: ByteArray

    init {
        require(linkId.isNotEmpty() && makerInstallId.isNotEmpty() && claimerInstallId.isNotEmpty()) { "Empty id" }
        require(makerInstallId != claimerInstallId) { "Two devices: a device enrols itself without a key exchange" }
        require(listOf(makerInstallKey, claimerInstallKey, approverKey, approverSealingKey, makerMessageKey, claimerMessageKey).all { it.isNotEmpty() }) { "Empty key" }
        require(makerNonce.size == NONCE_BYTES && claimerNonce.size == NONCE_BYTES) { "Each side's random value is $NONCE_BYTES bytes" }
        require(generation >= 0) { "Negative generation" }
        require(kex.idA.contentEquals(utf8(makerInstallId)) && kex.idB.contentEquals(utf8(claimerInstallId))) { "The key exchange ran under other identities" }
        kexHash = kexHash(kex)
    }

    fun installId(side: Side) = if (side == Side.MAKER) makerInstallId else claimerInstallId
    fun installKey(side: Side): ByteArray = (if (side == Side.MAKER) makerInstallKey else claimerInstallKey).copyOf()
    fun approverKey(): ByteArray = approverKey.copyOf()
    internal fun kexHash(): ByteArray = kexHash.copyOf()

    fun bytes(): ByteArray = prefixed(
        utf8(LABEL), utf8(linkId), utf8(makerInstallId), makerInstallKey, utf8(claimerInstallId), claimerInstallKey, utf8(approver.id),
        approverKey, approverSealingKey, approverAttestation, makerMessageKey, claimerMessageKey, kexHash,
        ByteBuffer.allocate(8).putLong(generation).array(), makerNonce, claimerNonce,
    )

    /** SHA-256 of [bytes]: what the confirmations and signatures of [EnrolProofs] are made over. */
    fun hash(): ByteArray = Spake2.sha256(bytes())

    companion object {
        const val LABEL = "bridgit-approver-enrol-v1"
        const val NONCE_BYTES = 32
        internal const val KEX_LABEL = "$LABEL kex"

        internal fun kexHash(kex: Spake2.Keys): ByteArray = Spake2.sha256(prefixed(utf8(KEX_LABEL), kex.idA, kex.idB, kex.pA, kex.pB))

        internal fun utf8(s: String) = s.toByteArray(Charsets.UTF_8)

        /** Each part behind its length as a 4-byte big-endian number. */
        internal fun prefixed(vararg parts: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            for (p in parts) {
                out.write(ByteBuffer.allocate(4).putInt(p.size).array())
                out.write(p)
            }
            return out.toByteArray()
        }
    }
}
