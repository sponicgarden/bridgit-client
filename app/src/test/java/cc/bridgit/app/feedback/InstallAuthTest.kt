package cc.bridgit.app.feedback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/** Vectors shared with bridgit-cloud/feedback/test/install.test.mjs, so the phone and the backend agree byte for byte. */
class InstallAuthTest {
    @Test fun base32MatchesRfc4648Lowercase() {
        assertEquals("", InstallAuth.base32(ByteArray(0)))
        assertEquals("my", InstallAuth.base32("f".toByteArray()))
        assertEquals("mzxw6ytboi", InstallAuth.base32("foobar".toByteArray()))
    }

    @Test fun installIdIsPrefixPlus24Chars() {
        // SHA-256("abc") = ba7816bf8f01cfea414140de5dae2223b00361a3...
        val id = InstallAuth.installId("abc".toByteArray())
        assertEquals("bgi_xj4bnp4pahh6uqkbidpf3lrc", id)
        assertEquals(28, id.length)
    }

    @Test fun signingTextIsFixedLayout() {
        val text = InstallAuth.signingText("bgi_x", "post", "/v1/issues/updates?since=5", 1700000000000, "n-1", "{}".toByteArray())
        assertEquals(
            "bridgit-install-v1\nbgi_x\nPOST\n/v1/issues/updates?since=5\n1700000000000\nn-1\n" +
                "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a",
            text,
        )
    }

    @Test fun derSignatureConvertsToP1363AndStillVerifies() {
        val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        repeat(20) {
            val msg = "message $it".toByteArray()
            val der = Signature.getInstance("SHA256withECDSA").apply { initSign(kp.private); update(msg) }.sign()
            val p1363 = InstallAuth.derToP1363(der)
            assertEquals(64, p1363.size)
            val ok = Signature.getInstance("SHA256withECDSAinP1363Format").apply { initVerify(kp.public); update(msg) }.verify(p1363)
            assertEquals(true, ok)
        }
    }

    @Test fun shortIntegersArePaddedLeft() {
        // r = 1, s = 0x80 (needs a leading 0 in DER)
        val der = byteArrayOf(0x30, 7, 0x02, 1, 1, 0x02, 2, 0, 0x80.toByte())
        val out = InstallAuth.derToP1363(der)
        assertArrayEquals(ByteArray(31) + byteArrayOf(1) + ByteArray(31) + byteArrayOf(0x80.toByte()), out)
    }
}
