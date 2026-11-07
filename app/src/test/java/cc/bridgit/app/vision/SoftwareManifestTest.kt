package cc.bridgit.app.vision

import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.security.SecureRandom
import java.util.Base64

class SoftwareManifestTest {
    // A throwaway key: the real signing key never touches the repo.
    private val keys = Ed25519KeyPairGenerator().apply { init(Ed25519KeyGenerationParameters(SecureRandom())) }.generateKeyPair()
    private val priv = keys.private as Ed25519PrivateKeyParameters
    private val pub = Base64.getEncoder().encodeToString((keys.public as Ed25519PublicKeyParameters).encoded)

    private fun file(path: String) = JSONObject().put("path", path).put("size", 10).put("sha256", "a".repeat(64))
    private fun item(id: String, engine: String, kind: String = "detector", pipeline: String = "florence2-ovd/1", version: String = "1") =
        JSONObject().put("id", id).put("engine", engine).put("kind", kind).put("pipeline", pipeline).put("runtime", "onnxruntime-1.22")
            .put("version", version).put("licence", "MIT").put("good_at", "things").put("files", JSONArray().put(file("$id/model.onnx")))
    private fun payload(serial: Long = 5, channel: String = "production", vararg items: JSONObject) = JSONObject().put("format", SoftwareManifest.FORMAT)
        .put("channel", channel).put("serial", serial).put("published_at", "2026-11-05T00:00:00Z")
        .put("items", JSONArray(items.toList().ifEmpty { listOf(item("florence-2-base-ft-int8", "on_device_detector")) }))

    private fun sign(p: JSONObject, tamper: Boolean = false): String {
        val bytes = p.toString().toByteArray()
        val s = Ed25519Signer(); s.init(true, priv); s.update(bytes, 0, bytes.size)
        val sig = s.generateSignature()
        val sent = if (tamper) p.toString().replace("\"serial\":5", "\"serial\":6").toByteArray() else bytes
        return JSONObject().put("format", SoftwareManifest.ENVELOPE_FORMAT).put("key_id", SoftwareManifest.KEY_ID)
            .put("payload", Base64.getEncoder().encodeToString(sent)).put("signature", Base64.getEncoder().encodeToString(sig)).toString()
    }

    private fun refused(text: String, contains: String, minSerial: Long = 0, channel: String = "production", key: String = pub) {
        try { SoftwareManifest.read(text, minSerial, channel, key); fail("expected refusal: $contains") }
        catch (e: SoftwareManifest.Bad) { assertTrue("${e.message} should mention $contains", e.message!!.contains(contains)) }
    }

    @Test fun readsASignedManifest() {
        val m = SoftwareManifest.read(sign(payload()), 0, "production", pub)
        assertEquals(5L, m.serial)
        val i = m.items.single()
        assertEquals("on_device_detector", i.engine); assertEquals("florence-2-base-ft-int8-1", i.folder)
        assertEquals("${SoftwareManifest.BASE}/florence-2-base-ft-int8/model.onnx", i.files.single().url)
    }

    @Test fun refusesTamperingOtherKeysReplaysAndOtherChannels() {
        refused(sign(payload(), tamper = true), "signature")
        val other = Base64.getEncoder().encodeToString((Ed25519KeyPairGenerator().apply { init(Ed25519KeyGenerationParameters(SecureRandom())) }.generateKeyPair().public as Ed25519PublicKeyParameters).encoded)
        refused(sign(payload()), "signature", key = other)
        refused(sign(payload(serial = 4)), "older", minSerial = 5)
        refused(sign(payload(channel = "test")), "channel")
        refused(JSONObject(sign(payload())).put("key_id", "someone-else").toString(), "unknown key")
        refused("x".repeat(300 * 1024), "larger")
    }

    @Test fun refusesUnsafeFilesAndBadFields() {
        for ((path, why) in listOf("../etc/passwd" to "path", "a/../b" to "path", "noslash" to "path", "https://evil.example/x" to "path", "Upper/x" to "path"))
            refused(sign(payload(items = arrayOf(item("m-one", "on_device_detector").put("files", JSONArray().put(file(path)))))), why)
        val badSha = item("m-one", "on_device_detector").put("files", JSONArray().put(file("m/x").put("sha256", "ZZ")))
        refused(sign(payload(items = arrayOf(badSha))), "sha256")
        refused(sign(payload(items = arrayOf(item("m-one", "Bad Engine")))), "engine")
        refused(sign(payload(items = arrayOf(item("m-one", "on_device_detector"), item("m-one", "on_device_outline")))), "unique")
    }

    @Test fun unsupportedItemsAreListedNotRun() {
        val m = SoftwareManifest.read(sign(payload(items = arrayOf(
            item("future-model", "on_device_detector", pipeline = "yolo-world/1"),
            item("needs-new-app", "on_device_detector").put("min_app_version_code", 99999),
            item("other-runtime", "on_device_detector").put("runtime", "litert-2"),
            item("florence-2-base-ft-int8", "on_device_detector")))), 0, "production", pub)
        assertEquals(4, m.items.size)
        assertNotNull(SoftwareManifest.unsupported(m.items[0], 2500))
        assertTrue(SoftwareManifest.unsupported(m.items[1], 2500)!!.contains("99999"))
        assertTrue(SoftwareManifest.unsupported(m.items[2], 2500)!!.contains("litert-2"))
        assertNull(SoftwareManifest.unsupported(m.items[3], 2500))
        assertEquals("florence-2-base-ft-int8", SoftwareManifest.active(m, "on_device_detector", 2500)!!.id)
    }

    @Test fun replacesAndEnabledPickTheActiveItem() {
        val m = SoftwareManifest.read(sign(payload(items = arrayOf(
            item("mobilesam", "on_device_outline", "outline", "sam-box/1", "1"),
            item("mobilesam-v2", "on_device_outline", "outline", "sam-box/1", "2").put("replaces", JSONArray().put("mobilesam")),
            item("off", "on_device_detector").put("enabled", false)))), 0, "production", pub)
        assertEquals("mobilesam-v2", SoftwareManifest.active(m, "on_device_outline", 1)!!.id)
        assertNull(SoftwareManifest.active(m, "on_device_detector", 1))
        assertEquals(listOf("on_device_outline", "on_device_detector"), SoftwareManifest.engines(m))
    }

    @Test fun bundledManifestIsValidAndMatchesTheSource() {
        val dir = listOf(File("src/main/assets/software"), File("app/src/main/assets/software")).first { it.isDirectory }
        val m = SoftwareManifest.read(File(dir, "manifest.json").readText())
        assertEquals("production", m.channel)
        for (e in listOf("on_device_detector", "on_device_outline")) assertNotNull(e, SoftwareManifest.active(m, e, 1_000_000))
        // The checked-in source the publish tool signs says the same as the shipped copy.
        val src = listOf(File("../tools/software/manifest.json"), File("tools/software/manifest.json")).first { it.isFile }
        val items = JSONObject(src.readText()).getJSONArray("items")
        assertEquals(m.items.map { it.id }, (0 until items.length()).map { items.getJSONObject(it).getString("id") })
    }
}
