package cc.bridgit.app.core.authority

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

/**
 * Runs the SPAKE2 part of contracts/client-core/crypto/spake2.json (copied here by tools/client-core/gen.mjs)
 * through [Spake2]: the four RFC 9382 Appendix B vectors, each as side A and as side B, and every share that
 * must be refused. The RFC's values are read from its text by tools/client-core/spake2-vectors.mjs.
 */
class Spake2VectorsTest {
    private val v = JSONObject(javaClass.getResource("/client-core/crypto/spake2.json")!!.readText())
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun toHex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun num(s: String) = BigInteger(s, 16)
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

    @Test fun suiteAndConstantsAreTheRfcs() {
        assertEquals(v.getString("suite"), Spake2.SUITE)
        assertEquals(v.getJSONObject("constants").getString("M"), Spake2.M_HEX)
        assertEquals(v.getJSONObject("constants").getString("N"), Spake2.N_HEX)
    }

    @Test fun rfc9382AppendixB() {
        val cases = v.getJSONArray("rfc9382").objects()
        assertEquals(4, cases.size)
        for (c in cases) {
            val at = "A='${c.getString("A")}' B='${c.getString("B")}'"
            val idA = c.getString("A").toByteArray()
            val idB = c.getString("B").toByteArray()
            val a = Spake2.start(Spake2.Side.A, num(c.getString("w")), idA, idB, num(c.getString("x")))
            val b = Spake2.start(Spake2.Side.B, num(c.getString("w")), idA, idB, num(c.getString("y")))
            assertEquals("$at pA", c.getString("pA"), toHex(a.share))
            assertEquals("$at pB", c.getString("pB"), toHex(b.share))
            val ka = a.finish(b.share)!!
            val kb = b.finish(a.share)!!
            for ((who, k) in listOf("A" to ka, "B" to kb)) {
                assertEquals("$at K as $who", c.getString("K"), toHex(k.k))
                assertEquals("$at TT as $who", c.getString("TT"), toHex(k.tt))
                assertEquals("$at Hash(TT) as $who", c.getString("hash_TT"), toHex(k.hash))
                assertEquals("$at Ke as $who", c.getString("Ke"), toHex(k.ke))
                assertEquals("$at Ka as $who", c.getString("Ka"), toHex(k.ka))
                assertEquals("$at KcA as $who", c.getString("KcA"), toHex(k.kcA))
                assertEquals("$at KcB as $who", c.getString("KcB"), toHex(k.kcB))
            }
            assertEquals("$at A conf", c.getString("conf_A"), toHex(ka.confirmation))
            assertEquals("$at B conf", c.getString("conf_B"), toHex(kb.confirmation))
            assertTrue(at, ka.peerConfirms(hex(c.getString("conf_B"))) && kb.peerConfirms(hex(c.getString("conf_A"))))
            // A side's own confirmation, sent back to it, is not the other side's.
            assertFalse(at, ka.peerConfirms(ka.confirmation) || kb.peerConfirms(kb.confirmation))
            assertFalse(at, ka.peerConfirms(hex(c.getString("conf_B")).copyOf(31)) || ka.peerConfirms(ByteArray(0)))
        }
    }

    @Test fun badSharesAreRefused() {
        val cases = v.getJSONArray("bad_shares").objects()
        assertTrue(cases.any { it.getString("name") == "identity_k" } && cases.any { it.getString("name") == "off_curve" })
        for (c in cases) {
            val side = Spake2.Side.valueOf(c.getString("side"))
            fun receiver() = Spake2.start(side, num(c.getString("w")), ByteArray(0), ByteArray(0), num(c.getString("scalar")))
            assertNull(c.getString("name"), receiver().finish(hex(c.getString("share"))))
            // The same receiver accepts a good share, so the refusal is the share's doing.
            val other = if (side == Spake2.Side.A) Spake2.Side.B else Spake2.Side.A
            val good = Spake2.start(other, num(c.getString("w")), ByteArray(0), ByteArray(0), BigInteger.valueOf(7)).share
            assertNotNull(c.getString("name"), receiver().finish(good))
        }
    }

