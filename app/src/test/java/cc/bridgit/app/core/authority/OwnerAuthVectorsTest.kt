package cc.bridgit.app.core.authority

import cc.bridgit.app.core.contract.ContractVocab
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Runs every vector of contracts/client-core/crypto/owner-auth.json (copied here by tools/client-core/gen.mjs)
 * through [OwnerAuth]. The vectors are made and proved in Node (tools/client-core/crypto.mjs), not by this code.
 */
class OwnerAuthVectorsTest {
    private val v = JSONObject(javaClass.getResource("/client-core/crypto/owner-auth.json")!!.readText())
    private fun b64(s: String) = Base64.getDecoder().decode(s)
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun toHex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    private fun JSONArray.strings() = (0 until length()).map { getString(it) }
    private fun spki(name: String) = b64((v.getJSONObject("keys").optJSONObject(name) ?: v.getJSONObject("bad_keys").getJSONObject(name)).getString("public_spki"))

    private fun record(o: JSONObject, purpose: String = o.getString("purpose")) =
        OwnerAuth.Record(purpose, hex(o.getString("digest_hex")), o.getString("nonce"), o.getLong("generation"), o.getLong("expiry"))

    private fun answer(o: JSONObject) = OwnerAuth.Answer(o.getString("label"), o.getString("purpose"), o.getString("decision"),
        o.getString("target"), o.getString("approver"), b64(o.getString("signature_p1363")))

    /** The top-level state, with a case's own fields replacing it. */
    private fun state(over: JSONObject?, now: Long, used: List<String> = emptyList()): OwnerAuth.State {
        val s = JSONObject(v.getJSONObject("state").toString())
        over?.keys()?.forEach { s.put(it, over.get(it)) }
        return OwnerAuth.State(
            self = s.getString("self"), nowMs = now,
            approvers = s.getJSONArray("approvers").objects().map {
                OwnerAuth.Approver(it.getString("id"), spki(it.getString("approver_key")), spki(it.getString("install_key")), it.getBoolean("past_wait"))
            },
            enabledProposed = s.getJSONArray("enabled_proposed").strings().toSet(), nonceUsed = { it in used },
        )
    }

    private fun name(r: OwnerAuth.Result) = when (r) {
        is OwnerAuth.Result.Approved -> "approved"
        is OwnerAuth.Result.Declined -> "declined"
        is OwnerAuth.Result.Refused -> "refused:${r.reason.id}"
    }

    @Test fun purposesAndLabelComeFromThePack() {
        val enc = v.getJSONObject("encoding")
        assertEquals(enc.getString("label"), OwnerAuth.LABEL)
        assertEquals(listOf(OwnerAuth.APPROVE, OwnerAuth.DECLINE), ContractVocab.OWNER_AUTH_DECISIONS)
        val matrix = v.getJSONObject("cross_purpose").getJSONObject("signatures")
        assertEquals(ContractVocab.OWNER_AUTH_PURPOSES.map { it.id }.toSet(), matrix.keys().asSequence().toSet())
        assertEquals(listOf("spend_setting"), ContractVocab.OWNER_AUTH_PURPOSES.filter { it.proposed }.map { it.id })
        assertFalse(OwnerAuth.isPurpose("approval"))
    }

    @Test fun canonicalBytes() {
        for (c in v.getJSONArray("bytes").objects()) {
            val f = c.getJSONObject("fields")
            val got = OwnerAuth.bytes(f.getString("purpose"), f.getString("decision"), f.getString("target"), f.getString("approver"),
                hex(f.getString("digest_hex")), f.getString("nonce"), f.getLong("generation"), f.getLong("expiry"))
            assertEquals(c.getString("name"), c.getString("out_hex"), toHex(got))
        }
    }

    @Test fun bytesRefuseWhatTheContractDoesNotName() {
        val d = ByteArray(32)
        assertThrows(IllegalArgumentException::class.java) { OwnerAuth.bytes("refund", "approve", "t", "a", d, "n", 1, 1) }
        assertThrows(IllegalArgumentException::class.java) { OwnerAuth.bytes("payment", "maybe", "t", "a", d, "n", 1, 1) }
        assertThrows(IllegalArgumentException::class.java) { OwnerAuth.bytes("payment", "approve", "t", "a", ByteArray(31), "n", 1, 1) }
        assertThrows(IllegalArgumentException::class.java) { OwnerAuth.bytes("payment", "approve", "t", "a", d, "", 1, 1) }
        assertThrows(IllegalArgumentException::class.java) { OwnerAuth.bytes("payment", "approve", "t", "a", d, "n", -1, 1) }
    }

