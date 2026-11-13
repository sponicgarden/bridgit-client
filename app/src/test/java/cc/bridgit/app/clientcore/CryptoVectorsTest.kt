package cc.bridgit.app.clientcore

import cc.bridgit.app.feedback.InstallAuth
import cc.bridgit.app.link.LinkCode
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Client Core crypto vectors (contracts/client-core/crypto/, copied here by tools/client-core/gen.mjs).
 * The same files run in the pack's own JS check and, in phase 2, in BridgitCore's Swift tests.
 */
class CryptoVectorsTest {
    private fun vectors(name: String) = JSONObject(javaClass.getResource("/client-core/crypto/$name.json")!!.readText())
    private fun b64(s: String) = Base64.getDecoder().decode(s)
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    private fun publicKey(key: JSONObject): PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(b64(key.getString("public_spki"))))
    private fun verifies(key: JSONObject, text: String, sig: String) =
        Signature.getInstance("SHA256withECDSAinP1363Format").apply { initVerify(publicKey(key)); update(text.toByteArray()) }.verify(b64(sig))

    @Test fun installIdentity() {
        val v = vectors("install-identity")
        for (c in v.getJSONArray("base32").objects()) assertEquals(c.getString("out"), InstallAuth.base32(c.getString("in_utf8").toByteArray()))
        for (c in v.getJSONArray("install_id").objects()) {
            val spki = if (c.has("spki_b64")) b64(c.getString("spki_b64")) else c.getString("spki_utf8").toByteArray()
            assertEquals(c.getString("out"), InstallAuth.installId(spki))
        }
        for (c in v.getJSONArray("signing_text").objects()) {
            val text = InstallAuth.signingText(c.getString("install_id"), c.getString("method"), c.getString("path_and_query"),
                c.getLong("at"), c.getString("nonce"), c.getString("body_utf8").toByteArray())
            assertEquals(c.getString("out"), text)
            if (c.has("signature_p1363")) {
                assertTrue(verifies(v.getJSONObject("key"), text, c.getString("signature_p1363")))
                assertEquals(c.getString("header"), InstallAuth.signatureHeader(c.getLong("at"), c.getString("nonce"), b64(c.getString("signature_p1363"))))
            }
        }
        for (c in v.getJSONArray("der_to_p1363").objects()) assertArrayEquals(hex(c.getString("p1363_hex")), InstallAuth.derToP1363(hex(c.getString("der_hex"))))
    }

    @Test fun relayProofs() {
        val v = vectors("relay-proofs")
        for (p in v.getJSONArray("proofs").objects()) {
            val time = if (p.has("at")) p.getLong("at") else p.getLong("until")
            // RelayStore signs exactly this layout (challengeProof, selfProof, sign).
            val text = "${p.getString("context")}\n${p.getString("device")}\n${p.getString("nonce")}\n$time"
            assertEquals(p.getString("text"), text)
            assertTrue(p.getString("purpose"), verifies(v.getJSONObject("key"), text, p.getString("signature_p1363")))
        }
    }

    @Test fun linkCodes() {
        val v = vectors("link-code")
        for (c in v.getJSONArray("normalise").objects()) {
            val input = if (c.isNull("in")) null else c.getString("in")
            val want = if (c.isNull("out")) null else c.getString("out")
            assertEquals(input.toString(), want, LinkCode.normalise(input))
        }
        for (d in v.getJSONArray("derive").objects()) {
            val keys = LinkCode.keys(d.getString("code"), d.getString("name"))
            assertArrayEquals(hex(d.getString("dk_hex")), keys.dk)
            assertEquals(d.getString("code_hash"), keys.codeHash)
            assertEquals(d.getString("claim_token"), keys.claimToken)
            assertEquals(d.getString("claim_token_sha256"), LinkCode.sha256(keys.claimToken))
            val proof = d.getJSONObject("proof")
            assertEquals(proof.getString("out"), keys.proof(d.getString("name"), proof.getString("claiming_install")))
        }
    }
}