    @Test fun aBlindedLadderGivesTheSameMultipleWhateverTheBlind() {
        val random = java.security.SecureRandom()
        val g = Spake2.generator
        val top = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)
        val scalars = listOf(BigInteger.ZERO, BigInteger.ONE, BigInteger.TWO, Spake2.order.subtract(BigInteger.ONE), Spake2.order.shiftRight(1)) +
            v.getJSONArray("rfc9382").objects().flatMap { listOf(num(it.getString("w")), num(it.getString("x")), num(it.getString("y"))) } +
            List(20) { BigInteger(256, random).mod(Spake2.order) }
        for (k in scalars) {
            // Another base point each time: a multiple of G the default multiplier made.
            val base = g.multiply(BigInteger(200, random).add(BigInteger.ONE)).normalize()
            val want = base.multiply(k).normalize()
            val blinds = listOf(BigInteger.ZERO, BigInteger.ONE, top, BigInteger.ONE.shiftLeft(63)) + List(8) { BigInteger(64, random) }
            for (r in blinds) {
                val got = Spake2.blindedMultiply(base, k, r).normalize()
                assertTrue("k=${k.toString(16)} r=${r.toString(16)}", want.equals(got))
                assertEquals(k.signum() == 0, got.isInfinity)
            }
        }
        assertThrows(IllegalArgumentException::class.java) { Spake2.blindedMultiply(g, Spake2.order, BigInteger.ONE) }
        assertThrows(IllegalArgumentException::class.java) { Spake2.blindedMultiply(g, BigInteger.ONE, BigInteger.ONE.shiftLeft(64)) }
    }

    @Test fun anExchangeFinishesOnceAndDrawsAFreshScalar() {
        val c = v.getJSONArray("rfc9382").getJSONObject(0)
        val a = Spake2.start(Spake2.Side.A, num(c.getString("w")), ByteArray(0), ByteArray(0), num(c.getString("x")))
        assertNotNull(a.finish(hex(c.getString("pB"))))
        assertThrows(IllegalStateException::class.java) { a.finish(hex(c.getString("pB"))) }
        // A refused share spends the exchange too.
        val b = Spake2.start(Spake2.Side.A, num(c.getString("w")), ByteArray(0), ByteArray(0), num(c.getString("x")))
        assertNull(b.finish(ByteArray(65)))
        assertThrows(IllegalStateException::class.java) { b.finish(hex(c.getString("pB"))) }
        assertThrows(IllegalArgumentException::class.java) { Spake2.start(Spake2.Side.A, Spake2.order, ByteArray(0), ByteArray(0), BigInteger.ONE) }
        assertThrows(IllegalArgumentException::class.java) { Spake2.start(Spake2.Side.A, BigInteger.ONE, ByteArray(0), ByteArray(0), BigInteger.ZERO) }
        assertThrows(IllegalArgumentException::class.java) { Spake2.start(Spake2.Side.A, BigInteger.ONE, ByteArray(0), ByteArray(0), Spake2.order) }
        val secret = EnrolSecret.generate()
        val one = Spake2.start(Spake2.Side.A, secret, "name", "a".toByteArray(), "b".toByteArray())
        val two = Spake2.start(Spake2.Side.A, secret, "name", "a".toByteArray(), "b".toByteArray())
        assertNotEquals(toHex(one.share), toHex(two.share))
        // Two devices with the same secret and name agree; the secret appears in neither text form.
        val peer = Spake2.start(Spake2.Side.B, EnrolSecret.parse(secret.text())!!, "name", "a".toByteArray(), "b".toByteArray())
        val k1 = one.finish(peer.share)!!
        val k2 = peer.finish(one.share)!!
        assertEquals(toHex(k1.ke), toHex(k2.ke))
        assertTrue(k1.peerConfirms(k2.confirmation) && k2.peerConfirms(k1.confirmation))
        assertFalse(k1.toString().contains(toHex(k1.ke)))
    }
}
