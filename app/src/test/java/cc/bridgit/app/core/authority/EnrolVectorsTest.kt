package cc.bridgit.app.core.authority

import cc.bridgit.app.core.contract.ContractVocab
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.SecureRandom
import java.util.Base64

/**
 * Runs the enrolment part of contracts/client-core/crypto/spake2.json (copied here by tools/client-core/gen.mjs)
 * through [EnrolSecret], [EnrolTranscript] and [EnrolProofs]: the secret's text form, w, and every side of
 * every run. Each side is one device: it holds its secret, scalar and view, receives what the case says, and
 * must send what the case says it sends and reach the case's verdict. The vectors are made and proved in Node
 * (tools/client-core/crypto.mjs), not by this code.
 */
class EnrolVectorsTest {
    private val v = JSONObject(javaClass.getResource("/client-core/crypto/spake2.json")!!.readText())
    private val enrol = v.getJSONObject("enrol")
    private fun b64(s: String) = Base64.getDecoder().decode(s)
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun toHex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    private fun JSONArray.strings() = (0 until length()).map { getString(it) }
    private fun spki(name: String) = b64(v.getJSONObject("keys").getJSONObject(name).getString("public_spki"))
    private fun point(name: String) = spki(name).let { it.copyOfRange(it.size - 65, it.size) }
    private fun side(name: String) = EnrolTranscript.Side.entries.first { it.id == name }

    @Test fun labelsReasonsAndLimitsComeFromThePack() {
        val enc = enrol.getJSONObject("encoding")
        assertEquals(enrol.getString("label"), EnrolTranscript.LABEL)
        assertEquals(enc.getString("kex_label"), EnrolTranscript.KEX_LABEL)
        assertEquals(enc.getString("confirm_label"), EnrolProofs.CONFIRM_LABEL)
        assertEquals(enc.getString("proof_label"), EnrolProofs.PROOF_LABEL)
        assertEquals(enc.getString("approver_purpose"), EnrolProofs.APPROVER_PURPOSE)
        assertTrue(ContractVocab.OWNER_AUTH_PURPOSES.any { it.id == EnrolProofs.APPROVER_PURPOSE && !it.proposed })
        assertEquals(enc.getJSONArray("sides").strings(), EnrolTranscript.Side.entries.map { it.id })
        assertEquals(enrol.getJSONArray("reasons").strings(), EnrolProofs.Reason.entries.map { it.id })
        assertEquals(v.getJSONObject("secret").getInt("bytes"), EnrolSecret.BYTES)
        assertEquals(v.getJSONObject("password").getInt("iterations"), AuthorityLimits.ENROL_PBKDF2_ITERATIONS)
        assertEquals(v.getJSONObject("password").getString("label"), EnrolTranscript.LABEL)
    }

    @Test fun secretTextForm() {
        for (c in v.getJSONObject("secret").getJSONArray("parse").objects()) {
            val text = c.getString("in")
            val got = EnrolSecret.parse(text)
            if (c.isNull("out_hex")) {
                assertNull(text, got)
            } else {
                assertEquals(text, got!!.text())
                // The bytes are the ones the pack names: the same secret from them gives the same w.
                assertEquals(text, EnrolSecret.parse(Base64.getUrlEncoder().withoutPadding().encodeToString(hex(c.getString("out_hex"))))!!.w("n"), got.w("n"))
            }
        }
        assertNull(EnrolSecret.parse(null))
    }

