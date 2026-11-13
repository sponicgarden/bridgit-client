package cc.bridgit.app.core.authority

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Runs the self-enrolment cases of contracts/client-core/crypto/spake2.json (`enrol.self`, copied here by
 * tools/client-core/gen.mjs) through [SelfEnrol]: the transcript bytes, what each key signs, and the
 * device's verdict on the signatures the case holds. The vectors are made and proved in Node.
 */
class SelfEnrolVectorsTest {
    private val v = JSONObject(javaClass.getResource("/client-core/crypto/spake2.json")!!.readText())
    private val self = v.getJSONObject("enrol").getJSONObject("self")
    private fun b64(s: String) = Base64.getDecoder().decode(s)
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun toHex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    private fun spki(name: String) = b64(v.getJSONObject("keys").getJSONObject(name).getString("public_spki"))
    private fun point(name: String) = spki(name).let { it.copyOfRange(it.size - 65, it.size) }

    private fun transcript(view: JSONObject) = SelfEnrol(
        installId = view.getString("install_id"), installKey = spki(view.getString("install_key")), approverKey = spki(view.getString("approver_key")),
        sealingKey = point(view.getString("approver_sealing_key")), attestation = b64(view.getString("approver_attestation_b64")),
        messageKey = point(view.getString("message_key")), generation = view.getLong("generation"), nonce = hex(view.getString("nonce_hex")),
    )

    @Test fun theSideIsThePacks() = assertEquals(self.getString("side"), SelfEnrol.SIDE)

    @Test fun everyCaseGivesThePacksBytesAndVerdict() {
        val cases = self.getJSONArray("cases").objects()
        assertTrue(cases.size >= 6)
        val verdicts = HashSet<String>()
        for (c in cases) {
            val name = c.getString("name")
            val t = transcript(c.getJSONObject("view"))
            if (c.has("transcript_hex")) assertEquals(name, c.getString("transcript_hex"), toHex(t.bytes()))
            assertEquals(name, c.getString("transcript_hash_hex"), toHex(t.hash()))
            assertEquals(name, c.getString("install_proof_hex"), toHex(t.installProofBytes()))
            assertEquals(name, c.getString("approver_proof_hex"), toHex(t.approverProofBytes()))
            assertEquals(name, c.getJSONObject("view").getString("nonce_hex"), t.nonceText())
            val verdict = when (val r = t.verify(b64(c.getString("install_signature_p1363")), c.optString("approver_signature_p1363").takeIf { it.isNotEmpty() }?.let(::b64))) {
                EnrolProofs.Result.Accepted -> "accepted"
                is EnrolProofs.Result.Refused -> r.reason.id
            }
            assertEquals(name, c.getString("expect"), verdict)
            verdicts += verdict
        }
        assertEquals(setOf("accepted", "install_id_mismatch", "bad_install_signature", "bad_approver_signature"), verdicts)
    }

    @Test fun theApproverSignatureEnrolsAndCanNeverBeALiveApproval() {
        val c = self.getJSONArray("cases").objects().first { it.getString("name") == "ok" }
        val view = c.getJSONObject("view")
        val t = transcript(view)
        val id = view.getString("install_id")
        // Presented as an answer to a request with the same digest and one-use id, it is expired: its expiry is 0.
        val record = OwnerAuth.Record(EnrolProofs.APPROVER_PURPOSE, t.hash(), t.nonceText(), t.generation, 0)
        val answer = OwnerAuth.Answer(OwnerAuth.LABEL, EnrolProofs.APPROVER_PURPOSE, OwnerAuth.APPROVE, id, id, b64(c.getString("approver_signature_p1363")))
        val state = OwnerAuth.State(id, listOf(OwnerAuth.Approver(id, t.approverKey(), t.installKey(), true)), nowMs = 1)
        assertEquals(OwnerAuth.Result.Refused(OwnerAuth.Reason.EXPIRED), OwnerAuth.verify(record, answer, state))
    }

    @Test fun theTwoFormsCannotBeConfused() {
        val view = self.getJSONArray("cases").getJSONObject(0).getJSONObject("view")
        val t = transcript(view)
        // The two-device transcript refuses equal install ids, so no self transcript is also a two-device one.
        val kex = Spake2.start(Spake2.Side.A, java.math.BigInteger.ONE, t.installId.toByteArray(), t.installId.toByteArray(), java.math.BigInteger.TEN)
        val peer = Spake2.start(Spake2.Side.B, java.math.BigInteger.ONE, t.installId.toByteArray(), t.installId.toByteArray(), java.math.BigInteger.valueOf(11))
        val keys = kex.finish(peer.share)!!
        assertThrows(IllegalArgumentException::class.java) {
            EnrolTranscript("lnk", t.installId, t.installKey(), t.installId, t.installKey(), EnrolTranscript.Side.MAKER, t.approverKey(), ByteArray(65), ByteArray(0),
                ByteArray(65), ByteArray(65), keys, 0, ByteArray(32), ByteArray(32))
        }
        assertThrows(IllegalArgumentException::class.java) { SelfEnrol(t.installId, t.installKey(), t.approverKey(), ByteArray(65), ByteArray(0), ByteArray(65), 0, ByteArray(31)) }
    }
}