    @Test fun everyCase() {
        val seen = mutableSetOf<String>()
        for (c in v.getJSONArray("cases").objects()) {
            val used = c.optJSONArray("used_nonces")?.strings() ?: emptyList()
            val got = name(OwnerAuth.verify(record(c.getJSONObject("record")), answer(c.getJSONObject("answer")), state(c.optJSONObject("state"), c.getLong("now"), used)))
            val want = if (c.getString("expect") == "refused") "refused:${c.getString("reason")}" else c.getString("expect")
            assertEquals(c.getString("name"), want, got)
            seen += want
        }
        // Every refusal reason the verifier has is proved by a case, and both good outcomes are.
        assertEquals(OwnerAuth.Reason.values().map { "refused:${it.id}" }.toSet() + setOf("approved", "declined"), seen)
    }

    @Test fun damagedSignaturesAndMalformedKeysAreRefusedBeforeTheProvider() {
        val damaged = v.getJSONArray("cases").objects().filter { it.getJSONObject("signed").has("damaged") }
        assertTrue(damaged.size >= 7)
        for (c in damaged) {
            val sig = b64(c.getJSONObject("answer").getString("signature_p1363"))
            assertFalse(c.getString("name"), OwnerAuth.validScalars(sig))
            assertTrue(c.getString("name"), OwnerAuth.validScalars(b64(c.getJSONObject("signed").getString("signature_p1363"))))
        }
        val factory = java.security.KeyFactory.getInstance("EC")
        for (name in v.getJSONObject("bad_keys").keys()) {
            // A provider may refuse to parse it at all; if it parses, our own check must refuse it.
            val key = runCatching { factory.generatePublic(java.security.spec.X509EncodedKeySpec(spki(name))) as java.security.interfaces.ECPublicKey }.getOrNull()
            if (key != null) assertFalse(name, OwnerAuth.validKey(key))
            assertFalse(name, OwnerAuth.verifies(spki(name), ByteArray(1), ByteArray(64) { 1 }))
        }
        for (name in v.getJSONObject("keys").keys()) {
            assertTrue(name, OwnerAuth.validKey(factory.generatePublic(java.security.spec.X509EncodedKeySpec(spki(name))) as java.security.interfaces.ECPublicKey))
        }
    }

    @Test fun theDevicesCurrentGenerationIsNotRead() {
        val c = v.getJSONArray("cases").objects().first { it.getString("name") == "generation_rose_since_request" }
        assertTrue(c.getJSONObject("state").getLong("generation") > c.getJSONObject("record").getLong("generation"))
        assertEquals("approved", c.getString("expect"))
        assertFalse(OwnerAuth.Reason.values().any { it.id == "stale_generation" })
    }

    @Test fun everyCrossPurposePair() {
        val m = v.getJSONObject("cross_purpose")
        val base = m.getJSONObject("record")
        val st = state(m.optJSONObject("state"), m.getLong("now"))
        val ids = ContractVocab.OWNER_AUTH_PURPOSES.map { it.id }
        var refused = 0
        for (signed in ids) for (presented in ids) {
            val answer = OwnerAuth.Answer(OwnerAuth.LABEL, presented, OwnerAuth.APPROVE, st.self, m.getString("approver"), b64(m.getJSONObject("signatures").getString(signed)))
            val got = name(OwnerAuth.verify(record(base, presented), answer, st))
            assertEquals("$signed presented for $presented", if (signed == presented) "approved" else "refused:bad_signature", got)
            if (signed != presented) refused++
        }
        assertEquals(ids.size * (ids.size - 1), refused)
    }

    @Test fun signatureHelperTakesOnlyP256AndSixtyFourBytes() {
        val c = v.getJSONArray("cases").objects().first { it.getString("name") == "approve" }
        val f = c.getJSONObject("signed").getJSONObject("fields")
        val bytes = hex(v.getJSONArray("bytes").getJSONObject(0).getString("out_hex"))
        assertEquals(toHex(bytes), toHex(OwnerAuth.bytes(f.getString("purpose"), f.getString("decision"), f.getString("target"), f.getString("approver"),
            hex(f.getString("digest_hex")), f.getString("nonce"), f.getLong("generation"), f.getLong("expiry"))))
        val sig = b64(c.getJSONObject("answer").getString("signature_p1363"))
        assertTrue(OwnerAuth.verifies(spki("approver"), bytes, sig))
        assertFalse(OwnerAuth.verifies(spki("approver"), bytes, sig.copyOf(63)))
        assertFalse(OwnerAuth.verifies(spki("approver"), bytes, ByteArray(64)))
        assertFalse(OwnerAuth.verifies(ByteArray(10), bytes, sig))
        assertFalse(OwnerAuth.verifies(spki("approver"), bytes + 0, sig))
        // A P-384 key is not an approver key.
        val p384 = java.security.KeyPairGenerator.getInstance("EC").apply { initialize(java.security.spec.ECGenParameterSpec("secp384r1")) }.generateKeyPair()
        assertFalse(OwnerAuth.verifies(p384.public.encoded, bytes, sig))
    }
}