    @Test fun aNewSecretIs128RandomBitsAndNeverPrinted() {
        val a = EnrolSecret.generate()
        val b = EnrolSecret.generate()
        assertEquals(EnrolSecret.TEXT_CHARS, a.text().length)
        assertNotEquals(a.text(), b.text())
        assertEquals(a.text(), EnrolSecret.parse(a.text())!!.text())
        assertEquals(16, Base64.getUrlDecoder().decode(a.text()).size)
        assertFalse(a.toString().contains(a.text()))
        // All 16 bytes come from the random source.
        val fixed = object : SecureRandom() { override fun nextBytes(bytes: ByteArray) { bytes.fill(0x5a) } }
        assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16) { 0x5a }), EnrolSecret.generate(fixed).text())
    }

    @Test fun passwordToW() {
        for (c in v.getJSONObject("password").getJSONArray("cases").objects()) {
            val w = EnrolSecret.parse(c.getString("es"))!!.w(c.getString("name"))
            assertEquals(c.getString("es") + " " + c.getString("name"), c.getString("w"), "%064x".format(w))
            assertTrue(w < Spake2.order)
        }
    }

    private class Device(val keys: Spake2.Keys, val transcript: EnrolTranscript)

    /** One side of a run, built only from what that device holds and the share it received. Null when the share is refused. */
    private fun device(mine: EnrolTranscript.Side, s: JSONObject): Pair<ByteArray, Device?> {
        val view = s.getJSONObject("view")
        val idA = view.getString("maker_install_id").toByteArray()
        val idB = view.getString("claimer_install_id").toByteArray()
        val w = EnrolSecret.parse(s.getString("es"))!!.w(s.getString("target_name"))
        val exchange = Spake2.start(mine.spake, w, idA, idB, BigInteger(s.getString("scalar_hex"), 16))
        val keys = exchange.finish(hex(s.getJSONObject("receives").getString("share_hex"))) ?: return exchange.share to null
        val transcript = EnrolTranscript(
            linkId = view.getString("link_id"),
            makerInstallId = view.getString("maker_install_id"), makerInstallKey = spki(view.getString("maker_install_key")),
            claimerInstallId = view.getString("claimer_install_id"), claimerInstallKey = spki(view.getString("claimer_install_key")),
            approver = side(view.getString("approver")), approverKey = spki(view.getString("approver_key")),
            approverSealingKey = point(view.getString("approver_sealing_key")), approverAttestation = b64(view.getString("approver_attestation_b64")),
            makerMessageKey = point(view.getString("maker_message_key")), claimerMessageKey = point(view.getString("claimer_message_key")),
            kex = keys, generation = view.getLong("generation"),
            makerNonce = hex(view.getString("maker_nonce_hex")), claimerNonce = hex(view.getString("claimer_nonce_hex")),
        )
        return exchange.share to Device(keys, transcript)
    }

    private fun received(r: JSONObject) = EnrolProofs.Received(
        hex(r.getString("key_confirmation_hex")), hex(r.getString("confirmation_hex")), b64(r.getString("install_signature_p1363")),
        r.optString("approver_signature_p1363").takeIf { it.isNotEmpty() }?.let { b64(it) },
    )

    private fun name(r: EnrolProofs.Result) = when (r) {
        is EnrolProofs.Result.Accepted -> "accepted"
        is EnrolProofs.Result.Refused -> r.reason.id
    }

    @Test fun everySideOfEveryRun() {
        val seen = mutableSetOf<String>()
        var transcriptsShown = 0
        for (run in enrol.getJSONArray("runs").objects()) {
            for (mine in EnrolTranscript.Side.entries) {
                val s = run.optJSONObject(mine.id) ?: continue
                val at = "${run.getString("name")} ${mine.id}"
                val sends = s.getJSONObject("sends")
                val (share, d) = device(mine, s)
                assertEquals("$at share", sends.getString("share_hex"), toHex(share))
                assertNotNull("$at: the share is refused", d)
                val t = d!!.transcript
                val hash = t.hash()
                // What this device sends, made here from its own view.
                assertEquals("$at key confirmation", sends.getString("key_confirmation_hex"), toHex(d.keys.confirmation))
                assertEquals("$at kex hash", s.getString("kex_hash_hex"), toHex(t.kexHash()))
                if (s.has("transcript_hex")) {
                    assertEquals("$at transcript", s.getString("transcript_hex"), toHex(t.bytes()))
                    transcriptsShown++
                }
                assertEquals("$at transcript hash", s.getString("transcript_hash_hex"), toHex(hash))
                assertEquals("$at confirmation", sends.getString("confirmation_hex"), toHex(EnrolProofs.confirmation(d.keys, mine, hash)))
                // Its signatures are over the bytes this code would hand to the key store.
                val signs = s.getJSONObject("signs")
                assertTrue("$at install signature", OwnerAuth.verifies(spki(signs.getString("install")), EnrolProofs.installProofBytes(mine, hash), b64(sends.getString("install_signature_p1363"))))
                if (sends.has("approver_signature_p1363") && !signs.has("approver_purpose")) {
                    assertTrue("$at approver signature", OwnerAuth.verifies(spki(signs.getString("approver")), EnrolProofs.approverProofBytes(t), b64(sends.getString("approver_signature_p1363"))))
                }
                // Its verdict on the other side.
                val got = name(EnrolProofs.verify(mine, t, d.keys, received(s.getJSONObject("receives"))))
                assertEquals(at, s.getString("expect"), got)
                seen += got
            }
        }
        assertTrue(transcriptsShown >= 2)
        // Every refusal a device can give after a good share is reached by some case, and so is acceptance.
        assertEquals(EnrolProofs.Reason.entries.map { it.id }.toSet() - "bad_share" + "accepted", seen)
    }

    @Test fun theTranscriptIsLengthPrefixedAndBoundToItsKeyExchange() {
        val run = enrol.getJSONArray("runs").objects().first { it.getString("name") == "ok_claimer_approver" }
        val s = run.getJSONObject("maker")
        val d = device(EnrolTranscript.Side.MAKER, s).second!!
        val bytes = d.transcript.bytes()
        // 16 fields; the first is the label behind its 4-byte length.
        assertEquals("00000019" + toHex(EnrolTranscript.LABEL.toByteArray()), toHex(bytes.copyOf(4 + 25)))
        var at = 0
        var fields = 0
        while (at < bytes.size) { at += 4 + java.nio.ByteBuffer.wrap(bytes, at, 4).int; fields++ }
        assertEquals(bytes.size, at)
        assertEquals(enrol.getJSONObject("encoding").getJSONArray("fields").length(), fields)
        // The kex hash is not Hash(TT), which is Ke || Ka.
        assertNotEquals(toHex(d.keys.hash), toHex(d.transcript.kexHash()))
        // A transcript cannot be built over a key exchange that ran under other identities, and a device is not its own peer here.
        val view = s.getJSONObject("view")
        fun build(maker: String, claimer: String, nonce: ByteArray = ByteArray(32)) = EnrolTranscript(
            "l", maker, ByteArray(1), claimer, ByteArray(1), EnrolTranscript.Side.CLAIMER, ByteArray(1), ByteArray(1), ByteArray(0), ByteArray(1), ByteArray(1),
            d.keys, 0, nonce, ByteArray(32),
        )
        assertNotNull(build(view.getString("maker_install_id"), view.getString("claimer_install_id")))
        assertThrows(IllegalArgumentException::class.java) { build(view.getString("claimer_install_id"), view.getString("maker_install_id")) }
        assertThrows(IllegalArgumentException::class.java) { build(view.getString("maker_install_id"), view.getString("maker_install_id")) }
        assertThrows(IllegalArgumentException::class.java) { build(view.getString("maker_install_id"), view.getString("claimer_install_id"), ByteArray(31)) }
        // The verifier is given its own side's key exchange, never the other's.
        val r = received(s.getJSONObject("receives"))
        assertThrows(IllegalArgumentException::class.java) { EnrolProofs.verify(EnrolTranscript.Side.CLAIMER, d.transcript, d.keys, r) }
    }

    @Test fun aProofSentBackOrDamagedIsRefused() {
        val run = enrol.getJSONArray("runs").objects().first { it.getString("name") == "ok_claimer_approver" }
        val s = run.getJSONObject("maker")
        val d = device(EnrolTranscript.Side.MAKER, s).second!!
        val good = s.getJSONObject("receives")
        val own = s.getJSONObject("sends")
        fun verdict(change: (JSONObject) -> Unit) = name(EnrolProofs.verify(EnrolTranscript.Side.MAKER, d.transcript, d.keys, received(JSONObject(good.toString()).also(change))))
        assertEquals("accepted", verdict { })
        // The maker's own values, reflected to it.
        assertEquals("key_confirmation_failed", verdict { it.put("key_confirmation_hex", own.getString("key_confirmation_hex")) })
        assertEquals("bad_confirmation", verdict { it.put("confirmation_hex", own.getString("confirmation_hex")) })
        assertEquals("bad_install_signature", verdict { it.put("install_signature_p1363", own.getString("install_signature_p1363")) })
        // The claimer's install signature is not its approver signature, and the other way round.
        assertEquals("bad_approver_signature", verdict { it.put("approver_signature_p1363", good.getString("install_signature_p1363")) })
        assertEquals("bad_install_signature", verdict { it.put("install_signature_p1363", good.getString("approver_signature_p1363")) })
        assertEquals("bad_confirmation", verdict { it.put("confirmation_hex", good.getString("confirmation_hex").dropLast(2)) })
        assertEquals("bad_approver_signature", verdict { it.remove("approver_signature_p1363") })
        // The approver's enrolment signature is expired as an owner authorization: it can never approve anything.
        val view = s.getJSONObject("view")
        val state = OwnerAuth.State(self = view.getString("maker_install_id"), nowMs = 0, approvers = listOf(
            OwnerAuth.Approver(view.getString("claimer_install_id"), spki(view.getString("approver_key")), spki(view.getString("claimer_install_key")), pastWait = true)))
        val record = OwnerAuth.Record(EnrolProofs.APPROVER_PURPOSE, d.transcript.hash(), view.getString("link_id"), view.getLong("generation"), 0)
        val answer = OwnerAuth.Answer(OwnerAuth.LABEL, EnrolProofs.APPROVER_PURPOSE, OwnerAuth.APPROVE, state.self, view.getString("claimer_install_id"), b64(good.getString("approver_signature_p1363")))
        assertEquals(OwnerAuth.Result.Refused(OwnerAuth.Reason.EXPIRED), OwnerAuth.verify(record, answer, state))
    }
}
